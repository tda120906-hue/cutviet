package com.cutviet.editor.media.dubbing

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dubbing log kept on the phone (filesDir/dub/log.txt) as well as in logcat, so the owner can copy
 * it from the app without a computer. It also records why the app died last time (Android's
 * process exit reasons) and uncaught crashes, which logcat alone loses once the app restarts.
 */
object DubLog {
    private const val MAX_BYTES = 96 * 1024
    private const val PREFS = "dub_log"
    private var file: File? = null
    private val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)

    /** Call before logging; cheap to call again. */
    fun init(context: Context) {
        if (file != null) return
        synchronized(this) {
            if (file != null) return
            file = File(context.applicationContext.filesDir, "dub/log.txt").apply { parentFile?.mkdirs() }
            installCrashHandler()
        }
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append("I", tag, msg)
    }

    fun w(tag: String, msg: String, e: Throwable? = null) {
        Log.w(tag, msg, e)
        append("W", tag, if (e == null) msg else "$msg: ${e.javaClass.simpleName}: ${e.message}")
    }

    fun read(context: Context): String {
        init(context)
        return runCatching { file?.readText() }.getOrNull().orEmpty()
    }

    fun clear(context: Context) {
        init(context)
        synchronized(this) { file?.delete() }
    }

    @Synchronized
    private fun append(level: String, tag: String, msg: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {
                // Keep the newer half.
                val text = f.readText()
                f.writeText(text.substring(text.length / 2).substringAfter('\n'))
            }
            f.appendText("${time.format(Date())} $level $tag: $msg\n")
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            append("E", "crash", "thread ${t.name}: ${Log.getStackTraceString(e).lines().take(25).joinToString("\n  ")}")
            previous?.uncaughtException(t, e)
        }
    }

    /**
     * Writes the reasons Android gives for the app's recent deaths that are not yet in the log:
     * native crash, out of memory, killed for heat or resource use, and so on.
     */
    fun recordExitReasons(context: Context) {
        init(context)
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong("last_exit", 0)
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val infos = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 8) }.getOrNull() ?: return
        val fresh = infos.filter { it.timestamp > seen }.sortedBy { it.timestamp }
        for (e in fresh) {
            if (e.reason == ApplicationExitInfo.REASON_EXIT_SELF) continue
            val mb = 1024
            append(
                "E", "exit",
                "app dừng lúc ${time.format(Date(e.timestamp))}: ${reasonName(e.reason)} (${e.reason}), " +
                    "${e.description ?: "không có mô tả"}, pss=${e.pss / mb} MB, rss=${e.rss / mb} MB",
            )
            trace(e)?.let { append("E", "exit", it) }
        }
        fresh.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("last_exit", it).apply() }
    }

    /** The readable part of a native crash's tombstone or an ANR's stack dump, kept short. */
    @RequiresApi(30)
    private fun trace(e: ApplicationExitInfo): String? {
        if (e.reason != ApplicationExitInfo.REASON_CRASH_NATIVE && e.reason != ApplicationExitInfo.REASON_ANR) return null
        val bytes = runCatching {
            e.traceInputStream?.use { s ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (buf.size() < 256 * 1024) {
                    val n = s.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
        }.getOrNull() ?: return null
        // Tombstones are protobuf; their strings (signal, abort message, library and function
        // names) are enough to tell which part of the app crashed.
        val strings = Regex("[\\x20-\\x7E]{6,}").findAll(String(bytes, Charsets.ISO_8859_1)).map { it.value.trim() }
            .filter { s -> s.contains(".so") || s.contains("Abort", true) || s.contains("SIG") || s.contains("Exception") || s.startsWith("\"") || s.contains("at ") }
            .distinct()
            .take(30)
            .toList()
        return if (strings.isEmpty()) null else strings.joinToString("\n  ", prefix = "chi tiết:\n  ")
    }

    @RequiresApi(30)
    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_SIGNALED -> "bị hệ thống dừng (signal)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "hết RAM"
        ApplicationExitInfo.REASON_CRASH -> "lỗi Java"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "lỗi thư viện native"
        ApplicationExitInfo.REASON_ANR -> "không phản hồi (ANR)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "lỗi khởi động"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "đổi quyền"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "dùng quá nhiều tài nguyên"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "người dùng đóng"
        ApplicationExitInfo.REASON_USER_STOPPED -> "người dùng buộc dừng"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "tiến trình phụ thuộc bị dừng"
        ApplicationExitInfo.REASON_OTHER -> "lý do khác của hệ thống"
        else -> "mã $r"
    }

    /** One line about the phone's memory, for the log. */
    fun memory(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java) ?: return ""
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val mb = 1024 * 1024
        return "RAM trống ${mi.availMem / mb}/${mi.totalMem / mb} MB${if (mi.lowMemory) " (thấp)" else ""}"
    }
}
