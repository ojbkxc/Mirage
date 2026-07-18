package wx.mirage

import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import wx.mirage.config.ConfigManager
import wx.mirage.config.HookStatus
import wx.mirage.hook.ChatInputHook
import wx.mirage.hook.ContactHook
import wx.mirage.hook.ConversationHook
import wx.mirage.hook.ConversationLongClickHook
import wx.mirage.hook.GroupMemberHook
import wx.mirage.hook.LongPressHook
import wx.mirage.hook.MessageAntiRevokeHook
import wx.mirage.hook.MessageIndicatorHook
import wx.mirage.hook.MiscHook
import wx.mirage.hook.MomentsAdRemovalHook
import wx.mirage.hook.MomentsHook
import wx.mirage.hook.NotificationHook
import wx.mirage.hook.SearchHook
import wx.mirage.hook.SearchInputHook
import wx.mirage.hook.TempMomentsUnhideHook
import wx.mirage.hook.VoiceCallHook
import wx.mirage.lifecycle.HookLifecycleListener
import wx.mirage.receiver.ConfigReceiver
import wx.mirage.util.LogUtil

/**
 * Mirage - 微信好友隐身 Xposed 模块
 *
 * 主入口类，负责：
 * 1. Zygote 初始化阶段获取模块路径
 * 2. 微信主进程注入时初始化 DexKit 和所有 Hook
 * 3. 通过 Hook WeChat MMApplicationLike.onCreate 确保早期注入
 * 4. 多进程检测，仅在主进程 (com.tencent.mm) 中工作
 * 5. 提供 DexKit 失败时的降级机制
 * 6. DexKit 生命周期管理（创建/关闭/状态追踪）
 * 7. 带重试的 Hook 注册机制
 */
