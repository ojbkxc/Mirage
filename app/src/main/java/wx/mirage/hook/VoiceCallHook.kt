package wx.mirage.hook

import android.widget.Toast
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
 * 语音/视频通话 Hook 模块
 *
 * 功能：
 * a) 拦截来电/去电语音和视频通话，阻止被标记好友的通话
 * b) 阻止来电通知显示
 *
 * 修复要点：
 * - Hook onCreate 是合理的（通话 Activity 在此时已收到 Intent 参数）
 * - 递归查找嵌套对象中的 wxId
 * - 增加更多 Intent extras 字段名尝试
 */
object VoiceCallHook : HookLifecycleListener {

    private const val TAG = Constants.MODULE_TAG + ":VoiceCallHook"

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

        val voipClass = MainHook.dexKitBridge.findClass {
            searchString = "voip"
        }

        if (voipClass != null) {
            targetClass = classLoader.loadClass(voipClass.name)
            targetMethodName = "onCreate"
            cacheWarmedUp = true
            LogUtil.i(TAG, "DexKit found: ${voipClass.name}")
        }
    }

    private fun initFallback(classLoader: ClassLoader) {
        val candidates = listOf(
            "com.tencent.mm.plugin.voip.ui.VideoActivity",
            "com.tencent.mm.plugin.voip.model.v2protocal",
            "com.tencent.mm.plugin.voip.ui.InviteRemindDialog",
            "com.tencent.mm.plugin.voip.ui.VoipActivity",
            "com.tencent.mm.plugin.voip.ui.VoipDialActivity",
            "com.tencent.mm.plugin.voip.ui.VoipVoiceActivity"
        )

        for (className in candidates) {
            try {
                targetClass = classLoader.loadClass(className)
                targetMethodName = "onCreate"
                LogUtil.i(TAG, "Fallback found: $className")
                return
            } catch (_: ClassNotFoundException) {
            }
        }
    }

    override fun onHookRegistered() {
        LogUtil.i(TAG, "VoiceCallHook registered (status: ${status.description})")
        targetClass?.let { clazz ->
            targetMethodName?.let { method ->
                try {
                    XposedHelpers.findAndHookMethod(clazz, method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            blockVoiceCall(param)
                        }
                    })
                    LogUtil.i(TAG, "Hooked $method on ${clazz.name}")
                } catch (e: Throwable) {
                    LogUtil.w(TAG, "Failed to hook $method: ${e.message}")
                }
            }
        }
    }

    override fun onHookFailed(error: Throwable) {
        LogUtil.e(TAG, "VoiceCallHook failed: ${error.message}", error)
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
        LogUtil.i(TAG, "VoiceCallHook unregistered")
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

    private fun blockVoiceCall(param: XC_MethodHook.MethodHookParam) {
        try {
            val context = MainHook.appContext ?: return
            val blockedIds = ConfigManager.getVoiceCallBlockedIds(context)
            if (blockedIds.isEmpty()) return

            HookMetrics.recordSuccess(TAG)

            // 从 Activity 的 Intent 中提取对方 wxId
            val remoteWxId = extractRemoteWxId(param.thisObject)
            if (remoteWxId != null && remoteWxId in blockedIds) {
                LogUtil.i(TAG, "Blocking voice call from/to: $remoteWxId")
                tryFinishActivity(param.thisObject)
                try {
                    Toast.makeText(context, "已拦截来自隐藏好友的通话", Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {}
                param.result = null
                return
            }

            // 从方法参数中提取
            val wxId = extractWxIdFromArgs(param.args)
            if (wxId != null && wxId in blockedIds) {
                LogUtil.i(TAG, "Blocking voice call from/to (args): $wxId")
                tryFinishActivity(param.thisObject)
                param.result = null
            }
        } catch (e: Throwable) {
            LogUtil.e(TAG, "Error blocking voice call: ${e.message}", e)
        }
    }

    /**
     * 从 Activity 的 Intent 中提取对方的 wxId。
     */
    private fun extractRemoteWxId(activity: Any): String? {
        try {
            val intent = tryCallMethod(activity, "getIntent") ?: return null

            // 尝试从 Intent extras 中获取
            val extras = tryCallMethod(intent, "getExtras")
            if (extras != null) {
                val keyNames = arrayOf(
                    "Voip_User", "voip_user", "username",
                    "contact", "talker", "remote_user",
                    "Chat_User", "chat_user", "remoteUser",
                    "voip_remote_username", "remote_username",
                    "key_username", "key_talker",
                    "voip_caller", "voip_callee"
                )
                for (key in keyNames) {
                    val value = tryGetField(extras, key)
                        ?: tryCallMethod(extras, "getString", key)
                    if (value is String && value.isNotEmpty() && isValidWxId(value)) {
                        return value
                    }
                }
            }

            // 尝试直接从 Intent 获取
            val intentFieldNames = arrayOf(
                "field_username", "username", "mUserName",
                "field_talker", "talker", "mTalker",
                "field_remoteUsername", "remoteUsername",
                "a", "b", "c", "d", "e", "f", "g", "h"
            )
            for (fieldName in intentFieldNames) {
                val value = tryGetField(intent, fieldName)
                if (value is String && value.isNotEmpty() && isValidWxId(value)) {
                    return value
                }
            }

            // 递归查找 Intent 中的所有字段
            return findWxIdInAllFields(intent)
        } catch (_: Throwable) {}
        return null
    }

    /**
     * 从方法参数中提取 wxId。
     */
    private fun extractWxIdFromArgs(args: Array<out Any>): String? {
        for (arg in args) {
            if (arg == null) continue
            val wxId = extractWxId(arg)
            if (wxId != null) return wxId
        }
        return null
    }

    /**
     * 尝试 finish Activity 来阻止通话。
     */
    private fun tryFinishActivity(activity: Any) {
        try {
            tryCallMethod(activity, "finish")
            LogUtil.d(TAG, "Activity finished to block call")
        } catch (_: Throwable) {
            LogUtil.d(TAG, "Could not finish activity")
        }
    }

    // ========================================================================
    // wxId 提取
    // ========================================================================

    private fun extractWxId(obj: Any): String? {
        val fieldNames = arrayOf(
            "field_username", "username", "wxId",
            "mUserName", "mWxId", "userName",
            "field_talker", "talker", "mTalker",
            "field_remoteUsername", "remoteUsername",
            "field_caller", "caller", "mCaller",
            "field_callee", "callee", "mCallee",
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
            "getUserName", "getCaller", "getCallee",
            "getEncodeUserName", "getRemoteUsername"
        )
        for (methodName in methodNames) {
            val value = tryCallMethod(obj, methodName)
            if (value is String && value.isNotEmpty() && isValidWxId(value)) {
                return value
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