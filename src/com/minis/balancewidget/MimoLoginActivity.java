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
 * 小米 MiMo 登录页（内置 WebView）。
 *
 * 为什么必须这么干：
 *   小米 MiMo 开放平台**没有**用 API Key 就能查的余额接口 ——
 *   api.xiaomimimo.com 上除了 /v1/models、/v1/chat/completions 这类推理接口，
 *   所有 billing / user / balance 路径实测全是 404。余额只在控制台，
 *   而控制台的 /api/v1/balance 认的是**小米账号会话音 cookie**（account.xiaomi.com
 *   登录后 STS 换回来的），拿 sk-xxx 当 Bearer 一样是 401。
 *
 * 所以这里让用户在 App 内置 WebView 里登录一次，登录态 cookie 只存在本机
 * （KeyVault 加密，和 API Key 同一把设备密钥），之后后台刷新直接带 cookie 查余额。
 *
 * 判定「登录成功」的方式不是猜 cookie 名字，而是**拿 cookie 真的调一次余额接口**：
 * 小米会往 platform 域塞好几个 cookie（甚至有登录前就存在的），
 * 只有 /api/v1/balance 返回 200 才算数 —— 稳。
 */
public class MimoLoginActivity extends Activity {

    private static final String TAG = "MimoLogin";
    /** 登录后要回到的页面（控制台余额页会自己跳登录页，登录完自动回来） */
    private static final String START_URL = BalanceFetcher.MIMO_HOST + "/console/balance";
    /** 轮询间隔与总时限：登录可能要走短信验证码，给足 8 分钟 */
    private static final long POLL_MS = 2000L;
    private static final long DEADLINE_MS = 8 * 60 * 1000L;

    private WebView web;
    private TextView status;
    private final Handler h = new Handler();
    private volatile boolean done = false;
    private long t0;
    private Thread worker;

    private final Runnable poll = new Runnable() {
        public void run() {
            if (done) return;
            try {
                String ck = CookieManager.getInstance().getCookie(BalanceFetcher.MIMO_HOST);
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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        t0 = System.currentTimeMillis();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // ---- 顶部标题条 ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFFF6F7F9);
        bar.setPadding(dp(16), dp(14), dp(12), dp(14));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bar.setLayoutParams(blp);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText("登录小米账号");
        t1.setTextSize(16f);
        t1.setTextColor(0xFF1F2329);
        t1.setTypeface(null, android.graphics.Typeface.BOLD);
        titles.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText("仅用于查 MiMo 余额，登录态只存本机");
        t2.setTextSize(11.5f);
        t2.setTextColor(0xFF8A8F99);
        titles.addView(t2);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titles.setLayoutParams(tlp);
        bar.addView(titles);

        TextView close = new TextView(this);
        close.setText("关闭");
        close.setTextSize(13f);
        close.setTextColor(0xFF4D6BFE);
        close.setPadding(dp(12), dp(6), dp(12), dp(6));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        bar.addView(close);
        root.addView(bar);

        // ---- WebView ----
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        try { s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE); } catch (Throwable ig) { }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        try { cm.setAcceptThirdPartyCookies(web, true); } catch (Throwable ig) { }
        web.setWebViewClient(new WebViewClient());
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        web.setLayoutParams(wlp);
        root.addView(web);

        // ---- 底部状态 ----
        status = new TextView(this);
        status.setText("在下面登录小米账号；登录成功后会自动返回");
        status.setTextSize(11.5f);
        status.setTextColor(0xFF8A8F99);
        status.setPadding(dp(16), dp(10), dp(16), dp(14));
        root.addView(status);

        setContentView(root);
        web.loadUrl(START_URL);
        h.postDelayed(poll, POLL_MS);
    }

    private void setStatus(final String s) {
        runOnUiThread(new Runnable() {
            public void run() { if (status != null) status.setText(s); }
        });
    }

    /** 拿 cookie 试调一次余额接口；成功就落盘并收工 */
    private void verify(final String cookie) {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                String raw = "";
                try {
                    raw = BalanceFetcher.mimoBalanceRaw(cookie, 10000);
                    org.json.JSONObject o = new org.json.JSONObject(raw);
                    org.json.JSONObject d = o.optJSONObject("data");
                    if (d == null) d = o;
                    ok = d.has("balance") || d.has("cashBalance");
                } catch (Throwable e) {
                    raw = "" + e;
                }
                final boolean fok = ok;
                final String fraw = raw;
                if (fok) {
                    BalanceFetcher.setMimoSession(MimoLoginActivity.this, cookie);
                    BalanceFetcher.diag(MimoLoginActivity.this,
                            "MiMo 登录成功，余额应答 " + fraw);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            done = true;
                            h.removeCallbacks(poll);
                            Toast.makeText(MimoLoginActivity.this,
                                    "登录成功，MiMo 余额已可查询", Toast.LENGTH_SHORT).show();
                            kickWidget();
                            /* 立刻补一次账本快照：不然要等下一个 6 小时采样点，
                               统计页会一直空着，看着像没生效 */
                            BalanceFetcher.sampleNow(MimoLoginActivity.this);
                            finish();
                        }
                    });
                } else {
                    setStatus("还没登录成功，继续在页面里完成登录…");
                }
            }
        });
        worker.start();
    }

    /** 让桌面小组件立刻重刷一次，不用等下一个周期 */
    private void kickWidget() {
        try {
            Intent it = new Intent(this, BalanceWidgetProvider.class);
            it.setAction(BalanceWidgetProvider.ACTION_REFRESH);
            sendBroadcast(it);
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onDestroy() {
        done = true;
        h.removeCallbacks(poll);
        try { if (web != null) { web.stopLoading(); web.destroy(); web = null; } } catch (Throwable ignored) { }
        super.onDestroy();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
