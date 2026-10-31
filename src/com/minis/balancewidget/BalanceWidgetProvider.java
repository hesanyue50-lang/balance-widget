package com.minis.balancewidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

/**
 * 小组件入口：只管**生命周期与刷新编排**，具体活都交给下面三个类 ——
 *
 *   WidgetStyle    外观参数与尺寸计算（真机标定的那些数字）
 *   WidgetCache    结果缓存与 JSON 读写
 *   WidgetRenderer RemoteViews 的绘制
 *
 * 编排上要注意的三件事（都是踩过坑换来的）：
 *   1. **节流**：连续触发会瞬间开出几十个线程一起做 DNS，把系统解析器打爆，
 *      连本来好用的平台都会跟着挂。所以同一时刻只允许一轮抓取。
 *   2. **先画缓存再联网**：App 更新 / 桌面重建时只显示「刷新中」会让人干等好几秒，
 *      先把旧数据画上去，体感完全是两回事。
 *   3. **全军覆没不覆盖缓存**：后台弱网、Doze 掐断都是常事，
 *      一次失败把上次的好数据刷成一片「—」太亏。
 */
public class BalanceWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "com.minis.balancewidget.ACTION_REFRESH";

    /**
     * 全部小组件 Provider：本体（跟随主题）+ 七个固定风格。
     *
     * 凡是"对所有实例做点什么"的地方（刷新、重绘、空闲清理）都必须遍历这个数组 ——
     * 只认 BalanceWidgetProvider.class 的话，用户加的那七个固定风格组件会一直不刷新。
     */
    public static final Class<?>[] ALL_PROVIDERS = {
            BalanceWidgetProvider.class,
            WidgetAuroraLight.class, WidgetAuroraDark.class, WidgetEditorial.class,
            WidgetSwiss.class, WidgetDevtool.class, WidgetBrutal.class, WidgetClay.class,
    };

    /** 汇总所有 Provider 的实例 id（去重） */
    public static int[] allWidgetIds(Context ctx, AppWidgetManager mgr) {
        java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<Integer>();
        for (int i = 0; i < ALL_PROVIDERS.length; i++) {
            try {
                int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, ALL_PROVIDERS[i]));
                if (ids != null) for (int id : ids) set.add(Integer.valueOf(id));
            } catch (Throwable ignored) { }
        }
        int[] out = new int[set.size()];
        int i = 0;
        for (Integer v : set) out[i++] = v.intValue();
        return out;
    }
    /** 标记这次刷新来自后台闹钟（走精简模式，省电） */
    public static final String EXTRA_BG = "from_background_alarm";
    /** 不联网，直接用缓存把所有实例重画一遍（App 更新后救场用） */
    public static final String ACTION_FORCE_REDRAW = "com.minis.balancewidget.ACTION_FORCE_REDRAW";
    public static final String ACTION_PREV = "com.minis.balancewidget.ACTION_PREV";
    public static final String ACTION_NEXT = "com.minis.balancewidget.ACTION_NEXT";
    /**
     * 点小组件本体（非翻页按钮）走这个 action —— 由它累计连击次数：
     * 单击 = 用缓存同步（老行为），**双击 = 打开应用**，**三击 = 强制联网重拉**。
     */
    public static final String ACTION_TAP = "com.minis.balancewidget.ACTION_TAP";

    /* ---- 连击判定 ---- */
    /** 两次点击间隔超过这个时长就不算连击 */
    private static final long MULTI_TAP_GAP_MS = 500L;
    /* ⚠️ 连击状态**必须按 widgetId 分开**：用户桌面上可能有两个小组件，
       点 A 一下、点 B 一下会被全局计数当成"双击" → 莫名其妙打开应用。
       用 HashMap 按实例记，超过 8 个实例就清一次（防无限增长）。 */
    private static final java.util.HashMap<Integer, int[]> TAPS =
            new java.util.HashMap<Integer, int[]>();
    private static final java.util.HashMap<Integer, Long> TAP_AT =
            new java.util.HashMap<Integer, Long>();
    private static final Object TAP_LOCK = new Object();

    /** 后台抓取的超时预算（毫秒）。数组里的平台会并发抓，总耗时≈最慢的那个 */
    private static final int FETCH_TIMEOUT_MS = 8000;

    /** 刷新节流：同一时刻只允许一轮，两轮之间至少隔 2 秒 */
    private static final java.util.concurrent.atomic.AtomicBoolean FETCHING =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile long LAST_FETCH_AT = 0L;
    private static final long MIN_GAP_MS = 2000L;
    /** 下一次刷新要不要跳过节流（小组件三击置一次） */
    private static final java.util.concurrent.atomic.AtomicBoolean FORCE_ONCE =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // ---------- 生命周期 ----------

    /**
     * 这个 Provider 固定用哪套风格。
     *
     * -1 = 跟随应用主题（这是 {@code BalanceWidgetProvider} 本体的行为）；
     * 0~6 = 七个固定风格的子类各自的取值。
     *
     * 子类靠这个值把"风格"钉死，不再随用户换主题而变 —— 系统「添加小组件」
     * 列表里能看到七个独立条目，加哪个就是哪个样子。
     */
    public int fixedStyle() {
        return -1;
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        try {
            fetchAndRender(ctx, mgr, ids);
        } catch (Throwable t) {
            BalanceFetcher.diag(ctx, "onUpdate 异常: " + t);
            safeRedraw(ctx);
        }
    }

    @Override
    public void onReceive(final Context ctx, final Intent intent) {
        /* 兜住一切异常：小组件崩了桌面会退化成 App 默认图标 —— 那是最糟糕的表现，
           宁可少刷一次也不能崩。顺便把堆栈写进诊断日志。 */
        try {
            handle(ctx, intent);
        } catch (Throwable t) {
            BalanceFetcher.diag(ctx, "onReceive 异常: " + t);
            safeRedraw(ctx);
        }
    }

    private void handle(final Context ctx, final Intent intent) {
        String a = intent.getAction();
        if (a == null) {
            super.onReceive(ctx, intent);
            return;
        }

        final AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        final int[] ids = allWidgetIds(ctx, mgr);

        /* App 更新后救场：系统重建小组件时常常不调 onUpdate，桌面就一直挂着旧的
           RemoteViews（甚至空白），用户只能删掉重加。这里不联网，先把缓存画上去。 */
        if (ACTION_FORCE_REDRAW.equals(a)) {
            safeRedraw(ctx);
            /* 重画完立刻排一次联网取数，把数字换成最新的 */
            Intent nxt = new Intent(ACTION_REFRESH).setPackage(ctx.getPackageName());
            nxt.setAction(ACTION_REFRESH);
            ctx.sendBroadcast(nxt);
            return;
        }

        /* 点组件本体：按连击次数分派 —— 单击同步缓存 / 双击打开应用 / 三击强制重拉 */
        if (ACTION_TAP.equals(a)) {
            handleTap(ctx, intent == null ? -1 : intent.getIntExtra("widgetId", -1));
            return;
        }

        /* 注意：ACTION_REFRESH 即使当前没有小组件也必须执行 ——
           后台定时刷新（RefreshReceiver）正是靠它取数并做余额预警。 */
        if (ACTION_REFRESH.equals(a)) {
            /* RefreshReceiver（闹钟唤醒）会打上 EXTRA_BG 标记 */
            fetchAndRender(ctx, mgr, ids, intent.getBooleanExtra(EXTRA_BG, false));
            return;
        }

        // 翻页必须有小组件
        if (ids == null || ids.length == 0) {
            super.onReceive(ctx, intent);
            return;
        }

        if (ACTION_PREV.equals(a) || ACTION_NEXT.equals(a)) {
            BalanceFetcher.Result r = WidgetCache.read(ctx);
            if (!WidgetCache.usable(r)) {              // 无缓存才联网
                fetchAndRender(ctx, mgr, ids);
                return;
            }
            /* 页数必须和渲染器用**同一套算法**：渲染器按「合并后的平台槽位」算，
               而这里原来是按「原始条目数」算 —— 两者不等时，
               点「下一页」会被夹回第 0 页，表现为"点了只刷新、不翻页"。
               （实测：items=6 但槽位=9，渲染器显示 1/2，这里却算成 1/1。） */
            /* 取所有实例里**最小**的容量：page 是全局存的（WidgetCache.page），
               用 ids[0] 一个实例的尺寸算页数，遇到高度不同的另一个实例就会越界
               （页码超出 → 那个实例一片空白）。取最小值最保守，谁都不会越界。 */
            int perPage = Integer.MAX_VALUE;
            for (int i = 0; i < ids.length; i++) {
                perPage = Math.min(perPage, WidgetStyle.capacityFor(mgr, ids[i]));
            }
            if (perPage == Integer.MAX_VALUE || perPage < 1) perPage = 1;
            int cols = WidgetStyle.colsFor(mgr, ids[0]);
            java.util.List<BalanceFetcher.Item> grouped = BalanceFetcher.groupByKind(r.items);
            int slots = WidgetRenderer.computeSlots(grouped, cols, null, null);
            int pages = Math.max(1, (slots + perPage - 1) / perPage);
            int page = WidgetCache.page(ctx);
            page = ACTION_NEXT.equals(a) ? Math.min(pages - 1, page + 1)
                                         : Math.max(0, page - 1);
            WidgetCache.setPage(ctx, page);
            /* 记一条：排查「点了翻页却像刷新」时，靠它区分
               ① 点击没送到（日志里没有这行）② 送到了但页码没变。 */
            BalanceFetcher.diag(ctx, "小组件翻页 " + a + " → " + page + "/" + pages
                    + " (perPage=" + perPage + " items=" + r.items.size() + ")");
            for (int i = 0; i < ids.length; i++) {
                WidgetRenderer.renderResult(ctx, mgr, ids[i], r, page);
            }
            return;
        }

        super.onReceive(ctx, intent);
    }

    /**
     * 小组件本体的点击：按连击次数派活。
     *
     * | 连击 | 行为 |
     * |---|---|
     * | 单击 | 用缓存同步一遍（老行为，不联网） |
     * | 双击 | **打开应用** |
     * | 三击 | **强制联网重拉**（忽略节流与缓存，真的去请求各家接口） |
     *
     * 为什么要等一小会儿再执行：收到第 1 击时不知道后面还有没有第 2 击，
     * 只能等连击窗口过去再判定。窗口取 500ms —— 再长用户会觉得点了没反应。
     */
    private void handleTap(final Context ctx, final int widgetId) {
        final int n;
        synchronized (TAP_LOCK) {
            long now = System.currentTimeMillis();
            Long last = TAP_AT.get(widgetId);
            int[] cnt = TAPS.get(widgetId);
            if (cnt == null || last == null || now - last.longValue() > MULTI_TAP_GAP_MS) {
                cnt = new int[] { 0 };
                TAPS.put(widgetId, cnt);
            }
            cnt[0]++;
            TAP_AT.put(widgetId, Long.valueOf(now));
            n = cnt[0];
            /* 实例数防膨胀（卸载小组件后对应条目没人清） */
            if (TAPS.size() > 8) {
                TAPS.clear();
                TAP_AT.clear();
                TAPS.put(widgetId, new int[] { 1 });
                TAP_AT.put(widgetId, Long.valueOf(now));
            }
        }
        BalanceFetcher.diag(ctx, "小组件点击 第 " + n + " 下");

        /* 等连击窗口过去再动手；期间后续点击会继续累加 tapCount。
           用主线程 postDelayed 而不是 new Thread + sleep：这只是个延时，
           连点 5 下就是 5 个线程（每个 1MB 栈），完全没必要。 */
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                synchronized (TAP_LOCK) {
                    int[] cnt = TAPS.get(widgetId);
                    /* 窗口内又点了 → 说明是多击，交给那一次的处理去执行 */
                    if (cnt == null || cnt[0] != n) return;
                    cnt[0] = 0;
                }
                if (n >= 3) {
                    forceFetch(ctx);
                } else if (n == 2) {
                    openApp(ctx);
                } else {
                    /* 单击 = 老行为：用缓存重画一次 */
                    Intent it = new Intent(ACTION_REFRESH).setPackage(ctx.getPackageName());
                    it.setAction(ACTION_FORCE_REDRAW);
                    ctx.sendBroadcast(it);
                }
            }
        }, MULTI_TAP_GAP_MS + 60);
    }

    /** 强制刷新撞上并发时，退避重试（1.5s → 3s → 5s，最多 3 次） */
    private void retryForceLater(final Context ctx) {
        new Thread(new Runnable() {
            public void run() {
                /* 退避而不是固定 1.5 秒：并发那一轮本身可能要跑 8~18 秒，
                   固定间隔重试三次都在同一轮里，等于白试三次。 */
                final long[] wait = { 1500L, 3000L, 5000L };
                for (int i = 0; i < wait.length; i++) {
                    try { Thread.sleep(wait[i]); } catch (InterruptedException ig) { return; }
                    if (!FETCHING.get()) {
                        FORCE_ONCE.set(true);
                        Intent it = new Intent(ACTION_REFRESH).setPackage(ctx.getPackageName());
                        it.setAction(ACTION_REFRESH);
                        ctx.sendBroadcast(it);
                        return;
                    }
                }
                BalanceFetcher.diag(ctx, "强制刷新重试 3 次仍未抢到，放弃");
            }
        }).start();
    }

    /** 双击：打开应用主界面 */
    private void openApp(Context ctx) {
        try {
            Intent it = new Intent(ctx, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(it);
            BalanceFetcher.diag(ctx, "小组件双击 → 打开应用");
        } catch (Throwable t) {
            BalanceFetcher.diag(ctx, "小组件双击打开应用失败：" + t);
        }
    }

    /**
     * 三击：强制联网重拉。
     *
     * 和普通刷新的区别：普通刷新会走节流（2 秒内不重复）且可能复用缓存，
     * 这里要的是"用户明确要求重新拉一次"，所以两者都绕开。
     */
    private void forceFetch(final Context ctx) {
        BalanceFetcher.diag(ctx, "小组件三击 → 强制联网重拉");
        /* 只置"跳过节流"的标志；并发闸门不绕 —— 上一轮没跑完时硬开第二轮，
           请求会挤在一起反而都超时，交给 retryForceLater 排队重试。 */
        FORCE_ONCE.set(true);
        try {
            Intent it = new Intent(ACTION_REFRESH).setPackage(ctx.getPackageName());
            it.setAction(ACTION_REFRESH);
            ctx.sendBroadcast(it);
        } catch (Throwable t) {
            BalanceFetcher.diag(ctx, "小组件三击刷新失败：" + t);
        }
    }

    /** 用缓存重画一遍所有实例（崩溃兜底 / App 更新后救场共用） */
    private void safeRedraw(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = allWidgetIds(ctx, mgr);
            if (ids == null || ids.length == 0) return;
            BalanceFetcher.Result cached = WidgetCache.read(ctx);
            int pg = WidgetCache.page(ctx);
            for (int i = 0; i < ids.length; i++) {
                if (WidgetCache.usable(cached)) {
                    WidgetRenderer.renderResult(ctx, mgr, ids[i], cached, pg);
                } else {
                    WidgetRenderer.renderLoading(ctx, mgr, ids[i]);
                }
            }
            BalanceFetcher.diag(ctx, "重绘 " + ids.length + " 个实例（"
                    + (WidgetCache.usable(cached) ? "用缓存" : "无缓存") + "）");
        } catch (Throwable ignored) { }
    }

    // ---------- 刷新编排 ----------

    /** 后台联网拉取（goAsync 延长广播存活时间） */
    /** 非后台来源（用户点击、系统更新等） */
    private void fetchAndRender(final Context ctx, final AppWidgetManager mgr, final int[] ids) {
        fetchAndRender(ctx, mgr, ids, false);
    }

    /**
     * @param bg true = 这次刷新来自后台闹钟。走**精简模式**省电：
     *           跳过汇率查询、失败不做重试（Doze 下网络本就常不通，重试纯浪费电）。
     */
    private void fetchAndRender(final Context ctx, final AppWidgetManager mgr,
                                final int[] ids, final boolean bg) {
        final boolean hasWidget = ids != null && ids.length > 0;
        BalanceFetcher.diag(ctx, "实例 ids=" + java.util.Arrays.toString(ids)
                + " 数量=" + (ids == null ? -1 : ids.length));

        /* force = 用户明确要求重新拉一次（小组件三击）：跳过节流，
           但**不跳过并发闸门** —— 上一轮还没跑完时硬开第二轮，
           两家平台的请求会挤在一起，反而都超时。 */
        /* force 用静态标志传（本方法拿不到 intent） */
        boolean force = FORCE_ONCE.getAndSet(false);
        long now = System.currentTimeMillis();
        if (!force && now - LAST_FETCH_AT < MIN_GAP_MS) {
            BalanceFetcher.diag(ctx, "跳过刷新：距上次仅 " + (now - LAST_FETCH_AT) + "ms");
            return;
        }
        if (!FETCHING.compareAndSet(false, true)) {
            BalanceFetcher.diag(ctx, force ? "强制刷新：上一轮仍在进行中，稍后重试"
                    : "跳过刷新：上一轮仍在进行中");
            /* 强制刷新遇到并发时等一下再试，别直接放弃 —— 用户明确点了要重拉 */
            if (force) {
                retryForceLater(ctx);
                return;
            }
            return;
        }
        LAST_FETCH_AT = now;

        /* 先把缓存画出来，别让人对着「刷新中」干等 */
        if (hasWidget) {
            BalanceFetcher.Result cached = WidgetCache.read(ctx);
            int cachedPage = WidgetCache.page(ctx);
            boolean usable = WidgetCache.usable(cached);
            for (int i = 0; i < ids.length; i++) {
                if (usable) WidgetRenderer.renderResult(ctx, mgr, ids[i], cached, cachedPage);
                else        WidgetRenderer.renderLoading(ctx, mgr, ids[i]);
            }
        }

        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            public void run() {
                try {
                    long t0 = System.currentTimeMillis();
                    BalanceFetcher.diag(ctx, "=== 小组件刷新开始 ===");
                    /* 后台标记由 RefreshReceiver 经 onReceive 传进来；
                       后台走精简模式，并且不做失败重试 ——
                       Doze 下网络本来就常不通，重试既拿不到数据又白白耗电。 */
                    BalanceFetcher.Result r = BalanceFetcher.fetch(ctx, FETCH_TIMEOUT_MS, bg);
                    /* ★ 这里原来有「全失败 → sleep 2 秒 → 整轮重跑一次」。
                       问题是整轮重跑不是只重试失败项：单轮最坏 18 秒，两轮 40 秒+，
                       而 goAsync() 的 PendingResult 只有 10 秒窗口 —— 必然超时，
                       而且对同一批 URL 全量重复请求。缓存兜底已经在下面了，
                       弱网时让用户看到旧数据，比卡 40 秒后被系统掐掉好。 */
                    if (!bg && r.configured > 0 && r.failed == r.configured) {
                        BalanceFetcher.diag(ctx, "本轮全失败（" + r.failed + " 项），不重试，走缓存兜底");
                    }

                    /* 本轮全军覆没就别覆盖缓存了 —— 弱网、Doze 掐断都是常事 */
                    if (WidgetCache.okCount(r) == 0) {
                        BalanceFetcher.Result old = WidgetCache.read(ctx);
                        if (WidgetCache.usable(old)) {
                            BalanceFetcher.diag(ctx, "本轮 " + r.configured + " 个平台全失败，保留缓存");
                            Alert.check(ctx, r);
                            if (hasWidget) {
                                int pg = clampPage(ctx, mgr, old, ids[0]);
                                for (int i = 0; i < ids.length; i++) {
                                    WidgetRenderer.renderResult(ctx, mgr, ids[i], old, pg);
                                }
                            }
                            return;
                        }
                    }

                    Alert.check(ctx, r);           // 阈值判定 + 发通知（有防重复）

                    /* 每 6 小时记一次快照（统计页曲线的来源）。
                       逻辑已抽到 BalanceFetcher.sampleIfDue —— 打开 App 刷新时也会走一遍，
                       免得「不摆小组件就永远没曲线」。 */
                    BalanceFetcher.sampleIfDue(ctx, r);

                    int page = 0;
                    if (hasWidget) page = clampPage(ctx, mgr, r, ids[0]);
                    WidgetCache.save(ctx, r, page);

                    if (hasWidget) {
                        for (int i = 0; i < ids.length; i++) {
                            WidgetRenderer.renderResult(ctx, mgr, ids[i], r, page);
                        }
                    }
                    BalanceFetcher.diag(ctx, "刷新完成 hasWidget=" + hasWidget
                            + " 成功 " + WidgetCache.okCount(r) + "/" + r.items.size()
                            + " 用时 " + (System.currentTimeMillis() - t0) + "ms");
                } catch (Throwable t) {
                    BalanceFetcher.diag(ctx, "刷新异常: " + t);
                } finally {
                    FETCHING.set(false);
                    pr.finish();
                }
            }
        }).start();
    }

    /** 当前页码不能超出实际页数（条目变少时要收回来） */
    private static int clampPage(Context ctx, AppWidgetManager mgr,
                                 BalanceFetcher.Result r, int widgetId) {
        int perPage = WidgetStyle.capacityFor(mgr, widgetId);
        /* 必须和渲染器用同一套算法（groupByKind + computeSlots 补行后的槽位数）。
           以前这里用 r.items.size()，而槽位数 ≥ 条目数 —— 算出的页数偏小，
           刷新/后台闹钟一回就把页码夹到少一页以上，最后几页永远到不了。
           （注释里自己记着实测 items=6 但槽位=9。） */
        int cols = WidgetStyle.colsFor(mgr, widgetId);
        java.util.List<BalanceFetcher.Item> grouped = BalanceFetcher.groupByKind(r.items);
        int slots = WidgetRenderer.computeSlots(grouped, cols, null, null);
        int pages = Math.max(1, (slots + perPage - 1) / perPage);
        return Math.min(WidgetCache.page(ctx), pages - 1);
    }
}
