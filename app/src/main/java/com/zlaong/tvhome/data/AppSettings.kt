package com.zlaong.tvhome.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 桌面配置：Mac mini 上打印服务地址 + 天气城市。
 * 全部存在 SharedPreferences，改动后立即推给界面。
 */
data class AppConfig(
    val printerHost: String = "192.168.1.10:3000",
    val cityName: String = "杭州",
    val lat: Double? = null,
    val lon: Double? = null,
    /** 家里设备桥（tools/mijia-bridge）地址；留空时用 Mac mini 的 IP + 8080 */
    val homeHost: String = "",
    /** Home Assistant 地址，带 http://；留空时用 Mac mini 的 IP + 8123 */
    val haUrl: String = "",
    /** Home Assistant 长期访问令牌；填了就改读 Home Assistant，不再读设备桥 */
    val haToken: String = "",
    /** 首页米家卡片要显示的 Home Assistant 实体，按勾选顺序 */
    val haEntities: List<String> = emptyList(),
    /**
     * 米家卡片的环境读数用哪个传感器：temperature / humidity / pm25 / carbon_dioxide → 实体。
     * 没有这个 key = 自动挑（客厅优先）；值为空串 = 不显示这一项。
     */
    val haEnv: Map<String, String> = emptyMap(),
    /** 天气位置：true = 按网络自动定位，false = 用 cityName */
    val weatherAuto: Boolean = true,
    /** 自动定位上次查到的城市和经纬度；和手动城市分开存，来回切换不会互相覆盖 */
    val autoCity: String = "",
    val autoLat: Double? = null,
    val autoLon: Double? = null,
    val autoAt: Long = 0L,
) {
    val homeHostOrDefault: String
        get() = homeHost.ifBlank { printerHost.substringBefore(":") + ":8080" }

    val haUrlOrDefault: String
        get() = haUrl.ifBlank { "http://" + printerHost.substringBefore(":") + ":8123" }

    val useHa: Boolean get() = haToken.isNotBlank()

    /** 令牌不进日志 */
    override fun toString() =
        "AppConfig(printerHost=$printerHost, cityName=$cityName, weatherAuto=$weatherAuto, homeHost=$homeHost, haUrl=$haUrl, " +
            "haToken=${if (haToken.isBlank()) "<空>" else "<已设置>"}, haEntities=${haEntities.size})"
}

object AppSettings {

    private const val PREFS = "tvhome_settings"
    private const val K_HOST = "printer_host"
    private const val K_CITY = "city_name"
    private const val K_LAT = "lat"
    private const val K_LON = "lon"
    private const val K_HOME = "home_host"
    private const val K_PINNED = "pinned_apps"
    private const val K_LAST_VIDEO = "last_video_app"
    private const val K_HA_URL = "ha_url"
    private const val K_HA_TOKEN = "ha_token"
    private const val K_HA_ENTITIES = "ha_entities"
    private const val K_WEATHER_AUTO = "weather_auto"
    private const val K_HA_ENV = "ha_env"
    val ENV_KINDS = listOf("temperature", "humidity", "pm25", "carbon_dioxide")
    private const val K_AUTO_CITY = "auto_city"
    private const val K_AUTO_LAT = "auto_lat"
    private const val K_AUTO_LON = "auto_lon"
    private const val K_AUTO_AT = "auto_at"

    private lateinit var prefs: android.content.SharedPreferences

    private val _state = MutableStateFlow(AppConfig())
    val state: StateFlow<AppConfig> = _state.asStateFlow()

    /** 调试开关：显示假影视 APP，用来验证排版；正式包无效（HomeScreen 里判断 DEBUG） */
    var demoVideoApps: Boolean = false
        private set

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        demoVideoApps = prefs.getBoolean("demo_video_apps", false)
        _state.value = AppConfig(
            printerHost = prefs.getString(K_HOST, null) ?: AppConfig().printerHost,
            cityName = prefs.getString(K_CITY, null) ?: AppConfig().cityName,
            lat = prefs.getFloat(K_LAT, Float.NaN).let { if (it.isNaN()) null else it.toDouble() },
            lon = prefs.getFloat(K_LON, Float.NaN).let { if (it.isNaN()) null else it.toDouble() },
            homeHost = prefs.getString(K_HOME, null).orEmpty(),
            haUrl = prefs.getString(K_HA_URL, null).orEmpty(),
            haToken = prefs.getString(K_HA_TOKEN, null).orEmpty(),
            haEntities = prefs.getString(K_HA_ENTITIES, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            haEnv = prefs.getString(K_HA_ENV, null)?.split(',')?.mapNotNull {
                it.split('=', limit = 2).takeIf { p -> p.size == 2 && p[0].isNotBlank() }?.let { p -> p[0] to p[1] }
            }?.toMap().orEmpty(),
            // 老用户已经手动填过城市：保持手动，不擅自改成自动定位
            weatherAuto = prefs.getBoolean(K_WEATHER_AUTO, !prefs.contains(K_CITY)),
            autoCity = prefs.getString(K_AUTO_CITY, null).orEmpty(),
            autoLat = prefs.getFloat(K_AUTO_LAT, Float.NaN).let { if (it.isNaN()) null else it.toDouble() },
            autoLon = prefs.getFloat(K_AUTO_LON, Float.NaN).let { if (it.isNaN()) null else it.toDouble() },
            autoAt = prefs.getLong(K_AUTO_AT, 0L),
        )
        _pinned.value = prefs.getString(K_PINNED, null)?.split(',')?.filter { it.isNotBlank() }
        _lastVideo.value = prefs.getString(K_LAST_VIDEO, null)
    }

