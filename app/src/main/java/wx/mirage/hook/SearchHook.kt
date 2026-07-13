package wx.mirage.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.query.FindMethodUsingStringsArgs
import org.luckypray.dexkit.query.StringMatcher
import wx.mirage.MainHook
import wx.mirage.config.ConfigManager

/**
 * 搜索 Hook - 防止通过搜索找到隐藏好友
 *
 * 原理:
 * 1. 使用 DexKit 按真实微信搜索/FTS 字符串特征查找相关类和方法
 * 2. Hook 搜索结果的数据返回方法，在返回前过滤隐藏好友
 * 3. Hook 搜索 Adapter 的 getItem 方法
 * 4. 多重 wxId 提取策略
 *
 * 目标真实类:
 * - FTSEmojiDetailPageUI: com.tencent.mm.plugin.emoji.ui.fts.FTSEmojiDetailPageUI
 * - FTSBuildInfoReportStruct: com.tencent.mm.autogen.mmdata.rpt.FTSBuildInfoReportStruct
 * - FTSWASearchInsertWidgetViewEvent: com.tencent.mm.autogen.events.FTSWASearchInsertWidgetViewEvent
 * - FTSEmojiDownloadedEvent: com.tencent.mm.autogen.events.FTSEmojiDownloadedEvent
 */
object SearchHook {

    /**
     * 搜索结果 wxId 提取策略字段名
     */
    private val SEARCH_FIELD_NAMES = arrayOf(
        "userName",
        "field_userName",
        "username",
        "field_username",
        "mUserName",
        "userId",
        "field_userId",
        "wxid",
        "wxId",
        "field_wxId",
        "contactId",
        "field_contactId",
        "uin",
        "alias",
        "field_uin",
        "field_alias",
        "talker",
        "field_talker"
    )

    private val SEARCH_GETTER_NAMES = arrayOf(
        "getUserName",
        "getField_userName",
        "getUsername",
        "getField_username",
        "getUserId",
        "getContactId",
        "getWxid",
        "getWxId",
        "getField_wxId",
        "getUin",
        "getAlias",
        "getTalker",
        "getField_talker"
    )

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        XposedBridge.log("${MainHook.TAG}: [SearchHook] ========== Initializing ==========")

