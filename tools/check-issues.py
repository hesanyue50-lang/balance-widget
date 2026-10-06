#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查 balance-widget 仓库的新 Issue / 新评论，并列出「等你回复」的条目。

用法：
    python3 tools/check-issues.py            # 人类可读的简报
    python3 tools/check-issues.py --json     # 结构化输出

状态文件默认 /var/minis/shared/bw-issues/state.json，只记录上次成功检查的时间，
用它算增量。首次运行回看 7 天。检查失败时**不推进**时间戳，避免漏掉内容。

鉴权：读环境变量 GITHUB_TOKEN。没有也能跑（匿名限速 60 次/小时）。
"""
import datetime
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request

SLUG = "hesanyue50-lang/balance-widget"
ME = "hesanyue50-lang"
STATE = pathlib.Path(os.environ.get("BW_ISSUE_STATE", "/var/minis/shared/bw-issues/state.json"))
TOKEN = os.environ.get("GITHUB_TOKEN", "")
FIRST_LOOKBACK_DAYS = 7
MAX_WAIT_CHECK = 20          # 最多逐个查多少个 issue 的回复状态（省 API 次数）
BODY_PREVIEW = 140


def api(path):
    req = urllib.request.Request("https://api.github.com/repos/%s%s" % (SLUG, path))
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "bw-issue-check")
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def now_iso():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def load_since():
    """上次成功检查的时间。没有就从 FIRST_LOOKBACK_DAYS 天前开始。"""
    try:
        return json.loads(STATE.read_text())["last_run"]
    except Exception:
        t = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=FIRST_LOOKBACK_DAYS)
        return t.strftime("%Y-%m-%dT%H:%M:%SZ")


def save_state(when):
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps({"last_run": when}, indent=2))


def short(t, n):
    t = " ".join((t or "").split())
    return t if len(t) <= n else t[:n] + "…"


TZ = datetime.timezone(datetime.timedelta(hours=8))   # 北京时间


def fmt_time(s):
    """2026-10-07T10:12:33Z → 10-07 18:12（北京时间）"""
    try:
        d = datetime.datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc)
        return d.astimezone(TZ).strftime("%m-%d %H:%M")
    except Exception:
        return s


def main():
    as_json = "--json" in sys.argv
    if not TOKEN:
        print("⚠️  未设置 GITHUB_TOKEN，走匿名请求（限速 60 次/小时）", file=sys.stderr)

    since = load_since()
    check_time = now_iso()

    # ---------- 拉数据 ----------
    try:
        issues = api("/issues?state=all&sort=created&direction=desc&per_page=100")
    except urllib.error.HTTPError as e:
        print("❌ 拉取 Issue 失败：HTTP %s" % e.code)
        return 1
    except Exception as e:
        print("❌ 网络错误：%s" % e)
        return 1

    issues = [i for i in issues if "pull_request" not in i]

    try:
        comments = api("/issues/comments?since=%s&sort=created&direction=desc&per_page=100" % since)
    except Exception:
        comments = []
    comments = [c for c in comments if c["created_at"] > since and c["user"]["login"] != ME]

    new_issues = [i for i in issues if i["created_at"] > since and i["user"]["login"] != ME]

    # ---------- 等回复 ----------
    waiting = []
    for i in [x for x in issues if x["state"] == "open"][:MAX_WAIT_CHECK]:
        try:
            cs = api("/issues/%d/comments?per_page=100" % i["number"])
        except Exception:
            continue
        if not cs:
            waiting.append((i, None))          # 还没人回复过
        elif cs[-1]["user"]["login"] != ME:
            waiting.append((i, cs[-1]))        # 最后一条不是我 → 该我回

    # ---------- 输出 ----------
    if as_json:
        print(json.dumps({
            "since": since, "checked_at": check_time,
            "new_issues": [{"number": i["number"], "title": i["title"],
                            "user": i["user"]["login"], "created_at": i["created_at"],
                            "url": i["html_url"], "body": i.get("body") or ""} for i in new_issues],
            "new_comments": [{"issue": int(c["issue_url"].rsplit("/", 1)[-1]),
                              "user": c["user"]["login"], "created_at": c["created_at"],
                              "body": c["body"] or "", "url": c["html_url"]} for c in comments],
            "waiting": [{"number": i["number"], "title": i["title"],
                         "last_from": (l or {}).get("user", {}).get("login") if l else None,
                         "url": i["html_url"]} for i, l in waiting],
        }, ensure_ascii=False, indent=2))
        save_state(check_time)
        return 0

    out = []
    out.append("📬 balance-widget Issue 巡检")
    out.append("   检查时间 %s ｜ 上次 %s" % (fmt_time(check_time), fmt_time(since)))
    out.append("")

    if new_issues:
        out.append("🆕 新 Issue（%d）" % len(new_issues))
        for i in new_issues:
            out.append("   #%-4d %s" % (i["number"], short(i["title"], 60)))
            out.append("         @%s  %s" % (i["user"]["login"], fmt_time(i["created_at"])))
            if i.get("body"):
                out.append("         「%s」" % short(i["body"], BODY_PREVIEW))
            out.append("         %s" % i["html_url"])
        out.append("")

    if comments:
        out.append("💬 新评论（%d）" % len(comments))
        for c in comments:
            n = c["issue_url"].rsplit("/", 1)[-1]
            out.append("   #%s @%s  %s" % (n, c["user"]["login"], fmt_time(c["created_at"])))
            out.append("         「%s」" % short(c["body"], BODY_PREVIEW))
        out.append("")

    if waiting:
        out.append("⏳ 等你回复（%d）" % len(waiting))
        for i, last in waiting:
            who = ("@%s" % last["user"]["login"]) if last else "还没有人回复"
            out.append("   #%-4d %s  ← 最后一条来自 %s" % (i["number"], short(i["title"], 50), who))
            out.append("         %s" % i["html_url"])
        out.append("")

    if not (new_issues or comments):
        out.append("✅ 没有新 Issue、也没有新评论。")
        if not waiting:
            out.append("   （当前也没有等待回复的条目）")

    print("\n".join(out))
    save_state(check_time)
    return 0


if __name__ == "__main__":
    sys.exit(main())
