package wx.mirage.config

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import wx.mirage.model.FriendConfig
import wx.mirage.util.ConfigValidator
import wx.mirage.util.LogUtil

/**
 * Mirage 配置管理器
 *
 * 负责管理所有持久化配置，包括隐藏好友列表、好友详细配置、模块开关等。
 * 使用 SharedPreferences 作为底层存储，使用 Gson 进行 JSON 序列化。
 *
 * ## 存储结构
 * - hidden_wx_ids: Set<String> - 隐藏好友 wxId 集合（用于快速查询）
 * - friend_configs: Map<String, FriendConfig> - 每个好友的详细配置（JSON 序列化）
 * - module_enabled: Boolean - 模块总开关
 *
 * ## 缓存机制
 * 使用 dirty-flag 缓存模式：
 * - 读操作：先检查缓存是否有效（dirty=false），有效则直接返回，否则从 SP 重新加载
 * - 写操作：写入 SP 后调用 invalidateCache() 将 dirty 标记为 true
 *
 * ## 线程安全
 * - 缓存变量使用 @Volatile 注解保证多线程可见性
 * - 写操作通过 SharedPreferences.edit() 保证原子性
 */
object ConfigManager {
    private const val SP_NAME = "wx_mirage_config"
    private const val KEY_HIDDEN_IDS = "hidden_wx_ids"
    private const val KEY_ENABLED = "module_enabled"
    private const val KEY_FRIEND_CONFIGS = "friend_configs"

    private var spName: String = SP_NAME
    private var configuredPkg: String? = null

    // ===== Gson 单例 =====
    private val gson: Gson = GsonBuilder().create()

    // ===== hiddenIds 缓存 =====
    @Volatile
    private var hiddenIdsCache: Set<String> = emptySet()
    @Volatile
    private var hiddenIdsCacheDirty: Boolean = true
    @Volatile
    private var lastContextHash: Int = 0

    // ===== friendConfigs 缓存 =====
    @Volatile
    private var friendConfigsCache: Map<String, FriendConfig> = emptyMap()
    @Volatile
    private var friendConfigsCacheDirty: Boolean = true

    private fun invalidateCache() {
        hiddenIdsCacheDirty = true
        friendConfigsCacheDirty = true
    }

    private fun getCachedHiddenIds(context: Context): Set<String> {
        val ctxHash = context.hashCode()
        if (hiddenIdsCacheDirty || ctxHash != lastContextHash) {
            hiddenIdsCache = getSP(context).getStringSet(KEY_HIDDEN_IDS, emptySet()) ?: emptySet()
            hiddenIdsCacheDirty = false
            lastContextHash = ctxHash
        }
        return hiddenIdsCache
    }

    private fun getCachedFriendConfigs(context: Context): Map<String, FriendConfig> {
        val ctxHash = context.hashCode()
        if (friendConfigsCacheDirty || ctxHash != lastContextHash) {
            val json = getSP(context).getString(KEY_FRIEND_CONFIGS, null)
            friendConfigsCache = if (json != null) {
                try {
                    val type = object : TypeToken<Map<String, FriendConfig>>() {}.type
                    gson.fromJson(json, type)
                } catch (e: Exception) {
                    LogUtil.w("ConfigManager", "Failed to parse friend configs: ${e.message}")
                    emptyMap()
                }
            } else {
                emptyMap()
            }
            friendConfigsCacheDirty = false
            lastContextHash = ctxHash
        }
        return friendConfigsCache
    }

    // ===== 初始化 =====

    fun init(packageName: String) {
        configuredPkg = packageName
        spName = "${SP_NAME}_${packageName}"
        invalidateCache()
    }

    fun hasContext(): Boolean = configuredPkg != null

    private fun getSP(context: Context) =
        context.getSharedPreferences(resolveSpName(context), Context.MODE_PRIVATE)

