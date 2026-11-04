package com.zlaong.tvhome.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/** 设计稿里的线性图标（24x24，描边 1.6），原样搬过来 */
enum class TvIcon(val d: String, val filled: Boolean = false) {
    Gear(
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z" +
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0"
    ),
    Wifi("M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 19.5h.01"),
    Clock("M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0M12 7v5l3 2"),
    Cast("M3 16a5 5 0 0 1 5 5M3 12a9 9 0 0 1 9 9M3 8V6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-5"),
    Mac(
        "M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z" +
            "M4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2zM6 6h.01M6 18h.01"
    ),
    Printer(
        "M10 11v1M12 8h8M15 20v-3a1 1 0 0 0-1-1H9a1 1 0 0 0-1 1v3M4 20h16M4 22V4a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v18M4 8h4" +
            "M8.635 10.093A2 2 0 0 1 8 8.631V7a1 1 0 0 1 1-1h2a1 1 0 0 1 1 1v2a1 1 0 0 1-.293.707l-1 1a1 1 0 0 1-1.414 0z"
    ),
    House("M3 11l9-7 9 7M5 10v10h14V10M10 20v-5h4v5"),
    Pin("M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21zM9.5 9.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0"),
    Bulb("M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z"),
    Plus("M12 5v14M5 12h14"),

    // 米家卡片：按设备类型区分，看图标就知道是什么
    Fan("M12 12m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0M12 10.5C12 6 13.5 3 16 3s3 3-1 6M13.5 12c4.5 0 7.5 1.5 7.5 4s-3 3-6-1M12 13.5c0 4.5-1.5 7.5-4 7.5s-3-3 1-6M10.5 12C6 12 3 10.5 3 8s3-3 6 1"),
    Snow("M12 2v20M4.9 6.5l14.2 11M19.1 6.5L4.9 17.5M9 4l3 2 3-2M9 20l3-2 3 2"),
    Robot("M12 12m-9 0a9 9 0 1 0 18 0a9 9 0 1 0-18 0M12 12m-3 0a3 3 0 1 0 6 0a3 3 0 1 0-6 0M7 6.5h10"),
    Hanger("M12 7a2 2 0 1 1 2-2c0 1-2 1.5-2 3v1M12 9l-9 7.5c-.6.5-.3 1.5.5 1.5h17c.8 0 1.1-1 .5-1.5z"),
    Plug("M9 2v6M15 2v6M6 8h12v4a6 6 0 0 1-12 0zM12 18v4"),
    Alert("M12 3l10 18H2zM12 10v5M12 18h.01"),
    Check("M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0M8 12.5l2.5 2.5L16 9.5"),
    Picture("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM21 15l-5-5L5 21M8.5 8.5m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0"),
    Sun("M12 12m-4 0a4 4 0 1 0 8 0a4 4 0 1 0-8 0M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"),
    Phone("M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zM11 18h2"),

