package com.zlaong.tvhome.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * 天气数据：Open-Meteo 免费接口，不需要任何 API Key。
 * 城市名先转成经纬度，再取当天实况 + 高低温。
 */
data class WeatherUi(
    val loading: Boolean = true,
    val city: String = "",
    val temp: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val humidity: Int? = null,
    val code: Int = -1,
    val text: String = "",
    val emoji: String = "🌡",
    /** Open-Meteo 的 is_day：日出到日落之间为 true，晚上晴天画月亮 */
    val isDay: Boolean = true,
    val error: String? = null,
    val updatedAt: Long = 0L,
    /** 风向（中文，如「东北风」）和风力等级 */
    val windDir: String? = null,
    val windLevel: Int? = null,
    /** 接下来几个整点的预报，首页卡片底部用 */
    val hours: List<HourForecast> = emptyList(),

    // ---- 全屏详情页用
    /** 从下一个整点起 24 小时 */
    val hourly: List<HourForecast> = emptyList(),
    /** 今天起 7 天 */
    val daily: List<DayForecast> = emptyList(),
    val feelsLike: Double? = null,
    /** 百帕 */
    val pressure: Double? = null,
    /** 公里 */
    val visibilityKm: Double? = null,
    val uv: Double? = null,
    /** HH:mm */
    val sunrise: String? = null,
    val sunset: String? = null,
    /** 美国 EPA 标准 AQI（Open-Meteo 只提供这个和欧标），以及 PM 浓度 μg/m³ */
    val aqi: Int? = null,
    val pm25: Double? = null,
    val pm10: Double? = null,
)

/** pop：降水概率 %，拿不到时为 null */
data class HourForecast(val hour: Int, val temp: Double, val code: Int, val pop: Int? = null, val isDay: Boolean = true)

/** date：yyyy-MM-dd */
data class DayForecast(val date: String, val high: Double, val low: Double, val code: Int, val pop: Int?)

object WeatherRepo {

    /** 自动定位结果的有效期：家里网络出口城市基本不变，6 小时查一次足够 */
    private const val AUTO_TTL = 6 * 60 * 60 * 1000L

