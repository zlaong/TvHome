package com.zlaong.tvhome.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

@Composable
internal fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    /** 外面想把焦点放到这一项时用，作用在浏览状态的方框上 */
    focusRequester: FocusRequester? = null,
    /** 退出编辑时调用，用来保存 */
    onCommit: () -> Unit = {},
) {
    /*
     * 两种状态：
     * - 浏览：遥控器焦点停在这里只是高亮，没有输入框，系统就不会弹输入法；
     * - 编辑：按 OK 才换成真正的输入框并弹输入法；按「完成」、返回或上下键退出编辑。
     * 以前一直是输入框，焦点一到，这台索尼的输入法就会自己弹出来，挡住半个屏幕。
     */
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val boxFocus = focusRequester ?: remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val shape = RoundedCornerShape(14.dp)
    val active = focused || editing

    // 按「完成」或返回退出编辑时，焦点要回到这一项；按上下键退出时交给焦点系统挪走
    var refocus by remember { mutableStateOf(false) }
    var fieldHadFocus by remember { mutableStateOf(false) }

    fun finish(move: FocusDirection? = null) {
        onCommit()
        keyboard?.hide()
        fieldHadFocus = false
        if (move != null) focusManager.moveFocus(move) else refocus = true
        editing = false
    }

    // 编辑中按返回：先收起键盘、回到浏览状态，不直接退出设置页
    BackHandler(enabled = editing) { finish() }

    // 键盘开着时第一下返回键被输入法自己吃掉，App 收不到；所以改为监听键盘收起，收起即退出编辑
    val view = LocalView.current
    DisposableEffect(editing) {
        if (!editing) return@DisposableEffect onDispose { }
        var seen = false
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val shown = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (shown) seen = true
            else if (seen && editing) finish()
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }

    LaunchedEffect(editing) {
        if (editing) {
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
        } else if (refocus) {
            refocus = false
            runCatching { boxFocus.requestFocus() }
        }
    }

    Column(modifier) {
        Text(label, fontSize = 13.sp, color = Palette.textSecondary, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        val frame = Modifier
            .fillMaxWidth()
            .background(if (active) Palette.cardFocused else Palette.card, shape)
            .border(if (active) 2.dp else 1.dp, if (active) Palette.accent else Color(0x14FFFFFF), shape)
            .padding(horizontal = 18.dp, vertical = 13.dp)
        if (editing) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Palette.textPrimary, fontSize = 20.sp),
                cursorBrush = SolidColor(Palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { finish() }),
                modifier = Modifier
                    .focusRequester(fieldFocus)
                    // 输入框会吞掉上下键：手动交给焦点系统，否则遥控器下不去、够不到别的项
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> { finish(FocusDirection.Down); true }
                            Key.DirectionUp -> { finish(FocusDirection.Up); true }
                            else -> false
                        }
                    }
                    // 焦点被别处拿走（比如上下键挪走了）：只退出编辑，不抢焦点回来
                    .onFocusChanged {
                        // 刚出现时还没拿到焦点也会回调一次 false，要先拿到过焦点才算「失去」
                        if (it.isFocused) fieldHadFocus = true
                        else if (fieldHadFocus && editing && !refocus) {
                            fieldHadFocus = false
                            onCommit()
                            keyboard?.hide()
                            editing = false
                        }
                    }
                    .then(frame),
            )
        } else {
            Box(
                Modifier
                    .focusRequester(boxFocus)
                    .onFocusChanged { focused = it.isFocused }
                    .clickable { editing = true }
                    .then(frame),
            ) {
                Text(
                    value.ifBlank { "未填写" },
                    fontSize = 20.sp,
                    color = if (value.isBlank()) Palette.textDim else Palette.textPrimary,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(if (focused && !editing) "按 OK 键修改 · $hint" else hint, fontSize = 12.sp, color = Palette.textDim)
    }
}


/** 设置页里所有能按的东西共用一套样式：选中时白底黑字，和首页焦点一致 */
@Composable
internal fun TvButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .background(
                when {
                    focused -> Color.White
                    primary -> Palette.accent.copy(alpha = 0.9f)
                    else -> Color.White.copy(alpha = 0.08f)
                },
                shape,
            )
            .padding(horizontal = 24.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (focused || primary) Tv.dark else Palette.textPrimary,
        )
    }
}

/** 一行选项：左边标题 + 说明，右边当前值或勾选状态 */
@Composable
internal fun TvRow(
    title: String,
    modifier: Modifier = Modifier,
    desc: String? = null,
    value: String? = null,
    checked: Boolean? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val fg = if (focused) Tv.dark else Palette.textPrimary
    val dim = if (focused) Tv.dark.copy(alpha = 0.6f) else Palette.textSecondary
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.05f), shape)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, color = fg, maxLines = 1)
            desc?.let { Text(it, fontSize = 13.sp, color = dim, maxLines = 2) }
        }
        value?.let { Text(it, fontSize = 15.sp, color = dim, maxLines = 1, modifier = Modifier.padding(start = 12.dp)) }
        checked?.let {
            Box(
                Modifier
                    .padding(start = 12.dp)
                    .size(22.dp)
                    .background(if (it) Palette.accent else Color.Transparent, RoundedCornerShape(6.dp))
                    .border(2.dp, if (it) Palette.accent else dim, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) { if (it) Text("✓", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}

/** 分组小标题 */
@Composable
internal fun TvGroup(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = Palette.textSecondary,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

/** 二维码 + 旁边说明，给「用手机设置」用 */
@Composable
internal fun QrCard(link: String?, title: String, lines: List<String>, modifier: Modifier = Modifier) {
    val qr = remember(link) { link?.let { qrBitmap(it) } }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (qr != null) {
            Image(
                qr.asImageBitmap(),
                null,
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .size(132.dp)
                    .background(Color.White, RoundedCornerShape(10.dp))
                    .padding(8.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.textPrimary)
            if (qr == null) Text("电视没有联网，连上网络后再试", fontSize = 14.sp, color = Palette.error)
            else lines.forEach { Text(it, fontSize = 14.sp, color = Palette.textSecondary) }
        }
    }
}

internal fun qrBitmap(text: String): Bitmap? = runCatching {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val px = IntArray(m.width * m.height) { if (m[it % m.width, it / m.width]) 0xFF000000.toInt() else -1 }
    Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}.getOrNull()
