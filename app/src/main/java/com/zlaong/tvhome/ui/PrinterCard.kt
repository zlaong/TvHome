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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zlaong.tvhome.data.PrinterRepo
import com.zlaong.tvhome.data.PrinterUi

/**
 * 打印机：只在「正在打印 / 已暂停 / 出故障」时出现。
 * 平时（空闲、已完成）整张卡不显示，把屏幕让给内容。
 */
@Composable
fun PrinterCard(p: PrinterUi, scale: Float = 1f, modifier: Modifier = Modifier) {
    val tone = stateColor(p)
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
            Text("打印机", fontSize = (10f * scale).sp, color = Palette.textDim, letterSpacing = (2f * scale).sp)
            Spacer(Modifier.weight(1f))
            Text(p.stateText, fontSize = (11f * scale).sp, color = tone)
        }
        Spacer(Modifier.height(d(8f)))

        if (p.jobName.isNotBlank()) {
            Text(
                p.jobName,
                fontSize = (12f * scale).sp,
                color = Palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(d(10f)))
        }

        ProgressBar(p.progress, color = tone)
        Spacer(Modifier.height(d(7f)))
        Row(Modifier.fillMaxWidth()) {
            Text("${p.progress}%", fontSize = (12f * scale).sp, color = Palette.textPrimary)
            Spacer(Modifier.weight(1f))
            Text(
                "剩余 ${PrinterRepo.humanRemaining(p.remainingSec)}",
                fontSize = (11f * scale).sp,
                color = Palette.textSecondary,
            )
        }

        Spacer(Modifier.height(d(10f)))
        Row(horizontalArrangement = Arrangement.spacedBy(d(12f))) {
            TempItem("喷嘴", p.nozzle.degreeOf(p.nozzleTarget), scale)
            TempItem("热床", p.bed.degreeOf(p.bedTarget), scale)
            if (p.chamber != null) TempItem("腔体", p.chamber.degree(), scale)
        }

        if (p.hms != null) {
            Spacer(Modifier.height(d(8f)))
            Text(
                p.hms,
                fontSize = (10f * scale).sp,
                color = Palette.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TempItem(label: String, value: String, scale: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = (10f * scale).sp, color = Palette.textDim)
        Spacer(Modifier.width((5f * scale).dp))
        Text(value, fontSize = (11f * scale).sp, color = Palette.textPrimary)
    }
}

private fun stateColor(p: PrinterUi): androidx.compose.ui.graphics.Color = when {
    !p.reachable || !p.online -> Palette.textDim
    p.state.uppercase() == "RUNNING" -> Palette.ok
    p.state.uppercase() == "PAUSE" || p.state.uppercase() == "PAUSED" -> Palette.warn
    p.state.uppercase() == "FAILED" -> Palette.error
    p.state.uppercase() == "FINISH" -> Palette.accent
    else -> Palette.textDim
}