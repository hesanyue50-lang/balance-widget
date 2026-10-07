package com.minis.balancewidget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/**
 * 界面动效小工具。
 *
 * <b>为什么单独一个类</b>：动效要"全应用一个手感"，散落在各处手写
 * 参数迟早会跑偏（这边 200ms、那边 350ms，缓动曲线还不一样）。
 * 统一在这里定，改一处全应用生效。
 *
 * <b>只用 ViewPropertyAnimator / ValueAnimator</b>，不引第三方库 ——
 * 这个项目的体积和依赖一直是刻意控制的，为几个淡入动画拖一个库进来不值。
 */
public final class Anim {

    /** 展开 / 收起用时 */
    private static final long DUR_EXPAND = 220;
    /** 淡入用时 */
    private static final long DUR_FADE = 260;
    /** 列表项之间的错开间隔 */
    private static final long STAGGER = 32;

    private Anim() { }

    /**
     * 把视图从"当前高度"补间到目标高度，结束后交还给 wrap_content。
     *
     * 高度动画不能用 ViewPropertyAnimator（它没有 height 属性），
     * 只能自己 ValueAnimator 一帧帧设 layoutParams。
     */
    private static void animateHeight(final View v, final int from, final int to,
                                      final Runnable end) {
        v.getLayoutParams().height = from;
        v.requestLayout();
        ValueAnimator a = ValueAnimator.ofInt(from, to);
        a.setDuration(DUR_EXPAND);
        a.setInterpolator(new DecelerateInterpolator(2f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator an) {
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                lp.height = (Integer) an.getAnimatedValue();
                v.requestLayout();
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator an) {
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                /* 收回收起态时要留成 0，展开态交还 wrap_content ——
                   固定死高度的话，里面内容一变化就会被裁掉 */
                lp.height = (to == 0) ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
                v.requestLayout();
                if (end != null) end.run();
            }
        });
        a.start();
    }

    /** 量出这个视图在"不受高度约束"下的自然高度 */
    private static int naturalHeight(View v) {
        View parent = (View) v.getParent();
        int w = parent != null && parent.getWidth() > 0
                ? parent.getWidth()
                : v.getResources().getDisplayMetrics().widthPixels;
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return v.getMeasuredHeight();
    }

    /** 就地展开：高度从 0 长到自然高度，同时淡入 */
    public static void expand(View v) {
        if (v == null) return;
        int target = naturalHeight(v);
        v.setVisibility(View.VISIBLE);
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(DUR_FADE).start();
        animateHeight(v, 0, target, null);
    }

    /** 就地收起：高度收到 0，结束后转为 GONE */
    public static void collapse(final View v) {
        if (v == null) return;
        int from = v.getHeight() > 0 ? v.getHeight() : naturalHeight(v);
        animateHeight(v, from, 0, new Runnable() {
            public void run() { v.setVisibility(View.GONE); }
        });
    }

    /**
     * 淡入 + 轻微上移。
     *
     * @param delayMs 延时，用来做列表的错开出现
     */
    public static void fadeIn(View v, long delayMs) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(dp(v, 8));
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(DUR_FADE)
                .setInterpolator(new DecelerateInterpolator(2f))
                .start();
    }

    /** 列表逐条错开淡入；index 越大出现得越晚 */
    public static void stagger(View v, int index) {
        fadeIn(v, Math.min(index, 12) * STAGGER);
    }

    /**
     * 纯淡入，不带位移。
     *
     * 铺满整屏的遮罩层不能用 {@link #fadeIn} —— 整块跟着往上挪几 dp，
     * 边缘会露出底下的内容，看着像撕裂。
     */
    public static void fade(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(180).start();
    }

    /** 淡出，结束后转 GONE；本来就没显示则什么都不做 */
    public static void fadeOut(final View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(160)
                .withEndAction(new Runnable() {
                    public void run() { v.setVisibility(View.GONE); }
                })
                .start();
    }

    /**
     * 按下 / 松开的轻微缩放反馈。
     *
     * 挂在 OnTouchListener 上使用；返回 false 保证点击事件照常派发。
     */
    public static boolean pressFeedback(View v, android.view.MotionEvent e) {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                v.animate().scaleX(0.97f).scaleY(0.97f)
                        .setDuration(90).start();
                break;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(140).start();
                break;
        }
        return false;
    }

    private static float dp(View v, float value) {
        return value * v.getResources().getDisplayMetrics().density + 0.5f;
    }
}
