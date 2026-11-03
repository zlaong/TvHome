package com.zlaong.tvhome.data

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 米家全屏页：家里所有能控制的设备，按房间分组，可以直接下发控制。
 *
 * Home Assistant 把一台设备拆成很多实体（空气净化器 = fan + UV 开关 + 负离子开关 + 童锁 + 提示音……）。
 * 这里按 device_id 合并回「一台设备一个格子」：挑一个主实体负责开关，
 * 少数有用的附属开关（氛围灯、UV、负离子、摆风……）放进详情面板，童锁、提示音这类不显示。
 */
data class HomeThing(
    /** 主实体 entity_id，也当格子的唯一键 */
    val id: String,
    val name: String,
    val room: String,
    val domain: String,
    val state: String,
    val online: Boolean,
    val on: Boolean,
    /** 格子第二行：「80%」「制冷 26°」「自动」「已关」 */
    val stateText: String,
    /** 主实体的属性，生成控制项用 */
    val attrs: JSONObject,
    /** 附属开关：entity_id → (名字, 开着没有) */
    val extras: List<Extra> = emptyList(),
) {
    data class Extra(val id: String, val label: String, val on: Boolean, val online: Boolean)

    /** 按确认键直接开关；窗帘、扫地机这类动作不可逆或会动起来的，先弹面板 */
    val quickToggle: Boolean get() = domain in setOf("light", "switch", "fan", "climate", "humidifier")
}

data class HomeRoom(val name: String, val things: List<HomeThing>)

/** 一次控制：调用 Home Assistant 的 /api/services/<domain>/<service> */
data class HaCall(val domain: String, val service: String, val entity: String, val data: Map<String, Any> = emptyMap())

/** 详情面板里的一行 */
sealed class Ctl {
    abstract val key: String
    abstract val label: String

    /** 开 / 关；entity 是被开关的实体（主实体或附属开关） */
    data class Power(override val key: String, override val label: String, val entity: String, val on: Boolean, val turnOn: HaCall, val turnOff: HaCall) : Ctl()

    /** 左右键加减的数值：亮度、温度、风速档位 */
    data class Value(
        override val key: String,
        override val label: String,
        val value: Int,
        val min: Int,
        val max: Int,
        val step: Int,
        val format: (Int) -> String,
        val call: (Int) -> HaCall,
    ) : Ctl()

    /** 左右键切换的选项：模式、风量 */
    data class Choice(
        override val key: String,
        override val label: String,
        val options: List<Pair<String, String>>,
        val current: String?,
        val call: (String) -> HaCall,
    ) : Ctl()

    /** 按一下执行：打开窗帘、回充 */
    data class Action(override val key: String, override val label: String, val call: HaCall) : Ctl()
}

object HomeControl {

    // ---------------------------------------------------------------- 合并实体

    /** 主实体优先级：一台设备有空调又有灯（比如晾衣机有灯）时，格子按排在前面的算 */
    private val PRIMARY = listOf("climate", "vacuum", "cover", "fan", "humidifier", "light", "switch")

    /** 附属开关里值得放进面板的，按 entity_id 结尾识别 */
    private val EXTRA_SWITCH = linkedMapOf(
        "_uv" to "UV 杀菌",
        "_anion" to "负离子",
        "_heater" to "辅热",
        "_vertical_swing" to "上下摆风",
        "_horizontal_swing" to "左右摆风",
        "_nightlight_switch" to "夜灯",
        "_deodorization" to "除臭",
        "_fish_tank" to "鱼缸",
        "_water_pump" to "水泵",
    )

    private fun isIndicator(id: String, name: String): Boolean {
        val s = "$id $name".lowercase()
        return listOf("indicator", "指示灯", "status_light").any { it in s }
    }

    /** 灯里的次要灯：氛围灯、夜灯，有主灯时只放面板 */
    private fun isSecondaryLight(id: String) =
        id.endsWith("_ambient_light") || id.endsWith("_night_light") || id.endsWith("_background_light")

    /**
     * 主开关：智能插座、墙壁开关的 switch 实体通常以 _switch 结尾；
     * _switch_status 是净化器、马桶盖之类的「屏幕/总开关」镜像，有别的主实体时不要。
     */
    private fun isMainSwitch(id: String) = id.endsWith("_switch") || id.matches(Regex(".*_switch_\\d+$"))

