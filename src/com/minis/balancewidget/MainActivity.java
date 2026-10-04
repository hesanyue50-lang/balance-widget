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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private LinearLayout cards;
    private TextView tTotal;
    private TextView tSub;
    private boolean loading = false;
    /** 收起所有页面的下拉刷新指示器（三页共用一个胶囊，谁收起都一样） */
    private void hidePullRefresh() {
        if (scrolls == null) return;
        for (int i = 0; i < scrolls.length; i++) {
            if (scrolls[i] != null) scrolls[i].setRefreshing(false);
        }
    }
    /** 这次下拉刷新结束后要不要重算统计图表 */
    private boolean statsRebuildAfterRefresh = false;

    private final Handler autoHandler = new Handler();
    private final Runnable autoTask = new Runnable() {
        public void run() {
            refresh(true);          // 静默刷新，不闪"正在查询"
            /* 顺带把小米账号会话续上。小米控制台前端自带续期逻辑
               （带长期凭证换新会话），但必须**页面被加载**才会跑 ——
               老实现只在登录那一刻碰过 WebView，之后再没访问过，
               于是十几分钟就过期。这里定期静默加载一次即可（内部有 8 分钟间隔闸）。 */
            MimoKeepAlive.tick(MainActivity.this);
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

        /* 上次开着加速就自动拉起内核（约 1~2 秒）。放后台线程，别拖慢启动；
           内核是本应用的子进程，应用进程一死它就跟着走 —— 不会后台偷跑。 */
        if (Clash.enabled(this) && !Clash.isRunning()) {
            final Context c0 = this;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        String sub = readText(new java.io.File(Clash.workDir(c0), "sub.yaml"));
                        if (sub.length() > 0) {
                            String err = Clash.start(c0, Clash.buildConfig(sub));
                            BalanceFetcher.diag(c0, "自动启动加速：" + (err == null ? "成功" : err));
                        }
                    } catch (Throwable t) {
                        BalanceFetcher.diag(c0, "自动启动加速异常 " + t);
                    }
                }
            }).start();
        }

        /* 进 App 就先保活一次：会话多半在后台已经过期了，
           先续上再刷新，比让用户看到"登录过期"再手动登要顺得多。 */
        MimoKeepAlive.tick(this);

        cards = (LinearLayout) findViewById(R.id.cards);
        tTotal = (TextView) findViewById(R.id.t_total);
        tSub = (TextView) findViewById(R.id.t_sub);
        // 刷新按钮已移除：点总余额栏或任意卡片刷新
        findViewById(R.id.total_card).setOnClickListener(refreshClick);

        applyWindowInsets();
        setupPager();
        bindPullToRefresh();

        findViewById(R.id.total_card).setOnClickListener(refreshClick);
        /* 导航标签改由 rebuildNav() 动态创建（页面可隐藏，数量会变），
           这里不再对布局里的固定 id 设点击 —— 那些 id 已经删掉了。 */

        // 日期范围下拉（5/7/14/30/90/180 天）
        findViewById(R.id.range_pick).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showRangePicker(); }
        });
        findViewById(R.id.btn_fix_recharge).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showFixRecharge(); }
        });

        // 补数据：距上次刷新 >30 分钟则静默刷一次，保证 Ledger 快照连续（避免无快照日）
        final SharedPreferences bsp =
                getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
        if (System.currentTimeMillis() - bsp.getLong("last_refresh_ts", 0) > 30L * 60000) {
            bsp.edit().putLong("last_refresh_ts", System.currentTimeMillis()).apply();
            refresh(true);
        }
        // 从设置页同级导航滑回：切到指定 Tab
        String gt0 = getIntent().getStringExtra("goto_tab");
        int gi0 = getIntent().getIntExtra("goto_tab", -1);
        if (gt0 != null || gi0 >= 0) {
            /* 显式指定了目标页（通知、快捷方式等）：按 key 找它在当前可见页里的位置 */
            if ("stats".equals(gt0) || gi0 == 1) showPanel(indexOfPage(PageStore.K_STATS));
            else if ("vpn".equals(gt0) || gi0 == 2) showPanel(indexOfPage(PageStore.K_VPN));
            else if ("settings".equals(gt0) || gi0 == 3) showPanel(indexOfPage(PageStore.K_SETTINGS));
            else showPanel(indexOfPage(PageStore.K_API));
        } else {
            /* 没指定就落到「主页面」（用户自己设的，可能是任意一页） */
            showPanel(PageStore.homeIndex(this));
        }

        /* 机场网页点「导入 Clash」跳进来：确认后自动添加订阅并下载 */
        handleImportIntent(getIntent());

        // 预热设置面板：进入 App 后空闲时先构建一次，用户切到设置时几乎无感
        findViewById(R.id.settings_container).postDelayed(new Runnable() {
            public void run() { buildSettingsPanel(); }
        }, 900);
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
                                        final int dd = days;
                                        d.dismiss();
                                        Busy.run(MainActivity.this, "正在清理并重算…", new Runnable() {
                                            public void run() {
                                                Ledger.get(MainActivity.this)
                                                        .prune(MainActivity.this, dd);
                                                buildStats();
                                            }
                                        });
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
                                buildStats();   // 只换时间范围，重算很快，不必盖遮罩
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 修正充值弹窗：手动修正各平台充值额，防止自动匹配错误 */
    private void showFixRecharge() {
        Busy.run(this, "正在读取密钥…", new Runnable() { public void run() { showFixRechargeNow(); } });
    }

    private void showFixRechargeNow() {
        final java.util.List<KeyStore.ApiKey> aks = KeyStore.all(this);
        // 按平台一行：充值记录写在平台的代表 Key 上（平台消耗=各 Key 之和，只应扣一次充值）
        final java.util.List<KeyStore.ApiKey> targets = new java.util.ArrayList<KeyStore.ApiKey>();
        final java.util.List<EditText> inputs = new java.util.ArrayList<EditText>();
        java.util.HashSet<String> donePlat = new java.util.HashSet<String>();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(12), dp(20), 0);
        for (int i = 0; i < aks.size(); i++) {
            KeyStore.ApiKey ak = aks.get(i);
            if (!ak.isConfigured()) continue;
            BalanceFetcher.Preset p = BalanceFetcher.presetOf(ak.platform);
            if (p == null || !"balance".equals(p.kind)) continue;
            if (donePlat.contains(ak.platform)) continue;   // 同平台只出现一行
            donePlat.add(ak.platform);
            targets.add(ak);
            TextView lb = new TextView(this);
            lb.setText(p.name + "  充值额（留空=不改）");
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
                        buildStats();   // 同上：重算很快，遮罩反而显得卡
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

    private boolean settingsPanelBuilt = false;
    /** 设置面板绑定器：从 MiMo 登录页回来后要靠它刷新「登录状态」那行字 */
    private SettingsBinder settingsBinder;

    /** 设置面板：直接绑定与设置页相同的设置主体（同层，无跳转） */
    private void buildSettingsPanel() {
        if (settingsPanelBuilt) return;
        settingsPanelBuilt = true;
        settingsBinder = new SettingsBinder(this, findViewById(R.id.settings_container),
                new Runnable() {
                    public void run() { applyHideLocally(); refresh(false); }
                });
        /* 页面顺序 / 隐藏 / 主页面 改动后：重建导航条与页面排布 */
        settingsBinder.onPagesChanged = new Runnable() {
            public void run() {
                /* 按 **key** 找回原来那一页 —— 用下标不行：
                   重排之后同一个下标已经是另一页了，用户会觉得"跳页了"。 */
                String keep = curKey;
                rebuildNav();
                int idx = indexOfPage(keep);
                if (idx < 0) idx = PageStore.homeIndex(MainActivity.this);   // 那一页被隐藏了
                showPanel(Math.max(0, idx));
            }
        };
        settingsBinder.bind();
    }

    /** 当前页在**可见页**里的下标。具体对应哪一页由用户设置的顺序决定，
        所以索引只在运行时相对有效 —— 需要按 key 定位时用 indexOfPage()。 */
    private int curPanel = 0;
    /** 横向分页容器（左右滑动切换） */
    private PanelPager pager;
    /** 导航栏的滑动滑块 */
    private View segSlider;
    /** 分页数量：API 余额 / 用量统计 / 网络加速 / 设置 */
    private static final int TAB_COUNT = 4;
    /** 网络加速页控制器 */
    private VpnBinder vpnBinder;

    /** 三页的滚动容器，按页序排列 */
    private PullScrollView[] scrolls;

    /** 页面 key → 它的滚动容器（隐藏时也保留引用，切换可见要复原） */
    private final java.util.LinkedHashMap<String, PullScrollView> pageScrolls =
            new java.util.LinkedHashMap<String, PullScrollView>();
    /** 页面 key → 导航标签 */
    private final java.util.LinkedHashMap<String, TextView> navTabs =
            new java.util.LinkedHashMap<String, TextView>();

    /**
     * 初始化分页。
     *
     * 导航条是**代码动态构建**的（而不是布局里写死四个 TextView）——
     * 因为页面可以隐藏，标签数量会变；同时顺序也由用户决定。
     *
     * 导航栏的滑块动画不用动画器，直接由滚动进度驱动（见 applyNavProgress），
     * 手指拖到哪滑块就在哪，比播放动画跟手。
     */
    private void setupPager() {
        pager = (PanelPager) findViewById(R.id.panel_pager);
        segSlider = findViewById(R.id.seg_slider);

        /* 先把四个页面按 id 收好 —— 一旦从容器里挪动位置，
           activity 级 findViewById 就找不到它们了，所以引用要提前拿住。 */
        pageScrolls.put(PageStore.K_API, (PullScrollView) findViewById(R.id.scroll_api));
        pageScrolls.put(PageStore.K_STATS, (PullScrollView) findViewById(R.id.scroll_stats));
        pageScrolls.put(PageStore.K_VPN, (PullScrollView) findViewById(R.id.scroll_vpn));
        pageScrolls.put(PageStore.K_SETTINGS, (PullScrollView) findViewById(R.id.scroll_settings));

        rebuildNav();

        final View wrap = findViewById(R.id.seg_wrap);
        if (wrap != null) {
            wrap.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                public void onLayoutChange(View v, int l, int t, int r, int b,
                                           int ol, int ot, int or_, int ob) {
                    layoutSlider();
                }
            });
        }
        if (pager != null) {
            /* 折线图设为**横向死区**：用量统计里要看某个时间点的数值，
               得在图上按住左右拖；而分页器默认把横向滑动判成"翻页" ——
               两者抢同一个手势，结果图永远拖不动、页面先翻走了。
               给图表划一块死区，它那一片不参与翻页。 */
            View chart = findViewById(R.id.usage_chart);
            if (chart != null) pager.setHDeadZone(chart);

            pager.setListener(new PanelPager.Listener() {
                public void onPagerScroll(float progress) { applyNavProgress(progress); }
                public void onPageSettled(int page) { onPanelSettled(page); }
            });
            pager.post(new Runnable() {
                public void run() {
                    layoutSlider();
                    pager.setPage(curPanel, false);
                    onPanelSettled(curPanel);
                }
            });
        }
    }

    /**
     * 按存档重建：页面顺序、隐藏状态、导航标签。
     *
     * 页面**不销毁重建**，只是调换在容器里的次序 + 置 GONE。
     * 这样各页的状态（滚动位置、已渲染内容）都还在，切回来是瞬时的。
     */
    private void rebuildNav() {
        List<String> vis = PageStore.visibleOrder(this);
        if (vis.isEmpty()) vis.add(PageStore.K_API);

        /* 1) 页面重排。
              ⚠️ 必须按 **vis（用户排好的顺序）** 来加，不能按 PageStore.allKeys()
                 那个内置固定顺序 —— 之前就是这里写错了，导致导航栏文字顺序变了、
                 但页面内容还是老次序（用户看到的正是这个症状）。 */
        if (pager != null && pager.getChildCount() > 0) {
            LinearLayout row = (LinearLayout) pager.getChildAt(0);
            row.removeAllViews();
            for (int i = 0; i < vis.size(); i++) {
                PullScrollView v = pageScrolls.get(vis.get(i));
                if (v == null) continue;
                v.setVisibility(View.VISIBLE);
                row.addView(v);
            }
            /* 被隐藏的页面从容器里摘出去（不是只置 GONE）——
                 摘掉之后 PanelPager 的宽度计算天然就是对的。 */
            for (int i = 0; i < PageStore.allKeys().length; i++) {
                String k = PageStore.allKeys()[i];
                if (vis.contains(k)) continue;
                PullScrollView v = pageScrolls.get(k);
                if (v != null) v.setVisibility(View.GONE);
            }
        }

        // 2) 滚动区数组（顺序已按可见页）
        List<PullScrollView> scs = new ArrayList<PullScrollView>();
        for (int i = 0; i < vis.size(); i++) {
            PullScrollView v = pageScrolls.get(vis.get(i));
            if (v != null) scs.add(v);
        }
        scrolls = scs.toArray(new PullScrollView[0]);

        // 3) 导航标签重建
        LinearLayout tabs = (LinearLayout) findViewById(R.id.seg_tabs);
        navTabs.clear();
        if (tabs != null) {
            tabs.removeAllViews();
            for (int i = 0; i < vis.size(); i++) {
                final String key = vis.get(i);
                TextView t = new TextView(this);
                t.setText(PageStore.nameOf(key));
                t.setTextSize(12);
                t.setGravity(android.view.Gravity.CENTER);
                t.setPadding(0, dp(8), 0, dp(8));
                t.setLayoutParams(new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f / vis.size() * vis.size() / (float) vis.size()));
                /* weight 必须是 1，均分由 count 决定；上面那行是为了可读性写成等价形式，
                   这里直接覆盖成 1f 更稳妥 */
                t.setLayoutParams(new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                t.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { showPanel(indexOfPage(key)); }
                });
                navTabs.put(key, t);
                tabs.addView(t);
            }
        }
        /* scrolls 数组换了，下拉监听要重新挂 —— 否则重排后的页面下拉失效 */
        applyPullListeners();
        applyNavProgress(0f);
        layoutSlider();
    }

    /** 某个 key 在当前可见页里的下标（找不到返回 -1） */
    private int indexOfPage(String key) {
        List<String> vis = PageStore.visibleOrder(this);
        return vis.indexOf(key);
    }

    /** 当前可见页第 i 个对应的 key（越界返回空串） */
    private String keyOfIndex(int i) {
        List<String> vis = PageStore.visibleOrder(this);
        return (i < 0 || i >= vis.size()) ? "" : vis.get(i);
    }

    /** 当前页的 key（供重排后定位） */
    private String curKey = PageStore.K_API;

    /**
     * 按滑动进度刷新导航栏：滑块位置 + 三个标签的颜色渐变。
     * progress 可能是小数（正处在两页之间），所以颜色要按距离插值，
     * 而不是简单地"选中/未选中"二选一 —— 那会在滑动中间出现突兀的跳变。
     */
    /**
     * 滑块尺寸 = 等分内宽 × 标签行高（都得等布局完成后再量）。
     *
     * 高度必须由代码设：滑块是无内容的纯 View，若在 XML 里写 match_parent，
     * 父容器处于 AT_MOST 约束时 View.getDefaultSize() 会直接返回上限值 ——
     * 滑块会一路撑到整屏高，把顶栏和下面所有内容都挤没（踩过）。
     *
     * 等分数按**当前可见页数**算，隐藏页面后滑块会自动变宽，不用改代码。
     */
    private void layoutSlider() {
        View wrap = findViewById(R.id.seg_wrap);
        View tabs = findViewById(R.id.seg_tabs);
        if (wrap == null || segSlider == null) return;
        int inner = wrap.getWidth() - wrap.getPaddingLeft() - wrap.getPaddingRight();
        int n = Math.max(1, navTabs.size());
        int w = inner / n;
        int h = (tabs != null) ? tabs.getHeight() : 0;
        android.view.ViewGroup.LayoutParams lp = segSlider.getLayoutParams();
        boolean need = false;
        if (w > 0 && lp.width != w) { lp.width = w; need = true; }
        if (h > 0 && lp.height != h) { lp.height = h; need = true; }
        if (need) segSlider.setLayoutParams(lp);
        applyNavProgress(pager == null || pager.getWidth() == 0
                ? curPanel : pager.getScrollX() / (float) pager.getWidth());
    }

    /**
     * 按滑动进度刷新导航栏：滑块位置 + 各标签的颜色渐变。
     * progress 可能是小数（正处在两页之间），所以颜色按距离插值，
     * 而不是简单地"选中/未选中"二选一 —— 那会在滑动中间出现突兀的跳变。
     */
    private void applyNavProgress(float progress) {
        View wrap = findViewById(R.id.seg_wrap);
        if (wrap == null || segSlider == null) return;
        int inner = wrap.getWidth() - wrap.getPaddingLeft() - wrap.getPaddingRight();
        if (inner <= 0) return;
        float step = inner / (float) Math.max(1, navTabs.size());
        segSlider.setTranslationX(progress * step);

        /* 用当前的标签列表，而不是写死的四个 id —— 隐藏页面后标签会变少 */
        int on = getColor(R.color.accent);
        int off = getColor(R.color.tx2);
        int i = 0;
        for (java.util.Iterator<TextView> it = navTabs.values().iterator(); it.hasNext(); i++) {
            TextView t = it.next();
            float t01 = Math.max(0f, 1f - Math.abs(progress - i));
            t.setTextColor(blendColor(off, on, t01));
            t.setTypeface(null, t01 > 0.5f
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    /** 两色线性插值（用于导航标签的渐变） */
    private static int blendColor(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (android.graphics.Color.alpha(from)
                + (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * t);
        int r = (int) (android.graphics.Color.red(from)
                + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * t);
        int g = (int) (android.graphics.Color.green(from)
                + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * t);
        int b = (int) (android.graphics.Color.blue(from)
                + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * t);
        return android.graphics.Color.argb(a, r, g, b);
    }

    /**
     * 切到某页。动画、导航栏、滚动位置全部交给 PanelPager 统一处理；
     * 那些"重活"（重建统计 / 构建设置面板）放在**停稳之后**做 ——
     * 边滚边算会掉帧，而且用户可能只是滑过去又滑回来。
     */
    private void showPanel(int idx) {
        BalanceFetcher.diag(this, "showPanel " + idx);
        if (pager == null) { setupPager(); }
        if (pager == null) return;
        pager.setPage(idx, true);
    }

    /** 某页停稳后：建内容、切下拉开关、把该页滚回顶部 */
    private void onPanelSettled(int page) {
        curPanel = page;
        curKey = keyOfIndex(page);      // 记住 key：页面重排后靠它找回同一页
        // 下拉刷新：设置页不给下拉（那里没什么好刷的）
        /* 下拉刷新只在「会联网」的两页有意义（API 余额 / 用量统计），
           加速页与设置页关掉。这里用 key 判断而不是下标 —— 页面顺序用户可以改。 */
        boolean pullOk = PageStore.K_API.equals(curKey) || PageStore.K_STATS.equals(curKey);
        if (scrolls != null) {
            for (int i = 0; i < scrolls.length; i++) {
                if (scrolls[i] != null) scrolls[i].setPullEnabled(i == page && pullOk);
            }
        }
        /* 这两处**故意不加加载遮罩**：数据早已在内存/缓存里，重算只要几十毫秒，
           盖一层遮罩反而让人以为「卡了一下」，割裂感比省下的那点等待更明显。
           遮罩只留给真正可能耗时的操作（保存密钥要过 Keystore 加密、清理数据要删库）。 */
        if (page == 1) {
            buildStats();
        } else if (page == 2) {
            if (vpnBinder == null) vpnBinder = new VpnBinder(this, findViewById(R.id.vpn_page));
            vpnBinder.bind();
            dropEditFocus();
        } else if (page == 3) {
            buildSettingsPanel();
            dropEditFocus();     // 设置页里有输入框，别让它一进来就抢焦点弹键盘
        }
        // 回到该页顶部：沿用上一页的滚动位置会让人以为内容没加载
        if (scrolls != null && page >= 0 && page < scrolls.length && scrolls[page] != null) {
            final android.widget.ScrollView sc = scrolls[page];
            sc.post(new Runnable() {
                public void run() {
                    sc.scrollTo(0, 0);
                    dropEditFocus();
                }
            });
        }
    }

    /**
     * 清掉输入框焦点并收起键盘。
     *
     * 设置页里第一个 EditText 会跟着 Activity 一起自动获焦，结果一进设置就
     * 弹出键盘、光标闪烁 —— 用户根本没打算输入。三道保险一起上：
     *   ① manifest 的 stateAlwaysHidden（窗口初始不弹键盘）；
     *   ② 根布局 focusableInTouchMode，让初始焦点落在根上而不是第一个输入框；
     *   ③ 就是这里，面板停稳后主动清一次焦点。
     * 任意一道生效都不会再出现"自动弹键盘"，多余的重复只是无害。
     */
    private void dropEditFocus() {
        try {
            View f = getCurrentFocus();
            if (f instanceof android.widget.EditText) {
                f.clearFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
                }
            }
        } catch (Throwable ignored) { }
    }

    /** 当前统计范围天数（默认 7 天，可切换 30/90/180） */
    private int statsDays = 7;
    /** 当前图表序列与日期标签（供点击某天弹框用） */
    private java.util.List<UsageChartView.Series> statsSeries;
    private String[] statsLabels;
    /** keyId → 折线色，保证开关色点与折线同色 */
    /** 平台 → 折线/圆点颜色、平台 → 统计显示开关 */
    private final java.util.HashMap<String, Integer> platformColor = new java.util.HashMap<String, Integer>();
    private final java.util.HashMap<String, Boolean> platformDraw = new java.util.HashMap<String, Boolean>();

    /**
     * 构建用量统计页：按当前范围（默认 7 天，可切换 30/90/180）按天聚合消耗（柱）与总余额（线）。
     * 只统计 draw=true（未隐藏）的平台；每平台一行隐藏开关。
     * 消耗 = Ledger 快照差值，已扣除充值（record 内自动检测）。
     */
    /** 图表折线调色板（按线索引取色，同平台多 Key 也能区分） */
    private static final int[] CHART_PALETTE = {
            0xFF4D6BFE, 0xFF22C55E, 0xFFF97316, 0xFF8B5CF6, 0xFF0EA5E9,
            0xFFEC4899, 0xFF14B8A6, 0xFFEAB308, 0xFF6366F1, 0xFF84CC16 };

    private void buildStats() { buildStats(true); }

    /**
     * 分桶粒度的候选档位。图表宽度有限，点数太多会糊成一片，
     * 所以按范围挑一个"能把点数压到 200 以内、又尽量细"的粒度。
     */
    private static final long[] SLOT_CANDIDATES = {
            5L * 60 * 1000,             // 5 分钟（= 采样下限，最细档）
            15L * 60 * 1000,            // 15 分钟
            30L * 60 * 1000,            // 30 分钟
            60L * 60 * 1000,            // 1 小时
            2L * 3600 * 1000,           // 2 小时
            4L * 3600 * 1000,           // 4 小时
            6L * 3600 * 1000,           // 6 小时
            12L * 3600 * 1000,          // 12 小时
            24L * 3600 * 1000,          // 1 天
            48L * 3600 * 1000,          // 2 天
    };

    /**
     * 格子数上限。
     *
     * 定得比较宽（2200）是**故意的**：格子多只意味着遍历次数多（可忽略），
     * 但粒度够细才不会有"两次刷新落进同一格"的情况 ——
     * 那正是"余额谷底被抹掉"的根源：实测 16:55=14.52 与 17:01=24.11
     * 只差 6 分钟，在 1 小时粒度下被并成一个点，取最新就只剩 24.11，
     * 用户看到的曲线凭空少了那个低谷。
     *
     * 把粒度压到采样下限（5 分钟）之后，两点最小间隔就是 5 分钟，
     * 不会再有合并损失。空格子不画点（遍历时跳过 NaN），所以视觉上依然是
     * 稀疏的点连成的线，不会因为格子多而变糊。
     */
    private static final int TARGET_SLOTS = 2200;

    /** 按范围挑分桶粒度：近 7 日用 1 小时/点，近 30 日用 4 小时，近 180 日用 1 天 */
    private static long pickSlotMs(int days) {
        long span = (long) days * 86400000L;
        for (int i = 0; i < SLOT_CANDIDATES.length; i++) {
            if (span / SLOT_CANDIDATES[i] <= TARGET_SLOTS) return SLOT_CANDIDATES[i];
        }
        return SLOT_CANDIDATES[SLOT_CANDIDATES.length - 1];
    }

    /** 粒度的人类可读写法（"1 小时/点"） */
    private static String slotLabel(long ms) {
        long min = ms / 60000L;
        if (min < 60) return min + " 分钟";
        long h = ms / 3600000L;
        if (h < 24) return h + " 小时";
        return (h / 24) + " 天";
    }

    /** 首轮构建时打一次点明细（诊断用），之后不再打，免得刷屏 */
    private boolean statsDiagDone = false;

    private void buildStats(boolean rebuildToggles) {
        final boolean diagThisRound = !statsDiagDone;
        statsDiagDone = true;
        /* 分桶粒度按范围自适应 —— 以前固定 6 小时/点，近 7 天只有 28 个点，
           每次刷新带来的变化完全看不出来。近 7 天现在是 1 小时/点（168 点）。 */
        final long SLOT_MS = pickSlotMs(statsDays);
        final int DAYS = (int) ((long) statsDays * 86400000L / SLOT_MS);
        long now = System.currentTimeMillis();
        long from = now - (long) statsDays * 86400000L;
        SharedPreferences sp = getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
        double rate = sp.getFloat("last_rate", 7.1f);
        // 一次性迁移：旧版默认开柱，设计稿只要折线 → 重置为关
        if (!sp.contains("bars_reset_v180")) {
            sp.edit().putBoolean("chart_show_bars", false)
                    .putBoolean("bars_reset_v180", true).apply();
        }
        boolean showBars = false;      // 设计稿只画折线
        boolean showGrid = true;
        boolean showLegend = true;     // 触摸浮动框用
        boolean showTotal = false;     // 总计已移除

        String[] labels = new String[DAYS];
        java.util.Calendar cal = java.util.Calendar.getInstance();
        for (int i = 0; i < DAYS; i++) {
            cal.setTimeInMillis(now - (long) (DAYS - 1 - i) * SLOT_MS);
            labels[i] = String.format("%d日%02d时",
                    cal.get(java.util.Calendar.DAY_OF_MONTH),
                    cal.get(java.util.Calendar.HOUR_OF_DAY));
        }

        Ledger lg = Ledger.get(this);
        java.util.List<KeyStore.ApiKey> aks = KeyStore.all(this);

        /* 当前一轮的余额（卡片缓存里就是刚才拉到的实时值）：
           用来给「还没有历史快照」的平台补上当前这个点。 */
        final java.util.List<BalanceFetcher.Item> liveItems;
        {
            BalanceFetcher.Result lr = lastResult != null ? lastResult : WidgetCache.read(this);
            liveItems = (lr != null && lr.items != null)
                    ? lr.items : new java.util.ArrayList<BalanceFetcher.Item>();
        }
        // 按「平台」分组：同一平台下多个 Key 合并为一条（各平台普遍不提供按 Key 查询用量）
        java.util.LinkedHashMap<String, java.util.List<KeyStore.ApiKey>> groups =
                new java.util.LinkedHashMap<String, java.util.List<KeyStore.ApiKey>>();
        for (int i = 0; i < aks.size(); i++) {
            KeyStore.ApiKey k = aks.get(i);
            /* 未配置 Key 的平台也照收 —— 只要它"有办法取数"。
               MiMo 正是这种：凭据是登录会话音、不是 API Key。老代码把它挡在分组外，
               统计页就只能拿**平台 id** 去查账本，而账本按 **Key id** 记账
               → 怎么查都是空（这就是"小米 MiMo 没有任何数据"的真因）。
               让它进组之后，查询用的 kid 才会等于账本里的 key id。 */
            if (!k.isConfigured() && !BalanceFetcher.platformUsable(this,
                    k.platform == null ? "" : k.platform)) continue;
            String plat = k.platform == null ? "" : k.platform;
            java.util.List<KeyStore.ApiKey> g = groups.get(plat);
            if (g == null) { g = new java.util.ArrayList<KeyStore.ApiKey>(); groups.put(plat, g); }
            g.add(k);
        }

        /* 按用户排好的顺序重排分组。LinkedHashMap 的迭代顺序就是后面的绘制顺序，
           而 Sequence 的顺序决定图例和折线的前后，所以这里重排就够了。 */
        java.util.List<String> platIds =
                OrderStore.apply(this, OrderStore.STATS, new java.util.ArrayList<String>(groups.keySet()));
        if (platIds.size() == groups.size()) {
            java.util.LinkedHashMap<String, java.util.List<KeyStore.ApiKey>> sorted =
                    new java.util.LinkedHashMap<String, java.util.List<KeyStore.ApiKey>>();
            for (int i = 0; i < platIds.size(); i++) sorted.put(platIds.get(i), groups.get(platIds.get(i)));
            groups = sorted;
        }

        /* 登录型平台（小米 MiMo）：凭据是账号会话音，KeyStore 里可能一条 Key 都没有，
           但卡片和账本里其实是有它的数据的（fetch 补位时用平台 id 当条目 id）。
           以前只遍历 KeyStore，结果就是「卡片上有余额、统计页却没这条线」。 */
        for (int i = 0; i < BalanceFetcher.PRESETS.length; i++) {
            BalanceFetcher.Preset p = BalanceFetcher.PRESETS[i];
            if (!"balance".equals(p.kind)) continue;
            if (groups.containsKey(p.id)) continue;
            if (!BalanceFetcher.platformUsable(this, p.id)) continue;
            groups.put(p.id, new java.util.ArrayList<KeyStore.ApiKey>());
        }

        java.util.List<UsageChartView.Series> series =
                new java.util.ArrayList<UsageChartView.Series>();
        platformColor.clear();
        platformDraw.clear();
        double[] consDay = new double[DAYS];
        double[] totalBal = new double[DAYS];
        java.util.Arrays.fill(totalBal, Double.NaN);
        StringBuilder detail = new StringBuilder();
        double totalCons = 0;
        double totalCharged = 0;
        int balIdx = 0;

        for (java.util.Iterator<java.util.Map.Entry<String, java.util.List<KeyStore.ApiKey>>> it =
                groups.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<String, java.util.List<KeyStore.ApiKey>> e = it.next();
            String plat = e.getKey();
            java.util.List<KeyStore.ApiKey> ks = e.getValue();
            BalanceFetcher.Preset p = BalanceFetcher.presetOf(plat);
            String kind = p == null ? "balance" : p.kind;
            boolean usd = p != null && "USD".equals(p.unit);
            double mul = usd ? rate : 1.0;
            String name = p == null ? plat : p.name;
            if (p == null && plat.startsWith("custom")) {
                /* 自定义平台没有 Preset，名字得去 custom_json 里找 —— 否则图例上写的是 "custom:0" */
                try {
                    String n = plat.startsWith("custom:") ? plat.substring(7) : plat.substring(6);
                    int ci = Integer.parseInt(n);
                    java.util.List<BalanceFetcher.Custom> cs0 = BalanceFetcher.loadCustom(this);
                    if (ci >= 0 && ci < cs0.size() && cs0.get(ci).name != null
                            && cs0.get(ci).name.length() > 0) name = cs0.get(ci).name;
                } catch (Throwable ignored) { }
            }

            boolean anyDraw = false;
            for (int i = 0; i < ks.size(); i++) if (ks.get(i).draw) anyDraw = true;
            platformDraw.put(plat, Boolean.valueOf(anyDraw));

            if (!"balance".equals(kind)) {
                // 免费/无余额接口的平台：贴 0 直线，开关有可见效果
                if (anyDraw) {
                    int zc = CHART_PALETTE[balIdx % CHART_PALETTE.length];
                    platformColor.put(plat, Integer.valueOf(zc));
                    series.add(new UsageChartView.Series(zc, new double[DAYS], name, false));
                    detail.append(name).append("   无数据 · 查看控制台\n");
                    balIdx++;
                }
                continue;
            }

            // 聚合该平台所有 Key（同一账户多密钥余额相同 → 取最大值，避免重复计算）
            double[] own = new double[DAYS];
            java.util.Arrays.fill(own, Double.NaN);
            double[] dayCons = new double[DAYS];      // 平台每日消耗（跨 Key 取最大）
            java.util.Arrays.fill(dayCons, Double.NaN);
            int[] cnt = new int[DAYS];
            double platCons = 0, platCharged = 0;
            /* 组里没有 Key（MiMo 这种靠登录态的）就拿平台 id 当账本 key —— 与 fetch 补位时
               写入的快照 id 一致。以前这里直接跳过，所以新平台一条线都出不来。 */
            int srcN = ks.isEmpty() ? 1 : ks.size();
            int ptsAny = 0;                 // 只要有 1 个点就出线（至少能看到最新余额）
            for (int i = 0; i < srcN; i++) {
                String kid = ks.isEmpty() ? plat : ks.get(i).id;
                /* 历史遗留：早期版本原始条目被别名改写，快照按**平台 id** 记过一批。
                   合并查询两个来源 —— 只在 Key id 查会漏掉那批老数据
                   （用户反馈的"MiMo 比上一版少了两个点"正是这么来的）。 */
                String[] kids = (ks.size() <= 1 && !plat.equals(kid))
                        ? new String[]{kid, plat} : new String[]{kid};
                java.util.List<Ledger.Point> pts = lg.series(this, kids, from);
                BalanceFetcher.diag(this, "  stats 查账本 " + plat
                        + " kid=" + kid + " 点数=" + pts.size());
                /* 首轮构建时把原始点序列打出来 —— 判断"某值没画出来"
                   到底是聚合吃掉还是采样没覆盖（只打首轮，免得刷屏）。 */
                if (diagThisRound) {
                    BalanceFetcher.diag(this, "  点明细 " + plat + "  "
                            + lg.dumpPoints(this, kids, from, 40));
                }
                if (pts.size() > 0) ptsAny++;
                double kCons = 0, kCharged = 0;
                double[] kDay = new double[DAYS];
                double[] kOwn = new double[DAYS];          // 该 Key 在各桶的最新余额
                java.util.Arrays.fill(kOwn, Double.NaN);
                boolean[] hasDay = new boolean[DAYS];
                for (int j = 0; j < pts.size(); j++) {
                    Ledger.Point pt = pts.get(j);
                    int idx = DAYS - 1 - (int) ((now - pt.ts) / SLOT_MS);
                    if (idx < 0 || idx >= DAYS) continue;
                    /* ★ 桶内取**最新**值，不是最大值。
                       pts 是按时间升序的，直接覆盖即可拿到最新。
                       原实现用 Math.max —— 余额一路下降时（正常消耗），
                       取到的反而是这个桶里最早、最高的那个值，曲线会虚高。
                       （6 小时采样时每桶基本只有一个点，问题看不出来；
                       采样加密之后就会明显失真。） */
                    kOwn[idx] = pt.balance * mul;
                    kDay[idx] += pt.consumed * mul;
                    hasDay[idx] = true;
                    kCons += pt.consumed * mul;
                    kCharged += pt.charged * mul;
                }
                /* 各 Key 的桶值合并（同平台多密钥余额相同，取最大避免重复累计） */
                for (int d = 0; d < DAYS; d++) {
                    if (!Double.isNaN(kOwn[d])) {
                        own[d] = Double.isNaN(own[d]) ? kOwn[d] : Math.max(own[d], kOwn[d]);
                        cnt[d]++;
                    }
                }
                platCons = Math.max(platCons, kCons);
                platCharged = Math.max(platCharged, kCharged);
                for (int d = 0; d < DAYS; d++)
                    if (hasDay[d])
                        dayCons[d] = Double.isNaN(dayCons[d]) ? kDay[d] : Math.max(dayCons[d], kDay[d]);
            }
            if (ptsAny == 0) {
                if (anyDraw) {
                    /* MiMo 要分两种"没数据"：
                       cookie 为空 = 从没登录过；cookie 在、服务端 401 = 登录已过期。
                       老代码只判断前者 —— 于是过期会话被显示成"数据积累中"，
                       用户完全看不出真正该做的是重新登录一次。 */
                    boolean mimoExpired = "mimo".equals(plat)
                            && BalanceFetcher.mimoExpired(this);
                    boolean needLogin = "mimo".equals(plat)
                            && BalanceFetcher.mimoSession(this).length() == 0;
                    /* 没历史数据也别留白 —— 把「当前余额」当成最新那一个点打上去。
                       否则新加的平台在图上什么都没有，用户根本分不清
                       「平台没加进来」和「数据还在积累」。 */
                    double live = liveBalanceOf(liveItems, plat, ks);
                    double[] empty = new double[DAYS];
                    java.util.Arrays.fill(empty, Double.NaN);
                    if (!Double.isNaN(live)) {
                        empty[DAYS - 1] = live * mul;      // 最新一个槽位 = 现在
                        detail.append(name).append("   当前 ").append(String.format("%.2f", live * mul))
                              .append("   历史数据积累中\n");
                    } else {
                        detail.append(name).append(mimoExpired
                                ? "   登录已过期 · 点卡片重新登录\n"
                                : (needLogin
                                        ? "   未登录 · 点卡片登录后开始积累\n"
                                        : "   数据积累中 · 暂无快照\n"));
                    }
                    int zc = CHART_PALETTE[balIdx % CHART_PALETTE.length];
                    platformColor.put(plat, Integer.valueOf(zc));
                    series.add(new UsageChartView.Series(zc, empty, name, false));
                    if (!Double.isNaN(empty[DAYS - 1])) {
                        totalBal[DAYS - 1] = Double.isNaN(totalBal[DAYS - 1])
                                ? empty[DAYS - 1] : totalBal[DAYS - 1] + empty[DAYS - 1];
                    }
                    balIdx++;
                }
                continue;
            }
            for (int d = 0; d < DAYS; d++)
                if (cnt[d] == 0) own[d] = (d > 0 && !Double.isNaN(own[d - 1])) ? own[d - 1] : Double.NaN;

            /* 有历史但最新那个槽位还空着（恰好还没到采样点）→ 用当前余额补上，
               不然用户点进来会觉得「刚才的余额没进去」。 */
            if (Double.isNaN(own[DAYS - 1])) {
                double live = liveBalanceOf(liveItems, plat, ks);
                if (!Double.isNaN(live)) own[DAYS - 1] = live * mul;
            }

            totalCons += platCons;
            totalCharged += platCharged;
            if (anyDraw) {
                for (int d = 0; d < DAYS; d++)
                    if (!Double.isNaN(dayCons[d])) consDay[d] += dayCons[d];
                detail.append(name).append("   充值 ¥").append(String.format("%.2f", platCharged))
                      .append("   消耗 ¥").append(String.format("%.2f", platCons)).append("\n");
                int lineColor = CHART_PALETTE[balIdx % CHART_PALETTE.length];
                platformColor.put(plat, Integer.valueOf(lineColor));
                series.add(new UsageChartView.Series(lineColor, own, name, false));
                for (int d = 0; d < DAYS; d++)
                    if (!Double.isNaN(own[d]))
                        totalBal[d] = Double.isNaN(totalBal[d]) ? own[d] : totalBal[d] + own[d];
            }
            balIdx++;
        }
        /* 账本明细：排查"少点/没线"时一眼看出数据记在哪个 id 下 */
        BalanceFetcher.diag(this, "账本明细 " + lg.dumpKeys(this));
        BalanceFetcher.diag(this, "stats 构建: 平台组=" + groups.size() + " 折线=" + series.size()
                + " 明细长=" + detail.length() + " aks=" + aks.size());
        UsageChartView chart = (UsageChartView) findViewById(R.id.usage_chart);
        chart.setData(series, showBars ? consDay : null, labels, showBars, showGrid, showLegend);
        statsSeries = series;
        statsLabels = labels;
        // 范围标签 / 下拉按钮文字
        TextView rl = (TextView) findViewById(R.id.stats_range_label);
        if (rl != null) rl.setText("近 " + statsDays + " 日（" + slotLabel(SLOT_MS) + "/点）");
        /* 采样精度声明：曲线是"按时段聚合的近似值"，不是逐笔流水。
           充值/消费的准确数字以各平台后台为准 —— 说清楚比让用户猜好。 */
        TextView note = (TextView) findViewById(R.id.stats_sampling_note);
        if (note != null) {
            note.setText(getString(R.string.stats_sampling_note, slotLabel(SLOT_MS)));
        }
        TextView rp = (TextView) findViewById(R.id.range_pick);
        if (rp != null) rp.setText("近 " + statsDays + " 天 ▽");

        // 每平台充值/消耗明细
        LinearLayout dbox = (LinearLayout) findViewById(R.id.stats_detail);
        if (dbox != null) {
            dbox.removeAllViews();
            String[] lines = detail.toString().split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].length() == 0) continue;
                TextView row = new TextView(this);
                row.setText(lines[i]);
                row.setTextColor(getColor(R.color.tx));
                row.setTextSize(13);
                row.setPadding(0, dp(5), 0, dp(5));
                dbox.addView(row);
            }
        }
        // 总充值 / 总消耗
        TextView tt = (TextView) findViewById(R.id.stats_totals);
        if (tt != null) tt.setText("总充值 ¥" + String.format("%.2f", totalCharged)
                + "    总消耗 ¥" + String.format("%.2f", totalCons));

        if (rebuildToggles) buildPlatformToggles(aks);
    }

    /** 每个 API 一个 Switch 开关数据源；总计单独一个 Switch。切换后重绘图表。 */
    /** 统计页数据源开关：按「平台」一行（同平台多 Key 一起开/关） */
    private void buildPlatformToggles(java.util.List<KeyStore.ApiKey> aks) {
        LinearLayout box = (LinearLayout) findViewById(R.id.stats_platforms);
        if (box == null) return;
        box.removeAllViews();

        /* 隐藏项先收进这个容器（稍后整体塞进折叠区）；可见项直接进 box */
        final LinearLayout hiddenContainer = new LinearLayout(this);
        hiddenContainer.setOrientation(LinearLayout.VERTICAL);

        /* 分组口径必须和 buildStats 一致：只用 KeyStore 的话，
           登录型平台（MiMo 没有 Key）在图表里有线、开关列表里却没它，对不上。 */
        java.util.LinkedHashMap<String, java.util.List<KeyStore.ApiKey>> groups =
                new java.util.LinkedHashMap<String, java.util.List<KeyStore.ApiKey>>();
        for (int i = 0; i < aks.size(); i++) {
            KeyStore.ApiKey k = aks.get(i);
            /* 未配置 Key 的平台也照收 —— 只要它"有办法取数"。
               MiMo 正是这种：凭据是登录会话音、不是 API Key。老代码把它挡在分组外，
               统计页就只能拿**平台 id** 去查账本，而账本按 **Key id** 记账
               → 怎么查都是空（这就是"小米 MiMo 没有任何数据"的真因）。
               让它进组之后，查询用的 kid 才会等于账本里的 key id。 */
            if (!k.isConfigured() && !BalanceFetcher.platformUsable(this,
                    k.platform == null ? "" : k.platform)) continue;
            String plat = k.platform == null ? "" : k.platform;
            java.util.List<KeyStore.ApiKey> g = groups.get(plat);
            if (g == null) { g = new java.util.ArrayList<KeyStore.ApiKey>(); groups.put(plat, g); }
            g.add(k);
        }
        for (int i = 0; i < BalanceFetcher.PRESETS.length; i++) {
            BalanceFetcher.Preset pp = BalanceFetcher.PRESETS[i];
            if (!"balance".equals(pp.kind)) continue;
            if (groups.containsKey(pp.id)) continue;
            if (!BalanceFetcher.platformUsable(this, pp.id)) continue;
            groups.put(pp.id, new java.util.ArrayList<KeyStore.ApiKey>());
        }
        /* 顺序沿用用户在统计页排好的那套 */
        java.util.List<String> orderIds = OrderStore.apply(this, OrderStore.STATS,
                new java.util.ArrayList<String>(groups.keySet()));
        if (orderIds.size() == groups.size()) {
            java.util.LinkedHashMap<String, java.util.List<KeyStore.ApiKey>> sortedGroups =
                    new java.util.LinkedHashMap<String, java.util.List<KeyStore.ApiKey>>();
            for (int i = 0; i < orderIds.size(); i++) {
                sortedGroups.put(orderIds.get(i), groups.get(orderIds.get(i)));
            }
            groups = sortedGroups;
        }

        /* 隐藏的（draw=false）平台不铺在主列表里，收进下面那块折叠区 ——
           关掉的 API 通常会越来越多，全摊开的话主列表会被淹没。 */
        final java.util.List<String> hiddenIds = new java.util.ArrayList<String>();
        /* 匿名内部类要用，得是 final（groups 后面还有可能被重排赋值） */
        final java.util.LinkedHashMap<String, java.util.List<KeyStore.ApiKey>> gmap = groups;

        for (java.util.Iterator<java.util.Map.Entry<String, java.util.List<KeyStore.ApiKey>>> it =
                groups.entrySet().iterator(); it.hasNext(); ) {
            final java.util.Map.Entry<String, java.util.List<KeyStore.ApiKey>> e = it.next();
            final String plat = e.getKey();
            final java.util.List<KeyStore.ApiKey> ks = e.getValue();
            BalanceFetcher.Preset p = BalanceFetcher.presetOf(plat);
            String name = p == null ? plat : p.name;
            if (p == null && plat.startsWith("custom")) {
                try {
                    String n0 = plat.startsWith("custom:") ? plat.substring(7) : plat.substring(6);
                    int ci0 = Integer.parseInt(n0);
                    java.util.List<BalanceFetcher.Custom> cs1 = BalanceFetcher.loadCustom(this);
                    if (ci0 >= 0 && ci0 < cs1.size() && cs1.get(ci0).name != null
                            && cs1.get(ci0).name.length() > 0) name = cs1.get(ci0).name;
                } catch (Throwable ignored) { }
            }
            final String finalName = name;
            Boolean dr = platformDraw.get(plat);
            final boolean on0 = dr != null && dr.booleanValue();
            if (!on0) hiddenIds.add(plat);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(6), dp(6), dp(6));
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable gd =
                    new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            Integer lc = platformColor.get(plat);
            gd.setColor(lc != null ? lc.intValue() : 0xFF9AA3B0);
            dot.setBackground(gd);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(14), dp(14));
            dlp.rightMargin = dp(12);
            dot.setLayoutParams(dlp);
            row.addView(dot);

            android.widget.Switch sw = new android.widget.Switch(this);
            sw.setText(name);
            sw.setTextColor(getColor(R.color.tx));
            sw.setTextSize(13);
            sw.setChecked(on0);
            sw.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][] { { android.R.attr.state_checked }, { -android.R.attr.state_checked } },
                    new int[] { 0xFF3D6FD6, 0xFFC6CDD6 }));
            sw.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            sw.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    // 平台级开关：把该平台下所有 Key 一起设置（写回前取最新对象，防覆盖其他字段）
                    for (int i = 0; i < ks.size(); i++) {
                        KeyStore.ApiKey fresh = KeyStore.byId(MainActivity.this, ks.get(i).id);
                        KeyStore.ApiKey t = fresh != null ? fresh : ks.get(i);
                        t.draw = on;
                        KeyStore.update(MainActivity.this, t);
                    }
                    platformDraw.put(plat, Boolean.valueOf(on));
                    Integer nc = platformColor.get(plat);
                    gd.setColor(on && nc != null ? nc.intValue() : 0xFF9AA3B0);
                    dot.invalidate();
                    /* 图表先按新状态重算（不重建开关列表，免得把正在拨的这个 Switch 拆了）；
                       再把开关列表的重建推到下一帧 —— 因为这一行可能要换组：
                       关掉 → 收进「已隐藏的 API」，打开 → 回到主列表，计数也得跟着变。 */
                    buildStats(false);
                    box.post(new Runnable() {
                        public void run() { buildStats(true); }
                    });
                }
            });
            row.addView(sw);
            /* 长按行（非开关区）进排序；开关本身的长按不吃，免得误触 */
            row.setOnLongClickListener(new android.view.View.OnLongClickListener() {
                public boolean onLongClick(android.view.View v) {
                    java.util.List<String> ids = new java.util.ArrayList<String>(gmap.keySet());
                    java.util.List<String> names = new java.util.ArrayList<String>();
                    for (int i = 0; i < ids.size(); i++) names.add(labelOfPlatform(ids.get(i)));
                    showStatsOrder(ids, names);
                    return true;
                }
            });
            if (on0) box.addView(row); else hiddenContainer.addView(row);
        }

        /* 隐藏的 API 折叠区：标题可点，展开后才列出 —— 主列表保持干净 */
        if (!hiddenIds.isEmpty()) {
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setPadding(dp(2), dp(10), dp(2), 0);

            final TextView head = new TextView(this);
            head.setTextSize(12.5f);
            head.setTextColor(getColor(R.color.tx3));
            head.setPadding(dp(6), dp(8), dp(6), dp(8));
            head.setBackgroundResource(R.drawable.mini_btn_border);
            wrap.addView(head);

            final boolean[] open = { getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE)
                    .getBoolean("stats_hidden_open", false) };
            final int hn = hiddenIds.size();
            final Runnable sync = new Runnable() {
                public void run() {
                    head.setText((open[0] ? "已隐藏的 API (" + hn + ")  ▴"
                                          : "已隐藏的 API (" + hn + ")  ▾"));
                    hiddenContainer.setVisibility(open[0] ? android.view.View.VISIBLE
                                                          : android.view.View.GONE);
                }
            };
            sync.run();
            head.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    open[0] = !open[0];
                    sync.run();
                    getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE)
                            .edit().putBoolean("stats_hidden_open", open[0]).apply();
                }
            });
            wrap.addView(hiddenContainer);
            box.addView(wrap);
        }

        /* 长按任意一行 → 调整统计顺序（图表折线顺序跟着变） */
        final java.util.List<String> allIds = new java.util.ArrayList<String>(groups.keySet());
        final java.util.List<String> allNames = new java.util.ArrayList<String>();
        for (int i = 0; i < allIds.size(); i++) allNames.add(labelOfPlatform(allIds.get(i)));
        hideHintRow(box, allIds, allNames);
    }

    /**
     * 取某平台「当前余额」（原币种，未折算）。卡片同平台多 Key 是取最大值，
     * 这里沿用同一口径，免得图上这个点跟卡片上的数字对不上。
     * 没有当前数据（未登录 / 上一轮失败）返回 NaN。
     */
    private double liveBalanceOf(java.util.List<BalanceFetcher.Item> liveItems,
                                 String plat, java.util.List<KeyStore.ApiKey> ks) {
        double best = Double.NaN;
        for (int i = 0; i < liveItems.size(); i++) {
            BalanceFetcher.Item it = liveItems.get(i);
            if (it.noData || !it.ok) continue;
            if (!"balance".equals(it.kind)) continue;
            if (!plat.equals(it.platform)) continue;
            if (Double.isNaN(best) || it.bal > best) best = it.bal;
        }
        return best;
    }

    /** 平台显示名（自定义平台去 custom_json 里取，别露出 custom:0） */
    private String labelOfPlatform(String plat) {
        BalanceFetcher.Preset p = BalanceFetcher.presetOf(plat);
        if (p != null) return p.name;
        if (plat != null && plat.startsWith("custom")) {
            try {
                String n = plat.startsWith("custom:") ? plat.substring(7) : plat.substring(6);
                int ci = Integer.parseInt(n);
                java.util.List<BalanceFetcher.Custom> cs = BalanceFetcher.loadCustom(this);
                if (ci >= 0 && ci < cs.size() && cs.get(ci).name != null
                        && cs.get(ci).name.length() > 0) return cs.get(ci).name;
            } catch (Throwable ignored) { }
        }
        return plat == null ? "" : plat;
    }

    /** 底部提示行：长按可排序（点它也能进排序，省得去猜手势） */
    private void hideHintRow(LinearLayout box, final java.util.List<String> ids,
                             final java.util.List<String> names) {
        if (ids == null || ids.size() < 2) return;      // 一项都没有就别摆提示
        TextView hint = new TextView(this);
        hint.setText("长按任意一行可调整顺序");
        hint.setTextSize(11.5f);
        hint.setTextColor(getColor(R.color.tx3));
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setPadding(dp(6), dp(10), dp(6), dp(4));
        hint.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) { showStatsOrder(ids, names); }
        });
        box.addView(hint);
    }

    /** 刘海 / 状态栏 / 手势条适配（与设置界面共用同一套逻辑） */
    private void applyWindowInsets() {
        UiInsets.apply(this, R.id.app_header, 12, 20,
                R.id.scroll_api, R.id.scroll_stats, R.id.scroll_vpn, R.id.scroll_settings);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        /* singleTask 模式下应用已在运行时会走到这里，而不是重新 onCreate */
        setIntent(intent);
        handleImportIntent(intent);
    }

    /** 处理 clash://install-config 导入请求 */
    private void handleImportIntent(Intent it) {
        if (!ClashImport.isImport(it)) return;
        /* 弹确认框的同时切到加速页 —— 导入完成后用户正好能看见节点在下载 */
        showPanel(indexOfPage(PageStore.K_VPN));
        ClashImport.handle(this, it, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 放在 onResume：界面已可见，避免弹窗盖在未初始化的界面上，
        // 也避免用户从后台返回时错过提示。内部有"问过就不再弹"的闸门。
        NotifyPermission.ensure(this, null, false);
        // 从 MiMo 登录页回来时，把「登录状态」重画一遍（面板已构建才需要）
        if (settingsBinder != null && settingsPanelBuilt) {
            try { settingsBinder.refreshKeys(); } catch (Throwable ignored) { }
        }
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

    /**
     * 下拉刷新：API 余额页与统计页共用。
     * 设置页没什么好刷的，所以切到设置面板时直接关掉下拉。
     */
    private void bindPullToRefresh() {
        applyPullListeners();
    }

    /**
     * 给当前所有**可见**页面挂上下拉刷新监听。
     *
     * 必须能在 rebuildNav() 之后**重复调用** —— 页面重排/隐藏后 scrolls 数组变了，
     * 只在 onCreate 绑一次的话，新换进来的页面就没有下拉刷新。
     * 而且这样还能天然实现「只有当前页能下拉」：下面每次都被重新设一遍。
     */
    private void applyPullListeners() {
        if (scrolls == null) return;
        View box = findViewById(R.id.pull_box);
        TextView label = (TextView) findViewById(R.id.pull_text);
        for (int i = 0; i < scrolls.length; i++) {
            final PullScrollView ps = scrolls[i];
            if (ps == null) continue;
            /* 所有页共用同一个指示器胶囊 —— 同一时刻只可能有一页在下拉，不会打架。
               它按容器 paddingTop（= 顶部栏高度）定位，所以永远贴在顶部栏正下方。 */
            ps.attachIndicator(box, label);
            ps.setListener(new PullScrollView.Listener() {
                public void onPull(float progress) { }
                public void onCancel() { }
                public void onRefresh() {
                    ps.setRefreshing(true);
                    /* 正好有自动刷新在跑：不用另起一次，它收尾时会把指示器收掉，
                       数据也才刚刚拉过，没必要重复请求。 */
                    if (loading) return;
                    /* 只在会联网的两页有意义；当前页是统计页时刷完还要重算图表 */
                    String k = curKey;
                    if (!PageStore.K_API.equals(k) && !PageStore.K_STATS.equals(k)) return;
                    statsRebuildAfterRefresh = PageStore.K_STATS.equals(k);
                    refresh(false);
                }
            });
        }
    }

    /** 读文本文件（订阅配置），没有就返回空串 */
    private static String readText(java.io.File f) {
        try {
            if (!f.exists()) return "";
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int off = 0;
                while (off < buf.length) {
                    int r = in.read(buf, off, buf.length - off);
                    if (r <= 0) break;
                    off += r;
                }
            } finally { in.close(); }
            return new String(buf, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private void refresh() { refresh(false); }

    /**
     * @param silent true = 自动刷新，不显示"正在查询"（避免每 5 分钟闪一下，也不打断阅读）
     */
    private void refresh(final boolean silent) {
        if (loading) return;
        loading = true;
        if (!silent) {
            tSub.setText(R.string.refreshing);
            tSub.setTextColor(getColor(R.color.tx3));
        }

        final Context ctx = this;
        new Thread(new Runnable() {
            public void run() {
                final BalanceFetcher.Result r = BalanceFetcher.fetch(ctx, 12000);
                // 到点在账本里落一条快照：这样即使桌面没摆小组件，统计页也有曲线
                BalanceFetcher.sampleIfDue(ctx, r);
                // 判定阈值并发通知（内部有防重复，重复调用安全）
                Alert.check(ctx, r);
                runOnUiThread(new Runnable() {
                    public void run() {
                        /* Activity 可能已经 finish/销毁（用户退出、转屏重建）——
                           这时再碰 UI 轻则无效、重则崩。老代码缺这道检查，
                           而抓取要跑好几秒，退出得刚好就会撞上。 */
                        if (isFinishing() || isDestroyed()) return;
                        loading = false;
                        render(r);
                        if (statsRebuildAfterRefresh && curPanel == 1) {
                            statsRebuildAfterRefresh = false;
                            buildStats();
                        }
                        hidePullRefresh();
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
                        if (isFinishing() || isDestroyed()) return;
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

    private BalanceFetcher.Result lastResult;

    /** 隐藏开关改动后：先按本地 KeyStore 立即过滤已显示卡片，再后台刷新（即时反馈） */
    private void applyHideLocally() {
        if (lastResult == null) return;
        java.util.HashMap<String, Boolean> hid = new java.util.HashMap<String, Boolean>();
        java.util.List<KeyStore.ApiKey> aks = KeyStore.all(this);
        for (int i = 0; i < aks.size(); i++) hid.put(aks.get(i).id, Boolean.valueOf(aks.get(i).hideCard));
        for (int i = lastResult.items.size() - 1; i >= 0; i--) {
            BalanceFetcher.Item it = lastResult.items.get(i);
            Boolean h = hid.get(it.id);
            if (h != null && h.booleanValue()) lastResult.items.remove(i);
        }
        render(lastResult);
    }

    private void render(BalanceFetcher.Result r) {
        lastResult = r;
        cards.removeAllViews();
        BalanceFetcher.diag(this, "卡片渲染 " + r.items.size() + " 项");

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
        /* 按用户排好的顺序渲染（没排过就是原始顺序） */
        final java.util.List<BalanceFetcher.Item> ordered =
                OrderStore.applyItems(this, OrderStore.CARDS, r.items);
        for (int i = 0; i < ordered.size(); i++) {
            BalanceFetcher.Item it = ordered.get(i);
            final BalanceFetcher.Item fi = it;
            View v = inf.inflate(R.layout.item_platform, cards, false);
            /* 点卡片 = 弹出菜单：刷新这一项 / 看密钥（看密钥要先过密码） */
            v.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v2) {
                    /* MiMo 是登录型平台：登录态失效时，菜单里必须先给"重新登录"。
                       否则用户点开只有「刷新/看密钥/控制台」，
                       根本看不出该做什么才能把这个平台救回来。 */
                    if ("mimo".equals(fi.platform)
                            && BalanceFetcher.mimoNeedsRelogin(MainActivity.this)) {
                        LockDialog.choose(MainActivity.this, fi.label,
                            new String[] { "重新登录小米账号", "刷新", "控制台", "充值" },
                            new LockDialog.OnPick() {
                                public void pick(int which) {
                                    if (which == 0) {
                                        startActivity(new Intent(MainActivity.this,
                                                MimoLoginActivity.class));
                                    } else if (which == 1) {
                                        refreshOne(fi);
                                    } else if (which == 2) {
                                        openSiteMenu(fi);
                                    } else {
                                        openTopup(fi);
                                    }
                                }
                            });
                        return;
                    }
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
                                    final String plat = fi.platform;
                                    final String platName = fi.label;
                                    LockDialog.ask(MainActivity.this,
                                        "查看「" + platName + "」的全部密钥",
                                        new LockDialog.OnPass() {
                                            public void ok() {
                                                // 显示该平台下的所有 Key（而不只是卡片对应那一个）
                                                java.util.List<KeyStore.ApiKey> mine =
                                                        KeyStore.get(MainActivity.this, plat);
                                                StringBuilder sb = new StringBuilder();
                                                for (int i = 0; i < mine.size(); i++) {
                                                    KeyStore.ApiKey kk = mine.get(i);
                                                    String lb = (kk.label == null || kk.label.length() == 0)
                                                            ? "默认" : kk.label;
                                                    sb.append(lb)
                                                      .append(kk.isConfigured() ? "：" + kk.key : "：（未填）")
                                                      .append("\n\n");
                                                }
                                                if (sb.length() == 0) sb.append("该平台下还没有 Key");
                                                LockDialog.showKey(MainActivity.this,
                                                    platName + " 的密钥（共 " + mine.size() + " 条）",
                                                    sb.toString().trim());
                                            }
                                        });
                                }
                            }
                        });
                }
            });

            /* 长按卡片 → 排序模式（▲▼ 调顺序，记住不放） */
            v.setOnLongClickListener(new android.view.View.OnLongClickListener() {
                public boolean onLongClick(View v2) {
                    showCardOrder();
                    return true;
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

    /** 长按卡片：进入卡片排序 */
    private void showCardOrder() {
        final BalanceFetcher.Result r = lastResult != null ? lastResult : WidgetCache.read(this);
        if (r == null || r.items == null || r.items.size() < 2) {
            android.widget.Toast.makeText(this, "只有一项，不用排序",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<BalanceFetcher.Item> ordered =
                OrderStore.applyItems(this, OrderStore.CARDS, r.items);
        java.util.List<String> ids = new java.util.ArrayList<String>();
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (int i = 0; i < ordered.size(); i++) {
            ids.add(ordered.get(i).platform);
            names.add(ordered.get(i).label);
        }
        OrderDialog.show(this, OrderStore.CARDS, "调整卡片顺序", ids, names,
                new Runnable() {
                    public void run() {
                        if (lastResult != null) render(lastResult);
                    }
                });
    }

    /** 长按统计页平台行：进入折线 / 开关排序 */
    private void showStatsOrder(final java.util.List<String> ids,
                                final java.util.List<String> names) {
        OrderDialog.show(this, OrderStore.STATS, "调整统计顺序", ids, names,
                new Runnable() {
                    public void run() { buildStats(); }
                });
    }
}
