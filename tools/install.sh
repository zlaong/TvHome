#!/usr/bin/env bash
# 编译并安装到当前连接的设备（模拟器或电视），改一行代码跑这一条就够了
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE="./gradlew"
[ -x "$GRADLE" ] || GRADLE="../.tools/gradle-8.13/bin/gradle"

$GRADLE assembleDebug -q
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 投屏：电视停在别的应用里时，收到照片/视频要能切到前台
adb shell appops set com.zlaong.tvhome SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
adb shell am start -n com.zlaong.tvhome/.MainActivity >/dev/null
echo "已推送到设备并打开桌面"