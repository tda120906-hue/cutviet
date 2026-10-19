package com.cutviet.editor.media.dubbing

import kotlin.math.abs
import kotlin.math.max

/**
 * Fits synthesized speech into the time slot of the sentence it replaces, as a closed loop:
 * estimate a rate, synthesize, measure the real WAV, correct the rate and try again.
 *
 * A line may run past its own slot into the silence before the next sentence ([maxMs]), as dubbing
 * does; only a line that would run into the next one, or end far too early, gets its rate changed.
 * Speech length is close to inversely proportional to the TTS rate, so each correction scales the
 * rate by measured/target. The rate stays in [MIN_RATE]..[MAX_RATE]; beyond that the voice distorts.
 */
object AudioFitter {
    const val MIN_RATE = 0.85f
    const val MAX_RATE = 1.15f
    const val TOLERANCE_MS = 200L
    const val MAX_ATTEMPTS = 3

    data class Attempt(val rate: Float, val durationMs: Long)

    data class Fit(val rate: Float, val durationMs: Long, val attempts: Int, val withinTolerance: Boolean)

    /** Longest acceptable length: the room until the next line, and never less than slot + tolerance. */
    fun upper(slotMs: Long, maxMs: Long): Long = max(slotMs + TOLERANCE_MS, maxMs)

    fun fits(durationMs: Long, slotMs: Long, maxMs: Long = slotMs): Boolean =
        durationMs >= slotMs - TOLERANCE_MS && durationMs <= upper(slotMs, maxMs)

    /** Length to aim for when [durationMs] is outside the acceptable range. */
    private fun aim(durationMs: Long, slotMs: Long, maxMs: Long): Long =
        if (durationMs > upper(slotMs, maxMs)) max(slotMs, upper(slotMs, maxMs) - TOLERANCE_MS / 2) else slotMs

    /** First rate to try from an instant estimate of the length at rate 1.0. */
    fun initialRate(estimateAtOneMs: Long, slotMs: Long, maxMs: Long = slotMs): Float = when {
        estimateAtOneMs <= 0 || slotMs <= 0 -> 1f
        fits(estimateAtOneMs, slotMs, maxMs) -> 1f
        else -> clamp(estimateAtOneMs.toFloat() / aim(estimateAtOneMs, slotMs, maxMs))
    }

    /** Next rate after an attempt, or null when no change can bring it closer. */
    fun nextRate(last: Attempt, slotMs: Long, maxMs: Long = slotMs): Float? {
        if (slotMs <= 0 || last.durationMs <= 0) return null
        if (fits(last.durationMs, slotMs, maxMs)) return null
        val next = clamp(last.rate * last.durationMs / aim(last.durationMs, slotMs, maxMs))
        return if (abs(next - last.rate) < 0.005f) null else next
    }

    /**
     * Runs the loop. [synthesize] renders the text at a rate and returns the measured length in ms,
     * or null on failure. Returns the best attempt (one that fits, with the most natural rate), or
     * null if nothing was rendered.
     */
    suspend fun fit(estimateAtOneMs: Long, slotMs: Long, maxMs: Long = slotMs, synthesize: suspend (Float) -> Long?): Fit? {
        val attempts = mutableListOf<Attempt>()
        var rate: Float? = initialRate(estimateAtOneMs, slotMs, maxMs)
        while (rate != null && attempts.size < MAX_ATTEMPTS) {
            val ms = synthesize(rate) ?: break
            val a = Attempt(rate, ms)
            attempts += a
            rate = nextRate(a, slotMs, maxMs)
        }
        val good = attempts.filter { fits(it.durationMs, slotMs, maxMs) }
        val best = good.minByOrNull { abs(it.rate - 1f) }
            ?: attempts.minByOrNull { miss(it.durationMs, slotMs, maxMs) }
            ?: return null
        return Fit(best.rate, best.durationMs, attempts.size, fits(best.durationMs, slotMs, maxMs))
    }

    /** How far a length is outside the acceptable range. */
    fun miss(durationMs: Long, slotMs: Long, maxMs: Long = slotMs): Long = when {
        durationMs > upper(slotMs, maxMs) -> durationMs - upper(slotMs, maxMs)
        durationMs < slotMs - TOLERANCE_MS -> slotMs - TOLERANCE_MS - durationMs
        else -> 0
    }

    private fun clamp(r: Float) = r.coerceIn(MIN_RATE, MAX_RATE)
}
