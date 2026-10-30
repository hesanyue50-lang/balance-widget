package com.minis.balancewidget;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 「上次崩溃了」提示页 —— 一个**极轻**的独立 Activity。
 *
 * 为什么做成独立 Activity 而不是进 MainActivity 再弹窗：
 * 用户报的崩溃发生在主界面初始化链上（页面隐藏后切页崩）—— 如果提示逻辑
 * 也挂在 MainActivity 里，可能**提示还没弹出来就又崩了**，恶性循环。
 * 这个页面只做三件事：读一个字符串、画几个 TextView、写一个布尔标记，
 * 不依赖任何可能崩的组件，所以它能可靠地站在崩溃的废墟上。
 *
 * 流程：CrashGuard 崩溃时置 pending 标记 → 下次启动 App.java 第一个
 * 检查并跳转过来 → 用户看完（或点掉）→ 清标记 → 再进主界面。
 * 这样即使主界面一启动又崩，用户也已经知道日志在哪了。
 */
public class CrashNoticeActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        /* 主题必须在 setContentView 之前应用：attr 是在 View 创建时解析的 */
        Theme.apply(this);

        String logPath = getSharedPreferences(BalanceFetcher.PREFS, MODE_PRIVATE)
                .getString("last_crash_where", "");
        String stack = getSharedPreferences(BalanceFetcher.PREFS, MODE_PRIVATE)
                .getString("last_crash_stack", "");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(16));

        TextView title = new TextView(this);
        title.setText("💥 应用上次意外退出了");
        title.setTextSize(18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFFE23B3B);
        root.addView(title);

        TextView where = new TextView(this);
        where.setText("\n崩溃日志已保存到：\n"
                + (logPath.length() > 0 ? logPath : "Download 文件夹（默认位置）")
                + "\n\n把日志文件发给开发者（QQ 群 1108314670 / GitHub Issue），"
                + "就能快速定位修复。");
        where.setTextSize(14);
        where.setLineSpacing(dp(3), 1f);
        root.addView(where);

        TextView tail = new TextView(this);
        tail.setTextSize(13);
        if (stack != null && stack.length() > 0) {
            tail.setText("\n异常摘要：\n" + stack);
            tail.setTypeface(android.graphics.Typeface.MONOSPACE);
        }
        TextView copy = new TextView(this);
        copy.setText("点击这里复制日志路径");
        copy.setTextSize(13);
        copy.setTextColor(0xFF2563EB);
        copy.setPadding(0, dp(14), 0, dp(4));
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            "crash-log", logPath));
                    Toast.makeText(CrashNoticeActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                } catch (Throwable ig) { }
            }
        });
        root.addView(tail);
        root.addView(copy);

        TextView ok = new TextView(this);
        ok.setText("知道了，进入应用");
        ok.setTextSize(14);
        ok.setTextColor(0xFFFFFFFF);
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(0, dp(12), 0, dp(12));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF2563EB);
        bg.setCornerRadius(dp(12));
        ok.setBackground(bg);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(20);
        ok.setLayoutParams(olp);
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                /* 清掉"待提示"标记再走 —— 不清的话下次启动还会弹 */
                getSharedPreferences(App.PREFS_FLAGS, MODE_PRIVATE)
                        .edit().putBoolean("crash_pending", false).apply();
                startActivity(new Intent(CrashNoticeActivity.this, MainActivity.class));
                finish();
            }
        });
        root.addView(ok);

        ScrollView sc = new ScrollView(this);
        sc.addView(root);
        setContentView(sc);

        /* 兜底：按返回键也要清标记，否则用户按返回离开后下次还会弹 */
        getSharedPreferences(App.PREFS_FLAGS, MODE_PRIVATE)
                .edit().putBoolean("crash_pending", false).apply();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 打开日志文件所在目录的查看器（尽力而为，失败静默） */
    private void tryOpenFolder(String path) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse("content://"), "resource/folder");
            startActivity(i);
        } catch (Throwable ignored) { }
    }
}
