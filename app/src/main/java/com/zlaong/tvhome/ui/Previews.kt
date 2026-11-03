package com.zlaong.tvhome.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.zlaong.tvhome.data.AppConfig
import com.zlaong.tvhome.data.HomeAlert
import com.zlaong.tvhome.data.HomeDevice
import com.zlaong.tvhome.data.HomeEnv
import com.zlaong.tvhome.data.HomeUi
import com.zlaong.tvhome.data.HourForecast
import com.zlaong.tvhome.data.InstalledApp
import com.zlaong.tvhome.data.PrinterUi
import com.zlaong.tvhome.data.WeatherUi

/**
 * Android Studio 里点右上角的分屏图标即可看到效果，不需要装到电视上。
 * 尺寸按 1080p 电视算：960 x 540 dp。
 */

private fun demoOtherApps(): List<InstalledApp> = listOf(
    "文件管理", "相册", "音乐", "Google Play", "当贝市场", "我的电视",
).map { name ->
    InstalledApp(label = name, packageName = name, icon = null, banner = null, intent = Intent(), isLive = name == "我的电视")
}

private val demoWeather = WeatherUi(
    loading = false,
    city = "上海",
    temp = 13.0,
    high = 17.0,
    low = 11.0,
    humidity = 62,
    code = 2,
    text = "多云",
    windDir = "东北风",
    windLevel = 2,
    hours = listOf(HourForecast(14, 15.0, 2), HourForecast(16, 16.0, 3), HourForecast(18, 14.0, 61), HourForecast(20, 12.0, 3)),
    updatedAt = System.currentTimeMillis(),
)

private val demoPrinter = PrinterUi(
    reachable = true,
    online = true,
    name = "Bambu Lab X2D",
    state = "RUNNING",
    stateText = "打印中",
    jobName = "Benchy_0.2mm_PLA_X2D",
    progress = 63,
    remainingSec = 2 * 3600 + 13 * 60,
    layerNum = 132,
    totalLayers = 210,
)

private val demoHome = HomeUi(
    reachable = true,
    devices = listOf(
        HomeDevice("1", "吸顶灯", "light", online = true, on = true, brightness = 80, stateText = "80%"),
        HomeDevice("2", "空气净化器", "fan", online = true, on = true, brightness = null, stateText = "自动"),
        HomeDevice("3", "空调", "climate", online = true, on = false, brightness = null),
    ),
    env = HomeEnv(temp = 21.5, humidity = 47.0, pm25 = 12.0, co2 = 552.0),
    alerts = listOf(HomeAlert("次卧门窗 开着", 1), HomeAlert("客厅空气净化器 滤芯剩 8%，该换了", 2)),
)

@Composable
private fun DemoHome(nav: Nav, printer: PrinterUi = demoPrinter) {
    val context = LocalContext.current
    val apps = remember { DemoData.videoApps(context) + demoOtherApps() }
    val groups = remember { buildGroups(apps, emptyList(), listOf("Google Play")) }
    val config = AppConfig()
    HomeContent(
        data = HomeData(
            weather = demoWeather,
            printer = printer,
            home = demoHome,
            now = System.currentTimeMillis(),
            net = Net.Wifi,
            media = apps.filter { it.isVideo },
            lastVideo = apps.firstOrNull { it.isVideo }?.packageName,
            groups = groups,
            printerHost = config.printerHost,
            homeHost = config.homeHostOrDefault,
        ),
        rows = drawerRows(groups),
        nav = nav,
        wallpaper = remember { Wallpaper.load(context) },
    )
}

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun HomePreview() = DemoHome(remember { Nav() })

@Preview(widthDp = 960, heightDp = 540, name = "打印机离线")
@Composable
private fun HomePreviewOffline() = DemoHome(
    remember { Nav().apply { row = Area.CARD; card = 1 } },
    printer = PrinterUi(reachable = false, error = "连不上 Mac mini"),
)

@Preview(widthDp = 960, heightDp = 540, name = "全部应用")
@Composable
private fun DrawerPreview() = DemoHome(remember { Nav().apply { row = Area.DRAWER; expanded = true } })

@Preview(widthDp = 960, heightDp = 540, name = "设置页")
@Composable
private fun SettingsPreview() {
    SettingsScreen(config = AppConfig(), onSaved = {})
}
