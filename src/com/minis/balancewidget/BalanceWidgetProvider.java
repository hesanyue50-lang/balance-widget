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
    /** 不联网，直接用缓存把所有实例重画一遍（App 更新后救场用） */
    public static final String ACTION_FORCE_REDRAW = "com.minis.balancewidget.ACTION_FORCE_REDRAW";
    public static final String ACTION_PREV = "com.minis.balancewidget.ACTION_PREV";
    public static final String ACTION_NEXT = "com.minis.balancewidget.ACTION_NEXT";

    /** 后台抓取的超时预算（毫秒）。数组里的平台会并发抓，总耗时≈最慢的那个 */
    private static final int FETCH_TIMEOUT_MS = 8000;

    /** 刷新节流：同一时刻只允许一轮，两轮之间至少隔 2 秒 */
    private static final java.util.concurrent.atomic.AtomicBoolean FETCHING =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile long LAST_FETCH_AT = 0L;
    private static final long MIN_GAP_MS = 2000L;

    // ---------- 生命周期 ----------

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
        final int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, BalanceWidgetProvider.class));

        /* App 更新后救场：系统重建小组件时常常不调 onUpdate，桌面就一直挂着旧的
           RemoteViews（甚至空白），用户只能删掉重加。这里不联网，先把缓存画上去。 */
        if (ACTION_FORCE_REDRAW.equals(a)) {
            safeRedraw(ctx);
            /* 重画完立刻排一次联网取数，把数字换成最新的 */
            Intent nxt = new Intent(ctx, BalanceWidgetProvider.class);
            nxt.setAction(ACTION_REFRESH);
            ctx.sendBroadcast(nxt);
            return;
        }

        /* 注意：ACTION_REFRESH 即使当前没有小组件也必须执行 ——
           后台定时刷新（RefreshReceiver）正是靠它取数并做余额预警。 */
        if (ACTION_REFRESH.equals(a)) {
            fetchAndRender(ctx, mgr, ids);
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
            int perPage = WidgetStyle.capacityFor(mgr, ids[0]);
            int pages = Math.max(1, (r.items.size() + perPage - 1) / perPage);
            int page = WidgetCache.page(ctx);
            page = ACTION_NEXT.equals(a) ? Math.min(pages - 1, page + 1)
                                         : Math.max(0, page - 1);
            WidgetCache.setPage(ctx, page);
            for (int i = 0; i < ids.length; i++) {
                WidgetRenderer.renderResult(ctx, mgr, ids[i], r, page);
            }
            return;
        }

        super.onReceive(ctx, intent);
    }

    /** 用缓存重画一遍所有实例（崩溃兜底 / App 更新后救场共用） */
    private void safeRedraw(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, BalanceWidgetProvider.class));
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
    private void fetchAndRender(final Context ctx, final AppWidgetManager mgr, final int[] ids) {
        final boolean hasWidget = ids != null && ids.length > 0;
        BalanceFetcher.diag(ctx, "实例 ids=" + java.util.Arrays.toString(ids)
                + " 数量=" + (ids == null ? -1 : ids.length));

        long now = System.currentTimeMillis();
        if (now - LAST_FETCH_AT < MIN_GAP_MS) {
            BalanceFetcher.diag(ctx, "跳过刷新：距上次仅 " + (now - LAST_FETCH_AT) + "ms");
            return;
        }
        if (!FETCHING.compareAndSet(false, true)) {
            BalanceFetcher.diag(ctx, "跳过刷新：上一轮仍在进行中");
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
                    BalanceFetcher.Result r = BalanceFetcher.fetch(ctx, FETCH_TIMEOUT_MS);

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

                    /* 每 6 小时记一次快照，顺带检测这段时间有没有充值。
                       记在本地 SQLite，统计页的曲线全靠它。 */
                    try {
                        SharedPreferences sps = ctx.getSharedPreferences(
                                BalanceFetcher.PREFS, Context.MODE_PRIVATE);
                        long lastSample = sps.getLong("last_sample_at", 0);
                        if (System.currentTimeMillis() - lastSample >= Ledger.SAMPLE_MS) {
                            sps.edit().putLong("last_sample_at",
                                    System.currentTimeMillis()).apply();
                            Ledger lg = Ledger.get(ctx);
                            for (int i = 0; i < r.items.size(); i++) {
                                BalanceFetcher.Item it = r.items.get(i);
                                if (it.ok && it.bal >= 0) {
                                    lg.record(ctx, it.id, it.bal, -1, "USD".equals(it.tag));
                                }
                            }
                            /* 顺手清理超期数据（默认留 90 天） */
                            int keep = sps.getInt("ledger_keep_days",
                                    Ledger.KEEP_DAYS_DEFAULT);
                            lg.prune(ctx, keep);
                        }
                    } catch (Throwable t) {
                        BalanceFetcher.diag(ctx, "快照记录失败: " + t);
                    }

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
        int pages = Math.max(1, (r.items.size() + perPage - 1) / perPage);
        return Math.min(WidgetCache.page(ctx), pages - 1);
    }
}
