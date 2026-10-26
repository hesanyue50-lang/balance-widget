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
    public static volatile long dataVersion = 0;
    /** 保护 cache/cacheVer 的锁（后台刷新线程与 UI 线程会并发访问） */
    private static final Object CACHE_LOCK = new Object();

    /**
     * 内存缓存（关键性能修复）。
     *
     * load() 每读一次就要对**每个** Key 做一遍 Android Keystore 的 AES-GCM 解密 ——
     * 单次解密要过 TEE，几毫秒到几十毫秒。而设置页渲染时对每个平台都要调
     * get()/all()，PRESETS 16 个平台 × 每平台 2~3 次 × 20 个 Key
     * = 几百次硬件解密，全在主线程 → 保存一个 Key 要卡好几秒。
     *
     * 所以按 dataVersion 缓存：只有真的增删改过才重读重解密。
     */
    private static List<ApiKey> cache;
    private static long cacheVer = -1L;

    /** 失效缓存：任何写操作前调用 */
    /**
     * 整库作废缓存（外部改了底层存储时用）。
     *
     * 备份恢复就是这种情形：它直接把 keys_json_v2 写进 SharedPreferences，
     * 不经过 add/update/remove，所以 dataVersion 没变 ——
     * KeyStore.load 认为缓存还是新的，返回的仍是**恢复前**的旧列表。
     * 紧接着设置页任何一次 save() 都会用这份旧列表把刚恢复的 Key 整段覆盖掉。
     */
    public static void invalidateAll() {
        dataVersion++;
        invalidate();
    }

    private static void invalidate() {
        cache = null;
        cacheVer = -1L;
    }

    private static final String K_KEYS = "keys_json_v2";
    /** 单平台 Key 数量的上限：太多了统计页会糊成一片，也没实际意义 */
    public static final int MAX_PER_PLATFORM = 8;

    /** 一个 Key 条目 */
    public static class ApiKey {
        public String id = "";
        public String platform = "";     // 所属平台：deepseek / qiniu / custom:xxx
        public String label = "";        // 显示名：主力 / 备用 / 项目A
        public String key = "";          // 密钥（KeyVault 加密后存）
        /**
         * 原始密文（只在内存里，不参与序列化）。
         *
         * 用途：KeyVault.dec 失败时（换机、Keystore 被清、系统升级后密钥失效）
         * 只能返回空串，和"从未配置"无法区分。此时如果照常 save，
         * enc("")="" 会把磁盘上的原密文写没 —— 用户进设置页改任意一把 Key，
         * 所有解不开的 Key 就被永久销毁，连换回原机的机会都没了。
         * 保存时若 key 为空而 rawKey 非空，原样写回密文，不动它。
         */
        public String rawKey = "";
        public String rawAccessKeyId = "";
        public String rawAccessKeySecret = "";
        public String note = "";         // 用户备注
        public double budget = 0;        // 预算（消费制用；0 = 不设）
        public double threshold = 0;     // 低余额预警阈值（0 = 不预警）
        public boolean draw = true;      // 是否参与统计绘图（免费 Key 默认关）
        
        // 阿里云百炼专用：AccessKey（用于 BSS OpenAPI 查询余额）
        public String accessKeyId = "";     // AccessKeyId（LTAI 开头）
        public String accessKeySecret = ""; // AccessKeySecret
        public String planMode = "";        // 百炼计费模式：balance=余额制 / subscription=订阅制(Token Plan)，空视为 balance
        public boolean hideCard = false;    // 在 API 卡片/小组件中隐藏（统计由 draw 单独控制）

        /* WorkBuddy 网关专用：服务地址。
           网关跑在本机（如 http://127.0.0.1:7863），地址由用户自己填 ——
           不同人可能换端口、或者把网关放在局域网另一台机器上，写死没有意义。 */
        public String baseUrl = "";

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
    /** 按 id 取最新的 Key 对象（写回前用它，避免陈旧快照覆盖字段） */
    public static ApiKey byId(Context c, String id) {
        List<ApiKey> all = load(c);
        for (int i = 0; i < all.size(); i++)
            if (all.get(i).id.equals(id)) return all.get(i);
        return null;
    }

    public static List<ApiKey> all(Context c) {
        /* 返回副本：缓存是共享的可变列表，外面拿到就改会污染全局 */
        return new ArrayList<ApiKey>(load(c));
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
        dataVersion++; invalidate();
        List<ApiKey> all = load(c);
        if (k.id == null || k.id.length() == 0) {
            k.id = UUID.randomUUID().toString().substring(0, 8);
        }
        k.platform = platform;
        all.add(k);
        save(c, all);
    }

    public static void update(Context c, ApiKey k) {
        dataVersion++; invalidate();
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
        dataVersion++; invalidate();
        List<ApiKey> all = load(c);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).id.equals(id)) all.remove(i);
        }
        save(c, all);
    }

    /**
     * 删掉某平台下**所有** Key，返回删了几条。
     *
     * ⚠️ 删除自定义平台时必须调这个 —— 否则它的 Key 会变成孤儿：
     * 平台没了、Key 还在 KeyStore 里，统计页遍历 KeyStore 时照常给它分组，
     * 图例上就挂着一个解析不出名字的 "custom:0"（用户看到会以为见鬼了）。
     */
    public static int removeAllForPlatform(Context c, String platform) {
        dataVersion++; invalidate();
        List<ApiKey> all = load(c);
        List<ApiKey> keep = new ArrayList<ApiKey>();
        int n = 0;
        for (int i = 0; i < all.size(); i++) {
            if (platform.equals(all.get(i).platform)) n++;
            else keep.add(all.get(i));
        }
        if (n > 0) save(c, keep);
        return n;
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
        synchronized (CACHE_LOCK) {
            if (cache != null && cacheVer == dataVersion) return cache;
        }
        /* parseAll 要逐个过 TEE 解密，窗口有上百毫秒 —— 这期间完全可能
           后台刷新线程在读、而 UI 线程刚 add/update 完。以前无条件写回 cache，
           就会把别人刚写的新列表盖成自己这份旧的，还把 cacheVer 记成新版本号
           → 新 Key 在内存和磁盘上双双"消失"。 */
        final long ver = dataVersion;
        List<ApiKey> out = parseAll(c);
        synchronized (CACHE_LOCK) {
            if (ver == dataVersion) {      // 期间没人改过，这份结果才可信
                cache = out;
                cacheVer = ver;
            }
        }
        return out;
    }

    private static List<ApiKey> parseAll(Context c) {
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
                k.rawKey = o.optString("key", "");
                k.key = KeyVault.dec(k.rawKey);
                k.note = o.optString("note", "");
                k.budget = o.optDouble("budget", 0);
                k.threshold = o.optDouble("threshold", 0);
                k.draw = o.optBoolean("draw", true);
                // 阿里云 AccessKey（新增字段）
                k.rawAccessKeyId = o.optString("accessKeyId", "");
                k.rawAccessKeySecret = o.optString("accessKeySecret", "");
                k.accessKeyId = KeyVault.dec(k.rawAccessKeyId);
                k.accessKeySecret = KeyVault.dec(k.rawAccessKeySecret);
                k.planMode = o.optString("planMode", "balance");
                k.hideCard = o.optBoolean("hideCard", false);
                k.baseUrl = o.optString("baseUrl", "");
                if (k.planMode == null || k.planMode.length() == 0) k.planMode = "balance";
                out.add(k);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** 保存全部（包内整理用：删除自定义平台后的 Key 重排要走这里） */
    public static void save(Context c, List<ApiKey> list) {
        /* 这里不再自增 dataVersion：自增会让刚写好的缓存立刻失效，
           下一次读又得把所有 Key 重新解密一遍。版本号由调用方（add/update/remove）负责推。 */
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < list.size(); i++) {
                ApiKey k = list.get(i);
                JSONObject o = new JSONObject();
                o.put("id", k.id);
                o.put("platform", k.platform);
                o.put("label", k.label);
                /* 解不开（key 空但原来有密文）→ 原样写回，绝不覆盖 */
                o.put("key", (k.key == null || k.key.length() == 0)
                        && k.rawKey != null && k.rawKey.length() > 0
                        ? k.rawKey : KeyVault.enc(k.key));
                o.put("note", k.note);
                o.put("budget", k.budget);
                o.put("threshold", k.threshold);
                o.put("draw", k.draw);
                // 阿里云 AccessKey（新增字段）
                o.put("accessKeyId", (k.accessKeyId == null || k.accessKeyId.length() == 0)
                        && k.rawAccessKeyId != null && k.rawAccessKeyId.length() > 0
                        ? k.rawAccessKeyId : KeyVault.enc(k.accessKeyId));
                o.put("accessKeySecret", (k.accessKeySecret == null || k.accessKeySecret.length() == 0)
                        && k.rawAccessKeySecret != null && k.rawAccessKeySecret.length() > 0
                        ? k.rawAccessKeySecret : KeyVault.enc(k.accessKeySecret));
                o.put("hideCard", k.hideCard);
                o.put("baseUrl", k.baseUrl == null ? "" : k.baseUrl);
                o.put("planMode", (k.planMode == null || k.planMode.length() == 0)
                        ? "balance" : k.planMode);
                arr.put(o);
            }
            /* ⚠️ 必须 commit：这是用户手录的密钥，apply() 异步写盘时
               若进程被回收，内存缓存已经是新值、磁盘还是旧值 ——
               表现就是"明明保存了，重启后没了"。几十条 Key 的序列化只有几毫秒。 */
            sp(c).edit().putString(K_KEYS, arr.toString()).commit();
            cache = new ArrayList<ApiKey>(list);   // 回填缓存，省掉下一次重解密
            cacheVer = dataVersion;
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
