package com.minis.balancewidget;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台配置的导出 / 导入 / 分享。
 *
 * <h3>文件格式 .bwp（BalanceWidget Platform）</h3>
 * 就是一个 JSON 文件，后缀换成 .bwp 以便双击/分享时能认出来：
 * <pre>
 * {
 *   "app": "balance-widget-platform",
 *   "version": 1,
 *   "exportedAt": 1760000000000,
 *   "platforms": [
 *     { "type": "web",    "data": { ...WebCustom 全字段... } },
 *     { "type": "custom", "data": { ...Custom   全字段... } }
 *   ]
 * }
 * </pre>
 *
 * <h3>为什么用明文导出</h3>
 * 导出的是**明文**密钥 / 请求头（落盘那份是加密的，换台设备解不开）。
 * 这是有意的：分享出去就是为了"别人能直接用"，
 * 所以界面上必须提醒用户别把带密钥的文件发到公开地方。
 *
 * <h3>为什么分享走文本而不是文件</h3>
 * 分享文件要配 FileProvider（多一个 authority、多一份 xml、还要处理 URI 授权），
 * 而微信/QQ 发文件对 .bwp 这类自定义后缀也未必友好。
 * 直接发**文本**最省事：对方复制 → 粘贴导入即可，跨设备也通用。
 */
public final class PlatformPack {

    public static final String MAGIC = "balance-widget-platform";
    public static final int VERSION = 1;
    public static final String EXT = ".bwp";
    public static final String MIME = "application/json";

    public static final int REQ_EXPORT = 8811;
    public static final int REQ_IMPORT = 8812;

    private PlatformPack() { }

    /* ==================== 打包 ==================== */

