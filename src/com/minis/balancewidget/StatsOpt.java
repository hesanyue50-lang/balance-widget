package com.minis.balancewidget;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 「要不要算进余额统计」的逐平台开关，外加积分折算率与控制台地址。
 *
 * 背景：像 WorkBuddy 网关这种平台返回的不是钱而是**积分**，
 * 1 积分 ≠ 1 元，直接加进总额/折线图会得到一个没意义的数字。
 * 但用户又可能想让它参与统计（比如按折算价当钱看），所以做成开关：
 *   默认不统计 → 卡片照常显示，金额仍能一眼看到，只是不进汇总和曲线。
 *
 * 折算率：官方套餐换算出来的积分单价约 0.02 元/积分
 * （标准版 ¥99/4000分、高级版 ¥199/9000分、旗舰版 ¥999/50000分，
 *  正价约 0.020~0.025，连续包月/包年更低）。
 * 这是**估值**不是官方汇率 —— 积分只能换套餐权益，不能提现，
 * 所以算出来的"余额"只是个参考，界面会标「估」字提醒。
 */
public class StatsOpt {

    private static final String P = "stats_opt";

    /** 默认折算率：元/积分。取官方正价档的中间值。 */
    public static final double DEFAULT_RATE = 0.02;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    /**
     * 该平台是否计入余额统计与图表。
     *
     * 钱的平台（kind=balance）恒为 true —— 它本来就是钱，没有"要不要算"的选择，
     * 界面上那个开关也是灰的。只有积分类（asset）才由用户决定，默认 false。
     */
    public static boolean inStats(Context c, String platform) {
        if (isMoneyPlatform(platform)) return true;
        return sp(c).getBoolean("in_" + platform, false);
    }

    /** 平台是不是"真钱"类。查预设表，查不到按积分/免费处理（不擅自当钱加进总额）。 */
    private static boolean isMoneyPlatform(String platform) {
        BalanceFetcher.Preset p = BalanceFetcher.presetOf(platform);
        return p != null && "balance".equals(p.kind);
    }

    public static void setInStats(Context c, String platform, boolean in) {
        /* 钱类平台不接受写入：它的 inStats 恒为 true，写了也是死数据，
           将来有人改判定逻辑时会被这堆历史值咬一口。 */
        if (isMoneyPlatform(platform)) return;
        sp(c).edit().putBoolean("in_" + platform, in).apply();
    }

    public static boolean toggle(Context c, String platform) {
        boolean v = !inStats(c, platform);
        setInStats(c, platform, v);
        return v;
    }

    /**
     * 该平台是否出现在「用量统计」图表里。
     *
     * 跟 inStats 是两件事：inStats 管"算不算钱"（进总资产），
     * inChart 管"画不画图"。想只画图不算钱、或只算钱不画图，都能单独调。
     */
    public static boolean inChart(Context c, String platform) {
        if (isMoneyPlatform(platform)) return true;
        return sp(c).getBoolean("chart_" + platform, false);
    }

    public static void setInChart(Context c, String platform, boolean in) {
        if (isMoneyPlatform(platform)) return;
        sp(c).edit().putBoolean("chart_" + platform, in).apply();
    }

    public static void toggleChart(Context c, String platform) {
        boolean v = !inChart(c, platform);
        /* 开图表时顺手打开记账：画图要有历史点，否则开了也是空线，
           用户还得自己去别处再找一个开关，体验很断。 */
        if (v) setInStats(c, platform, true);
        setInChart(c, platform, v);
    }

    /** 折算率（元/积分）。0 或负数视为未设置，回落到默认值。 */
    public static double rate(Context c, String platform) {
        float v = sp(c).getFloat("rate_" + platform, 0f);
        if (v <= 0f) v = (float) DEFAULT_RATE;
        return v;
    }

    public static void setRate(Context c, String platform, double rate) {
        sp(c).edit().putFloat("rate_" + platform, (float) rate).apply();
    }

    /** 用户单独指定的「控制台」地址。空 = 没填。 */
    /** 用户单独指定的「控制台」地址 —— 已废弃：控制台固定为 网关地址 + /panel/ */
    @Deprecated
    public static String consoleUrl(Context c, String platform) {
        String s = sp(c).getString("console_" + platform, "");
        return s == null ? "" : s.trim();
    }

    public static void setConsoleUrl(Context c, String platform, String url) {
        sp(c).edit().putString("console_" + platform, url == null ? "" : url.trim()).apply();
    }

    /**
     * 把一个以「积分」为单位的数值折算成人民币。
     * 平台没开统计时返回 NaN —— 调用方据此跳过累加。
     */
    public static double toCny(Context c, String platform, double credits) {
        if (!inStats(c, platform)) return Double.NaN;
        return credits * rate(c, platform);
    }

    /** 某个 Item 折算后的人民币金额；不参与统计返回 NaN。 */
    public static double cnyOf(Context c, BalanceFetcher.Item it) {
        if (it == null || !it.ok) return Double.NaN;
        // 本来就是钱（kind=balance）的平台直接用它自己的金额
        if ("balance".equals(it.kind)) return it.cny;
        // 积分类：仅当用户开了统计才折算
        if (!inStats(c, it.platform)) return Double.NaN;
        return it.bal * rate(c, it.platform);
    }
}
