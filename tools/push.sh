#!/bin/sh
# balance-widget 一键同步 + 推送 GitHub
#
#   sh tools/push.sh "提交说明"
#
# 做三件事：
#   ① 从 AIWord（源码母本）同步到 git 仓库 —— AIWord 是 FUSE 卷，git objects 写不进去，
#      所以仓库必须放沙盒真实文件系统，两边靠 tar 同步；
#   ② 把刚编译出的 APK 覆盖为 BalanceWidget.apk（.gitignore 排除 *.apk，须 git add -f）；
#   ③ 提交并推送。
#
# Token 从环境变量 GITHUB_TOKEN 读，**不写进 .git/config** ——
# 否则 token 会以明文躺在 .git/config 里，被整个目录拷贝时一起带走。
set -e

SRC=/var/minis/mounts/AIWord/projects/balance-widget
REPO=/var/minis/shared/balance-widget
SLUG=hesanyue50-lang/balance-widget

[ -n "$GITHUB_TOKEN" ] || { echo "❌ 缺少 GITHUB_TOKEN 环境变量"; exit 1; }
[ -d "$SRC" ] || { echo "❌ 源码目录不存在：$SRC"; exit 1; }

cd "$REPO"

# ① 同步源码（排除编译产物与本地状态）
tar --exclude='./build' --exclude='./*.apk' --exclude='./*.idsig' \
    --exclude='./.git' --exclude='./.buildcount' \
    -cf - -C "$SRC" . | tar -xf -

# ② APK（有就更新，没有就沿用仓库里已有的）
if [ -f "$SRC/com.minis.balancewidget.apk" ]; then
    cp -f "$SRC/com.minis.balancewidget.apk" BalanceWidget.apk
fi

MSG="${1:-同步 $(date '+%Y-%m-%d %H:%M')}"
git add -A
[ -f BalanceWidget.apk ] && git add -f BalanceWidget.apk

if git diff --cached --quiet; then
    echo "无变更，无需提交"
    exit 0
fi

git commit -q -m "$MSG"
git push "https://x-access-token:$GITHUB_TOKEN@github.com/$SLUG.git" HEAD:main
echo "✅ 已推送：$MSG"
