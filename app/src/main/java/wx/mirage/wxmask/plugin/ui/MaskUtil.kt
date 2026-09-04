package wx.mirage.wxmask.plugin.ui

import wx.mirage.wxmask.bean.MaskItemBean


class MaskUtil {

    companion object {
        @JvmStatic
        fun checkExitMaskId(lst: List<MaskItemBean>, maskId: String): Boolean {
            return lst.indexOfFirst { it.maskId == maskId } > -1
        }

        @JvmStatic
        fun findIndex(lst: List<MaskItemBean>, maskId: String): Int {
            return lst.indexOfFirst { maskId == it.maskId }
        }
    }


}