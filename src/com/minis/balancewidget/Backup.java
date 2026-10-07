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
                } else {
                    prefs.put(e.getKey(), v);
                }
            }
            root.put("prefs", prefs);

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
                    if (v instanceof String) ed.putString(k, (String) v);
                    else if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
                    else if (v instanceof Integer) ed.putInt(k, (Integer) v);
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
            ed.apply();

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
                .apply();
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
            sp(c).edit().putLong(K_AUTO_LAST, now).apply();
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