    /**
     * 打成 .bwp 文本。
     *
     * @param which "web"=只要高级平台 / "custom"=只要自定义平台 / "all"=都要
     */
    public static String exportText(Context c, String which) {
        try {
            JSONObject root = new JSONObject();
            root.put("app", MAGIC);
            root.put("version", VERSION);
            root.put("exportedAt", System.currentTimeMillis());

            JSONArray arr = new JSONArray();
            if ("web".equals(which) || "all".equals(which)) {
                List<WebCustom> ws = WebCustom.loadAll(c);
                for (int i = 0; i < ws.size(); i++) {
                    JSONObject it = new JSONObject();
                    it.put("type", "web");
                    it.put("data", ws.get(i).toJson());
                    arr.put(it);
                }
            }
            if ("custom".equals(which) || "all".equals(which)) {
                List<BalanceFetcher.Custom> cs = BalanceFetcher.loadCustom(c);
                for (int i = 0; i < cs.size(); i++) {
                    JSONObject it = new JSONObject();
                    it.put("type", "custom");
                    it.put("data", BalanceFetcher.customToJson(cs.get(i), true));
                    arr.put(it);
                }
            }
            root.put("platforms", arr);
            return root.toString(2);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 导出单个高级平台 */
    public static String exportOneWeb(Context c, int idx) {
        try {
            List<WebCustom> ws = WebCustom.loadAll(c);
            if (idx < 0 || idx >= ws.size()) return "";
            JSONObject root = new JSONObject();
            root.put("app", MAGIC);
            root.put("version", VERSION);
            root.put("exportedAt", System.currentTimeMillis());
            JSONArray arr = new JSONArray();
            JSONObject it = new JSONObject();
            it.put("type", "web");
            it.put("data", ws.get(idx).toJson());
            arr.put(it);
            root.put("platforms", arr);
            return root.toString(2);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 建议文件名（去掉不能做文件名的字符） */
    public static String suggestName(String base) {
        String s = base == null ? "" : base.trim();
        if (s.length() == 0) s = "balance-platform";
        s = s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (s.length() > 40) s = s.substring(0, 40);
        return s + EXT;
    }

    /* ==================== 解析 / 导入 ==================== */

    /** 解析结果：条数 + 每条的类型与名字（导入前给用户看一眼） */
    public static class Info {
        public int webCount;
        public int customCount;
        public final List<String> names = new ArrayList<String>();
        public int getTotal() { return webCount + customCount; }
    }

    /** 只解析不落库，用于导入前预览 */
    public static Info parse(String text) throws Exception {
        if (text == null || text.trim().length() == 0) throw new Exception("内容为空");
        JSONObject root = new JSONObject(text.trim());
        if (!MAGIC.equals(root.optString("app"))) {
            throw new Exception("这不是平台配置文件（缺少标识）。\n"
                    + "请确认选的是本应用导出的 .bwp 文件。");
        }
        JSONArray arr = root.optJSONArray("platforms");
        if (arr == null || arr.length() == 0) throw new Exception("文件里没有任何平台");
        Info info = new Info();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            String type = it.optString("type", "");
            JSONObject d = it.optJSONObject("data");
            if (d == null) continue;
            String nm = d.optString("name", "");
            if ("web".equals(type)) {
                info.webCount++;
                info.names.add("高级平台 · " + (nm.length() == 0 ? "未命名" : nm));
            } else if ("custom".equals(type)) {
                info.customCount++;
                info.names.add("自定义平台 · " + (nm.length() == 0 ? "未命名" : nm));
            }
        }
        if (info.getTotal() == 0) throw new Exception("文件里没有可识别的平台");
        return info;
    }

    /** 导入结果：新增条数 / 跳过条数 */
    public static class Result {
        public int added;
        public int skipped;
        public String describe() {
            String s = "已导入 " + added + " 个平台";
            if (skipped > 0) s += "，跳过 " + skipped + " 个（已存在）";
            return s;
        }
    }

    /**
     * 真正落库。**同名同网址的视为已存在，跳过**（不会重复添加）。
     * 用"追加"而不是"覆盖"：用户多半是想把别人分享的平台加进来，而不是清空自己的。
     */
    public static Result importText(Context c, String text) throws Exception {
        Info info = parse(text);   // 先校验，格式不对就别动现有数据
        JSONObject root = new JSONObject(text.trim());
        JSONArray arr = root.optJSONArray("platforms");

        List<WebCustom> ws = WebCustom.loadAll(c);
        List<BalanceFetcher.Custom> cs = BalanceFetcher.loadCustom(c);
        Result r = new Result();

        /* 判重只看"名字 + 网址"：同一个平台换个名字还能再导一份，用于多账号场景 */
        java.util.HashSet<String> seenW = new java.util.HashSet<String>();
        for (int i = 0; i < ws.size(); i++) {
            seenW.add(ws.get(i).name + "\u0001" + ws.get(i).url);
        }
        java.util.HashSet<String> seenC = new java.util.HashSet<String>();
        for (int i = 0; i < cs.size(); i++) {
            seenC.add(cs.get(i).name + "\u0001" + cs.get(i).url);
        }

        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            String type = it.optString("type", "");
            JSONObject d = it.optJSONObject("data");
            if (d == null) continue;
            String key = d.optString("name", "") + "\u0001" + d.optString("url", "");
            if ("web".equals(type)) {
                if (seenW.contains(key)) { r.skipped++; continue; }
                ws.add(WebCustom.fromJson(d));
                seenW.add(key);
                r.added++;
            } else if ("custom".equals(type)) {
                if (seenC.contains(key)) { r.skipped++; continue; }
                cs.add(BalanceFetcher.customFromJson(d, false));
                seenC.add(key);
                r.added++;
            }
        }
        WebCustom.saveAll(c, ws);
        BalanceFetcher.saveCustom(c, cs);
        return r;
    }

    /* ==================== 文件进出（SAF） ==================== */

    public static void exportToFile(Activity act, String json, String baseName) {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(MIME);
            i.putExtra(Intent.EXTRA_TITLE, suggestName(baseName));
            pendingJson = json;
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            act.startActivityForResult(i, REQ_EXPORT);
        } catch (Throwable t) {
            toast(act, "打不开保存界面：" + t);
        }
    }

    public static void pickImportFile(Activity act) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");      // 有些文件管理器不认自定义后缀，放宽更稳
            act.startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            toast(act, "打不开选择界面：" + t);
        }
    }

    /** 导出成功后要写的文本（按钮点完 → SAF 回调之间传递） */
    private static String pendingJson = null;

    public static boolean onResult(final Activity act, int req, int res, Intent data) {
        if (req != REQ_EXPORT && req != REQ_IMPORT) return false;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) {
            pendingJson = null;
            return true;
        }
        final Uri u = data.getData();
        if (req == REQ_EXPORT) {
            final String json = pendingJson;
            pendingJson = null;
            if (json == null) return true;
            new Thread(new Runnable() {
                public void run() {
                    String m;
                    try {
                        OutputStream os = act.getContentResolver().openOutputStream(u);
                        os.write(json.getBytes("UTF-8"));
                        os.flush();
                        os.close();
                        m = "已导出";
                    } catch (Throwable t) {
                        m = "写入失败：" + t.getMessage();
                    }
                    final String msg = m;
                    act.runOnUiThread(new Runnable() {
                        public void run() { toast(act, msg); }
                    });
                }
            }).start();
        } else {
            new Thread(new Runnable() {
                public void run() {
                    String m;
                    try {
                        InputStream is = act.getContentResolver().openInputStream(u);
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                        is.close();
                        String text = new String(bo.toByteArray(), "UTF-8");
                        Result r = importText(act, text);
                        m = r.describe();
                    } catch (Throwable t) {
                        m = "导入失败：" + t.getMessage();
                    }
                    final String msg = m;
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            toast(act, msg);
                            BalanceFetcher.onDataRestored(act);   // 清缓存，让新平台立刻出现
                        }
                    });
                }
            }).start();
        }
        return true;
    }

    /* ==================== 分享 / 剪贴板 ==================== */

    /** 分享为文本（微信/QQ 直接发，对方粘贴导入）。文件分享要 FileProvider，不值得。 */
    public static void share(Activity act, String title, String json) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, title);
            i.putExtra(Intent.EXTRA_TEXT, json);
            act.startActivity(Intent.createChooser(i, "分享平台配置"));
        } catch (Throwable t) {
            toast(act, "分享失败：" + t);
        }
    }

    public static void copyToClipboard(Activity act, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("平台配置", text));
            toast(act, "已复制，粘贴给对方即可");
        } catch (Throwable t) {
            toast(act, "复制失败：" + t);
        }
    }

    /** 从剪贴板导入（对方发的是文本时用） */
    public static String clipboardText(Activity act) {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) return "";
            CharSequence s = cm.getPrimaryClip().getItemAt(0).getText();
            return s == null ? "" : s.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_LONG).show();
    }
}
