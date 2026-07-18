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
 * 会话长按菜单 Hook 模块
 *
 * 功能：拦截会话列表中的长按弹出菜单，提供快速隐藏/取消隐藏的选项。
 * 当用户长按一个会话时，识别该会话的好友 wxId，提供隐藏/取消隐藏的切换功能。
 *
 * 实现方式：
 * - Hook 会话列表的 onContextItemSelected 方法
 * - 识别当前选中的会话 wxId
 * - 在菜单中添加隐藏/取消隐藏选项
 */
object ConversationLongClickHook : HookLifecycleListener {

    private const val TAG = Constants.MODULE_TAG + ":ConversationLongClickHook"

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

        val conversationClass = MainHook.dexKitBridge.findClass {
            searchString = "conversation"
            searchPackage = Constants.WECHAT_UI_CONVERSATION
        }

        if (conversationClass != null) {
            targetClass = classLoader.loadClass(conversationClass.name)
            targetMethodName = "onCreate"
            cacheWarmedUp = true
            LogUtil.i(TAG, "DexKit found: ${conversationClass.name}")
        }
    }

    private fun initFallback(classLoader: ClassLoader) {
        val candidates = listOf(
            "${Constants.WECHAT_UI_CONVERSATION}.ConversationFragment",
            "${Constants.WECHAT_UI_CONVERSATION}.ConversationListUI",
            "${Constants.WECHAT_UI_CONVERSATION}.ConversationWithCacheAdapter",
            "com.tencent.mm.ui.LauncherUI",
            "com.tencent.mm.ui.conversation.MainUI"
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
        LogUtil.i(TAG, "ConversationLongClickHook registered (status: ${status.description})")
        targetClass?.let { clazz ->
            targetMethodName?.let { method ->
                try {
                    XposedHelpers.findAndHookMethod(clazz, method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            hookContextMenu(param)
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
                    hookContextMenu(param)
                }
            })
            return
        } catch (_: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod(clazz, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    hookContextMenu(param)
                }
            })
            return
        } catch (_: Throwable) {}
        LogUtil.w(TAG, "Could not find any suitable method to hook")
    }

    override fun onHookFailed(error: Throwable) {
        LogUtil.e(TAG, "ConversationLongClickHook failed: ${error.message}", error)
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
        LogUtil.i(TAG, "ConversationLongClickHook unregistered")
    }

    @JvmStatic
    fun clearDexKitCache() {
        cacheWarmedUp = false
        targetClass = null
        targetMethodName = null
        LogUtil.d(TAG, "DexKit cache cleared")
    }

    // ========================================================================
    // 长按菜单处理
    // ========================================================================

    private fun hookContextMenu(param: XC_MethodHook.MethodHookParam) {
        try {
            val context = MainHook.appContext ?: return
            HookMetrics.recordHookExecution(TAG)

            val activity = param.thisObject

            // 尝试 Hook onContextItemSelected 方法来拦截长按菜单
            try {
                XposedHelpers.findAndHookMethod(
                    activity.javaClass,
                    "onContextItemSelected",
                    android.view.MenuItem::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            handleConversationContextMenu(param)
                        }
                    }
                )
                LogUtil.d(TAG, "Context menu hooked for conversation")
            } catch (_: Throwable) {
                LogUtil.d(TAG, "Context menu not available for conversation")
            }

            // 尝试 Hook onCreateContextMenu 来在菜单中添加自定义选项
            try {
                XposedHelpers.findAndHookMethod(
                    activity.javaClass,
                    "onCreateContextMenu",
                    android.view.ContextMenu::class.java,
                    android.view.View::class.java,
                    android.view.ContextMenu.ContextMenuInfo::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            addCustomMenuItem(param)
                        }
                    }
                )
                LogUtil.d(TAG, "Create context menu hooked for conversation")
            } catch (_: Throwable) {
                LogUtil.d(TAG, "Create context menu not available")
            }
        } catch (e: Throwable) {
            LogUtil.e(TAG, "Error hooking context menu: ${e.message}", e)
        }
    }

    private fun handleConversationContextMenu(param: XC_MethodHook.MethodHookParam) {
        try {
            val menuItem = param.args[0] as? android.view.MenuItem ?: return
            val title = menuItem.title?.toString() ?: return
            val context = MainHook.appContext ?: return

            LogUtil.d(TAG, "Context menu selected: $title")

            // 从当前选中的会话中提取 wxId
            val selectedWxId = extractSelectedWxId(param.thisObject)
            if (selectedWxId == null) return

            // 根据菜单项标题判断是否为自定义选项
            when {
                title.contains("隐藏") || title.contains("Hide") -> {
                    ConfigManager.addHiddenWxId(context, selectedWxId)
                    LogUtil.i(TAG, "Hidden conversation via menu: $selectedWxId")
                }
                title.contains("取消隐藏") || title.contains("Unhide") || title.contains("显示") -> {
                    ConfigManager.removeHiddenWxId(context, selectedWxId)
                    LogUtil.i(TAG, "Unhidden conversation via menu: $selectedWxId")
                }
            }
        } catch (e: Throwable) {
            LogUtil.d(TAG, "Error handling context menu: ${e.message}")
        }
    }

    private fun addCustomMenuItem(param: XC_MethodHook.MethodHookParam) {
        try {
            val menu = param.args[0] as? android.view.ContextMenu ?: return
            val context = MainHook.appContext ?: return

            val selectedWxId = extractSelectedWxId(param.thisObject)
            if (selectedWxId == null) return

            val isHidden = ConfigManager.isHidden(context, selectedWxId)

            if (isHidden) {
                menu.add("取消隐藏")
            } else {
                menu.add("隐藏")
            }

            LogUtil.d(TAG, "Added custom menu item for: $selectedWxId (hidden=$isHidden)")
        } catch (e: Throwable) {
            LogUtil.d(TAG, "Error adding custom menu item: ${e.message}")
        }
    }

    // ========================================================================
    // wxId 提取
    // ========================================================================

    private fun extractSelectedWxId(activity: Any): String? {
        // 查找当前选中的会话条目
        val selectedItem = tryGetField(activity,
            "mSelectedItem", "selectedItem", "mCurrentItem",
            "currentItem", "mSelectedConversation", "selectedConversation",
            "mLongClickedItem", "longClickedItem"
        )
        if (selectedItem != null) {
            return extractWxId(selectedItem)
        }

        // 查找 ListView 的选中项
        val listView = findListView(activity)
        if (listView != null) {
            try {
                val selectedPosition = tryCallMethod(listView, "getSelectedItemPosition")
                if (selectedPosition is Int && selectedPosition >= 0) {
                    val adapter = findAdapter(listView)
                    if (adapter != null) {
                        val item = tryCallMethod(adapter, "getItem", selectedPosition)
                        if (item != null) {
                            return extractWxId(item)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        return null
    }

    private fun extractWxId(obj: Any): String? {
        val fieldNames = arrayOf(
            "field_username", "username", "wxId",
            "mUserName", "mWxId", "userName",
            "field_talker", "talker", "mTalker",
            "field_contactUsername", "contactUsername",
            "mContactUsername", "mFieldUsername",
            "field_conversationUsername", "conversationUsername",
            "a", "b", "c", "d", "e", "f", "g", "h",
            "i", "j", "k", "l", "m", "n", "o", "p",
            "wxid", "mWxid", "field_wxid",
            "encodeUserName", "mEncodeUserName"
        )

        for (fieldName in fieldNames) {
            val value = tryGetField(obj, fieldName)
            if (value is String && value.isNotEmpty() && value.startsWith("wxid_")) {
                return value
            }
        }

        val methodNames = arrayOf(
            "getUsername", "getWxId", "getTalker",
            "getFieldUsername", "getUserName", "getContactUsername",
            "getEncodeUserName", "getConversationUsername"
        )
        for (methodName in methodNames) {
            val value = tryCallMethod(obj, methodName)
            if (value is String && value.isNotEmpty() && value.startsWith("wxid_")) {
                return value
            }
        }

        return null
    }

    // ========================================================================
    // 查找方法
    // ========================================================================

    private fun findListView(activity: Any): Any? {
        val fieldNames = arrayOf(
            "mListView", "listView", "mRecyclerView", "recyclerView",
            "mConversationListView", "conversationListView",
            "mMainListView", "mSwipeRefreshLayout", "mRefreshLayout"
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

    // ========================================================================
    // 反射辅助方法
    // ========================================================================

    private fun tryGetField(obj: Any, vararg fieldNames: String): Any? {
        for (name in fieldNames) {
            try {
                return XposedHelpers.getObjectField(obj, name)
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun tryCallMethod(obj: Any, methodName: String, vararg args: Any): Any? {
        try {
            return XposedHelpers.callMethod(obj, methodName, *args)
        } catch (_: Throwable) {
            return null
        }
    }
}