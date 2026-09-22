package com.minis.balancewidget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/**
 * 用量统计图表（纯代码绘制 + 渐变/圆角等素材效果，无第三方库）。
 * 多折线（每 API 一条 + 总计）+ 可选消耗柱 + 渐变面积填充 + 数据点 + Y 轴刻度。
 */
public class UsageChartView extends View {

    public static class Series {
        public int color;
        public double[] vals;
        public String label;
        public boolean fill;      // 是否画渐变面积填充（总计线用）
        public Series(int color, double[] vals, String label, boolean fill) {
            this.color = color; this.vals = vals; this.label = label; this.fill = fill;
        }
    }

    private List<Series> series;
    private double[] bars;
    private String[] labels;
    private boolean showBars = true, showGrid = true, showLegend = true;
    private double maxLine = 1, maxBar = 1;

    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDotIn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    public UsageChartView(Context c) { super(c); init(); }
    public UsageChartView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        pBar.setStyle(Paint.Style.FILL);
        pFill.setStyle(Paint.Style.FILL);
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(4f);
        pLine.setStrokeCap(Paint.Cap.ROUND);
        pLine.setStrokeJoin(Paint.Join.ROUND);
        pDot.setStyle(Paint.Style.FILL);
        pDotIn.setStyle(Paint.Style.FILL);
        pGrid.setStyle(Paint.Style.STROKE);
        pGrid.setStrokeWidth(1f);
        pGrid.setPathEffect(new DashPathEffect(new float[]{6, 6}, 0));
        pText.setTextSize(18f);
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
        float padL = 46, padR = 12, padT = showLegend ? 34 : 14, padB = 40;
        float cw = w - padL - padR, ch = h - padT - padB;
        if (cw <= 0 || ch <= 0) return;
        int n = labels != null ? labels.length : 0;
        if (n == 0) return;

        // 虚线网格 + Y 轴刻度
        pGrid.setColor(0x2A888888);
        pText.setColor(0xFF8A93A0);
        for (int g = 0; g <= 4; g++) {
            float y = padT + ch * g / 4f;
            if (showGrid) cv.drawLine(padL, y, w - padR, y, pGrid);
            double val = maxLine * (4 - g) / 4f;
            cv.drawText(fmt(val), 4, y + 6, pText);
        }

        float slot = cw / n;

        // 消耗柱（半透明圆角感）
        if (showBars && bars != null) {
            float barW = Math.max(3f, slot * 0.45f);
            pBar.setColor(0x404D6BFE);
            for (int i = 0; i < n && i < bars.length; i++) {
                float bh = (float) (bars[i] / maxBar * ch);
                if (bh < 1) continue;
                float x = padL + slot * i + (slot - barW) / 2f;
                cv.drawRoundRect(x, padT + ch - bh, x + barW, padT + ch, 3, 3, pBar);
            }
        }

        // 折线 + 渐变面积 + 数据点
        if (series != null) {
            for (Series s : series) {
                if (s.vals == null) continue;
                // 面积填充（仅 fill=true 的线，如总计）
                if (s.fill) {
                    fillPath.rewind();
                    boolean st = false;
                    float firstX = 0, lastX = 0;
                    for (int i = 0; i < n && i < s.vals.length; i++) {
                        float x = padL + slot * i + slot / 2f;
                        float y = padT + ch - (float) (s.vals[i] / maxLine * ch);
                        if (!st) { fillPath.moveTo(x, y); firstX = x; st = true; }
                        else fillPath.lineTo(x, y);
                        lastX = x;
                    }
                    fillPath.lineTo(lastX, padT + ch);
                    fillPath.lineTo(firstX, padT + ch);
                    fillPath.close();
                    pFill.setShader(new LinearGradient(0, padT, 0, padT + ch,
                            (s.color & 0x00FFFFFF) | 0x55000000,
                            (s.color & 0x00FFFFFF) | 0x00000000,
                            Shader.TileMode.CLAMP));
                    cv.drawPath(fillPath, pFill);
                    pFill.setShader(null);
                }
                // 折线
                pLine.setColor(s.color);
                linePath.rewind();
                boolean st2 = false;
                for (int i = 0; i < n && i < s.vals.length; i++) {
                    float x = padL + slot * i + slot / 2f;
                    float y = padT + ch - (float) (s.vals[i] / maxLine * ch);
                    if (!st2) { linePath.moveTo(x, y); st2 = true; }
                    else linePath.lineTo(x, y);
                }
                cv.drawPath(linePath, pLine);
                // 数据点（外圈色 + 内圈白）
                pDot.setColor(s.color);
                pDotIn.setColor(0xFFFFFFFF);
                for (int i = 0; i < n && i < s.vals.length; i++) {
                    float x = padL + slot * i + slot / 2f;
                    float y = padT + ch - (float) (s.vals[i] / maxLine * ch);
                    cv.drawCircle(x, y, 5f, pDot);
                    cv.drawCircle(x, y, 2.2f, pDotIn);
                }
            }
        }

        // X 轴标签（居中于刻度）
        pText.setColor(0xFF8A93A0);
        int step = Math.max(1, n / 5);
        for (int i = 0; i < n; i += step) {
            float x = padL + slot * i + slot / 2f;
            cv.drawText(labels[i], x - pText.measureText(labels[i]) / 2, h - 12, pText);
        }

        // 图例：圆点 + 名称
        if (showLegend && series != null) {
            float lx = padL;
            for (Series s : series) {
                pDot.setColor(s.color);
                cv.drawCircle(lx + 6, 16, 6, pDot);
                pText.setColor(0xFFB9C1CC);
                cv.drawText(s.label, lx + 18, 22, pText);
                lx += 18 + pText.measureText(s.label) + 26;
                if (lx > w - 60) break;
            }
        }
    }

    private String fmt(double v) {
        if (v >= 1000) return String.format("%.1fk", v / 1000);
        if (v >= 100) return String.format("%.0f", v);
        return String.format("%.1f", v);
    }
}
