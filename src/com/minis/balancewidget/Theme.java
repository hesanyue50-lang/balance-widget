package com.minis.balancewidget;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;

/**
 * 主题（配色皮肤）的切换与取色。
 *
 * 设计要点：
 * 1. **颜色不走 R.color，走主题 attr**。Android 的资源颜色是编译期常量，
 *    想换一套配色只能重装；而 attr 可以在运行时通过 setTheme() 换。
 *    所以 layout 写 ?attr/cXxx、drawable 写 ?attr/cXxx、Java 用 {@link #c}。
 * 2. 切换主题必须 recreate()：attr 在 View 创建时解析，已经建好的 View
 *    不会自己重来一遍。
 * 3. 7 套都保持在 "Aurora Glass 的结构" 下（卡片 + 1px 描边 + 20dp 圆角 +
 *    一个强调色），只换颜色层 —— 布局、间距、字号不动。这是 skill 里
 *    「换风格只动变量层」的落地方式，也是保证切完不会散架的前提。
 */
public final class Theme {

    /** 主题 id，与 {@link #STYLES} 一一对应 */
    public static final String[] IDS = {
            "aurora_light", "aurora_dark", "editorial", "swiss", "devtool", "brutal", "clay"
    };

    public static final String[] NAMES = {
            "Aurora 亮", "Aurora 暗", "Editorial 纸感",
            "Minimal 瑞士", "Devtool 终端", "Brutalism 撞色", "Soft Clay 柔和"
    };

    /** 一句话说清气质差异（设置页里显示在名称下面） */
    public static final String[] DESCS = {
            "近白底 + 冷蓝强调。默认，最不容易看腻",
            "近黑底 + 玻璃层次。夜里用眼睛舒服",
            "暖纸色 + 砖红。像看一本印刷品",
            "纯黑白 + 直角感。信息密度最高",
            "纯黑 + 等宽绿。程序员那一挂",
            "奶油底 + 撞色块 + 硬描边。个性最强",
            "灰蓝底 + 大圆角。柔和不刺眼"
    };

    public static final int[] STYLES = {
            R.style.AppTheme, R.style.ThemeAuroraDark, R.style.ThemeEditorial,
            R.style.ThemeSwiss, R.style.ThemeDevtool, R.style.ThemeBrutal, R.style.ThemeClay
    };

    /** 预览色块：{底, 卡片, 强调, 主文字}，与 styles.xml 里那套值保持一致 */
    public static final int[][] PREVIEW = {
            { 0xFFF6F7F9, 0xFFFFFFFF, 0xFF4166F5, 0xFF0B0E14 },
            { 0xFF0A0B0F, 0xFF14161C, 0xFF6C8CFF, 0xFFF5F7FA },
            { 0xFFFAF7F2, 0xFFFFFFFF, 0xFF9A3412, 0xFF1A1614 },
            { 0xFFFFFFFF, 0xFFFFFFFF, 0xFF0A0A0A, 0xFF0A0A0A },
            { 0xFF0A0A0A, 0xFF141414, 0xFF3ECF8E, 0xFFE6E6E6 },
            { 0xFFFFF8E7, 0xFFFFFFFF, 0xFFFF5C5C, 0xFF000000 },
            { 0xFFEEF1F6, 0xFFF7F9FC, 0xFF7C9CFF, 0xFF2B3446 }
    };

    /**
     * 形状与字体维度：{圆角 dp, 描边 dp, 字体 0默认/1衬线/2等宽, 投影 0无/1软/2硬}
     *
     * 只换颜色不叫换风格 —— 直角和 20dp 圆角是两种气质，衬线和等宽也是。
     * 这两组参数由 {@link #skin} 在运行时套到视图树上。
     *
     * 前两套（Aurora）走 0 值：它们的视觉靠现有 9-patch + 描边，不重绘背景，
     * 免得把已经调好的圆角/内边距改坏。
     */
    public static final int[][] SHAPE = {
            { 20, 1, 0, 0 },    // Aurora 亮（走 9-patch，不重绘）
            { 20, 1, 0, 0 },    // Aurora 暗（同上）
            { 0,  1, 1, 0 },    // Editorial：直角 + 衬线
            { 4,  1, 0, 0 },    // Swiss：小圆角 + 默认字
            { 4,  1, 2, 0 },    // Devtool：小圆角 + 等宽
            { 0,  2, 0, 0 },    // Brutalism：直角 + 2dp 黑边（粗野感靠描边，不靠投影）
            { 24, 0, 0, 1 },    // Clay：大圆角 + 无描边 + 软阴影
    };

    public static int radiusDp(Context c) { return SHAPE[index(c)][0]; }

    public static int strokeDp(Context c) { return SHAPE[index(c)][1]; }

    public static int fontKind(Context c) { return SHAPE[index(c)][2]; }

