package com.zlaong.tvhome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.zlaong.tvhome.data.AppConfig
import com.zlaong.tvhome.data.AppSettings
import com.zlaong.tvhome.data.HaEntity
import com.zlaong.tvhome.data.HomeRepo

@Composable
internal fun HomePanel(config: AppConfig) {
    var host by remember { mutableStateOf(config.printerHost) }
    var haUrl by remember { mutableStateOf(config.haUrl) }
    var token by remember { mutableStateOf("") }
    var bridge by remember { mutableStateOf(config.homeHost) }
    var picking by remember { mutableStateOf(false) }
    val (link, _) = rememberSetupLink("home")

    Text("首页顶栏的状态点、3D 打印机卡片和米家卡片从这里取数据", fontSize = 14.sp, color = Palette.textSecondary)

    TvGroup("MAC MINI · 3D 打印机")
    TvTextField(
        label = "Mac mini 地址",
        value = host,
        onValueChange = { host = it },
        hint = "例：192.168.1.10:3000，不用写 http://",
        onCommit = { if (host.isNotBlank()) AppSettings.save(host, AppSettings.state.value.cityName) },
    )

    TvGroup("米家 · HOME ASSISTANT")
    QrCard(
        link,
        if (config.useHa) "已连接 Home Assistant" else "建议用手机填令牌",
        listOf("令牌有一百多位，遥控器输入很费劲", "扫码后在网页里粘贴，还能选环境读数"),
    )
    Spacer(Modifier.height(14.dp))
    TvTextField(
        label = "Home Assistant 地址",
        value = haUrl,
        onValueChange = { haUrl = it },
        hint = "留空就用 ${config.haUrlOrDefault}",
        onCommit = { AppSettings.saveHa(haUrl, null, AppSettings.state.value.haEntities) },
    )
    Spacer(Modifier.height(14.dp))
    TvTextField(
        label = "长期访问令牌",
        value = token,
        onValueChange = { token = it },
        hint = if (config.useHa) "已设置（末 4 位 ${config.haToken.takeLast(4)}），输入新的会替换" else "在 Home Assistant 头像 →「安全」里创建",
        onCommit = {
            if (token.isNotBlank()) {
                AppSettings.saveHa(AppSettings.state.value.haUrl, token, AppSettings.state.value.haEntities)
                token = ""
            }
        },
    )
    if (config.useHa) {
        Spacer(Modifier.height(14.dp))
        TvRow("首页显示哪些设备", desc = "开着的设备才会出现在米家卡片底部", value = "已选 ${config.haEntities.size} 个 ›") {
            picking = true
        }
        Spacer(Modifier.height(8.dp))
        TvRow("清除令牌", desc = "米家卡片改回读 Mac mini 上的设备桥") { AppSettings.saveHa(haUrl, "", emptyList()) }
    } else {
        Spacer(Modifier.height(14.dp))
        TvTextField(
            label = "家里设备桥地址（旧）",
            value = bridge,
            onValueChange = { bridge = it },
            hint = "没配 Home Assistant 时用；留空就用 Mac mini 的 IP + :8080",
            onCommit = {
                val c = AppSettings.state.value
                AppSettings.save(c.printerHost, c.cityName, homeHost = bridge)
            },
        )
    }

    if (picking) EntityPicker(config) { picking = false }
}

/** 勾选首页米家卡片要显示的设备，勾一个存一个 */
@Composable
private fun EntityPicker(config: AppConfig, onClose: () -> Unit) {
    val result by produceState<Result<List<HaEntity>>?>(null) {
        value = runCatching {
            HomeRepo.listHaEntities(config.haUrlOrDefault, config.haToken).filter { it.envKind == null || it.domain != "sensor" }
        }
    }
    val first = remember { FocusRequester() }
    var selected by remember { mutableStateOf(config.haEntities) }

    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier
                .width(560.dp)
                .background(Color(0xFF161B26), RoundedCornerShape(20.dp))
                .padding(28.dp),
        ) {
            Text("首页显示哪些设备", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.textPrimary)
            Text("确认键勾选 / 取消 · 返回键关闭 · 最多 40 个", fontSize = 13.sp, color = Palette.textSecondary)
            Spacer(Modifier.height(14.dp))
            val r = result
            when {
                r == null -> Text("正在读取 Home Assistant…", fontSize = 16.sp, color = Palette.textSecondary)
                r.isFailure -> Text(HomeRepo.haError(r.exceptionOrNull(), config.haUrlOrDefault), fontSize = 16.sp, color = Palette.error)
                r.getOrThrow().isEmpty() -> Text("Home Assistant 里没有可显示的设备", fontSize = 16.sp, color = Palette.textSecondary)
                else -> {
                    val list = r.getOrThrow()
                    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                    Column(
                        Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        list.forEachIndexed { i, e ->
                            val on = e.id in selected
                            TvRow(
                                e.name,
                                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                                desc = e.id,
                                checked = on,
                            ) {
                                selected = if (on) selected - e.id else (selected + e.id).take(40)
                                val c = AppSettings.state.value
                                AppSettings.saveHa(c.haUrl, null, selected)
                            }
                        }
                    }
                }
            }
            if (result?.isSuccess != true) {
                Spacer(Modifier.height(16.dp))
                TvButton("关闭", modifier = Modifier.padding(top = 4.dp)) { onClose() }
            }
        }
    }
}
