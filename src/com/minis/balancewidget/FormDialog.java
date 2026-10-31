package com.minis.balancewidget;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 平台表单弹窗外壳：标题 + 可滚动字段区 + 「取消 / 保存」底栏。
 *
 * <p>为什么不用 AlertDialog：它的标题、按钮、分隔线全是系统画的，字号和间距改不动，
 * 也塞不进「等宽两个按钮」这种排法。自己造一个 Dialog，样式才能和设计稿对上。
 *
 * <p>字段一律通过 {@link Form} 往 {@link #box} 里加。
 */
public class FormDialog {

    public final Dialog dlg;
    /** 字段容器（在 ScrollView 里）。 */
    public final LinearLayout box;

    private final Activity act;
    private Runnable onSave;

    public FormDialog(Activity act, String title, String saveText) {
        this.act = act;

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_card);
        int ph = Form.dp(act, 20);
        root.setPadding(ph, Form.dp(act, 18), ph, Form.dp(act, 14));

        TextView tvTitle = new TextView(act);
        tvTitle.setText(title);
        tvTitle.setTextSize(18);
        tvTitle.setTextColor(Theme.c(act, R.attr.c_tx));
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setLetterSpacing(0.012f);
        root.addView(tvTitle);

        ScrollView sv = new ScrollView(act);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Form.dp(act, 8), 0, 0);
        sv.addView(box);
        root.addView(sv);

        /* ---------- 底栏：等宽两个按钮 ---------- */
        LinearLayout foot = new LinearLayout(act);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = Form.dp(act, 16);
        foot.setLayoutParams(flp);

        TextView cancel = footBtn("取消", R.drawable.btn_secondary,
                Theme.c(act, R.attr.c_tx2));
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(
                0, Form.dp(act, 46), 1f);
        lpC.rightMargin = Form.dp(act, 6);
        cancel.setLayoutParams(lpC);
        foot.addView(cancel);

        TextView save = footBtn(saveText == null ? "保存" : saveText,
                R.drawable.btn_primary, Theme.c(act, R.attr.c_accent_tx));
        LinearLayout.LayoutParams lpS = new LinearLayout.LayoutParams(
                0, Form.dp(act, 46), 1f);
        lpS.leftMargin = Form.dp(act, 6);
        save.setLayoutParams(lpS);
        foot.addView(save);

        root.addView(foot);

        dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            /* 92% 屏宽：留出两侧 4% 的呼吸位，比系统弹窗的 90% 略宽一点，
               长输入框（URL / JSON 路径）不至于一屏只看到半截。 */
            int sw = act.getResources().getDisplayMetrics().widthPixels;
            w.setLayout((int) (sw * 0.92f), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            /* 软键盘弹出来时把弹窗顶上去，别把正在填的输入框挡住 */
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                /* 校验失败时不动弹窗（调用方的 onSave 自己决定要不要 dismiss）——
                   以前用 AlertDialog 的按钮监听，一点就被系统关掉，填的一屏全丢。 */
                if (onSave != null) onSave.run();
            }
        });

        Theme.skin(act, root);   // 弹窗是独立窗口，Activity 那棵树遍历不到
    }

    private TextView footBtn(String s, int bgRes, int txColor) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(txColor);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(null, Typeface.BOLD);
        t.setBackgroundResource(bgRes);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** 「保存」里做什么。校验不过直接 return，别 dismiss。 */
    public FormDialog onSave(Runnable r) {
        this.onSave = r;
        return this;
    }

    /** 往字段区追加一个带间距的视图（折叠区、自定义行用）。 */
    public void add(View v, float topMarginDp) {
        if (v == null) return;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Form.dp(act, topMarginDp);
        v.setLayoutParams(lp);
        box.addView(v);
    }

    /**
     * 可折叠区块：返回内容容器，调用方往里塞字段。
     * 展开时按钮文字跟着变，用户能看出这个块是开着的。
     */
    public LinearLayout collapsible(String closed, final String open) {
        final LinearLayout content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setVisibility(View.GONE);

        final TextView toggle = Form.chip(act, closed, null);
        toggle.setTypeface(null, Typeface.NORMAL);
        toggle.setTextColor(Theme.c(act, R.attr.c_tx2));
        LinearLayout r = Form.row(act, box);
        r.addView(toggle);
        add(content, 6);

        toggle.setOnClickListener(new View.OnClickListener() {
            boolean open0 = false;

            public void onClick(View v) {
                open0 = !open0;
                toggle.setText(open0 ? open : closed);
                content.setVisibility(open0 ? View.VISIBLE : View.GONE);
            }
        });
        return content;
    }

    public void show() {
        dlg.show();
    }

    public void dismiss() {
        try { dlg.dismiss(); } catch (Throwable ignored) { }
    }
}