    // 米家卡片的室内环境，用 Home Assistant 同款的 Material Design Icons（Apache 2.0），实心填充
    Droplet( // mdi:water-percent
        "M12,3.25C12,3.25 6,10 6,14C6,17.32 8.69,20 12,20A6,6 0 0,0 18,14C18,10 12,3.25 12,3.25M14.47,9.97L15.53,11.03L9.53,17.03L8.47,15.97M9.75,10A1.25,1.25 0 0,1 11,11.25A1.25,1.25 0 0,1 9.75,12.5A1.25,1.25 0 0,1 8.5,11.25A1.25,1.25 0 0,1 9.75,10M14.25,14.5A1.25,1.25 0 0,1 15.5,15.75A1.25,1.25 0 0,1 14.25,17A1.25,1.25 0 0,1 13,15.75A1.25,1.25 0 0,1 14.25,14.5Z",
        filled = true,
    ),
    Particles( // mdi:blur
        "M14,8.5A1.5,1.5 0 0,0 12.5,10A1.5,1.5 0 0,0 14,11.5A1.5,1.5 0 0,0 15.5,10A1.5,1.5 0 0,0 14,8.5M14,12.5A1.5,1.5 0 0,0 12.5,14A1.5,1.5 0 0,0 14,15.5A1.5,1.5 0 0,0 15.5,14A1.5,1.5 0 0,0 14,12.5M10,17A1,1 0 0,0 9,18A1,1 0 0,0 10,19A1,1 0 0,0 11,18A1,1 0 0,0 10,17M10,8.5A1.5,1.5 0 0,0 8.5,10A1.5,1.5 0 0,0 10,11.5A1.5,1.5 0 0,0 11.5,10A1.5,1.5 0 0,0 10,8.5M14,20.5A0.5,0.5 0 0,0 13.5,21A0.5,0.5 0 0,0 14,21.5A0.5,0.5 0 0,0 14.5,21A0.5,0.5 0 0,0 14,20.5M14,17A1,1 0 0,0 13,18A1,1 0 0,0 14,19A1,1 0 0,0 15,18A1,1 0 0,0 14,17M21,13.5A0.5,0.5 0 0,0 20.5,14A0.5,0.5 0 0,0 21,14.5A0.5,0.5 0 0,0 21.5,14A0.5,0.5 0 0,0 21,13.5M18,5A1,1 0 0,0 17,6A1,1 0 0,0 18,7A1,1 0 0,0 19,6A1,1 0 0,0 18,5M18,9A1,1 0 0,0 17,10A1,1 0 0,0 18,11A1,1 0 0,0 19,10A1,1 0 0,0 18,9M18,17A1,1 0 0,0 17,18A1,1 0 0,0 18,19A1,1 0 0,0 19,18A1,1 0 0,0 18,17M18,13A1,1 0 0,0 17,14A1,1 0 0,0 18,15A1,1 0 0,0 19,14A1,1 0 0,0 18,13M10,12.5A1.5,1.5 0 0,0 8.5,14A1.5,1.5 0 0,0 10,15.5A1.5,1.5 0 0,0 11.5,14A1.5,1.5 0 0,0 10,12.5M10,7A1,1 0 0,0 11,6A1,1 0 0,0 10,5A1,1 0 0,0 9,6A1,1 0 0,0 10,7M10,3.5A0.5,0.5 0 0,0 10.5,3A0.5,0.5 0 0,0 10,2.5A0.5,0.5 0 0,0 9.5,3A0.5,0.5 0 0,0 10,3.5M10,20.5A0.5,0.5 0 0,0 9.5,21A0.5,0.5 0 0,0 10,21.5A0.5,0.5 0 0,0 10.5,21A0.5,0.5 0 0,0 10,20.5M3,13.5A0.5,0.5 0 0,0 2.5,14A0.5,0.5 0 0,0 3,14.5A0.5,0.5 0 0,0 3.5,14A0.5,0.5 0 0,0 3,13.5M14,3.5A0.5,0.5 0 0,0 14.5,3A0.5,0.5 0 0,0 14,2.5A0.5,0.5 0 0,0 13.5,3A0.5,0.5 0 0,0 14,3.5M14,7A1,1 0 0,0 15,6A1,1 0 0,0 14,5A1,1 0 0,0 13,6A1,1 0 0,0 14,7M21,10.5A0.5,0.5 0 0,0 21.5,10A0.5,0.5 0 0,0 21,9.5A0.5,0.5 0 0,0 20.5,10A0.5,0.5 0 0,0 21,10.5M6,5A1,1 0 0,0 5,6A1,1 0 0,0 6,7A1,1 0 0,0 7,6A1,1 0 0,0 6,5M3,9.5A0.5,0.5 0 0,0 2.5,10A0.5,0.5 0 0,0 3,10.5A0.5,0.5 0 0,0 3.5,10A0.5,0.5 0 0,0 3,9.5M6,9A1,1 0 0,0 5,10A1,1 0 0,0 6,11A1,1 0 0,0 7,10A1,1 0 0,0 6,9M6,17A1,1 0 0,0 5,18A1,1 0 0,0 6,19A1,1 0 0,0 7,18A1,1 0 0,0 6,17M6,13A1,1 0 0,0 5,14A1,1 0 0,0 6,15A1,1 0 0,0 7,14A1,1 0 0,0 6,13Z",
        filled = true,
    ),
    Molecule( // mdi:molecule-co2
        "M5,7A2,2 0 0,0 3,9V15A2,2 0 0,0 5,17H8V15H5V9H8V7H5M11,7A2,2 0 0,0 9,9V15A2,2 0 0,0 11,17H13A2,2 0 0,0 15,15V9A2,2 0 0,0 13,7H11M11,9H13V15H11V9M16,10.5V12H19V13.5H17.5A1.5,1.5 0 0,0 16,15V18H20.5V16.5H17.5V15H19A1.5,1.5 0 0,0 20.5,13.5V12A1.5,1.5 0 0,0 19,10.5H16Z",
        filled = true,
    ),
}

