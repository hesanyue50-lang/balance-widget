package com.minis.balancewidget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 备份 / 恢复。
 *
 * <b>为什么需要</b>：应用数据全在私有目录，一旦卸载、清数据或出意外就全没了
 * （API 密钥、订阅、几个月的余额曲线）。备份成一个文件放到用户自己能拿到的地方，
 * 心里才踏实。
 *
 * <b>文件形态</b>：单个 JSON（扩展名 {@code .apibak}），自包含、可读、可人工检查。
 * 里面的密钥仍是 KeyVault 加密后的串 —— 换台设备可能解不开，
 * 同机恢复不受影响（这正是主要场景）。
 */
public final class Backup {

    /** 备份文件扩展名 */
    public static final String EXT = "apibak";
    /** 自定义 MIME，便于系统把这类文件与本应用关联起来 */
    public static final String MIME = "application/x-apibak";
    private static final String MAGIC = "api-manager-assistant";
    private static final int FORMAT = 1;

    private Backup() { }

    /** 导出成 JSON 文本；失败返回空串 */
    public static String exportJson(Context c) {
        try {
            JSONObject root = new JSONObject();
            root.put("app", MAGIC);
            root.put("format", FORMAT);
            root.put("exportedAt", System.currentTimeMillis());
            try {
                root.put("appVersion", c.getPackageManager()
                        .getPackageInfo(c.getPackageName(), 0).versionName);
            } catch (Throwable ignored) { }

            /* 设置（含密钥、订阅、订阅正文、各种偏好）。
               类型要原样保留 —— 数字被写成字符串的话恢复后读出来就全错了。 */
            JSONObject prefs = new JSONObject();
            Map<String, ?> all = sp(c).getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                if (v instanceof Set) {
                    JSONArray arr = new JSONArray();
                    for (Object o : (Set<?>) v) arr.put(String.valueOf(o));
                    prefs.put(e.getKey(), arr);
                } else if (v instanceof Float) {
                    /* Float 必须打类型标记：org.json 序列化时会把 7.0 压成 "7"，
                       再解析回来是 Integer → 恢复后 putInt，而读取方 getFloat 会抛
                       ClassCastException（折算率设成 1 元/积分、恢复后统计页直接崩）。 */
                    JSONObject fo = new JSONObject();
                    fo.put("__type", "float");
                    fo.put("value", ((Float) v).doubleValue());
                    prefs.put(e.getKey(), fo);
                } else if (v instanceof Long) {
                    /* ⚠️ Long 也必须打标记。org.json 把 3L 序列化成 "3"，
                       读回来是 Integer → 恢复时 putInt，而读取方 getLong 会抛
                       ClassCastException。后果不是"崩一下"：SubStore.save 的
                       catch 整段吞掉 → 订阅的新增/改名/删除**静默不落盘**；
                       Alert.threshold 的 getFloat 同理会把整轮刷新在预警处中断。 */
                    JSONObject lo = new JSONObject();
                    lo.put("__type", "long");
                    lo.put("value", ((Long) v).longValue());
                    prefs.put(e.getKey(), lo);
                } else if (v instanceof Integer) {
                    JSONObject io = new JSONObject();
                    io.put("__type", "int");
                    io.put("value", ((Integer) v).intValue());
                    prefs.put(e.getKey(), io);
                } else {
                    prefs.put(e.getKey(), v);
                }
            }
            root.put("prefs", prefs);

            /* 统计页的开关 / 折算率 / 曲线配色 / 控制台地址存在独立的 stats_opt 文件里，
               不跟着走的话换机全丢（而且恢复时也不会被清，留下半新半旧的配置）。 */
            try {
                SharedPreferences so = c.getSharedPreferences(
                        StatsOpt.PREF_NAME, Context.MODE_PRIVATE);
                JSONObject soj = new JSONObject();
                for (java.util.Map.Entry<String, ?> e : so.getAll().entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Float) {
                        JSONObject fo = new JSONObject();
                        fo.put("__type", "float");
                        fo.put("value", ((Float) v).doubleValue());
                        soj.put(e.getKey(), fo);
                    } else if (v instanceof Long) {
                        JSONObject lo = new JSONObject();
                        lo.put("__type", "long");
                        lo.put("value", ((Long) v).longValue());
                        soj.put(e.getKey(), lo);
                    } else if (v instanceof Integer) {
                        JSONObject io = new JSONObject();
                        io.put("__type", "int");
                        io.put("value", ((Integer) v).intValue());
                        soj.put(e.getKey(), io);
                    } else if (v instanceof String || v instanceof Boolean) {
                        soj.put(e.getKey(), v);
                    }
                }
                root.put("statsOpt", soj);
            } catch (Throwable ignored) { }

