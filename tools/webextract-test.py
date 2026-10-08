#!/usr/bin/env python3
"""
balance-widget 高级自定义平台 —— 提取逻辑验证脚本
=================================================
用途：在把「高级自定义平台」配置进 App 之前，先在电脑/沙箱上验证提取规则能不能抠出值。
把 App 里的提取模式（auto/json/regex/between/text）在这里跑一遍，结果一致。

用法：
    python3 tools/webextract-test.py <网址> [模式] [参数...]
    python3 tools/webextract-test.py https://api.example.com/status json data.balance
    python3 tools/webextract-test.py https://example.com regex '余额[:：]\\s*([0-9.]+)'
    python3 tools/webextract-test.py https://example.com between '余额:' '元'
    python3 tools/webextract-test.py https://example.com auto

也支持离线测试（直接喂文本）：
    python3 tools/webextract-test.py --text '{"data":{"balance":12.34}}' json data.balance

提取规则与 App 内 WebCustom.java 一一对应：
    auto     : 先按 JSON（BAL 关键字段→深度找数）→ 再按正则（页面上第一串数字）
    json     : 点分路径，支持数组下标，| 分隔多候选
    regex    : 有捕获组取组1，否则取整个匹配
    between  : 左右标记截取后抠数字
    text     : 去标签整页展示
"""
import json
import re
import sys
import urllib.request

BAL_KEYS = [
    "total_balance", "totalBalance", "available_balance", "availableBalance",
    "balance", "cash_balance", "cashBalance", "chargeBalance",
    "remaining", "remain", "left", "amount", "value", "credit", "quota",
]


def http_get(url, method="GET", headers=None, body=None, charset="UTF-8", timeout=15):
    req = urllib.request.Request(url, method=method)
    req.add_header("User-Agent",
                   "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
    req.add_header("Accept", "*/*")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    data = body.encode("UTF-8") if body else None
    with urllib.request.urlopen(req, data=data, timeout=timeout) as r:
        return r.read().decode(charset, errors="replace")


def to_number(s):
    if s is None:
        return None
    t = str(s).strip().replace(",", "").replace("，", "").replace(" ", "")
    if not t:
        return None
    try:
        return float(t)
    except ValueError:
        pass
    m = re.search(r"-?\d+(?:\.\d+)?", str(s))
    return float(m.group()) if m else None


def deep_find_num(o, depth=0):
    """递归找第一个数字值（与 App 的 deepFind 对应）"""
    if depth > 6:
        return None
    if isinstance(o, dict):
        for k in BAL_KEYS:
            if k in o:
                v = to_number(o[k])
                if v is not None:
                    return v
        for v in o.values():
            r = deep_find_num(v, depth + 1)
            if r is not None:
                return r
    elif isinstance(o, list):
        for v in o:
            r = deep_find_num(v, depth + 1)
            if r is not None:
                return r
    elif isinstance(o, (int, float)):
        return float(o)
    return None


def json_path(o, path):
    """点分路径（支持数组下标），与 App 的 jsonPath 对应"""
    cur = o
    for seg in path.split("."):
        seg = seg.strip()
        if isinstance(cur, list):
            try:
                idx = int(seg)
            except ValueError:
                cur = next((e for e in cur if isinstance(e, dict) and seg in e), None)
                continue
            if idx < 0 or idx >= len(cur):
                return None
            cur = cur[idx]
        elif isinstance(cur, dict):
            if seg not in cur:
                return None
            cur = cur[seg]
        else:
            return None
    if isinstance(cur, bool):
        return 1.0 if cur else 0.0
    if isinstance(cur, (int, float)):
        return float(cur)
    if isinstance(cur, str):
        try:
            return float(cur)
        except ValueError:
            return None
    return None


def by_json(raw, path):
    t = raw.strip()
    if not (t.startswith("{") or t.startswith("[")):
        return None, None
    try:
        o = json.loads(t)
    except ValueError:
        return None, None
    for p in [p.strip() for p in path.split("|") if p.strip()]:
        v = json_path(o, p)
        if v is not None:
            return v, "json: %s" % p
    v = deep_find_num(o)
    if v is not None:
        return v, "json: 自动识别"
    return None, None


def by_regex(raw, pattern):
    if not pattern:
        return None, None
    m = re.search(pattern, raw)
    if not m:
        return None, None
    s = m.group(1) if m.groups() else m.group(0)
    v = to_number(s)
    return (v, "regex: %s" % pattern[:24]) if v is not None else (None, None)


def by_between(raw, start, end):
    if not start and not end:
        return None, None
    s = raw
    if start:
        i = s.find(start)
        if i < 0:
            return None, None
        s = s[i + len(start):]
    if end:
        j = s.find(end)
        if j < 0:
            return None, None
        s = s[:j]
    v = to_number(s.strip())
    return (v, "between: %s…%s" % (start[:12], end[:12])) if v is not None else (None, None)


def by_text(raw):
    s = re.sub(r"<[^>]+>", " ", raw)
    s = re.sub(r"\s+", " ", s).strip()
    return (s or "（空）"), "text"


def extract(raw, mode="auto", path="", pattern="", start="", end=""):
    if mode == "json":
        return by_json(raw, path)
    if mode == "regex":
        return by_regex(raw, pattern)
    if mode == "between":
        return by_between(raw, start, end)
    if mode == "text":
        return by_text(raw)
    # auto
    t = raw.strip()
    if t.startswith("{") or t.startswith("["):
        v, how = by_json(raw, path)
        if v is not None:
            return v, how
    if pattern:
        v, how = by_regex(raw, pattern)
        if v is not None:
            return v, how
    m = re.search(r"\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+\.\d+", raw)
    if m:
        v = to_number(m.group())
        if v is not None:
            return v, "auto: 页面数字"
    return None, None


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(0)
    if args[0] == "--text":
        raw = args[1]
        rest = args[2:]
    else:
        url, rest = args[0], args[1:]
        method = "GET"
        headers = {}
        body = None
        charset = "UTF-8"
        i = 0
        while i < len(rest) and rest[i].startswith("--"):
            opt = rest.pop(i)
            if opt == "--method":
                method = rest.pop(i).upper()
            elif opt == "--header":
                k, _, v = rest.pop(i).partition(":")
                headers[k.strip()] = v.strip()
            elif opt == "--body":
                body = rest.pop(i)
            elif opt == "--charset":
                charset = rest.pop(i)
            else:
                i += 1
        raw = http_get(url, method, headers, body, charset)
    mode = rest[0] if rest else "auto"
    params = rest[1:]
    path = ""
    pattern = ""
    start = ""
    end = ""
    if mode == "json":
        path = params[0] if params else ""
    elif mode == "regex":
        pattern = params[0] if params else ""
    elif mode == "between":
        start = params[0] if params else ""
        end = params[1] if len(params) > 1 else ""
    elif mode == "auto":
        # auto 允许带 json 路径提示
        path = params[0] if params else ""

    v, how = extract(raw, mode, path, pattern, start, end)
    if v is None:
        print("❌ 未识别。原文开头：", re.sub(r"\s+", " ", raw)[:160])
        sys.exit(1)
    print("✅ 值 = %s   （%s）" % (v, how))


if __name__ == "__main__":
    main()
