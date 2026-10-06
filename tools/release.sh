#!/bin/sh
# balance-widget 一键发布：编译 → 同步 → 提交 → 推送 GitHub
#
#   sh tools/release.sh "提交说明"        编译 + 发布
#   sh tools/release.sh -n "提交说明"     跳过编译，只发布（改的只是文档之类）
#
# 为什么需要「同步」这一步：
#   AIWord 是 FUSE 挂载卷，git objects 写不进去 —— 所以源码母本在 AIWord，
#   但 git 仓库必须放在沙盒真实文件系统（/var/minis/shared/），两边靠 tar 同步。
#
# ⚠️ 因此：**改本脚本必须改 AIWord 里的那一份**（AIWord 是母本）。
#    若只改仓库副本，下次跑它就会用 AIWord 的旧版把自己覆盖掉。
#
# Token 从环境变量 GITHUB_TOKEN 读，**不写进 .git/config**：
#   写进去的话，一旦 token 轮换就会以「Authentication failed」的形式神秘失败，
#   而且整个目录被拷贝时会把明文凭证一起带走。这里改成推送时临时拼接。
#
# 关于网络：沙盒访问 github.com 不稳定（有时 7s 握手成功，有时直接连不上，
#   而 api.github.com 却一直正常）。所以推送**自动重试**，别让一次抖动白跑一轮。
set -e

SRC=/var/minis/mounts/AIWord/projects/balance-widget
REPO=/var/minis/shared/balance-widget
SLUG=hesanyue50-lang/balance-widget
TRIES=3

# ⚠️ 本脚本只发源码。APK 不进 git（二进制会把仓库撑爆），
#    编完另跑：sh tools/release-apk.sh   （上传到 GitHub Release）

DO_BUILD=1
if [ "$1" = "-n" ] || [ "$1" = "--no-build" ]; then
    DO_BUILD=0
    shift
fi

[ -n "$GITHUB_TOKEN" ] || { echo "❌ 缺少 GITHUB_TOKEN 环境变量"; exit 1; }
[ -d "$SRC" ] || { echo "❌ 源码目录不存在：$SRC"; exit 1; }

# ---------- ① 编译 ----------
if [ "$DO_BUILD" = "1" ]; then
    echo "🔨 编译…"
    cd "$SRC"
    sh build.sh || { echo "❌ 编译失败"; exit 1; }
fi

# ---------- ② 同步到仓库 ----------
cd "$REPO"
echo "📦 同步源码…"
tar --exclude='./build' --exclude='./*.apk' --exclude='./*.idsig' \
    --exclude='./.git' --exclude='./.buildcount' --exclude='./tools/probe.jar' \
    -cf - -C "$SRC" . | tar -xf -

# ---------- ③ 提交 ----------
# ⚠️ APK 不再进 git（二进制放 GitHub Release，用 tools/release-apk.sh 上传）。
#    历史里曾经每个提交都塞一份 24MB 的 APK，把 .git 撑到 500MB，已清理。
MSG="${1:-同步 $(date '+%Y-%m-%d %H:%M')}"
git add -A
if git diff --cached --quiet; then
    echo "✅ 无变更，跳过提交"
    exit 0
fi

VER=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | head -1 | cut -d'"' -f2)
git commit -q -m "$MSG"$'\n\n'"versionName=$VER"
echo "📝 已提交：$MSG (versionName=$VER)"

# ---------- ④ 推送（带重试） ----------
# 两个坑：
#   ① 别把 git 的输出直接接管道 —— 管道的退出码是 sed 的，git 失败也会被判成成功；
#      所以重定向到文件，再显式看退出码。
#   ② github.com 在沙盒里可达性抖动，一次失败不代表凭证有问题，重试通常就好。
URL="https://x-access-token:$GITHUB_TOKEN@github.com/$SLUG.git"
LOG=/tmp/bw-push.log
i=1
while [ "$i" -le "$TRIES" ]; do
    echo "🚀 推送（第 $i/$TRIES 次）…"
    if git push "$URL" HEAD:main >"$LOG" 2>&1; then
        grep -v '^remote:' "$LOG" | sed "s/$GITHUB_TOKEN/<token>/g"
        rm -f "$LOG"
        echo "✅ 完成"
        exit 0
    fi
    sed "s/$GITHUB_TOKEN/<token>/g" "$LOG" | tail -3
    i=$((i + 1))
    [ "$i" -le "$TRIES" ] && { echo "⏳ 5 秒后重试…"; sleep 5; }
done
rm -f "$LOG"
echo "❌ 推送失败 $TRIES 次（改动已本地提交，网络恢复后重跑本脚本即可）"
exit 1
