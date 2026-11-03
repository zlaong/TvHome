package com.zlaong.tvhome.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent as AKey
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.zlaong.tvhome.BuildConfig
import com.zlaong.tvhome.data.AppConfig
import com.zlaong.tvhome.data.AppRepo
import com.zlaong.tvhome.data.AppSettings
import com.zlaong.tvhome.data.CastRepo
import com.zlaong.tvhome.data.Feeds
import com.zlaong.tvhome.data.WallpaperRepo
import com.zlaong.tvhome.data.HdmiSource
import com.zlaong.tvhome.data.InputRepo
import com.zlaong.tvhome.data.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(config: AppConfig, onOpenSettings: () -> Unit, resets: Flow<Unit> = emptyFlow()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nav = remember { Nav() }
    val focus = remember { FocusRequester() }

    // 卡片数据和刷新节奏在 Feeds 里（进设置页时这个界面会被销毁，状态不能放这里）
    val weather by Feeds.weather.collectAsState()
    val printer by Feeds.printer.collectAsState()
    val home by Feeds.home.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var net by remember { mutableStateOf(Net.Wifi) }
    var resumed by remember { mutableIntStateOf(0) }
    val pinned by AppSettings.pinned.collectAsState()
    val lastVideo by AppSettings.lastVideo.collectAsState()

    val wallpaperVer by WallpaperRepo.version.collectAsState()
    val wallpaper by produceState<WallpaperImages?>(null, wallpaperVer) {
        value = withContext(Dispatchers.Default) { Wallpaper.load(context) }
    }

    // 每次回到桌面都重新扫一遍：装/卸了应用、HDMI 设备开关机都能及时反映
    LifecycleResumeEffect(Unit) {
        resumed++
        runCatching { focus.requestFocus() }
        // 每日壁纸：过了零点回到桌面就换今天的
        scope.launch { WallpaperRepo.refreshDaily() }
        onPauseOrDispose { }
    }

    var apps by remember { mutableStateOf(emptyList<InstalledApp>()) }
    var sources by remember { mutableStateOf(emptyList<HdmiSource>()) }
    var appsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(resumed) {
        val (a, s) = withContext(Dispatchers.IO) {
            val real = runCatching { AppRepo.loadApps(context) }.getOrDefault(emptyList())
            val all = if (BuildConfig.DEBUG && AppSettings.demoVideoApps) DemoData.videoApps(context) + real else real
            AppRepo.warmBitmaps(all)
            all to runCatching { InputRepo.load(context) }.getOrDefault(emptyList())
        }
        apps = a
        sources = s
        appsLoaded = true
    }

    // 第一次装好：「常用」先放几个默认的（装了才放），之后完全以用户的选择为准
    LaunchedEffect(apps, pinned) {
        if (pinned == null && apps.isNotEmpty()) {
            val installed = apps.map { it.packageName }.toSet()
            AppSettings.setPinned(DEFAULT_PINNED.filter { it in installed })
        }
    }

    // 卡片全屏页（天气 / 米家）
    val detail = remember { DetailCtl(scope) { nav.toast(it) } }
    LaunchedEffect(detail.page) {
        Feeds.setHomeDetailOpen(detail.page == CardPage.Home)
        if (detail.page == CardPage.Weather) Feeds.touchWeather()
    }
    LaunchedEffect(home) {
        detail.prune()
        // 设备变少时焦点别停在不存在的格子上
        val g = gridRows(home.rooms)
        if (g.isNotEmpty()) {
            detail.row = detail.row.coerceIn(0, g.lastIndex)
            detail.col = detail.col.coerceIn(0, g[detail.row].things.lastIndex)
        }
    }

    LaunchedEffect(Unit) { resets.collect { nav.reset(); detail.close() } }
    // 手机开始投了，二维码弹窗就没用了
    LaunchedEffect(Unit) { CastRepo.state.collect { if (it !is CastRepo.State.Idle) nav.castQr = false } }
    var webSetupUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            net = currentNet(context)
            webSetupUrl = com.zlaong.tvhome.data.ConfigServer.url()
            delay(5_000)
        }
    }
    // 刷新节奏见 Feeds。翻到米家页时马上刷新（10 秒内刚拉过就不重复）
    LaunchedEffect(nav.card) {
        if (CARD_PAGES.getOrNull(nav.card) == CardPage.Home) Feeds.touchHome()
    }
    LaunchedEffect(nav.toastSeq) {
        if (nav.toast != null) {
            delay(2_400)
            nav.toast = null
        }
    }

    val pinnedList = pinned.orEmpty()
    val groups = remember(apps, sources, pinnedList) { buildGroups(apps, sources, pinnedList) }
    val rows = remember(groups) { drawerRows(groups) }
    val media = remember(apps) { apps.filter { it.isVideo } }
    val printerView = if (BuildConfig.DEBUG && AppSettings.demoVideoApps && !printer.reachable) DemoData.printer() else printer

    val data = HomeData(
        weather = weather,
        printer = printerView,
        home = home,
        now = now,
        net = net,
        media = media,
        lastVideo = lastVideo,
        groups = groups,
        printerHost = config.printerHost,
        homeHost = if (config.useHa) config.haUrlOrDefault else config.homeHostOrDefault,
        appsLoaded = appsLoaded,
        homeUsesHa = config.useHa,
        webSetupUrl = webSetupUrl,
    )

    // 列表变短（卸载应用、PS5 关机）时，焦点别停在不存在的格子上
    SideEffect {
        if (rows.isNotEmpty()) {
            nav.dr = nav.dr.coerceIn(0, rows.lastIndex)
            nav.dc = nav.dc.coerceIn(0, rows[nav.dr].tiles.lastIndex)
        }
        nav.media = nav.media.coerceIn(0, (media.size - 1).coerceAtLeast(0))
    }

    // ------------------------------------------------------------ 动作

    fun start(intent: Intent, name: String) {
        if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
            nav.toast("打不开 $name")
        }
    }

    fun launch(app: InstalledApp) = start(app.intent, app.label)

    fun pin(pkg: String) {
        AppSettings.setPinned(pinnedList.filter { it != pkg } + pkg)
        nav.toast("已加到「常用」")
    }

    fun unpin(pkg: String) {
        AppSettings.setPinned(pinnedList.filter { it != pkg })
        nav.toast("已从「常用」移除")
    }

    fun openChip(id: ChipId) = when (id) {
        ChipId.Settings -> onOpenSettings()
        ChipId.Cast -> nav.castQr = true
        ChipId.Wifi -> start(Intent(Settings.ACTION_WIFI_SETTINGS), "网络设置")
        ChipId.Time -> start(Intent(Settings.ACTION_DATE_SETTINGS), "日期和时间设置")
        ChipId.Mac, ChipId.Printer -> { nav.card = CARD_PAGES.indexOf(CardPage.Printer).coerceAtLeast(0); nav.row = Area.CARD }
        ChipId.Home -> { nav.card = CARD_PAGES.indexOf(CardPage.Home).coerceAtLeast(0); nav.row = Area.CARD }
    }

    fun openTile(tile: Tile) {
        when (tile) {
            is Tile.Source -> if (!InputRepo.launch(context, tile.source)) nav.toast("切换到 ${tile.source.name} 失败")
            is Tile.App -> launch(tile.app)
            Tile.Add -> {
                val set = pinnedList.toSet()
                nav.picker = apps.filter { it.packageName !in set }.sortedBy { it.label }
                nav.pickerIndex = 0
            }
        }
    }

    fun openMedia(i: Int) {
        val app = media.getOrNull(i)
        if (app == null) {
            nav.row = Area.DRAWER; nav.expanded = true; nav.dr = 0; nav.dc = 0
            return
        }
        AppSettings.setLastVideo(app.packageName)
        launch(app)
    }

    fun sheetFor(tile: Tile) {
        val t = tile as? Tile.App ?: return
        val app = t.app
        fun close(block: () -> Unit): () -> Unit = { nav.sheet = null; block() }
        val actions = buildList {
            add("打开" to close { launch(app) })
            if (t.pinned) add("从「常用」移除" to close { unpin(app.packageName) })
            else add("加到「常用」" to close { pin(app.packageName) })
            add("应用信息" to close {
                start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")), "应用信息")
            })
            add("取消" to close { })
        }
        nav.sheet = Sheet(app.label, actions)
        nav.sheetIndex = 0
    }

    // ------------------------------------------------------------ 遥控器

    fun move(dx: Int, dy: Int) {
        if (nav.castQr) return
        if (detail.page != null) { detail.move(home, dx, dy); return }
        nav.picker?.let { list ->
            nav.pickerIndex = (nav.pickerIndex + dy).coerceIn(0, (list.size - 1).coerceAtLeast(0))
            return
        }
        nav.sheet?.let { s ->
            nav.sheetIndex = (nav.sheetIndex + dy).coerceIn(0, s.actions.lastIndex)
            return
        }
        when (nav.row) {
            Area.TOP -> when {
                dx != 0 -> nav.top = (nav.top + dx).coerceIn(0, TOP_CHIPS.lastIndex)
                dy > 0 -> nav.row = Area.CARD
            }
            Area.CARD -> when {
                dx != 0 -> nav.card = (nav.card + dx + CARD_PAGES.size) % CARD_PAGES.size
                dy < 0 -> nav.row = Area.TOP
                dy > 0 -> nav.row = Area.MEDIA
            }
            Area.MEDIA -> when {
                dx != 0 -> nav.media = (nav.media + dx).coerceIn(0, (media.size - 1).coerceAtLeast(0))
                dy < 0 -> nav.row = Area.CARD
                dy > 0 && rows.isNotEmpty() -> {
                    nav.row = Area.DRAWER; nav.expanded = true; nav.dr = 0; nav.dc = 0
                }
            }
            else -> {
                val row = rows.getOrNull(nav.dr) ?: return
                when {
                    dx != 0 -> nav.dc = (nav.dc + dx).coerceIn(0, row.tiles.lastIndex)
                    dy > 0 && nav.dr < rows.lastIndex -> {
                        nav.dr++; nav.dc = nav.dc.coerceAtMost(rows[nav.dr].tiles.lastIndex)
                    }
                    dy < 0 && nav.dr > 0 -> {
                        nav.dr--; nav.dc = nav.dc.coerceAtMost(rows[nav.dr].tiles.lastIndex)
                    }
                    dy < 0 -> { nav.row = Area.MEDIA; nav.expanded = false }
                }
            }
        }
    }

    fun ok() {
        if (nav.castQr) { nav.castQr = false; return }
        if (detail.page != null) { detail.ok(home); return }
        nav.picker?.let { list ->
            list.getOrNull(nav.pickerIndex)?.let { pin(it.packageName) }
            nav.picker = null
            return
        }
        nav.sheet?.let { s -> s.actions.getOrNull(nav.sheetIndex)?.second?.invoke(); return }
        when (nav.row) {
            Area.TOP -> openChip(TOP_CHIPS[nav.top])
            // 天气、米家打开全屏页；打印机没有全屏页，按一下刷新
            Area.CARD -> when (val page = CARD_PAGES[nav.card]) {
                CardPage.Weather, CardPage.Home -> detail.open(page)
                CardPage.Printer -> { Feeds.refreshPrinter(); nav.toast("${page.title}已刷新") }
            }
            Area.MEDIA -> openMedia(nav.media)
            else -> rows.getOrNull(nav.dr)?.tiles?.getOrNull(nav.dc)?.let(::openTile)
        }
    }

    fun longPress() {
        if (nav.picker != null || nav.sheet != null || nav.castQr) return
        if (detail.page != null) { detail.longPress(home); return }
        when (nav.row) {
            Area.TOP -> if (TOP_CHIPS[nav.top] == ChipId.Settings) start(Intent(Settings.ACTION_SETTINGS), "系统设置")
            Area.DRAWER -> rows.getOrNull(nav.dr)?.tiles?.getOrNull(nav.dc)?.let(::sheetFor)
        }
    }

    fun back() {
        when {
            nav.castQr -> nav.castQr = false
            detail.page != null -> detail.back()
            nav.picker != null -> nav.picker = null
            nav.sheet != null -> nav.sheet = null
            else -> { nav.row = Area.MEDIA; nav.expanded = false }
        }
    }

    val keys = remember { KeyState() }

    HomeContent(
        data = data,
        rows = rows,
        nav = nav,
        wallpaper = wallpaper,
        modifier = Modifier
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                val code = e.key.nativeKeyCode
                val down = e.type == KeyEventType.KeyDown
                val up = e.type == KeyEventType.KeyUp
                when (code) {
                    AKey.KEYCODE_DPAD_LEFT -> { if (down) move(-1, 0); true }
                    AKey.KEYCODE_DPAD_RIGHT -> { if (down) move(1, 0); true }
                    AKey.KEYCODE_DPAD_UP -> { if (down) move(0, -1); true }
                    AKey.KEYCODE_DPAD_DOWN -> { if (down) move(0, 1); true }
                    AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER, AKey.KEYCODE_NUMPAD_ENTER -> {
                        // 短按松手才触发；按住不放约半秒算长按（弹菜单），松手不再打开
                        val repeat = e.nativeKeyEvent.repeatCount
                        if (down && repeat == 0) { keys.okDown = true; keys.longFired = false }
                        if (down && repeat == 1 && keys.okDown && !keys.longFired) { keys.longFired = true; longPress() }
                        if (up) {
                            if (keys.okDown && !keys.longFired) ok()
                            keys.okDown = false
                        }
                        true
                    }
                    AKey.KEYCODE_MENU -> { if (up) longPress(); true }
                    AKey.KEYCODE_BACK, AKey.KEYCODE_ESCAPE -> {
                        if (down) keys.backDown = true
                        if (up && keys.backDown) { keys.backDown = false; back() }
                        true
                    }
                    else -> false
                }
            },
        onChip = { id -> nav.row = Area.TOP; nav.top = TOP_CHIPS.indexOf(id); openChip(id) },
        onCard = { if (nav.row == Area.CARD) ok() else nav.row = Area.CARD },
        onMedia = { i -> nav.row = Area.MEDIA; nav.media = i; openMedia(i) },
        onTile = { r, c -> nav.dr = r; nav.dc = c; rows.getOrNull(r)?.tiles?.getOrNull(c)?.let(::openTile) },
        onPick = { i -> nav.pickerIndex = i; ok() },
        detail = detail,
    )

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

