package wx.mirage.manager

import android.content.Context
import wx.mirage.Constants
import wx.mirage.util.LogUtil

/**
 * 模块级配置管理器
 *
 * 管理模块级别的全局配置（非好友级别的配置），包括：
 * - 命令前缀和命令字符串
 * - 长按触发时长
 * - 各功能模块的启用/禁用开关
 *
 * 所有配置存储在 SharedPreferences 中，支持持久化。
 *
 * ## 与 ConfigManager 的区别
 * - [ConfigManager] 管理的是好友级别的配置（每个好友的隐藏开关）
 * - [ModuleConfigManager] 管理的是模块级别的配置（全局命令、功能开关等）
 *
 * ## 线程安全
 * 使用 SharedPreferences 作为底层存储，通过其内置的线程安全机制保证数据一致性。
 * 不使用缓存（直接读写 SP），因为模块级配置访问频率低。
 */
object ModuleConfigManager {

    private const val TAG = "${Constants.MODULE_TAG}:ModuleConfigManager"
    private const val SP_NAME = "wx_mirage_module_config"

    // ===== SharedPreferences Key 常量 =====

    private const val KEY_COMMAND_PREFIX = "command_prefix"
    private const val KEY_SHOW_COMMAND = "show_command"
    private const val KEY_KEEP_COMMAND = "keep_command"
    private const val KEY_LIST_COMMAND = "list_command"
    private const val KEY_WXID_COMMAND = "wxid_command"
    private const val KEY_LONG_PRESS_DURATION = "long_press_duration"
    private const val KEY_LONG_PRESS_BLANK_DURATION = "long_press_blank_duration"
    private const val KEY_ENABLE_LONG_PRESS = "enable_long_press"
    private const val KEY_ENABLE_COMMAND = "enable_command"
    private const val KEY_ENABLE_MESSAGE_INDICATOR = "enable_message_indicator"
    private const val KEY_ENABLE_ANTI_REVOKE = "enable_anti_revoke"
    private const val KEY_ENABLE_AD_REMOVAL = "enable_ad_removal"

    // ===== 默认值 =====

    private const val DEFAULT_COMMAND_PREFIX = "#veil"
    private const val DEFAULT_SHOW_COMMAND = "#show"
    private const val DEFAULT_KEEP_COMMAND = "#keep"
    private const val DEFAULT_LIST_COMMAND = "#list"
    private const val DEFAULT_WXID_COMMAND = "#wxid"
    private const val DEFAULT_LONG_PRESS_DURATION = 5000
    private const val DEFAULT_LONG_PRESS_BLANK_DURATION = 10000
    private const val DEFAULT_ENABLE_LONG_PRESS = true
    private const val DEFAULT_ENABLE_COMMAND = true
    private const val DEFAULT_ENABLE_MESSAGE_INDICATOR = true
    private const val DEFAULT_ENABLE_ANTI_REVOKE = true
    private const val DEFAULT_ENABLE_AD_REMOVAL = true

    // ========================================================================
    // 命令配置
    // ========================================================================

    /**
     * 获取命令前缀（默认 "#veil"）。
     * 用户在聊天输入框或搜索框中输入此前缀触发模块功能。
     */
    fun getCommandPrefix(context: Context): String {
        return getSP(context).getString(KEY_COMMAND_PREFIX, DEFAULT_COMMAND_PREFIX) ?: DEFAULT_COMMAND_PREFIX
    }

    /**
     * 设置命令前缀。
     */
    fun setCommandPrefix(context: Context, prefix: String) {
        getSP(context).edit().putString(KEY_COMMAND_PREFIX, prefix).apply()
        LogUtil.d(TAG, "setCommandPrefix: $prefix")
    }

    /**
     * 获取 #show 命令（默认 "#show"）。
     */
    fun getShowCommand(context: Context): String {
        return getSP(context).getString(KEY_SHOW_COMMAND, DEFAULT_SHOW_COMMAND) ?: DEFAULT_SHOW_COMMAND
    }

