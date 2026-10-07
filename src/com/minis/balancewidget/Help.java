package com.minis.balancewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
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
        { "按住图表左右拖动", "图表上方浮出该时间点各平台的余额。图表区不参与左右翻页（已设独立手势），可以放心拖动查看；松手即收起" },
        { "点日期范围「近 N 天 ▽」", "切换 5 / 7 / 14 / 30 / 90 / 180 日" },
        { "长按设置里的密钥条目", "查看 / 复制这条密钥（需先过查看密码）" },
        { "点设置里的「展开 ▾」", "「刷新频率」「API 密钥与平台」「隐藏 API」「备份与恢复」这些栏目都能折叠。展开/收起的状态会记住，下次进设置还是上次的样子" },
        { "点空白处", "收起键盘（设置页里输入到一半想收手时用）" },
    };

    /** 网络加速页：{标题, 说明} */
    private static final String[][] VPN = {
        { "添加订阅", "「VPN 订阅管理」右上角「＋ 新增」，填订阅名与链接即可。可同时保存多个机场，点一行就切换启用" },
        { "从机场网页一键导入", "多数机场官网都有「一键导入 Clash」按钮，点它会跳出应用选择器 —— 选本应用即可自动填好订阅地址与名称，不用手动复制链接" },
        { "长按订阅条目", "重命名 / 更新配置 / 删除。" },
        { "更新配置", "重新下载该订阅的节点。机场换了节点、或订阅到期续费后，都需要更新一次" },
        { "节点切换", "点任意节点即可切换（左侧 ● 标记的就是当前节点）。" },
        { "测速与重测", "点右侧「测速」单独测一个节点；「全部测速」并行测完所有节点。测出来偏慢显示橙/红色，测不通显示「超时」—— 再点一下就是重测" },
        { "切换策略组", "点「策略组：xxx ▾」可以在订阅提供的多个组之间切换（如 节点选择 / 流媒体 / AI）" },
        { "三种代理模式", "应用内全局 = 本应用所有请求走代理（无需系统授权）；部分 API 模式 = 只有勾选的平台走代理（最省流量）；系统全局 = 建立系统 VPN 接管整机流量（需授权，通知栏会常驻提示）" },
        { "节点显示未选中 / 上不了外网", "订阅刚导入时策略组常停在 DIRECT（等于直连）。本应用会在拉到节点后自动切到一个真实节点并提示。若仍不通，用「全部测速」挑个延迟低的节点手动切" },
        { "切节点提示失败但其实生效了？", "内核切换节点成功时返回 204 空响应，早期版本会误报失败，现已修正" },
    };

    /** 后台与省电：{标题, 说明} */
    private static final String[][] POWER = {
        { "后台刷新间隔", "应用退到后台后，系统只允许按一定间隔唤醒刷新。间隔在「设置 → 刷新频率」里调整：前台最小 2 分钟，后台最小 5 分钟。填小于最小值会自动改成最小值" },
        { "省电模式", "设置 → 刷新频率 → 「省电模式」。开启后：后台间隔至少 60 分钟（你设了更长的则尊重你的设置），且后台刷新跳过汇率查询、失败不再重试" },
        { "为什么要跳过汇率", "汇率日内波动通常不到 0.5%，对余额折合人民币的展示没有实际影响；而这一跳请求要唤醒网络，是后台耗电的主要来源之一。前台刷新不受影响" },
        { "电池优化白名单", "设置 → 刷新频率 → 「电池优化白名单」。加入后系统深度休眠（Doze）不会推迟闹钟。这台机器上实测：加入前后台唤醒被推迟 2 分多钟、闹钟带 15 分钟窗口，加入后基本准时" },
        { "自启动权限", "设置 → 刷新频率 → 「设置自启动」。国产系统特有的后台唤醒白名单，不给的话闹钟压根无法唤醒本应用" },
        { "两项都要给", "「自启动」和「电池优化白名单」是**两套独立机制**，缺一样后台都不正常：前者决定闹钟能不能唤醒，后者决定唤醒准不准时" },
    };

    /** 备份与恢复：{标题, 说明} */
    private static final String[][] BACKUP = {
        { "备份到文件", "把 API 密钥、VPN 订阅、全部设置和历史曲线打包成一个 .apibak 文件，存到你选的位置（下载目录、网盘同步目录都行）。内容多也不大，通常几十 KB。建议定期做一次" },
        { "本机备份记录", "每次备份都会在应用内也留一份，列表里能看到时间、大小，以及它导出成的那个文件名。点「恢复」直接还原，点「删除」清掉这条" },
        { "从备份文件恢复", "在设置 → 备份与恢复 → 「从备份文件恢复」里挑一个 .apibak。选择器会自动落在你上次备份的文件夹；也可以直接在文件管理器里点开备份文件，应用会自动接手" },
        { "换设备 / 重装后恢复", "把 .apibak 拷到新设备再恢复即可。注意：密钥是用本机设备密钥加密的，换设备可能解不开（会提示恢复失败）；同一台设备上恢复没问题" },
        { "恢复会覆盖当前数据", "恢复是用备份整体替换现有数据 —— 密钥、订阅、设置、历史曲线全部被覆盖，且不可撤销，所以操作前会二次确认" },
        { "删除备份时文件删不掉？", "系统通常不允许应用直接删你选中的文件。这时会先删掉本机记录，再弹窗告诉你是哪个文件，并提供「去文件夹」按钮带你过去手动删" },
        { "备份文件被我手动删了？", "应用内那条记录不会自动消失，看到后手动点「删除」清掉即可；如果文件已经打不开，列表里会标注出来，不影响其他记录" },
    };

    /** 常见问题：{问题, 回答} */
    private static final String[][] FAQ = {
        { "统计页没有曲线？", "曲线靠每 6 小时一条的余额快照累积。刚加的平台会先打一个当前余额的点，之后逐渐连成线。被关掉的平台收在「已隐藏的 API」里。" },
        { "小米 MiMo 显示「需登录」或「登录已过期」？", "MiMo 官方没有用 API Key 查余额的接口，余额只能靠小米账号会话音。到设置 → API 密钥与平台 → 小米 MiMo 点「登录小米账号」，登录成功即可自动查询。会话音有效期较短，本应用会在打开应用时自动续期；确实过期时，点卡片菜单里的「重新登录小米账号」重登一次即可。" },
        { "某个境外平台总是刷新失败？", "多为直连 TLS 握手超时（不是网络不通）。到「网络加速」页添加订阅并启动，再让该平台走代理即可。境外平台默认就会勾上。" },
        { "代理是全局的吗？会影响其他应用吗？", "不会。「全局模式」指的是**本应用内**的全局 —— 其它应用和系统网络完全不受影响。真正的系统级 VPN 需要系统授权且会接管全机流量，与「只加速余额查询」的初衷相悖，所以没有采用。" },
        { "为什么没有可用的 VPN 分组？", "两个原因：① 没在「VPN 订阅管理」里添加并更新订阅；② 内核只随包提供 arm64 版本，32 位设备不可用。" },
        { "想让某个页面成为打开应用时的首页？", "设置 → 📑 页面管理 → 选「设为主页」。它同时会自动排到导航条最左边。" },
        { "不想看到某些页面？", "设置 → 📑 页面管理 → 点「隐藏」。导航条会自动适应，剩下几个标签就均分多宽，至少保留一页。" },
        { "桌面小组件不动？", "小组件按设置里的「后台刷新间隔」更新。系统省电策略可能限制后台，请到设置 → 刷新频率，把「设置自启动」和「电池优化白名单」两项都开启。" },
        { "后台会不会很耗电？", "后台每次唤醒只做一件事：拉一次余额、判断是否低于预警线。可以在「设置 → 刷新频率」开「省电模式」把后台间隔拉到 1 小时以上，并跳过汇率等非必要请求。余额变化本身很慢，拉长间隔对预警影响很小。" },
        { "图表上看不到某天的具体数值？", "在图表上按住并左右拖动，顶部会浮出该时间点各平台的余额；松手收起。这个区域已和应用翻页手势分开，拖动不会误翻页。" },
        { "余额对不上？", "同一平台多个密钥取最大值（同一账户余额相同，避免重复累计）。充值记录由余额突增自动推断，不准时可用统计页的「修正充值」手动改。" },
        { "数据存在哪？", "密钥与订阅都用设备级密钥加密后存在应用私有目录，历史账本在本地 SQLite。换设备或清除应用数据后需要重新填写。所有数据都在本机，不上传任何服务器 —— 也正因为如此，卸载或清除数据前请先做一次备份（设置 → 备份与恢复）" },
    };

    /** 章节标题（顺序即显示顺序） */
    private static final String[] SECTIONS = {
        "快捷手势", "网络加速", "后台与省电", "备份与恢复", "常见问题",
    };

    /** 各章节内容，与 {@link #SECTIONS} 一一对应 */
    private static String[][][] contents() {
        return new String[][][] { GESTURES, VPN, POWER, BACKUP, FAQ };
    }

    public static void show(Activity a) {
        if (a == null) return;

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 20), dp(a, 12), dp(a, 20), dp(a, 12));

        final String[][][] data = contents();

        /* 每个章节 = 「可点的标题」+「跟着标题就地展开的内容」。
           不做跳转目录 —— 帮助页本来就是"查一条看一条"，
           跳到下面还得自己往回翻，反而不如就地展开。 */
        for (int i = 0; i < SECTIONS.length; i++) {
            final String title = SECTIONS[i];

            final LinearLayout body = new LinearLayout(a);
            body.setOrientation(LinearLayout.VERTICAL);
            for (int j = 0; j < data[i].length; j++) {
                body.addView(item(a, data[i][j][0], data[i][j][1]));
            }
            body.setVisibility(View.GONE);      // 默认全部收起

            final TextView head = section(a, title + "    ▸");
            head.setPadding(0, dp(a, 16), 0, dp(a, 10));
            head.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean open = body.getVisibility() != View.VISIBLE;
                    head.setText(title + (open ? "    ▾" : "    ▸"));
                    if (open) Anim.expand(body);
                    else Anim.collapse(body);
                }
            });

            box.addView(head);
            box.addView(body);
        }

        TextView foot = new TextView(a);
        foot.setText("版本 " + version(a) + "\n点标题可展开或收起");
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
