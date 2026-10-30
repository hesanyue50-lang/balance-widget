package com.minis.balancewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 排序弹窗：▲ ▼ 上下移动。
 *
 * 为什么不用长按拖拽：滚动容器里的拖拽要么和 ScrollView 抢手势，
 * 要么得自己做 ItemTouchHelper（那要 androidx，本项目是纯手写构建链，没依赖）。
 * 上下箭头在手机上其实更好点、更准 —— 拖拽在长列表里经常落错位置。
 *
 * 每点一下就立刻落盘 + 回调刷新，所以「返回键退出」不会丢结果。
 */
public final class OrderDialog {

    private OrderDialog() { }

    /**
     * @param which   OrderStore.CARDS / OrderStore.STATS
     * @param ids     当前要排序的 id（平台 id），按现有显示顺序传入
     * @param names   与 ids 一一对应的显示名
     * @param onSaved 顺序变化后回调（用来重绘宿主界面）
     */
    public static void show(final Activity a, final String which, String title,
                            List<String> ids, List<String> names, final Runnable onSaved) {
        /* 交给 DragSort（长按拖动）。原来这里是 ▲▼ 上下移动：
           长列表里点十几次才能把一个平台挪到顶，拖拽一次到位。
           注：老实现注释里担心的"和 ScrollView 抢手势"已经用
           requestDisallowInterceptTouchEvent 解决。 */
        List<String> ordered = OrderStore.apply(a, which, ids);
        List<String> nms = new ArrayList<String>();
        java.util.Map<String, String> byId = new java.util.HashMap<String, String>();
        for (int i = 0; i < ids.size() && i < names.size(); i++) byId.put(ids.get(i), names.get(i));
        for (int i = 0; i < ordered.size(); i++) {
            String nm = byId.get(ordered.get(i));
            nms.add(nm == null ? ordered.get(i) : nm);
        }
        DragSort.show(a, which, title, ordered, nms, onSaved);
    }

    private static void commit(Activity a, String which, List<String> order,
                               Runnable onSaved, Runnable refresh) {
        OrderStore.save(a, which, new ArrayList<String>(order));
        refresh.run();
        if (onSaved != null) onSaved.run();
    }

    private static TextView arrow(Activity a, String sym, boolean enabled, View.OnClickListener l) {
        TextView t = new TextView(a);
        t.setText(sym);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setWidth(dp(a, 44));
        t.setHeight(dp(a, 40));
        t.setTextColor(enabled ? Theme.c(a, R.attr.c_accent) : Theme.c(a, R.attr.c_tx3));
        if (enabled) {
            t.setBackgroundResource(Theme.bgRes(t.getContext(), R.attr.bg_pill));
        }
        t.setAlpha(enabled ? 1f : 0.5f);
        t.setOnClickListener(enabled ? l : null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(a, 6);
        t.setLayoutParams(lp);
        t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }
}
