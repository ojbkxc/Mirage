package wx.mirage.wxmask;

import android.app.Application;

/**
 * 模块 Application。
 *
 * 原 wxmask 版本依赖 frame-ui 的 JsonMenuManager 远程菜单，
 * Mirage 并入后不再使用远程菜单，保留最小实现。
 */
public final class App extends Application {

    public static App instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }
}
