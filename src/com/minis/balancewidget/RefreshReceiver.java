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
            if (hasKeys(context)) RefreshScheduler.schedule(context);
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
            if (hasKeys(ac)) RefreshScheduler.schedule(ac);
            /* 更新后 VpnService 一定已经断了（进程被杀），而用户的开关还开着 ——
               恢复了才不会让更新变成"莫名其妙断网"。 */
            resumeVpnIfWanted(ac);
            return;
        }

        if (!RefreshScheduler.ACTION_BG_REFRESH.equals(action)) return;

        Context app = context.getApplicationContext();

        // 没配置任何 Key 就不必打扰系统
        if (!hasKeys(app)) return;

        // 交给小组件那条链路统一处理：取数 → 阈值判定/发通知 → 渲染
        // （即使当前没有小组件，Provider 内部也会取数并做预警）
        Intent refresh = new Intent(app, BalanceWidgetProvider.class);
        refresh.setAction(BalanceWidgetProvider.ACTION_REFRESH);
        /* 打上"来自后台闹钟"的标记：Provider 据此走精简模式
           （跳过汇率、失败不重试），减少后台耗电。 */
        refresh.putExtra(BalanceWidgetProvider.EXTRA_BG, true);
        app.sendBroadcast(refresh);

        // 排下一次
        RefreshScheduler.schedule(app);
    }

    private boolean hasKeys(Context ctx) {
        try {
            java.util.List<KeyStore.ApiKey> all = KeyStore.all(ctx);
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).isConfigured()) return true;
            }
            if (BalanceFetcher.loadCustom(ctx).size() > 0) return true;
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
                        Clash.start(ac, Clash.buildConfig(a.yaml));
                        BalanceFetcher.diag(ac, "开机/更新后已自动恢复加速");
                    } catch (Throwable t) {
                        BalanceFetcher.diag(ac, "自动恢复加速失败：" + t.getClass().getSimpleName());
                    }
                }
            }).start();
        } catch (Throwable ignored) { }
    }
}
