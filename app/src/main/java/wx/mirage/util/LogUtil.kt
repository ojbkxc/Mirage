package wx.mirage.util

import de.robv.android.xposed.XposedBridge
import wx.mirage.Constants

/**
 * Mirage 集中式日志工具类
 *
 * 封装 XposedBridge.log，提供统一的 TAG 格式和日志级别过滤。
 * 所有日志输出格式: "Mirage: [TAG] message"
 */
object LogUtil {

    const val GLOBAL_TAG = Constants.MODULE_TAG

    const val LEVEL_DEBUG = 0
    const val LEVEL_INFO = 1
    const val LEVEL_WARN = 2
    const val LEVEL_ERROR = 3

    @Volatile private var currentLevel: Int = LEVEL_DEBUG
    @Volatile private var debugMode: Boolean = false
    private val logBuffer = mutableListOf<String>()
    private const val MAX_BUFFER_SIZE = 500

    private fun format(tag: String, message: String): String {
        return "$GLOBAL_TAG: [$tag] $message"
    }

    private fun shouldLog(level: Int): Boolean = level >= currentLevel

    fun setDebugMode(enabled: Boolean) { debugMode = enabled }
    fun setLogLevel(level: Int) { currentLevel = level.coerceIn(LEVEL_DEBUG, LEVEL_ERROR) }
    fun isDebugMode(): Boolean = debugMode

    fun d(tag: String, message: String) {
        if (!shouldLog(LEVEL_DEBUG)) return
        val msg = "[DEBUG] ${format(tag, message)}"
        XposedBridge.log(msg)
        addToBuffer(msg)
    }

    fun i(tag: String, message: String) {
        if (!shouldLog(LEVEL_INFO)) return
        val msg = "[INFO] ${format(tag, message)}"
        XposedBridge.log(msg)
        addToBuffer(msg)
    }

    fun w(tag: String, message: String) {
        if (!shouldLog(LEVEL_WARN)) return
        val msg = "[WARN] ${format(tag, message)}"
        XposedBridge.log(msg)
        addToBuffer(msg)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldLog(LEVEL_ERROR)) return
        val msg = "[ERROR] ${format(tag, message)}"
        XposedBridge.log(msg)
        addToBuffer(msg)
        throwable?.let { XposedBridge.log(it) }
    }

    private fun addToBuffer(msg: String) {
        synchronized(logBuffer) {
            if (logBuffer.size >= MAX_BUFFER_SIZE) {
                logBuffer.removeAt(0)
            }
            logBuffer.add(msg)
        }
    }

    fun getRecentLogs(count: Int): List<String> {
        synchronized(logBuffer) {
            val start = (logBuffer.size - count).coerceAtLeast(0)
            return logBuffer.subList(start, logBuffer.size).toList()
        }
    }

    fun clearLogs() {
        synchronized(logBuffer) { logBuffer.clear() }
    }
}