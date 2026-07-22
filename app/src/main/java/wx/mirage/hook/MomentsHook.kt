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
 * 朋友圈/相册 Hook 模块
 *
 * 功能：
 * a) 拦截朋友圈时间线加载，隐藏被标记好友发布的动态
 * b) 过滤隐藏好友的评论和点赞
 *
 * 修复要点：
 * - Hook onResume 确保数据已加载
 * - 递归查找 ListView 和 adapter
 * - 遍历所有字段查找 wxId 和子列表
 */
object MomentsHook : HookLifecycleListener {

    private const val TAG = Constants.MODULE_TAG + ":MomentsHook"

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

        val momentsClass = MainHook.dexKitBridge.findClass {
            searchPackages = listOf(Constants.WECHAT_PLUGIN_SNS)
            matcher {
                usingStrings = listOf("sns")
            }
        }.firstOrNull()

        if (momentsClass != null) {
            targetClass = classLoader.loadClass(momentsClass.name)
            targetMethodName = "onResume"
            cacheWarmedUp = true
            LogUtil.i(TAG, "DexKit found: ${momentsClass.name}")
        }
    }

    private fun initFallback(classLoader: ClassLoader) {
        val candidates = listOf(
            "${Constants.WECHAT_PLUGIN_SNS}.ui.SnsTimeLineUI",
            "${Constants.WECHAT_PLUGIN_SNS}.ui.SnsTimeLineFragment",
            "${Constants.WECHAT_PLUGIN_SNS}.adapter.SnsTimeLineAdapter",
            "${Constants.WECHAT_PLUGIN_SNS}.ui.SnsCommentDetailUI",
            "com.tencent.mm.plugin.sns.ui.SnsActivity"
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
        LogUtil.i(TAG, "MomentsHook registered (status: ${status.description})")
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
        LogUtil.e(TAG, "MomentsHook failed: ${error.message}", error)
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
        LogUtil.i(TAG, "MomentsHook unregistered")
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
            // 检查临时取消隐藏状态
            if (TempMomentsUnhideHook.isUnfiltered) {
                LogUtil.d(TAG, "Moments unfiltered mode active, skipping filter")
                return
            }

            val context = MainHook.appContext ?: return
            val hiddenIds = ConfigManager.getMomentsHiddenIds(context)
            if (hiddenIds.isEmpty()) return

            HookMetrics.recordSuccess(TAG)
            LogUtil.d(TAG, "Filtering moments, hidden count: ${hiddenIds.size}")

            filterMomentsTimeline(param.thisObject, hiddenIds)
        } catch (e: Throwable) {
            LogUtil.e(TAG, "Error in performFiltering: ${e.message}", e)
        }
    }

    private fun filterMomentsTimeline(activity: Any, hiddenIds: Set<String>) {
        val listView = findListView(activity) ?: run {
            LogUtil.d(TAG, "No list view found in moments activity")
            return
        }

        val adapter = findAdapter(listView) ?: run {
            LogUtil.d(TAG, "No adapter found in moments list")
            return
        }

        val dataList = findMutableListField(adapter) ?: run {
            LogUtil.d(TAG, "Moments data list not found")
            return
        }

        var removedCount = 0

        val iterator = dataList.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next() ?: continue
            val posterWxId = extractPosterWxId(item)
            if (posterWxId != null && posterWxId in hiddenIds) {
                iterator.remove()
                removedCount++
                LogUtil.d(TAG, "Removed hidden moment from: $posterWxId")
            } else {
                // 过滤评论和点赞
                filterCommentsAndLikes(item, hiddenIds)
            }
        }

        if (removedCount > 0) {
            LogUtil.i(TAG, "Removed $removedCount hidden moments from timeline")
            tryCallMethod(adapter, "notifyDataSetChanged")
        }
    }

    /**
     * 过滤朋友圈动态中的评论和点赞。
     */
    private fun filterCommentsAndLikes(snsItem: Any, hiddenIds: Set<String>) {
        // 查找所有子列表（评论、点赞等）
        try {
            for (field in snsItem.javaClass.declaredFields) {
                field.isAccessible = true
                try {
                    val value = field.get(snsItem)
                    if (value is MutableList<*>) {
                        val iterator = value.iterator()
                        var removed = 0
                        while (iterator.hasNext()) {
                            val subItem = iterator.next() ?: continue
                            val wxId = extractWxId(subItem)
                            if (wxId != null && wxId in hiddenIds) {
                                iterator.remove()
                                removed++
                            }
                        }
                        if (removed > 0) {
                            LogUtil.d(TAG, "Removed $removed hidden items from ${field.name}")
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /**
     * 从朋友圈动态对象中提取发布者 wxId。
     */
    private fun extractPosterWxId(obj: Any): String? {
        // 先尝试标准提取
        val wxId = extractWxId(obj)
        if (wxId != null) return wxId

        // 尝试从嵌套的 user info 对象中提取
        val nestedFields = arrayOf(
            "field_userInfo", "userInfo", "mUserInfo",
            "field_snsInfo", "snsInfo", "mSnsInfo",
            "field_poster", "poster", "mPoster",
            "field_owner", "owner", "mOwner",
            "field_creater", "creater", "mCreater"
        )
        for (fieldName in nestedFields) {
            val nested = tryGetField(obj, fieldName)
            if (nested != null && nested !== obj) {
                val result = extractWxId(nested)
                if (result != null) return result
            }
        }
        return null
    }

    // ========================================================================
    // 查找方法
    // ========================================================================

    private fun findListView(activity: Any): Any? {
        val listView = tryGetField(activity,
            "mListView", "listView", "mRecyclerView", "recyclerView",
            "mSnsListView", "snsListView", "mSnsRecyclerView",
            "mTimelineListView", "timelineListView",
            "mSwipeRefreshLayout", "mRefreshLayout",
            "mPullToRefreshLayout"
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
            "field_snsId", "snsId", "field_userName",
            "field_poster", "poster",
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
            "getUsername", "getWxId", "getFieldUsername",
            "getUserName", "getTalker", "getContactUsername",
            "getEncodeUserName", "getSnsId"
        )
        for (methodName in methodNames) {
            val value = tryCallMethod(obj, methodName)
            if (value is String && value.isNotEmpty() && isValidWxId(value)) {
                return value
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