    /**
     * 设置 #show 命令。
     */
    fun setShowCommand(context: Context, command: String) {
        getSP(context).edit().putString(KEY_SHOW_COMMAND, command).apply()
        LogUtil.d(TAG, "setShowCommand: $command")
    }

    /**
     * 获取 #keep 命令（默认 "#keep"）。
     */
    fun getKeepCommand(context: Context): String {
        return getSP(context).getString(KEY_KEEP_COMMAND, DEFAULT_KEEP_COMMAND) ?: DEFAULT_KEEP_COMMAND
    }

    /**
     * 设置 #keep 命令。
     */
    fun setKeepCommand(context: Context, command: String) {
        getSP(context).edit().putString(KEY_KEEP_COMMAND, command).apply()
        LogUtil.d(TAG, "setKeepCommand: $command")
    }

    /**
     * 获取 #list 命令（默认 "#list"）。
     */
    fun getListCommand(context: Context): String {
        return getSP(context).getString(KEY_LIST_COMMAND, DEFAULT_LIST_COMMAND) ?: DEFAULT_LIST_COMMAND
    }

    /**
     * 设置 #list 命令。
     */
    fun setListCommand(context: Context, command: String) {
        getSP(context).edit().putString(KEY_LIST_COMMAND, command).apply()
        LogUtil.d(TAG, "setListCommand: $command")
    }

    /**
     * 获取 #wxid 命令（默认 "#wxid"）。
     */
    fun getWxidCommand(context: Context): String {
        return getSP(context).getString(KEY_WXID_COMMAND, DEFAULT_WXID_COMMAND) ?: DEFAULT_WXID_COMMAND
    }

    /**
     * 设置 #wxid 命令。
     */
    fun setWxidCommand(context: Context, command: String) {
        getSP(context).edit().putString(KEY_WXID_COMMAND, command).apply()
        LogUtil.d(TAG, "setWxidCommand: $command")
    }

    // ========================================================================
    // 长按配置
    // ========================================================================

    /**
     * 获取长按触发时长（毫秒，默认 5000）。
     */
    fun getLongPressDuration(context: Context): Int {
        return getSP(context).getInt(KEY_LONG_PRESS_DURATION, DEFAULT_LONG_PRESS_DURATION)
    }

    /**
     * 设置长按触发时长。
     */
    fun setLongPressDuration(context: Context, durationMs: Int) {
        getSP(context).edit().putInt(KEY_LONG_PRESS_DURATION, durationMs).apply()
        LogUtil.d(TAG, "setLongPressDuration: ${durationMs}ms")
    }

    /**
     * 获取空白区域长按触发时长（毫秒，默认 10000）。
     */
    fun getLongPressBlankDuration(context: Context): Int {
        return getSP(context).getInt(KEY_LONG_PRESS_BLANK_DURATION, DEFAULT_LONG_PRESS_BLANK_DURATION)
    }

    /**
     * 设置空白区域长按触发时长。
     */
    fun setLongPressBlankDuration(context: Context, durationMs: Int) {
        getSP(context).edit().putInt(KEY_LONG_PRESS_BLANK_DURATION, durationMs).apply()
        LogUtil.d(TAG, "setLongPressBlankDuration: ${durationMs}ms")
    }

    // ========================================================================
    // 功能开关
    // ========================================================================

    /**
     * 获取长按功能是否启用（默认 true）。
     */
    fun isLongPressEnabled(context: Context): Boolean {
        return getSP(context).getBoolean(KEY_ENABLE_LONG_PRESS, DEFAULT_ENABLE_LONG_PRESS)
    }

    /**
     * 设置长按功能启用状态。
     */
    fun setLongPressEnabled(context: Context, enabled: Boolean) {
        getSP(context).edit().putBoolean(KEY_ENABLE_LONG_PRESS, enabled).apply()
        LogUtil.d(TAG, "setLongPressEnabled: $enabled")
    }

