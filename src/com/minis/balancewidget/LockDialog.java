package com.minis.balancewidget;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 密码 / 密保相关的对话框。
 *
 * 三条流程：
 *   ask()    —— 只要密码，用来放行「看一眼密钥」这种操作
 *   setup()  —— 首次设置：密码 + 密保问题（问题答案自定，忘了密码靠它找回）
 *   reset()  —— 重置密码：输旧密码，或者回答密保问题
 */
public final class LockDialog {

    public interface OnPass { void ok(); }

    public interface OnPick { void pick(int which); }

    private LockDialog() { }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------- 基础控件 ----------

    private static Dialog shell(Context ctx, String title, View[] outCard) {
        ScrollView sc = new ScrollView(ctx);
        sc.setPadding(dp(ctx, 10), dp(ctx, 18), dp(ctx, 10), dp(ctx, 18));

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(ctx, 22), dp(ctx, 22), dp(ctx, 22), dp(ctx, 22));
        sc.addView(card);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextColor(ctx.getColor(R.color.tx));
        t.setTextSize(17);
        t.setTypeface(null, Typeface.BOLD);
        card.addView(t);

        Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(sc);
        /* 去掉 Dialog 自带的窗口底色 —— 否则卡片外面会再套一层白框，看着很臃肿 */
        try {
            if (dlg.getWindow() != null) {
                dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }
        } catch (Throwable ignored) { }
        outCard[0] = card;
        return dlg;
    }

    private static TextView label(Context ctx, String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(ctx.getColor(R.color.tx2));
        t.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 14);
        t.setLayoutParams(lp);
        return t;
    }

    private static EditText input(Context ctx, String hint, int type) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setTextSize(14);
        e.setTextColor(ctx.getColor(R.color.tx));
        e.setHintTextColor(ctx.getColor(R.color.hint_c));
        e.setInputType(type);
        e.setSingleLine(true);
        /* 同上：遮罩要在 setSingleLine 之后，否则会被冲掉 */
        if ((type & android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0) {
            e.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        }
        e.setBackgroundResource(R.drawable.input_bg);
        e.setPadding(dp(ctx, 20), dp(ctx, 19), dp(ctx, 20), dp(ctx, 19));
        return e;
    }

    private static TextView button(Context ctx, String s, boolean primary, View.OnClickListener l) {
        TextView b = new TextView(ctx);
        b.setText(s);
        b.setTextColor(ctx.getColor(primary ? R.color.accent_tx : R.color.tx2));
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(primary ? R.drawable.btn_primary : R.drawable.card_bg);
        b.setPadding(0, dp(ctx, 22), 0, dp(ctx, 22));
        b.setOnClickListener(l);
        return b;
    }

    private static void addButtons(Context ctx, LinearLayout card, Dialog dlg,
                                   String okText, View.OnClickListener ok) {
        addButtons(ctx, card, dlg, "取消", okText, ok);
    }

    private static void addButtons(Context ctx, LinearLayout card, Dialog dlg,
                                   String cancelText, String okText, View.OnClickListener ok) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 20);
        row.setLayoutParams(lp);

        TextView cancel = button(ctx, cancelText, false, new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        c1.rightMargin = dp(ctx, 6);
        cancel.setLayoutParams(c1);
        row.addView(cancel);

        TextView okBtn = button(ctx, okText, true, ok);
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        c2.leftMargin = dp(ctx, 6);
        okBtn.setLayoutParams(c2);
        row.addView(okBtn);
        card.addView(row);
    }

    /** 统一弹窗宽度。
        Dialog 默认是 wrap_content，内容一长就挤成窄条，按钮文字会折行 ——
        「看密钥」被折成两行就是这么来的。这里统一撑到屏幕的 90%。 */
    private static void showSized(Dialog dlg, Context ctx) {
        dlg.show();
        try {
            android.view.Window w = dlg.getWindow();
            if (w != null) {
                int target = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.9f);
                w.setLayout(target, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) { }
    }

    private static void tip(Context ctx, final String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------- 只验证密码 ----------

    public static void ask(Context ctx, String title, final OnPass cb) {
        if (!Lock.isSet(ctx)) { cb.ok(); return; }      // 没设过密码，直接放行

        final View[] cardHolder = new View[1];
        final Dialog dlg = shell(ctx, title, cardHolder);
        LinearLayout card = (LinearLayout) cardHolder[0];

        final EditText e = input(ctx, "输入密码", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 18);
        e.setLayoutParams(lp);
        card.addView(e);

        addButtons(ctx, card, dlg, "确定", new View.OnClickListener() {
            public void onClick(View v) {
                if (Lock.check(ctx, e.getText().toString())) {
                    dlg.dismiss();
                    cb.ok();
                } else {
                    tip(ctx, "密码不对");
                    e.setText("");
                }
            }
        });
        showSized(dlg, ctx);
    }

    // ---------- 通用确认弹窗 ----------

    /** 标题 + 说明 + 「去操作」按钮。用于权限这类"需要用户动手"的提示 */
    public static void confirm(Context ctx, String title, String note,
                               String okText, final OnPass cb) {
        final View[] cardHolder = new View[1];
        final Dialog dlg = shell(ctx, title, cardHolder);
        LinearLayout card = (LinearLayout) cardHolder[0];

        card.addView(label(ctx, note));

        addButtons(ctx, card, dlg, "暂不", okText, new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
                cb.ok();
            }
        });
        showSized(dlg, ctx);
    }

    // ---------- 通用选择弹窗（风格与密码框一致） ----------

    public static void choose(Context ctx, String title, String[] items, final OnPick cb) {
        final View[] cardHolder = new View[1];
        final Dialog dlg = shell(ctx, title, cardHolder);
        LinearLayout card = (LinearLayout) cardHolder[0];

        /* 选项横向并排 —— 竖着往下堆两个按钮会把弹窗拉得很长 */
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(ctx, 14);
        row.setLayoutParams(rlp);

        for (int i = 0; i < items.length; i++) {
            final int which = i;
            TextView btn = button(ctx, items[i], i == 0, new View.OnClickListener() {
                public void onClick(View v) {
                    dlg.dismiss();
                    cb.pick(which);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(ctx, 8);
            btn.setLayoutParams(lp);
            row.addView(btn);
        }
        card.addView(row);

        dlg.setCanceledOnTouchOutside(true);   // 点外面即可关闭，省掉「取消」那一行
        showSized(dlg, ctx);
    }

    // ---------- 亮出密钥（风格和验证弹窗一致） ----------

    public static void showKey(final Context ctx, String title, final String key) {
        final View[] cardHolder = new View[1];
        final Dialog dlg = shell(ctx, title, cardHolder);
        LinearLayout card = (LinearLayout) cardHolder[0];

        if (key == null || key.trim().length() == 0) {
            card.addView(label(ctx, "这一项还没有填 Key。"));
            addButtons(ctx, card, dlg, "关闭", "知道了", new View.OnClickListener() {
                public void onClick(View v) { dlg.dismiss(); }
            });
        showSized(dlg, ctx);
            return;
        }

        card.addView(label(ctx, "别人看到这串就等于拿到你的额度，别外传"));

        TextView kv = new TextView(ctx);
        kv.setText(key);
        kv.setTextColor(ctx.getColor(R.color.tx));
        kv.setTextSize(14);
        kv.setTypeface(Typeface.MONOSPACE);
        kv.setTextIsSelectable(true);               // 也能长按手动选
        kv.setBackgroundResource(R.drawable.input_bg);
        kv.setPadding(dp(ctx, 20), dp(ctx, 19), dp(ctx, 20), dp(ctx, 19));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 8);
        kv.setLayoutParams(lp);
        card.addView(kv);

        addButtons(ctx, card, dlg, "关闭", "复制", new View.OnClickListener() {
            public void onClick(View v) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("key", key));
                    tip(ctx, "已复制到剪贴板");
                }
                dlg.dismiss();                      // 复制完顺手关掉
            }
        });
        showSized(dlg, ctx);
    }

    // ---------- 首次设置：密码 + 密保 ----------

    public static void setup(Context ctx, final OnPass cb) {
        final View[] cardHolder = new View[1];
        final Dialog dlg = shell(ctx, "设置查看密码", cardHolder);
        LinearLayout card = (LinearLayout) cardHolder[0];

        card.addView(label(ctx, "以后查看 API 密钥需要输这个密码"));
        final EditText p1 = input(ctx, "设置密码", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 6);
        p1.setLayoutParams(lp);
        card.addView(p1);

        card.addView(label(ctx, "再输一遍"));
        final EditText p2 = input(ctx, "确认密码", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(p2);

        card.addView(label(ctx, "密保问题（忘了密码时用它重置，自己随便定）"));
        final EditText q = input(ctx, "例如：我家猫叫什么", InputType.TYPE_CLASS_TEXT);
        card.addView(q);

        card.addView(label(ctx, "答案（找回时忽略大小写和空格）"));
        final EditText a = input(ctx, "答案", InputType.TYPE_CLASS_TEXT);
        card.addView(a);

        addButtons(ctx, card, dlg, "保存", new View.OnClickListener() {
            public void onClick(View v) {
                String s1 = p1.getText().toString();
                String s2 = p2.getText().toString();
                String sq = q.getText().toString().trim();
                String sa = a.getText().toString().trim();
                if (s1.length() < 4) { tip(ctx, "密码至少 4 位"); return; }
                if (!s1.equals(s2))  { tip(ctx, "两次输入的密码不一样"); return; }
                if (sq.length() == 0 || sa.length() == 0) { tip(ctx, "密保问题和答案都要填"); return; }
                Lock.setPassword(ctx, s1);
                Lock.setQuestion(ctx, sq, sa);
                dlg.dismiss();
                tip(ctx, "已设置");
                cb.ok();
            }
        });
        showSized(dlg, ctx);
    }

    // ---------- 重置密码：旧密码 或 密保 ----------

    public static void reset(Context ctx) {
        if (!Lock.isSet(ctx)) { setup(ctx, new OnPass() { public void ok() { } }); return; }

        final View[] cardHolder = new View[1];
        final Dialog dlg = shell(ctx, "重置密码", cardHolder);
        LinearLayout card = (LinearLayout) cardHolder[0];

        card.addView(label(ctx, "方式一：输入当前密码"));
        final EditText oldPwd = input(ctx, "当前密码", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 6);
        oldPwd.setLayoutParams(lp);
        card.addView(oldPwd);

        card.addView(label(ctx, "方式二：回答密保问题"));
        final TextView qLabel = new TextView(ctx);
        qLabel.setTextColor(ctx.getColor(R.color.tx));
        qLabel.setTextSize(13);
        qLabel.setText(Lock.hasQuestion(ctx) ? ("问题：" + Lock.question(ctx)) : "（没有设过密保问题）");
        card.addView(qLabel);

        final EditText ans = input(ctx, "答案", InputType.TYPE_CLASS_TEXT);
        card.addView(ans);

        card.addView(label(ctx, "新密码"));
        final EditText np1 = input(ctx, "新密码", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(np1);
        final EditText np2 = input(ctx, "再输一遍", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(np2);

        addButtons(ctx, card, dlg, "重置", new View.OnClickListener() {
            public void onClick(View v) {
                String s1 = np1.getText().toString();
                String s2 = np2.getText().toString();
                if (s1.length() < 4) { tip(ctx, "新密码至少 4 位"); return; }
                if (!s1.equals(s2))  { tip(ctx, "两次输入的新密码不一样"); return; }

                boolean viaOld = Lock.check(ctx, oldPwd.getText().toString());
                boolean viaQ = Lock.hasQuestion(ctx) && Lock.checkAnswer(ctx, ans.getText().toString());
                if (!viaOld && !viaQ) {
                    tip(ctx, "当前密码或密保答案不正确");
                    return;
                }
                Lock.setPassword(ctx, s1);
                dlg.dismiss();
                tip(ctx, viaOld ? "已重置" : "已通过密保重置");
            }
        });
        showSized(dlg, ctx);
    }
}
