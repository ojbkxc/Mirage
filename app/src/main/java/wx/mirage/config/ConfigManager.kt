package wx.mirage.config

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object ConfigManager {
    private const val SP_NAME = "wx_mirage_config"
    private const val KEY_HIDDEN_IDS = "hidden_wx_ids"
    private const val KEY_ENABLED = "module_enabled"

    private var spName: String = SP_NAME

    fun init(packageName: String) {
        spName = "${SP_NAME}_${packageName}"
    }

    private fun getSP(context: Context) =
        context.getSharedPreferences(spName, Context.MODE_PRIVATE)

    // ===== 隐藏好友列表 =====

    fun getHiddenWxIds(context: Context): Set<String> {
        return getSP(context).getStringSet(KEY_HIDDEN_IDS, emptySet()) ?: emptySet()
    }

    fun setHiddenWxIds(context: Context, ids: Set<String>) {
        getSP(context).edit().putStringSet(KEY_HIDDEN_IDS, ids).apply()
    }

    fun addHiddenWxId(context: Context, wxId: String) {
        val current = getHiddenWxIds(context).toMutableSet()
        current.add(wxId)
        setHiddenWxIds(context, current)
    }

    fun removeHiddenWxId(context: Context, wxId: String) {
        val current = getHiddenWxIds(context).toMutableSet()
        current.remove(wxId)
        setHiddenWxIds(context, current)
    }

    fun isHidden(context: Context, wxId: String): Boolean {
        return wxId in getHiddenWxIds(context)
    }

    // ===== 模块开关 =====

    fun isEnabled(context: Context): Boolean {
        return getSP(context).getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        getSP(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // ===== 备注/标签 =====
    private const val KEY_LABELS = "hidden_labels"

    data class FriendLabel(val wxId: String, val label: String)

    fun getLabels(context: Context): Map<String, String> {
        val json = getSP(context).getString(KEY_LABELS, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) { emptyMap() }
    }

    fun setLabel(context: Context, wxId: String, label: String) {
        val labels = getLabels(context).toMutableMap()
        labels[wxId] = label
        getSP(context).edit().putString(KEY_LABELS, Gson().toJson(labels)).apply()
    }
}
