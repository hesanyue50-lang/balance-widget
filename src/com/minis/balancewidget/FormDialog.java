package com.minis.balancewidget;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
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
    private final LinearLayout rootView;
    private Runnable onSave;

    public FormDialog(Activity act, String title, String saveText) {
        this.act = act;

        LinearLayout root = new LinearLayout(act);
        rootView = root;
        root.setOrientation(LinearLayout.VERTICAL);
        /* 用主题帮手机取背景：Aurora 两套给 20dp + 1dp 描边，
           Editorial / Brutal 是直角主题（0dp，Brutal 还有 2dp 黑边）——
           写死 @drawable/dialog_card 的话，直角主题里就只剩弹窗是圆的。 */
        root.setBackground(Theme.cardBg(act));
        int ph = Form.dp(act, 20);
        root.setPadding(ph, Form.dp(act, 18), ph, Form.dp(act, 14));

        TextView tvTitle = new TextView(act);
        tvTitle.setText(title);
        tvTitle.setTextSize(18);
        tvTitle.setTextColor(Theme.c(act, R.attr.c_tx));
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setLetterSpacing(0.012f);
        root.addView(tvTitle);

        /* ⚠️ ScrollView 必须封顶。
           窗口高度是 WRAP_CONTENT，LinearLayout 按顺序把「剩余高度」下发：
           ScrollView 拿到的是 AT_MOST(屏高 - 标题)，内容一超屏它就吃掉全部剩余高度，
           排在后面的底栏只能拿到 AT_MOST(0) —— 高度 0、被裁到屏幕外，
           「保存」按钮彻底消失（高级平台展开「高级选项」后 100% 复现）。
           封到屏高的 58%（并给底栏留 90dp）之后，超出部分在 ScrollView 内部滚，底栏永远在。 */
        final int screenH = act.getResources().getDisplayMetrics().heightPixels;
        final int reserve = Form.dp(act, 90);        // 底栏 + 底部内边距
        final int hardCap = (int) (screenH * 0.58f);
        ScrollView sv = new ScrollView(act) {
            @Override
            protected void onMeasure(int wSpec, int hSpec) {
                int avail = View.MeasureSpec.getMode(hSpec) == View.MeasureSpec.AT_MOST
                        ? View.MeasureSpec.getSize(hSpec) : screenH;
                int limit = Math.min(hardCap, Math.max(Form.dp(act, 120), avail - reserve));
                super.onMeasure(wSpec,
                        View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            }
        };
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

        TextView cancel = footBtn("取消", R.attr.c_input, R.attr.c_tx2);
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(
                0, Form.dp(act, 46), 1f);
        lpC.rightMargin = Form.dp(act, 6);
        cancel.setLayoutParams(lpC);
        foot.addView(cancel);

        TextView save = footBtn(saveText == null ? "保存" : saveText,
                R.attr.c_accent, R.attr.c_accent_tx);
        LinearLayout.LayoutParams lpS = new LinearLayout.LayoutParams(
                0, Form.dp(act, 46), 1f);
        lpS.leftMargin = Form.dp(act, 6);
        save.setLayoutParams(lpS);
        foot.addView(save);

        root.addView(foot);

        dlg = new Dialog(act, dialogStyle(act));
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            /* 92% 屏宽，但封 560dp —— 横屏/平板上不然会变成一张巨幅表单 */
            int sw = act.getResources().getDisplayMetrics().widthPixels;
            int wp = Math.min((int) (sw * 0.92f), Form.dp(act, 560));
            w.setLayout(wp, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            /* 软键盘弹出来时把弹窗顶上去，别把正在填的输入框挡住 */
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        /* 表单一屏能填十几个字段，手指在窗外蹭一下就把全屏输入丢了 —— 关掉外部点击取消 */
        dlg.setCanceledOnTouchOutside(false);

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
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) {
                /* 断开引用：onSave 里捕获着整棵视图树，进而捕获 Activity */
                onSave = null;
            }
        });
    }

    /**
     * 弹窗主题（决定 EditText 光标色、选择手柄色、进出动画）。
     * 只有 {@code android:alertDialogTheme} 是给 AlertDialog 用的，
     * {@code new Dialog(act)} 解析的是 dialogTheme —— 不显式指定就落到系统默认，
     * 深色主题下光标会是系统蓝，动画也跟 App 里其它弹窗不一致。
     */
    private static int dialogStyle(Activity a) {
        switch (Theme.index(a)) {
            case 1: return R.style.AppDialog_AuroraDark;
            case 2: return R.style.AppDialog_Editorial;
            case 3: return R.style.AppDialog_Swiss;
            case 4: return R.style.AppDialog_Devtool;
            case 5: return R.style.AppDialog_Brutal;
            case 6: return R.style.AppDialog_Clay;
            default: return R.style.AppDialog_AuroraLight;
        }
    }

    private TextView footBtn(String s, int bgAttr, int txAttr) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(Theme.c(act, txAttr));
        t.setGravity(Gravity.CENTER);
        t.setTypeface(null, Typeface.BOLD);
        Form.paint(act, t, bgAttr, 13, 999);
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
        /* ⚠️ skin 放在 show 而不是构造函数里：调用方是「构造完再往 box 里加字段」，
           构造末尾 skin 一遍只能覆盖标题和底栏，正文那几十个 TextView 全是默认字体
           （Editorial 衬线主题下弹窗里外两种字，Brutal 下也不跟）。 */
        Theme.skin(act, rootView);
        try {
            dlg.show();
        } catch (Throwable ignored) {
            /* Activity 正在 finish 时 show() 抛 BadTokenException，别让设置页跟着崩 */
        }
    }

    public void dismiss() {
        try { dlg.dismiss(); } catch (Throwable ignored) { }
    }
}
