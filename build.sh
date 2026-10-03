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
# ⚠️ 必须看 javac 的**退出码**，不能只看「有没有 class 产出」。
#    曾经的写法是 `javac ... | grep -v warning || true` 再数 class 个数 ——
#    管道把退出码换成了 grep 的，于是「部分文件编译失败」会被静默放过：
#    产出里确实有 class（其他类编出来了），但缺掉的那个类一运行就
#    NoClassDefFoundError 闪退。真机上排查这种崩溃非常费劲，这里必须堵死。
if ! javac -source 8 -target 8 -bootclasspath "$AJ" -d "$OUT/classes" \
      $(find "$SRC/src" "$OUT/gen" -name '*.java') > "$OUT/javac.log" 2>&1; then
  echo "❌ javac 编译失败："
  grep -v '^Note:\|warning:' "$OUT/javac.log" | head -30
  exit 1
fi
CNT=$(find "$OUT/classes" -name '*.class' | wc -l)
[ "$CNT" -gt 0 ] || { echo "编译失败：无 class 产出"; exit 1; }
echo "      class 数: $CNT"

echo "[5/7] d8 -> dex"
d8x --lib "$AJ" --min-api 24 --output "$OUT" $(find "$OUT/classes" -name '*.class') 2>&1 | tail -3 || true
[ -f "$OUT/classes.dex" ] || { echo "d8 未产出 dex"; exit 1; }

echo "[6/7] 打包 + 对齐"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
( cd "$OUT" && zip -q -j unaligned.apk classes.dex )

# ---- 内置 Clash 内核（mihomo）----
# 必须以 lib/<abi>/xxx.so 的形式放进 APK：Android 10 起应用**不能执行私有可写目录**
# 里的文件，唯一可执行的位置就是系统从 APK 释放出来的 nativeLibraryDir。
# 所以内核借用了 .so 这个身份，运行时按 nativeLibraryDir + "/libmihomo.so" 拼路径执行。
# 内核本体不放在工程目录里（61MB，会拖垮同步与 git），放沙盒共享区按需取。
LIBSO=/var/minis/shared/clash/libmihomo.so
if [ -f "$LIBSO" ]; then
  mkdir -p "$OUT/lib/arm64-v8a"
  cp "$LIBSO" "$OUT/lib/arm64-v8a/libmihomo.so"
  # -1 快速压缩：61MB 的二进制用 -9 压要多花几十秒，而体积只差几 MB；
  # extractNativeLibs 打开时系统会在安装阶段解压，所以压缩存储没有副作用。
  ( cd "$OUT" && zip -q -1 unaligned.apk lib/arm64-v8a/libmihomo.so )
  echo "      已打入 Clash 内核 arm64-v8a ($(du -h "$LIBSO" | cut -f1))"
else
  echo "      ⚠️ 未找到 $LIBSO —— 本次产物不含 Clash 内核（代理功能不可用）"
fi

zipalignx -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "[7/7] 签名"
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$SRC/$PKG.apk" "$OUT/aligned.apk"

ls -l "$SRC/$PKG.apk"
apksigner verify "$SRC/$PKG.apk" && echo "✅ 签名验证通过"
