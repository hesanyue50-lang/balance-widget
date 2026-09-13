package com.minis.balancewidget;

import android.content.Context;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 崩溃捕获。
 *
 * 小组件一旦抛异常被系统杀掉，桌面上就会变成 App 的默认图标 —— 而且 logcat
 * 在不少 ROM 上对 shell 是空的，什么都查不到。这里把堆栈写进诊断日志文件，
 * 至少留下证据。装完之后继续交给系统原本的处理器，行为不变。
 */
public final class CrashGuard {

    private static volatile boolean installed = false;

    private CrashGuard() { }

    public static void install(final Context ctx) {
        if (installed) return;
        installed = true;
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    BalanceFetcher.diag(app, "💥 崩溃 [" + t.getName() + "]\n" + sw.toString());
                } catch (Throwable ignored) { }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
        BalanceFetcher.diag(app, "崩溃捕获已安装");
    }
}
