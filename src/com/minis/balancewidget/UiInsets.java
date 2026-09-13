package com.minis.balancewidget;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * 刘海 / 状态栏 / 手势条适配（主界面与设置界面共用，保证风格一致）。
 *
 * 主题里已关掉 fitsSystemWindows，这里完全自己控制：
 *   ① 让窗口内容延伸到系统栏后面（否则顶部栏背景盖不住状态栏，会留一条分界）
 *   ② 顶部栏 paddingTop = 系统栏(含刘海)高度 + 设计间距 → 背景随之铺满状态栏区域
 *   ③ 滚动区 paddingTop = 顶部栏实际高度 → 内容正好从其下方开始，不被遮挡
 *   ④ 滚动区 paddingBottom = 手势条高度 + 原有留白
 */
public final class UiInsets {

    private UiInsets() { }

    public static void apply(final Activity act, int headerId, int scrollId,
                             final int baseTopDp, final int baseBottomDp) {
        // ① 内容延伸到系统栏后
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                act.getWindow().setDecorFitsSystemWindows(false);
            } else {
                act.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                      | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                      | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
        } catch (Throwable ignored) { }

        final View header = act.findViewById(headerId);
        final View scroll = act.findViewById(scrollId);
        if (header == null || scroll == null) return;

        final int baseTop = dp(act, baseTopDp);
        final int baseBottom = dp(act, baseBottomDp);

        header.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top, bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets sb =
                            insets.getInsets(WindowInsets.Type.systemBars());
                    top = sb.top;
                    bottom = sb.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                // ② 顶部栏：背景铺满状态栏区域，内容下移
                v.setPadding(v.getPaddingLeft(), baseTop + top,
                             v.getPaddingRight(), v.getPaddingBottom());
                // ④ 滚动区底部让开手势条
                scroll.setPadding(scroll.getPaddingLeft(), scroll.getPaddingTop(),
                                  scroll.getPaddingRight(), baseBottom + bottom);
                // ③ 顶部栏高度随 insets 变化，同步滚动区上留白
                syncScrollTop(header, scroll);
                return insets;
            }
        });

        // insets 回调不一定每次都触发（如分屏切换），布局变化时兜底
        header.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if ((b - t) != (ob - ot)) syncScrollTop(header, scroll);
            }
        });

        try { header.requestApplyInsets(); } catch (Throwable ignored) { }
    }

    private static void syncScrollTop(final View header, final View scroll) {
        header.post(new Runnable() {
            public void run() {
                int h = header.getHeight();
                if (h > 0 && scroll.getPaddingTop() != h) {
                    scroll.setPadding(scroll.getPaddingLeft(), h,
                                      scroll.getPaddingRight(), scroll.getPaddingBottom());
                }
            }
        });
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
