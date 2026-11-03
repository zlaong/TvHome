package com.zlaong.tvhome.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import com.zlaong.tvhome.data.AppSettings
import com.zlaong.tvhome.data.Ctl
import com.zlaong.tvhome.data.Feeds
import com.zlaong.tvhome.data.HaCall
import com.zlaong.tvhome.data.HomeControl
import com.zlaong.tvhome.data.HomeRepo
import com.zlaong.tvhome.data.HomeRoom
import com.zlaong.tvhome.data.HomeThing
import com.zlaong.tvhome.data.HomeUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

// ================================================================== 状态与遥控器

/** 全屏页里的设备网格按 5 列切行，焦点上下以行为单位 */
const val GRID_COLS = 5

data class GridRow(val room: HomeRoom, val firstInRoom: Boolean, val things: List<HomeThing>)

fun gridRows(rooms: List<HomeRoom>): List<GridRow> = rooms.flatMap { r ->
    r.things.chunked(GRID_COLS).mapIndexed { i, row -> GridRow(r, i == 0, row) }
}

/**
 * 卡片全屏页的状态和遥控器逻辑：天气只看，米家可以控制。
 *
 * 控制是「先改界面、后台下发」：按下马上显示新状态（pending），
 * 下发成功后隔几秒重拉状态；8 秒内服务器还没跟上就以服务器为准，下发失败立即撤回并提示。
 * 调亮度、温度连按时只发最后一次（停手 0.6 秒后发）。
 */
class DetailCtl(private val scope: CoroutineScope, private val toast: (String) -> Unit) {
    var page by mutableStateOf<CardPage?>(null)
        private set
    var row by mutableIntStateOf(0)
    var col by mutableIntStateOf(0)
    /** 打开着详情面板的设备 id */
    var panel by mutableStateOf<String?>(null)
    var panelIndex by mutableIntStateOf(0)

    private class Pending(val value: Any, val at: Long)
    private val pending = mutableStateMapOf<String, Pending>()
    private val debounce = HashMap<String, Job>()

    fun open(p: CardPage) {
        page = p
        row = 0; col = 0; panel = null
    }

    fun close() {
        page = null
        panel = null
    }

    // ---------------------------------------------------------------- 乐观状态

    /** key 形如 "light.x|power"、"light.x|bri" */
    @Suppress("UNCHECKED_CAST")
    fun <T> effective(key: String, real: T): T {
        val p = pending[key] ?: return real
        return if (System.currentTimeMillis() - p.at < PENDING_TTL) p.value as T else real
    }

    fun isPending(key: String, real: Any?): Boolean {
        val p = pending[key] ?: return false
        return System.currentTimeMillis() - p.at < PENDING_TTL && p.value != real
    }

    fun thingOn(t: HomeThing): Boolean = effective("${t.id}|power", t.on)

    private fun ctlValue(t: HomeThing, c: Ctl): Any? = when (c) {
        is Ctl.Power -> effective("${c.entity}|power", c.on)
        is Ctl.Value -> effective("${t.id}|${c.key}", c.value)
        is Ctl.Choice -> effective("${t.id}|${c.key}", c.current)
        is Ctl.Action -> null
    }

    /** 每次拉到新状态时调用：过期的改动扔掉，界面回到服务器的状态 */
    fun prune() {
        val now = System.currentTimeMillis()
        pending.filterValues { now - it.at >= PENDING_TTL }.keys.forEach { pending.remove(it) }
    }

    /** 面板里实际显示的值：有没确认的改动就用改动后的 */
    fun display(t: HomeThing, c: Ctl): Any? = ctlValue(t, c)

    // ---------------------------------------------------------------- 遥控器

    fun move(home: HomeUi, dx: Int, dy: Int) {
        if (page != CardPage.Home) return
        val thing = panelThing(home)
        if (thing != null) {
            val ctls = HomeControl.controls(thing)
            if (dy != 0) panelIndex = (panelIndex + dy).coerceIn(0, (ctls.size - 1).coerceAtLeast(0))
            if (dx != 0) ctls.getOrNull(panelIndex)?.let { adjust(thing, it, dx) }
            return
        }
        val rows = gridRows(home.rooms)
        if (rows.isEmpty()) return
        when {
            dx != 0 -> col = (col + dx).coerceIn(0, rows[row.coerceIn(0, rows.lastIndex)].things.lastIndex)
            dy != 0 -> {
                row = (row + dy).coerceIn(0, rows.lastIndex)
                col = col.coerceAtMost(rows[row].things.lastIndex)
            }
        }
    }

