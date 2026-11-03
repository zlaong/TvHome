package com.zlaong.tvhome.data

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build

/**
 * 已连接的 HDMI 设备（PS5、机顶盒等）。
 * 只列出真正插着且通电的口，空口不显示。
 */
data class HdmiSource(
    val inputId: String,
    /** 设备名，优先用 CEC 报上来的名字（如「PS5」），拿不到就用口名 */
    val name: String,
    /** 口名，如「HDMI 3」 */
    val port: String,
) {
    val isPlayStation: Boolean
        get() = name.contains("PS", ignoreCase = true) || name.contains("PlayStation", ignoreCase = true)
}

object InputRepo {

    fun load(context: Context): List<HdmiSource> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return emptyList()
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        val inputs = runCatching { tim.tvInputList }.getOrNull() ?: return emptyList()

        val hdmi = inputs.filter { it.type == TvInputInfo.TYPE_HDMI && it.isPassthroughInput }
        val ports = hdmi.filter { it.parentId == null }
        // CEC 设备是挂在某个 HDMI 口下面的子输入，带设备名
        val children = hdmi.filter { it.parentId != null }

        fun label(info: TvInputInfo): String {
            val custom = runCatching { info.loadCustomLabel(context)?.toString() }.getOrNull()
            val base = runCatching { info.loadLabel(context)?.toString() }.getOrNull()
            return custom?.takeIf { it.isNotBlank() } ?: base?.takeIf { it.isNotBlank() } ?: "HDMI"
        }

        fun connected(info: TvInputInfo): Boolean =
            runCatching { tim.getInputState(info.id) }.getOrDefault(TvInputManager.INPUT_STATE_DISCONNECTED) !=
                TvInputManager.INPUT_STATE_DISCONNECTED

        val out = ArrayList<HdmiSource>()
        ports.forEach { port ->
            val portName = runCatching { port.loadLabel(context)?.toString() }.getOrNull()?.ifBlank { null } ?: "HDMI"
            val kids = children.filter { it.parentId == port.id && connected(it) }
            if (kids.isNotEmpty()) {
                kids.forEach { out.add(HdmiSource(it.id, label(it), portName)) }
            } else if (connected(port)) {
                out.add(HdmiSource(port.id, label(port), portName))
            }
        }
        return out
    }

    /** 切到这个 HDMI 口，交给系统自带的电视应用去播 */
    fun launch(context: Context, source: HdmiSource): Boolean {
        val uri = TvContract.buildChannelUriForPassthroughInput(source.inputId)
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
