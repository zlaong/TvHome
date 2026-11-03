package com.zlaong.tvhome

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zlaong.tvhome.data.AppSettings
import com.zlaong.tvhome.data.CastRepo
import com.zlaong.tvhome.data.Feeds
import com.zlaong.tvhome.ui.PRINTER_ENABLED
import com.zlaong.tvhome.data.WallpaperRepo
import com.zlaong.tvhome.data.ConfigServer
import com.zlaong.tvhome.ui.HomeScreen
import com.zlaong.tvhome.ui.Palette
import com.zlaong.tvhome.ui.SettingsScreen
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MainActivity : ComponentActivity() {

    companion object {
        /** 已经在桌面时再按 HOME：收起抽屉、关掉设置页，回到首页初始状态 */
        private val _homeResets = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val homeResets = _homeResets.asSharedFlow()

        // 桌面「看得见」= Activity 在前台 且 不在设置页；只有看得见时首页卡片才请求网络
        private var resumed = false
        var inSettings = false
            set(v) { field = v; updateVisible() }

        private fun updateVisible() = Feeds.setVisible(resumed && !inSettings)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            _homeResets.tryEmit(Unit)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        CastRepo.init(this)
        WallpaperRepo.init(this)
        ConfigServer.start(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 作为桌面，返回键不应该把桌面自己退掉，直接吞掉
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        Feeds.start(printerEnabled = PRINTER_ENABLED)
        setContent { TvHomeApp() }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateVisible()
    }

    override fun onPause() {
        resumed = false
        updateVisible()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) ConfigServer.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}

@Composable
private fun TvHomeApp() {
    var inSettings by remember { mutableStateOf(false) }
    val config by AppSettings.state.collectAsState()
    SideEffect { MainActivity.inSettings = inSettings }

    // 设置页里返回键回到桌面
    BackHandler(enabled = inSettings) { inSettings = false }
    LaunchedEffect(Unit) { MainActivity.homeResets.collect { inSettings = false } }

    Box(Modifier.fillMaxSize().background(Palette.bgTop)) {
        if (inSettings) {
            SettingsScreen(config = config, onSaved = { inSettings = false })
        } else {
            HomeScreen(config = config, onOpenSettings = { inSettings = true }, resets = MainActivity.homeResets)
        }
    }
}