    fun ok(home: HomeUi) {
        if (page != CardPage.Home) return
        val thing = panelThing(home)
        if (thing != null) {
            HomeControl.controls(thing).getOrNull(panelIndex)?.let { press(thing, it) }
            return
        }
        val t = focused(home) ?: return
        when {
            !t.online -> toast("${t.name}离线了")
            t.quickToggle -> togglePower(t, HomeControl.controls(t).filterIsInstance<Ctl.Power>().firstOrNull { it.entity == t.id })
            else -> openPanel(t)
        }
    }

    fun longPress(home: HomeUi) {
        if (page != CardPage.Home || panel != null) return
        val t = focused(home) ?: return
        if (!t.online) toast("${t.name}离线了") else openPanel(t)
    }

    /** 返回键：先关面板，再关全屏页 */
    fun back() {
        if (panel != null) panel = null else close()
    }

    fun tapTile(home: HomeUi, r: Int, c: Int) {
        row = r; col = c
        ok(home)
    }

    fun tapCtl(home: HomeUi, i: Int) {
        panelIndex = i
        ok(home)
    }

    fun focused(home: HomeUi): HomeThing? = gridRows(home.rooms).getOrNull(row)?.things?.getOrNull(col)

    fun panelThing(home: HomeUi): HomeThing? {
        val id = panel ?: return null
        return home.rooms.asSequence().flatMap { it.things }.firstOrNull { it.id == id }
    }

    private fun openPanel(t: HomeThing) {
        panel = t.id
        panelIndex = 0
    }

    // ---------------------------------------------------------------- 下发

    private fun press(t: HomeThing, c: Ctl) {
        when (c) {
            is Ctl.Power -> togglePower(t, c)
            is Ctl.Value, is Ctl.Choice -> adjust(t, c, 1)
            is Ctl.Action -> send(c.call, null) { toast("已发送：${t.name}${c.label}") }
        }
    }

    private fun togglePower(t: HomeThing, c: Ctl.Power?) {
        if (c == null) return
        val key = "${c.entity}|power"
        val now = effective(key, c.on)
        pending[key] = Pending(!now, System.currentTimeMillis())
        send(if (now) c.turnOff else c.turnOn, key)
    }

    private fun adjust(t: HomeThing, c: Ctl, dir: Int) {
        val key = "${t.id}|${c.key}"
        val call: HaCall = when (c) {
            is Ctl.Power -> {
                // 左右键在开关上：右 = 开，左 = 关
                val want = dir > 0
                if (effective("${c.entity}|power", c.on) == want) return
                togglePower(t, c)
                return
            }
            is Ctl.Value -> {
                val cur = effective(key, c.value)
                // 先对齐到步长，再加减：亮度 37% 按右键到 40%，而不是 47%
                val base = c.min + ((cur - c.min) / c.step) * c.step
                val next = (if (dir > 0) base + c.step else if (base < cur) base else base - c.step).coerceIn(c.min, c.max)
                if (next == cur) return
                pending[key] = Pending(next, System.currentTimeMillis())
                c.call(next)
            }
            is Ctl.Choice -> {
                if (c.options.isEmpty()) return
                val cur = effective(key, c.current)
                val i = c.options.indexOfFirst { it.first == cur }
                val next = c.options[((if (i < 0) 0 else i + dir) + c.options.size) % c.options.size].first
                pending[key] = Pending(next, System.currentTimeMillis())
                c.call(next)
            }
            is Ctl.Action -> return
        }
        // 连按只发最后一次
        debounce.remove(key)?.cancel()
        debounce[key] = scope.launch {
            delay(600)
            send(call, key)
        }
    }

