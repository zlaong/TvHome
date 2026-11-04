package com.zlaong.tvhome.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zlaong.tvhome.data.HomeUi
import com.zlaong.tvhome.data.InstalledApp
import com.zlaong.tvhome.data.PrinterUi
import com.zlaong.tvhome.data.WeatherUi

/** 抽屉里的一格：一个应用 */
data class Tile(val app: InstalledApp) {
    val key get() = "app:" + app.packageName
    val name get() = app.label
}

data class TileGroup(val title: String, val tiles: List<Tile>)

/** 应用在抽屉里第几行第几列；找不到返回 null */
fun locate(rows: List<DrawerRow>, pkg: String): Pair<Int, Int>? {
    rows.forEachIndexed { r, row ->
        val c = row.tiles.indexOfFirst { it.app.packageName == pkg }
        if (c >= 0) return r to c
    }
    return null
}

/** 抽屉按 6 列切成的一行，焦点上下移动以行为单位 */
data class DrawerRow(val group: Int, val first: Boolean, val tiles: List<Tile>)

const val DRAWER_COLS = 6

fun drawerRows(groups: List<TileGroup>): List<DrawerRow> = groups.flatMapIndexed { gi, g ->
    g.tiles.chunked(DRAWER_COLS).mapIndexed { ri, row -> DrawerRow(gi, ri == 0, row) }
}

const val GROUP_APPS = "全部应用"

/** 抽屉只有「全部应用」一组，扫到的应用都放 */
fun buildGroups(apps: List<InstalledApp>, order: List<String>): List<TileGroup> = listOfNotNull(
    apps.takeIf { it.isNotEmpty() }?.let { a -> TileGroup(GROUP_APPS, sortApps(a, order).map { Tile(it) }) },
)

/** 按用户排过的顺序；没排过的（新装的）保持扫描时的默认顺序，接在最后 */
fun sortApps(apps: List<InstalledApp>, order: List<String>): List<InstalledApp> {
    val rank = order.withIndex().associate { it.value to it.index }
    return apps.withIndex()
        .sortedWith(compareBy({ rank[it.value.packageName] ?: Int.MAX_VALUE }, { it.index }))
        .map { it.value }
}

/** 应用 pkg 在排好的列表里移到 to 位置 */
fun List<String>.moved(pkg: String, to: Int): List<String> {
    val rest = filter { it != pkg }
    val i = to.coerceIn(0, rest.size)
    return rest.take(i) + pkg + rest.drop(i)
}

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
    /** 还在后台没退出的应用（包名），首页图标下面画个小点 */
    val running: Set<String> = emptySet(),
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
    /** 上下换行时想回到的列：经过短行被挤到左边，再回长行时还原 */
    var dcWant by mutableIntStateOf(0)
    var expanded by mutableStateOf(false)
    var sheet by mutableStateOf<Sheet?>(null)
    var sheetIndex by mutableIntStateOf(0)
    /** 正在挪位置的应用（包名）；非 null 表示在「移动」状态 */
    var moving by mutableStateOf<String?>(null)
    /** 挪动过程中的临时顺序，确认才保存，返回键作废 */
    var moveOrder by mutableStateOf<List<String>>(emptyList())
    var moveFrom by mutableIntStateOf(0)
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
        moving = null
        castQr = false
        dr = 0
        dc = 0
        dcWant = 0
    }
}
