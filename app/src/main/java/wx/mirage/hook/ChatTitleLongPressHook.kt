package wx.mirage.hook

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import wx.mirage.Constants
import wx.mirage.MainHook
import wx.mirage.R
import wx.mirage.config.ConfigManager
import wx.mirage.config.FriendToggle
import wx.mirage.config.HookStatus
import wx.mirage.lifecycle.HookLifecycleListener
import wx.mirage.manager.ModuleConfigManager
import wx.mirage.manager.TempUnhideManager
import wx.mirage.model.FriendConfig
import wx.mirage.util.HookMetrics
import wx.mirage.util.LogUtil

/**
 * 聊天页标题长按 Hook 模块（对齐面纱核心体验）
 *
 * 功能：在好友聊天页面长按顶部标题（好友名）5 秒（可配置），
 * 弹出该好友的隐藏配置菜单，无需手动填写 wxId。
 *
 * 实现方式：
 * - Hook BaseChattingUIFragment.onResume
 * - 从聊天 Fragment 中提取当前聊天对象 wxId
 * - 遍历视图树找到顶部标题 TextView，注入 OnTouchListener 计时长按
 * - 长按达到阈值后在主线程弹出 AlertDialog 配置菜单
 *
 * 菜单内容：
 * - 总开关 + 8 项独立子开关（与 Mirage 管理界面一致）
 * - 伪装目标 ID 输入
 * - 临时显示 / 立即隐藏 快捷操作
 */
object ChatTitleLongPressHook : HookLifecycleListener {

    private const val TAG = Constants.MODULE_TAG + ":ChatTitleLongPressHook"

