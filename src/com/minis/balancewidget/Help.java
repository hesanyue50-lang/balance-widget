package com.minis.balancewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 使用帮助（快捷手势 + 常见问题）。
 *
 * 这个文件是**功能的手册**，所以每次加功能都要顺手更新它 ——
 * 手势类操作（长按、下拉）在界面上没有可点入口，没人会去猜，
 * 帮助里漏掉就等于这个功能不存在。
 */
public final class Help {

    private Help() { }

    /** 快捷手势 / 操作：{标题, 说明} */
    private static final String[][] GESTURES = {
        { "左右滑动切换页面", "四个页面之间横向滑动切换，松手自动对齐。一次最多走一页" },
        { "下拉刷新", "在「API 余额」或「用量统计」页从顶部下拉，拉过触发线松手。统计页会连带重算图表" },
        { "长按 API 卡片", "进入排序模式，用 ▲▼ 调整卡片顺序。顺序会一直记住" },
        { "长按统计页的平台行", "同样进入排序模式，调整折线与开关顺序（与卡片顺序相互独立）" },
        { "点 API 卡片", "弹出操作菜单：刷新 / 看密钥 / 控制台 / 充值" },
        { "点统计页的「已隐藏的 API」", "展开或收起被关掉的数据源" },
        { "点图表里的某一天", "弹框列出那天各平台的余额" },
        { "点日期范围「近 N 天 ▽」", "切换 5 / 7 / 14 / 30 / 90 / 180 日" },
        { "长按设置里的密钥条目", "查看 / 复制这条密钥（需先过查看密码）" },
        { "点空白处", "收起键盘（设置页里输入到一半想收手时用）" },
    };

    /** 网络加速页：{标题, 说明} */
    private static final String[][] VPN = {
        { "添加订阅", "「VPN 订阅管理」右上角「＋ 新增」，填订阅名与链接即可。可同时保存多个机场，点一行就切换启用" },
        { "从机场网页一键导入", "在机场页面点「导入 Clash」会跳出应用选择器，选本应用即可自动填好订阅地址" },
        { "长按订阅条目", "重命名 / 更新配置 / 删除。" },
        { "更新配置", "重新下载该订阅的节点。机场换了节点、或订阅到期续费后，都需要更新一次" },
        { "节点切换", "点任意节点即可切换（左侧 ● 标记的就是当前节点）。" },
        { "测速与重测", "点右侧「测速」单独测一个节点；「全部测速」并行测完所有节点。测出来偏慢显示橙/红色，测不通显示「超时」—— 再点一下就是重测" },
        { "切换策略组", "点「策略组：xxx ▾」可以在订阅提供的多个组之间切换（如 节点选择 / 流媒体 / AI）" },
        { "三种代理模式", "应用内全局 = 本应用所有请求走代理（无需系统授权）；部分 API 模式 = 只有勾选的平台走代理（最省流量）；系统全局 = 建立系统 VPN 接管整机流量（需授权，通知栏会常驻提示）" },
        { "节点显示未选中 / 上不了外网", "订阅刚导入时策略组常停在 DIRECT（等于直连）。本应用会在拉到节点后自动切到一个真实节点并提示。若仍不通，用「全部测速」挑个延迟低的节点手动切" },
        { "切节点提示失败但其实生效了？", "内核切换节点成功时返回 204 空响应，早期版本会误报失败，现已修正" },
    };

    /** 常见问题：{问题, 回答} */
    private static final String[][] FAQ = {
        { "统计页没有曲线？", "曲线靠每 6 小时一条的余额快照累积。刚加的平台会先打一个当前余额的点，之后逐渐连成线。被关掉的平台收在「已隐藏的 API」里。" },
        { "小米 MiMo 显示「需登录」？", "MiMo 官方没有 API Key 查余额的接口，余额只能靠小米账号会话音。在设置 → API 密钥与平台 → 小米 MiMo 里点「登录小米账号」，登录成功即可自动查询。" },
        { "某个境外平台总是刷新失败？", "多为直连 TLS 握手超时（不是网络不通）。到「网络加速」页添加订阅并启动，再让该平台走代理即可。境外平台默认就会勾上。" },
        { "代理是全局的吗？会影响其他应用吗？", "不会。「全局模式」指的是**本应用内**的全局 —— 其它应用和系统网络完全不受影响。真正的系统级 VPN 需要系统授权且会接管全机流量，与「只加速余额查询」的初衷相悖，所以没有采用。" },
        { "为什么没有可用的 VPN 分组？", "两个原因：① 没在「VPN 订阅管理」里添加并更新订阅；② 内核只随包提供 arm64 版本，32 位设备不可用。" },
        { "想让某个页面成为打开应用时的首页？", "设置 → 📑 页面管理 → 选「设为主页」。它同时会自动排到导航条最左边。" },
        { "不想看到某些页面？", "设置 → 📑 页面管理 → 点「隐藏」。导航条会自动适应，剩下几个标签就均分多宽，至少保留一页。" },
        { "桌面小组件不动？", "小组件按设置里的「后台刷新间隔」更新。系统省电策略可能限制后台，可在设置里点「后台运行设置」允许自启动。" },
        { "余额对不上？", "同一平台多个密钥取最大值（同一账户余额相同，避免重复累计）。充值记录由余额突增自动推断，不准时可用统计页的「修正充值」手动改。" },
        { "数据存在哪？", "密钥与订阅都用设备级密钥加密后存在应用私有目录，历史账本在本地 SQLite。换设备或清除应用数据后需要重新填写。" },
    };

    public static void show(Activity a) {
        if (a == null) return;

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 20), dp(a, 12), dp(a, 20), dp(a, 12));

        box.addView(section(a, "快捷手势"));
        for (int i = 0; i < GESTURES.length; i++) {
            box.addView(item(a, GESTURES[i][0], GESTURES[i][1]));
        }

        box.addView(section(a, "网络加速"));
        for (int i = 0; i < VPN.length; i++) {
            box.addView(item(a, VPN[i][0], VPN[i][1]));
        }

        box.addView(section(a, "常见问题"));
        for (int i = 0; i < FAQ.length; i++) {
            box.addView(item(a, FAQ[i][0], FAQ[i][1]));
        }

        TextView foot = new TextView(a);
        foot.setText("版本 " + version(a));
        foot.setTextSize(11f);
        foot.setTextColor(0xFFA2ABB9);
        foot.setPadding(0, dp(a, 16), 0, 0);
        box.addView(foot);

        ScrollView sc = new ScrollView(a);
        sc.addView(box);
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.75f);
        sc.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxH));

        new AlertDialog.Builder(a)
                .setTitle("使用帮助")
                .setView(sc)
                .setPositiveButton("知道了", null)
                .show();
    }

    private static String version(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private static TextView section(Activity a, String t) {
        TextView tv = new TextView(a);
        tv.setText(t);
        tv.setTextSize(15);
        tv.setTextColor(0xFF39424F);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(a, 16), 0, dp(a, 6));
        return tv;
    }

    private static LinearLayout item(Activity a, String title, String desc) {
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(a);
        t.setText("· " + title);
        t.setTextSize(13.5f);
        t.setTextColor(0xFF2F3946);
        wrap.addView(t);

        TextView d = new TextView(a);
        d.setText("   " + desc);
        d.setTextSize(12.5f);
        d.setTextColor(0xFF5E6878);
        d.setPadding(0, dp(a, 2), 0, dp(a, 8));
        wrap.addView(d);
        return wrap;
    }

    private static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }
}
