package com.cutviet.editor.media.dubbing

import kotlin.math.abs

/**
 * Fits synthesized speech into the time slot of the sentence it replaces, as a closed loop:
 * estimate a rate, synthesize, measure the real WAV, correct the rate and try again.
 *
 * Speech length is close to inversely proportional to the TTS rate, so each correction scales the
 * rate by measured/slot. The rate stays in [MIN_RATE]..[MAX_RATE]; beyond that the voice distorts.
 */
object AudioFitter {
    const val MIN_RATE = 0.85f
    const val MAX_RATE = 1.15f
    const val TOLERANCE_MS = 200L
    const val MAX_ATTEMPTS = 3

    data class Attempt(val rate: Float, val durationMs: Long)

    data class Fit(val rate: Float, val durationMs: Long, val attempts: Int, val withinTolerance: Boolean)

    /** First rate to try from an instant estimate of the length at rate 1.0. */
    fun initialRate(estimateAtOneMs: Long, slotMs: Long): Float =
        if (estimateAtOneMs <= 0 || slotMs <= 0) 1f else clamp(estimateAtOneMs.toFloat() / slotMs)

    /** Next rate after an attempt, or null when no change can bring it closer. */
    fun nextRate(last: Attempt, slotMs: Long): Float? {
        if (slotMs <= 0 || last.durationMs <= 0) return null
        if (abs(last.durationMs - slotMs) <= TOLERANCE_MS) return null
        val next = clamp(last.rate * last.durationMs / slotMs)
        return if (abs(next - last.rate) < 0.005f) null else next
    }

    /**
     * Runs the loop. [synthesize] renders the text at a rate and returns the measured length in ms,
     * or null on failure. Returns the attempt closest to the slot, or null if nothing was rendered.
     */
    suspend fun fit(estimateAtOneMs: Long, slotMs: Long, synthesize: suspend (Float) -> Long?): Fit? {
        val attempts = mutableListOf<Attempt>()
        var rate: Float? = initialRate(estimateAtOneMs, slotMs)
        while (rate != null && attempts.size < MAX_ATTEMPTS) {
            val ms = synthesize(rate) ?: break
            val a = Attempt(rate, ms)
            attempts += a
            rate = nextRate(a, slotMs)
        }
        val best = attempts.minByOrNull { abs(it.durationMs - slotMs) } ?: return null
        return Fit(best.rate, best.durationMs, attempts.size, abs(best.durationMs - slotMs) <= TOLERANCE_MS)
    }

    private fun clamp(r: Float) = r.coerceIn(MIN_RATE, MAX_RATE)
}
