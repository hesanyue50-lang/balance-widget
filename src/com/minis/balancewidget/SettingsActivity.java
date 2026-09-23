package com.minis.balancewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends Activity {

    private static final String TAG = "BalanceWidget";

    private LinearLayout customBox;
    private EditText eFg;
    private EditText eBg;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private EditText mkInput(String hint, String value, int type, int topMargin) {
        EditText et = new EditText(this);
        et.setHint(hint);
        if (value != null) et.setText(value);
        et.setTextSize(13);
        et.setTextColor(getColor(R.color.tx));
        et.setHintTextColor(getColor(R.color.hint_c));
        et.setInputType(type);
        et.setSingleLine(true);
        /* ⚠️ 顺序很重要：setSingleLine() 内部会重设 inputType，
           从而把已经设好的密码遮罩冲掉 —— 这就是「密钥明明配了密码还是显示明文」的真因。
           所以遮罩必须放在 setSingleLine 之后。 */
        if ((type & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0) {
            et.setTransformationMethod(PasswordTransformationMethod.getInstance());
        }
        et.setBackgroundResource(R.drawable.input_bg);
        et.setPadding(dp(20), dp(19), dp(20), dp(19));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMargin);
        et.setLayoutParams(lp);
        return et;
    }

    private TextView mkText(String s, int color, float size, int topMargin) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(color);
        tv.setTextSize(size);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMargin);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** mkText 的横向版：宽度自适应。
        ⚠️ 默认的 mkText 是 MATCH_PARENT，放进横向容器会把自己撑满、
        把旁边的元素挤成 0 宽度（平台名消失就是这么来的）。 */
    private TextView mkTextWrap(String s, int color, float size) {
        TextView tv = mkText(s, color, size, 0);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    private void mkDot(LinearLayout head, int color) {
        View dot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dot.setLayoutParams(dlp);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        dot.setBackground(gd);
        head.addView(dot);
    }

    private TextView mkDotLabel(LinearLayout head, String text) {
        TextView name = new TextView(this);
        name.setText(text);
        name.setTextColor(getColor(R.color.tx));
        name.setTextSize(13);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = dp(8);
        name.setLayoutParams(nlp);
        head.addView(name);
        return name;
    }


    // ---------- 自定义平台表单 ----------

    // ---------- 自定义平台：列表 + 弹窗 ----------

    /** 重建自定义平台列表（每行一个，点击可编辑） */
    /** 制式取值兜底 */
    private static String kindOf(String k) {
        if (k == null) return "balance";
        for (int i = 0; i < BalanceFetcher.KIND_ORDER.length; i++) {
            if (BalanceFetcher.KIND_ORDER[i].equals(k)) return k;
        }
        return "balance";
    }

    /** 制式的中文名 */
    static String kindLabel(String k) {
        if ("sub".equals(k))   return "订阅制";
        if ("usage".equals(k)) return "后付费";
        if ("free".equals(k))  return "免费额度";
        return "余额制";
    }

    /** 查看密码的状态 + 重置入口 */
    private void buildSecuritySection() {
        View keyCard = findViewById(R.id.key_card);
        ViewGroup parent = keyCard == null ? null : (ViewGroup) keyCard.getParent();
        if (parent == null) return;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        box.setLayoutParams(lp);

        String st = Lock.isSet(this)
                ? "🔒 已启用密码保护 · 点小组件上的卡片看密钥需验证"
                    + (Lock.hasQuestion(this) ? " · 已设密保" : " · 未设密保问题")
                : "⚠️ 还没设查看密码 —— 设了以后，查看 API 密钥都要先验证";
        box.addView(mkText(st, getColor(Lock.isSet(this) ? R.color.tx2 : R.color.warn), 12f, 0));

        TextView btn = mkText(Lock.isSet(this) ? "重置查看密码" : "设置查看密码",
                getColor(R.color.accent_tx), 13f, 10);
        btn.setGravity(Gravity.CENTER);
        btn.setBackgroundResource(R.drawable.btn_primary);
        btn.setPadding(0, dp(12), 0, dp(12));
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (Lock.isSet(SettingsActivity.this)) LockDialog.reset(SettingsActivity.this);
                else LockDialog.setup(SettingsActivity.this, new LockDialog.OnPass() {
                    public void ok() { rebuildSecurity(); }
                });
            }
        });
        box.addView(btn);
        int at = parent.indexOfChild(keyCard);
        parent.addView(box, at < 0 ? parent.getChildCount() : at + 1);
    }

    /** 设置完密码后刷新状态文字（最简单的做法：重进一次设置页的这段） */
    private void rebuildSecurity() {
        try { recreate(); } catch (Throwable ignored) { }
    }

    // ---------- 密钥区块折叠（平台太多，全展开太长） ----------

    private void setupCollapsibleKeys() {
        final View card = findViewById(R.id.key_card);
        final TextView toggle = (TextView) findViewById(R.id.key_toggle);
        if (card == null || toggle == null) return;

        final SharedPreferences sp = getSharedPreferences(BalanceFetcher.PREFS, MODE_PRIVATE);
        boolean opened = sp.getBoolean("key_opened", false);
        card.setVisibility(opened ? View.VISIBLE : View.GONE);
        markKeyTitle(toggle, opened);
        if (opened) searchHandler.post(new Runnable() { public void run() { ensureKeyBlocks(); } });

        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean now = card.getVisibility() != View.VISIBLE;
                card.setVisibility(now ? View.VISIBLE : View.GONE);
                getSharedPreferences(BalanceFetcher.PREFS, MODE_PRIVATE)
                        .edit().putBoolean("key_opened", now).apply();
                markKeyTitle((TextView) v, now);
                if (now) {
                    ensureKeyBlocks();          // 展开时才懒加载密钥列表
                    card.setAlpha(0f);
                    card.setTranslationY(-dp(12));
                    card.animate().alpha(1f).translationY(0f).setDuration(220).start();
                }
            }
        });
    }

    private void markKeyTitle(TextView t, boolean opened) {
        t.setText(opened ? "收起" : "展开");
    }

    private void kickWidget() {
        try {
            Intent k = new Intent(this, BalanceWidgetProvider.class);
            k.setAction(BalanceWidgetProvider.ACTION_FORCE_REDRAW);
            sendBroadcast(k);
        } catch (Throwable ignored) { }
    }

    // ---------- 密钥列表（一个平台可挂多个 Key）----------

    /** 当前搜索关键字（空=不过滤） */
    private String searchQuery = "";

    /** 搜索防抖：停止输入 250ms 后才重建列表，避免每键全量 inflate 卡死输入法 */
    private final android.os.Handler searchHandler = new android.os.Handler();
    private final Runnable searchFilterTask = new Runnable() {
        public void run() { renderKeyList(); }
    };

    /** 刷新间隔即时保存任务（修改即保存，无需底部按钮） */
    private final Runnable saveIntervalsTask = new Runnable() {
        public void run() { saveIntervalsInstant(); }
    };

    private void saveIntervalsInstant() {
        int fg = parseMin(eFg, RefreshScheduler.FG_DEFAULT_MIN);
        int bg = parseMin(eBg, RefreshScheduler.BG_DEFAULT_MIN);
        RefreshScheduler.setIntervals(this, fg, bg);
        kickWidget();
    }

    /** 平台名 / 平台 id / 该平台任一 Key 的 label 是否命中搜索关键字 */
    private boolean matches(String q, String name, String plat) {
        if (name != null && name.toLowerCase().contains(q)) return true;
        if (plat != null && plat.toLowerCase().contains(q)) return true;
        List<KeyStore.ApiKey> ks = KeyStore.get(this, plat);
        for (int i = 0; i < ks.size(); i++) {
            KeyStore.ApiKey k = ks.get(i);
            if (k.label != null && k.label.toLowerCase().contains(q)) return true;
        }
        return false;
    }

    /** 左右滑回主界面对应 Tab（与主界面切换同语言，视觉同级） */
    private void gotoMain(String tab) {
        Intent it = new Intent(this, MainActivity.class);
        it.putExtra("goto_tab", tab);
        it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(it);
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }


    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    @Override
    protected void onDestroy() {
        searchHandler.removeCallbacks(searchFilterTask);   // 退出后不再重建列表
        super.onDestroy();
    }

    /** 已构建的平台块缓存（过滤时只切可见性，不重建，根治回车卡顿） */
    private final java.util.List<View> cachedBlocks = new java.util.ArrayList<View>();
    private final java.util.List<String> cachedKeys = new java.util.ArrayList<String>();
    private TextView noneView;

    /** 平台的可搜索关键字串（平台名 + id + 各 Key 标签，小写） */
    private String searchKey(String name, String plat) {
        StringBuilder sb = new StringBuilder();
        sb.append(name == null ? "" : name).append('\u0001').append(plat == null ? "" : plat);
        List<KeyStore.ApiKey> ks = KeyStore.get(this, plat);
        for (int i = 0; i < ks.size(); i++) sb.append('\u0001').append(ks.get(i).label);
        return sb.toString().toLowerCase();
    }

    /** 只切换已缓存块的可见性 —— O(n) 设 visibility，毫秒级，不 inflate */
    private void applyFilter() {
        String q = searchQuery == null ? "" : searchQuery.trim().toLowerCase();
        int shown = 0;
        for (int i = 0; i < cachedBlocks.size(); i++) {
            boolean hit = q.length() == 0 || cachedKeys.get(i).contains(q);
            cachedBlocks.get(i).setVisibility(hit ? View.VISIBLE : View.GONE);
            if (hit) shown++;
        }
        if (noneView != null) {
            boolean showNone = q.length() > 0 && shown == 0;
            noneView.setVisibility(showNone ? View.VISIBLE : View.GONE);
            if (showNone) noneView.setText("没有匹配「" + searchQuery + "」的平台或 Key");
        }
    }

    /** 「隐藏 API」折叠区：每平台两个开关（卡片隐藏 / 统计隐藏） */
    private void setupHideSection() {
        final View card = findViewById(R.id.hide_card);
        final TextView toggle = (TextView) findViewById(R.id.hide_toggle);
        if (card == null || toggle == null) return;
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean now = card.getVisibility() != View.VISIBLE;
                card.setVisibility(now ? View.VISIBLE : View.GONE);
                toggle.setText(now ? "收起" : "展开");
                if (now) {
                    buildHideList();
                    card.setAlpha(0f);
                    card.setTranslationY(-dp(12));
                    card.animate().alpha(1f).translationY(0f).setDuration(220).start();
                }
            }
        });
    }

    private void buildHideList() {
        LinearLayout box = (LinearLayout) findViewById(R.id.hide_fields);
        if (box == null) return;
        box.removeAllViews();
        List<KeyStore.ApiKey> aks = KeyStore.all(this);
        for (int i = 0; i < aks.size(); i++) {
            final KeyStore.ApiKey ak = aks.get(i);
            if (!ak.isConfigured()) continue;
            BalanceFetcher.Preset p = BalanceFetcher.presetOf(ak.platform);
            String pname = p == null ? ak.platform : p.name;
            String lb = ak.label == null ? "" : ak.label.trim();
            String name = (lb.length() > 0 && !"默认".equals(lb)) ? pname + " · " + lb : pname;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView nm = new TextView(this);
            nm.setText(name);
            nm.setTextColor(getColor(R.color.tx));
            nm.setTextSize(13);
            nm.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(nm);

            Switch swCard = new Switch(this);
            swCard.setText("卡片");
            swCard.setTextSize(11);
            swCard.setTextColor(getColor(R.color.tx2));
            swCard.setChecked(ak.hideCard);
            swCard.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    ak.hideCard = on;
                    KeyStore.update(SettingsActivity.this, ak);
                    kickWidget();
                }
            });
            row.addView(swCard);

            Switch swStat = new Switch(this);
            swStat.setText("统计");
            swStat.setTextSize(11);
            swStat.setTextColor(getColor(R.color.tx2));
            swStat.setChecked(!ak.draw);   // 开关语义=「在统计中隐藏」
            swStat.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    ak.draw = !on;
                    KeyStore.update(SettingsActivity.this, ak);
                }
            });
            row.addView(swStat);
            box.addView(row);
        }
    }

    /** 密钥块缓存是否已构建 / 构建时的数据版本 / 是否正在分片构建 */
    private boolean keyCacheBuilt = false;
    private long keyCacheVersion = -1;
    private boolean keyBuilding = false;
    private int buildCursor = 0, presetCount = 0, customCount = 0;

    private boolean keyCardVisible() {
        View c = findViewById(R.id.key_card);
        return c != null && c.getVisibility() == View.VISIBLE;
    }

    /** 数据变更时调用：作废缓存；若当前展开则立即分片重建 */
    private void renderKeyList() {
        keyCacheBuilt = false;
        if (keyCardVisible()) ensureKeyBlocks();
    }

    /** 展开时调用：有缓存且版本未变直接挂；否则分片构建（一次构建多次使用） */
    private void ensureKeyBlocks() {
        long ver = KeyStore.dataVersion;
        if (keyCacheBuilt && ver == keyCacheVersion) { attachCache(); return; }
        if (keyBuilding) return;
        keyBuilding = true;
        buildCursor = 0;
        cachedBlocks.clear();
        cachedKeys.clear();
        presetCount = BalanceFetcher.PRESETS.length;
        customCount = BalanceFetcher.loadCustom(this).size();
        LinearLayout box = (LinearLayout) findViewById(R.id.key_fields);
        if (box != null) box.removeAllViews();
        searchHandler.post(buildChunk);
    }

    /** 分片构建：每片最多 3 块并让出主线程，构建期间屏幕仍可滑动 */
    private final Runnable buildChunk = new Runnable() {
        public void run() {
            int done = 0;
            while (done < 3) {
                if (buildCursor < presetCount) {
                    int i = buildCursor++;
                    BalanceFetcher.Preset p = BalanceFetcher.PRESETS[i];
                    cachedBlocks.add(platformBlock(p.id, p.name, BalanceFetcher.COLORS[i], p.hint));
                    cachedKeys.add(searchKey(p.name, p.id));
                } else if (buildCursor - presetCount < customCount) {
                    int ci = buildCursor++ - presetCount;
                    List<BalanceFetcher.Custom> cs = BalanceFetcher.loadCustom(SettingsActivity.this);
                    if (ci < cs.size()) {
                        cachedBlocks.add(customBlock("custom:" + ci, cs.get(ci), ci));
                        cachedKeys.add(searchKey(cs.get(ci).name, "custom:" + ci));
                    }
                } else break;
                done++;
            }
            if (buildCursor < presetCount + customCount) { searchHandler.post(this); return; }
            keyBuilding = false;
            keyCacheBuilt = true;
            keyCacheVersion = KeyStore.dataVersion;
            attachCache();
        }
    };

    /** 把缓存块挂进容器（复用，不重建） */
    private void attachCache() {
        LinearLayout box = (LinearLayout) findViewById(R.id.key_fields);
        if (box == null) return;
        box.removeAllViews();
        for (int i = 0; i < cachedBlocks.size(); i++) box.addView(cachedBlocks.get(i));
        if (noneView == null) {
            noneView = new TextView(this);
            noneView.setTextColor(getColor(R.color.tx3));
            noneView.setTextSize(12);
            noneView.setPadding(0, dp(8), 0, dp(8));
        }
        noneView.setVisibility(View.GONE);
        box.addView(noneView);
        applyFilter();
    }

    /** 自定义平台一块：名称 + 配置/删除 + 它名下的 Key */
    private View customBlock(final String plat, final BalanceFetcher.Custom c, final int idx) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(18);
        wrap.setLayoutParams(wlp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        mkDot(head, 0xFF8B5CF6);

        TextView label = mkDotLabel(head, c.name.length() > 0 ? c.name : "自定义");
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView cfg = mkTextWrap("配置", getColor(R.color.accent), 11f);
        cfg.setPadding(dp(10), dp(5), dp(10), dp(5));
        cfg.setBackgroundResource(R.drawable.mini_btn_border);
        cfg.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showCustomDialog(idx, c); }
        });
        head.addView(cfg);

        TextView del = mkTextWrap("删除", getColor(R.color.danger), 11f);
        del.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(6);
        del.setLayoutParams(dlp);
        del.setBackgroundResource(R.drawable.mini_btn_border);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                /* 删平台时把它名下的 Key 记录一并清掉，
                   否则会留下永远查不到的孤儿条目 */
                List<KeyStore.ApiKey> mine = KeyStore.get(SettingsActivity.this, plat);
                for (int i = 0; i < mine.size(); i++) {
                    KeyStore.remove(SettingsActivity.this, mine.get(i).id);
                }
                List<BalanceFetcher.Custom> list =
                        BalanceFetcher.loadCustom(SettingsActivity.this);
                if (idx >= 0 && idx < list.size()) {
                    list.remove(idx);
                    BalanceFetcher.saveCustom(SettingsActivity.this, list);
                }
                renderKeyList();
                renderCustomList();
                kickWidget();
            }
        });
        head.addView(del);
        wrap.addView(head);

        List<KeyStore.ApiKey> mine2 = KeyStore.get(this, plat);
        if (mine2.isEmpty()) {
            wrap.addView(mkText("还没填 Key（点「配置」填）", getColor(R.color.tx3), 11.5f, 8));
        } else {
            for (int i = 0; i < mine2.size(); i++) wrap.addView(keyRow(mine2.get(i)));
        }
        return wrap;
    }

    private View platformBlock(final String platform, final String name, int color, String hint) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(18);
        wrap.setLayoutParams(wlp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        /* ⚠️ 必须显式给 MATCH_PARENT：容器若是 WRAP_CONTENT，
           里面的 weight 分不到空间，标题会被压成 0 宽度（就是"平台名消失"的原因） */
        head.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        mkDot(head, color);
        TextView label = mkDotLabel(head, name);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final java.util.ArrayList<String> siteLabels = new java.util.ArrayList<String>();
        final java.util.ArrayList<String> siteUrls = new java.util.ArrayList<String>();
        int nSites = BalanceFetcher.collectSites(platform, siteLabels, siteUrls);
        if (nSites > 0) {
            TextView site = mkTextWrap("控制台",
                    getColor(R.color.tx2), 11f);
            site.setPadding(dp(10), dp(5), dp(10), dp(5));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            slp.rightMargin = dp(6);
            site.setLayoutParams(slp);
            site.setBackgroundResource(R.drawable.mini_btn_border);
            site.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // 直达控制台（collectSites 控制台永远排第一），不弹子菜单
                    openUrl(siteUrls.get(0));
                }
            });
            head.addView(site);
        }

        // 充值按钮已按用户要求移除（控制台内可充值）
        final BalanceFetcher.Preset pt = BalanceFetcher.presetOf(platform);

        TextView add = mkTextWrap("+ 添加", getColor(R.color.accent), 11f);
        add.setPadding(dp(10), dp(5), dp(10), dp(5));
        add.setBackgroundResource(R.drawable.mini_btn_border);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { editKey(platform, null); }
        });
        head.addView(add);
        wrap.addView(head);

        java.util.List<KeyStore.ApiKey> mine = KeyStore.get(this, platform);
        if (mine.isEmpty()) {
            wrap.addView(mkText("还没填 Key", getColor(R.color.tx3), 11.5f, 8));
        } else {
            for (int i = 0; i < mine.size(); i++) wrap.addView(keyRow(mine.get(i)));
        }
        wrap.addView(mkText(hint, getColor(R.color.tx3), 10.5f, 6));
        return wrap;
    }

    private View keyRow(final KeyStore.ApiKey k) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        row.setLayoutParams(rlp);

        TextView tag = mkText(k.label.length() == 0 ? "默认" : k.label,
                getColor(R.color.tx), 12.5f, 0);
        tag.setPadding(0, dp(10), 0, dp(10));
        tag.setLayoutParams(new LinearLayout.LayoutParams(
                dp(66), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(tag);

        TextView kv = mkText(k.isConfigured() ? mask(k.key) : "未填写",
                getColor(R.color.tx3), 11.5f, 0);
        kv.setSingleLine(true);
        kv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(kv);

        TextView edit = mkTextWrap("编辑", getColor(R.color.accent), 11f);
        edit.setPadding(dp(10), dp(5), dp(10), dp(5));
        edit.setBackgroundResource(R.drawable.mini_btn_border);
        edit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { editKey(k.platform, k); }
        });
        row.addView(edit);

        TextView del = mkTextWrap("删除", getColor(R.color.danger), 11f);
        del.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(6);
        del.setLayoutParams(dlp);
        del.setBackgroundResource(R.drawable.mini_btn_border);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                KeyStore.remove(SettingsActivity.this, k.id);
                renderKeyList();
                kickWidget();
            }
        });
        row.addView(del);
        return row;
    }

    /** 新增 / 编辑一个 Key（多 Key 结构下，增删改都是即时生效的） */
    private void editKey(final String platform, final KeyStore.ApiKey existing) {
        final boolean isNew = (existing == null);
        final KeyStore.ApiKey src = isNew ? new KeyStore.ApiKey() : existing;

        ScrollView sc = new ScrollView(this);
        sc.setPadding(dp(16), dp(24), dp(16), dp(24));
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(24), dp(24), dp(24), dp(24));
        sc.addView(card);

        TextView title = mkText(isNew ? "添加 Key" : "编辑 Key", getColor(R.color.tx), 17, 0);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);

        card.addView(mkText("标签（区分同一平台的多个 Key）", getColor(R.color.tx2), 12, 16));
        final EditText eLabel = mkInput("如：主力 / 备用 / 项目A", src.label,
                InputType.TYPE_CLASS_TEXT, 6);
        card.addView(eLabel);

        card.addView(mkText("密钥", getColor(R.color.tx2), 12, 14));
        final EditText eKey = mkInput("sk-...", src.key,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 6);
        card.addView(eKey);

        // 阿里云百炼专用：AccessKey 输入框
        final EditText[] accessKeyInputs = new EditText[2];  // 用于在保存时访问
        final android.widget.RadioGroup[] modeRef = new android.widget.RadioGroup[1];
        final android.widget.RadioButton[] subRef = new android.widget.RadioButton[1];
        if ("dashscope".equals(platform)) {
            card.addView(mkText("阿里云 AccessKeyId（可选，用于查询余额）", getColor(R.color.tx2), 12, 14));
            final EditText eAccessKeyId = mkInput("LTAI...", src.accessKeyId,
                    InputType.TYPE_CLASS_TEXT, 6);
            card.addView(eAccessKeyId);
            accessKeyInputs[0] = eAccessKeyId;

            card.addView(mkText("阿里云 AccessKeySecret（可选）", getColor(R.color.tx2), 12, 14));
            final EditText eAccessKeySecret = mkInput("AccessKeySecret", src.accessKeySecret,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 6);
            card.addView(eAccessKeySecret);
            accessKeyInputs[1] = eAccessKeySecret;

            // 计费模式单选：余额制 / 订阅制
            card.addView(mkText("计费模式（决定用哪种方式查询）", getColor(R.color.tx2), 12, 14));
            android.widget.RadioGroup rgMode = new android.widget.RadioGroup(this);
            rgMode.setOrientation(android.widget.RadioGroup.HORIZONTAL);
            android.widget.RadioButton rbBal = new android.widget.RadioButton(this);
            rbBal.setText("余额制");
            rbBal.setTextSize(13);
            android.widget.RadioButton rbSub = new android.widget.RadioButton(this);
            rbSub.setText("订阅制");
            rbSub.setTextSize(13);
            rgMode.addView(rbBal);
            rgMode.addView(rbSub);
            rgMode.check("subscription".equals(src.planMode)
                    ? rbSub.getId() : rbBal.getId());
            card.addView(rgMode);
            card.addView(mkText("余额制：按量付费，查账户余额\n订阅制：Token Plan 套餐，查实例与到期时间（余量见控制台）",
                    getColor(R.color.tx3), 11, 4));
            final android.widget.RadioGroup rgModeRef = rgMode;
            final android.widget.RadioButton rbSubRef = rbSub;
            modeRef[0] = rgModeRef;
            subRef[0] = rbSubRef;

            card.addView(mkText("提示：AccessKey 在阿里云控制台 → AccessKey 管理中创建。配置后可自动查询百炼账户余额。",
                    getColor(R.color.tx3), 11, 8));
            
            // 添加"如何配置"帮助按钮
            TextView helpBtn = mkText("如何配置阿里云 AccessKey？", getColor(R.color.accent), 12, 12);
            helpBtn.setGravity(Gravity.CENTER);
            helpBtn.setBackgroundResource(R.drawable.mini_btn_border);
            helpBtn.setPadding(0, dp(10), 0, dp(10));
            helpBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAliyunAccessKeyHelp();
                }
            });
            card.addView(helpBtn);
        }

        card.addView(mkText("备注（可选）", getColor(R.color.tx2), 12, 14));
        final EditText eNote = mkInput("给自己看的说明", src.note, InputType.TYPE_CLASS_TEXT, 6);
        card.addView(eNote);

        card.addView(mkText("低余额预警阈值（0 = 不预警）", getColor(R.color.tx2), 12, 14));
        final EditText eThr = mkInput("0",
                src.threshold > 0 ? String.valueOf(src.threshold) : "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 6);
        card.addView(eThr);

        card.addView(mkText("预算（消费制用，0 = 不设）", getColor(R.color.tx2), 12, 14));
        final EditText eBudget = mkInput("0",
                src.budget > 0 ? String.valueOf(src.budget) : "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 6);
        card.addView(eBudget);

        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(sc);
        try {
            if (dlg.getWindow() != null) {
                dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }
        } catch (Throwable ignored) { }

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(20);
        btns.setLayoutParams(blp);

        TextView cancel = mkText("取消", getColor(R.color.tx2), 14f, 0);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackgroundResource(R.drawable.mini_btn_border);
        cancel.setPadding(0, dp(12), 0, dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clp.rightMargin = dp(6);
        cancel.setLayoutParams(clp);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        btns.addView(cancel);

        TextView save = mkText("保存", getColor(R.color.accent_tx), 14f, 0);
        save.setGravity(Gravity.CENTER);
        save.setTypeface(null, Typeface.BOLD);
        save.setBackgroundResource(R.drawable.btn_primary);
        save.setPadding(0, dp(12), 0, dp(12));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = dp(6);
        save.setLayoutParams(slp);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                KeyStore.ApiKey k = new KeyStore.ApiKey();
                k.id = src.id;
                k.label = eLabel.getText().toString().trim();
                k.key = eKey.getText().toString().trim();
                k.note = eNote.getText().toString().trim();
                try { k.threshold = Double.parseDouble(eThr.getText().toString().trim()); }
                catch (Exception ig) { k.threshold = 0; }
                try { k.budget = Double.parseDouble(eBudget.getText().toString().trim()); }
                catch (Exception ig) { k.budget = 0; }
                k.draw = src.draw;
                
                // 保存阿里云 AccessKey（如果是百炼平台）
                if ("dashscope".equals(platform) && accessKeyInputs[0] != null) {
                    k.accessKeyId = accessKeyInputs[0].getText().toString().trim();
                    k.accessKeySecret = accessKeyInputs[1].getText().toString().trim();
                    k.planMode = (modeRef[0] != null && subRef[0] != null
                            && modeRef[0].getCheckedRadioButtonId() == subRef[0].getId())
                            ? "subscription" : "balance";
                }

                boolean allowEmpty = "modelscope".equals(platform)
                        || "dashscope".equals(platform);
                if (k.key.length() == 0 && !allowEmpty) {
                    android.widget.Toast.makeText(SettingsActivity.this,
                            "密钥不能为空", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (isNew) KeyStore.add(SettingsActivity.this, platform, k);
                else       KeyStore.update(SettingsActivity.this, k);

                dlg.dismiss();
                renderKeyList();
                kickWidget();
            }
        });
        btns.addView(save);
        card.addView(btns);

        dlg.show();
        try {
            android.view.Window w = dlg.getWindow();
            if (w != null) {
                w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) { }
    }

    /** 打开外部链接（系统浏览器） */
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

    /** 脱敏：留头 6 位和尾 4 位 */
    private static String mask(String key) {
        if (key == null) return "";
        int n = key.length();
        if (n <= 12) return key.substring(0, Math.min(4, n)) + "\u2026";
        return key.substring(0, 6) + "\u2026" + key.substring(n - 4);
    }

    private void renderCustomList() {
        if (customBox == null) return;
        customBox.removeAllViews();
        List<BalanceFetcher.Custom> list = BalanceFetcher.loadCustom(this);
        if (list.isEmpty()) {
            customBox.addView(mkText(getString(R.string.custom_empty),
                    getColor(R.color.tx3), 11.5f, 8));
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            customBox.addView(customRow(i, list.get(i)));
        }
    }

    private View customRow(final int index, final BalanceFetcher.Custom c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.card_bg);
        row.setPadding(dp(24), dp(18), dp(14), dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(9);
        row.setLayoutParams(lp);
        mkDot(row, 0xFF94A3B8);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t1 = new TextView(this);
        t1.setText(c.name.length() > 0 ? c.name : getString(R.string.custom_unnamed));
        t1.setTextColor(getColor(R.color.tx));
        t1.setTextSize(14);
        col.addView(t1);

        StringBuilder sub = new StringBuilder(c.unit);
        if (c.threshold > 0) {
            sub.append(" · 预警 ").append(c.threshold == Math.floor(c.threshold)
                    ? String.valueOf((long) c.threshold) : String.valueOf(c.threshold));
        }
        TextView t2 = new TextView(this);
        t2.setText(sub.toString());
        t2.setTextColor(getColor(R.color.tx3));
        t2.setTextSize(11);
        col.addView(t2);
        row.addView(col);

        TextView del = new TextView(this);
        del.setText(R.string.custom_delete);
        del.setTextColor(getColor(R.color.danger));
        del.setTextSize(12);
        del.setPadding(dp(14), dp(10), dp(6), dp(10));
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                List<BalanceFetcher.Custom> list = BalanceFetcher.loadCustom(SettingsActivity.this);
                if (index < list.size()) list.remove(index);
                BalanceFetcher.saveCustom(SettingsActivity.this, list);
                renderCustomList();
                refreshWidget();
            }
        });
        row.addView(del);

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showCustomDialog(index, c); }
        });
        return row;
    }

    /** 添加 / 编辑自定义平台的弹窗（应用内样式，与设置页卡片一致） */
    private void showCustomDialog(final int index, final BalanceFetcher.Custom init) {
        final boolean editing = index >= 0;
        final BalanceFetcher.Custom src = (init != null) ? init : new BalanceFetcher.Custom();
        final String[] unit = { "USD".equals(src.unit) ? "USD" : "CNY" };
        final String[] kind = { kindOf(src.kind) };
        final String[] suffix = { src.suffix == null ? "" : src.suffix };

        ScrollView sc = new ScrollView(this);
        sc.setPadding(dp(16), dp(24), dp(16), dp(24));

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(27), dp(27), dp(27), dp(27));
        sc.addView(card);

        TextView title = mkText(getString(editing ? R.string.custom_edit_title
                : R.string.custom_add_title), getColor(R.color.tx), 17, 0);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);

        card.addView(mkText(getString(R.string.custom_lbl_name), getColor(R.color.tx2), 12, 18));
        final EditText eName = mkInput(getString(R.string.custom_name_ph), src.name,
                InputType.TYPE_CLASS_TEXT, 6);
        card.addView(eName);

        card.addView(mkText("URL（免费服务可留空）", getColor(R.color.tx2), 12, 14));
        final EditText eUrl = mkInput(getString(R.string.custom_url_ph), src.url,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, 6);
        card.addView(eUrl);

        card.addView(mkText(getString(R.string.custom_lbl_key), getColor(R.color.tx2), 12, 14));
        final EditText eKey = mkInput(getString(R.string.custom_key_ph), src.key,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 6);
        card.addView(eKey);

        card.addView(mkText(getString(R.string.custom_lbl_path), getColor(R.color.tx2), 12, 14));
        final EditText ePath = mkInput(getString(R.string.custom_path_ph), src.path,
                InputType.TYPE_CLASS_TEXT, 6);
        card.addView(ePath);

        // 币种（独占一行：与阈值标签挤在一行时，窄屏弹窗里会互相遮挡）
        LinearLayout unitRow = new LinearLayout(this);
        unitRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = dp(16);
        unitRow.setLayoutParams(ulp);

        final TextView unitBtn = mkText("币种：" + unit[0], getColor(R.color.tx2), 12, 0);
        unitBtn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        unitBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        unitBtn.setBackgroundResource(R.drawable.mini_btn_border);
        unitRow.addView(unitBtn);
        card.addView(unitRow);

        // 制式：决定这一项算不算资产、以及小组件里归到哪一档
        LinearLayout kindRow = new LinearLayout(this);
        kindRow.setOrientation(LinearLayout.HORIZONTAL);
        kindRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        klp.topMargin = dp(10);
        kindRow.setLayoutParams(klp);

        final TextView kindBtn = mkText("制式：" + kindLabel(kind[0]), getColor(R.color.tx2), 12, 0);
        kindBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        kindBtn.setBackgroundResource(R.drawable.mini_btn_border);
        kindRow.addView(kindBtn);
        card.addView(kindRow);

        // 静态文本：填了就连 URL 都不用，永远是这几个字（魔搭这种免费服务）
        card.addView(mkText("显示文字（不填 URL 时用，默认「免费」）", getColor(R.color.tx2), 12, 14));
        final EditText eText = mkInput("例如：免费 / 不限量", src.text == null ? "" : src.text,
                InputType.TYPE_CLASS_TEXT, 6);
        card.addView(eText);

        // 显示后缀（订阅类填 % 或 次；留空则按金额显示）
        card.addView(mkText("显示后缀（可空，如 % 或 次）", getColor(R.color.tx2), 12, 14));
        final EditText eSuffix = mkInput("留空＝按金额显示", suffix[0], InputType.TYPE_CLASS_TEXT, 6);
        card.addView(eSuffix);

        final Runnable syncKind = new Runnable() {
            public void run() { kindBtn.setText("制式：" + kindLabel(kind[0])); }
        };
        kindBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                for (int i = 0; i < BalanceFetcher.KIND_ORDER.length; i++) {
                    if (BalanceFetcher.KIND_ORDER[i].equals(kind[0])) {
                        kind[0] = BalanceFetcher.KIND_ORDER[
                                (i + 1) % BalanceFetcher.KIND_ORDER.length];
                        break;
                    }
                }
                syncKind.run();
            }
        });

        // 预警阈值（独占一行，标签可自动排版不挤压）
        LinearLayout thrRow = new LinearLayout(this);
        thrRow.setOrientation(LinearLayout.HORIZONTAL);
        thrRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        trlp.topMargin = dp(10);
        thrRow.setLayoutParams(trlp);

        final TextView thrLabel = new TextView(this);
        thrLabel.setTextColor(getColor(R.color.tx2));
        thrLabel.setTextSize(12);
        thrLabel.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        thrRow.addView(thrLabel);

        final EditText eThr = new EditText(this);
        eThr.setHint("0");
        eThr.setTextSize(13);
        eThr.setTextColor(getColor(R.color.tx));
        eThr.setHintTextColor(getColor(R.color.hint_c));
        eThr.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        eThr.setSingleLine(true);
        eThr.setGravity(Gravity.END);
        eThr.setBackgroundResource(R.drawable.input_bg);
        eThr.setPadding(dp(20), dp(19), dp(20), dp(19));
        if (src.threshold > 0) eThr.setText(src.threshold == Math.floor(src.threshold)
                ? String.valueOf((long) src.threshold) : String.valueOf(src.threshold));
        LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(
                dp(90), LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp2.leftMargin = dp(10);
        eThr.setLayoutParams(tlp2);
        thrRow.addView(eThr);
        card.addView(thrRow);

        final Runnable syncUnit = new Runnable() {
            public void run() {
                unitBtn.setText("币种：" + unit[0]);
                thrLabel.setText(getString(R.string.custom_lbl_thr)
                        + "（" + ("USD".equals(unit[0]) ? "$" : "¥") + "）");
            }
        };
        syncUnit.run();
        unitBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                unit[0] = "USD".equals(unit[0]) ? "CNY" : "USD";
                syncUnit.run();
            }
        });

        // 底部按钮
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(20);
        btns.setLayoutParams(blp);

        final Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        TextView cancel = new TextView(this);
        cancel.setText(R.string.custom_cancel);
        cancel.setTextColor(getColor(R.color.tx2));
        cancel.setTextSize(14);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackgroundResource(R.drawable.mini_btn_border);
        cancel.setPadding(0, dp(12), 0, dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clp.rightMargin = dp(6);
        cancel.setLayoutParams(clp);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        btns.addView(cancel);

        TextView save = new TextView(this);
        save.setText(R.string.custom_save);
        save.setTextColor(getColor(R.color.accent_tx));
        save.setTextSize(14);
        save.setTypeface(null, Typeface.BOLD);
        save.setGravity(Gravity.CENTER);
        save.setBackgroundResource(R.drawable.btn_primary);
        save.setPadding(0, dp(12), 0, dp(12));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = dp(6);
        save.setLayoutParams(slp);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String url = eUrl.getText().toString().trim();
                String key = eKey.getText().toString().trim();
                String txt = eText.getText().toString().trim();
                if (key.length() == 0) {
                    Toast.makeText(SettingsActivity.this, R.string.custom_need_url_key,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                BalanceFetcher.Custom c = new BalanceFetcher.Custom();
                c.name = eName.getText().toString().trim();
                c.url = url;
                c.key = key;
                c.path = ePath.getText().toString().trim();
                c.unit = unit[0];
                c.kind = kind[0];
                c.suffix = eSuffix.getText().toString().trim();
                c.text = txt;
                try {
                    c.threshold = Double.parseDouble(eThr.getText().toString().trim());
                } catch (Exception ignored) { c.threshold = 0; }

                List<BalanceFetcher.Custom> list = BalanceFetcher.loadCustom(SettingsActivity.this);
                if (editing && index < list.size()) list.set(index, c);
                else list.add(c);
                BalanceFetcher.saveCustom(SettingsActivity.this, list);
                renderCustomList();
                refreshWidget();
                dlg.dismiss();
            }
        });
        btns.addView(save);
        card.addView(btns);

        dlg.setContentView(sc);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
    }

    /** 自定义平台改动后立刻刷新小组件 */
    private void refreshWidget() {
        Intent it = new Intent(this, BalanceWidgetProvider.class);
        it.setAction(BalanceWidgetProvider.ACTION_REFRESH);
        sendBroadcast(it);
    }

    // ---------- 生命周期 ----------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        UiInsets.apply(this, R.id.settings_header, R.id.settings_scroll, 12, 20);

        // 与主界面同级导航：点 API余额/用量统计 左右滑回主界面对应页
        findViewById(R.id.tab_api).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { gotoMain("api"); }
        });
        findViewById(R.id.tab_stats).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { gotoMain("stats"); }
        });
        // 设置页布局已默认高亮「设置」（无需指示块）

        // 设置主体统一由 SettingsBinder 绑定（与主界面第三面板同一份逻辑）
        new SettingsBinder(this, findViewById(R.id.settings_root)).bind();
    }

    /** 通知不可用时，在设置页顶部显著提示 */
    private void refreshNotifyState() {
        TextView tv = (TextView) findViewById(R.id.notify_state);
        if (tv == null) return;
        tv.setVisibility(NotifyPermission.isEnabled(this) ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshNotifyState();      // 从系统设置返回后同步状态
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        NotifyPermission.onRequestResult(this, code, perms, results);
        refreshNotifyState();
    }

    private int parseMin(EditText e, int def) {
        if (e == null) return def;
        try {
            String t = e.getText().toString().trim();
            if (t.length() == 0) return def;
            return Integer.parseInt(t);
        } catch (Throwable t) {
            return def;
        }
    }

    /** 保存全部设置并触发刷新 */
    private void doSave() {
        SharedPreferences sp = getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        /* 密钥的增删改现在是即时生效的（走 KeyStore），
           这里不再批量保存 —— 否则会和「一个平台多个 Key」的结构打架。 */

            // 内置平台的阈值单独走 Alert（它自己管 prefs）
        ed.apply();

        // 刷新间隔：越界的值会被钳制到允许范围
        int fg = parseMin(eFg, RefreshScheduler.FG_DEFAULT_MIN);
        int bg = parseMin(eBg, RefreshScheduler.BG_DEFAULT_MIN);
        RefreshScheduler.setIntervals(SettingsActivity.this, fg, bg);
        String note = "";
        if (fg != RefreshScheduler.clampFg(fg) || bg != RefreshScheduler.clampBg(bg)) {
            note = "（间隔已调整为允许范围：前台 ≥" + RefreshScheduler.FG_MIN
                 + " 分钟，后台 ≥" + RefreshScheduler.BG_MIN + " 分钟）";
        }

        Context c = SettingsActivity.this;
        Intent it = new Intent(c, BalanceWidgetProvider.class);
        it.setAction(BalanceWidgetProvider.ACTION_REFRESH);
        c.sendBroadcast(it);

        Toast.makeText(c, "已保存（" + KeyStore.all(this).size() + " 个 Key），正在刷新" + note,
                Toast.LENGTH_SHORT).show();
        finish();
    }

    /**
     * 显示阿里云 AccessKey 配置帮助
     */
    private void showAliyunAccessKeyHelp() {
        String helpText = "阿里云百炼余额查询需要配置 AccessKey，推荐按以下步骤操作：\n\n"
                + "【先选计费模式】\n"
                + "• 余额制（按量付费）：充值后按调用扣费，可自动查账户余额\n"
                + "• 订阅制（Token Plan 套餐）：无公开余量 API，可自动查"
                + "订阅实例与到期时间；套餐余量请在百炼控制台查看\n\n"
                + "【步骤 1】创建 RAM 子用户（推荐）\n"
                + "1. 登录阿里云控制台：https://ram.console.aliyun.com/users\n"
                + "2. 点击「创建用户」\n"
                + "3. 填写登录名称（如：balance-query）\n"
                + "4. 勾选「OpenAPI 调用访问」\n"
                + "5. 点击「确定」创建\n\n"
                
                + "【步骤 2】授予权限（二选一）\n\n"
                + "▶ 方式 A：系统策略（最简单，推荐）\n"
                + "1. 进入 RAM 用户列表：https://ram.console.aliyun.com/users\n"
                + "2. 找到刚创建的用户，点击「添加权限」\n"
                + "3. 在「系统策略」中搜索：AliyunBSSReadOnlyAccess\n"
                + "4. 勾选它，点击「确定」完成\n"
                + "（这是费用中心只读权限，官方维护，绝不会写错）\n\n"
                
                + "▶ 方式 B：自定义最小权限策略\n"
                + "⚠️ 注意：余额查询接口的授权 Action 名称是历史遗留的\n"
                + "「bss:DescribeAcccount」（注意是三个 c），\n"
                + "不是 API 名 QueryAccountBalance！填后者会显示「无效授权」。\n\n"
                + "1. 进入 RAM 控制台 → 权限策略：https://ram.console.aliyun.com/policies\n"
                + "2. 点击「创建权限策略」\n"
                + "3. 选择「JSON」模式，粘贴以下内容：\n\n"
                + "{\n"
                + "  \"Version\": \"1\",\n"
                + "  \"Statement\": [\n"
                + "    {\n"
                + "      \"Effect\": \"Allow\",\n"
                + "      \"Action\": \"bss:DescribeAcccount\",\n"
                + "      \"Resource\": \"*\"\n"
                + "    }\n"
                + "  ]\n"
                + "}\n\n"
                + "4. 填写策略名称（如：QueryBalancePolicy），保存\n"
                + "5. 回到 RAM 用户 → 添加权限 → 自定义策略 → 勾选它\n\n"
                
                + "【步骤 4】获取 AccessKey\n"
                + "1. 进入 RAM 用户详情页\n"
                + "2. 点击「认证管理」标签\n"
                + "3. 点击「创建 AccessKey」\n"
                + "4. 复制 AccessKeyId 和 AccessKeySecret\n"
                + "5. ⚠️ AccessKeySecret 只显示一次，务必保存好！\n\n"
                
                + "【步骤 5】在本应用中配置\n"
                + "1. 将 AccessKeyId 和 AccessKeySecret 填入上方输入框\n"
                + "2. 点击「保存」\n"
                + "3. 返回主界面，点击百炼卡片的「刷新」\n"
                + "4. 如果配置正确，应该能看到可用余额\n\n"
                
                + "【安全提示】\n"
                + "• 强烈建议使用 RAM 子用户的 AccessKey，不要使用主账号\n"
                + "• AccessKey 会加密存储在本地，不会上传到任何服务器\n"
                + "• 如果 AccessKey 泄露，请立即在阿里云控制台禁用\n"
                + "• 本应用只使用 QueryAccountBalance 权限查询余额，不会进行其他操作\n\n"
                
                + "【常见问题】\n"
                + "Q: 为什么不能用主账号 AccessKey？\n"
                + "A: 主账号 AccessKey 拥有所有权限，一旦泄露风险极大。RAM 子用户可以精确控制权限。\n\n"
                
                + "Q: 配置后查询失败怎么办？\n"
                + "A: 请检查：\n"
                + "  1. AccessKey 是否正确（注意不要有多余空格）\n"
                + "  2. 权限策略是否已授予给 RAM 用户\n"
                + "  3. 网络连接是否正常\n"
                + "  4. 查看应用日志获取详细错误信息\n\n"
                
                + "Q: 可以不用 AccessKey 吗？\n"
                + "A: 可以。不配置 AccessKey 时，百炼卡片会显示「见控制台」，点击可跳转到百炼控制台查看余额。";
        
        new AlertDialog.Builder(this)
                .setTitle("阿里云 AccessKey 配置指南")
                .setMessage(helpText)
                .setPositiveButton("知道了", null)
                .setNeutralButton("打开阿里云控制台", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://ram.console.aliyun.com/users"));
                            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } catch (Exception e) {
                            Toast.makeText(SettingsActivity.this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void doPin() {
        // vivo 桌面虽声明支持 requestPinAppWidget，实测静默无响应；
        // 直接给手动添加引导，最可靠。
        new AlertDialog.Builder(this)
                .setTitle(R.string.pin_title)
                .setMessage(R.string.pin_manual)
                .setPositiveButton(R.string.pin_ok, null)
                .show();
    }
}
