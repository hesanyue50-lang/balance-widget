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
 * 使用帮助（快捷手势说明）。
 *
 * 这些手势在界面上没有可点入口 —— 没人会去猜「长按卡片能排序」，
 * 所以把全部手势 + 常见问题集中写在这里，设置里留一个入口。
 */
public final class Help {

    private Help() { }

    /** 每个条目：{标题, 说明} */
    private static final String[][] GESTURES = {
        { "下拉刷新", "在 API 余额页或用量统计页，从顶部往下拉，拉过触发线松手即刷新。统计页会连带重算图表。设置页不支持（那里没什么好刷的）" },
        { "长按 API 卡片", "进入排序模式，用 ▲▼ 调整卡片顺序。顺序会一直记住" },
        { "长按统计页的平台行", "同样进入排序模式，调整折线与开关的顺序（与卡片顺序相互独立）" },
        { "点 API 卡片", "弹出操作菜单：刷新 / 看密钥 / 控制台 / 充值" },
        { "点统计页的「已隐藏的 API」", "展开或收起被关掉的数据源" },
        { "点图表里的某一天", "弹框列出那天各平台的余额" },
        { "点日期范围「近 N 天 ▽」", "切换 5 / 7 / 14 / 30 / 90 / 180 日" },
        { "点总资产卡片右上角", "立刻刷新一次（下拉也能刷新）" },
        { "长按设置里的密钥条目", "查看 / 复制这条密钥（需要先过查看密码）" },
        { "设置 → ❓ 使用帮助", "随时再打开这份说明" },
    };

    private static final String[][] FAQ = {
        { "统计页没有曲线？", "曲线靠每 6 小时一条的余额快照累积。刚加的平台会先打一个当前余额的点，之后逐渐连成线。安卓「用量统计」里被关掉的平台在「已隐藏的 API」中。" },
        { "小米 MiMo 显示「需登录」？", "MiMo 官方没有 API Key 查余额的接口，余额只能靠小米账号会话音。在设置 → API 密钥与平台 → 小米 MiMo 里点「登录小米账号」，登录成功即可自动查询。" },
        { "桌面小组件不动？", "小组件按设置里的「后台刷新间隔」更新。系统省电策略可能限制后台，可在设置里点「后台运行设置」允许自启动。" },
        { "余额对不上？", "同一平台多个密钥取最大值（同一账户余额相同，避免重复累计）。充值记录由余额突增自动推断，不准时可用统计页的「修正充值」手动改。" },
        { "数据存在哪？", "密钥用设备级密钥加密后存在应用私有目录，历史账本在本地 SQLite。换设备或清除应用数据后需要重新填写。" },
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

        box.addView(section(a, "常见问题"));
        for (int i = 0; i < FAQ.length; i++) {
            box.addView(item(a, FAQ[i][0], FAQ[i][1]));
        }

        ScrollView sc = new ScrollView(a);
        sc.addView(box);
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.72f);
        sc.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxH));

        new AlertDialog.Builder(a)
                .setTitle("使用帮助")
                .setView(sc)
                .setPositiveButton("知道了", null)
                .show();
    }

    private static TextView section(Activity a, String t) {
        TextView tv = new TextView(a);
        tv.setText(t);
        tv.setTextSize(15);
        tv.setTextColor(0xFF39424F);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(a, 14), 0, dp(a, 6));
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
