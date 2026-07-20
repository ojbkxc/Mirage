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
 * 会话列表 Hook 模块
 *
 * 功能：
 * a) 隐藏被标记好友的会话（hideChatHistory）
 * b) 主页伪装：替换被标记好友的显示名称和头像（disguiseMainPage）
 *
 * 修复要点：
 * - Hook onResume 确保数据已加载
 * - 递归查找 ListView/RecyclerView 和 adapter
 * - 遍历所有字段查找 wxId 和可修改的显示字段
 */
object ConversationHook : HookLifecycleListener {

    private const val TAG = Constants.MODULE_TAG + ":ConversationHook"

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

        val conversationClass = MainHook.dexKitBridge.findClass {
            searchString = "conversation"
            searchPackage = Constants.WECHAT_UI_CONVERSATION
        }

        if (conversationClass != null) {
            targetClass = classLoader.loadClass(conversationClass.name)
            targetMethodName = "onResume"
            cacheWarmedUp = true
            LogUtil.i(TAG, "DexKit found: ${conversationClass.name}")
        }
    }

    private fun initFallback(classLoader: ClassLoader) {
        val candidates = listOf(
            "${Constants.WECHAT_UI_CONVERSATION}.ConversationFragment",
            "${Constants.WECHAT_UI_CONVERSATION}.ConversationListUI",
            "${Constants.WECHAT_UI_CONVERSATION}.ConversationWithCacheAdapter",
            "${Constants.WECHAT_UI_CONVERSATION}.BaseConversationUI",
            "com.tencent.mm.ui.LauncherUI",
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
        LogUtil.i(TAG, "ConversationHook registered (status: ${status.description})")
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
            LogUtil.i(TAG, "Hooked onResume (alternative)")
            return
        } catch (_: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod(clazz, "onStart", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    performFiltering(param)
                }
            })
            LogUtil.i(TAG, "Hooked onStart (alternative)")
            return
        } catch (_: Throwable) {}
        LogUtil.w(TAG, "Could not find any suitable method to hook")
    }

    override fun onHookFailed(error: Throwable) {
        LogUtil.e(TAG, "ConversationHook failed: ${error.message}", error)
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
        LogUtil.i(TAG, "ConversationHook unregistered")
    }

    @JvmStatic
    fun clearDexKitCache() {
        cacheWarmedUp = false
        targetClass = null
        targetMethodName = null
        LogUtil.d(TAG, "DexKit cache cleared")
    }

    // ========================================================================
    // 核心过滤与伪装逻辑
    // ========================================================================

    private fun performFiltering(param: XC_MethodHook.MethodHookParam) {
        try {
            val context = MainHook.appContext ?: return
            val chatHistoryHiddenIds = ConfigManager.getChatHistoryHiddenIds(context)
            val disguiseMap = ConfigManager.getDisguiseMap(context)

            if (chatHistoryHiddenIds.isEmpty() && disguiseMap.isEmpty()) return

            HookMetrics.recordSuccess(TAG)
            LogUtil.d(TAG, "Filtering: hidden=${chatHistoryHiddenIds.size}, disguise=${disguiseMap.size}")

            processConversationList(param.thisObject, chatHistoryHiddenIds, disguiseMap)
        } catch (e: Throwable) {
            LogUtil.e(TAG, "Error in performFiltering: ${e.message}", e)
        }
    }

    private fun processConversationList(
        activity: Any,
        hiddenIds: Set<String>,
        disguiseMap: Map<String, String>
    ) {
        val listView = findListView(activity) ?: run {
            LogUtil.d(TAG, "No list view found in conversation activity")
            return
        }

        val adapter = findAdapter(listView) ?: run {
            LogUtil.d(TAG, "No adapter found in conversation list")
            return
        }

        val dataList = findMutableListField(adapter) ?: run {
            LogUtil.d(TAG, "Conversation data list not found")
            return
        }

        var removedCount = 0
        var disguisedCount = 0

        val iterator = dataList.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next() ?: continue
            val wxId = extractWxId(item) ?: continue

            when {
                wxId in hiddenIds -> {
                    iterator.remove()
                    removedCount++
                    LogUtil.d(TAG, "Removed hidden conversation: $wxId")
                }
                wxId in disguiseMap -> {
                    val targetId = disguiseMap[wxId] ?: continue
                    disguiseConversationItem(item, targetId)
                    disguisedCount++
                    LogUtil.d(TAG, "Disguised conversation: $wxId -> $targetId")
                }
            }
        }

        if (removedCount > 0 || disguisedCount > 0) {
            LogUtil.i(TAG, "Done: removed=$removedCount, disguised=$disguisedCount")
            tryCallMethod(adapter, "notifyDataSetChanged")
        }
    }

    /**
     * 伪装会话列表项：替换显示名称和头像。
     */
    private fun disguiseConversationItem(item: Any, targetId: String) {
        try {
            // 尝试修改所有可能的显示名称字段
            val displayNameFields = arrayOf(
                "field_conversationNickname", "conversationNickname",
                "mNickName", "nickName", "field_nickname",
                "mDisplayName", "displayName", "field_displayName",
                "mConNickName", "conNickName", "field_conNickName",
                "mRemark", "remark", "field_remark",
                "mAlias", "alias", "field_alias",
                "mChattingName", "chattingName",
                "mConversationDisplayName", "conversationDisplayName"
            )

            var modified = false
            for (fieldName in displayNameFields) {
                try {
                    XposedHelpers.setObjectField(item, fieldName, targetId)
                    modified = true
                    break
                } catch (_: Throwable) {}
            }

            // 如果字段方式失败，尝试 setter 方法
            if (!modified) {
                val setterMethods = arrayOf(
                    "setNickName", "setDisplayName", "setConversationNickname",
                    "setRemark", "setAlias", "setConNickName"
                )
                for (methodName in setterMethods) {
                    try {
                        XposedHelpers.callMethod(item, methodName, targetId)
                        modified = true
                        break
                    } catch (_: Throwable) {}
                }
            }

            // 尝试修改所有可能的头像字段
            val avatarFields = arrayOf(
                "field_avatarUrl", "avatarUrl", "mAvatarUrl",
                "mAvatar", "field_avatar", "avatar",
                "mAvatarPath", "avatarPath", "field_avatarPath",
                "mHeadImgUrl", "headImgUrl", "field_headImgUrl",
                "mHeadImage", "headImage", "field_headImage"
            )
            for (fieldName in avatarFields) {
                try {
                    XposedHelpers.setObjectField(item, fieldName, null)
                    break
                } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            LogUtil.d(TAG, "Error disguising item: ${e.message}")
        }
    }

    // ========================================================================
    // 查找方法
    // ========================================================================

    private fun findListView(activity: Any): Any? {
        val listView = tryGetField(activity,
            "mRecyclerView", "recyclerView", "mListView", "listView",
            "mConversationListView", "conversationListView",
            "mMainListView", "mainListView", "mChatListView",
            "mConversationRecyclerView", "mSwipeRefreshLayout",
            "mRefreshLayout", "mPullToRefreshLayout"
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
            "field_wxId", "field_conversationUsername",
            "conversationUsername", "mConversationUsername",
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
            "getEncodeUserName", "getConversationUsername"
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
            "field_conversation", "conversation"
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