private class KeyState {
    var okDown = false
    var longFired = false
    var backDown = false
}

private fun currentNet(context: Context): Net = runCatching {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return Net.None
    when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Net.Ethernet
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Net.Wifi
        else -> Net.Wifi
    }
}.getOrDefault(Net.Wifi)

/**
 * 纯展示层：不联网、不读配置，Android Studio 里可以直接预览。
 * 尺寸按 1920x1080 设计稿取值，按屏幕宽度等比缩放。
 */
@Composable
fun HomeContent(
    data: HomeData,
    rows: List<DrawerRow>,
    nav: Nav,
    wallpaper: WallpaperImages?,
    modifier: Modifier = Modifier,
    onChip: (ChipId) -> Unit = {},
    onCard: () -> Unit = {},
    onMedia: (Int) -> Unit = {},
    onTile: (Int, Int) -> Unit = { _, _ -> },
    onPick: (Int) -> Unit = {},
    detail: DetailCtl? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize().then(if (wallpaper == null) Modifier.background(Color(0xFF0B0F18)) else Modifier)) {
        val density = LocalDensity.current
        val ui = remember(maxWidth, density.fontScale) { Ui(maxWidth.value / 1920f, density.fontScale) }
        val rootSize = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        val backdrop = remember(wallpaper, rootSize) { Backdrop(wallpaper?.blurred, rootSize) }

        CompositionLocalProvider(
            LocalUi provides ui,
            LocalBackdrop provides backdrop,
        ) {
            // 壁纸和上下压暗已经在 Wallpaper 里合成成一张，这里只画一次，省两层全屏混合
            wallpaper?.let {
                Image(
                    it.full,
                    null,
                    contentScale = ContentScale.Crop,
                    // 壁纸和屏幕是 1:1 时用最近邻：像素完全一样，但省掉每帧的双线性采样
                    filterQuality = if (it.full.width == rootSize.width && it.full.height == rootSize.height) {
                        FilterQuality.None
                    } else {
                        FilterQuality.Low
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Box(Modifier.padding(start = ui.d(96), top = ui.d(136))) {
                InfoCard(data, nav.card, nav.row == Area.CARD && !nav.expanded, onCard)
            }

            MediaRow(
                apps = data.media,
                loaded = data.appsLoaded,
                lastVideo = data.lastVideo,
                focusedIndex = nav.media,
                rowFocused = nav.row == Area.MEDIA,
                onTap = onMedia,
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = ui.d(8)),
            )

            AppDrawer(data.groups, rows, nav.expanded, nav.dr, nav.dc, onTile)

            Box(Modifier.zIndex(5f)) {
                TopBar(data, if (nav.row == Area.TOP) TOP_CHIPS.getOrNull(nav.top) else null, onChip)
            }

            if (detail != null) {
                DetailOverlay(detail.page) { page ->
                    when (page) {
                        CardPage.Weather -> WeatherDetail(data.weather)
                        CardPage.Home -> HomeDetail(
                            data, detail,
                            onTile = { r, c -> detail.tapTile(data.home, r, c) },
                            onCtl = { i -> detail.tapCtl(data.home, i) },
                        )
                        CardPage.Printer -> Unit
                    }
                }
            }

            nav.sheet?.let { SheetOverlay(it, nav.sheetIndex) }
            nav.picker?.let { PickerOverlay(it, nav.pickerIndex, onPick) }
            if (nav.castQr) CastQrOverlay(data.webSetupUrl)
            ToastView(nav.toast)
        }
    }
}
