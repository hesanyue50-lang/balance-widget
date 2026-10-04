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
    /* 最小值定得比"技术上能跑通"更保守一些：
       设太短只会让平台限流、耗电上升，而余额变化本身很慢，收益为零。
       前台 2 分钟（用户盯着看时够灵敏）；后台 5 分钟（唤醒一次要拉起进程+建连接，
       是主要耗电来源，没必要太频繁）。 */
    public static final int FG_MIN = 2;      // 前台最小 2 分钟
    public static final int BG_MIN = 5;      // 后台最小 5 分钟
    public static final int MAX_MIN = 360;

    private static final String KEY_FG = "fg_interval_min";
    private static final String KEY_BG = "bg_interval_min";
    /** 省电模式开关 */
    private static final String KEY_PS = "power_save";

    /**
     * 省电模式下后台刷新的最小间隔。
     *
     * 为什么是 60 分钟：后台唤醒一次要拉起进程、建连接、跑完所有平台 ——
     * 这是本应用最主要的耗电来源，而**余额变化本身很慢**（尤其预付费平台）。
     * 拉长到 1 小时对预警的实际影响很小，省电收益却很明显。
     * 用户仍可在省电模式下手动设更长。
     */
    public static final int PS_BG_MIN = 60;

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
    }

    public static int fgMinutes(Context c) {
        return clampFg(prefs(c).getInt(KEY_FG, FG_DEFAULT_MIN));
    }

    public static int bgMinutes(Context c) {
        int v = clampBg(prefs(c).getInt(KEY_BG, BG_DEFAULT_MIN));
        /* 省电模式：把后台间隔托到 PS_BG_MIN 以上（用户设了更长的就尊重用户） */
        if (powerSave(c) && v < PS_BG_MIN) v = PS_BG_MIN;
        return v;
    }

    /**
     * 用户**设定**的后台间隔（不经省电模式托底）。
     *
     * 设置页的编辑框必须用它填 —— 若用 bgMinutes()，省电模式下会显示托底后的 60，
     * 用户没动过也会在保存时把 60 写回去，一关省电模式间隔就莫名变成 1 小时。
     */
    public static int bgMinutesRaw(Context c) {
        return clampBg(prefs(c).getInt(KEY_BG, BG_DEFAULT_MIN));
    }

    public static boolean powerSave(Context c) {
        try { return prefs(c).getBoolean(KEY_PS, false); } catch (Throwable t) { return false; }
    }

    public static void setPowerSave(Context c, boolean on) {
        try { prefs(c).edit().putBoolean(KEY_PS, on).apply(); } catch (Throwable ignored) { }
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
