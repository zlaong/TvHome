# 家里桌面（TvHome）

替换电视自带首页的 Android TV 桌面。打开电视就能看到天气、3D 打印机、米家设备状态，常用的影视 App 一键进入。

![首页](docs/screenshot-home.png)
![米家](docs/screenshot-mijia.png)
![天气](docs/screenshot-weather.png)

## 功能

**首页**
- 顶栏：左边设置、手机投屏、网络、时间；右边 Mac mini、3D 打印机、米家三个状态点（绿=正常，黄=打印中，红=断开），选中时展开显示文字
- 左上角信息卡：天气 / 米家（接了 3D 打印机多一页），平时只露当前那页；按上键进卡片区，所有卡片平铺成一排，左右选择，确认键进对应的全屏页，按下键回到影视行
- 点播行：已安装的影视 App 用 16:9 横幅排一行，上次打开的那个带「上次观看」角标
- 已经在桌面时再按 HOME 键，回到首页初始状态

**米家全屏页**
- 左边是室内温度、湿度、PM2.5、CO₂ 和需要注意的提醒（如滤芯快用完）
- 右边按房间列出所有设备，确认键开关，长按确认键调节（亮度、温度、风速等）
- 控制需要接 Home Assistant；只接设备桥时只能看状态

**天气全屏页**
- 当前温度、体感、湿度、风、空气质量、紫外线、PM2.5、能见度、日出日落
- 未来 24 小时温度曲线和 7 天预报

**全部应用**（在点播行按「下」打开）
- 扫到的应用都在（含影视、直播），一个列表排下来
- 长按确认键或按菜单键：打开 / 移动位置 / 移到最前 / 应用信息 / 卸载（仅自己装的应用）
- 移动位置：方向键挪，确认键放下，返回键取消；顺序会记住，新装的应用排在最后

**手机投屏**
- 顶栏「手机投屏」弹出二维码，iPhone 相机扫码用 Safari 打开，选照片或视频后电视全屏播放，手机上能暂停、继续、停止。iPhone 不用装 App

**设置页**

| 分类 | 能改什么 |
|------|----------|
| 系统设置 | 跳到电视自带的系统设置（顶栏齿轮长按也能直接进） |
| 壁纸 | 自带壁纸 / 必应每日壁纸 / 必应近 8 天任选一张 / 手机上传照片 |
| 天气 | 城市 |
| 家里设备 | Home Assistant 地址和令牌、要显示的设备；或设备桥地址 |
| 手机 / 电脑设置 | 在浏览器打开 `http://<电视IP>:8686`，输入电视上显示的 6 位配对码后改设置。长令牌建议在这里填 |

## 先决条件

| 项 | 要求 |
|----|------|
| 电视 | 原生 Android TV（索尼 / 雷鸟 / TCL 等），Android 7.0 及以上 |
| 3D 打印机状态（可选） | Mac mini 上跑着 PrintPilot 后端 |
| 米家（可选，二选一） | Home Assistant + xiaomi_miot 集成（能看能控）；或 Mac mini 上跑 `tools/mijia-bridge`（只支持 Yeelight 灯） |
| 网络 | 电视和上述设备在同一个局域网 |

## 编译和安装

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

