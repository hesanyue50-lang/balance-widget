package com.minis.balancewidget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高级自定义平台：从一个网页 / 接口里抓一段数字或文本显示到卡片上。
 *
 * 与普通自定义平台（Custom）的区别：
 * - Custom 只会「GET 一个返回 JSON 的接口、按路径取数字」；
 * - WebCustom 支持任意方法、任意请求头、任意响应格式（JSON / HTML / 纯文本），
 *   用四种提取模式之一把值抠出来，还能套显示模板、乘倍率。
 *
 * 两种用法（同一份数据结构）：
 * - 简单模式：只填名字和网址，mode=auto —— 引擎按「JSON 关键字段 → 常见余额正则」
 *   的顺序自动识别，识别不出时报错并展示原文片段，引导用户改用高级模式；
 * - 高级模式：所有字段全部开放，给有基础的人折腾。
 *
 * 平台 id 形如 "web:0"，与 "custom:N" 互不干扰。
 */
public class WebCustom {

    // ---------------- 数据字段 ----------------

    public String name = "";
    public String url = "";

    /** 提取模式：auto / json / regex / between / text */
    public String mode = "auto";
    /** json 模式：点分路径，支持数组下标（data.balance / list.0.total），可用 | 分隔多候选 */
    public String path = "";
    /** regex 模式：正则；有捕获组取 group(1)，没有则取整个匹配 */
    public String pattern = "";
    /** between 模式：左右标记文本（左空=从头，右空=到尾） */
    public String start = "";
    public String end = "";

    /** HTTP 方法 GET / POST */
    public String method = "GET";
    /** POST 体（method=POST 时发送） */
    public String body = "";
    /** 附加请求头，多行「名字: 值」 */
    public String headers = "";
    /** 响应字符集（默认 UTF-8；老网页可能要 GBK） */
    public String charset = "";

    /**
     * 数字清洗：raw → double。
     * none 不清洗原文解析；strip 去千分位逗号；first 取正文第一个数字串。
     */
    public String numMode = "auto";
    /** 倍率：分→元填 0.01，千次单位填 0.001 之类 */
    public double scale = 1.0;

    /** 显示模板：{v} 会被替换成值；留空直接显示值 */
    public String template = "";

    /** 制式：balance=余额制（计入总资产）/ asset=额度制（只展示） */
    public String kind = "balance";
    /** 货币：CNY / USD / NONE（NONE=纯数值，如 次数、积分） */
    public String unit = "CNY";
    /** 显示后缀：unit=NONE 时有效（"% "、" 次"），挂在数字后面 */
    public String suffix = "";

    /** 境外网站：请求走代理 + 双倍超时预算 */
    public boolean foreign = false;
    /** 低值预警阈值（0 = 不预警） */
    public double threshold = 0;

    /**
     * API Key：可选。
     *
     * 高级平台本来是靠「自定义请求头 + 网页登录」过鉴权的，key 字段对抓取流程
     * 没有任何作用 —— 但用户可能想把它当一个**登记处**：
     * 记下这平台用的是哪个 Key、方便以后换设备或核对。
     * 所以这里只存不参与请求，界面上明说「仅记录，不发送」。
     */
    public String key = "";

    /**
     * 点卡片后跳转到哪个界面。
     *
     * 留空 = 保持默认（有 key 进密钥页，没有就原地不动）。
     * 可填：console（控制台）、recharge（充值）、home（主界面）、
     *       settings（设置）、stats（统计）、none（点了没反应）。
     */
    /**
     * 点卡片后弹出的按钮（最多 4 个）。
     *
     * 每个 = 一个自定义按钮：label 是按钮文字，url 点开后去的地址。
     * url 支持两类：
     * - 以 http/https 开头 → 直接用浏览器打开（控制台、充值页、工单页都行）
     * - 内置指令 app:home / app:settings / app:stats / app:refresh → 切到对应界面
     *
     * 全空时也要给用户一个「刷新」按钮 —— 点卡片至少能做点事，
     * 不能点了没反应（用户会以为 App 坏了）。
     */
    public String[] btnLabels = { "", "", "", "" };
    public String[] btnUrls = { "", "", "", "" };

