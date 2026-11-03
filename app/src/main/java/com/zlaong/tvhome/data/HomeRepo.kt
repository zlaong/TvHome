package com.zlaong.tvhome.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 家里设备，两种来源：
 * - 配了 Home Assistant 令牌：读 Home Assistant（米家设备经 xiaomi_miot 接入）；
 * - 否则读 Mac mini 上 tools/mijia-bridge 的 /api/home。
 * 首页卡片只展示状态；全屏页的控制见 HomeControl（只支持 Home Assistant）。
 */
data class HomeDevice(
    val id: String,
    val name: String,
    val kind: String,
    val online: Boolean,
    /** 「运行中」：灯亮着、空调开着、扫地机在扫、窗帘开着…… */
    val on: Boolean,
    val brightness: Int?,
    /** 运行中显示的状态，比如「制冷 26°」「清扫中」「自动」；为空时界面按开关显示 */
    val stateText: String? = null,
)

/** 卡片上方的环境读数 */
data class HomeEnv(
    val temp: Double? = null,
    val humidity: Double? = null,
    val pm25: Double? = null,
    val co2: Double? = null,
)

/** 需要注意的事，红字显示；level 越小越紧急，用来排序 */
data class HomeAlert(val text: String, val level: Int)

data class HomeUi(
    val reachable: Boolean = false,
    /** 勾选的设备，按勾选顺序；卡片只显示其中运行中的 */
    val devices: List<HomeDevice> = emptyList(),
    val env: HomeEnv = HomeEnv(),
    val alerts: List<HomeAlert> = emptyList(),
    val error: String? = null,
    /** 全屏页：家里所有能控制的设备，按房间分组；只有 Home Assistant 有 */
    val rooms: List<HomeRoom> = emptyList(),
) {
    val running: List<HomeDevice> get() = devices.filter { it.online && it.on }
    val onCount: Int get() = running.size
}

/** 设置网页里列出来供勾选的一项 */
data class HaEntity(
    val id: String,
    val name: String,
    val domain: String,
    val state: String,
    /** 默认帮你勾上的：主灯、风扇、空调等，不含设备自带的指示灯 */
    val suggested: Boolean,
    /** 能当环境读数的：temperature / humidity / pm25 / carbon_dioxide */
    val envKind: String? = null,
)

object HomeRepo {

    // 提醒阈值
    private const val FILTER_LOW = 10.0
    private const val BATTERY_LOW = 15.0
    private const val PM25_BAD = 75.0
    private const val CO2_BAD = 1000.0

    suspend fun fetch(config: AppConfig): HomeUi =
        if (config.useHa) fetchHa(config) else fetchBridge(config)

    // ------------------------------------------------------------ Home Assistant

    /**
     * 一次拉 /api/states（约 270 个实体、160 KB），同时算出环境、提醒、设备状态。
     * 提醒要扫所有门窗、滤芯、电量，按实体逐个拉反而更慢；30 秒一次电视解析得动。
     */
    private suspend fun fetchHa(config: AppConfig): HomeUi {
        val base = config.haUrlOrDefault
        val arr = try {
            JSONArray(Http.get("$base/api/states", timeoutMs = 8000, headers = auth(config.haToken)))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return HomeUi(reachable = false, error = haError(e, base))
        }
        val byId = HashMap<String, JSONObject>(arr.length() * 2)
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { byId[it.optString("entity_id")] = it }

        val reg = registry(base, config.haToken)
        val areas = reg.areas
        val devices = config.haEntities.mapNotNull { id -> byId[id]?.let(::toDevice) }
        val env = env(config, byId, areas)
        val alerts = alerts(byId.values, env, areas)
        val rooms = runCatching { HomeControl.build(byId, areas, reg.devices) }.getOrDefault(emptyList())
        return HomeUi(reachable = true, devices = devices, env = env, alerts = alerts, rooms = rooms)
    }

    // ---------------------------------------------------------------- 房间

    private class Registry(val areas: Map<String, String>, val devices: Map<String, String>)

    /**
     * 实体 → 房间名、实体 → 设备 id。REST 的 /api/states 里没有这两项，要用模板接口查；很少变，10 分钟查一次。
     * 房间用来把「窗户开着」写成「主卧窗户开着」、默认挑客厅的温湿度、全屏页按房间分组；
     * 设备 id 用来把同一台设备的多个实体合成一个格子。
     */
    @Volatile private var regCache: Pair<Long, Registry> = 0L to Registry(emptyMap(), emptyMap())

