package wx.mirage.hook

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.query.FindMethodUsingStringsArgs
import org.luckypray.dexkit.query.StringMatcher
import wx.mirage.MainHook
import wx.mirage.config.ConfigManager

/**
 * 朋友圈 Hook - 在朋友圈 Feed 中隐藏指定好友的动态
 *
 * 原理:
 * 1. 使用 DexKit 按真实微信 SNS 字符串特征查找朋友圈相关类和方法
 * 2. Hook SnsTimeLineBaseAdapter (rs) 的 getView, getItem, getCursor 方法
 * 3. Hook 数据加载/渲染方法，在返回列表前过滤掉隐藏好友的 SNS 动态
 * 4. 多重作者 wxId 提取策略（userName, field_userName, snsId, talker 等）
 *
 * 目标真实类:
 * - SnsTimeLineUI: com.tencent.mm.plugin.sns.ui.SnsTimeLineUI (朋友圈主界面)
 * - rs: com.tencent.mm.plugin.sns.ui.rs (SnsTimeLineBaseAdapter: getView, getItem, getCursor)
 * - SnsObject: com.tencent.mm.protocal.protobuf.SnsObject (SNS 对象 protobuf)
 * - TimeLineObject: com.tencent.mm.protocal.protobuf.TimeLineObject (timeline 对象)
 * - q: com.tencent.mm.plugin.sns.ui.item.q (SNS timeline item: FinderLiveTimeLineItem, BaseTimeLineItem)
 */
object MomentsHook {

    /**
     * SNS 作者 wxId 提取策略字段名（按优先级排序）
     * 基于真实微信 APK 分析的字段名：
     * - SnsObject: userName, snsId
     * - TimeLineObject: userName, feedId
     */
    private val SNS_AUTHOR_FIELD_NAMES = arrayOf(
        "userName",             // SnsObject.userName (真实字段)
        "field_userName",       // 混淆后的字段名
        "username",             // 通用字段名
        "snsId",                // SNS 动态 ID
        "field_snsId",          // 混淆后的 snsId
        "talker",               // 会话发送者
        "field_talker",         // 混淆后的 talker
        "feedId",               // Feed ID
        "field_feedId",         // 混淆后的 feedId
        "mUserName",            // 内部字段名
        "userId",
        "field_username",
        "field_wxId",
        "wxId",
        "snsUserName",
        "authorId",
        "field_authorId"
    )

    private val SNS_AUTHOR_GETTER_NAMES = arrayOf(
        "getUserName",
        "getField_userName",
        "getUsername",
        "getSnsId",
        "getFeedId",
        "getTalker",
        "getSnsUserName",
        "getUserId",
        "getWxId",
        "getField_wxId",
        "getAuthorId"
    )

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        XposedBridge.log("${MainHook.TAG}: [MomentsHook] ========== Initializing ==========")

