package wx.mirage.manager

import android.os.Handler
import android.os.Looper
import wx.mirage.Constants
import wx.mirage.config.ConfigManager
import wx.mirage.util.LogUtil

/**
 * 临时取消隐藏管理器
 *
 * 管理 #keep 命令的临时取消隐藏功能。
 * 当用户通过 #keep 命令临时取消隐藏某个好友后，该好友的所有隐藏功能将暂时失效，
 * 2分钟后自动恢复到隐藏状态。用户也可以手动通过"立即隐藏"菜单项提前恢复。
 *
 * ## 核心功能
 * - [tempUnhide]: 标记好友为临时取消隐藏，启动2分钟自动恢复定时器
 * - [isTempUnhidden]: 检查好友是否当前处于临时取消隐藏状态
 * - [restoreAll]: 立即恢复所有临时取消隐藏的好友
 * - [restoreOnAppBackground]: 当微信进入后台时启动2分钟定时器
 * - [cancelTimers]: 取消所有待处理的定时器
 *
 * ## 存储策略
 * 所有临时取消隐藏状态仅存储在内存中（不持久化），
 * 进程重启后自动清除，确保不会意外遗留取消隐藏状态。
 *
 * ## 线程安全
 * 使用 @Synchronized 和 @Volatile 保证多线程安全。
 * Handler 操作在主线程（Looper.getMainLooper()）上执行。
 */
object TempUnhideManager {

    private const val TAG = "${Constants.MODULE_TAG}:TempUnhideManager"

    /** 默认临时取消隐藏持续时间（毫秒）：2分钟 */
    private const val DEFAULT_TEMP_DURATION_MS = 2 * 60 * 1000L

    /** 后台后自动恢复延迟（毫秒）：2分钟 */
    private const val BACKGROUND_RESTORE_DELAY_MS = 2 * 60 * 1000L

    /**
     * 当前临时取消隐藏的好友集合。
     * 线程安全：使用 @Volatile 保证跨线程可见性，
     * 所有写操作通过 @Synchronized 方法进行。
     */
    @Volatile
    private var tempUnhiddenSet: Set<String> = emptySet()

    /** 主线程 Handler，用于定时器 */
    private val handler = Handler(Looper.getMainLooper())

    /** 当前待执行的自动恢复定时器 Runnable */
    private var autoRestoreRunnable: Runnable? = null

    /** 后台恢复定时器 Runnable */
    private var backgroundRestoreRunnable: Runnable? = null

    /**
     * 添加"立即隐藏"菜单项相关的 wxId 集合。
     * 与 tempUnhiddenSet 不同，此集合用于跟踪哪些好友需要显示"立即隐藏"菜单项，
     * 当用户使用 #keep 命令后添加，当用户点击"立即隐藏"后移除。
     */
    @Volatile
    private var immediateHideMenuSet: Set<String> = emptySet()

    // ========================================================================
    // 公共 API
    // ========================================================================

    /**
     * 标记好友为临时取消隐藏状态。
     *
     * 将好友添加到临时取消隐藏集合，并启动2分钟自动恢复定时器。
     * 如果好友已经在临时取消隐藏列表中，则重新启动定时器（延长2分钟）。
     *
     * @param wxId 好友的微信 ID
     */
    @Synchronized
    fun tempUnhide(wxId: String) {
        val current = tempUnhiddenSet.toMutableSet()
        if (wxId in current) {
            LogUtil.d(TAG, "tempUnhide: $wxId already temp-unhidden, resetting timer")
            // 已存在，重置定时器
            cancelAutoRestoreTimer()
            scheduleAutoRestore()
            return
        }

        current.add(wxId)
        tempUnhiddenSet = current

        // 添加到立即隐藏菜单集合
        val menuCurrent = immediateHideMenuSet.toMutableSet()
        menuCurrent.add(wxId)
        immediateHideMenuSet = menuCurrent

        LogUtil.i(TAG, "tempUnhide: $wxId marked as temp-unhidden (${current.size} total)")

        // 启动自动恢复定时器
        scheduleAutoRestore()
    }

    /**
     * 检查好友是否当前处于临时取消隐藏状态。
     *
     * @param wxId 好友的微信 ID
     * @return true 如果好友当前为临时取消隐藏
     */
    fun isTempUnhidden(wxId: String): Boolean {
        return wxId in tempUnhiddenSet
    }

    /**
     * 获取所有当前临时取消隐藏的好友 wxId 集合。
     *
     * @return 临时取消隐藏的好友 wxId 集合
     */
    fun getTempUnhiddenIds(): Set<String> {
        return tempUnhiddenSet
    }

