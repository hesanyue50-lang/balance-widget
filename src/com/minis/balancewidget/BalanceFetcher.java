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

    /** 小米 MiMo 控制台会话音 cookie（**账号级**，不是 Key 级）：由 App 内登录页写入 */
    private static final String K_MIMO_SESSION = "mimo_session";
    /** MiMo 登录态失效标记：cookie 还在本地，但服务端已经 401 */
    private static final String K_MIMO_EXPIRED = "mimo_session_expired";

    /* 会话音的内存缓存：解密要过 TEE，一次几毫秒到几十毫秒，
       而统计页/卡片渲染会反复问「登录了没」，不缓存就是白等。 */
    private static String sessionCache;
    private static boolean sessionCached = false;

    /** 取 MiMo 会话音（空 = 还没登录过 / 已被清掉） */
    public static String mimoSession(Context c) {
        if (sessionCached) return sessionCache;
        String v = "";
        try {
            v = KeyVault.dec(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(K_MIMO_SESSION, ""));
        } catch (Throwable t) {
            v = "";
        }
        sessionCache = v == null ? "" : v;
        sessionCached = true;
        return sessionCache;
    }

    /** 写入 MiMo 会话音（KeyVault 加密，和 API Key 同一把设备密钥） */
    public static void setMimoSession(Context c, String cookie) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(K_MIMO_SESSION, KeyVault.enc(cookie))
                    .putBoolean(K_MIMO_EXPIRED, false)      // 重新登录 → 清掉过期标记
                    .apply();
            sessionCache = cookie == null ? "" : cookie;
            sessionCached = true;
        } catch (Throwable ignored) { }
    }

    /**
     * MiMo 登录态是否**已失效**：本地留着 cookie，但服务端返回 401。
     *
     * 为什么要单独记这个：cookie 非空时老代码只判断"登录过没有"，
     * 于是一个过期会话会被当成正常状态 —— 统计页显示"数据积累中"，
     * 用户根本看不出其实是需要重新登录。
     */
    public static boolean mimoExpired(Context c) {
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(K_MIMO_EXPIRED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 需要重新登录：完全没登录，或者登录态已失效 */
    public static boolean mimoNeedsRelogin(Context c) {
        return mimoSession(c).length() == 0 || mimoExpired(c);
    }

    /**
     * 从备份恢复数据后调用：清掉内存里的缓存。
     * 不清的话，恢复完还继续用上一次运行的旧密钥/旧会话，看着像"没恢复成功"。
     */
    public static void onDataRestored(Context c) {
        try {
            sessionCached = false;
            sessionCache = "";
            SUB_USERINFO.remove();
            SUB_UA.remove();
            diag(c, "备份恢复：内存缓存已清理");
        } catch (Throwable ignored) { }
    }

    /** 清除 MiMo 过期标记（保活确认会话有效、或取数成功时调用） */
    public static void clearMimoExpired(Context c) {
        if (c == null) return;
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(K_MIMO_EXPIRED, false).apply();
        } catch (Throwable ignored) { }
    }

    /** 标记 MiMo 登录态已失效（供保活逻辑在"被弹回登录页"时调用） */
    public static void markMimoExpired(Context c) {
        if (c == null) return;
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(K_MIMO_EXPIRED, true).apply();
        } catch (Throwable ignored) { }
    }

    /** MiMo 控制台余额接口（小米账号会话音鉴权，返回 {balance: 元}） */
    public static final String MIMO_BALANCE_URL =
            "https://platform.xiaomimimo.com/api/v1/balance";
    /** 登录页用的域（取 cookie 只认这个域） */
    public static final String MIMO_HOST = "https://platform.xiaomimimo.com";

    /**
     * 到点就往账本记一次快照（统计页曲线的唯一来源）。
     *
     * 原来这段只写在桌面小组件里 —— 结果就是「不摆小组件 / 小组件没刷新，
     * 统计页就永远是空的」。现在打开 App 的刷新也会走这里，双保险。
     */
    public static void sampleIfDue(Context ctx, Result r) {
        if (ctx == null || r == null) return;
        /* ★ 复用缓存的结果绝不能记账：它不是"此刻读到的余额"，
           写进去会让曲线凭空回跳到过去的旧值。 */
        if (r.fromCache) {
            diag(ctx, "跳过记账：本轮为缓存复用，非新抓取");
            return;
        }
        try {
            SharedPreferences sps = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            /* 这里只挡"同一分钟内重复调用"（并发闸门外的第二道保险）。
               真正的采样节奏交给 Ledger.record 逐 Key 判断 ——
               全局时间戳挡不出"某平台余额变了但别的没变"这种情况，
               而按 6 小时全局挡会把新点全挡掉。 */
            long lastSample = sps.getLong("last_sample_at", 0);
            if (System.currentTimeMillis() - lastSample < 60 * 1000L) return;
            sps.edit().putLong("last_sample_at", System.currentTimeMillis()).apply();
            Ledger lg = Ledger.get(ctx);
            /* 账本按「密钥」记录快照（用合并前的原始条目，避免平台级 id 覆盖） */
            java.util.List<Item> raw = r.rawItems != null ? r.rawItems : r.items;
            int n = 0;
            StringBuilder who = new StringBuilder();
            for (int i = 0; i < raw.size(); i++) {
                Item it = raw.get(i);
                if (it.noData) continue;                    // 未登录之类：没数就是没数，别写 0
                if (it.ok && it.bal >= 0) {
                    /* 钱的平台照记；积分类也要记账 —— 否则"用量统计"里永远没有它的历史，
                       打开统计开关后曲线还是空的。存的是**原始积分**，不是折后价：
                       折算率用户可以随时改，存折后价会让历史数据跟着变，越查越乱。
                       折算是画图时现算的（见 liveBalanceOf / buildStats）。 */
                    boolean isMoney = "balance".equals(it.kind);
                    /* 记账是为了画图 —— 所以看 inChart，不是 inStats。
                       只算钱不画图（inStats 开、inChart 关）时不需要攒历史点。 */
                    boolean isAsset = !isMoney && StatsOpt.inChart(ctx, it.platform);
                    if (isMoney || isAsset) {
                        /* 写入前先清掉历史假点：否则下一个点会以假值为基准算差值，
                           把 23.9 误判成"又充值了 9.38 元"。 */
                        try { lg.dropCacheArtifacts(ctx, it.id); } catch (Throwable ig) { }
                        /* 积分类的变动刻度是 1 分，用"元"那套 0.005 阈值会把
                           每次刷新都记成一个点，图上全是没意义的密集点。 */
                        double eps = isMoney ? 0.005 : 0.5;
                        lg.record(ctx, it.id, it.bal, -1, "USD".equals(it.tag), eps);
                        who.append(it.id).append(' ');
                        n++;
                    }
                }
            }
            /* 顺手清理超期数据（默认留 90 天） */
            lg.prune(ctx, sps.getInt("ledger_keep_days", Ledger.KEEP_DAYS_DEFAULT));
            diag(ctx, "账本采样完成，写入 " + n + " 条 [" + who.toString().trim() + "]");
        } catch (Throwable t) {
            diag(ctx, "快照记录失败: " + t);
        }
    }

    /**
     * 这个平台「有办法取数」但 KeyStore 里一条 Key 都没有 —— 目前只有小米 MiMo
     * （凭据是账号级会话音，不是 Key）。统计页靠它决定要不要给平台补一条线。
     */
    /**
     * 这个平台"能用"吗？—— 判断的是**有没有凭据来源**，不看 KeyStore 里有没有条目。
     *
     * ⚠️ 这个方法是给「遍历 PRESETS 补位」用的：登录型平台（MiMo）、自定义平台、
     *    高级自定义平台都没有 KeyStore 记录，只遍历 KeyStore 会把它们整组漏掉 ——
     *    表现就是「卡片上有余额、统计页却没这条线」。
     *
     * 凭据来源有三类，都要认：
     * - 会话型（登录 cookie）：mimo
     * - 普通自定义平台：custom:<i>
     * - 高级自定义平台：web:<i>
     */
    public static boolean platformUsable(Context c, String platform) {
        if ("mimo".equals(platform)) return mimoSession(c).length() > 0;
        if (platform == null) return false;

        /* 普通自定义平台：配置完备就算可用 */
        if (platform.startsWith("custom")) {
            try {
                String n = platform.startsWith("custom:")
                        ? platform.substring(7) : platform.substring(6);
                int idx = Integer.parseInt(n);
                List<Custom> cs = loadCustom(c);
                if (idx >= 0 && idx < cs.size()) return cs.get(idx).ready();
            } catch (Throwable ignored) { }
            return false;
        }

        /* 高级自定义平台：填了网址就算可用 */
        if (platform.startsWith("web")) {
            try {
                String n = platform.startsWith("web:")
                        ? platform.substring(4) : platform.substring(3);
                int idx = Integer.parseInt(n);
                List<WebCustom> ws = WebCustom.loadAll(c);
                if (idx >= 0 && idx < ws.size()) return ws.get(idx).ready();
            } catch (Throwable ignored) { }
            return false;
        }

        return false;
    }

    private static boolean hasConfiguredKey(Context c, String platform) {
        try {
            List<KeyStore.ApiKey> all = KeyStore.all(c);
            for (int i = 0; i < all.size(); i++) {
                KeyStore.ApiKey k = all.get(i);
                if (platform.equals(k.platform) && k.isConfigured()) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /**
     * 立刻抓一轮并写快照，**不看 6 小时间隔**。
     *
     * 场景：用户刚在登录页登好 MiMo —— 但今天这个 6 小时窗口可能已经采样过了，
     * 那样统计页要再等 6 小时才有点，用户会以为「功能没生效」。
     * 所以登录成功立刻强制采一次；同时在后台线程跑，不阻塞界面。
     */
    public static void sampleNow(final Context ctx) {
        if (ctx == null) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    Result r = fetch(ctx, 12000);
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putLong("last_sample_at", 0L).apply();
                    sampleIfDue(ctx, r);
                } catch (Throwable t) {
                    diag(ctx, "即时采样失败: " + t);
                }
            }
        }).start();
    }

    /** 供登录页校验用：带会话音拉一次余额，返回原始应答（401/302 会抛异常） */
    public static String mimoBalanceRaw(String cookie, int timeoutMs) throws Exception {
        return get(MIMO_BALANCE_URL, null, cookie, timeoutMs);
    }
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
        /**
         * 国外平台标记。
         *
         * 国内网络访问境外站点（尤其 Cloudflare 系）握手本来就慢且不稳，
         * 给 12 秒的总预算经常卡在 TLS 握手上直接超时 —— 明明网络是通的。
         * 标了 foreign 的会给更长的单地址预算，必要时还能走代理。
         */
        public boolean foreign;
        /** 走代理访问（用户在设置里为这个平台开了 VPN 分流） */
        public boolean viaProxy;
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
        /** 国外平台用这个构造：末尾多一个 foreign 标记 */
        public Preset(String id, String name, String url, String paths,
                      String unit, String kind, String hint,
                      String console, String topup, boolean foreign) {
            this(id, name, url, paths, unit, kind, hint, console, topup);
            this.foreign = foreign;
        }
    }

    /** 通用解析的候选字段：各家命名不统一，按顺序试到命中为止 */
    private static final String BAL_PATHS =
        "data.balance|balance|data.available_balance|available_balance"
        + "|data.total_balance|total_balance|data.remain|remain|remaining"
        + "|data.credit|credit|data.amount|amount|data.quota|quota"
        + "|data.available|available|data.money|money|data.balance_amount";

    public static final Preset[] PRESETS = {
        /* WorkBuddy 网关：本机自建服务（CodeBuddy 账号 → OpenAI 兼容网关）。
           特点：①地址由用户自己填（可能换端口 / 局域网别机）②余额接口只收 POST
           ③服务没跑时查不到，需要提示 + 一键拉起。
           url 留空 —— 真实地址从 Key.baseUrl 来，走专门分支。 */
        /* kind 用 asset：积分不是钱，不参与「余额统计」与消耗计算（见 countsInTotal） */
        new Preset("workbuddy", "WorkBuddy 网关", "", "", "积分", "asset",
                   "自建网关地址（如 http://127.0.0.1:7863）+ 网关 api_key",
                   "", ""),
        new Preset("deepseek", "DeepSeek", "", "", "CNY", "balance",
                   "platform.deepseek.com → API Keys",
                   "https://platform.deepseek.com/usage",
                   "https://platform.deepseek.com/top_up"),
        new Preset("openrouter", "OpenRouter", "", "", "USD", "balance",
                   "openrouter.ai → Keys。余额 = 累计充值 − 已用",
                   "https://openrouter.ai/settings/keys",
                   "https://openrouter.ai/credits",
                   true),
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
                   "https://novita.ai/billing",
                   true),
        new Preset("fireworks", "Fireworks AI", "", "", "USD", "balance",
                   "fireworks.ai → API Keys",
                   "https://fireworks.ai/account/billing",
                   "https://fireworks.ai/account/billing",
                   true),
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
        /* 小米 MiMo：官方**没有** API Key 可查的余额接口（api.xiaomimimo.com 只有
           /v1/models 等推理接口，所有 billing/user 路径实测 404）。余额只在控制台，
           而控制台的 /api/v1/balance 是小米账号会话音（account.xiaomi.com 登录）鉴权，
           Bearer sk-xxx 一律 401。所以这里 url 留空 —— 走「App 内登录」：
           MimoLoginActivity 用 WebView 登录后取出 platform 域 cookie，
           再由下面的专用分支带 Cookie 调 /api/v1/balance。 */
        new Preset("mimo", "小米 MiMo", "", "", "CNY", "balance",
                   "platform.xiaomimimo.com → API Keys。余额需在下方点「登录小米账号」后自动查询",
                   "https://platform.xiaomimimo.com/console/balance",
                   "https://platform.xiaomimimo.com/console/recharge"),
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

        /** 配置完备吗？—— 静态文本模式不用填 URL，其余要有 URL */
        public boolean ready() {
            return name.length() > 0
                    && (text.length() > 0 || url.length() > 0);
        }
    }

    public static List<Custom> loadCustom(Context ctx) {
        List<Custom> list = new ArrayList<Custom>();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(sp.getString("custom_json", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Custom c = customFromJson(o, false);   // false = 存储态，key 是密文，要解
                /* ⚠️ 早先这里是「有 Key 才算配置好」，结果只填了接口地址、
                   不带 Key 的 OpenAI 兼容平台会被静默丢掉 ——
                   表现是"添加成功，但设置页和主界面都没有它"。
                   Key 本来就是可选的（很多自建网关不需要 Key），
                   所以改成看 ready()：有地址或有静态文本就算数。 */
                if (c.ready()) list.add(c);
            }
        } catch (Exception ignored) { }
        return list;
    }

    public static void saveCustom(Context ctx, List<Custom> list) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            try {
                arr.put(customToJson(list.get(i), false));   // 存储态：key 加密
            } catch (Exception ignored) { }
        }
        /* 同步落盘：这是用户刚填好的配置，apply() 异步写盘时若进程被回收
           就可能丢 —— 表现同样是"添加了但下次进来没有" */
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putString("custom_json", arr.toString()).commit();
    }

    /**
     * 自定义平台 → JSON。
     *
     * @param plain true = 导出用（key 明文，换台设备才用得上）；
     *              false = 落盘用（key 走 KeyVault 加密）
     * 存储与导出共用这一份，免得加字段时漏改一处。
     */
    public static JSONObject customToJson(Custom c, boolean plain) {
        JSONObject o = new JSONObject();
        try {
            o.put("name", c.name);
            o.put("url", c.url);
            o.put("key", plain ? c.key : KeyVault.enc(c.key));
            o.put("path", c.path);
            o.put("unit", c.unit);
            o.put("kind", c.kind);
            o.put("suffix", c.suffix);
            o.put("text", c.text);
            o.put("threshold", c.threshold);
        } catch (Exception ignored) { }
        return o;
    }

    /** @param stored true = 来自落盘（key 是密文，需要解密） */
    public static Custom customFromJson(JSONObject o, boolean stored) {
        Custom c = new Custom();
        c.name = o.optString("name", "");
        c.url = o.optString("url", "");
        String k = o.optString("key", "");
        c.key = stored ? KeyVault.dec(k) : k;
        c.path = o.optString("path", "");
        c.unit = o.optString("unit", "CNY");
        c.kind = o.optString("kind", "balance");
        c.suffix = o.optString("suffix", "");
        c.text = o.optString("text", "");
        c.threshold = o.optDouble("threshold", 0);
        return c;
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
        public long subEndMs = 0;       // 订阅制最近到期时间戳（按到期天数报警用）
        public int mergeCount = 1;      // 合并了多少个密钥（同平台，卡片按平台合并时用）
        public double usageTokens = -1; // usage 平台 token 用量（千token，-1=无数据）
        public String debug = "";       // 诊断：原始应答截断（仅排查用）
        /** 平台本身没数据可查（MiMo 未登录 / 登录过期）—— 别拿这种 0 值去记快照、画曲线 */
        public boolean noData;
        /** 国外平台：给更长的请求预算 */
        public boolean foreign;
        /** 走代理访问 */
        public boolean viaProxy;

        /* ---- WorkBuddy 网关专用 ---- */
        /** 网关没在运行（或地址没填）—— 界面据此显示「拉起网关」按钮，而不是当成查询失败 */
        public boolean gatewayDown;
        /** 该条目的网关地址，拉起时要用 */
        public String gatewayUrl = "";
        /** 剩余百分比（-1 = 不适用）。积分有"总量"概念才画得出来，钱的平台用不上 */
        public int pctLeft = -1;
        /** 该平台有"总额度"分母（积分制），数据未到时 UI 可显示占位而不是留空。 */
        public boolean hasQuota = false;
        /** 覆盖单位显示（网关是"积分"不是货币，不能套 ¥ 符号） */
        public String unitOverride = "";
        /** 金额是折算估算值（积分 × 折算率），界面标「估」 */
        public boolean estimated = false;

        /**
         * 字段级副本。
         *
         * 为什么需要它：账本快照必须按「合并前的原始条目」记录（用 Key id），
         * 而结果汇总时会给 Item 写 `id = platform` 做平台级合并 ——
         * 如果两份列表引用同一批对象，那次改写就会**把原始条目一起改掉**，
         * 快照从此按 platform 记账，统计页按 Key id 查询自然什么都查不到。
         */
        public Item copy() {
            Item o = new Item();
            o.id = id; o.platform = platform; o.label = label; o.tag = tag;
            o.kind = kind; o.amount = amount; o.conv = conv; o.rows = rows;
            o.error = error; o.cny = cny; o.bal = bal; o.threshold = threshold;
            o.gatewayDown = gatewayDown; o.gatewayUrl = gatewayUrl;
            o.unitOverride = unitOverride;
            o.alertKey = alertKey; o.low = low; o.ok = ok; o.subEndMs = subEndMs;
            o.mergeCount = mergeCount; o.usageTokens = usageTokens;
            o.debug = debug; o.noData = noData; o.foreign = foreign;
            o.viaProxy = viaProxy;
            return o;
        }
    }

    public static class Result {
        public double rate = 7.1;
        /**
         * 这份结果是**复用的缓存**，不是刚抓到的。
         *
         * 为什么必须区分：并发闸门在"已有抓取在跑"时会直接返回上次的缓存，
         * 这本身是对的（避免叠加请求）；但账本记账**只能记新读到的数据** ——
         * 拿旧缓存记账会把当前时间点的余额改写成过去的旧值，
         * 曲线上就出现一个凭空回跳的假点（实测 DeepSeek 因此被写成
         * 14.52，而当时真实余额是 24.11）。
         */
        public boolean fromCache;
        /** 合并前的逐密钥原始条目（供账本按密钥记录快照用） */
        public java.util.List<Item> rawItems;
        public double totalCny = 0;
        /** 总额里含有折算来的估值（积分 × 折算率）→ 界面要给总额标 ≈ */
        public boolean totalEstimated = false;
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

    /**
     * 最近一次响应里的 `subscription-userinfo`（机场流量信息）。
     *
     * 为什么用 ThreadLocal 而不是给一串方法加"输出响应头"的参数：
     * 这条链路（手写 socket + 多地址回退 + 代理回退）是实测能用的，
     * 而换成 HttpURLConnection 在部分机场上会被直接掐断
     * （实测 SSLHandshakeException: connection closed）。所以只在这里
     * "顺手"把头带出来，主流程一行不动。
     */
    public static final ThreadLocal<String> SUB_USERINFO = new ThreadLocal<String>();

    /**
     * 订阅请求专用的 User-Agent。
     *
     * **机场普遍按 UA 决定返回什么格式**：带 clash 字样 → 返回 Clash YAML；
     * 其它 UA → 返回 base64 编码的节点串（v2ray/ss 通用订阅）。
     * 用默认 UA 去要 Clash 配置，拿回来一堆 base64，就被判成"不是 Clash 订阅"。
     *
     * 只对订阅请求生效（用 ThreadLocal 传），不动余额查询那条链路 ——
     * 各家 API 的 WAF 对 UA 的偏好不一样，没必要一起去改。
     */
    public static final ThreadLocal<String> SUB_UA = new ThreadLocal<String>();

    /**
     * 订阅请求专用的 Accept。
     *
     * 余额接口都是 JSON（用 application/json 没问题），但**订阅要的是 YAML** ——
     * 拿 application/json 去要 YAML，部分机场（尤其 Cloudflare 后面那些）
     * 会直接给个错误响应甚至掐掉连接。订阅这一路用通配 Accept 最稳
     * （写死在代码里是 "星号斜杠星号"，这里注释没法直接写那两个字符）。
     */
    public static final ThreadLocal<String> SUB_ACCEPT = new ThreadLocal<String>();

    /**
     * 最近一次响应体的读取情况（诊断用）：走的是 chunked 还是定长、
     * 多少字符、**含多少个换行**。
     *
     * 为什么要专门记这个：订阅 YAML 全靠换行分层，一旦被压成一行就解析不出
     * 任何节点（实测机场返回 22KB、0 个换行 → 节点数 0）。
     * 而这条读取链路有好几处（直连 / 代理 / chunked / 定长），
     * 不记下来根本不知道该修哪一处。
     */
    public static volatile String LAST_BODY_INFO = "";

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
        return get(url, bearer, null, timeoutMs);
    }

    /** 带 Cookie 的 GET（小米 MiMo 控制台余额走这条：会话鉴权，不是 Bearer） */
    private static String get(String url, String bearer, String cookie, int timeoutMs) throws Exception {
        return get(url, bearer, cookie, timeoutMs, false);
    }

    /**
     * @param forceDirect true = 这一跳强制直连（用于代理失败后的回退）
     */
    private static String get(String url, String bearer, String cookie, int timeoutMs,
                              boolean forceDirect) throws Exception {
        URL u = new URL(url);
        String host = u.getHost();
        boolean https = "https".equalsIgnoreCase(u.getProtocol());
        int port = u.getPort() > 0 ? u.getPort() : (https ? 443 : 80);
        String path = u.getFile();

        /* 走代理的请求不解析 DNS、也不做多地址回退 —— 域名解析和目标连接
           都交给内核去办（它自己有 DNS 与节点选择逻辑），我们只管把请求丢给它。 */
        if (useProxyNow() && !forceDirect) {
            try {
                return getThroughProxy(host, port, https, path, bearer, cookie, timeoutMs);
            } catch (StatusException se) {
                throw se;                          // 服务器已应答（401/429 等），不必再试直连
            } catch (Exception e) {
                /* 代理这条路失败就**回退直连**再试一次。
                   实测过：某些机场节点会被 Cloudflare 拒（openrouter 这类就挂在 CF 上），
                   表现为 TLS 握手阶段 connection closed —— 而同一时刻直连反而是通的。
                   有这条回退，最坏情况退化成"代理没用上"，而不会变成"平台查不出来"。 */
                diag(null, "代理失败，回退直连 " + host + "：" + e);
                try {
                    return get(url, bearer, cookie, timeoutMs, true);
                } catch (Exception e2) {
                    throw new Exception("代理与直连均失败（代理：" + e.getMessage()
                            + " / 直连：" + e2.getMessage() + "）");
                }
            }
        }

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
        /* 剔除内核的 fake-ip。
           Clash 接管 DNS 后，分流里走代理的域名会被解析成 198.18.0.0/15 的假地址 ——
           那个地址只在内核内部有意义，拿它去"直连"必然失败，而且会白等一整个超时
           （实测日志里就是 198.18.0.34=SSLHandshakeException）。 */
        java.util.Iterator<InetAddress> fakeIt = ordered.iterator();
        int fakes = 0;
        while (fakeIt.hasNext()) {
            if (isFakeIp(fakeIt.next())) { fakeIt.remove(); fakes++; }
        }
        if (fakes > 0) {
            diag(null, "剔除 " + fakes + " 个内核 fake-ip（该域名分流走代理）" + host);
        }
        if (ordered.isEmpty() && !forceDirect && useProxyNow() && Clash.proxyAlive()) {
            /* 解析结果全是假地址 → 直连这条路本来就不通，直接走代理省时间 */
            try {
                return getThroughProxy(host, port, https, path, bearer, cookie, timeoutMs);
            } catch (Throwable ig) { }
        }
        if (ordered.isEmpty()) return getViaHost(url, bearer, cookie, timeoutMs);

        StringBuilder tried = new StringBuilder();
        /* 首选地址要吃满大部分预算：OpenRouter/七牛云实测稳定要 4~6s，
           按 timeoutMs/2 分（8000 → 4000）会让它们次次超时。
           地址已按本机出口协议排好，首选通常一次就通，所以给它 3/4 预算。 */
        int per = Math.max(3000, timeoutMs * 3 / 4);
        /* ★ 总预算（deadline）：timeoutMs 是**整个请求**的预算，不是每个地址的额度。
           原实现里每个地址能吃 per，超时后同地址还会再吃一次 per ——
           8 个地址理论上最多 48 秒，实测汇率请求把 3 秒预算拖成了 22.5 秒。
           结果就是"调用方以为 3 秒放弃，实际卡了 20 多秒"，刷新被整体拖慢。 */
        final long deadline = System.currentTimeMillis() + Math.max(2500, timeoutMs);
        for (int i = 0; i < ordered.size(); i++) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 400) { tried.append("总预算耗尽; "); break; }
            int eff = (int) Math.min(per, left);
            long t0 = System.currentTimeMillis();
            String ip = ordered.get(i).getHostAddress();
            try {
                String r = getViaIp(ordered.get(i), host, port, https, path, bearer, cookie, eff);
                Log.i(TAG, "  ✅ " + ip + " " + (System.currentTimeMillis() - t0) + "ms");
                return r;
            } catch (StatusException se) {
                Log.w(TAG, "  ⚠️ " + ip + " 服务端应答 " + (System.currentTimeMillis() - t0) + "ms: " + se.getMessage());
                throw se;                                  // 服务器已应答，换地址没意义
            } catch (java.net.SocketTimeoutException ste) {
                /* 间歇性慢接口（七牛账单实测 0.2s~9s 随机抖动）：同地址立即重试一次，
                   重试大概率命中快的那次；仍慢才换下一个地址。 */
                Log.w(TAG, "  ⏳ " + ip + " 超时 " + (System.currentTimeMillis() - t0) + "ms，重试一次");
                long left2 = deadline - System.currentTimeMillis();
                if (left2 <= 400) { tried.append(ip).append("=超时且预算尽; "); break; }
                try {
                    String r2 = getViaIp(ordered.get(i), host, port, https, path, bearer, cookie,
                                         (int) Math.min(per, left2));
                    Log.i(TAG, "  ✅(重试) " + ip + " " + (System.currentTimeMillis() - t0) + "ms");
                    return r2;
                } catch (Exception e2) {
                    tried.append(ip).append('=').append(e2.getClass().getSimpleName()).append("+retry; ");
                }
            } catch (Exception e) {
                Log.w(TAG, "  ❌ " + ip + " " + (System.currentTimeMillis() - t0) + "ms: " + e);
                /* 把每个地址的失败原因攒进异常消息 —— logcat 在很多 ROM 上读不到（本机就是），
                   这样写进诊断日志才看得见到底卡在哪一步。 */
                tried.append(ip).append('=').append(e.getClass().getSimpleName()).append("; ");
            }
        }
        throw new Exception("所有地址均失败 [" + (tried.length() == 0 ? "无" : tried.toString().trim()) + "]");
    }

    /** 数一下字符串里有几个换行 */
    private static int countNl(String s) {
        if (s == null) return -1;
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    /** 内核 fake-ip 段：Clash 默认用 198.18.0.0/15，这类地址只有内核自己认识 */
    private static boolean isFakeIp(InetAddress a) {
        try {
            String ip = a.getHostAddress();
            return ip != null && (ip.startsWith("198.18.") || ip.startsWith("198.19."));
        } catch (Throwable t) {
            return false;
        }
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

    /**
     * 「这次请求是否走代理」用线程局部变量传，而不是一层层加参数。
     *
     * 理由：get() → getViaIp() 这条链路上有五六层，加参数要改所有调用点；
     * 而抓取本来就是「每个平台一个线程」，线程局部天然就是「按平台分流」的粒度。
     */
    private static final ThreadLocal<Boolean> VIA_PROXY = new ThreadLocal<Boolean>();

    static boolean useProxyNow() {
        Boolean b = VIA_PROXY.get();
        return b != null && b.booleanValue();
    }

    /** 带代理的 GET：直接暴露给 Clash 的连通性自检用 */
    public static String getViaProxy(String url, String bearer, String cookie,
                                     int timeoutMs) throws Exception {
        VIA_PROXY.set(Boolean.TRUE);
        try {
            return get(url, bearer, cookie, timeoutMs);
        } finally {
            VIA_PROXY.remove();
        }
    }

    /**
     * 走本地 Clash 代理（HTTP CONNECT 隧道）。
     *
     * 先连到 127.0.0.1:7890 发 CONNECT 建隧道，隧道通了之后再在它上面做 TLS 握手 ——
     * 关键点是 **SNI 仍然填真实目标域名**（不是 127.0.0.1），
     * 否则对方服务器会按错误的主机名给证书，握手直接失败。
     */
    private static String getThroughProxy(String host, int port, boolean https,
                                          String path, String bearer, String cookie,
                                          int timeoutMs) throws Exception {
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress("127.0.0.1", Clash.PROXY_PORT),
                    Math.min(timeoutMs, 4000));
            sock.setSoTimeout(timeoutMs);
            Socket io = sock;
            if (https) {
                /* 建 CONNECT 隧道：告诉代理「我要连 host:port」，它回 200 就通了 */
                java.io.OutputStream os = sock.getOutputStream();
                String req = "CONNECT " + host + ":" + port + " HTTP/1.1\r\n"
                        + "Host: " + host + ":" + port + "\r\n"
                        + "Proxy-Connection: keep-alive\r\n\r\n";
                os.write(req.getBytes("UTF-8"));
                os.flush();
                java.io.BufferedInputStream in = new java.io.BufferedInputStream(sock.getInputStream());
                String status = readLine(in);
                if (status == null || status.indexOf(" 200") < 0) {
                    throw new StatusException("代理未能建立隧道 (" + status + ")");
                }
                while (true) {                       // 吃掉 CONNECT 的响应头
                    String l = readLine(in);
                    if (l == null || l.length() == 0) break;
                }
                SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket ssl = (SSLSocket) sf.createSocket(sock, host, port, true);
                ssl.startHandshake();
                io = ssl;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
            sb.append("Host: ").append(host).append("\r\n");
            String _acc = SUB_ACCEPT.get();
            sb.append("Accept: ").append(_acc == null ? "application/json" : _acc).append("\r\n");
            sb.append("Accept-Encoding: identity\r\n");
            String _ua = SUB_UA.get();
            sb.append("User-Agent: ").append(_ua == null ? "MinisWidget/1.2" : _ua).append("\r\n");
            sb.append("Connection: close\r\n");
            if (bearer != null) sb.append("Authorization: Bearer ").append(bearer).append("\r\n");
            if (cookie != null && cookie.length() > 0) sb.append("Cookie: ").append(cookie).append("\r\n");
            sb.append("\r\n");
            java.io.OutputStream os2 = io.getOutputStream();
            os2.write(sb.toString().getBytes("UTF-8"));
            os2.flush();

            java.io.BufferedInputStream in2 = new java.io.BufferedInputStream(io.getInputStream());
            String st = readLine(in2);
            int code = 0;
            if (st != null) {
                String[] p = st.split(" ");
                if (p.length > 1) { try { code = Integer.parseInt(p[1].trim()); } catch (Exception ig) { } }
            }
            boolean chunked = false;
            String line;
            while ((line = readLine(in2)) != null && line.length() > 0) {
                String l = line.toLowerCase();
                if (l.startsWith("transfer-encoding") && l.indexOf("chunked") >= 0) chunked = true;
                else if (l.startsWith("subscription-userinfo")) captureUserInfo(line);
            }
            String body = chunked ? readChunked(in2) : readRest(in2);
            LAST_BODY_INFO = "代理 chunked=" + chunked
                    + " len=" + body.length() + " nl=" + countNl(body);
            if (code < 200 || code >= 300) {
                if (code == 401 || code == 403) throw new StatusException("Key 无效或未授权 (" + code + ")");
                if (code == 429) throw new StatusException("请求过于频繁 (429)");
                throw new StatusException("HTTP " + code);
            }
            return body;
        } finally {
            try { sock.close(); } catch (Exception ig) { }
        }
    }

    private static String getViaIp(InetAddress addr, String host, int port, boolean https,
                                   String path, String bearer, String cookie, int timeoutMs) throws Exception {
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
            String _acc = SUB_ACCEPT.get();
            sb.append("Accept: ").append(_acc == null ? "application/json" : _acc).append("\r\n");
            sb.append("Accept-Encoding: identity\r\n");
            String _ua = SUB_UA.get();
            sb.append("User-Agent: ").append(_ua == null ? "MinisWidget/1.2" : _ua).append("\r\n");
            sb.append("Connection: close\r\n");
            if (bearer != null) sb.append("Authorization: Bearer ").append(bearer).append("\r\n");
            if (cookie != null && cookie.length() > 0) sb.append("Cookie: ").append(cookie).append("\r\n");
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
                else if (l.startsWith("subscription-userinfo")) captureUserInfo(line);
            }
            String body = chunked ? readChunked(in) : readRest(in);
            LAST_BODY_INFO = "直连 chunked=" + chunked
                    + " len=" + body.length() + " nl=" + countNl(body);
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

    /** 记下响应头里的流量信息（只有订阅请求会带这个头，别的不受影响） */
    private static void captureUserInfo(String headerLine) {
        try {
            int c = headerLine.indexOf(':');
            if (c > 0) SUB_USERINFO.set(headerLine.substring(c + 1).trim());
        } catch (Throwable ignored) { }
    }

    /* ==================== WorkBuddy 网关支持 ==================== */

    /**
     * 该条目是否计入「总额」。
     *
     * 总额是人民币汇总，只应包含真正的钱。积分性质跟钱不同（1 积分 ≠ 1 元），
     * 混进去会让总额变成一个看不懂的数字 —— 所以默认排除。
     *
     * 但"要不要算"最终由用户在卡片菜单里决定（开关存在 StatsOpt），
     * 开了统计就按折算率折成人民币参与汇总，界面上会标「估」。
     */
    public static boolean countsInTotal(Context ctx, Item it) {
        if (it == null) return false;
        if ("balance".equals(it.kind)) return true;   // 本来就是钱，天然入账
        return StatsOpt.inStats(ctx, it.platform);    // 积分类看用户开关
    }

    /**
     * 探测网关是否在运行。
     *
     * 用 /healthz 而不是余额接口，理由：①它不需要 api_key，密钥没填也能先判断进程在不在
     * ②它极轻（几乎不查上游账号），适合"每次刷新都探一下"。
     * 任何异常都当"没在跑"处理 —— 探活失败本来就不该区分是拒绝连接还是超时。
     */
    public static boolean probeGateway(String base, int timeoutMs) {
        try {
            String raw = getViaHost(normalizeBase(base) + "/healthz", null, null, timeoutMs, "GET", null);
            return raw != null && raw.indexOf("\"service\"") >= 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 服务地址规范化：补协议、去尾部斜杠。用户常只填 127.0.0.1:7863 */
    public static String normalizeBase(String base) {
        if (base == null) return "";
        String s = base.trim();
        if (s.length() == 0) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** POST 一小段 JSON 并取回响应文本（网关的余额接口只收 POST） */
    private static String postJson(String url, String bearer, String body, int timeoutMs) throws Exception {
        return getViaHost(url, bearer, null, timeoutMs, "POST",
                body == null || body.length() == 0 ? "{}" : body);
    }

    /** 积分是整数，别显示成 2464.0 */
    /** 折算率显示：0.02 别写成 0.0200000001 */
    private static String fmtRate(double v) {
        String s = String.format(java.util.Locale.US, "%.4f", v);
        while (s.endsWith("0") && s.indexOf('.') < s.length() - 2) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 积分整数显示：别显示成 2464.0 */
    private static String fmtInt(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.format("%.1f", v);
    }

    /**
     * 到期时间 → MM-dd（非今年补年份）。
     *
     * 网关返回的是 ISO 8601 字符串（2026-10-31T23:59:59+08:00），不是时间戳数字。
     * 直接 substring 取 yyyy-MM-dd 就行 —— 前面固定 10 个字符，不用引 SimpleDateFormat
     * （后者还得处理时区，反而更容易出错）。
     */
    private static String fmtExpiry(String iso) {
        if (iso == null) return "";
        String s = iso.trim();
        if (s.length() < 10) return s;
        String date = s.substring(0, 10);                    // yyyy-MM-dd
        if (date.charAt(4) != '-' || date.charAt(7) != '-') return s;
        String y = date.substring(0, 4);
        String md = date.substring(5);                       // MM-dd
        String nowY = new java.text.SimpleDateFormat("yyyy").format(new java.util.Date());
        return nowY.equals(y) ? md : y + "-" + md;
    }

    /* ==================== 通用解析 ==================== */

    /** 解析不出任何地址时的兜底：交回系统默认行为。 */
    private static String getViaHost(String url, String bearer, String cookie, int timeoutMs) throws Exception {
        return getViaHost(url, bearer, cookie, timeoutMs, "GET", null);
    }

    /**
     * 兜底路径（可指定方法与请求体）。
     *
     * 为什么要 POST：WorkBuddy 网关的余额接口 /panel/api/balance_all 只接受 POST
     * （GET 返回 405）。这是本工具里唯一一个非 GET 的平台，所以单开一个参数，
     * 不改动既有调用点的行为。
     */
    private static String getViaHost(String url, String bearer, String cookie, int timeoutMs,
                                     String method, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestMethod(method == null ? "GET" : method);
        String _acc2 = SUB_ACCEPT.get();
        c.setRequestProperty("Accept", _acc2 == null ? "application/json" : _acc2);
        String _ua2 = SUB_UA.get();
        c.setRequestProperty("User-Agent", _ua2 == null ? "MinisWidget/1.2" : _ua2);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (cookie != null && cookie.length() > 0) c.setRequestProperty("Cookie", cookie);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(body.getBytes("UTF-8").length);
            java.io.OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();
        }
        try {
            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                String line;
                /* ★ 换行必须补回来！
                   readLine() 会把行尾的 \n 吃掉，直接 append 得到的是"一行到底"的文本。
                   对 JSON 无所谓（JSON 不靠换行分层），但对 **YAML 是致命的** ——
                   订阅配置全是靠缩进和换行分层的，压成一行后一个节点都解析不出来
                   （实测机场返回 22KB、0 个换行 → 节点数 0、内核 0 个策略组）。
                   这个坑一直埋着，直到 fake-ip 过滤让解析结果为空、改走这条兜底路径才暴露。 */
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
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

    /** WebCustom 自动识别复用：同一套「常见余额字段名 → 深度找数」逻辑 */
    /** 点卡片时的跳转目标（WebCustom.clickAction 可取值） */
    public static final String CLICK_CONSOLE = "console";
    public static final String CLICK_RECHARGE = "recharge";
    public static final String CLICK_HOME = "home";
    public static final String CLICK_SETTINGS = "settings";
    public static final String CLICK_STATS = "stats";
    public static final String CLICK_NONE = "none";

    /** 该平台在设置页里有没有可编辑的凭据（决定点击卡片跳哪） */
    public static boolean hasCredentials(Context c, String platform) {
        if (platform == null) return false;
        if (platform.startsWith("custom")) {
            try {
                String n = platform.startsWith("custom:")
                        ? platform.substring(7) : platform.substring(6);
                int idx = Integer.parseInt(n);
                List<Custom> cs = loadCustom(c);
                if (idx >= 0 && idx < cs.size()) {
                    Custom cu = cs.get(idx);
                    return cu.key.length() > 0 || cu.url.length() > 0;
                }
            } catch (Throwable ignored) { }
            return false;
        }
        if (platform.startsWith("web")) {
            try {
                String n = platform.startsWith("web:")
                        ? platform.substring(4) : platform.substring(3);
                int idx = Integer.parseInt(n);
                List<WebCustom> ws = WebCustom.loadAll(c);
                if (idx >= 0 && idx < ws.size()) return ws.get(idx).url.length() > 0;
            } catch (Throwable ignored) { }
            return false;
        }
        /* 内置平台：看有没有配过 Key */
        return hasConfiguredKey(c, platform);
    }

    public static double pickForWeb(JSONObject o) {
        return pick(o);
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
        if (ctx == null) return;      // 少数深层调用点拿不到 Context，静默跳过即可
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

    /**
     * 并发闸门：同一时刻只允许一轮抓取在跑。
     *
     * 以前没有这道闸 —— 前台自动刷新、手动点刷新、小组件广播、RefreshReceiver
     * 各走各的，一波操作就能叠出好几轮 fetch；每轮又按平台数开线程，
     * 再叠加汇率最长 20+ 秒的阻塞，线程越堆越多，最后把进程拖垮
     * （用户反馈的"刷着刷着就闪退"就是这个）。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean IN_FLIGHT =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static Result fetch(Context ctx, int timeoutMs) {
        return fetch(ctx, timeoutMs, false);
    }

    /**
     * @param background 后台刷新（闹钟唤醒）。
     *   true 时走**精简模式**，省电：
     *     - 跳过汇率请求，直接用缓存值（汇率日内波动极小，对余额展示没有影响，
     *       但这一跳实测可能吃掉好几秒的 radio 活跃时间）
     *     - 调用方不再做"全失败重试"（Doze 下网络本就常不通，重试纯浪费）
     */
    public static Result fetch(Context ctx, int timeoutMs, boolean background) {
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            /* 已有抓取在进行：直接复用上次结果，绝不叠加第二轮 */
            Result cached = WidgetCache.read(ctx);
            if (cached != null && cached.items != null && cached.items.size() > 0) {
                diag(ctx, "已有抓取在进行，复用缓存结果（不记账）");
                cached.fromCache = true;            // ★ 标记为缓存，禁止记账
                return cached;
            }
            /* 没有可用缓存就短暂等一会儿，多半能等到正在跑的那轮结束 */
            for (int i = 0; i < 40 && IN_FLIGHT.get(); i++) {
                try { Thread.sleep(100); } catch (Exception ig) { }
            }
            Result c2 = WidgetCache.read(ctx);
            if (c2 != null && c2.items != null && c2.items.size() > 0) {
                c2.fromCache = true;                // ★ 同上
                return c2;
            }
            Result empty = new Result();
            empty.items = new ArrayList<Item>();
            empty.rawItems = new ArrayList<Item>();
            empty.fromCache = true;                 // 空结果更不能记账
            return empty;
        }
        try {
            return fetchInner(ctx, timeoutMs, background);
        } finally {
            IN_FLIGHT.set(false);
        }
    }

    private static Result fetchInner(Context ctx, int timeoutMs, boolean background) {
        long t0 = System.currentTimeMillis();
        Result res = new Result();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        diag(ctx, "fetch 开始，预算 " + timeoutMs + "ms");

        /* 汇率：各平台共用，先拿（很快，通常 200~800ms）。
           拿不到就沿用上次成功的缓存值，再退到内置默认 7.1 —— 硬编码会偏离真实汇率。 */
        try {
            res.rate = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getFloat("last_rate", 7.1f);
        } catch (Throwable ig) { }
        if (background) {
            /* 省电：后台不查汇率，直接用缓存。
               汇率一天波动通常不到 0.5%，对"余额折合人民币"的展示没有实际影响；
               而对 USD 平台之外的抓取完全无关 —— 白白多一跳网络，不如省掉。 */
            diag(ctx, "后台精简刷新：跳过汇率查询，用缓存 " + res.rate);
        } else
        try {
            String j = get(RATE_URL,
                           null, Math.min(timeoutMs, 3000));
            JSONObject r = new JSONObject(j).optJSONObject("rates");
            if (r != null) {
                double v = num(r, "CNY");
                if (!Double.isNaN(v) && v > 0) {
                    res.rate = v;
                    try {
                        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                .edit().putFloat("last_rate", (float) v).apply();
                    } catch (Throwable ig) { }
                }
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
            /* 没配置的平台**不出现在余额列表里**。
               原来把百炼/魔搭/MiMo 当"展示型"始终显示，结果它们在卡片区摆着
               "见控制台""免费""需登录"—— 用户会以为这些平台已经有数据了。
               要看有哪些平台、去哪里配置，走「设置 → API 密钥与平台」。

               MiMo 是例外：它的凭据是**登录态**而不是 API Key，
               所以"登录过"就算已配置。 */
            boolean mimoReady = "mimo".equals(plat) && mimoSession(ctx).length() > 0;
            if (!ak.isConfigured() && !mimoReady) continue;
            if (ak.hideCard) { diag(ctx, "隐藏卡片·跳过 " + ak.id); continue; }   // 用户在「隐藏 API」里关掉卡片显示的，不出卡片/小组件

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
                /* 自定义平台不预设是否国外，交给用户在设置里勾 */
                it.viaProxy = Clash.platformViaProxy(ctx, it.platform, false);
                items.add(it);
                custs.add(c);
                keys.add(ak.key);
                apiKeys.add(ak);
                res.configured++;
            } else {
                Preset p = presetOf(plat);
                if (p == null) continue;
                /* 卡片是**平台粒度**的（同平台多密钥合并成一张），所以标题只写平台名。
                   以前在这里挂上某一个密钥的备注（"DeepSeek · minis"）会误导：
                   那张卡代表的是整个平台，而备注只属于其中一个密钥。
                   想区分具体密钥，走「查看密钥」——那里本就按密钥逐条列。 */
                it.label = p.name;
                it.tag = p.unit;
                it.kind = p.kind;
                it.foreign = p.foreign;
                it.viaProxy = p.viaProxy;
                items.add(it);
                custs.add(null);
                keys.add(ak.key);
                apiKeys.add(ak);
                res.configured++;
            }
        }

        /* 这里原来有一段"展示型平台补位"：百炼 / 魔搭 / MiMo 即使没填 Key
           也硬塞一张卡，理由是"方便点官网"。
           但那会让未配置的平台出现在余额列表里，看着像已经有数据了
           （用户反馈："没填 Key 就不该显示"）。现在一律不补 ——
           要看平台清单、去配置，走「设置 → API 密钥与平台」。 */

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

        /* 高级自定义平台（web:N）：任意网页/接口抓值，与 custom:N 互不干扰 */
        List<WebCustom> webs = WebCustom.loadAll(ctx);
        final List<WebCustom> webs2 = new ArrayList<WebCustom>();
        for (int i = 0; i < webs.size(); i++) {
            WebCustom w = webs.get(i);
            res.configured++;
            Item it = new Item();
            it.id = "web" + i;
            it.platform = "web:" + i;
            it.threshold = w.threshold;
            it.alertKey = "web:" + (w.name.length() > 0 ? w.name : String.valueOf(i));
            it.label = w.name.length() > 0 ? w.name : "网页平台";
            it.kind = w.kind;
            it.foreign = w.foreign;
            if ("NONE".equals(w.unit)) {
                /* 纯数值（次数/积分/百分号），不参与金额折算 */
                it.tag = "";
                it.unitOverride = w.suffix == null ? "" : w.suffix;
            } else {
                it.tag = "USD".equals(w.unit) ? "USD" : "CNY";
            }
            items.add(it);
            webs2.add(w);
        }
        /* 并行列表对齐：custs/keys/apiKeys 与 items 一一对应，web 项补 null 占位 */
        while (custs.size() < items.size()) custs.add(null);
        while (keys.size() < items.size()) keys.add(null);
        while (apiKeys.size() < items.size()) apiKeys.add(null);

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
            /* web 项按 platform 前缀精确识别（"web:" 开头，不会误伤 workbuddy） */
            final WebCustom w = it.platform != null && it.platform.startsWith("web:")
                    && i < webs2.size() ? webs2.get(i) : null;
            ts[i] = new Thread(new Runnable() {
                public void run() {
                    long ts0 = System.currentTimeMillis();
                    /* 按当前分流模式决定这次请求直连还是走本地代理。
                       抓取是「一个平台一个线程」，所以线程局部变量天然就是平台级粒度。 */
                    boolean viaProxy = Clash.enabled(ctx) && Clash.isRunning()
                            && Clash.shouldProxy(ctx, it.platform, it.foreign);
                    if (viaProxy) VIA_PROXY.set(Boolean.TRUE);
                    try {
                        /* 国外平台给双倍预算：境内访问境外站点常卡在 TLS 握手，
                           12 秒总预算切成 9 秒单地址往往不够，网络明明是通的。 */
                        int budget = it.foreign ? per * 2 : per;
                        if (w != null)            fillWeb(it, w, rate, budget, viaProxy);
                        else if (c == null)       fill(ctx, it, ak, rate, budget);
                        else                      fillCustom(it, c, rate, per);
                        it.ok = true;
                        Log.i(TAG, "平台 " + it.id + " 成功: " + it.amount);
                        diag(ctx, "  OK  " + it.id + " = " + it.amount
                                + ("dashscope".equals(it.platform) && it.rows != null && it.rows.length() > 0
                                        ? " | " + it.rows.replace('\n', ' ') : "")
                                + "   (" + (System.currentTimeMillis() - ts0) + "ms)");
                    } catch (Exception e) {
                        it.ok = false;
                        it.error = (e.getMessage() == null) ? "查询失败" : e.getMessage();
                        it.amount = "—";
                        Log.w(TAG, "平台 " + it.id + " 失败: " + it.error
                                + " | " + e.getClass().getSimpleName());
                        diag(ctx, "  NG  " + it.id + " " + it.error
                                + "   (" + (System.currentTimeMillis() - ts0) + "ms)");
                    } finally {
                        if (viaProxy) VIA_PROXY.remove();   // 线程即将结束，顺手清掉
                    }
                }
            });
            ts[i].start();
        }
        for (int i = 0; i < n; i++) {
            try {
                /* foreign 项的预算是 per*2（并发段给的），join 也得跟着给足，
                   否则那边还没跑完这边就中断了 —— 白抓 */
                ts[i].join((items.get(i).foreign ? per * 2 : per) + 1500L);
                if (ts[i].isAlive()) {
                    /* ★ 预算内没收尾就直接中断：不掐的话它会继续占着 socket 和线程，
                       多轮刷新叠加起来就是线程/内存堆积（闪退的来源之一）。 */
                    ts[i].interrupt();
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
                /* 钱的平台直接加；积分类平台按开关 + 折算率换算（未开则跳过） */
                double add = countsInTotal(ctx, it) ? it.cny : Double.NaN;
                if (!Double.isNaN(add)) {
                    res.totalCny += add;
                    if (it.estimated) res.totalEstimated = true;
                }
            } else {
                res.failed++;
                if (it.amount == null || it.amount.length() == 0) it.amount = "—";
            }
            res.items.add(it);
        }
        /* 平台级合并：同一平台多密钥 → 一张卡片（余额取最大值，避免同账户重复计算） */
        /* ★ 必须**深拷贝**：下面是平台级合并，会给 Item 写 `id = platform`。
           浅拷贝（原实现 `new ArrayList<>(res.items)`）下两份列表指向同一批对象，
           那次改写会连原始条目的 id 一起覆盖 —— 与"账本按密钥记录"的初衷正好相反。 */
        res.rawItems = new java.util.ArrayList<Item>(res.items.size());
        for (int i = 0; i < res.items.size(); i++) res.rawItems.add(res.items.get(i).copy());
        java.util.LinkedHashMap<String, Item> byPlat =
                new java.util.LinkedHashMap<String, Item>();
        for (int i = 0; i < res.items.size(); i++) {
            Item it = res.items.get(i);
            String plat = it.platform == null ? "" : it.platform;
            Item prev = byPlat.get(plat);
            if (prev == null) {
                it.mergeCount = 1;
                byPlat.put(plat, it);
            } else {
                prev.mergeCount++;
                if (it.ok && (!prev.ok || it.bal > prev.bal)) {
                    it.mergeCount = prev.mergeCount;
                    byPlat.put(plat, it);
                }
            }
        }
        res.items = new java.util.ArrayList<Item>(byPlat.values());
        res.totalCny = 0;
        res.failed = 0;
        res.configured = res.items.size();
        for (int i = 0; i < res.items.size(); i++) {
            Item it = res.items.get(i);
            it.id = it.platform;           // 卡片/刷新/预警统一用平台标识
            it.alertKey = it.platform;
            if (it.ok) {
                if (countsInTotal(ctx, it)) {
                    res.totalCny += it.cny;
                    if (it.estimated) res.totalEstimated = true;
                }
                if (it.mergeCount > 1 && it.rows != null) {
                    it.rows = it.rows + "\n（同平台 " + it.mergeCount + " 个密钥，取最大）";
                }
            } else {
                res.failed++;
            }
        }
        diag(ctx, "fetch 结束：成功 " + (res.configured - res.failed) + "/" + res.configured
                + "，耗时 " + (System.currentTimeMillis() - t0) + "ms");
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
        if (ak == null) {   // 卡片现在是平台级：按平台取第一个已配置密钥为代表
            for (int i = 0; i < all.size(); i++) {
                if (it.id.equals(all.get(i).platform) && all.get(i).isConfigured()) {
                    ak = all.get(i); break;
                }
            }
        }
        if (ak == null) {
            /* 高级自定义平台（web:N）没有 KeyStore 记录，按 platform 前缀分流 */
            if (it.id.startsWith("web")) {
                int widx = -1;
                try {
                    String n = it.id.startsWith("web:")
                            ? it.id.substring(4) : it.id.substring(3);
                    widx = Integer.parseInt(n);
                } catch (Exception ig) { }
                List<WebCustom> webs = WebCustom.loadAll(ctx);
                if (widx < 0 || widx >= webs.size()) {
                    it.label = "高级平台";
                    it.error = "这条高级平台已经被删掉了";
                    it.amount = "\u2014";
                    return it;
                }
                WebCustom w = webs.get(widx);
                it.id = "web" + widx;
                it.platform = "web:" + widx;
                it.threshold = w.threshold;
                it.label = w.name.length() > 0 ? w.name : "网页平台";
                it.kind = w.kind;
                it.foreign = w.foreign;
                if ("NONE".equals(w.unit)) {
                    it.tag = "";
                    it.unitOverride = w.suffix == null ? "" : w.suffix;
                } else {
                    it.tag = "USD".equals(w.unit) ? "USD" : "CNY";
                }
                double rate2 = 7.1;
                try {
                    rate2 = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .getFloat("last_rate", 7.1f);
                } catch (Throwable ig) { }
                boolean vpx = Clash.enabled(ctx) && Clash.isRunning()
                        && Clash.shouldProxy(ctx, it.platform, w.foreign);
                try {
                    fillWeb(it, w, rate2, w.foreign ? timeoutMs * 2 : timeoutMs, vpx);
                    it.ok = true;
                } catch (Exception e) {
                    it.ok = false;
                    it.error = (e.getMessage() == null) ? "查询失败" : e.getMessage();
                    it.amount = "\u2014";
                }
                return it;
            }
            /* 展示型平台（百炼 / 魔搭）可以没有 Key 记录 */
            Preset p0 = presetOf(it.id);
            if (p0 != null && ("dashscope".equals(p0.id) || "modelscope".equals(p0.id))) {
                it.platform = p0.id;
                it.label = p0.name;
                it.tag = p0.unit;
                it.kind = p0.kind;
                try { fill(ctx, it, null, 7.1, timeoutMs); it.ok = true; }  // 修改：传递 null
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

        // 汇率（USD 项换算要用）：优先上次成功的缓存，避免硬编码偏离真实汇率
        double rate = 7.1;
        try {
            rate = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getFloat("last_rate", 7.1f);
        } catch (Throwable ig) { }
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
                /* 同上：单平台刷新出来的也是平台级卡片，标题不带密钥备注 */
                it.label = p.name;
                it.kind = p.kind;
                it.tag = p.unit;
                it.foreign = p.foreign;
                it.viaProxy = p.viaProxy;
                /* 单平台刷新走的是当前线程（调用方开的），所以这里手动把代理标志
                   挂上再清掉 —— 与批量抓取保持同一套分流判断。 */
                boolean viaProxy = Clash.enabled(ctx) && Clash.isRunning()
                        && Clash.shouldProxy(ctx, p.id, p.foreign);
                if (viaProxy) VIA_PROXY.set(Boolean.TRUE);
                try {
                    fill(ctx, it, ak, rate, it.foreign ? timeoutMs * 2 : timeoutMs);
                } finally {
                    if (viaProxy) VIA_PROXY.remove();
                }
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
    /**
     * 高级自定义平台：交给 WebCustom 引擎抓取与提取。
     *
     * @param viaProxy 由调用方（并发段）按「平台勾选 + foreign」统一判定后传入，
     *                 不要在这里再算一遍 —— 两处规则迟早跑偏（部分模式下用户勾了
     *                 web:0 走代理，这里自己算就会把勾选吞掉）。
     */
    private static void fillWeb(Item it, WebCustom w, double rate, int t, boolean viaProxy) throws Exception {
        WebCustom.Result r = w.fetch(viaProxy, t);
        if (!r.numeric) {
            /* 纯文本模式：直接展示，没有数值语义。
               阈值一并归零 —— 不然 Alert 会拿 bal=0 去比阈值，永远"低于预警"。 */
            it.amount = r.display;
            it.cny = 0;
            it.bal = 0;
            it.threshold = 0;
            it.rows = "文本  " + clip(r.display, 40);
            return;
        }
        it.bal = r.value;
        boolean isBalanceKind = "balance".equals(it.kind);
        boolean usd = "USD".equals(it.tag);
        it.amount = r.display;
        if (usd) {
            it.conv = "≈¥" + String.format("%.2f", r.value * rate);
        } else if ("CNY".equals(it.tag)) {
            it.conv = "";
        } else {
            it.conv = "";                       // NONE 单位不折算
        }
        it.cny = isBalanceKind ? (usd ? r.value * rate : ("CNY".equals(it.tag) ? r.value : 0)) : 0;
        it.rows = "来源  " + r.how;
        it.debug = r.display;
    }

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

        /* 余额制的才算资产；订阅余量 / 后付费账单 / 免费额度都不是钱。
           注意：这只决定"折不折进总资产"，不决定卡片上要不要显示折算金额 ——
           只要是美元余额，卡片就值得把人民币估算显示出来（用户看得懂 USD 更好）。 */
        boolean isBalanceKind = "balance".equals(it.kind);

        if (c.suffix != null && c.suffix.length() > 0) {
            /* 订阅类：显示成「62%」「12/20 次」这种，不做货币格式化 */
            it.amount = num(bal) + c.suffix;
            it.conv = "";
        } else {
            it.amount = money(bal, usd);
            it.conv = usd ? "≈¥" + String.format("%.2f", bal * rate) : "";
        }
        it.cny = isBalanceKind ? (usd ? bal * rate : bal) : 0;
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

    private static void fill(Context ctx, Item it, KeyStore.ApiKey apiKey, double rate, int t) throws Exception {
        String id = it.platform;              // 解析逻辑按「平台」选，不按 Key
        boolean usd = "USD".equals(it.tag);
        String key = apiKey != null ? apiKey.key : "";

        /* WorkBuddy 网关：本机自建服务。
           流程 = 探活 → 取余额。
           探活走 GET /healthz（不需要鉴权，能最快判断"进程在不在"）；
           余额走 POST /panel/api/balance_all（该接口 GET 返回 405）。
           服务没跑时抛专门异常，界面据此提示 + 给「拉起网关」按钮。 */
        if ("workbuddy".equals(id)) {
            String base = apiKey != null ? apiKey.baseUrl : "";
            base = normalizeBase(base);
            if (base.length() == 0) {
                it.ok = false;
                it.gatewayDown = true;          // 地址没填，同样按"服务不可用"引导
                it.gatewayUrl = "";
                it.error = "还没填网关地址";
                it.amount = "\u2014";
                return;
            }
            it.gatewayUrl = base;

            /* ① 探活：/healthz 不需要 key，服务活着就能通 */
            if (!probeGateway(base, Math.min(t, 4000))) {
                it.ok = false;
                it.gatewayDown = true;
                it.error = "网关未运行";
                it.amount = "\u2014";
                return;
            }

            /* ② 取余额 */
            String json = postJson(base + "/panel/api/balance_all", key, "", Math.max(t, 8000));
            JSONObject root = new JSONObject(json);
            JSONArray accounts = root.optJSONArray("accounts");
            if (accounts == null) accounts = new JSONArray();

            double total = 0, totalCap = 0;
            int n = 0, cooling = 0;
            StringBuilder sb = new StringBuilder();
            String firstName = "";
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject a = accounts.optJSONObject(i);
                if (a == null) continue;
                String nick = a.optString("nickname", a.optString("uid", "账号" + (i + 1)));
                double c = num(a, "credits");
                if (Double.isNaN(c)) continue;      // credits 是主字段，没有就没法显示
                boolean cool = a.optBoolean("cooling", false);
                String expiry = a.optString("credits_earliest_expiry", "");
                double expRemain = num(a, "credits_earliest_remaining");
                /* 积分上限：接口给的是 credits_total（总额度） */
                double cap = num(a, "credits_total");

                total += c;
                if (!Double.isNaN(cap) && cap > 0) totalCap += cap;
                n++;
                if (cool) cooling++;
                if (i == 0) firstName = nick;

                if (accounts.length() > 1) {
                    sb.append(nick).append("  ").append(fmtInt(c)).append(" 积分");
                    if (cool) sb.append("（冷却中）");
                    sb.append("\n");
                } else {
                    if (!Double.isNaN(cap) && cap > 0) {
                        sb.append("总积分  ").append(fmtInt(cap)).append("\n");
                    }
                    if (a.has("checkin_done")) {
                        sb.append("签到  ").append(a.optBoolean("checkin_done") ? "今日已签" : "今日未签").append("\n");
                    }
                    if (expiry != null && expiry.length() > 0) {
                        sb.append("最近到期  ").append(fmtExpiry(expiry));
                        if (!Double.isNaN(expRemain) && expRemain > 0) {
                            sb.append("（").append(fmtInt(expRemain)).append(" 分）");
                        }
                        sb.append("\n");
                    }
                    if (cool) sb.append("状态  冷却中\n");
                }
            }
            if (n == 0) throw new Exception("网关没有返回账号");

            it.cny = total;
            it.bal = total;
            /* 主体永远显示积分（那才是账户里的真数）。
               人民币写进卡片正文（"≈¥49"），单位位留"积分"就行 ——
               总资产卡片那边已经有 ¥ 汇总了，这里再挂汇率只是重复。 */
            it.amount = fmtInt(total);
            String tail = n > 1 ? "（" + n + " 账号合计）" : "";
            /* 剩余百分比：本周期还剩多少没用。用 credits_total 当分母 ——
               那是这轮额度的总量，剩下 2442/2650 = 92.2%。 */
            int pct = -1;
            if (totalCap > 0) {
                pct = (int) Math.round(total / totalCap * 100.0);
                if (pct > 100) pct = 100;
                if (pct < 0) pct = 0;
            }
            it.pctLeft = pct;
            /* 标一下"这个平台是有额度分母的"，供 UI 在数据未到时显示占位。
               不靠 amount 是否为空判断 —— 那太间接，别的平台也可能为空。 */
            it.hasQuota = true;
            /* 折算金额恒定显示 —— 卡片上写着 2442 积分，用户自然想知道值多少钱，
               这是"信息展示"，跟"要不要计入总资产"是两码事。
               开关只管 it.cny（汇总口径），不管显示。 */
            double disc = StatsOpt.rate(ctx, "workbuddy");   // 元/积分
            it.unitOverride = "积分" + tail;
            /* 放 it.conv —— 渲染层会按 isAssetRow 把它送到 p_conv2（百分比下方）。
               顺序读下来是「2442 积分 → 剩余 62% → ≈¥48.84」：先给账户里的真数，
               再看用了多少，最后才是值多少钱。money() 自带 ¥，别再手写一次。 */
            it.conv = "≈ " + money(total * disc, false)
                    + "（1 积分 ≈ " + fmtRate(disc) + " 元）";
            if (StatsOpt.inStats(ctx, "workbuddy")) {
                it.cny = total * disc;
                it.estimated = true;
            } else {
                it.cny = 0;   // 不参与汇总，封死任何累加路径
            }
            it.rows = sb.toString().trim();
            return;
        }

        /* 百炼：按计费模式分流 —— 余额制查账户余额，订阅制查套餐实例 */
        if ("dashscope".equals(id)) {
            boolean isSub = apiKey != null && "subscription".equals(apiKey.planMode);
            if (apiKey != null && apiKey.hasAliyunAccessKey()) {
                try {
                    if (isSub) {
                        /* 订阅制（Token Plan）：无公开余量 API，查实例列表拿状态与到期时间 */
                        String json = AliyunSigner.queryAvailableInstances(
                            apiKey.accessKeyId, apiKey.accessKeySecret);
                        it.debug = json.length() > 400 ? json.substring(0, 400) : json;
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
                            if (earliestEnd != null) {
                                sb.append("\n最近到期  ").append(earliestEnd);
                                try {
                                    java.text.SimpleDateFormat iso =
                                            new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
                                    iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                                    it.subEndMs = iso.parse(earliestEnd).getTime();
                                } catch (Exception ig) { }
                            }
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

        /* 小米 MiMo：余额只有控制台有，用登录会话 cookie 调 /api/v1/balance。
           应答形如 {"balance": 12.34, ...}，**单位是元**（控制台前端 Dn() 就是 Number()，
           且低余额阈值直接比 ≤5 元 —— 没有分转元这一步），所以不做任何换算。 */
        if ("mimo".equals(id)) {
            String ck = mimoSession(ctx);
            if (ck == null || ck.length() == 0) {
                it.noData = true;
                it.amount = "需登录";
                it.cny = 0;
                it.bal = 0;
                it.conv = "";
                it.rows = "余额需登录小米账号后查询\n设置 → API 密钥与平台 → 小米 MiMo";
                return;
            }
            String body;
            try {
                body = get(MIMO_BALANCE_URL, null, ck, Math.max(t, 9000));
            } catch (StatusException se) {
                /* 401/302 基本都是登录态过期（小米账号会话有有效期） */
                diag(ctx, "MiMo 余额被拒 " + se.getMessage());
                /* cookie 还在、服务端却 401 —— 登录态过期。
                   记下来，好让界面说清"需要重新登录"而不是含糊的"数据积累中"。 */
                if (se.getMessage() != null && se.getMessage().indexOf("401") >= 0) {
                    try {
                        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putBoolean(K_MIMO_EXPIRED, true).apply();
                    } catch (Throwable ig) { }
                }
                it.noData = true;
                it.amount = "登录过期";
                it.cny = 0;
                it.bal = 0;
                it.conv = "";
                it.rows = "小米账号登录态已过期\n重新点「登录小米账号」即可";
                return;
            }
            diag(ctx, "MiMo 余额应答 " + clip(body.replaceAll("\\s+", " "), 300));
            JSONObject root = new JSONObject(body);
            JSONObject d = root.optJSONObject("data");
            if (d == null) d = root;
            double bal = findNum(d, new String[] { "balance", "availableBalance", "available_balance" });
            if (Double.isNaN(bal)) bal = pick(d);
            if (Double.isNaN(bal)) {
                throw new Exception("未识别余额字段，接口返回：" + clip(body.replaceAll("\\s+", " "), 140));
            }
            it.bal = bal;
            it.cny = bal;                       // 接口本身就是人民币元
            /* 取数成功 → 登录态是好的，清掉过期标记 */
            try {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(K_MIMO_EXPIRED, false).apply();
            } catch (Throwable ig) { }
            it.amount = money(bal, false);
            it.conv = "";
            StringBuilder sb = new StringBuilder("余额  ").append(money(bal, false));
            double cash = findNum(d, new String[] { "cashBalance", "cash_balance" });
            if (!Double.isNaN(cash)) sb.append("\n现金余额  ").append(money(cash, false));
            double gift = findNum(d, new String[] { "giftBalance", "gift_balance" });
            if (!Double.isNaN(gift)) sb.append("\n赠送余额  ").append(money(gift, false));
            double frozen = findNum(d, new String[] { "frozenBalance", "frozen_balance" });
            if (!Double.isNaN(frozen) && frozen > 0) {
                sb.append("\n冻结金额  ").append(money(frozen, false));
            }
            it.rows = sb.toString();
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
            // 七牛账单接口间歇性慢（实测 0.2s~9s 抖动），给足预算 + get() 内超时重试兜底
            JSONObject root = new JSONObject(get(url, key, Math.max(t, 9000)));
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
            // 汇总 token 用量（usage.count 单位 k/tokens）
            double tokK = 0;
            if (bills != null) {
                for (int bi = 0; bi < bills.length(); bi++) {
                    JSONObject bill = bills.optJSONObject(bi);
                    if (bill == null) continue;
                    JSONArray models = bill.optJSONArray("models");
                    if (models == null) continue;
                    for (int mi = 0; mi < models.length(); mi++) {
                        JSONObject mo = models.optJSONObject(mi);
                        if (mo == null) continue;
                        JSONArray items2 = mo.optJSONArray("items");
                        if (items2 == null) continue;
                        for (int ii = 0; ii < items2.length(); ii++) {
                            JSONObject it2 = items2.optJSONObject(ii);
                            if (it2 == null) continue;
                            JSONObject usage = it2.optJSONObject("usage");
                            if (usage != null) tokK += usage.optDouble("count", 0);
                        }
                    }
                }
            }
            if (tokK > 0) {
                it.usageTokens = tokK;
                sb.append("\nToken 用量  ").append(String.format("%.1f", tokK)).append(" K");
            }
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