        try {
            // 策略 1: 通过 DexKit 查找搜索/FTS 相关方法
            if (MainHook.dexKitAvailable) {
                hookSearchViaDexKit(lpparam)
            } else {
                XposedBridge.log("${MainHook.TAG}: [SearchHook] DexKit not available, using direct class detection")
            }

            // 策略 2: 直接 Hook 已知的 FTS 搜索类
            hookKnownFtsClasses(lpparam)

            // 策略 3: Hook 搜索 Adapter 方法
            hookSearchAdapterMethods(lpparam)

            XposedBridge.log("${MainHook.TAG}: [SearchHook] ========== Initialized OK ==========")
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [SearchHook] Initialization FAILED: ${e.message}")
            XposedBridge.log(e)
        }
    }

    // ========================================================================
    // 策略 1: DexKit 动态查找
    // ========================================================================

    /**
     * 使用 DexKit 按真实微信搜索/FTS 字符串特征查找相关类和方法
     *
     * 搜索字符串: "FTS", "FTS5", "FTSSearch", "FTSDetailUI", "useFTS",
     *              "search", "SearchContact", "searchResult", "match", "fTS", "fts"
     */
    private fun hookSearchViaDexKit(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        val dexKit = MainHook.dexKitBridge

        try {
            XposedBridge.log("${MainHook.TAG}: [SearchHook:DexKit] Searching for search/FTS methods...")

            // 第一轮搜索：按 FTS 相关字符串查找
            val ftsResults = dexKit.batchFindMethodUsingStrings(
                FindMethodUsingStringsArgs.Builder()
                    .searchInClass(true)
                    .addString("FTS")
                    .addString("FTS5")
                    .addString("FTSSearch")
                    .addString("FTSDetailUI")
                    .addString("useFTS")
                    .addString("fTS")
                    .addString("fts")
                    .addMatcher(StringMatcher("contains", true))
                    .build()
            )

            XposedBridge.log("${MainHook.TAG}: [SearchHook:DexKit] Found ${ftsResults.size} methods with FTS strings")

            // 第二轮搜索：按搜索相关字符串查找
            val searchResults = dexKit.batchFindMethodUsingStrings(
                FindMethodUsingStringsArgs.Builder()
                    .searchInClass(true)
                    .addString("search")
                    .addString("SearchContact")
                    .addString("searchResult")
                    .addString("match")
                    .addMatcher(StringMatcher("contains", true))
                    .build()
            )

            XposedBridge.log("${MainHook.TAG}: [SearchHook:DexKit] Found ${searchResults.size} methods with search strings")

            // ===== 第三轮搜索: WAuxiliary 验证过的 MicroMsg.FTS 日志模式 =====
            XposedBridge.log("${MainHook.TAG}: [SearchHook:DexKit] Searching for MicroMsg.FTS (WAuxiliary-verified)")
            val microMsgResults = dexKit.batchFindMethodUsingStrings(
                FindMethodUsingStringsArgs.Builder()
                    .searchInClass(true)
                    .addString("MicroMsg.FTS")
                    .addMatcher(StringMatcher("contains", true))
                    .build()
            )
            XposedBridge.log("${MainHook.TAG}: [SearchHook:DexKit] Found ${microMsgResults.size} methods with MicroMsg.FTS")

            // 合并所有找到的方法
            val allResults = (ftsResults + searchResults + microMsgResults).distinctBy { "${it.className}.${it.name}" }

            XposedBridge.log("${MainHook.TAG}: [SearchHook:DexKit] Total unique methods: ${allResults.size}")

            // 对每个找到的方法进行 Hook
            for (methodData in allResults) {
                try {
                    val methodName = methodData.name
                    XposedBridge.log("${MainHook.TAG}: [SearchHook:DexKit] Hooking method: ${methodData.className}.$methodName")

                    val method = methodData.getMethodInstance(classLoader)

                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val result = param.result ?: return

                                    // 根据返回类型进行过滤
                                    when (result) {
                                        is List<*> -> {
                                            val filtered = filterSearchResults(result)
                                            if (filtered.size != result.size) {
                                                XposedBridge.log("${MainHook.TAG}: [SearchHook] Filtered search results: ${result.size} -> ${filtered.size}")
                                                param.result = filtered
                                            }
                                        }
                                        is Array<*> -> {
                                            val list = result.filterNotNull()
                                            val filtered = filterSearchResults(list)
                                            if (filtered.size != list.size) {
                                                XposedBridge.log("${MainHook.TAG}: [SearchHook] Filtered search results array: ${list.size} -> ${filtered.size}")
                                                @Suppress("UNCHECKED_CAST")
                                                param.result = filtered.toTypedArray()
                                            }
                                        }
                                        is Collection<*> -> {
                                            val filtered = filterSearchResults(result.toList())
                                            if (filtered.size != result.size) {
                                                param.result = filtered
                                            }
                                        }
                                        else -> {
                                            // 单个搜索结果对象
                                            val wxId = extractWxIdFromSearchResult(result)
                                            if (!wxId.isNullOrEmpty() && shouldHide(wxId)) {
                                                XposedBridge.log("${MainHook.TAG}: [SearchHook] Hiding single search result from: $wxId")
                                                param.result = null
                                            }
                                        }
                                    }
                                } catch (e: Throwable) {
                                    XposedBridge.log("${MainHook.TAG}: [SearchHook] afterHookedMethod error: ${e.message}")
                                }
                            }
                        }
                    )

                } catch (e: Throwable) {
                    XposedBridge.log("${MainHook.TAG}: [SearchHook:DexKit] Failed to hook ${methodData.className}.${methodData.name}: ${e.message}")
                }
            }

        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [SearchHook:DexKit] DexKit search FAILED: ${e.message}")
            XposedBridge.log(e)
        }
    }

    // ========================================================================
    // 策略 2: 直接 Hook 已知的 FTS/搜索类
    // ========================================================================

    /**
     * 直接 Hook 已知的微信 FTS/搜索相关类
     *
     * 真实类名:
     * - FTSEmojiDetailPageUI: com.tencent.mm.plugin.emoji.ui.fts.FTSEmojiDetailPageUI
     * - FTSBuildInfoReportStruct: com.tencent.mm.autogen.mmdata.rpt.FTSBuildInfoReportStruct
     * - FTSWASearchInsertWidgetViewEvent: com.tencent.mm.autogen.events.FTSWASearchInsertWidgetViewEvent
     * - FTSEmojiDownloadedEvent: com.tencent.mm.autogen.events.FTSEmojiDownloadedEvent
     */
    private fun hookKnownFtsClasses(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        XposedBridge.log("${MainHook.TAG}: [SearchHook:KnownFTS] Hooking known FTS/search classes...")

        // 已知的真实 FTS 和搜索相关类
        val knownFtsClasses = listOf(
            // FTS 事件类
            "com.tencent.mm.autogen.events.FTSWASearchInsertWidgetViewEvent",
            "com.tencent.mm.autogen.events.FTSEmojiDownloadedEvent",
            // FTS UI 类
            "com.tencent.mm.plugin.emoji.ui.fts.FTSEmojiDetailPageUI",
            // FTS 数据结构
            "com.tencent.mm.autogen.mmdata.rpt.FTSBuildInfoReportStruct",
            // FTS 搜索相关类
            "com.tencent.mm.plugin.fts.ui.FTSMainUI",
            "com.tencent.mm.plugin.fts.ui.FTSDetailUI",
            "com.tencent.mm.plugin.fts.ui.FTSSearchUI",
            "com.tencent.mm.plugin.fts.ui.FTSContactUI",
            "com.tencent.mm.plugin.fts.ui.FTSConversationUI",
            "com.tencent.mm.plugin.fts.a",
            "com.tencent.mm.plugin.fts.b",
            "com.tencent.mm.plugin.fts.c",
            "com.tencent.mm.plugin.fts.d",
            "com.tencent.mm.plugin.fts.e",
            "com.tencent.mm.plugin.fts.f",
            "com.tencent.mm.plugin.fts.g",
            "com.tencent.mm.plugin.fts.h",
            "com.tencent.mm.plugin.fts.i",
            "com.tencent.mm.plugin.fts.j",
            "com.tencent.mm.plugin.fts.k",
            "com.tencent.mm.plugin.fts.l",
            "com.tencent.mm.plugin.fts.m",
            "com.tencent.mm.plugin.fts.n",
            "com.tencent.mm.plugin.fts.o",
            "com.tencent.mm.plugin.fts.p",
            "com.tencent.mm.plugin.fts.q",
            "com.tencent.mm.plugin.fts.r",
            "com.tencent.mm.plugin.fts.s",
            "com.tencent.mm.plugin.fts.t",
            "com.tencent.mm.plugin.fts.u",
            "com.tencent.mm.plugin.fts.v",
            "com.tencent.mm.plugin.fts.w",
            "com.tencent.mm.plugin.fts.x",
            "com.tencent.mm.plugin.fts.y",
            "com.tencent.mm.plugin.fts.z",
            // 通用搜索 UI 类
            "com.tencent.mm.ui.search.FTSSearchView",
            "com.tencent.mm.ui.search.FTSMainSearchUI",
            "com.tencent.mm.ui.search.SearchUI",
            "com.tencent.mm.ui.search.a",
            "com.tencent.mm.ui.search.b",
            "com.tencent.mm.ui.search.c",
            "com.tencent.mm.ui.search.d",
            "com.tencent.mm.ui.search.e",
            "com.tencent.mm.ui.search.f",
            "com.tencent.mm.ui.search.g",
            "com.tencent.mm.ui.search.h",
            "com.tencent.mm.ui.search.i",
            "com.tencent.mm.ui.search.j",
            "com.tencent.mm.ui.search.k",
            "com.tencent.mm.ui.search.l",
            "com.tencent.mm.ui.search.m",
            "com.tencent.mm.ui.search.n",
            "com.tencent.mm.ui.search.o",
            "com.tencent.mm.ui.search.p",
            "com.tencent.mm.ui.search.q",
            "com.tencent.mm.ui.search.r",
            "com.tencent.mm.ui.search.s",
            "com.tencent.mm.ui.search.t",
            "com.tencent.mm.ui.search.u",
            "com.tencent.mm.ui.search.v",
            "com.tencent.mm.ui.search.w",
            "com.tencent.mm.ui.search.x",
            "com.tencent.mm.ui.search.y",
            "com.tencent.mm.ui.search.z"
        )

        var hookedCount = 0

        for (className in knownFtsClasses) {
            try {
                val clazz = classLoader.loadClass(className)
                XposedBridge.log("${MainHook.TAG}: [SearchHook:KnownFTS] Found FTS class: $className")
                hookSearchDataMethods(clazz, classLoader)
                hookedCount++
            } catch (e: ClassNotFoundException) {
                // 此类在当前微信版本中不存在
            } catch (e: Throwable) {
                XposedBridge.log("${MainHook.TAG}: [SearchHook:KnownFTS] Failed to hook $className: ${e.message}")
            }
        }

        XposedBridge.log("${MainHook.TAG}: [SearchHook:KnownFTS] Hooked $hookedCount known FTS classes")
    }

    // ========================================================================
    // 策略 3: 搜索 Adapter 方法 Hook
    // ========================================================================

    /**
     * 额外 Hook 搜索 Adapter 的 getItem 方法
     */
    private fun hookSearchAdapterMethods(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!MainHook.dexKitAvailable) return

        val classLoader = lpparam.classLoader
        val dexKit = MainHook.dexKitBridge

        try {
            XposedBridge.log("${MainHook.TAG}: [SearchHook:Adapter] Searching for search adapter methods...")

            val adapterResults = dexKit.batchFindMethodUsingStrings(
                FindMethodUsingStringsArgs.Builder()
                    .searchInClass(true)
                    .addString("SearchAdapter")
                    .addString("SearchResultAdapter")
                    .addString("getCount")
                    .addString("getItem")
                    .addMatcher(StringMatcher("contains", true))
                    .build()
            )

            XposedBridge.log("${MainHook.TAG}: [SearchHook:Adapter] Found ${adapterResults.size} methods")

            val hookedAdapterMethods = mutableSetOf<String>()

            for (methodData in adapterResults) {
                try {
                    val methodName = methodData.name
                    val methodKey = "${methodData.className}.$methodName"

                    // 只 Hook getItem 类型的方法，避免重复 Hook
                    if (methodName != "getItem" && methodName != "getItemId") continue
                    if (methodKey in hookedAdapterMethods) continue
                    hookedAdapterMethods.add(methodKey)

                    XposedBridge.log("${MainHook.TAG}: [SearchHook:Adapter] Hooking: $methodKey")

                    val method = methodData.getMethodInstance(classLoader)

                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val result = param.result ?: return
                                    val wxId = extractWxIdFromSearchResult(result)
                                    if (!wxId.isNullOrEmpty() && shouldHide(wxId)) {
                                        XposedBridge.log("${MainHook.TAG}: [SearchHook:Adapter] Hiding search adapter item from: $wxId")
                                        param.result = null
                                    }
                                } catch (e: Throwable) {
                                    XposedBridge.log("${MainHook.TAG}: [SearchHook:Adapter] Hook error: ${e.message}")
                                }
                            }
                        }
                    )

                } catch (e: Throwable) {
                    XposedBridge.log("${MainHook.TAG}: [SearchHook:Adapter] Failed to hook: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [SearchHook:Adapter] DexKit search FAILED: ${e.message}")
        }
    }

    /**
     * 对搜索数据类的方法进行 Hook
     */
    private fun hookSearchDataMethods(clazz: Class<*>, classLoader: ClassLoader) {
        // 查找所有返回 List、Array 或 Collection 的方法并 Hook
        for (method in clazz.declaredMethods) {
            val returnType = method.returnType
            if (List::class.java.isAssignableFrom(returnType) ||
                Array::class.java.isAssignableFrom(returnType) ||
                Collection::class.java.isAssignableFrom(returnType)) {

                XposedBridge.log("${MainHook.TAG}: [SearchHook:KnownFTS] Hooking data method: ${clazz.name}.${method.name}")

                XposedHelpers.findAndHookMethod(
                    clazz,
                    method.name,
                    *method.parameterTypes,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val result = param.result ?: return
                                when (result) {
                                    is List<*> -> {
                                        val filtered = filterSearchResults(result)
                                        if (filtered.size != result.size) {
                                            param.result = filtered
                                        }
                                    }
                                    is Array<*> -> {
                                        val list = result.filterNotNull()
                                        val filtered = filterSearchResults(list)
                                        if (filtered.size != list.size) {
                                            @Suppress("UNCHECKED_CAST")
                                            param.result = filtered.toTypedArray()
                                        }
                                    }
                                    is Collection<*> -> {
                                        val filtered = filterSearchResults(result.toList())
                                        if (filtered.size != result.size) {
                                            param.result = filtered
                                        }
                                    }
                                }
                            } catch (e: Throwable) {
                                XposedBridge.log("${MainHook.TAG}: [SearchHook:KnownFTS] Data method hook error: ${e.message}")
                            }
                        }
                    }
                )
            }
        }
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 过滤搜索结果列表，移除隐藏好友的条目
     */
    private fun filterSearchResults(searchList: List<*>): List<Any> {
        val context = MainHook.appContext ?: return searchList.filterNotNull()
        val filtered = mutableListOf<Any>()

        for (item in searchList) {
            if (item == null) continue

            try {
                val wxId = extractWxIdFromSearchResult(item)
                if (wxId.isNullOrEmpty()) {
                    // 无法提取 wxId，保留该条目
                    filtered.add(item)
                    continue
                }

                if (ConfigManager.isHidden(context, wxId)) {
                    XposedBridge.log("${MainHook.TAG}: [SearchHook] Filtered search result from hidden friend: $wxId")
                    continue
                }

                filtered.add(item)
            } catch (e: Throwable) {
                XposedBridge.log("${MainHook.TAG}: [SearchHook] Error filtering search item: ${e.message}")
                filtered.add(item)
            }
        }

        return filtered
    }

    /**
     * 从搜索结果对象中提取 wxId
     * 使用多重策略，适配微信混淆后的各种字段名
     */
    private fun extractWxIdFromSearchResult(result: Any?): String? {
        if (result == null) return null

        // 策略 1: 尝试直接字段访问
        for (fieldName in SEARCH_FIELD_NAMES) {
            try {
                val field = result.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                val value = field.get(result) as? String
                if (!value.isNullOrEmpty()) {
                    return value
                }
            } catch (_: Throwable) {
                // 字段不存在，继续尝试下一个
            }
        }

        // 策略 2: 尝试 getter 方法
        for (getterName in SEARCH_GETTER_NAMES) {
            try {
                val method = result.javaClass.getDeclaredMethod(getterName)
                method.isAccessible = true
                val value = method.invoke(result) as? String
                if (!value.isNullOrEmpty()) {
                    return value
                }
            } catch (_: Throwable) {
                // 方法不存在，继续尝试下一个
            }
        }

        // 策略 3: 尝试 XposedHelpers 的 getObjectField
        for (fieldName in SEARCH_FIELD_NAMES) {
            try {
                val value = XposedHelpers.getObjectField(result, fieldName) as? String
                if (!value.isNullOrEmpty()) {
                    return value
                }
            } catch (_: Throwable) {
                // 忽略
            }
        }

        // 策略 4: 尝试 XposedHelpers 的 callMethod
        for (getterName in SEARCH_GETTER_NAMES) {
            try {
                val value = XposedHelpers.callMethod(result, getterName) as? String
                if (!value.isNullOrEmpty()) {
                    return value
                }
            } catch (_: Throwable) {
                // 忽略
            }
        }

        // 策略 5: 遍历对象的所有字段，查找看起来像 wxId 的字符串
        try {
            val declaredFields = result.javaClass.declaredFields
            for (field in declaredFields) {
                try {
                    field.isAccessible = true
                    val value = field.get(result)
                    if (value is String && value.matches(Regex("^[a-zA-Z][a-zA-Z0-9_-]{5,}$"))) {
                        return value
                    }
                } catch (_: Throwable) {
                    // 忽略访问异常
                }
            }
        } catch (_: Throwable) {
            // 忽略反射异常
        }

        return null
    }

    /**
     * 判断一个 wxId 是否应该被隐藏
     */
    private fun shouldHide(wxId: String?): Boolean {
        if (wxId.isNullOrEmpty()) return false
        val ctx = MainHook.appContext ?: return false
        return ConfigManager.isEnabled(ctx) && ConfigManager.isHidden(ctx, wxId)
    }
}