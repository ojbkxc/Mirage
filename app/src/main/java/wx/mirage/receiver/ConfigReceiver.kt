package wx.mirage.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import wx.mirage.Constants
import wx.mirage.MainHook
import wx.mirage.config.ConfigManager
import wx.mirage.manager.TempUnhideManager
import wx.mirage.util.LogUtil

/**
 * 配置变更广播接收器
 *
 * 监听来自 Mirage 管理界面的配置变更广播，
 * 支持重新加载配置、清除 DexKit 缓存、强制重新加载 Hook 等操作。
 *
 * 实现为 object 单例，确保全局只有一个接收器实例。
 * 线程安全：onReceive() 可能在不同线程中并发调用，
 * 但所有操作都是无状态的，不需要额外同步。
 */
object ConfigReceiver : BroadcastReceiver() {

    private var registered = false
    private var contextRef: Context? = null

    /**
     * 注册广播接收器
     *
     * 动态注册广播接收器，监听以下 Action：
     * - [Constants.ACTION_RELOAD_CONFIG]: 重新加载配置
     * - [Constants.ACTION_CLEAR_DEXKIT_CACHE]: 清除 DexKit 缓存
     * - [Constants.ACTION_FORCE_RELOAD_HOOKS]: 强制重新加载 Hook
     *
     * 使用自定义权限 [Constants.PERMISSION_CONTROL] 保护广播，
     * 防止未授权的应用发送伪造广播。
     *
     * @param context Android Context，用于注册广播接收器
     */
    fun register(context: Context) {
        if (registered) {
            LogUtil.d(Constants.MODULE_TAG, "ConfigReceiver already registered, skipping")
            return
        }

        val filter = IntentFilter().apply {
            addAction(Constants.ACTION_RELOAD_CONFIG)
            addAction(Constants.ACTION_CLEAR_DEXKIT_CACHE)
            addAction(Constants.ACTION_FORCE_RELOAD_HOOKS)
            addAction(Constants.ACTION_TEMP_UNHIDE)
            addAction(Constants.ACTION_RESTORE_HIDE)
            addAction(Constants.ACTION_BACKGROUND_RESTORE)
            addAction(Constants.ACTION_CONFIG_CHANGED)
        }

        context.registerReceiver(this, filter, Constants.PERMISSION_CONTROL, null)
        contextRef = context.applicationContext
        registered = true
        LogUtil.i(Constants.MODULE_TAG, "ConfigReceiver registered")
    }

    /**
     * 注销广播接收器并清理资源
     */
    fun cleanup() {
        try {
            if (registered) {
                contextRef?.unregisterReceiver(this)
                LogUtil.i(Constants.MODULE_TAG, "ConfigReceiver unregistered")
            }
        } catch (e: Throwable) {
            LogUtil.w(Constants.MODULE_TAG, "Error unregistering ConfigReceiver: ${e.message}")
        } finally {
            registered = false
            contextRef = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        LogUtil.i(Constants.MODULE_TAG, "ConfigReceiver received: $action")

        when (action) {
            Constants.ACTION_RELOAD_CONFIG -> {
                handleReloadConfig()
            }
            Constants.ACTION_CLEAR_DEXKIT_CACHE -> {
                handleClearDexKitCache()
            }
            Constants.ACTION_FORCE_RELOAD_HOOKS -> {
                handleForceReloadHooks()
            }
            Constants.ACTION_TEMP_UNHIDE -> {
                handleTempUnhide(intent)
            }
            Constants.ACTION_RESTORE_HIDE -> {
                handleRestoreHide(intent)
            }
            Constants.ACTION_BACKGROUND_RESTORE -> {
                handleBackgroundRestore()
            }
            Constants.ACTION_CONFIG_CHANGED -> {
                handleConfigChanged()
            }
            else -> {
                LogUtil.w(Constants.MODULE_TAG, "ConfigReceiver: unknown action $action")
            }
        }
    }

    /**
     * 重新加载配置
     */
    private fun handleReloadConfig() {
        try {
            LogUtil.i(Constants.MODULE_TAG, "Reloading configuration...")
            ConfigManager.reloadConfig()
            LogUtil.i(Constants.MODULE_TAG, "Configuration reloaded successfully")
        } catch (e: Throwable) {
            LogUtil.e(Constants.MODULE_TAG, "Failed to reload config: ${e.message}", e)
        }
    }

    /**
     * 清除 DexKit 缓存
     */
    private fun handleClearDexKitCache() {
        try {
            LogUtil.i(Constants.MODULE_TAG, "Clearing DexKit caches...")
            MainHook.clearAllDexKitCaches()
            LogUtil.i(Constants.MODULE_TAG, "DexKit caches cleared")
        } catch (e: Throwable) {
            LogUtil.e(Constants.MODULE_TAG, "Failed to clear DexKit caches: ${e.message}", e)
        }
    }

    /**
     * 强制重新加载 Hook
     */
    private fun handleForceReloadHooks() {
        try {
            LogUtil.i(Constants.MODULE_TAG, "Force reloading hooks...")
            MainHook.clearAllDexKitCaches()
            MainHook.isShutdown = false
            MainHook.hooksRegistered = false
            MainHook.registerAllHooks(MainHook.lpparam)
            LogUtil.i(Constants.MODULE_TAG, "Hooks force reloaded")
        } catch (e: Throwable) {
            LogUtil.e(Constants.MODULE_TAG, "Failed to force reload hooks: ${e.message}", e)
        }
    }

    /**
     * 临时取消隐藏
     */
    private fun handleTempUnhide(intent: Intent) {
        try {
            val wxId = intent.getStringExtra("wxId") ?: return
            TempUnhideManager.tempUnhide(wxId)
            LogUtil.i(Constants.MODULE_TAG, "Temp unhide: $wxId")
        } catch (e: Throwable) {
            LogUtil.e(Constants.MODULE_TAG, "Failed to temp unhide: ${e.message}", e)
        }
    }

    /**
     * 恢复隐藏
     */
    private fun handleRestoreHide(intent: Intent) {
        try {
            val wxId = intent.getStringExtra("wxId")
            if (wxId != null) {
                TempUnhideManager.restoreForFriend(wxId)
                LogUtil.i(Constants.MODULE_TAG, "Restore hide: $wxId")
            } else {
                TempUnhideManager.restoreAll()
                LogUtil.i(Constants.MODULE_TAG, "Restore all hide")
            }
        } catch (e: Throwable) {
            LogUtil.e(Constants.MODULE_TAG, "Failed to restore hide: ${e.message}", e)
        }
    }

    /**
     * 后台自动恢复
     */
    private fun handleBackgroundRestore() {
        try {
            TempUnhideManager.restoreOnAppBackground()
            LogUtil.i(Constants.MODULE_TAG, "Background restore scheduled")
        } catch (e: Throwable) {
            LogUtil.e(Constants.MODULE_TAG, "Failed to schedule background restore: ${e.message}", e)
        }
    }

    /**
     * 配置变更
     */
    private fun handleConfigChanged() {
        try {
            ConfigManager.reloadConfig()
            LogUtil.i(Constants.MODULE_TAG, "Config changed, cache reloaded")
        } catch (e: Throwable) {
            LogUtil.e(Constants.MODULE_TAG, "Failed to handle config change: ${e.message}", e)
        }
    }
}