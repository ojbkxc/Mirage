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
 * 消息防撤回 Hook 模块
 *
 * 功能：拦截微信消息撤回机制，防止好友撤回消息。
 * 通过 Hook 消息撤回方法，在被撤回前拦截操作。
 *
 * 实现方式：
 * - Hook 系统消息处理方法（检测撤回消息）
 * - 阻止撤回操作执行
 */
object MessageAntiRevokeHook : HookLifecycleListener {

    private const val TAG = Constants.MODULE_TAG + ":MessageAntiRevokeHook"

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

        // 查找消息撤回相关类
        val revokeClass = MainHook.dexKitBridge.findClass {
            searchString = "revoke"
        }

        if (revokeClass != null) {
            targetClass = classLoader.loadClass(revokeClass.name)
            targetMethodName = findRevokeMethod(revokeClass.name, classLoader)
            cacheWarmedUp = true
            LogUtil.i(TAG, "DexKit found: ${revokeClass.name}")
        }

        // 尝试查找消息处理类
        if (targetClass == null) {
            val msgClass = MainHook.dexKitBridge.findClass {
                searchString = "message"
                searchPackage = "com.tencent.mm.model"
            }
            if (msgClass != null) {
                targetClass = classLoader.loadClass(msgClass.name)
                targetMethodName = findRevokeMethod(msgClass.name, classLoader)
                cacheWarmedUp = true
                LogUtil.i(TAG, "DexKit found (message): ${msgClass.name}")
            }
        }
    }

    private fun initFallback(classLoader: ClassLoader) {
        val candidates = listOf(
            "com.tencent.mm.model.b",
            "com.tencent.mm.model.c",
            "com.tencent.mm.modelmessage.MessageRevoke",
            "com.tencent.mm.modelmessage.RevokeMsg",
            "com.tencent.mm.storage.bi",
            "com.tencent.mm.model.aj",
            "com.tencent.mm.model.ak",
            "com.tencent.mm.app.plugin.voicereminder.a",
            "${Constants.WECHAT_CHATTING_COMPONENT}.ChattingUIFragment"
        )

        for (className in candidates) {
            try {
                targetClass = classLoader.loadClass(className)
                targetMethodName = findRevokeMethod(className, classLoader)
                LogUtil.i(TAG, "Fallback found: $className -> $targetMethodName")
                if (targetMethodName != null) return
            } catch (_: ClassNotFoundException) {
            }
        }
    }

    private fun findRevokeMethod(className: String, classLoader: ClassLoader): String? {
        try {
            val clazz = classLoader.loadClass(className)
            val methodNames = arrayOf(
                "a", "b", "c", "d", "e", "f", "g", "h",
                "i", "j", "k", "l", "m", "n", "o", "p",
                "handleMessage", "onReceive", "onRevokeMsg",
                "revoke", "revokeMsg", "doRevoke",
                "processRevoke", "onRevoke"
            )
            for (name in methodNames) {
                try {
                    for (method in clazz.declaredMethods) {
                        if (method.name == name) return name
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return null
    }

    override fun onHookRegistered() {
        LogUtil.i(TAG, "MessageAntiRevokeHook registered (status: ${status.description})")
        targetClass?.let { clazz ->
            targetMethodName?.let { method ->
                try {
                    XposedHelpers.findAndHookMethod(clazz, method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            blockRevoke(param)
                        }
                    })
                    LogUtil.i(TAG, "Hooked $method on ${clazz.name}")
                } catch (e: Throwable) {
                    LogUtil.w(TAG, "Failed to hook $method: ${e.message}")
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
                            blockRevoke(param)
                        }
                    })
                    LogUtil.i(TAG, "Hooked $methodName with ${m.parameterTypes.size} params")
                    return
                } catch (e: Throwable) {
                    LogUtil.d(TAG, "Failed to hook specific params: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            LogUtil.w(TAG, "Could not hook any overload of $methodName")
        }
    }

    override fun onHookFailed(error: Throwable) {
        LogUtil.e(TAG, "MessageAntiRevokeHook failed: ${error.message}", error)
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
        LogUtil.i(TAG, "MessageAntiRevokeHook unregistered")
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

    private fun blockRevoke(param: XC_MethodHook.MethodHookParam) {
        try {
            HookMetrics.recordSuccess(TAG)

            // 检查参数中是否包含撤回相关的消息标识
            val isRevokeMsg = checkIfRevokeMessage(param)
            if (isRevokeMsg) {
                LogUtil.d(TAG, "Blocking message revoke")
                param.result = null
            }
        } catch (e: Throwable) {
            LogUtil.e(TAG, "Error blocking revoke: ${e.message}", e)
        }
    }

    /**
     * 检查方法调用是否涉及消息撤回。
     */
    private fun checkIfRevokeMessage(param: XC_MethodHook.MethodHookParam): Boolean {
        // 检查方法参数中是否包含撤回类型标识
        for (arg in param.args) {
            if (arg == null) continue
            try {
                // 检查消息类型字段
                val type = tryGetField(arg, "field_type", "type", "mType", "msgType", "field_msgType")
                // 微信的消息撤回类型通常是 10000 (系统消息)
                if (type is Int && type == 10000) return true
                if (type is Long && type == 10000L) return true

                // 检查内容字段
                val content = tryGetField(arg, "field_content", "content", "mContent")
                if (content is String && (content.contains("revoke") || content.contains("撤回"))) {
                    return true
                }
            } catch (_: Throwable) {}
        }
        return false
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
}