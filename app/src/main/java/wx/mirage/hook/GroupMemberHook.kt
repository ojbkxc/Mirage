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
 * 群聊成员列表 Hook - 在群聊成员列表中隐藏指定好友
 *
 * 原理:
 * 1. 使用 DexKit 按真实微信聊天室成员字符串特征查找相关类和方法
 * 2. Hook 群成员列表 Adapter 的 getCount/getItem/getItemCount 方法
 * 3. Hook ViewHolder 的 onBindViewHolder 方法，隐藏对应成员条目
 * 4. 多重 wxId 提取策略（username, field_username, memberName, wxId 等）
 * 5. 支持群聊 @ 列表中的成员隐藏
 *
 * 目标真实类:
 * - ChatroomInfoUI: com.tencent.mm.plugin.chatroom.ui.ChatroomInfoUI (群聊信息页)
 * - SelectMemberUI: com.tencent.mm.ui.chatting.SelectMemberUI (选择群成员)
 * - 群成员 Adapter: com.tencent.mm.ui.chatting 包下的各类 Adapter
 */
object GroupMemberHook {

    private val MEMBER_FIELD_NAMES = arrayOf(
        "field_username", "username", "mUserName", "userName",
        "field_wxId", "wxId", "field_userId", "userId",
        "uin", "alias", "memberName", "field_memberName",
        "displayName", "field_displayName", "nickName",
        "mNickName", "field_nickName"
    )

    private val MEMBER_GETTER_NAMES = arrayOf(
        "getUsername", "getUserName", "getWxId", "getField_username",
        "getUin", "getAlias", "getField_wxId", "getMemberName",
        "getDisplayName", "getNickName", "getUserId"
    )

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        XposedBridge.log("${MainHook.TAG}: [GroupMemberHook] ========== Initializing ==========")

