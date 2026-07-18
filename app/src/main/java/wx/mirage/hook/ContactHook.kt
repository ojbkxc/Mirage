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
 * 联系人 Hook 模块
 *
 * 功能：拦截联系人列表加载，隐藏 ConfigManager 中标记为 hideContact 的好友。
 * 使用 DexKit 动态查找微信混淆类，失败时降级为直接类加载。
 *
 * 修复要点：
 * - Hook onResume 而非 onCreate（数据在 onResume 时已加载）
 * - 通过遍历所有字段来发现实际的 Adapter 和数据列表
 * - 使用 DexKit 查找实际方法名
 */
object ContactHook : HookLifecycleListener {

    private const val TAG = Constants.MODULE_TAG + ":ContactHook"

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

        // 尝试查找联系人 UI 类
        val contactsClass = MainHook.dexKitBridge.findClass {
            searchString = "contact"
            searchPackage = Constants.WECHAT_UI_CONTACT
        }

        if (contactsClass != null) {
            targetClass = classLoader.loadClass(contactsClass.name)
            targetMethodName = "onResume"
            cacheWarmedUp = true
            LogUtil.i(TAG, "DexKit found: ${contactsClass.name}")
        }
    }

    private fun initFallback(classLoader: ClassLoader) {
        val candidates = listOf(
            "${Constants.WECHAT_UI_CONTACT}.ContactInfoUI",
            "${Constants.WECHAT_UI_CONTACT}.SelectContactUI",
            "${Constants.WECHAT_UI_CONTACT}.AddressUI",
            "${Constants.WECHAT_UI_CONTACT}.ContactWidget",
            "com.tencent.mm.ui.contact.ContactListUI",
            "com.tencent.mm.ui.contact.MMBaseSelectContactUI"
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
        LogUtil.i(TAG, "ContactHook registered (status: ${status.description})")
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

    /**
     * 尝试 Hook 替代方法（Adapter 的 getView/getItem）
     */
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

        LogUtil.w(TAG, "Could not find any suitable method to hook in ${clazz.name}")
    }

    override fun onHookFailed(error: Throwable) {
        LogUtil.e(TAG, "ContactHook failed: ${error.message}", error)
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
        LogUtil.i(TAG, "ContactHook unregistered")
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
            val hiddenIds = ConfigManager.getContactHiddenIds(context)
            if (hiddenIds.isEmpty()) return

            HookMetrics.recordHookExecution(TAG)
            LogUtil.d(TAG, "Filtering contacts, hidden count: ${hiddenIds.size}")

            val activity = param.thisObject
            filterListView(activity, hiddenIds)
        } catch (e: Throwable) {
            LogUtil.e(TAG, "Error in performFiltering: ${e.message}", e)
        }
    }

    /**
     * 从 Activity/Fragment 中查找 ListView/RecyclerView，获取 adapter 数据并过滤。
     */
    private fun filterListView(activity: Any, hiddenIds: Set<String>) {
        // 步骤1：查找 ListView 或 RecyclerView
        val listView = findListView(activity) ?: run {
            LogUtil.d(TAG, "No list view found in contact activity")
            return
        }

        // 步骤2：获取 adapter
        val adapter = findAdapter(listView) ?: run {
            LogUtil.d(TAG, "No adapter found in contact list view")
            return
        }

        // 步骤3：获取 adapter 的数据列表
        val dataList = findAdapterDataList(adapter) ?: run {
            LogUtil.d(TAG, "Contact data list not found or not mutable")
            return
        }

        // 步骤4：过滤并移除隐藏好友
        var removedCount = 0
        val iterator = dataList.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next() ?: continue
            val wxId = extractWxId(item)
            if (wxId != null && wxId in hiddenIds) {
                iterator.remove()
                removedCount++
                LogUtil.d(TAG, "Removed hidden contact: $wxId")
            }
        }

        if (removedCount > 0) {
            LogUtil.i(TAG, "Removed $removedCount hidden contacts from list")
            tryCallMethod(adapter, "notifyDataSetChanged")
        }
    }

    /**
     * 查找 ListView 或 RecyclerView。
     * 尝试多种字段名和递归查找。
     */
    private fun findListView(activity: Any): Any? {
        // 直接查找常见字段名
        val listView = tryGetField(activity,
            "mListView", "listView", "contactListView",
            "mRecyclerView", "recyclerView", "mContactListView",
            "mMainListView", "mainListView", "mContactList",
            "mContactRecyclerView", "mRvContacts", "mRvList",
            "mRecyclerViewContact", "mSwipeRefreshLayout",
            "mRefreshLayout", "mPullToRefreshLayout"
        )
        if (listView != null) return listView

        // 递归查找：遍历 activity 的所有字段，寻找 ListView/RecyclerView
        return findListViewRecursive(activity, 0, 3)
    }

    /**
     * 递归查找 ListView/RecyclerView。
     */
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

    /**
     * 查找 adapter。
     */
    private fun findAdapter(listView: Any): Any? {
        return tryGetField(listView, "mAdapter", "adapter", "mListAdapter", "listAdapter")
            ?: tryCallMethod(listView, "getAdapter")
    }

    /**
     * 查找 adapter 的数据列表。
     */
    private fun findAdapterDataList(adapter: Any): MutableList<*>? {
        // 尝试常见数据字段名
        val dataList = tryGetField(adapter,
            "mData", "data", "mList", "list",
            "mDataSource", "dataSource", "mItems", "items",
            "mContactList", "contactList", "mContactData",
            "mDataList", "dataList", "mObjects", "objects",
            "mDisplayList", "displayList", "mResultList",
            "mAllContacts", "allContacts", "mContactItems",
            "mAdapterData", "adapterData", "mFilteredList",
            "mOriginalData", "originalData", "mContacts"
        )
        if (dataList is MutableList<*>) return dataList

        // 递归查找 adapter 中所有 MutableList 字段
        return findMutableListField(adapter)
    }

    /**
     * 递归查找对象中第一个 MutableList 字段。
     */
    private fun findMutableListField(obj: Any): MutableList<*>? {
        try {
            for (field in obj.javaClass.declaredFields) {
                field.isAccessible = true
                try {
                    val value = field.get(obj)
                    if (value is MutableList<*>) return value
                } catch (_: Throwable) {}
            }
            // 检查父类
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
        // 尝试所有可能的字段名（微信混淆后的字段名可能是 a, b, c 等）
        val fieldNames = arrayOf(
            "field_username", "username", "wxId",
            "mUserName", "mWxId", "userName",
            "field_talker", "talker", "mTalker",
            "field_contactUsername", "contactUsername",
            "mContactUsername", "mFieldUsername",
            "field_wxId", "m_strName", "mNickName",
            // 混淆后的常见单字母字段
            "a", "b", "c", "d", "e", "f", "g", "h",
            "i", "j", "k", "l", "m", "n", "o", "p",
            "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
            // 可能是 wxid_ 前缀
            "wxid", "mWxid", "field_wxid",
            // 可能是 encodeUserName
            "encodeUserName", "mEncodeUserName",
            "field_encodeUserName"
        )

        for (fieldName in fieldNames) {
            val value = tryGetField(obj, fieldName)
            if (value is String && value.isNotEmpty() && isValidWxId(value)) {
                return value
            }
        }

        // 尝试 getter 方法
        val methodNames = arrayOf(
            "getUsername", "getWxId", "getFieldUsername",
            "getUserName", "getTalker", "getContactUsername",
            "getEncodeUserName", "getStrName", "getNickName"
        )
        for (methodName in methodNames) {
            val value = tryCallMethod(obj, methodName)
            if (value is String && value.isNotEmpty() && isValidWxId(value)) {
                return value
            }
        }

        // 尝试获取嵌套的 contact 或 user 对象
        val nestedFields = arrayOf(
            "field_contact", "contact", "mContact",
            "field_userInfo", "userInfo", "mUserInfo",
            "field_user", "user", "mUser",
            "field_chatroomMember", "chatroomMember"
        )
        for (fieldName in nestedFields) {
            val nested = tryGetField(obj, fieldName)
            if (nested != null && nested !== obj) {
                val wxId = extractWxId(nested)
                if (wxId != null) return wxId
            }
        }

        // 遍历所有字段，找第一个看起来像 wxId 的字符串
        return findWxIdInAllFields(obj)
    }

    /**
     * 遍历对象的所有字段，寻找看起来像 wxId 的字符串。
     */
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

    /**
     * 检查字符串是否像有效的 wxId。
     * wxId 通常以 wxid_ 开头或者是纯字母数字组合。
     */
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