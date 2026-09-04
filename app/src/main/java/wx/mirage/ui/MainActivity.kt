package wx.mirage.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lu.magic.util.SizeUtil
import com.lu.magic.util.ToastUtil
import com.lu.magic.util.ripple.RectangleRippleBuilder
import com.lu.magic.util.ripple.RippleApplyUtil
import wx.mirage.BuildInfo
import wx.mirage.R
import wx.mirage.wxmask.ClazzN
import wx.mirage.wxmask.Constrant
import wx.mirage.wxmask.adapter.AbsListAdapter
import wx.mirage.wxmask.adapter.CommonListAdapter

/**
 * Mirage 模块主界面（Inkhide 三卡片风格）
 *
 * 1. 模块状态卡片：显示激活状态 + 版本/构建信息，点击查看更新日志
 * 2. 添加配置：跳转微信内添加密友
 * 3. 配置管理：跳转微信内管理密友名单
 */
class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private var mListAdapter: CommonListAdapter<Int, ItemViewHolder>? = null

    private val rippleRadius by lazy { SizeUtil.dp2px(resources, 8f).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_main)
        title = getString(R.string.action_bar_title)
        listView = findViewById(R.id.listView)

        mListAdapter = object : CommonListAdapter<Int, ItemViewHolder>() {
            init {
                setData(arrayListOf(1, 2, 3))
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
                val itemView = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_icon_text, parent, false)
                return ItemViewHolder(itemView).also { vh ->
                    vh.layoutItem.setOnClickListener {
                        when (getItem(vh.layoutPosition)) {
                            1 -> clickModuleCard()
                            2 -> jumpWxManagerConfigUI(Constrant.VALUE_INTENT_PLUGIN_MODE_ADD)
                            3 -> jumpWxManagerConfigUI(Constrant.VALUE_INTENT_PLUGIN_MODE_MANAGER)
                        }
                    }
                }
            }

            override fun onBindViewHolder(vh: ItemViewHolder, position: Int, parent: ViewGroup) {
                val itemValue = getItem(position) ?: return
                if (position != 0) {
                    applyCommonItemRipple(vh.layoutItem)
                }
                when (itemValue) {
                    1 -> {
                        // 模块自身进程无法通过 SelfHook 判断激活状态（那是微信进程内的 hook），
                        // 简化为始终显示模块版本信息
                        vh.ivItemIcon.setImageResource(R.drawable.ic_icon_check)
                        vh.tvItemTitle.setText(R.string.module_have_active)
                        applyModuleStateRipple(vh.layoutItem, true)
                        vh.tvItemTitleSub.text = getString(R.string.module_version) + "：v" + versionName
                        vh.tvItemTitleSub2.text = "构建时间：" + BuildInfo.BUILD_TIME
                        vh.tvItemTitleSub3.visibility = View.GONE
                        vh.tvItemTitleSub4.visibility = View.GONE
                    }

                    2 -> {
                        vh.ivItemIcon.setImageResource(R.drawable.ic_icon_add)
                        vh.tvItemTitle.setText(R.string.config_add)
                        vh.tvItemTitleSub.setText(R.string.click_here_to_add)
                        vh.tvItemTitleSub2.visibility = View.GONE
                        vh.tvItemTitleSub3.visibility = View.GONE
                        vh.tvItemTitleSub4.visibility = View.GONE
                    }

                    3 -> {
                        vh.ivItemIcon.setImageResource(R.drawable.ic_icon_manager)
                        vh.tvItemTitle.setText(R.string.config_manager)
                        vh.tvItemTitleSub.setText(R.string.click_here_to_manager)
                        vh.tvItemTitleSub2.visibility = View.GONE
                        vh.tvItemTitleSub3.visibility = View.GONE
                        vh.tvItemTitleSub4.visibility = View.GONE
                    }
                }
            }

            private fun applyCommonItemRipple(v: View) {
                RectangleRippleBuilder(Color.TRANSPARENT, Color.GRAY, rippleRadius).let {
                    RippleApplyUtil.apply(v, it)
                }
            }

            private fun applyModuleStateRipple(v: View, enable: Boolean) {
                val contentColor = if (enable) {
                    0xFFF8A1A4.toInt()
                } else {
                    0xFFFF6027.toInt()
                }
                RectangleRippleBuilder(contentColor, Color.GRAY, rippleRadius).let {
                    RippleApplyUtil.apply(v, it)
                }
            }
        }
        listView.adapter = mListAdapter
    }

    private val versionName: String
        get() = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

    private fun clickModuleCard() {
        try {
            val uri = "https://github.com/ojbkxc/Mirage/releases"
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
        } catch (e: Exception) {
            ToastUtil.show("打开发布页失败")
        }
    }

    /**
     * 跳转到微信进行配置
     */
    private fun jumpWxManagerConfigUI(mode: Int) {
        try {
            val intent = Intent()
            intent.action = "com.tencent.mm.action.BIZSHORTCUT"
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(Constrant.KEY_INTENT_FROM_MASK, true)
            intent.putExtra(Constrant.KEY_INTENT_PLUGIN_MODE, mode)
            intent.component = ComponentName("com.tencent.mm", ClazzN.LauncherUI)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "跳转WeChat配置页面失败", Toast.LENGTH_SHORT).show()
        }
    }

    class ItemViewHolder(itemView: View) : AbsListAdapter.ViewHolder(itemView) {
        val layoutItem: View = itemView.findViewById(R.id.layoutItem)
        val ivItemIcon: ImageView = itemView.findViewById(R.id.ivItemIcon)
        val tvItemTitle: TextView = itemView.findViewById(R.id.tvItemTitle)
        val tvItemTitleSub: TextView = itemView.findViewById(R.id.tvItemTitleSub)
        val tvItemTitleSub2: TextView = itemView.findViewById(R.id.tvItemTitleSub2)
        val tvItemTitleSub3: TextView = itemView.findViewById(R.id.tvItemTitleSub3)
        val tvItemTitleSub4: TextView = itemView.findViewById(R.id.tvItemTitleSub4)
    }
}