    /**
     * 获取命令功能是否启用（默认 true）。
     */
    fun isCommandEnabled(context: Context): Boolean {
        return getSP(context).getBoolean(KEY_ENABLE_COMMAND, DEFAULT_ENABLE_COMMAND)
    }

    /**
     * 设置命令功能启用状态。
     */
    fun setCommandEnabled(context: Context, enabled: Boolean) {
        getSP(context).edit().putBoolean(KEY_ENABLE_COMMAND, enabled).apply()
        LogUtil.d(TAG, "setCommandEnabled: $enabled")
    }

    /**
     * 获取消息指示器是否启用（默认 true）。
     */
    fun isMessageIndicatorEnabled(context: Context): Boolean {
        return getSP(context).getBoolean(KEY_ENABLE_MESSAGE_INDICATOR, DEFAULT_ENABLE_MESSAGE_INDICATOR)
    }

    /**
     * 设置消息指示器启用状态。
     */
    fun setMessageIndicatorEnabled(context: Context, enabled: Boolean) {
        getSP(context).edit().putBoolean(KEY_ENABLE_MESSAGE_INDICATOR, enabled).apply()
        LogUtil.d(TAG, "setMessageIndicatorEnabled: $enabled")
    }

    /**
     * 获取防撤回功能是否启用（默认 true）。
     */
    fun isAntiRevokeEnabled(context: Context): Boolean {
        return getSP(context).getBoolean(KEY_ENABLE_ANTI_REVOKE, DEFAULT_ENABLE_ANTI_REVOKE)
    }

    /**
     * 设置防撤回功能启用状态。
     */
    fun setAntiRevokeEnabled(context: Context, enabled: Boolean) {
        getSP(context).edit().putBoolean(KEY_ENABLE_ANTI_REVOKE, enabled).apply()
        LogUtil.d(TAG, "setAntiRevokeEnabled: $enabled")
    }

    /**
     * 获取广告移除功能是否启用（默认 true）。
     */
    fun isAdRemovalEnabled(context: Context): Boolean {
        return getSP(context).getBoolean(KEY_ENABLE_AD_REMOVAL, DEFAULT_ENABLE_AD_REMOVAL)
    }

    /**
     * 设置广告移除功能启用状态。
     */
    fun setAdRemovalEnabled(context: Context, enabled: Boolean) {
        getSP(context).edit().putBoolean(KEY_ENABLE_AD_REMOVAL, enabled).apply()
        LogUtil.d(TAG, "setAdRemovalEnabled: $enabled")
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    /**
     * 重置所有配置为默认值。
     */
    fun resetAll(context: Context) {
        getSP(context).edit().clear().apply()
        LogUtil.i(TAG, "All module config reset to defaults")
    }

    /**
     * 获取所有配置的摘要信息（用于调试）。
     */
    fun getConfigSummary(context: Context): String = buildString {
        appendLine("ModuleConfig Summary:")
        appendLine("  commandPrefix: ${getCommandPrefix(context)}")
        appendLine("  showCommand: ${getShowCommand(context)}")
        appendLine("  keepCommand: ${getKeepCommand(context)}")
        appendLine("  listCommand: ${getListCommand(context)}")
        appendLine("  wxidCommand: ${getWxidCommand(context)}")
        appendLine("  longPressDuration: ${getLongPressDuration(context)}ms")
        appendLine("  longPressBlankDuration: ${getLongPressBlankDuration(context)}ms")
        appendLine("  enableLongPress: ${isLongPressEnabled(context)}")
        appendLine("  enableCommand: ${isCommandEnabled(context)}")
        appendLine("  enableMessageIndicator: ${isMessageIndicatorEnabled(context)}")
        appendLine("  enableAntiRevoke: ${isAntiRevokeEnabled(context)}")
        appendLine("  enableAdRemoval: ${isAdRemovalEnabled(context)}")
    }

    // ========================================================================
    // 内部方法
    // ========================================================================

    private fun getSP(context: Context) =
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
}