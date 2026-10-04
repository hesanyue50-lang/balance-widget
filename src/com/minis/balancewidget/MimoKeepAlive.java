package com.minis.balancewidget;

import android.app.Activity;
import android.content.Context;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * 小米账号会话保活（静默续期）。
 *
 * <h3>为什么要这个</h3>
 * MiMo 的余额接口认的是**小米账号会话音**，而这个会话很短 ——
 * 实测 13:37 还能查、13:53 就 401 了（十几分钟）。
 * 会话一过期，余额就查不到，用户只看到"登录过期"，得手动再登一次。
 *
 * <h3>为什么不能靠"自动登录脚本"</h3>
 * 想脚本化登录就得存账号密码，而小米登录全程有风控：
 * 设备指纹、短信验证码、滑块 —— 一次都绕不过去。
 * 存明文密码 + 撞风控，代价远大于收益。
 *
 * <h3>真正的原因与解法</h3>
 * 小米控制台**前端自己就有续期逻辑**：页面加载时会带着长期凭证
 * （passToken 那一类）去换新的会话 cookie。
 * 问题出在我们的实现：登录时把 cookie 拷进了 App 存储，之后只读那一份，
 * WebView 再也不访问 —— 前端的续期代码根本没机会跑，会话自然就到期了。
 *
 * 所以解法是：**定期把控制台页面在后台静默加载一次**，
 * 让官方前端逻辑自己去续期，然后把续期后的 cookie 同步回 App 存储。
 * 不用存密码、不用过风控，用的就是它自己的机制。
 */
public final class MimoKeepAlive {

    /** 续期最短间隔。会话实测只有十几分钟，8 分钟一续足够稳，也不至于太费电 */
    private static final long MIN_GAP_MS = 8 * 60 * 1000L;
    /** 单次保活的硬上限，到了就收尾，绝不留 WebView 挂着 */
    private static final long DEADLINE_MS = 30 * 1000L;

    private static WebView web;
    private static boolean busy;
    private static long lastAt;
    /** 收尾时还要写日志/落盘，Activity 可能已经不可用 → 存 application context */
    private static Context appCtx;

    private MimoKeepAlive() { }

    /**
     * 由前台定时任务调用。
     * 不在前台（小组件后台刷新）时不会触发 —— WebView 只能在有 Activity 时用，
     * 那种场景下会话可能仍会过期，但用户下次打开 App 就会自动续上。
     */
    public static void tick(final Activity act) {
        if (act == null || act.isFinishing()) return;
        if (busy) return;
        if (BalanceFetcher.mimoSession(act).length() == 0) return;   // 从没登录过，没什么可续
        long now = System.currentTimeMillis();
        if (now - lastAt < MIN_GAP_MS) return;
        lastAt = now;
        act.runOnUiThread(new Runnable() {
            public void run() { start(act); }
        });
    }

    /** 用户手动点了"重新登录并保活"之类时，强制立刻来一次 */
    public static void forceNow(final Activity act) {
        lastAt = 0;
        tick(act);
    }

    private static void start(final Activity act) {
        final WebView w;
        try {
            busy = true;
            appCtx = act.getApplicationContext();
            w = new WebView(act);
            web = w;
        } catch (Throwable t) {
            busy = false;
            if (appCtx != null) BalanceFetcher.diag(appCtx, "MiMo 保活：WebView 创建失败 " + t);
            appCtx = null;
            return;
        }
        try {
            WebSettings s = w.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            try { cm.setAcceptThirdPartyCookies(w, true); } catch (Throwable ig) { }

            /* 1×1 且全透明。**不能给 0 尺寸** —— 那样它不参与布局，
               页面会被判定为不可见而跳过加载，JS 也就不会执行。 */
            w.setAlpha(0f);
            ((ViewGroup) act.getWindow().getDecorView())
                    .addView(w, new FrameLayout.LayoutParams(1, 1));

            final long t0 = System.currentTimeMillis();
            /* onPageFinished 收尾；loaded 标志保证只收尾一次 */
            w.setWebViewClient(new WebViewClient() {
                public void onPageFinished(WebView v, String url) {
                    boolean toLogin = url != null && url.indexOf("/login") >= 0;
                    BalanceFetcher.diag(appCtx, "MiMo 保活：页面加载完成"
                            + (toLogin ? "（被弹回登录页 → 会话已失效）" : "（仍在控制台）")
                            + " " + (System.currentTimeMillis() - t0) + "ms");
                    finish(toLogin);
                }
            });
            w.loadUrl(BalanceFetcher.MIMO_HOST + "/console/balance");

            /* 兜底收尾：网络卡住时不能无限等 */
            w.postDelayed(new Runnable() {
                public void run() {
                    BalanceFetcher.diag(appCtx, "MiMo 保活：超时兜底收尾");
                    finish(false);
                }
            }, DEADLINE_MS);
        } catch (Throwable t) {
            BalanceFetcher.diag(appCtx, "MiMo 保活失败：" + t);
            finish(false);
        }
    }

    /**
     * 收尾：把 WebView 里**续期后的** cookie 同步回 App 存储，然后销毁。
     *
     * 这一步是关键 —— CookieManager 和 App 存的那份是两份独立数据，
     * 不同步的话保活白做（页面是刷新了，但真正拿去查余额的还是老 cookie）。
     */
    private static void finish(boolean toLogin) {
        try {
            if (web != null) {
                String fresh = null;
                try {
                    fresh = CookieManager.getInstance().getCookie(BalanceFetcher.MIMO_HOST);
                } catch (Throwable ig) { }

                Context ctx = appCtx;
                if (ctx != null && toLogin) {
                    /* 页面被弹回登录页 = 会话彻底失效，长期凭证也没救回来。
                       明确记下来，界面才好提示"重新登录"。 */
                    BalanceFetcher.markMimoExpired(ctx);
                    BalanceFetcher.diag(ctx, "MiMo 保活：会话已失效（被弹回登录页），"
                            + "已标记为需重新登录");
                } else if (ctx != null && fresh != null && fresh.length() > 0) {
                    String old = BalanceFetcher.mimoSession(ctx);
                    /* 只在真的变了才写盘（避免每次都触发加密+落盘） */
                    if (!fresh.equals(old)) {
                        BalanceFetcher.setMimoSession(ctx, fresh);
                        BalanceFetcher.diag(ctx, "MiMo 保活：已同步续期后的会话（长度 "
                                + old.length() + " → " + fresh.length() + "）");
                    } else {
                        BalanceFetcher.diag(ctx, "MiMo 保活：会话未变化（长度 "
                                + fresh.length() + "）");
                    }
                }

                ViewGroup p = (ViewGroup) web.getParent();
                if (p != null) p.removeView(web);
                web.stopLoading();
                web.destroy();
                web = null;
            }
        } catch (Throwable ig) { }
        busy = false;
        appCtx = null;
    }
}
