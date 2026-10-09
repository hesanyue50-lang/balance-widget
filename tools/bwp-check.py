#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
校验一个 .bwp 平台配置文件是否合法。

    python3 tools/bwp-check.py 我的平台.bwp

为什么要这个：导入失败时 App 只给一句"这不是平台配置文件"，
用户手上多半是别人发来的文本、中间被聊天软件截断或者被加了前后缀。
这个脚本能把具体哪一条、哪个字段有问题指出来。

格式说明（也就是一个普通 JSON）：
{
  "app": "balance-widget-platform",     ← 必须
  "version": 1,
  "exportedAt": 1760000000000,
  "platforms": [
     {"type": "web",    "data": {...}},   ← 高级自定义平台
     {"type": "custom", "data": {...}}    ← 普通自定义平台
  ]
}
"""
import json
import sys

MAGIC = "balance-widget-platform"

# 各类型必填字段（没有这两项，导进去也是一张空卡片）
REQUIRED = {
    "web": ["name", "url"],
    "custom": ["name", "url"],
}

# 有默认值的字段（列出便于人工核对，缺了不算错）
OPTIONAL = {
    "web": ["mode", "path", "pattern", "start", "end", "method", "body", "headers",
            "charset", "numMode", "scale", "template", "kind", "unit", "suffix",
            "foreign", "threshold"],
    "custom": ["key", "path", "unit", "kind", "suffix", "text", "threshold"],
}


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    path = sys.argv[1]
    try:
        raw = open(path, encoding="utf-8").read()
    except Exception as e:
        print("❌ 读不了文件：%s" % e)
        return 1

    # 聊天软件转发常见问题：前后带说明文字
    t = raw.strip()
    if not t.startswith("{"):
        i = t.find("{")
        if i < 0:
            print("❌ 内容里找不到 JSON（是不是被截断了？）")
            return 1
        print("⚠️  开头有额外文字，已自动跳过 %d 个字符" % i)
        t = t[i:]

    try:
        root = json.loads(t)
    except Exception as e:
        print("❌ JSON 解析失败：%s" % e)
        print("   常见原因：复制时被聊天软件截断（结尾少了 } 或 ]）")
        return 1

    if root.get("app") != MAGIC:
        print("❌ 不是平台配置文件：app 字段是 %r，应为 %r"
              % (root.get("app"), MAGIC))
        return 1

    plats = root.get("platforms")
    if not isinstance(plats, list) or not plats:
        print("❌ platforms 是空的")
        return 1

    ok = True
    print("✅ 格式合法，共 %d 个平台\n" % len(plats))
    for i, item in enumerate(plats):
        typ = item.get("type")
        data = item.get("data") or {}
        nm = data.get("name") or "(未命名)"
        if typ not in REQUIRED:
            print("  [%d] ❌ 未知类型 %r（应为 web 或 custom）" % (i + 1, typ))
            ok = False
            continue
        miss = [f for f in REQUIRED[typ] if not data.get(f)]
        flag = "❌ 缺字段: %s" % ", ".join(miss) if miss else "✅"
        if miss:
            ok = False
        kind = "高级平台" if typ == "web" else "自定义平台"
        line = "  [%d] %s  %s %s" % (i + 1, flag, kind, nm)
        if typ == "web":
            line += "  （模式 %s）" % data.get("mode", "auto")
        print(line)
        if data.get("url"):
            print("       网址 %s" % data["url"])
        if typ == "web" and data.get("headers"):
            print("       ⚠️ 含自定义请求头（可能有明文 Cookie/Token）")
        if typ == "custom" and data.get("key") and data["key"] not in ("", "在这里填密钥"):
            print("       ⚠️ 含明文密钥")

    print()
    print("✅ 全部通过" if ok else "❌ 有问题，见上面标红的行")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
