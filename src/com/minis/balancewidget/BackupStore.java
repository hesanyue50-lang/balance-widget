package com.minis.balancewidget;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 本机备份记录：把每次备份的内容再存一份在应用私有目录里，
 * 并**记住它被导出到了磁盘上的哪个文件**。
 *
 * <b>为什么要记位置</b>：备份真正落地的形式是用户自己选的那个文件
 * （下载目录、网盘同步目录…）。如果这里只记"备份过 3 次"，用户删记录时
 * 那份文件还躺在文件夹里，成了没人管的垃圾；反过来，用户在文件管理器里
 * 把文件删了，这里却还显示着一条点不开的记录。
 * 所以每条记录都带上导出 URI，删除时两边一起处理。
 *
 * <b>删不掉怎么办</b>：Android 的分区存储下，应用对 SAF 拿到的文件
 * 通常只有读/写权限、没有删除权限（而且用户可能在文件管理器里改过位置）。
 * 这种情况下**不假装成功**，而是把结果如实返回给界面，由界面引导用户
 * 去文件夹里手动删。
 */
public final class BackupStore {

    /** 最多保留的份数（超出自动删最旧） */
    public static final int KEEP_MAX = 20;

    private static final String DIR = "backups";
    private static final String PREFIX = "backup-";
    private static final String SUFFIX = "." + Backup.EXT;
    private static final String META = ".meta";

    /** 删除结果：本机与外部文件都删掉了 */
    public static final int DEL_OK = 0;
    /** 本机删掉了，但外部文件删不动（没权限 / 已被移动）——需要引导用户 */
    public static final int DEL_NO_PERM = 1;
    /** 本机那条都没删掉 */
    public static final int DEL_FAIL = 2;

    private BackupStore() { }

    /** 一条备份记录 */
    public static final class Item {
        public final File file;
        public final String name;
        public final long time;
        public final long size;
        /** 导出到的位置（SAF URI）；没有则为 null */
        public final String uri;
        /** 导出时的文件名（给用户看的） */
        public final String displayName;

        Item(File f, String uri, String displayName) {
            this.file = f;
            this.name = f.getName();
            this.time = f.lastModified();
            this.size = f.length();
            this.uri = uri;
            this.displayName = displayName;
        }

        /** 形如 2026-10-07 15:04 */
        public String label() {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                    java.util.Locale.US).format(new java.util.Date(time));
        }

