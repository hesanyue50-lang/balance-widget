package com.minis.balancewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 页面管理弹窗：调整顺序 / 隐藏 / 设为主页面。
 *
 * 「设为主页面」与「排到最左」是同一件事 —— 用户要的是打开应用就落在那一页，
 * 而它排在导航条第一个才符合直觉，所以合并成一个操作（见 PageStore.setHome）。
 *
 * 顺序调整沿用 ▲▼ 而不是拖拽：这里的列表很短（最多 4 项），
 * 箭头更精确；而且项目没有 androidx，拖拽要自己写还容易和滚动容器抢手势。
 */
public final class PageManager {

    private PageManager() { }

    public static void show(final Activity act, final Runnable onChanged) {
        final LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(act, 8), dp(act, 6), dp(act, 8), dp(act, 6));

        ScrollView sc = new ScrollView(act);
        sc.addView(box);
        sc.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 420)));

        /* 顺序表用可变列表（要和界面上的 ▲▼ 同步改） */
        final java.util.ArrayList<String> order =
                new java.util.ArrayList<String>(PageStore.order(act));

        /* 拖拽排序的状态（照 DragSort / LinkMesh 那套）：
           固定行高 + 滞回换位 + 不 bringToFront。 */
        final int rowH = dp(act, 64);
        final java.util.Map<String, View> rowByKey =
                new java.util.HashMap<String, View>();
        final View[] dragging = new View[1];
        final String[] dragKey = new String[1];
        final float[] startRawY = new float[1];
        /* 拖动开始那一刻的索引快照（和 DragSort 同一套算法）：
           不能读 getTop()，ScrollView 一滚就变，位移会算错。 */
        final java.util.Map<String, Integer> baseIdx = new java.util.HashMap<String, Integer>();

        /* 上面的 Runnable 里用 run() 自调用是无效的（匿名类方法遮蔽），
           所以这里用一格数组做真正的自引用。 */
        final Runnable[] self = new Runnable[1];
        self[0] = new Runnable() {
            public void run() {
                box.removeAllViews();
                rowByKey.clear();
                box.setAlpha(0f);      // 配合末尾的 Anim.fade，重建时才有"浮现"的过程

                TextView tip = new TextView(act);
                tip.setText("长按任意一行拖动排序（左侧 ≡ 是手柄）。\n"
                        + "「设为主页」= 打开应用直接进这一页，并自动排到最左。\n"
                        + "隐藏后导航条会自动适应（至少保留一页）。");
                tip.setTextSize(11.5f);
                tip.setTextColor(Theme.c(act, R.attr.c_tx3));
                tip.setPadding(dp(act, 6), dp(act, 4), dp(act, 6), dp(act, 10));
                box.addView(tip);

                final List<String> vis = PageStore.visibleOrder(act);
                final String home = PageStore.home(act);

                for (int i = 0; i < order.size(); i++) {
                    final int idx = i;
                    final String key = order.get(i);
                    boolean hidden = !vis.contains(key);
                    boolean isHome = key.equals(home);

                    LinearLayout row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(act, 2), 0, dp(act, 4), 0);
                    /* 固定行高：拖拽要靠"第几行 × 行高"算目标槽位，
                       wrap_content 会让每行高度不一致，换位判定就飘了 */
                    row.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, rowH));

                    TextView handle = new TextView(act);
                    handle.setText("\u2261");
                    handle.setTextSize(17);
                    handle.setTextColor(Theme.c(act, R.attr.c_tx3));
                    handle.setPadding(0, 0, dp(act, 10), 0);
                    row.addView(handle);

                    LinearLayout col = new LinearLayout(act);
                    col.setOrientation(LinearLayout.VERTICAL);
                    col.setLayoutParams(new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    TextView nm = new TextView(act);
                    nm.setText(PageStore.nameOf(key)
                            + (isHome ? "   ★主页" : "")
                            + (hidden ? "   （已隐藏）" : ""));
                    nm.setTextSize(14);
                    nm.setTextColor(hidden ? Theme.c(act, R.attr.c_tx3) : Theme.c(act, R.attr.c_tx));
                    nm.setTypeface(null, isHome ? Typeface.BOLD : Typeface.NORMAL);
                    col.addView(nm);

                    TextView sub = new TextView(act);
                    sub.setText(isHome ? "打开应用即进入此页"
                            : (hidden ? "不在导航条显示" : "在导航条第 " + (vis.indexOf(key) + 1) + " 位"));
                    sub.setTextSize(11.5f);
                    sub.setTextColor(Theme.c(act, R.attr.c_tx3));
                    col.addView(sub);
                    row.addView(col);

                    /* 长按整行拖动（原来的 ▲▼ 点一次只挪一格，四页也要点好几下） */
                    row.setOnLongClickListener(new View.OnLongClickListener() {
                        public boolean onLongClick(View v) {
                            if (v.getHeight() <= 0) return false;
                            baseIdx.clear();
                            for (int k = 0; k < order.size(); k++) baseIdx.put(order.get(k), k);
                            dragging[0] = v;
                            dragKey[0] = key;
                            startRawY[0] = 0;
                            v.setAlpha(0.92f);
                            v.setElevation(dp(act, 8));
                            v.setTranslationZ(dp(act, 8));
                            try { sc.requestDisallowInterceptTouchEvent(true); } catch (Throwable ig) { }
                            return true;
                        }
                    });
                    row.setOnTouchListener(new View.OnTouchListener() {
                        public boolean onTouch(View v, MotionEvent e) {
                            if (dragging[0] != v) return false;
                            int action = e.getActionMasked();
                            if (action == MotionEvent.ACTION_MOVE) {
                                if (startRawY[0] == 0) startRawY[0] = e.getRawY();
                                float dy = e.getRawY() - startRawY[0];
                                v.setTranslationY(dy);
                                Integer bi = baseIdx.get(dragKey[0]);
                                int orig = bi == null ? order.indexOf(dragKey[0]) : bi.intValue();
                                int target = Math.round(orig + dy / (float) rowH);
                                target = Math.max(0, Math.min(order.size() - 1, target));
                                int cur = order.indexOf(dragKey[0]);
                                boolean changed = (target != cur);
                                if (changed) {
                                    order.remove(dragKey[0]);
                                    order.add(target, dragKey[0]);
                                    /* 只有目标槽位变了才重排动画，否则每帧重设会一直打断 */
                                    for (int k = 0; k < order.size(); k++) {
                                        String kk = order.get(k);
                                        View r = rowByKey.get(kk);
                                        if (r == null || r == v) continue;
                                        Integer b2 = baseIdx.get(kk);
                                        int bo = b2 == null ? k : b2.intValue();
                                        r.animate().translationY((k - bo) * (float) rowH)
                                                .setDuration(150).start();
                                    }
                                }
                            } else if (action == MotionEvent.ACTION_UP
                                    || action == MotionEvent.ACTION_CANCEL) {
                                dragging[0] = null;
                                try { sc.requestDisallowInterceptTouchEvent(false); } catch (Throwable ig) { }
                                v.setAlpha(1f);
                                v.setElevation(0);
                                v.setTranslationZ(0);
                                /* 先吸附到目标槽位（140ms），到位后归零 + 重排 */
                                final int curIdx = order.indexOf(dragKey[0]);
                                Integer b0 = baseIdx.get(dragKey[0]);
                                final int bo0 = b0 == null ? curIdx : b0.intValue();
                                v.animate().translationY((curIdx - bo0) * (float) rowH)
                                        .setDuration(140)
                                        .withEndAction(new Runnable() {
                                            public void run() {
                                                for (int k = 0; k < order.size(); k++) {
                                                    View r = rowByKey.get(order.get(k));
                                                    if (r == null) continue;
                                                    r.animate().cancel();
                                                    r.setTranslationY(0);
                                                    r.setAlpha(1f);
                                                    r.setElevation(0);
                                                    r.setTranslationZ(0);
                                                }
                                                if (box.getChildCount() > 1) {
                                                    box.removeViews(1, box.getChildCount() - 1);
                                                }
                                                /* 重排时要把每行后的 1px 分隔线一起补回来，
                                                   否则拖一次整个列表就变成没有分隔线的秃块 */
                                                for (int k = 0; k < order.size(); k++) {
                                                    View r = rowByKey.get(order.get(k));
                                                    if (r != null) box.addView(r);
                                                    View ln = new View(act);
                                                    ln.setLayoutParams(new LinearLayout.LayoutParams(
                                                            ViewGroup.LayoutParams.MATCH_PARENT, 1));
                                                    ln.setBackgroundColor(
                                                            Theme.ca(act, R.attr.c_div_line, 40));
                                                    box.addView(ln);
                                                }
                                            }
                                        })
                                        .start();
                                PageStore.saveOrder(act, order);
                                if (onChanged != null) onChanged.run();
                                /* 不再 self.run() 整表重建：
                                   重建会把 ScrollView 的滚动位置打回顶部，
                                   用户看到的就是"一松手列表先弹回最上面"。
                                   行的副标题（第 N 位）下次打开时自然是对的。 */
                            }
                            return true;
                        }
                    });
                    rowByKey.put(key, row);

                    if (!isHome) {
                        row.addView(btn(act, "设为主页", Theme.c(act, R.attr.c_accent), new View.OnClickListener() {
                            public void onClick(View v) {
                                PageStore.setHome(act, key);
                                order.remove(key);
                                order.add(0, key);
                                if (onChanged != null) onChanged.run();
                                self[0].run();
                            }
                        }));
                    }

                    if (PageStore.hideable(key)) {
                        final boolean h = hidden;
                        row.addView(btn(act, h ? "显示" : "隐藏",
                                h ? 0xFF22A06B : 0xFFC93B3B, new View.OnClickListener() {
                            public void onClick(View v) {
                                if (!h && PageStore.visibleOrder(act).size() <= 1) {
                                    Toast.makeText(act, "至少要保留一个页面",
                                            Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                PageStore.setHidden(act, key, !h);
                                if (onChanged != null) onChanged.run();
                                self[0].run();
                            }
                        }));
                    } else {
                        /* 设置页：不给隐藏按钮，改成一个说明性的静态标签，
                           避免用户以为是"坏了"（它其实是刻意锁定的） */
                        TextView lock = new TextView(act);
                        lock.setText("固定显示");
                        lock.setTextSize(11.5f);
                        lock.setTextColor(Theme.c(act, R.attr.c_tx3));
                        lock.setPadding(dp(act, 10), dp(act, 7), dp(act, 10), dp(act, 7));
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                        lp.leftMargin = dp(act, 5);
                        lock.setLayoutParams(lp);
                        row.addView(lock);
                    }

                    box.addView(row);

                    View line = new View(act);
                    line.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 1));
                    line.setBackgroundColor(Theme.ca(act, R.attr.c_div_line, 40));
                    box.addView(line);
                }
                /* 排完序整体淡入一次 —— 不然点了 ▲▼ 界面直接跳到新顺序，
                   眼睛跟不上，看不出"哪两行换了位置" */
                Anim.fade(box);
            }
        };
        self[0].run();

        Theme.skin(act, sc);   // 弹窗是独立窗口，Activity 那棵树遍历不到
        new AlertDialog.Builder(act)
                .setTitle("页面管理")
                .setView(sc)
                .setPositiveButton("完成", null)
                .show();
    }

    private static TextView arrow(Activity a, String sym, boolean enabled, View.OnClickListener l) {
        TextView t = new TextView(a);
        t.setText(sym);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setWidth(dp(a, 42));
        t.setHeight(dp(a, 38));
        t.setTextColor(enabled ? Theme.c(a, R.attr.c_accent) : Theme.c(a, R.attr.c_tx3));
        t.setBackgroundResource(Theme.bgRes(t.getContext(), R.attr.bg_pill));
        t.setAlpha(enabled ? 1f : 0.4f);
        t.setOnClickListener(enabled ? l : null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(a, 5);
        t.setLayoutParams(lp);
        t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private static TextView btn(Activity a, String text, int color, View.OnClickListener l) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(11.5f);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(color);
        t.setPadding(dp(a, 10), dp(a, 7), dp(a, 10), dp(a, 7));
        t.setBackgroundResource(Theme.bgRes(t.getContext(), R.attr.bg_pill));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(a, 5);
        t.setLayoutParams(lp);
        return t;
    }

    private static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }
}
