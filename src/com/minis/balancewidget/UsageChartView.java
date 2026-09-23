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
 * 用量统计图表 —— 手机查看优化版：
 * 信息密度低（稀疏标签/网格/数据点）、字号大、标注明显（Y 刻度+峰值数值）。
 * 多折线（每 API 一条 + 总计）+ 可选消耗柱 + 渐变面积填充。
 */
public class UsageChartView extends View {

    public static class Series {
        public int color;
        public double[] vals;
        public String label;
        public boolean fill;
        public Series(int color, double[] vals, String label, boolean fill) {
            this.color = color; this.vals = vals; this.label = label; this.fill = fill;
        }
    }

    /** 点击图表某天位置的回调 */
    public interface OnTapDay { void onTap(int dayIndex); }
    private OnTapDay tapListener;
    public void setOnTapDay(OnTapDay l) { tapListener = l; }
    private float mPadL, mCw; private int mN;

    @Override
    public boolean onTouchEvent(android.view.MotionEvent ev) {
        if (ev.getAction() == android.view.MotionEvent.ACTION_UP
                && tapListener != null && mN > 0 && mCw > 0) {
            float slot = mCw / mN;
            int idx = (int) ((ev.getX() - mPadL) / slot);
            if (idx >= 0 && idx < mN) { tapListener.onTap(idx); return true; }
        }
        return super.onTouchEvent(ev);
    }