        public String sizeText() {
            long kb = size / 1024;
            if (kb < 1) return size + " B";
            if (kb < 1024) return kb + " KB";
            return String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0);
        }

        /** 有导出文件的那条，副标题显示文件名 */
        public String fileLabel() {
            return displayName == null || displayName.length() == 0 ? null : displayName;
        }
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File metaFile(File dataFile) {
        String n = dataFile.getName();
        String base = n.endsWith(SUFFIX) ? n.substring(0, n.length() - SUFFIX.length()) : n;
        return new File(dataFile.getParentFile(), base + META);
    }

    /**
     * 存一份新记录。
     * @param uri 导出到的位置（SAF URI），可为 null（例如以后从别处补记）
     * @param displayName 导出文件名，可为 null
     * @return 成功返回记录名，失败返回 null
     */
    public static String save(Context c, String json, String uri, String displayName) {
        if (json == null || json.length() == 0) return null;
        try {
            String name = PREFIX + new java.text.SimpleDateFormat(
                    "yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date()) + SUFFIX;
            File f = new File(dir(c), name);
            FileOutputStream os = new FileOutputStream(f);
            try {
                os.write(json.getBytes("UTF-8"));
                os.flush();
            } finally { os.close(); }

            if (uri != null || displayName != null) {
                JSONObject meta = new JSONObject();
                if (uri != null) meta.put("uri", uri);
                if (displayName != null) meta.put("name", displayName);
                FileOutputStream ms = new FileOutputStream(metaFile(f));
                try {
                    ms.write(meta.toString().getBytes("UTF-8"));
                    ms.flush();
                } finally { ms.close(); }
            }

            prune(c);
            return name;
        } catch (Throwable t) {
            BalanceFetcher.diag(c, "本机备份记录写入失败：" + t);
            return null;
        }
    }

    /** 全部记录，新的在前 */
    public static List<Item> list(Context c) {
        List<Item> out = new ArrayList<Item>();
        try {
            File[] fs = dir(c).listFiles();
            if (fs == null) return out;
            for (File f : fs) {
                if (!f.isFile() || !f.getName().endsWith(SUFFIX)) continue;
                String uri = null, disp = null;
                try {
                    File mf = metaFile(f);
                    if (mf.exists()) {
                        byte[] buf = new byte[(int) mf.length()];
                        FileInputStream in = new FileInputStream(mf);
                        try { in.read(buf); } finally { in.close(); }
                        JSONObject m = new JSONObject(new String(buf, "UTF-8"));
                        uri = m.optString("uri", null);
                        disp = m.optString("name", null);
                    }
                } catch (Throwable ignored) { }
                out.add(new Item(f, uri, disp));
            }
            Collections.sort(out, new Comparator<Item>() {
                public int compare(Item a, Item b) { return Long.compare(b.time, a.time); }
            });
        } catch (Throwable t) {
            BalanceFetcher.diag(c, "读取本机备份列表失败：" + t);
        }
        return out;
    }

    /** 只删本机这一份（连同 meta）；返回是否删掉了 */
    public static boolean delete(Context c, String name) {
        try {
            if (name == null || !isSafe(name)) return false;
            File f = new File(dir(c), name);
            if (!f.exists() || !f.isFile()) return false;
            metaFile(f).delete();
            return f.delete();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 删除一条记录：本机副本 + 它导出到磁盘的那份文件。
     *
     * @return {@link #DEL_OK} / {@link #DEL_NO_PERM} / {@link #DEL_FAIL}
     */
    public static int deleteBoth(Context c, Item it) {
        if (it == null) return DEL_FAIL;
        boolean local = delete(c, it.name);
        if (!local) return DEL_FAIL;
        if (it.uri == null) return DEL_OK;
        return deleteExternal(c, it.uri) ? DEL_OK : DEL_NO_PERM;
    }

    /**
     * 尝试删掉导出到磁盘的那份文件。
     *
     * 分区存储下大多数情况**删不动**（SAF 通常不给 DELETE 权限，
     * 或者文件已经被用户挪走），这是正常的，返回 false 让界面去引导用户。
     */
    public static boolean deleteExternal(Context c, String uriStr) {
        if (uriStr == null || uriStr.length() == 0) return true;
        try {
            Uri u = Uri.parse(uriStr);
            return android.provider.DocumentsContract.deleteDocument(
                    c.getContentResolver(), u);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 删除全部记录（本机 + 能删掉的外部文件）；返回删掉的条数 */
    public static int deleteAll(Context c) {
        int n = 0;
        for (Item it : list(c)) if (deleteBoth(c, it) != DEL_FAIL) n++;
        return n;
    }

    /** 这条记录还能不能用（0 字节 = 当初就没写成功） */
    public static boolean isUsable(Item it) {
        return it != null && it.size > 0;
    }

    /** 读出某条记录的内容；失败返回 null */
    public static String read(Context c, String name) {
        InputStream is = null;
        try {
            if (name == null || !isSafe(name)) return null;
            File f = new File(dir(c), name);
            if (!f.exists()) return null;
            is = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ig) { }
        }
    }

    /**
     * 把用户带到那份文件所在的位置。
     *
     * 系统没有"打开某个文件夹"的标准 Intent，退而求其次：
     * 用 ACTION_VIEW 打开那个文件 —— 文件管理器会跳到它所在的目录
     * （或者用能预览它的应用打开），用户就能顺手删掉。
     *
     * @return 是否成功发起了跳转
     */
    public static boolean openExternal(Context c, String uriStr) {
        if (uriStr == null || uriStr.length() == 0) return false;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(uriStr));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 最近一次备份导出到的位置。
     *
     * 「从备份文件恢复」时用它当文件选择器的起始目录 ——
     * 不给的话系统默认停在"最近使用"，用户还得自己一层层翻回备份所在的文件夹。
     */
    public static String lastExportUri(Context c) {
        for (Item it : list(c)) {
            if (it.uri != null && it.uri.length() > 0) return it.uri;
        }
        return null;
    }

    /** 只保留最近 KEEP_MAX 份 */
    private static void prune(Context c) {        List<Item> all = list(c);
        for (int i = KEEP_MAX; i < all.size(); i++) delete(c, all.get(i).name);
    }

    /** 防目录穿越：名字必须是本模块自己生成的那种 */
    private static boolean isSafe(String name) {
        return name.startsWith(PREFIX) && name.endsWith(SUFFIX)
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0
                && !name.contains("..");
    }

    /** 目录里所有记录的总字节数 */
    public static long totalSize(Context c) {
        long n = 0;
        for (Item it : list(c)) n += it.size;
        return n;
    }
}
