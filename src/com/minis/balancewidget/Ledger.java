package com.minis.balancewidget;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史账本（SQLite）。
 *
 * 为什么必须用 SQLite：每 6 小时一条、20 个 Key、一年就是三万多行 ——
 * SharedPreferences 塞不下这种时间序列，也没法按区间查询。
 *
 * 两张表：
 *   snapshots  余额快照（每 6 小时一条）
 *   recharges  充值记录（由余额突增反推出来的）
 *
 * 消费不单独存表 —— 它可以从余额曲线**推导**：
 *     期间消费 = 前一次余额 + 期间充值 − 后一次余额
 * 存两份数据容易对不上，留一份真相更可靠。
 */
public final class Ledger extends SQLiteOpenHelper {

    private static final String DB = "ledger.db";
    private static final int VER = 1;

    public static final String T_SNAP = "snapshots";
    public static final String T_RECH = "recharges";

    /** 默认保留天数：超出部分由 prune() 清掉 */
    public static final int KEEP_DAYS_DEFAULT = 90;

    /**
     * 兜底采样间隔：6 小时。
     *
     * 余额长期没有变化时，也保证这么久落一个点 —— 曲线不会断档，
     * 消耗统计（相邻点差值）也不会因为中间空太久而失真。
     */
    public static final long SAMPLE_MS = 6L * 60 * 60 * 1000;

    /**
     * 常规采样下限：5 分钟。
     *
     * 余额**有变化**时，最快按这个间隔落点 —— 也就是"每次刷新都打一个点"
     * （前台刷新间隔本身就是 5 分钟起）。余额没变则不必重复记，
     * 那种点除了占空间没有信息量，交给上面的 6 小时兜底。
     */
    public static final long SAMPLE_MIN_MS = 5L * 60 * 1000;

    /** 余额视为"没有变化"的容差（元）。小于它就不值得单独落一个点 */
    private static final double SAME_EPS = 0.005;

    private static Ledger inst;

    public static synchronized Ledger get(Context c) {
        if (inst == null) inst = new Ledger(c.getApplicationContext());
        return inst;
    }

    private Ledger(Context c) {
        super(c, DB, null, VER);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_SNAP + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "key_id TEXT NOT NULL,"
                + "ts INTEGER NOT NULL,"
                + "balance REAL NOT NULL,"          // 余额（原币种，便于比对充值档位）
                + "topped_up REAL"                  // 累计充值（平台不给则 NULL）
                + ")");
        db.execSQL("CREATE INDEX idx_snap ON " + T_SNAP + "(key_id, ts)");

        db.execSQL("CREATE TABLE " + T_RECH + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "key_id TEXT NOT NULL,"
                + "ts INTEGER NOT NULL,"
                + "delta REAL NOT NULL,"            // 余额实际净增
                + "matched REAL,"                   // 匹配到的档位（NULL = 没匹配上）
                + "amount REAL NOT NULL"            // 记账金额（匹配值，或净增）
                + ")");
        db.execSQL("CREATE INDEX idx_rech ON " + T_RECH + "(key_id, ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // 结构变了就重建 —— 这个库是纯记录，重建的代价只是丢历史，不会影响功能
        db.execSQL("DROP TABLE IF EXISTS " + T_SNAP);
        db.execSQL("DROP TABLE IF EXISTS " + T_RECH);
        onCreate(db);
    }

    // ---------- 写入 ----------