    private suspend fun registry(base: String, token: String): Registry {
        val (at, cached) = regCache
        if (System.currentTimeMillis() - at < 10 * 60_000L && cached.areas.isNotEmpty()) return cached
        val tpl = "{% set ns = namespace(o={}) %}{% for s in states %}" +
            "{% set a = area_name(s.entity_id) %}{% set d = device_id(s.entity_id) %}" +
            "{% if a or d %}{% set ns.o = dict(ns.o, **{s.entity_id: [a or '', d or '']}) %}{% endif %}" +
            "{% endfor %}{{ ns.o | tojson }}"
        val fresh = runCatching {
            val o = JSONObject(Http.post("$base/api/template", JSONObject().put("template", tpl).toString(), timeoutMs = 8000, headers = auth(token)))
            val areas = HashMap<String, String>()
            val devs = HashMap<String, String>()
            for (k in o.keys()) {
                val pair = o.optJSONArray(k) ?: continue
                pair.optString(0).takeIf { it.isNotBlank() }?.let { areas[k] = it }
                pair.optString(1).takeIf { it.isNotBlank() }?.let { devs[k] = it }
            }
            Registry(areas, devs)
        }.getOrNull() ?: return cached
        regCache = System.currentTimeMillis() to fresh
        return fresh
    }

    /** 「主卧 窗户」→「主卧窗户」；名字里已经带房间就不重复加 */
    private fun withRoom(dev: String, room: String?): String {
        val short = dev.replace("门窗传感器", "门窗")
        return if (room.isNullOrBlank() || short.contains(room)) short else room + short
    }

    // ---------------------------------------------------------------- 环境

    private fun env(config: AppConfig, byId: Map<String, JSONObject>, areas: Map<String, String>): HomeEnv {
        fun read(id: String): Double? = byId[id]?.optString("state")?.toDoubleOrNull()
        val picked = config.haEnv
        // 没在网页里选过：优先客厅里的专用温湿度计，其次客厅里别的设备，最后任意一个
        fun auto(kind: String): Double? {
            val all = byId.values.filter { envKind(it) == kind && it.optString("state").toDoubleOrNull() != null }
            fun inLiving(o: JSONObject) = areas[o.optString("entity_id")] == "客厅" || name(o).contains("客厅")
            val best = all.firstOrNull { inLiving(it) && name(it).contains("温湿度") }
                ?: all.firstOrNull(::inLiving)
                ?: all.firstOrNull()
            return best?.optString("state")?.toDoubleOrNull()
        }
        fun pick(kind: String) = picked[kind]?.let(::read) ?: if (picked.containsKey(kind)) null else auto(kind)
        return HomeEnv(temp = pick("temperature"), humidity = pick("humidity"), pm25 = pick("pm25"), co2 = pick("carbon_dioxide"))
    }

    private fun envKind(o: JSONObject): String? {
        val id = o.optString("entity_id")
        if (!id.startsWith("sensor.")) return null
        val dc = o.optJSONObject("attributes")?.optString("device_class").orEmpty()
        return when {
            dc in setOf("temperature", "humidity", "pm25", "carbon_dioxide") -> dc
            id.endsWith("_pm25_density") -> "pm25"
            id.endsWith("_co2_density") -> "carbon_dioxide"
            else -> null
        }.takeUnless { id.contains("chunmi_") || id.contains("hfjh_") || id.contains("lx40") } // 电饭煲、鱼缸、净水器的水温不算室温
    }

    // ---------------------------------------------------------------- 提醒

