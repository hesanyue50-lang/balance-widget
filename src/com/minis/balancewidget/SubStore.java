package com.minis.balancewidget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * VPN 订阅管理（可同时保存多个机场 / 多份订阅，随时切换启用）。
 *
 * 一个「订阅」= 一份 Clash 配置来源，字段：
 *   id / name（用户起的名字）/ url / yaml（下载到的配置正文）/ updatedAt / nodeCount
 *
 * 为什么把 yaml 正文也存下来（而不只是存 url）：
 *   机场的订阅地址常有**请求次数限制**，而且断网时也希望能切回已下载的节点。
 *   正文存在本地，启用时直接用，不必每次重新下载 —— 只有用户主动点「更新订阅」才联网。
 *
 * 存储：SharedPreferences 里一段 JSON 数组（订阅数量通常在个位数，
 * 用不着上 SQLite；而且这些内容要跟着应用数据一起被清除，prefs 更合适）。
 */
public final class SubStore {

    private static final String K_SUBS = "clash_subs";
    private static final String K_ACTIVE = "clash_active_sub";

    /** 一份订阅 */
    public static class Sub {
        public String id = "";
        public String name = "";
        public String url = "";
        /** 已下载的配置正文（启用时直接用，不联网） */
        public String yaml = "";
        /**
         * 上一版能用的配置。
         *
         * 为什么要留：下载可能拿到"看起来正常但实际残缺"的正文（机场限流时常见），
         * 覆盖之后内核就起不来；而重新下载订阅往往又**必须经过代理** ——
         * 代理正是起不来的那个，于是彻底卡死。
         * 有这一份就能在内核启动失败时自动回滚，不用用户去别的客户端折腾。
         */
        public String prevYaml = "";
        public long updatedAt = 0;
        public int nodeCount = 0;
        /** 机场流量（字节；-1 = 机场没提供）。expire 是秒级时间戳 */
        public long up = -1, down = -1, total = -1, expire = -1;
        /** 流量信息的取得时间（下载订阅时一并更新） */
        public long trafficAt = 0;
        /** 自动更新：打开应用时若距上次更新超过 AUTO_UPDATE_GAP，就自动刷一次 */
        public boolean autoUpdate = false;

        /** 自动更新的最小间隔：6 小时。订阅里换节点又不是天天换，更勤没意义还费流量 */
        public static final long AUTO_UPDATE_GAP = 6L * 60 * 60 * 1000;

        public boolean hasTraffic() { return total > 0; }

        public long usedBytes() { return Math.max(0, up) + Math.max(0, down); }

        public long leftBytes() {
            if (total <= 0) return -1;
            return Math.max(0, total - usedBytes());
        }

        public boolean ready() {
            return yaml != null && yaml.length() > 0;
        }

        /** 给界面用的一行摘要 */
        public String summary() {
            if (!ready()) return "尚未下载配置";
            String t = updatedAt <= 0 ? "" : ("  ·  更新于 " + fmtTime(updatedAt));
            return nodeCount + " 个节点" + t;
        }
    }

