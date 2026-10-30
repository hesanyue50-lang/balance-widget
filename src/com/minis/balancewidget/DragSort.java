package com.minis.balancewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 拖拽排序列表（长按整行拖动，左侧 ≡ 是视觉手柄）。
 *
 * 交互照搬 LinkMesh 的 NavManager —— 那套是踩过坑之后定下来的，三个关键点：
 *
 * 1. **不要 bringToFront**。LinearLayout 会把该行移到 children 末尾，
 *    表现为"一按住就跳到最后一行"。用 elevation 让它在 Z 轴上浮起即可。
 * 2. **滞回 0.55 行**才换位。小于阈值手指微抖就会在边界来回抽搐。
 * 3. 换位时**其他行做 translationY 动画**到新槽位，不重建 View。
 *
 * 另外这里比 LinkMesh 多一层：列表可能比屏幕长（19 个平台），所以在拖动开始时
 * 让外层 ScrollView 别抢事件（requestDisallowInterceptTouchEvent）。
 * 代价是拖到可视区边缘不会自动滚 —— 条目多时得先滚到目标位置再拖。
 */
public final class DragSort {

    private DragSort() { }

    /**
     * @param ids   条目 key（保存顺序用）
     * @param names 显示名
     * @param onSaved 顺序变了之后回调（保存和刷新由调用方做）
     */
    public static void show(final Activity a, final String orderKey, String title,
                            List<String> ids, List<String> names,
                            final Runnable onSaved) {
        if (a == null || ids == null || ids.size() == 0) return;

        final int rowH = dp(a, 56);

        final List<String> order = new ArrayList<String>(ids);
        final Map<String, String> nameOf = new HashMap<String, String>();
        for (int i = 0; i < ids.size() && i < names.size(); i++) {
            nameOf.put(ids.get(i), names.get(i));
        }

        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 6), dp(a, 4), dp(a, 6), dp(a, 4));

        final ScrollView sc = new ScrollView(a);
        sc.addView(box);
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.72f);
        sc.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxH));

        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(title + " · 长按拖动")
                .setView(sc)
                .setPositiveButton("完成", null)
                .create();

        final Map<String, View> rowByKey = new HashMap<String, View>();
        final View[] dragging = new View[1];
        final String[] dragKey = new String[1];
        final float[] startRawY = new float[1];
        /* 拖动**开始那一刻**每行的索引快照。
           计算位移必须以它为基准，不能读 getTop() —— ScrollView 一滚就变，
           累加下来两行会画到同一位置（用户看到的"文字重叠"）。 */
        final Map<String, Integer> baseIdx = new HashMap<String, Integer>();

        final Runnable rebuild = new Runnable() {
            public void run() {
                box.removeAllViews();
                rowByKey.clear();
                for (int i = 0; i < order.size(); i++) {
                    String key = order.get(i);
                    View row = row(a, order, key, nameOf.get(key),
                            rowH, box, rowByKey, dragging, dragKey, startRawY, baseIdx,
                            sc, orderKey, onSaved);
                    rowByKey.put(key, row);
                    box.addView(row);
                }
            }
        };
        rebuild.run();

        Theme.skin(a, box);
        dlg.show();
    }

    private static View row(final Activity a, final List<String> order, final String key,
                            String label, final int rowH, final LinearLayout box,
                            final Map<String, View> rowByKey,
                            final View[] dragging, final String[] dragKey,
                            final float[] startRawY, final Map<String, Integer> baseIdx,
                            final ScrollView sc, final String orderKey,
                            final Runnable onSaved) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(a, 10), 0, dp(a, 10), 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowH));

        /* ≡ 手柄：纯视觉提示，长按行为挂在整行上（手指不一定正好按到手柄） */
        TextView handle = new TextView(a);
        handle.setText("\u2261");
        handle.setTextSize(17);
        handle.setTextColor(Theme.c(a, R.attr.c_tx3));
        handle.setPadding(0, 0, dp(a, 12), 0);
        row.addView(handle);

        TextView nm = new TextView(a);
        nm.setText(label == null ? key : label);
        nm.setTextSize(14.5f);
        nm.setTextColor(Theme.c(a, R.attr.c_tx));
        nm.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(nm);

        row.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                if (v.getHeight() <= 0) return false;
                baseIdx.clear();
                for (int k = 0; k < order.size(); k++) baseIdx.put(order.get(k), k);
                dragging[0] = v;
                dragKey[0] = key;
                startRawY[0] = 0;
                v.setAlpha(0.92f);
                /* 关键：不 bringToFront，只抬 Z 轴 */
                v.setElevation(dp(a, 8));
                v.setTranslationZ(dp(a, 8));
                /* 拖动期间别让 ScrollView 把事件抢走 */
                try { sc.requestDisallowInterceptTouchEvent(true); } catch (Throwable ignored) { }
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
                    /* 目标位置 = 起始索引 + 移动了几行。
                       用起始索引而不是"当前索引"，翻回来才能回到原位。 */
                    Integer bi = baseIdx.get(dragKey[0]);
                    int orig = bi == null ? order.indexOf(dragKey[0]) : bi.intValue();
                    int target = Math.round(orig + dy / (float) rowH);
                    target = Math.max(0, Math.min(order.size() - 1, target));
                    int cur = order.indexOf(dragKey[0]);
                    boolean changed = (target != cur);
                    if (changed) {
                        order.remove(dragKey[0]);
                        order.add(target, dragKey[0]);
                    }
                    /* 只在「目标槽位真的变了」时给其他行做动画。
                       每次 MOVE 都重设会不停打断上一个动画（那就成了抽搐）；
                       而等目标变了才动，150ms 的让位过渡刚好跟得上手指。 */
                    if (changed) {
                        for (int i = 0; i < order.size(); i++) {
                            String k = order.get(i);
                            View r = rowByKey.get(k);
                            if (r == null || r == v) continue;
                            Integer b2 = baseIdx.get(k);
                            int bo = b2 == null ? i : b2.intValue();
                            r.animate().translationY((i - bo) * (float) rowH)
                                    .setDuration(150).start();
                        }
                    }
                } else if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    /* ★ 松手时**所有行**都要归零，不能只复位被拖的那一行。
                       只复位它的话，其他行还停在"让位后"的 translationY 上，
                       而布局顺序仍是旧的 —— 结果就是行之间空一大块、甚至两行叠在一起。
                       归零后按新 order 重排视图（复用已有 View，不新建，所以不闪）。 */
                    dragging[0] = null;
                    try { sc.requestDisallowInterceptTouchEvent(false); } catch (Throwable ignored) { }
                    v.setAlpha(1f);
                    v.setElevation(0);
                    v.setTranslationZ(0);
                    /* 被拖的那行先**吸附**到它该在的槽位（140ms），
                       到位后再把所有行归零并按新顺序重排 —— 重排是瞬时的，
                       但此刻视觉上已经对齐，看不出跳变。 */
                    final int curIdx = order.indexOf(dragKey[0]);
                    Integer b0 = baseIdx.get(dragKey[0]);
                    final int bo0 = b0 == null ? curIdx : b0.intValue();
                    v.animate().translationY((curIdx - bo0) * (float) rowH)
                            .setDuration(140)
                            .withEndAction(new Runnable() {
                                public void run() {
                                    for (int i = 0; i < order.size(); i++) {
                                        View r = rowByKey.get(order.get(i));
                                        if (r == null) continue;
                                        r.animate().cancel();
                                        r.setTranslationY(0);
                                        r.setAlpha(1f);
                                        r.setElevation(0);
                                        r.setTranslationZ(0);
                                    }
                                    box.removeAllViews();
                                    for (int i = 0; i < order.size(); i++) {
                                        View r = rowByKey.get(order.get(i));
                                        if (r != null) box.addView(r);
                                    }
                                }
                            })
                            .start();
                    /* 顺序已经变了：落盘 + 通知调用方刷新 */
                    try {
                        if (orderKey != null) OrderStore.save(a, orderKey, order);
                    } catch (Throwable ignored) { }
                    if (onSaved != null) onSaved.run();
                }
                return true;
            }
        });
        return row;
    }

    private static int dp(Activity a, float v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
