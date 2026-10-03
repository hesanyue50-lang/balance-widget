package com.minis.balancewidget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 带下拉刷新的滚动容器。
 *
 * 为什么自己写：项目是纯手写构建链，**没有 androidx 依赖**，用不了 SwipeRefreshLayout。
 *
 * 手势要点：
 *   - 只在**已经滑到顶部**（scrollY ≤ 0）且**向下**拖时才接管，正常滚动完全不受影响；
 *   - 越过分辨阈值（touchSlop）那一刻把基准点重置成当前位置，否则指示器会"跳"一大截；
 *   - 接管后 onInterceptTouchEvent 必须**一直返回 true**，不然事件会漏给子 View，
 *     表现为拖到一半突然失灵。
 *
 * 指示器由外部传进来（布局里定义，方便随时改样式），本类只负责它的位移/透明度。
 * 指示器的 topMargin 跟着 ScrollView 的 paddingTop 走 ——
 * 而 paddingTop 正好是顶部栏高度（见 UiInsets），所以它天然出现在顶部栏下方。
 */
public class PullScrollView extends ScrollView {

    public interface Listener {
        /** 拖动中，progress 0~1（1 = 已达触发线） */
        void onPull(float progress);
        /** 松手且已过触发线 */
        void onRefresh();
        /** 松手但没过线，或手势取消 */
        void onCancel();
    }

    private Listener listener;
    private View indicator;
    private TextView label;

    private float downY;
    private boolean pulling;
    private boolean refreshing;
    private boolean pullEnabled = true;

    private int touchSlop;
    private int triggerPx;
    private int indicatorH;

    public PullScrollView(Context c) { super(c); init(c); }
    public PullScrollView(Context c, AttributeSet a) { super(c, a); init(c); }
    public PullScrollView(Context c, AttributeSet a, int d) { super(c, a, d); init(c); }

    private void init(Context c) {
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        triggerPx = dp(c, 68);
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    /** 绑定指示器（布局里那个小胶囊） */
    public void attachIndicator(View ind, TextView lab) {
        this.indicator = ind;
        this.label = lab;
        if (ind != null) {
            ind.setVisibility(View.GONE);
            applyIndicatorTop(getPaddingTop());
        }
    }

    /** 关掉下拉（设置面板用：那里下拉刷新没意义） */
    public void setPullEnabled(boolean enabled) {
        this.pullEnabled = enabled;
        if (!enabled) collapseIndicator();
    }

    public boolean isRefreshing() {
        return refreshing;
    }

    /** 由宿主在刷新开始 / 结束时调用 */
    public void setRefreshing(boolean r) {
        refreshing = r;
        if (indicator == null) return;
        if (r) {
            if (indicatorH <= 0) indicatorH = Math.max(indicator.getHeight(), dp(getContext(), 36));
            indicator.setVisibility(View.VISIBLE);
            indicator.animate().cancel();
            indicator.setAlpha(1f);
            indicator.setTranslationY(0f);
            if (label != null) label.setText("正在刷新…");
        } else {
            collapseIndicator();
        }
    }

    // ---------- 指示器 ----------

    private void updateIndicator(float p) {
        if (indicator == null) return;
        if (indicatorH <= 0) indicatorH = Math.max(indicator.getHeight(), dp(getContext(), 36));
        if (indicator.getVisibility() != View.VISIBLE) indicator.setVisibility(View.VISIBLE);
        indicator.animate().cancel();
        indicator.setAlpha(0.25f + 0.75f * p);
        indicator.setTranslationY(-indicatorH * (1f - p));
        if (label != null && !refreshing) {
            label.setText(p >= 1f ? "松手刷新" : "下拉刷新");
        }
    }

    private void collapseIndicator() {
        if (indicator == null || indicator.getVisibility() != View.VISIBLE) return;
        if (indicatorH <= 0) indicatorH = Math.max(indicator.getHeight(), dp(getContext(), 36));
        indicator.animate().cancel();
        indicator.animate().alpha(0f).translationY(-indicatorH)
                .setDuration(160)
                .withEndAction(new Runnable() {
                    public void run() {
                        if (!refreshing) indicator.setVisibility(View.GONE);
                    }
                }).start();
    }

    private void applyIndicatorTop(int top) {
        if (indicator == null) return;
        ViewGroup.LayoutParams lp = indicator.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
            if (flp.topMargin != top) {
                flp.topMargin = top;
                indicator.setLayoutParams(flp);   // 顶部栏高度变化时跟着让位
            }
        }
    }

    /** paddingTop 是顶部栏高度，指示器要落在它下面 */
    @Override
    public void setPadding(int l, int t, int r, int b) {
        super.setPadding(l, t, r, b);
        applyIndicatorTop(t);
    }

    // ---------- 手势 ----------

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (pulling) return true;                       // 已接管：必须持续拦截，否则拖到一半会失灵
        if (refreshing || !pullEnabled) return super.onInterceptTouchEvent(e);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getY();
                break;
            case MotionEvent.ACTION_MOVE: {
                float dy = e.getY() - downY;
                if (dy > touchSlop && getScrollY() <= 0) {
                    pulling = true;
                    downY = e.getY();                   // 重置基准，避免指示器一上来就跳
                    updateIndicator(0f);
                    return true;
                }
                break;
            }
        }
        return super.onInterceptTouchEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (pulling) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getY() - downY;
                    float p = dy <= 0 ? 0f : Math.min(1f, dy / triggerPx);
                    updateIndicator(p);
                    if (listener != null) listener.onPull(p);
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    float dy = e.getY() - downY;
                    pulling = false;
                    if (dy >= triggerPx) {
                        if (listener != null) listener.onRefresh();
                        /* 监听者若因为「已在刷新中」之类没接管，把指示器收回去，别僵在那 */
                        postDelayed(new Runnable() {
                            public void run() {
                                if (!refreshing) collapseIndicator();
                            }
                        }, 120);
                    } else {
                        collapseIndicator();
                        if (listener != null) listener.onCancel();
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    pulling = false;
                    collapseIndicator();
                    if (listener != null) listener.onCancel();
                    return true;
            }
            return true;
        }
        return super.onTouchEvent(e);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
