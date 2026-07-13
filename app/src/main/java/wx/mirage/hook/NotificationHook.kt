package wx.mirage.hook

import android.app.Notification
import android.app.PendingIntent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.query.FindMethodUsingStringsArgs
import org.luckypray.dexkit.query.StringMatcher
import wx.mirage.MainHook
import wx.mirage.config.ConfigManager

/**
 * 通知 Hook - 拦截隐藏好友的消息通知
 *
 * 原理:
 * 1. Hook NotificationManager.notify() 拦截系统通知
 * 2. Hook WeChat 内部通知事件: NewNotificationEvent, SendMsgFailNotificationEvent
 * 3. 多策略 wxId 提取：通知 extras key、PendingIntent、通知标题/内容
 * 4. 使用真实 WeChat 通知 extras key 进行提取
 *
 * 目标真实事件类:
 * - NewNotificationEvent: com.tencent.mm.autogen.events.NewNotificationEvent
 * - SendMsgFailNotificationEvent: com.tencent.mm.autogen.events.SendMsgFailNotificationEvent
 */
object NotificationHook {

    /**
     * 微信通知 extras 中可能包含发送者信息的 key
     * 基于真实微信 APK 分析
     */
    private val WECHAT_NOTIFICATION_KEYS = arrayOf(
        "conversation_id",      // 微信会话 ID
        "username",             // 通用 username
        "wxid",                 // 直接 wxId
        "userName",             // 微信 userName
        "fromUser",             // 发送者
        "talker",               // 会话对方
        "sender",               // 发送者
        "ContactId",            // 联系人 ID
        "msg_source",           // 消息来源
        "chat_user",            // 聊天用户
        "Chat_User",            // 聊天用户（大写）
        "source_user",          // 源用户
        "notify_user",          // 通知用户
        "field_username",       // 混淆字段
        "field_talker"          // 混淆 talker
    )

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        XposedBridge.log("${MainHook.TAG}: [NotificationHook] ========== Initializing ==========")

