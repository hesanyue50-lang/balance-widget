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
        if (a == null || ids == null || ids.isEmpty()) return;

        // 以「当前显示顺序」为准起步，再用存档顺序对齐（存档顺序才是权威）
        final List<String> order = OrderStore.apply(a, which, ids);
        final java.util.Map<String, String> nameOf = new java.util.HashMap<String, String>();
        for (int i = 0; i < ids.size() && i < names.size(); i++) nameOf.put(ids.get(i), names.get(i));

        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 8), dp(a, 6), dp(a, 8), dp(a, 6));

        ScrollView sc = new ScrollView(a);
        sc.addView(box);
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.6f);
        sc.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(maxH, dp(a, 420))));

        /* 用一格数组自引用：匿名内部类里要回调刷新自己，直接引用变量会「未初始化」 */
        final Runnable[] refreshRef = new Runnable[1];
        final Runnable refresh = new Runnable() {
            public void run() {
                box.removeAllViews();
                for (int i = 0; i < order.size(); i++) {
                    final int idx = i;
                    String id = order.get(i);
                    String nm = nameOf.get(id);
                    if (nm == null) nm = id;

                    LinearLayout row = new LinearLayout(a);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(a, 6), dp(a, 4), dp(a, 6), dp(a, 4));
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    row.setLayoutParams(rlp);

                    TextView num = new TextView(a);
                    num.setText(String.valueOf(i + 1));
                    num.setTextSize(12);
                    num.setTextColor(0xFF8A94A3);
                    num.setWidth(dp(a, 26));
                    row.addView(num);

                    TextView name = new TextView(a);
                    name.setText(nm);
                    name.setTextSize(14);
                    name.setTextColor(0xFF39424F);
                    name.setLayoutParams(new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    row.addView(name);

                    row.addView(arrow(a, "▲", i > 0, new View.OnClickListener() {
                        public void onClick(View v) {
                            if (idx <= 0) return;
                            String t = order.get(idx - 1);
                            order.set(idx - 1, order.get(idx));
                            order.set(idx, t);
                            commit(a, which, order, onSaved, refreshRef[0]);
                        }
                    }));
                    row.addView(arrow(a, "▼", i < order.size() - 1, new View.OnClickListener() {
                        public void onClick(View v) {
                            if (idx >= order.size() - 1) return;
                            String t = order.get(idx + 1);
                            order.set(idx + 1, order.get(idx));
                            order.set(idx, t);
                            commit(a, which, order, onSaved, refreshRef[0]);
                        }
                    }));

                    box.addView(row);
                    View line = new View(a);
                    LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 1);
                    line.setLayoutParams(llp);
                    line.setBackgroundColor(0x1A8A94A3);
                    if (i < order.size() - 1) box.addView(line);
                }
            }
        };
        refreshRef[0] = refresh;
        refresh.run();

        new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(sc)
                .setPositiveButton("完成", null)
                .show();
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
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setWidth(dp(a, 44));
        t.setHeight(dp(a, 40));
        t.setTextColor(enabled ? 0xFF3D6FD6 : 0xFFC6CDD6);
        if (enabled) {
            t.setBackgroundResource(R.drawable.mini_btn_border);
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
