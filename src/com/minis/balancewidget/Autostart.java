package com.minis.balancewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;

/**
 * 自启动（后台运行）白名单引导。
 *
 * 背景：Android 原生没有"自启动"这个概念，是国产 ROM 加的后台管理策略。
 * 不在白名单里，AlarmManager 闹钟无法唤醒本应用，余额预警就会延迟甚至收不到。
 *
 * 注意：**系统不提供"是否已在白名单"的查询接口**，
 * 所以只能引导一次（用标记记录），并在设置页保留可重复触发的入口。
 *
 * 本应用的后台唤醒**唯一用途**是刷新 API 余额并判断是否低于预警阈值。
 */
public final class Autostart {

    private static final String KEY_ASKED = "autostart_asked";

    /** 各厂商的自启动管理页（逐个尝试，失败则回退到应用详情页） */
    private static final String[][] TARGETS = {
        // 包名, Activity
        {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
        {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
        {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"},
        {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
        {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
        {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
        {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
        {"com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"},
        {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
        {"com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"},
        {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
    };

    private Autostart() { }

    /**
     * 是否已在**电池优化白名单**里。
     *
     * 这和"自启动"是两件事，缺一不可：
     *   - 自启动白名单（国产 ROM 特有）：决定闹钟能不能唤醒本应用
     *   - 电池优化白名单（Android 6+ 标准）：决定 Doze 深度睡眠时闹钟会不会被推迟
     * 实测这台机器自启动已给、电池优化却没进 —— 闹钟虽然排上了，
     * 但 Doze 下会被显著推迟（观察到的 window 就有 15 分钟）。
     */
    public static boolean isIgnoringBattery(Context c) {
        try {
            if (android.os.Build.VERSION.SDK_INT < 23) return true;   // 6.0 以下无此机制
            android.os.PowerManager pm = (android.os.PowerManager)
                    c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 请求加入电池优化白名单。
     * 系统会弹一个标准确认框，用户点一下即可，比让他在设置里翻菜单省事得多。
     */
    public static void requestIgnoreBattery(Context c) {
        if (c == null) return;
        try {
            if (android.os.Build.VERSION.SDK_INT < 23) return;
            Intent it = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            it.setData(Uri.fromParts("package", c.getPackageName(), null));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(it);
        } catch (Throwable t) {
            /* 部分 ROM 没有这个系统对话框 → 退到"电池优化"列表页让用户自己找 */
            try {
                Intent it = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(it);
            } catch (Throwable ig) { }
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean hasAsked(Context c) {
        try { return prefs(c).getBoolean(KEY_ASKED, false); } catch (Throwable t) { return false; }
    }

    public static void markAsked(Context c) {
        try { prefs(c).edit().putBoolean(KEY_ASKED, true).apply(); } catch (Throwable ignored) { }
    }

    /** 打开系统自启动管理页；全部失败时回退到应用详情页 */
    public static void openSettings(Context c) {
        for (int i = 0; i < TARGETS.length; i++) {
            try {
                Intent it = new Intent();
                it.setComponent(new ComponentName(TARGETS[i][0], TARGETS[i][1]));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(it);
                return;                       // 成功就不再往下试
            } catch (Throwable ignored) { }
        }
        // 回退：应用详情页（用户可在此找到"自启动/后台管理"入口）
        try {
            Intent it = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            it.setData(Uri.fromParts("package", c.getPackageName(), null));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(it);
        } catch (Throwable ignored) { }
    }

    /**
     * 弹出自启动引导。
     * @param force true = 用户主动点击（每次都弹）；false = 自动提示（只弹一次）
     */
    public static void ensure(final Activity act, boolean force) {
        if (act == null || act.isFinishing()) return;
        if (android.os.Build.VERSION.SDK_INT >= 17 && act.isDestroyed()) return;

        /* 电池优化没进白名单的话，即使用户点过"暂不"也值得再提一次 ——
           这一项直接影响闹钟准不准，比自启动更"硬"。 */
        final boolean needBattery = !isIgnoringBattery(act);
        if (!force && hasAsked(act) && !needBattery) return;

        markAsked(act);

        try {
            String msg = act.getString(R.string.autostart_msg, RefreshScheduler.bgMinutes(act));
            if (needBattery) msg = msg + "\n\n" + act.getString(R.string.battery_msg);

            AlertDialog.Builder b = new AlertDialog.Builder(act)
                    .setTitle(R.string.autostart_title)
                    .setMessage(msg)
                    .setPositiveButton(R.string.autostart_go,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    openSettings(act);          // 自启动白名单
                                }
                            })
                    .setNegativeButton(R.string.autostart_later, null)
                    .setCancelable(true);
            if (needBattery) {
                /* 多给一个入口：两项都要做，分两个按钮各管一个最清楚 */
                b.setNeutralButton(R.string.battery_go,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                requestIgnoreBattery(act);
                            }
                        });
            }
            b.show();
        } catch (Throwable ignored) { }
    }
}
