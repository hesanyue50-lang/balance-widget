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

    /**
     * @param scrollIds 需要按顶部栏高度留出上边距的滚动区。三面板分页后有多页，
     *                  每一页都得同步，否则切过去会被顶部栏盖住。
     */
    public static void apply(final Activity act, int headerId,
                             final int baseTopDp, final int baseBottomDp,
                             int... scrollIds) {
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

        // ② 系统栏本身透明 + 图标配色跟着应用主题走
        try {
            android.view.Window w = act.getWindow();
            /* 内容伸到状态栏下面了，状态栏自己就不能再有底色，
               否则顶部会有一条突兀的色带 */
            w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);

            View decor = w.getDecorView();
            /* 应用是浅色底，图标/文字要深色才看得见。
               用 WindowInsetsController（API 30+）或旧的 systemUiVisibility 两套写法。 */
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.setSystemBarsAppearance(
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                    /* 导航栏保持浅色图标：应用底部是深色卡片，深色图标会看不见 */
                }
            } else {
                decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        } catch (Throwable ignored) { }

        final View header = act.findViewById(headerId);
        if (header == null) return;
        final View[] scrolls = new View[scrollIds.length];
        for (int i = 0; i < scrollIds.length; i++) scrolls[i] = act.findViewById(scrollIds[i]);
        for (int i = 0; i < scrolls.length; i++) if (scrolls[i] == null) return;

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
                // ④ 每个滚动区底部都让开手势条
                for (int i = 0; i < scrolls.length; i++) {
                    View sc = scrolls[i];
                    sc.setPadding(sc.getPaddingLeft(), sc.getPaddingTop(),
                                  sc.getPaddingRight(), baseBottom + bottom);
                }
                // ③ 顶部栏高度随 insets 变化，同步各滚动区上留白
                syncScrollTops(header, scrolls);
                return insets;
            }
        });

        // insets 回调不一定每次都触发（如分屏切换），布局变化时兜底
        header.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if ((b - t) != (ob - ot)) syncScrollTops(header, scrolls);
            }
        });

        try { header.requestApplyInsets(); } catch (Throwable ignored) { }
    }

    private static void syncScrollTops(final View header, final View[] scrolls) {
        header.post(new Runnable() {
            public void run() {
                int h = header.getHeight();
                if (h <= 0) return;
                for (int i = 0; i < scrolls.length; i++) {
                    View sc = scrolls[i];
                    if (sc.getPaddingTop() != h) {
                        sc.setPadding(sc.getPaddingLeft(), h,
                                      sc.getPaddingRight(), sc.getPaddingBottom());
                    }
                }
            }
        });
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
