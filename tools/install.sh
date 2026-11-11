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
# 首页图标下面的「还在后台」小点：要读系统的任务列表，这两个权限普通应用拿不到，只能这样给
adb shell pm grant com.zlaong.tvhome android.permission.DUMP >/dev/null 2>&1 || true
adb shell pm grant com.zlaong.tvhome android.permission.PACKAGE_USAGE_STATS >/dev/null 2>&1 || true
# 正式版：让电视把整个桌面提前编译成机器码。默认只按使用记录慢慢优化，刚装好时动画偏涩
# 调试版不做：调试版本身没开优化，编译了也没用
if [ "${1:-}" != "--debug" ]; then
  echo "正在提前编译（约半分钟）…"
  adb shell cmd package compile -m speed -f com.zlaong.tvhome >/dev/null 2>&1 || echo "提前编译失败，不影响使用"
fi
adb shell am start -n com.zlaong.tvhome/.MainActivity >/dev/null
echo "已推送到设备并打开桌面"
