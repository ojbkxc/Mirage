package wx.mirage.wxmask

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 构建信息。
 *
 * 原版通过 buildConfigField 注入 git 信息，
 * Mirage 并入后改为静态版本信息（BuildConfig 关闭，不依赖注入）。
 */
class MaskAppBuildInfo(
    val buildTime: String,
    val branch: String,
    val commit: String,
) {
    companion object {
        fun of(): MaskAppBuildInfo {
            return MaskAppBuildInfo(
                SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
                    .format(System.currentTimeMillis()),
                "main",
                "mirage"
            )
        }
    }
}
