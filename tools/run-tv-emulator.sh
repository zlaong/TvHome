#!/usr/bin/env bash
# 启动一台 1080p 的 Android TV 模拟器，用来在 Mac 上预览桌面效果
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AVD_NAME="tv1080"
IMAGE="system-images;android-34;android-tv;arm64-v8a"

if ! "$SDK/emulator/emulator" -list-avds | grep -qx "$AVD_NAME"; then
  echo "第一次使用，正在创建模拟器 $AVD_NAME …"
  echo "no" | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd \
    -n "$AVD_NAME" -k "$IMAGE" -d tv_1080p --force
fi

# 打开「Mac 键盘当遥控器」：方向键 = 上下左右，回车 = 确认，Esc = 返回
CONFIG="$HOME/.android/avd/$AVD_NAME.avd/config.ini"
if grep -q '^hw.keyboard=' "$CONFIG"; then
  sed -i '' 's/^hw.keyboard=.*/hw.keyboard=yes/' "$CONFIG"
else
  echo "hw.keyboard=yes" >> "$CONFIG"
fi

exec "$SDK/emulator/emulator" -avd "$AVD_NAME" \
  -gpu swiftshader_indirect \
  -no-snapshot-save \
  -no-boot-anim \
  -no-audio