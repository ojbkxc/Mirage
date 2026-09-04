package wx.mirage.wxmask.bean

import androidx.annotation.Keep
import com.google.gson.JsonObject
import com.lu.magic.util.kxt.optInt
import wx.mirage.wxmask.Constrant

/**
 * 解除密码模式配置：输入数字密码后临时解除隐藏
 */
@Keep
class CipherTemporaryBean(
    /** 数字密码，空串表示未设置（关闭密码模式） */
    var password: String = "",
    /** 临时解除时长/毫秒 */
    var duration: Int = 15000
) : BaseTemporary(Constrant.CONFIG_TEMPORARY_MODE_CIPHER) {

    constructor(json: JsonObject) : this() {
        password = try {
            json.get("password")?.asString ?: ""
        } catch (e: Exception) {
            ""
        }
        duration = json.optInt("duration", 15000)
    }

    val enable: Boolean get() = password.isNotEmpty()

    companion object {
        @JvmStatic
        fun from(json: JsonObject?): CipherTemporaryBean {
            return CipherTemporaryBean(json ?: JsonObject())
        }
    }
}