    public boolean ready() {
        return name.length() > 0 && url.length() > 0;
    }

    // ---------------- 存取 ----------------

    private static final String KEY = "webcustom_json";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
    }

    public static List<WebCustom> loadAll(Context ctx) {
        List<WebCustom> list = new ArrayList<WebCustom>();
        try {
            JSONArray arr = new JSONArray(sp(ctx).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                WebCustom w = fromJson(o);
                if (w.ready()) list.add(w);
            }
        } catch (Exception ignored) { }
        return list;
    }

    public static void saveAll(Context ctx, List<WebCustom> list) {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < list.size(); i++) {
                arr.put(list.get(i).toJson());
            }
            /* 同步落盘：用户刚填完配置，apply() 异步写盘时若进程被回收会丢 */
            sp(ctx).edit().putString(KEY, arr.toString()).commit();
        } catch (Exception ignored) { }
    }

    /**
     * 序列化。**存储与导出共用这一份** —— 早先字段清单散在两处，
     * 加一个字段就得记得改两个地方，漏了就是"导出到别的设备少一截配置"。
     */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("url", url);
            o.put("mode", mode);
            o.put("path", path);
            o.put("pattern", pattern);
            o.put("start", start);
            o.put("end", end);
            o.put("method", method);
            o.put("body", body);
            o.put("headers", headers);
            o.put("charset", charset);
            o.put("key", key);
            o.put("numMode", numMode);
            o.put("scale", scale);
            o.put("template", template);
            o.put("kind", kind);
            o.put("unit", unit);
            o.put("suffix", suffix);
            o.put("foreign", foreign);
            o.put("threshold", threshold);
            org.json.JSONArray bl = new org.json.JSONArray();
            org.json.JSONArray bu = new org.json.JSONArray();
            for (int i = 0; i < 4; i++) {
                bl.put(btnLabels[i]);
                bu.put(btnUrls[i]);
            }
            o.put("btnLabels", bl);
            o.put("btnUrls", bu);
        } catch (Exception ignored) { }
        return o;
    }

    public static WebCustom fromJson(JSONObject o) {
        WebCustom w = new WebCustom();
        w.name = o.optString("name", "");
        w.url = o.optString("url", "");
        w.mode = o.optString("mode", "auto");
        w.path = o.optString("path", "");
        w.pattern = o.optString("pattern", "");
        w.start = o.optString("start", "");
        w.end = o.optString("end", "");
        w.method = o.optString("method", "GET");
        w.body = o.optString("body", "");
        w.headers = o.optString("headers", "");
        w.charset = o.optString("charset", "");
        w.key = o.optString("key", "");
        w.numMode = o.optString("numMode", "auto");
        w.scale = o.optDouble("scale", 1.0);
        w.template = o.optString("template", "");
        w.kind = o.optString("kind", "balance");
        w.unit = o.optString("unit", "CNY");
        w.suffix = o.optString("suffix", "");
        w.foreign = o.optBoolean("foreign", false);
        w.threshold = o.optDouble("threshold", 0);
        org.json.JSONArray bl = o.optJSONArray("btnLabels");
        org.json.JSONArray bu = o.optJSONArray("btnUrls");
        for (int i = 0; i < 4; i++) {
            if (bl != null) w.btnLabels[i] = bl.optString(i, "");
            if (bu != null) w.btnUrls[i] = bu.optString(i, "");
        }
        String legacy = o.optString("clickAction", "");
        if (legacy.length() > 0 && w.btnUrls[0].length() == 0) {
            w.btnUrls[0] = "app:" + legacy;
            w.btnLabels[0] = legacyLabel(legacy);
        }
        return w;
    }

    /** 旧版 clickAction 值 → 按钮文字（用于老数据迁移） */
    private static String legacyLabel(String act) {
        if ("console".equals(act)) return "控制台";
        if ("recharge".equals(act)) return "充值";
        if ("home".equals(act)) return "主界面";
        if ("settings".equals(act)) return "设置";
        if ("stats".equals(act)) return "统计";
        return "打开";
    }

    /**
     * 把「名字: 值」合进多行请求头里 —— 已存在同名就替换，没有就追加。
     *
     * 登录抓到新 Cookie 时用它落回 headers：不这样做的话每登一次就多一行
     * Cookie，头会越攒越长，而且旧的那条还可能是过期值。
     */
    public static String mergeHeader(String existing, String name, String value) {
        java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
        if (existing != null && existing.length() > 0) {
            String[] ps = existing.split("\n");
            for (int i = 0; i < ps.length; i++) {
                String ln = ps[i].trim();
                if (ln.length() == 0) continue;
                int c = ln.indexOf(':');
                /* 同名跳过：下面统一把新值加进去 */
                if (c > 0 && ln.substring(0, c).trim().equalsIgnoreCase(name)) continue;
                lines.add(ln);
            }
        }
        lines.add(name + ": " + value);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    // ---------------- 抓取引擎 ----------------

    /** 抓取结果：值 + 调试信息（识别方式），失败时抛异常 */
    public static class Result {
        public double value;        // 抠出来的数字（text 模式为 0）
        public String display;      // 模板渲染后的最终显示
        public String how;          // "json: data.balance" 这类调试说明
        public boolean numeric;     // 是数字（可预警/可折算）还是纯文本
    }

    /**
     * 抓取并提取。viaProxy=true 时请求走本应用内核的本地代理端口。
     *
     * 为什么不直接复用 BalanceFetcher.get()：那条链的请求头写死为
     * Bearer + Cookie 两项，高级模式要任意头；而且网页可能要 GBK 解码。
     * 这里独立用 HttpURLConnection 实现，经 Proxy 对象走内核 ——
     * 分流决定权照旧在 Clash.shouldProxy（平台级）。
     */
    public Result fetch(boolean viaProxy, int timeoutMs) throws Exception {
        String raw = http(viaProxy, timeoutMs);
        return extract(raw);
    }

    /** 提取（供「测试」按钮对已抓到的原文反复试） */
    public Result extract(String raw) throws Exception {
        if (raw == null) raw = "";
        String m = mode == null ? "auto" : mode;
        Result r;
        if ("json".equals(m))       r = byJson(raw);
        else if ("regex".equals(m)) r = byRegex(raw);
        else if ("between".equals(m)) r = byBetween(raw);
        else if ("text".equals(m))  r = byText(raw);
        else r = byAuto(raw);                          // auto：JSON → 正则 → 截取
        if (r == null) {
            throw new Exception("未能从页面识别出数值。原文开头："
                    + clip(raw.replaceAll("\\s+", " "), 160));
        }
        r.display = render(r);
        return r;
    }

    /** auto 模式：先按 JSON 试，再按正则试。返回 null 表示全部失败。 */
    private Result byAuto(String raw) throws Exception {
        String t = raw.trim();
        boolean looksJson = t.startsWith("{") || t.startsWith("[");
        if (looksJson) {
            try {
                Result r = byJson(raw);
                if (r != null) return r;
            } catch (Exception ignored) { }
        }
        try {
            Result r = byRegex(raw);
            if (r != null) return r;
        } catch (Exception ignored) { }
        /* 用户没填正则时的兜底：HTML 页面里第一串像金额的数字
           （带小数或千分位逗号优先，避免抓到时间戳/ID 这类长整数） */
        if (pattern == null || pattern.trim().length() == 0) {
            Matcher mm = Pattern.compile(
                    "\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+\\.\\d+").matcher(raw);
            if (mm.find()) {
                Double v = toNumber(mm.group());
                if (v != null) {
                    Result r = new Result();
                    r.value = v.doubleValue();
                    r.numeric = true;
                    r.how = "auto: 页面数字";
                    return r;
                }
            }
        }
        return null;
    }

    private Result byJson(String raw) throws Exception {
        String t = raw.trim();
        if (!(t.startsWith("{") || t.startsWith("["))) return null;
        JSONObject o = null;
        JSONArray a = null;
        try {
            if (t.startsWith("[")) a = new JSONArray(t); else o = new JSONObject(t);
        } catch (Exception e) {
            return null;                                // 不是合法 JSON，交给下一模式
        }
        /* path 支持多个候选，用 | 分隔依次试 */
        String[] cands = (path == null || path.trim().length() == 0)
                ? new String[0] : path.split("\\|");
        for (int ci = 0; ci < cands.length; ci++) {
            String p = cands[ci].trim();
            if (p.length() == 0) continue;
            Double v = jsonPath(o, a, p);
            if (v != null && !v.isNaN()) {
                Result r = new Result();
                r.value = v.doubleValue();
                r.numeric = true;
                r.how = "json: " + p;
                return r;
            }
        }
        /* 没填路径（或全没命中）→ 沿用普通自定义平台那套自动识别 */
        if (o != null) {
            double v = BalanceFetcher.pickForWeb(o);
            if (!Double.isNaN(v)) {
                Result r = new Result();
                r.value = v;
                r.numeric = true;
                r.how = "json: 自动识别";
                return r;
            }
        }
        return null;
    }

    /**
     * 点分路径求值。段可以是对象键，也可以是数组下标；
     * 根是数组时第一段按下标（或对象键落在数组元素上时取第一个非空）。
     */
    private static Double jsonPath(JSONObject o, JSONArray a, String p) {
        Object cur = (o != null) ? o : a;
        String[] parts = p.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            String seg = parts[i].trim();
            if (cur == null) return null;
            if (cur instanceof JSONArray) {
                JSONArray ja = (JSONArray) cur;
                int idx;
                try { idx = Integer.parseInt(seg); } catch (Exception e) {
                    /* 键落在数组上：找第一个含该键的元素 */
                    Object hit = null;
                    for (int k = 0; k < ja.length() && hit == null; k++) {
                        JSONObject eo = ja.optJSONObject(k);
                        if (eo != null && eo.has(seg)) hit = eo;
                    }
                    cur = hit;
                    continue;
                }
                if (idx < 0 || idx >= ja.length()) return null;
                cur = ja.opt(idx);
                continue;
            }
            JSONObject jo = (JSONObject) cur;
            if (!jo.has(seg)) return null;
            cur = jo.opt(seg);
        }
        if (cur instanceof Number) return ((Number) cur).doubleValue();
        if (cur instanceof String) {
            try { return Double.parseDouble((String) cur); } catch (Exception e) { return null; }
        }
        if (cur instanceof Boolean) return (Boolean) cur ? 1.0 : 0.0;
        return null;
    }

    private Result byRegex(String raw) throws Exception {
        if (pattern == null || pattern.trim().length() == 0) return null;
        java.util.regex.Matcher mm;
        try {
            mm = Pattern.compile(pattern).matcher(raw);
        } catch (Exception e) {
            /* 正则写错（比如少个括号）要给人话，不能混进"未识别"里让人猜 */
            throw new Exception("正则表达式有误：" + e.getMessage());
        }
        if (!mm.find()) return null;
        String s = mm.groupCount() > 0 ? mm.group(1) : mm.group(0);
        Double v = toNumber(s);
        if (v == null) return null;
        Result r = new Result();
        r.value = v.doubleValue();
        r.numeric = true;
        r.how = "regex: " + (pattern.length() > 24 ? pattern.substring(0, 24) + "…" : pattern);
        return r;
    }

    private Result byBetween(String raw) throws Exception {
        if ((start == null || start.length() == 0)
                && (end == null || end.length() == 0)) return null;
        String s = raw;
        int from = 0;
        if (start != null && start.length() > 0) {
            int i = s.indexOf(start);
            if (i < 0) return null;
            from = i + start.length();
            s = s.substring(Math.min(from, s.length()));
            from = i;
        }
        if (end != null && end.length() > 0) {
            int j = s.indexOf(end);
            if (j < 0) return null;
            s = s.substring(0, j);
        }
        /* 截出来的片段里抠数字（"余额: 12.34 元" 这类） */
        Double v = toNumber(s.trim());
        if (v == null) return null;
        Result r = new Result();
        r.value = v.doubleValue();
        r.numeric = true;
        r.how = "between: " + clip(start, 12) + " … " + clip(end, 12);
        return r;
    }

    /** text 模式：整页当文本展示（静态公告牌），不做数字处理 */
    private Result byText(String raw) throws Exception {
        Result r = new Result();
        r.numeric = false;
        r.value = 0;
        String s = raw.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (s.length() == 0) s = "（空）";
        r.how = "text";
        r.display = s;
        return r;
    }

    /** 模板渲染 + 单位/后缀/倍率统一在这里收口 */
    private String render(Result r) {
        if (!r.numeric) return r.display;               // text 模式已定
        double v = r.value * (scale == 0 ? 1.0 : scale);
        r.value = v;                                    // 倍率后的值写回（预警/折算都用它）
        String s;
        if (v == Math.floor(v) && !Double.isInfinite(v)
                && Math.abs(v) < 1e15) {
            s = String.valueOf((long) v);
        } else {
            s = String.format(java.util.Locale.US, "%.2f", v);
        }
        String suff = suffix == null ? "" : suffix;
        if ("NONE".equals(unit) && suff.length() > 0) {
            s = s + suff;
        } else if ("USD".equals(unit)) {
            s = "$" + s;
        } else if ("CNY".equals(unit)) {
            s = "¥" + s;
        }
        if (template != null && template.length() > 0) {
            s = template.replace("{v}", s);
        }
        return s;
    }

    /** 从一段文本里抠数字：auto = 去逗号直接 parse，失败取第一个数字串 */
    private static Double toNumber(String s) {
        if (s == null) return null;
        String t = s.trim().replace(",", "").replace("，", "").replace(" ", "");
        if (t.length() == 0) return null;
        try { return Double.parseDouble(t); } catch (Exception e) { }
        Matcher mm = Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(s);
        if (mm.find()) {
            try { return Double.parseDouble(mm.group()); } catch (Exception e) { }
        }
        return null;
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    // ---------------- HTTP ----------------

    private String http(boolean viaProxy, int timeoutMs) throws Exception {
        URL u = new URL(url);
        HttpURLConnection c;
        if (viaProxy) {
            Proxy px = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress("127.0.0.1", Clash.PROXY_PORT));
            c = (HttpURLConnection) u.openConnection(px);
        } else {
            c = (HttpURLConnection) u.openConnection();
        }
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        String mth = (method == null || method.length() == 0) ? "GET" : method.toUpperCase();
        c.setRequestMethod(mth);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "identity");
        /* 自定义请求头：多行「名字: 值」，允许覆盖上面的默认值 */
        if (headers != null && headers.trim().length() > 0) {
            String[] lines = headers.split("\n");
            for (int i = 0; i < lines.length; i++) {
                int p = lines[i].indexOf(':');
                if (p <= 0) continue;
                String k = lines[i].substring(0, p).trim();
                String v = lines[i].substring(p + 1).trim();
                if (k.length() > 0 && v.length() > 0) {
                    try { c.setRequestProperty(k, v); } catch (Exception ig) { }
                }
            }
        }
        boolean hasBody = mth.equals("POST") || mth.equals("PUT") || mth.equals("PATCH");
        if (hasBody) {
            /* POST/PUT 即使没填 body 也要走输出流（有些服务端对无体 POST 才认），
               Content-Type 在这里兜底 —— 自定义头里填了就以用户的为准 */
            byte[] b = (body == null || body.length() == 0) ? new byte[0] : body.getBytes("UTF-8");
            c.setDoOutput(true);
            if (c.getRequestProperty("Content-Type") == null) {
                c.setRequestProperty("Content-Type", "application/json");
            }
            c.setFixedLengthStreamingMode(b.length);
            OutputStream os = c.getOutputStream();
            if (b.length > 0) os.write(b);
            os.flush();
            os.close();
        }
        int code = c.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        String cs = (charset == null || charset.trim().length() == 0) ? "UTF-8" : charset.trim();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            BufferedReader r;
            try {
                r = new BufferedReader(new InputStreamReader(is, cs));
            } catch (Exception e) {
                r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            }
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
        }
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + "，响应开头：" + clip(sb.toString(), 120));
        }
        return sb.toString();
    }
}
