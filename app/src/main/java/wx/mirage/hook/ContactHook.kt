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
 * 联系人列表 Hook - 在联系人列表中隐藏指定好友
 *
 * 原理:
 * 1. 使用 DexKit 按真实微信字符串特征查找联系人相关类和方法
 * 2. Hook 联系人列表 Adapter 的 getCount/getItem/getItemCount 方法
 * 3. Hook 数据库 Cursor 的 moveToNext/moveToFirst 方法，跳过隐藏好友行
 * 4. Hook ViewHolder 的 onBindViewHolder 方法，隐藏对应的 itemView
 * 5. 多重 wxId 提取策略，确保在各种混淆环境下都能正确匹配
 *
 * 目标真实类:
 * - SelectContactUI: com.tencent.mm.ui.contact.SelectContactUI
 * - SnsLabelContactListUI: com.tencent.mm.ui.contact.SnsLabelContactListUI
 * - 联系人 Adapter: com.tencent.mm.ui.contact 包下的各类 Adapter
 */
object ContactHook {

    /**
     * 联系人 wxId 提取策略字段名（按优先级排序）
     * 基于真实微信 APK 分析的字段名
     */
    private val WXID_FIELD_NAMES = arrayOf(
        "field_username",   // 微信混淆后最常见的字段名
        "username",         // 通用字段名
        "mUserName",        // 内部字段名
        "uin",              // 微信 UIN
        "alias",            // 微信号
        "field_wxId",
        "field_userId",
        "wxId",
        "userName",
        "mUsername"
    )

    private val WXID_GETTER_NAMES = arrayOf(
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
        XposedBridge.log("${MainHook.TAG}: [ContactHook] ========== Initializing ==========")

        try {
            // 策略 1: 通过 DexKit 查找联系人相关方法
            if (MainHook.dexKitAvailable) {
                hookContactViaDexKit(lpparam)
            } else {
                XposedBridge.log("${MainHook.TAG}: [ContactHook] DexKit not available, using direct class detection")
            }

            // 策略 2: 直接 Hook 已知的联系人 UI 类
            hookKnownContactUI(lpparam)

            // 策略 3: Hook 联系人数据库 Cursor
            hookContactCursor(lpparam)

            XposedBridge.log("${MainHook.TAG}: [ContactHook] ========== Initialized OK ==========")
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [ContactHook] Initialization FAILED: ${e.message}")
            XposedBridge.log(e)
        }
    }

    // ========================================================================
    // 策略 1: DexKit 动态查找
    // ========================================================================

    /**
     * 使用 DexKit 按真实微信字符串特征查找联系人相关类和方法
     *
     * 搜索字符串: "username", "nickname", "alias", "conRemark",
     *              "pyInitial", "quanPin", "rcontact"
     */
    private fun hookContactViaDexKit(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        val dexKit = MainHook.dexKitBridge

        try {
            XposedBridge.log("${MainHook.TAG}: [ContactHook:DexKit] Searching for contact methods...")

            // 第一轮搜索：按联系人字段特征字符串查找
            val results = dexKit.batchFindMethodUsingStrings(
                FindMethodUsingStringsArgs.Builder()
                    .searchInClass(true)
                    .addString("username")
                    .addString("nickname")
                    .addString("alias")
                    .addString("conRemark")
                    .addString("pyInitial")
                    .addString("quanPin")
                    .addMatcher(StringMatcher("contains", true))
                    .build()
            )

            XposedBridge.log("${MainHook.TAG}: [ContactHook:DexKit] Found ${results.size} methods with contact field strings")

            // 第二轮搜索：按 rcontact 表名查找
            val rcontactResults = dexKit.batchFindMethodUsingStrings(
                FindMethodUsingStringsArgs.Builder()
                    .searchInClass(true)
                    .addString("rcontact")
                    .addMatcher(StringMatcher("contains", true))
                    .build()
            )

            XposedBridge.log("${MainHook.TAG}: [ContactHook:DexKit] Found ${rcontactResults.size} methods with 'rcontact'")

            // 合并所有找到的方法
            val allResults = (results + rcontactResults).distinctBy { "${it.className}.${it.name}" }

            XposedBridge.log("${MainHook.TAG}: [ContactHook:DexKit] Total unique methods: ${allResults.size}")

            // 对每个找到的类进行 Hook
            val hookedClasses = mutableSetOf<String>()

            for (methodData in allResults) {
                try {
                    val className = methodData.className
                    if (className in hookedClasses) continue
                    hookedClasses.add(className)

                    val clazz = classLoader.loadClass(className)
                    XposedBridge.log("${MainHook.TAG}: [ContactHook:DexKit] Hooking class: $className")

                    // 注册 Adapter 相关 Hook
                    hookContactAdapterMethods(clazz, classLoader)

                } catch (e: Throwable) {
                    XposedBridge.log("${MainHook.TAG}: [ContactHook:DexKit] Failed to hook $className: ${e.message}")
                }
            }

        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [ContactHook:DexKit] DexKit search FAILED: ${e.message}")
            XposedBridge.log(e)
        }
    }

