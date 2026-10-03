package com.minis.balancewidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「网络加速」页控制器。
 *
 * 承担：分流模式、多订阅管理（新增/启用/重命名/删除/更新）、启停内核、节点选择、测速。
 *
 * 线程纪律：
 *   - 网络操作（下载订阅、测速）一律后台线程；测速**并行**跑（见 {@link #testAll}）；
 *   - UI 更新一律 runOnUiThread。
 */
public class VpnBinder {

    private final Activity act;
    private final View root;
    private final Handler h = new Handler();

    private List<Clash.Group> groups = new ArrayList<Clash.Group>();
    private int groupIdx = 0;
    /** 正在测速的节点名（行内显示"…"） */
    private final java.util.HashSet<String> testing = new java.util.HashSet<String>();
    /** 测过但失败的节点（显示"超时"，点一下重测）—— 与"从未测过"区分开 */
    private final java.util.HashSet<String> timedOut = new java.util.HashSet<String>();
    /** 测速线程池：并行但限流，避免把机场打限速反而全测不准 */
    private ExecutorService pool;
    private boolean built;
    /** VPN 授权请求码（MainActivity 转发 onActivityResult 用） */
    public static final int REQ_VPN = 0x5670;

    public VpnBinder(Activity a, View r) {
        this.act = a;
        this.root = r;
    }

    public void bind() {
        if (built) { render(); return; }
        built = true;
        SubStore.migrateIfNeeded(act);

        /* 从机场网页「导入 Clash」跳进来时，订阅已由 MainActivity 落库，
           这里取走那条待办请求并开始下载。 */
        String pending = ClashImport.takePendingDownload();
        if (pending != null) {
            final SubStore.Sub s0 = SubStore.byId(act, pending);
            if (s0 != null) h.postDelayed(new Runnable() {
                public void run() { downloadSub(s0, false); }
            }, 300);
        }

        View imp = root.findViewById(R.id.btn_import_sub);
        if (imp != null) imp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { updateActiveSub(); }
        });
        View run = root.findViewById(R.id.btn_proxy_run);
        if (run != null) run.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleRun(); }
        });
        View all = root.findViewById(R.id.btn_test_all);
        if (all != null) all.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { testAll(); }
        });
        View gp = root.findViewById(R.id.group_pick);
        if (gp != null) gp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickGroup(); }
        });
        View add = root.findViewById(R.id.btn_add_sub);
        if (add != null) add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { addSubDialog(); }
        });

        render();
    }

    public void refresh() {
        if (built) render();
    }

    // ---------------- 渲染 ----------------

    private void render() {
        renderState();
        renderMode();
        renderSubs();
        renderPlatforms();
        renderNodes();
    }

    /** 模式选择：三选一的单选行 */
    private void renderMode() {
        LinearLayout box = (LinearLayout) root.findViewById(R.id.mode_box);
        if (box == null) return;
        box.removeAllViews();

        /* 三选一。原先还有个「应用内模式」，语义和「应用内全局」完全一样，
           留着只会让人犹豫该选哪个，已删（旧存档里的 "app" 会被 mode() 归一化成 all）。 */
        final String[] modes = { Clash.MODE_ALL, Clash.MODE_PARTIAL, Clash.MODE_SYSTEM };
        final String[] titles = { "应用内全局", "部分 API 模式", "系统全局（接管整机）" };
        final String[] descs = {
            "本应用发出的所有请求都走代理。其它应用不受影响，无需系统授权。",
            "只有下面勾选的平台走代理，其余直连。省机场流量、国内平台更快。",
            "建立系统 VPN，**手机上所有应用**的流量都走代理。需要系统授权，"
                    + "通知栏会出现常驻提示；不需要时请及时关掉。"
        };
        final String cur = Clash.mode(act);   // 已归一化（旧值 "app" → "all"）

        for (int i = 0; i < modes.length; i++) {
            final String m = modes[i];
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setPadding(0, dp(7), 0, dp(7));

            TextView mark = new TextView(act);
            mark.setText(m.equals(cur) ? "◉" : "○");
            mark.setTextSize(14);
            mark.setTextColor(act.getColor(m.equals(cur) ? R.color.accent : R.color.tx3));
            mark.setWidth(dp(22));
            row.addView(mark);

            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView t = new TextView(act);
            t.setText(titles[i]);
            t.setTextSize(13.5f);
            t.setTextColor(act.getColor(m.equals(cur) ? R.color.accent : R.color.tx));
            if (m.equals(cur)) t.setTypeface(null, Typeface.BOLD);
            col.addView(t);

            TextView d = new TextView(act);
            d.setText(descs[i]);
            d.setTextSize(11.5f);
            d.setTextColor(act.getColor(R.color.tx3));
            d.setPadding(0, dp(2), 0, 0);
            col.addView(d);
            row.addView(col);

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (Clash.MODE_SYSTEM.equals(m)) { pickSystemMode(); return; }
                    /* 从「系统全局」切回来：把系统 VPN 关掉，改走本地代理 */
                    if (Clash.MODE_SYSTEM.equals(Clash.mode(act))) {
                        ClashVpnService.stop(act);
                    }
                    Clash.setMode(act, m);
                    BalanceFetcher.diag(act, "代理模式 → " + m);
                    renderMode();
                    renderPlatforms();     // 部分模式下才需要看勾选列表
                }
            });
            box.addView(row);
        }

        /* 部分模式下把「平台勾选」卡片显示出来，其它模式隐藏 ——
           全局/应用内模式下那份勾选没有意义，留着只会让人困惑。 */
        View route = root.findViewById(R.id.route_card);
        if (route != null) {
            route.setVisibility(Clash.MODE_PARTIAL.equals(cur) ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 选择「系统全局」。
     *
     * 这条路和别的模式不同：要建系统 VPN，必须**用户在系统弹窗里点确认**
     * （VpnService.prepare 返回的 Intent 就是那个授权页）。
     * 授权是一次性的，之后可反复启停；但如果用户在系统设置里撤销了，会再弹一次。
     */
    private void pickSystemMode() {
        if (!Clash.available(act)) {
            Toast.makeText(act, "内核不可用（仅支持 arm64 设备）", Toast.LENGTH_LONG).show();
            return;
        }
        SubStore.Sub sub = SubStore.active(act);
        if (sub == null || !sub.ready()) {
            Toast.makeText(act, "请先在下面添加并更新一个订阅", Toast.LENGTH_LONG).show();
            return;
        }

        if (ClashVpnService.authorized(act)) {
            confirmSystemMode();
            return;
        }
        /* 还没授权：用 VpnService.prepare 拿到授权页，让用户去点确认 */
        try {
            android.content.Intent i = android.net.VpnService.prepare(act);
            if (i != null) {
                Toast.makeText(act, "请在弹出的系统窗口中允许建立 VPN 连接",
                        Toast.LENGTH_LONG).show();
                act.startActivityForResult(i, REQ_VPN);
                return;
            }
        } catch (Throwable t) {
            BalanceFetcher.diag(act, "请求 VPN 授权失败 " + t);
        }
        confirmSystemMode();
    }

    /** 授权已就绪：再确认一次（毕竟会影响整机流量）再启动 */
    private void confirmSystemMode() {
        /* 这个提示只在**第一次**开启时弹 —— 每次开都弹会让人烦，
           而且用户点过一次就说明他已经知道会影响整机流量了。 */
        if (act.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE)
                .getBoolean("vpn_notice_shown", false)) {
            doStartSystemMode();
            return;
        }
        new AlertDialog.Builder(act)
                .setTitle("开启系统全局代理")
                .setMessage("这会在手机上建立一条 VPN，**所有应用的网络都会经过代理**，"
                        + "通知栏会出现常驻提示。\n\n不用时记得回来关掉（切回其它模式即可）。")
                .setPositiveButton("开启", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        act.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE)
                                .edit().putBoolean("vpn_notice_shown", true).apply();
                        doStartSystemMode();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doStartSystemMode() {
        Clash.setMode(act, Clash.MODE_SYSTEM);
        Clash.setEnabled(act, true);
        ClashVpnService.start(act);
        BalanceFetcher.diag(act, "代理模式 → system（系统全局）");
        toast("正在建立系统 VPN…");
        /* 建立 tun + 拉起内核要一两秒，而且这次是在后台线程做的，
           所以轮询几次直到状态稳定，别只等一个固定时长。 */
        for (int i = 1; i <= 6; i++) {
            h.postDelayed(new Runnable() {
                public void run() { renderMode(); renderState(); }
            }, 500L * i);
        }
    }

    /** 授权页回来 */
    public void onActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQ_VPN) return;
        if (ClashVpnService.authorized(act)) {
            confirmSystemMode();
        } else {
            Toast.makeText(act, "未获得 VPN 授权，无法使用系统全局模式",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 订阅列表：点一行启用，长按重命名/删除，每行右侧「更新」 */
    private void renderSubs() {
        LinearLayout box = (LinearLayout) root.findViewById(R.id.sub_list);
        if (box == null) return;
        box.removeAllViews();

        final List<SubStore.Sub> subs = SubStore.all(act);
        final String active = SubStore.activeId(act);

        if (subs.isEmpty()) {
            TextView t = new TextView(act);
            t.setText("还没有订阅，点右上角「＋ 新增」添加");
            t.setTextColor(act.getColor(R.color.tx3));
            t.setTextSize(12);
            box.addView(t);
            return;
        }

        for (int i = 0; i < subs.size(); i++) {
            final SubStore.Sub sub = subs.get(i);
            final boolean on = sub.id.equals(active);

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            TextView mark = new TextView(act);
            mark.setText(on ? "◉" : "○");
            mark.setTextSize(14);
            mark.setTextColor(act.getColor(on ? R.color.accent : R.color.tx3));
            mark.setWidth(dp(22));
            row.addView(mark);

            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView nm = new TextView(act);
            nm.setText(sub.name + (on ? "   使用中" : ""));
            nm.setTextSize(13.5f);
            nm.setTextColor(act.getColor(on ? R.color.accent : R.color.tx));
            if (on) nm.setTypeface(null, Typeface.BOLD);
            col.addView(nm);

            TextView meta = new TextView(act);
            meta.setText(sub.summary());
            meta.setTextSize(11.5f);
            meta.setTextColor(act.getColor(R.color.tx3));
            meta.setPadding(0, dp(2), 0, 0);
            col.addView(meta);
            row.addView(col);

            TextView upd = new TextView(act);
            upd.setText("更新");
            upd.setTextSize(11.5f);
            upd.setTextColor(act.getColor(R.color.accent));
            upd.setPadding(dp(10), dp(6), dp(10), dp(6));
            upd.setBackgroundResource(R.drawable.mini_btn_border);
            upd.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { downloadSub(sub, false); }
            });
            row.addView(upd);

            /* 点整行 = 启用这个订阅 */
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { useSub(sub); }
            });
            /* 长按 = 重命名 / 删除 */
            row.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    subMenu(sub);
                    return true;
                }
            });
            box.addView(row);

            View line = new View(act);
            line.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
            line.setBackgroundColor(0x148A94A3);
            box.addView(line);
        }
    }

    private void renderState() {
        TextView st = (TextView) root.findViewById(R.id.proxy_state);
        TextView run = (TextView) root.findViewById(R.id.btn_proxy_run);
        EditText e = (EditText) root.findViewById(R.id.e_sub_url);
        if (st == null) return;

        SubStore.Sub a = SubStore.active(act);
        if (e != null && !e.hasFocus() && a != null && a.url.length() > 0) {
            e.setText(a.url);       // 只展示启用中的订阅地址，编辑请到上面的管理区
        }

        boolean avail = Clash.abiSupported() && Clash.available(act);
        boolean on = Clash.enabled(act);
        boolean running = Clash.isRunning();

        StringBuilder sb = new StringBuilder();
        if (!avail) {
            sb.append("状态：不可用 —— 内置内核仅支持 arm64 设备");
        } else if (running) {
            sb.append("状态：运行中  ·  已运行 ").append(Clash.uptimeMs() / 1000).append(" 秒");
            sb.append("\n代理端口 127.0.0.1:").append(Clash.PROXY_PORT);
            if (a != null) sb.append("\n启用订阅：").append(a.name);
            String m = Clash.mode(act);
            sb.append("\n模式：").append(modeName(m));
            if (Clash.MODE_SYSTEM.equals(m)) {
                sb.append(ClashVpnService.isRunning() ? "（已接管整机流量）" : "（未建立，需授权）");
            }
        } else if (on) {
            sb.append("状态：已启用但未运行（点「启动加速」重新拉起）");
        } else {
            sb.append("状态：未启动");
            if (a == null || !a.ready()) sb.append("\n先在下面添加并更新一个订阅");
        }
        st.setText(sb.toString());
        st.setTextColor(act.getColor(running ? R.color.tx2 : R.color.tx3));

        if (run != null) {
            run.setText(running ? "停止加速" : "启动加速");
            run.setTextColor(act.getColor(running ? R.color.danger : R.color.accent));
        }
    }

    private void renderNodes() {
        LinearLayout box = (LinearLayout) root.findViewById(R.id.node_list);
        TextView hint = (TextView) root.findViewById(R.id.node_hint);
        TextView gp = (TextView) root.findViewById(R.id.group_pick);
        if (box == null) return;
        box.removeAllViews();

        if (!Clash.isRunning()) {
            if (hint != null) hint.setText("加速启动后这里会列出订阅里的节点。");
            if (gp != null) gp.setVisibility(View.GONE);
            return;
        }
        if (groups.isEmpty()) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        BalanceFetcher.diag(act, "clash节点 " + Clash.dumpGroupsDiag());
                        final List<Clash.Group> gs = Clash.listGroups();
                        /* 拿到节点列表后顺手纠正一次「出口还停在 DIRECT」的情况：
                           否则用户会看到"加速运行中"却上不了外网，很难自己想到是节点没选。 */
                        final String auto = Clash.autoSelectRealNode();
                        act.runOnUiThread(new Runnable() {
                            public void run() {
                                if (auto != null) {
                                    /* 切了节点，本地列表要重新拉一次，
                                       否则界面上显示的还是旧的"当前节点" */
                                    try { groups = Clash.listGroups(); }
                                    catch (Throwable ig) { groups = gs; }
                                } else {
                                    groups = gs;
                                }
                                groupIdx = 0;
                                if (auto != null) {
                                    toast("已自动选择节点：" + auto + "（原先停在直连）");
                                }
                                renderNodes();
                            }
                        });
                    } catch (Throwable t) {
                        act.runOnUiThread(new Runnable() {
                            public void run() {
                                if (hint != null) hint.setText("读取节点失败：" + t.getMessage());
                            }
                        });
                    }
                }
            }).start();
            if (hint != null) hint.setText("正在读取节点…");
            if (gp != null) gp.setVisibility(View.GONE);
            return;
        }

        if (groupIdx >= groups.size()) groupIdx = 0;
        final Clash.Group g = groups.get(groupIdx);
        if (gp != null) {
            gp.setVisibility(View.VISIBLE);
            gp.setText("策略组：" + g.name + "   (" + g.nodes.size() + " 个)  ▾");
        }
        if (hint != null) hint.setText("点节点即切换；点右侧延迟可单独重测。");

        for (int i = 0; i < g.nodes.size(); i++) {
            final Clash.Node n = g.nodes.get(i);
            final boolean current = n.name.equals(g.now);

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));

            TextView mark = new TextView(act);
            mark.setText(current ? "●" : "○");
            mark.setTextSize(12);
            mark.setTextColor(act.getColor(current ? R.color.accent : R.color.tx3));
            mark.setWidth(dp(20));
            row.addView(mark);

            TextView nm = new TextView(act);
            nm.setText(n.name);
            nm.setTextSize(13);
            nm.setTextColor(act.getColor(current ? R.color.accent : R.color.tx));
            if (current) nm.setTypeface(null, Typeface.BOLD);
            nm.setSingleLine(true);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            nm.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(nm);

            final TextView delay = new TextView(act);
            delay.setTextSize(12);
            delay.setGravity(Gravity.CENTER);
            delay.setPadding(dp(10), dp(4), dp(10), dp(4));
            delay.setBackgroundResource(R.drawable.mini_btn_border);
            bindDelay(delay, n);
            delay.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { testOne(delay, n.name); }
            });
            row.addView(delay);

            final int idx = groupIdx;
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { switchTo(idx, n.name); }
            });
            box.addView(row);

            View line = new View(act);
            line.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
            line.setBackgroundColor(0x148A94A3);
            box.addView(line);
        }
    }

    /**
     * 延迟按钮的三种状态：
     *   测试中 → "…"
     *   测过且失败 → **"超时"**（红色，点一下就是重测 —— 用户点名要的行为）
     *   有结果 → "123ms"（按快慢着色）
     *   从未测过 → "测速"
     */
    private void bindDelay(TextView tv, Clash.Node n) {
        if (testing.contains(n.name)) {
            tv.setText("…");
            tv.setTextColor(act.getColor(R.color.tx3));
            return;
        }
        if (n.delay > 0) {
            tv.setText(n.delay + "ms");
            int c = n.delay < 200 ? 0xFF22A06B : (n.delay < 500 ? 0xFFA8730F : 0xFFC93B3B);
            tv.setTextColor(c);
            return;
        }
        if (timedOut.contains(n.name)) {
            tv.setText("超时");
            tv.setTextColor(act.getColor(R.color.danger));
            return;
        }
        tv.setText("测速");
        tv.setTextColor(act.getColor(R.color.tx3));
    }

    private void renderPlatforms() {
        LinearLayout box = (LinearLayout) root.findViewById(R.id.proxy_plats);
        if (box == null) return;
        box.removeAllViews();

        java.util.LinkedHashMap<String, String> plats =
                new java.util.LinkedHashMap<String, String>();
        List<KeyStore.ApiKey> aks = KeyStore.all(act);
        for (int i = 0; i < aks.size(); i++) {
            KeyStore.ApiKey k = aks.get(i);
            String plat = k.platform == null ? "" : k.platform;
            if (plat.length() == 0) continue;
            if (!k.isConfigured() && !plat.startsWith("custom")) continue;
            if (plats.containsKey(plat)) continue;
            plats.put(plat, platName(plat));
        }
        List<BalanceFetcher.Custom> cs = BalanceFetcher.loadCustom(act);
        for (int i = 0; i < cs.size(); i++) {
            String plat = "custom:" + i;
            if (plats.containsKey(plat)) continue;
            String nm = cs.get(i).name;
            plats.put(plat, (nm == null || nm.length() == 0) ? "自定义平台 " + (i + 1) : nm);
        }

        if (plats.isEmpty()) {
            TextView t = new TextView(act);
            t.setText("还没有添加任何平台");
            t.setTextColor(act.getColor(R.color.tx3));
            t.setTextSize(12);
            box.addView(t);
            return;
        }

        final boolean kernelOn = Clash.enabled(act) && Clash.isRunning();
        for (java.util.Iterator<java.util.Map.Entry<String, String>> it =
                plats.entrySet().iterator(); it.hasNext(); ) {
            final java.util.Map.Entry<String, String> en = it.next();
            final String plat = en.getKey();
            BalanceFetcher.Preset p = BalanceFetcher.presetOf(plat);

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView nm = new TextView(act);
            nm.setText(en.getValue() + (p != null && p.foreign ? "  (境外)" : ""));
            nm.setTextColor(act.getColor(R.color.tx));
            nm.setTextSize(13);
            nm.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(nm);

            Switch sw = new Switch(act);
            sw.setTextSize(11);
            sw.setChecked(Clash.platformViaProxy(act, plat, p != null && p.foreign));
            sw.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][] { { android.R.attr.state_checked }, { -android.R.attr.state_checked } },
                    new int[] { 0xFF3D6FD6, 0xFFC6CDD6 }));
            sw.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    Clash.setPlatformViaProxy(act, plat, on);
                    BalanceFetcher.diag(act, "走代理 " + plat + " = " + on);
                    if (on && !kernelOn) {
                        Toast.makeText(act, "加速未启动，点上方「启动加速」后生效",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
            row.addView(sw);
            box.addView(row);
        }
    }

    private String modeName(String m) {
        if (Clash.MODE_PARTIAL.equals(m)) return "部分 API";
        if (Clash.MODE_SYSTEM.equals(m)) return "系统全局";
        if (Clash.MODE_APP.equals(m)) return "应用内";
        return "应用内全局";
    }

    private String platName(String plat) {
        BalanceFetcher.Preset p = BalanceFetcher.presetOf(plat);
        if (p != null) return p.name;
        if (plat.startsWith("custom")) {
            try {
                String n = plat.startsWith("custom:") ? plat.substring(7) : plat.substring(6);
                int idx = Integer.parseInt(n);
                List<BalanceFetcher.Custom> cs = BalanceFetcher.loadCustom(act);
                if (idx >= 0 && idx < cs.size() && cs.get(idx).name != null
                        && cs.get(idx).name.length() > 0) return cs.get(idx).name;
            } catch (Throwable ignored) { }
        }
        return plat;
    }

    // ---------------- 订阅管理 ----------------

    private void addSubDialog() {
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(10), dp(20), 0);

        TextView l1 = new TextView(act);
        l1.setText("名称（给自己看的，比如「主力机场」）");
        l1.setTextColor(act.getColor(R.color.tx2));
        l1.setTextSize(12);
        panel.addView(l1);
        final EditText eName = new EditText(act);
        eName.setTextSize(14);
        panel.addView(eName);

        TextView l2 = new TextView(act);
        l2.setText("Clash 订阅链接");
        l2.setTextColor(act.getColor(R.color.tx2));
        l2.setTextSize(12);
        l2.setPadding(0, dp(10), 0, 0);
        panel.addView(l2);
        final EditText eUrl = new EditText(act);
        eUrl.setTextSize(13);
        eUrl.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        eUrl.setSingleLine(true);
        panel.addView(eUrl);

        new AlertDialog.Builder(act)
                .setTitle("新增 VPN 订阅")
                .setView(panel)
                .setPositiveButton("添加并下载", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String url = eUrl.getText().toString().trim();
                        if (url.length() == 0) {
                            Toast.makeText(act, "订阅链接不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String id = SubStore.add(act, eName.getText().toString(), url);
                        SubStore.Sub s = SubStore.byId(act, id);
                        if (s != null) downloadSub(s, true);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 长按订阅：重命名 / 删除 */
    private void subMenu(final SubStore.Sub sub) {
        new AlertDialog.Builder(act)
                .setTitle(sub.name)
                .setItems(new String[] { "重命名", "更新配置", "删除" },
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                if (which == 0) renameDialog(sub);
                                else if (which == 1) downloadSub(sub, false);
                                else confirmDelete(sub);
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void renameDialog(final SubStore.Sub sub) {
        final EditText e = new EditText(act);
        e.setText(sub.name);
        e.setTextSize(14);
        LinearLayout p = new LinearLayout(act);
        p.setPadding(dp(20), dp(10), dp(20), 0);
        p.addView(e);
        new AlertDialog.Builder(act)
                .setTitle("重命名")
                .setView(p)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        SubStore.rename(act, sub.id, e.getText().toString());
                        renderSubs();
                        renderState();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final SubStore.Sub sub) {
        new AlertDialog.Builder(act)
                .setTitle("删除订阅")
                .setMessage("确定删除「" + sub.name + "」？\n\n只影响本机保存的配置，不会动你的机场账号。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        boolean wasActive = sub.id.equals(SubStore.activeId(act));
                        SubStore.remove(act, sub.id);
                        /* 删掉的正是启用中的：内核还挂着旧配置，需要重启才会用新订阅 */
                        if (wasActive && Clash.isRunning()) {
                            Clash.stop(act);
                            toast("已删除，加速已停止（可重新启动）");
                        }
                        renderSubs();
                        renderState();
                        renderNodes();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 启用某份订阅：重启内核让新配置生效 */
    private void useSub(final SubStore.Sub sub) {
        if (!sub.ready()) {
            toast("这份订阅还没下载配置，先点「更新」");
            return;
        }
        SubStore.setActive(act, sub.id);
        renderSubs();
        if (Clash.isRunning()) {
            toast("已切换到「" + sub.name + "」，正在重启加速…");
            restartCore();
        } else {
            toast("已启用「" + sub.name + "」");
        }
        renderState();
    }

    /** 下载/更新订阅配置 */
    private void downloadSub(final SubStore.Sub sub, final boolean thenStart) {
        if (sub.url == null || sub.url.trim().length() == 0) {
            toast("这份订阅没有填链接，长按可删除后重新添加");
            return;
        }
        toast("正在下载「" + sub.name + "」…");
        new Thread(new Runnable() {
            public void run() {
                String err = null, yaml = null;
                int cnt = 0;
                try {
                    yaml = Clash.fetchSubscription(act, sub.url);
                    if (!Clash.looksLikeClash(yaml)) {
                        err = "不是 Clash 格式（可能是 SS/V2Ray 链接集，或需在机场换用 Clash 订阅）";
                    } else {
                        cnt = Clash.countProxies(yaml);
                    }
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                final String fErr = err, fYaml = yaml;
                final int fCnt = cnt;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (fErr != null) {
                            Toast.makeText(act, "下载失败：" + fErr, Toast.LENGTH_LONG).show();
                        } else {
                            SubStore.Sub s = SubStore.byId(act, sub.id);
                            if (s == null) return;
                            s.yaml = fYaml;
                            s.nodeCount = fCnt;
                            s.updatedAt = System.currentTimeMillis();
                            SubStore.update(act, s);
                            toast("「" + s.name + "」已更新，共 " + fCnt + " 个节点");
                            // 启用中的那份更新了，要重启内核才用得上新节点
                            boolean isActive = s.id.equals(SubStore.activeId(act));
                            renderSubs();
                            renderState();
                            if (isActive && Clash.isRunning()) {
                                restartCore();
                            } else if (thenStart && isActive) {
                                toggleRun();
                            }
                        }
                    }
                });
            }
        }).start();
    }

    /** 更新「启用中」那份订阅（页面上那个按钮用的就是它） */
    private void updateActiveSub() {
        SubStore.Sub a = SubStore.active(act);
        if (a == null) {
            EditText e = (EditText) root.findViewById(R.id.e_sub_url);
            String url = e == null ? "" : e.getText().toString().trim();
            if (url.length() == 0) {
                toast("还没有订阅，请点「VPN 订阅管理」右上角新增");
                return;
            }
            // 兜底：用户直接在输入框里填了地址
            String id = SubStore.add(act, null, url);
            SubStore.setActive(act, id);
            a = SubStore.byId(act, id);
            renderSubs();
        }
        downloadSub(a, false);
    }

    /** 用当前启用的订阅重启内核 */
    private void restartCore() {
        Clash.stop(act);
        groups.clear();
        h.postDelayed(new Runnable() {
            public void run() { startCore(); }
        }, 400);
    }

    private void toggleRun() {
        /* 系统全局模式下，「停止」要连系统 VPN 一起撤掉 */
        if (Clash.MODE_SYSTEM.equals(Clash.mode(act))) {
            ClashVpnService.stop(act);
            Clash.setEnabled(act, false);
            groups.clear();
            render();
            toast("已关闭系统全局代理");
            return;
        }
        if (Clash.isRunning()) {
            Clash.stop(act);
            Clash.setEnabled(act, false);
            groups.clear();
            render();
            toast("已停止加速");
            return;
        }
        SubStore.Sub a = SubStore.active(act);
        if (a == null || !a.ready()) {
            toast("先添加并更新一个订阅");
            return;
        }
        startCore();
    }

    private void startCore() {
        final SubStore.Sub a = SubStore.active(act);
        if (a == null || !a.ready()) { toast("没有可用的订阅配置"); return; }
        toast("正在启动加速…");
        new Thread(new Runnable() {
            public void run() {
                String err;
                try {
                    err = Clash.start(act, Clash.buildConfig(a.yaml));
                } catch (Throwable t) {
                    err = "启动异常：" + t;
                }
                final String fErr = err;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        Clash.setEnabled(act, fErr == null);
                        toast(fErr == null ? "加速已启动" : fErr);
                        groups.clear();
                        render();
                    }
                });
            }
        }).start();
    }

    // ---------------- 节点 ----------------

    private void switchTo(final int gi, final String node) {
        if (gi < 0 || gi >= groups.size()) return;
        final Clash.Group g = groups.get(gi);
        if (node.equals(g.now)) { toast("已经是当前节点"); return; }
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    Clash.selectNode(g.name, node);
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                final String fErr = err;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (fErr != null) {
                            Toast.makeText(act, "切换失败：" + fErr, Toast.LENGTH_SHORT).show();
                        } else {
                            g.now = node;
                            toast("已切换到 " + node);
                        }
                        renderNodes();
                    }
                });
            }
        }).start();
    }

    private void testOne(final TextView tv, final String node) {
        if (testing.contains(node)) return;
        testing.add(node);
        timedOut.remove(node);
        tv.setText("…");
        tv.setTextColor(act.getColor(R.color.tx3));
        new Thread(new Runnable() {
            public void run() {
                final int d = Clash.testDelay(node, 3000);
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        testing.remove(node);
                        if (d <= 0) timedOut.add(node);
                        setDelay(node, d);
                        Clash.Node tmp = new Clash.Node();
                        tmp.name = node;
                        tmp.delay = d;
                        bindDelay(tv, tmp);
                    }
                });
            }
        }).start();
    }

    /**
     * 全部测速 —— **并行跑**。
     *
     * 原来是逐个串行，21 个节点 × 每个最多 5 秒 = 最坏一分半，用户等不了。
     * 现在用固定大小的线程池并发：速度约等于「最慢的那一个」。
     *
     * 但仍然**限流**（并发 8）：机场普遍对并发连接有风控，
     * 一次放 21 个出去容易被限速，测出来的数字反而全都不准。
     * 所以是"并行 + 分批"，而不是无脑全开。
     */
    private void testAll() {
        if (!Clash.isRunning()) { toast("请先启动加速"); return; }
        if (groups.isEmpty()) { toast("还没读到节点"); return; }

        final List<String> names = new ArrayList<String>();
        List<Clash.Node> ns = groups.get(groupIdx).nodes;
        for (int i = 0; i < ns.size(); i++) names.add(ns.get(i).name);
        if (names.isEmpty()) return;

        if (pool == null || pool.isShutdown()) pool = Executors.newFixedThreadPool(8);
        toast("开始并行测速（共 " + names.size() + " 个）…");

        final int[] remaining = { names.size() };
        for (int i = 0; i < names.size(); i++) {
            final String nm = names.get(i);
            testing.add(nm);
            timedOut.remove(nm);
            pool.execute(new Runnable() {
                public void run() {
                    final int d = Clash.testDelay(nm, 3000);
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            testing.remove(nm);
                            if (d <= 0) timedOut.add(nm);
                            setDelay(nm, d);
                            boundRefresh();
                            remaining[0]--;
                            if (remaining[0] <= 0) toast("测速完成");
                        }
                    });
                }
            });
        }
        boundRefresh();
    }

    private void setDelay(String node, int d) {
        for (int i = 0; i < groups.size(); i++) {
            List<Clash.Node> ns = groups.get(i).nodes;
            for (int j = 0; j < ns.size(); j++) {
                if (ns.get(j).name.equals(node)) ns.get(j).delay = d;
            }
        }
    }

    /** 重绘节点行（测速结果回来时调用；节流到每 250ms 一次，避免频繁重建整列表） */
    private long lastRepaint;
    private boolean repaintScheduled;

    private void boundRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastRepaint > 250) {
            lastRepaint = now;
            renderNodes();
            return;
        }
        if (repaintScheduled) return;
        repaintScheduled = true;
        h.postDelayed(new Runnable() {
            public void run() {
                repaintScheduled = false;
                lastRepaint = System.currentTimeMillis();
                renderNodes();
            }
        }, 250);
    }

    private void pickGroup() {
        if (groups.size() < 2) { toast("订阅里只有一个策略组"); return; }
        String[] items = new String[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            items[i] = groups.get(i).name + "（" + groups.get(i).nodes.size() + " 个）";
        }
        new AlertDialog.Builder(act)
                .setTitle("选择策略组")
                .setSingleChoiceItems(items, groupIdx, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        groupIdx = which;
                        d.dismiss();
                        renderNodes();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 工具 ----------------

    private void toast(final String s) {
        act.runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private int dp(float v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }
}