    /**
     * 记一条快照；如果余额比上次明显上升，顺带推导出一条充值记录。
     *
     * 充值判定逻辑（用户确认过的口径）：
     *     净增 Δ = 本次余额 − 上次余额
     *     Δ > 阈值  →  充值额 = 最接近且 ≥ Δ 的档位
     *                  当期消费 = 充值额 − Δ   ← 差额照样算作消耗，账不能不平
     *     Δ ≤ 阈值  →  没有充值，消费就是 −Δ
     *
     * 返回本次推导出的消费额（没有充值就是余额的减少量；余额不降则为 0）。
     */
    public double record(Context c, String keyId, double balance, double toppedUp, boolean usd) {
        double consumed = 0;
        try {
            SQLiteDatabase db = getWritableDatabase();
            double prevBal = Double.NaN;
            long prevTs = 0;

            Cursor cur = db.rawQuery("SELECT ts, balance FROM " + T_SNAP
                    + " WHERE key_id=? ORDER BY ts DESC LIMIT 1", new String[]{keyId});
            if (cur.moveToFirst()) {
                prevTs = cur.getLong(0);
                prevBal = cur.getDouble(1);
            }
            cur.close();

            long now = System.currentTimeMillis();

            /* ---- 采样节奏（"每次刷新打点 + 保底 6 小时"）----
               ① 距上次太近（< 5 分钟）→ 跳过，防止同一波刷新重复落点
               ② 余额和上次一样，且还没到 6 小时兜底 → 跳过，这种点没有信息量
               余额变了就落点，所以"每次刷新"实际上都会记上一个新点。 */
            if (!Double.isNaN(prevBal)) {
                long gap = now - prevTs;
                if (gap < SAMPLE_MIN_MS) return 0;
                if (Math.abs(balance - prevBal) < SAME_EPS && gap < SAMPLE_MS) return 0;
            }

            if (!Double.isNaN(prevBal)) {
                double delta = balance - prevBal;

                if (delta > 0.02) {
                    // 余额涨了 → 充过值
                    double tier = matchTier(delta, usd);
                    double amount = tier > 0 ? tier : delta;
                    ContentValues cv = new ContentValues();
                    cv.put("key_id", keyId);
                    cv.put("ts", now);
                    cv.put("delta", delta);
                    if (tier > 0) cv.put("matched", tier);
                    cv.put("amount", amount);
                    db.insert(T_RECH, null, cv);

                    // 差额是在这段时间里花掉的，不能凭空消失
                    consumed = Math.max(0, amount - delta);
                    BalanceFetcher.diag(c, "检测到充值 " + keyId
                            + " 净增 " + fmt(delta)
                            + (tier > 0 ? (" → 匹配档位 " + fmt(tier)) : " → 未匹配档位")
                            + "，期间消费 " + fmt(consumed));
                } else {
                    consumed = Math.max(0, -delta);
                }
            }

            ContentValues sv = new ContentValues();
            sv.put("key_id", keyId);
            sv.put("ts", now);
            sv.put("balance", balance);
            if (toppedUp >= 0) sv.put("topped_up", toppedUp);
            db.insert(T_SNAP, null, sv);
        } catch (Throwable t) {
            BalanceFetcher.diag(c, "账本写入失败: " + t);
        }
        return consumed;
    }

    // ---------- 查询 ----------

    public static class Point {
        public long ts;
        public double balance;
        public double consumed;     // 相对上一点的消费（首点 = 0）
        public double charged;      // 相对上一点的充值（首点 = 0）

        public Point(long ts, double balance) {
            this.ts = ts;
            this.balance = balance;
        }
    }

    /** 取某个 Key 的时间序列（升序）。充值额会从充值表里补进来 */
    public List<Point> series(Context c, String keyId, long fromTs) {
        return series(c, new String[]{keyId}, fromTs);
    }

