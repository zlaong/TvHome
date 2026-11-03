package com.zlaong.tvhome.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 电视卡片：遥控器聚焦时放大 + 蓝色描边，给用户明确的"现在选中哪一块"。
 */
@Composable
fun TvCard(
    modifier: Modifier = Modifier,
    focusedScale: Float = 1.03f,
    onClick: (() -> Unit)? = null,
    padding: Int = 24,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "cardScale")
    val shape = RoundedCornerShape(20.dp)

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            // 没有点击行为的卡片不该参与焦点移动，否则遥控器会“走空”
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(if (focused) Palette.cardFocused else Palette.card, shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) Palette.accent else Color(0x14FFFFFF), shape)
            .padding(padding.dp),
        content = content,
    )
}

/** 细进度条 */
@Composable
fun ProgressBar(progress: Int, modifier: Modifier = Modifier, color: Color = Palette.accent) {
    Box(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0x33FFFFFF))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth((progress.coerceIn(0, 100)) / 100f)
                .background(color)
        )
    }
}