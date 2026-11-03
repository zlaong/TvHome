package com.zlaong.tvhome.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zlaong.tvhome.data.HdmiSource
import com.zlaong.tvhome.data.HomeUi
import com.zlaong.tvhome.data.InstalledApp
import com.zlaong.tvhome.data.PrinterUi
import com.zlaong.tvhome.data.WeatherUi

/** 抽屉里的一格：HDMI 设备 / 应用 / 「添加」 */
sealed class Tile {
    abstract val key: String
    abstract val name: String

    data class Source(val source: HdmiSource) : Tile() {
        override val key get() = "src:" + source.inputId
        override val name get() = source.name
    }

    data class App(val app: InstalledApp, val pinned: Boolean) : Tile() {
        override val key get() = "app:" + app.packageName
        override val name get() = app.label
    }

    data object Add : Tile() {
        override val key = "add"
        override val name = "添加"
    }
}

data class TileGroup(val title: String, val tiles: List<Tile>)

/** 抽屉按 6 列切成的一行，焦点上下移动以行为单位 */
data class DrawerRow(val group: Int, val first: Boolean, val tiles: List<Tile>)

const val DRAWER_COLS = 6

fun drawerRows(groups: List<TileGroup>): List<DrawerRow> = groups.flatMapIndexed { gi, g ->
    g.tiles.chunked(DRAWER_COLS).mapIndexed { ri, row -> DrawerRow(gi, ri == 0, row) }
}

fun buildGroups(
    apps: List<InstalledApp>,
    sources: List<HdmiSource>,
    pinned: List<String>,
): List<TileGroup> {
    val byPkg = apps.associateBy { it.packageName }
    val pinnedApps = pinned.mapNotNull { byPkg[it] }
    val pinnedSet = pinnedApps.map { it.packageName }.toSet()
    val common = sources.map { Tile.Source(it) } +
        pinnedApps.map { Tile.App(it, pinned = true) } +
        Tile.Add
    val live = apps.filter { it.isLive && !it.isVideo && it.packageName !in pinnedSet }
    val rest = apps.filter { !it.isVideo && !it.isLive && it.packageName !in pinnedSet }
    return listOfNotNull(
        TileGroup("常用", common),
        live.takeIf { it.isNotEmpty() }?.let { l -> TileGroup("直播", l.map { Tile.App(it, false) }) },
        rest.takeIf { it.isNotEmpty() }?.let { r -> TileGroup("全部应用", r.sortedBy { it.label }.map { Tile.App(it, false) }) },
    )
}

/** 第一次装好时「常用」默认放哪些：Keep、索尼媒体播放器（装了才放） */
val DEFAULT_PINNED = listOf("com.gotokeep.androidtv", "com.sony.dtv.smartmediaapp")

enum class Net { Wifi, Ethernet, None }

/**
 * 3D 打印机（经 Mac mini）还没接入：关掉后不轮询，也不显示 Mac mini / 打印机图标和打印机页。
 * 接好以后改成 true 即可，相关代码都还在。
 */
const val PRINTER_ENABLED = false

enum class ChipId { Settings, Cast, Wifi, Time, Mac, Printer, Home }

val TOP_CHIPS = ChipId.entries.filter { PRINTER_ENABLED || (it != ChipId.Mac && it != ChipId.Printer) }
val LEFT_CHIPS = listOf(ChipId.Settings, ChipId.Cast, ChipId.Wifi, ChipId.Time)

enum class CardPage(val title: String) { Weather("天气"), Printer("3D 打印机"), Home("米家") }

/** 卡片实际有哪几页；nav.card 是这个列表的下标 */
val CARD_PAGES = CardPage.entries.filter { PRINTER_ENABLED || it != CardPage.Printer }

/** 首页要展示的全部数据，界面只读不改 */
data class HomeData(
    val weather: WeatherUi,
    val printer: PrinterUi,
    val home: HomeUi,
    val now: Long,
    val net: Net,
    val media: List<InstalledApp>,
    val lastVideo: String?,
    val groups: List<TileGroup>,
    val printerHost: String,
    val homeHost: String,
    /** 应用列表扫完了没有；没扫完时影视行先空着，不提示「还没装影视应用」 */
    val appsLoaded: Boolean = true,
    /** 米家走 Home Assistant（true）还是旧的设备桥（false） */
    val homeUsesHa: Boolean = false,
    /** 电脑浏览器设置网页地址，没联网时为 null */
    val webSetupUrl: String? = null,
)

/** 长按弹出的操作菜单 */
data class Sheet(val title: String, val actions: List<Pair<String, () -> Unit>>)

object Area {
    const val TOP = -2
    const val CARD = -1
    const val MEDIA = 0
    const val DRAWER = 1
}

/**
 * 遥控器焦点。首页的焦点全部自己管（哪一行、第几个），
 * 不交给 Compose 默认的焦点搜索：分组网格里上下移动时默认行为很难预测。
 */
class Nav {
    var row by mutableIntStateOf(Area.MEDIA)
    var top by mutableIntStateOf(0)
    var card by mutableIntStateOf(0)
    var media by mutableIntStateOf(0)
    var dr by mutableIntStateOf(0)
    var dc by mutableIntStateOf(0)
    var expanded by mutableStateOf(false)
    var sheet by mutableStateOf<Sheet?>(null)
    var sheetIndex by mutableIntStateOf(0)
    /** 「添加到常用」选择列表；非 null 表示打开着 */
    var picker by mutableStateOf<List<InstalledApp>?>(null)
    var pickerIndex by mutableIntStateOf(0)
    /** 「投屏」二维码弹窗 */
    var castQr by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    var toastSeq by mutableIntStateOf(0)

    fun toast(msg: String) {
        toast = msg
        toastSeq++
    }

    fun reset() {
        row = Area.MEDIA
        expanded = false
        sheet = null
        picker = null
        castQr = false
        dr = 0
        dc = 0
    }
}