    /**
     * 添加好友到"立即隐藏"菜单项集合。
     * 当用户使用 #keep 命令后，该好友的对话长按菜单中将出现"立即隐藏"选项。
     *
     * @param wxId 好友的微信 ID
     */
    @Synchronized
    fun addImmediateHideMenu(wxId: String) {
        val current = immediateHideMenuSet.toMutableSet()
        if (current.add(wxId)) {
            immediateHideMenuSet = current
            LogUtil.d(TAG, "addImmediateHideMenu: $wxId")
        }
    }

    /**
     * 检查好友是否在"立即隐藏"菜单项集合中。
     *
     * @param wxId 好友的微信 ID
     * @return true 如果好友需要显示"立即隐藏"菜单项
     */
    fun hasImmediateHideMenu(wxId: String): Boolean {
        return wxId in immediateHideMenuSet
    }

    /**
     * 立即恢复指定好友的隐藏状态。
     * 从临时取消隐藏集合和"立即隐藏"菜单集合中移除该好友。
     *
     * @param wxId 好友的微信 ID
     */
    @Synchronized
    fun restoreForFriend(wxId: String) {
        val current = tempUnhiddenSet.toMutableSet()
        if (current.remove(wxId)) {
            tempUnhiddenSet = current
            LogUtil.i(TAG, "restoreForFriend: $wxId restored to hidden state")
        }

        val menuCurrent = immediateHideMenuSet.toMutableSet()
        if (menuCurrent.remove(wxId)) {
            immediateHideMenuSet = menuCurrent
            LogUtil.d(TAG, "restoreForFriend: removed $wxId from immediate hide menu")
        }

        // 如果集合为空，取消定时器
        if (tempUnhiddenSet.isEmpty()) {
            cancelAutoRestoreTimer()
        }
    }

    /**
     * 当微信应用进入后台时调用。
     * 启动一个2分钟的后台定时器，到时自动恢复所有隐藏。
     */
    @Synchronized
    fun restoreOnAppBackground() {
        if (tempUnhiddenSet.isEmpty()) {
            LogUtil.d(TAG, "restoreOnAppBackground: no temp-unhidden friends, skipping")
            return
        }

        LogUtil.i(TAG, "restoreOnAppBackground: scheduling background restore timer (${tempUnhiddenSet.size} friends)")

        // 取消之前的后台定时器
        cancelBackgroundRestoreTimer()

        backgroundRestoreRunnable = Runnable {
            LogUtil.i(TAG, "Background restore timer fired, restoring all temp-unhidden friends")
            restoreAll()
        }

        handler.postDelayed(backgroundRestoreRunnable!!, BACKGROUND_RESTORE_DELAY_MS)
    }

    /**
     * 立即恢复所有临时取消隐藏的好友。
     * 清空所有临时状态和定时器。
     */
    @Synchronized
    fun restoreAll() {
        val count = tempUnhiddenSet.size
        if (count == 0) {
            LogUtil.d(TAG, "restoreAll: nothing to restore")
            return
        }

        LogUtil.i(TAG, "restoreAll: restoring $count temp-unhidden friends")

        tempUnhiddenSet = emptySet()
        immediateHideMenuSet = emptySet()

        cancelAutoRestoreTimer()
        cancelBackgroundRestoreTimer()
    }

    /**
     * 取消所有待处理的定时器。
     * 在模块关闭或不再需要定时器时调用。
     */
    @Synchronized
    fun cancelTimers() {
        LogUtil.d(TAG, "cancelTimers: cancelling all pending timers")
        cancelAutoRestoreTimer()
        cancelBackgroundRestoreTimer()
    }

    // ========================================================================
    // 内部方法
    // ========================================================================

    /**
     * 启动自动恢复定时器。
     * 在 [DEFAULT_TEMP_DURATION_MS] 毫秒后自动调用 [restoreAll]。
     */
    private fun scheduleAutoRestore() {
        cancelAutoRestoreTimer()

        autoRestoreRunnable = Runnable {
            LogUtil.i(TAG, "Auto-restore timer fired, restoring all temp-unhidden friends")
            restoreAll()
        }

        handler.postDelayed(autoRestoreRunnable!!, DEFAULT_TEMP_DURATION_MS)
        LogUtil.d(TAG, "Auto-restore scheduled in ${DEFAULT_TEMP_DURATION_MS / 1000}s")
    }

    /**
     * 取消自动恢复定时器。
     */
    private fun cancelAutoRestoreTimer() {
        autoRestoreRunnable?.let {
            handler.removeCallbacks(it)
            autoRestoreRunnable = null
            LogUtil.d(TAG, "Auto-restore timer cancelled")
        }
    }

    /**
     * 取消后台恢复定时器。
     */
    private fun cancelBackgroundRestoreTimer() {
        backgroundRestoreRunnable?.let {
            handler.removeCallbacks(it)
            backgroundRestoreRunnable = null
            LogUtil.d(TAG, "Background restore timer cancelled")
        }
    }
}