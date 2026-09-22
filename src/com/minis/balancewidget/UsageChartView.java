package com.minis.balancewidget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * 用量统计图表（纯代码绘制，无第三方库）。
 * 柱状 = 每日消耗（CNY），折线 = 每日总余额（CNY）。
 */
public class UsageChartView extends View {

    private double[] cons;      // 每日消耗
    private double[] bal;       // 每日总余额
    private String[] labels;    // 每日标签（如 9/1）
    private double maxCons = 1;
    private double maxBal = 1;

    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();

    public UsageChartView(Context c) { super(c); init(); }
    public UsageChartView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        pBar.setStyle(Paint.Style.FILL);
        pBar.setColor(0xFF4D6BFE);
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(3f);
        pLine.setColor(0xFF22C55E);
        pAxis.setStyle(Paint.Style.STROKE);
        pAxis.setStrokeWidth(1f);
        pAxis.setColor(0x33888888);
        pText.setTextSize(20f);
        pText.setColor(0xFF9AA3AF);
    }

    public void setData(double[] cons, double[] bal, String[] labels) {
        this.cons = cons; this.bal = bal; this.labels = labels;
        maxCons = 1; maxBal = 1;
        if (cons != null) for (double v : cons) if (v > maxCons) maxCons = v;
        if (bal != null) for (double v : bal) if (v > maxBal) maxBal = v;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        if (cons == null || cons.length == 0) return;
        int w = getWidth(), h = getHeight();
        float padL = 8, padR = 8, padT = 24, padB = 40;
        float cw = w - padL - padR, ch = h - padT - padB;
        if (cw <= 0 || ch <= 0) return;
        int n = cons.length;

        // 横向网格 4 条
        for (int g = 0; g <= 4; g++) {
            float y = padT + ch * g / 4f;
            cv.drawLine(padL, y, w - padR, y, pAxis);
        }

        float slot = cw / n;
        float barW = Math.max(2f, slot * 0.55f);

        // 消耗柱状
        for (int i = 0; i < n; i++) {
            float bh = (float) (cons[i] / maxCons * ch);
            float x = padL + slot * i + (slot - barW) / 2f;
            cv.drawRect(x, padT + ch - bh, x + barW, padT + ch, pBar);
        }

        // 余额折线
        linePath.rewind();
        for (int i = 0; i < n && bal != null && i < bal.length; i++) {
            float x = padL + slot * i + slot / 2f;
            float y = padT + ch - (float) (bal[i] / maxBal * ch);
            if (i == 0) linePath.moveTo(x, y); else linePath.lineTo(x, y);
        }
        cv.drawPath(linePath, pLine);

        // X 轴标签（稀疏：每 ~1/5 显示一个）
        int step = Math.max(1, n / 5);
        for (int i = 0; i < n; i += step) {
            if (labels != null && i < labels.length) {
                cv.drawText(labels[i], padL + slot * i, h - 12, pText);
            }
        }
        // 图例
        pText.setColor(0xFF4D6BFE);
        cv.drawText("■ 消耗", padL, 16, pText);
        pText.setColor(0xFF22C55E);
        cv.drawText("— 余额", padL + 90, 16, pText);
        pText.setColor(0xFF9AA3AF);
    }
}
