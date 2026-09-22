package com.minis.balancewidget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 多 Key 存储。
 *
 * 以前是「一个平台一个 Key」—— SharedPreferences 里 deepseek → "sk-xxx"。
 * 但一个人在同一家开好几个 Key 太常见了（主力 / 备用 / 不同项目分开算），
 * 所以改成**一个平台挂一组 Key**：
 *
 *     {"deepseek": [ {id,label,key,note,budget,threshold,draw}, ... ]}
 *
 * 老数据自动迁移：原来那条 Key 变成数组里的第一项，标签叫「默认」。
 * 迁移只在第一次读到旧键时做一次，之后旧的键会被抹掉。
 */
public final class KeyStore {

    /** 数据版本号：add/update/remove/save 时递增，供 UI 判断缓存是否失效 */
    public static long dataVersion = 0;

    private static final String K_KEYS = "keys_json_v2";
    /** 单平台 Key 数量的上限：太多了统计页会糊成一片，也没实际意义 */
    public static final int MAX_PER_PLATFORM = 8;

    /** 一个 Key 条目 */
    public static class ApiKey {
        public String id = "";
        public String platform = "";     // 所属平台：deepseek / qiniu / custom:xxx
        public String label = "";        // 显示名：主力 / 备用 / 项目A
        public String key = "";          // 密钥（KeyVault 加密后存）
        public String note = "";         // 用户备注
        public double budget = 0;        // 预算（消费制用；0 = 不设）
        public double threshold = 0;     // 低余额预警阈值（0 = 不预警）
        public boolean draw = true;      // 是否参与统计绘图（免费 Key 默认关）
        
        // 阿里云百炼专用：AccessKey（用于 BSS OpenAPI 查询余额）
        public String accessKeyId = "";     // AccessKeyId（LTAI 开头）
        public String accessKeySecret = ""; // AccessKeySecret
        public String planMode = "";        // 百炼计费模式：balance=余额制 / subscription=订阅制(Token Plan)，空视为 balance

        public boolean isConfigured() {
            return key != null && key.length() > 0;
        }
        
        // 阿里云百炼是否配置了 AccessKey
        public boolean hasAliyunAccessKey() {
            return accessKeyId != null && accessKeyId.length() > 0 
                && accessKeySecret != null && accessKeySecret.length() > 0;
        }
    }

