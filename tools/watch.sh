#!/bin/sh
# balance-widget 变更监听 + 自动同步推送
#
#   sh tools/watch.sh [轮询间隔秒，默认 5]
#
# 用途：边改边同步。每隔几秒给源码目录做一次「修改时间 + 大小」快照，
# 快照变了就 tar 同步到 git 仓库并推送。
#
# 注意：这是给「人在电脑/终端前反复改代码」用的辅助工具。
# Minis 里 shell_execute 每次都是独立进程，**别指望后台常驻脚本能一直活** ——
# 应用被挂起后进程就没了，定时/常驻类需求要用 minis-scheduled 或系统级闹钟。
# 日常发布直接用 tools/release.sh。
set -e

SRC=/var/minis/mounts/AIWord/projects/balance-widget
REPO=/var/minis/shared/balance-widget
SLUG=hesanyue50-lang/balance-widget
INTERVAL="${1:-5}"
STAMP=/tmp/balance-widget-stamp

[ -n "$GITHUB_TOKEN" ] || { echo "❌ 缺少 GITHUB_TOKEN 环境变量"; exit 1; }

EXCLUDE="--exclude=./build --exclude=./*.apk --exclude=./*.idsig --exclude=./.git --exclude=./.buildcount --exclude=./tools/probe.jar"

log() { echo "[$(date '+%H:%M:%S')] $*"; }

snapshot() {
    find "$SRC" -type f \
        ! -path '*/build/*' ! -name '*.apk' ! -name '*.idsig' \
        ! -path '*/.git/*' ! -name 'probe.jar' ! -name '.buildcount' \
        -exec stat -c '%Y %s %n' {} \; 2>/dev/null | sort
}

changed() {
    [ -f "$STAMP" ] || { snapshot > "$STAMP"; return 1; }
    local cur
    cur=$(snapshot)
    [ "$cur" != "$(cat "$STAMP")" ] || return 1
    echo "$cur" > "$STAMP"
    return 0
}

do_sync() {
    cd "$REPO"
    tar $EXCLUDE -cf - -C "$SRC" . | tar -xf - 2>/dev/null
    git diff --quiet && git diff --cached --quiet && { log "无实质变更"; return; }
    git add -A
    [ -f BalanceWidget.apk ] && git add -f BalanceWidget.apk
    git commit -q -m "自动同步 $(date '+%Y-%m-%d %H:%M')" >/dev/null 2>&1
    if git push "https://x-access-token:$GITHUB_TOKEN@github.com/$SLUG.git" HEAD:main \
            >/dev/null 2>&1; then
        log "✅ 已推送"
    else
        log "❌ 推送失败（下次变更会重试）"
    fi
}

trap 'log "停止监听"; rm -f "$STAMP"; exit 0' INT TERM

log "监听 $SRC（每 ${INTERVAL}s 检查一次，Ctrl+C 退出）"
snapshot > "$STAMP"

while true; do
    sleep "$INTERVAL"
    if changed; then
        log "检测到变更，同步中…"
        do_sync
    fi
done
