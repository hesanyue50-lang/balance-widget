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
            public void onClick(View v) { customDialog(-1); }
        });
        renderCustoms();

        // ---- 高级自定义平台（网页抓取）----
        View addWeb = root.findViewById(R.id.btn_add_web);
        if (addWeb != null) addWeb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { webDialog(-1); }
        });
        View packShare = root.findViewById(R.id.btn_pack_share);
        if (packShare != null) packShare.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { packPickScope(true); }
        });
        View packExport = root.findViewById(R.id.btn_pack_export);
        if (packExport != null) packExport.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { packPickScope(false); }
        });
        View packImport = root.findViewById(R.id.btn_pack_import);
        if (packImport != null) packImport.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { packImportMenu(); }
        });
        renderWebs();

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

        /* 字段越来越多（标签/密钥/百炼 AK/计费模式/网关地址/预警阈值…），
           不包 ScrollView 的话小屏上「保存」按钮会被顶出屏幕够不着。 */
        final android.widget.ScrollView scroll = new android.widget.ScrollView(act);
        scroll.addView(panel);

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

        /* ---------- 低余额预警阈值 ----------
           ⚠️ 这一栏原来只在旧 SettingsActivity 里有，而设置页早就改由本类渲染 ——
           阈值设置界面等于被覆盖掉了：老用户找不到入口，新用户压根不知道有这功能。
           现在补到真正生效的这套界面里。 */
        TextView lThr = new TextView(act);
        lThr.setText("低余额预警阈值（0 = 不预警）");
        lThr.setTextColor(color(R.color.tx2));
        lThr.setTextSize(12);
        lThr.setPadding(0, dp(12), 0, 0);
        panel.addView(lThr);

        TextView tipThr = new TextView(act);
        tipThr.setText("余额低于这个数时发通知提醒，用卡片上显示的原币种。\n"
                + "　例：填 5　→　余额低于 5 元时提醒\n"
                + "订阅制平台填的是「提前几天提醒」，填 3 就是到期前 3 天提醒。\n"
                + "留空或填 0 = 不预警。阈值只有后台刷新时才判断，前台刷新不打扰你。");
        tipThr.setTextColor(color(R.color.tx3));
        tipThr.setTextSize(11);
        tipThr.setLineSpacing(dp(2), 1f);
        tipThr.setPadding(0, dp(4), 0, 0);
        panel.addView(tipThr);

        final EditText eThr = new EditText(act);
        eThr.setHint("0");
        eThr.setText(src.threshold == 0 ? "" : String.valueOf(src.threshold));
        eThr.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        eThr.setTextSize(14);
        eThr.setMaxLines(1);
        panel.addView(eThr);

        /* ⚠️ 阿里云百炼专用：DashScope 的 sk- 令牌查不到余额（余额挂在阿里云主账户上），
           必须用 AccessKey 走 BSS OpenAPI 签名查询。
           旧实现只把这两栏画在 SettingsActivity 里，而设置页实际由本类渲染 ——
           所以百炼的 AccessKey 永远填不上，卡片永远显示「见控制台」。
           本次把入口补到真正生效的这一套。 */
        final EditText[] eAkId = new EditText[1];
        final EditText[] eAkSec = new EditText[1];
        final android.widget.RadioGroup[] rgMode = new android.widget.RadioGroup[1];
        final android.widget.RadioButton[] rbSub = new android.widget.RadioButton[1];
        if ("dashscope".equals(platform)) {
            TextView l3 = new TextView(act);
            l3.setText("阿里云 AccessKeyId（查余额用，LTAI 开头，可留空）");
            l3.setTextColor(color(R.color.tx2));
            l3.setTextSize(12);
            l3.setPadding(0, dp(12), 0, 0);
            panel.addView(l3);
            EditText akId = new EditText(act);
            akId.setHint("LTAI...");
            akId.setText(src.accessKeyId);
            akId.setTextSize(14);
            panel.addView(akId);
            eAkId[0] = akId;

            TextView l4 = new TextView(act);
            l4.setText("阿里云 AccessKeySecret（可留空）");
            l4.setTextColor(color(R.color.tx2));
            l4.setTextSize(12);
            l4.setPadding(0, dp(12), 0, 0);
            panel.addView(l4);
            EditText akSec = new EditText(act);
            akSec.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            akSec.setText(src.accessKeySecret);
            akSec.setTextSize(14);
            panel.addView(akSec);
            eAkSec[0] = akSec;

            LinearLayout modeRow = new LinearLayout(act);
            modeRow.setOrientation(LinearLayout.HORIZONTAL);
            modeRow.setGravity(Gravity.CENTER_VERTICAL);
            modeRow.setPadding(0, dp(12), 0, 0);
            TextView l5 = new TextView(act);
            l5.setText("计费模式");
            l5.setTextColor(color(R.color.tx2));
            l5.setTextSize(12);
            modeRow.addView(l5);
            android.widget.RadioGroup rg = new android.widget.RadioGroup(act);
            rg.setOrientation(android.widget.RadioGroup.HORIZONTAL);
            android.widget.RadioButton rbBal = new android.widget.RadioButton(act);
            rbBal.setText("余额制");
            rbBal.setTextSize(13);
            android.widget.RadioButton rbSubscription = new android.widget.RadioButton(act);
            rbSubscription.setText("订阅制");
            rbSubscription.setTextSize(13);
            rg.addView(rbBal);
            rg.addView(rbSubscription);
            rg.check("subscription".equals(src.planMode)
                    ? rbSubscription.getId() : rbBal.getId());
            modeRow.addView(rg);
            panel.addView(modeRow);
            rgMode[0] = rg;
            rbSub[0] = rbSubscription;

            TextView tip = new TextView(act);
            tip.setText("余额制＝查阿里云账户余额（QueryAccountBalance）；"
                    + "订阅制＝查 Token Plan 实例与到期时间。"
                    + "AccessKey 在阿里云控制台创建，RAM 用户给只读权限即可。"
                    + "两项都填才会发起查询，否则卡片显示「见控制台」。");
            tip.setTextColor(color(R.color.tx3));
            tip.setTextSize(11);
            tip.setPadding(0, dp(6), 0, 0);
            panel.addView(tip);
        }

        /* WorkBuddy 网关：地址由用户自己填。
           不预设默认值 —— 端口、是否在本机、是否局域网别机都不确定，
           写死一个反而会让人以为"填过了"而忽略。 */
        final EditText[] eBase = new EditText[1];
        if ("workbuddy".equals(platform)) {
            /* 这个界面填的是「别人开源的网关项目」的地址，不是某个官方平台。
               说明放最上面 —— 先讲清楚"你在接什么"，再让人填地址，
               否则一进来只看到"网关地址"四个字，不知道背后是什么东西。 */
            TextView src1 = new TextView(act);
            src1.setText("本功能对接的是开源网关项目：");
            src1.setTextColor(color(R.color.tx2));
            src1.setTextSize(12);
            src1.setPadding(0, dp(4), 0, 0);
            panel.addView(src1);

            TextView lnk = new TextView(act);
            lnk.setText(GATEWAY_REPO);
            lnk.setTextColor(0xFF3D6FD6);
            lnk.setTextSize(12);
            lnk.setPaintFlags(lnk.getPaintFlags()
                    | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            lnk.setPadding(0, dp(2), 0, 0);
            /* 点链接走系统浏览器 —— 用户可能要先看 README、拉代码再回来填地址，
               在应用内开个 WebView 反而看不全、也存不了书签。 */
            lnk.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { openRepo(act, GATEWAY_REPO); }
            });
            panel.addView(lnk);

            TextView lw = new TextView(act);
            lw.setText("网关地址（必填）");
            lw.setTextColor(color(R.color.tx2));
            lw.setTextSize(12);
            lw.setPadding(0, dp(12), 0, 0);
            panel.addView(lw);
            EditText eb = new EditText(act);
            eb.setHint("http://127.0.0.1:7863");
            eb.setText(src.baseUrl);
            eb.setTextSize(14);
            eb.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            panel.addView(eb);
            eBase[0] = eb;

            TextView tipw = new TextView(act);
            tipw.setText("上面的「密钥」填网关的 api_key。\n"
                    + "查余额前会先探活：网关没在运行会提示「网关未运行」，"
                    + "点提示可复制启动命令。\n"
                    + "端口不是 7863 就改成实际端口；网关在别的机器上就填它的局域网地址。\n"
                    + "卡片菜单里的「控制台」会打开这个地址下的 /panel/ 页面。");
            tipw.setTextColor(color(R.color.tx3));
            tipw.setTextSize(11);
            tipw.setPadding(0, dp(6), 0, 0);
            panel.addView(tipw);
        }

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
                /* 阈值：以输入框为准（空/非法 = 0 = 不预警） */
                try {
                    String tv0 = eThr.getText().toString().trim();
                    k.threshold = tv0.length() == 0 ? 0 : Double.parseDouble(tv0);
                } catch (Exception ig) { k.threshold = 0; }
                if (k.threshold < 0) k.threshold = 0;
                k.budget = src.budget;
                k.draw = true;
                /* 百炼：有输入框就读框里的值；其他平台没有这两个框，沿用原值 */
                k.accessKeyId = (eAkId[0] != null)
                        ? eAkId[0].getText().toString().trim() : src.accessKeyId;
                k.accessKeySecret = (eAkSec[0] != null)
                        ? eAkSec[0].getText().toString().trim() : src.accessKeySecret;
                k.planMode = (rgMode[0] != null && rbSub[0] != null
                        && rgMode[0].getCheckedRadioButtonId() == rbSub[0].getId())
                        ? "subscription"
                        : (eAkId[0] != null ? "balance" : src.planMode);
                k.hideCard = src.hideCard;
                k.baseUrl = (eBase[0] != null)
                        ? BalanceFetcher.normalizeBase(eBase[0].getText().toString())
                        : src.baseUrl;
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
                .setView(scroll)
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

    // ---------------- 高级自定义平台（网页抓取） ----------------

    /** 选择导出范围（高级平台 / 自定义平台 / 全部），再决定分享还是存文件 */
    private void packPickScope(final boolean share) {
        new AlertDialog.Builder(act)
                .setTitle(share ? "分享哪些平台？" : "导出哪些平台？")
                .setItems(new String[] { "高级自定义平台", "自定义平台", "全部" },
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                String which = w == 0 ? "web" : (w == 1 ? "custom" : "all");
                                String json = PlatformPack.exportText(act, which);
                                if (json.length() == 0) {
                                    Toast.makeText(act, "没有可导出的平台",
                                            Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                if (share) packShareMenu(json);
                                else PlatformPack.exportToFile(act, json, "balance-platform");
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void packShareMenu(final String json) {
        new AlertDialog.Builder(act)
                .setTitle("怎么分享？")
                .setItems(new String[] { "发到聊天（微信/QQ 等）", "复制文本到剪贴板" },
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (w == 0) PlatformPack.share(act, "平台配置", json);
                                else PlatformPack.copyToClipboard(act, json);
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void packImportMenu() {
        new AlertDialog.Builder(act)
                .setTitle("从哪里导入？")
                .setItems(new String[] { "选文件（.bwp）", "粘贴文本" },
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (w == 0) {
                                    PlatformPack.pickImportFile(act);
                                } else {
                                    String text = PlatformPack.clipboardText(act);
                                    if (text == null || text.trim().length() == 0) {
                                        Toast.makeText(act, "剪贴板是空的",
                                                Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    /* 先解析给用户看一眼再落库 —— 万一是别的内容，
                                       直接"导入成功 0 个"会让人摸不着头脑 */
                                    try {
                                        PlatformPack.Info info = PlatformPack.parse(text);
                                        final String t = text;
                                        StringBuilder sb = new StringBuilder();
                                        for (int i = 0; i < info.names.size() && i < 8; i++) {
                                            sb.append("· ").append(info.names.get(i)).append('\n');
                                        }
                                        if (info.names.size() > 8) {
                                            sb.append("… 共 ").append(info.getTotal()).append(" 个\n");
                                        }
                                        new AlertDialog.Builder(act)
                                                .setTitle("确认导入 " + info.getTotal() + " 个平台？")
                                                .setMessage(sb.toString()
                                                        + "\n同名的会自动跳过，不会重复添加。")
                                                .setPositiveButton("导入",
                                                        new DialogInterface.OnClickListener() {
                                                            public void onClick(DialogInterface dd, int ww) {
                                                                try {
                                                                    PlatformPack.Result r =
                                                                            PlatformPack.importText(act, t);
                                                                    Toast.makeText(act, r.describe(),
                                                                            Toast.LENGTH_LONG).show();
                                                                    renderWebs();
                                                                    renderCustoms();
                                                                    BalanceFetcher.onDataRestored(act);
                                                                    kick();
                                                                } catch (Throwable ex) {
                                                                    Toast.makeText(act,
                                                                            "导入失败：" + ex.getMessage(),
                                                                            Toast.LENGTH_LONG).show();
                                                                }
                                                            }
                                                        })
                                                .setNegativeButton("取消", null)
                                                .show();
                                    } catch (Throwable ex) {
                                        Toast.makeText(act, "剪贴板内容不是平台配置："
                                                + ex.getMessage(), Toast.LENGTH_LONG).show();
                                    }
                                }
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }


    /** 当前 headers 里有没有 Cookie */
    private static boolean hasCookie(String headers) {
        if (headers == null) return false;
        String[] ps = headers.split("\n");
        for (int i = 0; i < ps.length; i++) {
            String ln = ps[i].trim();
            int c = ln.indexOf(':');
            if (c > 0 && "cookie".equalsIgnoreCase(ln.substring(0, c).trim())) {
                return true;
            }
        }
        return false;
    }

    /** 列出所有 WebCustom，每条带「编辑 / 测试 / 删除」 */
    private void renderWebs() {
        LinearLayout box = (LinearLayout) root.findViewById(R.id.web_fields);
        if (box == null) return;
        box.removeAllViews();

        List<WebCustom> ws = WebCustom.loadAll(act);
        for (int i = 0; i < ws.size(); i++) {
            final int idx = i;
            WebCustom w = ws.get(i);
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, 0);

            TextView t = new TextView(act);
            String modeDesc;
            if ("auto".equals(w.mode)) modeDesc = "自动";
            else if ("json".equals(w.mode)) modeDesc = "JSON";
            else if ("regex".equals(w.mode)) modeDesc = "正则";
            else if ("between".equals(w.mode)) modeDesc = "区间";
            else modeDesc = "文本";
            t.setText(w.name + "  ·  " + modeDesc);
            t.setTextColor(color(R.color.tx));
            t.setTextSize(13);
            t.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(t);

            TextView test = mkBtn("测试", color(R.color.tx2));
            test.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { testWeb(idx); }
            });
            row.addView(test);

            TextView share = mkBtn("分享", color(R.color.tx2));
            share.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String json = PlatformPack.exportOneWeb(act, idx);
                    if (json.length() == 0) {
                        Toast.makeText(act, "导出失败", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final String j = json;
                    new AlertDialog.Builder(act)
                            .setTitle("分享这个平台")
                            .setItems(new String[] { "发到聊天（微信/QQ 等）", "复制文本到剪贴板" },
                                    new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int w) {
                                            if (w == 0) PlatformPack.share(act, "平台配置", j);
                                            else PlatformPack.copyToClipboard(act, j);
                                        }
                                    })
                            .setNegativeButton("取消", null)
                            .show();
                }
            });
            row.addView(share);

            TextView edit = mkBtn("编辑", color(R.color.accent));
            edit.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { webDialog(idx); }
            });
            row.addView(edit);

            TextView del = mkBtn("删除", color(R.color.danger));
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    List<WebCustom> list = WebCustom.loadAll(act);
                    if (idx < list.size()) {
                        list.remove(idx);
                        WebCustom.saveAll(act, list);
                    }
                    Busy.run(act, "正在更新…", new Runnable() {
                        public void run() { renderWebs(); kick(); }
                    });
                }
            });
            row.addView(del);
            box.addView(row);
        }
        if (ws.isEmpty()) {
            TextView t = new TextView(act);
            t.setText("还没有添加。适合监控任意网页上的一串数字 —— 比如自己搭的服务、公司内部面板、论坛积分。");
            t.setTextColor(color(R.color.tx3));
            t.setTextSize(12);
            box.addView(t);
        }
    }

    /** 抓一次并在对话框里显示识别结果（成功：值 + 识别方式；失败：原文片段） */
    private void testWeb(final int idx) {
        final List<WebCustom> ws = WebCustom.loadAll(act);
        if (idx < 0 || idx >= ws.size()) return;
        final WebCustom w = ws.get(idx);
        Busy.run(act, "正在抓取…", new Runnable() {
            public void run() {
                String msg;
                try {
                    WebCustom.Result r = w.fetch(w.foreign, 12000);
                    msg = "✅ " + r.display
                            + "\n识别方式：" + r.how
                            + "\n数值：" + r.value;
                } catch (Throwable t) {
                    msg = "❌ " + t.getMessage();
                }
                final String m = msg;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        new AlertDialog.Builder(act)
                                .setTitle("测试结果")
                                .setMessage(m)
                                .setPositiveButton("好", null)
                                .show();
                    }
                });
            }
        });
    }

    /**
     * 添加 / 编辑高级自定义平台。
     *
     * 双模式设计：
     * - 默认只露「名字 / 网址」两项（简单模式）—— 大多数人只填这两个就能用；
     * - 「高级选项」是一个折叠区，点开才露出全部字段（模式/路径/正则/方法/头/倍率/模板…）。
     *   有基础的人展开折腾，没基础的人看不见，不会被吓跑。
     */
    private void webDialog(final int editIdx) {
        final boolean isEdit = editIdx >= 0;
        final List<WebCustom> ws = WebCustom.loadAll(act);
        final WebCustom w0 = isEdit && editIdx < ws.size() ? ws.get(editIdx) : new WebCustom();

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(10), dp(20), 0);

        /* 高级选项展开后有十来个控件，AlertDialog 本身不滚动 ——
           不包 ScrollView 的话小屏幕上「保存」按钮够不着。
           包一层 + 限制高度，展开时对话框内部自己滚。 */
        final android.widget.ScrollView scroll = new android.widget.ScrollView(act);
        scroll.addView(panel);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ================= 基础两栏（必填） ================= */

        final EditText eName = new EditText(act);
        eName.setHint("比如：我的图床额度");
        eName.setText(w0.name);
        eName.setMaxLines(1);
        addField(panel, "① 名称",
                "这张卡片显示的名字，会出现在余额列表、统计图表和小组件里。",
                eName);

        final EditText eUrl = new EditText(act);
        eUrl.setHint("https://example.com/api/balance");
        eUrl.setText(w0.url);
        eUrl.setMaxLines(1);
        addField(panel, "② 网址",
                "要抓取的页面或接口地址，以 http:// 或 https:// 开头。\n"
                        + "只填上面两项、直接点「测试」，十有八九能自动识别出数字 ——\n"
                        + "识别不出来再往下展开「高级选项」。",
                eUrl);

        /* ---------- API Key（可选，只记录） ---------- */
        final EditText eKey = new EditText(act);
        eKey.setHint("可不填");
        eKey.setText(w0.key);
        eKey.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        eKey.setMaxLines(1);
        addField(panel, "③ API Key（可选）",
                "想记一下这平台用的是哪个 Key 就填在这里。\n"
                        + "⚠️ 它**不参与请求**、不会被发送 —— 只是给你自己看的登记。\n"
                        + "真正要让请求带上登录信息，用下面「网页登录」抓到的 Cookie，\n"
                        + "或者在高级选项里手写请求头。",
                eKey);

        /* ---------- 网页登录 ---------- */
        final TextView loginBtn = mkBtn("🌐 打开网页登录", color(R.color.accent));
        addField(panel, "④ 网页登录（需要登录才能看到数据时用）",
                "很多平台的余额页面要先登录才显示。点下面的按钮，\n"
                        + "App 会内置打开这个网址 —— 你在里面正常登录（输账号、\n"
                        + "收验证码都行），登录完成后按提示返回，\n"
                        + "App 会把登录态的 Cookie 抄下来，之后的抓取就能看到数据了。\n"
                        + "抓到的 Cookie 只存在本机，可以在下面查看或清除。",
                loginBtn);
        final String[] cookieRef = { w0.headers };
        final TextView cookieInfo = mkLabel("");
        panel.addView(cookieInfo);

        loginBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String u = eUrl.getText().toString().trim();
                if (u.length() == 0) {
                    Toast.makeText(act, "先填网址", Toast.LENGTH_SHORT).show();
                    return;
                }
                WebLoginActivity.open(act, u, new WebLoginActivity.OnCookie() {
                    public void onCookie(String host, String cookie) {
                        if (cookie == null || cookie.length() == 0) return;
                        /* 把 Cookie 收进「附加请求头」，这样抓取时就会带上 */
                        String merged = WebCustom.mergeHeader(cookieRef[0], "Cookie", cookie);
                        cookieRef[0] = merged;
                        w0.headers = merged;
                        String has = "";
                        try {
                            String[] ps0 = merged.split("\n");
                            for (int k = 0; k < ps0.length; k++) {
                                if (ps0[k].trim().toLowerCase().startsWith("cookie:")) {
                                    has = "已记录登录态（含 Cookie）";
                                }
                            }
                        } catch (Throwable ig) { }
                        if (cookieInfo != null) {
                            cookieInfo.setText(has.length() > 0 ? has : "还没有登录态");
                        }
                        Toast.makeText(act, "已记录登录态", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        /* ---------- 点击卡片后弹出哪些按钮（最多 4 个） ---------- */
        final int NBTN = 4;
        final EditText[] eBtnName = new EditText[NBTN];
        final EditText[] eBtnUrl = new EditText[NBTN];
        LinearLayout btnBox = new LinearLayout(act);
        btnBox.setOrientation(LinearLayout.VERTICAL);

        /* 「无」按钮的提示：空地址 = 这个按钮不显示 */
        final String[] presetHints = {
            "app:refresh（刷新这一项）",
            "app:home / app:settings / app:stats",
            "https://… 控制台地址",
            "https://… 充值页地址"
        };

        for (int i = 0; i < NBTN; i++) {
            final int bi = i;
            TextView cap = new TextView(act);
            cap.setText("按钮 " + (i + 1));
            cap.setTextColor(color(R.color.tx));
            cap.setTextSize(12.5f);
            cap.setTypeface(Typeface.DEFAULT_BOLD);
            cap.setPadding(0, i == 0 ? 0 : dp(14), 0, 0);
            btnBox.addView(cap);

            LinearLayout rr = new LinearLayout(act);
            rr.setOrientation(LinearLayout.HORIZONTAL);

            eBtnName[i] = new EditText(act);
            eBtnName[i].setHint("按钮名字（如：控制台）");
            String lb0 = (w0.btnLabels != null && i < w0.btnLabels.length)
                    ? w0.btnLabels[i] : "";
            eBtnName[i].setText(lb0 == null ? "" : lb0);
            eBtnName[i].setMaxLines(1);
            LinearLayout.LayoutParams ln = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ln.rightMargin = dp(6);
            eBtnName[i].setLayoutParams(ln);
            rr.addView(eBtnName[i]);

            eBtnUrl[i] = new EditText(act);
            eBtnUrl[i].setHint(presetHints[i]);
            String u0 = (w0.btnUrls != null && i < w0.btnUrls.length)
                    ? w0.btnUrls[i] : "";
            eBtnUrl[i].setText(u0 == null ? "" : u0);
            eBtnUrl[i].setMaxLines(1);
            eBtnUrl[i].setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
            rr.addView(eBtnUrl[i]);
            btnBox.addView(rr);
        }

        /* 常用指令一键填：省得用户手打 app:xxx */
        LinearLayout quick = new LinearLayout(act);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        String[] quickNames = { "填刷新", "填主界面", "填设置", "填统计" };
        String[] quickVals = { "app:refresh", "app:home", "app:settings", "app:stats" };
        for (int qi = 0; qi < quickNames.length; qi++) {
            final String qv = quickVals[qi];
            TextView q = mkBtn(quickNames[qi], color(R.color.tx2));
            q.setPadding(dp(6), dp(4), dp(6), dp(4));
            /* 填到第一个还空着的地址框里 */
            q.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    for (int k = 0; k < NBTN; k++) {
                        if (eBtnUrl[k].getText().toString().trim().length() == 0) {
                            eBtnUrl[k].setText(qv);
                            if (eBtnName[k].getText().toString().trim().length() == 0) {
                                eBtnName[k].setText(qv.startsWith("app:")
                                        ? qv.substring(4) : qv);
                            }
                            return;
                        }
                    }
                    Toast.makeText(act, "四个按钮都填满了", Toast.LENGTH_SHORT).show();
                }
            });
            quick.addView(q);
        }
        btnBox.addView(quick);

        addField(panel, "⑤ 点卡片后弹出哪些按钮",
                "首页点这张卡片时会弹出下面的按钮，**最多 4 个**，地址由你自己填：\n"
                        + "· 填 https:// 开头的网址 → 用浏览器打开（控制台、充值页、工单页都行）\n"
                        + "· 填 app: 指令 → 切到 App 内的界面\n"
                        + "　　可用指令：app:refresh（刷新）、app:home（主界面）、\n"
                        + "　　app:settings（设置）、app:stats（统计）\n"
                        + "· 地址留空 = 这个按钮不显示\n"
                        + "· **四个全空也没关系** —— 会兜底给一个「刷新」按钮，\n"
                        + "　不会出现点了卡片没反应的情况。",
                btnBox);

        /* ================= 高级选项（折叠） ================= */
        final LinearLayout advBox = new LinearLayout(act);
        advBox.setOrientation(LinearLayout.VERTICAL);
        advBox.setVisibility(View.GONE);

        final TextView advToggle = mkBtn("▸ 高级选项（识别不准时再展开）", color(R.color.tx2));
        advToggle.setOnClickListener(new View.OnClickListener() {
            boolean open = false;
            public void onClick(View v) {
                open = !open;
                advToggle.setText(open ? "▾ 高级选项（识别不准时再展开）" : "▸ 高级选项（识别不准时再展开）");
                advBox.setVisibility(open ? View.VISIBLE : View.GONE);
            }
        });
        /* 折叠开关自己也要上下留白，跟字段区分开 */
        LinearLayout.LayoutParams lpAdv = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpAdv.topMargin = dp(20);
        advToggle.setLayoutParams(lpAdv);
        panel.addView(advToggle);
        panel.addView(advBox);

        TextView advIntro = new TextView(act);
        advIntro.setText("下面这些全部有默认值，按需修改；");
        advIntro.setTextColor(color(R.color.tx3));
        advIntro.setTextSize(11.5f);
        LinearLayout.LayoutParams lpI = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpI.topMargin = dp(4);
        advIntro.setLayoutParams(lpI);
        advBox.addView(advIntro);

        /* ---------- 提取模式 ---------- */
        final String[] modeVals = { "auto", "json", "regex", "between", "text" };
        final String[] modeNames = { "自动识别", "JSON 路径", "正则表达式", "区间截取", "整页文本" };
        final int[] modeIdx = { idxOf(w0.mode, modeVals) };
        final TextView modePick = mkLabel(modeNames[modeIdx[0]]);
        modePick.setTextColor(color(R.color.accent));
        modePick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                modeIdx[0] = (modeIdx[0] + 1) % modeVals.length;
                modePick.setText(modeNames[modeIdx[0]]);
            }
        });
        addField(advBox, "③ 提取模式",
                "从网页内容里「拿出那个数字」的方法，点下面的蓝色字循环切换：\n"
                        + "自动识别 —— 先当 JSON 试、再按正则试，都不行就抓页面里\n"
                        + "　　　　　　第一串像金额的数字。多数情况用这个就够。\n"
                        + "JSON 路径 —— 接口返回 JSON 时用，配合下面的「路径」。\n"
                        + "正则表达式 —— 页面是 HTML 时用，配合下面的「正则」。\n"
                        + "区间截取 —— 数字夹在两段固定文字中间时用，最直观。\n"
                        + "整页文本 —— 不抓数字，直接显示一段文字（如会员状态）。",
                modePick);

        /* ---------- JSON 路径 ---------- */
        final EditText ePath = new EditText(act);
        ePath.setHint("data.balance");
        ePath.setText(w0.path);
        addField(advBox, "④ JSON 路径",
                "提取模式为「JSON 路径 / 自动识别」时生效。\n"
                        + "按层级写，点号分隔；多个候选用竖线隔开，从左到右取第一个命中的：\n"
                        + "　例：data.balance\n"
                        + "　例：data.money | data.amount | balance",
                ePath);

        /* ---------- 正则 ---------- */
        final EditText ePattern = new EditText(act);
        ePattern.setHint("余额[:：]\\s*([0-9.,]+)");
        ePattern.setText(w0.pattern);
        addField(advBox, "⑤ 正则表达式",
                "提取模式为「正则表达式」时生效。\n"
                        + "表达式里有括号时取第 1 个括号里的内容，没括号取整个匹配。\n"
                        + "　例：余额[:：]\\s*([0-9.,]+)　← 匹配「余额：12.5」抓出 12.5\n"
                        + "　例：credits\">([0-9,]+)　　　← 匹配网页里的积分数字",
                ePattern);

        /* ---------- 区间截取 ---------- */
        LinearLayout betweenRow = new LinearLayout(act);
        betweenRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText eStart = new EditText(act);
        eStart.setHint("左标记，如：余额：");
        eStart.setText(w0.start);
        LinearLayout.LayoutParams lpS = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpS.rightMargin = dp(6);
        eStart.setLayoutParams(lpS);
        final EditText eEnd = new EditText(act);
        eEnd.setHint("右标记，如：元");
        eEnd.setText(w0.end);
        eEnd.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        betweenRow.addView(eStart);
        betweenRow.addView(eEnd);
        addField(advBox, "⑥ 区间左 / 右标记",
                "提取模式为「区间截取」时生效。\n"
                        + "抓「左标记」和「右标记」**之间**的内容再取数字。\n"
                        + "　例：页面写着「您的余额：1,234.56 元」\n"
                        + "　　　左填「余额：」右填「元」→ 抓出 1234.56\n"
                        + "左标记必须唯一，填多个相同文字时只认第一处。",
                betweenRow);

        /* ---------- 请求方法 / 字符集 ---------- */
        LinearLayout mrow = new LinearLayout(act);
        mrow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText eMethod = new EditText(act);
        eMethod.setHint("GET");
        eMethod.setText(w0.method);
        eMethod.setMaxLines(1);
        LinearLayout.LayoutParams lpM = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpM.rightMargin = dp(6);
        eMethod.setLayoutParams(lpM);
        final EditText eCharset = new EditText(act);
        eCharset.setHint("UTF-8");
        eCharset.setText(w0.charset);
        eCharset.setMaxLines(1);
        eCharset.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mrow.addView(eMethod);
        mrow.addView(eCharset);
        addField(advBox, "⑦ 请求方法 / 字符集",
                "方法：GET（默认，普通网页）或 POST（部分接口要求 POST 才返回数据）。\n"
                        + "字符集：网页不是 UTF-8 编码且中文乱码时才填，比如 GBK。",
                mrow);

        /* ---------- 附加请求头 ---------- */
        final EditText eHeaders = new EditText(act);
        eHeaders.setHint("Authorization: Bearer sk-xxx\nX-Token: abc");
        eHeaders.setText(w0.headers);
        eHeaders.setMinLines(2);
        eHeaders.setTextSize(12);
        addField(advBox, "⑧ 附加请求头",
                "需要登录才能访问的页面，把浏览器开发者工具里的请求头抄过来，\n"
                        + "一行一个，格式「名字: 值」。\n"
                        + "　例：Authorization: Bearer sk-xxxx\n"
                        + "　例：Cookie: session=abcdefg\n"
                        + "这是唯一能过鉴权的地方，别把 Cookie 泄露给别人。",
                eHeaders);

        /* ---------- POST 请求体 ---------- */
        final EditText eBody = new EditText(act);
        eBody.setHint("{\"page\": 1}");
        eBody.setText(w0.body);
        eBody.setMinLines(2);
        eBody.setTextSize(12);
        addField(advBox, "⑨ POST 请求体",
                "方法选了 POST 时才需要。发给接口的 JSON 内容，按接口要求填：\n"
                        + "　例：{\"page\": 1, \"size\": 20}",
                eBody);

        /* ---------- 倍率 + 后缀 ---------- */
        LinearLayout nrow = new LinearLayout(act);
        nrow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText eScale = new EditText(act);
        eScale.setHint("1（不缩放）");
        eScale.setText(w0.scale == 1.0 ? "" : String.valueOf(w0.scale));
        eScale.setMaxLines(1);
        LinearLayout.LayoutParams lpN = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpN.rightMargin = dp(6);
        eScale.setLayoutParams(lpN);
        final EditText eSuffix = new EditText(act);
        eSuffix.setHint("%（留空表示无）");
        eSuffix.setText(w0.suffix);
        eSuffix.setMaxLines(1);
        eSuffix.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nrow.addView(eScale);
        nrow.addView(eSuffix);
        addField(advBox, "⑩ 倍率 / 显示后缀",
                "倍率：抓到的数字统一乘这个数。单位是「分」的接口填 0.01 变成「元」。\n"
                        + "后缀：单位选「纯数值」时，数字后面显示的字。\n"
                        + "　例：抓到 87，后缀填 %　→　卡片显示「87%」",
                nrow);

        /* ---------- 显示模板 ---------- */
        final EditText eTemplate = new EditText(act);
        eTemplate.setHint("还剩 {v} 天");
        eTemplate.setText(w0.template);
        addField(advBox, "⑪ 显示模板",
                "想自定义卡片上显示的样子时填。{v} 会被替换成抓到的数值：\n"
                        + "　例：还剩 {v} 天\n"
                        + "　例：已用 {v}%，请及时充值\n"
                        + "留空则按「单位」的规则正常显示金额。",
                eTemplate);

        /* ---------- 单位 ---------- */
        final String[] unitVals = { "CNY", "USD", "NONE" };
        final String[] unitNames = { "人民币 ¥", "美元 $", "纯数值" };
        final int[] unitIdx = { idxOf(w0.unit, unitVals) };
        final TextView unitPick = mkLabel(unitNames[unitIdx[0]]);
        unitPick.setTextColor(color(R.color.accent));
        unitPick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                unitIdx[0] = (unitIdx[0] + 1) % unitVals.length;
                unitPick.setText(unitNames[unitIdx[0]]);
            }
        });
        addField(advBox, "⑫ 单位",
                "决定数字怎么显示、要不要折算成人民币：\n"
                        + "人民币 ¥ —— 显示 ¥12.34，计入总资产。\n"
                        + "美元 $ —— 显示 $12.34，按汇率折算后计入总资产。\n"
                        + "纯数值 —— 不是钱的量（积分、次数、百分比）。\n"
                        + "　　　　　配合「显示后缀」用，不进总资产。",
                unitPick);

        /* ---------- 制式 ---------- */
        final String[] kindVals = { "balance", "asset" };
        final String[] kindNames = { "余额制（计入总资产）", "额度制（只展示）" };
        final int[] kindIdx = { idxOf(w0.kind, kindVals) };
        final TextView kindPick = mkLabel(kindNames[kindIdx[0]]);
        kindPick.setTextColor(color(R.color.accent));
        kindPick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                kindIdx[0] = (kindIdx[0] + 1) % kindVals.length;
                kindPick.setText(kindNames[kindIdx[0]]);
            }
        });
        addField(advBox, "⑬ 制式",
                "余额制 —— 这是真实的钱（钱包、账户余额），算进「总资产」。\n"
                        + "额度制 —— 积分、点数这类不是钱的量，只展示、不进总资产。",
                kindPick);

        /* ---------- 境外开关 ---------- */
        final TextView foreignPick = mkLabel(
                w0.foreign ? "🌐 境外网站（走代理）" : "中国网站（直连）");
        foreignPick.setTextColor(color(R.color.accent));
        foreignPick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                w0.foreign = !w0.foreign;
                foreignPick.setText(w0.foreign ? "🌐 境外网站（走代理）" : "中国网站（直连）");
            }
        });
        addField(advBox, "⑭ 网站位置",
                "中国网站直连更快；境外网站走代理（开了「网络加速」时才生效），\n"
                        + "不确定就先选中国网站，抓不到再切换。",
                foreignPick);

        /* ---------- 预警阈值 ---------- */
        final EditText eThreshold = new EditText(act);
        eThreshold.setHint("0（不预警）");
        eThreshold.setText(w0.threshold == 0 ? "" : String.valueOf(w0.threshold));
        eThreshold.setMaxLines(1);
        addField(advBox, "⑮ 低值预警阈值",
                "抓到的数字低于这个值时发通知提醒（只在后台刷新时判断）。\n"
                        + "　例：填 5　→　余额低于 5 元时提醒\n"
                        + "填 0 或留空 = 不预警。",
                eThreshold);

        new AlertDialog.Builder(act)
                .setTitle(isEdit ? "编辑高级平台" : "添加高级平台")
                .setView(scroll)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int wi) {
                        WebCustom w = new WebCustom();
                        w.name = eName.getText().toString().trim();
                        w.url = eUrl.getText().toString().trim();
                        w.mode = modeVals[modeIdx[0]];
                        w.path = ePath.getText().toString().trim();
                        w.pattern = ePattern.getText().toString().trim();
                        w.start = eStart.getText().toString().trim();
                        w.end = eEnd.getText().toString().trim();
                        String m = eMethod.getText().toString().trim().toUpperCase();
                        w.method = m.length() == 0 ? "GET" : m;
                        w.charset = eCharset.getText().toString().trim();
                        /* API Key 与登录 Cookie 合并进「附加请求头」——
                           这里也带上：用户在基础区填的 Key，抓取时若平台要鉴权就自动走 Bearer。 */
                        String hdrs = eHeaders.getText().toString().trim();
                        String kv = eKey.getText().toString().trim();
                        if (kv.length() > 0) {
                            hdrs = WebCustom.mergeHeader(hdrs, "Authorization", "Bearer " + kv);
                        }
                        w.headers = hdrs;
                        w.key = kv;
                        w.body = eBody.getText().toString().trim();
                        w.suffix = eSuffix.getText().toString().trim();
                        w.template = eTemplate.getText().toString().trim();
                        w.unit = unitVals[unitIdx[0]];
                        w.kind = kindVals[kindIdx[0]];
                        w.foreign = w0.foreign;
                        /* 四个自定义按钮：名字 + 地址，一一对应存进数组 */
                        for (int bi = 0; bi < 4; bi++) {
                            w.btnLabels[bi] = eBtnName[bi].getText().toString().trim();
                            w.btnUrls[bi] = eBtnUrl[bi].getText().toString().trim();
                        }
                        try {
                            String sv = eScale.getText().toString().trim();
                            w.scale = sv.length() == 0 ? 1.0 : Double.parseDouble(sv);
                        } catch (Exception e) { w.scale = 1.0; }
                        try {
                            String tv = eThreshold.getText().toString().trim();
                            w.threshold = tv.length() == 0 ? 0 : Double.parseDouble(tv);
                        } catch (Exception e) { w.threshold = 0; }

                        if (w.name.length() == 0 || w.url.length() == 0) {
                            Toast.makeText(act, "名称和网址不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        List<WebCustom> list = WebCustom.loadAll(act);
                        if (isEdit && editIdx < list.size()) list.set(editIdx, w);
                        else list.add(w);
                        WebCustom.saveAll(act, list);
                        Busy.run(act, "正在更新…", new Runnable() {
                            public void run() { renderWebs(); kick(); }
                        });
                    }
                })
                .setNeutralButton("测试", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int wi) {
                        /* 用当前表单里的值试抓（不落库）—— 边填边试 */
                        WebCustom w = new WebCustom();
                        w.name = "test";
                        w.url = eUrl.getText().toString().trim();
                        w.mode = modeVals[modeIdx[0]];
                        w.path = ePath.getText().toString().trim();
                        w.pattern = ePattern.getText().toString().trim();
                        w.start = eStart.getText().toString().trim();
                        w.end = eEnd.getText().toString().trim();
                        String m = eMethod.getText().toString().trim().toUpperCase();
                        w.method = m.length() == 0 ? "GET" : m;
                        w.charset = eCharset.getText().toString().trim();
                        w.headers = eHeaders.getText().toString().trim();
                        w.body = eBody.getText().toString().trim();
                        w.foreign = w0.foreign;
                        if (w.url.length() == 0) {
                            Toast.makeText(act, "先填网址再测试", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        try {
                            WebCustom.Result r = w.fetch(w.foreign, 12000);
                            new AlertDialog.Builder(act)
                                    .setTitle("测试成功")
                                    .setMessage("显示：" + r.display
                                            + "\n识别方式：" + r.how
                                            + "\n数值：" + r.value)
                                    .setPositiveButton("好", null)
                                    .show();
                        } catch (Throwable t) {
                            new AlertDialog.Builder(act)
                                    .setTitle("测试失败")
                                    .setMessage(t.getMessage())
                                    .setPositiveButton("好", null)
                                    .show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static int idxOf(String v, String[] arr) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) return i;
        }
        return 0;
    }

    /**
     * 一个表单字段 = 标题 + 说明 + 控件。
     *
     * 高级自定义平台的表单项多，光靠输入框里的 hint 根本说不清每个空是干嘛的；
     * 所以每个字段都带一段灰字说明（支持换行），用户照着读就能填，
     * 不用猜、也不用去翻文档。
     */
    private void addField(LinearLayout box, String title, String desc, View control) {
        TextView t = new TextView(act);
        t.setText(title);
        t.setTextColor(color(R.color.tx));
        t.setTextSize(13.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp0 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp0.topMargin = dp(18);
        t.setLayoutParams(lp0);
        box.addView(t);

        if (desc != null && desc.length() > 0) {
            TextView d = new TextView(act);
            d.setText(desc);
            d.setTextColor(color(R.color.tx3));
            d.setTextSize(11.5f);
            d.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp1.topMargin = dp(5);
            d.setLayoutParams(lp1);
            box.addView(d);
        }

        if (control != null) {
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp2.topMargin = dp(8);
            control.setLayoutParams(lp2);
            box.addView(control);
        }
    }

    /** 字段说明里的「例子」行：等宽灰字，和说明区分开 */
    private TextView mkLabel(String s) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextColor(color(R.color.tx2));
        t.setTextSize(12);
        t.setPadding(0, dp(8), 0, dp(2));
        return t;
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
            TextView edit = mkBtn("编辑", color(R.color.accent));
            edit.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { customDialog(idx); }
            });
            row.addView(edit);
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

    /**
     * 添加自定义平台（OpenAI 兼容接口）。
     *
     * ⚠️ 早先这里只有「名称 / 接口地址 / 取值路径」三个框，**没有 Key 输入框** ——
     * 而保存时也不写 key，于是新建出来的平台 key 恒为空。
     * 配合 loadCustom 里"有 Key 才算配置好"的旧过滤，结果就是
     * 「添加成功，但设置页和主界面都看不到它」。两个现象一个根因。
     */
    /**
     * 添加 / 编辑自定义平台（OpenAI 兼容接口）。
     *
     * ⚠️ 两处历史欠账，一次补齐：
     * ① 早先这里只有「名称 / 接口地址 / 取值路径」，**没有 Key 输入框** ——
     *    而保存时也不写 key，于是新建出来的平台 key 恒为空；
     *    配合 loadCustom 里"有 Key 才算配置好"的旧过滤，结果就是
     *    「添加成功，但设置页和主界面都看不到它」。
     * ② 没有阈值输入框、列表里也只有「删除」没有「编辑」——
     *    填错了只能删掉重加，预警阈值更是无处可设。
     */
    private void customDialog(final int editIdx) {
        final boolean isEdit = editIdx >= 0;
        List<BalanceFetcher.Custom> all0 = BalanceFetcher.loadCustom(act);
        final BalanceFetcher.Custom c0 =
                isEdit && editIdx < all0.size() ? all0.get(editIdx)
                        : new BalanceFetcher.Custom();

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(10), dp(20), 0);

        final EditText eName = new EditText(act);
        eName.setHint("比如：我的自建网关");
        eName.setText(c0.name);
        eName.setMaxLines(1);
        addField(panel, "① 名称",
                "显示在余额卡片和设置页列表里的名字。",
                eName);

        final EditText eUrl = new EditText(act);
        eUrl.setHint("https://api.example.com/v1");
        eUrl.setText(c0.url);
        eUrl.setMaxLines(1);
        addField(panel, "② 接口地址",
                "查询余额用的接口，以 https:// 开头。\n"
                        + "接口返回 JSON 时，程序会自动找里面的余额数字；\n"
                        + "找不到再用下面「取值路径」指定。",
                eUrl);

        addField(panel, "③ API Key —— 在下面单独添加",
                "**这里不填 Key。** 保存之后，这个平台会出现在上面的\n"
                        + "「API 密钥与平台」列表里，点它下面的「添加 Key」来填。\n"
                        + "这样和内置平台是同一个入口，一个平台也能挂多把 Key。",
                null);

        final EditText ePath = new EditText(act);
        ePath.setHint("data.balance（可留空自动识别）");
        ePath.setText(c0.path);
        ePath.setMaxLines(1);
        addField(panel, "④ 取值路径",
                "从接口返回的 JSON 里按层级取值，点号分隔。\n"
                        + "留空时程序自动识别常见字段名（balance / amount / money 等）。",
                ePath);

        final EditText eThr = new EditText(act);
        eThr.setHint("0（不预警）");
        eThr.setText(c0.threshold == 0 ? "" : String.valueOf(c0.threshold));
        eThr.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        eThr.setMaxLines(1);
        addField(panel, "⑤ 低余额预警阈值",
                "余额低于这个数时发通知提醒（只按卡片显示的币种判断）。\n"
                        + "　例：填 5　→　余额低于 5 元时提醒\n"
                        + "填 0 或留空 = 不预警。",
                eThr);

        /* 字段多了要能滚，否则小屏上「保存」够不着 */
        final android.widget.ScrollView scroll = new android.widget.ScrollView(act);
        scroll.addView(panel);

        new AlertDialog.Builder(act)
                .setTitle(isEdit ? "编辑自定义平台" : "添加自定义平台")
                .setView(scroll)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        BalanceFetcher.Custom c = new BalanceFetcher.Custom();
                        c.name = eName.getText().toString().trim();
                        c.url = eUrl.getText().toString().trim();
                        c.key = c0.key;      // Key 不在这里填，沿用旧值（一般为 ""）
                        c.path = ePath.getText().toString().trim();
                        try {
                            String tv = eThr.getText().toString().trim();
                            c.threshold = tv.length() == 0 ? 0 : Double.parseDouble(tv);
                        } catch (Exception e) { c.threshold = 0; }
                        if (c.threshold < 0) c.threshold = 0;
                        /* 保留旧值里没在这一屏露出的字段（单位/制式/后缀/静态文本），
                           编辑时别被悄悄清空 */
                        if (isEdit) {
                            c.unit = c0.unit;
                            c.kind = c0.kind;
                            c.suffix = c0.suffix;
                            c.text = c0.text;
                        }
                        if (c.name.length() == 0) {
                            Toast.makeText(act, "名称不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (c.url.length() == 0) {
                            Toast.makeText(act, "接口地址不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        List<BalanceFetcher.Custom> list = BalanceFetcher.loadCustom(act);
                        if (isEdit && editIdx < list.size()) list.set(editIdx, c);
                        else list.add(c);
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

    /** 对接的开源网关项目地址。填错一个字用户就找不到源头，所以集中成常量。 */
    static final String GATEWAY_REPO =
            "https://github.com/linguo2625469/workbuddy2api-panel";

    /** 用系统浏览器打开链接。没装浏览器/被拦时静默失败，不打断填表流程。 */
    static void openRepo(android.content.Context ctx, String url) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)));
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
