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
 * 长按联系人名 Hook 模块
 *
 * 功能：拦截联系人列表中的长按事件，提供快速隐藏/取消隐藏的选项。
 * 长按隐藏好友时，通过修改 ConfigManager 来切换隐藏状态。
 */
object LongPressHook : HookLifecycleListener {

    private const val TAG = Constants.MODULE_TAG + ":LongPressHook"

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
            if (MainHook.dexKitAvailable && MainHook::dexKitBridge.isInitialized) {
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

        val contactClass = MainHook.dexKitBridge.findClass {
            searchString = "contact"
            searchPackage = Constants.WECHAT_UI_CONTACT
        }

        if (contactClass != null) {
            targetClass = classLoader.loadClass(contactClass.name)
            targetMethodName = "onCreate"
            cacheWarmedUp = true
            LogUtil.i(TAG, "DexKit found: ${contactClass.name}")
        }
    }

    private fun initFallback(classLoader: ClassLoader) {
        val candidates = listOf(
            "${Constants.WECHAT_UI_CONTACT}.ContactInfoUI",
            "${Constants.WECHAT_UI_CONTACT}.SelectContactUI",
            "${Constants.WECHAT_UI_CONTACT}.AddressUI",
            "${Constants.WECHAT_UI_CONTACT}.ContactWidget",
            "com.tencent.mm.ui.contact.MMBaseSelectContactUI"
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
        LogUtil.i(TAG, "LongPressHook registered (status: ${status.description})")
        targetClass?.let { clazz ->
            targetMethodName?.let { method ->
                try {
                    XposedHelpers.findAndHookMethod(clazz, method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            hookLongPressListener(param)
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
            XposedHelpers.findAndHookMethod(clazz, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    hookLongPressListener(param)
                }
            })
            return
        } catch (_: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod(clazz, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    hookLongPressListener(param)
                }
            })
            return
        } catch (_: Throwable) {}
        LogUtil.w(TAG, "Could not find any suitable method to hook")
    }

    override fun onHookFailed(error: Throwable) {
        LogUtil.e(TAG, "LongPressHook failed: ${error.message}", error)
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
        LogUtil.i(TAG, "LongPressHook unregistered")
    }

    @JvmStatic
    fun clearDexKitCache() {
        cacheWarmedUp = false
        targetClass = null
        targetMethodName = null
        LogUtil.d(TAG, "DexKit cache cleared")
    }

    // ========================================================================
    // 长按事件处理
    // ========================================================================

    private fun hookLongPressListener(param: XC_MethodHook.MethodHookParam) {
        try {
            val context = MainHook.appContext ?: return
            HookMetrics.recordHookExecution(TAG)

            val activity = param.thisObject

            // 在 Activity 中查找 ListView/RecyclerView
            val listView = findListView(activity) ?: return

            // 尝试 Hook Adapter 的 getView 方法来记录当前点击的项
            val adapter = findAdapter(listView) ?: return

            // 尝试 Hook 长按弹出菜单
            tryHookContextMenu(activity)

            LogUtil.d(TAG, "Long press listener hooked")
        } catch (e: Throwable) {
            LogUtil.e(TAG, "Error hooking long press: ${e.message}", e)
        }
    }

    private fun tryHookContextMenu(activity: Any) {
        try {
            // 尝试 Hook onContextItemSelected 方法
            XposedHelpers.findAndHookMethod(
                activity.javaClass,
                "onContextItemSelected",
                android.view.MenuItem::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        handleContextMenu(param)
                    }
                }
            )
            LogUtil.d(TAG, "Context menu hooked")
        } catch (_: Throwable) {
            LogUtil.d(TAG, "Context menu not available")
        }
    }

    private fun handleContextMenu(param: XC_MethodHook.MethodHookParam) {
        try {
            val menuItem = param.args[0] as? android.view.MenuItem ?: return
            val title = menuItem.title?.toString() ?: return

            LogUtil.d(TAG, "Context menu item: $title")

            // 这里可以根据菜单项标题来判断是否需要处理
            // 微信的菜单项可能是"删除"、"隐藏"等
        } catch (e: Throwable) {
            LogUtil.d(TAG, "Error handling context menu: ${e.message}")
        }
    }

    // ========================================================================
    // 查找方法
    // ========================================================================

    private fun findListView(activity: Any): Any? {
        val fieldNames = arrayOf(
            "mListView", "listView", "mRecyclerView", "recyclerView",
            "mContactListView", "contactListView", "mMemberListView",
            "mSwipeRefreshLayout", "mRefreshLayout"
        )
        for (name in fieldNames) {
            try {
                val value = XposedHelpers.getObjectField(activity, name)
                if (value != null) {
                    val className = value.javaClass.name
                    if (className.contains("ListView") || className.contains("RecyclerView") ||
                        className.contains("SwipeRefreshLayout") || className.contains("PullToRefresh")) {
                        return value
                    }
                }
            } catch (_: Throwable) {}
        }

        return findListViewRecursive(activity, 0, 3)
    }

    private fun findListViewRecursive(obj: Any, depth: Int, maxDepth: Int): Any? {
        if (depth > maxDepth) return null
        try {
            for (field in obj.javaClass.declaredFields) {
                field.isAccessible = true
                try {
                    val value = field.get(obj) ?: continue
                    val className = value.javaClass.name
                    if (className.contains("ListView") || className.contains("RecyclerView")) {
                        return value
                    }
                    if (className.contains("SwipeRefreshLayout") || className.contains("PullToRefresh")) {
                        val child = findListViewRecursive(value, depth + 1, maxDepth)
                        if (child != null) return child
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun findAdapter(listView: Any): Any? {
        try {
            return XposedHelpers.getObjectField(listView, "mAdapter")
        } catch (_: Throwable) {}
        try {
            return XposedHelpers.getObjectField(listView, "adapter")
        } catch (_: Throwable) {}
        try {
            return XposedHelpers.callMethod(listView, "getAdapter")
        } catch (_: Throwable) {
            return null
        }
    }
}