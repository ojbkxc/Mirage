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
import wx.mirage.util.HookMetrics
import wx.mirage.util.LogUtil

/**
 * 搜索框 Hook 模块
 *
 * 功能：拦截微信搜索框中的 #veil 命令。
 * 在搜索框中输入 #veil 命令可以快速添加好友到隐藏列表。
 *
 * 支持的命令：
 * - #veil <wxId>：将指定好友添加到隐藏列表
 * - #unveil <wxId>：将指定好友从隐藏列表中移除
 * - #list：列出当前隐藏的好友
 */
object SearchInputHook : HookLifecycleListener {

    private const val TAG = Constants.MODULE_TAG + ":SearchInputHook"

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

        val searchClass = MainHook.dexKitBridge.findClass {
            searchPackages = listOf(Constants.WECHAT_PLUGIN_FTS)
            matcher {
                usingStrings = listOf("search")
            }
        }.firstOrNull()

        if (searchClass != null) {
            targetClass = classLoader.loadClass(searchClass.name)
            targetMethodName = "onResume"
            cacheWarmedUp = true
            LogUtil.i(TAG, "DexKit found: ${searchClass.name}")
        }
    }

    private fun initFallback(classLoader: ClassLoader) {
        val candidates = listOf(
            "${Constants.WECHAT_PLUGIN_FTS}.ui.FTSMainUI",
            "${Constants.WECHAT_PLUGIN_FTS}.ui.FTSDetailUI",
            "${Constants.WECHAT_PLUGIN_FTS}.ui.FTSSearchTabUI",
            "${Constants.WECHAT_UI_CONVERSATION}.ConversationFragment",
            "com.tencent.mm.ui.LauncherUI"
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
        LogUtil.i(TAG, "SearchInputHook registered (status: ${status.description})")
        targetClass?.let { clazz ->
            targetMethodName?.let { method ->
                try {
                    XposedHelpers.findAndHookMethod(clazz, method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            attachSearchWatcher(param)
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
                    attachSearchWatcher(param)
                }
            })
            return
        } catch (_: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod(clazz, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    attachSearchWatcher(param)
                }
            })
            return
        } catch (_: Throwable) {}
        LogUtil.w(TAG, "Could not find any suitable method to hook")
    }

    override fun onHookFailed(error: Throwable) {
        LogUtil.e(TAG, "SearchInputHook failed: ${error.message}", error)
        status = HookStatus.ERROR
    }

    override fun onHookUnregistered() {
        try {
            targetClass?.let { clazz ->
                targetMethodName?.let { method ->
                    XposedBridge.unhookMethod(clazz.getDeclaredMethod(method), null)
                }
            }
        } catch (_: Throwable) {}
        status = HookStatus.INACTIVE
        LogUtil.i(TAG, "SearchInputHook unregistered")
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

    private fun attachSearchWatcher(param: XC_MethodHook.MethodHookParam) {
        try {
            val activity = param.thisObject
            HookMetrics.recordSuccess(TAG)

            val editText = findSearchEditText(activity) ?: run {
                LogUtil.d(TAG, "No search EditText found")
                return
            }

            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    handleSeachCommand(s?.toString() ?: "")
                }
            })

            LogUtil.i(TAG, "TextWatcher attached to search input")
        } catch (e: Throwable) {
            LogUtil.e(TAG, "Error attaching search watcher: ${e.message}", e)
        }
    }

    private fun findSearchEditText(activity: Any): EditText? {
        val fieldNames = arrayOf(
            "mSearchView", "searchView", "mSearchBar",
            "mSearchEditText", "searchEditText", "mEditText",
            "mSearchInput", "searchInput", "mSearchTextView",
            "mSearchBox", "mQueryEditText", "mQueryView"
        )

        for (fieldName in fieldNames) {
            try {
                val value = XposedHelpers.getObjectField(activity, fieldName)
                if (value is EditText) return value
                try {
                    for (f in value.javaClass.declaredFields) {
                        f.isAccessible = true
                        val nested = f.get(value)
                        if (nested is EditText) return nested
                    }
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }

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

    private fun handleSeachCommand(text: String) {
        if (text.isBlank()) return

        val context = MainHook.appContext ?: return

        when {
            text.startsWith("#veil ") -> {
                val wxId = text.substringAfter("#veil ").trim()
                if (wxId.isNotEmpty()) {
                    LogUtil.i(TAG, "Command: veil $wxId")
                    ConfigManager.addHiddenWxId(context, wxId)
                }
            }

            text.startsWith("#unveil ") -> {
                val wxId = text.substringAfter("#unveil ").trim()
                if (wxId.isNotEmpty()) {
                    LogUtil.i(TAG, "Command: unveil $wxId")
                    ConfigManager.removeHiddenWxId(context, wxId)
                }
            }

            text.startsWith("#list") -> {
                LogUtil.i(TAG, "Command: list hidden")
                val hiddenIds = ConfigManager.getHiddenWxIds(context)
                LogUtil.i(TAG, "Hidden IDs: ${hiddenIds.joinToString(", ")}")
            }
        }
    }
}