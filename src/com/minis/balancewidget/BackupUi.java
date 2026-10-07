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
            /* 拿到持久权限，以后才可能用同一个 URI 去读/删这个文件 */
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
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
            /* 尽量落到上次备份所在的文件夹 —— 不给这个的话，
               系统默认停在"最近使用"，用户得自己一层层翻回备份的位置。
               用字符串常量而不是 DocumentsContract.EXTRA_INITIAL_URI，
               免得在低版本上引用到不存在的字段。 */
            String last = BackupStore.lastExportUri(act);
            if (last != null) {
                try {
                    i.putExtra("android.provider.extra.INITIAL_URI", Uri.parse(last));
                } catch (Throwable ignored) { }
            }
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
                    final String disp = displayName(act, u);
                    if (err == null) {
                        takePersist(act, u);
                        /* 记下这份备份落到磁盘上的哪个文件 ——
                           以后用户删记录时，这份文件也要一并处理掉 */
                        BackupStore.save(act, json, u.toString(), disp);
                    }
                    /* 备份是跳到系统文件选择器完成的，回来时设置面板还是旧画面，
                       必须主动通知它重画一次，否则用户看到的是"0 份" */
                    if (err == null) notifyListChanged();
                    ui(act, err == null
                            ? "已备份" + (disp == null ? "" : "：" + disp)
                              + "（" + (json.length() / 1024) + " KB）\n"
                              + "在设置 → 备份与恢复 里可以恢复或删除"
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
     * 从本机备份记录恢复（私有目录里的那份，不用挑文件）。
     * @param onDone 恢复完成后回调（在 UI 线程），用于刷新界面
     */
    public static void restoreLocal(final Activity act, final String name,
                                    final Runnable onDone) {
        ui(act, "正在恢复…");
        new Thread(new Runnable() {
            public void run() {
                final String text = BackupStore.read(act, name);
                if (text == null) {
                    ui(act, "这条备份读不出来了（可能已损坏）");
                    if (onDone != null) post(act, onDone);
                    return;
                }
                ui(act, Backup.importJson(act, text));
                if (onDone != null) post(act, onDone);
            }
        }).start();
    }

    /**
     * 删除一条备份：本机记录 + 它导出到磁盘的那份文件。
     * 文件删不动时（分区存储下很常见），引导用户自己去文件夹删。
     */
    public static void deleteLocal(final Activity act, final BackupStore.Item it,
                                   final Runnable onDone) {
        new Thread(new Runnable() {
            public void run() {
                final int r = BackupStore.deleteBoth(act, it);
                if (r == BackupStore.DEL_OK) {
                    ui(act, it.uri == null ? "已删除这条备份" : "已删除这条备份及其文件");
                } else if (r == BackupStore.DEL_NO_PERM) {
                    post(act, new Runnable() {
                        public void run() { askDeleteExternal(act, it); }
                    });
                } else {
                    ui(act, "删除失败");
                }
                if (onDone != null) post(act, onDone);
            }
        }).start();
    }

    /** 文件删不掉时的引导：告诉用户它叫什么，并把他带到文件夹去 */
    private static void askDeleteExternal(final Activity act, final BackupStore.Item it) {
        if (act == null || act.isFinishing()) return;
        String nm = it.fileLabel() == null ? "备份文件" : it.fileLabel();
        new android.app.AlertDialog.Builder(act)
                .setTitle("本机记录已删除")
                .setMessage("但它导出到文件夹的那份文件删不掉"
                        + "（系统不允许应用直接删，或文件已被移动）。\n\n"
                        + "文件：" + nm
                        + "\n\n要现在去文件夹里手动删除吗？")
                .setPositiveButton("去文件夹",
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                if (!BackupStore.openExternal(act, it.uri)) {
                                    toast(act, "打不开文件管理器，请手动到文件夹里删除");
                                }
                            }
                        })
                .setNegativeButton("稍后", null)
                .show();
    }

    /** 持久化 URI 权限：不申请的话，下次启动就再也访问不到这个文件了 */
    private static void takePersist(Activity act, Uri u) {
        try {
            act.getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable ignored) { }
    }

    /** 从 URI 取用户看得见的文件名（SAF 的 _display_name） */
    private static String displayName(Activity act, Uri u) {
        android.database.Cursor cur = null;
        try {
            cur = act.getContentResolver().query(u,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (cur != null && cur.moveToFirst()) {
                String s = cur.getString(0);
                if (s != null && s.length() > 0) return s;
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (cur != null) cur.close(); } catch (Throwable ig) { }
        }
        return u.getLastPathSegment();
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

    /** 把一个动作丢回 UI 线程（Activity 已结束时忽略） */
    private static void post(final Activity act, final Runnable r) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                if (act == null || act.isFinishing()) return;
                try { r.run(); } catch (Throwable ignored) { }
            }
        });
    }

    /** 本机备份有增减 → 让正开着的设置面板重画列表 */
    private static void notifyListChanged() {
        final SettingsBinder b = SettingsBinder.active;
        if (b == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                try { b.refreshBackups(); } catch (Throwable ignored) { }
            }
        });
    }

    private static void toast(Activity act, String s) {
        try { Toast.makeText(act, s, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) { }
    }
}
