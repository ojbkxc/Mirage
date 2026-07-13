package wx.mirage.ui

import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import wx.mirage.MainHook
import wx.mirage.R
import wx.mirage.config.ConfigManager

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private val hiddenIds = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 标题
        supportActionBar?.title = "Mirage - 微信好友隐身"

        listView = findViewById(R.id.listView)

        // 添加按钮
        findViewById<Button>(R.id.btnAdd).setOnClickListener { showAddDialog() }

        // 模块开关
        val switchEnabled = findViewById<Switch>(R.id.switchEnabled)
        switchEnabled.isChecked = ConfigManager.isEnabled(this)
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            ConfigManager.setEnabled(this, isChecked)
        }

        loadHiddenIds()
    }

    override fun onResume() {
        super.onResume()
        loadHiddenIds()
    }

    private fun loadHiddenIds() {
        val ctx = MainHook.appContext ?: this
        hiddenIds.clear()
        hiddenIds.addAll(ConfigManager.getHiddenWxIds(ctx))

        // 加载标签
        val labels = ConfigManager.getLabels(ctx)
        val displayList = hiddenIds.map { wxId ->
            val label = labels[wxId] ?: ""
            if (label.isNotEmpty()) "$wxId ($label)" else wxId
        }

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayList)
        listView.adapter = adapter

        // 长按删除
        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (position < hiddenIds.size) {
                AlertDialog.Builder(this)
                    .setTitle("移除")
                    .setMessage("确定要移除 ${hiddenIds[position]} 的隐藏吗?")
                    .setPositiveButton("确定") { _, _ ->
                        ConfigManager.removeHiddenWxId(ctx, hiddenIds[position])
                        loadHiddenIds()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            true
        }
    }

    private fun showAddDialog() {
        val input = EditText(this).apply {
            hint = "输入要隐藏的微信 ID (wxid_xxx)"
            inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("添加隐藏好友")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val wxId = input.text.toString().trim()
                if (wxId.isNotEmpty()) {
                    ConfigManager.addHiddenWxId(MainHook.appContext ?: this, wxId)
                    loadHiddenIds()
                    Toast.makeText(this, "已添加: $wxId", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
