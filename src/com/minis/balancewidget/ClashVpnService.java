package com.minis.balancewidget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * 系统级 VPN（真正意义上的全局代理）。
 *
 * ## 和「应用内模式」的区别
 *
 *   应用内模式：本应用自己把请求发到 127.0.0.1:7890（本地 HTTP 代理）。
 *              别的应用、系统流量完全不受影响。够用、轻量、不用授权。
 *
 *   本服务（系统全局）：向系统注册一条 VPN，**接管整机流量** ——
 *              所有应用的网络都会经过我们建出来的 tun 设备，
 *              再交给 mihomo 用 gvisor 协议栈处理。
 *
 * ## 为什么要自己建 tun
 *
 * mihomo 会用 gvisor 协议栈**在用户态**完成 TCP/IP 处理，但它需要一个
 * 「与系统对接的入口」，也就是 tun 设备。而创建 tun 需要 CAP_NET_ADMIN ——
 * 只有 VpnService 能合法拿到（这也是 Android 上所有 VPN 客户端的通行做法）。
 *
 * 所以分工是：**本服务负责建 tun 并交给内核，内核负责所有协议细节**。
 *
 * ## fd 怎么传进子进程（关键细节）
 *
 * Java 通过 ProcessBuilder 起的子进程默认只继承 0/1/2 三个描述符，
 * 其余都带 FD_CLOEXEC 会被关掉。而 tun 的 fd 必须传到内核手里，于是：
 *
 *   `ParcelFileDescriptor.detachFd()` —— **它会顺带清掉 FD_CLOEXEC**，
 *   返回的 fd 既能被子进程继承、编号也不会变。
 *   配置里写 `tun.file-descriptor: N`，同时再用环境变量 CLASH_TUN_FD 兜一道，
 *   两种内核版本都能认。
 *
 * 代价是要自己管这个 fd 的生命周期：服务停止时必须 os.close()，
 * 否则 tun 设备不会释放（下一次启动会失败）。
 */
public class ClashVpnService extends VpnService {

    public static final String ACTION_START = "com.minis.balancewidget.VPN_START";
    public static final String ACTION_STOP = "com.minis.balancewidget.VPN_STOP";

    private static final String CH_ID = "clash_vpn";
    private static final int NOTI_ID = 0x7A51;

    /** tun 的虚拟网段：用 CGNAT 保留段，和常见的家庭/公司内网不冲突 */
    private static final String TUN_ADDR = "172.19.0.1";
    private static final int TUN_PREFIX = 30;
    private static final String TUN_DNS = "172.19.0.2";
    private static final int TUN_MTU = 8500;      // 对接回环，不需要受物理 MTU 限制

    /** 当前持有的 tun fd（-1 = 没有）。停止时负责关闭。 */
    private static int tunFd = -1;
    private static boolean running;

