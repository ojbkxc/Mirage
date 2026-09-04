package wx.mirage.wxmask.plugin.part

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.ViewGroup
import android.widget.TextView
import com.lu.lposed.api2.XC_MethodHook2
import com.lu.lposed.api2.XposedHelpers2
import com.lu.lposed.plugin.IPlugin
import com.lu.magic.util.AppUtil
import com.lu.magic.util.log.LogUtil
import wx.mirage.wxmask.ClazzN
import wx.mirage.wxmask.util.ConfigUtil
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 密友专属提醒（防漏看）：
 * - 震动：密友发消息时触发一次短震动
 * - 顶部加粗：微信主界面顶部标题栏「微信(1)」文字加粗，提示有新消息但不显示内容
 *
 * 原理：
 * - hook NotificationManager.notify，从 Notification.extras 提取会话 id，
 *   命中密友名单则震动（通知本身不拦截，保持原样避免查岗时露馅）
 * - 主页 LauncherUI 标题栏在通知到达后刷新，进入主页 onResume 时统一加粗
 */
class MaskAlertPluginPart : IPlugin {

    companion object {
        private var hasHook = false
        /** 上次震动时间戳，防止连续消息反复震 */
        private var lastVibrateTime = 0L
        private const val VIBRATE_INTERVAL = 2000L
        /** 密友消息最近到达时间；标题加粗窗口期（5 分钟内保持加粗） */
        @Volatile
        private var maskArrivedTime = 0L
        private const val BOLD_WINDOW = 5 * 60 * 1000L

        fun hasMaskArrivedRecently(): Boolean {
            return System.currentTimeMillis() - maskArrivedTime < BOLD_WINDOW
        }

        fun markMaskArrived() {
            maskArrivedTime = System.currentTimeMillis()
        }

        fun tryVibrate() {
            val now = System.currentTimeMillis()
            if (now - lastVibrateTime < VIBRATE_INTERVAL) {
                return
            }
            lastVibrateTime = now
            try {
                val context = AppUtil.getContext()
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // 轻微短震两次
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 80, 40), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 40, 80, 40), -1)
                }
            } catch (e: Throwable) {
                LogUtil.w("alert vibrate fail", e)
            }
        }
    }

    override fun handleHook(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        if (hasHook) {
            return
        }
        hasHook = true
        hookNotification(context, lpparam)
        hookLauncherTitle(context, lpparam)
    }

    /**
     * 通知到达时：在微信进程内 hook NotificationManager.notify，
     * 从 Notification.extras 中提取会话 id（微信通知 extras 无标准 key，
     * 因此同时 hook 微信通知构造入库点不可行时，退化为“密友窗口期”策略：
     * 只要微信发了通知且 extras 命中密友名单中的昵称，就震动并记录到达时间）
     */
    private fun hookNotification(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers2.findAndHookMethod(
                NotificationManager::class.java,
                "notify",
                java.lang.Integer.TYPE,
                Notification::class.java,
                object : XC_MethodHook2() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val option = ConfigUtil.getOptionData()
                        if (!option.enableAlertVibrate && !option.enableAlertBoldTitle) {
                            return
                        }
                        val notification = param.args[1] as? Notification ?: return
                        val hit = isMaskNotification(notification)
                        if (hit) {
                            markMaskArrived()
                            if (option.enableAlertVibrate) {
                                tryVibrate()
                                LogUtil.d("alert vibrate for mask user")
                            }
                        }
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hook notify fail", it)
        }
    }

    /**
     * 判断通知是否来自密友：遍历密友名单，比对通知标题/文本是否含密友昵称。
     * （通知 extras 中只有展示用的昵称，没有 wxid；这是微信通知机制限制）
     */
    private fun isMaskNotification(notification: Notification): Boolean {
        return try {
            val extras = notification.extras ?: return false
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            if (title.isNullOrBlank() && text.isNullOrBlank()) {
                return false
            }
            ConfigUtil.getMaskList().any { bean ->
                val nick = bean.tagName
                if (nick.isBlank()) {
                    false
                } else {
                    nick == title || nick == text
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 主页标题加粗：LauncherUI onResume 后找 Toolbar 标题设 BOLD
     * 微信主页顶部标题是「微信」，有未读时显示「微信(1)」
     */
    private fun hookLauncherTitle(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers2.findAndHookMethod(
                ClazzN.LauncherUI,
                context.classLoader,
                "onResume",
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!ConfigUtil.getOptionData().enableAlertBoldTitle) {
                            return
                        }
                        val activity = param.thisObject as? android.app.Activity ?: return
                        boldTitleIfHasMaskUnread(activity)
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hook LauncherUI fail", it)
        }
    }

    private fun boldTitleIfHasMaskUnread(activity: android.app.Activity) {
        try {
            // 判断是否有密友未读：无未读时不加粗（保持正常外观）
            if (!hasMaskUnread()) {
                return
            }
            val decor = activity.window?.decorView as? ViewGroup ?: return
            findTitleView(decor, 0)?.let {
                it.paint.isFakeBoldText = true
                it.tag = "mirage-bold-title"
            }
        } catch (e: Throwable) {
            LogUtil.w("bold title fail", e)
        }
    }

    private fun hasMaskUnread(): Boolean {
        // 简化：会话数据无法直接读，依赖通知到达时记录的状态
        return hasMaskArrivedRecently()
    }

    private fun findTitleView(view: ViewGroup, depth: Int): TextView? {
        if (depth > 8) {
            return null
        }
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child is TextView) {
                val text = child.text?.toString() ?: continue
                // 主页标题形如「微信」「微信(1)」，排除副标题与无文字项
                if (text == "微信" || text.matches(Regex("^微信\\s*\\(\\d+\\)$"))) {
                    return child
                }
            }
            if (child is ViewGroup) {
                findTitleView(child, depth + 1)?.let { return it }
            }
        }
        return null
    }
}
