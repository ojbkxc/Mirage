package wx.mirage.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import wx.mirage.Constants
import wx.mirage.MainHook
import wx.mirage.config.HookStatus
import wx.mirage.lifecycle.HookLifecycleListener
import wx.mirage.util.HookMetrics
import wx.mirage.util.LogUtil

/**
 * 朋友圈广告移除 Hook 模块
 *
 * 功能：拦截朋友圈时间线加载，移除广告条目。
 * 通过检查广告标识字段来识别并移除广告内容。
 *
 * 实现方式：
 * - Hook 朋友圈 Adapter 的 getView 方法
 * - 检查每个条目的广告标识
 * - 移除广告相关条目
 */
object MomentsAdRemovalHook : HookLifecycleListener {

    private const val TAG = Constants.MODULE_TAG + ":MomentsAdRemovalHook"

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

        val snsClass = MainHook.dexKitBridge.findClass {
            searchPackages = listOf(Constants.WECHAT_PLUGIN_SNS)
            matcher {
                usingStrings = listOf("sns")
            }
        }.firstOrNull()

        if (snsClass != null) {
            targetClass = classLoader.loadClass(snsClass.name)
            targetMethodName = "onResume"
            cacheWarmedUp = true
            LogUtil.i(TAG, "DexKit found: ${snsClass.name}")
        }
    }

    private fun initFallback(classLoader: ClassLoader) {
        val candidates = listOf(
            "${Constants.WECHAT_PLUGIN_SNS}.ui.SnsTimeLineUI",
            "${Constants.WECHAT_PLUGIN_SNS}.ui.SnsTimeLineFragment",
            "${Constants.WECHAT_PLUGIN_SNS}.adapter.SnsTimeLineAdapter",
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
        LogUtil.i(TAG, "MomentsAdRemovalHook registered (status: ${status.description})")
        targetClass?.let { clazz ->
            targetMethodName?.let { method ->
                try {
                    XposedHelpers.findAndHookMethod(clazz, method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            removeAds(param)
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
                    removeAds(param)
                }
            })
            return
        } catch (_: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod(clazz, "onStart", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    removeAds(param)
                }
            })
            return
        } catch (_: Throwable) {}
        LogUtil.w(TAG, "Could not find any suitable method to hook")
    }

    override fun onHookFailed(error: Throwable) {
        LogUtil.e(TAG, "MomentsAdRemovalHook failed: ${error.message}", error)
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
        LogUtil.i(TAG, "MomentsAdRemovalHook unregistered")
    }

    @JvmStatic
    fun clearDexKitCache() {
        cacheWarmedUp = false
        targetClass = null
        targetMethodName = null
        LogUtil.d(TAG, "DexKit cache cleared")
    }

    // ========================================================================
    // 广告移除逻辑
    // ========================================================================

    private fun removeAds(param: XC_MethodHook.MethodHookParam) {
        try {
            HookMetrics.recordSuccess(TAG)

            val activity = param.thisObject

            // 查找 ListView/RecyclerView
            val listView = findListView(activity) ?: return
            val adapter = findAdapter(listView) ?: return

            // 查找 adapter 数据列表
            val dataList = findMutableListField(adapter) ?: return

            var removedCount = 0
            val iterator = dataList.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next() ?: continue
                if (isAdItem(item)) {
                    iterator.remove()
                    removedCount++
                    LogUtil.d(TAG, "Removed an ad from moments")
                }
            }

            if (removedCount > 0) {
                LogUtil.i(TAG, "Removed $removedCount ads from moments")
                tryCallMethod(adapter, "notifyDataSetChanged")
            }
        } catch (e: Throwable) {
            LogUtil.e(TAG, "Error removing ads: ${e.message}", e)
        }
    }

    /**
     * 检查朋友圈条目是否为广告。
     */
    private fun isAdItem(item: Any): Boolean {
        // 0. 检查类型字段
        val type = tryGetField(item,
            "field_type", "type", "mType", "snsType",
            "field_snsType", "msgType", "field_msgType",
            "a", "b", "c", "d", "e", "f", "g", "h"
        )
        if (type is Int) {
            // 微信朋友圈广告类型通常为特定值
            if (type == 15 || type == 16 || type == 17 || type == 20) return true
        }

        // 1. 检查广告标识字段
        val adFields = arrayOf(
            "field_isAdvertisement", "isAdvertisement",
            "mIsAd", "isAd", "field_isAd",
            "adInfo", "field_adInfo", "mAdInfo",
            "field_advertisement", "advertisement",
            "adType", "field_adType", "mAdType",
            "field_adXml", "adXml", "mAdXml"
        )
        for (fieldName in adFields) {
            try {
                val value = XposedHelpers.getObjectField(item, fieldName)
                if (value != null) return true
            } catch (_: Throwable) {}
        }

        // 2. 检查内容字段是否包含广告相关字符串
        try {
            val content = tryGetField(item, "field_content", "content", "mContent", "field_desc", "desc")
            if (content is String && (content.contains("广告") || content.contains("推广") ||
                    content.contains("ad") || content.contains("sponsored"))) {
                return true
            }
        } catch (_: Throwable) {}

        return false
    }

    // ========================================================================
    // 查找方法
    // ========================================================================

    private fun findListView(activity: Any): Any? {
        val listView = tryGetField(activity,
            "mListView", "listView", "mRecyclerView", "recyclerView",
            "mSnsListView", "snsListView", "mSnsRecyclerView",
            "mTimelineListView", "mSwipeRefreshLayout", "mRefreshLayout"
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