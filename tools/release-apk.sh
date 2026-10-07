#!/bin/sh
# 把编译好的 APK 上传到 GitHub Release（二进制不进 git 历史）
#
#   sh tools/release-apk.sh                      标签取 v<versionName>，说明自动生成
#   sh tools/release-apk.sh v26.10.370 "更新说明"  指定标签与说明
#
# 为什么要单独一个脚本：
#   二进制进 git 历史会让仓库无限膨胀（本仓库历史里曾塞了几十份 24MB 的 APK，
#   把 .git 撑到 500MB）。所以编译产物一律走 Release 资产，源码走 git。
#
# 前置：先 sh build.sh 编译出 APK；环境变量 GITHUB_TOKEN 必须可用。
set -e

SRC=/var/minis/mounts/AIWord/projects/balance-widget
SLUG=hesanyue50-lang/balance-widget
APK=$SRC/com.minis.balancewidget.apk
ASSET=BalanceWidget.apk
API=https://api.github.com/repos/$SLUG

# 沙盒直连 github 常年不稳，先确保代理可用（curl 认 http(s)_proxy 环境变量）。
if command -v vpnup >/dev/null 2>&1; then
    if vpnup --fast >/dev/null 2>&1 || vpnup >/dev/null 2>&1; then
        export http_proxy=http://127.0.0.1:7891
        export https_proxy=http://127.0.0.1:7891
        echo "🌐 走代理上传（$https_proxy）"
    fi
fi
TRIES=3

[ -n "$GITHUB_TOKEN" ] || { echo "❌ 缺少 GITHUB_TOKEN 环境变量"; exit 1; }
[ -f "$APK" ] || { echo "❌ 找不到 APK：$APK（先跑 sh build.sh）"; exit 1; }

VER=$(grep -o 'versionName="[^"]*"' "$SRC/AndroidManifest.xml" | head -1 | cut -d'"' -f2)
TAG="${1:-v$VER}"
NOTES="${2:-版本 $VER}"
MB=$(awk -v s="$(wc -c < "$APK")" 'BEGIN{printf "%.1f", s/1048576}')

echo "📦 $ASSET  $VER  ${MB}MB"
echo "🏷  标签 $TAG"

AUTH="Authorization: Bearer $GITHUB_TOKEN"
ACC="Accept: application/vnd.github+json"

# ---------- ① 找 Release，没有就建 ----------
RID=$(curl -s -m 30 -H "$AUTH" -H "$ACC" "$API/releases/tags/$TAG" \
      | python3 -c "import json,sys
try: print(json.load(sys.stdin).get('id',''))
except Exception: print('')")

if [ -z "$RID" ]; then
    echo "🆕 创建 Release $TAG"
    BODY=$(python3 -c "
import json,sys
print(json.dumps({'tag_name':sys.argv[1],'name':sys.argv[1],
                  'body':sys.argv[2],'target_commitish':'main'}))
" "$TAG" "$NOTES")
    RID=$(curl -s -m 30 -X POST -H "$AUTH" -H "$ACC" -d "$BODY" "$API/releases" \
          | python3 -c "import json,sys
try: print(json.load(sys.stdin).get('id',''))
except Exception: print('')")
fi
[ -n "$RID" ] || { echo "❌ 创建/定位 Release 失败"; exit 1; }
echo "   release id = $RID"

# ---------- ② 同名旧资产先删（Release 资产不能覆盖） ----------
OLD=$(curl -s -m 30 -H "$AUTH" -H "$ACC" "$API/releases/$RID/assets" \
      | python3 -c "import json,sys
try:
    print(' '.join(str(a['id']) for a in json.load(sys.stdin) if a['name'].endswith('.apk')))
except Exception: print('')")
for aid in $OLD; do
    echo "🗑  删除旧资产 $aid"
    curl -s -m 30 -X DELETE -H "$AUTH" -H "$ACC" "$API/releases/assets/$aid" >/dev/null
done

# ---------- ③ 上传（24MB 在弱网下容易断，重试） ----------
i=1
while [ "$i" -le "$TRIES" ]; do
    echo "⬆️  上传（第 $i/$TRIES 次）…"
    CODE=$(curl -s -m 900 -X POST \
        -H "$AUTH" \
        -H "Content-Type: application/vnd.android.package-archive" \
        --data-binary "@$APK" \
        -o /tmp/relapk.json -w '%{http_code}' \
        "https://uploads.github.com/repos/$SLUG/releases/$RID/assets?name=$ASSET") || CODE=000

    if [ "$CODE" = "201" ]; then
        URL=$(python3 -c "import json;print(json.load(open('/tmp/relapk.json')).get('browser_download_url',''))")
        echo "✅ 上传成功"
        echo "   发布页：https://github.com/$SLUG/releases/tag/$TAG"
        echo "   直链：  $URL"
        exit 0
    fi
    echo "   HTTP $CODE"
    [ -f /tmp/relapk.json ] && head -c 300 /tmp/relapk.json && echo
    i=$((i + 1))
    [ "$i" -le "$TRIES" ] && { echo "⏳ 5 秒后重试…"; sleep 5; }
done

echo "❌ 上传失败 $TRIES 次"
exit 1
