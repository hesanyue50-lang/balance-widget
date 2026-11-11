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
    private static final String AUTH_NOTIFIED_PREFIX = "authnotified_";
    public static final String CHANNEL_AUTH = "balance_auth";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
    }

    // ---------- 阈值读写 ----------

    /** 阈值（原币种）。0 表示不预警 */
    public static double threshold(Context c, String id) {
        /* 用 getAll() 取原类型再转：备份恢复后这里可能变成 Integer，
           直接 getFloat 会抛 ClassCastException —— 而这个异常会从
           mark() 一路抛到 check()，被两端的 catch 吞掉，
           等于整轮刷新在预警处中止（不落缓存、不渲染小组件、曲线也停更）。 */
        try {
            Object o = sp(c).getAll().get(THR_PREFIX + id);
            if (o instanceof Number) return ((Number) o).doubleValue();
        } catch (Throwable ignored) { }
        return 0;
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
                /* noData = "这平台压根没有余额可查"（免费服务 / 未登录 / 未填凭据）。
                   它们 bal 恒为 0，不排除掉就会天天误报"余额不足"。 */
                it.low = it.ok && !it.noData && thr > 0 && it.bal < thr
                        && !"消费".equals(it.tag);   // 后付费平台（如七牛云）没有余额概念，不参与低余额预警
                /* 顺手算「还能用几天」。放在 mark() 里是因为这是每轮刷新唯一
                   遍历全部平台的地方（应用内、小组件、单卡刷新都会经过），
                   在渲染循环里算会变成"每张卡一次 SQL"，那才是真的慢。 */
                it.daysLeft = -1;
                it.forecastHint = "";
                if (it.ok && !it.noData && it.bal > 0) {
                    Prediction.Estimate est = forecast(c, it);
                    if (est != null) {
                        it.daysLeft = Prediction.daysLeft(it.bal, est.perDay);
                        if (it.daysLeft <= 0 && est.validDays > 0) {
                            double have = est.validDays * 24;
                            if (have < 24) {
                                int need = (int) Math.ceil(24 - have);
                                if (need < 1) need = 1;
                                it.forecastHint = "消耗速度积累中（还需约 " + need + " 小时）";
                            } else {
                                /* 有足够长的数据却算不出速率 = 这段时间余额没怎么下降。
                                   以前这里会算成 need = 24 - 168 = 负数 → 夹到 1，
                                   于是永远显示"还需约 1 小时"，等到天荒地老也不变。 */
                                it.forecastHint = "近期余额没有明显下降，暂不预测";
                            }
                        }
                    }
                }
            }
            if (it.low) lows.add(it);
        }
        return lows;
    }

    /**
     * 按近 7 天消耗速度，这点余额还能撑几天。
     *
     * <p>账本里每 6 小时一条快照，区间法扣掉充值后得到日均消耗 —— 见
     * {@link Prediction}。数据不足（< 24 小时）时返回 -1，
     * 界面据此整行隐藏，不显示"还剩 -- 天"这种没法解释的东西。
     */
    /** 预测本体（可能返回"算不出来"的 Estimate，不返回 null 以外的错误） */
    static Prediction.Estimate forecast(Context c, BalanceFetcher.Item it) {
        try {
            /* 优先用「这张卡由哪几个密钥组成」去查 —— 合并卡的 it.id 是平台标识，
               账本里没有这个键，直接查永远是"数据不足"。 */
            String[] kids = (it.keyIds != null && it.keyIds.length > 0)
                    ? it.keyIds : new String[] { it.id };
            return Ledger.estimate(c, kids, 7);
        } catch (Throwable t) {
            return null;    // 预测永远不能把预警流程搞崩
        }
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
        /* 凭证失效单独一类通知：它和"余额低"是两件事，混在一起会互相干扰
           （余额机正常时也会因为登录过期而查不到值）。 */
        try { checkAuth(c, r.items); } catch (Throwable ignored) { }

        // 找出"本次刚开始低"的（上次未告警）
        List<BalanceFetcher.Item> fresh = new ArrayList<BalanceFetcher.Item>();
        SharedPreferences p = sp(c);
        SharedPreferences.Editor ed = p.edit();
        for (int i = 0; i < lows.size(); i++) {
            BalanceFetcher.Item it = lows.get(i);
            if (!p.getBoolean(ALERTED_PREFIX + alertKey(it), false)) fresh.add(it);
        }
        /* 同步告警状态（余额恢复 → 复位，以便下次再低还能提醒）。
           ⚠️ 复位必须**无条件**落盘：余额恢复时 fresh 必然为空（那正是最常见的情况），
           原来跟在下面的 `if (fresh.isEmpty() || !canNotify) return;` 后面，
           复位标记永远写不进去 —— 同一个平台再次跌破阈值就不再提醒了，
           要等进程重启才恢复。 */
        boolean anyReset = false;
        for (int i = 0; i < r.items.size(); i++) {
            BalanceFetcher.Item it = r.items.get(i);
            if (!it.ok) continue;
            double thr = it.threshold > 0 ? it.threshold : threshold(c, it.id);
            /* ⚠️ 不要在这里 continue。thr <= 0 的语义是"当前不该低"，本来就该复位。
           跳过的话：用户清空阈值 → 余额继续跌 → 再把阈值设回来（此时已经低于新阈值），
           因为 alerted 标记还挂着 true，一条通知都不会发。
           用户只觉得这功能"时灵时不灵"。 */
        if (thr <= 0) {
            ed.putBoolean(ALERTED_PREFIX + alertKey(it), false);
            continue;
        }
            if (!it.low) {
                ed.putBoolean(ALERTED_PREFIX + alertKey(it), false);
                anyReset = true;
            }
        }
        if (anyReset) ed.commit();

        /* 置 true 的标记仍然只在"能通知"时写 —— 否则用户没开通知权限时
           标记照样写进去了，事后他去开启也收不到这次预警。 */
        if (fresh.isEmpty() || !canNotify(c)) return;
        for (int i = 0; i < fresh.size(); i++) {
            ed.putBoolean(ALERTED_PREFIX + alertKey(fresh.get(i)), true);
        }
        /* 必须 commit：这是"已经提醒过"的幂等标记，丢了就会重复推送 ——
           而且推送往往发生在后台刷新刚结束时，正是进程容易被回收的时机。 */
        ed.commit();

        notifyLow(c, fresh);
    }

    /**
     * 凭证失效通知：某个平台连续失败到被判"需要重新登录"时，主动说一声。
     *
     * <p>为什么必须有：真实发生过——小米 MiMo 的会话在后台断了 10 小时，
     * 每半小时撞一次 401，界面上只是红字「查询失败」，用户完全不知道。
     * 这类"静默失败"比功能缺失更消耗信任。
     *
     * <p>幂等：每平台只在「正常 → 需要重登」的跳变时发一次；恢复后自动复位，
     * 下次再失效还能提醒。
     */
    public static void checkAuth(Context c, List<BalanceFetcher.Item> items) {
        if (items == null || items.isEmpty()) return;
        SharedPreferences p = sp(c);
        List<BalanceFetcher.Item> need = new ArrayList<BalanceFetcher.Item>();
        SharedPreferences.Editor ed = p.edit();
        boolean touched = false;

        for (int i = 0; i < items.size(); i++) {
            BalanceFetcher.Item it = items.get(i);
            String plat = it.platform == null ? "" : it.platform;
            if (plat.length() == 0) continue;
            String key = AUTH_NOTIFIED_PREFIX + plat;
            boolean needLogin = !it.ok && AuthFail.needLogin(c, plat);
            if (needLogin) {
                if (!p.getBoolean(key, false)) need.add(it);
            } else if (it.ok && p.getBoolean(key, false)) {
                ed.putBoolean(key, false);      // 恢复了：复位，下次还能提醒
                touched = true;
            }
        }
        if (touched) ed.commit();
        if (need.isEmpty() || !canNotify(c)) return;

        for (int i = 0; i < need.size(); i++) {
            String plat = need.get(i).platform == null ? "" : need.get(i).platform;
            ed.putBoolean(AUTH_NOTIFIED_PREFIX + plat, true);
        }
        ed.commit();
        notifyAuth(c, need);
    }

    private static void notifyAuth(Context c, List<BalanceFetcher.Item> items) {
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_AUTH) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_AUTH,
                        "登录状态", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("当某个平台的登录态彻底失效、需要重新登录时提醒");
                nm.createNotificationChannel(ch);
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append("\n");
                BalanceFetcher.Item it = items.get(i);
                sb.append(it.label).append("：登录态已失效");
                if ("mimo".equals(it.platform)) sb.append("（打开 App 会自动尝试续期）");
            }

            Intent open = new Intent(c, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(c, 1, open, flags);

            /* 跟 notifyLow 用同一套 Notification.Builder —— 项目没有 androidx，
               NotificationCompat 根本不在 classpath 里 */
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(c, CHANNEL_AUTH);
            } else {
                b = new Notification.Builder(c);
            }
            b.setSmallIcon(R.drawable.ic_refresh)
             .setContentTitle("有平台需要重新登录")
             .setContentText(items.size() == 1
                     ? items.get(0).label + " 登录态已失效"
                     : items.size() + " 个平台登录态已失效")
             .setStyle(new Notification.BigTextStyle().bigText(sb.toString()))
             .setContentIntent(pi)
             .setAutoCancel(true);
            nm.notify(9001, b.build());
        } catch (Throwable ignored) { }
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
            sb.append(it.label).append("  ");
            /* 订阅制没有"余额"这个概念：该显示的是还能用几天，
               写成 ¥0.00（阈值 7.00）用户根本看不懂在提醒什么。 */
            if ("sub".equals(it.kind) && it.subEndMs > 0) {
                long dd = (it.subEndMs - System.currentTimeMillis()) / 86400000L;
                sb.append(dd <= 0 ? "今天到期" : ("还有 " + dd + " 天到期"));
            } else {
                sb.append(("USD".equals(it.tag) ? "$" : "¥"))
                  .append(String.format("%.2f", it.bal));
                /* 有预测就补一句"还能用几天"—— 比单看余额有决策价值 */
                if (it.daysLeft > 0) sb.append("，约够 ").append(it.daysLeft).append(" 天");
                sb.append("（阈值 ")
                  .append(("USD".equals(it.tag) ? "$" : "¥"))
                  .append(String.format("%.2f", thr))
                  .append("）");
            }
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