class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        /**
         * DexKit 实例，用于动态查找微信混淆类和方法。
         * 线程安全：标记为 @Volatile，保证跨线程可见性。
         * 写入仅在初始化时发生一次（handleLoadPackage -> initializeDexKit），
         * 读取在多个 Hook 线程中频繁发生，@Volatile 确保每次读取都能看到最新值。
         */
        @JvmStatic
        @Volatile
        lateinit var dexKitBridge: DexKitBridge
            private set

        /**
         * DexKit 是否初始化成功。
         * 线程安全：标记为 @Volatile，保证多线程可见性。
         * 写入仅在 initializeDexKit() 中发生（单线程），
         * 读取在多个 Hook 模块中发生。
         */
        @JvmStatic
        @Volatile
        var dexKitAvailable: Boolean = false
            private set

        /**
         * DexKit 是否已关闭。
         * 线程安全：标记为 @Volatile，保证 shutdownDexKit() 中的写入
         * 对其他线程的读取可见。
         */
        @JvmStatic
        @Volatile
        var dexKitClosed: Boolean = false
            private set

        /**
         * 微信 Application Context。
         * 线程安全：标记为 @Volatile，保证在 Hook 线程中写入后
         * 其他线程（如 UI 线程）能立即读取到最新值。
         */
        @JvmStatic
        @Volatile
        var appContext: Context? = null
            private set

        /**
         * 当前加载的 LoadPackageParam。
         * 线程安全：标记为 @Volatile。写入在 handleLoadPackage 中
         * 发生一次（主线程），读取主要在 Hook 模块初始化时。
         */
        @JvmStatic
        @Volatile
        lateinit var lpparam: XC_LoadPackage.LoadPackageParam
            private set

        /**
         * 所有 Hook 是否已注册。
         * 线程安全：标记为 @Volatile。写入在 registerAllHooks() 中
         * （单线程），读取可能在多个 Hook 回调线程中发生。
         * 注意：写入操作不是原子的，但此处的语义是"标记状态"，
         * 不需要强制原子性。
         */
        @JvmStatic
        @Volatile
        var hooksRegistered: Boolean = false
            private set

        /**
         * 模块是否已关闭。
         * 线程安全：标记为 @Volatile，保证 shutdown() 中的写入
         * 对其他线程的读取可见。shutdown() 内部使用 isShutdown 检查
         * 防止重复关闭，但此检查不是原子的，在极端并发场景下
         * 可能发生重复关闭。不过 shutdown 操作是幂等的，重复执行安全。
         */
        @JvmStatic
        @Volatile
        var isShutdown: Boolean = false
            private set

        /**
         * 模块启动时间戳（毫秒），用于计算 uptime。
         * 线程安全：标记为 @Volatile。写入在 handleLoadPackage 中
         * 发生一次，读取在 UI 线程中。
         */
        @JvmStatic
        @Volatile
        var startupTimestamp: Long = 0L
            private set

        /**
         * 模块总体 Hook 状态。
         * 线程安全：标记为 @Volatile，保证跨线程可见性。
         */
        @JvmStatic
        @Volatile
        var hookStatus: HookStatus = HookStatus.INACTIVE
            private set

        /** 模块 APK 路径，在 initZygote 中设置 */
        @JvmStatic
        var modulePath: String? = null
            private set
    }

    /**
     * Zygote 初始化阶段回调
     *
     * 在 Zygote 进程中执行，用于获取模块 APK 路径。
     * 此回调在所有 Xposed 模块的 initZygote 中最早执行，
     * 用于设置模块路径供后续 DexKit 初始化使用。
     *
     * @param startupParam Zygote 启动参数，包含模块路径等信息
     */
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
        LogUtil.i(Constants.MODULE_TAG, "modulePath=${startupParam.modulePath}")
        LogUtil.i(Constants.MODULE_TAG, "Mirage v${Constants.VERSION} - Zygote initialized")
    }

    /**
     * Xposed 加载包回调
     *
     * 仅在微信主进程（com.tencent.mm）中执行 Hook 初始化。
     * 执行流程：
     * 1. 检查目标进程是否为微信主进程
     * 2. 执行版本兼容性检查
     * 3. 初始化修复工具
     * 4. 初始化 DexKit
     * 5. 初始化 ConfigManager
     * 6. 注册所有 Hook 模块
     * 7. 注册广播接收器
     *
     * @param lpparam 加载包参数，包含包名、ClassLoader 等信息
     */
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 仅注入微信包
        if (lpparam.packageName != Constants.WECHAT_PACKAGE) return

        // 多进程检测：仅注入主进程
        val processName = lpparam.processName
        LogUtil.i(Constants.MODULE_TAG, "packageName=${lpparam.packageName}, processName=$processName")

        if (!isWeChatMainProcess(processName)) {
            LogUtil.i(Constants.MODULE_TAG, "Skipping sub-process: $processName")
            return
        }

        LogUtil.i(Constants.MODULE_TAG, "========== Injecting into WeChat main process ==========")
        this.lpparam = lpparam
        startupTimestamp = System.currentTimeMillis()

        // 版本兼容性检查
        checkVersionCompatibility(lpparam)

        try {
            // 步骤 1: 初始化 DexKit
            initializeDexKit(lpparam)

            // 步骤 2: 加载配置
            ConfigManager.init(lpparam.packageName)
            LogUtil.i(Constants.MODULE_TAG, "ConfigManager initialized")

            // 步骤 3: Hook MMApplicationLike.onCreate - 在微信 Application 创建时注册所有 Hook
            hookWeChatApplication(lpparam)

            LogUtil.i(Constants.MODULE_TAG, "Hook initialization chain complete")

        } catch (e: Throwable) {
            LogUtil.e(Constants.MODULE_TAG, "CRITICAL ERROR during initialization: ${e.message}", e)
            hookStatus = HookStatus.ERROR
        }
    }

    // ========================================================================
    // DexKit 生命周期管理
    // ========================================================================

    /**
     * 初始化 DexKit
     * 尝试从微信 APK 创建 DexKitBridge，失败时标记为不可用
     */
    private fun initializeDexKit(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val appInfo = lpparam.appInfo
            val sourceDir = appInfo.sourceDir
            LogUtil.i("${Constants.MODULE_TAG}:DexKit", "Initializing with sourceDir=$sourceDir")

            dexKitBridge = DexKitBridge.create(sourceDir)
            dexKitAvailable = true
            dexKitClosed = false
            LogUtil.i("${Constants.MODULE_TAG}:DexKit", "Initialization SUCCESS")

        } catch (e: Throwable) {
            LogUtil.e("${Constants.MODULE_TAG}:DexKit", "Initialization FAILED: ${e.message}")
            LogUtil.w("${Constants.MODULE_TAG}:DexKit", "Will use fallback direct class loading strategy")
            dexKitAvailable = false
            dexKitClosed = false
        }
    }

    /**
     * 关闭 DexKit 桥接，释放资源
     * 在模块卸载或微信进程退出前调用
     */
    @JvmStatic
    fun shutdownDexKit() {
        if (dexKitClosed) {
            LogUtil.d(Constants.MODULE_TAG, "DexKit already closed, skipping")
            return
        }

        LogUtil.i(Constants.MODULE_TAG, "Shutting down DexKit bridge...")
        try {
            if (dexKitAvailable) {
                dexKitBridge.close()
                LogUtil.i(Constants.MODULE_TAG, "DexKit bridge closed successfully")
            }
        } catch (e: Throwable) {
            LogUtil.e(Constants.MODULE_TAG, "Error closing DexKit bridge: ${e.message}", e)
        } finally {
            dexKitClosed = true
            dexKitAvailable = false
        }
    }

    // ========================================================================
    // 模块关闭与资源清理
    // ========================================================================

    /**
     * 关闭模块，释放所有资源。
     *
     * 执行流程：
     * 1. 关闭 DexKit 桥接
     * 2. 清除所有 Hook 模块的 DexKit 缓存
     * 3. 通知所有 Hook 模块执行卸载回调（onHookUnregistered）
     * 4. 清理 ConfigReceiver 资源
     * 5. 清除 Context 引用
     * 6. 重置状态标志
     *
     * 每个步骤独立 try-catch，防止单个步骤失败导致整体 shutdown 不完整。
     * 此方法可安全地多次调用，后续调用会被忽略（isShutdown 检查）。
     */
    @JvmStatic
    fun shutdown() {
        if (isShutdown) {
            LogUtil.d(Constants.MODULE_TAG, "Module already shut down, skipping")
            return
        }

        LogUtil.i(Constants.MODULE_TAG, "========== Shutting down Mirage module ==========")

        try {
            // 1. 关闭 DexKit
            shutdownDexKit()

            // 2. 清除所有 Hook 模块的 DexKit 缓存
            clearAllDexKitCaches()

            // 3. 通知所有 Hook 模块卸载
            notifyHookUnregistered()

            // 4. 清理 BroadcastReceiver 资源
            try {
                ConfigReceiver.cleanup()
            } catch (e: Throwable) {
                LogUtil.w(Constants.MODULE_TAG, "Error cleaning up ConfigReceiver: ${e.message}")
            }

            // 5. 清除引用
            appContext = null

            // 6. 重置状态
            hooksRegistered = false
            isShutdown = true
            hookStatus = HookStatus.INACTIVE

            LogUtil.i(Constants.MODULE_TAG, "========== Mirage module shut down complete ==========")
        } catch (e: Throwable) {
            LogUtil.e(Constants.MODULE_TAG, "Error during shutdown: ${e.message}", e)
        }
    }

    /**
     * 清除所有 Hook 模块中的 DexKit 缓存
     */
    private fun clearAllDexKitCaches() {
        try {
            ChatInputHook.clearDexKitCache()
            ContactHook.clearDexKitCache()
            ConversationHook.clearDexKitCache()
            ConversationLongClickHook.clearDexKitCache()
            GroupMemberHook.clearDexKitCache()
            LongPressHook.clearDexKitCache()
            MessageAntiRevokeHook.clearDexKitCache()
            MessageIndicatorHook.clearDexKitCache()
            MiscHook.clearDexKitCache()
            MomentsAdRemovalHook.clearDexKitCache()
            MomentsHook.clearDexKitCache()
            NotificationHook.clearDexKitCache()
            SearchHook.clearDexKitCache()
            SearchInputHook.clearDexKitCache()
            TempMomentsUnhideHook.clearDexKitCache()
            VoiceCallHook.clearDexKitCache()
            LogUtil.d(Constants.MODULE_TAG, "All DexKit caches cleared")
        } catch (e: Throwable) {
            LogUtil.w(Constants.MODULE_TAG, "Error clearing DexKit caches: ${e.message}")
        }
    }

    /**
     * 通知所有 Hook 模块执行卸载回调。
     * 每个模块独立 try-catch，确保单个模块失败不影响其他模块的清理。
     */
    private fun notifyHookUnregistered() {
        try {
            LogUtil.i(Constants.MODULE_TAG, "Notifying all Hook modules of unregistration...")
            val hooks = listOf(
                ChatInputHook as HookLifecycleListener,
                ContactHook as HookLifecycleListener,
                ConversationHook as HookLifecycleListener,
                ConversationLongClickHook as HookLifecycleListener,
                GroupMemberHook as HookLifecycleListener,
                LongPressHook as HookLifecycleListener,
                MessageAntiRevokeHook as HookLifecycleListener,
                MessageIndicatorHook as HookLifecycleListener,
                MiscHook as HookLifecycleListener,
                MomentsAdRemovalHook as HookLifecycleListener,
                MomentsHook as HookLifecycleListener,
                NotificationHook as HookLifecycleListener,
                SearchHook as HookLifecycleListener,
                SearchInputHook as HookLifecycleListener,
                TempMomentsUnhideHook as HookLifecycleListener,
                VoiceCallHook as HookLifecycleListener
            )
            for (hook in hooks) {
                try {
                    hook.onHookUnregistered()
                } catch (e: Throwable) {
                    LogUtil.w(Constants.MODULE_TAG, "Error in onHookUnregistered for ${hook.javaClass.simpleName}: ${e.message}")
                }
            }
            LogUtil.i(Constants.MODULE_TAG, "All Hook modules notified of unregistration")
        } catch (e: Throwable) {
            LogUtil.w(Constants.MODULE_TAG, "Error notifying hooks: ${e.message}")
        }
    }

    // ========================================================================
    // 带重试的 Hook 注册机制
    // ========================================================================

    /**
     * 带重试机制的 Hook 注册
     *
     * 如果目标类尚未加载，会等待指定时间后重试，最多重试 [Constants.MAX_HOOK_RETRY_COUNT] 次。
     * 适用于微信某些延迟加载的类。
     *
     * @param className 目标类全限定名
     * @param classLoader 类加载器
     * @param hookAction 注册 Hook 的具体操作（接收 Class 对象作为参数）
     * @return 是否成功注册 Hook
     */
    @JvmStatic
    fun registerHookWithRetry(
        className: String,
        classLoader: ClassLoader,
        hookAction: (Class<*>) -> Unit
    ): Boolean {
        LogUtil.d(Constants.MODULE_TAG, "registerHookWithRetry: attempting to hook $className")

        for (attempt in 1..Constants.MAX_HOOK_RETRY_COUNT) {
            try {
                val clazz = classLoader.loadClass(className)
                hookAction(clazz)
                LogUtil.i(Constants.MODULE_TAG, "registerHookWithRetry: $className hooked on attempt $attempt")
                return true
            } catch (e: ClassNotFoundException) {
                if (attempt < Constants.MAX_HOOK_RETRY_COUNT) {
                    LogUtil.d(
                        Constants.MODULE_TAG,
                        "registerHookWithRetry: $className not found on attempt $attempt, retrying in ${Constants.HOOK_RETRY_DELAY_MS}ms..."
                    )
                    try {
                        Thread.sleep(Constants.HOOK_RETRY_DELAY_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        LogUtil.w(Constants.MODULE_TAG, "registerHookWithRetry: interrupted during retry delay")
                        return false
                    }
                } else {
                    LogUtil.w(
                        Constants.MODULE_TAG,
                        "registerHookWithRetry: $className not found after ${Constants.MAX_HOOK_RETRY_COUNT} attempts, giving up"
                    )
                }
            } catch (e: Throwable) {
                LogUtil.e(
                    Constants.MODULE_TAG,
                    "registerHookWithRetry: failed to hook $className on attempt $attempt: ${e.message}",
                    e
                )
                return false
            }
        }

        return false
    }

    /**
     * 带重试机制的 Hook 注册（重载版本，接受 Runnable）
     *
     * 适用于不需要传递 Class 对象，只需执行 Hook 操作的场景。
     * 内部会尝试加载 className 来检测类是否可用，然后执行 hookAction。
     *
     * @param className 目标类全限定名（用于检测类是否已加载）
     * @param classLoader 类加载器
     * @param hookAction 注册 Hook 的具体操作
     * @return 是否成功注册 Hook
     */
    @JvmStatic
    fun registerHookWithRetry(
        className: String,
        classLoader: ClassLoader,
        hookAction: Runnable
    ): Boolean {
        return registerHookWithRetry(className, classLoader) { _ ->
            hookAction.run()
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
        LogUtil.i(Constants.MODULE_TAG, "Attempting to hook ${Constants.WECHAT_APP_CLASS}")

        try {
            // 尝试加载 MMApplicationLike 类
            val appClass = try {
                classLoader.loadClass(Constants.WECHAT_APP_CLASS)
            } catch (e: ClassNotFoundException) {
                LogUtil.i(Constants.MODULE_TAG, "${Constants.WECHAT_APP_CLASS} not found, trying generic fallback")
                // 降级：尝试查找任何包含 "MMApplicationLike" 的类
                findWeChatAppClassFallback(classLoader)
            }

            if (appClass == null) {
                LogUtil.i(Constants.MODULE_TAG, "Could not find WeChat Application class, using android.app.Application")
                hookGenericApplication(classLoader, lpparam)
                return
            }

            LogUtil.i(Constants.MODULE_TAG, "Found WeChat App class: ${appClass.name}")

            // Hook onCreate 方法
            XposedHelpers.findAndHookMethod(
                appClass,
                "onCreate",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        LogUtil.d(Constants.MODULE_TAG, "MMApplicationLike.onCreate beforeHook - preparing to register hooks")
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        LogUtil.d(Constants.MODULE_TAG, "MMApplicationLike.onCreate afterHook - registering all hooks")

                        try {
                            // 获取 Application Context
                            val app = XposedHelpers.callMethod(param.thisObject, "getApplication") as? Context
                            if (app != null) {
                                appContext = app.applicationContext
                                LogUtil.i(Constants.MODULE_TAG, "appContext obtained: ${appContext != null}")
                            }

                            // 注册所有业务 Hook
                            registerAllHooks(lpparam)

                            LogUtil.i(Constants.MODULE_TAG, "========== All hooks registered SUCCESS ==========")
                        } catch (e: Throwable) {
                            LogUtil.e(Constants.MODULE_TAG, "Failed to register hooks: ${e.message}", e)
                        }
                    }
                }
            )

            LogUtil.i(Constants.MODULE_TAG, "Successfully hooked MMApplicationLike.onCreate")

        } catch (e: Throwable) {
            LogUtil.e(Constants.MODULE_TAG, "Failed to hook WeChat Application: ${e.message}")
            LogUtil.i(Constants.MODULE_TAG, "Attempting fallback to android.app.Application")
            // 降级方案：Hook 通用的 Application.attach
            hookGenericApplication(classLoader, lpparam)
        }
    }

    /**
     * 降级方案：Hook android.app.Application.attach
     * 当 MMApplicationLike 无法找到时使用
     */
    private fun hookGenericApplication(classLoader: ClassLoader, lpparam: XC_LoadPackage.LoadPackageParam) {
        LogUtil.i(Constants.MODULE_TAG, "Using generic Application.attach fallback")

        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                classLoader,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        LogUtil.d(Constants.MODULE_TAG, "Application.attach beforeHook")
                        appContext = param.args[0] as? Context
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        LogUtil.d(Constants.MODULE_TAG, "Application.attach afterHook - registering hooks via fallback")

                        try {
                            registerAllHooks(lpparam)
                            LogUtil.i(Constants.MODULE_TAG, "========== All hooks registered (fallback) ==========")
                        } catch (e: Throwable) {
                            LogUtil.e(Constants.MODULE_TAG, "Failed to register hooks: ${e.message}", e)
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            LogUtil.e(Constants.MODULE_TAG, "CRITICAL: Failed to hook Application.attach: ${e.message}")
        }
    }

    /**
     * 降级查找 WeChat Application 类
     * 当精确类名查找失败时，通过类名包含关系查找
     */
    private fun findWeChatAppClassFallback(classLoader: ClassLoader): Class<*>? {
        LogUtil.i(Constants.MODULE_TAG, "Searching for WeChat App class...")

        val candidates = listOf(
            "com.tencent.mm.app.MMApplicationLike",
            "com.tencent.mm.app.MMApplication",
            "com.tencent.mm.app.Application",
            "com.tencent.mm.app.MMApp"
        )

        for (candidate in candidates) {
            try {
                val clazz = classLoader.loadClass(candidate)
                LogUtil.i(Constants.MODULE_TAG, "Found: $candidate")
                return clazz
            } catch (_: Throwable) {
                // 继续尝试
            }
        }

        LogUtil.i(Constants.MODULE_TAG, "No WeChat App class found")
        return null
    }

    /**
     * 注册所有 Hook 模块。
     *
     * 按顺序注册 ContactHook -> ConversationHook -> MomentsHook ->
     * NotificationHook -> SearchHook -> GroupMemberHook。
     * 每个模块独立 try-catch，单个模块失败不影响其他模块的注册。
     * 注册完成后，所有模块的 init() 方法会调用 onHookRegistered() 回调。
     *
     * @param lpparam Xposed LoadPackage 参数
     * @return true 如果至少有一个 Hook 模块注册成功，false 如果全部失败
     */
    @JvmStatic
    fun registerAllHooks(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        if (hooksRegistered) {
            LogUtil.i(Constants.MODULE_TAG, "Hooks already registered, skipping")
            return true
        }

        LogUtil.i(Constants.MODULE_TAG, "Starting hook registration...")

        // 按顺序注册各模块 Hook，每个模块独立 try-catch，互不影响
        val hookModules = listOf(
            Triple("ChatInputHook", { ChatInputHook.init(lpparam) }, ChatInputHook as HookLifecycleListener),
            Triple("ContactHook", { ContactHook.init(lpparam) }, ContactHook as HookLifecycleListener),
            Triple("ConversationHook", { ConversationHook.init(lpparam) }, ConversationHook as HookLifecycleListener),
            Triple("ConversationLongClickHook", { ConversationLongClickHook.init(lpparam) }, ConversationLongClickHook as HookLifecycleListener),
            Triple("GroupMemberHook", { GroupMemberHook.init(lpparam) }, GroupMemberHook as HookLifecycleListener),
            Triple("LongPressHook", { LongPressHook.init(lpparam) }, LongPressHook as HookLifecycleListener),
            Triple("MessageAntiRevokeHook", { MessageAntiRevokeHook.init(lpparam) }, MessageAntiRevokeHook as HookLifecycleListener),
            Triple("MessageIndicatorHook", { MessageIndicatorHook.init(lpparam) }, MessageIndicatorHook as HookLifecycleListener),
            Triple("MiscHook", { MiscHook.init(lpparam) }, MiscHook as HookLifecycleListener),
            Triple("MomentsAdRemovalHook", { MomentsAdRemovalHook.init(lpparam) }, MomentsAdRemovalHook as HookLifecycleListener),
            Triple("MomentsHook", { MomentsHook.init(lpparam) }, MomentsHook as HookLifecycleListener),
            Triple("NotificationHook", { NotificationHook.init(lpparam) }, NotificationHook as HookLifecycleListener),
            Triple("SearchHook", { SearchHook.init(lpparam) }, SearchHook as HookLifecycleListener),
            Triple("SearchInputHook", { SearchInputHook.init(lpparam) }, SearchInputHook as HookLifecycleListener),
            Triple("TempMomentsUnhideHook", { TempMomentsUnhideHook.init(lpparam) }, TempMomentsUnhideHook as HookLifecycleListener),
            Triple("VoiceCallHook", { VoiceCallHook.init(lpparam) }, VoiceCallHook as HookLifecycleListener)
        )

        var successCount = 0
        var failCount = 0

        for ((name, initFn, lifecycleListener) in hookModules) {
            try {
                initFn()
                successCount++
                LogUtil.i(Constants.MODULE_TAG, "$name registered OK")
            } catch (e: Throwable) {
                failCount++
                LogUtil.e(Constants.MODULE_TAG, "$name FAILED: ${e.message}", e)
                try {
                    lifecycleListener.onHookFailed(e)
                } catch (_: Throwable) {
                    // 生命周期回调本身不应影响主流程
                }
            }
        }

        hooksRegistered = true
        hookStatus = if (failCount == 0) {
            if (dexKitAvailable) HookStatus.ACTIVE else HookStatus.DEGRADED
        } else if (successCount > 0) {
            HookStatus.DEGRADED
        } else {
            HookStatus.ERROR
        }
        LogUtil.i(Constants.MODULE_TAG, "Done - $successCount success, $failCount failed (status: ${hookStatus.description})")

        // 注册广播接收器
        try {
            appContext?.let { ctx ->
                ConfigReceiver.register(ctx)
                LogUtil.i(Constants.MODULE_TAG, "ConfigReceiver registered")
            }
        } catch (e: Throwable) {
            LogUtil.w(Constants.MODULE_TAG, "Failed to register ConfigReceiver: ${e.message}")
        }

        return successCount > 0
    }

    /**
     * 判断是否为微信主进程
     *
     * 微信主进程名固定为 "com.tencent.mm"，
     * 子进程名格式为 "com.tencent.mm:xxx"
     */
    private fun isWeChatMainProcess(processName: String): Boolean {
        // 主进程名就是包名本身
        if (processName == Constants.WECHAT_MAIN_PROCESS) return true

        // 检查是否包含冒号（子进程特征）
        if (processName.contains(":")) {
            LogUtil.d(Constants.MODULE_TAG, "Detected sub-process: $processName")
            return false
        }

        // 安全起见，如果不是子进程格式，也当作主进程处理
        LogUtil.d(Constants.MODULE_TAG, "Unknown process pattern: $processName, treating as main")
        return processName.startsWith(Constants.WECHAT_PACKAGE)
    }

    // ========================================================================
    // 版本兼容性检查
    // ========================================================================

    /**
     * 检查当前微信版本与 Mirage 的兼容性。
     *
     * 根据三个级别进行判断：
     * 1. [Constants.KNOWN_INCOMPATIBLE_VERSIONS] - 已知不兼容，输出 ERROR 级别日志
     * 2. [Constants.FULLY_TESTED_VERSIONS] - 完全测试通过，输出 INFO 级别日志
     * 3. [Constants.KNOWN_COMPATIBLE_VERSIONS] - 已知兼容，输出 INFO 级别日志
     * 4. 其他 - 未测试版本，输出 WARN 级别日志
     *
     * 此检查不会阻止模块加载，仅用于诊断和日志记录。
     *
     * @param lpparam Xposed LoadPackage 参数，用于获取微信版本信息
     */
    @JvmStatic
    fun checkVersionCompatibility(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val appInfo = lpparam.appInfo
            val versionName = appInfo?.versionName ?: "unknown"
            val versionCode = appInfo?.versionCode ?: 0

            LogUtil.i(Constants.MODULE_TAG, "WeChat version: $versionName (code: $versionCode)")

            when {
                // 已知不兼容
                versionName in Constants.KNOWN_INCOMPATIBLE_VERSIONS -> {
                    LogUtil.e(
                        Constants.MODULE_TAG,
                        "WARNING: WeChat version $versionName is KNOWN INCOMPATIBLE with Mirage! " +
                        "Some features may not work correctly. " +
                        "Please upgrade WeChat or use a compatible version."
                    )
                }
                // 完全测试通过
                versionName in Constants.FULLY_TESTED_VERSIONS -> {
                    LogUtil.i(
                        Constants.MODULE_TAG,
                        "WeChat version $versionName is fully tested and compatible."
                    )
                }
                // 已知兼容
                versionName in Constants.KNOWN_COMPATIBLE_VERSIONS -> {
                    LogUtil.i(
                        Constants.MODULE_TAG,
                        "WeChat version $versionName is in the known compatible list."
                    )
                }
                // 未测试版本
                else -> {
                    LogUtil.w(
                        Constants.MODULE_TAG,
                        "WeChat version $versionName is UNTESTED. " +
                        "Mirage may or may not work correctly. " +
                        "Compatible versions: ${Constants.KNOWN_COMPATIBLE_VERSIONS.joinToString(", ")}"
                    )
                    LogUtil.w(
                        Constants.MODULE_TAG,
                        "If you encounter issues, please report them with the WeChat version."
                    )
                }
            }
        } catch (e: Throwable) {
            LogUtil.w(Constants.MODULE_TAG, "Failed to check version compatibility: ${e.message}")
        }
    }
}