    private ParcelFileDescriptor tunPfd;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }
        startVpn();
        return START_STICKY;
    }

    // ---------------- 启动 ----------------

    private void startVpn() {
        if (running) {
            BalanceFetcher.diag(this, "VPN 已在运行");
            return;
        }
        if (PrepareNotDone()) {
            BalanceFetcher.diag(this, "VPN 未获授权，放弃启动");
            stopSelf();
            return;
        }

        SubStore.Sub sub = SubStore.active(this);
        if (sub == null || !sub.ready()) {
            BalanceFetcher.diag(this, "没有可用的订阅配置，VPN 不启动");
            stopSelf();
            return;
        }
        if (!Clash.available(this)) {
            BalanceFetcher.diag(this, "内核不可用，VPN 不启动");
            stopSelf();
            return;
        }

        startForegroundSafely();

        /* ⚠️ 必须放到后台线程。
           onStartCommand 是在**主线程**跑的，而下面这段里有两处会阻塞：
             - VpnService.Builder.establish()：要等系统把 tun 建好；
             - Clash.start()：起子进程后会轮询控制端口，最长等 8 秒。
           以前直接在主线程做，用户点「开启系统全局」就是明显的卡一下
           （界面完全没响应）。现在主线程只负责启动线程，立刻返回。 */
        new Thread(new Runnable() {
            public void run() { establishAndStart(); }
        }, "clash-vpn-start").start();
    }

    /** 真正耗时的部分：建 tun + 拉起内核。全程在后台线程。 */
    private void establishAndStart() {
        final SubStore.Sub sub = SubStore.active(this);
        if (sub == null || !sub.ready()) { stopSelf(); return; }
        try {
            /* ① 建 tun。setBlocking(false) 让读操作可被打断，gvisor 需要非阻塞语义。
                  两个「不」很重要：
                    - 不设 addRoute 全量路由的话就不是全局；
                    - 但**必须排除自己的应用**，否则本应用发往 127.0.0.1:7890 的
                      代理请求会被自己的 tun 再抓一遍，形成回环。 */
            Builder b = new Builder()
                    .setSession("API 余额")
                    .setMtu(TUN_MTU)
                    .addAddress(TUN_ADDR, TUN_PREFIX)
                    .addDnsServer(TUN_DNS)
                    .addRoute("0.0.0.0", 0)          // IPv4 全部走 VPN
                    .setBlocking(false);

            if (Build.VERSION.SDK_INT >= 29) {
                try { b.setMetered(false); } catch (Throwable ig) { }
            }
            /* 排除自己：本应用仍旧走本地 HTTP 代理那条通路，避免自我循环 */
            try { b.addDisallowedApplication(getPackageName()); } catch (Throwable ig) { }

            tunPfd = b.establish();
            if (tunPfd == null) {
                BalanceFetcher.diag(this, "建立 tun 失败（establish 返回 null）");
                stopSelf();
                return;
            }

            /* ② 取 fd 并清掉 FD_CLOEXEC，让 mihomo 能继承到它 */
            tunFd = tunPfd.detachFd();

            /* ③ 生成带 tun 段的配置并启动内核 */
            String cfg = Clash.buildTunConfig(sub.yaml, tunFd);
            String err = Clash.startWithFd(this, cfg, tunFd);
            if (err != null) {
                BalanceFetcher.diag(this, "内核启动失败：" + err);
                stopVpn();
                stopSelf();
                return;
            }

            running = true;
            BalanceFetcher.diag(this, "系统级 VPN 已启动，tun fd=" + tunFd);
            updateNotification("已接管全部应用流量");
        } catch (Throwable t) {
            BalanceFetcher.diag(this, "VPN 启动异常 " + t);
            stopVpn();
            stopSelf();
        }
    }

    /** prepare() 的授权状态由外部（MainActivity）先确认过，这里只做防御性判断 */
    private boolean PrepareNotDone() {
        try {
            Intent i = VpnService.prepare(this);
            if (i != null) return true;      // 还有待确认的授权
        } catch (Throwable ignored) { }
        return false;
    }

    // ---------------- 停止 ----------------

    private void stopVpn() {
        running = false;
        try {
            Clash.stop(this);
        } catch (Throwable ignored) { }

        /* 关 fd：detachFd 之后所有权在我们手上，不关的话 tun 设备不会释放，
           下次 establish 会失败。
           注意 Os.close 收的是 FileDescriptor 对象，这里只有一个 int，
           所以用 ParcelFileDescriptor.adoptFd 包一层再 close。 */
        try {
            if (tunFd >= 0) {
                ParcelFileDescriptor p = ParcelFileDescriptor.adoptFd(tunFd);
                p.close();
                tunFd = -1;
            }
        } catch (Throwable t) {
            BalanceFetcher.diag(this, "关闭 tun fd 失败 " + t);
        }
        try {
            if (tunPfd != null) {
                tunPfd.close();
                tunPfd = null;
            }
        } catch (Throwable ignored) { }

        try {
            stopForeground(true);
        } catch (Throwable ignored) { }
        BalanceFetcher.diag(this, "系统级 VPN 已停止");
    }

    @Override
    public void onRevoke() {
        /* 用户在系统里撤销了 VPN 授权（或别的 VPN 抢占了）：必须干净退出 */
        BalanceFetcher.diag(this, "VPN 授权被撤销，自动停止");
        stopVpn();
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    // ---------------- 前台通知 ----------------

    private void startForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel(CH_ID) == null) {
                    NotificationChannel ch = new NotificationChannel(CH_ID, "网络加速",
                            NotificationManager.IMPORTANCE_LOW);
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            }
            Notification.Builder nb = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(this, CH_ID)
                    : new Notification.Builder(this);
            nb.setContentTitle("网络加速运行中")
              .setContentText("已接管全部应用流量")
              .setSmallIcon(R.drawable.ic_notify)
              .setOngoing(true);

            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            nb.setContentIntent(pi);

            startForeground(NOTI_ID, nb.build());
        } catch (Throwable t) {
            BalanceFetcher.diag(this, "前台通知创建失败 " + t);
        }
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder nb = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(this, CH_ID)
                    : new Notification.Builder(this);
            nb.setContentTitle("网络加速运行中")
              .setContentText(text)
              .setSmallIcon(R.drawable.ic_notify)
              .setOngoing(true);
            nm.notify(NOTI_ID, nb.build());
        } catch (Throwable ignored) { }
    }

    // ---------------- 对外控制 ----------------

    public static void start(Context c) {
        Intent i = new Intent(c, ClashVpnService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        Intent i = new Intent(c, ClashVpnService.class);
        i.setAction(ACTION_STOP);
        try {
            c.startService(i);
        } catch (Throwable t) {
            BalanceFetcher.diag(c, "停止 VPN 服务失败 " + t);
        }
    }

    /** 系统是否已授权（false = 需要先弹授权框让用户确认） */
    public static boolean authorized(Context c) {
        try {
            return VpnService.prepare(c) == null;
        } catch (Throwable t) {
            return false;
        }
    }
}
