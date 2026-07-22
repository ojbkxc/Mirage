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
 * 杂项隐藏 Hook 模块
 *
 * 功能：
 * a) 好友状态隐藏：拦截好友在线状态/状态显示
 * b) 聊天大小排序隐藏：拦截存储管理页面的聊天大小排序列表
 * c) 其他杂项列表中的隐藏
 *
 * 使用 ConfigManager.getOtherMiscHiddenIds 获取需要隐藏的好友列表。
 *
 * 修复要点：
 * - Hook onResume 确保数据已加载
 * - 递归查找 ListView 和 adapter
 * - 遍历所有字段查找 wxId
 */
object MiscHook : HookLifecycleListener {

    private const val TAG = Constants.MODULE_TAG + ":MiscHook"

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

        // 尝试查找存储管理相关类
        val cleanClass = MainHook.dexKitBridge.findClass {
            matcher {
                usingStrings = listOf("clean")
            }
        }.firstOrNull()

        if (cleanClass != null) {
            targetClass = classLoader.loadClass(cleanClass.name)
            targetMethodName = "onResume"
            cacheWarmedUp = true
            LogUtil.i(TAG, "DexKit found clean: ${cleanClass.name}")
            return
        }

        // 尝试查找聊天相关类
        val chattingClass = MainHook.dexKitBridge.findClass {
            searchPackages = listOf(Constants.WECHAT_CHATTING_COMPONENT)
            matcher {
                usingStrings = listOf("chatting")
            }
        }.firstOrNull()
        if (chattingClass != null) {
            targetClass = classLoader.loadClass(chattingClass.name)
            targetMethodName = "onResume"
            cacheWarmedUp = true
            LogUtil.i(TAG, "DexKit found chatting: ${chattingClass.name}")
        }
    }

    private fun initFallback(classLoader: ClassLoader) {
        val candidates = listOf(
            "com.tencent.mm.plugin.clean.ui.CleanUI",
            "com.tencent.mm.plugin.clean.ui.StorageCleanUI",
            "com.tencent.mm.ui.tools.MMListPopupWindow",
            "${Constants.WECHAT_CHATTING_COMPONENT}.ChattingUIFragment",
            "com.tencent.mm.ui.chatting.ChattingUI",
            "com.tencent.mm.ui.tools.NewFitSystemWindowView"
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
        LogUtil.i(TAG, "MiscHook registered (status: ${status.description})")
        targetClass?.let { clazz ->
            targetMethodName?.let { method ->
                try {
                    XposedHelpers.findAndHookMethod(clazz, method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            performFiltering(param)
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
                    performFiltering(param)
                }
            })
            return
        } catch (_: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod(clazz, "onStart", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    performFiltering(param)
                }
            })
            return
        } catch (_: Throwable) {}
        LogUtil.w(TAG, "Could not find any suitable method to hook")
    }

    override fun onHookFailed(error: Throwable) {
        LogUtil.e(TAG, "MiscHook failed: ${error.message}", error)
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
        LogUtil.i(TAG, "MiscHook unregistered")
    }

    @JvmStatic
    fun clearDexKitCache() {
        cacheWarmedUp = false
        targetClass = null
        targetMethodName = null
        LogUtil.d(TAG, "DexKit cache cleared")
    }

    // ========================================================================
    // 核心过滤逻辑
    // ========================================================================

    private fun performFiltering(param: XC_MethodHook.MethodHookParam) {
        try {
            val context = MainHook.appContext ?: return
            val hiddenIds = ConfigManager.getOtherMiscHiddenIds(context)
            if (hiddenIds.isEmpty()) return

            HookMetrics.recordSuccess(TAG)
            LogUtil.d(TAG, "Filtering misc items, hidden count: ${hiddenIds.size}")

            filterListViewItems(param.thisObject, hiddenIds)
        } catch (e: Throwable) {
            LogUtil.e(TAG, "Error in performFiltering: ${e.message}", e)
        }
    }

    private fun filterListViewItems(activity: Any, hiddenIds: Set<String>) {
        val listView = findListView(activity) ?: run {
            LogUtil.d(TAG, "No list view found in misc activity")
            return
        }

        val adapter = findAdapter(listView) ?: run {
            LogUtil.d(TAG, "No adapter found in misc list")
            return
        }

        val dataList = findMutableListField(adapter) ?: run {
            LogUtil.d(TAG, "Misc data list not found")
            return
        }

        var removedCount = 0

        val iterator = dataList.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next() ?: continue
            val wxId = extractWxId(item)
            if (wxId != null && wxId in hiddenIds) {
                iterator.remove()
                removedCount++
                LogUtil.d(TAG, "Removed hidden misc item: $wxId")
            }
        }

        if (removedCount > 0) {
            LogUtil.i(TAG, "Removed $removedCount hidden items from misc list")
            tryCallMethod(adapter, "notifyDataSetChanged")
        }
    }

    // ========================================================================
    // 查找方法
    // ========================================================================

    private fun findListView(activity: Any): Any? {
        val listView = tryGetField(activity,
            "mListView", "listView", "mRecyclerView", "recyclerView",
            "mStorageListView", "storageListView",
            "mChatListView", "chatListView", "mCleanListView",
            "mSwipeRefreshLayout", "mRefreshLayout"
        )
        if (listView != null) return listView
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
        return tryGetField(listView, "mAdapter", "adapter", "mListAdapter", "listAdapter")
            ?: tryCallMethod(listView, "getAdapter")
    }

    private fun findMutableListField(obj: Any): MutableList<*>? {
        try {
            for (field in obj.javaClass.declaredFields) {
                field.isAccessible = true
                try {
                    val value = field.get(obj)
                    if (value is MutableList<*>) return value
                } catch (_: Throwable) {}
            }
            var superClass = obj.javaClass.superclass
            while (superClass != null && superClass != Any::class.java) {
                for (field in superClass.declaredFields) {
                    field.isAccessible = true
                    try {
                        val value = field.get(obj)
                        if (value is MutableList<*>) return value
                    } catch (_: Throwable) {}
                }
                superClass = superClass.superclass
            }
        } catch (_: Throwable) {}
        return null
    }

    // ========================================================================
    // wxId 提取
    // ========================================================================

    private fun extractWxId(obj: Any): String? {
        val fieldNames = arrayOf(
            "field_username", "username", "wxId",
            "mUserName", "mWxId", "userName",
            "field_talker", "talker", "mTalker",
            "field_contactUsername", "contactUsername",
            "mContactUsername", "mFieldUsername",
            "a", "b", "c", "d", "e", "f", "g", "h",
            "i", "j", "k", "l", "m", "n", "o", "p",
            "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
            "wxid", "mWxid", "field_wxid",
            "encodeUserName", "mEncodeUserName",
            "field_encodeUserName"
        )

        for (fieldName in fieldNames) {
            val value = tryGetField(obj, fieldName)
            if (value is String && value.isNotEmpty() && isValidWxId(value)) {
                return value
            }
        }

        val methodNames = arrayOf(
            "getUsername", "getWxId", "getTalker",
            "getFieldUsername", "getUserName", "getContactUsername",
            "getEncodeUserName"
        )
        for (methodName in methodNames) {
            val value = tryCallMethod(obj, methodName)
            if (value is String && value.isNotEmpty() && isValidWxId(value)) {
                return value
            }
        }

        val nestedFields = arrayOf(
            "field_contact", "contact", "mContact",
            "field_userInfo", "userInfo", "mUserInfo",
            "field_user", "user", "mUser"
        )
        for (fieldName in nestedFields) {
            val nested = tryGetField(obj, fieldName)
            if (nested != null && nested !== obj) {
                val wxId = extractWxId(nested)
                if (wxId != null) return wxId
            }
        }

        return findWxIdInAllFields(obj)
    }

    private fun findWxIdInAllFields(obj: Any): String? {
        try {
            for (field in obj.javaClass.declaredFields) {
                field.isAccessible = true
                try {
                    val value = field.get(obj)
                    if (value is String && value.isNotEmpty() && isValidWxId(value)) {
                        return value
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun isValidWxId(value: String): Boolean {
        return value.startsWith("wxid_") ||
               value.startsWith("gh_") ||
               (value.length in 6..64 && value.matches(Regex("^[a-zA-Z0-9_\\-@]+$")) &&
                !value.contains(".") && !value.contains("/"))
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