            Ledger lg = Ledger.get(c);
            root.put("snapshots", lg.exportSnapshots());
            root.put("recharges", lg.exportRecharges());
            return root.toString();
        } catch (Throwable t) {
            BalanceFetcher.diag(c, "备份导出失败：" + t);
            return "";
        }
    }

    /** 从 JSON 恢复；返回给用户看的结果说明 */
    public static String importJson(Context c, String json) {
        try {
            if (json == null || json.trim().length() == 0) return "文件是空的";
            JSONObject root = new JSONObject(json);
            if (!MAGIC.equals(root.optString("app"))) {
                return "这不是本应用的备份文件";
            }

            SharedPreferences.Editor ed = sp(c).edit();
            ed.clear();
            JSONObject prefs = root.optJSONObject("prefs");
            if (prefs != null) {
                Iterator<String> it = prefs.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    Object v = prefs.get(k);
                    if (v instanceof JSONObject && ((JSONObject) v).has("__type")) {
                        JSONObject t = (JSONObject) v;
                        String ty = t.optString("__type");
                        if ("float".equals(ty)) ed.putFloat(k, (float) t.optDouble("value", 0));
                        else if ("long".equals(ty)) ed.putLong(k, t.optLong("value", 0));
                        else if ("int".equals(ty)) ed.putInt(k, t.optInt("value", 0));
                        else ed.putString(k, t.optString("value", ""));
                    }
                    else if (v instanceof String) ed.putString(k, (String) v);
                    else if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
                    else if (v instanceof Integer) {
                        /* 旧备份没有类型标记：Float 被压成了 Integer。
                           已知的"率"类键按 float 还原，否则读取方 getFloat 会崩。 */
                        if (k.startsWith("rate_") || "last_rate".equals(k)) {
                            ed.putFloat(k, ((Integer) v).floatValue());
                        } else {
                            ed.putInt(k, (Integer) v);
                        }
                    }
                    else if (v instanceof Long) ed.putLong(k, (Long) v);
                    else if (v instanceof Double) ed.putFloat(k, ((Double) v).floatValue());
                    else if (v instanceof JSONArray) {
                        JSONArray arr = (JSONArray) v;
                        Set<String> set = new HashSet<String>();
                        for (int i = 0; i < arr.length(); i++) set.add(arr.optString(i));
                        ed.putStringSet(k, set);
                    }
                }
            }
            /* 必须 commit：恢复是"整库设置"操作，用 apply 异步写盘时
               进程一被杀就全丢 —— 而账本紧接着在 SQLite 事务里已提交，
               会留下"历史恢复了、设置没恢复"的半截状态。 */
            ed.commit();

            /* 还原 stats_opt（旧备份没有这个字段就跳过） */
            try {
                JSONObject soj = root.optJSONObject("statsOpt");
                if (soj != null) {
                    SharedPreferences.Editor soe = c.getSharedPreferences(
                            StatsOpt.PREF_NAME, Context.MODE_PRIVATE).edit();
                    soe.clear();
                    java.util.Iterator<String> sit = soj.keys();
                    while (sit.hasNext()) {
                        String k = sit.next();
                        Object v = soj.get(k);
                        if (v instanceof JSONObject && ((JSONObject) v).has("__type")) {
                            JSONObject t2 = (JSONObject) v;
                            String ty = t2.optString("__type");
                            if ("float".equals(ty)) soe.putFloat(k, (float) t2.optDouble("value", 0));
                            else if ("long".equals(ty)) soe.putLong(k, t2.optLong("value", 0));
                            else if ("int".equals(ty)) soe.putInt(k, t2.optInt("value", 0));
                            else soe.putString(k, t2.optString("value", ""));
                        } else if (v instanceof String) soe.putString(k, (String) v);
                        else if (v instanceof Boolean) soe.putBoolean(k, (Boolean) v);
                        else if (v instanceof Integer) soe.putInt(k, (Integer) v);
                        else if (v instanceof Long) soe.putLong(k, (Long) v);
                    }
                    soe.commit();
                }
            } catch (Throwable ignored) { }

            Ledger lg = Ledger.get(c);
            int n = lg.importAll(root.optJSONArray("snapshots"),
                    root.optJSONArray("recharges"));

            /* 缓存类字段要清掉：它们是上一次运行的产物（比如 MiMo 会话的内存缓存），
               恢复后继续沿用旧的会读到已经不存在的密钥。 */
            BalanceFetcher.onDataRestored(c);

            String when = "";
            long at = root.optLong("exportedAt", 0);
            if (at > 0) {
                when = "（备份于 " + new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm", java.util.Locale.US)
                        .format(new java.util.Date(at)) + "）";
            }
            return "已恢复 " + n + " 条余额记录" + when;
        } catch (Throwable t) {
            return "恢复失败：" + t;
        }
    }

    /** 建议的文件名 */
    public static String suggestName() {
        return "API管理助手备份-"
                + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                        .format(new java.util.Date())
                + "." + EXT;
    }

    // ---------- 自动备份 ----------

    /** 自动备份的最小间隔（小时） */
    public static final int AUTO_MIN_HOURS = 2;
    /** 自动备份的默认间隔（小时） */
    public static final int AUTO_DEFAULT_HOURS = 24;

    private static final String K_AUTO_ON = "auto_backup_on";
    private static final String K_AUTO_HOURS = "auto_backup_hours";
    private static final String K_AUTO_LAST = "auto_backup_last";

    public static boolean autoOn(Context c) {
        return sp(c).getBoolean(K_AUTO_ON, false);
    }

    /** 间隔小时数，收敛到 [AUTO_MIN_HOURS, ∞) */
    public static int autoHours(Context c) {
        int h = sp(c).getInt(K_AUTO_HOURS, AUTO_DEFAULT_HOURS);
        return h < AUTO_MIN_HOURS ? AUTO_MIN_HOURS : h;
    }

    public static void setAuto(Context c, boolean on, int hours) {
        if (hours < AUTO_MIN_HOURS) hours = AUTO_MIN_HOURS;
        sp(c).edit()
                .putBoolean(K_AUTO_ON, on)
                .putInt(K_AUTO_HOURS, hours)
                .commit();
    }

    /** 上次自动备份的时间戳（0 = 还没备份过） */
    public static long autoLast(Context c) {
        return sp(c).getLong(K_AUTO_LAST, 0L);
    }

    /**
     * 该不该自动备份了。到点就把内容写进**本机备份记录**
     * （不弹文件选择器 —— 后台没人能点它）。
     *
     * @return 真的备份了返回 true
     */
    public static boolean maybeAutoBackup(Context c) {
        try {
            if (!autoOn(c)) return false;
            long gap = autoHours(c) * 3600000L;
            long last = autoLast(c);
            long now = System.currentTimeMillis();
            if (last > 0 && now - last < gap) return false;

            String json = exportJson(c);
            if (json.length() == 0) return false;
            /* uri 传 null：自动备份只落本机，用户导出到哪是他自己的事 */
            String name = BackupStore.save(c, json, null, "自动备份");
            if (name == null) return false;
            sp(c).edit().putLong(K_AUTO_LAST, now).commit();
            return true;
        } catch (Throwable t) {
            BalanceFetcher.diag(c, "自动备份失败：" + t);
            return false;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
    }
}
