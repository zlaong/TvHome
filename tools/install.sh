#!/usr/bin/env bash
# 编译并安装到当前连接的设备（模拟器或电视），改一行代码跑这一条就够了
# 默认装正式版：调试版关掉了代码优化，电视上动画明显更卡。要看调试日志时加 --debug
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE="./gradlew"
[ -x "$GRADLE" ] || GRADLE="../.tools/gradle-8.13/bin/gradle"

if [ "${1:-}" = "--debug" ]; then
  $GRADLE assembleDebug -q
  APK=app/build/outputs/apk/debug/app-debug.apk
else
  $GRADLE assembleRelease -q
  APK=app/build/outputs/apk/release/app-release.apk
fi
adb install -r "$APK"
# 投屏：电视停在别的应用里时，收到照片/视频要能切到前台
adb shell appops set com.zlaong.tvhome SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
adb shell am start -n com.zlaong.tvhome/.MainActivity >/dev/null
echo "已推送到设备并打开桌面"
