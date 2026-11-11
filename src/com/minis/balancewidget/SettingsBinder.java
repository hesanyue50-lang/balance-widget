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
    final View root;
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

    /**
     * 整库被换掉之后（恢复备份 / 导入平台包）必须调这个。
     *
     * <p>为什么：这些操作是**整表覆盖**，而设置页的三份列表（内置平台 / 自定义 /
     * 高级平台）都捕获了构建时的下标。只刷新主卡片的话，用户看到的还是旧列表，
     * 点某行「删除」时按旧下标去新列表里删 —— **删掉的是另一个平台**，
     * 连带它的 Key 被清、后面的平台整体前移。
     */
    /**
     * 宿主回到前台时重新登记自己。
     *
     * <p>为什么需要：`active` 是单例，两个宿主（主界面内联面板 / 独立设置页）
     * 各持一个 binder。用户进设置页后 `active` 被设置页的 binder 拿走，
     * 返回时设置页 `detach()` 把 `active` 置 null —— 主界面的 binder
     * **再也不会重新登记**（`settingsPanelBuilt` 闸门只放行一次）。
     * 此后在主界面恢复备份 / 导入平台包，`refreshAllLists()` 什么也不做，
     * 列表停在旧行、旧下标 → 点「删除」删到别的平台、连带清掉它的 Key。
     */
    public void activate() {
        if (act != null && !act.isFinishing()) active = this;
    }

    public static void refreshAllLists() {
        SettingsBinder b = active;
        if (b == null || b.act == null || b.act.isFinishing()) return;
        if (android.os.Build.VERSION.SDK_INT >= 17 && b.act.isDestroyed()) return;
        try {
            b.act.runOnUiThread(new Runnable() {
                public void run() {
                    SettingsBinder x = active;
                    if (x == null) return;
                    try {
                        x.renderKeys();
                        x.renderCustoms();
                        x.renderWebs();
                        x.renderBackups();
                        x.kick();
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }
    }

    public SettingsBinder(Activity a, View r, Runnable onChanged) {
        this.act = a; this.root = r; this.onChanged = onChanged;
    }

    private int dp(float v) { return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f); }

    /**
     * 一行主题选项：四个预览色块（底/卡片/强调/文字）+ 名称 + 一句气质说明 + 选中勾。
     *
     * 为什么用 4 个色块而不是一张缩略图：真正决定"气质"的就是这四个值，
     * 而缩略图要随窗口尺寸缩放、还要维护 7 张图，成本高且容易过期。
     */
    private View themeRow(final int idx, boolean checked) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), dp(11), dp(13), dp(11));

        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Theme.c(act, R.attr.c_card));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(checked ? 2 : 1),
                Theme.c(act, checked ? R.attr.c_accent : R.attr.c_line));
        row.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (idx > 0) lp.topMargin = dp(8);
        row.setLayoutParams(lp);

        int[] pv = Theme.PREVIEW[idx];
        LinearLayout sw = new LinearLayout(act);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 4; i++) {
            View b = new View(act);
            android.graphics.drawable.GradientDrawable d =
                    new android.graphics.drawable.GradientDrawable();
            d.setColor(pv[i]);
            d.setCornerRadius(dp(4));
            d.setStroke(dp(1), Theme.c(act, R.attr.c_line));
            b.setBackground(d);
            LinearLayout.LayoutParams bp =
                    new LinearLayout.LayoutParams(dp(15), dp(15));
            if (i > 0) bp.leftMargin = dp(3);
            b.setLayoutParams(bp);
            sw.addView(b);
        }
        row.addView(sw);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(12);
        col.setLayoutParams(clp);

        TextView nm = new TextView(act);
        nm.setText(Theme.NAMES[idx]);
        nm.setTextSize(14.5f);
        nm.setTextColor(Theme.c(act, R.attr.c_tx));
        nm.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        col.addView(nm);

        TextView ds = new TextView(act);
        ds.setText(Theme.DESCS[idx]);
        ds.setTextSize(11.5f);
        ds.setTextColor(Theme.c(act, R.attr.c_tx3));
        ds.setPadding(0, dp(2), 0, 0);
        col.addView(ds);
        row.addView(col);

        if (checked) {
            TextView ok = new TextView(act);
            ok.setText("\u2713");
            ok.setTextSize(17f);
            ok.setTextColor(Theme.c(act, R.attr.c_accent));
            ok.setPadding(dp(8), 0, dp(2), 0);
            row.addView(ok);
        }

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (Theme.index(act) == idx) return;
                Theme.setIndex(act, idx);
                android.widget.Toast.makeText(act,
                        act.getString(R.string.theme_applied, Theme.NAMES[idx]),
                        android.widget.Toast.LENGTH_SHORT).show();
                /* 必须重建：attr 是 View 创建时解析的，已经建好的控件不会自己换色。
                   ⚠️ 不要在这之前给 DecorView 做 alpha 淡出 —— 试过，
                   把整棵树设成 alpha=0 再 recreate()：只要重建没有立刻换掉窗口
                   （或动画回调跑在 Activity 已经不在前台的时候），
                   那个 alpha=0 就留在旧视图树上，整屏全透明 = 用户看到的黑屏，
                   而且退出重开才恢复。换主题这种一次性动作不值得冒这个风险。 */
                act.recreate();
            }
        });
        return row;
    }

    /** 渲染外观区块 */
    private void renderThemes() {
        LinearLayout box = (LinearLayout) root.findViewById(R.id.theme_list);
        if (box == null) return;
        box.removeAllViews();
        int cur = Theme.index(act);
        for (int i = 0; i < Theme.IDS.length; i++) {
            box.addView(themeRow(i, i == cur));
        }
    }
    /** 读主题色。参数是 R.attr.cXxx，不是 R.color.Xxx —— R.color 不随主题变 */
    private int color(int attr) { return Theme.c(act, attr); }
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

        // ---- 「其他」区折叠（默认收起，状态记 prefs）----
        final View moreBox = root.findViewById(R.id.more_box);
        final TextView moreToggle = (TextView) root.findViewById(R.id.more_toggle);
        final boolean moreOpened = prefs().getBoolean("more_opened", false);
        if (moreBox != null) moreBox.setVisibility(moreOpened ? View.VISIBLE : View.GONE);
        if (moreToggle != null) {
            moreToggle.setText(moreOpened
                    ? act.getString(R.string.toggle_collapse)
                    : act.getString(R.string.toggle_expand));
            moreToggle.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (moreBox == null) return;
                    boolean now = moreBox.getVisibility() != View.VISIBLE;
                    Anim.pulse(moreToggle);
                    prefs().edit().putBoolean("more_opened", now).apply();
                    moreToggle.setText(now
                            ? act.getString(R.string.toggle_collapse)
                            : act.getString(R.string.toggle_expand));
                    if (now) {
                        Anim.expand(moreBox);
                        /* 里面有运行时重建的卡片（备份列表等），补一次套皮 */
                        Theme.skin(act, moreBox);
                    } else {
                        Anim.collapse(moreBox);
                    }
                }
            });
        }

        // ---- 外观区折叠（默认收起，状态记 prefs）----
        final View themeBox = root.findViewById(R.id.theme_box);
        final TextView themeToggle = (TextView) root.findViewById(R.id.theme_toggle);
        final boolean themeOpened = prefs().getBoolean("theme_opened", false);
        if (themeBox != null) themeBox.setVisibility(themeOpened ? View.VISIBLE : View.GONE);
        if (themeToggle != null) {
            themeToggle.setText(themeOpened
                    ? act.getString(R.string.toggle_collapse)
                    : act.getString(R.string.toggle_expand));
            themeToggle.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (themeBox == null) return;
                    boolean now = themeBox.getVisibility() != View.VISIBLE;
                    Anim.pulse(themeToggle);
                    prefs().edit().putBoolean("theme_opened", now).apply();
                    themeToggle.setText(now
                            ? act.getString(R.string.toggle_collapse)
                            : act.getString(R.string.toggle_expand));
                    if (now) Anim.expand(themeBox);
                    else Anim.collapse(themeBox);
                }
            });
        }
        renderThemes();

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
                    if (keyCard == null) return;
                    boolean now = keyCard.getVisibility() != View.VISIBLE;
                    if (keyToggle != null) Anim.pulse(keyToggle);
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

        // ---- 崩溃日志 ----
        View crashBtn = root.findViewById(R.id.btn_crash);
        if (crashBtn != null) {
            crashBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { crashDialog(); }
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
                    if (hideCard == null) return;
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
        btn.setBackgroundResource(Theme.bgRes(btn.getContext(), R.attr.bg_pill));
        btn.setGravity(Gravity.CENTER);
        btn.setMinWidth(dp(64));
        btn.setPadding(dp(14), dp(8), dp(14), dp(8));
        btn.setTextColor(color(R.attr.c_accent));
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
        /* 已挂着的闹钟还按旧间隔排着 —— 只写 prefs 不重排的话，
           用户把 20 分钟改成 360 分钟，要等下一次（旧间隔）响了才生效。 */
        try { RefreshScheduler.schedule(act); } catch (Throwable ignored) { }
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
    public void refreshKeys() {
        renderKeys();
        /* 卡片是运行时新建的，不在 onCreate 那次 skin 的覆盖范围内 —— 补一次 */
        Theme.skin(act, root);
    }

    /**
     * 清理孤儿 Key：平台已被删除（custom:N / web:N 越界）但 Key 还挂着的条目。
     *
     * 一次性清掉历史遗留（比如用户见过的那种"统计页多出一个 custom:0"），
     * 之后删除平台时会同步清 Key，理论上不会再产生新的孤儿。
     * 每次渲染密钥列表时顺带跑一遍，代价是一次遍历。
     */
    private void cleanOrphanKeys() {
        try {
            List<KeyStore.ApiKey> all = KeyStore.all(act);
            boolean changed = false;
            List<KeyStore.ApiKey> keep = new java.util.ArrayList<KeyStore.ApiKey>();
            for (int i = 0; i < all.size(); i++) {
                KeyStore.ApiKey k = all.get(i);
                String pf = k.platform == null ? "" : k.platform;
                boolean orphan = false;
                /* ⚠️ 必须用**原始存档条数**，不能用 loadCustom().size()：
                   后者会过滤掉配置不完整（name 为空之类）的隐藏条目，
                   拿过滤后的长度比，后面所有合法平台都会被判成孤儿，
                   它们的 Key 就被静默删掉了。 */
                if (pf.startsWith("custom:")) {
                    try {
                        int pi = Integer.parseInt(pf.substring(7));
                        int n = BalanceFetcher.rawCustomCount(act);
                        orphan = n >= 0 && (pi < 0 || pi >= n);
                    } catch (Throwable ig) { orphan = false; }   // 读不出来就保留，别当孤儿删掉
                } else if (pf.startsWith("web:")) {
                    try {
                        int pi = Integer.parseInt(pf.substring(4));
                        int n = WebCustom.rawCount(act);
                        orphan = n >= 0 && (pi < 0 || pi >= n);
                    } catch (Throwable ig) { orphan = false; }   // 同理
                }
                if (orphan) {
                    changed = true;
                    BalanceFetcher.diag(act, "清理孤儿 Key：" + pf + "（平台已删除）");
                } else {
                    keep.add(k);
                }
            }
            if (changed) KeyStore.save(act, keep);
        } catch (Throwable ig) { }
    }

    private void renderKeys() {
        cleanOrphanKeys();
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
            View v = platformBlock(plat, cs.get(i).name, Theme.c(act, R.attr.c_tx3));
            box.addView(v);
            keyBlocks.add(v);
            keyBlockKeys.add(hay);
        }
        if (q.length() > 0 && keyBlocks.isEmpty()) {
            TextView none = new TextView(act);
            none.setText("没有匹配「" + search + "」的平台或 Key");
            none.setTextColor(color(R.attr.c_tx3));
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
        label.setTextColor(color(R.attr.c_tx));
        label.setTextSize(14);
        label.setTypeface(null, Typeface.BOLD);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(label);

        TextView add = mkBtn("+ 添加", color(R.attr.c_accent));
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { editKey(plat, null); }
        });
        head.addView(add);

        BalanceFetcher.Preset p = BalanceFetcher.presetOf(plat);
        if (p != null && p.console != null && p.console.length() > 0) {
            final String url = p.console;
            TextView site = mkBtn("控制台", color(R.attr.c_tx2));
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
            t.setTextColor(color(R.attr.c_tx3));
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
        t.setTextColor(k.isConfigured() ? color(R.attr.c_tx2) : color(R.attr.c_tx3));
        t.setTextSize(12);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);

        TextView edit = mkBtn("编辑", color(R.attr.c_accent));
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

        TextView del = mkBtn("删除", color(R.attr.c_danger));
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmDelete("删除这条密钥？",
                        (k.label == null || k.label.length() == 0 ? "这条密钥" : "「" + k.label + "」")
                                + " 删除后无法恢复，需要去平台后台重新生成。",
                        new Runnable() {
                            public void run() {
                                Busy.run(act, "正在删除…", new Runnable() {
                                    public void run() {
                                        KeyStore.remove(act, k.id);
                                        renderKeys();
                                        kick();
                                    }
                                });
                            }
                        });
            }
        });
        row.addView(del);
        return row;
    }

    /**
     * 设置页的小胶囊按钮。
     *
     * 上一版的问题：11.5sp + 底 #F1F3F7（与白卡只差 4%）+ 白色描边（白底不可见）
     * —— 三者叠加的结果是「按钮和正文几乎分不出来」，用户根本不知道哪块能点。
     * 现在：12.5sp、底色加深、1.2dp 深色描边、内边距加大，手指也更好按。
     */
    private TextView mkBtn(String s, int c) {
        TextView b = new TextView(act);
        b.setText(s);
        b.setTextColor(c);
        b.setTextSize(12.5f);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(Theme.bgRes(b.getContext(), R.attr.bg_pill));
        b.setPadding(dp(13), dp(7), dp(13), dp(7));
        b.setClickable(true);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private static String mask(String k) {
        if (k == null || k.length() == 0) return "（未填）";
        /* ⚠️ 短 Key 也必须打码。原来 <=10 位直接原样显示，
           于是自建网关的 6~10 位 token、以及"误把密码填进密钥"的情况，
           在列表里就是明文；而同一行长按"查看完整密钥"却要过查看密码 ——
           同一个界面两套规则，等于密码闸门形同虚设。 */
        /* 太短的 Key 打码后没有信息量，而且 substring(0,4)+substring(len-4)
           在 len<=8 时会重叠 —— 等于把整串又拼了出来。直接只给一个省略号。 */
        if (k.length() <= 8) return "…";
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
        l1.setTextColor(color(R.attr.c_tx2));
        l1.setTextSize(12);
        panel.addView(l1);
        final EditText eLabel = new EditText(act);
        eLabel.setText(src.label);
        eLabel.setTextSize(14);
        panel.addView(eLabel);

        TextView l2 = new TextView(act);
        l2.setText("密钥");
        l2.setTextColor(color(R.attr.c_tx2));
        l2.setTextSize(12);
        l2.setPadding(0, dp(10), 0, 0);
        panel.addView(l2);
        final EditText eKey = new EditText(act);
        eKey.setText(src.key);
        eKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        eKey.setTextSize(14);
        /* 这两个平台官方不给普通 Key 查余额，要填浏览器 Cookie —— 提示得说清，
           否则用户会拿 sk- 开头的 Key 反复试。Cookie 同样是凭据，仍然隐藏显示。 */
        if ("openai".equals(platform)) {
            eKey.setHint("粘贴浏览器 Cookie，或 Admin Key（sk-admin-…）");
        } else if ("gemini".equals(platform)) {
            eKey.setHint("这个平台不需要填（无余额接口）");
        } else {
            eKey.setHint("粘贴 API Key");
        }
        panel.addView(eKey);

        /* ---------- 低余额预警阈值 ----------
           ⚠️ 这一栏原来只在旧 SettingsActivity 里有，而设置页早就改由本类渲染 ——
           阈值设置界面等于被覆盖掉了：老用户找不到入口，新用户压根不知道有这功能。
           现在补到真正生效的这套界面里。 */
        TextView lThr = new TextView(act);
        lThr.setText("低余额预警阈值（0 = 不预警）");
        lThr.setTextColor(color(R.attr.c_tx2));
        lThr.setTextSize(12);
        lThr.setPadding(0, dp(12), 0, 0);
        panel.addView(lThr);

        TextView tipThr = new TextView(act);
        tipThr.setText("余额低于这个数时发通知提醒，用卡片上显示的原币种。\n"
                + "　例：填 5　→　余额低于 5 元时提醒\n"
                + "订阅制平台填的是「提前几天提醒」，填 3 就是到期前 3 天提醒。\n"
                + "留空或填 0 = 不预警。阈值只有后台刷新时才判断，前台刷新不打扰你。");
        tipThr.setTextColor(color(R.attr.c_tx3));
        tipThr.setTextSize(11.5f);
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
            l3.setTextColor(color(R.attr.c_tx2));
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
            l4.setTextColor(color(R.attr.c_tx2));
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
            l5.setTextColor(color(R.attr.c_tx2));
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
            /* 必须显式 setId：不设的话两个按钮的 getId() 都是 NO_ID(-1)，
               于是 rg.check(-1) 一个都不选中，而保存时
               「getCheckedRadioButtonId() == rbSub.getId()」变成 -1 == -1 **恒真** ——
               无论用户在界面上选什么，planMode 永远被写成 subscription，
               百炼就一直去查 Token Plan 实例而不是账户余额。 */
            rbBal.setId(1001);
            rbSubscription.setId(1002);
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
            tip.setTextColor(color(R.attr.c_tx3));
            tip.setTextSize(11.5f);
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
            src1.setTextColor(color(R.attr.c_tx2));
            src1.setTextSize(12);
            src1.setPadding(0, dp(4), 0, 0);
            panel.addView(src1);

            TextView lnk = new TextView(act);
            lnk.setText(GATEWAY_REPO);
            lnk.setTextColor(color(R.attr.c_accent));   // 走 token，暗色下才够亮
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
            lw.setTextColor(color(R.attr.c_tx2));
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
            tipw.setTextColor(color(R.attr.c_tx3));
            tipw.setTextSize(11.5f);
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
                /* 不能无条件 true：用户在「隐藏 API → 统计」里关掉的开关
                   会在每次编辑 Key 时被悄悄打开（src.draw 被丢弃）。 */
                k.draw = src.draw;
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
                /* 密文本体原样带过去。
                   换机／刷机／清 Keystore 之后 KeyVault.dec() 会返回空串，
                   此时 src.key 是空的，但 src.rawKey 里还留着磁盘上的原密文。
                   不拷的话 KeyStore.save() 那条"key 空就写回原密文"的保护条件
                   永远不成立，用户改一下备注就把这个 Key 的密文永久覆盖成空。 */
                if (k.key.length() == 0) {
                    k.rawKey = src.rawKey;
                    k.rawAccessKeyId = src.rawAccessKeyId;
                    k.rawAccessKeySecret = src.rawAccessKeySecret;
                }
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
            st.setTextColor(color(hasSession ? R.attr.c_tx2 : R.attr.c_tx3));
            st.setTextSize(12);
            st.setPadding(0, dp(10), 0, 0);
            panel.addView(st);

            TextView btn = new TextView(act);
            btn.setText(hasSession ? "重新登录小米账号" : "登录小米账号（用于查余额）");
            btn.setGravity(Gravity.CENTER);
            btn.setTextSize(13);
            btn.setTextColor(color(R.attr.c_accent));
            btn.setPadding(0, dp(10), 0, dp(10));
            btn.setBackgroundResource(Theme.bgRes(btn.getContext(), R.attr.bg_pill));
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

        Theme.skin(act, scroll);   // 弹窗是独立窗口，Activity 那棵树遍历不到
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
            nm.setTextColor(color(R.attr.c_tx));
            nm.setTextSize(13);
            nm.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(nm);

            Switch swCard = new Switch(act);
            swCard.setText("卡片");
            swCard.setTextSize(11.5f);
            swCard.setTextColor(color(R.attr.c_tx2));
            swCard.setChecked(hideCard);
            swCard.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][] { { android.R.attr.state_checked }, { -android.R.attr.state_checked } },
                    new int[] { Theme.c(act, R.attr.c_accent), Theme.c(act, R.attr.c_tx3) }));
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
            swStat.setTextSize(11.5f);
            swStat.setTextColor(color(R.attr.c_tx2));
            swStat.setChecked(drawOff);
            swStat.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][] { { android.R.attr.state_checked }, { -android.R.attr.state_checked } },
                    new int[] { Theme.c(act, R.attr.c_accent), Theme.c(act, R.attr.c_tx3) }));
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

    /**
     * 崩溃日志面板：看最近记录 / 设保存位置 / 主动触发一次测试崩溃。
     *
     * 「测试崩溃」看着吓人，其实是排障刚需 —— 用户配好目录后点一下，
     * 看到日志真的出现在那里，才敢确认"下次真崩了日志也能拿到"。
     */
    private void crashDialog() {
        /* 显示统一走 CrashGuard.dirDesc()：SAF 选的文件夹、明文目录、Download
           三种情况都覆盖 —— 早先这里只读 crash_dir 一个键，用户用文件选择器
           换的目录（crash_dir_uri）在界面上永远显示不出来。 */
        String curDesc = CrashGuard.dirDesc(act);

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(10), dp(20), 0);

        TextView info = new TextView(act);
        info.setText("当前保存位置：" + curDesc);
        info.setTextColor(color(R.attr.c_tx));
        info.setTextSize(13);
        panel.addView(info);

        TextView tip = new TextView(act);
        tip.setText("发生崩溃时会自动在这里保存一份日志，并发通知提醒你。\n"
                + "把日志文件发给开发者就能快速定位问题。\n"
                + "不设置就用系统的「下载」文件夹。");
        tip.setTextColor(color(R.attr.c_tx3));
        tip.setTextSize(11.5f);
        tip.setLineSpacing(dp(3), 1f);
        tip.setPadding(0, dp(6), 0, 0);
        panel.addView(tip);

        Theme.skin(act, panel);   // 弹窗是独立窗口，Activity 那棵树遍历不到
        new AlertDialog.Builder(act)
                .setTitle("💥 崩溃日志")
                .setView(panel)
                .setItems(new CharSequence[] {
                        "选择保存文件夹", "查看最近诊断日志", "触发一次测试崩溃" },
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (w == 0) pickCrashDir();
                                else if (w == 1) showRecentDiag();
                                else triggerTestCrash();
                            }
                        })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void pickCrashDir() {
        try {
            android.net.Uri initial = null;
            try {
                String last = act.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE)
                        .getString("crash_dir_uri", "");
                if (last.length() > 0) initial = android.net.Uri.parse(last);
            } catch (Throwable ig) { }
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            if (initial != null) {
                i.putExtra("android.provider.extra.INITIAL_URI", initial);
            }
            act.startActivityForResult(i, 9101);
        } catch (Throwable t) {
            Toast.makeText(act, "打不开文件夹选择器：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    private void showRecentDiag() {
        String recent = BalanceFetcher.recentDiag(act, 80);
        if (recent.trim().length() == 0) recent = "（还没有日志）";
        TextView tv = new TextView(act);
        tv.setText(recent);
        tv.setTextSize(10.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        android.widget.ScrollView sc = new android.widget.ScrollView(act);
        sc.addView(tv);
        Theme.skin(act, sc);   // 弹窗是独立窗口，Activity 那棵树遍历不到
        new AlertDialog.Builder(act)
                .setTitle("最近诊断日志")
                .setView(sc)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制全部", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) act.getSystemService(
                                        Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "diag", BalanceFetcher.recentDiag(act, 400)));
                        Toast.makeText(act, "已复制", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void triggerTestCrash() {
        new AlertDialog.Builder(act)
                .setTitle("触发测试崩溃？")
                .setMessage("App 会立刻崩溃一次，用于验证日志能不能正常保存。"
                        + "崩溃后重新打开这个 App 即可。")
                .setPositiveButton("崩吧", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        throw new IllegalStateException("测试崩溃（用户主动触发）");
                    }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    /** 处理「选择保存文件夹」的返回（MainActivity.onActivityResult 转发） */
    public static boolean onCrashDirResult(final Activity act, int req, int res, Intent data) {
        if (req != 9101) return false;
        if (res == Activity.RESULT_OK && data != null && data.getData() != null) {
            android.net.Uri u = data.getData();
            try {
                act.getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Throwable ignored) { }
            /* SAF 树 URI 不能直接当 File 用 —— 记下树 URI，CrashGuard 里用 DocumentFile 写。
               同时把可读路径提示给用户。 */
            act.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString("crash_dir_uri", u.toString())
                    .putString("crash_dir", "")
                    .apply();
            Toast.makeText(act, "已设置（走系统文件夹授权，崩溃时自动写入）", Toast.LENGTH_LONG).show();
        }
        return true;
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
        final boolean first = box.getChildCount() == 0;
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
            t.setTextColor(color(R.attr.c_tx));
            t.setTextSize(13);
            t.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(t);

            TextView test = mkBtn("测试", color(R.attr.c_tx2));
            test.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { testWeb(idx); }
            });
            row.addView(test);

            TextView share = mkBtn("分享", color(R.attr.c_tx2));
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

            TextView edit = mkBtn("编辑", color(R.attr.c_accent));
            edit.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { webDialog(idx); }
            });
            row.addView(edit);

            TextView del = mkBtn("删除", color(R.attr.c_danger));
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    confirmDelete("删除这个高级平台？",
                            "它的密钥和余额历史会一起删掉，无法恢复。",
                            new Runnable() {
                                public void run() {
                                    doDeleteWeb(idx);
                                }
                            });
                }
            });
            row.addView(del);
            box.addView(row);
        }


        if (ws.isEmpty()) {
            TextView t = new TextView(act);
            t.setText("还没有添加。适合监控任意网页上的一串数字 —— 比如自己搭的服务、公司内部面板、论坛积分。");
            t.setTextColor(color(R.attr.c_tx3));
            t.setTextSize(12);
            box.addView(t);
        }
        if (first) Anim.staggerChildren(box, 0, 10);
    }

        /** 真正执行删除（确认框点了「删除」之后才走到这里）。 */
        private void doDeleteWeb(final int idx) {
            {
                {
                    List<WebCustom> list = WebCustom.loadAll(act);
                    if (idx < list.size()) {
                        WebCustom del = list.get(idx);
                        WebCustom.removeOne(act, del.name, del.url);
                        /* 删掉 web:1 之后，原来的 web:2 会变成新的 web:1 ——
                           后面所有平台的 Key 都挂在旧 id 上，不处理就是全部错配。
                           ⚠️ 早先这里是「idx 及之后一律清掉」，注释写着重排、代码没做：
                           用户删一个高级平台，后面每个高级平台的密钥都被静默删除，
                           还得一个个重填。现在按自定义平台同一套逻辑**前移一位**。 */
                        List<KeyStore.ApiKey> all = KeyStore.all(act);
                        List<KeyStore.ApiKey> keep = new java.util.ArrayList<KeyStore.ApiKey>();
                        for (int ki = 0; ki < all.size(); ki++) {
                            KeyStore.ApiKey kk = all.get(ki);
                            String pf = kk.platform == null ? "" : kk.platform;
                            if (pf.startsWith("web:")) {
                                try {
                                    int pi = Integer.parseInt(pf.substring(4));
                                    if (pi == idx) continue;      // 被删平台的 Key，一起丢
                                    if (pi > idx) {               // 后面的平台前移一位
                                        kk.platform = "web:" + (pi - 1);
                                    }
                                } catch (Throwable ig) { continue; }
                            }
                            keep.add(kk);
                        }
                        KeyStore.save(act, keep);
                    }
                    Busy.run(act, "正在更新…", new Runnable() {
                        public void run() {
                            /* 账本重排要改两张表、几十个键，放主线程会在遮罩还没盖上时
                               僵一下；挪进 Busy 的 heavy 里正好被遮住 */
                            Ledger.shiftKeys(act, "web:", idx);
                            renderWebs(); kick();
                        }
                    });
                }
            }
        }

    /** 抓一次并在对话框里显示识别结果（成功：值 + 识别方式；失败：原文片段） */
    private void testWeb(final int idx) {
        final List<WebCustom> ws = WebCustom.loadAll(act);
        if (idx < 0 || idx >= ws.size()) return;
        final WebCustom w = ws.get(idx);
        /* ⚠️ 抓取必须在**后台线程**：Busy.run 的重活是 post 到主线程执行的，
           在里面直接 fetch 会抛 NetworkOnMainThreadException —— 表现是
           "测试永远失败"。之前就是这么写的，等于测试按钮从来没work过。 */
        Busy.show(act, "正在抓取…");
        new Thread(new Runnable() {
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
                        Busy.hide(act);
                        if (act.isFinishing() || act.isDestroyed()) return;
                        new AlertDialog.Builder(act)
                                .setTitle("测试结果")
                                .setMessage(m)
                                .setPositiveButton("好", null)
                                .show();
                    }
                });
            }
        }).start();
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
        if (isEdit && editIdx >= ws.size()) {
            Toast.makeText(act, "平台列表已经变了，退出重进再试", Toast.LENGTH_SHORT).show();
            return;
        }
        final WebCustom w0 = isEdit ? ws.get(editIdx) : new WebCustom();

        final FormDialog fd = new FormDialog(act,
                isEdit ? "编辑高级平台" : "添加高级平台", "保存");
        final LinearLayout box = fd.box;

        /* 首屏只放「名称 / 网址 / Key / 登录 / 币种制式 / 显示文字」六项 ——
           十来个高级字段全挪进下面的折叠区。原来不分层，一打开就是一整屏术语。 */

        Form.label(act, box, "名称");
        final EditText eName = Form.input(act, box, "比如：我的图床额度", w0.name);

        Form.label(act, box, "网址（要抓的页面或接口）");
        final EditText eUrl = Form.input(act, box, "https://example.com/api/balance", w0.url);
        Form.uri(eUrl);

        Form.label(act, box, "API Key（填了会作为 Authorization: Bearer 发送）");
        final EditText eKey = Form.secret(act, box, "可留空", w0.key);

        /* ---------- 网页登录：抓到的 Cookie 要塞进「附加请求头」 ---------- */
        final EditText[] eHeadersRef = new EditText[1];
        final TextView loginBtn = Form.wide(act, "🌐 打开网页登录", null);
        fd.add(loginBtn, 14);
        final TextView cookieInfo = Form.note(act, box,
                (hasCookieHeader(w0.headers) ? "已记录登录态（含 Cookie）" : "还没有登录态")
                        + "\n只要站点还在正常响应，登录态会自己续下去；"
                        + "只有服务端真的作废了才会让你重新登录。");

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
                        /* ⚠️ 以输入框**当前**内容为基线合并。
                           用打开弹窗时的快照 w0.headers 当基线，会把用户在
                           「附加请求头」里手改的内容整段覆盖回去。 */
                        String base = (eHeadersRef[0] != null
                                && eHeadersRef[0].getText().length() > 0)
                                ? eHeadersRef[0].getText().toString() : w0.headers;
                        String merged = WebCustom.mergeHeader(base, "Cookie", cookie);
                        w0.headers = merged;
                        if (eHeadersRef[0] != null) eHeadersRef[0].setText(merged);
                        if (cookieInfo != null) {
                            cookieInfo.setText(hasCookieHeader(merged)
                                    ? "已记录登录态（含 Cookie）" : "还没有登录态");
                        }
                        Toast.makeText(act, "已记录登录态", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        /* ---------- 币种 / 制式 ---------- */
        final String[] unitVals = { "CNY", "USD", "NONE" };
        final String[] unitNames = { "币种: CNY", "币种: USD", "币种: 纯数值" };
        final int ui0 = idxOfOr(w0.unit, unitVals);
        final int[] unitIdx = { ui0 < 0 ? 0 : ui0 };
        final boolean[] unitTouched = { false };
        final TextView unitChip = Form.chip(act,
                ui0 < 0 ? ("币种: " + w0.unit) : unitNames[unitIdx[0]], null);
        unitChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                unitTouched[0] = true;
                unitIdx[0] = (unitIdx[0] + 1) % unitVals.length;
                unitChip.setText(unitNames[unitIdx[0]]);
            }
        });

        final String[] kindVals = { "balance", "asset" };
        final String[] kindNames = { "制式: 金额", "制式: 额度" };
        final int ki0 = idxOfOr(w0.kind, kindVals);
        final int[] kindIdx = { ki0 < 0 ? 0 : ki0 };
        final boolean[] kindTouched = { false };
        final TextView kindChip = Form.chip(act,
                ki0 < 0 ? ("制式: " + w0.kind) : kindNames[kindIdx[0]], null);
        kindChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                kindTouched[0] = true;
                kindIdx[0] = (kindIdx[0] + 1) % kindVals.length;
                kindChip.setText(kindNames[kindIdx[0]]);
            }
        });

        LinearLayout chipRow = Form.row(act, box);
        chipRow.addView(unitChip);
        LinearLayout.LayoutParams lpKind = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpKind.leftMargin = dp(8);
        kindChip.setLayoutParams(lpKind);
        chipRow.addView(kindChip);
        Form.note(act, box, "金额 = 计入总资产；额度 = 只在卡片上展示，不进汇总。");

        Form.label(act, box, "显示文字（可留空；{v} 是抓到的数值本身，不带货币符号）");
        final EditText eTemplate = Form.input(act, box, "例如: 还剩 {v} 天", w0.template);
        Form.noSuggest(eTemplate);

        /* ---------- 测试：用当前填的值试抓，不落库、不关窗 ---------- */
        final TextView testBtn = Form.wide(act, "⟳ 测试抓取（用当前填的值试一下）", null);
        fd.add(testBtn, 14);

        /* ================= 高级选项（折叠） ================= */
        final LinearLayout adv = fd.collapsible("▸ 高级选项（识别不准时再展开）",
                "▾ 高级选项（识别不准时再展开）");

        final String[] modeVals = { "auto", "json", "regex", "between", "text" };
        final String[] modeNames = { "自动识别", "JSON 路径", "正则表达式", "区间截取", "整页文本" };
        final int mi0 = idxOfOr(w0.mode, modeVals);
        final int[] modeIdx = { mi0 < 0 ? 0 : mi0 };
        final boolean[] modeTouched = { false };
        Form.label(act, adv, "提取模式（点一下切换）");
        final TextView modeChip = Form.chip(act,
                mi0 < 0 ? ("模式: " + w0.mode) : modeNames[modeIdx[0]], null);
        modeChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                modeTouched[0] = true;
                modeIdx[0] = (modeIdx[0] + 1) % modeVals.length;
                modeChip.setText(modeNames[modeIdx[0]]);
            }
        });
        LinearLayout modeRow = Form.row(act, adv);
        modeRow.addView(modeChip);
        Form.note(act, adv, "自动识别 = 先按 JSON 试、再按正则试，都不行抓页面里第一串像金额的数字。");

        Form.label(act, adv, "JSON 路径（多个候选用 | 分隔）");
        final EditText ePath = Form.noSuggest(Form.input(act, adv, "data.balance", w0.path));

        Form.label(act, adv, "正则表达式");
        final EditText ePattern = Form.noSuggest(Form.input(act, adv,
                "余额[:：]\\s*([0-9.,]+)", w0.pattern));
        Form.note(act, adv, "有括号取第 1 个括号里的内容，没括号取整个匹配。");

        Form.label(act, adv, "区间截取：左标记 / 右标记");
        final EditText eStart = Form.inline(act, "左标记，如：余额：", w0.start, 1f);
        final EditText eEnd = Form.inline(act, "右标记，如：元", w0.end, 1f);
        LinearLayout betweenRow = Form.row(act, adv);
        betweenRow.addView(eStart);
        LinearLayout.LayoutParams lpE = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpE.leftMargin = dp(6);
        eEnd.setLayoutParams(lpE);
        betweenRow.addView(eEnd);

        Form.label(act, adv, "请求方法 / 字符集");
        final EditText eMethod = Form.inline(act, "GET", w0.method, 1f);
        final EditText eCharset = Form.inline(act, "UTF-8", w0.charset, 1f);
        LinearLayout mrow = Form.row(act, adv);
        mrow.addView(eMethod);
        LinearLayout.LayoutParams lpCs = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpCs.leftMargin = dp(6);
        eCharset.setLayoutParams(lpCs);
        mrow.addView(eCharset);

        Form.label(act, adv, "附加请求头（一行一个，格式「名字: 值」）");
        final EditText eHeaders = Form.area(act, adv,
                "Authorization: Bearer sk-xxx\nX-Token: abc", w0.headers, 2);
        eHeadersRef[0] = eHeaders;      // 让「网页登录」把 Cookie 写进来
        Form.note(act, adv, "需要鉴权的页面把浏览器里的请求头抄过来，这是唯一能过鉴权的地方。");

        Form.label(act, adv, "POST 请求体（方法选 POST 时才要）");
        final EditText eBody = Form.area(act, adv, "{\"page\": 1}", w0.body, 2);

        Form.label(act, adv, "倍率 / 显示后缀");
        final EditText eScale = Form.inline(act, "1（不缩放）",
                w0.scale == 1.0 ? "" : trimNum(w0.scale), 1f);
        eScale.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        final EditText eSuffix = Form.inline(act, "%（留空表示无）", w0.suffix, 1f);
        LinearLayout srow = Form.row(act, adv);
        srow.addView(eScale);
        LinearLayout.LayoutParams lpSf = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpSf.leftMargin = dp(6);
        eSuffix.setLayoutParams(lpSf);
        srow.addView(eSuffix);
        Form.note(act, adv, "单位是「分」的接口，倍率填 0.01 就变成元。");

        /* ---------- 网站位置 ---------- */
        final TextView foreignChip = Form.chip(act,
                w0.foreign ? "🌐 境外网站（走代理）" : "中国网站（直连）", null);
        foreignChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                w0.foreign = !w0.foreign;
                foreignChip.setText(w0.foreign ? "🌐 境外网站（走代理）" : "中国网站（直连）");
            }
        });
        Form.label(act, adv, "网站位置");
        LinearLayout frow = Form.row(act, adv);
        frow.addView(foreignChip);

        Form.label(act, adv, "低值预警阈值（0 = 不预警）");
        final EditText eThreshold = Form.input(act, adv, "0",
                w0.threshold == 0 ? "" : trimNum(w0.threshold));
        eThreshold.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        Form.label(act, adv, "下次扣费日（可留空）");
        final EditText eRenew = Form.noSuggest(Form.input(act, adv,
                "2026-11-19 或 11-19", fmtRenewAt(w0.renewAt)));
        Form.note(act, adv, "填了之后，到期前 3 天会提醒你。");

        /* ---------- 点卡片后弹出的按钮 ---------- */
        Form.label(act, adv, "点卡片后弹出的按钮（地址留空 = 不显示）");
        Form.note(act, adv, "https:// 开头用浏览器打开；app: 指令切到 App 内界面"
                + "（app:refresh / app:home / app:settings / app:stats）。");

        final int NBTN = 4;
        final EditText[] eBtnName = new EditText[NBTN];
        final EditText[] eBtnUrl = new EditText[NBTN];
        final String[] presetHints = {
                "app:refresh", "app:home", "https://… 控制台", "https://… 充值页"
        };
        for (int i = 0; i < NBTN; i++) {
            String lb0 = (w0.btnLabels != null && i < w0.btnLabels.length)
                    ? w0.btnLabels[i] : "";
            String u0 = (w0.btnUrls != null && i < w0.btnUrls.length)
                    ? w0.btnUrls[i] : "";
            eBtnName[i] = Form.inline(act, "按钮 " + (i + 1) + " 名字",
                    lb0 == null ? "" : lb0, 1f);
            eBtnUrl[i] = Form.inline(act, presetHints[i], u0 == null ? "" : u0, 1.5f);
            LinearLayout rr = Form.row(act, adv);
            rr.addView(eBtnName[i]);
            LinearLayout.LayoutParams lpU = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f);
            lpU.leftMargin = dp(6);
            eBtnUrl[i].setLayoutParams(lpU);
            rr.addView(eBtnUrl[i]);
        }

        /* 常用指令一键填：省得用户手打 app:xxx */
        LinearLayout quick = Form.row(act, adv);
        final String[] quickNames = { "填刷新", "填主界面", "填设置", "填统计" };
        final String[] quickVals = { "app:refresh", "app:home", "app:settings", "app:stats" };
        for (int qi = 0; qi < quickNames.length; qi++) {
            final String qv = quickVals[qi];
            TextView q = Form.chip(act, quickNames[qi], null);
            q.setTextSize(11.5f);
            q.setMinHeight(dp(34));
            q.setPadding(dp(9), dp(5), dp(9), dp(5));
            if (qi > 0) {
                LinearLayout.LayoutParams lpQ = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lpQ.leftMargin = dp(6);
                q.setLayoutParams(lpQ);
            }
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

        /* ---------- 保存 ---------- */
        fd.onSave(new Runnable() {
            public void run() {
                WebCustom w = new WebCustom();
                w.name = eName.getText().toString().trim();
                w.url = normUrl(eUrl.getText().toString());
                w.mode = (mi0 < 0 && !modeTouched[0]) ? w0.mode : modeVals[modeIdx[0]];
                w.path = ePath.getText().toString().trim();
                w.pattern = ePattern.getText().toString().trim();
                w.start = eStart.getText().toString().trim();
                w.end = eEnd.getText().toString().trim();
                String m = eMethod.getText().toString().trim().toUpperCase();
                w.method = m.length() == 0 ? "GET" : m;
                w.charset = eCharset.getText().toString().trim();
                /* Key 与登录 Cookie 合并进「附加请求头」：填了 Key 就自动走 Bearer */
                String hdrs = eHeaders.getText().toString().trim();
                String kv = eKey.getText().toString().trim();
                /* 换了网站就别把旧凭据带过去 —— A 站的 Cookie / Bearer 发给 B 站，
                   既抓不对，也是把登录态泄露给了别人。 */
                boolean hostChanged = isEdit && !hostOf(w0.url).equals(hostOf(w.url));
                if (hostChanged) {
                    hdrs = stripHeader(stripHeader(hdrs, "Cookie"), "Authorization");
                    /* ⚠️ Key 也必须清。原来只清了 headers 里的 Authorization，
                       而下面还有一句「Key 有值时自动拼 Bearer」——
                       旧站点的 Key 立刻又被拼回去发给新域名，正是这段注释要避免的事。
                       Toast 说的"已清空"必须是真的。 */
                    kv = "";
                    w.key = "";
                    Toast.makeText(act, "网址换了，已清空旧的登录态与 Key，请重新填写",
                            Toast.LENGTH_SHORT).show();
                }
                w.headers = hdrs;
                w.key = kv;
                w.body = eBody.getText().toString().trim();
                w.suffix = eSuffix.getText().toString().trim();
                w.template = eTemplate.getText().toString().trim();
                w.unit = (ui0 < 0 && !unitTouched[0]) ? w0.unit : unitVals[unitIdx[0]];
                w.kind = (ki0 < 0 && !kindTouched[0]) ? w0.kind : kindVals[kindIdx[0]];
                w.foreign = w0.foreign;
                w.numMode = w0.numMode;     // 这一屏没有开关，原样带回去
                for (int bi = 0; bi < NBTN; bi++) {
                    w.btnLabels[bi] = eBtnName[bi].getText().toString().trim();
                    w.btnUrls[bi] = eBtnUrl[bi].getText().toString().trim();
                }
                /* 日期写法错就拦在这里，不往下走：静默存 0 等于提醒永远不响 */
                w.renewAt = parseRenewAt(eRenew.getText().toString().trim());
                if (w.renewAt < 0) {
                    Toast.makeText(act, "日期要写成 2026-11-19 或 11-19",
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                if (w.name.length() == 0) {
                    Toast.makeText(act, "名称不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (w.url.length() == 0) {
                    Toast.makeText(act, "网址不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (w.url.length() <= "https://".length()) {
                    Toast.makeText(act, "网址不完整，请填完整地址", Toast.LENGTH_SHORT).show();
                    return;
                }
                String sv = eScale.getText().toString().trim();
                if (sv.length() > 0) {
                    double v;
                    try {
                        v = Double.parseDouble(sv);
                    } catch (Exception e) {
                        Toast.makeText(act, "倍率要填数字，例如 0.01", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!(v > 0) || Double.isInfinite(v)) {
                        Toast.makeText(act, "倍率要大于 0（不缩放就留空）", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    w.scale = v;
                } else {
                    w.scale = 1.0;
                }
                String tv = eThreshold.getText().toString().trim();
                if (tv.length() == 0) {
                    w.threshold = 0;
                } else {
                    double v;
                    try {
                        v = Double.parseDouble(tv);
                    } catch (Exception e) {
                        Toast.makeText(act, "预警阈值要填数字，例如 10", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!(v >= 0) || Double.isInfinite(v)) {
                        Toast.makeText(act, "预警阈值要填 ≥ 0 的数字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    w.threshold = v;
                }
                /* 正则先编译一遍：写错了不该等到下次刷新才在卡片上报错 */
                if (w.pattern.length() > 0
                        && ("regex".equals(w.mode) || "auto".equals(w.mode))) {
                    try {
                        java.util.regex.Pattern.compile(w.pattern);
                    } catch (Exception e) {
                        Toast.makeText(act, "正则写错了：" + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                /* Key 有值时自动拼 Bearer（和界面文案一致）；
                   清空 Key 时要把残留的 Authorization 去掉 —— 否则输入框是空的，
                   请求里还在发旧 token，用户完全找不到"哪还在发 Key"。 */
                if (kv.length() > 0) {
                    w.headers = WebCustom.mergeHeader(w.headers, "Authorization", "Bearer " + kv);
                } else if (w0 != null && w0.key != null && w0.key.length() > 0) {
                    w.headers = stripHeader(w.headers, "Authorization");
                }

                /* 以**原始存档**为基准替换这一条。
                   别用 loadAll()+saveAll()：loadAll 会丢掉 ready() 不过的项，
                   saveAll 再整表覆盖 —— 编辑一条就把界面上看不见的旧数据全删了。 */
                WebCustom.putOne(act, w, isEdit ? w0.name : null, isEdit ? w0.url : null);
                Busy.run(act, "正在更新…", new Runnable() {
                    public void run() { renderWebs(); kick(); }
                });
                fd.dismiss();
            }
        });

        testBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                /* 用当前表单里的值试抓（不落库）—— 边填边试。
                   ⚠️ 倍率 / 后缀 / 模板 / Key 都要带上，否则「测试」显示的是
                   裸数字，和保存后卡片上的数字不是一回事。 */
                WebCustom w = new WebCustom();
                w.name = "test";
                w.url = normUrl(eUrl.getText().toString());
                w.mode = (mi0 < 0 && !modeTouched[0]) ? w0.mode : modeVals[modeIdx[0]];
                w.path = ePath.getText().toString().trim();
                w.pattern = ePattern.getText().toString().trim();
                w.start = eStart.getText().toString().trim();
                w.end = eEnd.getText().toString().trim();
                String m = eMethod.getText().toString().trim().toUpperCase();
                w.method = m.length() == 0 ? "GET" : m;
                w.charset = eCharset.getText().toString().trim();
                w.headers = eHeaders.getText().toString().trim();
                w.body = eBody.getText().toString().trim();
                w.unit = (ui0 < 0 && !unitTouched[0]) ? w0.unit : unitVals[unitIdx[0]];
                w.suffix = eSuffix.getText().toString().trim();
                w.template = eTemplate.getText().toString().trim();
                w.foreign = w0.foreign;
                String kv2 = eKey.getText().toString().trim();
                if (kv2.length() > 0) {
                    w.headers = WebCustom.mergeHeader(w.headers, "Authorization", "Bearer " + kv2);
                }
                try {
                    String sv2 = eScale.getText().toString().trim();
                    w.scale = sv2.length() == 0 ? 1.0 : Double.parseDouble(sv2);
                } catch (Exception e) {
                    w.scale = 1.0;
                }
                if (w.url.length() == 0) {
                    Toast.makeText(act, "先填网址再测试", Toast.LENGTH_SHORT).show();
                    return;
                }
                final WebCustom wt = w;
                /* 按钮置灰 + 改字：抓取最长 24s，期间界面看起来毫无反应，
                   用户会连点三次 → 三个线程并发抓 → 连弹三个结果框。 */
                testBtn.setEnabled(false);
                testBtn.setText("正在抓取…");
                Busy.show(act, "正在抓取…");
                new Thread(new Runnable() {
                    public void run() {
                        String title, body;
                        try {
                            WebCustom.Result r = wt.fetch(wt.foreign, 12000);
                            title = "测试成功";
                            body = "显示：" + r.display
                                    + "\n识别方式：" + r.how
                                    + "\n数值：" + r.value;
                        } catch (Throwable t) {
                            title = "测试失败";
                            body = t.getMessage() == null ? "未知错误" : t.getMessage();
                        }
                        final String ft = title, fb = body;
                        act.runOnUiThread(new Runnable() {
                            public void run() {
                                Busy.hide(act);
                                testBtn.setEnabled(true);
                                testBtn.setText("⟳ 测试抓取（用当前填的值试一下）");
                                if (act.isFinishing() || act.isDestroyed()) return;
                                new AlertDialog.Builder(act)
                                        .setTitle(ft)
                                        .setMessage(fb)
                                        .setPositiveButton("好", null)
                                        .show();
                            }
                        });
                    }
                }).start();
            }
        });

        fd.show();
    }

    /* ---------------- 两个平台弹窗共用的小工具 ---------------- */

    /** 找不到返回 -1。idxOf 返回 0，会把「老数据里的未知取值」静默改写成第一项。 */
    private static int idxOfOr(String v, String[] arr) {
        if (v == null) return -1;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) return i;
        }
        return -1;
    }

    /** 数字回显：1.0E7 这种科学计数法用户看不懂，整数就别带小数尾巴 */
    private static String trimNum(double v) {
        if (v == Math.floor(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    /**
     * 网址规整：去掉所有空白（含全角空格 \u3000 与不换行空格 \u00A0）并补 scheme。
     * 输入法在长 URL 中间插一个空格，请求就会直接失败，用户只看到「查询失败」，
     * 根本猜不到是空格。
     */
    private static String normUrl(String u) {
        if (u == null) return "";
        String s = u.replaceAll("[\\s\\u3000\\u00A0]+", "");
        if (s.length() == 0) return s;
        String l = s.toLowerCase();
        if (!l.startsWith("http://") && !l.startsWith("https://")) s = "https://" + s;
        return s;
    }

    /** 取主机名：换域名时判断「旧的登录态还能不能带过去」 */
    private static String hostOf(String url) {
        if (url == null) return "";
        String s = url.trim().toLowerCase();
        int i = s.indexOf("://");
        if (i >= 0) s = s.substring(i + 3);
        int j = s.indexOf('/');
        if (j >= 0) s = s.substring(0, j);
        int k = s.indexOf(':');
        if (k >= 0) s = s.substring(0, k);
        return s;
    }

    /** 删掉某一类请求头（一行一个，名字不区分大小写） */
    private static String stripHeader(String headers, String name) {
        if (headers == null || headers.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        String[] ps = headers.split("\n");
        for (int i = 0; i < ps.length; i++) {
            String ln = ps[i].trim();
            if (ln.length() == 0) continue;
            int c = ln.indexOf(':');
            if (c > 0 && ln.substring(0, c).trim().equalsIgnoreCase(name)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(ln);
        }
        return sb.toString();
    }

    /** 请求头里有没有 Cookie（决定「还没有登录态」那行文案） */
    private static boolean hasCookieHeader(String headers) {
        if (headers == null || headers.length() == 0) return false;
        String[] ps = headers.split("\n");
        for (int i = 0; i < ps.length; i++) {
            String ln = ps[i].trim().toLowerCase();
            if (ln.startsWith("cookie:") && ln.length() > 7) return true;
        }
        return false;
    }

    private static boolean sameStr(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.equals(b);
    }

    private static int idxOf(String v, String[] arr) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) return i;
        }
        return 0;
    }

    /**
     * 解析「下次扣费日」。空串 → 0（= 没填，不提醒）。
     *
     * 认两种写法：yyyy-MM-dd（2026-11-19）和 MM-dd（11-19）。
     * 只写月日时补当前年；补出来那天已经过去就算明年 —— 用户填「11-19」
     * 想的是下一个 11-19，不是今年那个已经过掉的。
     *
     * @return 毫秒时间戳；0 = 没填；-1 = 写法不对。
     *         调用处见到 -1 必须弹提示后 return，不能静默存 0 ——
     *         否则用户以为填上了，到期提醒其实永远不响。
     */
    private static long parseRenewAt(String s) {
        if (s == null) return 0;
        s = s.trim();
        if (s.length() == 0) return 0;
        boolean full = s.matches("\\d{4}-\\d{1,2}-\\d{1,2}");
        if (!full && !s.matches("\\d{1,2}-\\d{1,2}")) return -1;
        String[] p = s.split("-");
        int y = full ? Integer.parseInt(p[0])
                : java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        int mo = Integer.parseInt(p[full ? 1 : 0]);
        int d = Integer.parseInt(p[full ? 2 : 1]);
        if (y < 1970 || y > 2999 || mo < 1 || mo > 12 || d < 1 || d > 31) return -1;
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(y, mo - 1, d, 0, 0, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        /* Calendar 默认宽松，2-30 会被悄悄进位成 3-2：回读一遍，对不上就是日期不存在 */
        if (c.get(java.util.Calendar.YEAR) != y
                || c.get(java.util.Calendar.MONTH) != mo - 1
                || c.get(java.util.Calendar.DAY_OF_MONTH) != d) {
            return -1;
        }
        if (!full) {
            java.util.Calendar today = java.util.Calendar.getInstance();
            today.set(java.util.Calendar.HOUR_OF_DAY, 0);
            today.set(java.util.Calendar.MINUTE, 0);
            today.set(java.util.Calendar.SECOND, 0);
            today.set(java.util.Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() < today.getTimeInMillis()) {
                /* 当天不算过去：今天就是扣费日时仍是今年 */
                c.set(y + 1, mo - 1, d);
            }
        }
        return c.getTimeInMillis();
    }

    /** 下次扣费日回填：统一写成 yyyy-MM-dd；没填返回空串 */
    private static String fmtRenewAt(long ms) {
        if (ms <= 0) return "";
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date(ms));
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
        /* ⚠️ 必须清掉防抖保存任务：用户改完输入框立刻退出时，
           500ms 后的 saveTask 还会跑，持有已销毁的 Activity（泄漏），
           还会去碰已经不存在的 View。 */
        try { h.removeCallbacksAndMessages(null); } catch (Throwable ignored) { }
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
            t.setTextColor(ok ? color(R.attr.c_tx) : color(R.attr.c_tx2));
            t.setTextSize(13);
            t.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(t);

            /* 损坏的就不给"恢复"按钮了，但保留"删除" —— 用户总得能清掉它 */
            if (ok) {
                TextView rest = mkBtn("恢复", color(R.attr.c_accent));
                rest.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { askRestoreBackup(it); }
                });
                row.addView(rest);
            }

            TextView del = mkBtn("删除", color(R.attr.c_danger));
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
            public void run() {
                /* 必须连高级平台一起重画：恢复备份后 custom/web 的下标都变了，
                   而 renderWebs 里每个「删除」按钮捕获的是**旧**下标 ——
                   不重画的话，点删除会删掉新列表里同下标的另一个平台。 */
                renderCustoms(); renderKeys(); renderWebs(); kick(); renderBackups();
            }
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
        final boolean first = box.getChildCount() == 0;
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
            t.setTextColor(color(R.attr.c_tx));
            t.setTextSize(13);
            t.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(t);
            TextView edit = mkBtn("编辑", color(R.attr.c_accent));
            edit.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { customDialog(idx); }
            });
            row.addView(edit);
            TextView del = mkBtn("删除", color(R.attr.c_danger));
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                /* 危险操作：先确认再动手 —— 列表在删除后整表重建、行会上移，
                   用户"以为没生效又点一下"，第二次点中的就是下一条那行的删除按钮。 */
                final Runnable doDelete = new Runnable() {
                    public void run() {

                        List<BalanceFetcher.Custom> list = BalanceFetcher.loadCustom(act);
                        if (idx < list.size()) {
                            /* 按内容删，不整表写回 —— loadCustom() 已经滤掉了隐藏条目，
                               拿它整表覆盖会把那些条目永久删掉。 */
                            BalanceFetcher.Custom del = list.get(idx);
                            BalanceFetcher.removeCustom(act, del.name, del.url, del.text);
                            /* 同步清掉这个平台的 Key（孤儿 Key 会让统计页出现 custom:N）。
                               ⚠️ 后面的平台会前移（custom:1 → custom:0），
                               所以要把 idx 之后的所有平台 Key 都重排 —— 干脆全清重标。 */
                            List<KeyStore.ApiKey> all = KeyStore.all(act);
                            List<KeyStore.ApiKey> keep = new java.util.ArrayList<KeyStore.ApiKey>();
                            for (int ki = 0; ki < all.size(); ki++) {
                                KeyStore.ApiKey kk = all.get(ki);
                                String pf = kk.platform == null ? "" : kk.platform;
                                if (pf.startsWith("custom:")) {
                                    try {
                                        int pi = Integer.parseInt(pf.substring(7));
                                        if (pi == idx) continue;      // 被删平台的 Key，一起丢
                                        if (pi > idx) {               // 后面的平台前移一位
                                            kk.platform = "custom:" + (pi - 1);
                                        }
                                    } catch (Throwable ig) { continue; }
                                }
                                keep.add(kk);
                            }
                            KeyStore.save(act, keep);
                        }
                        Busy.run(act, "正在更新…", new Runnable() {
                            public void run() {
                                Ledger.shiftKeys(act, "custom:", idx);
                                renderCustoms(); renderKeys(); kick();
                            }
                        });
                
                    }
                };
                confirmDelete("删除这个平台？",
                        "它的密钥和余额历史会一起删掉，无法恢复。", doDelete);
            }
            });
            row.addView(del);
            box.addView(row);
        }
        if (cs.isEmpty()) {
            /* web 区同位置一直有这条提示，自定义区缺 —— 用户看到的是一段突兀的空档，
               分不清是"还没加"还是"坏了"。 */
            TextView t = new TextView(act);
            t.setText("还没有添加。适合任意 OpenAI 兼容接口；只有地址、没有余额接口的服务，"
                    + "可以只填「显示文字」当静态卡。");
            t.setTextColor(color(R.attr.c_tx3));
            t.setTextSize(12);
            box.addView(t);
        }
        if (first) Anim.staggerChildren(box, 0, 10);
    }

    /**
     * 危险操作前的二次确认。
     *
     * <p>删除是**不可逆**的：Key 用 Keystore 加密存着，删了只能去平台后台重新生成；
     * 平台删掉还会连带清掉它的 Key 和账本历史。而列表在删除后会**整表重建、行会上移** ——
     * 用户"以为没生效又点一下"，第二次点中的就是下一条（同坐标）那行的删除按钮。
     * 确认框同时也是这次误触的刹车。
     */
    private void confirmDelete(String title, String message, final Runnable go) {
        try {
            new android.app.AlertDialog.Builder(act)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) { go.run(); }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            /* 弹窗都弹不出来时宁可不动手 —— 删除是不可逆的 */
            BalanceFetcher.diag(act, "确认框创建失败，已取消删除：" + t);
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
     * 模板选择：三层引导，确保用户知道"模板能做什么 / 哪个能自动查 / 接下来干什么"。
     *
     * 设计依据：模板本身只是"预填元数据"，但用户很容易以为"选了就能用"。
     * 所以第一次点进来先讲清边界，列表里标出能力，选中后再给分步动作。
     */
    private void showTemplatePicker(final Runnable after) {
        SharedPreferences sp = act.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
        boolean seen = sp.getBoolean("tpl_intro_seen", false);
        if (seen) { templateList(after); return; }

        sp.edit().putBoolean("tpl_intro_seen", true).commit();
        new AlertDialog.Builder(act)
                .setTitle("模板是什么？")
                .setMessage("模板会帮你**自动填好**：\n"
                        + "　平台名称、币种、制式、控制台地址\n\n"
                        + "但它**不会凭空变出余额** —— 余额接口不是行业标准，\n"
                        + "大多数平台根本没有公开的「查余额」API。\n\n"
                        + "所以模板分两种：\n"
                        + "　✓ 可自动查余额：填了 Key 就能查（目前只有少数平台）\n"
                        + "　仅记录：卡片显示「余额见控制台」，点一下跳到控制台自己看\n\n"
                        + "选「仅记录」不会报错，也不会在卡片上显示红字。")
                .setPositiveButton("开始选择", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { templateList(after); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void templateList(final Runnable after) {
        final PlatformTemplates.Tpl[] all = PlatformTemplates.ALL;
        String[] names = new String[all.length];
        for (int i = 0; i < all.length; i++) names[i] = PlatformTemplates.label(all[i]);

        new AlertDialog.Builder(act)
                .setTitle("选择平台")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        addFromTemplate(all[which], after);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 按模板建平台。
     *
     * 关键：**没有确认端点的模板一律建成"静态卡"**（text 模式，url 留空）——
     * 这样卡片显示「余额见控制台」而不是查询失败的红字。
     * 用户填了接口地址后才真正去请求。
     */
    private void addFromTemplate(final PlatformTemplates.Tpl t, final Runnable after) {
        final boolean auto = PlatformTemplates.autoQuery(t);
        BalanceFetcher.Custom c = new BalanceFetcher.Custom();
        c.name = t.name;
        c.unit = t.unit;
        c.console = t.console == null ? "" : t.console;   // 点卡片能直接跳过去
        c.kind = auto ? t.kind : "asset";     // 静态卡不进总资产
        if (auto) {
            c.url = t.url;
            c.path = t.paths;
        } else {
            c.url = "";                        // 不发请求
            c.text = "余额见控制台";
        }
        List<BalanceFetcher.Custom> list = BalanceFetcher.loadCustom(act);
        list.add(c);
        BalanceFetcher.saveCustom(act, list);
        renderCustoms();
        renderKeys();
        kick();

        if (auto) {
            new AlertDialog.Builder(act)
                    .setTitle("✅ " + t.name + " 已添加")
                    .setMessage("这个平台**可以自动查余额**，还差一步：填 API Key。\n\n"
                            + "1. 打开控制台，复制 API Key\n"
                            + "2. 回到这里，在这个平台下面点「添加 Key」\n"
                            + "3. 粘进去保存 —— 之后下拉刷新就能看到余额了\n\n"
                            + "控制台：" + t.console)
                    .setPositiveButton("去填 Key", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            /* 滚到密钥区并提示：直接把用户送到该平台的 Key 分组 */
                            scrollToKeys();
                        }
                    })
                    .setNegativeButton("我知道了", null)
                    .show();
        } else {
            new AlertDialog.Builder(act)
                    .setTitle(t.name + " 已添加（仅记录）")
                    .setMessage("这个平台**没有公开的余额接口**，所以卡片上会显示\n"
                            + "「余额见控制台」，不会自动查余额、也不会报错。\n\n"
                            + "用法：点卡片 → 直接打开控制台看余额。\n\n"
                            + "如果你知道它的接口地址，可以点「编辑」填进去，\n"
                            + "填了之后就会真的去查了。\n\n"
                            + "控制台：" + t.console
                            + (t.note != null && t.note.length() > 0 ? "\n\n" + t.note : ""))
                    .setPositiveButton("去填接口地址", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            List<BalanceFetcher.Custom> l2 = BalanceFetcher.loadCustom(act);
                            customDialog(l2.size() - 1);
                        }
                    })
                    .setNegativeButton("就这样用", null)
                    .show();
        }
        if (after != null) after.run();
    }

    /** 把用户带到密钥列表（填 Key 的地方） */
    private void scrollToKeys() {
        try {
            /* 原来只对 LinearLayout 调 requestFocus —— 它默认不可获焦，等于没做任何事，
               而且密钥区默认是收起的，用户点「去填 Key」只看到一个 Toast。 */
            View keyCard = root.findViewById(R.id.key_card);
            TextView keyToggle = (TextView) root.findViewById(R.id.key_toggle);
            if (keyCard != null && keyCard.getVisibility() != View.VISIBLE) {
                prefs().edit().putBoolean("key_opened", true).apply();
                Anim.expand(keyCard);
                if (keyToggle != null) keyToggle.setText(act.getString(R.string.toggle_collapse));
            }
            final View box = keyCard != null ? keyCard : root.findViewById(R.id.key_fields);
            if (box != null) {
                box.post(new Runnable() {
                    public void run() {
                        try {
                            box.requestRectangleOnScreen(new android.graphics.Rect(
                                    0, 0, box.getWidth(), box.getHeight()), true);
                        } catch (Throwable ignored) { }
                    }
                });
            }
            Toast.makeText(act, "在「API 密钥与平台」里找到该平台，点「添加 Key」",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) { }
    }

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
        if (isEdit && editIdx >= all0.size()) {
            /* 越界时别硬开一个空表单 —— 保存时会变成「新增一条」，
               用户以为在改，其实多了一条同名平台。 */
            Toast.makeText(act, "平台列表已经变了，退出重进再试", Toast.LENGTH_SHORT).show();
            return;
        }
        final BalanceFetcher.Custom c0 = isEdit
                ? all0.get(editIdx) : new BalanceFetcher.Custom();

        final FormDialog fd = new FormDialog(act,
                isEdit ? "编辑自定义平台" : "添加自定义平台", "保存");
        final LinearLayout box = fd.box;

        /* 字段顺序照「用户填表的顺序」排：先它是谁（名称）、在哪（URL）、
           怎么进（Key）、取哪个数（路径）、怎么显示（币种/制式/显示文字）。
           每行只在标签里点一句关键约束，长解释挪进 note。 */

        Form.label(act, box, "名称");
        final EditText eName = Form.input(act, box, "比如：WorkBuddy", c0.name);

        Form.label(act, box, "URL（免费服务可留空）");
        final EditText eUrl = Form.input(act, box, "https://api.example.com/v1", c0.url);
        Form.uri(eUrl);      // URI 变体 + 关联想：输入法不会自动大写、往长 URL 里插空格

        /* Key 有两个入口，但只有一个是**真的在被使用的**：fillCustom 里
           KeyStore（设置页「API 密钥」）优先，Custom.key 只是没有密钥记录时的兜底。
           界面上不把这件事说清，用户就会在弹窗里填一个新的、发现不生效、
           而且怎么都找不到原因。 */
        final boolean keyManaged = isEdit
                && !KeyStore.get(act, "custom:" + editIdx).isEmpty();
        Form.label(act, box, keyManaged
                ? "API Key（已由「API 密钥」管理，这里改不动）" : "API Key（可选）");
        final EditText eKey = Form.secret(act, box, keyManaged ? null : "sk-…（可留空）", c0.key);
        if (keyManaged) {
            eKey.setEnabled(false);
            eKey.setTextColor(Theme.c(act, R.attr.c_tx3));
            Form.note(act, box, "这条平台已经挂了一个密钥，以「API 密钥」里的为准。"
                    + "要换 Key 请去那里改。");
        } else if (!isEdit) {
            Form.note(act, box, "填在这里的 Key 只给这个平台用；"
                    + "想要统一管理（一个平台挂多把），去设置页「API 密钥」里加。");
        }

        Form.label(act, box, "余额字段路径（JSON 路径，如 data.balance）");
        final EditText ePath = Form.noSuggest(Form.input(act, box, "可留空，自动识别", c0.path));

        /* ---------- 币种 / 制式：两个点一下就换的胶囊 ---------- */
        /* 只留 CNY / USD：「纯数值」在这个弹窗没有后缀输入框，
           选了也还是显示 ¥12，属于假选项。 */
        final String[] unitVals = { "CNY", "USD" };
        final String[] unitNames = { "币种: CNY", "币种: USD" };
        final int ui0 = idxOfOr(c0.unit, unitVals);
        final int[] unitIdx = { ui0 < 0 ? 0 : ui0 };
        final boolean[] unitTouched = { false };
        final TextView unitChip = Form.chip(act,
                ui0 < 0 ? ("币种: " + ("NONE".equals(c0.unit) ? "纯数值" : c0.unit))
                        : unitNames[unitIdx[0]], null);
        unitChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                unitTouched[0] = true;
                unitIdx[0] = (unitIdx[0] + 1) % unitVals.length;
                unitChip.setText(unitNames[unitIdx[0]]);
            }
        });

        final String[] kindVals = { "balance", "asset" };
        final String[] kindNames = { "制式: 金额", "制式: 额度" };
        final int ki0 = idxOfOr(c0.kind, kindVals);
        final int[] kindIdx = { ki0 < 0 ? 0 : ki0 };
        final boolean[] kindTouched = { false };
        final TextView kindChip = Form.chip(act,
                ki0 < 0 ? ("制式: " + c0.kind) : kindNames[kindIdx[0]], null);
        kindChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                kindTouched[0] = true;
                kindIdx[0] = (kindIdx[0] + 1) % kindVals.length;
                kindChip.setText(kindNames[kindIdx[0]]);
            }
        });

        LinearLayout chipRow = Form.row(act, box);
        chipRow.addView(unitChip);
        LinearLayout.LayoutParams lpKind = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpKind.leftMargin = dp(8);
        kindChip.setLayoutParams(lpKind);
        chipRow.addView(kindChip);
        Form.note(act, box, "金额 = 计入总资产；额度 = 只在卡片上展示，不进汇总。");

        Form.label(act, box, "显示文字（不填 URL 时用，默认「免费」）");
        final EditText eText = Form.input(act, box, "例如: 免费 / 不限量", c0.text);

        /* ---------- 新增时才给「从模板开始」 ---------- */
        if (!isEdit) {
            TextView tpl = Form.wide(act, "📋 从常见平台模板开始", new View.OnClickListener() {
                public void onClick(View v) {
                    /* 模板自己会落库一条并可能再开编辑弹窗 —— 这个「添加」弹窗
                       必须跟着关掉，否则用户回到最底层再点保存又多一条。 */
                    showTemplatePicker(new Runnable() {
                        public void run() { fd.dismiss(); }
                    });
                }
            });
            fd.add(tpl, 14);
            Form.note(act, box,
                    "自动填好名称、币种、控制台地址。不会自动查余额 —— "
                            + "多数平台没有公开的余额接口，选「仅记录」时卡片上写「余额见控制台」，点卡片跳过去看。");
        }

        /* ---------- 更多：低频字段折叠，不占首屏 ---------- */
        final LinearLayout more = fd.collapsible("▸ 更多（控制台 / 预警阈值）",
                "▾ 更多（控制台 / 预警阈值）");
        Form.label(act, more, "控制台地址（可留空）");
        final EditText eConsole = Form.input(act, more, "https://…", c0.console);
        Form.note(act, more, "点余额卡片 →「控制台」会打开这个地址。");
        Form.label(act, more, "低值预警阈值（0 = 不预警）");
        final EditText eThreshold = Form.input(act, more, "0",
                c0.threshold == 0 ? "" : trimNum(c0.threshold));
        eThreshold.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Form.note(act, more, "低于这个值时后台刷新会发通知。");

        Form.label(act, more, "下次扣费日（可留空）");
        final EditText eRenew = Form.noSuggest(Form.input(act, more,
                "2026-11-19 或 11-19", fmtRenewAt(c0.renewAt)));
        Form.note(act, more, "填了之后，到期前 3 天会提醒你。");

        fd.onSave(new Runnable() {
            public void run() {
                BalanceFetcher.Custom c = new BalanceFetcher.Custom();
                c.name = eName.getText().toString().trim();
                c.url = normUrl(eUrl.getText().toString());
                /* 被禁用的框里放着的是 KeyStore 那份的明文，别拿它回写 c.key */
                c.key = keyManaged ? c0.key : eKey.getText().toString().trim();
                c.path = ePath.getText().toString().trim();
                c.text = eText.getText().toString().trim();
                /* 用户没点过 chip 就保留原值 —— 老数据里有 sub / free / usage
                   这些制式，归一成 balance 会把「额度」算进总资产。 */
                c.unit = (ui0 < 0 && !unitTouched[0]) ? c0.unit : unitVals[unitIdx[0]];
                c.kind = (ki0 < 0 && !kindTouched[0]) ? c0.kind : kindVals[kindIdx[0]];
                c.console = eConsole.getText().toString().trim();
                /* 后缀这一屏没有输入框，编辑时保留旧值，别悄悄清空 */
                c.suffix = isEdit && c0.suffix != null ? c0.suffix : "";
                /* 日期写法错就拦在这里，不往下走：静默存 0 等于提醒永远不响 */
                c.renewAt = parseRenewAt(eRenew.getText().toString().trim());
                if (c.renewAt < 0) {
                    Toast.makeText(act, "日期要写成 2026-11-19 或 11-19",
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                String tv = eThreshold.getText().toString().trim();
                if (tv.length() == 0) {
                    c.threshold = 0;
                } else {
                    double v;
                    try {
                        v = Double.parseDouble(tv);
                    } catch (Exception e) {
                        /* 以前这里 catch 后直接归零 = 悄悄关掉预警，用户以为设成了 1000 */
                        Toast.makeText(act, "预警阈值要填数字，例如 10 或 10.5",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!(v >= 0) || Double.isInfinite(v)) {
                        Toast.makeText(act, "预警阈值要填 ≥ 0 的数字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    c.threshold = v;
                }

                if (c.name.length() == 0) {
                    Toast.makeText(act, "名称不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                /* 静态卡（只写显示文字、不查接口）本来就允许空 URL ——
                   判定标准和 Custom.ready() 一致，别把「免费 / 不限量」这种卡堵死。 */
                if (c.url.length() == 0 && c.text.length() == 0) {
                    Toast.makeText(act, "URL 和「显示文字」至少填一个", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (c.url.length() > 0 && c.url.length() <= "https://".length()) {
                    Toast.makeText(act, "URL 不完整，请填完整地址", Toast.LENGTH_SHORT).show();
                    return;
                }

                /* ⚠️ 必须继承原始密文与扣费日 —— 新建的 Custom 里这两项都是空/0：
                   - rawKey 为空时序列化会落到 enc("")，把磁盘上的密文真正写成空串。
                     换机/重装后 Keystore 解不开，"改个显示文字再保存"就把 Key 永久废掉，
                     换回原设备也救不回来。
                   - renewAt 为 0 会让「还有 N 天」和到期提醒静默消失。 */
                if (isEdit) {
                    c.rawKey = c0.rawKey;
                    c.renewAt = c0.renewAt;
                    c.console = c0.console;
                }

                /* 以**原始存档**为基准替换这一条（按内容认出目标，不认下标），
                   原因同高级平台：整表写回会把 ready() 不过的旧数据一起删掉。 */
                BalanceFetcher.putCustom(act, c,
                        isEdit ? c0.name : null, isEdit ? c0.url : null,
                        isEdit ? c0.text : null);
                Busy.run(act, "正在保存…", new Runnable() {
                    public void run() { renderCustoms(); renderKeys(); kick(); }
                });
                fd.dismiss();
            }
        });
        fd.show();
    }

    /** 安全与密码区块（与设置页原实现等价，内联在密钥区同层之后） */
    private void buildSecuritySection() {
        /* 挂进「其他」折叠容器：安全设置属于低频入口，和其它未分类项放一起。
           more_box 取不到（老布局）时退回原来的位置，不至于整块消失。 */
        ViewGroup parent = (ViewGroup) root.findViewById(R.id.more_box);
        if (parent == null) {
            View keyCard = root.findViewById(R.id.key_card);
            parent = keyCard == null ? null : (ViewGroup) keyCard.getParent();
        }
        if (parent == null) return;

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        box.setLayoutParams(lp);

        TextView title = new TextView(act);
        title.setText("安全");
        title.setTextColor(color(R.attr.c_tx));      // 与其它分区标题一致（14sp + 主文字色）
        title.setTextSize(14);
        title.setTypeface(null, Typeface.BOLD);
        box.addView(title);

        final TextView btn = mkBtn(Lock.isSet(act) ? "密码保护已开启（点击修改或清除）" : "设置密码保护",
                color(R.attr.c_accent));
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
        q.setTextColor(color(R.attr.c_tx2));
        q.setTextSize(13);
        panel.addView(q);
        panel.addView(ans);
        Theme.skin(act, panel);   // 弹窗是独立窗口，Activity 那棵树遍历不到
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
            ps.setTextColor(Theme.c(ctx, on ? R.attr.c_accent : R.attr.c_tx3));
        } catch (Throwable ignored) { }
    }
}
