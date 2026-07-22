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
import wx.mirage.manager.TempUnhideManager
import wx.mirage.util.HookMetrics
import wx.mirage.util.LogUtil

/**
 * 朋友圈临时取消隐藏 Hook 模块
 *
 * 功能：在朋友圈页面提供临时取消隐藏的功能。
 * 当用户进入朋友圈时，可以临时显示所有被隐藏好友的动态，
 * 离开朋友圈时自动恢复隐藏状态。
 *
 * 使用 TempUnhideManager 来管理临时取消隐藏的状态。
 */
object TempMomentsUnhideHook : HookLifecycleListener {

    private const val TAG = Constants.MODULE_TAG + ":TempMomentsUnhideHook"

    @Volatile
    var status: HookStatus = HookStatus.INACTIVE
        private set

    @Volatile
    private var cacheWarmedUp: Boolean = false

    @Volatile
    var isUnfiltered: Boolean = false
        private set

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

        val snsClass = MainHook.dexKitBridge.findClass {
            searchPackages = listOf(Constants.WECHAT_PLUGIN_SNS)
            matcher {
                usingStrings = listOf("sns")
            }
        }.firstOrNull()

        if (snsClass != null) {
            targetClass = classLoader.loadClass(snsClass.name)
            targetMethodName = "onCreate"
            cacheWarmedUp = true
            LogUtil.i(TAG, "DexKit found: ${snsClass.name}")
        }
    }

    private fun initFallback(classLoader: ClassLoader) {
        val candidates = listOf(
            "${Constants.WECHAT_PLUGIN_SNS}.ui.SnsTimeLineUI",
            "${Constants.WECHAT_PLUGIN_SNS}.ui.SnsCommentDetailUI",
            "${Constants.WECHAT_PLUGIN_SNS}.ui.SnsActivity",
            "com.tencent.mm.plugin.sns.ui.SnsTimeLineFragment"
        )

        for (className in candidates) {
            try {
                targetClass = classLoader.loadClass(className)
                targetMethodName = "onCreate"
                LogUtil.i(TAG, "Fallback found: $className")
                return
            } catch (_: ClassNotFoundException) {
            }
        }
    }

    override fun onHookRegistered() {
        LogUtil.i(TAG, "TempMomentsUnhideHook registered (status: ${status.description})")
        targetClass?.let { clazz ->
            targetMethodName?.let { method ->
                try {
                    XposedHelpers.findAndHookMethod(clazz, method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            onMomentsEnter(param)
                        }
                    })
                    LogUtil.i(TAG, "Hooked $method on ${clazz.name}")
                } catch (e: Throwable) {
                    LogUtil.w(TAG, "Failed to hook $method: ${e.message}")
                    tryAlternativeHooks(clazz)
                }
            }

            // 同时 Hook onDestroy 来检测离开朋友圈
            try {
                XposedHelpers.findAndHookMethod(clazz, "onDestroy", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        onMomentsLeave(param)
                    }
                })
                LogUtil.i(TAG, "Hooked onDestroy on ${clazz.name}")
            } catch (e: Throwable) {
                LogUtil.d(TAG, "Could not hook onDestroy: ${e.message}")
            }
        }
    }

    private fun tryAlternativeHooks(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(clazz, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    onMomentsEnter(param)
                }
            })
            return
        } catch (_: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod(clazz, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    onMomentsEnter(param)
                }
            })
            return
        } catch (_: Throwable) {}
        LogUtil.w(TAG, "Could not find any suitable method to hook")
    }

    override fun onHookFailed(error: Throwable) {
        LogUtil.e(TAG, "TempMomentsUnhideHook failed: ${error.message}", error)
        status = HookStatus.ERROR
    }

    override fun onHookUnregistered() {
        try {
            targetClass?.let { clazz ->
                targetMethodName?.let { method ->
                    XposedBridge.unhookMethod(clazz.getDeclaredMethod(method), null)
                }
            }
        } catch (_: Throwable) {}
        status = HookStatus.INACTIVE
        LogUtil.i(TAG, "TempMomentsUnhideHook unregistered")
    }

    @JvmStatic
    fun clearDexKitCache() {
        cacheWarmedUp = false
        targetClass = null
        targetMethodName = null
        LogUtil.d(TAG, "DexKit cache cleared")
    }

    // ========================================================================
    // 进入/离开朋友圈处理
    // ========================================================================

    private fun onMomentsEnter(param: XC_MethodHook.MethodHookParam) {
        try {
            val context = MainHook.appContext ?: return
            HookMetrics.recordSuccess(TAG)

            val hiddenIds = ConfigManager.getMomentsHiddenIds(context)
            if (hiddenIds.isEmpty()) return

            // 检查 TempUnhideManager 是否处于临时取消隐藏状态
            if (TempUnhideManager.getTempUnhiddenIds().isNotEmpty()) {
                isUnfiltered = true
                LogUtil.i(TAG, "Moments unfiltered mode enabled (temp unhide active)")
            } else {
                isUnfiltered = false
                LogUtil.d(TAG, "Moments filtered mode (normal)")
            }
        } catch (e: Throwable) {
            LogUtil.e(TAG, "Error on moments enter: ${e.message}", e)
        }
    }

    private fun onMomentsLeave(param: XC_MethodHook.MethodHookParam) {
        try {
            isUnfiltered = false
            LogUtil.d(TAG, "Moments unfiltered mode disabled (leaving)")
        } catch (e: Throwable) {
            LogUtil.e(TAG, "Error on moments leave: ${e.message}", e)
        }
    }
}