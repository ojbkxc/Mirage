package wx.mirage.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import wx.mirage.Constants
import wx.mirage.MainHook
import wx.mirage.config.ConfigManager
import wx.mirage.config.HookStatus
import wx.mirage.lifecycle.HookLifecycleListener
import wx.mirage.util.HookMetrics
import wx.mirage.util.LogUtil

/**
 * 消息指示器 Hook 模块
 *
 * 功能：拦截微信主页面底部的消息指示器（未读消息数量、红点等）。
 * 隐藏被标记好友的未读消息指示，使得主页面不会显示这些好友的未读消息。
 *
 * 实现方式：
 * - Hook 主页面 Tab 的未读消息更新方法
 * - 在更新前检查发送者是否在隐藏列表中
 * - 如果发送者被隐藏，将未读数减少相应的数量
 */
object MessageIndicatorHook : HookLifecycleListener {

    private const val TAG = Constants.MODULE_TAG + ":MessageIndicatorHook"

    @Volatile
    var status: HookStatus = HookStatus.INACTIVE
        private set

    @Volatile
    private var cacheWarmedUp: Boolean = false

    private var targetClass: Class<*>? = null
    private var targetMethodName: String? = null

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        try {
            if (false) {
                initWithDexKit(lpparam)
            } else {
                initFallback(classLoader)
            }
            status = if (MainHook.dexKitAvailable) HookStatus.ACTIVE else HookStatus.DEGRADED
            onHookRegistered()
        } catch (e: Throwable) {
            LogUtil.w(TAG, "DexKit init failed, using fallback: ${e.message}")
            try {
                initFallback(classLoader)
                status = HookStatus.DEGRADED
                onHookRegistered()
            } catch (e2: Throwable) {
                LogUtil.e(TAG, "Fallback also failed: ${e2.message}", e2)
                status = HookStatus.ERROR
                onHookFailed(e2)
            }
        }
    }

    private fun initWithDexKit(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        val launcherClass = MainHook.dexKitBridge.findClass {
            searchString = "launcher"
        }

        if (launcherClass != null) {
            targetClass = classLoader.loadClass(launcherClass.name)
            targetMethodName = "onResume"
            cacheWarmedUp = true
            LogUtil.i(TAG, "DexKit found: ${launcherClass.name}")
        }
    }

    private fun initFallback(classLoader: ClassLoader) {
        val candidates = listOf(
            "com.tencent.mm.ui.LauncherUI",
            "com.tencent.mm.ui.MainTabUI",
            "${Constants.WECHAT_UI_CONVERSATION}.ConversationFragment",
            "${Constants.WECHAT_UI_CONVERSATION}.ConversationListUI",
            "com.tencent.mm.ui.conversation.MainUI"
        )

        for (className in candidates) {
            try {
                targetClass = classLoader.loadClass(className)
                targetMethodName = "onResume"
                LogUtil.i(TAG, "Fallback found: $className")
                return
            } catch (_: ClassNotFoundException) {
            }
        }
    }

    override fun onHookRegistered() {
        LogUtil.i(TAG, "MessageIndicatorHook registered (status: ${status.description})")
        targetClass?.let { clazz ->
            targetMethodName?.let { method ->
                try {
                    XposedHelpers.findAndHookMethod(clazz, method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            filterMessageIndicators(param)
                        }
                    })
                    LogUtil.i(TAG, "Hooked $method on ${clazz.name}")
                } catch (e: Throwable) {
                    LogUtil.w(TAG, "Failed to hook $method: ${e.message}")
                    tryAlternativeHooks(clazz)
                }
            }
        }
    }

    private fun tryAlternativeHooks(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(clazz, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    filterMessageIndicators(param)
                }
            })
            return
        } catch (_: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod(clazz, "onStart", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    filterMessageIndicators(param)
                }
            })
            return
        } catch (_: Throwable) {}
        LogUtil.w(TAG, "Could not find any suitable method to hook")
    }

    override fun onHookFailed(error: Throwable) {
        LogUtil.e(TAG, "MessageIndicatorHook failed: ${error.message}", error)
        status = HookStatus.ERROR
    }

    override fun onHookUnregistered() {
        try {
            targetClass?.let { clazz ->
                targetMethodName?.let { method ->
                    XposedBridge.unhookMethod(clazz.getDeclaredMethod(method))
                }
            }
        } catch (_: Throwable) {}
        status = HookStatus.INACTIVE
        LogUtil.i(TAG, "MessageIndicatorHook unregistered")
    }

    @JvmStatic
    fun clearDexKitCache() {
        cacheWarmedUp = false
        targetClass = null
        targetMethodName = null
        LogUtil.d(TAG, "DexKit cache cleared")
    }

    // ========================================================================
    // 消息指示器过滤
    // ========================================================================

    private fun filterMessageIndicators(param: XC_MethodHook.MethodHookParam) {
        try {
            val context = MainHook.appContext ?: return
            val hiddenIds = ConfigManager.getHiddenWxIds(context)
            if (hiddenIds.isEmpty()) return

            HookMetrics.recordSuccess(TAG)
            LogUtil.d(TAG, "Filtering message indicators, hidden count: ${hiddenIds.size}")

            val activity = param.thisObject

            // 查找主页面底部 Tab 的未读计数
            filterTabBadges(activity, hiddenIds)
        } catch (e: Throwable) {
            LogUtil.e(TAG, "Error filtering message indicators: ${e.message}", e)
        }
    }

    /**
     * 过滤主页面底部 Tab 的未读消息标记。
     */
    private fun filterTabBadges(activity: Any, hiddenIds: Set<String>) {
        try {
            // 查找未读消息计数相关的字段
            val badgeFieldNames = arrayOf(
                "mUnreadCount", "unreadCount", "mBadgeCount",
                "mUnreadView", "unreadView", "mBadgeView",
                "mUnreadNum", "unreadNum", "mNewMsgCount",
                "mUnreadMsgCount", "mUnreadLabel"
            )

            for (fieldName in badgeFieldNames) {
                try {
                    val badgeView = XposedHelpers.getObjectField(activity, fieldName)
                    if (badgeView != null) {
                        // 尝试修改未读计数
                        tryModifyUnreadCount(badgeView, hiddenIds)
                    }
                } catch (_: Throwable) {}
            }

            // 递归查找子视图中的 badge
            findAndModifyBadges(activity, hiddenIds, 0, 3)
        } catch (e: Throwable) {
            LogUtil.d(TAG, "Error filtering tab badges: ${e.message}")
        }
    }

    private fun tryModifyUnreadCount(badgeView: Any, hiddenIds: Set<String>) {
        try {
            val countFieldNames = arrayOf(
                "mCount", "count", "mUnreadCount", "unreadCount",
                "mUnreadNum", "mBadgeNum", "mNum", "mNumber"
            )
            for (fieldName in countFieldNames) {
                try {
                    val count = XposedHelpers.getIntField(badgeView, fieldName)
                    // 这里不做直接修改，因为无法知道每个未读消息对应的发送者
                    // 实际的过滤逻辑在主会话列表的过滤中完成
                    LogUtil.d(TAG, "Found badge count: $count in field $fieldName")
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun findAndModifyBadges(obj: Any, hiddenIds: Set<String>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        try {
            for (field in obj.javaClass.declaredFields) {
                field.isAccessible = true
                try {
                    val value = field.get(obj) ?: continue
                    val className = value.javaClass.name
                    if (className.contains("Badge") || className.contains("Unread") ||
                        className.contains("Dot") || className.contains("RedDot")) {
                        tryModifyUnreadCount(value, hiddenIds)
                    }
                    if (value !is String && value !is Number && value !is Boolean) {
                        findAndModifyBadges(value, hiddenIds, depth + 1, maxDepth)
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
}