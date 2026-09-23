package com.minis.balancewidget;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
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

        // 日期范围下拉（5/7/14/30/90/180 天）
        findViewById(R.id.range_pick).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showRangePicker(); }
        });
        findViewById(R.id.btn_fix_recharge).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showFixRecharge(); }
        });
        findViewById(R.id.stats_clean).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final int[] keep = { 7, 30, 90, 180 };
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("清理数据 · 选择保留范围")
                        .setSingleChoiceItems(
                                new String[] { "保留近 7 天", "保留近 30 天",
                                        "保留近 3 个月", "保留近半年" },
                                0,
                                new android.content.DialogInterface.OnClickListener() {
                                    public void onClick(android.content.DialogInterface d, int which) {
                                        int days = keep[which];
                                        Ledger.get(MainActivity.this)
                                                .prune(MainActivity.this, days);
                                        buildStats();
                                        d.dismiss();
                                        android.widget.Toast.makeText(MainActivity.this,
                                                "已删除 " + days + " 天前的记录",
                                                android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
    }

    /** 高亮当前选中的范围按钮 */
    /** 日期范围下拉菜单（设计稿：5/7/14/30/90/180 日） */
    private void showRangePicker() {
        final int[] days = { 5, 7, 14, 30, 90, 180 };
        String[] items = { "近 5 日", "近 7 日", "近 14 日", "近 30 日", "近 90 日", "近 180 日" };
        int checked = 1;
        for (int i = 0; i < days.length; i++) if (days[i] == statsDays) checked = i;
        new android.app.AlertDialog.Builder(this)
                .setTitle("日期范围")
                .setSingleChoiceItems(items, checked,
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                statsDays = days[which];
                                d.dismiss();
                                buildStats();
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 修正充值弹窗：手动修正各平台充值额，防止自动匹配错误 */
    private void showFixRecharge() {
        final java.util.List<KeyStore.ApiKey> aks = KeyStore.all(this);
        final java.util.List<KeyStore.ApiKey> targets = new java.util.ArrayList<KeyStore.ApiKey>();
        final java.util.List<EditText> inputs = new java.util.ArrayList<EditText>();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(12), dp(20), 0);
        for (int i = 0; i < aks.size(); i++) {
            KeyStore.ApiKey ak = aks.get(i);
            if (!ak.isConfigured()) continue;
            BalanceFetcher.Preset p = BalanceFetcher.presetOf(ak.platform);
            if (p == null || !"balance".equals(p.kind)) continue;
            targets.add(ak);
            TextView lb = new TextView(this);
            String nm = p.name + ((ak.label != null && ak.label.length() > 0) ? " · " + ak.label : "");
            lb.setText(nm + "  充值额（留空=不改）");
            lb.setTextColor(getColor(R.color.tx2));
            lb.setTextSize(12);
            lb.setPadding(0, dp(10), 0, dp(4));
            panel.addView(lb);
            EditText et = new EditText(this);
            et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            et.setHint("0");
            et.setTextSize(14);
            panel.addView(et);
            inputs.add(et);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("修正充值金额")
                .setView(panel)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        Ledger lg = Ledger.get(MainActivity.this);
                        int n = 0;
                        for (int i = 0; i < targets.size(); i++) {
                            String s = inputs.get(i).getText().toString().trim();
                            if (s.length() == 0) continue;
                            try {
                                double amt = Double.parseDouble(s);
                                if (amt > 0) { lg.manualRecharge(MainActivity.this,
                                        targets.get(i).id, amt); n++; }
                            } catch (Exception ig) { }
                        }
                        android.widget.Toast.makeText(MainActivity.this,
                                n > 0 ? ("已修正 " + n + " 条充值记录") : "没有修改",
                                android.widget.Toast.LENGTH_SHORT).show();
                        buildStats();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 点击图表某天：弹框显示该日各平台余额 */
    private void showDayDialog(int idx) {
        if (statsSeries == null || statsLabels == null || idx < 0
                || idx >= statsLabels.length) return;
        StringBuilder sb = new StringBuilder();
        sb.append("日期：").append(statsLabels[idx]).append("\n\n");
        for (int i = 0; i < statsSeries.size(); i++) {
            UsageChartView.Series s = statsSeries.get(i);
            double v = (s.vals != null && idx < s.vals.length) ? s.vals[idx] : 0;
            sb.append(s.label).append("   余额 ¥").append(String.format("%.2f", v)).append("\n");
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(statsLabels[idx] + " 各平台余额")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 切换 API / 统计 两个栏目 */
    private void showTab(boolean api) {
        findViewById(R.id.total_card).setVisibility(api ? View.VISIBLE : View.GONE);
        findViewById(R.id.cards).setVisibility(api ? View.VISIBLE : View.GONE);
        findViewById(R.id.stats_container).setVisibility(api ? View.GONE : View.VISIBLE);
        // 切换淡入+上浮动画
        View shown = api ? findViewById(R.id.cards) : findViewById(R.id.stats_container);
        shown.setAlpha(0f);
        shown.setTranslationY(dp(12));
        shown.animate().alpha(1f).translationY(0f).setDuration(220).start();
        if (api) {
            View tc = findViewById(R.id.total_card);
            tc.setAlpha(0f);
            tc.animate().alpha(1f).setDuration(220).start();
        }
        TextView ta = (TextView) findViewById(R.id.tab_api);
        TextView ts = (TextView) findViewById(R.id.tab_stats);
        ta.setTextColor(getColor(api ? R.color.accent : R.color.tx));
        ts.setTextColor(getColor(api ? R.color.tx : R.color.accent));
        // 注意：三元 int:null 会装箱 Integer，api=false 时拆箱 null 直接 NPE 崩溃
        ta.setTypeface(null, api ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        ts.setTypeface(null, api ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
    }

    /** 当前统计范围天数（默认 7 天，可切换 30/90/180） */
    private int statsDays = 7;
    /** 当前图表序列与日期标签（供点击某天弹框用） */
    private java.util.List<UsageChartView.Series> statsSeries;
    private String[] statsLabels;

    /**
     * 构建用量统计页：按当前范围（默认 7 天，可切换 30/90/180）按天聚合消耗（柱）与总余额（线）。
     * 只统计 draw=true（未隐藏）的平台；每平台一行隐藏开关。
     * 消耗 = Ledger 快照差值，已扣除充值（record 内自动检测）。
     */
    /** 图表折线调色板（按线索引取色，同平台多 Key 也能区分） */
    private static final int[] CHART_PALETTE = {
            0xFF4D6BFE, 0xFF22C55E, 0xFFF97316, 0xFF8B5CF6, 0xFF0EA5E9,
            0xFFEC4899, 0xFF14B8A6, 0xFFEAB308, 0xFF6366F1, 0xFF84CC16 };

    private void buildStats() {
        final int DAYS = statsDays;
        long now = System.currentTimeMillis();
        long from = now - (long) DAYS * 86400000L;
        SharedPreferences sp = getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
        double rate = sp.getFloat("last_rate", 7.1f);
        boolean showBars = sp.getBoolean("chart_show_bars", true);
        boolean showGrid = sp.getBoolean("chart_show_grid", true);
        boolean showLegend = sp.getBoolean("chart_show_legend", true);
        boolean showTotal = sp.getBoolean("stats_show_total", true);

        String[] labels = new String[DAYS];
        java.util.Calendar cal = java.util.Calendar.getInstance();
        for (int i = 0; i < DAYS; i++) {
            cal.setTimeInMillis(now - (long) (DAYS - 1 - i) * 86400000L);
            labels[i] = (cal.get(java.util.Calendar.MONTH) + 1) + "/"
                    + cal.get(java.util.Calendar.DAY_OF_MONTH);
        }

        Ledger lg = Ledger.get(this);
        java.util.List<KeyStore.ApiKey> aks = KeyStore.all(this);
        java.util.List<UsageChartView.Series> series =
                new java.util.ArrayList<UsageChartView.Series>();
        java.util.HashMap<String, Integer> seen = new java.util.HashMap<String, Integer>();
        double[] consDay = new double[DAYS];
        double[] totalBal = new double[DAYS];
        int[] totalCnt = new int[DAYS];
        StringBuilder sum = new StringBuilder();
        StringBuilder detail = new StringBuilder();
        double totalCons = 0;
        double totalCharged = 0;
        int balIdx = 0;   // balance 类平台序号（折线/圆点配色稳定用）

        for (int i = 0; i < aks.size(); i++) {
            final KeyStore.ApiKey ak = aks.get(i);
            if (!ak.isConfigured()) continue;
            BalanceFetcher.Preset p = BalanceFetcher.presetOf(ak.platform);
            String kind = p == null ? "balance" : p.kind;
            if (!"balance".equals(kind)) continue;              // 非余额类不进图表
            boolean usd = p != null && "USD".equals(p.unit);
            double mul = usd ? rate : 1.0;
            String pname = p == null ? ak.platform : p.name;
            String lb0 = ak.label == null ? "" : ak.label.trim();
            String name = (lb0.length() > 0 && !"默认".equals(lb0))
                    ? pname + " · " + lb0 : pname;
            // 重名加后缀，避免同名节点混淆（关一个像关了另一个）
            Integer cnt0 = seen.get(name);
            if (cnt0 == null) seen.put(name, 1);
            else { seen.put(name, cnt0 + 1); name = name + " #" + (cnt0 + 1); }

            java.util.List<Ledger.Point> pts = lg.series(this, ak.id, from);
            double[] own = new double[DAYS];
            int[] cnt = new int[DAYS];
            double platCons = 0;
            double platCharged = 0;
            for (int j = 0; j < pts.size(); j++) {
                Ledger.Point pt = pts.get(j);
                int idx = DAYS - 1 - (int) ((now - pt.ts) / 86400000L);
                if (idx < 0 || idx >= DAYS) continue;
                own[idx] = pt.balance * mul; cnt[idx]++;
                consDay[idx] += pt.consumed * mul;
                platCons += pt.consumed * mul;
                platCharged += pt.charged * mul;
            }
            if (pts.size() < 2) continue;                        // 数据太少不画线
            // 前向填充：当天无快照沿用前一天，避免断线掉到 0
            for (int d = 0; d < DAYS; d++) if (cnt[d] == 0) own[d] = (d > 0 ? own[d - 1] : 0);
            totalCons += platCons;
            totalCharged += platCharged;
            detail.append(name).append("   充值 ¥").append(String.format("%.2f", platCharged))
                  .append("   消耗 ¥").append(String.format("%.2f", platCons)).append("\n");
            sum.append(name).append("  消耗 ").append(String.format("%.2f", platCons)).append("\n");

            if (ak.draw) {                                       // 数据源开关：隐藏的不画线不计总计
                // 图例带最新余额，便于直接读数；颜色按平台序号稳定分配（开关切换不变色）
                String legendLabel = name + "  ¥" + String.format("%.2f", own[DAYS - 1]);
                series.add(new UsageChartView.Series(
                        CHART_PALETTE[balIdx % CHART_PALETTE.length], own, legendLabel, false));
                for (int d = 0; d < DAYS; d++) { totalBal[d] += own[d]; totalCnt[d]++; }
            }
            balIdx++;
        }
        // 总计线（独立开关，带渐变面积填充）
        if (showTotal && series.size() > 0) {
            series.add(new UsageChartView.Series(0xFFFF9800, totalBal, "总计", true));
        }

        UsageChartView chart = (UsageChartView) findViewById(R.id.usage_chart);
        chart.setData(series, showBars ? consDay : null, labels, showBars, showGrid, showLegend);
        statsSeries = series;
        statsLabels = labels;
        chart.setOnTapDay(new UsageChartView.OnTapDay() {
            public void onTap(int dayIndex) { showDayDialog(dayIndex); }
        });

        // 范围标签 / 下拉按钮文字
        TextView rl = (TextView) findViewById(R.id.stats_range_label);
        if (rl != null) rl.setText("近 " + DAYS + " 日");
        TextView rp = (TextView) findViewById(R.id.range_pick);
        if (rp != null) rp.setText("近 " + DAYS + " 天 ▽");

        // 每平台充值/消耗明细
        LinearLayout dbox = (LinearLayout) findViewById(R.id.stats_detail);
        if (dbox != null) {
            dbox.removeAllViews();
            String[] lines = detail.toString().split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].length() == 0) continue;
                TextView row = new TextView(this);
                row.setText(lines[i]);
                row.setTextColor(getColor(R.color.tx2));
                row.setTextSize(13);
                row.setPadding(0, dp(4), 0, dp(4));
                dbox.addView(row);
            }
        }
        // 总充值 / 总消耗
        TextView tt = (TextView) findViewById(R.id.stats_totals);
        if (tt != null) tt.setText("总充值 ¥" + String.format("%.2f", totalCharged)
                + "    总消耗 ¥" + String.format("%.2f", totalCons));

        buildPlatformToggles(aks);
    }

    /** 每个 API 一个 Switch 开关数据源；总计单独一个 Switch。切换后重绘图表。 */
    private void buildPlatformToggles(java.util.List<KeyStore.ApiKey> aks) {
        LinearLayout box = (LinearLayout) findViewById(R.id.stats_platforms);
        box.removeAllViews();
        final SharedPreferences sp =
                getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
        java.util.HashMap<String, Integer> seen = new java.util.HashMap<String, Integer>();
        int balIdx = 0;   // 与 buildStats 同序，保证圆点色=折线色

        // 总计开关
        android.widget.Switch totalSw = new android.widget.Switch(this);
        totalSw.setText("总计（所有已启用 API 之和）");
        totalSw.setTextColor(getColor(R.color.tx));
        totalSw.setTextSize(13);
        totalSw.setChecked(sp.getBoolean("stats_show_total", true));
        totalSw.setPadding(dp(14), dp(10), dp(14), dp(10));
        totalSw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                sp.edit().putBoolean("stats_show_total", on).apply();
                buildStats();
            }
        });
        box.addView(totalSw);

        for (int i = 0; i < aks.size(); i++) {
            final KeyStore.ApiKey ak = aks.get(i);
            if (!ak.isConfigured()) continue;
            BalanceFetcher.Preset p = BalanceFetcher.presetOf(ak.platform);
            if (p == null || !"balance".equals(p.kind)) continue;
            String pname = p == null ? ak.platform : p.name;
            String lb1 = ak.label == null ? "" : ak.label.trim();
            String name = (lb1.length() > 0 && !"默认".equals(lb1))
                    ? pname + " · " + lb1 : pname;
            Integer cnt1 = seen.get(name);
            if (cnt1 == null) seen.put(name, 1);
            else { seen.put(name, cnt1 + 1); name = name + " #" + (cnt1 + 1); }
            // 行：色点 + 名称 + Switch（色点与折线同色）
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(6), dp(6), dp(6));
            View dot = new View(this);
            dot.setBackgroundColor(CHART_PALETTE[balIdx % CHART_PALETTE.length]);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(12), dp(12));
            dlp.rightMargin = dp(10);
            dot.setLayoutParams(dlp);
            row.addView(dot);
            android.widget.Switch sw = new android.widget.Switch(this);
            sw.setText(name);
            sw.setTextColor(getColor(R.color.tx));
            sw.setTextSize(13);
            sw.setChecked(ak.draw);
            sw.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    ak.draw = on;
                    KeyStore.update(MainActivity.this, ak);
                    buildStats();
                }
            });
            row.addView(sw);
            box.addView(row);
            balIdx++;
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