    private fun alerts(all: Collection<JSONObject>, env: HomeEnv, areas: Map<String, String>): List<HomeAlert> {
        val out = ArrayList<HomeAlert>()
        val lowFilters = LinkedHashMap<String, Double>()
        val contactIds = all.map { it.optString("entity_id") }.filter { it.endsWith("_contact_state") }.toSet()
        for (o in all) {
            val id = o.optString("entity_id")
            val state = o.optString("state")
            val attrs = o.optJSONObject("attributes") ?: JSONObject()
            val dc = attrs.optString("device_class")
            val dev = withRoom(deviceName(o), areas[id])
            when {
                // 烟雾：报警最紧急；传感器离线也要提醒，不然真着火了也不知道
                id.startsWith("binary_sensor.") && (dc == "smoke" || id.contains("smoke")) -> when (state) {
                    "on" -> out += HomeAlert("${dev}检测到烟雾", 0)
                    "unavailable" -> out += HomeAlert("${dev}离线", 3)
                }

                /*
                 * 门窗：xiaomi_miot 给同一个门窗传感器建了两个实体，状态正好相反：
                 *   *_contact_state：on = 开着（已和实际核对：次卧开着时报 on）
                 *   *_magnet_sensor（device_class=door）：和上面反着，不可信
                 * 有 contact_state 就只看它；只有 magnet_sensor 的老款按 Home Assistant 惯例 on = 开着。
                 */
                id.startsWith("binary_sensor.") && id.endsWith("_contact_state") ->
                    if (state == "on") out += HomeAlert("${dev}开着", 1)

                id.startsWith("binary_sensor.") && dc in setOf("door", "window", "opening", "garage_door") -> {
                    val twin = id.removeSuffix("_magnet_sensor") + "_contact_state"
                    if (twin !in contactIds && state == "on") out += HomeAlert("${dev}开着", 1)
                }

                id.startsWith("vacuum.") && state == "error" -> out += HomeAlert("${dev}出错了", 1)
                id.startsWith("sensor.") && id.endsWith("_status") && id.contains("roborock") &&
                    state.contains("error", ignoreCase = true) -> out += HomeAlert("${dev}出错了", 1)

                id.startsWith("sensor.") && id.contains("filter_life_level") -> {
                    val short = deviceName(o)
                    val v = state.toDoubleOrNull()
                    // 同一台设备有两个滤芯时只提醒剩得最少的那个
                    if (v != null && v < FILTER_LOW && (lowFilters[short] ?: Double.MAX_VALUE) > v) lowFilters[short] = v
                }

                id.startsWith("sensor.") && dc == "battery" -> {
                    val v = state.toDoubleOrNull()
                    if (v != null && v < BATTERY_LOW) out += HomeAlert("${dev}电量 ${v.toInt()}%", 2)
                }
            }
        }
        lowFilters.forEach { (dev, v) -> out += HomeAlert("${dev}滤芯剩 ${v.toInt()}%", 2) }
        env.pm25?.takeIf { it > PM25_BAD }?.let { out += HomeAlert("PM2.5 ${it.toInt()}，空气差", 1) }
        env.co2?.takeIf { it > CO2_BAD }?.let { out += HomeAlert("CO₂ ${it.toInt()} ppm，该通风了", 1) }
        return out.distinctBy { it.text }.sortedBy { it.level }
    }

    // ---------------------------------------------------------------- 设备

    private fun toDevice(o: JSONObject): HomeDevice {
        val id = o.optString("entity_id")
        val domain = id.substringBefore('.')
        val state = o.optString("state")
        val attrs = o.optJSONObject("attributes") ?: JSONObject()
        val online = state != "unavailable"
        val on = when (domain) {
            "light", "switch", "fan", "humidifier" -> state == "on"
            "media_player" -> state == "playing"
            "climate" -> state != "off" && state != "unknown" && online
            "cover" -> state == "open" || state == "opening" || state == "closing"
            "vacuum" -> state in setOf("cleaning", "returning", "error")
            "lock" -> state == "unlocked"
            else -> state == "on"
        }
        val brightness = if (domain == "light" && on && attrs.has("brightness")) {
            (attrs.optDouble("brightness", 0.0) / 255.0 * 100).toInt().coerceIn(1, 100)
        } else null
        val text = when (domain) {
            "light" -> brightness?.let { "$it%" } ?: "开"
            "climate" -> climateText(state, attrs)
            "fan" -> attrs.optString("preset_mode").takeIf { it.isNotBlank() && it != "null" }?.let(::presetText)
                ?: attrs.optInt("percentage", -1).takeIf { it > 0 }?.let { "$it%" } ?: "开"
            "cover" -> mapOf("open" to "已打开", "opening" to "打开中", "closing" to "关闭中")[state]
            "vacuum" -> mapOf("cleaning" to "清扫中", "returning" to "回充中", "error" to "出错")[state]
            "media_player" -> "播放中"
            "lock" -> "未上锁"
            else -> "开"
        }
        return HomeDevice(id = id, name = deviceName(o), kind = domain, online = online, on = on, brightness = brightness, stateText = text)
    }

    private fun presetText(p: String) = when (p.lowercase()) {
        "auto" -> "自动"; "sleep", "silent" -> "睡眠"; "favorite" -> "最爱"; "strong", "turbo" -> "强力"
        else -> p
    }

    private fun climateText(state: String, attrs: JSONObject): String {
        val mode = mapOf("cool" to "制冷", "heat" to "制热", "dry" to "除湿", "fan_only" to "送风", "auto" to "自动", "heat_cool" to "自动")[state] ?: "开"
        val t = attrs.optDouble("temperature", Double.NaN)
        return if (t.isNaN()) mode else "$mode ${t.toInt()}°"
    }

