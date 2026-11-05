package com.minis.balancewidget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 表单控件工厂 —— 让「添加自定义平台」和「添加高级自定义平台」两个弹窗长一个样。
 *
 * <p>为什么单独抽一层：这两个表单原来各写各的，同一个「名称」字段在两边
 * 字号、间距、说明位置全不一样，用户来回看两个弹窗像两个 App。
 * 抽出来之后，一行 {@code Form.label(...) + Form.input(...)} 就是一对字段，
 * 排版尺寸只在这个文件里改一次。
 *
 * <p>尺寸取自设计稿：标签 11.5sp 三级色、输入框 14sp 圆角随主题内边距 13/11、
 * 字段之间 14dp、首个字段距标题 8dp。
 */
public final class Form {

    private Form() { }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 字段小标题。第一个字段不用顶部留白（标题下面已经有了）。 */
    public static TextView label(Context c, ViewGroup box, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(Theme.c(c, R.attr.c_tx3));
        t.setTextSize(11.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, box.getChildCount() == 0 ? 0 : 14);
        t.setLayoutParams(lp);
        box.addView(t);
        return t;
    }

    /** 单行输入框。 */
    public static EditText input(Context c, ViewGroup box, String hint, String value) {
        return input(c, box, hint, value, 1);
    }

    /** 多行输入框（请求头、POST 体）。固定行高，超出部分在框内滚，不抢外层手势。 */
    public static EditText area(Context c, ViewGroup box, String hint, String value, int lines) {
        return input(c, box, hint, value, lines < 1 ? 1 : lines);
    }

