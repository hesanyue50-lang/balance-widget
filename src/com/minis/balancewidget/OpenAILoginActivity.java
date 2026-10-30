package com.minis.balancewidget;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
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
 * OpenAI 登录页（内置 WebView），做法和小米 MiMo 一致。
 *
 * 为什么必须这么干：
 *   官方 2024 起关闭了普通 API Key 对 dashboard/billing 的访问 ——
 *   拿 sk-xxx 当 Bearer 请求 credit_grants 只会 401/403。
 *   唯一能读到"剩余额度"的是**浏览器会话**（platform.openai.com 的
 *   __Secure-next-auth.session-token）。
 *
 * 判定"登录成功"不靠猜 cookie 名字，而是**拿 cookie 真调一次余额接口**：
 * 只有 credit_grants 返回带 total_available 的 JSON 才算数。
 * 这样即使 OpenAI 改了 cookie 名、或页面上有别的干扰 cookie，也不会误判。
 *
 * 登录态只存本机（KeyStore 加密），存成该平台下一条 label 为「网页登录」的密钥。
 */
public class OpenAILoginActivity extends Activity {

    /** 登录后会自己跳登录页，登录完自动回到用量页 */
    private static final String START_URL = "https://platform.openai.com/usage";
    private static final String HOST = "https://platform.openai.com";
    private static final long POLL_MS = 2500L;
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
                String ck = CookieManager.getInstance().getCookie(HOST);
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
        /* 主题必须在 setContentView 之前应用：attr 是在 View 创建时解析的 */
        Theme.apply(this);
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
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText("登录 OpenAI 账号");
        t1.setTextSize(16);
        t1.setTextColor(0xFF1F2329);
        t1.setTypeface(null, android.graphics.Typeface.BOLD);
        titles.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText("仅用于查余额，登录态只存本机");
        t2.setTextSize(11.5f);
        t2.setTextColor(0xFF8A8F99);
        titles.addView(t2);
        titles.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(titles);

        TextView close = new TextView(this);
        close.setText("关闭");
        close.setTextSize(13);
        close.setTextColor(Theme.c(this, R.attr.c_accent));
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
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        try { s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE); } catch (Throwable ig) { }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        try { cm.setAcceptThirdPartyCookies(web, true); } catch (Throwable ig) { }
        web.setWebViewClient(new WebViewClient());
        web.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(web);

        // ---- 底部状态 ----
        status = new TextView(this);
        status.setText("在下面登录 OpenAI 账号；登录成功后会自动返回。\n"
                + "（需要能正常访问 platform.openai.com）");
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
                    raw = BalanceFetcher.openaiBalanceRaw(cookie, 12000);
                    org.json.JSONObject o = new org.json.JSONObject(raw);
                    ok = o.has("total_available") || o.has("total_granted")
                            || o.has("total_used");
                } catch (Throwable e) {
                    raw = "" + e;
                }
                final boolean fok = ok;
                final String fraw = raw;
                if (fok) {
                    saveCookie(cookie);
                    BalanceFetcher.diag(OpenAILoginActivity.this,
                            "OpenAI 登录成功，余额应答 " + fraw);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            done = true;
                            h.removeCallbacks(poll);
                            Toast.makeText(OpenAILoginActivity.this,
                                    "登录成功，OpenAI 余额已可查询", Toast.LENGTH_SHORT).show();
                            kickWidget();
                            BalanceFetcher.sampleNow(OpenAILoginActivity.this);
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

    /**
     * 把登录态存成该平台下的一条密钥。
     *
     * 复用 KeyStore 而不是新开一个字段：这样用户在设置页能看到、能删，
     * 刷新逻辑也不用为它写第二套读取路径 —— fill() 拿到的 key 就是这段 cookie。
     * 先删掉旧的「网页登录」条目，避免反复登录堆一堆。
     */
    private void saveCookie(String cookie) {
        try {
            java.util.List<KeyStore.ApiKey> list = KeyStore.get(this, "openai");
            for (int i = list.size() - 1; i >= 0; i--) {
                if ("网页登录".equals(list.get(i).label)) {
                    KeyStore.remove(this, list.get(i).id);
                }
            }
            KeyStore.ApiKey k = new KeyStore.ApiKey();
            k.platform = "openai";
            k.label = "网页登录";
            k.key = cookie;
            KeyStore.add(this, "openai", k);
        } catch (Throwable e) {
            BalanceFetcher.diag(this, "OpenAI 登录态保存失败 " + e);
        }
    }

    private void kickWidget() {
        try {
            android.content.Intent it = new android.content.Intent(this, BalanceWidgetProvider.class);
            it.setAction(BalanceWidgetProvider.ACTION_REFRESH);
            sendBroadcast(it);
        } catch (Throwable ignored) { }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        done = true;
        h.removeCallbacks(poll);
        try { if (web != null) web.destroy(); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
