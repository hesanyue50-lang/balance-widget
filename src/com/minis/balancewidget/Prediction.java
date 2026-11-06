package com.minis.balancewidget;

/**
 * 余额耗尽预测（纯计算，不碰 Context / 数据库 / UI）。
 *
 * 为什么要单独一个类，而不是写在 Ledger 里、或者直接塞进界面：
 * 这段逻辑最容易出错（符号、区间长度、充值抵扣），也最需要能被反复验证。
 * 把数据查询和算术切开之后，算法可以在普通 JVM 上喂假数据跑，不必起模拟器。
 * 所以这里**只接受数组**：由调用方（{@link Ledger#estimate}）把快照与充值查好传进来。
 *
 * <h3>算法：区间法</h3>
 * 对相邻两个快照 i、i+1：
 * <pre>
 *     Δ = bal[i] − bal[i+1] + 该区间内的充值总额
 * </pre>
 * 三项的来由：余额差是"净消耗"，充值是"往回补的钱"。把充值加回来，
 * 得到的就是这段时间**真实花掉**的钱 —— 不补的话，充过值的那天看起来像"余额涨了"，
 * 会被当成 0 消耗甚至负消耗（这是最容易犯的错，也是本类要解决的核心问题）。
 *
 * 规则：
 * <ul>
 *   <li>{@code Δ > 0}：记为消耗，区间时长（小时）计入分母；</li>
 *   <li>{@code Δ < 0}：余额涨了、却没有任何充值记录 —— 解释不通（漏记充值 / 缓存回跳 /
 *       换过账号）。**整个区间丢弃**：不计入分子也不计入分母。
 *       只丢分子会低估日均（分母还是那么大），只丢分母会高估，两边一起丢才干净。</li>
 *   <li>{@code |Δ| <= }{@link #ZERO_EPS}：余额基本没动，记为 0 消耗，但时长**保留** ——
 *       "6 小时没花钱"本身是有效信息，不该被当成缺数据。</li>
 * </ul>
 *
 * <h3>为什么要有"有效时长不足 24 小时就返回 -1"</h3>
 * 两个点只能算出一段的斜率。半小时里掉 0.5 元，线性外推是"每天 24 元"，
 * 但这个数字的置信度几乎为零；显示出去等于编。宁可显示"数据积累中"。
 *
 * <h3>边界一律"不预测"</h3>
 * perDay &lt; 0.001、balance &lt;= 0、出现 NaN/Infinity 时，返回 -1（日均/天数）或 0（时间戳）。
 * 界面必须能区分"算不出来（-1）"和"真的是 0 天"，所以**绝不返回 0 天**：
 * 不足 1 天时 {@link #daysLeft} 给 1。
 *
 * 全部静态方法、无状态，可在任意线程调用。
 */
public final class Prediction {

    /** 预测上限：999 天。再远就不是预测，是许愿了 */
    public static final double MAX_DAYS = 999d;

    /** 日均低于这个数视为"没有可测的消耗"，不外推（否则会算出几万天） */
    public static final double MIN_BURN = 0.001d;

    /** 余额视为"没变化"的容差（与 Ledger 的采样判定同一量级） */
    static final double ZERO_EPS = 0.005d;

    /** 有效数据下限：24 小时。不足则拒绝预测 */
    public static final double MIN_VALID_HOURS = 24d;

    private static final double DAY_MS = 86400000d;
    private static final double HOUR_MS = 3600000d;

    private Prediction() {
    }

    // ---------------------------------------------------------------- 核心

    /**
     * 近 N 天日均消耗（原币种，账户维度）。数据不足返回 -1。
     *
     * 本方法把"有效时长"丢掉只留速率；需要时长（比如判断置信度、或者做多 Key 加权）时
     * 用 {@link #burnStats}，它返回 {日均, 有效小时数}。
     *
     * @param tsMs            快照时间戳（毫秒，**升序**；Ledger.series 保证这个顺序）
     * @param balances        对应余额（原币种）
     * @param rechargeTsMs    充值时间戳，可为 null
     * @param rechargeAmounts 充值金额（正数），可为 null
     * @param sinceMs         统计窗口起点；<=0 表示不限。只统计**结束时间**在窗口内的区间
     * @return 日均消耗（> 0）；数据不足 / 读数异常返回 -1
     */
    public static double burnPerDay(double[] tsMs, double[] balances,
                                    double[] rechargeTsMs, double[] rechargeAmounts,
                                    long sinceMs) {
        return burnStats(tsMs, balances, rechargeTsMs, rechargeAmounts, sinceMs)[0];
    }

