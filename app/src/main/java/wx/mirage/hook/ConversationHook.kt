package wx.mirage.hook

import android.database.Cursor
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
 * 会话列表 Hook - 在聊天列表中隐藏指定好友的会话
 *
 * 原理:
 * 1. 使用 DexKit 按真实微信会话字符串特征查找相关类和方法
 * 2. Hook 会话列表 Adapter 的 getCount/getItem/getItemCount 方法
 * 3. Hook ViewHolder 的 onBindViewHolder 方法，隐藏对应会话条目
 * 4. Hook 会话数据库加载方法，在数据源层面过滤
 * 5. 多重 wxId 提取策略（talker, field_username, field_talker 等）
 *
 * 目标真实类:
 * - com.tencent.mm.ui.conversation.cb (会话列表，有 createTime, content, talker, msgId, msg 字段)
 * - com.tencent.mm.storage.MsgInfo (消息存储: msgId, msgSvrId, talker, content, createTime, type, status, isSend, imgPath)
 */
object ConversationHook {

    /**
     * 会话 wxId 提取策略字段名（按优先级排序）
     * 基于真实微信 APK 分析的字段名：
     * cb 类有: talker 字段
     * MsgInfo 类有: talker 字段
     */
    private val TALKER_FIELD_NAMES = arrayOf(
        "field_talker",     // 微信混淆后最常见的 talker 字段名
        "talker",           // 通用字段名（cb 类直接使用）
        "field_username",   // 混淆后的 username 字段名
        "username",         // 通用字段名
        "field_wxId",
        "field_userId",
        "mUserName",
        "userName",
        "mUsername",
        "wxId",
        "uin",
        "alias",
        "field_contactId"
    )

    private val TALKER_GETTER_NAMES = arrayOf(
        "getTalker",
        "getField_talker",
        "getUsername",
        "getUserName",
        "getWxId",
        "getField_username",
        "getUin",
        "getAlias",
        "getField_wxId"
    )

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        XposedBridge.log("${MainHook.TAG}: [ConversationHook] ========== Initializing ==========")

