package wx.mirage.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import wx.mirage.Constants
import wx.mirage.R
import wx.mirage.config.ConfigManager
import wx.mirage.config.FriendToggle
import wx.mirage.config.WxIdBlacklist
import wx.mirage.manager.ModuleConfigManager
import wx.mirage.model.FriendConfig
import wx.mirage.util.BackupManager
import wx.mirage.util.ConfigValidator
import wx.mirage.util.LogUtil

/**
 * Mirage 主管理界面
 *
 * 功能：
 * 1. 模块开关控制
 * 2. 添加/删除隐藏好友
 * 3. 每个好友的独立开关配置（总开关、语音通话、通知、主页伪装、聊天记录、联系人、朋友圈、其他杂项）
 * 4. 备份管理
 * 5. 导入/导出
 */
class MainActivity : AppCompatActivity() {

    private lateinit var wxIdInput: EditText
    private lateinit var addButton: Button
    private lateinit var hiddenList: ListView
    private lateinit var enableSwitch: Switch
    private lateinit var listHeader: TextView
    private lateinit var exportButton: Button
    private lateinit var importButton: Button
    private lateinit var clearAllButton: Button
    private lateinit var refreshButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        updateHiddenList()
        setupListeners()
    }

    private fun initViews() {
        wxIdInput = findViewById(R.id.editWxId) ?: error("editWxId not found")
        addButton = findViewById(R.id.btnAdd)
        hiddenList = findViewById(R.id.listView)
        enableSwitch = findViewById(R.id.switchEnabled)
        listHeader = findViewById(R.id.tvListHeader)
        exportButton = findViewById(R.id.btnExport)
        importButton = findViewById(R.id.btnImport)
        clearAllButton = findViewById(R.id.btnClearAll)
        refreshButton = findViewById(R.id.btnRefresh)

        enableSwitch.isChecked = ConfigManager.isEnabled(this)
    }

    private fun setupListeners() {
        addButton.setOnClickListener { addWxId() }
        enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            ConfigManager.setEnabled(this, isChecked)
            LogUtil.i(Constants.MODULE_TAG, "Module enabled: $isChecked")
        }

        // 短按：打开好友详细配置
        hiddenList.setOnItemClickListener { _, _, position, _ ->
            showFriendConfigDialog(position)
        }

        // 长按：删除好友
        hiddenList.setOnItemLongClickListener { _, _, position, _ ->
            showDeleteDialog(position)
            true
        }

        refreshButton.setOnClickListener { updateHiddenList() }
        exportButton.setOnClickListener { exportConfig() }
        importButton.setOnClickListener { importConfig() }
        clearAllButton.setOnClickListener { showClearAllDialog() }
    }

    private fun addWxId() {
        val wxId = wxIdInput.text.toString().trim()
        if (wxId.isEmpty()) {
            Toast.makeText(this, "请输入微信 ID", Toast.LENGTH_SHORT).show()
            return
        }

        if (ConfigValidator.isWxIdValid(wxId)) {
            val success = ConfigManager.addHiddenWxId(this, wxId)
            if (success) {
                wxIdInput.text.clear()
                updateHiddenList()
                Toast.makeText(this, "已添加: $wxId", Toast.LENGTH_SHORT).show()
            } else {
                if (WxIdBlacklist.isBlacklisted(wxId)) {
                    Toast.makeText(this, "该 wxId 为系统账户，不允许隐藏", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "添加失败：wxId 已存在或格式无效", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, "wxId 格式无效", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateHiddenList() {
        val ids = ConfigManager.getHiddenWxIds(this).toList()
        val configs = ConfigManager.getAllFriendConfigs(this).mapValues { it.value.label }
        val adapter = LabelAdapter(this, ids, configs)
        hiddenList.adapter = adapter
        listHeader.text = "已隐藏的好友 (${ids.size}) - 点击配置，长按删除"
    }

    private fun showDeleteDialog(position: Int) {
        val ids = ConfigManager.getHiddenWxIds(this).toList()
        if (position >= ids.size) return

        val wxId = ids[position]
        val config = ConfigManager.getFriendConfig(this, wxId)
        val label = config?.label ?: ""
        val displayText = if (label.isNotEmpty()) "$wxId [$label]" else wxId

        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要移除 \"$displayText\" 的隐藏吗？\n所有配置将被删除。")
            .setPositiveButton("删除") { _, _ ->
                ConfigManager.removeHiddenWxId(this, wxId)
                updateHiddenList()
                Toast.makeText(this, "已删除: $wxId", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ========================================================================
    // 好友详细配置对话框
    // ========================================================================

    private fun showFriendConfigDialog(position: Int) {
        val ids = ConfigManager.getHiddenWxIds(this).toList()
        if (position >= ids.size) return

        val wxId = ids[position]
        val config = ConfigManager.getFriendConfig(this, wxId) ?: return

        // 构建配置视图
        val scrollView = android.widget.ScrollView(this)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val label = config.label

        // 标题
        layout.addView(TextView(this).apply {
            text = "$wxId${if (label.isNotEmpty()) " [$label]" else ""}"
            textSize = 16f
            setPadding(0, 0, 0, 16)
        })

        // 伪装目标ID输入框（长按切换显示）
        var disguiseTargetId = config.disguiseTargetId
        val disguiseEditText = EditText(this).apply {
            hint = "伪装目标 wxId（留空则不伪装）"
            setText(disguiseTargetId)
            minLines = 1
            maxLines = 1
            setPadding(0, 8, 0, 8)
        }
        layout.addView(TextView(this).apply {
            text = "伪装目标ID:"
            textSize = 13f
            setPadding(0, 8, 0, 4)
        })
        layout.addView(disguiseEditText)

        // 分隔线
        layout.addView(View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
            setBackgroundColor(0xFFE0E0E0.toInt())
            setPadding(0, 8, 0, 8)
        })

        // 创建开关列表
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
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
            }

            val textView = TextView(this).apply {
                text = label
                textSize = 14f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            val switch = Switch(this).apply {
                isChecked = value
                tag = toggle
            }

            row.addView(textView)
            row.addView(switch)
            layout.addView(row)

            switches.add(switch)
        }

        scrollView.addView(layout)

        AlertDialog.Builder(this)
            .setTitle("好友配置")
            .setView(scrollView)
            .setPositiveButton("保存") { _, _ ->
                // 读取伪装目标ID
                disguiseTargetId = disguiseEditText.text.toString().trim()
                switches.forEach { switch ->
                    val toggle = switch.tag as FriendToggle
                    val isChecked = switch.isChecked
                    ConfigManager.updateFriendToggle(this, wxId, toggle, isChecked)
                }
                // 保存伪装目标ID
                if (disguiseTargetId != null) {
                    val cfg = ConfigManager.getFriendConfig(this, wxId) ?: FriendConfig.createDefault(wxId, label)
                    ConfigManager.setFriendConfig(this, cfg.copy(disguiseTargetId = disguiseTargetId))
                }
                updateHiddenList()
                Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("重置为默认") { _, _ ->
                val defaultConfig = FriendConfig.createDefault(wxId, label)
                ConfigManager.setFriendConfig(this, defaultConfig)
                updateHiddenList()
                Toast.makeText(this, "已重置为默认配置", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ========================================================================
    // 导入/导出
    // ========================================================================

    private fun exportConfig() {
        val json = ConfigManager.exportToJson(this)
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Mirage Backup", json))
        Toast.makeText(this, "配置已导出到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun importConfig() {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        val json = clip?.getItemAt(0)?.text?.toString() ?: ""

        val editText = EditText(this).apply {
            setText(json)
            minLines = 5
            hint = "粘贴要导入的 JSON 数据"
        }

        AlertDialog.Builder(this)
            .setTitle("导入配置")
            .setView(editText)
            .setPositiveButton("导入") { _, _ ->
                val inputJson = editText.text.toString().trim()
                if (inputJson.isEmpty()) {
                    Toast.makeText(this, "请输入 JSON 数据", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val count = ConfigManager.importFromJson(this, inputJson)
                if (count >= 0) {
                    updateHiddenList()
                    Toast.makeText(this, "成功导入 $count 个好友", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "导入失败：JSON 格式不正确", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearAllDialog() {
        val count = ConfigManager.getHiddenWxIds(this).size
        if (count == 0) {
            Toast.makeText(this, "没有可清除的隐藏好友", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("清空全部")
            .setMessage("确定要移除所有 $count 个隐藏好友吗？\n此操作不可撤销！")
            .setPositiveButton("确认清空") { _, _ ->
                ConfigManager.clear(this)
                updateHiddenList()
                Toast.makeText(this, "已清空全部隐藏好友", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}