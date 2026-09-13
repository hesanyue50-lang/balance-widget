package com.minis.balancewidget;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** 纯代码查询各家 API 余额，不调用任何 AI 接口。 */
public class BalanceFetcher {

    public static final String PREFS = "balance_widget";
    private static final String TAG = "BalanceWidget";

    /**
     * 内置服务商预设。**加一家服务商只要在这里加一行** ——
     * 以前是七个平行数组，漏改一个就数组越界闪退，这张表把那个坑彻底干掉了。
     *
     * url 留空 = 走专门解析（DeepSeek / OpenRouter / 七牛云 / 魔搭这些结构特殊）；
     * url 非空 = 走通用解析，用 paths 里的候选字段逐个试。
     */
    public static class Preset {
        public final String id, name, url, paths, unit, kind, hint;
        /** 官网控制台（账单 / 用量 / Key 管理的入口） */
        public final String console;
        /** 充值页（能直达就直达，直达不了就填控制台首页） */
        public final String topup;
        public Preset(String id, String name, String url, String paths,
                      String unit, String kind, String hint) {
            this(id, name, url, paths, unit, kind, hint, "", "");
        }
        public Preset(String id, String name, String url, String paths,
                      String unit, String kind, String hint, String console) {
            this(id, name, url, paths, unit, kind, hint, console, console);
        }
        public Preset(String id, String name, String url, String paths,
                      String unit, String kind, String hint,
                      String console, String topup) {
            this.id = id; this.name = name; this.url = url; this.paths = paths;
            this.unit = unit; this.kind = kind; this.hint = hint;
            this.console = console;
            this.topup = topup;
        }
    }

    /** 通用解析的候选字段：各家命名不统一，按顺序试到命中为止 */
    private static final String BAL_PATHS =
        "data.balance|balance|data.available_balance|available_balance"
        + "|data.total_balance|total_balance|data.remain|remain|remaining"
        + "|data.credit|credit|data.amount|amount|data.quota|quota"
        + "|data.available|available|data.money|money|data.balance_amount";

    public static final Preset[] PRESETS = {
        new Preset("deepseek", "DeepSeek", "", "", "CNY", "balance",
                   "platform.deepseek.com → API Keys",
                   "https://platform.deepseek.com/usage",
                   "https://platform.deepseek.com/top_up"),
        new Preset("openrouter", "OpenRouter", "", "", "USD", "balance",
                   "openrouter.ai → Keys。余额 = 累计充值 − 已用",
                   "https://openrouter.ai/settings/keys",
                   "https://openrouter.ai/credits"),
        new Preset("qiniu", "七牛云 AI", "", "", "消费", "usage",
                   "portal.qiniu.com → AI 推理 → API Key。后付费，显示本月消费",
                   "https://portal.qiniu.com/financial/balance",
                   "https://portal.qiniu.com/financial/balance"),
        new Preset("siliconflow", "硅基流动", "", "", "CNY", "balance",
                   "cloud.siliconflow.cn → API 密钥",
                   "https://cloud.siliconflow.cn/account/balance",
                   "https://cloud.siliconflow.cn/account/topup"),
        new Preset("moonshot", "月之暗面 Moonshot", "", "", "CNY", "balance",
                   "platform.moonshot.cn → API Key 管理",
                   "https://platform.moonshot.cn/console/user_info",
                   "https://platform.moonshot.cn/console/topup"),
        new Preset("novita", "Novita AI", "", "", "USD", "balance",
                   "novita.ai → Settings → API Keys",
                   "https://novita.ai/billing",
                   "https://novita.ai/billing"),
        new Preset("fireworks", "Fireworks AI", "", "", "USD", "balance",
                   "fireworks.ai → API Keys",
                   "https://fireworks.ai/account/billing",
                   "https://fireworks.ai/account/billing"),
        new Preset("zhipu", "智谱 AI", "https://open.bigmodel.cn/api/paas/v4/user/balance",
                   BAL_PATHS, "CNY", "balance", "bigmodel.cn → API Keys",
                   "https://bigmodel.cn/finance",
                   "https://bigmodel.cn/finance/recharge"),
        /* 阿里云百炼：余额挂在阿里云账户上，DashScope 侧没有公开的余额接口
           （所有 /user/balance、/quota 之类路径实测均 404，只有 /v1/models 返回 401）。
           所以这里 url 留空 —— 走静态展示，只提示去控制台看。
           真要自动查余额，得用阿里云 BSS OpenAPI 的 QueryAccountBalance，
           那需要 AccessKey 签名，跟 DashScope 的 sk- 令牌是两套东西。 */
        new Preset("dashscope", "阿里云百炼", "", "", "-", "sub",
                   "百炼控制台 → API-KEY 管理。计费走预付费资源包，用量与剩余额度在控制台查看",
                   "https://bailian.console.aliyun.com/",
                   "https://usercenter2.aliyun.com/finance/expense-report/expense-bill"),
        new Preset("compshare", "优云智算", "https://api.compshare.cn/v1/user/balance",
                   BAL_PATHS, "CNY", "balance", "compshare.cn → API 密钥",
                   "https://www.compshare.cn/console",
                   "https://www.compshare.cn/console"),
        new Preset("volc", "火山方舟", "https://ark.cn-beijing.volces.com/api/v3/user/balance",
                   BAL_PATHS, "CNY", "balance", "火山引擎 → 方舟控制台 → API Key",
                   "https://console.volcengine.com/ark",
                   "https://console.volcengine.com/billing"),
        new Preset("spark", "讯飞星火", "https://spark-api-open.xf-yun.com/v1/user/balance",
                   BAL_PATHS, "CNY", "balance", "console.xfyun.cn → 星火 → APIKey",
                   "https://console.xfyun.cn/",
                   "https://console.xfyun.cn/services"),
        new Preset("internlm", "书生 InternLM", "https://internlm.intern-ai.org.cn/api/v1/user/balance",
                   BAL_PATHS, "CNY", "balance", "internlm.intern-ai.org.cn → API Key",
                   "https://internlm.intern-ai.org.cn/",
                   "https://internlm.intern-ai.org.cn/console"),
        new Preset("stepfun", "阶跃星辰", "https://api.stepfun.com/v1/accounts",
                   BAL_PATHS, "CNY", "balance", "platform.stepfun.com → API Key",
                   "https://platform.stepfun.com/",
                   "https://platform.stepfun.com/console/topup"),
        new Preset("modelscope", "魔搭 ModelScope", "", "", "-", "free",
                   "免费服务，没有余额接口，只作展示",
                   "https://modelscope.cn/my/overview",
                   "https://modelscope.cn/my/overview"),
    };