    /**
     * 取**多个** Key 合并后的时间序列（升序）。
     *
     * 为什么要合并：早期版本有一处对象别名缺陷，把快照记到了**平台 id** 上
     * （而不是 Key id）。修好之后新数据按 Key id 落库，老数据却还留在平台 id 下 ——
     * 只查 Key id 的话，那批历史会凭空"消失"（用户看到的"MiMo 少了两个点"）。
     * 把两个来源一起查、按时间戳去重，历史与新增才能接得上。
     */
    public List<Point> series(Context c, String[] keyIds, long fromTs) {
        List<Point> out = new ArrayList<Point>();
        if (keyIds == null || keyIds.length == 0) return out;
        try {
            StringBuilder ph = new StringBuilder();
            String[] args = new String[keyIds.length + 1];
            for (int i = 0; i < keyIds.length; i++) {
                if (i > 0) ph.append(',');
                ph.append('?');
                args[i] = keyIds[i];
            }
            args[keyIds.length] = String.valueOf(fromTs);

            SQLiteDatabase db = getReadableDatabase();
            Cursor cur = db.rawQuery("SELECT ts, balance FROM " + T_SNAP
                    + " WHERE key_id IN (" + ph + ") AND ts>=? ORDER BY ts ASC", args);
            long lastTs = Long.MIN_VALUE;
            while (cur.moveToNext()) {
                long ts = cur.getLong(0);
                /* 同一时刻至多留一条：两个来源若有重叠，去重免得图上出现"双点" */
                if (ts == lastTs) continue;
                lastTs = ts;
                out.add(new Point(ts, cur.getDouble(1)));
            }
            cur.close();

            /* 充值记录**一次性**读进内存再按时间归并。
               原实现是「每个数据点查一次充值表」—— 30 天范围 120 个点、
               每个平台 120 次查询，8 个平台就近千次 SQLite 查询，全在主线程，
               统计页一打开就卡。归并写法把查询压到 1 次。 */
            List<Recharge> rs = new ArrayList<Recharge>();
            for (int i = 0; i < keyIds.length; i++) rs.addAll(rechargeListAsc(c, keyIds[i], fromTs));
            if (rs.size() > 1) {
                java.util.Collections.sort(rs, new java.util.Comparator<Recharge>() {
                    public int compare(Recharge a, Recharge b) {
                        return a.ts < b.ts ? -1 : (a.ts > b.ts ? 1 : 0);
                    }
                });
            }
            int ri = 0;
            for (int i = 1; i < out.size(); i++) {
                Point q = out.get(i - 1), p = out.get(i);
                double charged = 0;
                while (ri < rs.size() && rs.get(ri).ts <= p.ts) {
                    if (rs.get(ri).ts > q.ts) charged += rs.get(ri).amount;
                    ri++;                       // 每条充值只被消费一次
                }
                p.charged = charged;
                p.consumed = Math.max(0, q.balance + charged - p.balance);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /**
     * 清理"缓存回跳"造成的假点。
     *
     * 背景：并发闸门在已有抓取时会复用缓存，早期版本把它当新数据记了账 →
     * 曲线末尾凭空出现一个回跳到旧值的点（实测 DeepSeek 被写成 14.52，
     * 而真实余额是 24.11）。**危害不止于曲线难看**：下一个采样点会以这个
     * 假值为基准算差值，23.9 会被误判成"又充值了 9.38 元"。
     *
     * 判定（保守，只在很明确的形态下触发）：取最近三个点 P1(最新)/P2/P3，
     *   ① P1 与 P3 余额几乎相同（回跳到旧值）
     *   ② P2 与它们差异明显（中间确实有过变化）
     *   ③ P1 与 P3 相隔不超过 1 小时
     * 满足则删掉 P1 —— 真实业务里"一小时内先变化又精确回到原值"几乎不可能。
     */
    public int dropCacheArtifacts(Context c, String keyId) {
        int removed = 0;
        try {
            SQLiteDatabase db = getWritableDatabase();
            Cursor cur = db.rawQuery("SELECT id, ts, balance FROM " + T_SNAP
                    + " WHERE key_id=? ORDER BY ts DESC LIMIT 3", new String[]{keyId});
            long[] ids = new long[3];
            long[] ts = new long[3];
            double[] bal = new double[3];
            int n = 0;
            while (cur.moveToNext() && n < 3) {
                ids[n] = cur.getLong(0);
                ts[n] = cur.getLong(1);
                bal[n] = cur.getDouble(2);
                n++;
            }
            cur.close();
            if (n == 3
                    && Math.abs(bal[0] - bal[2]) < 0.01              // ① 回到旧值
                    && Math.abs(bal[1] - bal[2]) > 0.5               // ② 中间确有变化
                    && (ts[0] - ts[2]) < 60L * 60 * 1000) {          // ③ 一小时内
                removed = db.delete(T_SNAP, "id=?", new String[]{String.valueOf(ids[0])});
                if (removed > 0) {
                    BalanceFetcher.diag(c, "清理缓存假点 " + keyId + " "
                            + fmt(bal[0]) + " (回跳自 " + fmt(bal[2]) + "，中间 "
                            + fmt(bal[1]) + ")");
                }
            }
        } catch (Throwable ignored) { }
        return removed;
    }

    /** 区间内充值记录，按时间**升序**（供 series 归并） */
    private List<Recharge> rechargeListAsc(Context c, String keyId, long fromTs) {
        List<Recharge> out = new ArrayList<Recharge>();
        try {
            Cursor cur = getReadableDatabase().rawQuery("SELECT ts, delta, matched, amount FROM "
                    + T_RECH + " WHERE key_id=? AND ts>=? ORDER BY ts ASC",
                    new String[]{keyId, String.valueOf(fromTs)});
            while (cur.moveToNext()) {
                Recharge r = new Recharge();
                r.ts = cur.getLong(0);
                r.delta = cur.getDouble(1);
                r.matched = cur.isNull(2) ? -1 : cur.getDouble(2);
                r.amount = cur.getDouble(3);
                out.add(r);
            }
            cur.close();
        } catch (Throwable ignored) { }
        return out;
    }

    /** 手动修正充值：插入一条手动充值记录（防止自动匹配档位错误） */
    public void manualRecharge(Context c, String keyId, double amount) {
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put("key_id", keyId);
            cv.put("ts", System.currentTimeMillis());
            cv.put("delta", amount);
            cv.put("matched", amount);
            cv.put("amount", amount);
            getWritableDatabase().insert(T_RECH, null, cv);
            BalanceFetcher.diag(c, "手动修正充值 " + keyId + " +" + amount);
        } catch (Throwable ignored) { }
    }

    public static class Recharge {
        public long ts;
        public double delta, matched, amount;
    }

    public List<Recharge> recharges(Context c, String keyId, long fromTs) {
        List<Recharge> out = new ArrayList<Recharge>();
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor cur = db.rawQuery("SELECT ts, delta, matched, amount FROM " + T_RECH
                    + " WHERE key_id=? AND ts>=? ORDER BY ts DESC",
                    new String[]{keyId, String.valueOf(fromTs)});
            while (cur.moveToNext()) {
                Recharge r = new Recharge();
                r.ts = cur.getLong(0);
                r.delta = cur.getDouble(1);
                r.matched = cur.isNull(2) ? -1 : cur.getDouble(2);
                r.amount = cur.getDouble(3);
                out.add(r);
            }
            cur.close();
        } catch (Throwable ignored) { }
        return out;
    }

    private double rechargedBetween(Context c, String keyId, long from, long to) {
        double sum = 0;
        try {
            Cursor cur = getReadableDatabase().rawQuery(
                    "SELECT IFNULL(SUM(amount),0) FROM " + T_RECH
                    + " WHERE key_id=? AND ts>? AND ts<=?",
                    new String[]{keyId, String.valueOf(from), String.valueOf(to)});
            if (cur.moveToFirst()) sum = cur.getDouble(0);
            cur.close();
        } catch (Throwable ignored) { }
        return sum;
    }

    /**
     * 列出账本里所有 key_id 及各自的快照条数（诊断用）。
     *
     * 排查"某个平台少点/没线"时最有用的一招：一眼看出数据到底记在哪个 id 下 ——
     * 是按 Key id 记的、还是历史遗留按平台 id 记的。
     */
    public String dumpKeys(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            Cursor cur = getReadableDatabase().rawQuery(
                    "SELECT key_id, COUNT(*), MIN(ts), MAX(ts) FROM " + T_SNAP
                    + " GROUP BY key_id ORDER BY COUNT(*) DESC", null);
            while (cur.moveToNext()) {
                sb.append(cur.getString(0)).append('=').append(cur.getInt(1));
                sb.append('[').append(fmtTs(cur.getLong(2))).append('~')
                  .append(fmtTs(cur.getLong(3))).append("] ");
            }
            cur.close();
        } catch (Throwable t) {
            return "dump 失败 " + t;
        }
        return sb.length() == 0 ? "(账本为空)" : sb.toString();
    }

