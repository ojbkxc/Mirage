package wx.mirage.wxmask.route

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lu.magic.util.AppUtil
import com.lu.magic.util.ToastUtil
import com.lu.magic.util.log.LogUtil

/**
 * app内部跳转协议实现，如：
 * mirage://wx.mirage/feat/checkAppUpdate
 * mirage://wx.mirage/page/main
 *
 * 原版依赖 frame-ui 的 WebViewActivity/WebViewDialog/ViewModel，
 * Mirage 并入后精简：路由保留协议解析，跳转目标改为模块自身的旧管理界面。
 */
class MaskAppRouter {
    companion object {
        val vailScheme = "mirage"
        val vailHost = "wx.mirage"

        fun routeCheckAppUpdateFeat(activity: Activity) {
            // 更新检查精简：不再访问原仓库 Release
            ToastUtil.show("Mirage: 请通过 GitHub Releases 检查更新")
        }

        fun routeDonateFeat(activity: Activity) {
            ToastUtil.show("Good good study, day day up.")
        }

        fun routeWebViewPage(
            activity: Context,
            webUrl: String,
            title: String,
            isDialogUI: Boolean = false,
            forceHtml: Boolean = false
        ) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
            } catch (e: Throwable) {
                LogUtil.w(e)
            }
        }

        fun isMaskAppLink(uri: Uri?): Boolean {
            if (uri == null) {
                return false
            }
            return vailScheme == uri.scheme && vailHost == uri.host
        }

        @JvmOverloads
        fun route(context: Context = AppUtil.getContext(), url: String?, onFail: ((e: Throwable) -> Unit)? = null) {
            try {
                LogUtil.i("App Route", url)
                val uri = Uri.parse(url)
                if (isMaskAppLink(uri)) {
                    val pathSegments = uri.pathSegments
                    if (pathSegments.size == 2) {
                        val group = pathSegments[0] ?: ""
                        val name = pathSegments[1] ?: ""
                        routeMaskAppLink(context, uri, group, name)
                    } else {
                        LogUtil.w("is Mask App link, but pathSegments size mismatch")
                    }
                } else {
                    val intent = Intent.parseUri(url, Intent.URI_ALLOW_UNSAFE)
                    context.startActivity(intent)
                }
            } catch (e: Throwable) {
                LogUtil.w(e)
                onFail?.invoke(e)
            }
        }

        private fun routeMaskAppLink(context: Context, uri: Uri, group: String, name: String) {
            when (group) {
                "feat" -> routeFeatGroup(context, uri, name)
                "page" -> routePageGroup(context, uri, name)
                else -> {
                    LogUtil.w(group, "for mask link 's group not impl")
                }
            }
        }

        private fun routeFeatGroup(context: Context, uri: Uri, name: String) {
            when (name) {
                "checkAppUpdate" -> ToastUtil.show("Mirage: 请通过 GitHub Releases 检查更新")
                "donate" -> ToastUtil.show("Good good study, day day up.")
                else -> LogUtil.w(name, "for mask link featGroup not impl")
            }
        }

        private fun routePageGroup(context: Context, uri: Uri, name: String) {
            when (name) {
                "main" -> jumpMainPage(context, uri)
                else -> LogUtil.w(name, "for mask link pageGroup not impl")
            }
        }

        fun jumpMainPage(context: Context, uri: Uri) {
            try {
                val intent = Intent()
                intent.setClassName(context, "wx.mirage.ui.MainActivity")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Throwable) {
                LogUtil.w(e)
            }
        }
    }
}
