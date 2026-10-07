package com.minis.balancewidget;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

/**
 * 「正在加载」遮罩。
 *
 * 为什么要它：切面板要重建统计、保存密钥要过 Keystore 加密、设置页要重建密钥列表 ——
 * 这些活儿都在主线程，用户那一瞬间点什么都像点坏了。盖一层半透明遮罩 + 转圈 + 横向进度条，
 * 至少能立刻给出反馈。
 *
 * 关键在于**先让遮罩画出来再干活**：直接 show() 完就同步跑重活的话，界面根本没机会重绘，
 * 遮罩等于没显示。所以统一走 {@link #run} —— 它用 DecorView.post 把重活推到下一帧，
 * 那时遮罩已经在屏幕上了。
 *
 * 另外做了个 220ms 的最短展示：活儿太快时遮罩一闪而过反而像闪屏，不如让它稳一下。
 */
public final class Busy {

    private static final long MIN_SHOW_MS = 220L;

    private Busy() { }

    public static void show(Activity a, String msg) {
        if (a == null) return;
        final View ov = a.findViewById(R.id.busy_overlay);
        if (ov == null) return;
        TextView t = (TextView) a.findViewById(R.id.busy_text);
        if (t != null) t.setText(msg == null ? "正在加载…" : msg);
        ov.bringToFront();
        Anim.fade(ov);          // 遮罩用纯淡入（带位移会露出底下的内容）
    }

    public static void hide(Activity a) {
        if (a == null) return;
        View ov = a.findViewById(R.id.busy_overlay);
        if (ov != null) Anim.fadeOut(ov);
    }

    /**
     * 显示遮罩 → 下一帧执行重活 → 收尾后隐藏（不足最小时长则补足）。
     * heavy 始终在主线程执行，所以里面照常操作 View。
     */
    public static void run(final Activity a, final String msg, final Runnable heavy) {
        if (a == null) return;
        show(a, msg);
        final View deco = a.getWindow() == null ? null : a.getWindow().getDecorView();
        if (deco == null) {
            try { if (heavy != null) heavy.run(); } finally { hide(a); }
            return;
        }
        final long t0 = System.currentTimeMillis();
        deco.post(new Runnable() {
            public void run() {
                try {
                    if (heavy != null) heavy.run();
                } catch (Throwable ignored) {
                }
                long dt = System.currentTimeMillis() - t0;
                long wait = MIN_SHOW_MS - dt;
                if (wait <= 0) hide(a);
                else deco.postDelayed(new Runnable() {
                    public void run() { hide(a); }
                }, wait);
            }
        });
    }
}
