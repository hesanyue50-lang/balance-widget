package com.minis.balancewidget;

/**
 * 平台模板库：给「自定义平台」提供一键填充的常见平台元数据。
 *
 * 为什么是模板而不是内置平台：
 * 余额查询接口**不是行业标准** —— 大多数平台没有公开的"查余额" API，
 * 有的藏在控制台私有接口里、有的需要 AK/SK 签名、有的干脆没有。
 * 盲加进内置列表的结果是"用户选了、点了、报 404"，比没有更糟。
 *
 * 模板的价值：把**确定的信息**（平台名、控制台地址、单位、制式）一次填好，
 * 端点让用户按官方文档确认后填 —— 填错了改一下就行，不用从零查起。
 *
 * `verified` 标记端点是否实测存在（用无效 Key 探测：401/403 = 存在，404 = 不存在）。
 * 未验证的必须在 UI 上明说，不能让人以为"选了就能用"。
 */
public final class PlatformTemplates {

    public static class Tpl {
        public final String name;
        /** 余额接口（空 = 未确认，用户自己填） */
        public final String url;
        /** JSON 取值路径候选（| 分隔） */
        public final String paths;
        public final String unit;       // CNY / USD
        public final String kind;       // balance / asset
        public final String console;    // 控制台（账单/用量入口）
        /** 端点是否实测存在 */
        public final boolean verified;
        /** 给用户看的补充说明 */
        public final String note;

        Tpl(String name, String url, String paths, String unit, String kind,
            String console, boolean verified, String note) {
            this.name = name;
            this.url = url;
            this.paths = paths;
            this.unit = unit;
            this.kind = kind;
            this.console = console;
            this.verified = verified;
            this.note = note;
        }
    }

    /** 通用余额路径候选：大部分 OpenAI 兼容平台沿用这套命名 */
    private static final String P = "data.balance|balance|data.credit|credit|data.remain|remain";

    public static final Tpl[] ALL = {
            /* ---------- 端点实测存在（无效 Key 返回 401/403） ---------- */
            new Tpl("PPIO 派欧云", "https://api.ppio.com/v3/account/balance",
                    "data.balance|balance", "CNY", "balance",
                    "https://ppio.com/user/balance", true,
                    "端点已验证。余额在 data.balance"),

            /* ---------- OpenAI 兼容、路径沿用通用命名（端点需按文档确认） ---------- */
            new Tpl("MiniMax", "", P, "CNY", "balance",
                    "https://platform.minimaxi.com/user-center/basic-information", false,
                    "需 GroupId，余额接口按官方文档填（控制台里能看到余额）"),
            new Tpl("零一万物 01.AI", "", P, "CNY", "balance",
                    "https://platform.lingyiwanwu.com/", false,
                    "控制台有余额页，API 端点按文档确认"),
            new Tpl("百川智能", "", P, "CNY", "balance",
                    "https://platform.baichuan-ai.com/", false,
                    "控制台有余额页，API 端点按文档确认"),
            new Tpl("天工 / 昆仑万维", "", P, "CNY", "balance",
                    "https://platform.tiangong.cn/", false,
                    "控制台有余额页，API 端点按文档确认"),
            new Tpl("百度千帆", "", "data.balance|balance", "CNY", "balance",
                    "https://console.bce.baidu.com/qianfan/ais/console/onlineService", false,
                    "需 AK/SK 或 IAM 签名，接口较复杂；建议先看控制台"),
            new Tpl("腾讯混元", "", "data.balance|balance", "CNY", "balance",
                    "https://console.cloud.tencent.com/hunyuan", false,
                    "腾讯云 TC3 签名，接口较复杂；建议先看控制台"),

            /* ---------- 国外平台（多数需走代理，端点未逐一验证） ---------- */
            new Tpl("Groq", "", P, "USD", "balance",
                    "https://console.groq.com/settings/billing", false,
                    "免费额度制，控制台看用量；如需查余额请按文档填端点"),
            new Tpl("Together AI", "https://api.together.xyz/v1/account",
                    "data.balance|balance|credit", "USD", "balance",
                    "https://api.together.ai/settings/billing", false,
                    "端点来自公开文档，需代理访问；响应字段可能变化"),
            new Tpl("DeepInfra", "https://api.deepinfra.com/v1/user/balance",
                    P, "USD", "balance",
                    "https://deepinfra.com/dash/usage", false,
                    "端点来自公开文档，需代理访问"),
            new Tpl("Cerebras", "", P, "USD", "balance",
                    "https://cloud.cerebras.ai/", false,
                    "控制台有余额，API 端点按文档确认（有地区限制）"),
            new Tpl("Mistral", "", P, "USD", "balance",
                    "https://console.mistral.ai/billing/", false,
                    "控制台有账单页，API 端点按文档确认"),
            new Tpl("Cohere", "", P, "USD", "balance",
                    "https://dashboard.cohere.com/billing", false,
                    "控制台有账单页，API 端点按文档确认"),
    };

    /**
     * 这个模板能不能**自动查余额**。
     *
     * 判据：有端点 **且** 端点已验证。只有 PPIO 满足 —— 其余平台要么没有
     * 公开余额 API，要么端点没确认。这类模板建出来是"静态卡"：
     * 卡片上显示「余额见控制台」，不发请求、不会报红字。
     */
    public static boolean autoQuery(Tpl t) {
        return t != null && t.verified && t.url != null && t.url.length() > 0;
    }

    /** 列表里给用户看的一行说明（一眼看出这个模板能干什么） */
    public static String label(Tpl t) {
        return autoQuery(t)
                ? t.name + " · 可自动查余额"
                : t.name + " · 仅记录（看控制台）";
    }

    private PlatformTemplates() { }
}
