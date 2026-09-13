#!/bin/sh
# 本地编译 API 余额小组件 APK（沙盒内，靠 qemu 跑 x86_64 的 aapt2/zipalign）
set -e

BT=/opt/android-sdk/build-tools/34.0.0
AJ=/opt/android-sdk/platforms/android-36/android.jar
KS=/opt/android-dev/keystore/debug.keystore
SRC="$(cd "$(dirname "$0")" && pwd)"
OUT="$SRC/build"
PKG=com.minis.balancewidget

export PATH="$BT:$PATH"

# ---- 资源自检 ----
# 这些 9-patch 是 tools/gen_assets.py 画出来的，不是手工资源。
# 一旦被误删，编译会报一堆 "resource not found"，很难一眼看出原因。
# 所以这里先检查，缺了就自动重画。
if [ ! -f "$SRC/res/drawable-nodpi/card_bg.9.png" ] \
   || [ ! -f "$SRC/res/drawable-nodpi/btn_bg.9.png" ] \
   || [ ! -f "$SRC/res/drawable-night-nodpi/card_bg.9.png" ] \
   || [ ! -f "$SRC/res/drawable-nodpi/wg_glass_l.9.png" ]; then
  echo "[0/7] 资源缺失 → 自动重新生成"
  python3 "$SRC/tools/gen_assets.py" || true
fi

# ---- 版本号自动生成 ----
# 规则：年份后两位 . 月份 . 累计构建次数   （如 26.9.110）
# 版本名不再手写 —— 手写容易忘记改、也容易和构建次数对不上。
COUNT_FILE="$SRC/.buildcount"
if [ -f "$COUNT_FILE" ]; then
  COUNT=$(cat "$COUNT_FILE")
else
  COUNT=1
fi
CODE=$((COUNT + 1))
echo "$CODE" > "$COUNT_FILE"
VNAME="$(date +%y).$(date +%-m).$CODE"
echo "版本: $VNAME (versionCode=$CODE)"

# 写回 manifest（编译前改，aapt2 才会带上）
sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$CODE\"/" "$SRC/AndroidManifest.xml"
sed -i "s/android:versionName=\"[0-9.]*\"/android:versionName=\"$VNAME\"/" "$SRC/AndroidManifest.xml"

echo "[1/7] 清理"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/gen"

echo "[2/7] aapt2 compile 资源"
aapt2x compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "[3/7] aapt2 link"
aapt2x link -o "$OUT/base.apk" -I "$AJ" \
  --manifest "$SRC/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --auto-add-overlay

echo "[4/7] javac"
javac -source 8 -target 8 -bootclasspath "$AJ" -d "$OUT/classes" \
  $(find "$SRC/src" "$OUT/gen" -name '*.java') 2>&1 | grep -v 'warning:' || true
CNT=$(find "$OUT/classes" -name '*.class' | wc -l)
[ "$CNT" -gt 0 ] || { echo "编译失败：无 class 产出"; exit 1; }
echo "      class 数: $CNT"

echo "[5/7] d8 -> dex"
d8x --lib "$AJ" --min-api 24 --output "$OUT" $(find "$OUT/classes" -name '*.class') 2>&1 | tail -3 || true
[ -f "$OUT/classes.dex" ] || { echo "d8 未产出 dex"; exit 1; }

echo "[6/7] 打包 + 对齐"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
( cd "$OUT" && zip -q -j unaligned.apk classes.dex )
zipalignx -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "[7/7] 签名"
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$SRC/$PKG.apk" "$OUT/aligned.apk"

ls -l "$SRC/$PKG.apk"
apksigner verify "$SRC/$PKG.apk" && echo "✅ 签名验证通过"
