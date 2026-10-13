#!/bin/sh
# balance-widget 并行构建入口（加速版）
#
# 实测：原 build.sh 33.5s，其中 aapt2 compile 单独就 8.9s
#       （18 个 9-patch 在 qemu 下极慢，且串行）。
# 本脚本把资源编译换成文件级分片并行 + 桶级增量缓存：
#   - 冷缓存 compile 8.9s → 3.0s
#   - 热缓存 compile 8.9s → 0.11s（9-patch 不变时直接复用）
# 产物与串行**逐字节等价**（已验证 dex / resources.arsc / manifest 的 md5 一致）。
#
# 用法: sh build-fast.sh [--jobs N] [--no-cache]
# --no-cache: 忽略资源缓存全量重编（改了 res 但 hash 没变时用，一般不会遇到）
set -e
SRC="$(cd "$(dirname "$0")" && pwd)"
PB=/var/minis/shared/pbuild/pbuild.py
LIBSO=/var/minis/shared/clash/libmihomo.so

# ---- 版本号（与原 build.sh 同一套规则，共用 .buildcount 避免版本号分叉）----
COUNT_FILE="$SRC/.buildcount"
COUNT=$(cat "$COUNT_FILE" 2>/dev/null || echo 1)
CODE=$((COUNT + 1))
echo "$CODE" > "$COUNT_FILE"
VNAME="$(date +%y).$(date +%-m).$CODE"
echo "版本: $VNAME (versionCode=$CODE)"
sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$CODE\"/" "$SRC/AndroidManifest.xml"
sed -i "s/android:versionName=\"[0-9.]*\"/android:versionName=\"$VNAME\"/" "$SRC/AndroidManifest.xml"

# ---- 9-patch 资源自检（与原脚本一致：这些是脚本生成的，缺了要重画）----
if [ ! -f "$SRC/res/drawable-nodpi/card_bg.9.png" ] \
   || [ ! -f "$SRC/res/drawable-nodpi/btn_bg.9.png" ] \
   || [ ! -f "$SRC/res/drawable-night-nodpi/card_bg.9.png" ] \
   || [ ! -f "$SRC/res/drawable-nodpi/wg_glass_l.9.png" ]; then
  echo "[0] 资源缺失 → 自动重新生成"
  python3 "$SRC/tools/gen_assets.py" || true
fi

SO=""
[ -f "$LIBSO" ] && SO="--so arm64-v8a=$LIBSO"
[ -z "$SO" ] && echo "⚠️ 未找到 $LIBSO —— 本次产物不含 Clash 内核"

exec python3 "$PB" --project "$SRC" \
  --out "$SRC/com.minis.balancewidget.apk" \
  --keystore /opt/android-dev/keystore/debug.keystore --ks-pass android \
  --min-api 24 $SO "$@"
