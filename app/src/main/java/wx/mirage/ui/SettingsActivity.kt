package wx.mirage.ui

import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import wx.mirage.Constants
import wx.mirage.R
import wx.mirage.config.ConfigManager
import wx.mirage.config.WxIdBlacklist
import wx.mirage.manager.ModuleConfigManager
import wx.mirage.util.BackupHelper
import wx.mirage.util.HookMetrics
import wx.mirage.util.LogUtil

/**
 * Mirage 设置界面
 *
 * 功能：
 * 1. Debug 模式开关
 * 2. 日志级别选择
 * 3. 操作按钮（清除 DexKit 缓存、强制重载 Hook、查看日志、Hook 状态）
 * 4. 模块级配置（命令前缀、长按触发、功能开关）
 * 5. 备份管理
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var switchDebugMode: Switch
    private lateinit var spinnerLogLevel: Spinner
    private lateinit var btnClearDexKitCache: Button
    private lateinit var btnForceReloadHooks: Button
    private lateinit var btnViewLogs: Button
    private lateinit var btnHookStatus: Button
    private lateinit var moduleSettingsBtn: Button
    private lateinit var btnCreateBackup: Button
    private lateinit var btnRestoreBackup: Button
    private lateinit var btnListBackups: Button
    private lateinit var btnDeleteOldBackups: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initViews()
        setupListeners()
    }

    private fun initViews() {
        switchDebugMode = findViewById(R.id.switchDebugMode)
        spinnerLogLevel = findViewById(R.id.spinnerLogLevel)
        btnClearDexKitCache = findViewById(R.id.btnClearDexKitCache)
        btnForceReloadHooks = findViewById(R.id.btnForceReloadHooks)
        btnViewLogs = findViewById(R.id.btnViewLogs)
        btnHookStatus = findViewById(R.id.btnHookStatus)
        moduleSettingsBtn = findViewById(R.id.moduleSettingsBtn)
        btnCreateBackup = findViewById(R.id.btnCreateBackup)
        btnRestoreBackup = findViewById(R.id.btnRestoreBackup)
        btnListBackups = findViewById(R.id.btnListBackups)
        btnDeleteOldBackups = findViewById(R.id.btnDeleteOldBackups)

        // 设置日志级别 Spinner
        val logLevels = arrayOf("DEBUG", "INFO", "WARN", "ERROR")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, logLevels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLogLevel.adapter = adapter
        // 默认选中 INFO
        spinnerLogLevel.setSelection(1)

        switchDebugMode.isChecked = false
    }

    private fun setupListeners() {
        switchDebugMode.setOnCheckedChangeListener { _, isChecked ->
            LogUtil.setDebugMode(isChecked)
            LogUtil.i(Constants.MODULE_TAG, "Debug mode: $isChecked")
        }

        spinnerLogLevel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val level = when (position) {
                    0 -> LogUtil.LEVEL_DEBUG
                    1 -> LogUtil.LEVEL_INFO
                    2 -> LogUtil.LEVEL_WARN
                    3 -> LogUtil.LEVEL_ERROR
                    else -> LogUtil.LEVEL_INFO
                }
                LogUtil.setLogLevel(level)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnClearDexKitCache.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清除缓存")
                .setMessage(R.string.settings_clear_dexkit_cache_msg)
                .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                    try {
                        // 发送广播清除 DexKit 缓存
                        val intent = android.content.Intent(Constants.ACTION_CLEAR_DEXKIT_CACHE)
                        intent.setPackage(Constants.WECHAT_PACKAGE)
                        sendBroadcast(intent, Constants.PERMISSION_CONTROL)
                        Toast.makeText(this, R.string.settings_dexkit_cache_cleared, Toast.LENGTH_SHORT).show()
                    } catch (e: Throwable) {
                        Toast.makeText(this, getString(R.string.settings_dexkit_cache_clear_failed, e.message), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }

        btnForceReloadHooks.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("重载 Hook")
                .setMessage(R.string.settings_force_reload_hooks_msg)
                .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                    try {
                        val intent = android.content.Intent(Constants.ACTION_FORCE_RELOAD_HOOKS)
                        intent.setPackage(Constants.WECHAT_PACKAGE)
                        sendBroadcast(intent, Constants.PERMISSION_CONTROL)
                        Toast.makeText(this, R.string.settings_hooks_reload_triggered, Toast.LENGTH_SHORT).show()
                        Toast.makeText(this, R.string.settings_hooks_need_restart, Toast.LENGTH_SHORT).show()
                    } catch (e: Throwable) {
                        Toast.makeText(this, getString(R.string.settings_hooks_reload_failed, e.message), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }

        btnViewLogs.setOnClickListener {
            showLogsDialog()
        }

        btnHookStatus.setOnClickListener {
            showHookStatusDialog()
        }

        moduleSettingsBtn.setOnClickListener {
            showModuleSettingsDialog()
        }

        btnCreateBackup.setOnClickListener {
            BackupHelper.createBackup(this)
        }

        btnRestoreBackup.setOnClickListener {
            BackupHelper.showRestoreBackupDialog(this) {
                LogUtil.i(Constants.MODULE_TAG, "Backup restored")
            }
        }

        btnListBackups.setOnClickListener {
            BackupHelper.showListBackupsDialog(this)
        }

        btnDeleteOldBackups.setOnClickListener {
            BackupHelper.showDeleteOldBackupsDialog(this) {
                LogUtil.i(Constants.MODULE_TAG, "Old backups deleted")
            }
        }
    }

    // ========================================================================
    // 模块级设置对话框
    // ========================================================================

    private fun showModuleSettingsDialog() {
        val scrollView = android.widget.ScrollView(this)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        // --- 命令配置 ---
        layout.addView(sectionTitle("命令配置"))

        val commandPrefix = ModuleConfigManager.getCommandPrefix(this)
        val showCommand = ModuleConfigManager.getShowCommand(this)
        val keepCommand = ModuleConfigManager.getKeepCommand(this)
        val listCommand = ModuleConfigManager.getListCommand(this)
        val wxidCommand = ModuleConfigManager.getWxidCommand(this)

        val commandPrefixEdit = addEditRow(layout, "设置命令（默认 #veil）", commandPrefix)
        val showCommandEdit = addEditRow(layout, "显示命令（默认 #show）", showCommand)
        val keepCommandEdit = addEditRow(layout, "保持命令（默认 #keep）", keepCommand)
        val listCommandEdit = addEditRow(layout, "列表命令（默认 #list）", listCommand)
        val wxidCommandEdit = addEditRow(layout, "wxid命令（默认 #wxid）", wxidCommand)

        // --- 长按配置 ---
        layout.addView(sectionTitle("长按配置"))
        val longPressDuration = ModuleConfigManager.getLongPressDuration(this).toString()
        val blankPressDuration = ModuleConfigManager.getLongPressBlankDuration(this).toString()
        val longPressEdit = addEditRow(layout, "长按触发时长(ms)", longPressDuration)
        val blankPressEdit = addEditRow(layout, "空白处长按时长(ms)", blankPressDuration)

        // --- 功能开关 ---
        layout.addView(sectionTitle("功能开关"))
        val switchLongPress = addSwitchRow(layout, "长按触发功能", ModuleConfigManager.isLongPressEnabled(this))
        val switchCommand = addSwitchRow(layout, "命令输入功能", ModuleConfigManager.isCommandEnabled(this))
        val switchMsgIndicator = addSwitchRow(layout, "密友消息提示", ModuleConfigManager.isMessageIndicatorEnabled(this))
        val switchAntiRevoke = addSwitchRow(layout, "消息防撤回", ModuleConfigManager.isAntiRevokeEnabled(this))
        val switchAdRemoval = addSwitchRow(layout, "朋友圈去广告", ModuleConfigManager.isAdRemovalEnabled(this))

        scrollView.addView(layout)

        AlertDialog.Builder(this)
            .setTitle("模块设置")
            .setView(scrollView)
            .setPositiveButton("保存") { _, _ ->
                ModuleConfigManager.setCommandPrefix(this, commandPrefixEdit.text.toString().trim())
                ModuleConfigManager.setShowCommand(this, showCommandEdit.text.toString().trim())
                ModuleConfigManager.setKeepCommand(this, keepCommandEdit.text.toString().trim())
                ModuleConfigManager.setListCommand(this, listCommandEdit.text.toString().trim())
                ModuleConfigManager.setWxidCommand(this, wxidCommandEdit.text.toString().trim())

                val longPress = longPressEdit.text.toString().trim().toIntOrNull() ?: 5000
                val blankPress = blankPressEdit.text.toString().trim().toIntOrNull() ?: 10000
                ModuleConfigManager.setLongPressDuration(this, longPress)
                ModuleConfigManager.setLongPressBlankDuration(this, blankPress)

                ModuleConfigManager.setLongPressEnabled(this, switchLongPress.isChecked)
                ModuleConfigManager.setCommandEnabled(this, switchCommand.isChecked)
                ModuleConfigManager.setMessageIndicatorEnabled(this, switchMsgIndicator.isChecked)
                ModuleConfigManager.setAntiRevokeEnabled(this, switchAntiRevoke.isChecked)
                ModuleConfigManager.setAdRemovalEnabled(this, switchAdRemoval.isChecked)

                Toast.makeText(this, "模块设置已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 16, 0, 8)
        }
    }

    private fun addEditRow(layout: android.widget.LinearLayout, label: String, value: String): EditText {
        layout.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setPadding(0, 8, 0, 4)
        })
        val editText = EditText(this).apply {
            setText(value)
            minLines = 1
            maxLines = 1
            setPadding(0, 4, 0, 4)
        }
        layout.addView(editText)
        return editText
    }

    private fun addSwitchRow(layout: android.widget.LinearLayout, label: String, checked: Boolean): Switch {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val textView = TextView(this).apply {
            text = label
            textSize = 14f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val switch = Switch(this).apply {
            isChecked = checked
        }
        row.addView(textView)
        row.addView(switch)
        layout.addView(row)
        return switch
    }

    // ========================================================================
    // 日志查看
    // ========================================================================

    private fun showLogsDialog() {
        val logs = LogUtil.getRecentLogs(200)
        val scrollView = android.widget.ScrollView(this)
        val textView = TextView(this).apply {
            text = if (logs.isEmpty()) "暂无日志" else logs.joinToString("\n")
            textSize = 11f
            setPadding(16, 16, 16, 16)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scrollView.addView(textView)

        AlertDialog.Builder(this)
            .setTitle("日志查看")
            .setView(scrollView)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空") { _, _ ->
                LogUtil.clearLogs()
                Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showHookStatusDialog() {
        val msg = buildString {
            append("Hook 注册状态：\n")
            append("ContactHook: ${wx.mirage.hook.ContactHook.status}\n")
            append("ConversationHook: ${wx.mirage.hook.ConversationHook.status}\n")
            append("MomentsHook: ${wx.mirage.hook.MomentsHook.status}\n")
            append("NotificationHook: ${wx.mirage.hook.NotificationHook.status}\n")
            append("SearchHook: ${wx.mirage.hook.SearchHook.status}\n")
            append("GroupMemberHook: ${wx.mirage.hook.GroupMemberHook.status}\n")
            append("VoiceCallHook: ${wx.mirage.hook.VoiceCallHook.status}\n")
            append("MiscHook: ${wx.mirage.hook.MiscHook.status}\n")
            append("ChatInputHook: ${wx.mirage.hook.ChatInputHook.status}\n")
            append("SearchInputHook: ${wx.mirage.hook.SearchInputHook.status}\n")
            append("LongPressHook: ${wx.mirage.hook.LongPressHook.status}\n")
            append("MessageAntiRevokeHook: ${wx.mirage.hook.MessageAntiRevokeHook.status}\n")
            append("MessageIndicatorHook: ${wx.mirage.hook.MessageIndicatorHook.status}\n")
            append("MomentsAdRemovalHook: ${wx.mirage.hook.MomentsAdRemovalHook.status}\n")
            append("TempMomentsUnhideHook: ${wx.mirage.hook.TempMomentsUnhideHook.status}\n")
            append("ConversationLongClickHook: ${wx.mirage.hook.ConversationLongClickHook.status}\n")
        }

        AlertDialog.Builder(this)
            .setTitle("Hook 状态")
            .setMessage(msg)
            .setPositiveButton("关闭", null)
            .show()
    }
}