    fun build(byId: Map<String, JSONObject>, rooms: Map<String, String>, devices: Map<String, String>): List<HomeRoom> {
        // device_id → 它的实体；没有 device_id 的实体各自成组
        val groups = LinkedHashMap<String, MutableList<JSONObject>>()
        for ((id, o) in byId) {
            val domain = id.substringBefore('.')
            if (domain !in PRIMARY) continue
            groups.getOrPut(devices[id] ?: id) { ArrayList() } += o
        }

        val things = groups.values.mapNotNull { ents ->
            val usable = ents.filter { !isIndicator(it.optString("entity_id"), friendly(it)) }
            val primary = pickPrimary(usable) ?: return@mapNotNull null
            val pid = primary.optString("entity_id")
            val extras = usable.filter { it !== primary }.mapNotNull { e -> extraOf(e) }
            toThing(primary, rooms[pid] ?: "", extras)
        }

        return things
            .groupBy { it.room.ifBlank { "其他" } }
            .map { (room, list) -> HomeRoom(room, list.sortedWith(compareBy({ PRIMARY.indexOf(it.domain) }, { it.name }))) }
            .sortedWith(compareBy({ roomOrder(it.name) }, { it.name }))
    }

    private fun roomOrder(r: String) = when (r) {
        "客厅" -> 0
        "其他" -> 99
        else -> 1
    }

    private fun pickPrimary(ents: List<JSONObject>): JSONObject? {
        fun id(o: JSONObject) = o.optString("entity_id")
        for (d in PRIMARY) {
            val ofDomain = ents.filter { id(it).startsWith("$d.") }
            if (ofDomain.isEmpty()) continue
            when (d) {
                "light" -> return ofDomain.firstOrNull { !isSecondaryLight(id(it)) } ?: ofDomain.first()
                // 只剩摄像头开关、提示音这类附属开关的设备（摄像头、音箱）不当成可控设备
                "switch" -> return ofDomain.firstOrNull { isMainSwitch(id(it)) }
                else -> return ofDomain.first()
            }
        }
        return null
    }

    private fun extraOf(o: JSONObject): HomeThing.Extra? {
        val id = o.optString("entity_id")
        val state = o.optString("state")
        val label = when {
            id.startsWith("light.") && id.endsWith("_ambient_light") -> "氛围灯"
            id.startsWith("light.") && id.endsWith("_night_light") -> "夜灯"
            id.startsWith("light.") -> "灯"
            id.startsWith("switch.") -> EXTRA_SWITCH.entries.firstOrNull { id.endsWith(it.key) }?.value
            else -> null
        } ?: return null
        return HomeThing.Extra(id, label, on = state == "on", online = state != "unavailable")
    }

    private fun friendly(o: JSONObject) = o.optJSONObject("attributes")?.optString("friendly_name").orEmpty()

    private fun toThing(o: JSONObject, room: String, extras: List<HomeThing.Extra>): HomeThing {
        val id = o.optString("entity_id")
        val domain = id.substringBefore('.')
        val state = o.optString("state")
        val a = o.optJSONObject("attributes") ?: JSONObject()
        val online = state != "unavailable"
        val on = online && when (domain) {
            "climate" -> state != "off" && state != "unknown"
            "cover" -> state == "open" || state == "opening" || state == "closing"
            "vacuum" -> state in setOf("cleaning", "returning", "error")
            else -> state == "on"
        }
        val text = when {
            !online -> "离线"
            domain == "vacuum" -> vacuumText(state, a)
            domain == "cover" -> mapOf("open" to "已打开", "closed" to "已关闭", "opening" to "打开中", "closing" to "关闭中")[state] ?: "可控制"
            !on -> "已关"
            domain == "light" -> brightnessPct(a)?.let { "$it%" } ?: "开"
            domain == "climate" -> {
                val t = a.optDouble("temperature", Double.NaN)
                (HVAC[state] ?: "开") + if (t.isNaN()) "" else " ${t.roundToInt()}°"
            }
            domain == "fan" -> a.optString("preset_mode").takeIf { it.isNotBlank() && it != "null" && it != "None" }?.let(::presetLabel)
                ?: a.optInt("percentage", -1).takeIf { it > 0 }?.let { "$it%" } ?: "开"
            domain == "humidifier" -> a.optString("mode").takeIf { it.isNotBlank() && it != "null" }?.let(::presetLabel) ?: "开"
            else -> "开"
        }
        return HomeThing(id, HomeRepo.deviceName(o), room, domain, state, online, on, text, a, extras)
    }

