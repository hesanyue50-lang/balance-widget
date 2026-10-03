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
# Token 从环境变量 GITHUB_TOKEN 读，**不写进 .git/config**：
#   写进去的话，一旦 token 轮换就会以「Authentication failed」的形式神秘失败，
#   而且整个目录被拷贝时会把明文凭证一起带走。这里改成推送时临时拼接。
set -e

SRC=/var/minis/mounts/AIWord/projects/balance-widget
REPO=/var/minis/shared/balance-widget
SLUG=hesanyue50-lang/balance-widget
APK=com.minis.balancewidget.apk

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

# APK 单独复制（上面排除了 *.apk，这里显式更新）
if [ -f "$SRC/$APK" ]; then
    cp -f "$SRC/$APK" BalanceWidget.apk
fi

# ---------- ③ 提交 ----------
MSG="${1:-同步 $(date '+%Y-%m-%d %H:%M')}"
git add -A
[ -f BalanceWidget.apk ] && git add -f BalanceWidget.apk   # .gitignore 排除 *.apk，必须 -f

if git diff --cached --quiet; then
    echo "✅ 无变更，跳过提交"
    exit 0
fi

VER=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | head -1 | cut -d'"' -f2)
git commit -q -m "$MSG"$'\n\n'"versionName=$VER"
echo "📝 已提交：$MSG (versionName=$VER)"

# ---------- ④ 推送 ----------
echo "🚀 推送…"
git push "https://x-access-token:$GITHUB_TOKEN@github.com/$SLUG.git" HEAD:main \
    2>&1 | sed "s/$GITHUB_TOKEN/<token>/g"
echo "✅ 完成"
