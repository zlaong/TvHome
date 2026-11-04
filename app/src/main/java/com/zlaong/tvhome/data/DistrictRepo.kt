package com.zlaong.tvhome.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * 城市下面有哪些区县，以及每个区县的中心坐标，给「天气 → 选区」用。
 *
 * 来源：阿里云 DataV 的公开行政区划数据（geo.datav.aliyun.com），免费、不用 Key、国内能访问。
 * 中文区名直接查天气的地名接口查不准（「朝阳」会查到重庆），所以改成从这份列表里选。
 * 这个接口阿里云没有承诺长期提供：失败时只是选不了区，已经选好的区存在电视上，不受影响。
 */
object DistrictRepo {
    data class District(val name: String, val lat: Double, val lon: Double)

    private const val BASE = "https://geo.datav.aliyun.com/areas_v3/bound"

    /** 城市名 → 区县列表；只缓存在内存里，设置页每次打开最多查一次 */
    private val cache = HashMap<String, List<District>>()

    /** 「北京」「北京市」「杭州市」都算同一个城市 */
    fun sameCity(a: String, b: String) = a.trim().removeSuffix("市") == b.trim().removeSuffix("市")

    /** 查不到城市或网络失败时返回 null */
    suspend fun districts(city: String): List<District>? {
        val key = city.trim().removeSuffix("市")
        if (key.isBlank()) return null
        cache[key]?.let { return it }
        val result = runCatching { load(key) }.getOrNull() ?: return null
        cache[key] = result
        return result
    }

    private suspend fun load(city: String): List<District>? {
        // 全国 → 省；北京、上海这类直辖市在这一层就能直接找到
        val provinces = children("100000")
        provinces.firstOrNull { it.name.removeSuffix("市") == city }?.let { return districtsOf(it.adcode) }
        // 普通城市：不知道在哪个省，各省同时查下一级。只在第一次打开选区时查
        val hit = coroutineScope {
            provinces.filter { it.level == "province" }
                .map { p -> async { runCatching { children(p.adcode) }.getOrNull().orEmpty() } }
                .awaitAll()
                .flatten()
                .firstOrNull { it.name.removeSuffix("市") == city }
        } ?: return null
        return districtsOf(hit.adcode)
    }

    private suspend fun districtsOf(adcode: String) =
        children(adcode).filter { it.level == "district" }.map { District(it.name, it.lat, it.lon) }

    private data class Area(val adcode: String, val name: String, val level: String, val lat: Double, val lon: Double)

    /** 只取名字和中心点，不要边界线：`_full.json` 带边界，北京一份约 100 KB，可以接受 */
    private suspend fun children(adcode: String): List<Area> {
        val root = JSONObject(Http.get("$BASE/${adcode}_full.json", timeoutMs = 10_000))
        val features = root.getJSONArray("features")
        return (0 until features.length()).mapNotNull { i ->
            val p = features.getJSONObject(i).optJSONObject("properties") ?: return@mapNotNull null
            val center = p.optJSONArray("center") ?: p.optJSONArray("centroid") ?: return@mapNotNull null
            Area(
                adcode = p.opt("adcode")?.toString() ?: return@mapNotNull null,
                name = p.optString("name"),
                level = p.optString("level"),
                lat = center.optDouble(1),
                lon = center.optDouble(0),
            )
        }
    }
}
