package com.minis.balancewidget;

import android.appwidget.AppWidgetManager;

/**
 * 小组件尺寸计算 —— 只管「能放几个」，不管「长什么样」。
 *
 * 外观已经统一成一套（扁平 + 柔阴影），深浅色由资源目录区分：
 *     res/drawable-nodpi/         浅色
 *     res/drawable-night-nodpi/   深色
 * 所以代码里不再需要判断风格 —— 早期那三套皮肤（玻璃 / 新拟物 / 扁平）连同
 * 对应的 6 张 9-patch 已经删掉了，当时是为了给用户留切换选项，
 * 后来发现风格混用反而更乱，不如一套做干净。
 *
 * ⚠️ 下面这些数字是**真机实测标定**的，别随手改：
 *   - 高度模型是从 vivo 桌面的实际显示反推的，改了会影响一页能放几行
 *   - 有的桌面上报的是像素而非 dp，数值会离谱地大（vivo 就是），超过 400 要按密度换算
 */
public final class WidgetStyle {

    /** 高度模型：面板高 = 21(内边距) + 100(标题+总额+副行) + N*12.6 + (N-1)*6 */
    private static final float PAD_DP = 21f;
    private static final float HEAD_DP = 100f;
    private static final float ROW_DP = 12.6f;
    private static final float GAP_DP = 6f;

    /** 单列 / 双列的分界宽度：最长项加列距约 150dp，取 260dp 留余量 */
    private static final int TWO_COL_MIN_WIDTH_DP = 260;

    private WidgetStyle() { }

    /** 按实际宽度决定列数，窄于门槛就退化成单列 */
    public static int colsFor(AppWidgetManager mgr, int id) {
        try {
            int wDp = mgr.getAppWidgetOptions(id)
                        .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
            if (wDp <= 0) return 2;
            return wDp >= TWO_COL_MIN_WIDTH_DP ? 2 : 1;
        } catch (Throwable t) {
            return 2;
        }
    }

    public static float densityOf(AppWidgetManager mgr) {
        try {
            return android.content.res.Resources.getSystem().getDisplayMetrics().density;
        } catch (Throwable t) {
            return 3.5f;
        }
    }

    /** 按实际高度算出这一页能放几个槽位（最少 1，最多 4 行 × 列数） */
    public static int capacityFor(AppWidgetManager mgr, int id) {
        try {
            int raw = mgr.getAppWidgetOptions(id)
                        .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT);
            if (raw <= 0) return 4;                 // 拿不到就按 2 行保守处理
            float hDp = raw > 400 ? raw / densityOf(mgr) : raw;
            float avail = hDp - PAD_DP - HEAD_DP;
            int rows = (int) Math.floor((avail + GAP_DP) / (ROW_DP + GAP_DP));
            rows = Math.max(1, Math.min(4, rows));
            return rows * colsFor(mgr, id);
        } catch (Throwable t) {
            return 4;
        }
    }
}
