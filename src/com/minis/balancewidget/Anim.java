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

    /** 展开 / 收起用时（M3 duration 200 档） */
    private static final long DUR_EXPAND = 240;
    /** 淡入用时 */
    private static final long DUR_FADE = 260;
    /** 列表项之间的错开间隔 */
    private static final long STAGGER = 32;

    /* ---- M3 曲线（项目无 androidx，用 PathInterpolator 手写等价曲线） ---- */

    /**
     * M3 "emphasized decelerate"：先快后缓，收尾极稳。
     * 用于**高度/透明度**这类不能过冲的属性 —— 高度冲过头会把内容裁掉，
     * 透明度超 1 会被裁剪，所以这类一律走单调曲线。
     */
    /** 每个 View 当前的高度动画。expand/collapse 互不取消的话，
        先点收起、240ms 内再点展开时，收起那边的结束回调仍会把 View 置 GONE ——
        区块消失、按钮却显示「收起」。 */
    private static final java.util.WeakHashMap<View, ValueAnimator> HEIGHT_ANIM =
            new java.util.WeakHashMap<View, ValueAnimator>();

    private static final android.view.animation.Interpolator EMPHASIZED =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);

    /**
     * M3 spring-spatial-fast 的近似：ζ0.6 / k800，过冲约 +9%。
     * 用于**位移/缩放** —— 有重量感。OvershootInterpolator(2.2) 的
     * 峰值过冲实测约 9~10%，和官方值对得上。
     */
    private static android.view.animation.Interpolator springFast() {
        return new android.view.animation.OvershootInterpolator(2.2f);
    }

    /**
     * M3 spring-spatial-default 的近似：ζ0.8 / k380，过冲约 +1.5%。
     * 比 fast 更克制，用于中景元件（整块卡片、整行）。
     */
    private static android.view.animation.Interpolator springDefault() {
        return new android.view.animation.OvershootInterpolator(1.1f);
    }

    private Anim() { }

    /**
     * 把视图从"当前高度"补间到目标高度，结束后交还给 wrap_content。
     *
     * 高度动画不能用 ViewPropertyAnimator（它没有 height 属性），
     * 只能自己 ValueAnimator 一帧帧设 layoutParams。
     */
    private static void animateHeight(final View v, final int from, final int to,
                                      final Runnable end) {
        /* 视图还没被 addView 时 getLayoutParams() 是 null —— 动态 new 出来的控件
           在挂上父容器之前调过来就会撞上（原来的 setVisibility 不需要布局参数，
           所以换动画之前一直没暴露）。这时没法补间高度，直接落定即可，
           绝不能为了动画把应用搞崩。 */
        if (v.getLayoutParams() == null) {
            v.setVisibility(to == 0 ? View.GONE : View.VISIBLE);
            if (end != null) end.run();
            return;
        }
        /* 先把同一 View 上还没跑完的高度动画掐掉，否则两个动画会互相打架 */
        ValueAnimator prev = HEIGHT_ANIM.get(v);
        if (prev != null) {
            try { prev.cancel(); } catch (Throwable ignored) { }
        }
        v.getLayoutParams().height = from;
        v.requestLayout();
        ValueAnimator a = ValueAnimator.ofInt(from, to);
        a.setDuration(DUR_EXPAND);
        a.setInterpolator(EMPHASIZED);   // 高度不能过冲，用单调的 emphasized
        HEIGHT_ANIM.put(v, a);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator an) {
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp == null) return;         // 动画途中被摘出父容器
                lp.height = (Integer) an.getAnimatedValue();
                v.requestLayout();
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator an) {
                if (HEIGHT_ANIM.get(v) == an) HEIGHT_ANIM.remove(v);
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp != null) {
                    /* 收回收起态时要留成 0，展开态交还 wrap_content ——
                       固定死高度的话，里面内容一变化就会被裁掉 */
                    lp.height = (to == 0) ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
                    v.requestLayout();
                }
                if (end != null) end.run();
            }
        });
        a.start();
    }

    /** 量出这个视图在"不受高度约束"下的自然高度 */
    private static int naturalHeight(View v) {
        Object p = v.getParent();
        int w = (p instanceof View && ((View) p).getWidth() > 0)
                ? ((View) p).getWidth()
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
        /* 展开一块折叠区时，内容整体淡入会显得"平"；
           拆成子项瀑布（每个错开 36ms）才有"内容在长出来"的感觉。 */
        if (v instanceof ViewGroup) {
            cascadeIn((ViewGroup) v, 90, 6);
        }
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
                .setInterpolator(EMPHASIZED)
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
        /* 必须先置 VISIBLE：布局里很多遮罩/浮层默认是 visibility="gone"
           （busy_overlay 就是），对 GONE 的 View 做 alpha 动画毫无效果 ——
           之前 Busy.show() 调这个，16 处调用全部白调，遮罩一次都没出现过。 */
        v.setVisibility(View.VISIBLE);
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
     * 卡片/列表项的入场：从 0.96 缩放到 1 + 淡入 + 轻微上移。
     *
     * 比纯淡入"有重量"—— 视觉上像是"落"到位置上，而不是"显"出来。
     * 缩放用 springDefault（+1.5% 过冲），克制不夸张。
     */
    public static void popIn(View v, long delayMs) {
        if (v == null) return;
        v.animate().cancel();
        v.setAlpha(0f);
        v.setScaleX(0.96f);
        v.setScaleY(0.96f);
        v.setTranslationY(dp(v, 10));
        v.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(300)
                .setInterpolator(springDefault())
                .start();
    }

    /**
     * 数值变化：旧值上移淡出 → 换值 → 新值从下淡入。
     *
     * 为什么不用"数字滚动"：金额带千分位、货币符号、可能还有后缀，
     * 逐位滚动的实现成本和出错面都太大（还要处理 0→1 的进位）。
     * 上移交叉淡入在观感上同样能表达"数据更新了"，而且对任何格式都安全。
     *
     * @param applyNew 在这里面把文字改成新值
     */
    public static void valueChange(final android.widget.TextView tv, final Runnable applyNew) {
        if (tv == null || applyNew == null) return;
        final float dy = dp(tv, 7);
        tv.animate().cancel();
        tv.animate()
                .alpha(0f)
                .translationY(-dy)
                .setDuration(110)
                .setInterpolator(EMPHASIZED)
                .withEndAction(new Runnable() {
                    public void run() {
                        applyNew.run();                 // 换值
                        tv.setTranslationY(dy);         // 从下方进来
                        tv.animate()
                                .alpha(1f)
                                .translationY(0f)
                                .setDuration(200)
                                .setInterpolator(springDefault())   // 轻微回弹，别太跳
                                .start();
                    }
                })
                .start();
    }

    /**
     * 刷新完成后的"活一下"：轻微放大再回位。
     *
     * 只放大 2% —— 再大就成了"抖动"，会显得廉价。作用是给"数据已更新"
     * 一个肉眼可捕捉的确认，尤其在数字没变的时候（刷新了但余额没动）。
     */
    public static void pulse(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.animate()
                .scaleX(1.02f).scaleY(1.02f)
                .setDuration(120)
                .setInterpolator(EMPHASIZED)
                .withEndAction(new Runnable() {
                    public void run() {
                        v.animate().scaleX(1f).scaleY(1f)
                                .setDuration(220)
                                .setInterpolator(springDefault())
                                .start();
                    }
                })
                .start();
    }

    /**
     * 让一个容器里的子 View 依次淡入（用于设置页列表、卡片列表）。
     *
     * @param startIndex 从第几个开始（前面已显示的不重复播）
     * @param maxCount   最多错开多少个（超出的直接同批出现，避免长列表等太久）
     */
    public static void staggerChildren(ViewGroup box, int startIndex, int maxCount) {
        if (box == null) return;
        int n = box.getChildCount();
        for (int i = Math.max(0, startIndex); i < n; i++) {
            int idx = i - Math.max(0, startIndex);
            fadeIn(box.getChildAt(i), Math.min(idx, maxCount) * STAGGER);
        }
    }

    /**
     * 页面进场：内容整体轻微上移淡入。
     * 位移只有 10dp —— 切页本身有横向滑动，纵向再大就抢戏了。
     */
    public static void enterPage(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(dp(v, 10));
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(280)
                .setInterpolator(EMPHASIZED)
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
                /* 按下：0.96（skill 铁律值），快进不出冲 */
                v.animate().scaleX(0.96f).scaleY(0.96f)
                        .setDuration(110).start();
                break;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                /* 松开：弹簧过冲回弹（OvershootInterpolator ≈ M3 spatial-fast 的
                   +9% 过冲），比线性回弹"活"得多 —— 这是"显旧"与"现代"的分界 */
                v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(270)
                        .setInterpolator(springFast())
                        .start();
                break;
        }
        return false;
    }

    /**
     * 给整棵 View 树上**所有可点元素**挂按下反馈（skill 铁律 5）。
     *
     * 为什么用全局遍历而不是逐个手挂：全工程 60+ 处 setOnClickListener，
     * 手挂必然漏；而且新增控件时没人记得挂。树遍历一次到位，
     * 规则是"有 OnClickListener 且自身可点"的 View 才挂 —— 不影响列表滚动。
     *
     * ⚠️ 只在页面构建完成后调一次；动态添加的 View（对话框）不在此列，
     * 对话框按钮走 AlertDialog 自带的按压态，不需要。
     */
    public static void attachGlobalPressFeedback(View root) {
        if (root == null) return;
        if (root.isClickable() && root.hasOnClickListeners()) {
            root.setOnTouchListener(new android.view.View.OnTouchListener() {
                public boolean onTouch(View v, android.view.MotionEvent e) {
                    return pressFeedback(v, e);
                }
            });
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                attachGlobalPressFeedback(g.getChildAt(i));
            }
        }
    }

    private static float dp(View v, float value) {
        return value * v.getResources().getDisplayMetrics().density + 0.5f;
    }

    /* ==================== 数字滚动 ==================== */

    /** 正在跑的滚动动画（每个 TextView 一个，重复触发要先停掉旧的） */
    private static final java.util.WeakHashMap<View, ValueAnimator> COUNTING =
            new java.util.WeakHashMap<View, ValueAnimator>();

    /**
     * 金额从旧值"滚"到新值。
     *
     * 比交叉淡入（valueChange）多一层信息：用户能直接看出**涨了还是跌了**、
     * 差多少 —— 淡入换值只会让人知道"变了"。
     *
     * 用 EMPHASIZED（单调曲线）而不是弹簧：数字中途回弹会让人读错数。
     */
    public static void countTo(final android.widget.TextView tv,
                               double from, double to, final String fmt) {
        if (tv == null) return;
        /* 没有旧值 / 值没变 / 数值非法 → 直接落，别演 */
        if (Double.isNaN(from) || Double.isNaN(to) || Math.abs(to - from) < 1e-9) {
            try { tv.setText(String.format(fmt, to)); } catch (Throwable ignored) { }
            return;
        }
        ValueAnimator old = COUNTING.get(tv);
        if (old != null) {
            try { old.cancel(); } catch (Throwable ignored) { }
        }
        ValueAnimator va = ValueAnimator.ofFloat((float) from, (float) to);
        va.setDuration(560);
        va.setInterpolator(EMPHASIZED);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                Object v = a.getAnimatedValue();
                if (!(v instanceof Float)) return;
                try {
                    tv.setText(String.format(fmt, ((Float) v).doubleValue()));
                } catch (Throwable ignored) { }
            }
        });
        va.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator a) {
                /* 必须移除：COUNTING 的 value（animator）持有监听器，监听器又持有
                   TextView —— 也就是 key 本身。弱引用 key 永远不回收，
                   每切一次主题 recreate() 就泄漏一个旧 Activity。 */
                if (COUNTING.get(tv) == a) COUNTING.remove(tv);
                try { tv.setText(String.format(fmt, to)); } catch (Throwable ignored) { }
            }
        });
        COUNTING.put(tv, va);
        va.start();
    }

    /* ==================== 交错的"瀑布"入场 ==================== */

    /**
     * 一组子 View 依次从下方滑入 + 淡入。
     *
     * 和 stagger() 的区别：stagger 只错开时间，元素自身不动；
     * 这个是完整的入场（位移 + 缩放 + 淡入），用于"一屏内容整体出现"。
     *
     * @param maxCount 最多动几个（后面的直接显示，避免长列表播一分钟）
     */
    public static void cascadeIn(ViewGroup box, int startIndex, int maxCount) {
        if (box == null) return;
        int n = box.getChildCount();
        int done = 0;
        for (int i = 0; i < n; i++) {
            final View v = box.getChildAt(i);
            if (v == null) continue;
            if (done >= maxCount) { v.setAlpha(1f); v.setTranslationY(0f); v.setScaleX(1f); v.setScaleY(1f); continue; }
            v.setAlpha(0f);
            v.setTranslationY(dp(v, 14));
            v.setScaleX(0.97f);
            v.setScaleY(0.97f);
            v.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay((long) startIndex + (long) done * 36)
                    .setDuration(280)
                    .setInterpolator(springDefault())
                    .start();
            done++;
        }
    }

    /* ==================== 弹窗弹性弹出 ==================== */

    /** 弹窗内容缩放弹出（0.94 → 1，带轻微回弹） */
    public static void popInDialog(View content) {
        if (content == null) return;
        content.setAlpha(0f);
        content.setScaleX(0.94f);
        content.setScaleY(0.94f);
        content.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240)
                .setInterpolator(springDefault())
                .start();
    }

    /* ==================== 3D 倾斜（按压时跟着手指） ==================== */

    /**
     * 给卡片挂上"按压立体倾斜"：手指按在哪个位置，卡片就朝那个角度微微翻转。
     *
     * iOS 的按压只做 scale —— 加一点真实的透视旋转，立体感完全不是一个量级。
     * 关键是**幅度必须小**（±3°）且**必须有阻尼**，大了会像坏掉的转盘。
     *
     * 只作用于被按的那一张，不碰兄弟节点，所以不会引起重新布局。
     */
    public static void attachTilt(final View v, final float maxDeg) {
        if (v == null) return;
        try {
            /* camera distance 不设的话，旋转看起来像被压扁的平行四边形。
               8000 是经验值：再小透视过猛，再大就接近正交投影没立体感。 */
            v.setCameraDistance(v.getResources().getDisplayMetrics().density * 8000f);
        } catch (Throwable ignored) { }
        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, android.view.MotionEvent e) {
                int action = e.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN
                        || action == android.view.MotionEvent.ACTION_MOVE) {
                    float w = Math.max(1f, view.getWidth());
                    float h = Math.max(1f, view.getHeight());
                    float px = (e.getX() / w - 0.5f) * 2f;      // -1 .. 1
                    float py = (e.getY() / h - 0.5f) * 2f;
                    /* 手在左上 → 卡片向左上"低头"，所以 Y 轴反向、X 轴正向 */
                    view.setRotationY(-px * maxDeg);
                    view.setRotationX(py * maxDeg);
                    return false;      // 不消费：点击 / 长按 / 滚动照常
                }
                if (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    view.animate()
                            .rotationX(0f).rotationY(0f)
                            .setDuration(320)
                            .setInterpolator(springDefault())   // 回弹时有一点过冲，像被松开的弹簧
                            .start();
                    return false;
                }
                return false;
            }
        });
    }

    /** 一次性倾斜反馈（不需要持续跟手时用） */
    public static void tiltPulse(View v, float deg) {
        if (v == null) return;
        try {
            v.setCameraDistance(v.getResources().getDisplayMetrics().density * 8000f);
        } catch (Throwable ignored) { }
        v.animate().rotationX(-deg).setDuration(90)
                .withEndAction(new Runnable() {
                    public void run() {
                        v.animate().rotationX(0f).setDuration(300)
                                .setInterpolator(springDefault()).start();
                    }
                }).start();
    }

    /* ==================== 微光扫过（骨架屏 / 刷新中） ==================== */

    /**
     * 在一张卡片上扫过一道高光。
     *
     * 用 ViewOverlay 画一个渐变条然后平移过去 —— 不碰卡片自己的背景，
     * 所以和主题、和背景形状都不冲突。
     */
    public static void shimmer(final View v, long delayMs) {
        if (v == null) return;
        v.postDelayed(new Runnable() {
            public void run() {
                try {
                    if (!v.isAttachedToWindow() || v.getWidth() <= 0) return;
                    android.view.ViewOverlay ov = v.getOverlay();
                    if (ov == null) return;
                    final float bandW = v.getWidth() * 0.45f;
                    final android.graphics.Paint p = new android.graphics.Paint();
                    p.setShader(new android.graphics.LinearGradient(
                            0, 0, bandW, 0,
                            new int[]{ 0x00FFFFFF, 0x22FFFFFF, 0x00FFFFFF },
                            new float[]{ 0f, 0.5f, 1f },
                            android.graphics.Shader.TileMode.CLAMP));
                    final android.graphics.drawable.Drawable d =
                            new android.graphics.drawable.Drawable() {
                                public void draw(android.graphics.Canvas c) {
                                    c.save();
                                    c.translate(getBounds().left, 0);
                                    c.clipRect(0, getBounds().top, bandW, getBounds().bottom);
                                    c.drawRect(getBounds(), p);
                                    c.restore();
                                }
                                public void setAlpha(int a) { }
                                public void setColorFilter(android.graphics.ColorFilter cf) { }
                                public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
                            };
                    d.setBounds(0, 0, v.getWidth(), v.getHeight());
                    ov.add(d);
                    ValueAnimator va = ValueAnimator.ofFloat(-bandW, v.getWidth());
                    va.setDuration(680);
                    va.setInterpolator(EMPHASIZED);
                    va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        public void onAnimationUpdate(ValueAnimator a) {
                            Object o = a.getAnimatedValue();
                            if (!(o instanceof Float)) return;
                            d.setBounds((int) ((Float) o).floatValue(), 0,
                                    (int) ((Float) o).floatValue() + (int) bandW, v.getHeight());
                            v.invalidate();
                        }
                    });
                    va.addListener(new AnimatorListenerAdapter() {
                        public void onAnimationEnd(Animator a) {
                            try { ov.remove(d); } catch (Throwable ignored) { }
                        }
                    });
                    va.start();
                } catch (Throwable ignored) { }
            }
        }, delayMs);
    }

    /* ==================== 弹簧吸附到某个 X ==================== */

    /**
     * 把一个 View 用弹簧送到目标 translationX。
     *
     * 和直接 setTranslationX 的区别：有质量和回弹，像被磁铁吸过去。
     * 用于导航滑块、开关手柄这类"吸附"动作。
     */
    public static void springTo(View v, float targetX) {
        if (v == null) return;
        v.animate()
                .translationX(targetX)
                .setDuration(380)
                .setInterpolator(springFast())
                .start();
    }
}
