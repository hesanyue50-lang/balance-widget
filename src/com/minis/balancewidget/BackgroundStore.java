package com.minis.balancewidget;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 自定义背景：用户传一张图，裁切 + 模糊 + 吸色混白，生成小组件底图。
 *
 * 三条设计决定（都是被用户否掉上一版之后改的）：
 *
 *   1. **不用缩略图**。上一版把图缩到 40px 再放大，糊得发灰、还有块状感。
 *      现在改成：**原图先按组件比例中心裁切**（保住构图），
 *      再走箱式模糊 —— 模糊是平滑的，不是"放大一张小图"。
 *
 *   2. **吸色混白**。直接从背景取平均色会偏脏，纯白底又和背景割裂。
 *      取两者中间值作为底色，压一层半透明上去 —— 既有背景的色调，又不会脏。
 *
 *   3. 底色是**运行时画出来的位图**，不是固定 9-patch。因为颜色要跟着图变，
 *      静态资源做不到。圆角也在这张位图上裁出来。
 */
public final class BackgroundStore {

    private static final String K_ENABLED = "custom_bg_on";
    private static final String DIR = "bg";
    private static final String RAW = "custom_raw.png";

    /** 输出尺寸：小组件大致 2:1.1，定大一点让桌面缩放时有富余 */
    private static final int OUT_W = 420;
    private static final int OUT_H = 230;

    /** 圆角半径（px），和 App 内卡片保持一致 */
    private static final int CORNER = 60;

    /** 模糊半径：越大越糊。12 在 420px 画布上是"明显但不糊烂"的程度 */
    private static final int BLUR_RADIUS = 12;

    /** 底色里掺多少背景色（0=纯白，1=纯背景色）。0.5 就是取中间值 */
    private static final float TINT_MIX = 0.5f;

    /** 底色遮罩的不透明度 */
    private static final float TINT_ALPHA = 0.42f;

    private BackgroundStore() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File rawFile(Context c) {
        return new File(dir(c), RAW);
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean(K_ENABLED, false) && rawFile(c).exists();
    }

