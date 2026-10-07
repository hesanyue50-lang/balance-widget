package com.minis.balancewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 设置主体绑定器：把「设置」UI 绑定到任意 Activity 的 root 上。
 * 主界面第三面板与独立设置页共用同一份布局与逻辑，保证「设置」与其他页同层。
 */
public class SettingsBinder {

    private final Activity act;
    private final View root;
    private EditText eFg, eBg;
    private final Handler h = new Handler();
    private final Runnable saveTask = new Runnable() {
        public void run() { saveIntervals(); }
    };

    private final Runnable onChanged;

    public SettingsBinder(Activity a, View r) { this(a, r, null); }

    /** 页面顺序/隐藏/主页面 改动后的回调（宿主重建导航条） */
    public Runnable onPagesChanged;

    /**
     * 最近一次绑定的实例。
     *
     * <b>为什么要有</b>：备份走的是系统文件选择器（另一个 Activity），
     * 备份完成回到本应用时，设置面板并不知道"刚才多了一份备份"，
     * 于是列表还是旧的（用户看到"0 份"，其实文件已经存下了）。
     * 借这个静态引用回调一下即可。
     *
     * 生命周期由宿主负责：Activity onDestroy 时置空，避免拖住已销毁的实例。
     */
    public static SettingsBinder active;

    public SettingsBinder(Activity a, View r, Runnable onChanged) {
        this.act = a; this.root = r; this.onChanged = onChanged;
    }