    private fun send(call: HaCall, pendingKey: String?, onOk: () -> Unit = {}) {
        scope.launch {
            val r = runCatching { HomeControl.send(AppSettings.state.value, call) }
            if (r.isSuccess) {
                onOk()
                Feeds.refreshHomeAfterControl()
            } else {
                pendingKey?.let { pending.remove(it) }
                toast(HomeRepo.haError(r.exceptionOrNull(), AppSettings.state.value.haUrlOrDefault).let { if (it.startsWith("连不上")) "操作失败：$it" else it })
            }
        }
    }

    companion object {
        private const val PENDING_TTL = 8_000L
    }
}

// ================================================================== 全屏外壳

/**
 * 全屏页的底：整屏模糊壁纸 + 深色压暗，盖住首页。淡入并从 0.97 放大到 1。
 * 关闭时继续画上一页直到淡出完，不然内容会先消失再淡出。
 */
@Composable
fun DetailOverlay(page: CardPage?, content: @Composable (CardPage) -> Unit) {
    var last by remember { mutableStateOf(page) }
    if (page != null) last = page
    val shown by animateFloatAsState(if (page != null) 1f else 0f, tween(Tv.MOVE, easing = Tv.easeOut), label = "detail")
    val p = last
    if (shown <= 0.01f || p == null) return
    Box(
        Modifier
            .zIndex(6f)
            .fillMaxSize()
            .graphicsLayer { alpha = shown }
            .frosted(RoundedCornerShape(0), Tv.glassTint.copy(alpha = 0.72f))
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val s = 0.97f + 0.03f * shown
                scaleX = s; scaleY = s
            }
        ) { content(p) }
    }
}

// ================================================================== 米家全屏页

@Composable
fun HomeDetail(data: HomeData, ctl: DetailCtl, onTile: (Int, Int) -> Unit, onCtl: (Int) -> Unit) {
    val ui = LocalUi.current
    val h = data.home
    val rows = gridRows(h.rooms)

    Row(Modifier.fillMaxSize().padding(start = ui.d(96), end = ui.d(72), top = ui.d(64), bottom = ui.d(40))) {
        // 左栏：家里现在怎么样
        Column(Modifier.width(ui.d(400)).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(ui.d(20))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(TvIcon.House, ui.d(30), Tv.text2)
                Spacer(Modifier.width(ui.d(12)))
                Text("米家", fontSize = ui.t(36), fontWeight = FontWeight.SemiBold, color = Tv.text)
            }
            if (h.reachable) {
                EnvPanel(h)
                AlertList(h)
            } else {
                Text(h.error ?: "家里设备没有响应", fontSize = ui.t(24), color = Tv.danger)
                Text(data.homeHost, fontSize = ui.t(20), color = Tv.text3)
            }
            Spacer(Modifier.weight(1f))
            val running = h.rooms.sumOf { r -> r.things.count { ctl.thingOn(it) } }
            if (rows.isNotEmpty()) Text("$running 个设备开着", fontSize = ui.t(22), color = Tv.text2)
            Text("确认键开关 · 长按确认键调节\n返回键关闭", fontSize = ui.t(20), lineHeight = ui.t(30), color = Tv.text3)
        }
        Spacer(Modifier.width(ui.d(48)))

        Box(Modifier.weight(1f).fillMaxHeight()) {
            when {
                !h.reachable -> Unit
                !data.homeUsesHa -> EmptyHint("控制设备需要接入 Home Assistant", "在设置 → 家里设备里填写 Home Assistant 地址和令牌")
                rows.isEmpty() -> EmptyHint("没有找到能控制的设备", "Home Assistant 里的灯、开关、空调、风扇、窗帘、扫地机会显示在这里")
                else -> DeviceGrid(rows, ctl, panelOpen = ctl.panel != null, onTile = onTile)
            }
        }
    }

    ctl.panelThing(h)?.let { ControlPanel(it, ctl, onCtl) }
}

@Composable
private fun EmptyHint(title: String, sub: String) {
    val ui = LocalUi.current
    Column(Modifier.padding(top = ui.d(80)), verticalArrangement = Arrangement.spacedBy(ui.d(12))) {
        Text(title, fontSize = ui.t(30), color = Tv.text)
        Text(sub, fontSize = ui.t(22), color = Tv.text3)
    }
}