        try {
            // 策略 1: 通过 DexKit 查找会话相关方法
            if (MainHook.dexKitAvailable) {
                hookConversationViaDexKit(lpparam)
            } else {
                XposedBridge.log("${MainHook.TAG}: [ConversationHook] DexKit not available, using direct class detection")
            }

            // 策略 2: 直接 Hook 已知的会话类
            hookKnownConversationClasses(lpparam)

            // 策略 3: Hook 会话数据库加载
            hookConversationDatabase(lpparam)

            XposedBridge.log("${MainHook.TAG}: [ConversationHook] ========== Initialized OK ==========")
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [ConversationHook] Initialization FAILED: ${e.message}")
            XposedBridge.log(e)
        }
    }

    // ========================================================================
    // 策略 1: DexKit 动态查找
    // ========================================================================

    /**
     * 使用 DexKit 按真实微信字符串特征查找会话相关类和方法
     *
     * 搜索字符串: "talker", "createTime", "content", "msgId", "conversation", "rconversation"
     */
    private fun hookConversationViaDexKit(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        val dexKit = MainHook.dexKitBridge

        try {
            XposedBridge.log("${MainHook.TAG}: [ConversationHook:DexKit] Searching for conversation methods...")

            // 第一轮搜索：按会话字段特征字符串查找
            val results = dexKit.batchFindMethodUsingStrings(
                FindMethodUsingStringsArgs.Builder()
                    .searchInClass(true)
                    .addString("talker")
                    .addString("createTime")
                    .addString("content")
                    .addString("msgId")
                    .addMatcher(StringMatcher("contains", true))
                    .build()
            )

            XposedBridge.log("${MainHook.TAG}: [ConversationHook:DexKit] Found ${results.size} methods with conversation field strings")

            // 第二轮搜索：按 conversation 表名查找
            val convResults = dexKit.batchFindMethodUsingStrings(
                FindMethodUsingStringsArgs.Builder()
                    .searchInClass(true)
                    .addString("conversation")
                    .addString("rconversation")
                    .addMatcher(StringMatcher("contains", true))
                    .build()
            )

            XposedBridge.log("${MainHook.TAG}: [ConversationHook:DexKit] Found ${convResults.size} methods with 'conversation'")

            // ===== 第三轮搜索: WAuxiliary 验证过的 MicroMsg 日志模式 =====
            // 微信内部聊天组件特征字符串: "MicroMsg.ChattingUI", "doSendMessage begin send txt msg"
            XposedBridge.log("${MainHook.TAG}: [ConversationHook:DexKit] Searching for MicroMsg.ChattingUI (WAuxiliary-verified)")
            val microMsgResults = dexKit.batchFindMethodUsingStrings(
                FindMethodUsingStringsArgs.Builder()
                    .searchInClass(true)
                    .addString("MicroMsg.ChattingUI")
                    .addString("doSendMessage begin send txt msg")
                    .addMatcher(StringMatcher("contains", true))
                    .build()
            )
            XposedBridge.log("${MainHook.TAG}: [ConversationHook:DexKit] Found ${microMsgResults.size} methods with MicroMsg.ChattingUI")

            // 合并所有找到的方法
            val allResults = (results + convResults + microMsgResults).distinctBy { "${it.className}.${it.name}" }

            XposedBridge.log("${MainHook.TAG}: [ConversationHook:DexKit] Total unique methods: ${allResults.size}")

            // 对每个找到的类进行 Hook
            val hookedClasses = mutableSetOf<String>()

            for (methodData in allResults) {
                try {
                    val className = methodData.className
                    if (className in hookedClasses) continue
                    hookedClasses.add(className)

                    val clazz = classLoader.loadClass(className)
                    XposedBridge.log("${MainHook.TAG}: [ConversationHook:DexKit] Hooking class: $className")

                    hookConversationAdapterMethods(clazz, classLoader)

                } catch (e: Throwable) {
                    XposedBridge.log("${MainHook.TAG}: [ConversationHook:DexKit] Failed to hook $className: ${e.message}")
                }
            }

        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [ConversationHook:DexKit] DexKit search FAILED: ${e.message}")
            XposedBridge.log(e)
        }
    }

    // ========================================================================
    // 策略 2: 直接 Hook 已知的真实会话类
    // ========================================================================

    /**
     * 直接 Hook 已知的微信会话类
     *
     * 真实类名:
     * - com.tencent.mm.ui.conversation.cb (会话列表)
     * - com.tencent.mm.storage.MsgInfo (消息存储)
     */
    private fun hookKnownConversationClasses(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        XposedBridge.log("${MainHook.TAG}: [ConversationHook:Known] Hooking known conversation classes...")

        // 已知的真实会话相关类
        val knownClasses = listOf(
            // 会话列表
            "com.tencent.mm.ui.conversation.cb",
            // 消息存储
            "com.tencent.mm.storage.MsgInfo",
            // 可能的会话 Adapter 混淆类
            "com.tencent.mm.ui.conversation.a",
            "com.tencent.mm.ui.conversation.b",
            "com.tencent.mm.ui.conversation.c",
            "com.tencent.mm.ui.conversation.d",
            "com.tencent.mm.ui.conversation.e",
            "com.tencent.mm.ui.conversation.f",
            "com.tencent.mm.ui.conversation.g",
            "com.tencent.mm.ui.conversation.h",
            "com.tencent.mm.ui.conversation.i",
            "com.tencent.mm.ui.conversation.j",
            "com.tencent.mm.ui.conversation.k",
            "com.tencent.mm.ui.conversation.l",
            "com.tencent.mm.ui.conversation.m",
            "com.tencent.mm.ui.conversation.n",
            "com.tencent.mm.ui.conversation.o",
            "com.tencent.mm.ui.conversation.p",
            "com.tencent.mm.ui.conversation.q",
            "com.tencent.mm.ui.conversation.r",
            "com.tencent.mm.ui.conversation.s",
            "com.tencent.mm.ui.conversation.t",
            "com.tencent.mm.ui.conversation.u",
            "com.tencent.mm.ui.conversation.v",
            "com.tencent.mm.ui.conversation.w",
            "com.tencent.mm.ui.conversation.x",
            "com.tencent.mm.ui.conversation.y",
            "com.tencent.mm.ui.conversation.z",
            // 可能的存储类
            "com.tencent.mm.storage.bi",
            "com.tencent.mm.storage.bj",
            "com.tencent.mm.storage.bk",
            "com.tencent.mm.storage.bl",
            "com.tencent.mm.storage.bm",
            // ConversationInfo 相关
            "com.tencent.mm.storage.ConversationInfo",
            "com.tencent.mm.model.Conversation",
            // WAuxiliary 验证过的聊天组件包 (com.tencent.mm.ui.chatting.component)
            "com.tencent.mm.ui.chatting.component.SendTextComponent",
            "com.tencent.mm.ui.chatting.component.SignallingComponent",
            "com.tencent.mm.ui.chatting.component.a",
            "com.tencent.mm.ui.chatting.component.b",
            "com.tencent.mm.ui.chatting.component.c",
            "com.tencent.mm.ui.chatting.component.d",
            "com.tencent.mm.ui.chatting.component.e",
            "com.tencent.mm.ui.chatting.component.f",
            "com.tencent.mm.ui.chatting.component.g",
            "com.tencent.mm.ui.chatting.component.h",
            "com.tencent.mm.ui.chatting.component.i",
            "com.tencent.mm.ui.chatting.component.j",
            "com.tencent.mm.ui.chatting.component.k",
            "com.tencent.mm.ui.chatting.component.l",
            "com.tencent.mm.ui.chatting.component.m",
            "com.tencent.mm.ui.chatting.component.n",
            "com.tencent.mm.ui.chatting.component.o",
            "com.tencent.mm.ui.chatting.component.p",
            "com.tencent.mm.ui.chatting.component.q",
            "com.tencent.mm.ui.chatting.component.r",
            "com.tencent.mm.ui.chatting.component.s",
            "com.tencent.mm.ui.chatting.component.t",
            "com.tencent.mm.ui.chatting.component.u",
            "com.tencent.mm.ui.chatting.component.v",
            "com.tencent.mm.ui.chatting.component.w",
            "com.tencent.mm.ui.chatting.component.x",
            "com.tencent.mm.ui.chatting.component.y",
            "com.tencent.mm.ui.chatting.component.z"
        )

        var hookedCount = 0

        for (className in knownClasses) {
            try {
                val clazz = classLoader.loadClass(className)
                XposedBridge.log("${MainHook.TAG}: [ConversationHook:Known] Found class: $className")
                hookConversationAdapterMethods(clazz, classLoader)
                hookedCount++
            } catch (e: ClassNotFoundException) {
                // 此类在当前微信版本中不存在
            } catch (e: Throwable) {
                XposedBridge.log("${MainHook.TAG}: [ConversationHook:Known] Failed to hook $className: ${e.message}")
            }
        }

        XposedBridge.log("${MainHook.TAG}: [ConversationHook:Known] Hooked $hookedCount known classes")
    }

    // ========================================================================
    // Adapter 方法 Hook
    // ========================================================================

    /**
     * 对指定的会话 Adapter 类注册所有相关 Hook
     */
    private fun hookConversationAdapterMethods(clazz: Class<*>, classLoader: ClassLoader) {
        val className = clazz.name

        // Hook getCount - 返回集合时过滤
        hookMethodIfExists(clazz, "getCount", emptyArray(), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val result = param.result ?: return
                when (result) {
                    is List<*> -> {
                        val filtered = filterConversationList(result)
                        if (filtered.size != result.size) {
                            XposedBridge.log("${MainHook.TAG}: [ConversationHook] getCount filtered: ${result.size} -> ${filtered.size}")
                            param.result = filtered
                        }
                    }
                    is Collection<*> -> {
                        val filtered = filterConversationList(result.toList())
                        if (filtered.size != result.size) {
                            param.result = filtered
                        }
                    }
                }
            }
        })

        // Hook getItemCount (RecyclerView.Adapter)
        hookMethodIfExists(clazz, "getItemCount", emptyArray(), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val result = param.result ?: return
                when (result) {
                    is List<*> -> {
                        val filtered = filterConversationList(result)
                        if (filtered.size != result.size) {
                            param.result = filtered
                        }
                    }
                    is Collection<*> -> {
                        val filtered = filterConversationList(result.toList())
                        if (filtered.size != result.size) {
                            param.result = filtered
                        }
                    }
                }
            }
        })

        // Hook getItem - 对单个会话对象进行过滤
        hookMethodIfExists(clazz, "getItem", arrayOf(Int::class.java), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val item = param.result ?: return
                val wxId = extractConversationWxId(item)
                if (isHiddenConversation(wxId)) {
                    XposedBridge.log("${MainHook.TAG}: [ConversationHook] getItem - hiding conversation: $wxId")
                    param.result = null
                }
            }
        })

        // Hook onBindViewHolder - 在 UI 绑定阶段隐藏指定会话条目
        val viewHolderClass = findViewHolderClass(classLoader)
        if (viewHolderClass != null) {
            hookMethodIfExists(
                clazz,
                "onBindViewHolder",
                arrayOf(viewHolderClass, Int::class.java),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val adapter = param.thisObject
                            val position = param.args[1] as Int

                            val item = tryGetItemAtPosition(adapter, position) ?: return

                            val wxId = extractConversationWxId(item)
                            if (isHiddenConversation(wxId)) {
                                val holder = param.args[0]
                                val itemView = XposedHelpers.getObjectField(holder, "itemView") as? View
                                itemView?.visibility = View.GONE
                                itemView?.layoutParams = itemView?.layoutParams?.also {
                                    it.height = 0
                                }
                                XposedBridge.log("${MainHook.TAG}: [ConversationHook] onBindViewHolder - hidden view for: $wxId")
                            }
                        } catch (_: Throwable) {
                            // 反射异常，忽略
                        }
                    }
                }
            )

            // 三参数版本
            hookMethodIfExists(
                clazz,
                "onBindViewHolder",
                arrayOf(viewHolderClass, Int::class.java, List::class.java),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val adapter = param.thisObject
                            val position = param.args[1] as Int

                            val item = tryGetItemAtPosition(adapter, position) ?: return
                            val wxId = extractConversationWxId(item)

                            if (isHiddenConversation(wxId)) {
                                val holder = param.args[0]
                                val itemView = XposedHelpers.getObjectField(holder, "itemView") as? View
                                itemView?.visibility = View.GONE
                                itemView?.layoutParams = itemView?.layoutParams?.also {
                                    it.height = 0
                                }
                            }
                        } catch (_: Throwable) {
                            // 忽略
                        }
                    }
                }
            )
        }
    }

    // ========================================================================
    // 策略 3: 会话数据库加载 Hook
    // ========================================================================

    /**
     * Hook 会话数据库加载方法
     * 在从数据库加载会话列表时直接过滤
     */
    private fun hookConversationDatabase(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        XposedBridge.log("${MainHook.TAG}: [ConversationHook:DB] Hooking conversation database loading...")

        val hookedCursorClasses = mutableSetOf<String>()

        try {
            if (MainHook.dexKitAvailable) {
                val dexKit = MainHook.dexKitBridge

                // 搜索包含 "rconversation" 和 Cursor 相关的方法
                val results = dexKit.batchFindMethodUsingStrings(
                    FindMethodUsingStringsArgs.Builder()
                        .searchInClass(true)
                        .addString("rconversation")
                        .addString("talker")
                        .build()
                )

                for (methodData in results) {
                    try {
                        val clazz = classLoader.loadClass(methodData.className)

                        for (method in clazz.declaredMethods) {
                            if (!Cursor::class.java.isAssignableFrom(method.returnType)) continue
                            if (method.returnType == Cursor::class.java) continue // 跳过返回基类 Cursor 的

                            XposedBridge.log("${MainHook.TAG}: [ConversationHook:DB] Hooking cursor method: ${clazz.name}.${method.name}")

                            XposedHelpers.findAndHookMethod(
                                clazz,
                                method.name,
                                *method.parameterTypes,
                                object : XC_MethodHook() {
                                    override fun afterHookedMethod(param: MethodHookParam) {
                                        val cursor = param.result as? Cursor ?: return
                                        val cursorClassName = cursor.javaClass.name

                                        if (cursorClassName in hookedCursorClasses) return
                                        hookedCursorClasses.add(cursorClassName)

                                        hookConversationCursorClass(cursor.javaClass)
                                    }
                                }
                            )
                        }
                    } catch (e: Throwable) {
                        XposedBridge.log("${MainHook.TAG}: [ConversationHook:DB] Failed to hook ${methodData.className}: ${e.message}")
                    }
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [ConversationHook:DB] DexKit search failed: ${e.message}")
        }

        // 降级：尝试 Hook 常见 Cursor 类
        if (hookedCursorClasses.isEmpty()) {
            hookCommonConversationCursors(classLoader, hookedCursorClasses)
        }
    }

    /**
     * 降级：Hook 常见会话 Cursor 类
     */
    private fun hookCommonConversationCursors(classLoader: ClassLoader, hookedCursorClasses: MutableSet<String>) {
        XposedBridge.log("${MainHook.TAG}: [ConversationHook:DB] Trying common cursor classes...")

        val commonCursorClasses = listOf(
            "com.tencent.wcdb.CursorWrapper",
            "com.tencent.wcdb.CrossProcessCursorWrapper",
            "com.tencent.mm.storagebase.CursorWrapper",
            "net.sqlcipher.CursorWrapper",
            "net.sqlcipher.CrossProcessCursorWrapper"
        )

        for (className in commonCursorClasses) {
            try {
                val clazz = classLoader.loadClass(className)
                if (clazz.name !in hookedCursorClasses) {
                    XposedBridge.log("${MainHook.TAG}: [ConversationHook:DB] Hooking common cursor: ${clazz.name}")
                    hookedCursorClasses.add(clazz.name)
                    hookConversationCursorClass(clazz)
                }
            } catch (_: Throwable) {
                // 类不存在
            }
        }
    }

    /**
     * 对指定的会话 Cursor 类进行 Hook
     */
    private fun hookConversationCursorClass(cursorClass: Class<*>) {
        try {
            XposedBridge.log("${MainHook.TAG}: [ConversationHook:DB] Hooking cursor class: ${cursorClass.name}")

            // Hook moveToNext
            XposedHelpers.findAndHookMethod(cursorClass, "moveToNext", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result != true) return
                    val cursor = param.thisObject as Cursor
                    val originalMethod = param.method

                    var skipCount = 0
                    while (skipCount < 1000) {
                        val wxId = getCursorTalker(cursor)
                        if (!isHiddenConversation(wxId)) break
                        skipCount++
                        val moved = XposedBridge.invokeOriginalMethod(originalMethod, cursor, emptyArray()) as Boolean
                        if (!moved) {
                            param.result = false
                            return
                        }
                    }
                    if (skipCount > 0) {
                        XposedBridge.log("${MainHook.TAG}: [ConversationHook:DB] Skipped $skipCount hidden conversations")
                    }
                }
            })

            // Hook moveToFirst
            XposedHelpers.findAndHookMethod(cursorClass, "moveToFirst", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result != true) return
                    val cursor = param.thisObject as Cursor
                    val originalMethod = param.method

                    var skipCount = 0
                    while (skipCount < 1000) {
                        val wxId = getCursorTalker(cursor)
                        if (!isHiddenConversation(wxId)) break
                        skipCount++
                        val moved = XposedBridge.invokeOriginalMethod(originalMethod, cursor, emptyArray()) as Boolean
                        if (!moved) {
                            param.result = false
                            return
                        }
                    }
                }
            })

            // Hook getString - 对 talker/username 列做二次保护
            XposedHelpers.findAndHookMethod(cursorClass, "getString", Int::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val cursor = param.thisObject as Cursor
                    val columnIndex = param.args[0] as Int
                    val columnName = try {
                        cursor.getColumnName(columnIndex)
                    } catch (_: Throwable) {
                        return
                    }
                    // 检查是否是 talker 或 username 列
                    if (columnName == "talker" || columnName == "field_talker" ||
                        columnName.equals("username", ignoreCase = true)) {
                        val wxId = param.result as? String ?: return
                        if (isHiddenConversation(wxId)) {
                            param.result = ""
                        }
                    }
                }
            })

        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [ConversationHook:DB] Failed to hook cursor class ${cursorClass.name}: ${e.message}")
        }
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 从 Cursor 当前行中提取 talker
     */
    private fun getCursorTalker(cursor: Cursor): String? {
        return try {
            val columnNames = arrayOf("talker", "field_talker", "username", "field_username", "mUserName")
            for (colName in columnNames) {
                val columnIndex = cursor.getColumnIndex(colName)
                if (columnIndex >= 0) {
                    val value = cursor.getString(columnIndex)
                    if (!value.isNullOrEmpty()) return value
                }
            }
            null
        } catch (e: Throwable) {
            null
        }
    }

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
            XposedBridge.log("${MainHook.TAG}: [ConversationHook] hookMethodIfExists failed for ${clazz.name}.$methodName: ${e.message}")
        }
    }

    /**
     * 查找 ViewHolder 类
     */
    private fun findViewHolderClass(classLoader: ClassLoader): Class<*>? {
        return try {
            classLoader.loadClass("androidx.recyclerview.widget.RecyclerView\$ViewHolder")
        } catch (e: Throwable) {
            try {
                classLoader.loadClass("android.support.v7.widget.RecyclerView\$ViewHolder")
            } catch (e2: Throwable) {
                null
            }
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
     * 从微信会话对象中反射提取对方 wxId
     * 使用多重策略，确保在各种微信版本中都能正确提取
     */
    private fun extractConversationWxId(item: Any): String? {
        return try {
            // 策略 1: 尝试直接字段访问
            for (fieldName in TALKER_FIELD_NAMES) {
                try {
                    val field = item.javaClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    val value = field.get(item) as? String
                    if (!value.isNullOrEmpty()) {
                        return value
                    }
                } catch (_: Throwable) {
                    // 继续尝试下一个字段
                }
            }

            // 策略 2: 尝试 getter 方法
            for (methodName in TALKER_GETTER_NAMES) {
                try {
                    val method = item.javaClass.getDeclaredMethod(methodName)
                    method.isAccessible = true
                    val value = method.invoke(item) as? String
                    if (!value.isNullOrEmpty()) {
                        return value
                    }
                } catch (_: Throwable) {
                    // 继续尝试下一个方法
                }
            }

            // 策略 3: 尝试 XposedHelpers 的 getObjectField
            for (fieldName in TALKER_FIELD_NAMES) {
                try {
                    val value = XposedHelpers.getObjectField(item, fieldName) as? String
                    if (!value.isNullOrEmpty()) {
                        return value
                    }
                } catch (_: Throwable) {
                    // 忽略
                }
            }

            // 策略 4: 遍历对象的所有字段，查找看起来像 wxId 的字符串
            try {
                val declaredFields = item.javaClass.declaredFields
                for (field in declaredFields) {
                    try {
                        field.isAccessible = true
                        val value = field.get(item)
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

            null
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [ConversationHook] extractConversationWxId failed: ${e.message}")
            null
        }
    }

    /**
     * 过滤会话列表/集合，移除隐藏好友的会话
     */
    @Suppress("UNCHECKED_CAST")
    private fun filterConversationList(list: List<*>): List<Any> {
        val ctx = MainHook.appContext ?: return list.filterNotNull()
        if (!ConfigManager.isEnabled(ctx)) return list.filterNotNull()
        val hiddenIds = ConfigManager.getHiddenWxIds(ctx)

        return list.filterNot { item ->
            if (item == null) return@filterNot false
            val wxId = extractConversationWxId(item)
            !wxId.isNullOrEmpty() && wxId in hiddenIds
        }.filterNotNull()
    }

    /**
     * 判断会话是否应该被隐藏
     */
    private fun isHiddenConversation(wxId: String?): Boolean {
        if (wxId.isNullOrEmpty()) return false
        val ctx = MainHook.appContext ?: return false
        if (!ConfigManager.isEnabled(ctx)) return false
        return ConfigManager.isHidden(ctx, wxId)
    }
}