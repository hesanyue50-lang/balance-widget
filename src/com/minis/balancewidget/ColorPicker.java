package com.minis.balancewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.LinearGradient;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 平台曲线配色选择器：25 个预设色点 + HSV 自定义调色盘 + Hex 输入。
 *
 * 为什么不用系统 ColorPicker（也确实没有内置的）：
 *  1. 需求点名「要展示出每个颜色的点」—— 用户要先看一眼色库再挑；
 *  2. 自定义要精确到任意色值 —— 饱和度/明度双通道 + Hex 兜底。
 *
 * 交互模型（省事但完整）：
 *  - 25 色网格：点一下即选中，再点「确定」生效；
 *  - 色相条：横向拖动选色相；
 *  - 饱和度/明度面板：2D 拖动，一次定两个量；
 *  - Hex 输入：#RRGGBB，粘贴色值用；
 *  - 「恢复自动」：清除自定义，回自动分配色。
 */
public class ColorPicker {

    /** 25 个预设色（明度错开排布，相邻点不撞色） */
    private static final int[] PRESETS = {
            0xFF4D6BFE, 0xFF22C55E, 0xFFF97316, 0xFF8B5CF6, 0xFF0EA5E9,
            0xFFEC4899, 0xFF14B8A6, 0xFFEAB308, 0xFF6366F1, 0xFF84CC16,
            0xFFEF4444, 0xFF06B6D4, 0xFFA855F7, 0xFFF43F5E, 0xFF65A30D,
            0xFFD97706, 0xFF7C3AED, 0xFF0891B2, 0xFFDB2777, 0xFFCA8A04,
            0xFF16A34A, 0xFF9333EA, 0xFFDC2626, 0xFF2563EB, 0xFF0F766E };

    public interface OnPick {
        /** argb = 0 表示「清除自定义，恢复自动配色」 */
        void onPick(int argb);
    }

