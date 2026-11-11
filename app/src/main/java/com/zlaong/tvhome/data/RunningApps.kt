package com.zlaong.tvhome.data

import android.app.ActivityManager
import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 哪些应用还在后台没退出：系统里它的任务还留着界面（按主页键离开的），
 * 一路按返回退出、或被系统回收了的不算。
 *
 * 两个用途：
 * - 首页图标下面的小点；
 * - 点图标时把已经在后台的应用原样切回前台（不重新下发启动指令，App 不会闪启动图、
 *   也不会重走开屏广告、播放位置和停留的页面都保留）。
 *
 * 普通应用没有接口能看别的应用的任务，这里直接向系统的 activity 服务要它的状态转储
 * （和 `dumpsys activity activities` 同一份内容）。要 DUMP 和 PACKAGE_USAGE_STATS 两个权限，
 * 只能用 adb 授予（见 tools/install.sh）。没授予时返回空，首页就不显示小点，
 * 点图标也退回普通启动。
 *
 * 不能直接跑 dumpsys 命令：子进程认不出是哪个应用，PACKAGE_USAGE_STATS 校验过不去。
 *
 * 只看有界面的任务，不看进程：很多影视应用退出后还留着推送、下载之类的后台进程，
 * 按进程算的话几乎每个都亮点，没有意义。
 */
object RunningApps {

    private const val TAG = "RunningApps"

    /** `* TaskRecord{8ad82af #2515 A=com.ktcp.tvvideo … sz=1}`：#任务编号、包名、还剩几个界面 */
    private val TASK = Regex("""\* TaskRecord\{\S+ #(\d+) A=(?:\d+:)?([\w.]+) .*?sz=(\d+)\}""")

    /** 包名 → 任务编号。同名的多个任务取最后一个 */
    data class Tasks(val ids: Map<String, Int> = emptyMap()) {
        val packages: Set<String> get() = ids.keys
    }

    suspend fun load(): Tasks = withContext(Dispatchers.IO) {
        runCatching { parse(dump()) }
            .onFailure { Log.w(TAG, "读后台任务失败", it) }
            .getOrDefault(Tasks())
    }

    /**
     * 把还留在后台的任务原样切回前台。成功返回 true；
     * 任务已经不在了、或系统不让切，返回 false，由调用方退回普通启动。
     */
    fun bringToFront(context: Context, taskId: Int): Boolean = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION)
        true
    }.getOrElse {
        if (com.zlaong.tvhome.BuildConfig.DEBUG) Log.d(TAG, "切前台失败 task=$taskId", it)
        false
    }

    private fun dump(): String {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "activity") as IBinder
        val (read, write) = ParcelFileDescriptor.createPipe()
        // 系统往管道里写，写满了要等这边读走才能继续，所以另开一个线程边写边读。
        // 不能用 dumpAsync：单向调用系统拿不到调用方的进程号，权限校验会失败
        var text = ""
        val reader = Thread {
            text = ParcelFileDescriptor.AutoCloseInputStream(read).bufferedReader().use { it.readText() }
        }.apply { start() }
        try {
            binder.dump(write.fileDescriptor, arrayOf("activities"))
        } finally {
            write.close()
        }
        reader.join(3000)
        return text
    }

    fun parse(dump: String): Tasks = Tasks(
        TASK.findAll(dump)
            .filter { it.groupValues[3].toInt() > 0 }
            .associate { it.groupValues[2] to it.groupValues[1].toInt() },
    )
}