    suspend fun fetch(config: AppConfig): WeatherUi {
        val cityPlace = if (config.weatherAuto) autoPlace(config) else manualPlace(config)
        // 选了区就用区中心的坐标，标题显示「北京 · 顺义区」；自动定位要先拿到城市，才知道选的区还对不对
        val place = cityPlace?.let { p ->
            AppSettings.state.value.activeDistrict?.let { (name, lat, lon) -> Place("${p.city} · $name", lat, lon) } ?: p
        }
        val (city, lat, lon) = place ?: return WeatherUi(
            loading = false,
            city = if (config.weatherAuto) "" else config.cityName,
            error = if (config.weatherAuto) "自动定位失败，可在设置里改成手动城市" else "找不到城市「${config.cityName}」",
            updatedAt = System.currentTimeMillis(),
        )
        return try {

            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day," +
                "wind_speed_10m,wind_direction_10m,surface_pressure,visibility" +
                "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
                "uv_index_max,sunrise,sunset&forecast_days=7&timezone=auto"
            // 空气质量是另一个接口，和天气同时发；拿不到不影响天气
            val (root, aq) = coroutineScope {
                val a = async { runCatching { airQuality(lat, lon) }.getOrNull() }
                JSONObject(Http.get(url)) to a.await()
            }
            val cur = root.getJSONObject("current")
            val daily = root.getJSONObject("daily")

            val code = cur.optInt("weather_code", -1)
            val isDay = cur.optInt("is_day", 1) != 0
            val kind = describe(code, isDay)
            val windKmh = cur.optDouble("wind_speed_10m", Double.NaN)
            val windDeg = cur.optDouble("wind_direction_10m", Double.NaN)
            val days = parseDaily(daily)

            WeatherUi(
                loading = false,
                city = city,
                temp = cur.optDouble("temperature_2m", Double.NaN).takeIf { !it.isNaN() },
                humidity = cur.optInt("relative_humidity_2m", -1).takeIf { it >= 0 },
                high = daily.optJSONArray("temperature_2m_max")?.let { if (it.length() > 0) it.optDouble(0) else null },
                low = daily.optJSONArray("temperature_2m_min")?.let { if (it.length() > 0) it.optDouble(0) else null },
                code = code,
                text = kind.first,
                emoji = kind.second,
                isDay = isDay,
                updatedAt = System.currentTimeMillis(),
                windDir = windDeg.takeIf { !it.isNaN() }?.let(::windDirection),
                windLevel = windKmh.takeIf { !it.isNaN() }?.let(::beaufort),
                hours = parseHours(root.optJSONObject("hourly"), cur.optString("time")),
                hourly = parseHours(root.optJSONObject("hourly"), cur.optString("time"), count = 24, every = 1),
                daily = days,
                feelsLike = cur.num("apparent_temperature"),
                pressure = cur.num("surface_pressure"),
                visibilityKm = cur.num("visibility")?.let { it / 1000.0 },
                uv = daily.optJSONArray("uv_index_max")?.let { if (it.length() > 0 && !it.isNull(0)) it.optDouble(0) else null },
                sunrise = daily.optJSONArray("sunrise")?.optString(0)?.takeIf { it.length >= 16 }?.substring(11, 16),
                sunset = daily.optJSONArray("sunset")?.optString(0)?.takeIf { it.length >= 16 }?.substring(11, 16),
                aqi = aq?.num("us_aqi")?.roundToInt(),
                pm25 = aq?.num("pm2_5"),
                pm10 = aq?.num("pm10"),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 桌面不可见（待机、进设置）时轮询被取消：不能当成「拿不到」存下来，否则 15 分钟内都显示错误
            throw e
        } catch (e: Exception) {
            WeatherUi(loading = false, city = city, error = "天气暂时拿不到", updatedAt = System.currentTimeMillis())
        }
    }

    private data class Place(val city: String, val lat: Double, val lon: Double)

    private suspend fun manualPlace(config: AppConfig): Place? {
        if (config.lat != null && config.lon != null) return Place(config.cityName, config.lat, config.lon)
        val geo = geocode(config.cityName) ?: return null
        AppSettings.saveCoords(geo.first, geo.second)
        return Place(config.cityName, geo.first, geo.second)
    }

    /**
     * 按网络出口 IP 估算城市。电视没有 GPS，系统定位在这台索尼上也不可用（没有网络定位服务），
     * 所以只能用 IP，精度到城市级。查不到时沿用上一次的结果。
     */
    private suspend fun autoPlace(config: AppConfig): Place? {
        val cached = if (config.autoLat != null && config.autoLon != null) {
            Place(config.autoCity, config.autoLat, config.autoLon)
        } else null
        if (cached != null && System.currentTimeMillis() - config.autoAt < AUTO_TTL) return cached
        val fresh = ipLocate() ?: return cached
        AppSettings.saveAutoLocation(fresh.city, fresh.lat, fresh.lon)
        return fresh
    }

    /** 两家免费服务，第一家失败就换第二家；都不需要 API Key */
    private suspend fun ipLocate(): Place? {
        runCatching {
            val o = JSONObject(Http.get("https://ipwho.is/?lang=zh-CN&fields=success,city,latitude,longitude", timeoutMs = 5000))
            if (o.optBoolean("success")) {
                return Place(o.optString("city"), o.getDouble("latitude"), o.getDouble("longitude"))
            }
        }
        runCatching {
            val o = JSONObject(Http.get("http://ip-api.com/json/?lang=zh-CN&fields=status,city,lat,lon", timeoutMs = 5000))
            if (o.optString("status") == "success") {
                return Place(o.optString("city"), o.getDouble("lat"), o.getDouble("lon"))
            }
        }
        return null
    }

    private suspend fun geocode(city: String): Pair<Double, Double>? {
        repeat(2) { attempt ->
            try {
                val q = URLEncoder.encode(city, "UTF-8")
                val body = Http.get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=zh&format=json")
                val results = JSONObject(body).optJSONArray("results") ?: return null
                if (results.length() == 0) return null
                val first = results.getJSONObject(0)
                return first.getDouble("latitude") to first.getDouble("longitude")
            } catch (e: Exception) {
                if (attempt == 1) return null
                delay(800)
            }
        }
        return null
    }

    /** 从下一个整点往后取 count 个点，每隔 every 小时一个。首页卡片：4 个、隔 2 小时（12时、14时…） */
    private fun parseHours(hourly: JSONObject?, nowIso: String, count: Int = 4, every: Int = 2): List<HourForecast> {
        hourly ?: return emptyList()
        val times = hourly.optJSONArray("time") ?: return emptyList()
        val temps = hourly.optJSONArray("temperature_2m") ?: return emptyList()
        val codes = hourly.optJSONArray("weather_code") ?: return emptyList()
        val pops = hourly.optJSONArray("precipitation_probability")
        val days = hourly.optJSONArray("is_day")
        // 时间格式 2026-11-02T13:00，同一时区，直接比字符串
        val nowHour = nowIso.take(13)
        var start = (0 until times.length()).firstOrNull { times.optString(it).take(13) > nowHour } ?: return emptyList()
        val out = ArrayList<HourForecast>()
        while (out.size < count && start < times.length()) {
            val hour = times.optString(start).substring(11, 13).toIntOrNull() ?: break
            val pop = pops?.takeIf { !it.isNull(start) }?.optInt(start)
            val isDay = days?.optInt(start, 1)?.let { it != 0 } ?: true
            out.add(HourForecast(hour, temps.optDouble(start), codes.optInt(start, -1), pop, isDay))
            start += every
        }
        return out
    }

    private fun parseDaily(daily: JSONObject): List<DayForecast> {
        val dates = daily.optJSONArray("time") ?: return emptyList()
        val hi = daily.optJSONArray("temperature_2m_max") ?: return emptyList()
        val lo = daily.optJSONArray("temperature_2m_min") ?: return emptyList()
        val codes = daily.optJSONArray("weather_code")
        val pops = daily.optJSONArray("precipitation_probability_max")
        return (0 until dates.length()).mapNotNull { i ->
            if (hi.isNull(i) || lo.isNull(i)) return@mapNotNull null
            DayForecast(
                date = dates.optString(i),
                high = hi.optDouble(i),
                low = lo.optDouble(i),
                code = codes?.optInt(i, -1) ?: -1,
                pop = pops?.takeIf { !it.isNull(i) }?.optInt(i),
            )
        }
    }

    private suspend fun airQuality(lat: Double, lon: Double): JSONObject =
        JSONObject(
            Http.get(
                "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon" +
                    "&current=us_aqi,pm2_5,pm10&timezone=auto",
                timeoutMs = 6000,
            )
        ).getJSONObject("current")

    private fun JSONObject.num(key: String): Double? =
        if (isNull(key)) null else optDouble(key, Double.NaN).takeIf { !it.isNaN() }

    /** 美标 AQI 分级，和国标的分段一样（0-50 优 … 300+ 严重） */
    fun aqiLevel(aqi: Int): String = when {
        aqi <= 50 -> "优"
        aqi <= 100 -> "良"
        aqi <= 150 -> "轻度污染"
        aqi <= 200 -> "中度污染"
        aqi <= 300 -> "重度污染"
        else -> "严重污染"
    }

    private fun windDirection(deg: Double): String {
        val names = listOf("北风", "东北风", "东风", "东南风", "南风", "西南风", "西风", "西北风")
        return names[(((deg % 360) + 360) % 360 / 45.0 + 0.5).toInt() % 8]
    }

    /** km/h 换算成蒲福风级，界面上说「2 级」比说「9 km/h」更好懂 */
    private fun beaufort(kmh: Double): Int {
        val limits = listOf(1.0, 6.0, 12.0, 20.0, 29.0, 39.0, 50.0, 62.0, 75.0, 89.0, 103.0, 118.0)
        return limits.indexOfFirst { kmh < it }.let { if (it < 0) 12 else it }
    }

    /** 文字不分昼夜（iOS 中文版晚上也叫「晴」），只有 emoji 晚上换月亮 */
    fun describe(code: Int, isDay: Boolean = true): Pair<String, String> = when (code) {
        0 -> "晴" to if (isDay) "☀️" else "🌙"
        1 -> "少云" to if (isDay) "🌤" else "🌙"
        2 -> "多云" to if (isDay) "⛅️" else "☁️"
        3 -> "阴" to "☁️"
        45, 48 -> "有雾" to "🌫"
        51, 53, 55, 56, 57 -> "毛毛雨" to "🌦"
        61, 63, 65, 66, 67 -> "下雨" to "🌧"
        71, 73, 75, 77 -> "下雪" to "🌨"
        80, 81, 82 -> "阵雨" to "🌦"
        85, 86 -> "阵雪" to "❄️"
        95, 96, 99 -> "雷阵雨" to "⛈"
        else -> "天气" to "🌡"
    }
}