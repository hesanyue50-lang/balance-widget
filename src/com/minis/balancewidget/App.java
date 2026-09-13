package com.minis.balancewidget;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        /* 进程一起来就装上 —— 小组件崩溃后桌面会显示成 App 默认图标，
           必须留下堆栈才查得动（这台机器的 logcat 对 shell 是空的） */
        CrashGuard.install(this);
    }
}
