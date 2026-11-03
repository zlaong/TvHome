package com.zlaong.tvhome.data

import android.util.Log
import com.zlaong.tvhome.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 首页卡片的数据和刷新节奏，放在界面外面：进设置页时首页界面会被销毁，
 * 放在界面里的「上次什么时候拉的」也会丢，回来就会重复请求。
 *
 * 规则：
 * - 只有桌面看得见时（Activity 在前台且不在设置页）才请求；离开就停。
 * - 回到桌面：数据过期了才马上拉（米家 10 秒、天气 15 分钟），否则等到期。
 * - 改了相关设置、在卡片上按 OK、翻到米家页：马上拉。
 * - 停在桌面时兜底：米家 60 秒，天气 15 分钟，打印机按状态 10 秒～2 分钟（目前关闭）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
object Feeds {
    private const val TAG = "TvHomeNet"
    private const val HOME_EVERY = 60_000L
    private const val HOME_FRESH = 10_000L
    private const val WEATHER_EVERY = 15 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    private val visible = MutableStateFlow(false)

    private val _weather = MutableStateFlow(WeatherUi())
    private val _printer = MutableStateFlow(PrinterUi(error = "正在连接…"))
    private val _home = MutableStateFlow(HomeUi())
    val weather: StateFlow<WeatherUi> = _weather.asStateFlow()
    val printer: StateFlow<PrinterUi> = _printer.asStateFlow()
    val home: StateFlow<HomeUi> = _home.asStateFlow()

    private fun trigger() = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val forceWeather = trigger()
    private val forcePrinter = trigger()
    private val forceHome = trigger()

    // 上次成功发出请求的时间和当时的设置；设置变了就算过期
    private var weatherAt = 0L
    private var weatherKey: Any? = null
    private var homeAt = 0L
    private var homeKey: Any? = null

    /** MainActivity 调用：前台且不在设置页 = true */
    fun setVisible(v: Boolean) {
        if (visible.value != v && BuildConfig.DEBUG) Log.d(TAG, if (v) "桌面可见，开始轮询" else "桌面不可见，停止轮询")
        visible.value = v
    }

    fun refreshWeather() { forceWeather.tryEmit(Unit) }
    fun refreshPrinter() { forcePrinter.tryEmit(Unit) }
    fun refreshHome() { forceHome.tryEmit(Unit) }

    /**
     * 下发控制之后调用：设备上报新状态要一会儿，隔 1 秒、3 秒、6 秒各拉一次。
     * 连续操作时取消上一轮，只按最后一次的节奏拉。
     */
    private var settleJob: kotlinx.coroutines.Job? = null

    fun refreshHomeAfterControl() {
        settleJob?.cancel()
        settleJob = scope.launch {
            for (wait in listOf(1_000L, 2_000L, 3_000L)) {
                kotlinx.coroutines.delay(wait)
                refreshHome()
            }
        }
    }

    /** 翻到米家页时调用：10 秒内刚拉过就不重复 */
    fun touchHome() {
        if (System.currentTimeMillis() - homeAt > HOME_FRESH) refreshHome()
    }

    @Volatile private var homeDetailOpen = false

    /** 米家全屏页打开 / 关闭：打开时马上拉一次，之后加快轮询 */
    fun setHomeDetailOpen(open: Boolean) {
        homeDetailOpen = open
        if (open) touchHome()
    }

    /** 天气全屏页打开：超过 5 分钟没拉过就重拉 */
    fun touchWeather() {
        if (System.currentTimeMillis() - weatherAt > 5 * 60_000L) refreshWeather()
    }

    fun start(printerEnabled: Boolean) {
        if (started) return
        started = true

        // 自动定位写回的 autoLat/autoLon 不算，否则每次定位成功都会触发重拉
        loop(
            key = AppSettings.state.map { listOf(it.weatherAuto, it.cityName, it.lat, it.lon) },
            force = forceWeather,
            fresh = { key -> key == weatherKey && System.currentTimeMillis() - weatherAt < WEATHER_EVERY },
            remaining = { WEATHER_EVERY - (System.currentTimeMillis() - weatherAt) },
        ) { key ->
            weatherAt = System.currentTimeMillis(); weatherKey = key
            _weather.value = WeatherRepo.fetch(AppSettings.state.value)
            WEATHER_EVERY
        }

        loop(
            key = AppSettings.state.map { listOf(it.homeHostOrDefault, it.haUrlOrDefault, it.haToken, it.haEntities, it.haEnv) },
            force = forceHome,
            fresh = { key -> key == homeKey && System.currentTimeMillis() - homeAt < HOME_FRESH },
            remaining = { HOME_FRESH - (System.currentTimeMillis() - homeAt) },
        ) { key ->
            homeAt = System.currentTimeMillis(); homeKey = key
            // 160 KB 的 JSON 解析放到后台线程，免得遥控器操作卡一下
            _home.value = withContext(Dispatchers.Default) { HomeRepo.fetch(AppSettings.state.value) }
            // 开着米家全屏页时 10 秒一次，家里别人用手机开了灯也能很快看到
            if (homeDetailOpen) HOME_FRESH else HOME_EVERY
        }

        if (printerEnabled) {
            loop(
                key = AppSettings.state.map { it.printerHost },
                force = forcePrinter,
                fresh = { false },
                remaining = { 0L },
            ) {
                val p = PrinterRepo.fetch(AppSettings.state.value)
                _printer.value = p
                when {
                    !p.reachable -> 120_000L
                    p.isPrinting() -> 10_000L
                    else -> 60_000L
                }
            }
        }
    }

    /**
     * 一个数据源的轮询：看得见且设置不变时循环；看不见就整个停掉。
     * 回到桌面时数据还新鲜，就先等到期再拉；按 OK（force）随时打断等待立即拉。
     */
    private fun <K> loop(
        key: Flow<K>,
        force: MutableSharedFlow<Unit>,
        fresh: (K) -> Boolean,
        remaining: () -> Long,
        fetch: suspend (K) -> Long,
    ) {
        scope.launch {
            combine(visible, key.distinctUntilChanged()) { v, k -> v to k }
                .distinctUntilChanged()
                .collectLatest { (v, k) ->
                    if (!v) return@collectLatest
                    if (fresh(k)) withTimeoutOrNull(remaining().coerceAtLeast(0)) { force.first() }
                    while (true) {
                        val next = fetch(k)
                        withTimeoutOrNull(next) { force.first() }
                    }
                }
        }
    }
}
