package com.zlaong.tvhome.data

import android.content.Context
import android.content.Intent
import android.util.Log
import com.zlaong.tvhome.CastActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream

/**
 * 手机投过来的照片 / 视频，放在应用缓存目录里，停止投屏就删掉。
 *
 * 一次投屏 = 一批：一个视频，或者一组照片。手机按顺序一张张发，
 * 第一张到了电视就开始显示，后面的边收边加，遥控器左右键翻页。
 */
object CastRepo {

    private const val TAG = "CastRepo"
    private const val MAX_SIZE = 10L * 1024 * 1024 * 1024
    /** 至少给系统留这么多空间，别把电视存储塞满 */
    private const val KEEP_FREE = 500L * 1024 * 1024

    enum class Kind { Photo, Video }

    sealed interface State {
        data object Idle : State
        /** 这一批的第一个文件还在路上 */
        data class Receiving(val kind: Kind, val percent: Int) : State
        data class Showing(
            val kind: Kind,
            /** 已经收到的文件，按手机上选的顺序 */
            val files: List<File>,
            /** 电视上正在显示第几个 */
            val index: Int,
            /** 手机这一批一共要发几个 */
            val total: Int,
            /** 每批不同，换批时界面要重置 */
            val session: Long,
        ) : State {
            val current get() = files[index]
        }
    }

    enum class Command { Pause, Play, Prev, Next }

    private lateinit var appContext: Context
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 4)
    /** 手机网页上点的「暂停 / 继续」 */
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    /** 新的一批或停止投屏时 +1，还在传的旧文件看到不一样就放弃 */
    @Volatile private var session = 0L
    /** 手机给这一批起的编号，后续文件靠它认出属于同一批 */
    private var batch = ""

    fun init(context: Context) {
        appContext = context.applicationContext
        // 上次没清掉的文件（比如电视直接断电）
        dir().listFiles()?.forEach { it.delete() }
    }

    private fun dir() = File(appContext.cacheDir, "cast").apply { mkdirs() }

    fun kindOf(mime: String): Kind? = when {
        mime.startsWith("image/") -> Kind.Photo
        mime.startsWith("video/") -> Kind.Video
        else -> null
    }

    /**
     * 收一个文件。[index] 为 0 表示新的一批（替换掉正在放的），大于 0 表示追加到 [batchId] 这一批。
     * 返回 null 表示成功，否则是给手机看的出错原因。
     */
    fun receive(kind: Kind, length: Long, input: InputStream, batchId: String, index: Int, total: Int): String? {
        if (length <= 0 || length > MAX_SIZE) return "文件太大了（最多 10 GB）"
        val d = dir()
        if (d.usableSpace - length < KEEP_FREE) return "电视存储空间不够，换个小一点的试试"

        val first = index == 0
        val my = synchronized(this) {
            if (first) {
                session++
                batch = batchId
                _state.value = State.Receiving(kind, 0)
            } else {
                val s = _state.value
                if (batchId != batch || s !is State.Showing || s.kind != Kind.Photo) return "电视上已经换成别的投屏了"
            }
            session
        }
        if (first) openPlayer()

        val part = File(d, "$my-$index.part")
        val ok = write(part, length, input, my) { pct ->
            if (first) _state.value = State.Receiving(kind, pct)
        }

        synchronized(this) {
            if (my != session) { part.delete(); return "已被新的投屏替换" }
            if (!ok) {
                part.delete()
                if (first) _state.value = State.Idle
                return "传输中断了，请重试"
            }
            val file = File(d, "$my-$index." + if (kind == Kind.Photo) "img" else "mp4")
            if (!part.renameTo(file)) {
                part.delete()
                if (first) _state.value = State.Idle
                return "电视内部出错"
            }
            if (first) {
                d.listFiles()?.filter { it != file && !it.name.startsWith("$my-") }?.forEach { it.delete() }
                _state.value = State.Showing(kind, listOf(file), 0, total.coerceAtLeast(1), my)
            } else {
                val s = _state.value as? State.Showing ?: return "电视上已经换成别的投屏了"
                _state.value = s.copy(files = s.files + file)
            }
        }
        if (first) openPlayer()
        return null
    }

    private fun write(part: File, length: Long, input: InputStream, my: Long, onProgress: (Int) -> Unit): Boolean =
        try {
            part.outputStream().buffered(256 * 1024).use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var lastPct = -1
                while (done < length) {
                    // 手机又发了新的一批，或者电视上按了返回：这个不要了
                    if (my != session) return false
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), length - done).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val pct = (done * 100 / length).toInt()
                    if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                }
                done == length
            }
        } catch (e: Exception) {
            Log.w(TAG, "接收文件失败", e)
            false
        }

    /** 照片翻页，到头就停住 */
    @Synchronized
    fun step(delta: Int) {
        val s = _state.value as? State.Showing ?: return
        _state.value = s.copy(index = (s.index + delta).coerceIn(0, s.files.lastIndex))
    }

    fun command(c: Command) {
        when (c) {
            Command.Prev -> step(-1)
            Command.Next -> step(1)
            else -> _commands.tryEmit(c)
        }
    }

    /** 电视上按返回或手机上点「停止」 */
    @Synchronized
    fun stop() {
        session++
        batch = ""
        _state.value = State.Idle
        dir().listFiles()?.filter { !it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    fun statusText(): String = when (val s = _state.value) {
        State.Idle -> "idle"
        is State.Receiving -> "receiving"
        is State.Showing -> if (s.kind == Kind.Photo) "photo" else "video"
    }

    private fun openPlayer() {
        // 电视停在别的应用里时，安卓 10 以上需要「显示在其他应用上层」权限才能切过来（install.sh 会授权）
        runCatching {
            appContext.startActivity(
                Intent(appContext, CastActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }.onFailure { Log.w(TAG, "打不开投屏页", it) }
    }
}