    private fun resolveSpName(context: Context): String {
        val ctxPkg = context.packageName
        return if (configuredPkg != null && ctxPkg == configuredPkg) {
            spName
        } else {
            SP_NAME
        }
    }

    // ===== 隐藏好友列表 =====

    fun getHiddenWxIds(context: Context): Set<String> {
        return getCachedHiddenIds(context)
    }

    fun setHiddenWxIds(context: Context, ids: Set<String>) {
        getSP(context).edit().putStringSet(KEY_HIDDEN_IDS, ids).apply()
        invalidateCache()
    }

    fun isHidden(context: Context, wxId: String): Boolean {
        return wxId in getCachedHiddenIds(context)
    }

    // ===== 好友详细配置 (FriendConfig) =====

    /**
     * 获取指定好友的配置。
     * 如果好友不在隐藏列表中，返回 null。
     */
    fun getFriendConfig(context: Context, wxId: String): FriendConfig? {
        return getCachedFriendConfigs(context)[wxId]
    }

    /**
     * 获取所有好友的配置映射。
     */
    fun getAllFriendConfigs(context: Context): Map<String, FriendConfig> {
        return getCachedFriendConfigs(context)
    }

    /**
     * 设置指定好友的配置。
     */
    fun setFriendConfig(context: Context, config: FriendConfig) {
        val configs = getCachedFriendConfigs(context).toMutableMap()
        configs[config.wxId] = config
        val json = gson.toJson(configs)
        getSP(context).edit().putString(KEY_FRIEND_CONFIGS, json).apply()
        invalidateCache()
    }

    /**
     * 添加好友到隐藏列表，同时创建默认配置。
     *
     * @return true 如果成功添加
     */
    fun addHiddenWxId(context: Context, wxId: String, label: String = ""): Boolean {
        val validation = ConfigValidator.validateWxId(wxId)
        if (!validation.valid) {
            LogUtil.w("ConfigManager", "addHiddenWxId: invalid wxId '$wxId' - ${validation.message}")
            return false
        }

        if (WxIdBlacklist.isBlacklisted(wxId)) {
            LogUtil.w("ConfigManager", "addHiddenWxId: wxId '$wxId' is in the blacklist")
            return false
        }

        // 添加到 hiddenIds
        val current = getCachedHiddenIds(context).toMutableSet()
        current.add(wxId)
        setHiddenWxIds(context, current)

        // 创建默认 FriendConfig
        val config = FriendConfig.createDefault(wxId, label)
        setFriendConfig(context, config)

        return true
    }

    /**
     * 移除好友（同时清理 hiddenIds 和 friendConfig）。
     */
    fun removeHiddenWxId(context: Context, wxId: String) {
        // 移除 hiddenIds
        val current = getCachedHiddenIds(context).toMutableSet()
        current.remove(wxId)
        setHiddenWxIds(context, current)

        // 移除 friendConfig
        val configs = getCachedFriendConfigs(context).toMutableMap()
        configs.remove(wxId)
        val json = gson.toJson(configs)
        getSP(context).edit().putString(KEY_FRIEND_CONFIGS, json).apply()
        invalidateCache()
    }

    /**
     * 更新好友的单个开关状态。
     */
    fun updateFriendToggle(
        context: Context,
        wxId: String,
        toggle: FriendToggle,
        value: Boolean
    ) {
        val config = getFriendConfig(context, wxId)
            ?: FriendConfig.createDefault(wxId)
        val updated = when (toggle) {
            FriendToggle.MASTER_SWITCH -> config.copy(masterSwitch = value)
            FriendToggle.BLOCK_VOICE_CALL -> config.copy(blockVoiceCall = value)
            FriendToggle.BLOCK_NOTIFICATION -> config.copy(blockNotification = value)
            FriendToggle.DISGUISE_MAIN_PAGE -> config.copy(disguiseMainPage = value)
            FriendToggle.HIDE_CHAT_HISTORY -> config.copy(hideChatHistory = value)
            FriendToggle.HIDE_CONTACT -> config.copy(hideContact = value)
            FriendToggle.HIDE_MOMENTS -> config.copy(hideMoments = value)
            FriendToggle.HIDE_OTHER_MISC -> config.copy(hideOtherMisc = value)
        }
        setFriendConfig(context, updated)
    }

