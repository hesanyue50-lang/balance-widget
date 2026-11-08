package com.minis.balancewidget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 后台定时刷新接收器。
 *
 * 【唯一用途】按固定间隔拉取 API 余额并做预警判断 ——
 * 不做其它任何事（不采集数据、不联网到别处）。
 *
 * 之所以需要"自启动"白名单：国产 ROM 会冻结后台进程，
 * 若不加入白名单，闹钟无法唤醒本应用，余额预警就会延迟甚至收不到。
 */
public class RefreshReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();

        // 开机后重新排期（闹钟在重启后会被清除）
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            if (hasKeys(context)) {
                RefreshScheduler.schedule(context);
                /* 开机后排了期就完事的话，桌面要显示 20~60 分钟的旧缓存。
                   补一次"稍后立刻刷"：延迟 15 秒 —— 刚开机时网络栈常常还没就绪，
                   立刻刷会全平台失败，用户开机会看到一屏红字。 */
                RefreshScheduler.refreshSoon(context, 15 * 1000L);
            }
            resumeVpnIfWanted(context);
            return;
        }

        /* App 刚更新完：把小组件强制救活一遍，并重排闹钟 */
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.PACKAGE_REPLACED".equals(action)) {
            Context ac = context.getApplicationContext();
            BalanceFetcher.diag(ac, "收到 App 更新广播，强制重绘小组件");
            Intent redraw = new Intent(ac, BalanceWidgetProvider.class);
            redraw.setAction(BalanceWidgetProvider.ACTION_FORCE_REDRAW);
            ac.sendBroadcast(redraw);
            if (hasKeys(ac)) {
                RefreshScheduler.schedule(ac);
                RefreshScheduler.refreshSoon(ac, 5 * 1000L);   // 更新后立刻把数据刷一遍
            }
            /* 更新后 VpnService 一定已经断了（进程被杀），而用户的开关还开着 ——
               恢复了才不会让更新变成"莫名其妙断网"。 */
            resumeVpnIfWanted(ac);
            return;
        }

        if (!RefreshScheduler.ACTION_BG_REFRESH.equals(action)) return;

        Context app = context.getApplicationContext();

        /* ⚠️ 先排下一次，再判断要不要取数。
           以前是「没配置来源就直接 return」，而 return 在 schedule() 之前 ——
           闹钟是一次性的，跳过一次就再也不响。而 hasKeys 当时只认 API Key，
           只配了 MiMo（登录态）或只配了高级平台的用户会被判成"没配任何东西"，
           后台刷新与低余额预警从此彻底停止，且日志里一个字都没有。 */
        RefreshScheduler.schedule(app);

        if (!hasKeys(app)) {
            BalanceFetcher.diag(app, "后台刷新跳过：没有可用来源"
                    + "（API Key / MiMo 登录态 / 自定义平台都没有）");
            return;
        }

        // 交给小组件那条链路统一处理：取数 → 阈值判定/发通知 → 渲染
        // （即使当前没有小组件，Provider 内部也会取数并做预警）
        Intent refresh = new Intent(app, BalanceWidgetProvider.class);
        refresh.setAction(BalanceWidgetProvider.ACTION_REFRESH);
        /* 打上"来自后台闹钟"的标记：Provider 据此走精简模式
           （跳过汇率、失败不重试），减少后台耗电。 */
        refresh.putExtra(BalanceWidgetProvider.EXTRA_BG, true);
        app.sendBroadcast(refresh);
        /* 下一次已经在方法开头排好了（见那里的注释：提前返回也必须续命） */
    }

    /**
     * 有没有配置过任何余额来源。
     *
     * ⚠️ 这个判断跑在**主线程**（onReceive），所以不能调 KeyStore.all() ——
     * 那要逐个过 TEE 解密，Key 多时上百毫秒，开机广播/闹钟唤醒都会付一次代价。
     * 改成读存档里的密文是否非空，不碰 keymaster。
     */
    private boolean hasKeys(Context ctx) {
        try {
            if (KeyStore.hasAnyConfigured(ctx)) return true;
            if (BalanceFetcher.hasAnyCustom(ctx)) return true;
            /* 这两类都不在 KeyStore 里，漏掉就是"只配了 MiMo / 只配了网页平台"
               的用户后台永远不刷新。全部走"看存档有没有东西"，不解密。 */
            if (BalanceFetcher.hasMimoSession(ctx)) return true;
            if (WebCustom.hasAny(ctx)) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    /**
     * 开机 / App 更新后，把用户开着的加速拉起来。
     *
     * 开关是用户意图的持久记录：用户点开过一次，就表示"我要用加速"，
     * 那么重启或更新后应该自动接上，而不是让用户发现网断了、再手动开一次。
     * 只有用户主动关掉才会停。
     *
     * 延迟几秒再启：刚开机时系统网络栈、VPN 授权都还没就绪，
     * 立刻 startCore 多半失败。失败也没关系 —— 开关保持不动，
     * 用户下次打开 App 时那套"开关开着但内核没跑"的逻辑会再拉一次。
     */
    private void resumeVpnIfWanted(final Context ctx) {
        try {
            if (!Clash.enabled(ctx)) return;
            /* 系统全局代理不自动起 —— 它接管整机流量还要弹授权框，
               这个决定留给用户自己做。部分/应用内模式才自动接上。 */
            if (!Clash.autoStartAllowed(ctx)) return;
            final Context ac = ctx.getApplicationContext();
            new Thread(new Runnable() {
                public void run() {
                    try { Thread.sleep(8000); } catch (InterruptedException ignored) { }
                    try {
                        if (!Clash.enabled(ac)) return;   // 等待期间用户关了就别启
                        if (Clash.isRunning()) return;
                        SubStore.Sub a = SubStore.active(ac);
                        if (a == null || !a.ready()) return;
                        Clash.startWithRollback(ac, Clash.buildConfig(a.yaml));
                        BalanceFetcher.diag(ac, "开机/更新后已自动恢复加速");
                    } catch (Throwable t) {
                        BalanceFetcher.diag(ac, "自动恢复加速失败：" + t.getClass().getSimpleName());
                    }
                }
            }).start();
        } catch (Throwable ignored) { }
    }
}
