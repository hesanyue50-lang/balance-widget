package com.minis.balancewidget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * 决定小组件用哪套配色（深色字 / 浅色字）。
 *
 * 早先试图读系统壁纸主色，但那条路问题不少：要权限、动态壁纸拿到的是静态兜底图、
 * 还没法对齐小组件的位置。现在改成**从自定义背景图算平均亮度** ——
 * 背景是我们自己存的，算得准、不需要权限、也不存在"拿到的图和看到的不一样"。
 *
 * 没开自定义背景时，底色是我们自己画的浅色半透明，直接用浅色文字方案。
 */
public final class WallpaperTint {

    private static Boolean cachedDark = null;
    private static long cachedAt = 0L;
    private static final long TTL_MS = 30_000L;

    private WallpaperTint() { }

    public static boolean useDark(Context ctx) {
        long now = System.currentTimeMillis();
        if (cachedDark != null && now - cachedAt < TTL_MS) return cachedDark;

        boolean dark = false;
        try {
            if (BackgroundStore.isEnabled(ctx)) {
                dark = BackgroundStore.luminance(ctx) < 0.5;
            }
        } catch (Throwable ignored) { }

        cachedDark = dark;
        cachedAt = now;
        return dark;
    }

    public static void invalidate() {
        cachedDark = null;
        cachedAt = 0L;
    }

    /** 稀疏采样算平均亮度（人眼对绿最敏感，别用简单平均） */
    private static double avgLum(Bitmap b) {
        try {
            int w = b.getWidth(), h = b.getHeight();
            if (w <= 0 || h <= 0) return 1.0;
            int stepX = Math.max(1, w / 16), stepY = Math.max(1, h / 16);
            long sum = 0;
            int n = 0;
            for (int y = 0; y < h; y += stepY) {
                for (int x = 0; x < w; x += stepX) {
                    int c = b.getPixel(x, y);
                    sum += (int) (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c));
                    n++;
                }
            }
            return n == 0 ? 1.0 : (sum / (double) n) / 255.0;
        } catch (Throwable t) {
            return 1.0;
        }
    }

    // ---------- 两套预设配色 ----------

    public static int bgRes(boolean dark) {
        return dark ? R.drawable.wg_glass_d : R.drawable.wg_glass_l;
    }
    public static int tx(boolean dark)     { return dark ? 0xFFE9EEF6 : 0xFF232B38; }
    public static int tx2(boolean dark)    { return dark ? 0xFFA8B2C2 : 0xFF5A6472; }
    public static int tx3(boolean dark)    { return dark ? 0xFF7A8496 : 0xFF8C96A4; }
    public static int div(boolean dark)    { return dark ? 0x3DFFFFFF : 0x2E9AA6B2; }
    public static int danger(boolean dark) { return dark ? 0xFFFF8A8A : 0xFFC93B3B; }
}
