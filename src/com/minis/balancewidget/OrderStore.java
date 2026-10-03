package com.minis.balancewidget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排序存储。
 *
 * 卡片列表和统计页的折线顺序都由用户自己定 —— 常看的放前面。
 * 存的是**平台 id 的数组**（卡片按平台合并，一条平台一张卡），
 * 而不是 Key id：换 Key 不该打乱排好的顺序。
 *
 * 两套顺序分开存：
 *   order_cards  —— API 余额页的卡片顺序
 *   order_stats  —— 用量统计页的折线 / 开关顺序
 * 同一个平台在两个页面里的重要程度未必一样，分开更灵活。
 *
 * 排序是「软排序」：没存过的平台、新加的平台一律排在已排好的后面，
 * 保持它们在原始列表里的相对次序，不会因为排序把新平台弄丢。
 */
public final class OrderStore {

    public static final String CARDS = "order_cards";
    public static final String STATS = "order_stats";

    private OrderStore() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
    }

    /** 读取已保存的顺序（可能为空） */
    public static List<String> get(Context c, String which) {
        List<String> out = new ArrayList<String>();
        try {
            String s = sp(c).getString(which, "");
            if (s == null || s.length() == 0) return out;
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, "");
                if (v.length() > 0) out.add(v);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    public static void save(Context c, String which, List<String> ids) {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < ids.size(); i++) arr.put(ids.get(i));
            sp(c).edit().putString(which, arr.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /**
     * 按已保存的顺序重排 ids。没在顺序表里的（新平台）按原相对次序补在后面。
     * ids 里已不存在的（平台被删了）自然被忽略，不用特意清理。
     */
    public static List<String> apply(Context c, String which, List<String> ids) {
        List<String> order = get(c, which);
        if (order.isEmpty()) return new ArrayList<String>(ids);
        Map<String, String> pool = new LinkedHashMap<String, String>();
        for (int i = 0; i < ids.size(); i++) pool.put(ids.get(i), ids.get(i));
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < order.size(); i++) {
            String v = order.get(i);
            if (pool.remove(v) != null) out.add(v);
        }
        out.addAll(pool.values());          // 剩下的（新平台）维持原次序
        return out;
    }

    /** 同上，但是按 Item.platform 重排 Item 列表 */
    public static List<BalanceFetcher.Item> applyItems(Context c, String which,
                                                      List<BalanceFetcher.Item> items) {
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < items.size(); i++) ids.add(items.get(i).platform);
        List<String> sorted = apply(c, which, ids);
        List<BalanceFetcher.Item> out = new ArrayList<BalanceFetcher.Item>();
        for (int i = 0; i < sorted.size(); i++) {
            String plat = sorted.get(i);
            for (int j = 0; j < items.size(); j++) {
                if (items.get(j).platform.equals(plat)) { out.add(items.get(j)); break; }
            }
        }
        if (out.size() != items.size()) return items;   // 有异常就当没排序，别丢卡片
        return out;
    }
}
