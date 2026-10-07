package com.minis.balancewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
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

        /* 上面的 Runnable 里用 run() 自调用是无效的（匿名类方法遮蔽），
           所以这里用一格数组做真正的自引用。 */
        final Runnable[] self = new Runnable[1];
        self[0] = new Runnable() {
            public void run() {
                box.removeAllViews();
                box.setAlpha(0f);      // 配合末尾的 Anim.fade，重建时才有"浮现"的过程

                TextView tip = new TextView(act);
                tip.setText("点「设为主页」= 打开应用直接进这一页，并自动排到最左。\n"
                        + "隐藏后导航条会自动适应（至少保留一页）。");
                tip.setTextSize(11.5f);
                tip.setTextColor(0xFF8A94A3);
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
                    row.setPadding(dp(act, 4), dp(act, 5), dp(act, 4), dp(act, 5));

                    LinearLayout col = new LinearLayout(act);
                    col.setOrientation(LinearLayout.VERTICAL);
                    col.setLayoutParams(new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    TextView nm = new TextView(act);
                    nm.setText(PageStore.nameOf(key)
                            + (isHome ? "   ★主页" : "")
                            + (hidden ? "   （已隐藏）" : ""));
                    nm.setTextSize(13.5f);
                    nm.setTextColor(hidden ? 0xFF8A94A3 : 0xFF39424F);
                    nm.setTypeface(null, isHome ? Typeface.BOLD : Typeface.NORMAL);
                    col.addView(nm);

                    TextView sub = new TextView(act);
                    sub.setText(isHome ? "打开应用即进入此页"
                            : (hidden ? "不在导航条显示" : "在导航条第 " + (vis.indexOf(key) + 1) + " 位"));
                    sub.setTextSize(11.5f);
                    sub.setTextColor(0xFF8A94A3);
                    col.addView(sub);
                    row.addView(col);

                    row.addView(arrow(act, "▲", i > 0, new View.OnClickListener() {
                        public void onClick(View v) {
                            String t = order.get(idx - 1);
                            order.set(idx - 1, order.get(idx));
                            order.set(idx, t);
                            PageStore.saveOrder(act, order);
                            if (onChanged != null) onChanged.run();
                            self[0].run();
                        }
                    }));
                    row.addView(arrow(act, "▼", i < order.size() - 1, new View.OnClickListener() {
                        public void onClick(View v) {
                            String t = order.get(idx + 1);
                            order.set(idx + 1, order.get(idx));
                            order.set(idx, t);
                            PageStore.saveOrder(act, order);
                            if (onChanged != null) onChanged.run();
                            self[0].run();
                        }
                    }));

                    if (!isHome) {
                        row.addView(btn(act, "设为主页", 0xFF3D6FD6, new View.OnClickListener() {
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
                        lock.setTextColor(0xFF8A94A3);
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
                    line.setBackgroundColor(0x148A94A3);
                    box.addView(line);
                }
                /* 排完序整体淡入一次 —— 不然点了 ▲▼ 界面直接跳到新顺序，
                   眼睛跟不上，看不出"哪两行换了位置" */
                Anim.fade(box);
            }
        };
        self[0].run();

        new AlertDialog.Builder(act)
                .setTitle("页面管理")
                .setView(sc)
                .setPositiveButton("完成", null)
                .show();
    }

    private static TextView arrow(Activity a, String sym, boolean enabled, View.OnClickListener l) {
        TextView t = new TextView(a);
        t.setText(sym);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setWidth(dp(a, 42));
        t.setHeight(dp(a, 38));
        t.setTextColor(enabled ? 0xFF3D6FD6 : 0xFFC6CDD6);
        t.setBackgroundResource(R.drawable.mini_btn_border);
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
        t.setBackgroundResource(R.drawable.mini_btn_border);
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
