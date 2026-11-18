package com.cutviet.editor.media

import android.content.Context

/**
 * How fast this phone exports, learned from finished exports, to tell the user beforehand how long
 * an export will take. Speed is kept as output pixels × video ms per ms of real time, so one
 * measurement also covers other resolutions roughly.
 */
object ExportSpeed {
    private const val PREFS = "export_speed"
    private const val KEY_RATE = "rate"
    /** Guess before the first export: 1080p at about 4× real time (a recent flagship phone). */
    private const val DEFAULT_RATE = 1920.0 * 1080 * 4

    fun rate(context: Context): Double =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_RATE, 0f).toDouble().takeIf { it > 0 } ?: DEFAULT_RATE

    fun isMeasured(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_RATE, 0f) > 0f

    /** Estimated real time to export [mediaMs] of video at [width]×[height]. */
    fun estimateMs(context: Context, width: Int, height: Int, mediaMs: Long): Long = estimateMs(rate(context), width, height, mediaMs)

    fun estimateMs(rate: Double, width: Int, height: Int, mediaMs: Long): Long =
        (width.toDouble() * height * mediaMs / rate).toLong()

    /** Learns from a finished export (short ones are mostly start-up time and are skipped). */
    fun record(context: Context, width: Int, height: Int, mediaMs: Long, wallMs: Long) {
        if (mediaMs < 20_000 || wallMs < 2_000) return
        val measured = width.toDouble() * height * mediaMs / wallMs
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getFloat(KEY_RATE, 0f).toDouble()
        val next = if (old > 0) old * 0.5 + measured * 0.5 else measured
        prefs.edit().putFloat(KEY_RATE, next.toFloat()).apply()
    }
}
