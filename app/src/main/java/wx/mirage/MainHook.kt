package wx.mirage

import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import wx.mirage.config.ConfigManager
import wx.mirage.hook.ContactHook
import wx.mirage.hook.ConversationHook
import wx.mirage.hook.MomentsHook
import wx.mirage.hook.NotificationHook
import wx.mirage.hook.GroupMemberHook
import wx.mirage.hook.SearchHook

/**
 * Mirage - 微信好友隐身 Xposed 模块
 *
 * 主入口类，负责：
 * 1. Zygote 初始化阶段获取模块路径
 * 2. 微信主进程注入时初始化 DexKit 和所有 Hook
 * 3. 通过 Hook WeChat MMApplicationLike.onCreate 确保早期注入
 * 4. 多进程检测，仅在主进程 (com.tencent.mm) 中工作
 * 5. 提供 DexKit 失败时的降级机制
 */
class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        const val TAG = "Mirage"

        /** 模块 APK 路径，在 initZygote 中设置 */
        var modulePath: String? = null
            private set

        /** DexKit 实例，用于动态查找微信混淆类和方法 */
        lateinit var dexKitBridge: DexKitBridge
            private set

        /** DexKit 是否初始化成功 */
        var dexKitAvailable: Boolean = false
            private set

        /** 微信 Application Context */
        var appContext: Context? = null
            private set

        /** 当前加载的 LoadPackageParam */
        lateinit var lpparam: XC_LoadPackage.LoadPackageParam
            private set

        /** 所有 Hook 是否已注册 */
        var hooksRegistered: Boolean = false
            private set

        // ========== 微信包名和进程名常量 ==========
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val WECHAT_MAIN_PROCESS = "com.tencent.mm"

        // ========== 真实 WeChat Application 类名（来自 APK 分析） ==========
        const val WECHAT_APP_CLASS = "com.tencent.mm.app.MMApplicationLike"

        // ========== WAuxiliary 验证过的微信关键包名 ==========
        const val WECHAT_CHATTING_COMPONENT = "com.tencent.mm.ui.chatting.component"
        const val WECHAT_CHATTING_GALLERY = "com.tencent.mm.ui.chatting.gallery"
        const val WECHAT_PLUGIN_GALLERY = "com.tencent.mm.plugin.gallery"
        const val WECHAT_PLUGIN_SNS = "com.tencent.mm.plugin.sns"
        const val WECHAT_PLUGIN_FTS = "com.tencent.mm.plugin.fts"
        const val WECHAT_PLUGIN_LOCATION = "com.tencent.mm.plugin.location"
        const val WECHAT_UI_CONTACT = "com.tencent.mm.ui.contact"
        const val WECHAT_UI_CONVERSATION = "com.tencent.mm.ui.conversation"

        // ========== 各子进程名（用于多进程检测） ==========
        val WECHAT_SUB_PROCESSES = setOf(
            ":push",
            ":tools",
            ":support",
            ":sandbox",
            ":exdevice",
            ":appbrand",
            ":normsg",
            ":finder",
            ":game",
            ":snsad",
            ":appbrand0",
            ":appbrand1",
            ":appbrand2",
            ":appbrand3",
            ":appbrand4"
        )
    }

    /**
     * Zygote 初始化阶段回调
     * 在 Zygote 进程中执行，用于获取模块 APK 路径
     */
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
        XposedBridge.log("$TAG: [initZygote] modulePath=${startupParam.modulePath}")
        XposedBridge.log("$TAG: [initZygote] Mirage v1.0.1 - Zygote initialized")
    }

    /**
     * 加载包回调 (Xposed 主入口)
     * 仅在微信主进程中进行注入
     */
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 仅注入微信包
        if (lpparam.packageName != WECHAT_PACKAGE) return

        // 多进程检测：仅注入主进程
        val processName = lpparam.processName
        XposedBridge.log("$TAG: [handleLoadPackage] packageName=${lpparam.packageName}, processName=$processName")

        if (!isWeChatMainProcess(processName)) {
            XposedBridge.log("$TAG: [handleLoadPackage] Skipping sub-process: $processName")
            return
        }

        XposedBridge.log("$TAG: [handleLoadPackage] ========== Injecting into WeChat main process ==========")
        this.lpparam = lpparam

        try {
            // 步骤 1: 初始化 DexKit
            initializeDexKit(lpparam)

            // 步骤 2: 加载配置
            ConfigManager.init(lpparam.packageName)
            XposedBridge.log("$TAG: [handleLoadPackage] ConfigManager initialized")

            // 步骤 3: Hook MMApplicationLike.onCreate - 在微信 Application 创建时注册所有 Hook
            hookWeChatApplication(lpparam)

            XposedBridge.log("$TAG: [handleLoadPackage] Hook initialization chain complete")

        } catch (e: Throwable) {
            XposedBridge.log("$TAG: [handleLoadPackage] CRITICAL ERROR during initialization: ${e.message}")
            XposedBridge.log(e)
        }
    }

    /**
     * 初始化 DexKit
     * 尝试从微信 APK 创建 DexKitBridge，失败时标记为不可用
     */
    private fun initializeDexKit(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val appInfo = lpparam.appInfo
            val sourceDir = appInfo.sourceDir
            XposedBridge.log("$TAG: [DexKit] Initializing with sourceDir=$sourceDir")

            dexKitBridge = DexKitBridge.create(sourceDir)
            dexKitAvailable = true
            XposedBridge.log("$TAG: [DexKit] Initialization SUCCESS")

        } catch (e: Throwable) {
            XposedBridge.log("$TAG: [DexKit] Initialization FAILED: ${e.message}")
            XposedBridge.log("$TAG: [DexKit] Will use fallback direct class loading strategy")
            dexKitAvailable = false
        }
    }

    /**
     * Hook WeChat MMApplicationLike.onCreate
     *
     * 这是微信 Application 的真正入口点。
     * 在 MMApplicationLike.onCreate 被调用时注册所有子 Hook，
     * 确保在微信任何业务逻辑运行之前完成注入。
     */
    private fun hookWeChatApplication(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        XposedBridge.log("$TAG: [hookWeChatApplication] Attempting to hook $WECHAT_APP_CLASS")

        try {
            // 尝试加载 MMApplicationLike 类
            val appClass = try {
                classLoader.loadClass(WECHAT_APP_CLASS)
            } catch (e: ClassNotFoundException) {
                XposedBridge.log("$TAG: [hookWeChatApplication] $WECHAT_APP_CLASS not found, trying generic fallback")
                // 降级：尝试查找任何包含 "MMApplicationLike" 的类
                findWeChatAppClassFallback(classLoader)
            }

            if (appClass == null) {
                XposedBridge.log("$TAG: [hookWeChatApplication] Could not find WeChat Application class, using android.app.Application")
                hookGenericApplication(classLoader, lpparam)
                return
            }

            XposedBridge.log("$TAG: [hookWeChatApplication] Found WeChat App class: ${appClass.name}")

            // Hook onCreate 方法
            XposedHelpers.findAndHookMethod(
                appClass,
                "onCreate",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("$TAG: [MMApplicationLike.onCreate] beforeHook - preparing to register hooks")
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("$TAG: [MMApplicationLike.onCreate] afterHook - registering all hooks")

                        try {
                            // 获取 Application Context
                            val app = XposedHelpers.callMethod(param.thisObject, "getApplication") as? Context
                            if (app != null) {
                                appContext = app.applicationContext
                                XposedBridge.log("$TAG: [MMApplicationLike.onCreate] appContext obtained: ${appContext != null}")
                            }

                            // 注册所有业务 Hook
                            registerAllHooks(lpparam)

                            XposedBridge.log("$TAG: [MMApplicationLike.onCreate] ========== All hooks registered SUCCESS ==========")
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG: [MMApplicationLike.onCreate] Failed to register hooks: ${e.message}")
                            XposedBridge.log(e)
                        }
                    }
                }
            )

            XposedBridge.log("$TAG: [hookWeChatApplication] Successfully hooked MMApplicationLike.onCreate")

        } catch (e: Throwable) {
            XposedBridge.log("$TAG: [hookWeChatApplication] Failed to hook WeChat Application: ${e.message}")
            XposedBridge.log("$TAG: [hookWeChatApplication] Attempting fallback to android.app.Application")
            // 降级方案：Hook 通用的 Application.attach
            hookGenericApplication(classLoader, lpparam)
        }
    }

    /**
     * 降级方案：Hook android.app.Application.attach
     * 当 MMApplicationLike 无法找到时使用
     */
    private fun hookGenericApplication(classLoader: ClassLoader, lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("$TAG: [hookGenericApplication] Using generic Application.attach fallback")

        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                classLoader,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("$TAG: [Application.attach] beforeHook")
                        appContext = param.args[0] as? Context
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("$TAG: [Application.attach] afterHook - registering hooks via fallback")

                        try {
                            registerAllHooks(lpparam)
                            XposedBridge.log("$TAG: [Application.attach] ========== All hooks registered (fallback) ==========")
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG: [Application.attach] Failed to register hooks: ${e.message}")
                            XposedBridge.log(e)
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: [hookGenericApplication] CRITICAL: Failed to hook Application.attach: ${e.message}")
        }
    }

    /**
     * 降级查找 WeChat Application 类
     * 当精确类名查找失败时，通过类名包含关系查找
     */
    private fun findWeChatAppClassFallback(classLoader: ClassLoader): Class<*>? {
        XposedBridge.log("$TAG: [findWeChatAppClassFallback] Searching for WeChat App class...")

        val candidates = listOf(
            "com.tencent.mm.app.MMApplicationLike",
            "com.tencent.mm.app.MMApplication",
            "com.tencent.mm.app.Application",
            "com.tencent.mm.app.MMApp"
        )

        for (candidate in candidates) {
            try {
                val clazz = classLoader.loadClass(candidate)
                XposedBridge.log("$TAG: [findWeChatAppClassFallback] Found: $candidate")
                return clazz
            } catch (_: Throwable) {
                // 继续尝试
            }
        }

        XposedBridge.log("$TAG: [findWeChatAppClassFallback] No WeChat App class found")
        return null
    }

    /**
     * 统一注册所有业务 Hook
     */
    private fun registerAllHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (hooksRegistered) {
            XposedBridge.log("$TAG: [registerAllHooks] Hooks already registered, skipping")
            return
        }

        XposedBridge.log("$TAG: [registerAllHooks] Starting hook registration...")

        // 按顺序注册各模块 Hook，每个模块独立 try-catch，互不影响
        val hookModules = listOf(
            Triple("ContactHook") { ContactHook.init(lpparam) },
            Triple("ConversationHook") { ConversationHook.init(lpparam) },
            Triple("MomentsHook") { MomentsHook.init(lpparam) },
            Triple("NotificationHook") { NotificationHook.init(lpparam) },
            Triple("SearchHook") { SearchHook.init(lpparam) },
            Triple("GroupMemberHook") { GroupMemberHook.init(lpparam) }
        )

        var successCount = 0
        var failCount = 0

        for ((name, initFn) in hookModules) {
            try {
                initFn()
                successCount++
                XposedBridge.log("$TAG: [registerAllHooks] $name registered OK")
            } catch (e: Throwable) {
                failCount++
                XposedBridge.log("$TAG: [registerAllHooks] $name FAILED: ${e.message}")
                XposedBridge.log(e)
            }
        }

        hooksRegistered = true
        XposedBridge.log("$TAG: [registerAllHooks] Done - $successCount success, $failCount failed")
    }

    /**
     * 判断是否为微信主进程
     *
     * 微信主进程名固定为 "com.tencent.mm"，
     * 子进程名格式为 "com.tencent.mm:xxx"
     */
    private fun isWeChatMainProcess(processName: String): Boolean {
        // 主进程名就是包名本身
        if (processName == WECHAT_MAIN_PROCESS) return true

        // 检查是否包含冒号（子进程特征）
        if (processName.contains(":")) {
            XposedBridge.log("$TAG: [isWeChatMainProcess] Detected sub-process: $processName")
            return false
        }

        // 安全起见，如果不是子进程格式，也当作主进程处理
        XposedBridge.log("$TAG: [isWeChatMainProcess] Unknown process pattern: $processName, treating as main")
        return processName.startsWith(WECHAT_PACKAGE)
    }

    /**
     * 获取日志标签（带模块名）
     */
    fun logWithModule(module: String, message: String) {
        XposedBridge.log("$TAG: [$module] $message")
    }
}