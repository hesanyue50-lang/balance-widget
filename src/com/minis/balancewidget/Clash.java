package com.minis.balancewidget;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 内置网络加速（Clash / mihomo 内核）。
 *
 * ## 为什么是「本地代理」而不是系统 VPN
 *
 * 真正的系统 VPN 要走 `VpnService`：需要弹系统授权框、影响**全部**应用的流量、
 * 还得自己处理路由与 DNS。而这里的诉求其实很窄 ——
 * **只让本应用去访问那些国内握手很慢的境外 API**。
 *
 * 用本地 HTTP 代理（127.0.0.1:7890）正好：
 *   - 不动系统网络，其他 App 完全不受影响，也不用申请任何 VPN 权限；
 *   - 能做到**按平台分流**（哪个平台走代理由用户逐个勾）—— 系统 VPN 反而做不到这点；
 *   - 请求还是我们自己在发，改一个 Socket 出口就够了。
 *
 * ## 内核放在哪（关键约束）
 *
 * Android 10 起，`targetSdk >= 29` 的应用**不允许执行私有可写目录里的文件**
 * （/data/data/<pkg>/ 下的东西一律 execve 失败）。唯一合法且可执行的位置是
 * APK 释放出来的原生库目录 `/data/app/<pkg>/lib/<abi>/` —— 只读、r-xr-xr-x。
 *
 * 所以内核以 `libmihomo.so` 的形式打进 APK 的 `lib/arm64-v8a/`，
 * 运行时用 `ApplicationInfo.nativeLibraryDir` 拼出路径执行。
 * 它其实就是个普通 ELF，只是借用了 .so 这个身份。
 *
 * ## 进程生命周期
 *
 * 内核作为本应用的子进程运行：**应用进程一死，它就跟着走**。
 * 这是刻意的 —— 不需要额外的守护逻辑，也不会在后台偷偷常驻耗电。
 * 每次进应用（若用户开着代理）重新拉起即可，启动只要一两秒。
 */
public final class Clash {

    /** 本地混合代理端口（HTTP + SOCKS 共用一个口，mihomo 的 mixed-port） */
    public static final int PROXY_PORT = 7890;
    /** 控制 API 端口：查状态、看节点、触发订阅重载 */
    public static final int API_PORT = 9090;

    private static final String K_SUB_URL = "clash_sub_url";
    private static final String K_ENABLED = "clash_enabled";
    /** 每个平台是否走代理：proxy_plat_<platformId> = true */
    private static final String K_PLAT_PREFIX = "proxy_plat_";

    /* ---------- 分流模式 ---------- */

    /** 全局：本应用所有网络请求都走代理 */
    public static final String MODE_ALL = "all";
    /**
     * 【仅作兼容】早期版本用过的一个模式值，语义与 MODE_ALL 完全一致。
     * 界面上已不再提供；mode() 读到它会归一化成 MODE_ALL。
     */
    public static final String MODE_APP = "app";
    /** 部分：只有勾选的平台走代理 */
    public static final String MODE_PARTIAL = "partial";
    /**
     * 系统全局：建一条系统 VPN 接管**整机**流量（需要用户授权）。
     * 与应用内模式的区别在于：那个只管本应用，这个连别的应用也会走代理。
     */
    public static final String MODE_SYSTEM = "system";

    /**
     * 当前模式。对外**只暴露三种**：
     *   应用内全局 / 部分 API / 系统全局。
     *
     * 历史遗留的 "app" 值（语义与应用内全局完全相同）在这里统一归一化成
     * MODE_ALL —— 这样即使老用户存档里还留着 "app"，界面也不会出现
     * 一个多出来的、无意义的选项。
     */
    public static String mode(Context c) {
        String m = sp(c).getString("clash_mode", MODE_PARTIAL);
        if (MODE_APP.equals(m)) return MODE_ALL;
        return m;
    }

    /** 模式同样同步落盘：它是"要不要自动开启加速"的判定依据，丢了会导致行为不符预期 */
    public static void setMode(Context c, String m) {
        sp(c).edit().putString("clash_mode", m == null ? MODE_PARTIAL : m).commit();
    }

    /**
     * 这种模式下，加速该不该**自动**开启（打开 App / 开机时不用问）。
     *
     * - 部分 API 分流：用户已逐个勾选哪些平台走代理，意图明确、影响不外溢
     *   → 自动开，省得每次进来手动点一下。
     * - 应用内全局：只影响本应用自己的请求，不碰别的 App
     *   → 同样自动开。
     * - 系统全局：建一条系统 VPN 接管**整机**流量，影响面大、还要弹授权框
     *   → 必须用户手动开，不替他做这个决定。
     *
     * 注意 mode() 已把历史值 "app" 归一化成 MODE_ALL，这里只需判两个值。
     */
    public static boolean autoStartAllowed(Context c) {
        String m = mode(c);
        return MODE_ALL.equals(m) || MODE_PARTIAL.equals(m);
    }

    /**
     * 判断某个平台这次要不要走代理。
     *
     * 注意：这里的「全局 / 应用内」指的是**本应用内**的流量，
     * 不是接管整个手机 —— 系统级 VPN 需要 VpnService 授权，
     * 那会影响全部应用，和「只让几个余额平台走代理」的诉求背道而驰。
     */
    public static boolean shouldProxy(Context c, String platform, boolean foreign) {
        String m = mode(c);
        if (MODE_ALL.equals(m) || MODE_APP.equals(m)) return true;
        /* 系统全局模式下，整机流量都已经进了 tun，本应用不必再走本地代理 ——
           否则本应用会「先连 127.0.0.1:7890，再被自己的 tun 抓一次」，
           虽然回环不受影响，但绕一圈毫无意义。直接当直连处理，由 tun 兜住。 */
        if (MODE_SYSTEM.equals(m)) return false;
        return platformViaProxy(c, platform, foreign);
    }

    private static Process proc;
    private static long startAt;