    public static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(K_ENABLED, on).apply();
    }

    public static boolean hasImage(Context c) {
        return rawFile(c).exists();
    }

    /** 保存用户选的图片（存原图，渲染时再实时处理） */
    public static boolean saveFrom(Context c, Uri uri) {
        try {
            // 只读尺寸做下采样判断，避免超大图 OOM
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = c.getContentResolver().openInputStream(uri);
            if (in == null) return false;
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            if (o.outWidth <= 0 || o.outHeight <= 0) return false;

            int scale = 1;
            while (o.outWidth / (scale * 2) >= 1600) scale *= 2;

            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = scale;
            InputStream in2 = c.getContentResolver().openInputStream(uri);
            if (in2 == null) return false;
            Bitmap src = BitmapFactory.decodeStream(in2, null, o2);
            in2.close();
            if (src == null) return false;

            FileOutputStream fo = new FileOutputStream(rawFile(c));
            src.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            src.recycle();

            setEnabled(c, true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void clear(Context c) {
        try {
            rawFile(c).delete();
        } catch (Throwable ignored) { }
        setEnabled(c, false);
    }

    /**
     * 生成小组件底色位图：原图 → 中心裁切 → 模糊 → 叠「背景色与白混色」的遮罩 → 圆角。
     * 没开启自定义背景就返回 null（调用方会退回纯半透明 9-patch）。
     */
    public static Bitmap build(Context c) {
        if (!isEnabled(c)) return null;
        Bitmap src = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            src = BitmapFactory.decodeFile(rawFile(c).getAbsolutePath(), o);
            if (src == null) return null;

            // 1) 中心裁切到组件比例（保住构图，不拉伸变形）
            Bitmap cropped = centerCrop(src, OUT_W, OUT_H);
            if (cropped != src) src.recycle();
            src = null;

            // 2) 模糊：用箱式模糊（三次均值近似高斯），比"缩小再放大"平滑得多
            Bitmap blurred = boxBlur(cropped, BLUR_RADIUS);
            if (blurred != cropped) cropped.recycle();

            // 3) 吸色：取模糊图平均色，与白色各半 → 作为底色遮罩
            int avg = averageColor(blurred);
            int tint = mix(avg, Color.WHITE, TINT_MIX);

            Bitmap out = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            cv.drawBitmap(blurred, 0, 0, null);
            blurred.recycle();

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(tint);
            p.setAlpha((int) (TINT_ALPHA * 255));
            cv.drawRect(0, 0, OUT_W, OUT_H, p);

            // 4) 裁圆角
            roundCorners(out, CORNER);

            // 5) 保险：超过 1MB 过不了 RemoteViews 的跨进程传输
            if (out.getByteCount() > 900 * 1024) {
                float k = (float) Math.sqrt(900 * 1024.0 / out.getByteCount());
                Bitmap small = Bitmap.createScaledBitmap(out,
                        Math.max(1, (int) (out.getWidth() * k)),
                        Math.max(1, (int) (out.getHeight() * k)), true);
                out.recycle();
                out = small;
            }
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (src != null && !src.isRecycled()) src.recycle();
        }
    }

    // ---------- 图像处理 ----------

    /** 中心裁切到目标比例 */
    private static Bitmap centerCrop(Bitmap src, int w, int h) {
        int sw = src.getWidth(), sh = src.getHeight();
        if (sw <= 0 || sh <= 0) return src;
        float targetRatio = (float) w / h;
        float srcRatio = (float) sw / sh;
        int cw, ch;
        if (srcRatio > targetRatio) {          // 原图更宽 → 左右裁
            ch = sh;
            cw = Math.round(sh * targetRatio);
        } else {                                // 原图更高 → 上下裁
            cw = sw;
            ch = Math.round(sw / targetRatio);
        }
        int x = (sw - cw) / 2, y = (sh - ch) / 2;
        Bitmap c = Bitmap.createBitmap(src, x, y, cw, ch);
        if (c != src) {
            Bitmap scaled = Bitmap.createScaledBitmap(c, w, h, true);
            if (scaled != c) c.recycle();
            return scaled;
        }
        return c;
    }

    /** 箱式模糊：横向+纵向各做 3 次，效果接近高斯但快得多 */
    private static Bitmap boxBlur(Bitmap src, int radius) {
        int w = src.getWidth(), h = src.getHeight();
        int[] pix = new int[w * h];
        src.getPixels(pix, 0, w, 0, 0, w, h);
        int[] tmp = new int[w * h];

        for (int pass = 0; pass < 3; pass++) {
            blurH(pix, tmp, w, h, radius);
            blurV(tmp, pix, w, h, radius);
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pix, 0, w, 0, 0, w, h);
        return out;
    }

    private static void blurH(int[] in, int[] out, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int ti = y * w, li = ti, ri = ti + r;
            int fv = in[ti], lv = in[ti + w - 1];
            int a = (r + 1) * (fv >>> 24), rr = (r + 1) * ((fv >> 16) & 0xFF),
                g = (r + 1) * ((fv >> 8) & 0xFF), b = (r + 1) * (fv & 0xFF);
            for (int i = 0; i < r; i++) {
                int c = in[ti + i];
                a += c >>> 24; rr += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF;
            }
            for (int i = 0; i <= r; i++) {
                int c = in[ri++];
                a += (c >>> 24) - (fv >>> 24);
                rr += ((c >> 16) & 0xFF) - ((fv >> 16) & 0xFF);
                g += ((c >> 8) & 0xFF) - ((fv >> 8) & 0xFF);
                b += (c & 0xFF) - (fv & 0xFF);
                out[ti++] = ((a / div) << 24) | ((rr / div) << 16) | ((g / div) << 8) | (b / div);
            }
            for (int i = r + 1; i < w - r; i++) {
                int ci = in[ri++], co = in[li++];
                a += (ci >>> 24) - (co >>> 24);
                rr += ((ci >> 16) & 0xFF) - ((co >> 16) & 0xFF);
                g += ((ci >> 8) & 0xFF) - ((co >> 8) & 0xFF);
                b += (ci & 0xFF) - (co & 0xFF);
                out[ti++] = ((a / div) << 24) | ((rr / div) << 16) | ((g / div) << 8) | (b / div);
            }
            for (int i = w - r; i < w; i++) {
                int c = in[li++];
                a += (lv >>> 24) - (c >>> 24);
                rr += ((lv >> 16) & 0xFF) - ((c >> 16) & 0xFF);
                g += ((lv >> 8) & 0xFF) - ((c >> 8) & 0xFF);
                b += (lv & 0xFF) - (c & 0xFF);
                out[ti++] = ((a / div) << 24) | ((rr / div) << 16) | ((g / div) << 8) | (b / div);
            }
        }
    }

    private static void blurV(int[] in, int[] out, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            int ti = x, li = ti, ri = ti + r * w;
            int fv = in[ti], lv = in[ti + w * (h - 1)];
            int a = (r + 1) * (fv >>> 24), rr = (r + 1) * ((fv >> 16) & 0xFF),
                g = (r + 1) * ((fv >> 8) & 0xFF), b = (r + 1) * (fv & 0xFF);
            for (int i = 0; i < r; i++) {
                int c = in[ti + i * w];
                a += c >>> 24; rr += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF;
            }
            for (int i = 0; i <= r; i++) {
                int c = in[ri]; ri += w;
                a += (c >>> 24) - (fv >>> 24);
                rr += ((c >> 16) & 0xFF) - ((fv >> 16) & 0xFF);
                g += ((c >> 8) & 0xFF) - ((fv >> 8) & 0xFF);
                b += (c & 0xFF) - (fv & 0xFF);
                out[ti] = ((a / div) << 24) | ((rr / div) << 16) | ((g / div) << 8) | (b / div);
                ti += w;
            }
            for (int i = r + 1; i < h - r; i++) {
                int ci = in[ri], co = in[li];
                ri += w; li += w;
                a += (ci >>> 24) - (co >>> 24);
                rr += ((ci >> 16) & 0xFF) - ((co >> 16) & 0xFF);
                g += ((ci >> 8) & 0xFF) - ((co >> 8) & 0xFF);
                b += (ci & 0xFF) - (co & 0xFF);
                out[ti] = ((a / div) << 24) | ((rr / div) << 16) | ((g / div) << 8) | (b / div);
                ti += w;
            }
            for (int i = h - r; i < h; i++) {
                int c = in[li]; li += w;
                a += (lv >>> 24) - (c >>> 24);
                rr += ((lv >> 16) & 0xFF) - ((c >> 16) & 0xFF);
                g += ((lv >> 8) & 0xFF) - ((c >> 8) & 0xFF);
                b += (lv & 0xFF) - (c & 0xFF);
                out[ti] = ((a / div) << 24) | ((rr / div) << 16) | ((g / div) << 8) | (b / div);
                ti += w;
            }
        }
    }

    /** 稀疏采样求平均色 */
    private static int averageColor(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int step = Math.max(1, Math.min(w, h) / 24);
        long a = 0, r = 0, g = 0, bl = 0;
        int n = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int c = b.getPixel(x, y);
                a += (c >>> 24); r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; bl += c & 0xFF;
                n++;
            }
        }
        if (n == 0) return Color.WHITE;
        return Color.argb(255, (int) (r / n), (int) (g / n), (int) (bl / n));
    }

    /** 两色按比例混合（k=0 取白色，k=1 取原色） */
    private static int mix(int color, int with, float k) {
        int r = (int) (Color.red(color) * k + Color.red(with) * (1 - k));
        int g = (int) (Color.green(color) * k + Color.green(with) * (1 - k));
        int b = (int) (Color.blue(color) * k + Color.blue(with) * (1 - k));
        return Color.argb(255, r, g, b);
    }

    /** 就地把四角裁圆 */
    private static void roundCorners(Bitmap b, int radius) {
        Bitmap mask = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas mc = new Canvas(mask);
        Paint mp = new Paint(Paint.ANTI_ALIAS_FLAG);
        mp.setColor(Color.BLACK);
        mc.drawRoundRect(new RectF(0, 0, b.getWidth(), b.getHeight()), radius, radius, mp);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        new Canvas(b).drawBitmap(mask, 0, 0, p);
        mask.recycle();
    }

    /** 给「文字该用深色还是浅色」提供依据 */
    public static double luminance(Context c) {
        try {
            Bitmap b = build(c);
            if (b == null) return 1.0;
            int avg = averageColor(b);
            b.recycle();
            return (0.299 * Color.red(avg) + 0.587 * Color.green(avg)
                    + 0.114 * Color.blue(avg)) / 255.0;
        } catch (Throwable t) {
            return 1.0;
        }
    }
}
