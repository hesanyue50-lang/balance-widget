package com.minis.balancewidget;

import android.app.Application;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        /* 进程一起来就装上 —— 小组件崩溃后桌面会显示成 App 默认图标，
           必须留下堆栈才查得动（这台机器的 logcat 对 shell 是空的） */
        CrashGuard.install(this);

        /* 上次崩溃了？在**任何**可能崩的组件初始化之前，先把提示页顶出来。
           做法：这里只置一个标记，由启动页（MainActivity 在 setContentView 前）
           检查并跳转 —— Application 里直接 startActivity 有 ROM 不认，
           而且会跟 launcher 的启动动画打架。 */
        try {
            if (getSharedPreferences(BalanceFetcher.PREFS, MODE_PRIVATE)
                    .getBoolean("crash_pending", false)) {
                pendingCrashNotice = true;
            }
        } catch (Throwable ignored) { }
    }

    /** MainActivity 在 setContentView 之前检查：true = 先跳崩溃提示页去 */
    public static volatile boolean pendingCrashNotice = false;

    /** 消费标记（MainActivity 用） */
    public static boolean takePendingCrashNotice() {
        boolean v = pendingCrashNotice;
        pendingCrashNotice = false;
        return v;
    }
}
