package com.zlaong.tvhome.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import com.zlaong.tvhome.data.ConfigServer

/** 首页顶栏「手机投屏」：iPhone 相机扫码，Safari 打开投屏网页，自动配对 */
@Composable
fun CastQrOverlay(baseUrl: String?) {
    val ui = LocalUi.current
    val code by ConfigServer.pairingCode.collectAsState()
    val link = baseUrl?.let { "$it/cast#code=$code" }
    val qr = remember(link) { link?.let { qrBitmap(it) } }

    Scrim {
        Panel(1100) {
            Text("用手机投照片 / 视频", fontSize = ui.t(34), fontWeight = FontWeight.SemiBold, color = Tv.text)
            if (qr == null) {
                Text("电视没有联网，连上 Wi-Fi 后再试", fontSize = ui.t(24), color = Tv.danger)
                return@Panel
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ui.d(40)), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    qr.asImageBitmap(),
                    null,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier
                        .size(ui.d(360))
                        .background(Color.White, RoundedCornerShape(ui.d(16)))
                        .padding(ui.d(16)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(ui.d(14))) {
                    Step("1", "手机和电视连同一个 Wi-Fi")
                    Step("2", "用 iPhone 相机扫左边的二维码，在 Safari 里打开")
                    Step("3", "点「选择照片或视频」，电视会自动全屏播放")
                    Text(
                        "扫不了码？在手机浏览器输入\n$baseUrl/cast，配对码 $code",
                        fontSize = ui.t(20),
                        color = Tv.text3,
                        modifier = Modifier.padding(top = ui.d(12)),
                    )
                }
            }
            Text("返回键关闭", fontSize = ui.t(20), color = Tv.text3)
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    val ui = LocalUi.current
    Row(horizontalArrangement = Arrangement.spacedBy(ui.d(14))) {
        Text(n, fontSize = ui.t(26), fontWeight = FontWeight.Bold, color = Color(0xFF4F9DFF))
        Text(text, fontSize = ui.t(26), color = Tv.text)
    }
}
