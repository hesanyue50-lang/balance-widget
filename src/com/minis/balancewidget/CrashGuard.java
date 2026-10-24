package com.minis.balancewidget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃捕获 + 日志落盘 + 通知用户。
 *
 * 旧版只把堆栈写进 diag.log —— 那是 App 私有目录，用户根本拿不到，
 * 结果就是"开发者要日志、用户给不出"。新行为：
 *
 * 1. 堆栈写入用户**能拿到**的地方：设置里自定义的文件夹，没设就 Download
 * 2. 发一条通知：告诉用户崩了、日志在哪、可以把日志发给开发者
 * 3. 保留最近 N 份，防堆积
 * 4. 落盘后交给系统原处理器 —— 不吞崩溃，行为不变
 *
 * 为什么不用 FileProvider / MediaStore：崩溃路径要尽可能"什么都能坏"，
 * 依赖越少越好。直接 new File() 写 Download 在 API 29+ 需要
 * legacy 外存标记，本项目 targetSdk 老且未启用分区存储，正好可用。
 */
public final class CrashGuard {

    private static volatile boolean installed = false;
    private static final String CHANNEL = "crash_report";
    private static final int MAX_FILES = 10;

    private CrashGuard() { }

    /**
     * 用户自定义的日志保存目录（设置里可改；空 = Download）。
     *
     * ⚠️ 不能只在 install() 时读一次 —— 用户在设置里改了目录后，
     * 进程还活着，内存里的旧值会把日志继续写进老地方。
     * 所以 App 每次进来都调 refreshDir() 重读。
     */
    private static volatile String customDir = "";

    public static void setCustomDir(String dir) {
        customDir = dir == null ? "" : dir.trim();
    }