    /**
     * 获取所有需要拦截语音通话的好友 wxId 集合。
     */
    fun getVoiceCallBlockedIds(context: Context): Set<String> {
        return getCachedFriendConfigs(context)
            .filterValues { it.isVoiceCallBlocked }
            .keys
    }

    /**
     * 获取所有需要拦截通知的好友 wxId 集合。
     */
    fun getNotificationBlockedIds(context: Context): Set<String> {
        return getCachedFriendConfigs(context)
            .filterValues { it.isNotificationBlocked }
            .keys
    }

    /**
     * 获取所有需要主页伪装的好友 wxId 集合。
     */
    fun getDisguisedIds(context: Context): Set<String> {
        return getCachedFriendConfigs(context)
            .filterValues { it.isMainPageDisguised }
            .keys
    }

    /**
     * 获取所有需要隐藏聊天记录的好友 wxId 集合。
     */
    fun getChatHistoryHiddenIds(context: Context): Set<String> {
        return getCachedFriendConfigs(context)
            .filterValues { it.isChatHistoryHidden }
            .keys
    }

    /**
     * 获取所有需要隐藏联系人的好友 wxId 集合。
     */
    fun getContactHiddenIds(context: Context): Set<String> {
        return getCachedFriendConfigs(context)
            .filterValues { it.isContactHidden }
            .keys
    }

    /**
     * 获取所有需要隐藏朋友圈的好友 wxId 集合。
     */
    fun getMomentsHiddenIds(context: Context): Set<String> {
        return getCachedFriendConfigs(context)
            .filterValues { it.isMomentsHidden }
            .keys
    }

    /**
     * 获取所有需要其他杂项隐藏的好友 wxId 集合。
     */
    fun getOtherMiscHiddenIds(context: Context): Set<String> {
        return getCachedFriendConfigs(context)
            .filterValues { it.isOtherMiscHidden }
            .keys
    }

    /**
     * 获取伪装映射：隐藏好友 wxId -> 伪装目标 wxId。
     */
    fun getDisguiseMap(context: Context): Map<String, String> {
        return getCachedFriendConfigs(context)
            .filterValues { it.isMainPageDisguised && it.disguiseTargetId.isNotEmpty() }
            .mapValues { it.value.disguiseTargetId }
    }

    // ===== 模块开关 =====

