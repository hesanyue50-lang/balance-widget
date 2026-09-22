package com.minis.balancewidget;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private LinearLayout cards;
    private TextView tTotal;
    private TextView tSub;
    private TextView btnRefresh;
    private boolean loading = false;

    private final Handler autoHandler = new Handler();
    private final Runnable autoTask = new Runnable() {
        public void run() {
            refresh(true);          // 静默刷新，不闪"正在查询"
            autoHandler.postDelayed(this, RefreshScheduler.fgMillis(MainActivity.this));
        }
    };

    private final View.OnClickListener refreshClick = new View.OnClickListener() {
        public void onClick(View v) { refresh(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        /* 打开应用顺手把小组件催一遍（带节流，重复调用不会造成风暴）。
           刚更新完 App 时小组件常显示不出东西，这样至少开一次应用就能恢复。 */
        try {
            Intent kick = new Intent(this, BalanceWidgetProvider.class);
            kick.setAction(BalanceWidgetProvider.ACTION_REFRESH);
            sendBroadcast(kick);
        } catch (Throwable ignored) { }

        cards = (LinearLayout) findViewById(R.id.cards);
        tTotal = (TextView) findViewById(R.id.t_total);
        tSub = (TextView) findViewById(R.id.t_sub);
        btnRefresh = (TextView) findViewById(R.id.btn_refresh);

        applyWindowInsets();

        btnRefresh.setOnClickListener(refreshClick);
        findViewById(R.id.total_card).setOnClickListener(refreshClick);
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.tab_api).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTab(true); }
        });
        findViewById(R.id.tab_stats).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTab(false); buildStats(); }
        });
    }

    /** 切换 API / 统计 两个栏目 */
    private void showTab(boolean api) {
        findViewById(R.id.total_card).setVisibility(api ? View.VISIBLE : View.GONE);
        findViewById(R.id.cards).setVisibility(api ? View.VISIBLE : View.GONE);
        findViewById(R.id.stats_container).setVisibility(api ? View.GONE : View.VISIBLE);
        TextView ta = (TextView) findViewById(R.id.tab_api);
        TextView ts = (TextView) findViewById(R.id.tab_stats);
        ta.setTextColor(getColor(api ? R.color.tx : R.color.tx2));
        ts.setTextColor(getColor(api ? R.color.tx2 : R.color.tx));
        ta.setTypeface(null, api ? android.graphics.Typeface.BOLD : null);
        ts.setTypeface(null, api ? null : android.graphics.Typeface.BOLD);
    }

    /**
     * 构建用量统计页：近 30 天按天聚合消耗（柱）与总余额（线）。
     * 只统计 draw=true（未隐藏）的平台；每平台一行隐藏开关。
     * 消耗 = Ledger 快照差值，已扣除充值（record 内自动检测）。
     */
    private void buildStats() {
        final int DAYS = 30;
        long now = System.currentTimeMillis();
        long from = now - (long) DAYS * 86400000L;
        double rate = 7.1;
        try {
            rate = getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE)
                    .getFloat("last_rate", 7.1f);
        } catch (Throwable ig) { }

        double[] consDay = new double[DAYS];
        double[] balDay = new double[DAYS];
        int[] balCnt = new int[DAYS];
        String[] labels = new String[DAYS];
        java.util.Calendar cal = java.util.Calendar.getInstance();
        for (int i = 0; i < DAYS; i++) {
            cal.setTimeInMillis(now - (long) (DAYS - 1 - i) * 86400000L);
            labels[i] = (cal.get(java.util.Calendar.MONTH) + 1) + "/"
                    + cal.get(java.util.Calendar.DAY_OF_MONTH);
        }

        Ledger lg = Ledger.get(this);
        java.util.List<KeyStore.ApiKey> aks = KeyStore.all(this);
        StringBuilder sum = new StringBuilder();
        double totalCons = 0;
        for (int i = 0; i < aks.size(); i++) {
            final KeyStore.ApiKey ak = aks.get(i);
            if (!ak.isConfigured()) continue;
            BalanceFetcher.Preset p = BalanceFetcher.presetOf(ak.platform);
            String kind = p == null ? "balance" : p.kind;
            boolean usd = p != null && "USD".equals(p.unit);
            double mul = usd ? rate : 1.0;
            if (!ak.draw || !"balance".equals(kind)) continue;   // 隐藏 / 非余额类不进图表

            java.util.List<Ledger.Point> pts = lg.series(this, ak.id, from);
            double platCons = 0;
            for (int j = 0; j < pts.size(); j++) {
                Ledger.Point pt = pts.get(j);
                int idx = DAYS - 1 - (int) ((now - pt.ts) / 86400000L);
                if (idx < 0 || idx >= DAYS) continue;
                consDay[idx] += pt.consumed * mul;
                balDay[idx] += pt.balance * mul;
                balCnt[idx]++;
                platCons += pt.consumed * mul;
            }
            if (pts.size() >= 2) {
                totalCons += platCons;
                sum.append(ak.label.length() > 0 ? ak.label : (p == null ? ak.platform : p.name))
                   .append("  近30天消耗 ").append(String.format("%.2f", platCons)).append("\n");
            }
        }
        // 余额折线：当天无快照的平台用 0 占位会导致凹陷，这里按"有快照才平均"简化为总和
        for (int i = 0; i < DAYS; i++) if (balCnt[i] == 0) balDay[i] = (i > 0 ? balDay[i - 1] : 0);

        UsageChartView chart = (UsageChartView) findViewById(R.id.usage_chart);
        chart.setData(consDay, balDay, labels);

        TextView st = (TextView) findViewById(R.id.stats_summary);
        st.setText("近 30 天总消耗 " + String.format("%.2f", totalCons)
                + "（CNY，已扣除充值）\n" + sum.toString()
                + "\n采样精度 6 小时/点，按天聚合显示");

        buildPlatformToggles(aks);
    }

    /** 每个平台一行「显示/隐藏」开关，切换 KeyStore.draw 后重绘图表 */
    private void buildPlatformToggles(java.util.List<KeyStore.ApiKey> aks) {
        LinearLayout box = (LinearLayout) findViewById(R.id.stats_platforms);
        box.removeAllViews();
        for (int i = 0; i < aks.size(); i++) {
            final KeyStore.ApiKey ak = aks.get(i);
            if (!ak.isConfigured()) continue;
            BalanceFetcher.Preset p = BalanceFetcher.presetOf(ak.platform);
            String name = p == null ? ak.platform : p.name;
            TextView row = new TextView(this);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackgroundResource(R.drawable.btn_bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            row.setLayoutParams(lp);
            row.setTextSize(13);
            row.setText(name + "    " + (ak.draw ? "[显示中 · 点击隐藏]" : "[已隐藏 · 点击显示]"));
            row.setTextColor(getColor(ak.draw ? R.color.tx2 : R.color.tx3));
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    ak.draw = !ak.draw;
                    KeyStore.update(MainActivity.this, ak);
                    buildStats();
                }
            });
            box.addView(row);
        }
    }

    /** 刘海 / 状态栏 / 手势条适配（与设置界面共用同一套逻辑） */
    private void applyWindowInsets() {
        UiInsets.apply(this, R.id.app_header, R.id.main_scroll, 12, 20);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 放在 onResume：界面已可见，避免弹窗盖在未初始化的界面上，
        // 也避免用户从后台返回时错过提示。内部有"问过就不再弹"的闸门。
        NotifyPermission.ensure(this, null, false);
        refresh();
        // 前台：按设置的间隔自动刷新（页面可见时才跑，省电省流量）
        autoHandler.removeCallbacks(autoTask);
        autoHandler.postDelayed(autoTask, RefreshScheduler.fgMillis(this));
        // 回到前台后由 Handler 接管，取消后台闹钟
        RefreshScheduler.cancel(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        autoHandler.removeCallbacks(autoTask);
        // 切到后台：改由 AlarmManager 按后台间隔唤醒刷新（用于余额预警）
        RefreshScheduler.schedule(this);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        NotifyPermission.onRequestResult(this, code, perms, results);
    }

    private void refresh() { refresh(false); }

    /**
     * @param silent true = 自动刷新，不显示"正在查询"（避免每 5 分钟闪一下，也不打断阅读）
     */
    private void refresh(final boolean silent) {
        if (loading) return;
        loading = true;
        if (!silent) {
            if (btnRefresh != null) {
                btnRefresh.setText(R.string.refreshing);
                btnRefresh.setAlpha(0.5f);
            }
            tSub.setText(R.string.refreshing);
            tSub.setTextColor(getColor(R.color.tx3));
        }

        final Context ctx = this;
        new Thread(new Runnable() {
            public void run() {
                final BalanceFetcher.Result r = BalanceFetcher.fetch(ctx, 12000);
                // 判定阈值并发通知（内部有防重复，重复调用安全）
                Alert.check(ctx, r);
                runOnUiThread(new Runnable() {
                    public void run() {
                        loading = false;
                        if (btnRefresh != null) {
                            btnRefresh.setText(R.string.refresh);
                            btnRefresh.setAlpha(1f);
                        }
                        render(r);
                    }
                });
            }
        }).start();
    }

    // ---------- 点击卡片查看 API 密钥 ----------

    private static String labelOf(String id) {
        for (int i = 0; i < BalanceFetcher.IDS.length; i++) {
            if (BalanceFetcher.IDS[i].equals(id)) return BalanceFetcher.NAMES[i];
        }
        for (int i = 0; i < BalanceFetcher.PRESETS.length; i++) {
            if (BalanceFetcher.PRESETS[i].id.equals(id)) return BalanceFetcher.PRESETS[i].name;
        }
        if (id.startsWith("custom")) return "自定义平台";
        return "密钥";
    }

    /** 旧缓存可能缺 platform，用 Key id 反查 */
    private String platformOf(BalanceFetcher.Item it) {
        if (it.platform != null && it.platform.length() > 0) return it.platform;
        try {
            java.util.List<KeyStore.ApiKey> all = KeyStore.all(this);
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id.equals(it.id)) return all.get(i).platform;
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /**
     * 控制台直达：点击直接打开控制台，不再弹「控制台/充值」子菜单。
     */
    private void openSiteMenu(final BalanceFetcher.Item it) {
        final java.util.ArrayList<String> labels = new java.util.ArrayList<String>();
        final java.util.ArrayList<String> urls = new java.util.ArrayList<String>();
        int n = BalanceFetcher.collectSites(platformOf(it), labels, urls);
        if (n == 0) {
            android.widget.Toast.makeText(this, "这个平台还没收录官网地址",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        openUrl(urls.get(0));   // 控制台永远排第一
    }

    /** 充值页直达：所有平台都收录了 topup 地址 */
    private void openTopup(final BalanceFetcher.Item it) {
        BalanceFetcher.Preset p = BalanceFetcher.presetOf(platformOf(it));
        if (p == null || p.topup == null || p.topup.trim().length() == 0) {
            android.widget.Toast.makeText(this, "这个平台还没收录充值地址",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        openUrl(p.topup);
    }

    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "打不开：" + url,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 只刷新一个平台，改完缓存再重画列表 */
    private void refreshOne(final BalanceFetcher.Item old) {
        android.widget.Toast.makeText(this, "正在刷新 " + old.label,
                android.widget.Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                final BalanceFetcher.Item fresh =
                        BalanceFetcher.fetchOne(MainActivity.this, old.id, 12000);
                runOnUiThread(new Runnable() {
                    public void run() {
                        BalanceFetcher.Result r = WidgetCache.read(MainActivity.this);
                        if (r != null) {
                            for (int i = 0; i < r.items.size(); i++) {
                                if (r.items.get(i).id.equals(fresh.id)) {
                                    fresh.low = r.items.get(i).low;   // 预警判定保持不变
                                    r.items.set(i, fresh);
                                    break;
                                }
                            }
                            // 重算总额与失败数
                            double sum = 0;
                            int failed = 0;
                            for (int i = 0; i < r.items.size(); i++) {
                                BalanceFetcher.Item x = r.items.get(i);
                                if (x.ok) sum += x.cny; else failed++;
                            }
                            r.totalCny = sum;
                            r.failed = failed;
                            WidgetCache.save(MainActivity.this, r, 0);
                            render(r);
                        }
                        android.widget.Toast.makeText(MainActivity.this,
                                fresh.ok ? (fresh.label + "  " + fresh.amount)
                                         : (fresh.label + " 刷新失败"),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private String keyOf(String id) {
        if (id == null) return "";
        try {
            java.util.List<KeyStore.ApiKey> all = KeyStore.all(this);
            for (int i = 0; i < all.size(); i++) {
                if (id.equals(all.get(i).id)) return all.get(i).key;
            }
        } catch (Throwable ignored) { }
        /* 自定义平台老数据：Key 写在 custom_json 里 */
        if (id.startsWith("custom")) {
            try {
                String n = id.startsWith("custom:") ? id.substring(7) : id.substring(6);
                int idx = Integer.parseInt(n);
                java.util.List<BalanceFetcher.Custom> cs = BalanceFetcher.loadCustom(this);
                if (idx >= 0 && idx < cs.size()) return cs.get(idx).key;
            } catch (Throwable ignored) { }
        }
        return "";
    }

    /** 亮出密钥，可一键复制 */
    void showKey(final String id) {
        final String k = keyOf(id);
        if (k == null || k.trim().length() == 0) {
            android.widget.Toast.makeText(this, "这一项还没有填 Key",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle(labelOf(id) + " 的 API Key")
            .setMessage(k)
            .setPositiveButton("复制", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("key", k));
                        android.widget.Toast.makeText(MainActivity.this, "已复制到剪贴板",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("关闭", null)
            .show();
    }

    private void render(BalanceFetcher.Result r) {
        cards.removeAllViews();

        if (r.configured == 0) {
            tTotal.setText("—");
            tSub.setText(R.string.not_configured);
            tSub.setTextColor(getColor(R.color.tx2));
            return;
        }

        tTotal.setText("¥" + String.format("%.2f", r.totalCny));
        StringBuilder sb = new StringBuilder();
        sb.append("汇率 1 USD = ").append(String.format("%.2f", r.rate));
        sb.append("  ·  ").append(r.configured - r.failed).append("/").append(r.configured).append(" 个平台");
        sb.append("  ·  ").append(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
        if (r.failed > 0) sb.append("  ·  ").append(r.failed).append(" 项失败");
        tSub.setText(sb.toString());
        tSub.setTextColor(getColor(r.failed > 0 ? R.color.warn : R.color.tx3));

        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < r.items.size(); i++) {
            BalanceFetcher.Item it = r.items.get(i);
            final BalanceFetcher.Item fi = it;
            View v = inf.inflate(R.layout.item_platform, cards, false);
            /* 点卡片 = 弹出菜单：刷新这一项 / 看密钥（看密钥要先过密码） */
            v.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v2) {
                    LockDialog.choose(MainActivity.this, fi.label,
                        new String[] { "刷新", "看密钥", "控制台", "充值" },
                        new LockDialog.OnPick() {
                            public void pick(int which) {
                                if (which == 0) {
                                    refreshOne(fi);
                                } else if (which == 2) {
                                    openSiteMenu(fi);
                                } else if (which == 3) {
                                    openTopup(fi);
                                } else {
                                    LockDialog.ask(MainActivity.this,
                                        "查看「" + fi.label + "」的密钥",
                                        new LockDialog.OnPass() {
                                            public void ok() {
                                                LockDialog.showKey(MainActivity.this,
                                                    fi.label + " 的 API Key", keyOf(fi.id));
                                            }
                                        });
                                }
                            }
                        });
                }
            });

            TextView name = (TextView) v.findViewById(R.id.p_name);
            TextView tag = (TextView) v.findViewById(R.id.p_tag);
            TextView amount = (TextView) v.findViewById(R.id.p_amount);
            TextView conv = (TextView) v.findViewById(R.id.p_conv);
            TextView rows = (TextView) v.findViewById(R.id.p_rows);
            View dot = v.findViewById(R.id.p_dot);

            name.setText(it.label);
            tag.setText(it.tag);

            int color = BalanceFetcher.colorOf(platformOf(it));
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(color);
            dot.setBackground(gd);

            if (it.ok) {
                amount.setText(it.amount);
                // 低于预警阈值 → 标红
                amount.setTextColor(getColor(it.low ? R.color.danger : R.color.tx));
                if (it.conv != null && it.conv.length() > 0) {
                    conv.setVisibility(View.VISIBLE);
                    conv.setText(it.conv);
                } else {
                    conv.setVisibility(View.GONE);
                }
                if (it.rows != null && it.rows.length() > 0) {
                    rows.setVisibility(View.VISIBLE);
                    rows.setText(it.rows);
                    rows.setTextColor(getColor(R.color.tx_detail));
                } else {
                    rows.setVisibility(View.GONE);
                }
            } else {
                amount.setText("—");
                amount.setTextColor(getColor(R.color.tx3));
                conv.setVisibility(View.GONE);
                rows.setVisibility(View.VISIBLE);
                rows.setText(it.error);
                rows.setTextColor(getColor(R.color.danger));
            }
            cards.addView(v);
        }
    }
}
