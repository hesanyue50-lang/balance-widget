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
}
