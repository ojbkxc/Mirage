package wx.mirage

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 构建信息（原 MaskAppBuildInfo，供主界面展示）。
 */
object BuildInfo {
    val BUILD_TIME: String = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())
}
