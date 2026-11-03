package com.zlaong.tvhome

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent as AKey
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zlaong.tvhome.data.CastRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** 全屏播放手机投过来的照片 / 视频。按返回退出并删掉文件 */
class CastActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContent { CastScreen(onExit = ::exit) }
    }

    private fun exit() {
        CastRepo.stop()
        finish()
    }

    // 按 HOME 或切到别的应用：不在后台继续放
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && CastRepo.state.value is CastRepo.State.Showing) exit()
    }
}

@Composable
private fun CastScreen(onExit: () -> Unit) {
    val state by CastRepo.state.collectAsState()
    val focus = remember { FocusRequester() }
    var video by remember { mutableStateOf<VideoView?>(null) }
    var paused by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onExit)
    LaunchedEffect(state) { if (state is CastRepo.State.Idle) onExit() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val showing = state as? CastRepo.State.Showing
    LaunchedEffect(showing?.session) {
        error = null
        paused = false
        hint = when {
            showing == null -> null
            showing.kind == CastRepo.Kind.Video -> "确认键 暂停 / 继续 · 左右键 快退 / 快进 10 秒 · 返回键 退出"
            showing.total > 1 -> "左右键 翻页 · 返回键 退出"
            else -> "返回键 退出"
        }
        delay(4_000)
        hint = null
    }
    // 翻到下一张时清掉上一张的出错提示
    LaunchedEffect(showing?.current) { if (showing?.kind == CastRepo.Kind.Photo) error = null }

    fun setPaused(p: Boolean) {
        val v = video ?: return
        if (p) v.pause() else v.start()
        paused = p
    }

    LaunchedEffect(Unit) {
        CastRepo.commands.collect { setPaused(it == CastRepo.Command.Pause) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (showing?.kind == CastRepo.Kind.Photo) {
                    return@onPreviewKeyEvent when (e.key.nativeKeyCode) {
                        AKey.KEYCODE_DPAD_LEFT, AKey.KEYCODE_DPAD_UP, AKey.KEYCODE_MEDIA_PREVIOUS -> { CastRepo.step(-1); true }
                        AKey.KEYCODE_DPAD_RIGHT, AKey.KEYCODE_DPAD_DOWN, AKey.KEYCODE_MEDIA_NEXT,
                        AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER -> { CastRepo.step(1); true }
                        else -> false
                    }
                }
                val v = video ?: return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
                    AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER, AKey.KEYCODE_MEDIA_PLAY_PAUSE -> { setPaused(!paused); true }
                    AKey.KEYCODE_MEDIA_PAUSE -> { setPaused(true); true }
                    AKey.KEYCODE_MEDIA_PLAY -> { setPaused(false); true }
                    AKey.KEYCODE_DPAD_LEFT, AKey.KEYCODE_MEDIA_REWIND -> { v.seekTo((v.currentPosition - 10_000).coerceAtLeast(0)); true }
                    AKey.KEYCODE_DPAD_RIGHT, AKey.KEYCODE_MEDIA_FAST_FORWARD -> {
                        v.seekTo((v.currentPosition + 10_000).coerceAtMost(v.duration.coerceAtLeast(0))); true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            is CastRepo.State.Receiving -> Receiving(s)
            is CastRepo.State.Showing -> if (s.kind == CastRepo.Kind.Photo) {
                LaunchedEffect(s.session) { video = null }
                Photo(s.current) { error = it }
                if (s.total > 1) {
                    val more = if (s.files.size < s.total) " · 还在接收 ${s.files.size}/${s.total}" else ""
                    Text(
                        "${s.index + 1} / ${s.total}$more",
                        fontSize = 20.sp,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(32.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            val v = VideoView(ctx)
                            addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                        }
                    },
                    update = { box ->
                        val v = box.getChildAt(0) as VideoView
                        if (v.tag != s.session) {
                            v.tag = s.session
                            v.setOnPreparedListener { it.isLooping = false; v.start() }
                            v.setOnCompletionListener { paused = true }
                            v.setOnErrorListener { _, _, _ -> error = "这个视频电视放不了，换一个试试"; true }
                            v.setVideoPath(s.current.absolutePath)
                        }
                        video = v
                    },
                    onRelease = { box -> (box.getChildAt(0) as VideoView).stopPlayback() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            CastRepo.State.Idle -> Unit
        }

        val msg = error ?: if (paused && state is CastRepo.State.Showing) "已暂停" else null
        (msg ?: hint)?.let {
            Text(
                it,
                fontSize = 22.sp,
                color = Color.White,
                modifier = Modifier
                    .align(if (msg != null) Alignment.Center else Alignment.BottomCenter)
                    .padding(48.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun Receiving(s: CastRepo.State.Receiving) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            if (s.kind == CastRepo.Kind.Video) "正在接收手机发来的视频…" else "正在接收手机发来的照片…",
            fontSize = 30.sp,
            color = Color.White,
        )
        Box(Modifier.width(480.dp).height(8.dp).background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp))) {
            Box(
                Modifier
                    .fillMaxWidth(s.percent / 100f)
                    .height(8.dp)
                    .background(Color(0xFF4F9DFF), RoundedCornerShape(4.dp))
            )
        }
        Text("${s.percent}%", fontSize = 22.sp, color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
private fun Photo(file: File, onError: (String) -> Unit) {
    val bitmap by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { decode(file, 3840) }.getOrNull() }
        if (value == null) onError("这张照片电视打不开，换一张试试")
    }
    bitmap?.let {
        Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

/** 按长边缩到 maxSide 以内，避免 4800 万像素的照片撑爆内存；同时按拍摄方向转正 */
private fun decode(file: File, maxSide: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ ->
            val side = maxOf(info.size.width, info.size.height)
            if (side > maxSide) d.setTargetSize(info.size.width * maxSide / side, info.size.height * maxSide / side)
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    @Suppress("DEPRECATION")
    val deg = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (deg == 0f) return bmp
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
}