        try {
            // 策略 1: 通过 DexKit 查找 SNS 相关方法
            if (MainHook.dexKitAvailable) {
                hookMomentsViaDexKit(lpparam)
            } else {
                XposedBridge.log("${MainHook.TAG}: [MomentsHook] DexKit not available, using direct class detection")
            }

            // 策略 2: 直接 Hook 已知的真实 SNS 类
            hookKnownSnsClasses(lpparam)

            // 策略 3: Hook SnsTimeLineBaseAdapter 方法
            hookSnsAdapterMethods(lpparam)

            XposedBridge.log("${MainHook.TAG}: [MomentsHook] ========== Initialized OK ==========")
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [MomentsHook] Initialization FAILED: ${e.message}")
            XposedBridge.log(e)
        }
    }

    // ========================================================================
    // 策略 1: DexKit 动态查找
    // ========================================================================

    /**
     * 使用 DexKit 按真实微信 SNS 字符串特征查找朋友圈相关类和方法
     *
     * 搜索字符串: "SnsTimeLineUI", "SnsTimeLine", "SnsObject", "TimeLineObject",
     *              "feedId", "snsId", "SnsTimeLineVendingStruct"
     */
    private fun hookMomentsViaDexKit(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        val dexKit = MainHook.dexKitBridge

        try {
            XposedBridge.log("${MainHook.TAG}: [MomentsHook:DexKit] Searching for SNS methods...")

            // 第一轮搜索：按 SNS 类名字符串查找
            val results = dexKit.batchFindMethodUsingStrings(
                FindMethodUsingStringsArgs.Builder()
                    .searchInClass(true)
                    .addString("SnsTimeLineUI")
                    .addString("SnsTimeLine")
                    .addString("SnsObject")
                    .addString("TimeLineObject")
                    .addMatcher(StringMatcher("contains", true))
                    .build()
            )

            XposedBridge.log("${MainHook.TAG}: [MomentsHook:DexKit] Found ${results.size} methods with SNS class strings")

            // 第二轮搜索：按 SNS 字段特征字符串查找
            val fieldResults = dexKit.batchFindMethodUsingStrings(
                FindMethodUsingStringsArgs.Builder()
                    .searchInClass(true)
                    .addString("feedId")
                    .addString("snsId")
                    .addString("SnsTimeLineVendingStruct")
                    .addMatcher(StringMatcher("contains", true))
                    .build()
            )

            XposedBridge.log("${MainHook.TAG}: [MomentsHook:DexKit] Found ${fieldResults.size} methods with SNS field strings")

            // ===== 第三轮搜索: WAuxiliary 验证过的 MicroMsg.Sns 日志模式 =====
            XposedBridge.log("${MainHook.TAG}: [MomentsHook:DexKit] Searching for MicroMsg.Sns (WAuxiliary-verified)")
            val microMsgResults = dexKit.batchFindMethodUsingStrings(
                FindMethodUsingStringsArgs.Builder()
                    .searchInClass(true)
                    .addString("MicroMsg.Sns")
                    .addMatcher(StringMatcher("contains", true))
                    .build()
            )
            XposedBridge.log("${MainHook.TAG}: [MomentsHook:DexKit] Found ${microMsgResults.size} methods with MicroMsg.Sns")

            // 合并所有找到的方法
            val allResults = (results + fieldResults + microMsgResults).distinctBy { "${it.className}.${it.name}" }

            XposedBridge.log("${MainHook.TAG}: [MomentsHook:DexKit] Total unique methods: ${allResults.size}")

            // 对每个找到的方法进行 Hook
            for (methodData in allResults) {
                try {
                    val methodName = methodData.name
                    XposedBridge.log("${MainHook.TAG}: [MomentsHook:DexKit] Hooking method: ${methodData.className}.$methodName")

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
                                            val filtered = filterSnsList(result)
                                            if (filtered.size != result.size) {
                                                XposedBridge.log("${MainHook.TAG}: [MomentsHook] Filtered list: ${result.size} -> ${filtered.size}")
                                                param.result = filtered
                                            }
                                        }
                                        is Array<*> -> {
                                            val list = result.filterNotNull()
                                            val filtered = filterSnsList(list)
                                            if (filtered.size != list.size) {
                                                XposedBridge.log("${MainHook.TAG}: [MomentsHook] Filtered array: ${list.size} -> ${filtered.size}")
                                                @Suppress("UNCHECKED_CAST")
                                                param.result = filtered.toTypedArray()
                                            }
                                        }
                                        is Collection<*> -> {
                                            val filtered = filterSnsList(result.toList())
                                            if (filtered.size != result.size) {
                                                param.result = filtered
                                            }
                                        }
                                        else -> {
                                            // 可能是单个 SNS 对象，检查作者
                                            val wxId = extractAuthorWxId(result)
                                            if (!wxId.isNullOrEmpty() && shouldHide(wxId)) {
                                                XposedBridge.log("${MainHook.TAG}: [MomentsHook] Hiding single moment from: $wxId")
                                                param.result = null
                                            }
                                        }
                                    }
                                } catch (e: Throwable) {
                                    XposedBridge.log("${MainHook.TAG}: [MomentsHook] afterHookedMethod error: ${e.message}")
                                }
                            }
                        }
                    )

                } catch (e: Throwable) {
                    XposedBridge.log("${MainHook.TAG}: [MomentsHook:DexKit] Failed to hook ${methodData.className}.${methodData.name}: ${e.message}")
                }
            }

        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [MomentsHook:DexKit] DexKit search FAILED: ${e.message}")
            XposedBridge.log(e)
        }
    }

    // ========================================================================
    // 策略 2: 直接 Hook 已知的真实 SNS 类
    // ========================================================================

    /**
     * 直接 Hook 已知的微信 SNS 相关类
     *
     * 真实类名:
     * - SnsTimeLineUI: com.tencent.mm.plugin.sns.ui.SnsTimeLineUI
     * - rs: com.tencent.mm.plugin.sns.ui.rs (SnsTimeLineBaseAdapter)
     * - SnsObject: com.tencent.mm.protocal.protobuf.SnsObject
     * - TimeLineObject: com.tencent.mm.protocal.protobuf.TimeLineObject
     * - q: com.tencent.mm.plugin.sns.ui.item.q (SNS timeline item)
     */
    private fun hookKnownSnsClasses(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        XposedBridge.log("${MainHook.TAG}: [MomentsHook:Known] Hooking known SNS classes...")

        // 已知的真实 SNS 相关类
        val knownSnsClasses = listOf(
            // 朋友圈主界面
            "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI",
            // SnsTimeLineBaseAdapter (getView, getItem, getCursor)
            "com.tencent.mm.plugin.sns.ui.rs",
            // SNS 数据对象
            "com.tencent.mm.protocal.protobuf.SnsObject",
            "com.tencent.mm.protocal.protobuf.TimeLineObject",
            // SNS timeline item (FinderLiveTimeLineItem, BaseTimeLineItem 等)
            "com.tencent.mm.plugin.sns.ui.item.q",
            // 可能的 SNS Adapter 混淆类
            "com.tencent.mm.plugin.sns.ui.a",
            "com.tencent.mm.plugin.sns.ui.b",
            "com.tencent.mm.plugin.sns.ui.c",
            "com.tencent.mm.plugin.sns.ui.d",
            "com.tencent.mm.plugin.sns.ui.e",
            "com.tencent.mm.plugin.sns.ui.f",
            "com.tencent.mm.plugin.sns.ui.g",
            "com.tencent.mm.plugin.sns.ui.h",
            "com.tencent.mm.plugin.sns.ui.i",
            "com.tencent.mm.plugin.sns.ui.j",
            "com.tencent.mm.plugin.sns.ui.k",
            "com.tencent.mm.plugin.sns.ui.l",
            "com.tencent.mm.plugin.sns.ui.m",
            "com.tencent.mm.plugin.sns.ui.n",
            "com.tencent.mm.plugin.sns.ui.o",
            "com.tencent.mm.plugin.sns.ui.p",
            "com.tencent.mm.plugin.sns.ui.q",
            "com.tencent.mm.plugin.sns.ui.r",
            "com.tencent.mm.plugin.sns.ui.s",
            "com.tencent.mm.plugin.sns.ui.t",
            "com.tencent.mm.plugin.sns.ui.u",
            "com.tencent.mm.plugin.sns.ui.v",
            "com.tencent.mm.plugin.sns.ui.w",
            "com.tencent.mm.plugin.sns.ui.x",
            "com.tencent.mm.plugin.sns.ui.y",
            "com.tencent.mm.plugin.sns.ui.z",
            // SNS item 混淆类
            "com.tencent.mm.plugin.sns.ui.item.a",
            "com.tencent.mm.plugin.sns.ui.item.b",
            "com.tencent.mm.plugin.sns.ui.item.c",
            "com.tencent.mm.plugin.sns.ui.item.d",
            "com.tencent.mm.plugin.sns.ui.item.e",
            "com.tencent.mm.plugin.sns.ui.item.f",
            "com.tencent.mm.plugin.sns.ui.item.g",
            "com.tencent.mm.plugin.sns.ui.item.h",
            "com.tencent.mm.plugin.sns.ui.item.i",
            "com.tencent.mm.plugin.sns.ui.item.j",
            "com.tencent.mm.plugin.sns.ui.item.k",
            "com.tencent.mm.plugin.sns.ui.item.l",
            "com.tencent.mm.plugin.sns.ui.item.m",
            "com.tencent.mm.plugin.sns.ui.item.n",
            "com.tencent.mm.plugin.sns.ui.item.o",
            "com.tencent.mm.plugin.sns.ui.item.p"
        )

        var hookedCount = 0

        for (className in knownSnsClasses) {
            try {
                val clazz = classLoader.loadClass(className)
                XposedBridge.log("${MainHook.TAG}: [MomentsHook:Known] Found SNS class: $className")
                hookSnsDataMethods(clazz, classLoader)
                hookedCount++
            } catch (e: ClassNotFoundException) {
                // 此类在当前微信版本中不存在
            } catch (e: Throwable) {
                XposedBridge.log("${MainHook.TAG}: [MomentsHook:Known] Failed to hook $className: ${e.message}")
            }
        }

        XposedBridge.log("${MainHook.TAG}: [MomentsHook:Known] Hooked $hookedCount known SNS classes")
    }

    // ========================================================================
    // 策略 3: SnsTimeLineBaseAdapter 方法 Hook
    // ========================================================================

    /**
     * Hook SnsTimeLineBaseAdapter (rs) 的核心方法
     * getView, getItem, getCursor 是朋友圈列表渲染的关键方法
     */
    private fun hookSnsAdapterMethods(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        XposedBridge.log("${MainHook.TAG}: [MomentsHook:Adapter] Hooking SnsTimeLineBaseAdapter methods...")

        // 尝试加载 rs (SnsTimeLineBaseAdapter)
        try {
            val adapterClass = classLoader.loadClass("com.tencent.mm.plugin.sns.ui.rs")
            XposedBridge.log("${MainHook.TAG}: [MomentsHook:Adapter] Found SnsTimeLineBaseAdapter (rs)")

            // Hook getView - 返回单个 View，可以在此过滤
            hookMethodIfExists(adapterClass, "getView", arrayOf(Int::class.java, View::class.java, android.view.ViewGroup::class.java), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val position = param.args[0] as Int
                        val item = tryGetItemAtPosition(param.thisObject, position) ?: return
                        val wxId = extractAuthorWxId(item)
                        if (shouldHide(wxId)) {
                            XposedBridge.log("${MainHook.TAG}: [MomentsHook:Adapter] getView - hiding position $position for: $wxId")
                            // 返回一个 0 高度的空 View，避免显示隐藏好友的朋友圈
                            val context = try {
                                val parent = param.args[2] as? android.view.ViewGroup
                                parent?.context
                            } catch (_: Throwable) { null }
                            if (context != null) {
                                val emptyView = View(context)
                                emptyView.layoutParams = android.view.ViewGroup.LayoutParams(0, 0)
                                emptyView.visibility = View.GONE
                                param.result = emptyView
                            }
                        }
                    } catch (_: Throwable) {
                        // 忽略
                    }
                }
            })

            // Hook getItem - 返回单个 SNS 数据项
            hookMethodIfExists(adapterClass, "getItem", arrayOf(Int::class.java), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val item = param.result ?: return
                    val wxId = extractAuthorWxId(item)
                    if (shouldHide(wxId)) {
                        XposedBridge.log("${MainHook.TAG}: [MomentsHook:Adapter] getItem - hiding item from: $wxId")
                        param.result = null
                    }
                }
            })

            // Hook getCursor - 返回 Cursor 数据
            hookMethodIfExists(adapterClass, "getCursor", emptyArray(), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val cursor = param.result as? android.database.Cursor ?: return
                    XposedBridge.log("${MainHook.TAG}: [MomentsHook:Adapter] getCursor - hooking SNS cursor")
                    // Cursor 的过滤在策略 1 中处理
                }
            })

            // Hook getCount
            hookMethodIfExists(adapterClass, "getCount", emptyArray(), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val result = param.result ?: return
                    when (result) {
                        is List<*> -> {
                            val filtered = filterSnsList(result)
                            if (filtered.size != result.size) {
                                param.result = filtered
                            }
                        }
                        is Collection<*> -> {
                            val filtered = filterSnsList(result.toList())
                            if (filtered.size != result.size) {
                                param.result = filtered
                            }
                        }
                    }
                }
            })

        } catch (e: ClassNotFoundException) {
            XposedBridge.log("${MainHook.TAG}: [MomentsHook:Adapter] SnsTimeLineBaseAdapter (rs) not found in this version")
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [MomentsHook:Adapter] Failed to hook rs: ${e.message}")
        }
    }

    /**
     * 对 SNS 数据类的方法进行 Hook
     */
    private fun hookSnsDataMethods(clazz: Class<*>, classLoader: ClassLoader) {
        // 查找所有返回 List 或 Array 的方法并 Hook
        for (method in clazz.declaredMethods) {
            val returnType = method.returnType
            if (List::class.java.isAssignableFrom(returnType) ||
                Array::class.java.isAssignableFrom(returnType) ||
                Collection::class.java.isAssignableFrom(returnType)) {

                XposedBridge.log("${MainHook.TAG}: [MomentsHook:Known] Hooking data method: ${clazz.name}.${method.name}")

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
                                        val filtered = filterSnsList(result)
                                        if (filtered.size != result.size) {
                                            param.result = filtered
                                        }
                                    }
                                    is Array<*> -> {
                                        val list = result.filterNotNull()
                                        val filtered = filterSnsList(list)
                                        if (filtered.size != list.size) {
                                            @Suppress("UNCHECKED_CAST")
                                            param.result = filtered.toTypedArray()
                                        }
                                    }
                                    is Collection<*> -> {
                                        val filtered = filterSnsList(result.toList())
                                        if (filtered.size != result.size) {
                                            param.result = filtered
                                        }
                                    }
                                }
                            } catch (e: Throwable) {
                                XposedBridge.log("${MainHook.TAG}: [MomentsHook:Known] Data method hook error: ${e.message}")
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
     * 安全 Hook 辅助方法：方法不存在时静默忽略
     */
    private fun hookMethodIfExists(
        clazz: Class<*>,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        callback: XC_MethodHook
    ) {
        try {
            if (parameterTypes.isEmpty()) {
                XposedHelpers.findAndHookMethod(clazz, methodName, callback)
            } else {
                XposedHelpers.findAndHookMethod(clazz, methodName, *parameterTypes, callback)
            }
        } catch (e: NoSuchMethodError) {
            // 方法不存在，忽略
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [MomentsHook] hookMethodIfExists failed for ${clazz.name}.$methodName: ${e.message}")
        }
    }

    /**
     * 尝试从 Adapter 获取指定位置的数据项
     */
    private fun tryGetItemAtPosition(adapter: Any, position: Int): Any? {
        return try {
            val getItemMethod = adapter.javaClass.getMethod("getItem", Int::class.java)
            getItemMethod.isAccessible = true
            getItemMethod.invoke(adapter, position)
        } catch (e: Throwable) {
            try {
                val methods = adapter.javaClass.declaredMethods
                for (method in methods) {
                    if (method.name == "getItem" && method.parameterTypes.size == 1) {
                        method.isAccessible = true
                        return method.invoke(adapter, position)
                    }
                }
            } catch (_: Throwable) {
            }
            null
        }
    }

    /**
     * 从 SNS 动态对象中提取作者 wxId
     * 使用多重策略，适配微信混淆后的各种字段名
     */
    private fun extractAuthorWxId(snsObject: Any?): String? {
        if (snsObject == null) return null

        // 策略 1: 尝试直接字段访问
        for (fieldName in SNS_AUTHOR_FIELD_NAMES) {
            try {
                val field = snsObject.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                val value = field.get(snsObject) as? String
                if (!value.isNullOrEmpty()) {
                    return value
                }
            } catch (_: Throwable) {
                // 字段不存在，继续尝试下一个
            }
        }

        // 策略 2: 尝试 getter 方法
        for (getterName in SNS_AUTHOR_GETTER_NAMES) {
            try {
                val method = snsObject.javaClass.getDeclaredMethod(getterName)
                method.isAccessible = true
                val value = method.invoke(snsObject) as? String
                if (!value.isNullOrEmpty()) {
                    return value
                }
            } catch (_: Throwable) {
                // 方法不存在，继续尝试下一个
            }
        }

        // 策略 3: 尝试 XposedHelpers 的 getObjectField
        for (fieldName in SNS_AUTHOR_FIELD_NAMES) {
            try {
                val value = XposedHelpers.getObjectField(snsObject, fieldName) as? String
                if (!value.isNullOrEmpty()) {
                    return value
                }
            } catch (_: Throwable) {
                // 忽略
            }
        }

        // 策略 4: 尝试 XposedHelpers 的 callMethod
        for (getterName in SNS_AUTHOR_GETTER_NAMES) {
            try {
                val value = XposedHelpers.callMethod(snsObject, getterName) as? String
                if (!value.isNullOrEmpty()) {
                    return value
                }
            } catch (_: Throwable) {
                // 忽略
            }
        }

        // 策略 5: 遍历对象的所有字段，查找看起来像 wxId 的字符串
        try {
            val declaredFields = snsObject.javaClass.declaredFields
            for (field in declaredFields) {
                try {
                    field.isAccessible = true
                    val value = field.get(snsObject)
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
     * 过滤 SNS 动态列表，移除隐藏好友的动态
     */
    private fun filterSnsList(snsList: List<*>): List<Any> {
        val context = MainHook.appContext ?: return snsList.filterNotNull()
        val filtered = mutableListOf<Any>()

        for (item in snsList) {
            if (item == null) continue

            try {
                val wxId = extractAuthorWxId(item)
                if (wxId.isNullOrEmpty()) {
                    // 无法提取 wxId，保留该条目
                    filtered.add(item)
                    continue
                }

                if (ConfigManager.isHidden(context, wxId)) {
                    XposedBridge.log("${MainHook.TAG}: [MomentsHook] Filtered moment from hidden friend: $wxId")
                    continue
                }

                filtered.add(item)
            } catch (e: Throwable) {
                // 处理单个条目异常时不影响其他条目
                XposedBridge.log("${MainHook.TAG}: [MomentsHook] Error filtering moment item: ${e.message}")
                filtered.add(item)
            }
        }

        return filtered
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