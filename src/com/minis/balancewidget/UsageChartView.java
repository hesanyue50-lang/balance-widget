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

    private int touchIdx = -1;   // 按住时高亮的日期下标，-1=未按住

    @Override
    public boolean onTouchEvent(android.view.MotionEvent ev) {
        if (mN <= 0 || mCw <= 0) return super.onTouchEvent(ev);
        float slot = mCw / mN;
        int idx = (int) ((ev.getX() - mPadL) / slot);
        if (idx < 0) idx = 0;
        if (idx >= mN) idx = mN - 1;
        int a = ev.getAction();
        if (a == android.view.MotionEvent.ACTION_DOWN || a == android.view.MotionEvent.ACTION_MOVE) {
            touchIdx = idx; invalidate(); return true;
        }
        if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
            touchIdx = -1; invalidate(); return true;
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
            if (s.vals != null) for (double v : s.vals)
                if (!Double.isNaN(v) && v > maxLine) maxLine = v;
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
        float padL = 70, padR = 22, padT = 34, padB = 88;
        float cw = w - padL - padR, ch = h - padT - padB;
        if (cw <= 0 || ch <= 0) return;
        int n = labels != null ? labels.length : 0;
        if (n == 0) return;
        mPadL = padL; mCw = cw; mN = n;   // 供 onTouchEvent 换算点击的日期下标

        // 稀疏网格（5 档）+ 明显 Y 轴刻度（¥ 前缀，大字深色）
        pGrid.setColor(0x2E8A94A3);
        pAxis.setTextSize(24f);
        pAxis.setColor(0xFF6E7887);
        for (int g = 0; g <= 4; g++) {
            float y = padT + ch * g / 4f;
            if (showGrid) cv.drawLine(padL, y, w - padR, y, pGrid);
            double val = maxLine * (4 - g) / 4f;
            cv.drawText("¥" + fmt(val), 4, y + 8, pAxis);
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
                // 渐变面积填充：只覆盖有数据的连续段（跳过 NaN 空天）
                if (s.fill) {
                    fillPath.rewind();
                    boolean st = false;
                    float fx = 0, lx2 = 0;
                    for (int i = 0; i < n && i < s.vals.length; i++) {
                        if (Double.isNaN(s.vals[i])) continue;
                        float x = padL + slot * i + slot / 2f;
                        float y = padT + ch - (float) (s.vals[i] / maxLine * ch);
                        if (!st) { fillPath.moveTo(x, y); fx = x; st = true; }
                        else fillPath.lineTo(x, y);
                        lx2 = x;
                    }
                    if (st) {
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
                }
                // 折线：跳过 NaN 空天（断线重启，不从 0 陡升）
                pLine.setColor(s.color);
                linePath.rewind();
                boolean st2 = false;
                for (int i = 0; i < n && i < s.vals.length; i++) {
                    if (Double.isNaN(s.vals[i])) { st2 = false; continue; }
                    float x = padL + slot * i + slot / 2f;
                    float y = padT + ch - (float) (s.vals[i] / maxLine * ch);
                    if (!st2) { linePath.moveTo(x, y); st2 = true; }
                    else linePath.lineTo(x, y);
                }
                cv.drawPath(linePath, pLine);
                // 数据点：只画有数据的天
                pDot.setColor(s.color);
                pDotIn.setColor(0xFFFFFFFF);
                for (int i = 0; i < n && i < s.vals.length; i++) {
                    if (Double.isNaN(s.vals[i])) continue;
                    float x = padL + slot * i + slot / 2f;
                    float y = padT + ch - (float) (s.vals[i] / maxLine * ch);
                    cv.drawCircle(x, y, 9f, pDot);
                    cv.drawCircle(x, y, 3.5f, pDotIn);
                }
            }
        }

        cv.restore();   // 结束数据层裁剪

        // X 轴标签：尽量每天标（最多 8 个），MM/DD
        int step = Math.max(1, (n + 7) / 8);
        for (int i = 0; i < n; i += step) {
            float x = padL + slot * i + slot / 2f;
            String t = labels[i];
            float tw = pAxis.measureText(t);
            float dx = Math.min(Math.max(x - tw / 2, padL - 20), w - padR - tw);
            cv.drawText(t, dx, h - 56, pAxis);
        }

        // 按住时在该日位置显示浮动框：各 API 当日余额（松开隐藏）
        if (touchIdx >= 0 && series != null && labels != null && touchIdx < labels.length) {
            int entries = 0;
            for (Series ss : series) if (!ss.fill) entries++;
            if (entries > 0) {
                float rowH = 50f;
                pLegend.setTextSize(30f);
                float maxW = pLegend.measureText(labels[touchIdx]);
                for (Series ss : series) if (!ss.fill)
                    maxW = Math.max(maxW, pLegend.measureText(ss.label));
                float boxW = maxW + 130;
                float boxH = (entries + 1) * rowH + 12;
                float slot2 = mCw / mN;
                float cx = mPadL + slot2 * touchIdx + slot2 / 2f;
                float bx = Math.min(Math.max(cx - boxW / 2, padL), w - padR - boxW);
                float by = padT + 4;
                Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
                bp.setStyle(Paint.Style.FILL); bp.setColor(0xF5FFFFFF);
                cv.drawRoundRect(bx, by, bx + boxW, by + boxH, 12, 12, bp);
                bp.setStyle(Paint.Style.STROKE); bp.setStrokeWidth(1.5f); bp.setColor(0x2A000000);
                cv.drawRoundRect(bx, by, bx + boxW, by + boxH, 12, 12, bp);
                float ry = by + 8 + rowH / 2;
                pLegend.setColor(0xFF39424F);
                cv.drawText(labels[touchIdx], bx + 16, ry + 11, pLegend);
                ry += rowH;
                for (Series ss : series) {
                    if (ss.fill) continue;
                    double v = (ss.vals != null && touchIdx < ss.vals.length) ? ss.vals[touchIdx] : Double.NaN;
                    pDot.setColor(ss.color);
                    cv.drawCircle(bx + 24, ry, 9, pDot);
                    pLegend.setColor(0xFF39424F);
                    cv.drawText(ss.label + "  余额 \u00a5" + (Double.isNaN(v) ? "--" : String.format("%.2f", v)),
                            bx + 40, ry + 11, pLegend);
                    ry += rowH;
                }
                Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG);
                hl.setStyle(Paint.Style.STROKE); hl.setStrokeWidth(2f); hl.setColor(0x3339424F);
                cv.drawLine(cx, padT, cx, padT + ch, hl);
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