    // ========================================================================
    // 策略 2: 直接 Hook 已知的真实联系人 UI 类
    // ========================================================================

    /**
     * 直接 Hook 已知的微信联系人 UI 类
     *
     * 真实类名:
     * - SelectContactUI: com.tencent.mm.ui.contact.SelectContactUI
     * - SnsLabelContactListUI: com.tencent.mm.ui.contact.SnsLabelContactListUI
     * - 联系人 Adapter 通常位于 com.tencent.mm.ui.contact 包下
     */
    private fun hookKnownContactUI(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        XposedBridge.log("${MainHook.TAG}: [ContactHook:KnownUI] Hooking known contact UI classes...")

        // 已知的真实联系人 UI 类及它们可能的 Adapter 方法
        val knownUIClasses = listOf(
            "com.tencent.mm.ui.contact.SelectContactUI",
            "com.tencent.mm.ui.contact.SnsLabelContactListUI",
            "com.tencent.mm.ui.contact.ContactInfoUI",
            "com.tencent.mm.ui.contact.AddressUI",
            "com.tencent.mm.ui.contact.ContactWidget",
            // 可能的联系人 Adapter 内部类
            "com.tencent.mm.ui.contact.a",
            "com.tencent.mm.ui.contact.b",
            "com.tencent.mm.ui.contact.c",
            "com.tencent.mm.ui.contact.d",
            "com.tencent.mm.ui.contact.e",
            "com.tencent.mm.ui.contact.f",
            "com.tencent.mm.ui.contact.g",
            "com.tencent.mm.ui.contact.h",
            "com.tencent.mm.ui.contact.i",
            "com.tencent.mm.ui.contact.j",
            "com.tencent.mm.ui.contact.k",
            "com.tencent.mm.ui.contact.l",
            "com.tencent.mm.ui.contact.m",
            "com.tencent.mm.ui.contact.n",
            "com.tencent.mm.ui.contact.o",
            "com.tencent.mm.ui.contact.p",
            "com.tencent.mm.ui.contact.q",
            "com.tencent.mm.ui.contact.r",
            "com.tencent.mm.ui.contact.s",
            "com.tencent.mm.ui.contact.t",
            "com.tencent.mm.ui.contact.u",
            "com.tencent.mm.ui.contact.v",
            "com.tencent.mm.ui.contact.w",
            "com.tencent.mm.ui.contact.x",
            "com.tencent.mm.ui.contact.y",
            "com.tencent.mm.ui.contact.z"
        )

        var hookedCount = 0

        for (className in knownUIClasses) {
            try {
                val clazz = classLoader.loadClass(className)
                XposedBridge.log("${MainHook.TAG}: [ContactHook:KnownUI] Found class: $className")
                hookContactAdapterMethods(clazz, classLoader)
                hookedCount++
            } catch (e: ClassNotFoundException) {
                // 此类在当前微信版本中不存在，跳过
            } catch (e: Throwable) {
                XposedBridge.log("${MainHook.TAG}: [ContactHook:KnownUI] Failed to hook $className: ${e.message}")
            }
        }

        XposedBridge.log("${MainHook.TAG}: [ContactHook:KnownUI] Hooked $hookedCount known classes")
    }

    // ========================================================================
    // Adapter 方法 Hook
    // ========================================================================

