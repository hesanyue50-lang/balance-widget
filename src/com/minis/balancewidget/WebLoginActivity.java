package com.minis.balancewidget;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 通用网页登录（内置 WebView）。
 *
 * 为什么需要它：
 *   高级自定义平台要抓的余额页面，很多**必须登录才显示** ——
 *   直接 GET 拿到的是登录页的 HTML（"请先登录"），什么都抓不到。
 *
 * 做法是让用户在 App 内置浏览器里正常登录一次：
 *   1. 打开目标网址，用户在里面走完登录流程（账号密码 / 短信验证码都行）
 *   2. 登录完成后，App 把该域名下的 Cookie 抄下来
 *   3. 存进该平台的「附加请求头」—— 之后的抓取就会带上登录态
 *
 * 关键点：
 * - 用 `CookieManager` 抓 cookie，不猜 cookie 名字（各家域名/字段名都不一样）
 * - 登录完成时**真的调一次**目标接口验证，而不是看 URL 变化 ——
 *   不同站点登录后跳转行为千奇百怪，只看 URL 必然漏判
 * - Cookie 只存本机（和 API Key 一样走 KeyVault 加密）
 * - 用户可以随时查看或清除已记录的登录态
 */
public class WebLoginActivity extends Activity {

    private static final long POLL_MS = 2000L;
    /** 登录可能要走短信验证码，给足 8 分钟 */
    private static final long DEADLINE_MS = 8 * 60 * 1000L;

    private static final String EXTRA_URL = "url";

    /** 登录结果回调。静态持有，调用方在 onResume 里取走并置空，避免泄漏 Activity */
    private static OnCookie pending;

    public interface OnCookie {
        /**
         * @param host   抓到的域名（如 https://example.com）
         * @param cookie 登录态 cookie 串；为 null/空表示没有拿到
         */
        void onCookie(String host, String cookie);
    }

    private WebView web;
    private TextView status;
    private final Handler h = new Handler();
    private volatile boolean done = false;
    private long t0;
    private Thread worker;
    private String targetUrl = "";

    /**
     * 打开网页登录页。
     * 结果通过回调返回；调用方 Activity 应在 {@code onResume} 里调
     * {@link #takePending()} 取走结果（因为本 Activity 结束后会回调已重建的宿主）。
     */
    public static void open(Activity from, String url, OnCookie cb) {
        pending = cb;
        Intent it = new Intent(from, WebLoginActivity.class);
        it.putExtra(EXTRA_URL, url);
        from.startActivity(it);
    }

    /** 取走并清空待处理回调；本 Activity 的 onDestroy 也会清理 */
    public static OnCookie takePending() {
        OnCookie c = pending;
        pending = null;
        return c;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        t0 = System.currentTimeMillis();
        targetUrl = getIntent().getStringExtra(EXTRA_URL);
        if (targetUrl == null) targetUrl = "";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // ---- 顶部标题条 ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFFF6F7F9);
        bar.setPadding(dp(16), dp(14), dp(12), dp(14));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText("网页登录");
        t1.setTextSize(16f);
        t1.setTextColor(0xFF1F2329);
        t1.setTypeface(null, android.graphics.Typeface.BOLD);
        titles.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText("登录态只存在本机，用于抓取余额数据");
        t2.setTextSize(11.5f);
        t2.setTextColor(0xFF8A8F99);
        titles.addView(t2);
        titles.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(titles);

        TextView btnDone = new TextView(this);
        btnDone.setText("完成");
        btnDone.setTextSize(13f);
        btnDone.setTextColor(0xFF4D6BFE);
        btnDone.setPadding(dp(12), dp(6), dp(12), dp(6));
        btnDone.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finishWithCookie(); }
        });
        bar.addView(btnDone);

        TextView btnClear = new TextView(this);
        btnClear.setText("清除");
        btnClear.setTextSize(13f);
        btnClear.setTextColor(0xFFE5484D);
        btnClear.setPadding(dp(12), dp(6), dp(12), dp(6));
        btnClear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { clearCookie(); }
        });
        bar.addView(btnClear);
        root.addView(bar);

        // ---- WebView ----
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        try {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        } catch (Throwable ig) { }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        try { cm.setAcceptThirdPartyCookies(web, true); } catch (Throwable ig) { }
        web.setWebViewClient(new WebViewClient());
        web.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(web);

        // ---- 底部状态 ----
        status = new TextView(this);
        status.setText("在下面完成登录后，点右上角「完成」");
        status.setTextSize(11.5f);
        status.setTextColor(0xFF8A8F99);
        status.setPadding(dp(16), dp(10), dp(16), dp(14));
        root.addView(status);

        setContentView(root);
        if (targetUrl.length() > 0) web.loadUrl(targetUrl);
        h.postDelayed(poll, POLL_MS);
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            if (done) return;
            try {
                String ck = CookieManager.getInstance().getCookie(targetUrl);
                if (ck != null && ck.length() > 0) verify(ck);
            } catch (Throwable ignored) { }
            if (!done) {
                if (System.currentTimeMillis() - t0 > DEADLINE_MS) {
                    setStatus("登录超时，可返回重试");
                    return;
                }
                h.postDelayed(this, POLL_MS);
            }
        }
    };

    /** 验证一次：把 Cookie 交给调用方的平台配置去抓一次，能出数就算登录成功 */
    private void verify(final String cookie) {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                String msg = "";
                try {
                    /* 用一份临时配置试抓：把 Cookie 放进 headers */
                    WebCustom probe = new WebCustom();
                    probe.name = "probe";
                    probe.url = targetUrl;
                    probe.mode = "auto";
                    probe.headers = WebCustom.mergeHeader("", "Cookie", cookie);
                    probe.foreign = false;
                    WebCustom.Result r = probe.fetch(false, 10000);
                    ok = r.numeric || (r.display != null && r.display.length() > 0);
                    msg = "已取到 " + r.display + "（" + r.how + "）";
                } catch (Throwable e) {
                    msg = "试抓失败：" + e.getMessage();
                }
                final boolean fok = ok;
                final String fmsg = msg;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (fok) {
                            setStatus("✅ " + fmsg + " —— 点右上角「完成」保存");
                        } else {
                            setStatus(fmsg);
                        }
                    }
                });
            }
        });
        worker.start();
    }

    private void finishWithCookie() {
        String host = targetUrl;
        try {
            /* 只取协议+域名，别把路径带进去 */
            java.net.URL u = new java.net.URL(targetUrl);
            host = u.getProtocol() + "://" + u.getHost()
                    + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Throwable ignored) { }
        String ck = "";
        try {
            ck = CookieManager.getInstance().getCookie(host);
        } catch (Throwable ignored) { }
        if (ck != null && ck.length() > 0) {
            deliver(host, ck);
        }
        finish();
    }

    private void clearCookie() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
        } catch (Throwable ignored) { }
        setStatus("已清除本机的登录态");
    }

    private static void deliver(String host, String cookie) {
        OnCookie cb = pending;
        pending = null;
        if (cb != null) {
            try {
                cb.onCookie(host, cookie);
            } catch (Throwable ignored) { }
        }
    }

    private void setStatus(final String s) {
        runOnUiThread(new Runnable() {
            public void run() { if (status != null) status.setText(s); }
        });
    }

    @Override
    protected void onDestroy() {
        done = true;
        h.removeCallbacks(poll);
        try {
            if (web != null) {
                web.stopLoading();
                web.destroy();
                web = null;
            }
        } catch (Throwable ignored) { }
        super.onDestroy();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}