    private fun vacuumText(state: String, a: JSONObject): String {
        val battery = a.optInt("battery_level", -1).takeIf { it >= 0 }
        return when (state) {
            "cleaning" -> "清扫中"
            "returning" -> "回充中"
            "paused" -> "已暂停"
            "error" -> "出错了"
            "docked" -> if (battery != null && battery < 100) "充电中 $battery%" else "在充电座"
            "idle" -> "待机"
            else -> "待机"
        }
    }

    // ---------------------------------------------------------------- 控制项

    fun controls(t: HomeThing): List<Ctl> {
        val id = t.id
        val a = t.attrs
        val out = ArrayList<Ctl>()
        fun call(service: String, data: Map<String, Any> = emptyMap()) = HaCall(t.domain, service, id, data)

        when (t.domain) {
            "light" -> {
                out += power(t)
                val modes = a.optJSONArray("supported_color_modes")?.let { m -> (0 until m.length()).map { m.optString(it) } }.orEmpty()
                val dimmable = modes.any { it != "onoff" } || a.has("brightness")
                if (dimmable) {
                    out += Ctl.Value("bri", "亮度", brightnessPct(a) ?: 100, 1, 100, 10, { "$it%" }) { v ->
                        call("turn_on", mapOf("brightness_pct" to v))
                    }
                }
                if ("color_temp" in modes && a.has("min_color_temp_kelvin")) {
                    val lo = a.optInt("min_color_temp_kelvin", 2700)
                    val hi = a.optInt("max_color_temp_kelvin", 6500)
                    val cur = a.optInt("color_temp_kelvin", (lo + hi) / 2)
                    out += Ctl.Value("ct", "色温", cur.coerceIn(lo, hi), lo, hi, 400, { "${it}K" }) { v ->
                        call("turn_on", mapOf("color_temp_kelvin" to v))
                    }
                }
            }

            "switch" -> out += power(t)

            "fan" -> {
                out += power(t)
                val presets = strings(a, "preset_modes").filter { it != "None" }
                if (presets.isNotEmpty()) {
                    out += Ctl.Choice("preset", "模式", presets.map { it to presetLabel(it) }, a.optString("preset_mode").takeIf { it in presets }) { p ->
                        call("set_preset_mode", mapOf("preset_mode" to p))
                    }
                }
                val step = a.optDouble("percentage_step", 0.0)
                if (step in 1.0..50.0 && a.has("percentage")) {
                    val levels = (100.0 / step).roundToInt()
                    val cur = (a.optDouble("percentage", 0.0) / step).roundToInt().coerceIn(1, levels)
                    out += Ctl.Value("speed", "风速", cur, 1, levels, 1, { "$it 档" }) { v ->
                        call("set_percentage", mapOf("percentage" to (v * step).roundToInt().coerceAtMost(100)))
                    }
                }
            }

            "humidifier" -> {
                out += power(t)
                val modes = strings(a, "available_modes")
                if (modes.isNotEmpty()) {
                    out += Ctl.Choice("mode", "模式", modes.map { it to presetLabel(it) }, a.optString("mode")) { m ->
                        call("set_mode", mapOf("mode" to m))
                    }
                }
            }

            "climate" -> {
                out += power(t)
                val modes = strings(a, "hvac_modes").filter { it != "off" }
                if (modes.isNotEmpty()) {
                    out += Ctl.Choice("hvac", "模式", modes.map { it to (HVAC[it] ?: it) }, t.state.takeIf { it in modes }) { m ->
                        call("set_hvac_mode", mapOf("hvac_mode" to m))
                    }
                }
                if (a.has("temperature") && !a.isNull("temperature")) {
                    val lo = a.optDouble("min_temp", 16.0).roundToInt()
                    val hi = a.optDouble("max_temp", 30.0).roundToInt()
                    val step = a.optDouble("target_temp_step", 1.0).roundToInt().coerceAtLeast(1)
                    out += Ctl.Value("temp", "温度", a.optDouble("temperature").roundToInt().coerceIn(lo, hi), lo, hi, step, { "$it°" }) { v ->
                        call("set_temperature", mapOf("temperature" to v))
                    }
                }
                val fans = strings(a, "fan_modes")
                if (fans.isNotEmpty()) {
                    out += Ctl.Choice("fan", "风量", fans.map { it to (FAN[it] ?: it) }, a.optString("fan_mode")) { f ->
                        call("set_fan_mode", mapOf("fan_mode" to f))
                    }
                }
            }

            "cover" -> {
                out += Ctl.Action("open", "打开 / 升起", call("open_cover"))
                out += Ctl.Action("stop", "停止", call("stop_cover"))
                out += Ctl.Action("close", "关闭 / 降下", call("close_cover"))
            }

            "vacuum" -> {
                if (t.state == "cleaning") out += Ctl.Action("pause", "暂停", call("pause"))
                else out += Ctl.Action("start", if (t.state == "paused") "继续清扫" else "开始清扫", call("start"))
                out += Ctl.Action("dock", "回充电座", call("return_to_base"))
                val speeds = strings(a, "fan_speed_list")
                if (speeds.isNotEmpty()) {
                    out += Ctl.Choice("suction", "吸力", speeds.map { it to (SUCTION[it] ?: it) }, a.optString("fan_speed")) { s ->
                        call("set_fan_speed", mapOf("fan_speed" to s))
                    }
                }
            }
        }

        t.extras.filter { it.online }.forEach { e ->
            val d = e.id.substringBefore('.')
            out += Ctl.Power(e.id, e.label, e.id, e.on, HaCall(d, "turn_on", e.id), HaCall(d, "turn_off", e.id))
        }
        return out
    }