    fun isEnabled(context: Context): Boolean {
        return getSP(context).getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        getSP(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // ===== 标签 =====

    fun getLabels(context: Context): Map<String, String> {
        return getCachedFriendConfigs(context).mapValues { it.value.label }
    }

    fun setLabel(context: Context, wxId: String, label: String) {
        val config = getFriendConfig(context, wxId) ?: return
        setFriendConfig(context, config.copy(label = label))
    }

    fun removeLabel(context: Context, wxId: String) {
        setLabel(context, wxId, "")
    }

    // ===== 批量操作 =====

    fun getAllConfig(context: Context): Map<String, Any> {
        return mapOf(
            "enabled" to isEnabled(context),
            "hiddenIds" to getCachedHiddenIds(context).toList(),
            "friendConfigs" to getCachedFriendConfigs(context)
        )
    }

    fun setAllConfig(context: Context, config: Map<String, Any>): Int {
        var count = 0
        val editor = getSP(context).edit()

        config["enabled"]?.let { value ->
            if (value is Boolean) {
                editor.putBoolean(KEY_ENABLED, value)
                count++
            }
        }

        config["hiddenIds"]?.let { value ->
            @Suppress("UNCHECKED_CAST")
            val ids = (value as? List<*>)?.mapNotNull { it as? String }?.toSet()
            if (ids != null) {
                editor.putStringSet(KEY_HIDDEN_IDS, ids)
                count++
            }
        }

        config["friendConfigs"]?.let { value ->
            @Suppress("UNCHECKED_CAST")
            val configs = value as? Map<String, FriendConfig>
            if (configs != null) {
                editor.putString(KEY_FRIEND_CONFIGS, gson.toJson(configs))
                count++
            }
        }

        editor.apply()
        invalidateCache()
        return count
    }

    fun clear(context: Context) {
        getSP(context).edit()
            .clear()
            .putBoolean(KEY_ENABLED, true)
            .apply()
        invalidateCache()
    }

    fun backup(context: Context): String? {
        return try {
            val backupDir = File(
                Environment.getExternalStorageDirectory(),
                "Mirage/backup"
            )
            if (!backupDir.exists()) {
                backupDir.mkdirs()
            }

            val timestamp = System.currentTimeMillis()
            val backupFile = File(backupDir, "mirage_backup_$timestamp.json")

            val data = mapOf(
                "version" to 1,
                "timestamp" to timestamp,
                "enabled" to isEnabled(context),
                "hiddenIds" to getCachedHiddenIds(context).toList(),
                "friendConfigs" to getCachedFriendConfigs(context)
            )

            backupFile.writeText(gson.toJson(data))
            backupFile.absolutePath
        } catch (e: Exception) {
            LogUtil.e("ConfigManager", "Failed to backup config: ${e.message}", e)
            null
        }
    }

    fun exportToJson(context: Context): String {
        val data = mapOf(
            "hiddenIds" to getCachedHiddenIds(context).toList(),
            "friendConfigs" to getCachedFriendConfigs(context)
        )
        return gson.toJson(data)
    }

    fun importFromJson(context: Context, json: String): Int {
        return try {
            val validation = ConfigValidator.validateImportJson(json)
            if (!validation.valid) {
                LogUtil.w("ConfigManager", "importFromJson: invalid JSON - ${validation.message}")
                return -1
            }

            val type = object : TypeToken<Map<String, Any>>() {}.type
            val data: Map<String, Any> = gson.fromJson(json, type)

            val ids = (data["hiddenIds"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            if (ids.isNotEmpty()) {
                setHiddenWxIds(context, ids.toSet())
            }

            @Suppress("UNCHECKED_CAST")
            val configs = (data["friendConfigs"] as? Map<String, FriendConfig>)
            if (configs != null && configs.isNotEmpty()) {
                getSP(context).edit().putString(KEY_FRIEND_CONFIGS, gson.toJson(configs)).apply()
                invalidateCache()
            }

            ids.size
        } catch (e: Exception) {
            LogUtil.e("ConfigManager", "Failed to import JSON: ${e.message}", e)
            -1
        }
    }

    /**
     * 配置重载（供 ConfigReceiver 使用）。
     * 使所有缓存失效，下次读取时从 SP 重新加载。
     */
    fun reloadConfig() {
        invalidateCache()
        LogUtil.d("ConfigManager", "Config reloaded (cache invalidated)")
    }

    /**
     * 清除所有配置。
     */
    fun clearConfig() {
        invalidateCache()
        LogUtil.d("ConfigManager", "Config cleared (cache invalidated)")
    }
}

/**
 * 好友功能开关枚举。
 * 用于 updateFriendToggle 方法指定要更新的开关。
 */
enum class FriendToggle {
    MASTER_SWITCH,
    BLOCK_VOICE_CALL,
    BLOCK_NOTIFICATION,
    DISGUISE_MAIN_PAGE,
    HIDE_CHAT_HISTORY,
    HIDE_CONTACT,
    HIDE_MOMENTS,
    HIDE_OTHER_MISC
}