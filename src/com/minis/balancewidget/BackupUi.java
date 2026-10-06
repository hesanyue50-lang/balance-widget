package com.minis.balancewidget;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * 备份 / 恢复的界面流程（选文件、读写）。
 *
 * 走系统的文件选择器（SAF），而不是自己申请存储权限写死某个目录：
 * 用户想存哪儿就存哪儿（下载目录、网盘同步目录都行），也不用申请额外权限。
 */
public final class BackupUi {

    public static final int REQ_BACKUP = 8801;
    public static final int REQ_RESTORE = 8802;

    private BackupUi() { }

    /** 备份：让用户选保存位置 */
    public static void backup(Activity act) {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(Backup.MIME);
            i.putExtra(Intent.EXTRA_TITLE, Backup.suggestName());
            act.startActivityForResult(i, REQ_BACKUP);
        } catch (Throwable t) {
            toast(act, "打不开保存界面：" + t);
        }
    }

    /** 恢复：让用户挑一个备份文件 */
    public static void restore(Activity act) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");          // 有些文件管理器不认自定义类型，放宽更稳
            act.startActivityForResult(i, REQ_RESTORE);
        } catch (Throwable t) {
            toast(act, "打不开选择界面：" + t);
        }
    }

    /** 在 Activity.onActivityResult 里调；返回 true 表示这次结果被我们处理了 */
    public static boolean onResult(final Activity act, int req, int res, Intent data) {
        if (req != REQ_BACKUP && req != REQ_RESTORE) return false;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        final Uri u = data.getData();
        final boolean isBackup = (req == REQ_BACKUP);
        new Thread(new Runnable() {
            public void run() {
                if (isBackup) {
                    String json = Backup.exportJson(act);
                    if (json.length() == 0) {
                        ui(act, "导出失败（数据为空）");
                        return;
                    }
                    String err = writeText(act, u, json);
                    ui(act, err == null
                            ? "已备份（" + (json.length() / 1024) + " KB）\n以后点开这个文件即可恢复"
                            : "写入失败：" + err);
                } else {
                    String text = readText(act, u);
                    if (text == null) {
                        ui(act, "读不到文件内容");
                        return;
                    }
                    ui(act, Backup.importJson(act, text));
                }
            }
        }).start();
        return true;
    }

    /**
     * 外部"点开备份文件"进来时调用（Manifest 里注册了 .apibak 的 VIEW）。
     * @return true 表示这次 Intent 是备份文件、已接手
     */
    public static boolean handleViewIntent(final Activity act, Intent it) {
        if (it == null || !Intent.ACTION_VIEW.equals(it.getAction())) return false;
        final Uri u = it.getData();
        if (u == null) return false;
        String last = u.getLastPathSegment();
        /* 只认自己的备份文件 —— 万一系统把别的文件也派发过来，别乱恢复 */
        if (last == null || !last.toLowerCase().endsWith("." + Backup.EXT)) return false;

        ui(act, "正在从备份恢复…");
        new Thread(new Runnable() {
            public void run() {
                String text = readText(act, u);
                if (text == null) {
                    ui(act, "读不到备份文件");
                    return;
                }
                ui(act, Backup.importJson(act, text));
            }
        }).start();
        return true;
    }

    // ---------- 文件读写 ----------

    private static String writeText(Activity act, Uri u, String text) {
        java.io.OutputStream os = null;
        try {
            os = act.getContentResolver().openOutputStream(u, "wt");
            if (os == null) return "无法打开输出流";
            os.write(text.getBytes("UTF-8"));
            os.flush();
            return null;
        } catch (Throwable t) {
            return String.valueOf(t.getMessage());
        } finally {
            try { if (os != null) os.close(); } catch (Throwable ig) { }
        }
    }

    private static String readText(Activity act, Uri u) {
        java.io.InputStream is = null;
        try {
            is = act.getContentResolver().openInputStream(u);
            if (is == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
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

    private static void ui(final Activity act, final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                if (act == null || act.isFinishing()) return;
                Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void toast(Activity act, String s) {
        try { Toast.makeText(act, s, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) { }
    }
}
