#!/usr/bin/env bash
# 给 release 包签名。
#
# 为什么需要这一步：app/build.gradle.kts 里**没有** signingConfig，
# `assembleRelease` 产出的是未签名包（apksigner verify 会直接报 no signature）。
#
# 用的证书必须始终是同一张：`~/.android/debug.keystore`（CN=Android Debug，
# SHA-256 1c:71:bb:bf:2b:f2:5d:b1:3b:2a:7e:8b:f6:79:93:e9:95:92:12:51:37:e4:e4:7d:0a:56:62:24:d5:43:09:6a）。
# 换证书 = 老用户无法覆盖升级，只能卸载重装（数据全丢）。所以这张 keystore 一定要备份。
#
# 用法：
#   ./release/sign-apk.sh 0.5.1
# 可选环境变量：
#   KEYSTORE        密钥库路径（默认 ~/.android/debug.keystore）
#   KS_PASS         库口令（默认 android）
#   KEY_ALIAS       别名（默认 androiddebugkey）
#   KEY_PASS        私钥口令（默认同 KS_PASS）
#   ANDROID_BUILD_TOOLS  build-tools 目录（默认走 .toolchain 里那个）

set -euo pipefail

VERSION="${1:?用法: ./release/sign-apk.sh 0.5.1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UNSIGNED="$ROOT/android/app/build/outputs/apk/release/iptv-player-${VERSION}.apk"
OUT="$ROOT/release/apk/iptv-player-${VERSION}.apk"

KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${KEY_PASS:-$KS_PASS}"
BT="${ANDROID_BUILD_TOOLS:-/c/Users/Lik/WorkBuddy/.toolchain/android-sdk/build-tools/34.0.0}"

[ -f "$UNSIGNED" ] || { echo "找不到 $UNSIGNED，先跑 assembleRelease"; exit 1; }
[ -f "$KEYSTORE" ] || { echo "找不到密钥库 $KEYSTORE"; exit 1; }
APKSIGNER="$BT/apksigner.bat"
[ -f "$APKSIGNER" ] || APKSIGNER="$BT/apksigner"
[ -f "$APKSIGNER" ] || { echo "找不到 apksigner（$BT）"; exit 1; }

echo "签名 $VERSION：$(basename "$KEYSTORE") -> $OUT"
"$APKSIGNER" sign \
  --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --out "$OUT" "$UNSIGNED"

# 必须验一遍：签名不完整的包安装时才会报错，那时候已经发给用户了
"$APKSIGNER" verify --print-certs "$OUT" | grep -E "DN|SHA-256 digest" | head -2
echo "大小: $(stat -c%s "$OUT") 字节"
md5sum "$OUT"