@Composable
private fun EnvPanel(h: HomeUi) {
    val ui = LocalUi.current
    val env = h.env
    Column(verticalArrangement = Arrangement.spacedBy(ui.d(8))) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                env.temp?.let { String.format(Locale.CHINA, "%.1f", it) } ?: "—",
                fontSize = ui.t(96), lineHeight = ui.t(96), fontWeight = FontWeight.Light, color = Tv.text,
            )
            if (env.temp != null) Text("°", fontSize = ui.t(48), color = Tv.text2, modifier = Modifier.padding(bottom = ui.d(14)))
        }
        Text("室内", fontSize = ui.t(20), color = Tv.text3)
        listOfNotNull(
            env.humidity?.let { "湿度" to "${it.roundToInt()}%" },
            env.pm25?.let { "PM2.5" to "${it.roundToInt()}" },
            env.co2?.let { "CO₂" to "${it.roundToInt()} ppm" },
        ).forEach { (k, v) ->
            Row(Modifier.width(ui.d(260))) {
                Text(k, fontSize = ui.t(24), color = Tv.text2, modifier = Modifier.weight(1f))
                Text(v, fontSize = ui.t(24), color = Tv.text)
            }
        }
    }
}

@Composable
private fun AlertList(h: HomeUi) {
    val ui = LocalUi.current
    Column(Modifier.padding(top = ui.d(8)), verticalArrangement = Arrangement.spacedBy(ui.d(10))) {
        if (h.alerts.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(TvIcon.Check, ui.d(26), Tv.ok)
                Spacer(Modifier.width(ui.d(10)))
                Text("没有要处理的事", fontSize = ui.t(22), color = Tv.text2)
            }
        } else {
            h.alerts.take(6).forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(TvIcon.Alert, ui.d(24), Tv.danger)
                    Spacer(Modifier.width(ui.d(10)))
                    Text(a.text, fontSize = ui.t(22), color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (h.alerts.size > 6) Text("还有 ${h.alerts.size - 6} 件", fontSize = ui.t(20), color = Tv.text3, modifier = Modifier.padding(start = ui.d(34)))
        }
    }
}

// ------------------------------------------------------------------ 设备网格

