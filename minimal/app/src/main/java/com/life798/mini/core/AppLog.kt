package com.life798.mini.core

import android.content.Context
import android.util.Log
import com.life798.mini.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 应用内日志。
 *
 * 目的很单纯：出问题时能直接把现场交出去，不用连 adb、不用抓 logcat。
 *
 * - 内存环形缓冲 500 条，界面即时可见
 * - 同时落盘到 filesDir/logs/app.log，超过 512KB 自动轮转
 * - 捕获未处理异常，崩溃栈也会写进去
 * - 内置脱敏工具，token / 手机号不落明文
 *
 * 不引入任何第三方日志库，也不上传任何东西。
 */
object AppLog {

    private const val TAG = "AppLog"
    private const val MAX_LINES = 500
    private const val MAX_BYTES = 512 * 1024L
    private const val KEEP_ON_ROTATE = 1000

    private val lines = ArrayDeque<String>()
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "app-log").apply { isDaemon = true }
    }

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile != null) return

        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val file = File(dir, "app.log")
        logFile = file

        // 把上次运行的尾巴带回来，冷启动也能看到崩溃前的记录
        runCatching {
            if (file.exists()) {
                file.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) }
            }
        }

        installCrashHandler()
        i("App", "启动 v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }

    // ---------- 写入 ----------

    fun d(tag: String, msg: String) = write("D", tag, msg, null)
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val line = buildString {
            append(fmt.format(Date()))
            append(' ').append(level).append('/').append(tag).append(": ").append(msg)
            if (t != null) append('\n').append(t.stackTraceToString().take(3000))
        }

        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }

        // 同步打一份到 logcat，接 adb 时也能看
        when (level) {
            "E" -> Log.e(tag, msg, t)
            "W" -> Log.w(tag, msg, t)
            "D" -> Log.d(tag, msg)
            else -> Log.i(tag, msg)
        }

        io.execute { appendToFile(line) }
    }

    private fun appendToFile(line: String) {
        val f = logFile ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {
                // 轮转：保留最后若干行，丢弃更早的
                val keep = f.readLines().takeLast(KEEP_ON_ROTATE)
                f.writeText(keep.joinToString("\n") + "\n")
            }
            f.appendText(line + "\n")
        }
    }

    // ---------- 读取 / 清理 ----------

    fun dump(): String = synchronized(lock) {
        if (lines.isEmpty()) "(暂无日志)" else lines.joinToString("\n")
    }

    fun size(): Int = synchronized(lock) { lines.size }

    fun clear() {
        synchronized(lock) { lines.clear() }
        runCatching { logFile?.writeText("") }
        i("App", "日志已清空")
    }

    // ---------- 崩溃捕获 ----------

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val detail = buildString {
                append("未捕获异常 @ ").append(thread.name)
                append('\n').append(throwable.stackTraceToString().take(6000))
            }
            synchronized(lock) {
                lines.addLast("${fmt.format(Date())} E/Crash: $detail")
            }
            // 崩溃必须同步落盘，异步可能来不及
            appendToFile("${fmt.format(Date())} E/Crash: $detail")
            Log.e(TAG, "uncaught", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    // ---------- 脱敏 ----------

    /** token 只保留首 6 位与尾 4 位。 */
    fun mask(token: String?): String {
        if (token.isNullOrBlank()) return "(空)"
        return if (token.length <= 12) "***" else "${token.take(6)}…${token.takeLast(4)}"
    }

    /** 手机号中间四位打码。 */
    fun maskPhone(phone: String?): String {
        if (phone.isNullOrBlank()) return "(空)"
        return if (phone.length >= 11) phone.replaceRange(3, 7, "****") else "***"
    }

    /** 响应体只留前若干字符，避免日志被大 JSON 撑爆。 */
    fun preview(text: String?, limit: Int = 200): String {
        if (text.isNullOrBlank()) return "(空)"
        return if (text.length <= limit) text else text.take(limit) + "…(${text.length} chars)"
    }
}
