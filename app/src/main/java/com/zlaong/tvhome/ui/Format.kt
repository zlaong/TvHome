package com.zlaong.tvhome.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
private val dateFmt = SimpleDateFormat("M 月 d 日 EEEE", Locale.CHINA)

fun formatTime(ts: Long): String = timeFmt.format(Date(ts))
fun formatDate(ts: Long): String = dateFmt.format(Date(ts))
fun Double?.degree(): String = this?.let { "${it.roundToInt()}°" } ?: "--°"
fun Double?.degreeOf(target: Double?): String = when {
    this == null -> "--°"
    target != null && target > 0.0 -> "${roundToInt()}/${target.roundToInt()}°"
    else -> "${roundToInt()}°"
}