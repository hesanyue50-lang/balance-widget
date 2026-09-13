package com.minis.balancewidget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 余额结果的本地缓存。
 *
 * 缓存的三个用途：
 *   1. 翻页不该重新联网（一次抓取要好几秒）
 *   2. App 更新 / 桌面重建时先把缓存画上去，立刻有内容，不用干等联网
 *   3. 弱网导致本轮全军覆没时，留着上一次的好数据，别刷成一片「—」
 *
 * 键名保持 cache_json / widget_page / last_at 不变 —— 旧版本的数据能直接用。
 */
public final class WidgetCache {

    private static final String K_JSON = "cache_json";
    private static final String K_PAGE = "widget_page";
    private static final String K_LAST_AT = "last_at";

    private WidgetCache() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
    }

    /** 读缓存。没有或解析失败都返回 null */
    public static BalanceFetcher.Result read(Context c) {
        try {
            return fromJson(sp(c).getString(K_JSON, ""));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 缓存是否值得拿来渲染（得有过成功结果） */
    public static boolean usable(BalanceFetcher.Result r) {
        return r != null && r.items.size() > 0 && r.configured > 0;
    }

    public static void save(Context c, BalanceFetcher.Result r, int page) {
        try {
            sp(c).edit()
                 .putString(K_JSON, toJson(r))
                 .putString("last_total", String.format("%.2f", r.totalCny))
                 .putLong(K_LAST_AT, System.currentTimeMillis())
                 .putInt(K_PAGE, page)
                 .apply();
        } catch (Throwable ignored) { }
    }

    public static int page(Context c) {
        try {
            return sp(c).getInt(K_PAGE, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void setPage(Context c, int p) {
        try {
            sp(c).edit().putInt(K_PAGE, p).apply();
        } catch (Throwable ignored) { }
    }

    /** 上次成功刷新的时刻（HH:mm），没有则空串 */
    public static String lastAt(Context c) {
        try {
            long t = sp(c).getLong(K_LAST_AT, 0);
            if (t == 0) return "";
            return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(t));
        } catch (Throwable t) {
            return "";
        }
    }

    /** 本轮成功抓到的平台数（用来判断值不值得覆盖缓存） */
    public static int okCount(BalanceFetcher.Result r) {
        if (r == null) return 0;
        int c = 0;
        for (int i = 0; i < r.items.size(); i++) {
            if (r.items.get(i).ok) c++;
        }
        return c;
    }

    // ---------- JSON ----------

    private static String toJson(BalanceFetcher.Result r) {
        try {
            JSONObject o = new JSONObject();
            o.put("rate", r.rate);
            o.put("total", r.totalCny);
            o.put("configured", r.configured);
            o.put("failed", r.failed);
            o.put("at", System.currentTimeMillis());
            JSONArray a = new JSONArray();
            for (int i = 0; i < r.items.size(); i++) {
                BalanceFetcher.Item it = r.items.get(i);
                JSONObject x = new JSONObject();
                x.put("id", it.id); x.put("platform", it.platform);
                x.put("label", it.label); x.put("tag", it.tag);
                x.put("amount", it.amount); x.put("cny", it.cny);
                x.put("ok", it.ok); x.put("error", it.error);
                x.put("bal", it.bal); x.put("low", it.low);
                x.put("kind", it.kind); x.put("conv", it.conv);
                x.put("rows", it.rows); x.put("alertKey", it.alertKey);
                x.put("threshold", it.threshold);
                a.put(x);
            }
            o.put("items", a);
            return o.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static BalanceFetcher.Result fromJson(String s) {
        if (s == null || s.length() == 0) return null;
        try {
            JSONObject o = new JSONObject(s);
            BalanceFetcher.Result r = new BalanceFetcher.Result();
            r.rate = o.optDouble("rate", 7.1);
            r.totalCny = o.optDouble("total", 0);
            r.configured = o.optInt("configured", 0);
            r.failed = o.optInt("failed", 0);
            JSONArray a = o.optJSONArray("items");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject x = a.optJSONObject(i);
                    if (x == null) continue;
                    BalanceFetcher.Item it = new BalanceFetcher.Item();
                    it.id = x.optString("id", "");
                    it.platform = x.optString("platform", "");
                    it.label = x.optString("label", "");
                    it.tag = x.optString("tag", "CNY"); it.amount = x.optString("amount", "");
                    it.cny = x.optDouble("cny", 0);
                    it.ok = x.optBoolean("ok", false); it.error = x.optString("error", "");
                    it.bal = x.optDouble("bal", 0); it.low = x.optBoolean("low", false);
                    it.kind = x.optString("kind", "balance");
                    it.conv = x.optString("conv", "");
                    it.rows = x.optString("rows", "");
                    it.alertKey = x.optString("alertKey", it.id);
                    it.threshold = x.optDouble("threshold", 0);
                    r.items.add(it);
                }
            }
            return r;
        } catch (Exception e) {
            return null;
        }
    }
}