    /**
     * 对指定的 Adapter 类注册所有相关 Hook
     */
    private fun hookContactAdapterMethods(clazz: Class<*>, classLoader: ClassLoader) {
        val className = clazz.name

        // Hook getCount - 返回集合时过滤
        hookMethodIfExists(clazz, "getCount", emptyArray(), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val result = param.result ?: return
                when (result) {
                    is List<*> -> {
                        val filtered = filterContactList(result)
                        if (filtered.size != result.size) {
                            XposedBridge.log("${MainHook.TAG}: [ContactHook] getCount list filtered: ${result.size} -> ${filtered.size}")
                            param.result = filtered
                        }
                    }
                    is Collection<*> -> {
                        val filtered = filterContactList(result.toList())
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
                        val filtered = filterContactList(result)
                        if (filtered.size != result.size) {
                            param.result = filtered
                        }
                    }
                    is Collection<*> -> {
                        val filtered = filterContactList(result.toList())
                        if (filtered.size != result.size) {
                            param.result = filtered
                        }
                    }
                }
            }
        })

        // Hook getItem - 对单个联系人对象进行过滤
        hookMethodIfExists(clazz, "getItem", arrayOf(Int::class.java), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val item = param.result ?: return
                val wxId = extractWxIdFromContactItem(item)
                if (isHiddenContact(wxId)) {
                    XposedBridge.log("${MainHook.TAG}: [ContactHook] getItem - hiding contact: $wxId")
                    param.result = null
                }
            }
        })

        // Hook onBindViewHolder - 在 UI 绑定阶段隐藏指定条目
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

                            // 尝试获取该位置的数据项
                            val item = tryGetItemAtPosition(adapter, position) ?: return

                            val wxId = extractWxIdFromContactItem(item)
                            if (isHiddenContact(wxId)) {
                                val holder = param.args[0]
                                val itemView = XposedHelpers.getObjectField(holder, "itemView") as? View
                                itemView?.visibility = View.GONE
                                itemView?.layoutParams = itemView?.layoutParams?.also {
                                    it.height = 0
                                }
                                XposedBridge.log("${MainHook.TAG}: [ContactHook] onBindViewHolder - hidden view for: $wxId")
                            }
                        } catch (_: Throwable) {
                            // 反射异常，忽略
                        }
                    }
                }
            )
        }

        // Hook onBindViewHolder (如果有两个参数的版本)
        hookMethodIfExists(
            clazz,
            "onBindViewHolder",
            arrayOf(viewHolderClass ?: Any::class.java, Int::class.java, List::class.java),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val adapter = param.thisObject
                        val position = param.args[1] as Int

                        val item = tryGetItemAtPosition(adapter, position) ?: return
                        val wxId = extractWxIdFromContactItem(item)

                        if (isHiddenContact(wxId)) {
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

    // ========================================================================
    // 策略 3: Cursor 层 Hook
    // ========================================================================

    /**
     * Hook 联系人数据库 Cursor
     * 在数据库查询结果中排除隐藏好友
     */
    private fun hookContactCursor(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        val dexKit = MainHook.dexKitBridge

        val hookedCursorClasses = mutableSetOf<String>()

        try {
            if (MainHook.dexKitAvailable) {
                // 通过 DexKit 精确定位微信联系人查询方法
                val results = dexKit.batchFindMethodUsingStrings(
                    FindMethodUsingStringsArgs.Builder()
                        .searchInClass(true)
                        .addString("rcontact")
                        .addString("username")
                        .addString("nickname")
                        .build()
                )

                for (methodData in results) {
                    try {
                        val clazz = classLoader.loadClass(methodData.className)

                        // 查找并 Hook 返回 Cursor 的方法
                        for (method in clazz.declaredMethods) {
                            if (!Cursor::class.java.isAssignableFrom(method.returnType)) continue

                            XposedBridge.log("${MainHook.TAG}: [ContactHook:Cursor] Hooking cursor method: ${clazz.name}.${method.name}")

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

                                        hookCursorClass(cursor.javaClass)
                                    }
                                }
                            )
                        }
                    } catch (e: Throwable) {
                        XposedBridge.log("${MainHook.TAG}: [ContactHook:Cursor] Failed to hook ${methodData.className}: ${e.message}")
                    }
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [ContactHook:Cursor] DexKit search failed: ${e.message}")
        }

        // 降级：直接尝试 Hook 常见的 Cursor 包装类
        hookCommonCursorWrappers(classLoader, hookedCursorClasses)
    }

    /**
     * 降级方案：尝试 Hook 常见的 Cursor 实现类
     */
    private fun hookCommonCursorWrappers(classLoader: ClassLoader, hookedCursorClasses: MutableSet<String>) {
        XposedBridge.log("${MainHook.TAG}: [ContactHook:Cursor] Trying common cursor wrapper classes...")

        // 常见 Cursor 路径模式
        val cursorPatterns = listOf(
            "com.tencent.wcdb",
            "com.tencent.mm.storagebase",
            "com.tencent.mm.db",
            "net.sqlcipher"
        )

        // 尝试查找任何实现了 Cursor 接口的已知类
        try {
            // 通过 classLoader 尝试加载常见的 Cursor 实现类
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
                        XposedBridge.log("${MainHook.TAG}: [ContactHook:Cursor] Hooking common cursor class: ${clazz.name}")
                        hookedCursorClasses.add(clazz.name)
                        hookCursorClass(clazz)
                    }
                } catch (_: Throwable) {
                    // 类不存在
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [ContactHook:Cursor] Common cursor hook failed: ${e.message}")
        }
    }

    /**
     * 对指定的 Cursor 实现类进行 Hook，自动跳过隐藏好友行
     */
    private fun hookCursorClass(cursorClass: Class<*>) {
        try {
            XposedBridge.log("${MainHook.TAG}: [ContactHook:Cursor] Hooking cursor class: ${cursorClass.name}")

            // Hook moveToNext - 当指向隐藏好友时自动跳到下一行
            XposedHelpers.findAndHookMethod(cursorClass, "moveToNext", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result != true) return
                    val cursor = param.thisObject as Cursor
                    val originalMethod = param.method

                    // 最多跳过 1000 行，防止死循环
                    var skipCount = 0
                    while (skipCount < 1000) {
                        val wxId = getCursorUsername(cursor)
                        if (!isHiddenContact(wxId)) break
                        skipCount++
                        val moved = XposedBridge.invokeOriginalMethod(originalMethod, cursor, emptyArray()) as Boolean
                        if (!moved) {
                            param.result = false
                            return
                        }
                    }
                    if (skipCount > 0) {
                        XposedBridge.log("${MainHook.TAG}: [ContactHook:Cursor] Skipped $skipCount hidden contacts")
                    }
                }
            })

            // Hook moveToFirst - 当首行是隐藏好友时自动跳到下一行
            XposedHelpers.findAndHookMethod(cursorClass, "moveToFirst", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result != true) return
                    val cursor = param.thisObject as Cursor
                    val originalMethod = param.method

                    var skipCount = 0
                    while (skipCount < 1000) {
                        val wxId = getCursorUsername(cursor)
                        if (!isHiddenContact(wxId)) break
                        skipCount++
                        val moved = XposedBridge.invokeOriginalMethod(originalMethod, cursor, emptyArray()) as Boolean
                        if (!moved) {
                            param.result = false
                            return
                        }
                    }
                }
            })

            // Hook getString - 对 username 列做二次保护
            XposedHelpers.findAndHookMethod(cursorClass, "getString", Int::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val cursor = param.thisObject as Cursor
                    val columnIndex = param.args[0] as Int
                    val columnName = try {
                        cursor.getColumnName(columnIndex)
                    } catch (_: Throwable) {
                        return
                    }
                    if (columnName.equals("username", ignoreCase = true)) {
                        val wxId = param.result as? String ?: return
                        if (isHiddenContact(wxId)) {
                            param.result = ""
                        }
                    }
                }
            })

            // Hook getCount - 对 Cursor 的 count 进行修正
            try {
                XposedHelpers.findAndHookMethod(cursorClass, "getCount", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 此 Hook 用于确保即使 Cursor 层有隐藏好友，count 也已修正
                        // 由于 moveToNext 已跳过，实际上不需要额外处理
                    }
                })
            } catch (_: Throwable) {
                // getCount 可能不存在
            }

        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [ContactHook:Cursor] Failed to hook cursor class ${cursorClass.name}: ${e.message}")
        }
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 从 Cursor 当前行中提取 username
     */
    private fun getCursorUsername(cursor: Cursor): String? {
        return try {
            // 尝试多个可能的列名
            val columnNames = arrayOf("username", "field_username", "mUserName", "alias", "uin")
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
            XposedBridge.log("${MainHook.TAG}: [ContactHook] hookMethodIfExists failed for ${clazz.name}.$methodName: ${e.message}")
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
            // 尝试 getItem 的其他重载
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
     * 过滤联系人列表/集合，移除隐藏好友
     */
    private fun filterContactList(list: List<*>): List<Any> {
        val ctx = MainHook.appContext ?: return list.filterNotNull()
        if (!ConfigManager.isEnabled(ctx)) return list.filterNotNull()
        val hiddenIds = ConfigManager.getHiddenWxIds(ctx)

        return list.filterNot { item ->
            if (item == null) return@filterNot false
            val wxId = extractWxIdFromContactItem(item)
            !wxId.isNullOrEmpty() && wxId in hiddenIds
        }.filterNotNull()
    }

    /**
     * 从微信联系人对象中反射提取 wxId
     * 使用多重策略，确保在各种微信版本中都能正确提取
     */
    private fun extractWxIdFromContactItem(item: Any): String? {
        return try {
            // 策略 1: 尝试直接字段访问
            for (fieldName in WXID_FIELD_NAMES) {
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
            for (methodName in WXID_GETTER_NAMES) {
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
            for (fieldName in WXID_FIELD_NAMES) {
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
            XposedBridge.log("${MainHook.TAG}: [ContactHook] extractWxIdFromContactItem failed: ${e.message}")
            null
        }
    }

    /**
     * 判断联系人是否应该被隐藏
     */
    private fun isHiddenContact(wxId: String?): Boolean {
        if (wxId.isNullOrEmpty()) return false
        val ctx = MainHook.appContext ?: return false
        if (!ConfigManager.isEnabled(ctx)) return false
        return ConfigManager.isHidden(ctx, wxId)
    }

    /**
     * 判断一个 wxId 是否应该被隐藏（保留原有方法以兼容外部调用）
     */
    fun shouldHide(wxId: String?): Boolean {
        if (wxId.isNullOrEmpty()) return false
        val ctx = MainHook.appContext ?: return false
        return ConfigManager.isEnabled(ctx) && ConfigManager.isHidden(ctx, wxId)
    }
}