    private Clash() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
    }

    // ---------- 配置项 ----------

    public static String subUrl(Context c) {
        return sp(c).getString(K_SUB_URL, "");
    }

    public static void setSubUrl(Context c, String url) {
        sp(c).edit().putString(K_SUB_URL, url == null ? "" : url.trim()).apply();
    }

    /** 用户是否开启了加速总开关 */
    public static boolean enabled(Context c) {
        return sp(c).getBoolean(K_ENABLED, false);
    }

    /**
     * 开关状态：**必须同步落盘**。
     *
     * 早先这里用 apply()（异步写盘），结果用户开完开关后如果立刻关屏、
     * 被系统回收进程或重启手机，写盘任务还没跑完，下次进来开关就是关的 ——
     * 表现是"上次明明是开着的，今天打开又变关了"。
     *
     * 开关是用户明确表达过一次、不希望被改变的状态，用 commit() 换可靠性；
     * 这个值写入频率极低（用户点一下才写一次），同步写的开销可以忽略。
     */
    public static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(K_ENABLED, on).commit();
    }

    /**
     * 这个平台是否走代理。
     * 没设置过时按「平台是否标记为国外」给默认值 —— 国外平台默认走，
     * 免得用户还得挨个打开。
     */
    public static boolean platformViaProxy(Context c, String platform, boolean foreign) {
        return sp(c).getBoolean(K_PLAT_PREFIX + platform, foreign);
    }

    public static void setPlatformViaProxy(Context c, String platform, boolean on) {
        sp(c).edit().putBoolean(K_PLAT_PREFIX + platform, on).apply();
    }

    // ---------- 目录与文件 ----------

    /** 内核工作目录（配置、缓存都在这；它本身不可执行，只放数据） */
    public static File workDir(Context c) {
        File d = new File(c.getFilesDir(), "clash");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File configFile(Context c) {
        return new File(workDir(c), "config.yaml");
    }

    /** 内核可执行文件路径：APK 释放出的原生库目录（只读 → 可执行） */
    public static String binPath(Context c) {
        return c.getApplicationInfo().nativeLibraryDir + "/libmihomo.so";
    }

    /** 内核是否已就绪（APK 里带了且设备架构支持；32 位设备没有它） */
    public static boolean available(Context c) {
        try {
            return new File(binPath(c)).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- 运行状态 ----------

    public static boolean isRunning() {
        if (proc == null) return false;
        try {
            // exitValue() 只在进程已结束时返回，抛异常说明还活着
            proc.exitValue();
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static long uptimeMs() {
        return isRunning() ? System.currentTimeMillis() - startAt : 0;
    }

    // ---------- 启动 / 停止 ----------

    /**
     * 启动内核。
     *
     * @param cfgText 完整的 Clash 配置文本（由 {@link #buildConfig} 生成）
     */
    public static synchronized String start(Context c, String cfgText) {
        /* 先把上次遗留的内核清掉 —— 它占着 7890，不清的话这次必然启动失败 */
        killStaleCore(c);
        return startInternal(c, cfgText, -1);
    }

    private static synchronized String startInternal(Context c, String cfgText, int tunFd) {
        if (!available(c)) {
            return "内核不可用（此设备架构没有内置内核，仅支持 arm64）";
        }
        if (isRunning()) return null;              // 已在跑，直接当成功

        try {
            File dir = workDir(c);
            writeText(new File(dir, "config.yaml"), cfgText);
            // 日志写到私有目录，出问题能看
            File log = new File(dir, "core.log");

            ProcessBuilder pb = new ProcessBuilder(
                    binPath(c),
                    "-d", dir.getAbsolutePath(),
                    "-f", configFile(c).getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            if (tunFd >= 0) {
                /* 兜底通道：部分内核版本读的是环境变量而不是配置字段 */
                pb.environment().put("CLASH_TUN_FD", String.valueOf(tunFd));
            }
            pb.directory(dir);
            proc = pb.start();
            startAt = System.currentTimeMillis();

            /* 等控制端口起来：内核解析配置 + 建立节点连接需要一点时间，
               轮询 /version 最直接（比 sleep 固定时长稳）。 */
            for (int i = 0; i < 40; i++) {         // 最多约 8 秒
                if (!isRunning()) {
                    /* core.log 在**私有目录**，用户/排查工具读不到 ——
                       顺手抄一份到诊断日志里，否则"内核启动失败"永远只有结论没有原因。 */
                    String why = tail(log, 400);
                    BalanceFetcher.diag(c, "内核启动失败，core.log 末尾：\n" + why);
                    return "内核启动失败：" + why;
                }
                if (apiAlive()) return null;
                try { Thread.sleep(200); } catch (InterruptedException ig) { }
            }
            String why2 = tail(log, 400);
            BalanceFetcher.diag(c, "内核启动超时，core.log 末尾：\n" + why2);
            return "内核启动超时（8 秒内控制端口未就绪）\n" + why2;
        } catch (Throwable t) {
            BalanceFetcher.diag(c, "clash 启动异常 " + t);
            return "启动异常：" + t;
        }
    }

    public static synchronized void stop(Context c) {
        try {
            if (proc != null) {
                proc.destroy();
                proc = null;
                BalanceFetcher.diag(c, "clash 已停止");
            }
        } catch (Throwable ignored) { }
    }

    /** 控制 API 是否活着 */
    public static boolean apiAlive() {
        try {
            String r = apiGet("/version", 1500);
            return r != null && r.indexOf("version") >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 经代理访问一个轻量地址，验证代理链路真的通 */
    public static boolean proxyAlive() {
        try {
            String r = BalanceFetcher.getViaProxy(
                    "https://api.deepseek.com/user/balance", null, "", 6000);
            // 没有 key 时返回 401 也算"链路通"（BalancFetcher 会抛 StatusException）
            return r != null;
        } catch (Throwable t) {
            String m = String.valueOf(t.getMessage());
            /* 401/403 说明请求已经到达对方服务器 —— 链路是通的，只是没带有效 key */
            return m.indexOf("未授权") >= 0 || m.indexOf("401") >= 0 || m.indexOf("403") >= 0;
        }
    }

    // ---------- 配置生成 ----------

    /**
     * 把机场订阅返回的 Clash 配置整理成内核能直接吃的版本。
     *
     * 订阅里通常自带 mixed-port / external-controller 等字段，值五花八门，
     * 会和我们约定好的端口打架（端口变了 App 就找不到代理）。
     * 所以这里把这几个关键字段**统一覆盖**掉，其余（proxies / proxy-groups / rules）原样保留。
     *
     * 文本级处理而不是解析 YAML：项目没有 YAML 库，而 Clash 配置这几个顶层键
     * 格式非常规整（`key: value` 顶格），文本替换足够可靠；真遇到不认识的写法，
     * 最坏也就是多插一行重复键 —— mihomo 取最后一个，仍是我们想要的端口。
     */
    public static String buildConfig(String subYaml) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 由 API 余额 App 生成：关键端口字段已覆盖，其余沿用订阅\n");
        sb.append("mixed-port: ").append(PROXY_PORT).append('\n');
        sb.append("external-controller: 127.0.0.1:").append(API_PORT).append('\n');
        sb.append("allow-lan: false\n");
        sb.append("bind-address: 127.0.0.1\n");
        sb.append("mode: rule\n");
        sb.append("log-level: warning\n");
        sb.append("ipv6: false\n");
        sb.append("unified-delay: true\n");
        sb.append("tcp-concurrent: true\n");

        if (subYaml != null && subYaml.length() > 0) {
            String[] lines = subYaml.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                String t = l.trim();
                /* 头部已经写死的顶层键，订阅里若有同名的一律丢弃 ——
                   否则同一个键在文档里出现两次，YAML 直接解析失败
                   （实测内核报 "mapping key \"ipv6\" already defined at line 8"，
                   就是漏了 ipv6 这一项）。

                   ★ 只跳过**顶层**的：dns 段里的 ipv6 前面有缩进，是另一个层级的键，
                     必须原样保留。判断办法就是看这一行有没有前导空白。 */
                boolean topLevel = l.equals(t);
                if (topLevel && isOwnedTopKey(t)) continue;
                sb.append(l).append('\n');
            }
        }
        return sb.toString();
    }

    /** buildConfig 头部自己写死的顶层键 —— 订阅里的同名项要丢掉，免得重复定义 */
    private static final String[] OWNED_TOP_KEYS = {
        "mixed-port:", "port:", "socks-port:", "external-controller:",
        "allow-lan:", "bind-address:", "mode:", "log-level:",
        "ipv6:", "unified-delay:", "tcp-concurrent:",
    };

    private static boolean isOwnedTopKey(String trimmedLine) {
        for (int i = 0; i < OWNED_TOP_KEYS.length; i++) {
            if (trimmedLine.startsWith(OWNED_TOP_KEYS[i])) return true;
        }
        return false;
    }

    /**
     * 生成「系统级 VPN」用的配置：在普通配置基础上开 tun 入站。
     *
     * @param fd 由 VpnService 建立的 tun 文件描述符
     *
     * 关键字段说明：
     *   - `tun.file-descriptor`：把现成的 tun 交给内核，别再自己建；
     *   - `stack: gvisor`：在**用户态**实现 TCP/IP。这一步是必须的 ——
     *     系统栈（system）要内核支持，而 gvisor 纯用户态，兼容性最好；
     *   - `auto-route: false` / `auto-detect-interface: false`：
     *     路由已经由 VpnService 建好了，内核再动会把系统路由搞乱。
     */
    public static String buildTunConfig(String subYaml, int fd) {
        String base = buildConfig(subYaml);
        StringBuilder sb = new StringBuilder(base);
        sb.append('\n');
        sb.append("# ---- 系统级 VPN（tun 入站，由 VpnService 提供 fd）----\n");
        sb.append("tun:\n");
        sb.append("  enable: true\n");
        sb.append("  stack: gvisor\n");
        sb.append("  file-descriptor: ").append(fd).append('\n');
        sb.append("  auto-route: false\n");
        sb.append("  auto-detect-interface: false\n");
        sb.append("  mtu: 8500\n");
        return sb.toString();
    }

    /**
     * 启动内核并显式传入 tun fd。
     *
     * fd 通过两条路一起给：配置文件里的 `tun.file-descriptor`，
     * 以及环境变量 CLASH_TUN_FD —— 不同版本的内核认的不一样，
     * 两条都写上最稳。
     */
    public static synchronized String startWithFd(Context c, String cfgText, int fd) {
        return startInternal(c, cfgText, fd);
    }

    // ---------- 订阅下载 ----------

    /**
     * 下载订阅。
     *
     * 订阅服务器通常在国内可直连，但有些机场只给境外入口 ——
     * 所以先直连试一次，失败再经本地代理（内核已经在跑的话）来一次。
     */
    /**
     * 订阅请求依次尝试的 UA。
     *
     * 机场普遍按 UA 决定返回什么：带 clash 字样的给 Clash YAML，其它给 base64 节点串。
     * 但**具体认哪一个**各机场不一样 —— 有的只认 Clash for Windows，
     * 有的认 mihomo。所以拿到非 Clash 内容时换个 UA 再试，比自己猜一个更靠谱。
     */
    private static final String[] SUB_UAS = {
        "clash-verge/v1.7.7",
        "ClashMetaForAndroid/2.11.6",
        "ClashforWindows/0.20.39",
        "mihomo/1.19.32",
        "Clash/1.18.0",
    };

    /** 用户自定义 UA 的存放键（设置里填了就优先用它，且只用它） */
    public static final String KEY_CUSTOM_UA = "sub_user_agent";
    /** 兼容旧引用 */
    private static final String SUB_UA_VALUE = SUB_UAS[0];

    public static String fetchSubscription(Context c, String url) throws Exception {
        String direct = null;
        String viaProxy = null;

        /* UA 候选：用户自己填了就用它（能对上 ClashMeta 之类客户端的口径），
           没填才依次试内置那几个。 */
        String custom = "";
        try {
            custom = c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_CUSTOM_UA, "");
            if (custom == null) custom = "";
            custom = custom.trim();
        } catch (Throwable ignored) { }
        String[] uas = custom.length() > 0 ? new String[]{ custom } : SUB_UAS;
        if (custom.length() > 0) BalanceFetcher.diag(c, "订阅使用自定义 UA：" + custom);

        /* 依次换 UA 试：拿到内容但不像 Clash 配置时，很可能是这个机场认别的客户端 */
        for (int attempt = 0; attempt < uas.length; attempt++) {
            String ua = uas[attempt];
            BalanceFetcher.SUB_ACCEPT.set("*/*");     // 订阅要 YAML，不能用 application/json
            direct = null;
            viaProxy = null;
            BalanceFetcher.SUB_UA.set(ua);            // 只影响本次（本线程）的请求
            try {
                try {
                    direct = BalanceFetcher.httpGet(url, null, 20000);
                    if (looksLikeClash(direct)) return direct;
                } catch (Throwable t) {
                    BalanceFetcher.diag(c, "订阅直连失败：" + t);
                }
                if (apiAlive()) {
                    try {
                        viaProxy = BalanceFetcher.getViaProxy(url, null, null, 25000);
                        if (looksLikeClash(viaProxy)) return viaProxy;
                    } catch (Throwable t) {
                        BalanceFetcher.diag(c, "订阅经代理失败：" + t);
                    }
                }
            } finally {
                BalanceFetcher.SUB_UA.remove();
                BalanceFetcher.SUB_ACCEPT.remove();
            }
            String got = direct != null ? direct : viaProxy;
            diagBody(c, "UA=" + ua, got);
            /* 一个字都没拿到 = 网络不通，换 UA 没用，别再耗时间 */
            if (got == null || got.length() == 0) break;
        }

        /* 内容拿到了但不像 Clash 配置 —— 大概率是机场按 UA 给了 base64 节点串。
           把开头打出来，用户反馈时一眼能看出是哪一种。 */
        String got = direct != null ? direct : viaProxy;
        if (got != null && got.length() > 0) {
            BalanceFetcher.diag(c, "订阅内容不是 Clash 格式，开头：" + clip(got, 120));
            if (looksLikeBase64List(got)) {
                throw new Exception("机场返回的是 base64 节点串，不是 Clash 配置。\n"
                        + "请到机场后台选择「Clash」格式的订阅链接（通常叫 Clash 订阅 / Clash 专用）");
            }
            throw new Exception("拿到的内容不是 Clash 配置（开头：" + clip(got, 60) + "）");
        }
        throw new Exception("订阅下载失败：直连与代理都没拿到内容。\n"
                + "可在上方节点列表里换个节点后重试；订阅站多为 HTTPS，节点只通 HTTP 时会失败");
    }

    /** 数一下子串出现几次（判断配置里有几段 proxy-groups / rules） */
    private static int countOccur(String s, String sub) {
        if (s == null || s.length() == 0) return 0;
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    /** 打一下订阅内容的开头（截断），用于判断机场到底返回了什么 */
    private static void diagBody(Context c, String how, String body) {
        if (body == null) return;
        /* 换行数单列出来：YAML 全靠换行分层，一旦被压成一行就解析不出任何东西
           （实测机场返回 22KB 内容、0 个换行 → 节点数 0）。 */
        int nl = 0;
        for (int i = 0; i < body.length(); i++) if (body.charAt(i) == '\n') nl++;
        BalanceFetcher.diag(c, "订阅" + how + "拿到 " + body.length() + " 字符，换行数=" + nl
                + "，开头：" + clip(body, 80));
    }

    /** 粗略判断是不是 base64 节点串（纯 base64 字符、很长、base64 解码后含 vmess:// 之类） */
    private static boolean looksLikeBase64List(String s) {
        try {
            String t = s.trim();
            if (t.length() < 40) return false;
            for (int i = 0; i < t.length(); i++) {
                char ch = t.charAt(i);
                boolean ok = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                        || (ch >= '0' && ch <= '9') || ch == '+' || ch == '/'
                        || ch == '=' || ch == '-' || ch == '_'
                        || ch == '\n' || ch == '\r';
                if (!ok) return false;
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }

    // ---------- 订阅流量信息 ----------

    /**
     * 订阅下载结果：配置正文 + 机场给出的流量信息。
     *
     * 流量来自响应头 `subscription-userinfo` —— 这是 Clash / Shadowrocket 的通用约定：
     *     upload=123; download=456; total=789; expire=1735689600
     * 单位是**字节**，expire 是秒级时间戳。机场不一定给，缺的字段保持 -1。
     */
    public static class SubInfo {
        public String yaml = "";
        public long up = -1, down = -1, total = -1, expire = -1;

        public boolean hasTraffic() { return total > 0; }
        public long used() { return Math.max(0, up) + Math.max(0, down); }
    }

    /**
     * 当前生效的出口节点名。
     *
     * /proxies 里每个组都有 now 字段，但第一个组未必是真正在用的那个
     * （机场常把「官网：xxx 请收藏」这类占位节点放在前面），
     * 所以优先返回第一个**不是 DIRECT/REJECT** 的组的 now。
     */
    public static String currentNodeName() {
        try {
            java.util.List<Group> gs = listGroups();
            String fallback = "";
            for (int i = 0; i < gs.size(); i++) {
                Group g = gs.get(i);
                String now = g.now == null ? "" : g.now;
                if (now.length() == 0) continue;
                if (fallback.length() == 0) fallback = now;
                if (!"DIRECT".equalsIgnoreCase(now) && !"REJECT".equalsIgnoreCase(now)) {
                    return now;
                }
            }
            return fallback;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 内核**本次运行**以来的累计流量，返回 [上行, 下行] 字节；取不到返回 null。
     * 数据来自控制接口 /connections 的 uploadTotal / downloadTotal。
     */
    public static long[] trafficTotals() {
        try {
            String body = apiGet("/connections", 2500);
            org.json.JSONObject o = new org.json.JSONObject(body);
            long up = o.optLong("uploadTotal", -1);
            long down = o.optLong("downloadTotal", -1);
            if (up < 0 && down < 0) return null;
            return new long[]{ Math.max(0, up), Math.max(0, down) };
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 打开应用时顺带检查：开了「自动更新」的订阅，距上次更新超过 6 小时就刷一次。
     *
     * 没做独立的定时闹钟 —— 订阅只是拿节点列表，晚几小时无所谓；
     * 而多一个后台唤醒源对省电是实打实的负担。跟着"打开应用"走就够了。
     */
    public static void autoUpdateSubs(final Context c) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    java.util.List<SubStore.Sub> subs = SubStore.all(c);
                    long now = System.currentTimeMillis();
                    for (int i = 0; i < subs.size(); i++) {
                        SubStore.Sub s = subs.get(i);
                        if (!s.autoUpdate) continue;
                        if (now - s.updatedAt < SubStore.Sub.AUTO_UPDATE_GAP) continue;
                        if (s.url == null || s.url.length() == 0) continue;
                        BalanceFetcher.diag(c, "自动更新订阅：" + s.name);
                        SubInfo info = fetchSubscriptionInfo(c, s.url);
                        if (!looksLikeClash(info.yaml)) {
                            BalanceFetcher.diag(c, "自动更新失败（内容不是 Clash 配置）：" + s.name);
                            continue;
                        }
                        SubStore.Sub fresh = SubStore.byId(c, s.id);
                        if (fresh == null) continue;
                        fresh.yaml = info.yaml;
                        fresh.nodeCount = countProxies(info.yaml);
                        fresh.updatedAt = System.currentTimeMillis();
                        if (info.hasTraffic()) {
                            fresh.up = info.up;
                            fresh.down = info.down;
                            fresh.total = info.total;
                            fresh.expire = info.expire;
                            fresh.trafficAt = System.currentTimeMillis();
                        }
                        SubStore.update(c, fresh);
                        BalanceFetcher.diag(c, "自动更新完成：" + s.name
                                + "（" + fresh.nodeCount + " 个节点）");
                    }
                } catch (Throwable t) {
                    BalanceFetcher.diag(c, "自动更新异常：" + t);
                }
            }
        }).start();
    }

    // ---------- 残留进程清理 ----------

    /**
     * 清理上一次运行残留的内核进程。
     *
     * **为什么必须做**：内核是本应用 fork 出来的子进程，App 被 force-stop
     * 或被系统回收时，子进程**不一定跟着退出** —— 实测残留了 3 个 mihomo，
     * 把 7890 端口占死，于是「启动加速」永远报"内核启动失败"。
     * （外部工具如 pkill 反而没权限杀它 —— 只有同 uid 的本应用能杀。）
     */
    private static void killStaleCore(Context c) {
        int killed = 0;
        try {
            int me = android.os.Process.myUid();
            int myPid = android.os.Process.myPid();
            java.io.File[] ps = new java.io.File("/proc").listFiles();
            if (ps == null) return;
            for (int i = 0; i < ps.length; i++) {
                String name = ps[i].getName();
                if (name.length() == 0 || !Character.isDigit(name.charAt(0))) continue;
                int pid;
                try { pid = Integer.parseInt(name); } catch (Exception e) { continue; }
                if (pid == myPid) continue;
                String cmd = readProcFile(ps[i], "cmdline");
                if (cmd == null || cmd.indexOf("mihomo") < 0) continue;
                if (procUid(ps[i]) != me) continue;          // 只动自己的进程
                android.os.Process.killProcess(pid);
                killed++;
            }
        } catch (Throwable ignored) { }
        if (killed > 0) BalanceFetcher.diag(c, "清理残留内核进程 " + killed + " 个");
    }

    /** 读 /proc/<pid>/xxx 的前若干字节 */
    private static String readProcFile(java.io.File dir, String name) {
        try {
            java.io.File f = new java.io.File(dir, name);
            if (!f.exists() || f.length() == 0) return null;
            byte[] buf = new byte[(int) Math.min(f.length(), 512)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(buf);
            in.close();
            return n <= 0 ? null : new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取进程 uid（/proc/<pid>/status 里的 Uid 行） */
    private static int procUid(java.io.File dir) {
        try {
            String st = readProcFile(dir, "status");
            if (st == null) return -1;
            int i = st.indexOf("Uid:");
            if (i < 0) return -1;
            int j = i + 4;
            while (j < st.length() && st.charAt(j) == ' ') j++;
            int k = j;
            while (k < st.length() && Character.isDigit(st.charAt(k))) k++;
            return Integer.parseInt(st.substring(j, k));
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 解析 subscription-userinfo 头 */
    static void parseUserInfo(String s, SubInfo o) {
        if (s == null || s.length() == 0) return;
        String[] parts = s.split(";");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String k = p.substring(0, eq).trim().toLowerCase();
            long n;
            try { n = Long.parseLong(p.substring(eq + 1).trim()); }
            catch (Exception e) { continue; }
            if ("upload".equals(k)) o.up = n;
            else if ("download".equals(k)) o.down = n;
            else if ("total".equals(k)) o.total = n;
            else if ("expire".equals(k)) o.expire = n;
        }
    }

    /**
     * 下载订阅，并尽量把流量信息一起带回来。
     *
     * 直接复用 fetchSubscription 那条**实测可用**的链路（直连 → 代理 → 多地址回退），
     * 只额外读一下 `BalanceFetcher.SUB_USERINFO` —— 底层解析响应头时顺手存的。
     *
     * 曾经试着改用 HttpURLConnection 自己发请求（那样能直接读头），
     * 结果在部分机场上被掐断（SSLHandshakeException: connection closed），
     * 反而把本来能用的下载搞坏了。**能跑通的链路别轻易换。**
     */
    public static SubInfo fetchSubscriptionInfo(Context c, String url) {
        SubInfo out = new SubInfo();
        BalanceFetcher.SUB_USERINFO.remove();       // 清掉上一次的，免得读到旧值
        try {
            out.yaml = fetchSubscription(c, url);
            parseUserInfo(BalanceFetcher.SUB_USERINFO.get(), out);
            BalanceFetcher.diag(c, "订阅下载完成，长度=" + out.yaml.length()
                    + " 节点数=" + countProxies(out.yaml)
                    + " 策略组=" + countOccur(out.yaml, "proxy-groups:")
                    + " 规则=" + countOccur(out.yaml, "rules:")
                    + " 流量头="
                    + (out.hasTraffic()
                       ? (out.used() + "/" + out.total + " 字节，到期 " + out.expire)
                       : "机场未提供")
                    + " 读取=" + BalanceFetcher.LAST_BODY_INFO
                    + "\n开头=" + clip(out.yaml, 200));
            /* 顺手留一份原始订阅到 App 外部目录：
               排查"下到了但解析不出节点"这类问题时能直接看原文。 */
            try {
                java.io.File dir = c.getExternalFilesDir(null);
                if (dir != null) {
                    java.io.FileWriter w = new java.io.FileWriter(
                            new java.io.File(dir, "last_subscription.yaml"), false);
                    w.write(out.yaml);
                    w.close();
                }
            } catch (Throwable ig) { }
        } catch (Throwable t) {
            out.yaml = "";
            BalanceFetcher.diag(c, "订阅下载失败：" + t);
        } finally {
            BalanceFetcher.SUB_USERINFO.remove();
        }
        return out;
    }

    /** 判断拿到的是不是 Clash 配置（有的机场对 Clash 的 UA 才返回 YAML，否则给 base64 节点串） */
    public static boolean looksLikeClash(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() < 20) return false;
        if (t.startsWith("{") || t.startsWith("<")) return false;      // JSON / HTML
        boolean hasProxy = t.indexOf("proxies:") >= 0;
        boolean hasRules = t.indexOf("rules:") >= 0;
        boolean hasGroup = t.indexOf("proxy-groups:") >= 0;
        return hasProxy || (hasRules && hasGroup);
    }

    /** 粗数一下订阅里有多少个节点（给用户一个「导入成功」的直观反馈） */
    public static int countProxies(String yaml) {
        if (yaml == null) return 0;
        int idx = yaml.indexOf("proxies:");
        if (idx < 0) return 0;
        int n = 0;
        String[] lines = yaml.substring(idx).split("\n");
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i];
            if (l.length() == 0) continue;
            if (l.charAt(0) != ' ' && l.charAt(0) != '\t') break;      // 回到顶层，说明这段结束
            if (l.trim().startsWith("- ")) n++;
        }
        return n;
    }

    // ---------- 控制 API：节点管理 ----------

    /** 一个可选的出口节点 */
    public static class Node {
        public String name = "";
        public String type = "";
        /** 最近一次实测延迟（ms）；-1 = 还没测过 */
        public int delay = -1;
    }

    /** 一个策略组（Clash 里的 Selector / URLTest / Fallback …） */
    public static class Group {
        public String name = "";
        public String type = "";
        /** 当前选中的节点 */
        public String now = "";
        public List<Node> nodes = new ArrayList<Node>();
    }

    /**
     * 拉取策略组与节点。
     *
     * mihomo 的 /proxies 返回一个大字典：每个条目形如
     *   {"type":"Selector","now":"香港01","all":["香港01","日本02"],"history":[...]}
     * 其中带 all 的才是"组"，不带的是具体节点（含 DIRECT / REJECT 这类内置项）。
     * 这里只把**组 → 成员节点**这一段整理出来，够界面用了。
     */
    public static List<Group> listGroups() throws Exception {
        String body = apiGet("/proxies", 4000);
        JSONObject root = new JSONObject(body);
        JSONObject proxies = root.optJSONObject("proxies");
        if (proxies == null) return new ArrayList<Group>();

        List<Group> out = new ArrayList<Group>();
        java.util.Iterator<String> keys = proxies.keys();
        while (keys.hasNext()) {
            String gname = keys.next();
            /* 必须用 opt() 取出原始值再判类型，不能直接 optJSONObject()：
               mihomo 的 /proxies 字典里**并非所有值都是对象**（较新版本会混入
               计数器之类的数字字段），而 Android 的 org.json 对非对象值调
               optJSONObject 会直接抛 JSONException
               （"Value NNN of type java.lang.Integer cannot be converted to JSONObject"），
               结果整个节点列表都读不出来。 */
            Object gv = proxies.opt(gname);
            if (!(gv instanceof JSONObject)) continue;
            JSONObject g = (JSONObject) gv;
            JSONArray all = g.optJSONArray("all");
            if (all == null || all.length() == 0) continue;      // 不是策略组
            String type = g.optString("type", "");

            Group grp = new Group();
            grp.name = gname;
            grp.type = type;
            grp.now = g.optString("now", "");
            for (int i = 0; i < all.length(); i++) {
                String nname = all.optString(i, "");
                if (nname.length() == 0) continue;
                Node n = new Node();
                n.name = nname;
                Object pv = proxies.opt(nname);
                if (pv instanceof JSONObject) {
                    JSONObject p = (JSONObject) pv;
                    n.type = p.optString("type", "");
                    n.delay = lastDelay(p);
                }
                grp.nodes.add(n);
            }
            out.add(grp);
        }
        return out;
    }

    /** 从节点对象里取最近一次测速结果（history 数组的最后一条） */
    private static int lastDelay(JSONObject p) {
        JSONArray h = p.optJSONArray("history");
        if (h == null || h.length() == 0) return -1;
        JSONObject last = h.optJSONObject(h.length() - 1);
        if (last == null) return -1;
        int d = last.optInt("delay", 0);
        return d > 0 ? d : -1;
    }

    /**
     * 测某个节点的延迟（ms）。
     *
     * 用 mihomo 自己的 /delay 接口，而不是我们发起真实请求 ——
     * 它内部会复用节点连接、正确处理各协议，返回的数值也更接近实际体验。
     * 测试目标用 generate_204 这类轻量地址：只握手不传数据，快且省流量。
     */
    public static int testDelay(String nodeName, int timeoutMs) {
        try {
            String path = "/proxies/" + urlEncode(nodeName) + "/delay?timeout="
                    + Math.max(1000, timeoutMs)
                    + "&url=" + urlEncode("http://www.gstatic.com/generate_204");
            JSONObject o = new JSONObject(apiGet(path, timeoutMs + 2000));
            int d = o.optInt("delay", -1);
            return d > 0 ? d : -1;
        } catch (Throwable t) {
            return -1;      // 超时/不可达都算"测不出"
        }
    }

    /**
     * 诊断：把所有策略组和它们的 now 打出来。
     * 排查「节点列表全显示未选中」这类问题时，一眼就能看出是哪个组在起作用。
     */
    public static String dumpGroupsDiag() {
        try {
            List<Group> gs = listGroups();
            StringBuilder sb = new StringBuilder("策略组共 " + gs.size() + " 个: ");
            for (int i = 0; i < gs.size(); i++) {
                Group g = gs.get(i);
                sb.append("[").append(g.name).append("/").append(g.type)
                  .append(" now=").append(g.now.length() == 0 ? "(空)" : g.now)
                  .append(" 共").append(g.nodes.size()).append("个] ");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "诊断失败: " + t;
        }
    }

    /** 这类节点不是真代理，选了等于直连 —— 自动选中时要跳过 */
    private static boolean isPseudoNode(String name) {
        if (name == null) return true;
        String n = name.trim().toUpperCase();
        return n.equals("DIRECT") || n.equals("REJECT") || n.equals("REJECT-DROP")
                || n.equals("PASS") || n.equals("COMPATIBLE");
    }

    /**
     * 若策略组的当前节点是 DIRECT/REJECT 这类「假节点」，自动切到一个真实节点。
     *
     * 为什么需要：订阅刚导入时，很多配置的策略组默认停在 DIRECT。
     * 此时「加速已启动、节点也列出来了」，但出口其实是直连 ——
     * 表现就是境外平台照样连不上，而且报的是
     * **connection closed**（被墙 RST）而不是超时，非常难往代理方向想。
     *
     * @return 自动切换到的节点名；没切换则返回 null
     */
    public static String autoSelectRealNode() {
        try {
            List<Group> gs = listGroups();
            for (int i = 0; i < gs.size(); i++) {
                Group g = gs.get(i);
                String pick = null;
                for (int j = 0; j < g.nodes.size(); j++) {
                    if (!isPseudoNode(g.nodes.get(j).name)) { pick = g.nodes.get(j).name; break; }
                }
                if (pick == null) continue;                 // 这组没有真实节点，跳过
                if (!isPseudoNode(g.now)) return null;      // 已是真实节点，无需处理
                /* 注意 GLOBAL 组：mihomo 里它虽然叫 GLOBAL，但未必是流量实际走的组 ——
                   真正起作用的是 rules 指向的那个组。所以这里按「顺序第一个
                   既有真实节点、now 又是真实节点的组」判断，避免误把 GLOBAL 当成主组。 */
                if (g.name != null && g.name.toUpperCase().contains("GLOBAL")) continue;
                selectNode(g.name, pick);
                return pick;
            }
            /* 所有组都没能自动纠正：退一步，挑第一个有真实节点的组来切 */
            for (int i = 0; i < gs.size(); i++) {
                Group g = gs.get(i);
                if (g.name != null && g.name.toUpperCase().contains("GLOBAL")) continue;
                for (int j = 0; j < g.nodes.size(); j++) {
                    if (!isPseudoNode(g.nodes.get(j).name)) {
                        selectNode(g.name, g.nodes.get(j).name);
                        return g.nodes.get(j).name;
                    }
                }
            }
        } catch (Throwable t) {
            BalanceFetcher.diag(null, "自动选节点失败 " + t);
        }
        return null;
    }

    /**
     * 切换策略组当前使用的节点。
     *
     * 成功时内核返回 **204 空响应**（不是 JSON），所以这里不解析返回体 ——
     * 只要没抛异常就说明切好了。要确认结果可以重新拉一次 listGroups()。
     */
    public static boolean selectNode(String group, String node) throws Exception {
        JSONObject body = new JSONObject();
        body.put("name", node);
        apiSend("PUT", "/proxies/" + urlEncode(group), body.toString());
        return true;
    }

    /** 让内核重新读取订阅（机场更新了节点列表时用） */
    public static void reloadConfig() throws Exception {
        String r = apiSend("PUT", "/configs?force=true",
                "{\"path\":\"\",\"payload\":\"\"}");
    }

    // ---------- 控制 API 底层 ----------

    static String apiGet(String path, int timeoutMs) throws Exception {
        return httpExchange("GET", path, null, timeoutMs);
    }

    /**
     * 带请求体的本地调用（切换节点 / 重载配置）。
     */
    static String apiSend(String method, String path, String jsonBody) throws Exception {
        return httpExchange(method, path, jsonBody, 6000);
    }

    /**
     * 本机控制接口的极简 HTTP 客户端。
     *
     * 两个必须自己处理的点：
     *
     * ① **不能用 HttpURLConnection** —— manifest 声明了
     *    `usesCleartextTraffic="false"`，而控制口是 `http://127.0.0.1:9090`（明文），
     *    会被网络策略直接拦掉，表现为「内核明明活着却一直探不到」。
     *    裸 socket 不受该策略约束，本机回环也没有明文外传的风险。
     *
     * ② **必须自己解 chunked** —— 这是踩过的坑：mihomo 的分块响应形如
     *    `3288\r\n{"proxies":{…}}\r\n0\r\n`，如果不剥掉分块头，
     *    这段会被当成 JSON 去解析，`3288` 就成了顶层值，报出
     *    「Value 3288 of type java.lang.Integer cannot be converted to JSONObject」
     *    —— 错误信息里那个数字正是分块长度，非常误导。
     *    所以这里按字节解析响应头、识别 Transfer-Encoding，再决定是否解分块。
     */
    static String httpExchange(String method, String path, String jsonBody,
                               int timeoutMs) throws Exception {
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress("127.0.0.1", API_PORT),
                    Math.min(timeoutMs, 4000));
            sock.setSoTimeout(timeoutMs);

            byte[] body = (jsonBody == null ? "" : jsonBody).getBytes("UTF-8");
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: 127.0.0.1:").append(API_PORT).append("\r\n")
                .append("Accept: application/json\r\n");
            if (body.length > 0) head.append("Content-Type: application/json\r\n");
            head.append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n\r\n");

            java.io.OutputStream os = sock.getOutputStream();
            os.write(head.toString().getBytes("UTF-8"));
            if (body.length > 0) os.write(body);
            os.flush();

            java.io.InputStream in = sock.getInputStream();
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) raw.write(buf, 0, n);
            return parseHttpResponse(raw.toByteArray());
        } finally {
            try { sock.close(); } catch (Exception ig) { }
        }
    }

    /** 解析 HTTP 响应字节流：查状态码 → 剥 chunked → UTF-8 解码 */
    private static String parseHttpResponse(byte[] resp) throws Exception {
        int he = -1;
        for (int i = 0; i + 3 < resp.length; i++) {
            if (resp[i] == 13 && resp[i + 1] == 10 && resp[i + 2] == 13 && resp[i + 3] == 10) {
                he = i + 4;
                break;
            }
        }
        if (he < 0) throw new Exception("控制接口响应格式异常");
        String header = new String(resp, 0, he, "ISO-8859-1");

        int sp = header.indexOf(' ');
        if (sp > 0 && header.length() > sp + 4) {
            int code = 0;
            try { code = Integer.parseInt(header.substring(sp + 1, sp + 4).trim()); }
            catch (Exception ig) { }
            if (code < 200 || code >= 300) throw new Exception("控制接口返回 HTTP " + code);
        }

        byte[] bodyBytes;
        if (header.toLowerCase().indexOf("transfer-encoding: chunked") >= 0) {
            bodyBytes = decodeChunked(resp, he);
        } else {
            bodyBytes = new byte[resp.length - he];
            System.arraycopy(resp, he, bodyBytes, 0, bodyBytes.length);
        }
        /* 空响应体是**合法**的，不能当错误：
           mihomo 的写操作（PUT /proxies/{name} 切节点、PUT /configs 重载）
           成功时返回 204 No Content，body 本来就是空的。
           之前这里直接抛「控制接口返回空内容」，导致**每次切换节点都报错**，
           但实际上节点已经切过去了 —— 典型的"操作成功却提示失败"。 */
        return new String(bodyBytes, "UTF-8").trim();
    }

    /** 解 chunked：循环读「十六进制长度行 + 数据 + CRLF」直到长度 0 */
    private static byte[] decodeChunked(byte[] r, int off) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int i = off;
        while (i < r.length) {
            int e = i;
            while (e < r.length && r[e] != 10) e++;        // 找行尾
            if (e >= r.length) break;
            String line = new String(r, i, e - i, "ISO-8859-1").trim();
            int semi = line.indexOf(';');                   // 可能带扩展参数
            if (semi >= 0) line = line.substring(0, semi);
            int len;
            try { len = Integer.parseInt(line.trim(), 16); }
            catch (Exception ex) { break; }
            i = e + 1;
            if (len == 0) break;                            // 结束块
            int take = Math.min(len, r.length - i);
            if (take > 0) out.write(r, i, take);
            i += take;
            while (i < r.length && (r[i] == 13 || r[i] == 10)) i++;   // 跳过块后 CRLF
        }
        return out.toByteArray();
    }


    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Throwable t) {
            return s;
        }
    }

    // ---------- 小工具 ----------

    /** 读文本文件（没有/失败都返回空串） */
    public static String readText(File f) {
        try {
            if (f == null || !f.exists()) return "";
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int off = 0;
                while (off < buf.length) {
                    int r = in.read(buf, off, buf.length - off);
                    if (r <= 0) break;
                    off += r;
                }
            } finally {
                in.close();
            }
            return new String(buf, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 写文本文件（订阅配置 / 内核配置） */
    private static void writeText(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(s.getBytes("UTF-8"));
        } finally {
            o.close();
        }
    }

    /** 读日志尾部若干字符（启动失败时给用户看原因） */
    public static String tail(File f, int max) {
        try {
            long len = f.length();
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            try {
                long from = Math.max(0, len - max);
                raf.seek(from);
                byte[] buf = new byte[(int) (len - from)];
                raf.readFully(buf);
                String s = new String(buf, "UTF-8");
                int nl = s.lastIndexOf('\n', s.length() - 2);
                return nl > 0 ? s.substring(nl + 1).trim() : s.trim();
            } finally {
                raf.close();
            }
        } catch (Throwable t) {
            return "(读日志失败)";
        }
    }

    /** 设备是否 64 位 arm（32 位机器没有对应内核） */
    public static boolean abiSupported() {
        try {
            for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
                if (abi != null && abi.startsWith("arm64")) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }
}
