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

    public SettingsBinder(Activity a, View r, Runnable onChanged) {
        this.act = a; this.root = r; this.onChanged = onChanged;
    }

    private int dp(float v) { return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f); }
    private int color(int id) { return act.getColor(id); }
    private SharedPreferences prefs() {
        return act.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
    }

    public void bind() {
        // ---- 刷新间隔（修改即保存） ----
        eFg = (EditText) root.findViewById(R.id.e_fg_min);
        eBg = (EditText) root.findViewById(R.id.e_bg_min);
        if (eFg != null) eFg.setText(String.valueOf(RefreshScheduler.fgMinutes(act)));
        if (eBg != null) eBg.setText(String.valueOf(RefreshScheduler.bgMinutes(act)));
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

        View autostart = root.findViewById(R.id.btn_autostart);
        if (autostart != null) autostart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { Autostart.ensure(act, true); }
        });

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
                    keyCard.setVisibility(now ? View.VISIBLE : View.GONE);
                    prefs().edit().putBoolean("key_opened", now).apply();
                    keyToggle.setText(now ? "收起 ▴" : "展开 ▾");
                    if (now) keyFadeIn(keyCard);
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
                    sb.setVisibility(vis ? View.GONE : View.VISIBLE);
                    if (!vis) sb.requestFocus();
                }
            });
            sb.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                    search = v.getText() == null ? "" : v.getText().toString();
                    renderKeys();
                    return true;
                }
            });
        }

        // ---- 隐藏 API（默认收起）----
        final View hideCard = root.findViewById(R.id.hide_card);
        final TextView hideToggle = (TextView) root.findViewById(R.id.hide_toggle);
        if (hideToggle != null) hideToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean now = hideCard.getVisibility() != View.VISIBLE;
                hideCard.setVisibility(now ? View.VISIBLE : View.GONE);
                hideToggle.setText(now ? "收起 ▴" : "展开 ▾");
                if (now) { renderHides(); keyFadeIn(hideCard); }
            }
        });

        // ---- 自定义平台 ----
        View addCustom = root.findViewById(R.id.btn_add_custom);
        if (addCustom != null) addCustom.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { addCustomDialog(); }
        });
        renderCustoms();

        // ---- 安全与密码（内联，与密钥区同级）----
        buildSecuritySection();

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
                        + "GitHub：github.com/hesanyue50-lang/balance-widget");
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
    }

    private void keyFadeIn(View v) {
        v.setAlpha(0f);
        v.setTranslationY(-dp(10));
        v.animate().alpha(1f).translationY(0f).setDuration(200).start();
    }

    private void saveIntervals() {
        int f = RefreshScheduler.FG_DEFAULT_MIN, b = RefreshScheduler.BG_DEFAULT_MIN;
        try { f = Integer.parseInt(eFg.getText().toString().trim()); } catch (Exception ig) { }
        try { b = Integer.parseInt(eBg.getText().toString().trim()); } catch (Exception ig) { }
        RefreshScheduler.setIntervals(act, f, b);
    }

    // ---------------- 密钥列表 ----------------

    private String search = "";
    private final java.util.List<View> keyBlocks = new java.util.ArrayList<View>();
    private final java.util.List<String> keyBlockKeys = new java.util.ArrayList<String>();

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

        TextView del = mkBtn("删除", color(R.color.danger));
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                KeyStore.remove(act, k.id);
                renderKeys();
                kick();
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

        new AlertDialog.Builder(act)
                .setTitle(isNew ? "添加 Key" : "编辑 Key")
                .setView(panel)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
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
                        renderKeys();
                        kick();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 隐藏 API ----------------

    private void renderHides() {
        LinearLayout box = (LinearLayout) root.findViewById(R.id.hide_fields);
        if (box == null) return;
        box.removeAllViews();
        List<KeyStore.ApiKey> aks = KeyStore.all(act);
        for (int i = 0; i < aks.size(); i++) {
            final KeyStore.ApiKey ak = aks.get(i);
            if (!ak.isConfigured()) continue;
            BalanceFetcher.Preset p = BalanceFetcher.presetOf(ak.platform);
            String pname = p == null ? ak.platform : p.name;
            String lb = ak.label == null ? "" : ak.label.trim();
            String name = (lb.length() > 0 && !"默认".equals(lb)) ? pname + " · " + lb : pname;

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
            swCard.setChecked(ak.hideCard);
            swCard.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][] { { android.R.attr.state_checked }, { -android.R.attr.state_checked } },
                    new int[] { 0xFF3D6FD6, 0xFFC6CDD6 }));
            swCard.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            swCard.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    ak.hideCard = on;
                    KeyStore.update(act, ak);
                    BalanceFetcher.diag(act, "hideCard 写 " + ak.id + " = " + on);
                    kick();
                    notifyChanged();
                }
            });
            row.addView(swCard);

            Switch swStat = new Switch(act);
            swStat.setText("统计");
            swStat.setTextSize(11);
            swStat.setTextColor(color(R.color.tx2));
            swStat.setChecked(!ak.draw);
            swStat.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][] { { android.R.attr.state_checked }, { -android.R.attr.state_checked } },
                    new int[] { 0xFF3D6FD6, 0xFFC6CDD6 }));
            swStat.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            swStat.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    ak.draw = !on;
                    KeyStore.update(act, ak);
                    notifyChanged();
                }
            });
            row.addView(swStat);
            box.addView(row);
        }
    }

    // ---------------- 自定义平台 ----------------

    private void renderCustoms() {
        LinearLayout box = (LinearLayout) root.findViewById(R.id.custom_fields);
        if (box == null) return;
        box.removeAllViews();
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
                    renderCustoms();
                    renderKeys();
                    kick();
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
                        renderCustoms();
                        renderKeys();
                        kick();
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
                                    if (which == 0) LockDialog.setup(act, null);
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
}