    private fun name(o: JSONObject) =
        o.optJSONObject("attributes")?.optString("friendly_name").orEmpty().ifBlank { o.optString("entity_id") }

    /**
     * Home Assistant 的名字是「设备名 + 功能名」，比如「米家空气净化器 5S 空气净化器」「吸顶灯 灯」。
     * 卡片上只要设备名：去掉后半截功能名，再去掉「米家」前缀和型号。
     */
    fun deviceName(o: JSONObject): String {
        val full = name(o).trim()
        val parts = full.split(' ').filter { it.isNotBlank() }
        var dev = when {
            parts.size <= 1 -> full
            // 「温湿度计（客厅） Temperature Humidity Sensor Temperature」：英文功能名整段丢掉
            parts.drop(1).all { it.all { c -> c.code < 128 } } -> parts.first()
            // 「米家空气净化器 5S 空气净化器」：型号保留到功能名之前
            else -> parts.dropLast(1).joinToString(" ")
        }
        dev = dev.removePrefix("米家").removePrefix("小米").removePrefix("Yeelight").removePrefix("Chameleon").trim()
        // 「空气净化器 5S」→「空气净化器」：型号对看电视的人没用
        dev = dev.replace(Regex("\\s+[A-Za-z0-9.]+$"), "")
        // 「智能晾衣机Pro」→「晾衣机」
        dev = dev.removePrefix("智能").replace(Regex("(Pro|Max|Plus|Lite)$"), "").trim()
        return dev.ifBlank { full }
    }

    // ---------------------------------------------------------------- 设置网页用

    /** 设置网页「测试连接」和「列出设备」用 */
    suspend fun listHaEntities(base: String, token: String): List<HaEntity> {
        val arr = JSONArray(Http.get("$base/api/states", timeoutMs = 8000, headers = auth(token)))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("entity_id")
            val domain = id.substringBefore('.')
            val ek = envKind(o)
            if (domain !in DEVICE_DOMAINS && ek == null) return@mapNotNull null
            HaEntity(
                id = id,
                name = deviceName(o),
                domain = domain,
                state = o.optString("state"),
                suggested = domain in SUGGESTED_DOMAINS && !looksLikeIndicator(id, name(o)),
                envKind = ek,
            )
        }.sortedWith(compareBy({ DEVICE_DOMAINS.indexOf(it.domain).let { d -> if (d < 0) 99 else d } }, { it.name }))
    }

    /** 能当「运行中设备」显示的类型，按网页里的分组顺序 */
    private val DEVICE_DOMAINS = listOf(
        "light", "climate", "fan", "humidifier", "cover", "vacuum", "switch", "media_player", "lock",
    )
    private val SUGGESTED_DOMAINS = setOf("light", "fan", "climate", "cover", "humidifier", "vacuum")

    /** 摄像头、网关之类自带的指示灯也被 Home Assistant 当成 light，不默认勾 */
    private fun looksLikeIndicator(id: String, name: String): Boolean {
        val s = "$id $name".lowercase()
        return listOf("indicator", "指示灯", "status_light", "night_light_mode").any { it in s }
    }

    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token")

    fun haError(e: Throwable?, base: String): String = when ((e as? HttpStatusException)?.code) {
        401, 403 -> "Home Assistant 令牌无效或已过期"
        null -> "连不上 Home Assistant（$base）"
        else -> "Home Assistant 返回错误 ${(e as HttpStatusException).code}"
    }

    // ------------------------------------------------------------ mijia-bridge（旧）

    private suspend fun fetchBridge(config: AppConfig): HomeUi {
        val host = config.homeHostOrDefault
        return try {
            val body = Http.get("http://$host/api/home", timeoutMs = 4000)
            val arr = JSONObject(body).optJSONArray("devices")
            val devices = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val d = arr?.optJSONObject(i) ?: return@mapNotNull null
                val on = d.optBoolean("on", false)
                val b = d.optInt("brightness", -1).takeIf { it >= 0 }
                HomeDevice(
                    id = d.optString("id"),
                    name = d.optString("name").ifBlank { "设备" },
                    kind = d.optString("kind", d.optString("type", "light")),
                    online = d.optBoolean("online", false),
                    on = on,
                    brightness = b,
                    stateText = if (on) b?.let { "$it%" } ?: "开" else null,
                )
            }
            HomeUi(reachable = true, devices = devices)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            HomeUi(reachable = false, error = "连不上家里设备桥（$host）")
        }
    }
}
