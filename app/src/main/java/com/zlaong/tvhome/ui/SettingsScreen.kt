package com.zlaong.tvhome.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zlaong.tvhome.BuildConfig
import com.zlaong.tvhome.data.AppConfig
import com.zlaong.tvhome.data.AppSettings
import com.zlaong.tvhome.data.ConfigServer
import com.zlaong.tvhome.data.DistrictRepo
import com.zlaong.tvhome.data.WallpaperRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class Section(val label: String, val icon: TvIcon) {
    System("系统设置", TvIcon.Gear),
    Wallpaper("壁纸", TvIcon.Picture),
    Weather("天气", TvIcon.Sun),
    Home("家里设备", TvIcon.House),
    Remote("手机 / 电脑设置", TvIcon.Phone),
}

/**
 * 设置页：左边分类，右边内容。遥控器在左边上下移动，右边跟着切换；按右键进入内容。
 * 所有改动立即生效，没有「保存 / 取消」。返回键回到桌面。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(config: AppConfig, onSaved: () -> Unit) {
    val context = LocalContext.current
    var section by remember { mutableStateOf(Section.Wallpaper) }
    val navFocus = remember { Section.entries.associateWith { FocusRequester() } }

    // 进来时焦点放在「系统设置」上
    LaunchedEffect(Unit) { runCatching { navFocus.getValue(Section.System).requestFocus() } }

    val wallVer by WallpaperRepo.version.collectAsState()
    val wallpaper by produceState<WallpaperImages?>(null, wallVer) {
        value = withContext(Dispatchers.Default) { Wallpaper.load(context) }
    }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        wallpaper?.let {
            Image(it.full, null, contentScale = ContentScale.Crop, alpha = 0.35f, modifier = Modifier.fillMaxSize())
        }
        Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 36.dp, bottom = 24.dp)) {
            Column(
                Modifier
                    .width(230.dp)
                    .fillMaxHeight()
                    .focusGroup()
                    // 从右边内容按左键回来时，落在当前分类上，而不是离得最近的那一项
                    .focusProperties { enter = { navFocus.getValue(section) } },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("设置", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.textPrimary)
                Spacer(Modifier.height(14.dp))
                Section.entries.forEach { s ->
                    NavItem(
                        s,
                        selected = s == section,
                        modifier = Modifier.focusRequester(navFocus.getValue(s)),
                        onFocus = { if (s != Section.System) section = s },
                        onClick = {
                            if (s == Section.System) {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                        },
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("返回键回到桌面 · 改动立即生效", fontSize = 12.sp, color = Palette.textDim)
                Text("版本 ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = Palette.textDim)
            }
            Spacer(Modifier.width(36.dp))
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0xCC0E121A), RoundedCornerShape(20.dp))
                    .padding(horizontal = 28.dp, vertical = 22.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(section.label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Palette.textPrimary)
                Spacer(Modifier.height(4.dp))
                when (section) {
                    Section.System, Section.Wallpaper -> WallpaperPanel()
                    Section.Weather -> WeatherPanel(config)
                    Section.Home -> HomePanel(config)
                    Section.Remote -> RemotePanel()
                }
            }
        }
    }
}

@Composable
private fun NavItem(s: Section, selected: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val fg = if (focused) Tv.dark else if (selected) Palette.textPrimary else Palette.textSecondary
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onClick)
            .background(
                when {
                    focused -> Color.White
                    selected -> Color.White.copy(alpha = 0.10f)
                    else -> Color.Transparent
                },
                shape,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(s.icon, 22.dp, fg)
        Text(s.label, fontSize = 18.sp, color = fg, modifier = Modifier.weight(1f))
        if (s == Section.System) Text("›", fontSize = 20.sp, color = fg)
    }
}

// ------------------------------------------------------------ 天气

@Composable
private fun WeatherPanel(config: AppConfig) {
    var city by remember { mutableStateOf(config.cityName) }
    Text("首页左上角的天气卡片用哪里的天气", fontSize = 14.sp, color = Palette.textSecondary)
    TvGroup("位置")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TvRow(
            "自动定位",
            desc = "按家里网络的位置估算，只能到城市，区在下面选" + (config.autoCity.takeIf { it.isNotBlank() }?.let { "；当前：$it" } ?: ""),
            checked = config.weatherAuto,
        ) { AppSettings.save(config.printerHost, config.cityName, weatherAuto = true) }
        TvRow("手动设置城市", desc = "定位不准时用", checked = !config.weatherAuto) {
            AppSettings.save(config.printerHost, config.cityName, weatherAuto = false)
        }
    }
    if (!config.weatherAuto) {
        Spacer(Modifier.height(16.dp))
        TvTextField(
            label = "城市",
            value = city,
            onValueChange = { city = it },
            hint = "例：杭州、上海、北京（区在下面选）",
            onCommit = { if (city.isNotBlank()) AppSettings.save(AppSettings.state.value.printerHost, city) },
        )
    }
    DistrictPicker(config)
}

/** 城市太大时选到区：郊区和市中心最低温能差好几度 */
@Composable
private fun DistrictPicker(config: AppConfig) {
    val cityName = config.weatherCity
    if (cityName.isBlank()) return
    // null = 正在查；空列表 = 查不到
    val list by produceState<List<DistrictRepo.District>?>(null, cityName) {
        value = DistrictRepo.districts(cityName).orEmpty()
    }
    val chosen = config.activeDistrict?.first
    TvGroup("区县")
    when {
        list == null -> Text("正在查 $cityName 的区县…", fontSize = 14.sp, color = Palette.textSecondary)
        list!!.isEmpty() -> Text("查不到 $cityName 的区县列表，先按城市中心显示", fontSize = 14.sp, color = Palette.textSecondary)
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvRow("不选，用城市中心", checked = chosen == null) { AppSettings.saveDistrict(cityName, "", null, null) }
            list!!.forEach { d ->
                TvRow(d.name, checked = chosen == d.name) { AppSettings.saveDistrict(cityName, d.name, d.lat, d.lon) }
            }
        }
    }
}

