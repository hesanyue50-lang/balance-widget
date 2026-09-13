package com.minis.balancewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * 通知权限引导。
 *
 * 版本兼容：
 *   Android 13+ (API 33)  首次需运行时权限 POST_NOTIFICATIONS
 *   Android 8~12          无需运行时权限，但用户可能在系统设置里关掉通知
 *   Android 8 以下        通知默认开启，仍做一次可用性检查
 *
 * 设计要点：
 *   1. 系统权限弹窗被拒绝一次后就不再出现，所以拒绝后必须有二次引导
 *   2. 只在 Activity 可见时调用（onResume 系列），避免弹窗盖在未初始化的界面上
 *   3. 已询问过且未获授权时不再自动弹（用户看过就好），
 *      但设置页保留一个"通知已关闭"入口供用户主动开启
 */
public class NotifyPermission {

    public interface Callback {
        void onResult(boolean granted);
    }

    private static final Callback NONE = new Callback() {
        public void onResult(boolean granted) { }
    };

    private static final String TAG = "BalanceWidget";
    public static final int REQ_NOTIFY = 2001;

    /** 是否已自动询问过（避免每次冷启动都弹） */
    private static final String KEY_ASKED = "notify_asked";

    // ---------- 状态判定 ----------

    /** 通知是否可用（同时考虑系统级关闭与通道级关闭） */
    public static boolean isEnabled(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm == null || !nm.areNotificationsEnabled()) return false;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    android.app.NotificationChannel ch = nm.getNotificationChannel(Alert.CHANNEL_ID);
                    if (ch != null
                            && ch.getImportance() == android.app.NotificationManager.IMPORTANCE_NONE) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            return true;    // 判断不了就当可用，别拦着用户
        }
    }

    private static boolean hasRuntimePermission(Context c) {
        if (Build.VERSION.SDK_INT < 33) return true;
        try {
            return c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 通知是否处于完全就绪状态 */
    public static boolean isReady(Context c) {
        return isEnabled(c) && hasRuntimePermission(c);
    }

    private static boolean hasAsked(Context c) {
        try {
            return c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ASKED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void markAsked(Context c) {
        try {
            c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE)
             .edit().putBoolean(KEY_ASKED, true).apply();
        } catch (Throwable ignored) { }
    }

    // ---------- 跳系统设置 ----------

    public static void openSettings(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return;
            } catch (Throwable ignored) { }
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.fromParts("package", c.getPackageName(), null));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return;
        } catch (Throwable ignored) { }
        try {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) { }
    }

    // ---------- 主流程 ----------

    /**
     * 提示用户开启通知。
     *
     * @param force true = 用户主动点击（设置页入口），每次都弹；
     *              false = 自动调用（进入主界面），仅在"未就绪且未问过"时弹一次
     */
    public static void ensure(final Activity act, Callback callback, boolean force) {
        if (act == null || act.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 17 && act.isDestroyed()) return;

        final Callback cb = (callback == null) ? NONE : callback;

        // 已就绪：直接成功
        if (isReady(act)) { cb.onResult(true); return; }

        // 自动调用：问过就不再打扰（设置页仍有入口可主动开）
        if (!force && hasAsked(act)) { cb.onResult(false); return; }

        // 本次要弹了，先登记（无论用户选什么都算问过）
        markAsked(act);

        final boolean needRuntime = !hasRuntimePermission(act);

        try {
            new AlertDialog.Builder(act)
                    .setTitle(R.string.notify_title)
                    .setMessage(needRuntime ? R.string.notify_why : R.string.notify_closed_why)
                    .setPositiveButton(
                            needRuntime ? R.string.notify_allow : R.string.notify_goto,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    if (needRuntime) {
                                        requestRuntime(act, cb);
                                    } else {
                                        openSettings(act);
                                        cb.onResult(false);
                                    }
                                }
                            })
                    .setNegativeButton(R.string.notify_later,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    cb.onResult(false);
                                }
                            })
                    .setCancelable(true)
                    .show();
        } catch (Throwable t) {
            // 弹窗失败（极少数机型/主题问题）不能拖垮界面
            cb.onResult(false);
        }
    }

    /**
     * 请求运行时权限（仅 Android 13+）。
     * 回调通过 onRequestResult 转交。
     */
    public static void requestRuntime(Activity act, Callback cb) {
        Callback c = (cb == null) ? NONE : cb;
        if (Build.VERSION.SDK_INT < 33) {
            // 低版本没有该运行时权限，直接按当前可用性回报
            c.onResult(isEnabled(act));
            return;
        }
        // 先赋值再请求，且只在真正发起请求时赋值（避免残留脏回调）
        pending = c;
        try {
            act.requestPermissions(
                    new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
        } catch (Throwable t) {
            pending = null;
            c.onResult(false);
        }
    }

    private static Callback pending;

    /** 在 Activity.onRequestPermissionsResult 中调用 */
    public static void onRequestResult(final Activity act, int requestCode,
                                       String[] permissions, int[] grantResults) {
        if (requestCode != REQ_NOTIFY) return;

        // 先取走并清空，保证任何分支都不会残留脏回调
        final Callback cb = (pending == null) ? NONE : pending;
        pending = null;

        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        if (granted) {
            cb.onResult(true);
            return;
        }

        cb.onResult(false);

        // 被拒绝：系统弹窗之后不会再出现，引导去设置手动开
        if (act == null || act.isFinishing()) return;
        try {
            new AlertDialog.Builder(act)
                    .setTitle(R.string.notify_denied_title)
                    .setMessage(R.string.notify_denied_msg)
                    .setPositiveButton(R.string.notify_goto,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    openSettings(act);
                                }
                            })
                    .setNegativeButton(R.string.notify_later, null)
                    .show();
        } catch (Throwable ignored) { }
    }
}
