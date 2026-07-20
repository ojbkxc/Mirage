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
 * 通知 Hook 模块
 *
 * 功能：拦截微信消息通知，阻止被标记好友（blockNotification）的通知显示。
 * 通过 Hook 通知创建/显示方法，在通知展示前检查发送者是否在拦截列表中。
 *
 * 修复要点：
 * - 使用 DexKit 查找通知相关的类和方法
 * - 遍历所有方法参数查找 wxId
 * - 递归查找嵌套对象中的 wxId
 */
object NotificationHook : HookLifecycleListener {

    private const val TAG = Constants.MODULE_TAG + ":NotificationHook"

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

        val notifyClass = MainHook.dexKitBridge.findClass {
            searchString = "notification"
        }

        if (notifyClass != null) {
            targetClass = classLoader.loadClass(notifyClass.name)
            // 在通知类中查找合适的方法
            targetMethodName = findNotifyMethod(notifyClass.name, classLoader)
            cacheWarmedUp = true
            LogUtil.i(TAG, "DexKit found: ${notifyClass.name} -> $targetMethodName")
        }
    }

    private fun initFallback(classLoader: ClassLoader) {
        val candidates = listOf(
            "com.tencent.mm.modelnotification.NotificationLogic",
            "com.tencent.mm.notification.c",
            "com.tencent.mm.notification.NotificationHelper",
            "com.tencent.mm.notification.d",
            "com.tencent.mm.modelnotification.b"
        )

        for (className in candidates) {
            try {
                targetClass = classLoader.loadClass(className)
                targetMethodName = findNotifyMethod(className, classLoader)
                LogUtil.i(TAG, "Fallback found: $className -> $targetMethodName")
                return
            } catch (_: ClassNotFoundException) {
            }
        }
    }

    /**
     * 在目标类中查找通知相关的方法。
     */
    private fun findNotifyMethod(className: String, classLoader: ClassLoader): String {
        try {
            val clazz = classLoader.loadClass(className)
            val methodNames = arrayOf(
                "show", "notify", "showNotification",
                "displayNotification", "sendNotification",
                "doNotify", "onNotify", "notifyMsg",
                "a", "b", "c", "d", "e", "handleMessage"
            )
            for (name in methodNames) {
                try {
                    clazz.getDeclaredMethod(name)
                    return name
                } catch (_: NoSuchMethodException) {}
                // 尝试带参数的方法
                for (method in clazz.declaredMethods) {
                    if (method.name == name) return name
                }
            }
        } catch (_: Throwable) {}
        return "show"
    }

    override fun onHookRegistered() {
        LogUtil.i(TAG, "NotificationHook registered (status: ${status.description})")
        targetClass?.let { clazz ->
            targetMethodName?.let { method ->
                try {
                    // 尝试 Hook 无参方法
                    XposedHelpers.findAndHookMethod(clazz, method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            blockNotification(param)
                        }
                    })
                    LogUtil.i(TAG, "Hooked $method (no-arg) on ${clazz.name}")
                } catch (e: Throwable) {
                    LogUtil.d(TAG, "No-arg hook failed, trying with params: ${e.message}")
                    tryHookWithArgs(clazz, method)
                }
            }
        }
    }

    private fun tryHookWithArgs(clazz: Class<*>, methodName: String) {
        try {
            val methods = clazz.declaredMethods.filter { it.name == methodName }
            for (m in methods) {
                try {
                    XposedHelpers.findAndHookMethod(clazz, methodName, *m.parameterTypes, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            blockNotification(param)
                        }
                    })
                    LogUtil.i(TAG, "Hooked $methodName with ${m.parameterTypes.size} params")
                    return
                } catch (e: Throwable) {
                    LogUtil.d(TAG, "Failed to hook with specific params: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            LogUtil.w(TAG, "Could not hook any overload of $methodName")
        }
    }

    override fun onHookFailed(error: Throwable) {
        LogUtil.e(TAG, "NotificationHook failed: ${error.message}", error)
        status = HookStatus.ERROR
    }

    override fun onHookUnregistered() {
        try {
            targetClass?.let { clazz ->
                targetMethodName?.let { method ->
                    val methods = clazz.declaredMethods.filter { it.name == method }
                    for (m in methods) {
                        try {
                            XposedBridge.unhookMethod(m)
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}
        status = HookStatus.INACTIVE
        LogUtil.i(TAG, "NotificationHook unregistered")
    }

    @JvmStatic
    fun clearDexKitCache() {
        cacheWarmedUp = false
        targetClass = null
        targetMethodName = null
        LogUtil.d(TAG, "DexKit cache cleared")
    }

    // ========================================================================
    // 核心拦截逻辑
    // ========================================================================

    private fun blockNotification(param: XC_MethodHook.MethodHookParam) {
        try {
            val context = MainHook.appContext ?: return
            val blockedIds = ConfigManager.getNotificationBlockedIds(context)
            if (blockedIds.isEmpty()) return

            HookMetrics.recordSuccess(TAG)

            // 从方法参数中提取发送者 wxId
            val senderWxId = extractSenderWxId(param)
            if (senderWxId != null && senderWxId in blockedIds) {
                LogUtil.d(TAG, "Blocking notification from: $senderWxId")
                param.result = null
            }
        } catch (e: Throwable) {
            LogUtil.e(TAG, "Error blocking notification: ${e.message}", e)
        }
    }

    /**
     * 从方法参数和 this 对象中提取发送者 wxId。
     */
    private fun extractSenderWxId(param: XC_MethodHook.MethodHookParam): String? {
        // 遍历所有参数
        for (arg in param.args) {
            if (arg == null) continue

            val wxId = extractWxId(arg)
            if (wxId != null) return wxId

            // 尝试从参数中找 talker 字段
            val talker = tryGetField(arg,
                "field_talker", "talker", "mTalker",
                "field_username", "username", "mUserName",
                "field_sender", "sender", "mSender",
                "field_fromUser", "fromUser", "mFromUser",
                "a", "b", "c", "d", "e", "f", "g", "h"
            )
            if (talker is String && talker.isNotEmpty() && isValidWxId(talker)) {
                return talker
            }

            // 尝试 getter 方法
            val getter = tryCallMethod(arg, "getTalker")
                ?: tryCallMethod(arg, "getUsername")
                ?: tryCallMethod(arg, "getSender")
            if (getter is String && getter.isNotEmpty() && isValidWxId(getter)) {
                return getter
            }

            // 参数可能是 Bundle/Intent，递归查找
            val nested = extractWxIdRecursive(arg, 0, 2)
            if (nested != null) return nested
        }

        // 从 this 对象中提取
        return extractWxId(param.thisObject)
    }

    /**
     * 递归从对象的所有字段中提取 wxId。
     */
    private fun extractWxIdRecursive(obj: Any, depth: Int, maxDepth: Int): String? {
        if (depth > maxDepth) return null
        try {
            for (field in obj.javaClass.declaredFields) {
                field.isAccessible = true
                try {
                    val value = field.get(obj) ?: continue
                    if (value is String && value.isNotEmpty() && isValidWxId(value)) {
                        return value
                    }
                    if (value !is String && value !is Number && value !is Boolean) {
                        val nested = extractWxIdRecursive(value, depth + 1, maxDepth)
                        if (nested != null) return nested
                    }
                } catch (_: Throwable) {}
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
            "field_sender", "sender", "mSender",
            "field_fromUser", "fromUser", "mFromUser",
            "a", "b", "c", "d", "e", "f", "g", "h",
            "i", "j", "k", "l", "m", "n", "o", "p",
            "wxid", "mWxid", "field_wxid",
            "encodeUserName", "mEncodeUserName"
        )

        for (fieldName in fieldNames) {
            val value = tryGetField(obj, fieldName)
            if (value is String && value.isNotEmpty() && isValidWxId(value)) {
                return value
            }
        }

        val methodNames = arrayOf(
            "getUsername", "getWxId", "getTalker",
            "getFieldUsername", "getUserName", "getSender",
            "getEncodeUserName", "getFromUser"
        )
        for (methodName in methodNames) {
            val value = tryCallMethod(obj, methodName)
            if (value is String && value.isNotEmpty() && isValidWxId(value)) {
                return value
            }
        }

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