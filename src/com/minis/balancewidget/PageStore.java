package com.minis.balancewidget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 页面管理：显示顺序 / 隐藏 / 主页面。
 *
 * 四个页面（API 余额、用量统计、网络加速、设置）允许：
 *   - **排序**：调整它们在导航条里的先后；
 *   - **隐藏**：不常用的直接收起来，导航条自动适配（不是留个空位）；
 *   - **设为主页面**：打开应用直接进那一页，并把它挪到最左侧。
 *
 * 存储用「页面 key 数组」而不是索引 —— 索引会随增删页面而错位，
 * key 是稳定的。没记录的 key 按内置次序补在后面，这样以后新增页面
 * 不会因为旧存档而消失。
 */
public final class PageStore {

    /** 内置次序（也是默认顺序） */
    public static final String K_API = "api";
    public static final String K_STATS = "stats";
    public static final String K_VPN = "vpn";
    public static final String K_SETTINGS = "settings";

    private static final String[] KEYS = { K_API, K_STATS, K_VPN, K_SETTINGS };
    private static final String[] NAMES = { "API 余额", "用量统计", "网络加速", "设置" };

    private static final String P_ORDER = "page_order";
    private static final String P_HIDDEN = "page_hidden";
    private static final String P_HOME = "page_home";

    private PageStore() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
    }

    public static String nameOf(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return NAMES[i];
        return key;
    }

    public static String[] allKeys() {
        return KEYS.clone();
    }

    // ---------- 顺序 ----------

    /**
     * 完整显示顺序（含被隐藏的）。存档里缺的 key 按内置次序补在后面 ——
     * 这样版本升级新增页面时，老用户的存档不会让新页面"消失"。
     */
    public static List<String> order(Context c) {
        List<String> saved = readArray(c, P_ORDER);
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (int i = 0; i < saved.size(); i++) {
            String k = saved.get(i);
            if (isKnown(k)) out.add(k);
        }
        for (int i = 0; i < KEYS.length; i++) out.add(KEYS[i]);
        return new ArrayList<String>(out);
    }

    public static void saveOrder(Context c, List<String> order) {
        writeArray(c, P_ORDER, order);
    }

    // ---------- 隐藏 ----------

    public static Set<String> hidden(Context c) {
        Set<String> out = new LinkedHashSet<String>();
        List<String> l = readArray(c, P_HIDDEN);
        for (int i = 0; i < l.size(); i++) {
            String k = l.get(i);
            /* 顺手纠正历史存档里"设置页被隐藏"的状态 —— 旧版本没拦这个，
               不修的话用户会卡在没有入口的死局里。 */
            if (isKnown(k) && hideable(k)) out.add(k);
        }
        return out;
    }

    public static boolean isHidden(Context c, String key) {
        return hidden(c).contains(key);
    }

    /**
     * 这一页能不能被隐藏。
     *
     * **设置页恒定不可隐藏**：隐藏相关的开关本身就在设置页里，
     * 一旦把设置藏起来，用户就再也找不到地方把它放出来了（死锁）。
     */
    public static boolean hideable(String key) {
        return !K_SETTINGS.equals(key);
    }

    public static void setHidden(Context c, String key, boolean hide) {
        if (hide && !hideable(key)) return;              // 设置页不允许隐藏
        Set<String> h = hidden(c);
        if (hide) h.add(key); else h.remove(key);
        // 至少要留一页，否则应用打开就是空白
        if (h.size() >= KEYS.length) return;
        writeArray(c, P_HIDDEN, new ArrayList<String>(h));
        /* 主页面被藏起来了：自动把主页面改成第一个可见页，否则打开应用找不到落点 */
        if (hide && key.equals(home(c))) {
            List<String> vis = visibleOrder(c);
            if (!vis.isEmpty()) setHome(c, vis.get(0));
        }
    }

    /** 只保留可见页的顺序 */
    public static List<String> visibleOrder(Context c) {
        Set<String> h = hidden(c);
        List<String> out = new ArrayList<String>();
        List<String> o = order(c);
        for (int i = 0; i < o.size(); i++) {
            if (!h.contains(o.get(i))) out.add(o.get(i));
        }
        return out;
    }

    // ---------- 主页面 ----------

    /** 主页面 key（打开应用直接进这一页）。非法或已隐藏时回退到第一个可见页。 */
    public static String home(Context c) {
        String h = sp(c).getString(P_HOME, K_API);
        List<String> vis = visibleOrder(c);
        if (vis.contains(h)) return h;
        return vis.isEmpty() ? K_API : vis.get(0);
    }

    /**
     * 设为主页面。
     *
     * 「设为主页面」和「挪到最左侧」是同一件事的两面 —— 用户要的是
     * 打开应用就落在这一页，而它在导航条上排第一才符合直觉，
     * 所以这里同时把它移到顺序表最前面，不做成两个设置项。
     */
    public static void setHome(Context c, String key) {
        if (!isKnown(key)) return;
        sp(c).edit().putString(P_HOME, key).apply();
        List<String> o = order(c);
        o.remove(key);
        o.add(0, key);
        saveOrder(c, o);
    }

    /** 主页面对应的导航序号（在可见页里的下标） */
    public static int homeIndex(Context c) {
        List<String> vis = visibleOrder(c);
        int i = vis.indexOf(home(c));
        return i < 0 ? 0 : i;
    }

    // ---------- 内部 ----------

    private static boolean isKnown(String k) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(k)) return true;
        return false;
    }

    private static List<String> readArray(Context c, String key) {
        List<String> out = new ArrayList<String>();
        try {
            String s = sp(c).getString(key, "");
            if (s == null || s.length() == 0) return out;
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                String v = a.optString(i, "");
                if (v.length() > 0) out.add(v);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static void writeArray(Context c, String key, List<String> vals) {
        try {
            JSONArray a = new JSONArray();
            for (int i = 0; i < vals.size(); i++) a.put(vals.get(i));
            sp(c).edit().putString(key, a.toString()).apply();
        } catch (Throwable ignored) { }
    }
}