    private SubStore() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
    }

    // ---------- 读 ----------

    /* 订阅存档里含每个订阅的 yaml **全文**（几十上百 KB），每次 all() 都要
       完整 JSON 解析一遍。而进入加速页的一轮渲染里 all() 会被调好几次
       （all + active→byId→all），纯属重复劳动。用写入版本号做失效判断。 */
    /**
     * 只看「有没有订阅存档」，不解析 JSON。
     *
     * onStartCommand 在**主线程**跑，而 all() 要把含 yaml 全文的存档完整解析一遍；
     * 那里只是想知道"该不该继续启动"，用这个轻量判断就够，真正的解析交给后台线程。
     */
    public static boolean hasAny(Context c) {
        try {
            String s = sp(c).getString(K_SUBS, "");
            return s != null && s.length() > 2;
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<Sub> cacheList;
    private static long cacheVer = -1;

    public static List<Sub> all(Context c) {
        long ver = -1;
        try {
            /* 用 getAll() 拿原类型再转 —— 备份恢复后这里可能是 Integer，
               直接 getLong 会抛 ClassCastException，导致 ver 恒为 -1、
               缓存被永久钉死（订阅改了看不到）。 */
            Object o = sp(c).getAll().get("subs_ver");
            if (o instanceof Number) ver = ((Number) o).longValue();
            else if (o != null) ver = 0;
        } catch (Throwable ignored) { }
        if (cacheList != null && ver == cacheVer) return new ArrayList<Sub>(cacheList);
        List<Sub> out = new ArrayList<Sub>();
        try {
            String s = sp(c).getString(K_SUBS, "");
            if (s == null || s.length() == 0) return out;
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                Object o = arr.opt(i);
                if (!(o instanceof JSONObject)) continue;
                JSONObject j = (JSONObject) o;
                Sub sub = new Sub();
                sub.id = j.optString("id", "");
                sub.name = j.optString("name", "");
                sub.url = j.optString("url", "");
                sub.yaml = j.optString("yaml", "");
                sub.prevYaml = j.optString("prevYaml", "");
                sub.updatedAt = j.optLong("updatedAt", 0);
                sub.nodeCount = j.optInt("nodeCount", 0);
                sub.up = j.optLong("up", -1);
                sub.down = j.optLong("down", -1);
                sub.total = j.optLong("total", -1);
                sub.expire = j.optLong("expire", -1);
                sub.trafficAt = j.optLong("trafficAt", 0);
                sub.autoUpdate = j.optBoolean("autoUpdate", false);
                if (sub.id.length() == 0) continue;
                out.add(sub);
            }
        } catch (Throwable ignored) { }
        cacheList = out;
        cacheVer = ver;
        return new ArrayList<Sub>(out);
    }

    public static Sub byId(Context c, String id) {
        if (id == null) return null;
        List<Sub> l = all(c);
        for (int i = 0; i < l.size(); i++) if (id.equals(l.get(i).id)) return l.get(i);
        return null;
    }

    /** 当前启用的订阅 id（没有则空串） */
    public static String activeId(Context c) {
        return sp(c).getString(K_ACTIVE, "");
    }

    public static Sub active(Context c) {
        return byId(c, activeId(c));
    }

    public static void setActive(Context c, String id) {
        sp(c).edit().putString(K_ACTIVE, id == null ? "" : id).commit();
    }

    /**
     * 当前生效的订阅地址（兼容旧版本：早期只有单订阅，存在 clash_sub_url 里）。
     * 界面上「订阅链接」输入框显示的就是它。
     */
    public static String activeUrl(Context c) {
        Sub a = active(c);
        if (a != null && a.url.length() > 0) return a.url;
        return sp(c).getString("clash_sub_url", "");
    }

    // ---------- 写 ----------

    /** 新增一份订阅，返回它的 id */
    public static String add(Context c, String name, String url) {
        List<Sub> l = all(c);
        Sub s = new Sub();
        s.id = UUID.randomUUID().toString().substring(0, 8);
        s.name = (name == null || name.trim().length() == 0) ? "订阅 " + (l.size() + 1) : name.trim();
        s.url = url == null ? "" : url.trim();
        l.add(s);
        save(c, l);
        if (activeId(c).length() == 0) setActive(c, s.id);   // 第一个订阅自动设为启用
        return s.id;
    }

    public static void update(Context c, Sub sub) {
        if (sub == null) return;
        List<Sub> l = all(c);
        for (int i = 0; i < l.size(); i++) {
            if (!l.get(i).id.equals(sub.id)) continue;
            Sub old = l.get(i);
            /* 正文要变了 → 先把旧的那份挪到 prevYaml。
               旧 yaml 为空（首次下载）或新的和旧的一样（只改了名字/开关）就不用动。 */
            if (sub.yaml != null && sub.yaml.length() > 0
                    && old.yaml != null && old.yaml.length() > 0
                    && !old.yaml.equals(sub.yaml)) {
                sub.prevYaml = old.yaml;
            } else if (sub.prevYaml == null || sub.prevYaml.length() == 0) {
                sub.prevYaml = old.prevYaml;
            }
            l.set(i, sub);
            break;
        }
        save(c, l);
    }

    /** 内核起不来时回滚到上一版配置；没有可回滚的返回 false */
    public static boolean rollback(Context c, String id) {
        Sub s = byId(c, id);
        if (s == null) return false;
        if (s.prevYaml == null || s.prevYaml.length() < 200) return false;
        String bad = s.yaml;
        s.yaml = s.prevYaml;
        s.prevYaml = "";          // 只能用一次，避免来回横跳
        s.nodeCount = Clash.countProxies(s.yaml);
        s.updatedAt = System.currentTimeMillis();
        update(c, s);
        BalanceFetcher.diag(c, "配置回滚：用上一版 " + s.yaml.length()
                + " 字节替换掉 " + (bad == null ? 0 : bad.length()) + " 字节");
        return true;
    }

    public static void remove(Context c, String id) {
        List<Sub> l = all(c);
        for (int i = l.size() - 1; i >= 0; i--) {
            if (l.get(i).id.equals(id)) l.remove(i);
        }
        save(c, l);
        if (id != null && id.equals(activeId(c))) {
            setActive(c, l.isEmpty() ? "" : l.get(0).id);    // 删掉启用的，自动切到第一份
        }
    }

    /** 重命名 */
    public static void rename(Context c, String id, String name) {
        Sub s = byId(c, id);
        if (s == null) return;
        s.name = name == null ? "" : name.trim();
        if (s.name.length() == 0) s.name = "未命名";
        update(c, s);
    }

    private static void save(Context c, List<Sub> l) {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < l.size(); i++) {
                Sub s = l.get(i);
                JSONObject j = new JSONObject();
                j.put("id", s.id);
                j.put("name", s.name);
                j.put("url", s.url);
                j.put("yaml", s.yaml == null ? "" : s.yaml);
                if (s.prevYaml != null && s.prevYaml.length() > 0) {
                    j.put("prevYaml", s.prevYaml);
                }
                j.put("updatedAt", s.updatedAt);
                j.put("nodeCount", s.nodeCount);
                j.put("up", s.up);
                j.put("down", s.down);
                j.put("total", s.total);
                j.put("expire", s.expire);
                j.put("trafficAt", s.trafficAt);
                j.put("autoUpdate", s.autoUpdate);
                arr.put(j);
            }
            SharedPreferences.Editor ed = sp(c).edit();
            ed.putString(K_SUBS, arr.toString());
            /* 不读旧值直接自增会踩类型坑（见上）；用时间戳当版本号，单调且类型永远对 */
            ed.putLong("subs_ver", System.currentTimeMillis());
            ed.commit();
        } catch (Throwable ignored) { }
    }

    // ---------- 迁移 ----------

    /**
     * 把旧版的「单订阅」搬进新结构（只在第一次调用时做一次）。
     * 旧版把配置正文写在 files/clash/sub.yaml、地址写在 clash_sub_url。
     */
    public static void migrateIfNeeded(Context c) {
        try {
            if (sp(c).getBoolean("clash_subs_migrated", false)) return;
            List<Sub> l = all(c);
            if (!l.isEmpty()) {
                sp(c).edit().putBoolean("clash_subs_migrated", true).commit();
                return;
            }
            String oldUrl = sp(c).getString("clash_sub_url", "");
            String yaml = Clash.readText(new java.io.File(Clash.workDir(c), "sub.yaml"));
            if (oldUrl.length() == 0 && yaml.length() == 0) {
                sp(c).edit().putBoolean("clash_subs_migrated", true).commit();
                return;
            }
            Sub s = new Sub();
            s.id = UUID.randomUUID().toString().substring(0, 8);
            s.name = "默认订阅";
            s.url = oldUrl;
            s.yaml = yaml;
            s.nodeCount = Clash.countProxies(yaml);
            s.updatedAt = yaml.length() > 0 ? System.currentTimeMillis() : 0;
            l.add(s);
            save(c, l);
            setActive(c, s.id);
            sp(c).edit().putBoolean("clash_subs_migrated", true).commit();
            BalanceFetcher.diag(c, "Clash 订阅已迁移到多订阅结构");
        } catch (Throwable t) {
            BalanceFetcher.diag(c, "订阅迁移失败 " + t);
        }
    }

    private static String fmtTime(long ms) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US);
        return f.format(new java.util.Date(ms));
    }
}
