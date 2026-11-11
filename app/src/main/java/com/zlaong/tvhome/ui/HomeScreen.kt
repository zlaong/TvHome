package com.zlaong.tvhome.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent as AKey
import android.view.SoundEffectConstants
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
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
import com.zlaong.tvhome.data.InstalledApp
import com.zlaong.tvhome.data.RunningApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(config: AppConfig, onOpenSettings: () -> Unit, resets: Flow<Unit> = emptyFlow()) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val nav = remember { Nav() }
    val focus = remember { FocusRequester() }

    // 卡片数据和刷新节奏在 Feeds 里（进设置页时这个界面会被销毁，状态不能放这里）
    val weather by Feeds.weather.collectAsState()
    val printer by Feeds.printer.collectAsState()
    val home by Feeds.home.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var net by remember { mutableStateOf(Net.Wifi) }
    val appOrder by AppSettings.appOrder.collectAsState()
    val mediaOrder by AppSettings.mediaOrder.collectAsState()
    val lastVideo by AppSettings.lastVideo.collectAsState()

    val wallpaperVer by WallpaperRepo.version.collectAsState()
    val wallpaper by produceState<WallpaperImages?>(null, wallpaperVer) {
        value = withContext(Dispatchers.Default) { Wallpaper.load(context) }
    }

    // 回到桌面：抢焦点；顺便查一次后台任务、每日壁纸
    LifecycleResumeEffect(Unit) {
        runCatching { focus.requestFocus() }
        // 每日壁纸：回到桌面查一次；一直停在桌面（电视常开）也每半小时查一次，过了零点就换
        val job = scope.launch {
            while (true) {
                WallpaperRepo.refreshDaily()
                delay(30 * 60_000L)
            }
        }
        onPauseOrDispose { job.cancel() }
    }

    /**
     * 后台还在跑的任务（包名 → 任务编号）。
     * 图标下的小点用它；点图标时也用它把应用原样切回前台，而不是重新下发启动指令
     * （重发指令很多 App 会重走启动页/开屏广告，看起来就像被重启了）。
     * 只在回到桌面时查，之后每 30 秒补一次；不再每 5 秒读一遍。
     */
    var tasks by remember { mutableStateOf(RunningApps.Tasks()) }
    val running = tasks.packages
    LifecycleResumeEffect(Unit) {
        val job = scope.launch {
            while (true) {
                tasks = RunningApps.load()
                delay(30_000)
            }
        }
        onPauseOrDispose { job.cancel() }
    }

    // 应用列表扫一次就留着（图标、横幅图都在里面），装/卸/更新应用时才重扫
    var apps by remember { mutableStateOf(emptyList<InstalledApp>()) }
    var appsLoaded by remember { mutableStateOf(false) }
    var appsVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: Intent?) {
                AppRepo.invalidate()
                appsVersion++
            }
        }
        context.registerReceiver(receiver, filter)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(appsVersion) {
        apps = withContext(Dispatchers.IO) {
            val real = runCatching { AppRepo.loadApps(context) }.getOrDefault(emptyList())
            val all = if (BuildConfig.DEBUG && AppSettings.demoVideoApps) DemoData.videoApps(context) + real else real
            AppRepo.warmBitmaps(all)
            all
        }
        appsLoaded = true
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
    // 网络状态、设置网页地址这些变化很慢，30 秒刷一次就够；时间由顶栏自己每秒走
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            net = currentNet(context)
            webSetupUrl = com.zlaong.tvhome.data.ConfigServer.url()
            delay(30_000)
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

    // 挪位置时按临时顺序实时显示，确认后才写进设置。首页影视行和抽屉各有各的顺序
    val movingMedia = nav.moving != null && nav.row == Area.MEDIA
    val order = if (nav.moving != null && !movingMedia) nav.moveOrder else appOrder
    val groups = remember(apps, order) { buildGroups(apps, order) }
    val rows = remember(groups) { drawerRows(groups) }
    val videoApps = remember(apps) { apps.filter { it.isVideo } }
    val mOrder = if (movingMedia) nav.moveOrder else mediaOrder
    val media = remember(videoApps, mOrder) { sortApps(videoApps, mOrder) }
    val printerView = if (BuildConfig.DEBUG && AppSettings.demoVideoApps && !printer.reachable) DemoData.printer() else printer

    val data = HomeData(
        weather = weather,
        printer = printerView,
        home = home,
        now = now,
        net = net,
        media = media,
        lastVideo = lastVideo,
        running = running,
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

    fun launch(app: InstalledApp) {
        // 还留在后台的任务：直接切回前台，保留它原来的页面和播放进度，
        // 不会闪启动图、也不会重走开屏广告。切不过去（任务已被系统清掉）再走正常启动
        val taskId = tasks.ids[app.packageName]
        if (taskId != null && RunningApps.bringToFront(context, taskId)) return
        start(app.intent, app.label)
    }

    fun openDrawer() {
        nav.row = Area.DRAWER; nav.expanded = true; nav.dr = 0; nav.dc = 0; nav.dcWant = 0
    }

    fun orderedPkgs(order: List<String>) = sortApps(apps, order).map { it.packageName }

    /** 顺序变了以后，焦点跟着这个应用走 */
    fun focusOn(pkg: String, order: List<String>) {
        val (r, c) = locate(drawerRows(buildGroups(apps, order)), pkg) ?: return
        nav.dr = r; nav.dc = c; nav.dcWant = c
    }

    fun startMove(pkg: String) {
        nav.moveOrder = orderedPkgs(appOrder)
        nav.moving = pkg
    }

    // 首页影视行：长按直接拿起来，左右挪，确认放下，返回取消
    fun startMediaMove(i: Int) {
        val app = media.getOrNull(i) ?: return
        nav.moveOrder = media.map { it.packageName }
        nav.moving = app.packageName
    }

    fun moveMedia(dx: Int) {
        val pkg = nav.moving ?: return
        val list = nav.moveOrder
        val i = list.indexOf(pkg).takeIf { it >= 0 } ?: return
        val to = i + dx
        if (dx == 0 || to !in list.indices) return
        nav.moveOrder = list.moved(pkg, to)
        nav.media = to
    }

    fun endMediaMove(save: Boolean) {
        val pkg = nav.moving ?: return
        if (save) {
            AppSettings.setMediaOrder(nav.moveOrder)
        } else {
            nav.media = sortApps(videoApps, mediaOrder).indexOfFirst { it.packageName == pkg }.coerceAtLeast(0)
        }
        nav.moving = null
    }

    fun moveTile(dx: Int, dy: Int) {
        val pkg = nav.moving ?: return
        val list = nav.moveOrder
        val i = list.indexOf(pkg).takeIf { it >= 0 } ?: return
        val to = i + dx + dy * DRAWER_COLS
        if (to !in list.indices) return
        nav.moveOrder = list.moved(pkg, to)
        focusOn(pkg, nav.moveOrder)
    }

    fun endMove(save: Boolean) {
        val pkg = nav.moving ?: return
        if (save) AppSettings.setAppOrder(nav.moveOrder) else focusOn(pkg, appOrder)
        nav.moving = null
    }

    fun moveToFront(pkg: String) {
        val list = orderedPkgs(appOrder).moved(pkg, 0)
        AppSettings.setAppOrder(list)
        focusOn(pkg, list)
        nav.toast("已移到最前")
    }

    fun openChip(id: ChipId) = when (id) {
        ChipId.Settings -> onOpenSettings()
        ChipId.Cast -> nav.castQr = true
        ChipId.Wifi -> start(Intent(Settings.ACTION_WIFI_SETTINGS), "网络设置")
        ChipId.Time -> start(Intent(Settings.ACTION_DATE_SETTINGS), "日期和时间设置")
        ChipId.Mac, ChipId.Printer -> { nav.card = CARD_PAGES.indexOf(CardPage.Printer).coerceAtLeast(0); nav.row = Area.CARD }
        ChipId.Home -> { nav.card = CARD_PAGES.indexOf(CardPage.Home).coerceAtLeast(0); nav.row = Area.CARD }
    }

    fun openTile(tile: Tile) = launch(tile.app)

    fun openMedia(i: Int) {
        val app = media.getOrNull(i)
        if (app == null) {
            openDrawer()
            return
        }
        AppSettings.setLastVideo(app.packageName)
        launch(app)
    }

    fun sheetFor(tile: Tile) {
        val app = tile.app
        val pkg = app.packageName
        fun close(block: () -> Unit): () -> Unit = { nav.sheet = null; block() }
        val actions = buildList {
            add("打开" to close { launch(app) })
            add("移动位置" to close { startMove(pkg) })
            if (orderedPkgs(appOrder).firstOrNull() != pkg) add("移到最前" to close { moveToFront(pkg) })
            add("应用信息" to close {
                start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")), "应用信息")
            })
            if (app.removable) add("卸载" to close { start(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")), "卸载") })
            add("取消" to close { })
        }
        nav.sheet = Sheet(app.label, actions)
        nav.sheetIndex = 0
    }

    // ------------------------------------------------------------ 遥控器

    fun move(dx: Int, dy: Int) {
        if (nav.castQr) return
        if (detail.page != null) { detail.move(home, dx, dy); return }
        if (nav.moving != null) { if (movingMedia) moveMedia(dx) else moveTile(dx, dy); return }
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
                // 平铺成一排了，到头就停，不再绕回第一张
                dx != 0 -> nav.card = (nav.card + dx).coerceIn(0, CARD_PAGES.lastIndex)
                dy < 0 -> nav.row = Area.TOP
                dy > 0 -> nav.row = Area.MEDIA
            }
            Area.MEDIA -> when {
                dx != 0 -> nav.media = (nav.media + dx).coerceIn(0, (media.size - 1).coerceAtLeast(0))
                dy < 0 -> nav.row = Area.CARD
                dy > 0 && rows.isNotEmpty() -> openDrawer()
            }
            else -> {
                val row = rows.getOrNull(nav.dr) ?: return
                when {
                    // 行尾按「右」接到下一行开头，行首按「左」回到上一行末尾（同一分组内）
                    dx > 0 && nav.dc == row.tiles.lastIndex -> {
                        val next = rows.getOrNull(nav.dr + 1)
                        if (next != null && next.group == row.group) { nav.dr++; nav.dc = 0 }
                        nav.dcWant = nav.dc
                    }
                    dx < 0 && nav.dc == 0 -> {
                        val prev = rows.getOrNull(nav.dr - 1)
                        if (prev != null && prev.group == row.group) { nav.dr--; nav.dc = prev.tiles.lastIndex }
                        nav.dcWant = nav.dc
                    }
                    dx != 0 -> { nav.dc += dx; nav.dcWant = nav.dc }
                    dy > 0 && nav.dr < rows.lastIndex -> {
                        nav.dr++; nav.dc = nav.dcWant.coerceAtMost(rows[nav.dr].tiles.lastIndex)
                    }
                    dy < 0 && nav.dr > 0 -> {
                        nav.dr--; nav.dc = nav.dcWant.coerceAtMost(rows[nav.dr].tiles.lastIndex)
                    }
                    dy < 0 -> { nav.row = Area.MEDIA; nav.expanded = false }
                }
            }
        }
    }

    fun ok() {
        if (nav.castQr) { nav.castQr = false; return }
        if (detail.page != null) { detail.ok(home); return }
        if (nav.moving != null) { if (movingMedia) endMediaMove(save = true) else endMove(save = true); return }
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
        if (nav.moving != null || nav.sheet != null || nav.castQr) return
        if (detail.page != null) { detail.longPress(home); return }
        when (nav.row) {
            Area.TOP -> if (TOP_CHIPS[nav.top] == ChipId.Settings) start(Intent(Settings.ACTION_SETTINGS), "系统设置")
            Area.MEDIA -> startMediaMove(nav.media)
            Area.DRAWER -> rows.getOrNull(nav.dr)?.tiles?.getOrNull(nav.dc)?.let(::sheetFor)
        }
    }

    fun back() {
        when {
            nav.castQr -> nav.castQr = false
            detail.page != null -> detail.back()
            nav.moving != null -> if (movingMedia) endMediaMove(save = false) else endMove(save = false)
            nav.sheet != null -> nav.sheet = null
            // 抽屉滚到下面时，返回键先回到第一格，再按一次才收起
            nav.row == Area.DRAWER && nav.dr > 0 -> { nav.dr = 0; nav.dc = 0; nav.dcWant = 0 }
            else -> { nav.row = Area.MEDIA; nav.expanded = false }
        }
    }

    // 焦点真的动了才响，到头了按不动就不响，和系统桌面一样；跟随系统「声音 → 按键音」开关
    fun navSpot() = listOf(
        nav.row, nav.top, nav.card, nav.media, nav.dr, nav.dc, nav.sheetIndex,
        detail.row, detail.col, detail.panelIndex,
    )

    fun moveWithSound(dx: Int, dy: Int) {
        val before = navSpot()
        move(dx, dy)
        if (navSpot() != before) {
            view.playSoundEffect(
                when {
                    dx < 0 -> SoundEffectConstants.NAVIGATION_LEFT
                    dx > 0 -> SoundEffectConstants.NAVIGATION_RIGHT
                    dy < 0 -> SoundEffectConstants.NAVIGATION_UP
                    else -> SoundEffectConstants.NAVIGATION_DOWN
                }
            )
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
                    AKey.KEYCODE_DPAD_LEFT -> { if (down) moveWithSound(-1, 0); true }
                    AKey.KEYCODE_DPAD_RIGHT -> { if (down) moveWithSound(1, 0); true }
                    AKey.KEYCODE_DPAD_UP -> { if (down) moveWithSound(0, -1); true }
                    AKey.KEYCODE_DPAD_DOWN -> { if (down) moveWithSound(0, 1); true }
                    AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER, AKey.KEYCODE_NUMPAD_ENTER -> {
                        // 短按松手才触发；按住不放约半秒算长按（弹菜单），松手不再打开
                        val repeat = e.nativeKeyEvent.repeatCount
                        if (down && repeat == 0) { keys.okDown = true; keys.longFired = false }
                        if (down && repeat == 1 && keys.okDown && !keys.longFired) { keys.longFired = true; view.playSoundEffect(SoundEffectConstants.CLICK); longPress() }
                        if (up) {
                            if (keys.okDown && !keys.longFired) { view.playSoundEffect(SoundEffectConstants.CLICK); ok() }
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
        onCard = { i ->
            val same = nav.row == Area.CARD && nav.card == i
            nav.row = Area.CARD; nav.card = i
            if (same) ok()
        },
        onMedia = { i -> if (nav.moving == null) { nav.row = Area.MEDIA; nav.media = i; openMedia(i) } },
        onTile = { r, c ->
            if (nav.moving == null) {
                nav.dr = r; nav.dc = c; nav.dcWant = c
                rows.getOrNull(r)?.tiles?.getOrNull(c)?.let(::openTile)
            }
        },
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
    onCard: (Int) -> Unit = {},
    onMedia: (Int) -> Unit = {},
    onTile: (Int, Int) -> Unit = { _, _ -> },
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

            val tiled = nav.row == Area.CARD && !nav.expanded
            Box(Modifier.padding(start = ui.d(96), top = ui.d(136))) {
                InfoCard(data, nav.card, tiled, onCard)
            }

            MediaRow(
                apps = data.media,
                loaded = data.appsLoaded,
                lastVideo = data.lastVideo,
                running = data.running,
                focusedIndex = nav.media,
                rowFocused = nav.row == Area.MEDIA,
                moving = nav.moving != null && nav.row == Area.MEDIA,
                onTap = onMedia,
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = ui.d(8)),
            )

            AppDrawer(data.groups, rows, nav.expanded, nav.dr, nav.dc, nav.moving != null && nav.row == Area.DRAWER, onTile)

            if (nav.moving != null && nav.row == Area.MEDIA) {
                MoveHint("左右键挪位置 · 确认键放下 · 返回键取消", Modifier.align(Alignment.BottomCenter).padding(bottom = ui.d(300)))
            }

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
            if (nav.castQr) CastQrOverlay(data.webSetupUrl)
            ToastView(nav.toast)
        }
    }
}
