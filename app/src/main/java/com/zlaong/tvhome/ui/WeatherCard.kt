package com.zlaong.tvhome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zlaong.tvhome.data.WeatherUi

/** 天气：紧凑常驻卡。温度是主角，其他信息一行带过 */
@Composable
fun WeatherCard(w: WeatherUi, scale: Float = 1f, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape((14f * scale).dp)
    val d: (Float) -> androidx.compose.ui.unit.Dp = { (it * scale).dp }

    Column(
        modifier
            .fillMaxWidth()
            .background(Palette.surface, shape)
            .border(d(1f), Palette.stroke, shape)
            .padding(horizontal = d(16f), vertical = d(14f))
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("天气", fontSize = (10f * scale).sp, color = Palette.textDim, letterSpacing = (2f * scale).sp)
            Spacer(Modifier.weight(1f))
            if (w.updatedAt > 0) {
                Text("更新 ${formatTime(w.updatedAt)}", fontSize = (10f * scale).sp, color = Palette.textDim)
            }
        }
        Spacer(Modifier.height(d(10f)))

        when {
            w.temp == null && w.error != null -> {
                Text(w.city, fontSize = (11f * scale).sp, color = Palette.textSecondary)
                Spacer(Modifier.height(d(4f)))
                Text(w.error, fontSize = (15f * scale).sp, color = Palette.textSecondary)
            }

            w.loading -> Text("正在获取天气…", fontSize = (15f * scale).sp, color = Palette.textSecondary)

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(w.emoji, fontSize = (28f * scale).sp)
                    Spacer(Modifier.width(d(10f)))
                    Text(
                        w.temp.degree(),
                        fontSize = (34f * scale).sp,
                        fontWeight = FontWeight.Bold,
                        color = Palette.textPrimary,
                    )
                    Spacer(Modifier.width(d(10f)))
                    Column {
                        Text(w.city, fontSize = (11f * scale).sp, color = Palette.textDim)
                        Text(w.text, fontSize = (15f * scale).sp, color = Palette.textPrimary)
                    }
                }
                Spacer(Modifier.height(d(10f)))
                Row(horizontalArrangement = Arrangement.spacedBy(d(14f))) {
                    Text("最高 ${w.high.degree()}", fontSize = (11f * scale).sp, color = Palette.textSecondary)
                    Text("最低 ${w.low.degree()}", fontSize = (11f * scale).sp, color = Palette.textSecondary)
                    if (w.humidity != null) {
                        Text("湿度 ${w.humidity}%", fontSize = (11f * scale).sp, color = Palette.textSecondary)
                    }
                }
            }
        }
    }
}