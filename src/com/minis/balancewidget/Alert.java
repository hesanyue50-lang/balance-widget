package com.minis.balancewidget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * 低余额预警。
 *
 * 每个平台可设一个阈值（用**原币种**，因为卡片上显示的就是原币种）。
 * 余额低于阈值时：
 *   1. 该平台余额在应用内与小组件上标红
 *   2. 发一条系统通知
 *
 * 防重复：记录每个平台"上次是否已告警"，只在 未告警→告警 的跳变时发通知，
 * 余额恢复后自动复位，下次再低还能再提醒一次。
 */
public class Alert {

    public static final String CHANNEL_ID = "balance_low";
    private static final String THR_PREFIX = "thr_";
    private static final String ALERTED_PREFIX = "alerted_";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
    }

    // ---------- 阈值读写 ----------

    /** 阈值（原币种）。0 表示不预警 */
    public static double threshold(Context c, String id) {
        return sp(c).getFloat(THR_PREFIX + id, 0f);
    }

    public static void setThreshold(Context c, String id, double v) {
        sp(c).edit().putFloat(THR_PREFIX + id, (float) (v > 0 ? v : 0)).apply();
    }

    /** 告警幂等键：优先用 Item 携带的（自定义平台索引会变，不能用 id） */
    private static String alertKey(BalanceFetcher.Item it) {
        return it.alertKey != null && it.alertKey.length() > 0 ? it.alertKey : it.id;
    }

    // ---------- 判定 ----------

    /** 给结果里每个平台打上 low 标记。返回低于阈值的平台列表。 */
    public static List<BalanceFetcher.Item> mark(Context c, BalanceFetcher.Result r) {
        List<BalanceFetcher.Item> lows = new ArrayList<BalanceFetcher.Item>();
        for (int i = 0; i < r.items.size(); i++) {
            BalanceFetcher.Item it = r.items.get(i);
            double thr = it.threshold > 0 ? it.threshold : threshold(c, it.id);
            if ("sub".equals(it.kind)) {
                // 订阅制没有余额概念：按「距到期天数」报警，阈值含义=提前 N 天提醒
                if (it.ok && it.subEndMs > 0 && thr > 0) {
                    long days = (it.subEndMs - System.currentTimeMillis()) / 86400000L;
                    it.low = days < thr;
                } else {
                    it.low = false;
                }
            } else {
                it.low = it.ok && thr > 0 && it.bal < thr
                        && !"消费".equals(it.tag);   // 后付费平台（如七牛云）没有余额概念，不参与低余额预警
            }
            if (it.low) lows.add(it);
        }
        return lows;
    }

    // ---------- 通知 ----------

    private static boolean canNotify(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            return c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "余额不足预警", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("当某个平台的余额低于你设置的阈值时提醒");
        nm.createNotificationChannel(ch);
    }

    /**
     * 检查并发通知。只对"刚跌破阈值"的平台提醒，避免每次刷新都骚扰。
     * 应在每次取数成功后调用（应用内与小组件都调）。
     */
    public static void check(Context c, BalanceFetcher.Result r) {
        List<BalanceFetcher.Item> lows = mark(c, r);

        // 找出"本次刚开始低"的（上次未告警）
        List<BalanceFetcher.Item> fresh = new ArrayList<BalanceFetcher.Item>();
        SharedPreferences p = sp(c);
        SharedPreferences.Editor ed = p.edit();
        for (int i = 0; i < lows.size(); i++) {
            BalanceFetcher.Item it = lows.get(i);
            if (!p.getBoolean(ALERTED_PREFIX + alertKey(it), false)) fresh.add(it);
        }
        // 同步告警状态（余额恢复 → 复位，以便下次再低还能提醒）
        for (int i = 0; i < r.items.size(); i++) {
            BalanceFetcher.Item it = r.items.get(i);
            if (!it.ok) continue;
            double thr = it.threshold > 0 ? it.threshold : threshold(c, it.id);
            if (thr <= 0) continue;
            ed.putBoolean(ALERTED_PREFIX + alertKey(it), it.low);
        }
        ed.apply();

        if (fresh.isEmpty() || !canNotify(c)) return;
        notifyLow(c, fresh);
    }

    private static void notifyLow(Context c, List<BalanceFetcher.Item> items) {
        ensureChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            BalanceFetcher.Item it = items.get(i);
            double thr = it.threshold > 0 ? it.threshold : threshold(c, it.id);
            if (i > 0) sb.append("\n");
            sb.append(it.label).append("  ")
              .append(("USD".equals(it.tag) ? "$" : "¥")).append(String.format("%.2f", it.bal))
              .append("（阈值 ")
              .append(("USD".equals(it.tag) ? "$" : "¥")).append(String.format("%.2f", thr))
              .append("）");
        }

        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(c, CHANNEL_ID);
        } else {
            b = new Notification.Builder(c);
        }
        b.setSmallIcon(R.drawable.ic_refresh)
         .setContentTitle("余额不足预警")
         .setContentText(items.size() == 1
                 ? items.get(0).label + " 余额偏低"
                 : items.size() + " 个平台余额偏低")
         .setStyle(new Notification.BigTextStyle().bigText(sb.toString()))
         .setContentIntent(pi)
         .setAutoCancel(true);

        try {
            nm.notify(1001, b.build());
        } catch (Throwable ignored) { }
    }
}
