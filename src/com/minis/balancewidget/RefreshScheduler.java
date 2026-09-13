package com.minis.balancewidget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 后台定时刷新的调度器。
 *
 * 为什么不用 Handler：应用切到后台后进程会被系统挂起，Handler 定时器不再触发。
 * 后台必须用 AlarmManager 让系统在指定时间唤醒我们。
 *
 * 采用「单次闹钟 + 每次触发后重新排期」而非 setRepeating：
 * setRepeating 在 Doze 模式被推迟后不会自动恢复，单次+重排更可靠。
 * setAndAllowWhileIdle 让闹钟在 Doze 下也能触发（系统可能小幅推迟）。
 */
public final class RefreshScheduler {

    // ---------- 可配置的刷新间隔 ----------
    /** 前台默认 5 分钟 */
    public static final int FG_DEFAULT_MIN = 5;
    /** 后台默认 20 分钟 */
    public static final int BG_DEFAULT_MIN = 20;
    /** 范围（分钟）：过短会频繁请求被平台限流，过长则预警不及时 */
    public static final int FG_MIN = 1;      // 前台最小 1 分钟
    public static final int BG_MIN = 2;      // 后台最小 2 分钟（唤醒更耗电）
    public static final int MAX_MIN = 360;

    private static final String KEY_FG = "fg_interval_min";
    private static final String KEY_BG = "bg_interval_min";

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
    }

    public static int fgMinutes(Context c) {
        return clampFg(prefs(c).getInt(KEY_FG, FG_DEFAULT_MIN));
    }

    public static int bgMinutes(Context c) {
        return clampBg(prefs(c).getInt(KEY_BG, BG_DEFAULT_MIN));
    }

    public static int clampFg(int v) {
        if (v < FG_MIN) return FG_MIN;
        if (v > MAX_MIN) return MAX_MIN;
        return v;
    }

    public static int clampBg(int v) {
        if (v < BG_MIN) return BG_MIN;
        if (v > MAX_MIN) return MAX_MIN;
        return v;
    }

    public static void setIntervals(Context c, int fgMin, int bgMin) {
        prefs(c).edit()
                .putInt(KEY_FG, clampFg(fgMin))
                .putInt(KEY_BG, clampBg(bgMin))
                .apply();
    }

    public static long fgMillis(Context c) { return fgMinutes(c) * 60 * 1000L; }

    public static long bgMillis(Context c) { return bgMinutes(c) * 60 * 1000L; }

    public static final String ACTION_BG_REFRESH = "com.minis.balancewidget.ACTION_BG_REFRESH";
    private static final int REQ_CODE = 3001;

    private RefreshScheduler() { }

    private static PendingIntent pending(Context ctx) {
        Intent it = new Intent(ctx, RefreshReceiver.class);
        it.setAction(ACTION_BG_REFRESH);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, REQ_CODE, it, flags);
    }

    /** 排下一次后台刷新 */
    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long at = System.currentTimeMillis() + bgMillis(ctx);
            PendingIntent pi = pending(ctx);
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable ignored) { }
    }

    /** 取消后台刷新（应用回到前台时用，改由 Handler 接管） */
    public static void cancel(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pending(ctx));
        } catch (Throwable ignored) { }
    }
}