    private static final int[] PALETTE = {
        0xFF4D6BFE, 0xFF8B5CF6, 0xFF0EA5E9, 0xFF22C55E, 0xFFF97316,
        0xFF06B6D4, 0xFFEC4899, 0xFF10B981, 0xFFF59E0B, 0xFF6366F1,
        0xFFEF4444, 0xFF14B8A6, 0xFFA855F7, 0xFF84CC16
    };

    /** 下面几张表全部由 PRESETS 派生，老代码照旧用它们 */
    public static final String[] IDS, NAMES, UNITS, PLACEHOLDERS, HINTS, KINDS;
    public static final int[] COLORS;

    /** 显示顺序：余额 → 订阅 → 免费额度 → 后付费账单 */
    public static final String[] KIND_ORDER = { "balance", "sub", "free", "usage" };

    static {
        int n = PRESETS.length;
        IDS = new String[n]; NAMES = new String[n]; UNITS = new String[n];
        PLACEHOLDERS = new String[n]; HINTS = new String[n]; KINDS = new String[n];
        COLORS = new int[n];
        for (int i = 0; i < n; i++) {
            Preset p = PRESETS[i];
            IDS[i] = p.id; NAMES[i] = p.name; UNITS[i] = p.unit;
            HINTS[i] = p.hint; KINDS[i] = p.kind;
            COLORS[i] = PALETTE[i % PALETTE.length];
            PLACEHOLDERS[i] = placeholderFor(p.id);
        }
    }

    /** 输入框里的示例格式 */
    private static String placeholderFor(String id) {
        if ("openrouter".equals(id)) return "sk-or-v1-...";
        if ("novita".equals(id))     return "sk_...";
        if ("fireworks".equals(id))  return "fw_...";
        return "sk-...";
    }