    public static void show(final Activity act, String title, int current, final OnPick cb) {
        /* ---------------- 选中预览 ---------------- */
        final TextView preview = new TextView(act);
        preview.setText("　");
        preview.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 40));
        pp.bottomMargin = dp(act, 8);
        preview.setLayoutParams(pp);

        /* ---------------- 25 色网格 ---------------- */
        /* Hex 输入要在 buildGrid 之前建好 —— 点预设色时要把值同步进来 */
        final EditText eHex = new EditText(act);
        eHex.setHint("#RRGGBB，如 #FF5733");
        eHex.setMaxLines(1);
        eHex.setTextSize(13);

        final LinearLayout grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        /* 当前选中的颜色（int 值 + 来源标记） */
        final int[] sel = { current == 0 ? PRESETS[0] : current };
        final boolean[] fromPreset = { true };
        /* 单独记着 HSV 三元组：拖到纯黑/纯白时，从颜色反推不出色相和饱和度
           （黑白的色相是 0），若每次都反推，用户调好的颜色会莫名跳回红色。
           记住这三个量，拖动与重绘都以它为准。 */
        final float[] hsvRef = new float[3];
        Color.colorToHSV(sel[0], hsvRef);
        buildGrid(act, grid, sel, hsvRef, fromPreset, preview, eHex);

        /* ---------------- 分隔 ---------------- */
        TextView sep = new TextView(act);
        sep.setText("自定义颜色");
        sep.setTextColor(act.getColor(R.color.tx2));
        sep.setTextSize(12.5f);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(act, 14);
        sp.bottomMargin = dp(act, 6);
        sep.setLayoutParams(sp);

        /* ---------------- HSV 调色盘 ---------------- */
        /* 色相条（横向） */
        final View hueBar = new View(act) {
            @Override
            protected void onDraw(Canvas c) {
                LinearGradient lg = new LinearGradient(0, 0, getWidth(), getHeight(),
                        new int[] { Color.RED, Color.YELLOW, Color.GREEN,
                                Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED },
                        null, Shader.TileMode.CLAMP);
                Paint p = new Paint();
                p.setShader(lg);
                c.drawRect(0, 0, getWidth(), getHeight(), p);
                /* 当前色相位置画个小游标（用保存的色相，纯黑/纯白时也准） */
                float x = hsvRef[0] / 360f * getWidth();
                p = new Paint();
                p.setColor(0xFFFFFFFF);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(getContext(), 2));
                c.drawRect(x - dp(getContext(), 7), 0,
                        x + dp(getContext(), 7), getHeight(), p);
            }
        };

        /* 饱和度/明度面板（2D）：
           标准画法 = 先画「白→纯色」横向渐变，再叠「透明→黑」纵向渐变。
           两个 Shader 合成不了，分两遍画即可 —— 视觉上就是经典取色板。

           ⚠️ 填充和游标必须用**两个** Paint：早先把游标画在同一个 Paint 上，
           setStyle(STROKE) 会留到下一次 onDraw，两个 drawRect 就变成只描边 ——
           表现是"一拖动面板就整块变空白"（它其实是只剩个框）。 */
        final View svPad = new View(act) {
            private final Paint fill = new Paint();
            private final Paint cursor = new Paint();
            @Override
            protected void onDraw(Canvas c) {
                /* 明度=0（纯黑）时 colorToHSV 给的饱和/色相不可靠，
                   底色和游标都以 hsvRef 为准 */
                int pure = Color.HSVToColor(new float[]{hsvRef[0], 1f, 1f});
                fill.setStyle(Paint.Style.FILL);
                /* 第一遍：左白右纯色 */
                fill.setShader(new LinearGradient(0, 0, getWidth(), 0,
                        Color.WHITE, pure, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, getWidth(), getHeight(), fill);
                /* 第二遍：上透明下黑 */
                fill.setShader(new LinearGradient(0, 0, 0, getHeight(),
                        0x00FFFFFF, 0xFF000000, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, getWidth(), getHeight(), fill);
                /* 游标：白圈标当前位置（独立 Paint，不污染填充） */
                float cx = hsvRef[1] * getWidth();
                float cy = (1f - hsvRef[2]) * getHeight();
                cursor.setShader(null);
                cursor.setColor(0xFFFFFFFF);
                cursor.setStyle(Paint.Style.STROKE);
                cursor.setStrokeWidth(dp(getContext(), 2));
                c.drawCircle(cx, cy, dp(getContext(), 6), cursor);
            }
        };

        /* ---------------- Hex 输入 ---------------- */
        /* ---------------- 交互：拖动改颜色 ---------------- */
        bindHueTouch(hueBar, sel, hsvRef, fromPreset, preview, svPad, eHex, act);
        bindSvTouch(svPad, hueBar, sel, hsvRef, fromPreset, preview, eHex, act);
        eHex.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) applyHex(act, eHex, sel, hsvRef, fromPreset, preview, hueBar, svPad);
            }
        });

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(act, 18), dp(act, 6), dp(act, 18), 0);
        panel.addView(preview);
        panel.addView(grid);
        panel.addView(sep);
        panel.addView(hueBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 26)));
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 120));
        svLp.topMargin = dp(act, 10);
        panel.addView(svPad, svLp);
        LinearLayout.LayoutParams hxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hxLp.topMargin = dp(act, 12);
        panel.addView(eHex, hxLp);

        android.widget.ScrollView scroll = new android.widget.ScrollView(act);
        scroll.addView(panel);

        new AlertDialog.Builder(act)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        /* Hex 输入框还有焦点时先应用它 */
                        if (eHex.hasFocus()) applyHex(act, eHex, sel, hsvRef, fromPreset, preview, hueBar, svPad);
                        cb.onPick(sel[0]);
                    }
                })
                .setNeutralButton("恢复自动", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { cb.onPick(0); }
                })
                .setNegativeButton("取消", null)
                .show();

        /* 初始状态同步 */
        syncSelToUi(sel[0], preview, eHex);
    }

    /* ==================== 实现 ==================== */

    private static void buildGrid(final Activity act, final LinearLayout grid,
                                  final int[] sel, final float[] hsvRef,
                                  final boolean[] fromPreset,
                                  final TextView preview, final EditText eHex) {
        grid.removeAllViews();
        final int COLS = 5;
        LinearLayout row = null;
        for (int i = 0; i < PRESETS.length; i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.topMargin = dp(act, 6);
                row.setLayoutParams(rp);
                grid.addView(row);
            }
            final int color = PRESETS[i];
            View dot = new View(act);
            android.graphics.drawable.GradientDrawable gd =
                    new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gd.setColor(color);
            /* 选中的预设描个白圈 */
            if (color == sel[0] && fromPreset[0]) {
                gd.setStroke(dp(act, 3), 0xFFFFFFFF);
            }
            dot.setBackground(gd);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(act, 34), dp(act, 34));
            lp.rightMargin = dp(act, 14);
            lp.leftMargin = dp(act, 2);
            dot.setLayoutParams(lp);
            dot.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    sel[0] = color;
                    Color.colorToHSV(color, hsvRef);   // 选了预设，HSV 基准跟着走
                    fromPreset[0] = true;
                    buildGrid(act, grid, sel, hsvRef, fromPreset, preview, eHex);
                    /* 连 Hex 一起同步 —— 不然点预设后 Hex 还留着刚才输入的自定义值，
                       用户会以为选色没生效 */
                    syncSelToUi(color, preview, eHex);
                }
            });
            row.addView(dot);
        }
    }

    private static void bindHueTouch(final View bar, final int[] sel, final float[] hsvRef,
                                     final boolean[] fromPreset,
                                     final TextView preview, final View svPad,
                                     final EditText eHex, final Activity act) {
        bar.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN
                        || e.getAction() == MotionEvent.ACTION_MOVE) {
                    float fx = clamp(e.getX() / Math.max(1f, v.getWidth()), 0f, 1f);
                    hsvRef[0] = fx * 360f;      // 只改色相，饱和/明度保留
                    sel[0] = Color.HSVToColor(hsvRef);
                    fromPreset[0] = false;
                    v.invalidate();
                    svPad.invalidate();
                    syncSelToUi(sel[0], preview, eHex);
                    return true;
                }
                return false;
            }
        });
    }

    private static void bindSvTouch(final View pad, final View hueBar, final int[] sel,
                                    final float[] hsvRef, final boolean[] fromPreset,
                                    final TextView preview,
                                    final EditText eHex, final Activity act) {
        pad.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN
                        || e.getAction() == MotionEvent.ACTION_MOVE) {
                    float fx = clamp(e.getX() / Math.max(1f, v.getWidth()), 0f, 1f);
                    float fy = clamp(e.getY() / Math.max(1f, v.getHeight()), 0f, 1f);
                    hsvRef[1] = fx;             // 横轴 = 饱和度
                    hsvRef[2] = 1f - fy;        // 纵轴 = 明度（上亮下暗）
                    sel[0] = Color.HSVToColor(hsvRef);
                    fromPreset[0] = false;
                    v.invalidate();
                    hueBar.invalidate();
                    syncSelToUi(sel[0], preview, eHex);
                    return true;
                }
                return false;
            }
        });
    }

    private static void applyHex(Activity act, EditText eHex, int[] sel, float[] hsvRef,
                                 boolean[] fromPreset,
                                 TextView preview, View hueBar, View svPad) {
        String s = eHex.getText().toString().trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) {
            if (s.length() > 0) Toast.makeText(act, "格式：#RRGGBB（6 位）", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            int rgb = Integer.parseInt(s, 16);
            int argb = 0xFF000000 | rgb;
            sel[0] = argb;
            Color.colorToHSV(argb, hsvRef);    // 手填色值，HSV 基准同步
            fromPreset[0] = false;
            preview.setBackgroundColor(argb);
            hueBar.invalidate();
            svPad.invalidate();
        } catch (Exception ex) {
            Toast.makeText(act, "颜色值读不出来：" + s, Toast.LENGTH_SHORT).show();
        }
    }

    private static void syncSelToUi(int color, TextView preview, EditText eHex) {
        preview.setBackgroundColor(color);
        if (eHex != null) {
            eHex.setText(String.format("%02X%02X%02X",
                    (color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF));
        }
    }

    private static boolean isPreset(int c) {
        for (int i = 0; i < PRESETS.length; i++) if (PRESETS[i] == c) return true;
        return false;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int dp(android.content.Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
