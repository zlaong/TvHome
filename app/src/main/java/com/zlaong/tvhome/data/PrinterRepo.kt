package com.zlaong.tvhome.data

import org.json.JSONObject

/**
 * 打印机数据：直接读 Mac mini 上 PrintPilot 后端的 /api/status。
 * 只读，不向打印机下发任何命令。
 */
data class PrinterUi(
    val reachable: Boolean = false,
    val online: Boolean = false,
    val name: String = "打印机",
    val state: String = "IDLE",
    val stateText: String = "空闲",
    val jobName: String = "",
    val progress: Int = 0,
    val remainingSec: Int = 0,
    val nozzle: Double? = null,
    val nozzleTarget: Double? = null,
    val bed: Double? = null,
    val bedTarget: Double? = null,
    val chamber: Double? = null,
    val hms: String? = null,
    val layerNum: Int? = null,
    val totalLayers: Int? = null,
    val lastUpdate: Long = 0L,
    val error: String? = null,
)

object PrinterRepo {

    suspend fun fetch(config: AppConfig): PrinterUi {
        val host = config.printerHost
        return try {
            val body = Http.get("http://$host/api/status", timeoutMs = 4000)
            parse(JSONObject(body))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            PrinterUi(reachable = false, name = host, error = "连不上 Mac mini ($host)")
        }
    }

    private fun parse(json: JSONObject): PrinterUi {
        val state = json.optString("gcodeState", "IDLE").ifBlank { "IDLE" }
        val hms = json.optJSONArray("hmsErrors")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("text")?.ifBlank { null } } }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("；")

        return PrinterUi(
            reachable = true,
            online = json.optBoolean("online", true),
            name = json.optString("printerName", "").ifBlank { "打印机" },
            state = state,
            stateText = stateText(state),
            jobName = json.optString("printJobName", ""),
            progress = json.optInt("printProgress", 0).coerceIn(0, 100),
            remainingSec = json.optInt("printTimeRemaining", 0).coerceAtLeast(0),
            nozzle = json.optDouble("nozzleTemp", Double.NaN).takeIf { !it.isNaN() },
            nozzleTarget = json.optDouble("nozzleTargetTemp", Double.NaN).takeIf { !it.isNaN() },
            bed = json.optDouble("bedTemp", Double.NaN).takeIf { !it.isNaN() },
            bedTarget = json.optDouble("bedTargetTemp", Double.NaN).takeIf { !it.isNaN() },
            chamber = json.optDouble("chamberTemp", Double.NaN).takeIf { !it.isNaN() },
            hms = hms,
            layerNum = json.optInt("layerNum", -1).takeIf { it >= 0 },
            totalLayers = json.optInt("totalLayers", -1).takeIf { it > 0 },
            lastUpdate = System.currentTimeMillis(),
        )
    }

    fun stateText(state: String): String = when (state.uppercase()) {
        "RUNNING" -> "打印中"
        "PAUSE", "PAUSED" -> "已暂停"
        "PREPARE" -> "准备中"
        "SLICING" -> "切片中"
        "FINISH" -> "已完成"
        "FAILED" -> "失败"
        "IDLE" -> "空闲"
        else -> state
    }

    /** 打印机只在「正在打印 / 已暂停 / 出故障」时占用首页位置 */
    fun isPrinting(p: PrinterUi): Boolean = p.reachable && p.online &&
        p.state.uppercase() in setOf("RUNNING", "PAUSE", "PAUSED", "PREPARE", "SLICING", "FAILED")

    /** 剩余时间口语化：2 小时 13 分 */
    fun humanRemaining(sec: Int): String {
        if (sec <= 0) return "--"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return when {
            h > 0 && m > 0 -> "$h 小时 $m 分"
            h > 0 -> "$h 小时"
            else -> "$m 分"
        }
    }
}

/** 首页用：这张卡要不要出现 */
fun PrinterUi.isPrinting(): Boolean = PrinterRepo.isPrinting(this)