    public static int shadowKind(Context c) { return SHAPE[index(c)][3]; }

    /** 是否需要重绘背景。Aurora 两套用现有的 9-patch，其余由代码画。 */
    public static boolean isStyled(Context c) { return index(c) >= 2; }

    private static final String KEY = "ui_theme";

    private Theme() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(App.PREFS_FLAGS, Context.MODE_PRIVATE);
    }

    public static int index(Context c) {
        try {
            if (!sp(c).contains(KEY)) {
                /* 从没选过：跟随系统深浅。选过就听用户的 ——
                   否则用户明明选了「Aurora 亮」，系统一进深色模式又被拽回去。 */
                boolean night = (c.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                return night ? 1 : 0;
            }
            int i = sp(c).getInt(KEY, 0);
            return (i < 0 || i >= STYLES.length) ? 0 : i;
        } catch (Throwable ignored) { }
        return 0;
    }

    /** 当前主题是不是深色底（状态栏图标、系统栏配色要用） */
    public static boolean isDark(Context c) {
        int bg = PREVIEW[index(c)][0];
        int r = (bg >> 16) & 0xFF, g = (bg >> 8) & 0xFF, b = bg & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 < 128;
    }

    public static void setIndex(Context c, int i) {
        if (i < 0 || i >= STYLES.length) i = 0;
        try {
            sp(c).edit().putInt(KEY, i).apply();
        } catch (Throwable ignored) { }
    }

    public static int styleRes(Context c) {
        return STYLES[index(c)];
    }

    /** Activity 必须在 setContentView **之前**调用 */
    public static void apply(Activity a) {
        try {
            a.setTheme(styleRes(a));
        } catch (Throwable ignored) { }
    }

    /**
     * 取主题色。
     *
     * 解析失败时给一个中性灰而不是 0（透明）—— 透明会让控件"消失"，
     * 比颜色不对难查得多。
     */
    public static int c(Context ctx, int attr) {
        try {
            TypedValue tv = new TypedValue();
            if (ctx.getTheme().resolveAttribute(attr, tv, true)) {
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return tv.data;
                }
                if (tv.resourceId != 0) return ctx.getResources().getColor(tv.resourceId);
                return tv.data;
            }
        } catch (Throwable ignored) { }
        return 0xFF808080;
    }

    /** 带透明度地取主题色（0~255） */
    public static int ca(Context ctx, int attr, int alpha) {
        int v = c(ctx, attr);
        return (v & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /* ==================== 视图树套皮 ==================== */

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 把当前主题的**形状与字体**套到整棵视图树上。
     *
     * 只动两样东西：
     *   - tag="card" 的容器 → 重画背景（圆角 / 描边 / 投影）
     *   - TextView → 换字体（衬线 / 等宽）
     * 颜色不在这里处理，那是 attr 的活。
     *
     * Aurora 两套直接返回：它们的视觉靠现有 9-patch，重绘会把已经调好的
     * 圆角和内边距改坏 —— 没坏的东西别碰。
     */
    public static void skin(Context ctx, android.view.View root) {
        if (root == null || !isStyled(ctx)) return;
        try {
            skinOne(ctx, root);
        } catch (Throwable ignored) { }
    }

    private static void skinOne(Context ctx, android.view.View v) {
        if (v == null) return;
        if ("card".equals(v.getTag())) cardBg(ctx, v);
        if (v instanceof android.widget.TextView) typeface(ctx, (android.widget.TextView) v);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) skinOne(ctx, g.getChildAt(i));
        }
    }

    private static void cardBg(Context ctx, android.view.View v) {
        int r = dp(ctx, radiusDp(ctx));
        int sw = dp(ctx, strokeDp(ctx));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(c(ctx, R.attr.c_card));
        bg.setCornerRadius(r);
        if (sw > 0) bg.setStroke(sw, c(ctx, R.attr.c_line));
        v.setBackground(bg);
        /* 深色主题下投影几乎看不见，给了也没用；只有浅色的 Clay 需要一点 */
        v.setElevation(shadowKind(ctx) == 1 ? dp(ctx, 2) : 0);
    }

    private static void typeface(Context ctx, android.widget.TextView tv) {
        int kind = fontKind(ctx);
        if (kind == 0) return;
        boolean bold = false;
        android.graphics.Typeface cur = tv.getTypeface();
        if (cur != null) bold = cur.isBold();
        android.graphics.Typeface base = (kind == 2)
                ? android.graphics.Typeface.MONOSPACE
                : android.graphics.Typeface.SERIF;
        tv.setTypeface(base, bold ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
    }

    /**
     * 卡片背景（圆角 + 1dp 描边），跟随主题。
     *
     * 代码里动态建的卡片**别用 9-patch**：card_bg.9.png 是写死的白色，
     * 暗色主题下会变成一块白板（LockDialog 的菜单就是这么白的）。
     */
    public static android.graphics.drawable.GradientDrawable cardBg(Context c) {
        int r = dp(c, isStyled(c) ? radiusDp(c) : 20);
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setColor(c(c, R.attr.c_card));
        d.setCornerRadius(r);
        int sw = dp(c, isStyled(c) ? strokeDp(c) : 1);
        if (sw > 0) d.setStroke(sw, c(c, R.attr.c_line));
        return d;
    }

    /** 主按钮背景（胶囊 + 强调色），跟随主题。同上，别用 btn_primary.9.png。 */
    public static android.graphics.drawable.GradientDrawable primaryBg(Context c) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setColor(c(c, R.attr.c_accent));
        d.setCornerRadius(dp(c, 999));
        return d;
    }

    /** 取主题里指向的 drawable 资源 id（如 bg_pill：胶囊 / 直角由主题决定） */
    public static int bgRes(Context c, int attr) {
        try {
            TypedValue tv = new TypedValue();
            if (c.getTheme().resolveAttribute(attr, tv, true) && tv.resourceId != 0) {
                return tv.resourceId;
            }
        } catch (Throwable ignored) { }
        return 0;
    }

    /* ==================== 小组件专用配色 ==================== */

    /**
     * 桌面小组件的文字色三档：{主, 次, 三级}。
     *
     * 为什么不直接用 {@link #c} 解析 attr：小组件刷新发生在**没有 Activity 的
     * 后台上下文**里，那里的 Theme 是 manifest 上写死的 AppTheme，
     * 解析出来永远是 Aurora 亮的值 —— 用户选了 Editorial，桌面还是冷黑字。
     * 表格里的值是各主题 c_tx / c_tx2 / c_tx3 的原样抄录。
     */
    private static final int[][] SMALL_TEXT = {
            { 0xFF0B0E14, 0xFF414A5C, 0xFF55606F },   // Aurora 亮
            { 0xFFF5F7FA, 0xFF9AA3B2, 0xFF6B7382 },   // Aurora 暗
            { 0xFF1A1614, 0xFF574E45, 0xFF7A6E62 },   // Editorial
            { 0xFF0A0A0A, 0xFF555555, 0xFF808080 },   // Minimal 瑞士
            { 0xFFE6E6E6, 0xFF8A8A8A, 0xFF5C5C5C },   // Devtool
            { 0xFF000000, 0xFF333333, 0xFF555555 },   // Brutalism
            { 0xFF2B3446, 0xFF5A6B85, 0xFF8394AC },   // Soft Clay
    };

    public static int wTx(Context c) { return SMALL_TEXT[index(c)][0]; }

    public static int wTx2(Context c) { return SMALL_TEXT[index(c)][1]; }

    public static int wTx3(Context c) { return SMALL_TEXT[index(c)][2]; }

    /* ==================== 小组件的"风格"资源 ==================== */

    /**
     * 每套主题对应的小组件背景（圆角 / 底色 / 描边都不同）。
     *
     * RemoteViews 不能解析 ?attr，所以「换风格」只能靠**切换整份 drawable**：
     * `setInt(root, "setBackgroundResource", res)` 是 RemoteViews 支持的 action，
     * 而 setBackgroundColor 会把圆角一起丢了。
     */
    private static final int[] WIDGET_BG = {
            R.drawable.wg_t0, R.drawable.wg_t1, R.drawable.wg_t2,
            R.drawable.wg_t3, R.drawable.wg_t4, R.drawable.wg_t5,
            R.drawable.wg_t6,
    };

    public static int widgetBgRes(Context c) { return WIDGET_BG[index(c)]; }

    /** 小组件字号三档：{总额, 行值, 行名}。衬线和高密度主题要相应收一点。 */
    private static final float[][] WIDGET_SP = {
            { 23f, 15f, 11.5f },   // Aurora 亮
            { 23f, 15f, 11.5f },   // Aurora 暗
            { 22f, 14.5f, 11f },   // Editorial（衬线字更占宽）
            { 22f, 14.5f, 11f },   // Minimal 瑞士（高密度）
            { 22f, 14f, 11f },     // Devtool
            { 24f, 15.5f, 12f },   // Brutalism
            { 24f, 16f, 12f },     // Soft Clay
    };

    public static float widgetTotalSp(Context c) { return WIDGET_SP[index(c)][0]; }

    public static float widgetRowSp(Context c) { return WIDGET_SP[index(c)][1]; }

    public static float widgetNameSp(Context c) { return WIDGET_SP[index(c)][2]; }

    /** 只有 Aurora 两套用「壁纸毛玻璃」底，其余主题自带不透明底色 */
    public static boolean widgetUsesFrost(Context c) {
        int i = index(c);
        return i == 0 || i == 1;
    }
}