    /**
     * 某组 key 在范围内的快照**明细**（诊断用）：时间 + 余额，按时间升序。
     *
     * 排查"某个值没画到图上"时最有用的一招：一眼看清那个值到底有没有进账本 ——
     *   账本里有、图上没有  → 是分桶聚合把它吃掉了（同桶内被后来的值覆盖）
     *   账本里也没有        → 是采样没覆盖到（两次刷新之间来不及记录）
     * 两种情况对用户的意义完全不同，别凭猜。
     */
    public String dumpPoints(Context c, String[] keyIds, long fromTs, int max) {
        StringBuilder sb = new StringBuilder();
        if (keyIds == null || keyIds.length == 0) return "";
        try {
            StringBuilder ph = new StringBuilder();
            String[] args = new String[keyIds.length + 1];
            for (int i = 0; i < keyIds.length; i++) {
                if (i > 0) ph.append(',');
                ph.append('?');
                args[i] = keyIds[i];
            }
            args[keyIds.length] = String.valueOf(fromTs);
            Cursor cur = getReadableDatabase().rawQuery(
                    "SELECT key_id, ts, balance FROM " + T_SNAP + " WHERE key_id IN (" + ph
                    + ") AND ts>=? ORDER BY ts ASC LIMIT " + Math.max(1, max), args);
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                    "MM-dd HH:mm", java.util.Locale.US);
            while (cur.moveToNext()) {
                /* 带上 key_id：多 Key 平台（同平台开了两个账号）合并看会像"跳来跳去"，
                   标出来才分得清是"余额真的回跳"还是"两个账号各记各的" */
                sb.append(cur.getString(0)).append('@')
                  .append(f.format(new java.util.Date(cur.getLong(1)))).append('=')
                  .append(String.format("%.2f", cur.getDouble(2))).append(' ');
            }
            cur.close();
        } catch (Throwable t) {
            return "dump 失败 " + t;
        }
        return sb.length() == 0 ? "(无)" : sb.toString();
    }

    private static String fmtTs(long ts) {
        if (ts <= 0) return "-";
        return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                .format(new java.util.Date(ts));
    }

    /** 记录里最早的快照时间（用于"全部"范围） */
    public long earliest(Context c) {
        try {
            Cursor cur = getReadableDatabase().rawQuery(
                    "SELECT IFNULL(MIN(ts),0) FROM " + T_SNAP, null);
            if (cur.moveToFirst()) return cur.getLong(0);
            cur.close();
        } catch (Throwable ignored) { }
        return 0;
    }

    /** 数据总条数（设置页显示占用用） */
    public int count(Context c) {
        int n = 0;
        try {
            Cursor cur = getReadableDatabase().rawQuery(
                    "SELECT (SELECT COUNT(*) FROM " + T_SNAP + ")"
                    + " + (SELECT COUNT(*) FROM " + T_RECH + ")", null);
            if (cur.moveToFirst()) n = cur.getInt(0);
            cur.close();
        } catch (Throwable ignored) { }
        return n;
    }

    // ---------- 备份导出 / 导入 ----------

    /** 全部快照，形如 [[key_id, ts, balance, topped_up], ...]（备份用） */
    public org.json.JSONArray exportSnapshots() {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            Cursor cur = getReadableDatabase().rawQuery(
                    "SELECT key_id, ts, balance, topped_up FROM " + T_SNAP
                    + " ORDER BY ts ASC", null);
            while (cur.moveToNext()) {
                org.json.JSONArray r = new org.json.JSONArray();
                r.put(cur.getString(0));
                r.put(cur.getLong(1));
                r.put(cur.getDouble(2));
                r.put(cur.isNull(3) ? -1 : cur.getDouble(3));
                a.put(r);
            }
            cur.close();
        } catch (Throwable ignored) { }
        return a;
    }

    /** 全部充值记录（备份用） */
    public org.json.JSONArray exportRecharges() {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            Cursor cur = getReadableDatabase().rawQuery(
                    "SELECT key_id, ts, delta, matched, amount FROM " + T_RECH
                    + " ORDER BY ts ASC", null);
            while (cur.moveToNext()) {
                org.json.JSONArray r = new org.json.JSONArray();
                r.put(cur.getString(0));
                r.put(cur.getLong(1));
                r.put(cur.getDouble(2));
                r.put(cur.isNull(3) ? -1 : cur.getDouble(3));
                r.put(cur.getDouble(4));
                a.put(r);
            }
            cur.close();
        } catch (Throwable ignored) { }
        return a;
    }

    /**
     * 用备份内容**整体替换**账本（先清空再写）。
     * 放在一个事务里，中途失败不会留下半截数据。
     */
    public int importAll(org.json.JSONArray snaps, org.json.JSONArray rechs) {
        int n = 0;
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete(T_SNAP, null, null);
                db.delete(T_RECH, null, null);
                if (snaps != null) {
                    for (int i = 0; i < snaps.length(); i++) {
                        org.json.JSONArray r = snaps.optJSONArray(i);
                        if (r == null || r.length() < 3) continue;
                        ContentValues cv = new ContentValues();
                        cv.put("key_id", r.optString(0));
                        cv.put("ts", r.optLong(1));
                        cv.put("balance", r.optDouble(2));
                        double t = r.optDouble(3, -1);
                        if (t >= 0) cv.put("topped_up", t);
                        db.insert(T_SNAP, null, cv);
                        n++;
                    }
                }
                if (rechs != null) {
                    for (int i = 0; i < rechs.length(); i++) {
                        org.json.JSONArray r = rechs.optJSONArray(i);
                        if (r == null || r.length() < 5) continue;
                        ContentValues cv = new ContentValues();
                        cv.put("key_id", r.optString(0));
                        cv.put("ts", r.optLong(1));
                        cv.put("delta", r.optDouble(2));
                        double m = r.optDouble(3, -1);
                        if (m >= 0) cv.put("matched", m);
                        cv.put("amount", r.optDouble(4));
                        db.insert(T_RECH, null, cv);
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Throwable ignored) { }
        return n;
    }

    // ---------- 清理 ----------

    /** 只留最近 N 天（设置里可调） */
    public int prune(Context c, int keepDays) {
        try {
            long cut = System.currentTimeMillis() - (long) keepDays * 24 * 3600 * 1000L;
            SQLiteDatabase db = getWritableDatabase();
            int a = db.delete(T_SNAP, "ts<?", new String[]{String.valueOf(cut)});
            int b = db.delete(T_RECH, "ts<?", new String[]{String.valueOf(cut)});
            return a + b;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 手动删除某个时间段（数据管理界面用：按月 / 按年批量） */
    public int deleteRange(Context c, long fromTs, long toTs) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            String[] args = {String.valueOf(fromTs), String.valueOf(toTs)};
            int a = db.delete(T_SNAP, "ts>=? AND ts<?", args);
            int b = db.delete(T_RECH, "ts>=? AND ts<?", args);
            return a + b;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 清空全部 */
    public void clearAll(Context c) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(T_SNAP, null, null);
            db.delete(T_RECH, null, null);
        } catch (Throwable ignored) { }
    }

    // ---------- 充值档位 ----------

    /**
     * 常见充值档位。**这份表可能过期**，各家也会时不时调整。
     * 所以匹配不上时不硬凑 —— 直接按实际净增记账，宁可少标一个"约"字，
     * 也不能把金额记错。
     */
    private static final double[] TIERS_CNY = {
            10, 20, 30, 50, 100, 200, 300, 500, 1000, 2000, 5000, 10000
    };
    private static final double[] TIERS_USD = {
            5, 10, 20, 30, 50, 100, 200, 500, 1000
    };

    /** 匹配档位：优先取 ≥ 净增的最小档；都比净增小就取最接近的 */
    static double matchTier(double delta, boolean usd) {
        double[] tiers = usd ? TIERS_USD : TIERS_CNY;
        double up = -1;
        for (int i = 0; i < tiers.length; i++) {
            if (tiers[i] >= delta - 0.02) {
                if (up < 0 || tiers[i] < up) up = tiers[i];
            }
        }
        if (up > 0) return up;

        // 全部档位都小于净增 → 可能一次充了多笔，取最接近的
        double near = tiers[0];
        for (int i = 0; i < tiers.length; i++) {
            if (Math.abs(tiers[i] - delta) < Math.abs(near - delta)) near = tiers[i];
        }
        return near;
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