    private int dp(float v) { return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f); }
    private int color(int id) { return act.getColor(id); }
    private SharedPreferences prefs() {
        return act.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
    }

    public void bind() {
        active = this;

        // ---- 刷新间隔（修改即保存） ----
        eFg = (EditText) root.findViewById(R.id.e_fg_min);
        eBg = (EditText) root.findViewById(R.id.e_bg_min);
        if (eFg != null) eFg.setText(String.valueOf(RefreshScheduler.fgMinutes(act)));
        /* 填**用户设定值**而非托底后的值，否则保存时会把托底值写回去 */
        if (eBg != null) eBg.setText(String.valueOf(RefreshScheduler.bgMinutesRaw(act)));
        android.text.TextWatcher w = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                h.removeCallbacks(saveTask);
                h.postDelayed(saveTask, 500);
            }
            public void afterTextChanged(android.text.Editable s) { }
        };
        if (eFg != null) eFg.addTextChangedListener(w);
        if (eBg != null) eBg.addTextChangedListener(w);

        /* 提示里的"最小 N 分钟"从常量生成 —— 硬编码的话改了最小值文案就对不上 */
        TextView hf = (TextView) root.findViewById(R.id.hint_fg);
        if (hf != null) {
            hf.setText(act.getString(R.string.hint_refresh_fg, RefreshScheduler.FG_MIN));
        }
        TextView hb = (TextView) root.findViewById(R.id.hint_bg);
        if (hb != null) {
            hb.setText(act.getString(R.string.hint_refresh_bg, RefreshScheduler.BG_MIN));
        }

        /* 失焦时把越界的输入收敛到合法范围。
           为什么用"失焦"而不是"边打字边纠正"：
           用户想输 20，刚敲下 "2"（小于后台最小值 5）就被改成 5，
           接着再敲 "0" 就变成 50 —— 边打字边纠正一定会打架。
           失焦时用户已经表达完意图，这时收敛既准确又不打断输入。 */
        android.view.View.OnFocusChangeListener fc =
                new android.view.View.OnFocusChangeListener() {
                    public void onFocusChange(View v, boolean hasFocus) {
                        if (hasFocus || !(v instanceof EditText)) return;
                        int min = (v.getId() == R.id.e_bg_min)
                                ? RefreshScheduler.BG_MIN : RefreshScheduler.FG_MIN;
                        int fixed = clampInterval((EditText) v, min);
                        if (!String.valueOf(fixed).equals(
                                ((EditText) v).getText().toString().trim())) {
                            ((EditText) v).setText(String.valueOf(fixed));
                        }
                    }
                };
        if (eFg != null) eFg.setOnFocusChangeListener(fc);
        if (eBg != null) eBg.setOnFocusChangeListener(fc);

        View autostart = root.findViewById(R.id.btn_autostart);
        if (autostart != null) autostart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { Autostart.ensure(act, true); }
        });

        /* 电池优化白名单单独给一个入口。
           和"自启动"是两套独立机制：自启动决定闹钟能不能唤醒本应用（国产 ROM 特有），
           电池优化白名单决定 Doze 深度休眠时闹钟会不会被大幅推迟（Android 原生）。
           之前只引导了自启动，实测这台机器就是"自启动已给、电池优化没进" ——
           闹钟虽然排上了，却带着 15 分钟窗口。 */
        View battery = root.findViewById(R.id.btn_battery);
        if (battery != null) {
            battery.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (Autostart.isIgnoringBattery(act)) {
                        android.widget.Toast.makeText(act,
                                "已在电池优化白名单中，后台刷新不会被 Doze 推迟",
                                android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        Autostart.requestIgnoreBattery(act);
                    }
                }
            });
        }

        /* 省电模式 */
        final TextView ps = (TextView) root.findViewById(R.id.btn_power_save);
        if (ps != null) {
            renderPowerSave(ps, act);
            ps.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean on = !RefreshScheduler.powerSave(act);
                    RefreshScheduler.setPowerSave(act, on);
                    renderPowerSave(ps, act);
                    /* 立刻按新间隔重排闹钟 —— 已排上的那个还是旧间隔，
                       不重排的话要等它触发一次才生效。 */
                    RefreshScheduler.schedule(act);
                    if (on) {
                        android.widget.Toast.makeText(act,
                                "省电模式已开启：后台刷新的实际间隔为 "
                                        + RefreshScheduler.bgMinutes(act) + " 分钟",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        // ---- 通知状态提示 ----
        TextView ns = (TextView) root.findViewById(R.id.notify_state);
        if (ns != null) {
            ns.setText("余额预警通知：点此开启或检查系统通知权限");
            ns.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { NotifyPermission.openSettings(act); }
            });
        }

        // ---- 密钥区折叠（默认收起）----
        final View keyCard = root.findViewById(R.id.key_card);
        final TextView keyToggle = (TextView) root.findViewById(R.id.key_toggle);
        final boolean opened = prefs().getBoolean("key_opened", false);
        if (keyCard != null) keyCard.setVisibility(opened ? View.VISIBLE : View.GONE);
        if (keyToggle != null) {
            keyToggle.setText(opened ? "收起 ▴" : "展开 ▾");
            keyToggle.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean now = keyCard.getVisibility() != View.VISIBLE;
                    prefs().edit().putBoolean("key_opened", now).apply();
                    keyToggle.setText(now ? "收起 ▴" : "展开 ▾");
                    if (now) Anim.expand(keyCard);
                    else Anim.collapse(keyCard);
                }
            });
        }
        renderKeys();   // 预热：收起状态也先构建，展开时零等待

        // ---- 搜索（回车过滤）----
        final TextView st = (TextView) root.findViewById(R.id.search_toggle);
        final EditText sb = (EditText) root.findViewById(R.id.search_box);
        if (st != null && sb != null) {
            st.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean vis = sb.getVisibility() == View.VISIBLE;
                    if (vis) {
                        Anim.collapse(sb);
                    } else {
                        Anim.expand(sb);
                        sb.requestFocus();
                    }
                }
            });
            sb.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                    search = v.getText() == null ? "" : v.getText().toString();
                    Busy.run(act, "正在筛选…", new Runnable() {
                        public void run() { renderKeys(); }
                    });
                    return true;
                }
            });
        }

        // ---- 使用帮助 ----
        // 手势类操作（长按排序等）在界面上没有可点入口，必须有地方能查到
        View helpBtn = root.findViewById(R.id.btn_help);
        if (helpBtn != null) {
            helpBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { Help.show(act); }
            });
        }

        // ---- 隐藏 API（默认收起）----
        final View hideCard = root.findViewById(R.id.hide_card);
        final TextView hideToggle = (TextView) root.findViewById(R.id.hide_toggle);
        if (hideToggle != null) {
            /* 布局里写的是不带箭头的「展开」，进来先按当前状态补一次 ——
               否则初始状态没有箭头，点一下才出现，看着像坏了一半 */
            hideToggle.setText(hideCard != null && hideCard.getVisibility() == View.VISIBLE
                    ? "收起 ▴" : "展开 ▾");
            hideToggle.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean now = hideCard.getVisibility() != View.VISIBLE;
                    hideToggle.setText(now ? "收起 ▴" : "展开 ▾");
                    if (now) {
                        renderHides();          // 先把内容建出来，动画才有高度可量
                        Anim.expand(hideCard);
                    } else {
                        Anim.collapse(hideCard);
                    }
                }
            });
        }

        // ---- 页面管理 ----
        View pagesBtn = root.findViewById(R.id.btn_pages);
        if (pagesBtn != null) {
            pagesBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { PageManager.show(act, onPagesChanged); }
            });
        }

        // ---- 自定义平台 ----
        View addCustom = root.findViewById(R.id.btn_add_custom);
        if (addCustom != null) addCustom.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { addCustomDialog(); }
        });
        renderCustoms();

        // ---- 安全与密码（内联，与密钥区同级）----
        buildSecuritySection();

        // ---- 备份 / 恢复（含本机备份记录列表）----
        View bk = root.findViewById(R.id.btn_backup);
        if (bk != null) bk.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { BackupUi.backup(act); }
        });
        View rs = root.findViewById(R.id.btn_restore);
        if (rs != null) rs.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { BackupUi.restore(act); }
        });
        View bClear = root.findViewById(R.id.btn_backup_clear);
        if (bClear != null) bClear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmClearBackups(); }
        });

        // ---- 自动备份 ----
        final android.widget.Switch swAuto =
                (android.widget.Switch) root.findViewById(R.id.sw_auto_backup);
        final EditText eAuto = (EditText) root.findViewById(R.id.e_auto_hours);
        if (eAuto != null) eAuto.setText(String.valueOf(Backup.autoHours(act)));
        if (swAuto != null) swAuto.setChecked(Backup.autoOn(act));
        renderAutoHint();

        if (swAuto != null) {
            swAuto.setOnCheckedChangeListener(
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                        public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                            Backup.setAuto(act, on, readAutoHours(eAuto));
                            renderAutoHint(true);
                            if (on) {
                                Toast.makeText(act, "已开启自动备份（每 "
                                        + Backup.autoHours(act) + " 小时一次）",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
        }
        if (eAuto != null) {
            /* 失焦或按回车时收下新值 —— 和刷新间隔一个套路 */
            eAuto.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean has) {
                    if (!has) saveAutoHours(eAuto);
                }
            });
            eAuto.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                    saveAutoHours(eAuto);
                    return false;
                }
            });
        }

        renderBackups();

        // ---- 桌面固定引导 ----
        View pin = root.findViewById(R.id.btn_pin);
        if (pin != null) pin.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(act)
                        .setTitle(act.getString(R.string.pin_title))
                        .setMessage(act.getString(R.string.pin_manual))
                        .setPositiveButton(act.getString(R.string.pin_ok), null)
                        .show();
            }
        });

        // ---- 版本 + GitHub ----
        try {
            TextView ver = (TextView) root.findViewById(R.id.app_version);
            if (ver != null) {
                android.content.pm.PackageInfo pi =
                        act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
                ver.setText("版本 " + pi.versionName + " (" + pi.versionCode + ")\n"
                        + "源码仓库（GitHub）：hesanyue50-lang/balance-widget\n"
                        + "点击这里在浏览器中打开仓库 →");
                ver.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        try {
                            act.startActivity(new Intent(Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/hesanyue50-lang/balance-widget")));
                        } catch (Throwable ig) { }
                    }
                });
            }
        } catch (Throwable ignored) { }

        // ---- 栏目标题折叠（最后做：上面对各区块的引用已经拿完了）----
        setupCollapsible();
    }

    // ---------- 栏目标题折叠 ----------

    /**
     * 把「栏目标题 + 紧随其后的卡片」配成一组：点标题展开/收起，状态记住。
     *
     * 靠布局里的 {@code android:tag="sec_title" / "sec_card"} 配对，
     * 而不是按样式名猜 —— 后者拿不到 style，前者还能避开那些
     * 自带开关按钮的区块（「API 密钥」「隐藏 API」有自己的折叠逻辑）。
     */
    private void setupCollapsible() {
        /* 注意：root 是外层容器（FrameLayout / 页面容器），
           栏目标题在它下面的 include 里，层级不止一层 —— 必须递归找，
           按"直接子 View"遍历是找不到的。 */
        java.util.List<View> titles = new java.util.ArrayList<View>();
        collectByTag(root, "sec_title", titles);
        for (int i = 0; i < titles.size(); i++) {
            View t = titles.get(i);
            View card = nextSiblingWithTag(t, "sec_card");
            if (card != null) bindCollapse(t, card);
        }
    }

    private void collectByTag(View v, String tag, java.util.List<View> out) {
        if (v == null) return;
        if (tag.equals(tagOf(v))) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectByTag(g.getChildAt(i), tag, out);
        }
    }

    /** 在同一个父容器里，找它后面第一个带指定 tag 的兄弟 */
    private View nextSiblingWithTag(View v, String tag) {
        if (!(v.getParent() instanceof ViewGroup)) return null;
        ViewGroup p = (ViewGroup) v.getParent();
        int idx = p.indexOfChild(v);
        for (int i = idx + 1; i < p.getChildCount(); i++) {
            View s = p.getChildAt(i);
            if (tag.equals(tagOf(s))) return s;
        }
        return null;
    }

    private void bindCollapse(final View title, final View card) {
        final String base = String.valueOf(((TextView) title).getText());
        final String storeKey = "collapse_" + base;

        /* 把光秃秃的标题换成「标题 + 右侧展开按钮」的一行 ——
           标题本身没地方放按钮，只能在代码里包一层，
           这样和「API 密钥与平台」「隐藏 API」的交互完全一致。 */
        if (!(title.getParent() instanceof ViewGroup)) return;
        final ViewGroup parent = (ViewGroup) title.getParent();
        int idx = parent.indexOfChild(title);
        ViewGroup.LayoutParams lp = title.getLayoutParams();
        parent.removeView(title);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(lp);          // 沿用原标题的位置参数（含 marginTop/Left/Bottom）

        TextView tv = (TextView) title;
        tv.setText(base);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        final TextView btn = new TextView(act);
        btn.setBackgroundResource(R.drawable.mini_btn_border);
        btn.setGravity(Gravity.CENTER);
        btn.setMinWidth(dp(64));
        btn.setPadding(dp(14), dp(8), dp(14), dp(8));
        btn.setTextColor(color(R.color.accent));
        btn.setTextSize(13);
        btn.setTypeface(null, Typeface.BOLD);
        row.addView(btn);

        parent.addView(row, idx);

        boolean open = prefs().getBoolean(storeKey, false);      // 默认收起
        applyCollapse(btn, card, open, false);                   // 初始化不播动画
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean now = card.getVisibility() != View.VISIBLE;
                prefs().edit().putBoolean(storeKey, now).apply();
                applyCollapse(btn, card, now, true);
            }
        });
    }

    /** 与「API 密钥与平台」用同一套文案与行为 */
    private void applyCollapse(TextView btn, View card, boolean open, boolean animate) {
        btn.setText(open ? "收起 ▴" : "展开 ▾");
        if (!animate) {
            card.setVisibility(open ? View.VISIBLE : View.GONE);
            return;
        }
        if (open) Anim.expand(card);
        else Anim.collapse(card);
    }

    private String tagOf(View v) {
        Object t = v.getTag();
        return t == null ? "" : String.valueOf(t);
    }

    /** 淡入动效统一走 Anim —— 参数只在一处定，各处手感才不会跑偏 */
    private void keyFadeIn(View v) {
        Anim.fadeIn(v, 0);
    }

    private void saveIntervals() {
        int f = clampInterval(eFg, RefreshScheduler.FG_MIN);
        int b = clampInterval(eBg, RefreshScheduler.BG_MIN);
        RefreshScheduler.setIntervals(act, f, b);
    }

    /**
     * 把输入框的值收进 [min, MAX_MIN]，返回收敛后的结果。
     *
     * 空 / 非数字也按最小值处理（"没填有效值"和"填了个太小的值"结果一样）。
     * 若收敛结果与原文本不同，且**该框没有焦点**，就回填 —— 用户正在这个框里
     * 打字时不回填，否则会打断输入。
     */
    private int clampInterval(EditText e, int min) {
        if (e == null) return min;
        String raw = e.getText().toString().trim();
        int v;
        try {
            v = Integer.parseInt(raw);
        } catch (Exception ig) {
            v = min;                       // 空或非数字
        }
        if (v < min) v = min;
        if (v > RefreshScheduler.MAX_MIN) v = RefreshScheduler.MAX_MIN;

        if (!String.valueOf(v).equals(raw) && !e.hasFocus()) {
            e.setText(String.valueOf(v));
        }
        return v;
    }

    // ---------------- 密钥列表 ----------------

    private String search = "";
    private final java.util.List<View> keyBlocks = new java.util.ArrayList<View>();
    private final java.util.List<String> keyBlockKeys = new java.util.ArrayList<String>();

    /** 供宿主在外部状态变化后（如 MiMo 登录回来）重画密钥列表 */
    public void refreshKeys() { renderKeys(); }

    private void renderKeys() {
        LinearLayout box = (LinearLayout) root.findViewById(R.id.key_fields);
        if (box == null) return;
        box.removeAllViews();
        keyBlocks.clear();
        keyBlockKeys.clear();
        String q = search == null ? "" : search.trim().toLowerCase();
        for (int i = 0; i < BalanceFetcher.PRESETS.length; i++) {
            BalanceFetcher.Preset p = BalanceFetcher.PRESETS[i];
            List<KeyStore.ApiKey> ks = KeyStore.get(act, p.id);
            String name = p.name;
            String hay = (name + p.id + labels(ks)).toLowerCase();
            if (q.length() > 0 && !hay.contains(q)) continue;
            View v = platformBlock(p.id, name, BalanceFetcher.COLORS[i]);
            box.addView(v);
            keyBlocks.add(v);
            keyBlockKeys.add(hay);
        }
        List<BalanceFetcher.Custom> cs = BalanceFetcher.loadCustom(act);
        for (int i = 0; i < cs.size(); i++) {
            String plat = "custom:" + i;
            List<KeyStore.ApiKey> ks = KeyStore.get(act, plat);
            String hay = (cs.get(i).name + plat + labels(ks)).toLowerCase();
            if (q.length() > 0 && !hay.contains(q)) continue;
            View v = platformBlock(plat, cs.get(i).name, 0xFF8A94A3);
            box.addView(v);
            keyBlocks.add(v);
            keyBlockKeys.add(hay);
        }
        if (q.length() > 0 && keyBlocks.isEmpty()) {
            TextView none = new TextView(act);
            none.setText("没有匹配「" + search + "」的平台或 Key");
            none.setTextColor(color(R.color.tx3));
            none.setTextSize(12);
            box.addView(none);
        }
    }

    private String labels(List<KeyStore.ApiKey> ks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ks.size(); i++) sb.append(ks.get(i).label);
        return sb.toString();
    }

    private View platformBlock(final String plat, String name, int dotColor) {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(14);
        wrap.setLayoutParams(wlp);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        View dot = new View(act);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dlp.rightMargin = dp(8);
        dot.setLayoutParams(dlp);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(dotColor);
        dot.setBackground(gd);
        head.addView(dot);

        TextView label = new TextView(act);
        label.setText(name);
        label.setTextColor(color(R.color.tx));
        label.setTextSize(14);
        label.setTypeface(null, Typeface.BOLD);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(label);

        TextView add = mkBtn("+ 添加", color(R.color.accent));
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { editKey(plat, null); }
        });
        head.addView(add);

        BalanceFetcher.Preset p = BalanceFetcher.presetOf(plat);
        if (p != null && p.console != null && p.console.length() > 0) {
            final String url = p.console;
            TextView site = mkBtn("控制台", color(R.color.tx2));
            site.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    try {
                        act.startActivity(new Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse(url)));
                    } catch (Throwable ig) { }
                }
            });
            head.addView(site);
        }
        wrap.addView(head);

        List<KeyStore.ApiKey> mine = KeyStore.get(act, plat);
        if (mine.isEmpty()) {
            TextView t = new TextView(act);
            t.setText("还没填 Key（点「添加」）");
            t.setTextColor(color(R.color.tx3));
            t.setTextSize(11.5f);
            t.setPadding(0, dp(6), 0, 0);
            wrap.addView(t);
        } else {
            for (int i = 0; i < mine.size(); i++) wrap.addView(keyRow(mine.get(i)));
        }
        return wrap;
    }

    private View keyRow(final KeyStore.ApiKey k) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);

        TextView t = new TextView(act);
        String lb = k.label == null || k.label.length() == 0 ? "默认" : k.label;
        t.setText(lb + "   " + mask(k.key));
        t.setTextColor(k.isConfigured() ? color(R.color.tx2) : color(R.color.tx3));
        t.setTextSize(12);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);

        TextView edit = mkBtn("编辑", color(R.color.accent));
        edit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { editKey(k.platform, k); }
        });
        row.addView(edit);

        /* 长按整行 = 看 / 复制这条密钥（同样要过查看密码，和主界面卡片一个规矩） */
        final String showName = k.label.length() == 0 ? "默认" : k.label;
        row.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                if (!k.isConfigured()) {
                    Toast.makeText(act, "这一条还没填 Key", Toast.LENGTH_SHORT).show();
                    return true;
                }
                LockDialog.ask(act, "查看「" + showName + "」的密钥", new LockDialog.OnPass() {
                    public void ok() {
                        LockDialog.showKey(act, showName + " 的 API Key", k.key);
                    }
                });
                return true;
            }
        });

        TextView del = mkBtn("删除", color(R.color.danger));
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Busy.run(act, "正在删除…", new Runnable() {
                    public void run() {
                        KeyStore.remove(act, k.id);
                        renderKeys();
                        kick();
                    }
                });
            }
        });
        row.addView(del);
        return row;
    }

    private TextView mkBtn(String s, int c) {
        TextView b = new TextView(act);
        b.setText(s);
        b.setTextColor(c);
        b.setTextSize(11);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.mini_btn_border);
        b.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private static String mask(String k) {
        if (k == null || k.length() == 0) return "（未填）";
        if (k.length() <= 10) return k;
        return k.substring(0, 4) + "…" + k.substring(k.length() - 4);
    }

    private void editKey(final String platform, final KeyStore.ApiKey existing) {
        final boolean isNew = existing == null;
        final KeyStore.ApiKey src = isNew ? new KeyStore.ApiKey() : existing;

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(10), dp(20), 0);

        TextView l1 = new TextView(act);
        l1.setText("标签（区分同平台多个 Key）");
        l1.setTextColor(color(R.color.tx2));
        l1.setTextSize(12);
        panel.addView(l1);
        final EditText eLabel = new EditText(act);
        eLabel.setText(src.label);
        eLabel.setTextSize(14);
        panel.addView(eLabel);

        TextView l2 = new TextView(act);
        l2.setText("密钥");
        l2.setTextColor(color(R.color.tx2));
        l2.setTextSize(12);
        l2.setPadding(0, dp(10), 0, 0);
        panel.addView(l2);
        final EditText eKey = new EditText(act);
        eKey.setText(src.key);
        eKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        eKey.setTextSize(14);
        panel.addView(eKey);

        /* 弹窗自引用：MiMo 的「登录」按钮要在点完登录后把弹窗关掉，
           而 AlertDialog 本体是在下面才构建的，所以用一格数组带出来 */
        final AlertDialog[] dlgRef = new AlertDialog[1];

        /** 保存（新增或更新）——「保存」按钮和 MiMo 的「登录」按钮都要先落盘再说 */
        final Runnable persist = new Runnable() {
            public void run() {
                KeyStore.ApiKey k = new KeyStore.ApiKey();
                k.id = src.id;
                k.platform = platform;
                k.label = eLabel.getText().toString().trim();
                k.key = eKey.getText().toString().trim();
                k.note = src.note;
                k.threshold = src.threshold;
                k.budget = src.budget;
                k.draw = true;
                k.planMode = src.planMode;
                k.accessKeyId = src.accessKeyId;
                k.accessKeySecret = src.accessKeySecret;
                k.hideCard = src.hideCard;
                if (isNew) KeyStore.add(act, platform, k);
                else KeyStore.update(act, k);
            }
        };

        /* 小米 MiMo 专用：官方没有 API Key 能查的余额接口，
           余额只能靠小米账号会话音 —— 这里给一个内置 WebView 登录入口。 */
        if ("mimo".equals(platform)) {
            final boolean hasSession = BalanceFetcher.mimoSession(act).length() > 0;
            TextView st = new TextView(act);
            st.setText(hasSession
                    ? "登录状态：已登录（会话音已保存在本机）"
                    : "登录状态：未登录 —— 余额必须登录后才有（官方无 API Key 余额接口）");
            st.setTextColor(color(hasSession ? R.color.tx2 : R.color.tx3));
            st.setTextSize(12);
            st.setPadding(0, dp(10), 0, 0);
            panel.addView(st);

            TextView btn = new TextView(act);
            btn.setText(hasSession ? "重新登录小米账号" : "登录小米账号（用于查余额）");
            btn.setGravity(Gravity.CENTER);
            btn.setTextSize(13);
            btn.setTextColor(color(R.color.accent));
            btn.setPadding(0, dp(10), 0, dp(10));
            btn.setBackgroundResource(R.drawable.mini_btn_border);
            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Busy.run(act, "正在准备登录…", new Runnable() {
                        public void run() {
                            persist.run();               // 先把这条 Key 落盘，回来时卡片还在
                            renderKeys();
                        }
                    });
                    act.startActivity(new Intent(act, MimoLoginActivity.class));
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                    Toast.makeText(act, "登录成功后余额会自动刷新", Toast.LENGTH_SHORT).show();
                }
            });
            panel.addView(btn);
        }

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle(isNew ? "添加 Key" : "编辑 Key")
                .setView(panel)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        /* 保存这一下要过 Keystore 加密 + 重建密钥列表，
                           以前是「点完没反应、过一会儿突然变」，现在至少有个进度条 */
                        Busy.run(act, "正在保存…", new Runnable() {
                            public void run() {
                                persist.run();
                                renderKeys();
                                kick();
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dlgRef[0] = dlg;
        dlg.show();
    }

    // ---------------- 隐藏 API ----------------

    /** 隐藏 API：按「平台」一行（同平台多 Key 一起设置） */
    private void renderHides() {
        LinearLayout box = (LinearLayout) root.findViewById(R.id.hide_fields);
        if (box == null) return;
        box.removeAllViews();

        java.util.LinkedHashMap<String, java.util.List<KeyStore.ApiKey>> groups =
                new java.util.LinkedHashMap<String, java.util.List<KeyStore.ApiKey>>();
        List<KeyStore.ApiKey> aks = KeyStore.all(act);
        for (int i = 0; i < aks.size(); i++) {
            KeyStore.ApiKey k = aks.get(i);
            if (!k.isConfigured()) continue;
            String plat = k.platform == null ? "" : k.platform;
            java.util.List<KeyStore.ApiKey> g = groups.get(plat);
            if (g == null) { g = new java.util.ArrayList<KeyStore.ApiKey>(); groups.put(plat, g); }
            g.add(k);
        }

        for (java.util.Iterator<java.util.Map.Entry<String, java.util.List<KeyStore.ApiKey>>> it =
                groups.entrySet().iterator(); it.hasNext(); ) {
            final java.util.Map.Entry<String, java.util.List<KeyStore.ApiKey>> e = it.next();
            final String plat = e.getKey();
            final java.util.List<KeyStore.ApiKey> ks = e.getValue();
            BalanceFetcher.Preset p = BalanceFetcher.presetOf(plat);
            String name = p == null ? plat : p.name;
            boolean hideCard = false, drawOff = false;
            for (int i = 0; i < ks.size(); i++) {
                if (ks.get(i).hideCard) hideCard = true;
                if (!ks.get(i).draw) drawOff = true;
            }

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView nm = new TextView(act);
            nm.setText(name);
            nm.setTextColor(color(R.color.tx));
            nm.setTextSize(13);
            nm.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(nm);

            Switch swCard = new Switch(act);
            swCard.setText("卡片");
            swCard.setTextSize(11);
            swCard.setTextColor(color(R.color.tx2));
            swCard.setChecked(hideCard);
            swCard.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][] { { android.R.attr.state_checked }, { -android.R.attr.state_checked } },
                    new int[] { 0xFF3D6FD6, 0xFFC6CDD6 }));
            swCard.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            swCard.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    for (int i = 0; i < ks.size(); i++) {
                        KeyStore.ApiKey fresh = KeyStore.byId(act, ks.get(i).id);
                        KeyStore.ApiKey t = fresh != null ? fresh : ks.get(i);
                        t.hideCard = on;
                        KeyStore.update(act, t);
                    }
                    BalanceFetcher.diag(act, "hideCard(平台) " + plat + " = " + on);
                    kick();
                    notifyChanged();
                }
            });
            row.addView(swCard);

            Switch swStat = new Switch(act);
            swStat.setText("统计");
            swStat.setTextSize(11);
            swStat.setTextColor(color(R.color.tx2));
            swStat.setChecked(drawOff);
            swStat.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][] { { android.R.attr.state_checked }, { -android.R.attr.state_checked } },
                    new int[] { 0xFF3D6FD6, 0xFFC6CDD6 }));
            swStat.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            swStat.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    for (int i = 0; i < ks.size(); i++) {
                        KeyStore.ApiKey fresh = KeyStore.byId(act, ks.get(i).id);
                        KeyStore.ApiKey t = fresh != null ? fresh : ks.get(i);
                        t.draw = !on;
                        KeyStore.update(act, t);
                    }
                    notifyChanged();
                }
            });
            row.addView(swStat);
            box.addView(row);
        }
    }

    // ---------------- 自定义平台 ----------------

    // ---------- 自动备份 ----------

    private int readAutoHours(EditText e) {
        if (e == null) return Backup.autoHours(act);
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return v < Backup.AUTO_MIN_HOURS ? Backup.AUTO_MIN_HOURS : v;
        } catch (Throwable t) {
            return Backup.autoHours(act);
        }
    }

    private void saveAutoHours(EditText e) {
        if (e == null) return;
        int h = readAutoHours(e);
        e.setText(String.valueOf(h));          // 收敛后的值回写，用户看得见
        Backup.setAuto(act, Backup.autoOn(act), h);
        renderAutoHint();
    }

    private void renderAutoHint() {
        renderAutoHint(false);
    }

    /**
     * @param animate 用户切换开关时为 true —— 初始化（从 prefs 恢复）时不播动画，
     *                否则一进设置页那行就自己弹一下，像出了故障
     */
    private void renderAutoHint(boolean animate) {
        boolean on = Backup.autoOn(act);

        /* 关掉自动备份时，下面的「备份间隔」整行都没有意义 ——
           留着只会让人以为关了还在按那个间隔跑 */
        View rowHours = root.findViewById(R.id.row_auto_hours);
        if (rowHours != null) {
            boolean shown = rowHours.getVisibility() == View.VISIBLE;
            if (!animate) {
                rowHours.setVisibility(on ? View.VISIBLE : View.GONE);
            } else if (on != shown) {
                if (on) Anim.expand(rowHours);
                else Anim.collapse(rowHours);
            }
        }

        TextView h = (TextView) root.findViewById(R.id.hint_auto_backup);
        if (h == null) return;
        String s;
        if (!on) {
            s = "开启后会按间隔自动备份到本机（最短 2 小时），不会弹出文件选择框。";
        } else {
            long last = Backup.autoLast(act);
            s = last <= 0
                    ? "已开启，还没备份过 —— 下次刷新余额时会做第一次。"
                    : "已开启，上次自动备份于 " + new java.text.SimpleDateFormat(
                            "MM-dd HH:mm", java.util.Locale.US)
                            .format(new java.util.Date(last))
                            + "。每次刷新余额时检查是否到点。";
        }
        h.setText(s);
    }

    // ---------- 本机备份记录 ----------

    /** 供外部（备份 / 删除完成后）通知列表刷新 */
    public void refreshBackups() {
        try { renderBackups(); } catch (Throwable ignored) { }
    }

    /** 宿主销毁时调用：只清掉属于自己的那个静态引用（别人可能刚接管） */
    public void detach() {
        if (active == this) active = null;
    }

    /** 渲染「本机备份记录」列表（可恢复 / 可删除） */
    private void renderBackups() {
        LinearLayout box = (LinearLayout) root.findViewById(R.id.backup_list);
        if (box == null) return;
        box.removeAllViews();

        List<BackupStore.Item> items = BackupStore.list(act);

        TextView empty = (TextView) root.findViewById(R.id.backup_empty);
        if (empty != null) empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        View clear = root.findViewById(R.id.btn_backup_clear);
        if (clear != null) clear.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);

        TextView head = (TextView) root.findViewById(R.id.backup_local_head);
        if (head != null) {
            head.setText(items.isEmpty()
                    ? "本机备份记录"
                    : "本机备份记录（" + items.size() + " 份 · "
                      + humanSize(BackupStore.totalSize(act)) + "）");
        }

        for (int i = 0; i < items.size(); i++) {
            final BackupStore.Item it = items.get(i);
            final boolean ok = BackupStore.isUsable(it);
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (i > 0) row.setPadding(0, dp(12), 0, 0);

            TextView t = new TextView(act);
            String line = ok
                    ? (it.label() + " · " + it.sizeText())
                    : (it.label() + " · ⚠ 已损坏");
            String fn = it.fileLabel();
            if (fn != null) line += "\n📄 " + fn;
            t.setText(line);
            t.setTextColor(ok ? color(R.color.tx) : color(R.color.tx2));
            t.setTextSize(13);
            t.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(t);

            /* 损坏的就不给"恢复"按钮了，但保留"删除" —— 用户总得能清掉它 */
            if (ok) {
                TextView rest = mkBtn("恢复", color(R.color.accent));
                rest.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { askRestoreBackup(it); }
                });
                row.addView(rest);
            }

            TextView del = mkBtn("删除", color(R.color.danger));
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { askDeleteBackup(it); }
            });
            row.addView(del);

            box.addView(row);
            Anim.stagger(row, i);      // 逐条错开淡入
        }
    }

    private void askRestoreBackup(final BackupStore.Item it) {
        new AlertDialog.Builder(act)
                .setTitle("恢复这条备份？")
                .setMessage(it.label() + "\n\n"
                        + "当前的密钥、订阅、设置与历史曲线都会被这条备份覆盖，"
                        + "覆盖后无法撤销。")
                .setPositiveButton("恢复", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        BackupUi.restoreLocal(act, it.name, new Runnable() {
                            public void run() { afterRestore(); }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askDeleteBackup(final BackupStore.Item it) {
        final String fn = it.fileLabel();
        new AlertDialog.Builder(act)
                .setTitle("删除这条备份？")
                .setMessage(it.label() + " · " + it.sizeText()
                        + (fn == null ? "" : "\n文件：" + fn)
                        + "\n\n会同时删掉本机记录"
                        + (fn == null ? "" : "和它导出的那份文件")
                        + "；如果系统不允许删文件，会引导你去文件夹手动处理。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        BackupUi.deleteLocal(act, it, new Runnable() {
                            public void run() { renderBackups(); }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClearBackups() {
        final int n = BackupStore.list(act).size();
        if (n == 0) { renderBackups(); return; }
        new AlertDialog.Builder(act)
                .setTitle("清空全部本机备份？")
                .setMessage("将删除本机保存的 " + n + " 份备份。\n\n"
                        + "只影响本机这些记录，你已经导出到其他位置的文件不会被删除。")
                .setPositiveButton("全部删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final int done = BackupStore.deleteAll(act);
                        Toast.makeText(act, "已删除 " + done + " 份",
                                Toast.LENGTH_SHORT).show();
                        renderBackups();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 恢复完成后：配置全变了，把界面与余额重新拉一遍 */
    private void afterRestore() {
        Busy.run(act, "正在刷新…", new Runnable() {
            public void run() { renderCustoms(); renderKeys(); kick(); renderBackups(); }
        });
    }

    private String humanSize(long b) {
        if (b < 1024) return b + " B";
        long kb = b / 1024;
        if (kb < 1024) return kb + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0);
    }

    private void renderCustoms() {
        LinearLayout box = (LinearLayout) root.findViewById(R.id.custom_fields);
        if (box == null) return;
        box.removeAllViews();

        /* 「上面 N 家」里的 N 用内置平台数实时算 —— 加一家平台不用再来改文案。
           这个数必须和 renderKeys 实际列出的条目一致，所以直接取 PRESETS 长度。 */
        TextView note = (TextView) root.findViewById(R.id.custom_note);
        if (note != null) {
            note.setText(act.getString(R.string.custom_note, BalanceFetcher.PRESETS.length));
        }

        List<BalanceFetcher.Custom> cs = BalanceFetcher.loadCustom(act);
        for (int i = 0; i < cs.size(); i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(act);
            t.setText(cs.get(i).name);
            t.setTextColor(color(R.color.tx));
            t.setTextSize(13);
            t.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(t);
            TextView del = mkBtn("删除", color(R.color.danger));
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    List<BalanceFetcher.Custom> list = BalanceFetcher.loadCustom(act);
                    if (idx < list.size()) {
                        list.remove(idx);
                        BalanceFetcher.saveCustom(act, list);
                    }
                    Busy.run(act, "正在更新…", new Runnable() {
                        public void run() { renderCustoms(); renderKeys(); kick(); }
                    });
                }
            });
            row.addView(del);
            box.addView(row);
        }
    }

    private void addCustomDialog() {
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(10), dp(20), 0);
        final EditText eName = new EditText(act);
        eName.setHint("平台名称");
        panel.addView(eName);
        final EditText eUrl = new EditText(act);
        eUrl.setHint("余额接口 URL（https://…）");
        panel.addView(eUrl);
        final EditText ePath = new EditText(act);
        ePath.setHint("取值路径（可留空自动识别）");
        panel.addView(ePath);
        new AlertDialog.Builder(act)
                .setTitle("添加自定义平台")
                .setView(panel)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        BalanceFetcher.Custom c = new BalanceFetcher.Custom();
                        c.name = eName.getText().toString().trim();
                        c.url = eUrl.getText().toString().trim();
                        c.path = ePath.getText().toString().trim();
                        if (c.name.length() == 0 || c.url.length() == 0) {
                            Toast.makeText(act, "名称和 URL 不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        List<BalanceFetcher.Custom> list = BalanceFetcher.loadCustom(act);
                        list.add(c);
                        BalanceFetcher.saveCustom(act, list);
                        Busy.run(act, "正在保存…", new Runnable() {
                            public void run() { renderCustoms(); renderKeys(); kick(); }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 安全与密码区块（与设置页原实现等价，内联在密钥区同层之后） */
    private void buildSecuritySection() {
        View keyCard = root.findViewById(R.id.key_card);
        ViewGroup parent = keyCard == null ? null : (ViewGroup) keyCard.getParent();
        if (parent == null) return;

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        box.setLayoutParams(lp);

        TextView title = new TextView(act);
        title.setText("安全");
        title.setTextColor(color(R.color.tx3));
        title.setTextSize(12);
        title.setTypeface(null, Typeface.BOLD);
        box.addView(title);

        final TextView btn = mkBtn(Lock.isSet(act) ? "密码保护已开启（点击修改或清除）" : "设置密码保护",
                color(R.color.accent));
        btn.setPadding(dp(12), dp(8), dp(12), dp(8));
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (Lock.isSet(act)) {
                    LockDialog.choose(act, "密码保护", new String[] { "修改密码", "清除密码" },
                            new LockDialog.OnPick() {
                                public void pick(int which) {
                                    if (which == 0) changePassword(btn);
                                    else clearWithQuestion(btn);
                                }
                            });
                } else {
                    LockDialog.setup(act, new LockDialog.OnPass() {
                        public void ok() { btn.setText("密码保护已开启（点击修改或清除）"); }
                    });
                }
            }
        });
        box.addView(btn);
        parent.addView(box);
    }

    /**
     * 修改密码：**必须先验证当前密码**。
     *
     * 之前这里直接进"设置新密码"，等于给了个后门 ——
     * 拿到手机的人不用知道原密码，点两下就能改掉，然后正大光明看密钥，
     * 「密码保护」形同虚设。（"清除密码"那条路径一直是有验证的，只有改密码漏了。）
     */
    private void changePassword(final TextView btn) {
        LockDialog.ask(act, "输入当前密码", new LockDialog.OnPass() {
            public void ok() {
                LockDialog.setup(act, new LockDialog.OnPass() {
                    public void ok() {
                        btn.setText("密码保护已开启（点击修改或清除）");
                        Toast.makeText(act, "密码已更新", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /** 清除密码保护：必须先回答密保问题（未设问题则退化为验证原密码） */
    private void clearWithQuestion(final TextView btn) {
        if (!Lock.hasQuestion(act)) {
            LockDialog.ask(act, "输入密码以清除密码保护", new LockDialog.OnPass() {
                public void ok() {
                    Lock.clearPassword(act);
                    Toast.makeText(act, "已清除密码保护", Toast.LENGTH_SHORT).show();
                    btn.setText("设置密码保护");
                }
            });
            return;
        }
        final EditText ans = new EditText(act);
        ans.setHint("答案");
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), 0);
        TextView q = new TextView(act);
        q.setText("密保问题：" + Lock.question(act));
        q.setTextColor(color(R.color.tx2));
        q.setTextSize(13);
        panel.addView(q);
        panel.addView(ans);
        new AlertDialog.Builder(act)
                .setTitle("清除密码保护")
                .setMessage("需回答密保问题验证身份")
                .setView(panel)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (Lock.checkAnswer(act, ans.getText().toString())) {
                            Lock.clearPassword(act);
                            Toast.makeText(act, "已清除密码保护", Toast.LENGTH_SHORT).show();
                            btn.setText("设置密码保护");
                        } else {
                            Toast.makeText(act, "答案不正确，未清除", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("忘记密保问题？", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { showForgotGuide(); }
                })
                .show();
    }

    /** 忘记密保问题的唯一出路指引：清除应用数据（明确告知会丢失全部本地数据） */
    private void showForgotGuide() {
        new AlertDialog.Builder(act)
                .setTitle("忘记密保问题")
                .setMessage("出于安全考虑，密保答案无法找回。\n\n"
                        + "唯一办法：打开系统「设置 → 应用 → API 管理助手 → 存储」，点击「清除数据」，"
                        + "再重新打开应用并重新设置密码。\n\n"
                        + "⚠️ 清除数据会删除本机保存的【全部 API Key、余额快照与统计历史】，且无法恢复；"
                        + "各平台账号本身的余额与用量不受影响。")
                .setPositiveButton("打开应用设置", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            Intent it = new Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + act.getPackageName()));
                            act.startActivity(it);
                        } catch (Throwable t) {
                            Toast.makeText(act, "请手动到系统设置 → 应用 → API 管理助手 → 存储 清除数据",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    /** 通知宿主数据已变（主界面需立即重刷卡片列表） */
    private void notifyChanged() {
        if (onChanged != null) onChanged.run();
    }

    private void kick() {
        try {
            Intent it = new Intent(act, BalanceWidgetProvider.class);
            it.setAction(BalanceWidgetProvider.ACTION_REFRESH);
            act.sendBroadcast(it);
        } catch (Throwable ignored) { }
    }

    /** 省电开关的文字与配色（开启时用强调色，一眼看出状态） */
    private static void renderPowerSave(TextView ps, android.content.Context ctx) {
        boolean on = RefreshScheduler.powerSave(ctx);
        ps.setText(on ? ctx.getString(R.string.ps_on) : ctx.getString(R.string.ps_off));
        try {
            ps.setTextColor(ctx.getColor(on ? R.color.accent : R.color.tx3));
        } catch (Throwable ignored) { }
    }
}
