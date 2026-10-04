package com.minis.balancewidget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.HorizontalScrollView;

/**
 * 三页横向分页容器（左右滑动切换）。
 *
 * 为什么用 HorizontalScrollView 打底：
 *   项目没有 androidx，拿不到 ViewPager；而 HorizontalScrollView 自带
 *   跟手拖动、滚动计算、边缘钳制这套机制，白用不用白不用 ——
 *   真正需要自己写的只有「松手吸附到整页」。
 *
 * 两个关键点：
 *   ① **每页宽度必须显式设成容器宽度**。HorizontalScrollView 量宽度时用的是
 *      UNSPECIFIED，子项写 match_parent / wrap_content 都得不到屏宽，
 *      会在右侧留出空白并让吸附位置算错。所以在 onSizeChanged 里统一改写。
 *   ② 松手要按**速度**决定翻页方向，而不是只看位置 —— 否则快速甩一下
 *      只滑了 1/3 屏就回弹，手感很"粘"。速度够大时朝该方向至少翻一页。
 */
public class PanelPager extends HorizontalScrollView {

    public interface Listener {
        /** 滚动过程中的连续进度：0.0 = 第 1 页，1.5 = 正处在第 2、3 页中间 */
        void onPagerScroll(float progress);
        /** 停稳在某页（idx 从 0 起） */
        void onPageSettled(int page);
    }

    /** 甩动判定：超过这个像素/秒就当"用户想翻页"而不是"手抖" */
    private static final float FLING_VELOCITY = 500f;
    private static final long ANIM_MIN_MS = 200L;
    private static final long ANIM_MAX_MS = 380L;

    private Listener listener;
    private int pageWidth;
    private int currentPage;

    private android.view.VelocityTracker vt;
    private ValueAnimator anim;
    private int animFrom, animTo;

    public PanelPager(Context c) { super(c); init(); }
    public PanelPager(Context c, AttributeSet a) { super(c, a); init(); }
    public PanelPager(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        touchSlop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
    }

    public void setListener(Listener l) { this.listener = l; }

    /**
     * 点空白就收键盘 + 清焦点。
     *
     * 「点空白关闭输入法」这个看似理所当然的行为，Android 原生并不提供 ——
     * 不做的话键盘会一直杵在那儿挡住半屏。
     *
     * 放在 dispatchTouchEvent 而不是 onTouchEvent：无论这一下点击最终是被
     * 子页（卡片、开关、编辑框）消费还是被自己消费，都会先到这里，
     * 所以"点任何地方"都能生效。点到 EditText 本身也没问题 ——
     * 它随后会自己 requestFocus，键盘再弹起来。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            /* 死区判定放在这里 —— 这是**唯一一定被调用**的入口。
               若放到 onInterceptTouchEvent 里会漏：那个方法只在子视图接住了 DOWN
               时才会被回调；子视图没接住时 AOSP 直接把后续事件标成 intercepted，
               死区逻辑永远不执行（第一版就是这么失效的）。 */
            deadZoneGesture = inHDeadZone(e.getRawX(), e.getRawY());