private val pathCache = HashMap<TvIcon, Path>()

private fun TvIcon.path(): Path = pathCache.getOrPut(this) { PathParser().parsePathString(d).toPath() }

@Composable
fun Icon(icon: TvIcon, size: Dp, color: Color, modifier: Modifier = Modifier) {
    val path = icon.path()
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        scale(k, pivot = Offset.Zero) {
            if (icon.filled) drawPath(path, color)
            else drawPath(path, color, style = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

// ------------------------------------------------------------------ 天气插画

/** Moon / MoonPart 是晚上的晴、少云；阴雨雪不分昼夜，和 iOS 天气一样 */
enum class Sky { Sun, Part, Moon, MoonPart, Cloud, Rain, Snow }

/** WMO 天气代码 → 画哪种图；isDay 来自 Open-Meteo 的 is_day（按日出日落算） */
fun skyOf(code: Int, isDay: Boolean = true): Sky = when (code) {
    0, 1 -> if (isDay) Sky.Sun else Sky.Moon
    2 -> if (isDay) Sky.Part else Sky.MoonPart
    3, 45, 48 -> Sky.Cloud
    in 51..67, in 80..82, in 95..99 -> Sky.Rain
    in 71..77, 85, 86 -> Sky.Snow
    else -> Sky.Cloud
}

private val bigCloud by lazy { PathParser().parsePathString("M44 140a28 28 0 0 1-2-56 36 36 0 0 1 69-6 26 26 0 0 1 9 62z").toPath() }
private val bigCloudShade by lazy { PathParser().parsePathString("M44 140a28 28 0 0 1-2-56 36 36 0 0 1 9-1.5A34 34 0 0 0 58 140z").toPath() }
private val bigRays by lazy { PathParser().parsePathString("M96 14v10M96 104v10M146 64h-10M56 64H46M131 29l-7 7M68 92l-7 7M131 99l-7-7M68 36l-7-7").toPath() }
private val miniSunRays by lazy { PathParser().parsePathString("M16 3v3M16 26v3M3 16h3M26 16h3M6.8 6.8l2.1 2.1M23.1 23.1l2.1 2.1M6.8 25.2l2.1-2.1M23.1 8.9l2.1-2.1").toPath() }
private val miniPartCloud by lazy { PathParser().parsePathString("M9 27a5.5 5.5 0 0 1-.4-11 7 7 0 0 1 13.4-1.2A5 5 0 0 1 23.5 27z").toPath() }
private val miniCloud by lazy { PathParser().parsePathString("M9 25a6 6 0 0 1-.4-12 8 8 0 0 1 15.3-1.3A6 6 0 0 1 24 25z").toPath() }

/** 月牙：整圆减去往右上错开的圆，开口朝右上，和 iOS 的 moon.fill 一个方向 */
private fun crescent(c: Offset, r: Float): Path = Path().apply {
    val full = Path().apply { addOval(Rect(c, r)) }
    val bite = Path().apply { addOval(Rect(Offset(c.x + r * 0.5f, c.y - r * 0.38f), r * 0.82f)) }
    op(full, bite, PathOperation.Difference)
}

/** 四角星，晴夜旁边点缀 */
private fun sparkle(c: Offset, r: Float): Path = Path().apply {
    val k = r * 0.22f
    moveTo(c.x, c.y - r)
    quadraticTo(c.x + k, c.y - k, c.x + r, c.y)
    quadraticTo(c.x + k, c.y + k, c.x, c.y + r)
    quadraticTo(c.x - k, c.y + k, c.x - r, c.y)
    quadraticTo(c.x - k, c.y - k, c.x, c.y - r)
    close()
}

private val bigMoon by lazy { crescent(Offset(85f, 85f), 36f) }
private val bigMoonPart by lazy { crescent(Offset(96f, 64f), 32f) }
private val bigStars by lazy { Path().apply { addPath(sparkle(Offset(132f, 46f), 9f)); addPath(sparkle(Offset(118f, 22f), 5.5f)) } }
private val miniMoon by lazy { crescent(Offset(15f, 17f), 9f) }
private val miniMoonPart by lazy { crescent(Offset(19f, 12f), 7f) }
private val miniStar by lazy { sparkle(Offset(25f, 8f), 3f) }

private val sunColor = Color(0xFFFFD25A)
/** 月亮用偏暖的浅黄，比纯白在白云前面分得开 */
private val moonColor = Color(0xFFF6E7B0)
private val cloudColor = Color(0xFFF2F5FA)
private val cloudShade = Color(0xFFD9E1EC)
private val greyCloud = Color(0xFFCFD8E6)
private val rainColor = Color(0xFF7CC4FF)

/** 天气卡右侧的大插画（设计稿 viewBox 10 10 150 150） */
@Composable
fun WeatherArt(sky: Sky, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        scale(this.size.minDimension / 150f, pivot = Offset.Zero) {
            translate(-10f, -10f) {
                if (sky == Sky.Sun || sky == Sky.Part) {
                    val c = if (sky == Sky.Sun) Offset(85f, 85f) else Offset(96f, 64f)
                    translate(c.x - 96f, c.y - 64f) {
                        drawCircle(sunColor, 34f, Offset(96f, 64f))
                        drawPath(bigRays, sunColor, style = Stroke(6f, cap = StrokeCap.Round))
                    }
                }
                if (sky == Sky.Moon) {
                    drawPath(bigMoon, moonColor)
                    drawPath(bigStars, moonColor)
                }
                if (sky == Sky.MoonPart) drawPath(bigMoonPart, moonColor)
                if (sky != Sky.Sun && sky != Sky.Moon) {
                    val light = sky == Sky.Part || sky == Sky.MoonPart
                    val dy = if (light) 0f else -18f
                    translate(4f, dy) {
                        drawPath(bigCloud, if (light) cloudColor else greyCloud)
                        drawPath(bigCloudShade, cloudShade)
                    }
                    if (sky == Sky.Rain) {
                        listOf(56f, 80f, 104f).forEach { x ->
                            drawLine(rainColor, Offset(x, 134f), Offset(x - 8f, 152f), 6f, StrokeCap.Round)
                        }
                    }
                    if (sky == Sky.Snow) {
                        listOf(56f, 80f, 104f).forEach { x -> drawCircle(Color.White, 5f, Offset(x, 146f)) }
                    }
                }
            }
        }
    }
}

/** 逐小时预报用的小图（32x32） */
@Composable
fun WeatherMini(sky: Sky, size: Dp) {
    Canvas(Modifier.size(size)) { drawWeatherMini(sky, this.size.minDimension) }
}

/** 在当前原点画一个边长 px 的小天气图；天气全屏页的 24 小时曲线直接在画布上画 */
fun DrawScope.drawWeatherMini(sky: Sky, px: Float) {
    scale(px / 32f, pivot = Offset.Zero) {
            when (sky) {
                Sky.Sun -> {
                    drawCircle(sunColor, 7f, Offset(16f, 16f))
                    drawPath(miniSunRays, sunColor, style = Stroke(2.4f, cap = StrokeCap.Round))
                }
                Sky.Part -> {
                    drawCircle(sunColor, 6f, Offset(19f, 12f))
                    drawPath(miniPartCloud, cloudColor)
                }
                Sky.Moon -> {
                    drawPath(miniMoon, moonColor)
                    drawPath(miniStar, moonColor)
                }
                Sky.MoonPart -> {
                    drawPath(miniMoonPart, moonColor)
                    drawPath(miniPartCloud, cloudColor)
                }
                Sky.Cloud -> drawPath(miniCloud, greyCloud)
                Sky.Rain -> {
                    translate(0f, -3f) { drawPath(miniCloud, greyCloud) }
                    listOf(12f, 17f, 22f).forEach { x ->
                        drawLine(rainColor, Offset(x, 25f), Offset(x - 2f, 30f), 2f, StrokeCap.Round)
                    }
                }
                Sky.Snow -> {
                    translate(0f, -3f) { drawPath(miniCloud, greyCloud) }
                    listOf(12f, 17f, 22f).forEach { x -> drawCircle(Color.White, 1.5f, Offset(x, 28f)) }
                }
            }
    }
}