    /** 按 id 找预设 */
    public static Preset presetOf(String id) {
        if (id == null) return null;
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i].id.equals(id)) return PRESETS[i];
        }
        return null;
    }

    /** 平台圆点颜色（按 platform id，不是 Key uuid） */
    public static int colorOf(String platform) {
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(platform)) return COLORS[i];
        }
        return 0xFF94A3B8;
    }

    /**
     * 收集该平台的官网入口。控制台优先，充值页不同才另给一项。
     * 返回条数（0 = 没收录）。
     */
    public static int collectSites(String platform,
                                   java.util.List<String> labels,
                                   java.util.List<String> urls) {
        Preset p = presetOf(platform);
        if (p == null) return 0;
        String c = p.console == null ? "" : p.console.trim();
        String t = p.topup == null ? "" : p.topup.trim();
        if (c.length() > 0) {
            labels.add("控制台");
            urls.add(c);
        }
        if (t.length() > 0 && !t.equals(c)) {
            labels.add("去充值");
            urls.add(t);
        }
        return urls.size();
    }

    /** 依次尝试候选路径，返回第一个能取到的数 */
    private static double pickAny(JSONObject o, String paths) {
        if (paths == null || paths.length() == 0) return Double.NaN;
        String[] ps = paths.split("\\|");
        for (int i = 0; i < ps.length; i++) {
            double v = pickPath(o, ps[i].trim());
            if (!Double.isNaN(v)) return v;
        }
        return Double.NaN;
    }

    /** 用户自定义的 OpenAI 兼容平台 */
    public static class Custom {
        public String name = "";
        public String url = "";
        public String key = "";
        public String path = "";
        public String unit = "CNY";
        /** 制式（同 Item.kind） */
        public String kind = "balance";
        /** 显示后缀（如 "%"、"次"）。填了就不按金额格式化，直接「数值+后缀」 */
        public String suffix = "";
        /** 静态文本：填了就不发任何请求，直接显示这段字（免费服务 / 包月不限量之类） */
        public String text = "";
        /** 低余额预警阈值（原币种，0 = 不预警） */
        public double threshold = 0;
    }

    public static List<Custom> loadCustom(Context ctx) {
        List<Custom> list = new ArrayList<Custom>();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(sp.getString("custom_json", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Custom c = new Custom();
                c.name = o.optString("name", "");
                c.url = o.optString("url", "");
                c.key = KeyVault.dec(o.optString("key", ""));
                c.path = o.optString("path", "");
                c.unit = o.optString("unit", "CNY");
                c.kind = o.optString("kind", "balance");
                c.suffix = o.optString("suffix", "");
                c.text = o.optString("text", "");
                c.threshold = o.optDouble("threshold", 0);
                /* 有 Key 就算配置好了。URL 留空表示「查不到额度的免费服务」
                   （魔搭就是这种：服务本身免费，没有余额可查，只作展示）。 */
                if (c.key.length() > 0) list.add(c);
            }
        } catch (Exception ignored) { }
        return list;
    }

    public static void saveCustom(Context ctx, List<Custom> list) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            Custom c = list.get(i);
            try {
                JSONObject o = new JSONObject();
                o.put("name", c.name);
                o.put("url", c.url);
                o.put("key", KeyVault.enc(c.key));
                o.put("path", c.path);
                o.put("unit", c.unit);
                o.put("kind", c.kind);
                o.put("suffix", c.suffix);
                o.put("text", c.text);
                o.put("threshold", c.threshold);
                arr.put(o);
            } catch (Exception ignored) { }
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putString("custom_json", arr.toString()).apply();
    }

    public static class Item {
        public String id = "";            // Key 的唯一标识（同一平台可以有多个 Key）
        public String platform = "";      // 所属平台：deepseek / qiniu / custom:0
        public String label = "";
        public String tag = "";
        /** 制式：balance=余额制 / sub=订阅制 / usage=后付费账单 / free=免费额度 */
        public String kind = "balance";
        public String amount = "";
        public String conv = "";
        public String rows = "";
        public String error = "";
        public double cny = 0;          // 折合人民币
        public double bal = 0;          // 原币种余额（阈值判断用）
        public double threshold = 0;    // 预警阈值（原币种，随 Item 一起带出）
        public String alertKey = "";    // 告警幂等键（内置用 id，自定义用名称——索引会变）
        public boolean low = false;     // 是否低于预警阈值
        public boolean ok = false;
    }

    public static class Result {
        public double rate = 7.1;
        public double totalCny = 0;
        public int configured = 0;
        public int failed = 0;
        public List<Item> items = new ArrayList<Item>();
    }

    // ---------- HTTP ----------

    /**
     * 公开的 HTTP GET 方法（供其他类调用，如 AliyunSigner）
     */
    public static String httpGet(String url, String bearer, int timeoutMs) throws Exception {
        return get(url, bearer, timeoutMs);
    }

    /** 服务器已明确应答（非网络问题）时抛出，用于终止多地址重试。 */
    private static class StatusException extends Exception {
        StatusException(String m) { super(m); }
    }

    /**
     * 带多地址回退的 GET。
     * 本机网络下部分 CDN（Cloudflare 系）的 IPv6 是黑洞 —— 连不上又不报错，
     * 会把整个超时预算耗光，导致后面的 IPv4 来不及试。所以自己做地址调度：
     * IPv4 优先，逐个尝试，单地址最多用掉一半预算。
     */
    private static String get(String url, String bearer, int timeoutMs) throws Exception {
        URL u = new URL(url);
        String host = u.getHost();
        boolean https = "https".equalsIgnoreCase(u.getProtocol());
        int port = u.getPort() > 0 ? u.getPort() : (https ? 443 : 80);
        String path = u.getFile();

        List<InetAddress> v4 = new ArrayList<InetAddress>();
        List<InetAddress> v6 = new ArrayList<InetAddress>();
        resolve(host, v4, v6);
        if (v4.isEmpty() && v6.isEmpty()) {
            /* 解析失败通常是两种情形：进程刚被唤醒还没就绪，
               或者短时间内并发太猛把系统 DNS 打爆。略等再试一次，成功率高很多。 */
            try { Thread.sleep(700); } catch (Exception ig) { }
            resolve(host, v4, v6);
        }

        boolean localV4 = hasLocalIpv4();
        List<InetAddress> ordered = new ArrayList<InetAddress>();
        /* 按本机**真实存在的出口协议**排序：移动数据/纯 IPv6（NAT64）环境没有 IPv4 路由，
           硬先试 IPv4 只会白等满超时，最后落到 IPv6 还可能被 Cloudflare 拒（ECONNREFUSED）。 */
        if (localV4) { ordered.addAll(v4); ordered.addAll(v6); }
        else         { ordered.addAll(v6); ordered.addAll(v4); }
        Log.i(TAG, "GET " + host + " → " + v4.size() + " IPv4 / " + v6.size() + " IPv6，本机有IPv4出口="
                + localV4 + "，试序 " + ordered);
        if (ordered.isEmpty()) return getViaHost(url, bearer, timeoutMs);

        StringBuilder tried = new StringBuilder();
        /* 首选地址要吃满大部分预算：OpenRouter/七牛云实测稳定要 4~6s，
           按 timeoutMs/2 分（8000 → 4000）会让它们次次超时。
           地址已按本机出口协议排好，首选通常一次就通，所以给它 3/4 预算。 */
        int per = Math.max(3000, timeoutMs * 3 / 4);
        for (int i = 0; i < ordered.size(); i++) {
            long t0 = System.currentTimeMillis();
            String ip = ordered.get(i).getHostAddress();
            try {
                String r = getViaIp(ordered.get(i), host, port, https, path, bearer, per);
                Log.i(TAG, "  ✅ " + ip + " " + (System.currentTimeMillis() - t0) + "ms");
                return r;
            } catch (StatusException se) {
                Log.w(TAG, "  ⚠️ " + ip + " 服务端应答 " + (System.currentTimeMillis() - t0) + "ms: " + se.getMessage());
                throw se;                                  // 服务器已应答，换地址没意义
            } catch (Exception e) {
                Log.w(TAG, "  ❌ " + ip + " " + (System.currentTimeMillis() - t0) + "ms: " + e);
                /* 把每个地址的失败原因攒进异常消息 —— logcat 在很多 ROM 上读不到（本机就是），
                   这样写进诊断日志才看得见到底卡在哪一步。 */
                tried.append(ip).append('=').append(e.getClass().getSimpleName()).append("; ");
            }
        }
        throw new Exception("所有地址均失败 [" + (tried.length() == 0 ? "无" : tried.toString().trim()) + "]");
    }

    /** DNS 解析（失败不抛，交给调用方决定要不要重试） */
    private static void resolve(String host, List<InetAddress> v4, List<InetAddress> v6) {
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            for (int i = 0; i < all.length; i++) {
                if (all[i] instanceof Inet4Address) v4.add(all[i]);
                else if (all[i] instanceof Inet6Address) v6.add(all[i]);
            }
        } catch (Exception ignore) { }
    }

    /** 本机是否存在非环回 IPv4 出口。没有就是纯 IPv6 / NAT64 环境（移动数据常见）。 */
    private static boolean hasLocalIpv4() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static String getViaIp(InetAddress addr, String host, int port, boolean https,
                                   String path, String bearer, int timeoutMs) throws Exception {
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress(addr, port), timeoutMs);
            sock.setSoTimeout(timeoutMs);
            Socket io = sock;
            if (https) {
                SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket ssl = (SSLSocket) sf.createSocket(sock, host, port, true);   // host 决定 SNI
                ssl.startHandshake();
                io = ssl;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
            sb.append("Host: ").append(host).append("\r\n");
            sb.append("Accept: application/json\r\n");
            sb.append("Accept-Encoding: identity\r\n");
            sb.append("User-Agent: MinisWidget/1.2\r\n");
            sb.append("Connection: close\r\n");
            if (bearer != null) sb.append("Authorization: Bearer ").append(bearer).append("\r\n");
            sb.append("\r\n");
            OutputStream os = io.getOutputStream();
            os.write(sb.toString().getBytes("UTF-8"));
            os.flush();

            BufferedInputStream in = new BufferedInputStream(io.getInputStream());
            String status = readLine(in);
            int code = 0;
            if (status != null) {
                String[] p = status.split(" ");
                if (p.length > 1) { try { code = Integer.parseInt(p[1].trim()); } catch (Exception ig) { } }
            }
            boolean chunked = false;
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                String l = line.toLowerCase();
                if (l.startsWith("transfer-encoding") && l.indexOf("chunked") >= 0) chunked = true;
            }
            String body = chunked ? readChunked(in) : readRest(in);
            if (code < 200 || code >= 300) {
                if (code == 401 || code == 403) throw new StatusException("Key 无效或未授权 (" + code + ")");
                if (code == 404) throw new StatusException("接口地址不存在 (404)");
                if (code == 429) throw new StatusException("请求过于频繁 (429)");
                throw new StatusException("HTTP " + code);
            }
            return body;
        } finally {
            try { sock.close(); } catch (Exception ig) { }
        }
    }

    private static String readLine(BufferedInputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), "UTF-8");
    }

    private static String readRest(BufferedInputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return new String(b.toByteArray(), "UTF-8");
    }

    private static String readChunked(BufferedInputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sz = readLine(in);
            if (sz == null) break;
            sz = sz.trim();
            int semi = sz.indexOf(';');
            if (semi >= 0) sz = sz.substring(0, semi);
            if (sz.length() == 0) continue;
            int len;
            try { len = Integer.parseInt(sz, 16); } catch (Exception e) { break; }
            if (len == 0) break;
            for (int i = 0; i < len; i++) {
                int c = in.read();
                if (c == -1) break;
                out.write(c);
            }
            readLine(in);
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    /** 解析不出任何地址时的兜底：交回系统默认行为。 */
    private static String getViaHost(String url, String bearer, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "MinisWidget/1.2");
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        try {
            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
            }
            if (code < 200 || code >= 300) {
                if (code == 401 || code == 403) throw new Exception("Key 无效或未授权 (" + code + ")");
                if (code == 404) throw new Exception("接口地址不存在 (404)");
                if (code == 429) throw new Exception("请求过于频繁 (429)");
                throw new Exception("HTTP " + code);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static double num(JSONObject o, String key) {
        if (o == null || !o.has(key)) return Double.NaN;
        try {
            String s = o.getString(key);
            if (s == null || s.length() == 0 || "null".equals(s)) return Double.NaN;
            return Double.parseDouble(s);
        } catch (Exception e) {
            try { return o.getDouble(key); } catch (Exception e2) { return Double.NaN; }
        }
    }

    private static double findNum(JSONObject o, String[] keys) {
        if (o == null) return Double.NaN;
        for (int i = 0; i < keys.length; i++) {
            double v = num(o, keys[i]);
            if (!Double.isNaN(v)) return v;
        }
        return Double.NaN;
    }

    private static final String[] BAL_KEYS = {
        "total_balance", "totalBalance", "available_balance", "availableBalance",
        "balance", "cash_balance", "cashBalance", "chargeBalance",
        "remaining", "available", "amount", "credit"
    };

    /** 深度遍历兜底：容忍字段名大小写/下划线差异 */
    private static double deepFind(JSONObject o, int depth) {
        if (o == null || depth > 4) return Double.NaN;
        JSONArray names = o.names();
        if (names == null) return Double.NaN;
        for (int i = 0; i < names.length(); i++) {
            String k = names.optString(i);
            String kl = k.toLowerCase().replace("_", "").replace("-", "");
            for (int j = 0; j < BAL_KEYS.length; j++) {
                String want = BAL_KEYS[j].toLowerCase().replace("_", "").replace("-", "");
                if (kl.equals(want)) {
                    double v = num(o, k);
                    if (!Double.isNaN(v)) return v;
                }
            }
        }
        for (int i = 0; i < names.length(); i++) {
            JSONObject child = o.optJSONObject(names.optString(i));
            if (child != null) {
                double v = deepFind(child, depth + 1);
                if (!Double.isNaN(v)) return v;
            }
        }
        return Double.NaN;
    }

    private static double pick(JSONObject o) {
        double v = findNum(o, BAL_KEYS);
        if (!Double.isNaN(v)) return v;
        return deepFind(o, 0);
    }

    private static double pickPath(JSONObject o, String path) {
        if (o == null || path == null || path.length() == 0) return Double.NaN;
        String[] parts = path.split("\\.");
        JSONObject cur = o;
        for (int i = 0; i < parts.length; i++) {
            if (cur == null) return Double.NaN;
            if (i == parts.length - 1) return num(cur, parts[i].trim());
            cur = cur.optJSONObject(parts[i].trim());
        }
        return Double.NaN;
    }

    private static String money(double v, boolean usd) {
        return (usd ? "$" : "¥") + String.format("%.2f", v);
    }

    /** 诊断日志：写到 App 专属外部目录（不需要存储权限），方便用 shell 排查 */
    public static void diag(Context ctx, String msg) {
        try {
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            java.io.File fo = new java.io.File(dir, "diag.log");
            if (fo.length() > 256 * 1024) fo.delete();
            java.io.FileWriter w = new java.io.FileWriter(fo, true);
            w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date()) + "  " + msg + "\n");
            w.close();
        } catch (Throwable ignored) { }
    }

    // ---------- 主流程 ----------

    public static Result fetch(Context ctx, int timeoutMs) {
        long t0 = System.currentTimeMillis();
        Result res = new Result();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        diag(ctx, "fetch 开始，预算 " + timeoutMs + "ms");

        /* 汇率：各平台共用，先拿（很快，通常 200~800ms）。拿不到就沿用内置默认值。 */
        try {
            String j = get(RATE_URL,
                           null, Math.min(timeoutMs, 3000));
            JSONObject r = new JSONObject(j).optJSONObject("rates");
            if (r != null) {
                double v = num(r, "CNY");
                if (!Double.isNaN(v) && v > 0) res.rate = v;
            }
            diag(ctx, "汇率 " + res.rate + " (" + (System.currentTimeMillis() - t0) + "ms)");
        } catch (Exception e) {
            diag(ctx, "汇率失败 " + e + " (" + (System.currentTimeMillis() - t0) + "ms)");
        }

        /* 组装待抓清单：内置平台 + 自定义平台统一走同一条并发路径 */
        final List<Item> items = new ArrayList<Item>();
        final List<Custom> custs = new ArrayList<Custom>();     // 内置项为 null
        final List<String> keys = new ArrayList<String>();
        final List<KeyStore.ApiKey> apiKeys = new ArrayList<KeyStore.ApiKey>();  // 新增：存储 ApiKey 对象

        /* 遍历「Key」而不是「平台」—— 同一家开好几个 Key 是常态，
           每个 Key 独立算余额、独立设阈值、独立参与统计。 */
        List<KeyStore.ApiKey> aks = KeyStore.all(ctx);
        List<Custom> customList = loadCustom(ctx);

        for (int i = 0; i < aks.size(); i++) {
            KeyStore.ApiKey ak = aks.get(i);
            String plat = ak.platform == null ? "" : ak.platform;
            /* 百炼 / 魔搭是展示型：没 Key 也该出现在列表里，方便点官网 */
            boolean displayOnly = "dashscope".equals(plat) || "modelscope".equals(plat);
            if (!ak.isConfigured() && !displayOnly) continue;

            Item it = new Item();
            it.id = ak.id;                       // Key id 才是唯一标识
            it.platform = plat;
            it.alertKey = ak.id;
            it.threshold = ak.threshold;

            if (plat.startsWith("custom")) {
                int idx = -1;
                try {
                    String n = plat.startsWith("custom:") ? plat.substring(7) : plat.substring(6);
                    idx = Integer.parseInt(n);
                } catch (Exception ig) { }
                if (idx < 0 || idx >= customList.size()) continue;
                Custom c = customList.get(idx);
                it.label = c.name.length() > 0 ? c.name : "自定义";
                it.tag = "USD".equals(c.unit) ? "USD" : "CNY";
                it.kind = c.kind;
                items.add(it);
                custs.add(c);
                keys.add(ak.key);
                apiKeys.add(ak);
                res.configured++;
            } else {
                Preset p = presetOf(plat);
                if (p == null) continue;
                it.label = (ak.label.length() == 0 || "默认".equals(ak.label))
                        ? p.name : (p.name + " · " + ak.label);
                it.tag = p.unit;
                it.kind = p.kind;
                items.add(it);
                custs.add(null);
                keys.add(ak.key);
                apiKeys.add(ak);
                res.configured++;
            }
        }

        /* 展示型平台（百炼 / 魔搭）即使没填 Key 也给一张卡，方便点官网 */
        for (int i = 0; i < PRESETS.length; i++) {
            Preset p = PRESETS[i];
            if (!"dashscope".equals(p.id) && !"modelscope".equals(p.id)) continue;
            boolean already = false;
            for (int j = 0; j < items.size(); j++) {
                if (p.id.equals(items.get(j).platform)) { already = true; break; }
            }
            if (already) continue;
            Item it = new Item();
            it.id = p.id;
            it.platform = p.id;
            it.alertKey = p.id;
            it.label = p.name;
            it.tag = p.unit;
            it.kind = p.kind;
            items.add(it);
            custs.add(null);
            keys.add("");
            apiKeys.add(null);  // 展示型平台没有 ApiKey
            res.configured++;
        }

        List<Custom> cs = loadCustom(ctx);
        for (int i = 0; i < cs.size(); i++) {
            /* KeyStore 里已经有这条自定义平台时，上面第一段已经加过了，别再加一遍 */
            if (KeyStore.countConfigured(ctx, "custom:" + i) > 0
                    || KeyStore.countConfigured(ctx, "custom" + i) > 0) continue;
            Custom c = cs.get(i);
            res.configured++;
            Item it = new Item();
            it.id = "custom" + i;
            it.threshold = c.threshold;
            it.alertKey = "custom:" + (c.name.length() > 0 ? c.name : String.valueOf(i));
            it.label = c.name.length() > 0 ? c.name : "自定义";
            it.tag = "USD".equals(c.unit) ? "USD" : "CNY";
            it.kind = c.kind;
            items.add(it);
            custs.add(c);
            keys.add(c.key);
            apiKeys.add(null);  // 自定义平台没有 ApiKey 对象
            /* 静态项不该占用联网预算：直接把结果填好，并发阶段会跳过它 */
        }

        /* 🔥 并发抓取。原来是 for 串行，N 个平台最坏要 N × timeoutMs ——
           后台广播/小组件根本等不到那么久，排在后面的平台会被直接砍掉（数据残缺）。
           并发之后总耗时 ≈ 最慢的那一个，7 个平台也能在几秒内拿全。 */
        final double rate = res.rate;
        final int per = timeoutMs;
        int n = items.size();
        Thread[] ts = new Thread[n];
        for (int i = 0; i < n; i++) {
            final Item it = items.get(i);
            final Custom c = custs.get(i);
            final String k = keys.get(i);
            final KeyStore.ApiKey ak = apiKeys.get(i);  // 新增：获取 ApiKey 对象
            ts[i] = new Thread(new Runnable() {
                public void run() {
                    long ts0 = System.currentTimeMillis();
                    try {
                        if (c == null) fill(it, ak, rate, per);  // 修改：传递 ApiKey 对象
                        else           fillCustom(it, c, rate, per);
                        it.ok = true;
                        Log.i(TAG, "平台 " + it.id + " 成功: " + it.amount);
                        diag(ctx, "  OK  " + it.id + " = " + it.amount
                                + "   (" + (System.currentTimeMillis() - ts0) + "ms)");
                    } catch (Exception e) {
                        it.ok = false;
                        it.error = (e.getMessage() == null) ? "查询失败" : e.getMessage();
                        it.amount = "—";
                        Log.w(TAG, "平台 " + it.id + " 失败: " + it.error
                                + " | " + e.getClass().getSimpleName());
                        diag(ctx, "  NG  " + it.id + " " + it.error
                                + "   (" + (System.currentTimeMillis() - ts0) + "ms)");
                    }
                }
            });
            ts[i].start();
        }
        for (int i = 0; i < n; i++) {
            try {
                ts[i].join(per + 1500L);
                if (ts[i].isAlive()) {
                    /* 没在预算内收尾：换成一个干净的失败项，
                       免得它稍后再往正在被序列化/渲染的对象上写脏数据 */
                    Item src = items.get(i);
                    Item dead = new Item();
                    dead.id = src.id;
                    dead.platform = src.platform;
                    dead.label = src.label;
                    dead.tag = src.tag;
                    dead.kind = src.kind;
                    dead.alertKey = src.alertKey;
                    dead.threshold = src.threshold;
                    dead.ok = false;
                    dead.error = "请求超时";
                    dead.amount = "—";
                    items.set(i, dead);
                    Log.w(TAG, "平台 " + dead.id + " 超过预算未返回");
                }
            } catch (InterruptedException ignored) { }
        }

        /* 汇总（串行，避免多线程争用 totalCny） */
        for (int i = 0; i < n; i++) {
            Item it = items.get(i);
            if (it.ok) {
                res.totalCny += it.cny;
            } else {
                res.failed++;
                if (it.amount == null || it.amount.length() == 0) it.amount = "—";
            }
            res.items.add(it);
        }
        diag(ctx, "fetch 结束：成功 " + (n - res.failed) + "/" + n
                + "，总耗时 " + (System.currentTimeMillis() - t0) + "ms");
        return res;
    }

    /** 汇率源（免费、无需 key） */
    private static final String RATE_URL =
            "https://api.frankfurter.dev/v1/latest?base=USD&symbols=CNY";

    /** 数字的紧凑写法（订阅余量可能带小数，整数就不显示小数点） */
    static String num(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.format("%.2f", v);
    }

    /**
     * 只抓一个平台 —— 应用内点卡片的「刷新这一项」用。
     * 失败也返回 Item（ok=false + error），不抛异常，方便直接渲染。
     */
    public static Item fetchOne(Context ctx, String keyId, int timeoutMs) {
        Item it = new Item();
        it.id = keyId == null ? "" : keyId;
        it.alertKey = it.id;

        /* 按 Key id 找条目 —— 多 Key 之后，一个平台对应好几条记录，
           单刷一项时得先定位到具体是哪个 Key。 */
        KeyStore.ApiKey ak = null;
        List<KeyStore.ApiKey> all = KeyStore.all(ctx);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(it.id)) { ak = all.get(i); break; }
        }
        if (ak == null) {
            /* 展示型平台（百炼 / 魔搭）可以没有 Key 记录 */
            Preset p0 = presetOf(it.id);
            if (p0 != null && ("dashscope".equals(p0.id) || "modelscope".equals(p0.id))) {
                it.platform = p0.id;
                it.label = p0.name;
                it.tag = p0.unit;
                it.kind = p0.kind;
                try { fill(it, null, 7.1, timeoutMs); it.ok = true; }  // 修改：传递 null
                catch (Exception e) {
                    it.ok = false;
                    it.error = e.getMessage();
                    it.amount = "\u2014";
                }
                return it;
            }
            it.label = "已删除";
            it.error = "这条 Key 记录已经不在了";
            it.amount = "\u2014";
            return it;
        }
        it.platform = ak.platform;
        it.threshold = ak.threshold;

        // 汇率（USD 项换算要用）
        double rate = 7.1;
        try {
            String j2 = get(RATE_URL, null, Math.min(timeoutMs, 3000));
            JSONObject r0 = new JSONObject(j2).optJSONObject("rates");
            if (r0 != null) {
                double v = num(r0, "CNY");
                if (!Double.isNaN(v) && v > 0) rate = v;
            }
        } catch (Exception ignored) { }

        try {
            if (ak.platform.startsWith("custom")) {
                int idx = -1;
                try {
                    String n = ak.platform.startsWith("custom:")
                            ? ak.platform.substring(7) : ak.platform.substring(6);
                    idx = Integer.parseInt(n);
                } catch (Exception ig) { }
                List<Custom> cs = loadCustom(ctx);
                if (idx < 0 || idx >= cs.size()) {
                    it.label = "自定义";
                    it.error = "自定义平台已经被删掉了";
                    it.amount = "\u2014";
                    return it;
                }
                Custom c = cs.get(idx);
                it.label = c.name.length() > 0 ? c.name : "自定义";
                it.tag = "USD".equals(c.unit) ? "USD" : "CNY";
                it.kind = c.kind;
                fillCustom(it, c, rate, timeoutMs);
                it.ok = true;
            } else {
                Preset p = presetOf(ak.platform);
                if (p == null) {
                    it.label = "未知平台";
                    it.error = "找不到 " + ak.platform;
                    it.amount = "\u2014";
                    return it;
                }
                it.label = (ak.label.length() == 0 || "\u9ed8\u8ba4".equals(ak.label))
                        ? p.name : (p.name + " \u00b7 " + ak.label);
                it.kind = p.kind;
                it.tag = p.unit;
                fill(it, ak, rate, timeoutMs);  // 修改：传递 ak 对象
                it.ok = true;
            }
        } catch (Exception e) {
            it.ok = false;
            it.error = (e.getMessage() == null) ? "查询失败" : e.getMessage();
            it.amount = "\u2014";
        }
        return it;
    }
    /** 按制式归组（组内保持原顺序），供小组件分档渲染。 */
    public static List<Item> groupByKind(List<Item> src) {
        List<Item> out = new ArrayList<Item>();
        for (int k = 0; k < KIND_ORDER.length; k++) {
            for (int i = 0; i < src.size(); i++) {
                Item it = src.get(i);
                if (KIND_ORDER[k].equals(it.kind)) out.add(it);
            }
        }
        /* 兜底：没归类的（脏缓存 / 以后新增的制式）原样接在后面，不要丢 */
        for (int i = 0; i < src.size(); i++) {
            Item it = src.get(i);
            if (!out.contains(it)) out.add(it);
        }
        return out;
    }

    /** 自定义平台（任意 OpenAI 兼容接口：余额 / 订阅余量 / 后付费账单…） */
    private static void fillCustom(Item it, Custom c, double rate, int t) throws Exception {
        /* 没填 URL：这个服务没有可查的额度（免费的魔搭），只作展示，一个请求都不发 */
        if (c.url == null || c.url.length() == 0) {
            String shown = (c.text != null && c.text.length() > 0) ? c.text : "免费";
            it.amount = shown;
            it.cny = 0;
            it.bal = 0;
            it.rows = shown;
            return;
        }
        boolean usd = "USD".equals(it.tag);
        String body = get(c.url, c.key, t);
        JSONObject o = new JSONObject(body);
        double bal = pickPath(o, c.path);
        if (Double.isNaN(bal)) bal = pick(o);
        if (Double.isNaN(bal)) {
            String raw = body.replaceAll("\\s+", " ");
            throw new Exception("未识别余额字段，接口返回：" + clip(raw, 140));
        }
        it.bal = bal;

        /* 只有「余额制」才算资产进总额；订阅余量 / 后付费账单 / 免费额度都不是钱 */
        boolean isAsset = "balance".equals(it.kind);

        if (c.suffix != null && c.suffix.length() > 0) {
            /* 订阅类：显示成「62%」「12/20 次」这种，不做货币格式化 */
            it.amount = num(bal) + c.suffix;
            it.conv = "";
        } else {
            it.amount = money(bal, usd);
            it.conv = (usd && isAsset) ? "≈¥" + String.format("%.2f", bal * rate) : "";
        }
        it.cny = isAsset ? (usd ? bal * rate : bal) : 0;
        it.rows = ("balance".equals(it.kind) ? "余额  " : "数值  ") + money(bal, usd);
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static String todayStr() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format("%04d-%02d-%02d", c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    private static void fill(Item it, KeyStore.ApiKey apiKey, double rate, int t) throws Exception {
        String id = it.platform;              // 解析逻辑按「平台」选，不按 Key
        boolean usd = "USD".equals(it.tag);
        String key = apiKey != null ? apiKey.key : "";

        /* 百炼：按计费模式分流 —— 余额制查账户余额，订阅制查套餐实例 */
        if ("dashscope".equals(id)) {
            boolean isSub = apiKey != null && "subscription".equals(apiKey.planMode);
            if (apiKey != null && apiKey.hasAliyunAccessKey()) {
                try {
                    if (isSub) {
                        /* 订阅制（Token Plan）：无公开余量 API，查实例列表拿状态与到期时间 */
                        String json = AliyunSigner.queryAvailableInstances(
                            apiKey.accessKeyId, apiKey.accessKeySecret);
                        JSONObject root = new JSONObject(json);
                        String code = root.optString("Code", "");
                        boolean okCode = code.length() == 0
                                || "200".equals(code) || "Success".equalsIgnoreCase(code);
                        if (!okCode) {
                            String msg = root.optString("Message", "查询失败");
                            throw new Exception("阿里云 API 错误 [" + code + "]：" + msg);
                        }
                        JSONObject data = root.optJSONObject("Data");
                        org.json.JSONArray list = data == null
                                ? null : data.optJSONArray("InstanceList");
                        int total = data == null ? 0 : data.optInt("TotalCount",
                                list == null ? 0 : list.length());
                        String earliestEnd = null;
                        int active = 0;
                        if (list != null) {
                            for (int i = 0; i < list.length(); i++) {
                                Object o = list.opt(i);
                                JSONObject inst = null;
                                if (o instanceof JSONObject) inst = (JSONObject) o;
                                else if (o instanceof org.json.JSONArray) {
                                    org.json.JSONArray inner = (org.json.JSONArray) o;
                                    inst = inner.optJSONObject(0);   // 兼容嵌套数组结构
                                }
                                if (inst == null) continue;
                                String st = inst.optString("Status", "");
                                if ("Active".equalsIgnoreCase(st)) active++;
                                String end = inst.optString("EndTime", "");
                                if (end.length() > 0 && (earliestEnd == null
                                        || end.compareTo(earliestEnd) < 0)) earliestEnd = end;
                            }
                        }
                        if (total > 0 || (list != null && list.length() > 0)) {
                            it.bal = 1;          // 订阅视为"有效"，参与预警时恒不低余额
                            it.cny = 0;
                            it.amount = "见控制台";   // 金额位始终是提示文字，绝不显示 0 元
                            StringBuilder sb = new StringBuilder("订阅套餐 · 实例 ")
                                    .append(Math.max(total, list == null ? 0 : list.length())).append(" 个");
                            if (active > 0) sb.append(" · 活跃 ").append(active);
                            if (earliestEnd != null)
                                sb.append("\n最近到期  ").append(earliestEnd);
                            sb.append("\n套餐余量请在百炼控制台查看");
                            it.rows = sb.toString();
                        } else {
                            it.amount = "见控制台";
                            it.rows = "订阅套餐 · 未查到订阅实例\n（Token Plan 余量请到百炼控制台查看）";
                        }
                        it.conv = "";
                        return;
                    }

                    /* 余额制：QueryAccountBalance */
                    String json = AliyunSigner.queryAccountBalance(
                        apiKey.accessKeyId, apiKey.accessKeySecret);
                    JSONObject root = new JSONObject(json);

                    // 余额字段嵌套在 Data 里（历史应答偶尔在根层，两者都兼容）
                    JSONObject data = root.optJSONObject("Data");
                    if (data == null) data = root;

                    // 解析余额信息
                    String code = root.optString("Code", "");
                    boolean okCode = code.length() == 0
                            || "200".equals(code) || "Success".equalsIgnoreCase(code);
                    if (!okCode) {
                        String msg = root.optString("Message", "查询失败");
                        throw new Exception("阿里云 API 错误 [" + code + "]：" + msg);
                    }

                    // QueryAccountBalance 的余额字段在 Data 下，值为字符串（optDouble 会强转）
                    double available = data.optDouble("AvailableAmount", Double.NaN);
                    double credit    = data.optDouble("CreditAmount", Double.NaN);
                    double mybank    = data.optDouble("MybankCreditAmount", Double.NaN);
                    String currency  = data.optString("Currency", "CNY");

                    if (Double.isNaN(available)) {
                        // 成功应答却没拿到数字：抛原始片段，别再静默降级成「见控制台」
                        String raw = json.length() > 160 ? json.substring(0, 160) : json;
                        throw new Exception("未识别的余额应答：" + raw);
                    }

                    usd = currency != null && currency.toUpperCase().indexOf("USD") >= 0;
                    if (usd) it.tag = "USD";
                    it.bal = available;
                    it.cny = usd ? available * rate : available;
                    it.amount = money(available, usd);

                    StringBuilder sb = new StringBuilder();
                    sb.append("可用余额  ").append(money(available, usd));
                    if (!Double.isNaN(credit) && credit > 0) {
                        sb.append("\n信用额度  ").append(money(credit, usd));
                    }
                    if (!Double.isNaN(mybank) && mybank > 0) {
                        sb.append("\n网商贷额度  ").append(money(mybank, usd));
                    }
                    it.rows = sb.toString();
                    if (usd) it.conv = "≈¥" + (long) (available * rate);
                    return;
                } catch (Exception e) {
                    // 查询失败，降级到展示模式
                    it.amount = "查询失败";
                    it.rows = e.getMessage();
                    return;
                }
            }
            
            // 没有配置 AccessKey 或查询失败，降级到展示模式
            if (isSub) {
                it.amount = "见控制台";
                it.rows = "订阅套餐 · Token Plan 余量见控制台\n（配置阿里云 AccessKey 可查实例与到期时间）";
            } else {
                it.amount = "见控制台";
                it.rows = "预付费资源包 · 额度在控制台\n（设置中配置阿里云 AccessKey 可自动查询）";
            }
            it.cny = 0;
            it.bal = 0;
            it.conv = "";
            return;
        }

        /* 魔搭这类纯免费服务：没有额度可查，只作展示 */
        if ("modelscope".equals(id)) {
            it.amount = "免费";
            it.cny = 0;
            it.bal = 0;
            it.rows = "免费服务";
            return;
        }

        /* 通用服务商：预设里给了 URL，用候选字段逐个试（各家命名不统一） */
        Preset pre = presetOf(id);
        if (pre != null && pre.url.length() > 0) {
            String body = get(pre.url, key, t);
            JSONObject root = new JSONObject(body);
            double bal = pickAny(root, pre.paths);
            if (Double.isNaN(bal)) bal = pick(root);
            if (Double.isNaN(bal)) {
                String raw = body.replaceAll("\\s+", " ");
                throw new Exception("未识别余额字段，接口返回：" + clip(raw, 140));
            }
            it.bal = bal;
            it.cny = usd ? bal * rate : bal;
            it.amount = money(bal, usd);
            if (usd) it.conv = "≈¥" + String.format("%.2f", it.cny);
            it.rows = "余额  " + money(bal, usd);
            return;
        }

        /* 七牛云：后付费，没有余额只有账单 —— 显示本月消费额，不计入总资产 */
        if ("qiniu".equals(id)) {
            String td = todayStr();
            String url = "https://api.qnaigc.com/v3/stat/usage/apikey/cost-detail"
                    + "?start_date=" + td.substring(0, 8) + "01&end_date=" + td;
            JSONObject root = new JSONObject(get(url, key, t));
            if (!root.optBoolean("status", true)) {
                JSONObject err = root.optJSONObject("error");
                throw new Exception(err != null ? err.optString("message", "查询失败") : "查询失败");
            }
            JSONObject d = root.optJSONObject("data");
            if (d == null) throw new Exception("未返回账单数据");
            double fee = findNum(d, new String[] { "total_fee", "totalFee" });
            if (Double.isNaN(fee)) fee = pick(d);
            if (Double.isNaN(fee)) throw new Exception("未识别消费金额");
            it.bal = fee;
            it.cny = 0;                                  // 消费额不是资产
            it.amount = money(fee, false);
            StringBuilder sb = new StringBuilder();
            sb.append("本月消费（非余额）");
            String period = d.optString("period", "");
            if (period.length() > 0) sb.append("\n计费周期  ").append(period);
            JSONArray bills = d.optJSONArray("bills");
            sb.append("\n账单项数  ").append(bills == null ? 0 : bills.length());
            it.rows = sb.toString();
            return;
        }

        if ("deepseek".equals(id)) {
            JSONObject o = new JSONObject(get("https://api.deepseek.com/user/balance", key, t));
            JSONArray arr = o.optJSONArray("balance_infos");
            double total = 0;
            boolean got = false;
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject b = arr.optJSONObject(i);
                    if (b == null) continue;
                    String cur = b.optString("currency", "");
                    double tv = num(b, "total_balance");
                    if (Double.isNaN(tv)) continue;
                    total += ("CNY".equals(cur) || arr.length() == 1) ? tv : tv * rate;
                    got = true;
                }
            }
            if (!got) {
                double f = pick(o);
                if (Double.isNaN(f)) throw new Exception("未识别余额字段");
                total = f;
            }
            it.cny = total;
            it.bal = total;
            it.amount = money(total, false);
            StringBuilder sb = new StringBuilder();
            if (arr != null && arr.length() > 0) {
                JSONObject b0 = arr.optJSONObject(0);
                double g = num(b0, "granted_balance");
                double tp = num(b0, "topped_up_balance");
                if (!Double.isNaN(g)) sb.append("赠送余额  ").append(money(g, false));
                if (!Double.isNaN(tp)) sb.append(sb.length() > 0 ? "\n" : "").append("充值余额  ").append(money(tp, false));
            }
            if (o.has("is_available")) {
                sb.append(sb.length() > 0 ? "\n" : "").append("可用状态  ").append(o.optBoolean("is_available") ? "正常" : "不可用");
            }
            it.rows = sb.toString();
            return;
        }

        if ("openrouter".equals(id)) {
            JSONObject root = new JSONObject(get("https://openrouter.ai/api/v1/credits", key, t));
            JSONObject d = root.optJSONObject("data");
            if (d == null) d = root;
            double tc = num(d, "total_credits");
            double tu = num(d, "total_usage");
            if (Double.isNaN(tc)) throw new Exception("未识别余额字段");
            if (Double.isNaN(tu)) tu = 0;
            double bal = tc - tu;
            it.cny = bal * rate;
            it.bal = bal;
            it.amount = money(bal, true);
            it.conv = "≈ ¥" + String.format("%.2f", it.cny);
            it.rows = "累计充值  $" + String.format("%.2f", tc)
                    + "\n累计消耗  $" + String.format("%.2f", tu)
                    + "\n免费模型  " + (tc >= 10 ? "1000 次/天" : "50 次/天");
            return;
        }

        if ("siliconflow".equals(id)) {
            JSONObject root = new JSONObject(get("https://api.siliconflow.cn/v1/user/info", key, t));
            JSONObject d = root.optJSONObject("data");
            if (d == null) d = root;
            double bal = findNum(d, new String[]{"totalBalance", "total_balance", "balance"});
            if (Double.isNaN(bal)) bal = pick(d);
            if (Double.isNaN(bal)) throw new Exception("未识别余额字段");
            it.cny = bal;
            it.bal = bal;
            it.amount = money(bal, false);
            StringBuilder sb = new StringBuilder("总余额  ").append(money(bal, false));
            double cash = findNum(d, new String[]{"chargeBalance", "charge_balance", "cash_balance"});
            if (!Double.isNaN(cash)) sb.append("\n现金余额  ").append(money(cash, false));
            it.rows = sb.toString();
            return;
        }

        if ("moonshot".equals(id)) {
            JSONObject root = new JSONObject(get("https://api.moonshot.cn/v1/users/me/balance", key, t));
            JSONObject d = root.optJSONObject("data");
            if (d == null) d = root;
            double bal = findNum(d, new String[]{"available_balance", "availableBalance", "balance"});
            if (Double.isNaN(bal)) bal = pick(d);
            if (Double.isNaN(bal)) throw new Exception("未识别余额字段");
            it.cny = bal;
            it.bal = bal;
            it.amount = money(bal, false);
            StringBuilder sb = new StringBuilder("可用余额  ").append(money(bal, false));
            double v = findNum(d, new String[]{"voucher_balance", "voucherBalance"});
            if (!Double.isNaN(v)) sb.append("\n代金券  ").append(money(v, false));
            double c = findNum(d, new String[]{"cash_balance", "cashBalance"});
            if (!Double.isNaN(c)) sb.append("\n现金余额  ").append(money(c, false));
            it.rows = sb.toString();
            return;
        }

        if ("novita".equals(id)) {
            JSONObject root = new JSONObject(get("https://api.novita.ai/v3/user/balance", key, t));
            JSONObject d = root.optJSONObject("data");
            if (d == null) d = root;
            double bal = findNum(d, new String[]{"availableBalance", "available_balance", "balance"});
            if (Double.isNaN(bal)) bal = pick(d);
            if (Double.isNaN(bal)) throw new Exception("未识别余额字段");
            it.cny = bal * rate;
            it.bal = bal;
            it.amount = money(bal, true);
            it.conv = "≈ ¥" + String.format("%.2f", it.cny);
            StringBuilder sb = new StringBuilder("可用余额  ").append(money(bal, true));
            double c = findNum(d, new String[]{"cashBalance", "cash_balance"});
            if (!Double.isNaN(c)) sb.append("\n现金余额  ").append(money(c, true));
            double l = findNum(d, new String[]{"creditLimit", "credit_limit"});
            if (!Double.isNaN(l)) sb.append("\n信用额度  ").append(money(l, true));
            it.rows = sb.toString();
            return;
        }

        if ("fireworks".equals(id)) {
            JSONObject root = new JSONObject(get("https://api.fireworks.ai/v1/accounts", key, t));
            JSONArray arr = root.optJSONArray("accounts");
            if (arr == null) arr = root.optJSONArray("data");
            if (arr == null) arr = new JSONArray().put(root);
            double best = Double.NaN;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.optJSONObject(i);
                if (a == null) continue;
                double b = findNum(a, new String[]{"balance", "creditBalance", "credit_balance"});
                if (Double.isNaN(b)) b = pick(a);
                if (!Double.isNaN(b) && (Double.isNaN(best) || b > best)) best = b;
            }
            if (Double.isNaN(best)) throw new Exception("账户中未找到余额字段");
            it.cny = best * rate;
            it.bal = best;
            it.amount = money(best, true);
            it.conv = "≈ ¥" + String.format("%.2f", it.cny);
            it.rows = "余额  " + money(best, true) + "\n账户数  " + arr.length();
            return;
        }

        throw new Exception("未知平台");
    }
}