    /** 格子上按确认键：开着就关，关着就开 */
    fun toggle(t: HomeThing): HaCall = if (t.on) HaCall(t.domain, "turn_off", t.id) else HaCall(t.domain, "turn_on", t.id)

    private fun power(t: HomeThing) = Ctl.Power("power", "电源", t.id, t.on, HaCall(t.domain, "turn_on", t.id), HaCall(t.domain, "turn_off", t.id))

    // ---------------------------------------------------------------- 下发

    suspend fun send(config: AppConfig, c: HaCall) {
        val body = JSONObject().put("entity_id", c.entity)
        c.data.forEach { (k, v) -> body.put(k, v) }
        Http.post(
            "${config.haUrlOrDefault}/api/services/${c.domain}/${c.service}",
            body.toString(),
            timeoutMs = 8000,
            headers = mapOf("Authorization" to "Bearer ${config.haToken}"),
        )
    }

    // ---------------------------------------------------------------- 文案

    private fun brightnessPct(a: JSONObject): Int? =
        if (a.has("brightness") && !a.isNull("brightness")) (a.optDouble("brightness", 0.0) / 255.0 * 100).roundToInt().coerceIn(1, 100) else null

    private fun strings(a: JSONObject, key: String): List<String> =
        a.optJSONArray(key)?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() && it != "null" } }.orEmpty()

    val HVAC = mapOf("cool" to "制冷", "heat" to "制热", "dry" to "除湿", "fan_only" to "送风", "auto" to "自动", "heat_cool" to "自动")
    private val FAN = mapOf("auto" to "自动", "low" to "低", "medium" to "中", "high" to "高", "silent" to "静音", "turbo" to "强劲")
    private val SUCTION = mapOf("Silent" to "安静", "Basic" to "标准", "Strong" to "强力", "Full Speed" to "最大", "Turbo" to "强力", "Max" to "最大", "Quiet" to "安静", "Balanced" to "标准")

    fun presetLabel(p: String) = when (p.lowercase()) {
        "auto" -> "自动"
        "sleep", "silent" -> "睡眠"
        "favorite" -> "最爱"
        "strong", "turbo" -> "强力"
        "none" -> "手动"
        else -> p
    }
}