@Composable
private fun DeviceGrid(rows: List<GridRow>, ctl: DetailCtl, panelOpen: Boolean, onTile: (Int, Int) -> Unit) {
    val ui = LocalUi.current
    val list = rememberLazyListState()
    val r = ctl.row.coerceIn(0, rows.lastIndex)
    // 焦点行离开可见区域时滚过去；房间的第一行连房间名一起露出来（房间名不单独占 item，在行里画）
    LaunchedEffect(r) {
        val vis = list.layoutInfo.visibleItemsInfo
        if (vis.isEmpty()) return@LaunchedEffect
        if (r <= vis.first().index || r >= vis.last().index) list.animateScrollToItem((r - 1).coerceAtLeast(0))
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = false,
        verticalArrangement = Arrangement.spacedBy(ui.d(16)),
    ) {
        itemsIndexed(rows, key = { _, g -> g.room.name + "/" + g.things.first().id }) { ri, g ->
            Column {
                if (g.firstInRoom) {
                    val on = g.room.things.count { ctl.thingOn(it) }
                    Text(
                        g.room.name + if (on > 0) " · $on 个开着" else "",
                        fontSize = ui.t(24), color = Tv.text2,
                        modifier = Modifier.padding(start = ui.d(4), top = ui.d(if (ri == 0) 0 else 16), bottom = ui.d(14)),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(ui.d(20))) {
                    g.things.forEachIndexed { ci, t ->
                        ThingTile(t, ctl, focused = !panelOpen && ri == r && ci == ctl.col, onTap = { onTile(ri, ci) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ThingTile(t: HomeThing, ctl: DetailCtl, focused: Boolean, onTap: () -> Unit) {
    val ui = LocalUi.current
    val on = ctl.thingOn(t)
    val busy = ctl.isPending("${t.id}|power", t.on)
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, Tv.focus(), label = "tile")
    val shape = RoundedCornerShape(ui.d(22))
    val bg = when {
        focused -> Color.White
        on -> Color.White.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.06f)
    }
    val fg = if (focused) Tv.dark else Tv.text
    val sub = when {
        busy -> if (on) "正在打开…" else "正在关闭…"
        else -> t.stateText
    }
    Column(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(ui.d(232), ui.d(140))
            .background(bg, shape)
            .border(ui.d(1), Color.White.copy(alpha = if (focused) 0f else 0.08f), shape)
            .tap(onTap)
            .padding(ui.d(20))
            .graphicsLayer { alpha = if (t.online) 1f else 0.45f },
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            Modifier
                .size(ui.d(44))
                .background(if (on) Color(0xFFFFC857) else if (focused) Tv.dark.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(deviceIcon(t.domain), ui.d(24), if (on) Tv.dark else fg)
        }
        Column {
            Text(t.name, fontSize = ui.t(22), fontWeight = FontWeight.Medium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = ui.t(18), color = if (focused) Tv.dark.copy(alpha = 0.6f) else Tv.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ 控制面板

@Composable
private fun ControlPanel(t: HomeThing, ctl: DetailCtl, onCtl: (Int) -> Unit) {
    val ui = LocalUi.current
    val ctls = HomeControl.controls(t)
    val index = ctl.panelIndex.coerceIn(0, (ctls.size - 1).coerceAtLeast(0))
    Scrim {
        Panel(820) {
            Text(t.name, fontSize = ui.t(34), fontWeight = FontWeight.SemiBold, color = Tv.text, maxLines = 1)
            Text(listOf(t.room, t.stateText).filter { it.isNotBlank() }.joinToString(" · "), fontSize = ui.t(22), color = Tv.text3)
            Spacer(Modifier.height(ui.d(8)))
            if (ctls.isEmpty()) {
                Text("这个设备没有可以调节的项目", fontSize = ui.t(24), color = Tv.text2)
            }
            ctls.forEachIndexed { i, c -> CtlRow(t, c, ctl, i == index) { onCtl(i) } }
            Spacer(Modifier.height(ui.d(4)))
            Text("上下选择 · 左右调节 · 确认键执行 · 返回键关闭", fontSize = ui.t(20), color = Tv.text3)
        }
    }
}

@Composable
private fun CtlRow(t: HomeThing, c: Ctl, ctl: DetailCtl, focused: Boolean, onTap: () -> Unit) {
    val ui = LocalUi.current
    val shape = RoundedCornerShape(ui.d(16))
    val fg = if (focused) Tv.dark else Tv.text
    val dim = if (focused) Tv.dark.copy(alpha = 0.55f) else Tv.text3
    Row(
        Modifier
            .fillMaxWidth()
            .height(ui.d(76))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.06f), shape)
            .tap(onTap)
            .padding(horizontal = ui.d(28)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(c.label, fontSize = ui.t(26), color = fg, modifier = Modifier.weight(1f))
        when (c) {
            is Ctl.Power -> {
                val on = ctl.display(t, c) == true
                Box(
                    Modifier
                        .size(ui.d(76), ui.d(40))
                        .background(if (on) Tv.ok else if (focused) Tv.dark.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.15f), RoundedCornerShape(50)),
                    contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Box(Modifier.padding(ui.d(4)).size(ui.d(32)).background(Color.White, CircleShape))
                }
            }
            is Ctl.Value -> {
                val v = ctl.display(t, c) as? Int ?: c.value
                Stepper(c.format(v), focused, fg, dim, v > c.min, v < c.max)
            }
            is Ctl.Choice -> {
                val cur = ctl.display(t, c) as? String
                val label = c.options.firstOrNull { it.first == cur }?.second ?: if (t.on) "—" else "未开机"
                Stepper(label, focused, fg, dim, true, true)
            }
            is Ctl.Action -> Text("确认键执行", fontSize = ui.t(20), color = dim)
        }
    }
}

@Composable
private fun Stepper(text: String, focused: Boolean, fg: Color, dim: Color, canDown: Boolean, canUp: Boolean) {
    val ui = LocalUi.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (focused) Text("‹", fontSize = ui.t(34), color = if (canDown) fg else dim.copy(alpha = 0.25f))
        Text(
            text,
            fontSize = ui.t(26), fontWeight = FontWeight.Medium, color = fg,
            modifier = Modifier.padding(horizontal = ui.d(18)),
        )
        if (focused) Text("›", fontSize = ui.t(34), color = if (canUp) fg else dim.copy(alpha = 0.25f))
    }
}