// ------------------------------------------------------------ 手机 / 电脑

/** 扫码打开的设置网页地址；配对码放在 # 后面，网页自动配对 */
@Composable
internal fun rememberSetupLink(page: String = ""): Pair<String?, String> {
    val code by ConfigServer.pairingCode.collectAsState()
    var base by remember { mutableStateOf(ConfigServer.url()) }
    // 网络可能晚于设置页就绪，隔几秒重新取一次地址
    LaunchedEffect(Unit) {
        while (true) {
            base = ConfigServer.url()
            delay(3_000)
        }
    }
    return base?.let { "$it/#code=$code" + if (page.isNotEmpty()) "&s=$page" else "" } to code
}

@Composable
private fun RemotePanel() {
    val (link, code) = rememberSetupLink()
    val base = ConfigServer.url()
    Text("在手机或电脑上改设置，比用遥控器打字方便。Home Assistant 令牌很长，建议在这里填。", fontSize = 14.sp, color = Palette.textSecondary)
    Spacer(Modifier.height(16.dp))
    QrCard(link, "手机扫码", listOf("用相机扫左边的二维码", "打开网页后自动配对，不用输配对码"))
    TvGroup("电脑浏览器")
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
            .padding(18.dp),
    ) {
        Text("在浏览器地址栏输入", fontSize = 14.sp, color = Palette.textSecondary)
        Text(base ?: "电视没有联网", fontSize = 26.sp, fontWeight = FontWeight.Medium, color = Palette.textPrimary)
        Spacer(Modifier.height(10.dp))
        Text("配对码", fontSize = 14.sp, color = Palette.textSecondary)
        Text(code, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.accent, letterSpacing = 6.sp)
        Text("配对一次后这台设备会被记住；配对码用过就会换新的", fontSize = 12.sp, color = Palette.textDim)
    }
    // 让焦点能进到这一页，否则按右键没反应
    Spacer(Modifier.height(16.dp))
    TvRow("网页能改什么", desc = "壁纸（含上传照片）、天气、Mac mini 地址、Home Assistant 和首页显示的设备") {}
}
