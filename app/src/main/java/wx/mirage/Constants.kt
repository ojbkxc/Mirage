package wx.mirage

/**
 * Mirage 项目全局常量
 *
 * 集中管理所有项目级常量定义，避免在多个文件中重复声明。
 * 所有常量均为编译时常量（const val），由 Kotlin 编译器内联。
 */
object Constants {

    // ========== 模块基本信息 ==========

    /** 项目名称 */
    const val PROJECT_NAME = "Mirage"

    /** Mirage 版本号 */
    const val VERSION = "1.0.1"

    /** 模块日志标签 */
    const val MODULE_TAG = "Mirage"

    // ========== 微信相关 ==========

    /** 微信包名 */
    const val WECHAT_PACKAGE = "com.tencent.mm"

    /** 微信主进程名 */
    const val WECHAT_MAIN_PROCESS = "com.tencent.mm"

    /** 微信版本号最低要求 */
    const val MIN_WECHAT_VERSION = "8.0.40"

    // ========== 模块资源路径 ==========

    /** 模块资源路径（APK 内 assets 路径） */
    const val MODULE_ASSETS_PATH = "module"

    /** 模块配置文件名 */
    const val MODULE_CONFIG_FILE = "mirage_config.json"

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

    // ========== 重试配置 ==========

    /** Hook 注册最大重试次数 */
    const val MAX_HOOK_RETRY_COUNT = 3

    /** Hook 注册重试间隔（毫秒） */
    const val HOOK_RETRY_DELAY_MS = 1000L

    // ========== 版本兼容性 ==========

    /** 已知兼容的微信版本号列表 */
    val KNOWN_COMPATIBLE_VERSIONS = setOf(
        "8.0.49", "8.0.48", "8.0.47", "8.0.46", "8.0.45",
        "8.0.44", "8.0.43", "8.0.42", "8.0.41", "8.0.40"
    )

    /** 完全测试通过的微信版本号列表 */
    val FULLY_TESTED_VERSIONS = setOf(
        "8.0.49", "8.0.48"
    )

    /** 已知不兼容的微信版本号列表 */
    val KNOWN_INCOMPATIBLE_VERSIONS = setOf<String>()

    // ========== 广播 Action ==========

    /** 重新加载配置广播 */
    const val ACTION_RELOAD_CONFIG = "wx.mirage.action.RELOAD_CONFIG"

    /** 清除 DexKit 缓存广播 */
    const val ACTION_CLEAR_DEXKIT_CACHE = "wx.mirage.action.CLEAR_DEXKIT_CACHE"

    /** 强制重新加载 Hook 广播 */
    const val ACTION_FORCE_RELOAD_HOOKS = "wx.mirage.action.FORCE_RELOAD_HOOKS"

    /** 自定义权限名称 */
    const val PERMISSION_CONTROL = "wx.mirage.permission.CONTROL"

    // ========== 新增广播 Action ==========

    /** 临时取消隐藏广播 */
    const val ACTION_TEMP_UNHIDE = "wx.mirage.action.TEMP_UNHIDE"

    /** 恢复隐藏广播 */
    const val ACTION_RESTORE_HIDE = "wx.mirage.action.RESTORE_HIDE"

    /** 后台自动恢复广播 */
    const val ACTION_BACKGROUND_RESTORE = "wx.mirage.action.BACKGROUND_RESTORE"

    /** 配置变更通知广播 */
    const val ACTION_CONFIG_CHANGED = "wx.mirage.action.CONFIG_CHANGED"

    // ========== 默认指令 ==========

    /** 默认命令前缀 */
    const val DEFAULT_COMMAND_PREFIX = "#veil"

    /** 默认显示命令 */
    const val DEFAULT_SHOW_COMMAND = "#show"

    /** 默认保持命令 */
    const val DEFAULT_KEEP_COMMAND = "#keep"

    /** 默认列表命令 */
    const val DEFAULT_LIST_COMMAND = "#list"

    /** 默认 wxid 命令 */
    const val DEFAULT_WXID_COMMAND = "#wxid"

    /** 默认长按时长（毫秒） */
    const val DEFAULT_LONG_PRESS_DURATION = 5000

    /** 默认空白处长按时长（毫秒） */
    const val DEFAULT_LONG_PRESS_BLANK_DURATION = 10000

    /** 临时取消隐藏时长（毫秒） */
    const val TEMP_UNHIDE_DURATION_MS = 2 * 60 * 1000L
}