            View f = findFocus();
            if (f != null && f instanceof android.widget.EditText) {
                f.clearFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(getWindowToken(), 0);
                }
            }
        }
        boolean r = super.dispatchTouchEvent(e);
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) deadZoneGesture = false;
        return r;
    }

    /**
     * 禁止「子视图获焦时把分页容器滚过去」。
     *
     * 坑：设置页里有 EditText，启动时会自动抢焦点；HorizontalScrollView
     * 收到 requestChildRectangleOnScreen 后会把内容滚到那个子视图处 ——
     * 表现为**一打开应用就直接停在设置页**，而且只滚到"刚好可见"的半路位置，
     * 不吸附齐整。
     *
     * 垂直方向不受影响：软键盘弹起时让输入框可见由每一页内部的
     * PullScrollView（纵向 ScrollView）自己处理。
     */
    @Override
    public boolean requestChildRectangleOnScreen(View child, android.graphics.Rect rect,
                                                 boolean immediate) {
        return false;
    }

    public int getPage() { return currentPage; }

    /** 可见页数量（被隐藏的页面 GONE，不计入） */
    public int pageCount() {
        if (getChildCount() == 0) return 0;
        ViewGroup row = (ViewGroup) getChildAt(0);
        int c = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i).getVisibility() != View.GONE) c++;
        }
        return c;
    }

    /** 第 i 个**可见**页（隐藏页会被跳过，索引与导航条一一对应） */
    public View pageAt(int i) {
        if (getChildCount() == 0) return null;
        ViewGroup row = (ViewGroup) getChildAt(0);
        int seen = 0;
        for (int k = 0; k < row.getChildCount(); k++) {
            View v = row.getChildAt(k);
            if (v.getVisibility() == View.GONE) continue;
            if (seen == i) return v;
            seen++;
        }
        return null;
    }

    // ---------- 尺寸 ----------

    /**
     * 关键：显式把每一页的宽度定成「视口宽」，行容器定成「页数 × 视口宽」。
     *
     * 为什么不能靠 match_parent / wrap_content（都踩过）：
     *   ① 子页写 match_parent：LinearLayout 在 AT_MOST 约束下会把所有
     *      match_parent 子项**用 UNSPECIFIED 重新测一遍** —— 于是每页宽度塌缩成
     *      「内容自然宽度」，三页被压窄挤在一起。
     *   ② 行容器写 wrap_content 且子页宽度算对时，resolveSizeAndState 又会把
     *      行容器压回屏宽，HorizontalScrollView 以为只有一屏，**滚不到第 2 页**。
     *
     * 所以两边都要写死具体值：子页 = w，行容器 = w × n。
     *
     * 直接改 LayoutParams 的 width 字段（而不调 setLayoutParams）是有意的：
     * 后者会 requestLayout，在测量过程中调用会让这次测量作废。
     */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        if (w > 0 && getChildCount() > 0 && getChildAt(0) instanceof ViewGroup) {
            ViewGroup row = (ViewGroup) getChildAt(0);
            int n = row.getChildCount();
            int shown = 0;
            for (int i = 0; i < n; i++) {
                View p = row.getChildAt(i);
                ViewGroup.LayoutParams lp = p.getLayoutParams();
                if (p.getVisibility() == View.GONE) {
                    /* 被隐藏的页面宽度必须归 0：GONE 的子项在 LinearLayout 里本来就
                       不占位，但**我们在这里显式设过宽度**，如果不跳过，
                       隐藏页仍会占掉一整屏，吸附位置就整体错位。 */
                    if (lp != null && lp.width != 0) lp.width = 0;
                    continue;
                }
                shown++;
                if (lp != null && lp.width != w) lp.width = w;
            }
            ViewGroup.LayoutParams rlp = row.getLayoutParams();
            if (rlp != null && rlp.width != w * shown) rlp.width = w * shown;
            pageWidth = w;
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w > 0) pageWidth = w;
        if (pendingPage >= 0) {
            final int p = pendingPage;
            pendingPage = -1;
            currentPage = p;
            /* 此刻行容器可能还没按新宽度 layout，可滚动范围还是旧的，
               scrollTo 会被钳制 —— 所以推到下一帧再对齐一次。 */
            scrollTo(p * Math.max(pageWidth, 0), 0);
            post(new Runnable() {
                public void run() {
                    scrollTo(p * Math.max(pageWidth, 0), 0);
                    if (listener != null) listener.onPageSettled(p);
                }
            });
            return;
        }
        final int p = currentPage;
        post(new Runnable() {
            public void run() { scrollTo(p * Math.max(pageWidth, 0), 0); }
        });
    }

    // ---------- 滚动 ----------

    @Override
    protected void onScrollChanged(int l, int t, int ol, int ot) {
        super.onScrollChanged(l, t, ol, ot);
        if (listener != null && pageWidth > 0) {
            listener.onPagerScroll(l / (float) pageWidth);
        }
    }

    /** 切页时如果视口宽度还没量出来，先记下来，等 onSizeChanged 再补上 */
    private int pendingPage = -1;

    /** 切到某页（带动画） */
    public void setPage(int idx, boolean smooth) {
        int n = pageCount();
        if (n == 0) return;
        final int target = Math.max(0, Math.min(n - 1, idx));
        if (pageWidth == 0) {          // 还没布局完，等量到宽度再对齐
            pendingPage = target;
            currentPage = target;
            return;
        }
        final int to = target * pageWidth;
        cancelAnim();
        if (!smooth || Math.abs(getScrollX() - to) < 2) {
            scrollTo(to, 0);
            settle(target);
            return;
        }
        animateTo(to, target);
    }

    /** 自己驱动动画而不是用 smoothScrollTo：可以配更柔和的插值器和更合适的时长 */
    private void animateTo(int to, final int page) {
        animFrom = getScrollX();
        animTo = to;
        int dist = Math.abs(animTo - animFrom);
        long dur = Math.min(ANIM_MAX_MS, ANIM_MIN_MS + dist / 2L);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(dur);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                scrollTo((int) (animFrom + (animTo - animFrom) * f), 0);
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            public void onAnimationEnd(android.animation.Animator a) {
                scrollTo(animTo, 0);
                settle(page);
            }
        });
        anim.start();
    }

    private void cancelAnim() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    private void settle(int page) {
        currentPage = page;
        if (listener != null) listener.onPageSettled(page);
    }

    // ---------- 手势 ----------

    /* 手势方向判定（死区）。
     *
     * 为什么需要：HorizontalScrollView 默认的拦截逻辑**只看水平位移有没有超过
     * touchSlop，完全不看垂直**。所以下拉刷新时手指只要有一点点水平抖动，
     * 翻页容器就把手势抢走 —— 表现就是「下拉怎么都刷不出来」。
     *
     * 解法是给水平滑动设一道"死区"：先判方向，只有水平**明显占优**才归翻页，
     * 垂直占优的一律让给子页（垂直滚动 / 下拉刷新）。
     * 判定只做一次（首个意图定胜负），中途不会来回切换，手感才稳定。
     */
    /** 水平要超过 touchSlop 的几倍才认作翻页意图（死区） */
    private static final float H_DEAD_ZONE = 2.0f;
    /** 垂直方向只要占优到这个比例，就把手势让给子页 */
    private static final float V_RATIO = 1.2f;
    /** 水平方向要占优到这个比例，才认作翻页 */
    private static final float H_RATIO = 1.5f;

    /** 手势抖动阈值：由 ViewConfiguration 给出，随设备密度变化 */
    private int touchSlop;
    private float downX, downY;
    /** 按下点在屏幕上的原始坐标（判定死区用 —— 死区视图和本控件不在同一坐标系） */
    private float downRawX, downRawY;
    /** 0=未判定  1=已让给子页（垂直/死区）  2=归自己翻页（水平） */
    private int gestureMode;

    /**
     * 横向滑动死区。落在这个 View 上的手势**永不翻页** —— 全部让给它自己处理。
     *
     * 为什么需要：用量统计的折线图靠按住 + 左右滑动来看某个时间点的数值。
     * 但分页器把横向滑动一律判成"翻页"，两者抢同一个手势 ——
     * 结果就是用户想在图上拖一下看数据，页面先翻走了。
     * 给图表划一块死区，各管各的，互不打架。
     */
    private android.view.View hDeadZone;

    /** 由宿主页在布局完成后指定（通常是那张折线图） */
    public void setHDeadZone(android.view.View v) { hDeadZone = v; }

    /** 本次手势是否起于死区（DOWN 时判定一次，全程沿用）。
     *  判定在 dispatchTouchEvent 里做 —— 见文件上方那个方法。 */
    private boolean deadZoneGesture;

    /** 按下点是否落在死区内。两个 View 坐标系不同，统一换算到窗口坐标再比 */
    private boolean inHDeadZone(float rawX, float rawY) {
        if (hDeadZone == null || hDeadZone.getWidth() == 0) return false;
        int[] hl = new int[2];
        int[] sl = new int[2];
        try {
            hDeadZone.getLocationInWindow(hl);
            getLocationInWindow(sl);
        } catch (Throwable t) {
            return false;
        }
        float x = rawX - sl[0];
        float y = rawY - sl[1];
        float l = hl[0] - sl[0];
        float t = hl[1] - sl[1];
        return x >= l && x <= l + hDeadZone.getWidth()
                && y >= t && y <= t + hDeadZone.getHeight();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        /* 起于死区的手势：从头到尾不抢 */
        if (deadZoneGesture) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                gestureMode = 0;
                break;
            case MotionEvent.ACTION_MOVE:
                /* 已判定让给子页：全程不打断（否则下拉到一半会被抢走，前功尽弃） */
                if (gestureMode == 1) return false;
                if (gestureMode == 0) {
                    float dx = Math.abs(e.getX() - downX);
                    float dy = Math.abs(e.getY() - downY);
                    if (dy > touchSlop && dy > dx * V_RATIO) {
                        gestureMode = 1;      // 垂直占优 → 让给子页（下拉刷新走这条）
                        return false;
                    }
                    if (dx > touchSlop * H_DEAD_ZONE && dx > dy * H_RATIO) {
                        /* 按下点若在死区（折线图）内，这一整套手势都归子页 ——
                           复用 gestureMode=1「已让给子页」，后续 MOVE 全程不抢。 */
                        if (inHDeadZone(downRawX, downRawY)) {
                            gestureMode = 1;
                            return false;
                        }
                        gestureMode = 2;      // 水平明显占优 → 翻页
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                gestureMode = 0;
                break;
        }
        return super.onInterceptTouchEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        /* 兜底：万一事件还是落到自己头上（子视图全程没接住），
           死区手势直接放行，绝不拿它去翻页。 */
        if (deadZoneGesture) return false;
        if (vt == null) vt = android.view.VelocityTracker.obtain();
        vt.addMovement(e);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelAnim();          // 手指按下，立刻停掉正在跑的吸附动画，改为跟手
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float vx = 0f;
                try {
                    vt.computeCurrentVelocity(1000);
                    vx = vt.getXVelocity();
                } catch (Throwable ignored) { }
                snap(vx);
                if (vt != null) { vt.recycle(); vt = null; }
                break;
        }
        return super.onTouchEvent(e);
    }

    /**
     * 禁用 HorizontalScrollView 自带的惯性滚动。
     *
     * 不关的话，快速甩一下会一路 fling 到最右侧（一次滑过好几页），
     * 松手吸附时按位置就近取整，结果直接停在最后一页。
     * 翻页交给下面的 snap() 统一决定，一次最多挪一页。
     */
    @Override
    public void fling(int velocityX) {
        // 故意不调 super
    }

    /**
     * 松手吸附。
     *
     * 三个约束叠加，保证"一次最多翻一页"+ 不越界：
     *   ① 快速甩动 → 朝甩动方向再多一页（否则"甩一下只动 1/3 屏又弹回来"很难受）；
     *   ② 夹在 [0, 页数-1]；
     *   ③ 再夹在 [当前页-1, 当前页+1] —— 这条才是"滑动限位"，
     *      即使手指一次性拖了很远，也只走一页。
     */
    private void snap(float vx) {
        if (pageWidth <= 0) return;
        int n = pageCount();
        if (n <= 0) return;
        int base = Math.round(getScrollX() / (float) pageWidth);
        int page = base;
        if (vx < -FLING_VELOCITY)      page = base + 1;   // 向左甩 → 下一页
        else if (vx > FLING_VELOCITY)  page = base - 1;   // 向右甩 → 上一页
        page = Math.max(0, Math.min(n - 1, page));
        page = Math.max(currentPage - 1, Math.min(currentPage + 1, page));
        setPage(page, true);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnim();
        if (vt != null) { vt.recycle(); vt = null; }
        super.onDetachedFromWindow();
    }
}
