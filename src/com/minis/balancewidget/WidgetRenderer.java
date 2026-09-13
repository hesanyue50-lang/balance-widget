package com.minis.balancewidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import java.util.HashSet;
import java.util.List;

/**
 * 小组件渲染（重写版）。
 *
 * 只用 RemoteViews 的**正规 API**：
 *     setTextViewText / setTextColor / setViewVisibility / setOnClickPendingIntent
 *     setInt(..., "setBackgroundColor", ...)   —— 圆点着色，标准用法
 *
 * 刻意**不用**这些东西（旧版全用了，怀疑就是它们导致桌面拒绝渲染 —— 表现为
 * "RemoteViews 对象收到了、界面一动不动"）：
 *     ✗ setViewPadding         较新 API，跨进程解析失败会丢整份
 *     ✗ setColorFilter         要求目标是 ImageView，条件不满足就整份丢弃
 *     ✗ setBackgroundResource  皮肤靠它切换，现在背景写死在布局里
 *
 * 代价是外观固定一套（浅色圆角卡片），换来的是稳定 —— 先能用，再谈好看。
 */
public final class WidgetRenderer {

    private WidgetRenderer() { }

    // ---------- 视图 id ----------

    private static final int[] SLOT = {
        R.id.w2_slot1, R.id.w2_slot2, R.id.w2_slot3, R.id.w2_slot4,
        R.id.w2_slot5, R.id.w2_slot6, R.id.w2_slot7, R.id.w2_slot8
    };
    private static final int[] DOT = {
        R.id.w2_dot1, R.id.w2_dot2, R.id.w2_dot3, R.id.w2_dot4,
        R.id.w2_dot5, R.id.w2_dot6, R.id.w2_dot7, R.id.w2_dot8
    };
    private static final int[] NAME = {
        R.id.w2_name1, R.id.w2_name2, R.id.w2_name3, R.id.w2_name4,
        R.id.w2_name5, R.id.w2_name6, R.id.w2_name7, R.id.w2_name8
    };
    private static final int[] VAL = {
        R.id.w2_val1, R.id.w2_val2, R.id.w2_val3, R.id.w2_val4,
        R.id.w2_val5, R.id.w2_val6, R.id.w2_val7, R.id.w2_val8
    };
    private static final int[] ROW = {
        R.id.w2_row1, R.id.w2_row2, R.id.w2_row3, R.id.w2_row4
    };
    private static final int[] DIV = {
        R.id.w2_div1, R.id.w2_div2, R.id.w2_div3
    };

    private static final int LAYOUT = R.layout.widget_balance2;

    /** 每个平台一个颜色，按索引循环取 */
    private static final int[] PALETTE = {
        0xFF4D6BFE, 0xFF8B5CF6, 0xFF0EA5E9, 0xFF22C55E, 0xFFF97316,
        0xFF06B6D4, 0xFFEC4899, 0xFF10B981, 0xFFF59E0B, 0xFF6366F1,
        0xFFEF4444, 0xFF14B8A6, 0xFFA855F7, 0xFF84CC16
    };

    // ---------- 两个入口 ----------

