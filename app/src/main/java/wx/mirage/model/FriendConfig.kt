package wx.mirage.model

/**
 * 单个好友的隐藏配置
 *
 * 每个被隐藏的好友拥有独立的开关控制，支持以下功能：
 * - [masterSwitch]: 总开关，关闭后该好友所有隐藏功能均不生效
 * - [blockVoiceCall]: 拦截语音/视频通话
 * - [blockNotification]: 拦截消息通知
 * - [disguiseMainPage]: 主页好友列表伪装
 * - [hideChatHistory]: 聊天记录隐藏
 * - [hideContact]: 联系人/转发列表隐藏
 * - [hideMoments]: 朋友圈隐藏
 * - [hideOtherMisc]: 其他杂项隐藏（好友状态、聊天大小排序列表等）
 *
 * @param wxId 微信好友 ID
 * @param label 用户自定义标签
 * @param masterSwitch 总开关，默认开启
 * @param blockVoiceCall 拦截语音视频通话，默认开启
 * @param blockNotification 拦截消息通知，默认开启
 * @param disguiseMainPage 主页好友列表伪装，默认开启
 * @param hideChatHistory 聊天记录隐藏，默认开启
 * @param hideContact 联系人隐藏，默认开启
 * @param hideMoments 朋友圈隐藏，默认开启
 * @param hideOtherMisc 其他杂项隐藏，默认开启
 * @param disguiseTargetId 伪装目标好友的 wxId（用于主页伪装）
 */
data class FriendConfig(
    val wxId: String,
    val label: String = "",
    val masterSwitch: Boolean = true,
    val blockVoiceCall: Boolean = true,
    val blockNotification: Boolean = true,
    val disguiseMainPage: Boolean = true,
    val hideChatHistory: Boolean = true,
    val hideContact: Boolean = true,
    val hideMoments: Boolean = true,
    val hideOtherMisc: Boolean = true,
    val disguiseTargetId: String = ""
) {
    companion object {
        /**
         * 创建默认的全开配置。
         * 所有子开关默认开启，总开关默认开启。
         */
        fun createDefault(wxId: String, label: String = ""): FriendConfig {
            return FriendConfig(wxId = wxId, label = label)
        }

        /**
         * 创建仅总开关关闭的配置。
         * 用于"仅添加但暂不生效"的场景。
         */
        fun createDisabled(wxId: String, label: String = ""): FriendConfig {
            return FriendConfig(wxId = wxId, label = label, masterSwitch = false)
        }
    }

    /**
     * 判断指定功能是否生效。
     * 需要同时满足：总开关开启 + 该功能开关开启。
     */
    fun isFeatureEnabled(feature: (FriendConfig) -> Boolean): Boolean {
        return masterSwitch && feature(this)
    }

    // ===== 便捷的功能判断方法 =====

    /** 是否拦截语音视频通话 */
    val isVoiceCallBlocked: Boolean get() = isFeatureEnabled { it.blockVoiceCall }

    /** 是否拦截消息通知 */
    val isNotificationBlocked: Boolean get() = isFeatureEnabled { it.blockNotification }

    /** 是否主页伪装 */
    val isMainPageDisguised: Boolean get() = isFeatureEnabled { it.disguiseMainPage }

    /** 是否隐藏聊天记录 */
    val isChatHistoryHidden: Boolean get() = isFeatureEnabled { it.hideChatHistory }

    /** 是否隐藏联系人 */
    val isContactHidden: Boolean get() = isFeatureEnabled { it.hideContact }

    /** 是否隐藏朋友圈 */
    val isMomentsHidden: Boolean get() = isFeatureEnabled { it.hideMoments }

    /** 是否隐藏其他杂项 */
    val isOtherMiscHidden: Boolean get() = isFeatureEnabled { it.hideOtherMisc }

    /** 是否有任何功能生效（总开关开启且至少一个子开关开启） */
    val hasAnyEffect: Boolean
        get() = masterSwitch && (
            blockVoiceCall || blockNotification || disguiseMainPage ||
            hideChatHistory || hideContact || hideMoments || hideOtherMisc
        )
}