    private KeyStore() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
    }

    // ---------- 读 ----------

    /** 取某个平台的全部 Key（可能为空） */
    public static List<ApiKey> get(Context c, String platform) {
        List<ApiKey> all = load(c);
        List<ApiKey> out = new ArrayList<ApiKey>();
        for (int i = 0; i < all.size(); i++) {
            ApiKey k = all.get(i);
            if (platform.equals(k.platform)) out.add(k);
        }
        return out;
    }

    /** 全部 Key（跨平台） */
    public static List<ApiKey> all(Context c) {
        return load(c);
    }

    /** 平台下第一个配置了密钥的 Key，没有就返回 null */
    public static ApiKey firstConfigured(Context c, String platform) {
        List<ApiKey> list = get(c, platform);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).isConfigured()) return list.get(i);
        }
        // 没有 Key 也可能有 Key（静态平台），返回第一项
        return list.isEmpty() ? null : list.get(0);
    }

    // ---------- 写 ----------

    public static void add(Context c, String platform, ApiKey k) {
        dataVersion++;
        List<ApiKey> all = load(c);
        if (k.id == null || k.id.length() == 0) {
            k.id = UUID.randomUUID().toString().substring(0, 8);
        }
        k.platform = platform;
        all.add(k);
        save(c, all);
    }

    public static void update(Context c, ApiKey k) {
        dataVersion++;
        List<ApiKey> all = load(c);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(k.id)) {
                k.platform = all.get(i).platform;
                all.set(i, k);
                save(c, all);
                return;
            }
        }
    }

    public static void remove(Context c, String id) {
        dataVersion++;
        List<ApiKey> all = load(c);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).id.equals(id)) all.remove(i);
        }
        save(c, all);
    }

    /** 某个平台下已配置的 Key 数量 */
    public static int countConfigured(Context c, String platform) {
        int n = 0;
        List<ApiKey> list = get(c, platform);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).isConfigured()) n++;
        }
        return n;
    }

    // ---------- 内部 ----------

    private static List<ApiKey> load(Context c) {
        List<ApiKey> out = new ArrayList<ApiKey>();
        SharedPreferences p = sp(c);
        String json = p.getString(K_KEYS, "");

        // 第一次：从老格式迁移过来
        if (json == null || json.length() == 0) {
            return migrateLegacy(c);
        }

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (o.optString("platform", "").length() == 0) continue;
                ApiKey k = new ApiKey();
                k.id = o.optString("id", "");
                k.platform = o.optString("platform", "");
                k.label = o.optString("label", "");
                k.key = KeyVault.dec(o.optString("key", ""));
                k.note = o.optString("note", "");
                k.budget = o.optDouble("budget", 0);
                k.threshold = o.optDouble("threshold", 0);
                k.draw = o.optBoolean("draw", true);
                // 阿里云 AccessKey（新增字段）
                k.accessKeyId = KeyVault.dec(o.optString("accessKeyId", ""));
                k.accessKeySecret = KeyVault.dec(o.optString("accessKeySecret", ""));
                k.planMode = o.optString("planMode", "balance");
                if (k.planMode == null || k.planMode.length() == 0) k.planMode = "balance";
                out.add(k);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static void save(Context c, List<ApiKey> list) {
        dataVersion++;
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < list.size(); i++) {
                ApiKey k = list.get(i);
                JSONObject o = new JSONObject();
                o.put("id", k.id);
                o.put("platform", k.platform);
                o.put("label", k.label);
                o.put("key", KeyVault.enc(k.key));
                o.put("note", k.note);
                o.put("budget", k.budget);
                o.put("threshold", k.threshold);
                o.put("draw", k.draw);
                // 阿里云 AccessKey（新增字段）
                o.put("accessKeyId", KeyVault.enc(k.accessKeyId));
                o.put("accessKeySecret", KeyVault.enc(k.accessKeySecret));
                o.put("planMode", (k.planMode == null || k.planMode.length() == 0)
                        ? "balance" : k.planMode);
                arr.put(o);
            }
            sp(c).edit().putString(K_KEYS, arr.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** 把老格式（platform → key 字符串）搬进新结构，只做一次 */
    private static List<ApiKey> migrateLegacy(Context c) {
        List<ApiKey> out = new ArrayList<ApiKey>();
        SharedPreferences p = sp(c);
        SharedPreferences.Editor ed = p.edit();
        boolean any = false;

        for (int i = 0; i < BalanceFetcher.IDS.length; i++) {
            String id = BalanceFetcher.IDS[i];
            String raw = p.getString(id, "");
            if (raw == null || raw.length() == 0) continue;
            ApiKey k = new ApiKey();
            k.id = id + "-0";
            k.platform = id;
            k.label = "默认";
            k.key = KeyVault.dec(raw);
            k.threshold = p.getFloat("thr_" + id, 0f);
            k.draw = true;   // 默认所有 API 都显示（用户可在统计页逐个关）
            out.add(k);
            ed.remove(id);
            any = true;
        }

        if (any) {
            save(c, out);
            ed.apply();
            BalanceFetcher.diag(c, "多 Key 迁移完成，共 " + out.size() + " 项");
        }
        return out;
    }

    /** 给统计页/详情页用：所有条目里能参与绘图的 */
    public static List<ApiKey> drawable(Context c) {
        List<ApiKey> out = new ArrayList<ApiKey>();
        List<ApiKey> all = load(c);
        for (int i = 0; i < all.size(); i++) {
            ApiKey k = all.get(i);
            if (k.draw && k.isConfigured()) out.add(k);
        }
        return out;
    }
}