    public static void renderLoading(Context ctx, AppWidgetManager mgr, int id) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), LAYOUT);
        boolean darkL = WallpaperTint.useDark(ctx);
        v.setTextColor(R.id.w2_title, WallpaperTint.tx2(darkL));
        v.setTextColor(R.id.w2_total, WallpaperTint.tx(darkL));
        v.setTextViewText(R.id.w2_title, "API 管理助手");
        v.setTextViewText(R.id.w2_total, "…");
        v.setTextViewText(R.id.w2_sub, "正在刷新");
        v.setTextViewText(R.id.w2_page, "");
        hideAll(v);
        bind(ctx, v);
        push(ctx, mgr, id, v);
    }

    public static void renderResult(Context ctx, AppWidgetManager mgr, int id,
                                    BalanceFetcher.Result r, int page) {
        int cols = 2;                       // 新布局固定两列（窄屏靠缩小字号自适应）
        int perPage = WidgetStyle.capacityFor(mgr, id);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), LAYOUT);
        bind(ctx, v);

        /* 从壁纸吸色挑一套预设（亮壁纸 → 浅玻璃+深字，暗壁纸 → 深玻璃+浅字）。
           所有文字色都由它决定，不再依赖布局里写死的颜色 —— 布局里的色值只是
           设计时的参考值，运行时一律覆盖掉。 */
        final boolean dark = WallpaperTint.useDark(ctx);
        final int cTx  = WallpaperTint.tx(dark);
        final int cTx2 = WallpaperTint.tx2(dark);
        final int cTx3 = WallpaperTint.tx3(dark);
        v.setTextColor(R.id.w2_title, cTx2);
        v.setTextColor(R.id.w2_total, cTx);
        v.setTextColor(R.id.w2_sub, cTx3);
        v.setTextColor(R.id.w2_page, cTx3);
        for (int i = 0; i < NAME.length; i++) v.setTextColor(NAME[i], cTx2);

        if (r.configured == 0) {
            v.setTextViewText(R.id.w2_title, "API 管理助手");
            v.setTextViewText(R.id.w2_total, "—");
            v.setTextViewText(R.id.w2_sub, "还没填过 Key，点开应用加一个");
            v.setTextViewText(R.id.w2_page, "");
            hideAll(v);
            push(ctx, mgr, id, v);
            return;
        }

        v.setTextViewText(R.id.w2_title, "API 管理助手");
        v.setTextViewText(R.id.w2_total, "¥" + String.format("%.2f", r.totalCny));

        StringBuilder sub = new StringBuilder();
        sub.append("1USD=").append(String.format("%.2f", r.rate));
        if (r.failed > 0) sub.append("  ·  ").append(r.failed).append(" 项失败");
        String t = WidgetCache.lastAt(ctx);
        if (t.length() > 0) sub.append("  ·  ").append(t);
        v.setTextViewText(R.id.w2_sub, sub.toString());

        // ---- 排版：按制式分档，算出每个 item 落在哪个格子 ----
        List<BalanceFetcher.Item> items = BalanceFetcher.groupByKind(r.items);
        int n = items.size();
        int[] slotOf = new int[n];
        HashSet<Integer> groupStart = new HashSet<Integer>();
        int slot = 0;
        String prevKind = null;
        for (int i = 0; i < n; i++) {
            String k = items.get(i).kind;
            if (prevKind != null && !k.equals(prevKind)) {
                int rem = slot % cols;
                if (rem != 0) slot += (cols - rem);     // 新档从行首开始，分档线才完整
                groupStart.add(Integer.valueOf(slot));
            }
            slotOf[i] = slot;
            slot++;
            prevKind = k;
        }

        int pages = Math.max(1, (slot + perPage - 1) / perPage);
        v.setTextViewText(R.id.w2_page, pages > 1 ? (page + 1) + "/" + pages : "");
        v.setViewVisibility(R.id.w2_prev, pages > 1 ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.w2_next, pages > 1 ? View.VISIBLE : View.GONE);

        hideAll(v);

        // ---- 填格子 ----
        int base = page * perPage;
        int maxSlot = -1;
        for (int i = 0; i < n; i++) {
            int s = slotOf[i] - base;
            if (s < 0 || s >= perPage || s >= SLOT.length) continue;
            if (s > maxSlot) maxSlot = s;
            BalanceFetcher.Item it = items.get(i);

            v.setViewVisibility(SLOT[s], View.VISIBLE);
            v.setTextViewText(NAME[s], it.label);

            // 圆点用该平台的颜色（没查到就用调色板兜底）
            int color = BalanceFetcher.colorOf(it.platform);
            if (color == 0xFF94A3B8) color = PALETTE[i % PALETTE.length];
            try {
                v.setInt(DOT[s], "setBackgroundColor", color);
            } catch (Throwable ignored) { }

            if (!it.ok) {
                v.setTextViewText(VAL[s], "查询失败");
                v.setTextColor(VAL[s], WallpaperTint.danger(dark));
                continue;
            }
            v.setTextViewText(VAL[s], it.amount);
            v.setTextColor(VAL[s], it.low ? WallpaperTint.danger(dark) : cTx);
        }

        // ---- 行容器：只显示有内容的那几行 ----
        int rows = (maxSlot < 0) ? 0 : (maxSlot / cols) + 1;
        for (int i = 0; i < ROW.length; i++) {
            v.setViewVisibility(ROW[i], i < rows ? View.VISIBLE : View.GONE);
        }

        // ---- 分档线：某行行首是新档第一格时，在这行上方画线 ----
        for (int d = 0; d < DIV.length; d++) {
            int firstSlotOfRow = (d + 1) * cols;
            boolean show = (d + 1) < ROW.length
                    && firstSlotOfRow <= maxSlot
                    && groupStart.contains(Integer.valueOf(base + firstSlotOfRow));
            v.setViewVisibility(DIV[d], show ? View.VISIBLE : View.GONE);
        }

        push(ctx, mgr, id, v);
    }

    // ---------- 内部 ----------

    private static void hideAll(RemoteViews v) {
        for (int i = 0; i < SLOT.length; i++) v.setViewVisibility(SLOT[i], View.GONE);
        for (int i = 0; i < ROW.length; i++) v.setViewVisibility(ROW[i], View.GONE);
        for (int i = 0; i < DIV.length; i++) v.setViewVisibility(DIV[i], View.GONE);
    }

    private static void bind(Context ctx, RemoteViews v) {
        /* 背景：布局里已经写了，这里再设一次兜底 ——
           桌面会缓存 View 树，布局里新加的资源引用有时不生效 */
        try {
            v.setInt(R.id.w2_root, "setBackgroundResource",
                     WallpaperTint.bgRes(WallpaperTint.useDark(ctx)));
        } catch (Throwable ignored) { }

        /* 自定义背景层：用户自己传的图，模糊后盖在纯色底之上。
           没开启自定义背景就保持隐藏，退回纯色半透明。 */
        try {
            android.graphics.Bitmap frost = BackgroundStore.build(ctx);
            if (frost != null) {
                v.setImageViewBitmap(R.id.w2_frost, frost);
                v.setViewVisibility(R.id.w2_frost, View.VISIBLE);
            } else {
                v.setViewVisibility(R.id.w2_frost, View.GONE);
            }
        } catch (Throwable ignored) { }
        v.setOnClickPendingIntent(R.id.w2_root,
                pi(ctx, BalanceWidgetProvider.ACTION_REFRESH, 0));
        v.setOnClickPendingIntent(R.id.w2_refresh,
                pi(ctx, BalanceWidgetProvider.ACTION_REFRESH, 0));
        v.setOnClickPendingIntent(R.id.w2_prev,
                pi(ctx, BalanceWidgetProvider.ACTION_PREV, 1));
        v.setOnClickPendingIntent(R.id.w2_next,
                pi(ctx, BalanceWidgetProvider.ACTION_NEXT, 2));
    }

    private static PendingIntent pi(Context ctx, String action, int code) {
        Intent it = new Intent(ctx, BalanceWidgetProvider.class);
        it.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, code, it, flags);
    }

    private static void push(Context ctx, AppWidgetManager mgr, int id, RemoteViews v) {
        boolean a = false, b = false;
        try {
            mgr.updateAppWidget(id, v);
            a = true;
        } catch (Throwable ignored) { }
        try {
            /* 再补一发按组件名的全量更新 —— 实测桌面有时会忽略按 id 的单发 */
            mgr.updateAppWidget(new android.content.ComponentName(ctx,
                    BalanceWidgetProvider.class), v);
            b = true;
        } catch (Throwable ignored) { }
        BalanceFetcher.diag(ctx, "推送 id=" + id + " 单发=" + a + " 全量=" + b);
    }
}