        try {
            // 策略 1: 通过 DexKit 查找群成员相关方法
            if (MainHook.dexKitAvailable) {
                hookGroupMemberViaDexKit(lpparam)
            } else {
                XposedBridge.log("${MainHook.TAG}: [GroupMemberHook] DexKit not available, using direct class detection")
            }

            // 策略 2: 直接 Hook 已知的群成员相关类
            hookKnownGroupMemberClasses(lpparam)

            // 策略 3: Hook 群聊 @ 成员列表
            hookAtMemberList(lpparam)

            XposedBridge.log("${MainHook.TAG}: [GroupMemberHook] ========== Initialized OK ==========")
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [GroupMemberHook] Initialization FAILED: ${e.message}")
            XposedBridge.log(e)
        }
    }

    // ========================================================================
    // 策略 1: DexKit 动态查找
    // ========================================================================

    private fun hookGroupMemberViaDexKit(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        val dexKit = MainHook.dexKitBridge

        try {
            XposedBridge.log("${MainHook.TAG}: [GroupMemberHook:DexKit] Searching for group member methods...")

            // 第一轮搜索：按群成员字段特征字符串查找
            val results = dexKit.batchFindMethodUsingStrings(
                FindMethodUsingStringsArgs.Builder()
                    .searchInClass(true)
                    .addString("ChatroomMember")
                    .addString("chatroomMember")
                    .addString("groupMember")
                    .addString("memberCount")
                    .addString("roomowner")
                    .addString("chatroom")
                    .addMatcher(StringMatcher("contains", true))
                    .build()
            )

            XposedBridge.log("${MainHook.TAG}: [GroupMemberHook:DexKit] Found ${results.size} methods with group member strings")

            // 第二轮搜索：按聊天室 UI 字符串查找
            val uiResults = dexKit.batchFindMethodUsingStrings(
                FindMethodUsingStringsArgs.Builder()
                    .searchInClass(true)
                    .addString("ChatroomInfoUI")
                    .addString("SelectMemberUI")
                    .addString("RemoveMember")
                    .addString("AddMember")
                    .addMatcher(StringMatcher("contains", true))
                    .build()
            )

            XposedBridge.log("${MainHook.TAG}: [GroupMemberHook:DexKit] Found ${uiResults.size} methods with chatroom UI strings")

            // 第三轮搜索: WAuxiliary 验证过的 MicroMsg.Contact 日志模式
            // 群成员在联系人相关的日志中
            XposedBridge.log("${MainHook.TAG}: [GroupMemberHook:DexKit] Searching for MicroMsg.Contact (WAuxiliary-verified)")
            val microMsgResults = dexKit.batchFindMethodUsingStrings(
                FindMethodUsingStringsArgs.Builder()
                    .searchInClass(true)
                    .addString("MicroMsg.Contact")
                    .addMatcher(StringMatcher("contains", true))
                    .build()
            )
            XposedBridge.log("${MainHook.TAG}: [GroupMemberHook:DexKit] Found ${microMsgResults.size} methods with MicroMsg.Contact")

            // 合并所有找到的方法
            val allResults = (results + uiResults + microMsgResults).distinctBy { "${it.className}.${it.name}" }

            XposedBridge.log("${MainHook.TAG}: [GroupMemberHook:DexKit] Total unique methods: ${allResults.size}")

            // 对每个找到的类进行 Hook
            val hookedClasses = mutableSetOf<String>()

            for (methodData in allResults) {
                try {
                    val className = methodData.className
                    if (className in hookedClasses) continue
                    hookedClasses.add(className)

                    val clazz = classLoader.loadClass(className)
                    XposedBridge.log("${MainHook.TAG}: [GroupMemberHook:DexKit] Hooking class: $className")

                    hookGroupMemberAdapterMethods(clazz, classLoader)

                } catch (e: Throwable) {
                    XposedBridge.log("${MainHook.TAG}: [GroupMemberHook:DexKit] Failed to hook $className: ${e.message}")
                }
            }

        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [GroupMemberHook:DexKit] DexKit search FAILED: ${e.message}")
            XposedBridge.log(e)
        }
    }

    // ========================================================================
    // 策略 2: 直接 Hook 已知的群成员相关类
    // ========================================================================

    private fun hookKnownGroupMemberClasses(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        XposedBridge.log("${MainHook.TAG}: [GroupMemberHook:Known] Hooking known group member classes...")

        val knownClasses = listOf(
            // 群聊信息页
            "com.tencent.mm.plugin.chatroom.ui.ChatroomInfoUI",
            // 选择群成员
            "com.tencent.mm.ui.chatting.SelectMemberUI",
            // 群成员管理
            "com.tencent.mm.plugin.chatroom.ui.RoomManagerUI",
            "com.tencent.mm.plugin.chatroom.ui.SeeRoomMemberUI",
            // 群聊相关 Adapter 混淆类
            "com.tencent.mm.plugin.chatroom.ui.a",
            "com.tencent.mm.plugin.chatroom.ui.b",
            "com.tencent.mm.plugin.chatroom.ui.c",
            "com.tencent.mm.plugin.chatroom.ui.d",
            "com.tencent.mm.plugin.chatroom.ui.e",
            "com.tencent.mm.plugin.chatroom.ui.f",
            "com.tencent.mm.plugin.chatroom.ui.g",
            "com.tencent.mm.plugin.chatroom.ui.h",
            "com.tencent.mm.plugin.chatroom.ui.i",
            "com.tencent.mm.plugin.chatroom.ui.j",
            "com.tencent.mm.plugin.chatroom.ui.k",
            "com.tencent.mm.plugin.chatroom.ui.l",
            "com.tencent.mm.plugin.chatroom.ui.m",
            "com.tencent.mm.plugin.chatroom.ui.n",
            "com.tencent.mm.plugin.chatroom.ui.o",
            "com.tencent.mm.plugin.chatroom.ui.p",
            "com.tencent.mm.plugin.chatroom.ui.q",
            "com.tencent.mm.plugin.chatroom.ui.r",
            "com.tencent.mm.plugin.chatroom.ui.s",
            "com.tencent.mm.plugin.chatroom.ui.t",
            "com.tencent.mm.plugin.chatroom.ui.u",
            "com.tencent.mm.plugin.chatroom.ui.v",
            "com.tencent.mm.plugin.chatroom.ui.w",
            "com.tencent.mm.plugin.chatroom.ui.x",
            "com.tencent.mm.plugin.chatroom.ui.y",
            "com.tencent.mm.plugin.chatroom.ui.z"
        )

        var hookedCount = 0

        for (className in knownClasses) {
            try {
                val clazz = classLoader.loadClass(className)
                XposedBridge.log("${MainHook.TAG}: [GroupMemberHook:Known] Found class: $className")
                hookGroupMemberAdapterMethods(clazz, classLoader)
                hookedCount++
            } catch (e: ClassNotFoundException) {
                // 此类在当前微信版本中不存在
            } catch (e: Throwable) {
                XposedBridge.log("${MainHook.TAG}: [GroupMemberHook:Known] Failed to hook $className: ${e.message}")
            }
        }

        XposedBridge.log("${MainHook.TAG}: [GroupMemberHook:Known] Hooked $hookedCount known classes")
    }

    // ========================================================================
    // 策略 3: 群聊 @ 成员列表 Hook
    // ========================================================================

    /**
     * Hook 群聊中的 @ 成员列表
     * 微信在群聊中输入 @ 会弹出成员列表，需要在此处也隐藏
     */
    private fun hookAtMemberList(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        XposedBridge.log("${MainHook.TAG}: [GroupMemberHook:AtList] Hooking @ member list...")

        // 尝试 Hook 群聊 @ 提示相关的类
        val atListClasses = listOf(
            "com.tencent.mm.ui.chatting.AtSomeoneUI",
            "com.tencent.mm.ui.chatting.component.AtAllComponent",
            "com.tencent.mm.ui.chatting.ChattingUIFragment",
            "com.tencent.mm.ui.chatting.AtUI",
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

        for (className in atListClasses) {
            try {
                val clazz = classLoader.loadClass(className)
                XposedBridge.log("${MainHook.TAG}: [GroupMemberHook:AtList] Found @ list class: $className")
                hookGroupMemberAdapterMethods(clazz, classLoader)
                hookedCount++
            } catch (e: ClassNotFoundException) {
                // 此类在当前微信版本中不存在
            } catch (e: Throwable) {
                XposedBridge.log("${MainHook.TAG}: [GroupMemberHook:AtList] Failed to hook $className: ${e.message}")
            }
        }

        XposedBridge.log("${MainHook.TAG}: [GroupMemberHook:AtList] Hooked $hookedCount @ list classes")
    }

    // ========================================================================
    // Adapter 方法 Hook
    // ========================================================================

    private fun hookGroupMemberAdapterMethods(clazz: Class<*>, classLoader: ClassLoader) {

        // Hook getCount - 返回集合时过滤
        hookMethodIfExists(clazz, "getCount", emptyArray(), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val result = param.result ?: return
                when (result) {
                    is List<*> -> {
                        val filtered = filterMemberList(result)
                        if (filtered.size != result.size) {
                            XposedBridge.log("${MainHook.TAG}: [GroupMemberHook] getCount list filtered: ${result.size} -> ${filtered.size}")
                            param.result = filtered
                        }
                    }
                    is Collection<*> -> {
                        val filtered = filterMemberList(result.toList())
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
                        val filtered = filterMemberList(result)
                        if (filtered.size != result.size) {
                            param.result = filtered
                        }
                    }
                    is Collection<*> -> {
                        val filtered = filterMemberList(result.toList())
                        if (filtered.size != result.size) {
                            param.result = filtered
                        }
                    }
                }
            }
        })

        // Hook getItem - 对单个成员对象进行过滤
        hookMethodIfExists(clazz, "getItem", arrayOf(Int::class.java), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val item = param.result ?: return
                val wxId = extractMemberWxId(item)
                if (isHiddenMember(wxId)) {
                    XposedBridge.log("${MainHook.TAG}: [GroupMemberHook] getItem - hiding member: $wxId")
                    param.result = null
                }
            }
        })

        // Hook onBindViewHolder - 在 UI 绑定阶段隐藏指定成员条目
        val viewHolderClass = findViewHolderClass(classLoader)
        if (viewHolderClass != null) {
            // 两参数版本
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
                            val wxId = extractMemberWxId(item)
                            if (isHiddenMember(wxId)) {
                                val holder = param.args[0]
                                val itemView = XposedHelpers.getObjectField(holder, "itemView") as? View
                                itemView?.visibility = View.GONE
                                itemView?.layoutParams = itemView?.layoutParams?.also { it.height = 0 }
                                XposedBridge.log("${MainHook.TAG}: [GroupMemberHook] onBindViewHolder - hidden view for: $wxId")
                            }
                        } catch (_: Throwable) { }
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
                            val wxId = extractMemberWxId(item)
                            if (isHiddenMember(wxId)) {
                                val holder = param.args[0]
                                val itemView = XposedHelpers.getObjectField(holder, "itemView") as? View
                                itemView?.visibility = View.GONE
                                itemView?.layoutParams = itemView?.layoutParams?.also { it.height = 0 }
                            }
                        } catch (_: Throwable) { }
                    }
                }
            )
        }
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

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
            XposedBridge.log("${MainHook.TAG}: [GroupMemberHook] hookMethodIfExists failed for ${clazz.name}.$methodName: ${e.message}")
        }
    }

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
            } catch (_: Throwable) { }
            null
        }
    }

    /**
     * 从群成员对象中提取 wxId
     */
    private fun extractMemberWxId(item: Any): String? {
        return try {
            // 策略 1: 尝试直接字段访问
            for (fieldName in MEMBER_FIELD_NAMES) {
                try {
                    val field = item.javaClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    val value = field.get(item) as? String
                    if (!value.isNullOrEmpty()) return value
                } catch (_: Throwable) { }
            }

            // 策略 2: 尝试 getter 方法
            for (methodName in MEMBER_GETTER_NAMES) {
                try {
                    val method = item.javaClass.getDeclaredMethod(methodName)
                    method.isAccessible = true
                    val value = method.invoke(item) as? String
                    if (!value.isNullOrEmpty()) return value
                } catch (_: Throwable) { }
            }

            // 策略 3: 尝试 XposedHelpers 的 getObjectField
            for (fieldName in MEMBER_FIELD_NAMES) {
                try {
                    val value = XposedHelpers.getObjectField(item, fieldName) as? String
                    if (!value.isNullOrEmpty()) return value
                } catch (_: Throwable) { }
            }

            // 策略 4: 遍历所有字段查找 wxId
            try {
                val declaredFields = item.javaClass.declaredFields
                for (field in declaredFields) {
                    try {
                        field.isAccessible = true
                        val value = field.get(item)
                        if (value is String && value.matches(Regex("^[a-zA-Z][a-zA-Z0-9_-]{5,}$"))) {
                            return value
                        }
                    } catch (_: Throwable) { }
                }
            } catch (_: Throwable) { }

            null
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [GroupMemberHook] extractMemberWxId failed: ${e.message}")
            null
        }
    }

    /**
     * 过滤成员列表，移除隐藏好友
     */
    @Suppress("UNCHECKED_CAST")
    private fun filterMemberList(list: List<*>): List<Any> {
        val ctx = MainHook.appContext ?: return list.filterNotNull()
        if (!ConfigManager.isEnabled(ctx)) return list.filterNotNull()
        val hiddenIds = ConfigManager.getHiddenWxIds(ctx)

        return list.filterNot { item ->
            if (item == null) return@filterNot false
            val wxId = extractMemberWxId(item)
            !wxId.isNullOrEmpty() && wxId in hiddenIds
        }.filterNotNull()
    }

    /**
     * 判断成员是否应该被隐藏
     */
    private fun isHiddenMember(wxId: String?): Boolean {
        if (wxId.isNullOrEmpty()) return false
        val ctx = MainHook.appContext ?: return false
        if (!ConfigManager.isEnabled(ctx)) return false
        return ConfigManager.isHidden(ctx, wxId)
    }
}