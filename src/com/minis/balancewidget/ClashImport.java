package com.minis.balancewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * 处理「机场页面点导入 Clash」跳进来的意图。
 *
 * 机场网站上的「一键导入 Clash」按钮，本质是一个
 *   clash://install-config?url=<订阅地址>&name=<订阅名>
 * 的链接。注册了这个 scheme 之后，用户点它时系统会弹出应用选择器，
 * 选本应用就跳到这里。
 *
 * 几个必须照顾到的现实情况：
 *   ① 参数是 URL 编码的，且嵌套了一层（url 本身也是个带查询串的 URL），
 *      所以要 **decode 两次** 才能拿到真正的订阅地址；
 *   ② 有的机场不带 name，得自己从地址里猜一个像样的名字；
 *   ③ 也有把地址放在 path 里的写法（clash://install-config/https%3A%2F%2F...）；
 *   ④ 用户可能只是想看看，所以**先弹确认框再下载**，不要擅自开始下载。
 */
public final class ClashImport {

    private ClashImport() { }

    /** 这个 Intent 是不是「导入 Clash 配置」请求 */
    public static boolean isImport(Intent it) {
        if (it == null || it.getData() == null) return false;
        String scheme = it.getData().getScheme();
        if (scheme == null) return false;
        scheme = scheme.toLowerCase();
        if (!"clash".equals(scheme) && !"clashmeta".equals(scheme)) return false;
        // 有些客户端用别的前缀，只要 scheme 对就先收下，靠参数里有没有 url 来判定
        return true;
    }

    /** 从 URL 里解出订阅地址（解不出返回空串） */
    public static String urlFrom(Uri u) {
        if (u == null) return "";
        String raw = u.getQueryParameter("url");
        if (raw == null || raw.trim().length() == 0) {
            /* 没有 url 参数时，有的实现把内容塞在 path 或 fragment 里 */
            String p = u.getPath();
            if (p != null && p.length() > 1) raw = p.substring(1);
            else if (u.getFragment() != null) raw = u.getFragment();
        }
        if (raw == null) return "";
        raw = raw.trim();
        /* 解码两次：外层一次是 URL 编码，而 url 参数的值本身通常又被编码了一次 */
        String once = dec(raw);
        if (looksLikeUrl(once)) return once;
        String twice = dec(once);
        if (looksLikeUrl(twice)) return twice;
        return once;
    }

    /** 订阅名：优先取 name 参数，没有就从地址里猜 */
    public static String nameFrom(Uri u, String url) {
        String n = u == null ? null : u.getQueryParameter("name");
        if (n != null && n.trim().length() > 0) return dec(n.trim());
        // 用域名当名字，比 "订阅 2" 有信息量
        try {
            String host = Uri.parse(url).getHost();
            if (host != null && host.length() > 0) {
                String[] parts = host.split("\\.");
                if (parts.length >= 2) return parts[parts.length - 2] + "." + parts[parts.length - 1];
                return host;
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /**
     * 弹出确认并导入。
     *
     * @param onDone 导入完成（或放弃）后回调，让宿主切到网络加速页
     */
    public static void handle(final Activity act, final Intent it, final Runnable onDone) {
        final Uri u = it.getData();
        final String url = urlFrom(u);
        final String name = nameFrom(u, url);

        if (url.length() == 0) {
            new AlertDialog.Builder(act)
                    .setTitle("无法识别这个导入链接")
                    .setMessage("链接里没有找到订阅地址。\n\n可以回到机场网页重新点一次「导入 Clash」，"
                            + "或者复制订阅地址后，到「网络加速」页手动添加。")
                    .setPositiveButton("知道了", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            if (onDone != null) onDone.run();
                        }
                    })
                    .show();
            return;
        }

        /* 名字允许先改：机场给的 name 有时是一长串乱码 */
        final EditText e = new EditText(act);
        e.setText(name.length() == 0 ? "机场订阅" : name);
        e.setTextSize(14);
        LinearLayout p = new LinearLayout(act);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(act, 20), dp(act, 10), dp(act, 20), 0);
        p.addView(e);

        new AlertDialog.Builder(act)
                .setTitle("导入 Clash 订阅")
                .setMessage("订阅地址：\n" + clip(url, 120))
                .setView(p)
                .setPositiveButton("导入", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String nm = e.getText().toString().trim();
                        String id = SubStore.add(act, nm, url);
                        SubStore.setActive(act, id);
                        Toast.makeText(act, "已添加，正在下载配置…", Toast.LENGTH_SHORT).show();
                        if (onDone != null) onDone.run();   // 先切到加速页，用户能看到进度
                        /* 下载交给加速页去做 —— 那里有完整的进度与错误提示。
                           用「待办标记」而不是直接调用：此刻加速页可能还没被创建。 */
                        requestDownload(id);
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (onDone != null) onDone.run();
                    }
                })
                .show();
    }

    // ---------- 待办下载 ----------

    /* 导入发生在 MainActivity 里，而下载逻辑在 VpnBinder 里，
       两者生命周期不同步（加速页可能还没被创建）。
       用一格静态标记把请求交接过去，由加速页绑定/刷新时取走执行。 */
    private static String pendingDownloadId;

    public static void requestDownload(String subId) {
        pendingDownloadId = subId;
    }

    /** 取走待办的下载请求（取一次就清空，避免重复下载） */
    public static String takePendingDownload() {
        String s = pendingDownloadId;
        pendingDownloadId = null;
        return s;
    }

    // ---------- 小工具 ----------

    private static boolean looksLikeUrl(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase();
        return t.startsWith("http://") || t.startsWith("https://");
    }

    private static String dec(String s) {
        if (s == null) return "";
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        } catch (Throwable t) {
            return s;
        }
    }

    public static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static int dp(Activity a, float v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