        try {
            // 策略 1: Hook 系统 NotificationManager.notify()
            hookSystemNotification(lpparam)

            // 策略 2: Hook 微信内部通知事件
            hookWeChatNotificationEvents(lpparam)

            // 策略 3: DexKit 查找通知相关类
            hookNotificationViaDexKit(lpparam)

            XposedBridge.log("${MainHook.TAG}: [NotificationHook] ========== Initialized OK ==========")
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [NotificationHook] Initialization FAILED: ${e.message}")
            XposedBridge.log(e)
        }
    }

    // ========================================================================
    // 策略 1: 系统 NotificationManager.notify() Hook
    // ========================================================================

    /**
     * Hook 系统 NotificationManager.notify()
     * 拦截所有通过系统通知栏发出的微信通知
     */
    private fun hookSystemNotification(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        XposedBridge.log("${MainHook.TAG}: [NotificationHook:System] Hooking NotificationManager.notify()")

        try {
            // Hook notify(String, int, Notification) - 最常用的重载
            XposedHelpers.findAndHookMethod(
                "android.app.NotificationManager",
                classLoader,
                "notify",
                String::class.java,
                Int::class.java,
                Notification::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val notification = param.args[2] as Notification
                            processNotification(notification).also { shouldBlock ->
                                if (shouldBlock) {
                                    XposedBridge.log("${MainHook.TAG}: [NotificationHook:System] Blocking notification (notify with tag)")
                                    param.result = null
                                }
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("${MainHook.TAG}: [NotificationHook:System] notify(tag,id,notification) error: ${e.message}")
                        }
                    }
                }
            )

            // Hook notify(int, Notification) - 无 tag 的重载
            XposedHelpers.findAndHookMethod(
                "android.app.NotificationManager",
                classLoader,
                "notify",
                Int::class.java,
                Notification::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val notification = param.args[1] as Notification
                            processNotification(notification).also { shouldBlock ->
                                if (shouldBlock) {
                                    XposedBridge.log("${MainHook.TAG}: [NotificationHook:System] Blocking notification (notify without tag)")
                                    param.result = null
                                }
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("${MainHook.TAG}: [NotificationHook:System] notify(id,notification) error: ${e.message}")
                        }
                    }
                }
            )

            XposedBridge.log("${MainHook.TAG}: [NotificationHook:System] System NotificationManager hooks registered")

        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [NotificationHook:System] Failed to hook NotificationManager: ${e.message}")
        }
    }

    // ========================================================================
    // 策略 2: 微信内部通知事件 Hook
    // ========================================================================

    /**
     * Hook 微信内部通知事件
     *
     * 真实事件类:
     * - NewNotificationEvent: 新消息通知事件
     * - SendMsgFailNotificationEvent: 发送消息失败通知事件
     */
    private fun hookWeChatNotificationEvents(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        XposedBridge.log("${MainHook.TAG}: [NotificationHook:WeChat] Hooking WeChat internal notification events...")

        // 已知的微信通知事件类
        val notificationEventClasses = listOf(
            "com.tencent.mm.autogen.events.NewNotificationEvent",
            "com.tencent.mm.autogen.events.SendMsgFailNotificationEvent",
            "com.tencent.mm.autogen.events.MsgNotificationEvent",
            "com.tencent.mm.autogen.events.ShowNotificationEvent",
            "com.tencent.mm.autogen.events.ReceiveMsgEvent",
            "com.tencent.mm.autogen.events.NewMsgEvent"
        )

        var hookedCount = 0

        for (className in notificationEventClasses) {
            try {
                val eventClass = classLoader.loadClass(className)
                XposedBridge.log("${MainHook.TAG}: [NotificationHook:WeChat] Found notification event class: $className")

                // 尝试 Hook 事件类的回调方法
                hookEventClassMethods(eventClass, classLoader)
                hookedCount++

            } catch (e: ClassNotFoundException) {
                // 此类在当前微信版本中不存在
            } catch (e: Throwable) {
                XposedBridge.log("${MainHook.TAG}: [NotificationHook:WeChat] Failed to hook $className: ${e.message}")
            }
        }

        XposedBridge.log("${MainHook.TAG}: [NotificationHook:WeChat] Hooked $hookedCount notification event classes")
    }

    /**
     * Hook 事件类的方法
     * 尝试查找并 Hook 事件处理回调方法
     */
    private fun hookEventClassMethods(eventClass: Class<*>, classLoader: ClassLoader) {
        try {
            // 尝试 Hook 常见的回调方法名
            val callbackMethods = arrayOf("callback", "publish", "post", "dispatch", "run", "handleEvent")

            for (methodName in callbackMethods) {
                try {
                    // 查找无参方法
                    XposedHelpers.findAndHookMethod(
                        eventClass,
                        methodName,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    // 尝试从事件对象中提取 talker/username
                                    val event = param.thisObject
                                    val wxId = extractWxIdFromEventObject(event)
                                    if (!wxId.isNullOrEmpty() && isHiddenWxId(wxId)) {
                                        XposedBridge.log("${MainHook.TAG}: [NotificationHook:WeChat] Blocking event for: $wxId")
                                        param.result = null
                                    }
                                } catch (_: Throwable) {
                                    // 忽略
                                }
                            }
                        }
                    )
                    XposedBridge.log("${MainHook.TAG}: [NotificationHook:WeChat] Hooked ${eventClass.name}.$methodName()")
                    return
                } catch (_: NoSuchMethodError) {
                    // 此方法不存在，继续尝试下一个
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [NotificationHook:WeChat] Failed to hook event methods for ${eventClass.name}: ${e.message}")
        }
    }

    /**
     * 从微信事件对象中提取 wxId
     */
    private fun extractWxIdFromEventObject(event: Any): String? {
        return try {
            // 尝试常见的字段名
            val fieldNames = arrayOf(
                "talker", "field_talker", "username", "field_username",
                "userName", "field_userName", "fromUser", "sender",
                "wxId", "field_wxId", "mUserName"
            )

            for (fieldName in fieldNames) {
                try {
                    val value = XposedHelpers.getObjectField(event, fieldName) as? String
                    if (!value.isNullOrEmpty()) return value
                } catch (_: Throwable) {
                    // 字段不存在
                }
            }

            // 尝试获取嵌套的 data 对象
            try {
                val data = XposedHelpers.getObjectField(event, "data")
                if (data != null) {
                    for (fieldName in fieldNames) {
                        try {
                            val value = XposedHelpers.getObjectField(data, fieldName) as? String
                            if (!value.isNullOrEmpty()) return value
                        } catch (_: Throwable) {
                            // 忽略
                        }
                    }
                }
            } catch (_: Throwable) {
                // 忽略
            }

            null
        } catch (e: Throwable) {
            null
        }
    }

    // ========================================================================
    // 策略 3: DexKit 查找通知相关类
    // ========================================================================

    /**
     * 使用 DexKit 查找通知相关类和方法
     *
     * 搜索字符串: "NotificationManager", "notify", "notificationId",
     *              "NewNotificationEvent", "SendMsgFailNotificationEvent"
     */
    private fun hookNotificationViaDexKit(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!MainHook.dexKitAvailable) return

        val classLoader = lpparam.classLoader
        val dexKit = MainHook.dexKitBridge

        try {
            XposedBridge.log("${MainHook.TAG}: [NotificationHook:DexKit] Searching for notification methods...")

            val results = dexKit.batchFindMethodUsingStrings(
                FindMethodUsingStringsArgs.Builder()
                    .searchInClass(true)
                    .addString("NotificationManager")
                    .addString("notify")
                    .addString("NewNotificationEvent")
                    .addString("SendMsgFailNotificationEvent")
                    .addMatcher(StringMatcher("contains", true))
                    .build()
            )

            XposedBridge.log("${MainHook.TAG}: [NotificationHook:DexKit] Found ${results.size} methods")

            // ===== WAuxiliary 验证过的 MicroMsg.Notification 日志模式 =====
            XposedBridge.log("${MainHook.TAG}: [NotificationHook:DexKit] Searching for MicroMsg.Notification (WAuxiliary-verified)")
            val microMsgResults = dexKit.batchFindMethodUsingStrings(
                FindMethodUsingStringsArgs.Builder()
                    .searchInClass(true)
                    .addString("MicroMsg.Notification")
                    .addMatcher(StringMatcher("contains", true))
                    .build()
            )
            XposedBridge.log("${MainHook.TAG}: [NotificationHook:DexKit] Found ${microMsgResults.size} methods with MicroMsg.Notification")

            val allResults = (results + microMsgResults).distinctBy { "${it.className}.${it.name}" }

            val hookedClasses = mutableSetOf<String>()

            for (methodData in allResults) {
                try {
                    val className = methodData.className
                    if (className in hookedClasses) continue
                    hookedClasses.add(className)

                    val clazz = classLoader.loadClass(className)
                    XposedBridge.log("${MainHook.TAG}: [NotificationHook:DexKit] Hooking class: $className")

                    // 查找并 Hook 通知相关方法
                    hookNotificationClassMethods(clazz)

                } catch (e: Throwable) {
                    XposedBridge.log("${MainHook.TAG}: [NotificationHook:DexKit] Failed to hook $className: ${e.message}")
                }
            }

        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [NotificationHook:DexKit] DexKit search FAILED: ${e.message}")
        }
    }

    /**
     * Hook 通知相关类的方法
     */
    private fun hookNotificationClassMethods(clazz: Class<*>) {
        try {
            // 查找包含 "notify" 或 "show" 或 "send" 的方法
            for (method in clazz.declaredMethods) {
                val methodName = method.name.lowercase()
                if (methodName.contains("notify") || methodName.contains("show") ||
                    methodName.contains("send") || methodName.contains("push")) {

                    XposedBridge.log("${MainHook.TAG}: [NotificationHook:DexKit] Hooking method: ${clazz.name}.${method.name}")

                    XposedHelpers.findAndHookMethod(
                        clazz,
                        method.name,
                        *method.parameterTypes,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    // 检查参数中是否包含 Notification 对象
                                    for (arg in param.args) {
                                        if (arg is Notification) {
                                            processNotification(arg).also { shouldBlock ->
                                                if (shouldBlock) {
                                                    XposedBridge.log("${MainHook.TAG}: [NotificationHook:DexKit] Blocking notification via ${clazz.name}.${method.name}")
                                                    param.result = null
                                                }
                                            }
                                            return
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
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [NotificationHook:DexKit] Failed to hook methods for ${clazz.name}: ${e.message}")
        }
    }

    // ========================================================================
    // 通知处理核心逻辑
    // ========================================================================

    /**
     * 处理通知，判断是否应该拦截
     * @return true 表示应该拦截此通知
     */
    private fun processNotification(notification: Notification): Boolean {
        val context = MainHook.appContext ?: return false
        if (!ConfigManager.isEnabled(context)) return false

        val wxId = extractWxIdFromNotification(notification)
        if (!wxId.isNullOrEmpty()) {
            if (ConfigManager.isHidden(context, wxId)) {
                XposedBridge.log("${MainHook.TAG}: [NotificationHook] Blocked notification from hidden friend: $wxId")
                return true
            }
        }

        // 额外检查：如果无法获取 wxId，尝试通过标题进行匹配
        val title = getNotificationTitle(notification)
        if (!title.isNullOrEmpty() && shouldHideByTitle(title, context)) {
            XposedBridge.log("${MainHook.TAG}: [NotificationHook] Blocked notification by title match: $title")
            return true
        }

        return false
    }

    /**
     * 从 Notification 对象中提取发送者的 wxId
     * 尝试多种提取策略
     */
    private fun extractWxIdFromNotification(notification: Notification): String? {
        val extras = notification.extras ?: return null

        // 策略 1: 直接读取已知的微信特定 extras key
        for (key in WECHAT_NOTIFICATION_KEYS) {
            try {
                val value = extras.getString(key)
                if (!value.isNullOrEmpty()) {
                    XposedBridge.log("${MainHook.TAG}: [NotificationHook] Found wxId from extras key '$key': $value")
                    return value
                }
            } catch (_: Throwable) {
                // 忽略
            }
        }

        // 策略 2: 遍历 extras 中所有 String 类型的值，查找看起来像 wxId 的
        try {
            val keys = extras.keySet()
            for (key in keys) {
                try {
                    val value = extras.getString(key)
                    if (!value.isNullOrEmpty() && looksLikeWxId(value)) {
                        XposedBridge.log("${MainHook.TAG}: [NotificationHook] Found potential wxId from extras[$key]: $value")
                        return value
                    }
                } catch (_: Throwable) {
                    // 忽略
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [NotificationHook] Error iterating extras: ${e.message}")
        }

        // 策略 3: 从 contentIntent 中提取 wxId
        try {
            val contentIntent = notification.contentIntent
            if (contentIntent != null) {
                val wxIdFromIntent = extractWxIdFromPendingIntent(contentIntent)
                if (!wxIdFromIntent.isNullOrEmpty()) {
                    return wxIdFromIntent
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [NotificationHook] Error extracting from contentIntent: ${e.message}")
        }

        // 策略 4: 从 deleteIntent 中提取
        try {
            val deleteIntent = notification.deleteIntent
            if (deleteIntent != null) {
                val wxIdFromIntent = extractWxIdFromPendingIntent(deleteIntent)
                if (!wxIdFromIntent.isNullOrEmpty()) {
                    return wxIdFromIntent
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [NotificationHook] Error extracting from deleteIntent: ${e.message}")
        }

        // 策略 5: 解析通知标题和内容，尝试通过微信特定格式推断
        val title = getNotificationTitle(notification)
        if (!title.isNullOrEmpty()) {
            // 如果标题本身就是 wxId 格式
            if (looksLikeWxId(title)) {
                XposedBridge.log("${MainHook.TAG}: [NotificationHook] Title looks like wxId: $title")
                return title
            }
        }

        // 策略 6: 从通知的 tag 或 group 中提取
        try {
            val group = notification.group
            if (!group.isNullOrEmpty() && looksLikeWxId(group)) {
                return group
            }
        } catch (_: Throwable) {
            // 忽略
        }

        return null
    }

    /**
     * 从 PendingIntent 中提取 wxId
     */
    private fun extractWxIdFromPendingIntent(pendingIntent: PendingIntent): String? {
        try {
            // 通过反射获取 PendingIntent 内部的 Intent
            val intentField = XposedHelpers.findFieldIfExists(PendingIntent::class.java, "mIntent")
            if (intentField != null) {
                intentField.isAccessible = true
                val intent = intentField.get(pendingIntent) as? android.content.Intent
                if (intent != null) {
                    // 检查 Intent 的 extras
                    val bundle = intent.extras
                    if (bundle != null) {
                        for (key in WECHAT_NOTIFICATION_KEYS) {
                            val value = bundle.getString(key)
                            if (!value.isNullOrEmpty()) {
                                XposedBridge.log("${MainHook.TAG}: [NotificationHook] Found wxId from PendingIntent extras[$key]: $value")
                                return value
                            }
                        }
                    }

                    // 检查 Intent 的 data
                    val data = intent.data
                    if (data != null) {
                        val lastPath = data.lastPathSegment
                        if (!lastPath.isNullOrEmpty() && looksLikeWxId(lastPath)) {
                            return lastPath
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("${MainHook.TAG}: [NotificationHook] Error reflecting PendingIntent: ${e.message}")
        }
        return null
    }

    /**
     * 获取通知标题
     */
    private fun getNotificationTitle(notification: Notification): String? {
        return try {
            val extras = notification.extras ?: return null
            extras.getString(Notification.EXTRA_TITLE)
                ?: extras.getString("android.title")
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 判断字符串是否看起来像 wxId
     */
    private fun looksLikeWxId(str: String): Boolean {
        return str.matches(Regex("^[a-zA-Z][a-zA-Z0-9_-]{5,}$")) ||
               str.matches(Regex("^wxid_[a-zA-Z0-9]{10,}$"))
    }

    /**
     * 如果无法直接获取 wxId，尝试通过通知标题匹配隐藏列表
     */
    private fun shouldHideByTitle(title: String, context: android.content.Context): Boolean {
        val hiddenIds = ConfigManager.getHiddenWxIds(context)
        if (hiddenIds.isEmpty()) return false

        for (wxId in hiddenIds) {
            if (title == wxId || title.contains(wxId)) {
                XposedBridge.log("${MainHook.TAG}: [NotificationHook] Title matches hidden wxId: $wxId")
                return true
            }
        }

        return false
    }

    /**
     * 判断 wxId 是否应该被隐藏
     */
    private fun isHiddenWxId(wxId: String?): Boolean {
        if (wxId.isNullOrEmpty()) return false
        val ctx = MainHook.appContext ?: return false
        return ConfigManager.isEnabled(ctx) && ConfigManager.isHidden(ctx, wxId)
    }
}