    /**
     * {@link #burnPerDay} 的完整版：返回 {日均消耗, 有效区间总小时数}。
     *
     * 拆出时长是给多 Key 合并用的：同一账户下多个 Key 各算一条速率，
     * 按各自的有效时长做加权平均，才不会让"点多的那条 Key"凭点数压过真实速率。
     *
     * @return 长度恒为 2 的数组；算不出来时 { -1, 0 }
     */
    static double[] burnStats(double[] tsMs, double[] balances,
                              double[] rechargeTsMs, double[] rechargeAmounts,
                              long sinceMs) {
        double[] none = {-1d, 0d};
        if (tsMs == null || balances == null) return none;

        int n = Math.min(tsMs.length, balances.length);
        if (n < 2) return none;

        double consumed = 0d;      // Σ Δ（只累加 Δ > 0 的区间）
        double hours = 0d;         // 有效区间总时长

        for (int i = 0; i + 1 < n; i++) {
            double t1 = tsMs[i], t2 = tsMs[i + 1];
            double b1 = balances[i], b2 = balances[i + 1];

            /* 坏点：跳过它两侧的区间（本区间 + 下一轮的那个）。
               不把整段数据判死 —— 90 天里有一个脏点就全不给预测，那才是真的没用。 */
            if (bad(t1) || bad(t2) || bad(b1) || bad(b2)) continue;

            double span = t2 - t1;
            if (span <= 0) continue;                    // 时间没前进 / 乱序：无从计时
            if (sinceMs > 0 && t2 < sinceMs) continue;  // 整段在窗口之外

            double recharged = rechargedBetween(rechargeTsMs, rechargeAmounts, t1, t2);
            double delta = b1 - b2 + recharged;

            if (delta > ZERO_EPS) {
                consumed += delta;
                hours += span / HOUR_MS;
            } else if (delta < -ZERO_EPS) {
                /* 余额上涨却没有充值记录 —— 数据异常，整段丢弃（分子分母都不要） */
                continue;
            } else {
                /* 余额没动：0 消耗，但时长有效 */
                hours += span / HOUR_MS;
            }
        }

        if (hours < MIN_VALID_HOURS) return none;

        double perDay = consumed / (hours / 24d);
        if (bad(perDay) || perDay < MIN_BURN) return none;

        double[] out = {perDay, hours};
        return out;
    }

    /**
     * 预计耗尽时间戳；perDay &lt;= 0 / balance &lt;= 0 返回 0；上限 999 天。
     *
     * @param nowMs 基准时刻；&lt;= 0 时用当前时间
     * @return 毫秒时间戳；0 = 不做预测
     */
    public static long exhaustAt(double balance, double perDay, long nowMs) {
        double days = daysRaw(balance, perDay);
        if (bad(days) || days <= 0) return 0;
        long base = nowMs > 0 ? nowMs : System.currentTimeMillis();
        long add = (long) Math.round(days * DAY_MS);
        if (add < 0) return 0;                       // 理论上到不了，防溢出兜底
        return base + add;
    }

    /**
     * 剩余整数天；算不出来返回 -1。
     *
     * 取整规则：满 1 天以上向下取整（3.9 天说"3 天"，不说"4 天"，别让用户以为还能撑到第 4 天）；
     * 但**正数不足 1 天时返回 1** —— 返回 0 会被界面渲染成"还剩 0 天"，
     * 那比"还剩 1 天"更像是算错了。
     */
    public static int daysLeft(double balance, double perDay) {
        double days = daysRaw(balance, perDay);
        if (bad(days) || days <= 0) return -1;
        if (days >= MAX_DAYS) return (int) MAX_DAYS;
        int v = (int) Math.floor(days);
        if (v < 1) v = 1;
        return v;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 剩余天数的原始值（可能带小数）；不合法返回 -1，超上限截到 {@link #MAX_DAYS}。
     * daysLeft / exhaustAt 共用，避免两处取整口径走偏。
     */
    private static double daysRaw(double balance, double perDay) {
        if (bad(balance) || bad(perDay)) return -1d;
        if (balance <= 0 || perDay < MIN_BURN) return -1d;
        double d = balance / perDay;
        if (bad(d) || d <= 0) return -1d;
        return d > MAX_DAYS ? MAX_DAYS : d;
    }

    /** 区间 (from, to] 内的充值总额。recharge 按"记在哪个时刻就属于哪个区间"归并 */
    private static double rechargedBetween(double[] rts, double[] ramt, double from, double to) {
        if (rts == null || ramt == null) return 0d;
        int m = Math.min(rts.length, ramt.length);
        double sum = 0d;
        for (int k = 0; k < m; k++) {
            double t = rts[k], a = ramt[k];
            if (bad(t) || bad(a)) continue;
            if (t > from && t <= to) sum += a;
        }
        return sum;
    }

    private static boolean bad(double v) {
        return Double.isNaN(v) || Double.isInfinite(v);
    }

    // ---------------------------------------------------------------- 结果

    /**
     * 一次预测的结果。字段全部 public：这是给 UI 直接读的数据袋，不做封装。
     *
     * 约定：{@code perDay <= 0} 表示"算不出来"，此时 daysLeft = -1、exhaustAtMs = 0。
     * 界面要先看 {@link #ok()} 再决定显示什么，别直接格式化 -1。
     */
    public static final class Estimate {
        /** 日均消耗（原币种）；-1 = 数据不足 */
        public double perDay = -1d;
        /** 剩余整数天；-1 = 算不出来 */
        public int daysLeft = -1;
        /** 预计耗尽时间戳；0 = 不做预测 */
        public long exhaustAtMs = 0L;
        /** 参与计算的有效天数（有效小时数 / 24）。用来判断这预测有多可信 */
        public double validDays = 0d;
        /** 计算时用的当前余额（最新一条快照） */
        public double balance = 0d;

        /** 是否给出了一个可展示的预测 */
        public boolean ok() {
            return perDay > 0 && daysLeft > 0 && exhaustAtMs > 0;
        }

        @Override
        public String toString() {
            if (!ok()) return "Estimate(无预测, validDays=" + fmt(validDays) + ")";
            return "Estimate(日均 " + fmt(perDay) + ", 还剩 " + daysLeft
                    + " 天, 有效 " + fmt(validDays) + " 天)";
        }

        private static String fmt(double v) {
            return String.format(java.util.Locale.US, "%.2f", v);
        }
    }
}