    // ------------------------------------------------------------ 抽屉「常用」

    /** null 表示用户从没改过，由桌面按已安装应用给一份默认值 */
    private val _pinned = MutableStateFlow<List<String>?>(null)
    val pinned: StateFlow<List<String>?> = _pinned.asStateFlow()

    fun setPinned(packages: List<String>) {
        prefs.edit().putString(K_PINNED, packages.joinToString(",")).apply()
        _pinned.value = packages
    }

    // ------------------------------------------------------------ 上次观看

    private val _lastVideo = MutableStateFlow<String?>(null)
    val lastVideo: StateFlow<String?> = _lastVideo.asStateFlow()

    fun setLastVideo(pkg: String) {
        if (_lastVideo.value == pkg) return
        prefs.edit().putString(K_LAST_VIDEO, pkg).apply()
        _lastVideo.value = pkg
    }

    /** 只有城市真的变了才清掉旧经纬度，下次刷新重新解析；以前每次保存都清，白白多查一次 */
    @Synchronized
    fun save(
        host: String,
        city: String,
        homeHost: String = _state.value.homeHost,
        weatherAuto: Boolean = _state.value.weatherAuto,
    ) {
        val old = _state.value
        val newCity = city.trim().ifBlank { old.cityName }
        val cityChanged = newCity != old.cityName
        val next = old.copy(
            printerHost = normalizeHost(host),
            cityName = newCity,
            lat = if (cityChanged) null else old.lat,
            lon = if (cityChanged) null else old.lon,
            homeHost = homeHost.trim().let { if (it.isBlank()) "" else normalizeHost(it, 8080) },
            weatherAuto = weatherAuto,
        )
        val edit = prefs.edit()
            .putString(K_HOME, next.homeHost)
            .putString(K_HOST, next.printerHost)
            .putString(K_CITY, next.cityName)
            .putBoolean(K_WEATHER_AUTO, next.weatherAuto)
        if (cityChanged) edit.remove(K_LAT).remove(K_LON)
        edit.apply()
        _state.value = next
    }

    /** 自动定位的结果落盘，6 小时内不重复查 */
    @Synchronized
    fun saveAutoLocation(city: String, lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(K_AUTO_CITY, city)
            .putFloat(K_AUTO_LAT, lat.toFloat())
            .putFloat(K_AUTO_LON, lon.toFloat())
            .putLong(K_AUTO_AT, now)
            .apply()
        _state.value = _state.value.copy(autoCity = city, autoLat = lat, autoLon = lon, autoAt = now)
    }

    /**
     * Home Assistant 配置，来自电脑上的设置网页。
     * token 传 null 表示「沿用已保存的」，网页从不回传完整令牌。
     */
    @Synchronized
    fun saveHa(url: String, token: String?, entities: List<String>, env: Map<String, String>? = null) {
        val next = _state.value.copy(
            haUrl = normalizeHaUrl(url),
            haToken = token?.trim() ?: _state.value.haToken,
            haEntities = entities.map { it.trim() }.filter { ENTITY_ID.matches(it) }.distinct(),
            haEnv = env?.filter { (k, v) -> k in ENV_KINDS && (v.isEmpty() || ENTITY_ID.matches(v)) }
                ?: _state.value.haEnv,
        )
        prefs.edit()
            .putString(K_HA_URL, next.haUrl)
            .putString(K_HA_TOKEN, next.haToken)
            .putString(K_HA_ENTITIES, next.haEntities.joinToString(","))
            .putString(K_HA_ENV, next.haEnv.entries.joinToString(",") { "${it.key}=${it.value}" })
            .apply()
        _state.value = next
    }

    private val ENTITY_ID = Regex("^[a-z_]+\\.[a-z0-9_]+$")

    /** 「192.168.1.10」→「http://192.168.1.10:8123」；留空表示用默认地址 */
    fun normalizeHaUrl(raw: String): String {
        var u = raw.trim().removeSuffix("/")
        if (u.isBlank()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        val hostPart = u.substringAfter("://")
        if (!hostPart.contains(":")) u = "$u:8123"
        return u
    }

    /** 城市被解析成经纬度后落盘，避免每次刷新都查一次地理编码 */
    fun saveCoords(lat: Double, lon: Double) {
        prefs.edit().putFloat(K_LAT, lat.toFloat()).putFloat(K_LON, lon.toFloat()).apply()
        _state.value = _state.value.copy(lat = lat, lon = lon)
    }

    /** 打印机接口完整地址，自动补齐 http:// 与默认端口 */
    fun printerUrl(path: String): String = "http://${_state.value.printerHost}$path"

    fun normalizeHost(raw: String, defaultPort: Int = 3000): String {
        var h = raw.trim()
        h = h.removePrefix("http://").removePrefix("https://").removeSuffix("/")
        if (!h.contains(":")) h = "$h:$defaultPort"
        return h
    }
}