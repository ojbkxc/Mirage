package wx.mirage.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import wx.mirage.model.FriendConfig
import wx.mirage.config.ConfigManager

/**
 * 好友列表适配器
 *
 * 显示隐藏好友列表，每个条目显示 wxId 和标签。
 * 如果好友有 FriendConfig，还会显示其开关状态摘要。
 *
 * @param context Android Context
 * @param wxIds 隐藏好友 wxId 列表
 * @param labels wxId 到标签的映射
 */
class LabelAdapter(
    context: Context,
    private val wxIds: List<String>,
    private val labels: Map<String, String>
) : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, wxIds) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent) as TextView
        val wxId = wxIds[position]
        val label = labels[wxId] ?: ""

        // 获取 FriendConfig 以显示开关状态摘要
        val config = ConfigManager.getFriendConfig(context, wxId)
        val statusSummary = if (config != null) {
            buildStatusSummary(config)
        } else {
            ""
        }

        val displayText = buildString {
            if (label.isNotEmpty()) {
                append("[$label] ")
            }
            append(wxId)
            if (statusSummary.isNotEmpty()) {
                append("\n$statusSummary")
            }
        }

        view.text = displayText
        view.setPadding(32, 16, 32, 16)
        view.textSize = 13f
        return view
    }

    /**
     * 构建开关状态摘要字符串。
     * 显示总开关状态和启用的功能数量。
     */
    private fun buildStatusSummary(config: FriendConfig): String {
        val enabledFeatures = mutableListOf<String>()

        if (config.blockVoiceCall) enabledFeatures.add("语音")
        if (config.blockNotification) enabledFeatures.add("通知")
        if (config.disguiseMainPage) enabledFeatures.add("伪装")
        if (config.hideChatHistory) enabledFeatures.add("聊天")
        if (config.hideContact) enabledFeatures.add("联系人")
        if (config.hideMoments) enabledFeatures.add("朋友圈")
        if (config.hideOtherMisc) enabledFeatures.add("杂项")

        val masterStatus = if (config.masterSwitch) "✓" else "✗"
        val featureCount = enabledFeatures.size

        return if (config.masterSwitch) {
            "总开关: ✓ | 启用: ${enabledFeatures.joinToString(" ")} ($featureCount)"
        } else {
            "总开关: ✗ (已禁用)"
        }
    }
}