    /** 已注入过长按监听的 View，避免重复添加 */
    private val hookedViews = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<View, Boolean>()
    )

    @Volatile
    var status: HookStatus = HookStatus.INACTIVE
        private set

    private var targetClass: Class<*>? = null
    private var targetMethodName: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        try {
            initFallback(classLoader)
            status = if (MainHook.dexKitAvailable) HookStatus.ACTIVE else HookStatus.DEGRADED
            onHookRegistered()
        } catch (e: Throwable) {
            LogUtil.w(TAG, "Init failed: ${e.message}")
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

    private fun initFallback(classLoader: ClassLoader) {
        val candidates = listOf(
            Constants.WECHAT_CHATTING_BASE_FRAGMENT,
            "com.tencent.mm.ui.chatting.ChattingUIFragment",
            "com.tencent.mm.ui.chatting.ChattingUI"
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
        LogUtil.i(TAG, "ChatTitleLongPressHook registered (status: ${status.description})")
        targetClass?.let { clazz ->
            try {
                XposedHelpers.findAndHookMethod(clazz, "onResume", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        tryInstallLongPress(param.thisObject)
                    }
                })
                LogUtil.i(TAG, "Hooked onResume on ${clazz.name}")
            } catch (e: Throwable) {
                LogUtil.w(TAG, "Failed to hook onResume: ${e.message}")
            }
        }
    }

    override fun onHookFailed(error: Throwable) {
        LogUtil.e(TAG, "ChatTitleLongPressHook failed: ${error.message}", error)
        status = HookStatus.ERROR
    }

    override fun onHookUnregistered() {
        try {
            targetClass?.let { clazz ->
                XposedBridge.unhookMethod(clazz.getDeclaredMethod("onResume"), null)
            }
        } catch (_: Throwable) {}
        hookedViews.clear()
        status = HookStatus.INACTIVE
        LogUtil.i(TAG, "ChatTitleLongPressHook unregistered")
    }

    // ========================================================================
    // 长按监听安装
    // ========================================================================

    private fun tryInstallLongPress(fragment: Any?) {
        try {
            if (fragment == null) return
            HookMetrics.recordSuccess(TAG)

            val wxId = extractChatWxId(fragment)
            if (wxId.isNullOrEmpty()) {
                LogUtil.d(TAG, "No chat wxId found, skip")
                return
            }

            val view = findTitleView(fragment) ?: run {
                LogUtil.d(TAG, "No title view found in chat fragment")
                return
            }

            if (hookedViews.contains(view)) {
                // 已注入过，仅更新绑定的 wxId
                view.setTag(R.id.mirage_tag_wxid, wxId)
                return
            }

            installLongPressListener(view, wxId)
            LogUtil.i(TAG, "Long-press listener installed for chat: $wxId")
        } catch (e: Throwable) {
            LogUtil.e(TAG, "Error installing long press: ${e.message}", e)
        }
    }

    /**
     * 从聊天 Fragment 中提取当前聊天对象 wxId。
     * 供 ChatInputHook 的 #mwxid 指令复用。
     */
    fun extractWxIdFrom(fragment: Any): String? {
        return try {
            extractChatWxId(fragment)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 从聊天 Fragment 中提取当前聊天对象 wxId。
     *
     * 尝试顺序：
     * 1. 常见字段名（chattingUser / chatUser / mChatUser 等）
     * 2. Intent extra（Chat_Username / Single_chat_username）
     * 3. Fragment 参数
     */
    private fun extractChatWxId(fragment: Any): String? {
        // 1. 字段名试探
        val fieldNames = arrayOf(
            "chattingUser", "chattingUsername", "chatUser", "chatUsername",
            "mChatUser", "mChattingUser", "mUsername", "talker", "mTalker",
            "wxId", "mWxId", "peerUser", "peer"
        )
        for (name in fieldNames) {
            try {
                val v = XposedHelpers.getObjectField(fragment, name)
                if (v is String && v.isNotEmpty()) return v
            } catch (_: Throwable) {}
        }

        // 2. 通过 getArguments / Intent
        try {
            val args = XposedHelpers.callMethod(fragment, "getArguments")
            val intentKeys = arrayOf("Chat_Username", "Single_chat_username", "Chat_User", "username")
            for (key in intentKeys) {
                try {
                    val v = XposedHelpers.callMethod(args, "getString", key)
                    if (v is String && v.isNotEmpty()) return v
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // 3. 附加 Activity Intent
        try {
            val activity = XposedHelpers.callMethod(fragment, "getActivity")
            val intent = XposedHelpers.callMethod(activity, "getIntent")
            val keys = arrayOf("Chat_Username", "Single_chat_username", "Chat_User", "username")
            for (key in keys) {
                try {
                    val v = XposedHelpers.callMethod(intent, "getStringExtra", key)
                    if (v is String && v.isNotEmpty()) return v
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // 4. 递归反射查找 String 字段，取值像 wxId 的
        return findWxIdRecursive(fragment, 0, 2)
    }

    private fun findWxIdRecursive(obj: Any, depth: Int, maxDepth: Int): String? {
        if (depth > maxDepth) return null
        return try {
            for (field in obj.javaClass.declaredFields) {
                field.isAccessible = true
                try {
                    val v = field.get(obj) ?: continue
                    if (v is String && looksLikeWxId(v)) return v
                } catch (_: Throwable) {}
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun looksLikeWxId(s: String): Boolean {
        if (s.length < 6 || s.length > 40) return false
        if (!s[0].isLetter()) return false
        // 不含空格、路径分隔符
        if (s.contains(' ') || s.contains('/') || s.contains('\\')) return false
        // wxid_ 前缀或纯字母数字（含 -_）
        return s.matches(Regex("^[A-Za-z][A-Za-z0-9_-]*$"))
    }

    /**
     * 遍历视图树找到顶部标题 TextView。
     * 聊天页顶部是自定义 TitleBar 容器，标题是其中较大的 TextView。
     */
    private fun findTitleView(fragment: Any): View? {
        return try {
            val view = XposedHelpers.callMethod(fragment, "getView") as? View ?: return null
            findTitleInViewTree(view)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findTitleInViewTree(root: View): View? {
        // 收集所有可见的 TextView，按 Y 坐标排序，取最靠上且宽度较大的
        val candidates = mutableListOf<TextView>()
        collectTitleCandidates(root, candidates, 0)

        // 顶部区域（前 15% 高度内）且文字 1~25 字的 TextView
        return candidates
            .filter { it.text?.length in 1..25 && it.visibility == View.VISIBLE }
            .sortedBy { it.y }
            .firstOrNull { tv ->
                val loc = IntArray(2)
                try {
                    tv.getLocationOnScreen(loc)
                    loc[1] in 0..600
                } catch (_: Throwable) {
                    false
                }
            }
    }

    private fun collectTitleCandidates(view: View, out: MutableList<TextView>, depth: Int) {
        if (depth > 12 || out.size > 80) return
        if (view is TextView) {
            out.add(view)
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                try {
                    collectTitleCandidates(view.getChildAt(i), out, depth + 1)
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * 给标题 View 注入计时长按监听。
     * 面纱语义：按住 5 秒（可配置）弹出菜单，期间移开手指则取消。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun installLongPressListener(view: View, wxId: String) {
        try {
            view.setTag(R.id.mirage_tag_wxid, wxId)
            hookedViews.add(view)

            view.setOnTouchListener(object : View.OnTouchListener {
                private var downTime = 0L
                private val checkRunnable = Runnable {
                    showFriendMenu(view.context, view.getTag(R.id.mirage_tag_wxid) as? String ?: wxId)
                    downTime = 0L
                }

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    val ctx = MainHook.appContext
                    val duration = ctx?.let {
                        ModuleConfigManager.getLongPressDuration(it)
                    } ?: Constants.DEFAULT_LONG_PRESS_DURATION

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downTime = System.currentTimeMillis()
                            mainHandler.postDelayed(checkRunnable, duration.toLong())
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            mainHandler.removeCallbacks(checkRunnable)
                            downTime = 0L
                        }
                    }
                    // 返回 false 不消费事件，不影响原有点击行为
                    return false
                }
            })
        } catch (e: Throwable) {
            LogUtil.e(TAG, "installLongPressListener failed: ${e.message}", e)
        }
    }

    // ========================================================================
    // 配置菜单
    // ========================================================================

    private fun showFriendMenu(context: Context, wxId: String?) {
        try {
            if (wxId.isNullOrEmpty()) {
                Toast.makeText(context, "Mirage: 无法识别当前聊天对象", Toast.LENGTH_SHORT).show()
                return
            }

            val ctx = MainHook.appContext ?: context
            val hidden = ConfigManager.isHidden(ctx, wxId)

            AlertDialog.Builder(context)
                .setTitle("Mirage - ${if (hidden) "管理" else "隐藏"}此好友")
                .setMessage("wxId: $wxId")
                .setPositiveButton("隐藏设置") { _, _ ->
                    showConfigDialog(context, wxId)
                }
                .setNeutralButton(if (hidden) "取消隐藏" else "快速隐藏") { _, _ ->
                    if (hidden) {
                        ConfigManager.removeHiddenWxId(ctx, wxId)
                        TempUnhideManager.restoreForFriend(wxId)
                        Toast.makeText(context, "已取消隐藏: $wxId", Toast.LENGTH_SHORT).show()
                    } else {
                        ConfigManager.addHiddenWxId(ctx, wxId)
                        Toast.makeText(context, "已隐藏: $wxId", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Throwable) {
            LogUtil.e(TAG, "showFriendMenu failed: ${e.message}", e)
        }
    }

    /**
     * 完整配置弹窗（与 Mirage 管理界面一致的 8 项开关 + 伪装目标）。
     */
    private fun showConfigDialog(context: Context, wxId: String) {
        try {
            val ctx = MainHook.appContext ?: context
            val config = ConfigManager.getFriendConfig(ctx, wxId)
                ?: FriendConfig.createDefault(wxId)

            // 确保在隐藏列表中
            if (!ConfigManager.isHidden(ctx, wxId)) {
                ConfigManager.addHiddenWxId(ctx, wxId)
            }

            val scrollView = ScrollView(context)
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 24, 48, 24)
            }

            layout.addView(TextView(context).apply {
                text = wxId
                textSize = 16f
                setPadding(0, 0, 0, 16)
            })

            var disguiseTargetId = config.disguiseTargetId
            val disguiseEditText = EditText(context).apply {
                hint = "伪装目标 wxId（留空则不伪装）"
                setText(disguiseTargetId)
                setPadding(0, 8, 0, 8)
            }
            layout.addView(TextView(context).apply {
                text = "伪装目标ID:"
                textSize = 13f
                setPadding(0, 8, 0, 4)
            })
            layout.addView(disguiseEditText)

            val toggles = listOf(
                Triple("总开关", FriendToggle.MASTER_SWITCH, config.masterSwitch),
                Triple("拦截语音视频通话", FriendToggle.BLOCK_VOICE_CALL, config.blockVoiceCall),
                Triple("拦截消息通知", FriendToggle.BLOCK_NOTIFICATION, config.blockNotification),
                Triple("主页好友列表伪装", FriendToggle.DISGUISE_MAIN_PAGE, config.disguiseMainPage),
                Triple("聊天记录隐藏", FriendToggle.HIDE_CHAT_HISTORY, config.hideChatHistory),
                Triple("联系人隐藏", FriendToggle.HIDE_CONTACT, config.hideContact),
                Triple("朋友圈隐藏", FriendToggle.HIDE_MOMENTS, config.hideMoments),
                Triple("其他杂项隐藏", FriendToggle.HIDE_OTHER_MISC, config.hideOtherMisc)
            )

            val switches = mutableListOf<Switch>()

            toggles.forEach { (label, toggle, value) ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 12, 0, 12)
                }
                row.addView(TextView(context).apply {
                    text = label
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val sw = Switch(context).apply {
                    isChecked = value
                    tag = toggle
                }
                row.addView(sw)
                layout.addView(row)
                switches.add(sw)
            }

            scrollView.addView(layout)

            AlertDialog.Builder(context)
                .setTitle("好友配置")
                .setView(scrollView)
                .setPositiveButton("保存") { _, _ ->
                    disguiseTargetId = disguiseEditText.text.toString().trim()
                    switches.forEach { sw ->
                        ConfigManager.updateFriendToggle(ctx, wxId, sw.tag as FriendToggle, sw.isChecked)
                    }
                    val cfg = ConfigManager.getFriendConfig(ctx, wxId)
                        ?: FriendConfig.createDefault(wxId)
                    ConfigManager.setFriendConfig(ctx, cfg.copy(disguiseTargetId = disguiseTargetId))
                    Toast.makeText(context, "配置已保存", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("临时显示") { _, _ ->
                    TempUnhideManager.tempUnhide(wxId)
                    Toast.makeText(context, "已临时显示（2分钟后自动恢复隐藏）", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Throwable) {
            LogUtil.e(TAG, "showConfigDialog failed: ${e.message}", e)
        }
    }
}
