package com.minis.balancewidget;

import android.content.Context;
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
 * <p>尺寸取自设计稿：标签 11.5sp 三级色、输入框 14sp 圆角 12dp 内边距 13/11、
 * 字段之间 14dp、首个字段距标题 6dp。
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

    /** 多行输入框（请求头、POST 体）。 */
    public static EditText area(Context c, ViewGroup box, String hint, String value, int lines) {
        return input(c, box, hint, value, lines < 1 ? 1 : lines);
    }

    private static EditText input(Context c, ViewGroup box, String hint, String value, int lines) {
        EditText e = new EditText(c);
        if (hint != null && hint.length() > 0) e.setHint(hint);
        if (value != null && value.length() > 0) e.setText(value);
        e.setTextSize(14);
        e.setTextColor(Theme.c(c, R.attr.c_tx));
        e.setHintTextColor(Theme.c(c, R.attr.c_hint));
        e.setBackgroundResource(Theme.bgRes(c, R.attr.bg_input));
        e.setPadding(dp(c, 13), dp(c, 11), dp(c, 13), dp(c, 11));
        if (lines == 1) {
            e.setSingleLine(true);
        } else {
            e.setMinLines(lines);
            e.setGravity(Gravity.TOP | Gravity.START);
            e.setTextSize(13);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 6);
        e.setLayoutParams(lp);
        box.addView(e);
        return e;
    }

    /** 密码型输入框（API Key）：只影响回显，不影响保存的值。 */
    public static EditText secret(Context c, ViewGroup box, String hint, String value) {
        EditText e = input(c, box, hint, value);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setTextSize(14);
        return e;
    }

    /**
     * 行内输入框：不进 box、不设宽度，交给调用方塞进横向容器按 weight 分。
     * 给「按钮名字 + 按钮地址」这种一行两格的排法用。
     */
    public static EditText inline(Context c, String hint, String value, float weight) {
        EditText e = new EditText(c);
        if (hint != null && hint.length() > 0) e.setHint(hint);
        if (value != null && value.length() > 0) e.setText(value);
        e.setTextSize(13);
        e.setTextColor(Theme.c(c, R.attr.c_tx));
        e.setHintTextColor(Theme.c(c, R.attr.c_hint));
        e.setBackgroundResource(Theme.bgRes(c, R.attr.bg_input));
        e.setPadding(dp(c, 11), dp(c, 9), dp(c, 11), dp(c, 9));
        e.setSingleLine(true);
        e.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight));
        return e;
    }

    /** 胶囊 chip：点一下循环切换那种。label 由调用方自己改。 */
    public static TextView chip(Context c, String s, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(Theme.c(c, R.attr.c_accent));
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setBackgroundResource(R.drawable.chip_soft);
        t.setPadding(dp(c, 11), dp(c, 6), dp(c, 11), dp(c, 6));
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
        t.setBackgroundResource(R.drawable.btn_secondary);
        t.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        t.setClickable(true);
        t.setFocusable(true);
        if (l != null) t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 6);
        t.setLayoutParams(lp);
        return t;
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
