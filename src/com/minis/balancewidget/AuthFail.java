package com.minis.balancewidget;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 凭证 / 登录态失效判定层。
 *
 * <p>解决的问题：Cookie 或 API Key 过期后，卡片上只有一句「查询失败」，
 * 用户分不清是网络抖动、平台改版，还是该重新登录了。</p>
 *
 * <p>设计原则：<b>宁可漏判，不可误判</b>。把「网络不通」误判成「要重新登录」，
 * 比漏判更糟 —— 用户会去翻设置、重填 Key，问题却根本没解决。</p>
 *
 * <p>两道闸门：
 * <ol>
 *   <li>{@link #looksExpired} 只认「服务器明确说凭证不行」的特征；</li>
 *   <li>{@link #record} 连续 {@link #THRESHOLD} 次命中才置 needLogin ——
 *       单次 5xx / 网关抽风 / 平台临时改版不会让用户去重登。</li>
 * </ol>
 *
 * <p>存储：SharedPreferences {@code balance_widget}（与 {@link BalanceFetcher#PREFS} 相同），
 * 键 {@code authcnt_<id>}（int，连续命中次数）、{@code authneed_<id>}（boolean，是否需要重登）。
 * platformId 里的冒号（{@code custom:0}、{@code web:1}）原样拼进键名，不转义。</p>
 *
 * <p>Java 7 语法；所有入参可空。</p>
 */
public final class AuthFail {

    /** 必须与 BalanceFetcher.PREFS 一致（这里写字面量是为了本文件能独立编译）。 */
    public static final String PREFS = "balance_widget";

    /** 连续命中次数（int）。 */
    private static final String K_CNT = "authcnt_";
    /** 需要重新登录（boolean）。 */
    private static final String K_NEED = "authneed_";

    /** 连续几次判为凭证失效才认定需要重新登录。一次可能是临时 5xx 或平台抽风。 */
    public static final int THRESHOLD = 2;

    /**
     * 判定词表。匹配前统一 toLowerCase(Locale.US)。
     * 以后加词只改这里，不要在方法里散落字符串。
     *
     * <p>只放「平台在抱怨凭证」的措辞，不要放 "forbidden" / "denied" 这种
     * 也常用于 WAF、地区限制、权限不足的词 —— 那会把「没买这个服务」
     * 误判成「要重新登录」。</p>
     */
    static final String[] EXPIRE_HINTS = {
            // ---- 英文 ----
            "unauthorized",
            "invalid api key",
            "api key not valid",
            "incorrect api key",
            "token expired",
            "token has expired",
            "login required",
            "not logged in",
            "please login",
            "authentication failed",
            "invalid token",
            // ---- 中文 ----
            "请登录",
            "登录已过期",
            "未登录",
            "认证失败",
            "无权限",
            "令牌失效",
            "密钥无效",
    };

    /**
     * 扫描上限：正常余额接口的响应都在几 KB，登录页撑死几十 KB。
     * 加个上限纯粹是防止某个平台返回一个几十 MB 的 HTML 时白白复制一遍。
     */
    private static final int MAX_SCAN = 200000;

    /** 计数器是「读-改-写」，多线程并发抓取时串一下，避免丢计数。 */
    private static final Object LOCK = new Object();

    private AuthFail() {
    }

    /**
     * 这组失败特征看起来像「凭证失效」吗。
     *
     * <p>判定顺序（顺序本身就是规则，别调换）：
     * <ol>
     *   <li>httpCode &lt;= 0 → false（压根没拿到 HTTP 响应，是网络/代理/TLS 问题）；</li>
     *   <li>5xx、429、408 → false（服务端故障 / 限流 / 超时，换一次可能就好了）；</li>
     *   <li>401、403 → true（状态码是服务器最明确的表态，此时 body 为空也算）；</li>
     *   <li>body 与 errMsg 都为空 → false；</li>
     *   <li>body / errMsg 命中 {@link #EXPIRE_HINTS} → true；</li>
     *   <li>body 同时含 {@code <form} 和 password 输入框 → true（被重定向到了登录页）。</li>
     * </ol>
     *
     * @param httpCode HTTP 状态码；0 或负数表示没有 HTTP 响应
     * @param body     响应体，可空
     * @param errMsg   异常消息 / 本地错误文案，可空
     */
    public static boolean looksExpired(int httpCode, String body, String errMsg) {
        /* 没有 HTTP 响应 = 网络层就失败了（超时、DNS、连接被拒、SSL、WAF 掐断）。
           这类情况绝不能引导用户去重新登录。放在最前面，后面的规则都不参与。 */
        if (httpCode <= 0) return false;

        /* 服务端自己的问题，或者只是被限流 —— 都不是凭证的事。
           必须放在 401/403 判断之前吗？不需要（5xx 不可能是 401），
           但放在词表匹配之前是必须的：有些 CDN 的 5xx 页面正文里就写着 unauthorized。 */
        if (httpCode >= 500) return false;
        if (httpCode == 429 || httpCode == 408) return false;

        /* 403 还有一类完全无关的原因：Cloudflare / WAF 拦截、地区限制、
           人机校验页 —— 它们也回 403，但重新登录一万次也没用。
           这个 App 的用户大量走代理，撞上 Cloudflare 是常态，
           所以 403 要先排掉这些特征，别把人赶去白折腾。 */
        if (httpCode == 403) {
            String lb = (body == null ? "" : body).toLowerCase(Locale.US);
            if (lb.contains("cloudflare") || lb.contains("cf-ray")
                    || lb.contains("just a moment") || lb.contains("attention required")
                    || lb.contains("checking your browser")) {
                return false;
            }
        }

        /* 状态码优先：401/403 就算 body 是空的，也该让用户去重登。 */
        if (httpCode == 401 || httpCode == 403) return true;

        String b = body == null ? "" : body;
        String m = errMsg == null ? "" : errMsg;
        boolean bodyBlank = b.trim().length() == 0;
        boolean msgBlank = m.trim().length() == 0;
        /* 空响应体最容易被误读（"什么都没有" 不等于 "凭证失效"）。
           只有 errMsg 也空时才直接判 false；errMsg 有内容时还要过词表。 */
        if (bodyBlank && msgBlank) return false;

        String hay = lower(scan(b)) + "\n" + lower(m);
        for (int i = 0; i < EXPIRE_HINTS.length; i++) {
            String hint = EXPIRE_HINTS[i];
            if (hint != null && hint.length() > 0 && hay.contains(hint)) return true;
        }

        /* 最后才看「是不是被重定向到了登录页」：只看 body，不看 errMsg。
           注意这里不依赖 2xx —— 很多平台会先 200 返回登录页 HTML。 */
        return looksLikeLoginPage(lower(scan(b)));
    }

    /**
     * 记一次抓取结果。
     *
     * <ul>
     *   <li>{@code ok = true}：清零（成功是「凭证有效」的唯一硬证据）；</li>
     *   <li>{@code ok = false} 且 {@link #looksExpired} 命中：计数 +1，达到
     *       {@link #THRESHOLD} 时置 needLogin；</li>
     *   <li>{@code ok = false} 但不像凭证失效（超时 / 5xx / 429 / 空响应）：
     *       <b>既不加也不清零</b> —— 网络抖动不能证明凭证有效，也不该抹掉
     *       上一次 401 的证据。</li>
     * </ul>
     *
     * @param platformId 平台标识，如 {@code openai}、{@code custom:0}、{@code web:1}；空则忽略
     */
    public static void record(Context c, String platformId, boolean ok,
                              int httpCode, String body, String errMsg) {
        if (c == null || platformId == null || platformId.length() == 0) return;
        synchronized (LOCK) {
            try {
                SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                String kc = K_CNT + platformId;
                String kn = K_NEED + platformId;

                if (ok) {
                    /* 常态是「本来就没有记录」，先读一下再决定写不写，
                       免得每次刷新都给每个平台白写两次 flash。 */
                    if (sp.getInt(kc, 0) == 0 && !sp.getBoolean(kn, false)) return;
                    sp.edit().putInt(kc, 0).putBoolean(kn, false).apply();
                    return;
                }

                if (!looksExpired(httpCode, body, errMsg)) return;

                int n = sp.getInt(kc, 0) + 1;
                SharedPreferences.Editor ed = sp.edit().putInt(kc, n);
                if (n >= THRESHOLD) ed.putBoolean(kn, true);
                ed.apply();
            } catch (Throwable ignored) {
                /* 判定层永远不能把抓取流程搞崩：存储读写失败就当没这回事。 */
            }
        }
    }

    /**
     * 连续失败够数了，需要重新登录。
     * 同时看计数：万一上一次 putBoolean 没落盘（进程被杀 / Editor 冲突），
     * 计数已经到 2 也能判出来。
     */
    public static boolean needLogin(Context c, String platformId) {
        if (c == null || platformId == null || platformId.length() == 0) return false;
        try {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (sp.getBoolean(K_NEED + platformId, false)) return true;
            return sp.getInt(K_CNT + platformId, 0) >= THRESHOLD;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用户重新填了 Key / 重新登录后调用：清掉这个平台的计数与标记。 */
    public static void clear(Context c, String platformId) {
        if (c == null || platformId == null || platformId.length() == 0) return;
        synchronized (LOCK) {
            try {
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .remove(K_CNT + platformId)
                        .remove(K_NEED + platformId)
                        .apply();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 清掉所有平台的记录（备份恢复、「全部重新登录」之类）。只动 authcnt_/authneed_。 */
    public static void clearAll(Context c) {
        if (c == null) return;
        synchronized (LOCK) {
            try {
                SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                /* 先快照再改：直接遍历 getAll() 的 keySet 同时 remove，行为不确定。 */
                List<String> kill = new ArrayList<String>();
                for (String k : sp.getAll().keySet()) {
                    if (k != null && (k.startsWith(K_CNT) || k.startsWith(K_NEED))) kill.add(k);
                }
                if (kill.size() == 0) return;
                SharedPreferences.Editor ed = sp.edit();
                for (int i = 0; i < kill.size(); i++) ed.remove(kill.get(i));
                ed.apply();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 从异常消息里捞 HTTP 状态码，供接线时喂给 {@link #record}。
     *
     * <p>为什么需要它：{@code BalanceFetcher.get()} 抛的是
     * {@code StatusException("HTTP 503")} / {@code "Key 无效或未授权 (401)"}，
     * 异常本身不带状态码字段。抓取失败的统一出口只能拿到 message，
     * 所以在这里还原成数字 —— <b>不要</b>因此放宽 {@link #looksExpired} 的
     * {@code httpCode <= 0 → false} 规则（0 代表网络异常，必须继续当网络异常）。
     *
     * @return 100..599 之间第一个三位数；找不到返回 0
     */
    public static int httpCodeOf(String msg) {
        if (msg == null) return 0;
        int len = msg.length();
        for (int i = 0; i + 2 < len; i++) {
            char a = msg.charAt(i);
            char b = msg.charAt(i + 1);
            char d = msg.charAt(i + 2);
            if (a < '0' || a > '9' || b < '0' || b > '9' || d < '0' || d > '9') continue;
            /* 必须是独立的数字串：前后不能再挨着数字（避免从长数字里截三位）。 */
            if (i > 0 && isDigit(msg.charAt(i - 1))) continue;
            if (i + 3 < len && isDigit(msg.charAt(i + 3))) continue;
            int code = (a - '0') * 100 + (b - '0') * 10 + (d - '0');
            if (code >= 100 && code <= 599) return code;
        }
        return 0;
    }

    // ---------- 内部 ----------

    private static boolean isDigit(char ch) {
        return ch >= '0' && ch <= '9';
    }

    /** toLowerCase 必须指定 Locale：土耳其语环境下 "I" 会变成 "ı"，词表就永远匹配不上。 */
    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }

    /** 只保留前 MAX_SCAN 个字符（入参应已是原始大小写）。 */
    private static String scan(String s) {
        if (s == null) return "";
        return s.length() <= MAX_SCAN ? s : s.substring(0, MAX_SCAN);
    }

    /**
     * 是不是一个登录页：同时出现 {@code <form} 和 password 输入框。
     * 单引号属性也认（不少模板用 {@code type='password'}）。
     * 入参必须是已 toLowerCase 的文本。
     */
    private static boolean looksLikeLoginPage(String lowerBody) {
        if (lowerBody == null || lowerBody.length() == 0) return false;
        if (lowerBody.indexOf("<form") < 0) return false;
        return lowerBody.indexOf("type=\"password\"") >= 0
                || lowerBody.indexOf("type='password'") >= 0;
    }
}
