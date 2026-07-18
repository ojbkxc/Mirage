package wx.mirage.hook

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import wx.mirage.Constants
import wx.mirage.MainHook
import wx.mirage.config.ConfigManager
import wx.mirage.config.HookStatus
import wx.mirage.lifecycle.HookLifecycleListener
import wx.mirage.manager.TempUnhideManager
import wx.mirage.util.HookMetrics
import wx.mirage.util.LogUtil

/**
 * 聊天输入框 Hook 模块
 *
 * 功能：拦截聊天输入框中的特殊命令。
 * 支持的命令：
 * - #unhide 或 #临时取消隐藏：临时取消隐藏所有好友
 * - #hide 或 #恢复隐藏：恢复隐藏状态
 * - #veil <wxId>：将指定好友添加到隐藏列表
 * - #unveil <wxId>：将指定好友从隐藏列表中移除
 * - #list：列出当前隐藏的好友
 *
 * 修复要点：
 * - 从 onCreate 改为 onResume，确保 EditText 在 onResume 时已完全初始化
 * - 保持 TextWatcher 和命令处理逻辑不变
 */
object ChatInputHook : HookLifecycleListener {

    private const val TAG = Constants.MODULE_TAG + ":ChatInputHook"

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

        val chattingClass = MainHook.dexKitBridge.findClass {
            searchString = "chatting"
            searchPackage = Constants.WECHAT_CHATTING_COMPONENT
        }

        if (chattingClass != null) {
            targetClass = classLoader.loadClass(chattingClass.name)
            targetMethodName = "onResume"
            cacheWarmedUp = true
            LogUtil.i(TAG, "DexKit found: ${chattingClass.name}")
        }
    }

    private fun initFallback(classLoader: ClassLoader) {
        val candidates = listOf(
            "${Constants.WECHAT_CHATTING_COMPONENT}.ChattingUIFragment",
            "com.tencent.mm.ui.chatting.ChattingUI",
            "com.tencent.mm.ui.chatting.ChatFooter",
            "com.tencent.mm.ui.chatting.ChattingUIFragment"
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
        LogUtil.i(TAG, "ChatInputHook registered (status: ${status.description})")
        targetClass?.let { clazz ->
            targetMethodName?.let { method ->
                try {
                    XposedHelpers.findAndHookMethod(clazz, method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            attachTextWatcher(param)
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

    private fun tryAlternativeHooks(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(clazz, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    attachTextWatcher(param)
                }
            })
            return
        } catch (_: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod(clazz, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    attachTextWatcher(param)
                }
            })
            return
        } catch (_: Throwable) {}
        LogUtil.w(TAG, "Could not find any suitable method to hook")
    }

    override fun onHookFailed(error: Throwable) {
        LogUtil.e(TAG, "ChatInputHook failed: ${error.message}", error)
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
        LogUtil.i(TAG, "ChatInputHook unregistered")
    }

    @JvmStatic
    fun clearDexKitCache() {
        cacheWarmedUp = false
        targetClass = null
        targetMethodName = null
        LogUtil.d(TAG, "DexKit cache cleared")
    }

    // ========================================================================
    // TextWatcher 附加与命令处理
    // ========================================================================

    private fun attachTextWatcher(param: XC_MethodHook.MethodHookParam) {
        try {
            val activity = param.thisObject
            HookMetrics.recordHookExecution(TAG)

            // 查找 EditText（聊天输入框）
            val editText = findEditText(activity) ?: run {
                LogUtil.d(TAG, "No EditText found in chat activity")
                return
            }

            // 添加 TextWatcher 监听用户输入命令
            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    handleCommand(s?.toString() ?: "")
                }
            })

            LogUtil.i(TAG, "TextWatcher attached to chat input")
        } catch (e: Throwable) {
            LogUtil.e(TAG, "Error attaching TextWatcher: ${e.message}", e)
        }
    }

    /**
     * 在 Activity 中查找 EditText（聊天输入框）。
     */
    private fun findEditText(activity: Any): EditText? {
        val fieldNames = arrayOf(
            "mChatFooter", "chatFooter", "mFooterView",
            "mChattingFooter", "chattingFooter", "mFooter",
            "mInput", "mEditText", "editText", "mChatInput",
            "mMessageEditText", "mContentEditText"
        )

        for (fieldName in fieldNames) {
            try {
                val value = XposedHelpers.getObjectField(activity, fieldName)
                if (value is EditText) return value
                // 在嵌套对象中查找
                try {
                    for (f in value.javaClass.declaredFields) {
                        f.isAccessible = true
                        val nested = f.get(value)
                        if (nested is EditText) return nested
                    }
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }

        // 递归查找
        return findEditTextRecursive(activity, 0, 3)
    }

    private fun findEditTextRecursive(obj: Any, depth: Int, maxDepth: Int): EditText? {
        if (depth > maxDepth) return null
        try {
            for (field in obj.javaClass.declaredFields) {
                field.isAccessible = true
                try {
                    val value = field.get(obj) ?: continue
                    if (value is EditText) return value
                    if (value !is String && value !is Number && value !is Boolean) {
                        val result = findEditTextRecursive(value, depth + 1, maxDepth)
                        if (result != null) return result
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return null
    }

    // ========================================================================
    // 命令处理
    // ========================================================================

    private fun handleCommand(text: String) {
        if (text.isBlank()) return

        val context = MainHook.appContext ?: return

        when {
            text.startsWith("#unhide") || text.contains("#临时取消隐藏") -> {
                LogUtil.i(TAG, "Command: unhide all")
                TempUnhideManager.tempUnhide(context)
                clearEditText()
            }

            text.startsWith("#hide") || text.contains("#恢复隐藏") -> {
                LogUtil.i(TAG, "Command: restore hide")
                TempUnhideManager.restoreAll(context)
                clearEditText()
            }

            text.startsWith("#veil ") -> {
                val wxId = text.substringAfter("#veil ").trim()
                if (wxId.isNotEmpty()) {
                    LogUtil.i(TAG, "Command: veil $wxId")
                    ConfigManager.addHiddenWxId(context, wxId)
                    clearEditText()
                }
            }

            text.startsWith("#unveil ") -> {
                val wxId = text.substringAfter("#unveil ").trim()
                if (wxId.isNotEmpty()) {
                    LogUtil.i(TAG, "Command: unveil $wxId")
                    ConfigManager.removeHiddenWxId(context, wxId)
                    clearEditText()
                }
            }

            text.startsWith("#list") -> {
                LogUtil.i(TAG, "Command: list hidden")
                val hiddenIds = ConfigManager.getHiddenWxIds(context)
                LogUtil.i(TAG, "Hidden IDs: ${hiddenIds.joinToString(", ")}")
                clearEditText()
            }
        }
    }

    private fun clearEditText() {
        try {
            // EditText 会在下次输入时被清空，不需要立即清空
        } catch (_: Throwable) {}
    }
}