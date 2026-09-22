package com.minis.balancewidget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/**
 * 用量统计图表（纯代码绘制，无第三方库）。
 * 支持多条折线（每个 API 一条 + 总计一条）+ 可选消耗柱状 + 网格/图例开关。
 */
public class UsageChartView extends View {

    /** 一条折线序列 */
    public static class Series {
        public int color;
        public double[] vals;
        public String label;
        public Series(int color, double[] vals, String label) {
            this.color = color; this.vals = vals; this.label = label;
        }
    }

    private List<Series> series;     // 折线（各 API 余额 + 总计）
    private double[] bars;           // 消耗柱状（可 null）
    private String[] labels;         // X 轴日期
    private boolean showBars = true;
    private boolean showGrid = true;
    private boolean showLegend = true;
    private double maxLine = 1;
    private double maxBar = 1;

    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public UsageChartView(Context c) { super(c); init(); }
    public UsageChartView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        pBar.setStyle(Paint.Style.FILL);
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(3f);
        pAxis.setStyle(Paint.Style.STROKE);
        pAxis.setStrokeWidth(1f);
        pAxis.setColor(0x33888888);
        pText.setTextSize(19f);
        pText.setColor(0xFF9AA3AF);
    }

    public void setData(List<Series> series, double[] bars, String[] labels,
                        boolean showBars, boolean showGrid, boolean showLegend) {
        this.series = series; this.bars = bars; this.labels = labels;
        this.showBars = showBars; this.showGrid = showGrid; this.showLegend = showLegend;
        maxLine = 1; maxBar = 1;
        if (series != null) for (Series s : series)
            if (s.vals != null) for (double v : s.vals) if (v > maxLine) maxLine = v;
        if (bars != null) for (double v : bars) if (v > maxBar) maxBar = v;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();
        float padL = 8, padR = 8, padT = showLegend ? 30 : 12, padB = 38;
        float cw = w - padL - padR, ch = h - padT - padB;
        if (cw <= 0 || ch <= 0) return;
        int n = labels != null ? labels.length : 0;
        if (n == 0) return;

        if (showGrid) for (int g = 0; g <= 4; g++) {
            float y = padT + ch * g / 4f;
            cv.drawLine(padL, y, w - padR, y, pAxis);
        }

        float slot = cw / n;

        // 消耗柱状
        if (showBars && bars != null) {
            float barW = Math.max(2f, slot * 0.5f);
            pBar.setColor(0x554D6BFE);
            for (int i = 0; i < n && i < bars.length; i++) {
                float bh = (float) (bars[i] / maxBar * ch);
                float x = padL + slot * i + (slot - barW) / 2f;
                cv.drawRect(x, padT + ch - bh, x + barW, padT + ch, pBar);
            }
        }

        // 各 API 折线 + 总计
        if (series != null) {
            for (Series s : series) {
                if (s.vals == null) continue;
                pLine.setColor(s.color);
                path.rewind();
                boolean started = false;
                for (int i = 0; i < n && i < s.vals.length; i++) {
                    float x = padL + slot * i + slot / 2f;
                    float y = padT + ch - (float) (s.vals[i] / maxLine * ch);
                    if (!started) { path.moveTo(x, y); started = true; }
                    else path.lineTo(x, y);
                }
                cv.drawPath(path, pLine);
            }
        }

        // X 轴标签（稀疏）
        int step = Math.max(1, n / 5);
        for (int i = 0; i < n; i += step) {
            cv.drawText(labels[i], padL + slot * i, h - 10, pText);
        }

        // 图例（横排，色块+名）
        if (showLegend && series != null) {
            float lx = padL;
            for (Series s : series) {
                pText.setColor(s.color);
                String t = "— " + s.label;
                cv.drawText(t, lx, 18, pText);
                lx += pText.measureText(t) + 24;
                if (lx > w - 80) break;
            }
        }
    }
}
