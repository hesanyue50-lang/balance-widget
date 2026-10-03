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
        public long updatedAt = 0;
        public int nodeCount = 0;

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

    public static List<Sub> all(Context c) {
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
                sub.updatedAt = j.optLong("updatedAt", 0);
                sub.nodeCount = j.optInt("nodeCount", 0);
                if (sub.id.length() == 0) continue;
                out.add(sub);
            }
        } catch (Throwable ignored) { }
        return out;
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
        sp(c).edit().putString(K_ACTIVE, id == null ? "" : id).apply();
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
            if (l.get(i).id.equals(sub.id)) { l.set(i, sub); break; }
        }
        save(c, l);
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
                j.put("updatedAt", s.updatedAt);
                j.put("nodeCount", s.nodeCount);
                arr.put(j);
            }
            sp(c).edit().putString(K_SUBS, arr.toString()).apply();
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
                sp(c).edit().putBoolean("clash_subs_migrated", true).apply();
                return;
            }
            String oldUrl = sp(c).getString("clash_sub_url", "");
            String yaml = Clash.readText(new java.io.File(Clash.workDir(c), "sub.yaml"));
            if (oldUrl.length() == 0 && yaml.length() == 0) {
                sp(c).edit().putBoolean("clash_subs_migrated", true).apply();
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
            sp(c).edit().putBoolean("clash_subs_migrated", true).apply();
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