    /** 每次打开 App 时重读一次设置 —— 保证路径改动立刻生效 */
    public static void refreshDir(Context c) {
        try {
            customDir = c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE)
                    .getString("crash_dir", "");
        } catch (Throwable ignored) { }
    }

    /** 日志目录：设置里自定义的优先，否则 Download */
    public static File logDir(Context c) {
        String cd = customDir;
        if (cd.length() > 0) {
            File f = new File(cd);
            if (f.exists() || f.mkdirs()) return f;
        }
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    /** 给通知/界面用的目录描述（有真实路径给路径，SAF 就说"你选择的文件夹"） */
    public static String dirDesc(Context c) {
        try {
            String tree = c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE)
                    .getString("crash_dir_uri", "");
            if (tree.length() > 0) {
                /* 树 URI 里能解出显示名（最后一段），比笼统一句"所选文件夹"有用 */
                String docId = android.provider.DocumentsContract.getTreeDocumentId(
                        android.net.Uri.parse(tree));
                String[] ps = docId.split(":");
                String tail = ps.length > 1 ? ps[ps.length - 1] : docId;
                return "你选择的文件夹（" + tail + "）";
            }
        } catch (Throwable ignored) { }
        return logDir(c).getAbsolutePath();
    }

    public static void install(final Context ctx) {
        if (installed) return;
        installed = true;

        /* 进程起来时先读一次（此后靠 refreshDir 跟随改动） */
        refreshDir(ctx);

        /* 通知渠道**现在就建**，别等崩溃时才建 ——
           崩溃路径上任何一步都要尽量少做事；而且有些 ROM 对"崩溃瞬间建的渠道"
           第一次通知会吞掉（渠道注册是异步生效的）。 */
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                        "崩溃报告", NotificationManager.IMPORTANCE_HIGH));
            }
        } catch (Throwable ignored) { }

        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String stack = sw.toString();

                    /* ① 诊断日志照写（自家用） */
                    BalanceFetcher.diagNow(app, "💥 崩溃 [" + t.getName() + "]\n" + stack);

                    /* ② 用户可取的日志文件 + ③ 通知（要带上文件名 ——
                       MediaStore 路径拿不到真实 File，通知里至少得知道叫什么） */
                    String fname = "BW崩溃-" + new SimpleDateFormat("MMdd-HHmmss", Locale.US)
                            .format(new Date()) + "-"
                            + e.getClass().getSimpleName().replaceAll("[^A-Za-z0-9]", "") + ".log";
                    File f = saveLog(app, t, e, stack, fname);

                    /* ④ 给下次启动的提示页留位置信息（SharedPreferences 同步写，
                       保证崩溃前一定落盘）。通知有没有收到都无所谓了 ——
                       下次打开 App 第一个看到的就是它。 */
                    try {
                        String where = f != null ? f.getAbsolutePath() : dirDesc(app) + "/" + fname;
                        app.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE)
                                .edit()
                                .putString("last_crash_where", where)
                                .putString("last_crash_stack",
                                        stack.length() > 900 ? stack.substring(0, 900) : stack)
                                .putBoolean("crash_pending", true)
                                .commit();        // 崩溃路径必须同步写，apply 可能没机会落盘
                    } catch (Throwable ig) { }

                    notifyUser(app, f, fname);
                } catch (Throwable ignored) { }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
        BalanceFetcher.diagNow(app, "崩溃捕获已安装（日志目录："
                + logDir(app).getAbsolutePath() + "）");
    }

    /**
     * 写日志文件；返回给用户看的路径（失败返回 null，不再抛）。
     *
     * 两条路：
     * - 用户自定义目录 → 直接 File 写（用户在文件选择器里选的，App 对它有权）
     * - 默认 Download → **必须走 MediaStore**（targetSdk 29+ 直接 File 写
     *   Download 会 EACCES —— 那正是这个功能最要紧的场景，不能失败）
     */
    private static File saveLog(Context c, Thread t, Throwable e, String stack, String name) {
        String content = buildLog(c, t, e, stack);

        /* ① 用户自定义目录：SAF 树 URI（用户在文件选择器里选的）优先 */
        try {
            String tree = c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE)
                    .getString("crash_dir_uri", "");
            if (tree.length() > 0) {
                android.net.Uri doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        android.net.Uri.parse(tree),
                        android.provider.DocumentsContract.getTreeDocumentId(
                                android.net.Uri.parse(tree)));
                android.net.Uri file = android.provider.DocumentsContract.createDocument(
                        c.getContentResolver(), doc, "text/plain", name);
                if (file != null) {
                    java.io.OutputStream os = c.getContentResolver().openOutputStream(file);
                    os.write(content.getBytes("UTF-8"));
                    os.close();
                    return null;    // 无真实路径，通知只报目录
                }
            }
        } catch (Throwable ig) { }

        /* ①b 明文目录（旧设置）：直接写 */
        String cd = customDir;
        if (cd.length() > 0) {
            try {
                File dir = new File(cd);
                if (dir.exists() || dir.mkdirs()) {
                    File out = new File(dir, name);
                    FileWriter w = new FileWriter(out);
                    w.write(content);
                    w.close();
                    prune(dir);
                    return out;
                }
            } catch (Throwable ig) { }
        }

        /* ② Download：走 MediaStore（无需存储权限） */
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
            android.net.Uri uri = c.getContentResolver().insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return null;
            java.io.OutputStream os = c.getContentResolver().openOutputStream(uri);
            os.write(content.getBytes("UTF-8"));
            os.close();
            BalanceFetcher.diag(c, "崩溃日志已写入 Download（MediaStore）");
            return null;    // MediaStore 没有真实路径；通知里只报目录
        } catch (Throwable ig2) {
            BalanceFetcher.diag(c, "崩溃日志写入失败：" + ig2);
            return null;
        }
    }

    private static String buildLog(Context c, Thread t, Throwable e, String stack) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("API 余额小组件 崩溃日志\n");
            sb.append("时间：").append(new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
            sb.append("版本：").append(versionName(c)).append('\n');
            sb.append("线程：").append(t.getName()).append('\n');
            sb.append("设备：").append(android.os.Build.MANUFACTURER).append(' ')
                    .append(android.os.Build.MODEL).append("  Android ")
                    .append(android.os.Build.VERSION.RELEASE).append('\n');
            sb.append("\n===== 崩溃堆栈 =====\n").append(stack).append('\n');
            try {
                String recent = BalanceFetcher.recentDiag(c, 120);
                if (recent != null && recent.length() > 0) {
                    sb.append("\n===== 崩溃前诊断日志（最后 120 行）=====\n").append(recent);
                }
            } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    private static String versionName(Context c) {
        try {
            return c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 只留最近 MAX_FILES 份 */
    private static void prune(File dir) {
        try {
            File[] fs = dir.listFiles();
            if (fs == null || fs.length <= MAX_FILES) return;
            java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            for (int i = MAX_FILES; i < fs.length; i++) {
                if (fs[i].getName().startsWith("BW崩溃-")) fs[i].delete();
            }
        } catch (Throwable ignored) { }
    }

    /**
     * 通知用户：崩了、日志在哪、可以发给开发者。
     *
     * 路径显示分三种情况，都要明确：
     * - 自定义明文目录 → 完整文件路径
     * - SAF 文件夹     → "你选择的文件夹（xxx）/文件名"
     * - Download       → Download/BW崩溃-xxx.log（MediaStore 写入无真实路径，报相对位置）
     */
    private static void notifyUser(final Context c, final File f, final String fileName) {
        try {
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            /* 通知权限没给时必须留痕 —— 否则"用户没收到通知"永远查不出原因 */
            if (!nm.areNotificationsEnabled()) {
                BalanceFetcher.diag(c, "崩溃通知未发出：应用没有通知权限"
                        + "（系统设置里给本应用开通知后，下次崩溃就能收到）");
                return;
            }
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "崩溃报告", NotificationManager.IMPORTANCE_HIGH));

            String where;
            if (f != null) {
                where = f.getAbsolutePath();
            } else {
                where = dirDesc(c) + "/" + fileName;
            }
            String text = "日志已保存到：\n" + where
                    + "\n\n打开 App → 设置 → 💥 崩溃日志 也可以查看，把日志发给开发者即可修复";

            Notification.Builder b;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(c, CHANNEL);
            } else {
                b = new Notification.Builder(c);
            }
            b.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("应用意外退出，日志已保存")
                    .setContentText("日志位置：" + where)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true);

            /* 点通知 = 打开 App（设置里能直接看最近日志并复制 ——
               比"打开文件夹"可靠：文件管理器对 file:// URI 越来越不友好） */
            try {
                Intent i = new Intent(c, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                b.setContentIntent(PendingIntent.getActivity(c, 9001, i,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | (android.os.Build.VERSION.SDK_INT >= 23
                                        ? PendingIntent.FLAG_IMMUTABLE : 0)));
            } catch (Throwable ignored) { }

            nm.notify(9002, b.build());
        } catch (Throwable ignored) { }
    }
}