adb connect <电视IP>:5555      # 电视先开「开发者选项 → 网络调试」
./tools/install.sh             # 编译正式版并装到电视；要看调试日志加 --debug
```

装好后按遥控器 Home 键，在弹出的选择框里选「家里桌面」并勾「始终」。没弹框的话：设置 → 应用 → 默认应用 → 主屏幕 → 选择「家里桌面」。

## 预览方式

| 方式 | 怎么做 | 适合 |
|------|--------|------|
| Mac 上跑电视模拟器 | `./tools/run-tv-emulator.sh`，打开一台 1080p 安卓电视 | 想在电脑上自己点 |
| Android Studio 静态预览 | 打开 `app/src/main/java/com/zlaong/tvhome/ui/Previews.kt`，右上角切到 Split | 改样式时 |
| 真机截图 | `adb exec-out screencap -p > screen.png`（电视要先唤醒，不然是黑屏） | 看真实效果 |

## 首次配置

顶栏齿轮进「设置」：
- 天气：填城市（例 `杭州`）
- 家里设备：填 Home Assistant 地址和长期访问令牌（在 Home Assistant 头像 →「安全」里创建），再勾要显示的设备
- 没有 Home Assistant 时，填设备桥地址；留空则用 Mac mini 的 IP + `:8080`

## 数据来源

| 数据 | 来源 | 刷新 |
|------|------|------|
| 天气 | `api.open-meteo.com`，免费无 Key | 定时 |
| 3D 打印机 | `http://<Mac mini>:3000/api/status`，只读；Mac mini 状态点也看这个接口通不通 | 每 3 秒 |
| 米家（Home Assistant） | Home Assistant 的 `/api/states`，令牌只存在电视上 | 定时 |
| 米家（设备桥） | `http://<设备桥>:8080/api/home` | 每 10 秒 |
| 室内环境图标 | [Material Design Icons](https://pictogrammers.com/library/mdi/)（与 Home Assistant 同款，Apache 2.0） | — |
| 壁纸 | 自带《星空下的雪山》（[Unsplash](https://unsplash.com/photos/1519681393784-d120267933ba)，Unsplash License）或 `cn.bing.com` 每日壁纸 | 每天 |

## 免责声明

个人自用项目，非任何厂商或影视平台的官方产品，与奇异果 TV、芒果 TV、云视听、酷喵、CIBN、哔哩哔哩等平台没有合作或关联关系。桌面上出现的应用图标和横幅仅用于标识本机已安装的应用，商标与版权归各自权利人所有（详见 [THIRD-PARTY.md](THIRD-PARTY.md)）。请自行确认在所在地区使用的合法性后再安装。

## 安全说明

| 事项 | 情况 |
|------|------|
| 手机改设置的服务 | 电视上开 `8686` 端口的明文 HTTP，**只在局域网内可达**，首次访问要输电视屏幕上的 6 位配对码；输错 5 次会自动换码，配对成功后这台浏览器记住一年 |
| Home Assistant 令牌 | 存在电视本地配置里（未加密），不会上传到任何地方；电视被他人物理接触时可能被读走 |
| 内网地址 | 电视、Mac mini、设备桥的地址都只保存在电视上，不会上报 |
| 不要公开的内容 | 提 issue 或分享截图时，请遮掉令牌、电视 IP、配对码和房间里的设备清单 |

## 权限说明

| 权限 | 用来做什么 |
|------|-----------|
| `INTERNET` / `ACCESS_NETWORK_STATE` | 拉天气、家里设备状态；顶栏显示网络状态 |
| `SYSTEM_ALERT_WINDOW` | 手机投屏时，电视停在其他应用里也能切到前台播放 |
| `REQUEST_DELETE_PACKAGES` | 长按应用 → 卸载 |
| `REORDER_TASKS` | 点图标时把后台里的应用原样切回前台，不重新启动 |
| `DUMP` / `PACKAGE_USAGE_STATS` | 首页应用下面的「还在后台」小点。普通应用申请不到，靠安装时 adb 授予；没授权就不显示小点，不影响其他功能 |
| 设为默认桌面 | 按 HOME 键直接回到这个桌面 |

## 目录

| 路径 | 内容 |
|------|------|
| `app/` | 电视桌面 App（Kotlin + Jetpack Compose） |
| `tools/install.sh` | 编译并热装到电视 |
| `tools/run-tv-emulator.sh` | 启动 Android TV 模拟器 |
| `tools/mijia-bridge/` | 局域网 Yeelight 设备桥（Node.js，`node server.js` 启动） |
| `docs/` | 截图和设计稿 |

## 下一步可做

- 打印机控制（暂停 / 继续 / 取消），需要给 PrintPilot 后端加下发通道

## 许可证

[Apache License 2.0](LICENSE)。第三方组件、图标和图片的许可见 [THIRD-PARTY.md](THIRD-PARTY.md)。
