package wx.mirage.wxmask.plugin.part

import android.content.Context
import com.lu.lposed.api2.XC_MethodHook2
import com.lu.lposed.api2.XposedHelpers2
import com.lu.lposed.plugin.IPlugin
import com.lu.magic.util.log.LogUtil
import wx.mirage.wxmask.util.ConfigUtil
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 阻止微信热更新：
 * 微信使用 Tinker 热修复框架（tencent.tinker.loader.*），
 * 热更补丁会改变混淆类名/方法名，导致模块适配表失效。
 *
 * 原理：
 * 1. hook AbstractTinkerLoader.tryLoad / TinkerLoader.tryLoad —— 直接拦截补丁加载
 * 2. hook TinkerArkHotLoader —— 拦截 Ark 热更
 * 3. 微信自有更新事件（CheckTinkerUpdateEvent）不拦截网络请求，只拦截"应用补丁"这一步，
 *    避免影响其他功能
 */
class BlockHotUpdatePluginPart : IPlugin {

    companion object {
        private var hasHook = false

        private val TINKER_LOADERS = arrayOf(
            "com.tencent.tinker.loader.TinkerLoader",
            "com.tencent.tinker.loader.AbstractTinkerLoader",
            "com.tencent.tinker.loader.TinkerArkHotLoader"
        )
        private val LOAD_METHODS = arrayOf("tryLoad", "tryLoadTestOnly", "loadTinker")
    }

    override fun handleHook(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        if (hasHook) {
            return
        }
        if (!ConfigUtil.getOptionData().enableBlockHotUpdate) {
            return
        }
        hasHook = true
        blockTinkerLoad(context, lpparam)
    }

    private fun blockTinkerLoad(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        var blockedCount = 0
        for (className in TINKER_LOADERS) {
            val clazz = runCatching {
                context.classLoader.loadClass(className)
            }.getOrNull() ?: continue

            for (methodName in LOAD_METHODS) {
                runCatching {
                    XposedHelpers2.findMethodsByExactPredicate(clazz) { m ->
                        m.name == methodName
                    }.forEach { method ->
                        XposedHelpers2.hookMethod(method, object : XC_MethodHook2() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                // 拦截热更补丁加载
                                param.result = null
                                LogUtil.i("block tinker hot update:", className, methodName)
                            }
                        })
                        blockedCount++
                    }
                }
            }
        }
        if (blockedCount == 0) {
            // 找不到具名方法，退化为 hook 全部 tryLoad* 方法
            for (className in TINKER_LOADERS) {
                runCatching {
                    val clazz = context.classLoader.loadClass(className)
                    clazz.declaredMethods.filter { it.name.startsWith("tryLoad") }.forEach { method ->
                        XposedHelpers2.hookMethod(method, object : XC_MethodHook2() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                param.result = null
                                LogUtil.i("block tinker hot update(fallback):", className, param.method.name)
                            }
                        })
                        blockedCount++
                    }
                }
            }
        }
        LogUtil.i("block hot update hooks:", blockedCount)
    }
}
