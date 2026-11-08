package com.minis.balancewidget;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cookie 自动续期：把响应里的 {@code Set-Cookie} 合并回我们手里那份。
 *
 * <h3>为什么需要</h3>
 * 需要登录态的平台（OpenAI 网页版、自定义 HTTP 平台）鉴权靠的是一条 session cookie。
 * 我们把用户在网页登录时抓到的 cookie 存下来，之后每次刷新都拿它去请求 ——
 * 但**只读不写**：服务器在响应里下发了新 cookie（滑动续期、或换了 session id），
 * 我们收不到，那份就成了死字符串。表现是"用几天就失效，只能重新登录"。
 *
 * <p>绝大多数站点的 session 是**滑动过期**的：只要还有请求进来，服务端就把过期时间往后推，
 * 并在响应里回一条新的 {@code Set-Cookie}。把这些接回来，会话就能一直续下去，
 * 直到服务端因为改密码 / 主动踢下线 / 长期不活跃而真正作废 —— 那时才该打扰用户。
 *
 * <h3>只做三件事</h3>
 * <ol>
 *   <li>解析 {@code name=value; Path=/; Expires=…; HttpOnly}，只取 name/value；</li>
 *   <li>同名覆盖，其余保留（不能因为服务器只回了刷新的那一条就把别的 cookie 丢了）；</li>
 *   <li>删标记（{@code Max-Age=0} 或已过期的 {@code Expires}）会把同名项移除。</li>
 * </ol>
 *
 * <p>注意：**不处理 Domain / Path / Secure**。我们只按"整条 Cookie 头"存，
 * 不做多域名隔离 —— 一个平台上所有请求都发同一个 host，细分反而会丢。
 */
public final class CookieJar {

    private CookieJar() { }

    /**
     * 合并响应里的 Set-Cookie 到已有 Cookie 串。
     *
     * @param oldCookie      现有 Cookie 头（可为空、可为 null）
     * @param setCookieLines 响应里的 Set-Cookie 行（可含 null 项）
     * @return 合并后的 Cookie 头；没有任何变化时返回与入参等值的串
     */
    public static String merge(String oldCookie, List<String> setCookieLines) {
        if (setCookieLines == null || setCookieLines.isEmpty()) return oldCookie == null ? "" : oldCookie;

        Map<String, String> jar = parse(oldCookie);
        boolean touched = false;

        for (int i = 0; i < setCookieLines.size(); i++) {
            String line = setCookieLines.get(i);
            if (line == null) continue;
            line = line.trim();
            if (line.length() == 0) continue;

            String[] parts = line.split(";");
            String pair = parts.length > 0 ? parts[0].trim() : "";
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;                       // 形如 "deleted" 的怪值，忽略
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (name.length() == 0) continue;

            if (isDeletion(parts, value)) {
                if (jar.remove(name) != null) touched = true;
                continue;
            }
            String prev = jar.put(name, value);
            if (!value.equals(prev)) touched = true;
        }

        if (!touched) return oldCookie == null ? "" : oldCookie;
        return join(jar);
    }

    /** Set-Cookie 里的删除标记：Max-Age<=0，或 Expires 已经是过去时间 */
    private static boolean isDeletion(String[] parts, String value) {
        if ("deleted".equalsIgnoreCase(value)) return true;
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String k = p.substring(0, eq).trim();
            String v = p.substring(eq + 1).trim();
            if ("Max-Age".equalsIgnoreCase(k)) {
                try {
                    if (Long.parseLong(v) <= 0) return true;
                } catch (Exception ignored) { }
            } else if ("Expires".equalsIgnoreCase(k)) {
                if (isPast(v)) return true;
            }
        }
        return false;
    }

    /** Expires 有几种常见写法，解析不出来就当"不是过期"（宁可留着也别误删） */
    private static boolean isPast(String v) {
        String[] fmts = {
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEE, dd-MMM-yyyy HH:mm:ss zzz",
                "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
                "dd MMM yyyy HH:mm:ss zzz"
        };
        for (int i = 0; i < fmts.length; i++) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(fmts[i], Locale.US);
                java.util.Date d = f.parse(v);
                if (d != null) return d.getTime() < System.currentTimeMillis();
            } catch (Exception ignored) { }
        }
        return false;
    }

    /** "a=1; b=2" → {a:1, b:2}；保持原顺序（LinkedHashMap） */
    public static Map<String, String> parse(String cookie) {
        Map<String, String> jar = new LinkedHashMap<String, String>();
        if (cookie == null || cookie.length() == 0) return jar;
        String[] ps = cookie.split(";");
        for (int i = 0; i < ps.length; i++) {
            String p = ps[i].trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            jar.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        return jar;
    }

    private static String join(Map<String, String> jar) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** 两份 Cookie 串是否等价（顺序、空白不算差异） */
    public static boolean same(String a, String b) {
        Map<String, String> x = parse(a);
        Map<String, String> y = parse(b);
        return x.equals(y);
    }

    /** 调试用：只列 cookie 名字，不打印任何值（值可能是会话凭据） */
    public static String names(String cookie) {
        List<String> ns = new ArrayList<String>();
        for (String k : parse(cookie).keySet()) ns.add(k);
        return ns.toString();
    }
}