    private List<Series> series;
    private double[] bars;
    private String[] labels;
    private boolean showBars = true, showGrid = true, showLegend = true;
    private double maxLine = 1, maxBar = 1;
    private float progress = 1f;   // 渐进揭示进度 0→1

    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDotIn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAxis = new Paint(Paint.ANTI_ALIAS_FLAG);   // Y/X 刻度（大）
    private final Paint pMark = new Paint(Paint.ANTI_ALIAS_FLAG);   // 峰值标注（最大最亮）
    private final Paint pLegend = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    public UsageChartView(Context c) { super(c); init(); }
    public UsageChartView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        pBar.setStyle(Paint.Style.FILL);
        pFill.setStyle(Paint.Style.FILL);
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(5f);
        pLine.setStrokeCap(Paint.Cap.ROUND);
        pLine.setStrokeJoin(Paint.Join.ROUND);
        pDot.setStyle(Paint.Style.FILL);
        pDotIn.setStyle(Paint.Style.FILL);
        pGrid.setStyle(Paint.Style.STROKE);
        pGrid.setStrokeWidth(1.5f);
        pGrid.setPathEffect(new DashPathEffect(new float[]{8, 8}, 0));
        // 手机可读字号：刻度 22、图例 26、峰值标注 24 加粗
        pAxis.setTextSize(22f);
        pAxis.setColor(0xFF9AA3B0);
        pMark.setTextSize(24f);
        pMark.setFakeBoldText(true);
        pLegend.setTextSize(26f);
        pLegend.setFakeBoldText(true);
    }

    public void setData(List<Series> series, double[] bars, String[] labels,
                        boolean showBars, boolean showGrid, boolean showLegend) {
        this.series = series; this.bars = bars; this.labels = labels;
        this.showBars = showBars; this.showGrid = showGrid; this.showLegend = showLegend;
        maxLine = 1; maxBar = 1;
        if (series != null) for (Series s : series)
            if (s.vals != null) for (double v : s.vals) if (v > maxLine) maxLine = v;
        if (bars != null) for (double v : bars) if (v > maxBar) maxBar = v;
        // 渐进揭示动画：从左到右画出数据层
        progress = 0f;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(650);
        va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                progress = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        va.start();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();
        float padL = 70, padR = 16, padT = showLegend ? 46 : 20, padB = 46;
        float cw = w - padL - padR, ch = h - padT - padB;
        if (cw <= 0 || ch <= 0) return;
        int n = labels != null ? labels.length : 0;
        if (n == 0) return;
        mPadL = padL; mCw = cw; mN = n;   // 供 onTouchEvent 换算点击的日期下标

        // 稀疏网格（3 条）+ 大 Y 刻度（max / mid / 0）
        pGrid.setColor(0x2E8A94A3);
        for (int g = 0; g <= 2; g++) {
            float y = padT + ch * g / 2f;
            if (showGrid) cv.drawLine(padL, y, w - padR, y, pGrid);
            double val = maxLine * (2 - g) / 2f;
            cv.drawText(fmt(val), 6, y + 8, pAxis);
        }

        float slot = cw / n;

        // 数据层（柱+折线）按 progress 从左到右揭示
        cv.save();
        cv.clipRect(0, 0, padL + cw * progress + slot, h);

        // 消耗柱（中性石板色，宽而淡，不抢折线）
        if (showBars && bars != null) {
            float barW = Math.max(4f, slot * 0.5f);
            pBar.setColor(0x308A94A3);
            for (int i = 0; i < n && i < bars.length; i++) {
                float bh = (float) (bars[i] / maxBar * ch);
                if (bh < 1) continue;
                float x = padL + slot * i + (slot - barW) / 2f;
                cv.drawRoundRect(x, padT + ch - bh, x + barW, padT + ch, 4, 4, pBar);
            }
        }

        // 折线 + 渐变面积 + 稀疏数据点 + 峰值标注
        if (series != null) {
            for (Series s : series) {
                if (s.vals == null) continue;
                if (s.fill) {
                    fillPath.rewind();
                    boolean st = false;
                    float fx = 0, lx2 = 0;
                    for (int i = 0; i < n && i < s.vals.length; i++) {
                        float x = padL + slot * i + slot / 2f;
                        float y = padT + ch - (float) (s.vals[i] / maxLine * ch);
                        if (!st) { fillPath.moveTo(x, y); fx = x; st = true; }
                        else fillPath.lineTo(x, y);
                        lx2 = x;
                    }
                    fillPath.lineTo(lx2, padT + ch);
                    fillPath.lineTo(fx, padT + ch);
                    fillPath.close();
                    pFill.setShader(new LinearGradient(0, padT, 0, padT + ch,
                            (s.color & 0x00FFFFFF) | 0x4D000000,
                            (s.color & 0x00FFFFFF) | 0x00000000,
                            Shader.TileMode.CLAMP));
                    cv.drawPath(fillPath, pFill);
                    pFill.setShader(null);
                }
                pLine.setColor(s.color);
                linePath.rewind();
                boolean st2 = false;
                int peakI = 0;
                double peakV = -1;
                for (int i = 0; i < n && i < s.vals.length; i++) {
                    float x = padL + slot * i + slot / 2f;
                    float y = padT + ch - (float) (s.vals[i] / maxLine * ch);
                    if (!st2) { linePath.moveTo(x, y); st2 = true; }
                    else linePath.lineTo(x, y);
                    if (s.vals[i] > peakV) { peakV = s.vals[i]; peakI = i; }
                }
                cv.drawPath(linePath, pLine);

                // 稀疏数据点：点少全画，点多只画首/峰/末
                pDot.setColor(s.color);
                pDotIn.setColor(0xFFFFFFFF);
                int stepDot = (n > 12) ? n : 1;   // 点多时只画关键三点
                for (int i = 0; i < n && i < s.vals.length; i++) {
                    boolean key = (n <= 12) || (i == 0 || i == peakI || i == n - 1);
                    if (!key) continue;
                    float x = padL + slot * i + slot / 2f;
                    float y = padT + ch - (float) (s.vals[i] / maxLine * ch);
                    cv.drawCircle(x, y, 7f, pDot);
                    cv.drawCircle(x, y, 3f, pDotIn);
                }
                // 峰值数值标注（明显）
                if (peakV > 0) {
                    float px = padL + slot * peakI + slot / 2f;
                    float py = padT + ch - (float) (peakV / maxLine * ch);
                    pMark.setColor(s.color);
                    String mk = fmt(peakV);
                    float tw = pMark.measureText(mk);
                    float mx = Math.min(Math.max(px - tw / 2, padL), w - padR - tw);
                    cv.drawText(mk, mx, Math.max(py - 14, padT + 20), pMark);
                }
            }
        }

        cv.restore();   // 结束数据层裁剪

        // X 轴标签：只显 首/中/末 三个，大字
        int[] xi = { 0, n / 2, n - 1 };
        for (int k = 0; k < xi.length; k++) {
            int i = xi[k];
            if (i < 0 || i >= n) continue;
            float x = padL + slot * i + slot / 2f;
            String t = labels[i];
            float tw = pAxis.measureText(t);
            float dx = (k == 0) ? padL : (k == 2 ? w - padR - tw : x - tw / 2);
            cv.drawText(t, dx, h - 12, pAxis);
        }

        // 图例：消耗柱 + 各折线（大圆点+大字）
        if (showLegend) {
            float lx = padL;
            if (showBars && bars != null) {
                pBar.setColor(0x558A94A3);
                cv.drawRoundRect(lx, 14, lx + 20, 34, 5, 5, pBar);
                pLegend.setColor(0xFFC9D0DA);
                cv.drawText("消耗", lx + 28, 34, pLegend);
                lx += 28 + pLegend.measureText("消耗") + 34;
            }
            if (series != null) for (Series s : series) {
                pDot.setColor(s.color);
                cv.drawCircle(lx + 10, 24, 10, pDot);
                pLegend.setColor(0xFFC9D0DA);
                cv.drawText(s.label, lx + 28, 34, pLegend);
                lx += 28 + pLegend.measureText(s.label) + 34;
                if (lx > w - 90) break;
            }
        }
    }

    private String fmt(double v) {
        if (v >= 10000) return String.format("%.1fw", v / 10000);
        if (v >= 1000) return String.format("%.1fk", v / 1000);
        if (v >= 100) return String.format("%.0f", v);
        return String.format("%.1f", v);
    }
}
