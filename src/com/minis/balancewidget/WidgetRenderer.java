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

    /** 兜底布局（Aurora 亮）。实际用哪个由 {@link #layoutFor} 按主题决定。 */
    private static final int LAYOUT = R.layout.widget_style0;

    /**
     * 按当前主题选小组件布局。
     *
     * 每套主题一份独立 layout，而不是"一份布局换资源" —— 后者改不了字体和间距，
     * 效果就是所有主题长一个样、只有颜色不同（试过，确实差）。
     * 七份的成本主要在维护，所以它们都是由 widget_balance2 生成的，
     * **视图 id 完全一致**，渲染代码一行都不用分叉。
     */
    /**
     * 这个实例属于哪个固定风格。
     *
     * -1 = 主 Provider（跟随应用主题）；0~6 = 七个固定风格子类。
     * 用 Provider 的类名判断，不反射实例化 —— 拿得到、开销小、不会因为
     * 某个子类构造失败把整个刷新搞崩。
     */
    /** 七个固定风格的 Provider，**顺序必须和 fixedStyle() 的返回值一致** */
    private static final Class<?>[] STYLE_PROVIDERS = {
            WidgetAuroraLight.class, WidgetAuroraDark.class, WidgetEditorial.class,
            WidgetSwiss.class, WidgetDevtool.class, WidgetBrutal.class, WidgetClay.class,
    };

    private static int styleOf(AppWidgetManager mgr, int id) {
        try {
            android.appwidget.AppWidgetProviderInfo info = mgr.getAppWidgetInfo(id);
            if (info == null || info.provider == null) return -1;
            String cn = info.provider.getClassName();
            for (int i = 0; i < STYLE_PROVIDERS.length; i++) {
                if (STYLE_PROVIDERS[i].getName().equals(cn)) return i;
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** @param style 固定风格 0~6；-1 = 跟随应用主题 */
    /* 固定风格的七套资源：背景 + 三档文字色 + 强调色。
       style >= 0 时一律从这里取，不再跟壁纸/主题走 —— 否则用户在桌面挑了
       「Brutalism 撞色」，运行时又被 Theme.wTx() 覆盖成灰黑字，等于白选。 */
    private static final int[] STYLE_BG = {
            R.drawable.wg_t0, R.drawable.wg_t1, R.drawable.wg_t2, R.drawable.wg_t3,
            R.drawable.wg_t4, R.drawable.wg_t5, R.drawable.wg_t6,
    };
    private static final int[] STYLE_TX = {
            R.color.ws0_tx, R.color.ws1_tx, R.color.ws2_tx, R.color.ws3_tx,
            R.color.ws4_tx, R.color.ws5_tx, R.color.ws6_tx,
    };
    private static final int[] STYLE_TX2 = {
            R.color.ws0_tx2, R.color.ws1_tx2, R.color.ws2_tx2, R.color.ws3_tx2,
            R.color.ws4_tx2, R.color.ws5_tx2, R.color.ws6_tx2,
    };
    private static final int[] STYLE_TX3 = {
            R.color.ws0_tx3, R.color.ws1_tx3, R.color.ws2_tx3, R.color.ws3_tx3,
            R.color.ws4_tx3, R.color.ws5_tx3, R.color.ws6_tx3,
    };

    private static int layoutFor(Context ctx, int style) {
        int i = style >= 0 ? style : Theme.index(ctx);
        switch (i) {
            case 1:  return R.layout.widget_style1;   // Aurora 暗
            case 2:  return R.layout.widget_style2;   // Editorial 衬线
            case 3:  return R.layout.widget_style3;   // Minimal 瑞士
            case 4:  return R.layout.widget_style4;   // Devtool 等宽
            case 5:  return R.layout.widget_style5;   // Brutalism
            case 6:  return R.layout.widget_style6;   // Soft Clay
            default: return R.layout.widget_style0;   // Aurora 亮
        }
    }

    /**
     * 计算每个条目落在哪个格子，返回**总共需要多少格子**。
     *
     * 为什么单独抽出来：翻页要用到页数，而页数 = ceil(槽位数 / 每页容量)。
     * 槽位数**不等于条目数** —— 制式切换时要补到行首（分档线才完整），
     * 补出来的空位也算占位。
     *
     * ⚠️ 这个方法必须被渲染器和 Provider **共用**。曾经 Provider 自己用
     * `items.size()` 算页数，结果和渲染器显示的页数不一致：
     * 实测 items=6 但槽位=9，渲染器显示 1/2、Provider 算出 1/1，
     * 于是点「下一页」被夹回第 0 页 —— 表现就是"点了只刷新、不翻页"。
     *
     * @param slotOf     输出参数，长度应 >= items.size()；可为 null（只要总数时）
     * @param groupStart 输出参数，记录每个制式分档的起始槽位；可为 null
     */
    public static int computeSlots(List<BalanceFetcher.Item> items, int cols,
                                   int[] slotOf, HashSet<Integer> groupStart) {
        if (items == null) return 0;
        if (cols <= 0) cols = 1;
        int slot = 0;
        String prevKind = null;
        for (int i = 0; i < items.size(); i++) {
            String k = items.get(i).kind;
            if (prevKind != null && !k.equals(prevKind)) {
                int rem = slot % cols;
                if (rem != 0) slot += (cols - rem);     // 新档从行首开始，分档线才完整
                if (groupStart != null) groupStart.add(Integer.valueOf(slot));
            }
            if (slotOf != null && i < slotOf.length) slotOf[i] = slot;
            slot++;
            prevKind = k;
        }
        return slot;
    }

    /** 每个平台一个颜色，按索引循环取 */
    private static final int[] PALETTE = {
        0xFF4D6BFE, 0xFF8B5CF6, 0xFF0EA5E9, 0xFF22C55E, 0xFFF97316,
        0xFF06B6D4, 0xFFEC4899, 0xFF10B981, 0xFFF59E0B, 0xFF6366F1,
        0xFFEF4444, 0xFF14B8A6, 0xFFA855F7, 0xFF84CC16
    };

    // ---------- 两个入口 ----------

    public static void renderLoading(Context ctx, AppWidgetManager mgr, int id) {
        final int style = styleOf(mgr, id);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), layoutFor(ctx, style));
        boolean darkL = WallpaperTint.useDark(ctx);
        v.setTextColor(R.id.w2_title, style >= 0 ? ctx.getColor(STYLE_TX2[style]) : Theme.wTx2(ctx));
        v.setTextColor(R.id.w2_total, style >= 0 ? ctx.getColor(STYLE_TX[style])  : Theme.wTx(ctx));

        /* 样式（背景 / 字号 / 字体 / 间距）已经写在各自主题的 layout 里，
           这里不再 setBackgroundResource / setTextViewTextSize ——
           两套机制同时存在迟早会不同步。 */
        v.setTextViewText(R.id.w2_title, "API 管理助手");
        v.setTextViewText(R.id.w2_total, "…");
        v.setTextViewText(R.id.w2_sub, "正在刷新");
        v.setTextViewText(R.id.w2_page, "");
        hideAll(v);
        bind(ctx, v, id, style);
        push(ctx, mgr, id, v);
    }

    public static void renderResult(Context ctx, AppWidgetManager mgr, int id,
                                    BalanceFetcher.Result r, int page) {
        int cols = 2;                       // 新布局固定两列（窄屏靠缩小字号自适应）
        int perPage = WidgetStyle.capacityFor(mgr, id);
        final int style = styleOf(mgr, id);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), layoutFor(ctx, style));
        bind(ctx, v, id, style);

        /* 从壁纸吸色挑一套预设（亮壁纸 → 浅玻璃+深字，暗壁纸 → 深玻璃+浅字）。
           所有文字色都由它决定，不再依赖布局里写死的颜色 —— 布局里的色值只是
           设计时的参考值，运行时一律覆盖掉。 */
        final boolean dark = WallpaperTint.useDark(ctx);
        /* 文字色跟**应用主题**走（wallpaper 只管背景要不要深色）。
           原来三档全按壁纸明暗取，用户把 App 换成 Editorial 纸感，
           桌面上还是冷黑字，两边像两个产品。 */
        final int cTx  = style >= 0 ? ctx.getColor(STYLE_TX[style])  : Theme.wTx(ctx);
        final int cTx2 = style >= 0 ? ctx.getColor(STYLE_TX2[style]) : Theme.wTx2(ctx);
        final int cTx3 = style >= 0 ? ctx.getColor(STYLE_TX3[style]) : Theme.wTx3(ctx);
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
        int slot = computeSlots(items, cols, slotOf, groupStart);

        int pages = Math.max(1, (slot + perPage - 1) / perPage);
        /* 调用方给的 page 可能是上一轮留下的（实例被缩小 / 平台变少），
           越界时下面 base = page*perPage 会超出所有槽位 → 格子全隐藏、
           组件一片空白，而且 pages==1 时翻页箭头也被藏掉，用户没法翻回来。 */
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        /* 顺序要紧：先全清，再按需点亮。
           RemoteViews 的 action 是顺序执行的，hideAll 里也会把 prev/next 置 GONE ——
           放在后面写就会把刚设好的 VISIBLE 覆盖掉，翻页箭头永远不出现。 */
        hideAll(v);
        v.setTextViewText(R.id.w2_page, pages > 1 ? (page + 1) + "/" + pages : "");
        v.setViewVisibility(R.id.w2_prev, pages > 1 ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.w2_next, pages > 1 ? View.VISIBLE : View.GONE);

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
                v.setTextColor(DOT[s], color);
            } catch (Throwable ignored) { }

            if (!it.ok) {
                v.setTextViewText(VAL[s], "查询失败");
                v.setTextColor(VAL[s], WallpaperTint.danger(dark));
                continue;
            }
            /* 小组件的格子窄，没有位置单放一个单位控件 ——
               直接把单位拼在数字后面（2458积分）。主界面才是大小字分离的排版。 */
            String shown = it.amount;
            if (it.unitOverride != null && it.unitOverride.length() > 0) {
                shown = shown + it.unitOverride;
            }
            v.setTextViewText(VAL[s], shown);
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
        /* 翻页箭头也必须一起藏：RemoteViews 是"reapply 到现有 View 树"，
           没被提到的控件会保留上次状态 —— 多页渲染后切到"正在刷新"或
           "还没填过 Key"状态时，会残留两个还能点的箭头。 */
        try {
            v.setViewVisibility(R.id.w2_prev, View.GONE);
            v.setViewVisibility(R.id.w2_next, View.GONE);
        } catch (Throwable ignored) { }
    }

    private static void bind(Context ctx, RemoteViews v, int widgetId, int style) {
        /* 背景：布局里已经写了，这里再设一次兜底 ——
           桌面会缓存 View 树，布局里新加的资源引用有时不生效。
           固定风格的组件用它自己那张，只有「跟随主题」那个才看壁纸明暗。 */
        try {
            /* 跟随主题的那个组件也用主题背景（Theme.widgetBgRes 就是 wg_t0~t6）。
               原来写死 WallpaperTint.bgRes（壁纸玻璃图），用户把 App 切成
               Brutal/Clay 后，桌面组件的底还是 Aurora 玻璃，字却换成了新主题色。 */
            v.setInt(R.id.w2_root, "setBackgroundResource",
                     style >= 0 ? STYLE_BG[style] : Theme.widgetBgRes(ctx));
        } catch (Throwable ignored) { }
        /* 组件本体与刷新按钮 → 走连击计数（单击同步 / 双击开应用 / 三击强制重拉）。
           注意 requestCode 必须区分开：同一个 code 会被系统当成同一个 PendingIntent
           互相覆盖，翻页按钮的 1/2 也是同理。 */
        v.setOnClickPendingIntent(R.id.w2_root,
                pi(ctx, BalanceWidgetProvider.ACTION_TAP, 10, widgetId));
        v.setOnClickPendingIntent(R.id.w2_refresh,
                pi(ctx, BalanceWidgetProvider.ACTION_TAP, 11, widgetId));
        v.setOnClickPendingIntent(R.id.w2_prev,
                pi(ctx, BalanceWidgetProvider.ACTION_PREV, 1, widgetId));
        v.setOnClickPendingIntent(R.id.w2_next,
                pi(ctx, BalanceWidgetProvider.ACTION_NEXT, 2, widgetId));
    }

    /**
     * @param code    动作编号（每个动作一个）
     * @param widgetId 实例 id —— **必须混进 requestCode**，否则多个小组件实例的
     *                 PendingIntent 会被系统当成同一个互相覆盖（点 A 触发 B 的动作）；
     *                 同时塞进 extra，让连击计数能按实例分开。
     */
    private static PendingIntent pi(Context ctx, String action, int code, int widgetId) {
        /* 定向单播给主 Provider，**不能**用 setPackage 广播：
           ACTION_TAP 会被 8 个 Provider 各收一次，一次单击被记成 8 下，
           单击直接判成三击。连击计数只该有一个地方做。 */
        Intent it = new Intent(ctx, BalanceWidgetProvider.class);
        it.setAction(action);
        it.putExtra("widgetId", widgetId);
        it.putExtra("tapCode", code);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        /* 不能写成 code*1000 + (widgetId % 1000)：两个 appWidgetId 相差 1000 的倍数时
           requestCode 会相撞，系统把它们的 PendingIntent 当成同一个
           （FLAG_UPDATE_CURRENT 互相覆盖 extras）。code 只有 1/2/10/11，用 ×100 就够。 */
        int req = widgetId * 100 + code;
        return PendingIntent.getBroadcast(ctx, req, it, flags);
    }

    private static void push(Context ctx, AppWidgetManager mgr, int id, RemoteViews v) {
        boolean a = false;
        try {
            mgr.updateAppWidget(id, v);
            a = true;
        } catch (Throwable ignored) { }
        /* ⚠️ 这里原来还有一发 updateAppWidget(ComponentName, v) 做"兜底"，
           但那个接口的语义是「把**这一份** RemoteViews 写给该 provider 的**所有实例**」。
           而这份内容绑定的是当前 id 的 perPage、以及 PendingIntent 里的 widgetId ——
           循环里每渲染一个实例就全量推一次，后一个把前一个盖掉，
           最终所有组件都显示"最后一个被渲染的实例"的内容和 widgetId：
           大小不同的两个组件会显示错误行数/页数，点击连击也串到别的实例上。
           等于把已经修好的"多个小组件串号"从另一条路又引了回来。 */
        BalanceFetcher.diag(ctx, "推送 id=" + id + (a ? " 成功" : " 失败"));
    }
}
