package com.cutviet.editor.ui

import java.util.Locale

fun formatTime(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0) / 1000)
    val m = totalSec / 60
    val s = totalSec % 60
    val tenth = (ms.coerceAtLeast(0) % 1000) / 100
    return if (m >= 60) String.format(Locale.US, "%d:%02d:%02d", m / 60, m % 60, s)
    else String.format(Locale.US, "%02d:%02d.%d", m, s, tenth)
}

fun formatShort(ms: Long): String {
    val totalSec = ((ms.coerceAtLeast(0) + 500) / 1000)
    return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%d KB", b / 1024)
}

fun formatBitrate(bps: Int): String = String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)

fun formatSpeed(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}x" else String.format(Locale.US, "%.2fx", s).replace(Regex("0+x$"), "x")
