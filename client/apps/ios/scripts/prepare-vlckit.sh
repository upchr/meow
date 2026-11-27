#!/usr/bin/env bash
# 准备 iOS 端内 VLCKit（MobileVLCKit，LGPLv2.1）。
#
# 用途：web 的 <video> 播不了 MKV/AVI/FLV/DTS/10-bit 时的**原生兜底**
# （与 TV 的 openNative 同构；实现见 Meow/NativeVlcPlayer.swift）。
# iOS 不能 fork/exec，服务端 ffmpeg 那条路不存在，只能把解码器静态链进 App。
#
# 产物（不进 git，CI 缓存 + 打进 App）：
#   client/apps/ios/build/vlckit/MobileVLCKit.framework
#
# 归一化：上游 3.5+ 是 xcframework（含模拟器 slice + dSYM，tar.xz 约 230MB），
# 脚本只抽 arm64 真机那一份，统一成 build/vlckit/MobileVLCKit.framework，
# project.yml 只认这一个路径 —— 上游换打包形态不用改工程文件。
#
# 用法：bash client/apps/ios/scripts/prepare-vlckit.sh
# 环境变量：VLCKIT_URL 覆盖下载地址（镜像 / 离线缓存）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/build"
URL="${VLCKIT_URL:-https://download.videolan.org/pub/cocoapods/prod/MobileVLCKit-3.7.5-a213fd11-6de05adc.tar.xz}"

CACHE="$BUILD/vlckit-cache"
TAR="$CACHE/$(basename "$URL")"
STAGE="$CACHE/stage"
OUT="$BUILD/vlckit"

mkdir -p "$CACHE"
if [ ! -s "$TAR" ]; then
  echo "[vlc] downloading $URL"
  curl -fL --retry 4 --retry-all-errors -o "$TAR.part" "$URL"
  mv "$TAR.part" "$TAR"
fi
echo "[vlc] archive $(du -h "$TAR" | cut -f1)"

rm -rf "$STAGE" "$OUT"
mkdir -p "$STAGE" "$OUT"

echo "[vlc] extracting"
tar xf "$TAR" -C "$STAGE"

# 只取真机 slice：模拟器 slice 进包会让体积翻倍，而 IPA 只发 device。
# 上游 slice 命名随版本变（3.7 之后已无 armv7），按可能性依次尝试。
SRC=""
for cand in \
  "MobileVLCKit.xcframework/ios-arm64_armv7/MobileVLCKit.framework" \
  "MobileVLCKit.xcframework/ios-arm64/MobileVLCKit.framework" \
  "MobileVLCKit.framework"; do
  if [ -d "$STAGE/$cand" ]; then SRC="$STAGE/$cand"; break; fi
done
if [ -z "$SRC" ]; then
  SRC="$(find "$STAGE" -type d -name 'MobileVLCKit.framework' -path '*arm64*' | head -1 || true)"
fi
if [ -z "$SRC" ] || [ ! -d "$SRC" ]; then
  echo "[vlc] MobileVLCKit.framework (arm64) not found; archive layout:" >&2
  find "$STAGE" -maxdepth 4 -type d | head -30 >&2
  exit 1
fi

echo "[vlc] framework: $SRC"
cp -R "$SRC" "$OUT/MobileVLCKit.framework"

# fat framework 里若还有 armv7，裁掉：deploymentTarget 15.0 用不到，白占体积。
BIN="$OUT/MobileVLCKit.framework/MobileVLCKit"
if [ -f "$BIN" ]; then
  echo "[vlc] arch before: $(lipo -info "$BIN" 2>/dev/null | tail -1 || echo unknown)"
  if lipo -info "$BIN" 2>/dev/null | grep -q 'armv7 '; then
    lipo -thin arm64 "$BIN" -output "$BIN.arm64"
    mv "$BIN.arm64" "$BIN"
    echo "[vlc] thinned to arm64"
  fi
fi

echo "[vlc] done: $(du -sh "$OUT/MobileVLCKit.framework" 2>/dev/null | cut -f1)"