    private static EditText input(Context c, ViewGroup box, String hint, String value, int lines) {
        EditText e = new EditText(c);
        if (lines == 1) {
            /* ⚠️ 先定 inputType、再 setText：反过来的话 KeyListener 会重建，
               插入点被丢回 0，用户接着输入就从开头插进 URL 中间。 */
            e.setSingleLine(true);
            e.setInputType(InputType.TYPE_CLASS_TEXT);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setMinLines(lines);
            e.setMaxLines(lines);          // 封顶，否则多行框自己变成滚动区，外层滚不动
            e.setVerticalScrollBarEnabled(true);
            e.setGravity(Gravity.TOP | Gravity.START);
        }
        if (hint != null && hint.length() > 0) e.setHint(hint);
        if (value != null && value.length() > 0) e.setText(value);
        e.setTextSize(lines == 1 ? 14 : 13);
        e.setTextColor(Theme.c(c, R.attr.c_tx));
        e.setHintTextColor(Theme.c(c, R.attr.c_hint));
        e.setBackgroundResource(Theme.bgRes(c, R.attr.bg_input));
        e.setPadding(dp(c, 13), dp(c, 11), dp(c, 13), dp(c, 11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 6);
        e.setLayoutParams(lp);
        box.addView(e);
        return e;
    }

    /** 密码型输入框（API Key）：只影响回显，getText() 拿到的仍是明文。 */
    public static EditText secret(Context c, ViewGroup box, String hint, String value) {
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (hint != null && hint.length() > 0) e.setHint(hint);
        if (value != null && value.length() > 0) {
            e.setText(value);
            e.setSelection(e.length());     // 光标放到末尾，别让用户从中间改
        }
        e.setTextSize(14);
        e.setTextColor(Theme.c(c, R.attr.c_tx));
        e.setHintTextColor(Theme.c(c, R.attr.c_hint));
        e.setBackgroundResource(Theme.bgRes(c, R.attr.bg_input));
        e.setPadding(dp(c, 13), dp(c, 11), dp(c, 13), dp(c, 11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 6);
        e.setLayoutParams(lp);
        box.addView(e);
        return e;
    }

    /**
     * 关掉输入法联想。URL / JSON 路径 / 正则 / 请求头这类机器串最怕中文输入法
     * 自动联想和大写 —— 表现为「填得好好的，保存后多了一串奇怪的字」。
     */
    public static EditText noSuggest(EditText e) {
        if (e == null) return null;
        e.setInputType(e.getInputType() | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }

    /** URL 专用：URI 变体 + 关联想，输入法不会再自动大写和加空格。 */
    public static EditText uri(EditText e) {
        if (e == null) return null;
        e.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }

    /**
     * 行内输入框：不进 box、宽度交给调用方按 weight 分。
     * 给「按钮名字 + 按钮地址」这种一行两格的排法用。必须放进 {@link #row}。
     */
    public static EditText inline(Context c, String hint, String value, float weight) {
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (hint != null && hint.length() > 0) e.setHint(hint);
        if (value != null && value.length() > 0) e.setText(value);
        e.setTextSize(13);
        e.setTextColor(Theme.c(c, R.attr.c_tx));
        e.setHintTextColor(Theme.c(c, R.attr.c_hint));
        e.setBackgroundResource(Theme.bgRes(c, R.attr.bg_input));
        e.setPadding(dp(c, 11), dp(c, 9), dp(c, 11), dp(c, 9));
        e.setLayoutParams(weight > 0
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                /* weight 传 0 说明调用方不打算分宽 —— 给个可见的宽度，别塌成 0 */
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    /**
     * 胶囊 chip：点一下循环切换那种。label 由调用方自己改。
     * 触摸区垫到 40dp，单手也点得中（原来 12sp + 6dp 内边距只有约 30dp）。
     */
    public static TextView chip(Context c, String s, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(Theme.c(c, R.attr.c_accent));
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setMinHeight(dp(c, 40));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        paint(c, t, R.attr.c_accent_soft, 14, 999);
        t.setClickable(true);
        t.setFocusable(true);
        if (l != null) t.setOnClickListener(l);
        return t;
    }

    /**
     * 通栏浅底按钮：底色同输入框，字用次文字色。
     * 比 chip 更「重」，用来放「打开网页登录」「测试抓取」这类会跳走/会联网的动作。
     */
    public static TextView wide(Context c, String s, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(Theme.c(c, R.attr.c_tx2));
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 44));
        t.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        paint(c, t, R.attr.c_input, 13, 999);
        t.setClickable(true);
        t.setFocusable(true);
        if (l != null) t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 6);
        t.setLayoutParams(lp);
        return t;
    }

    /**
     * 给可点文字上底色 + 涟漪。
     *
     * <p>两个要点：
     * ① 纯 {@code <shape>} 背景点下去毫无反馈，46dp 的主按钮用户会以为没点中而连点；
     * ② 圆角必须跟主题走 —— Brutal / Editorial 是直角主题，写死 13dp 就会出现
     *    「输入框是方的、按钮是圆的」这种自相矛盾。
     *
     * @param colorAttr 底色取哪个 token（c_accent / c_input / c_accent_soft）
     * @param radiusDp  非风格化主题（Aurora 两套）用这个圆角
     * @param radiusToken 风格化主题的圆角上限（999 = 胶囊）
     */
    public static void paint(Context c, TextView t, int colorAttr, float radiusDp, float radiusToken) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Theme.c(c, colorAttr));
        float r = radiusDp;
        if (Theme.isStyled(c)) {
            float tr = Math.min(999, Theme.radiusDp(c));
            /* 直角主题（0dp）用小圆角而不是纯直角：4dp 触摸目标看起来仍像个按钮 */
            r = tr <= 0 ? 2 : Math.min(tr, radiusToken);
        }
        g.setCornerRadius(dp(c, r));
        int ripple = Theme.isDark(c) ? 0x33FFFFFF : 0x22000000;
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), g, null));
    }

    /** 横向容器：chips 并排用。子视图自己管左右间距。 */
    public static LinearLayout row(Context c, ViewGroup box) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 8);
        r.setLayoutParams(lp);
        box.addView(r);
        return r;
    }

    /** 灰色补充说明，一行两行都行。放「默认值是什么」这类信息，不写长教程。 */
    public static TextView note(Context c, ViewGroup box, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(Theme.c(c, R.attr.c_tx3));
        t.setTextSize(11);
        t.setLineSpacing(dp(c, 2), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 7);
        t.setLayoutParams(lp);
        box.addView(t);
        return t;
    }

    /** 一条极淡的分隔线，用来隔开「更多选项」这类次要区块。 */
    public static View divider(Context c, ViewGroup box) {
        View v = new View(c);
        v.setBackgroundColor(Theme.c(c, R.attr.c_line));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        lp.topMargin = dp(c, 14);
        v.setLayoutParams(lp);
        box.addView(v);
        return v;
    }
}
