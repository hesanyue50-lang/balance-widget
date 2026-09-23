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

    /** 采样间隔：6 小时（用户要求） */
    public static final long SAMPLE_MS = 6L * 60 * 60 * 1000;

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
        List<Point> out = new ArrayList<Point>();
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor cur = db.rawQuery("SELECT ts, balance FROM " + T_SNAP
                    + " WHERE key_id=? AND ts>=? ORDER BY ts ASC",
                    new String[]{keyId, String.valueOf(fromTs)});
            while (cur.moveToNext()) {
                out.add(new Point(cur.getLong(0), cur.getDouble(1)));
            }
            cur.close();

            // 补充值与消费
            for (int i = 1; i < out.size(); i++) {
                Point p = out.get(i), q = out.get(i - 1);
                double charged = rechargedBetween(c, keyId, q.ts, p.ts);
                p.charged = charged;
                p.consumed = Math.max(0, q.balance + charged - p.balance);
            }
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
