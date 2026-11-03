package com.zlaong.tvhome.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 电脑浏览器改电视设置用的小网页服务，只在局域网里能访问。
 *
 * 防护：
 * - 第一次打开要输电视屏幕上的 6 位配对码；配对成功后这台浏览器记住 1 年（Cookie）。
 * - 连续输错 5 次就换一个新码，需要回电视前看。
 * - 改设置的请求必须带自定义请求头 X-TvHome，其他网站的页面没法跨域伪造。
 * - 完整令牌只进不出：网页只能看到「已设置 · 末 4 位」。
 */
object ConfigServer {

    const val PORT = 8686
    private const val TAG = "ConfigServer"
    private const val MAX_BODY = 64 * 1024
    private const val MAX_FAILS = 5
    private const val MAX_SESSIONS = 10

    private val random = SecureRandom()
    private lateinit var appContext: Context
    private lateinit var sessionPrefs: SharedPreferences
    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null
    private val slots = Semaphore(4)

    private val _code = MutableStateFlow(newCode())
    /** 电视上显示的配对码 */
    val pairingCode: StateFlow<String> = _code.asStateFlow()
    private var fails = 0

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        appContext = context.applicationContext
        sessionPrefs = appContext.getSharedPreferences("tvhome_web_sessions", Context.MODE_PRIVATE)
        val s = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        }.getOrElse {
            Log.w(TAG, "端口 $PORT 被占用，设置网页不可用", it)
            return
        }
        server = s
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        sc.launch {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                sc.launch {
                    if (!slots.tryAcquire()) { client.close(); return@launch }
                    try { handle(client) } finally { slots.release() }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        scope?.cancel()
        scope = null
    }

    /** 电视当前的局域网地址，没联网时返回 null */
    fun url(): String? = lanIp()?.let { "http://$it:$PORT" }

    private fun lanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun newCode() = (random.nextInt(900_000) + 100_000).toString()

    // ------------------------------------------------------------ HTTP

    private class Req(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
        /** 投屏上传：请求体不读进内存，留给处理函数边收边写文件 */
        val input: InputStream,
        val length: Long,
    )

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 8_000
            val req = runCatching { read(s.getInputStream()) }.getOrNull() ?: return
            // 大视频传得慢，放宽读超时
            if (req.path == "/api/cast/upload" || req.path == "/api/wallpaper/upload") s.soTimeout = 60_000
            val out = s.getOutputStream()
            runCatching { route(req, out) }.onFailure {
                Log.w(TAG, "处理请求出错 ${req.method} ${req.path}", it)
                json(out, 500, JSONObject().put("error", "电视内部出错"))
            }
        }
    }

    private fun read(input: InputStream): Req? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 16 * 1024) return null
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) return null
        val headers = lines.drop(1).filter { ':' in it }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val path = parts[1].substringBefore('?')
        if (parts[0].uppercase() == "POST" && (path == "/api/cast/upload" || path == "/api/wallpaper/upload")) {
            val total = headers["content-length"]?.toLongOrNull() ?: return null
            return Req("POST", path, headers, "", input, total)
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len !in 0..MAX_BODY) return null
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(body, off, len - off)
            if (n < 0) return null
            off += n
        }
        return Req(parts[0].uppercase(), path, headers, String(body, Charsets.UTF_8), input, len.toLong())
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray, extra: List<String> = emptyList()) {
        val status = mapOf(200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden",
            404 to "Not Found", 429 to "Too Many Requests", 500 to "Internal Server Error", 502 to "Bad Gateway")[code] ?: "OK"
        val head = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Connection: close\r\n")
            extra.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun json(out: OutputStream, code: Int, obj: Any, extra: List<String> = emptyList()) =
        send(out, code, "application/json; charset=utf-8", obj.toString().toByteArray(Charsets.UTF_8), extra)

    // ------------------------------------------------------------ 配对

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun cookie(req: Req, name: String): String? =
        req.headers["cookie"]?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    private fun paired(req: Req): Boolean {
        val sid = cookie(req, "tvhome_sid") ?: return false
        return sha256(sid) in sessionPrefs.getStringSet("sessions", emptySet()).orEmpty()
    }

    @Synchronized
    private fun addSession(): String {
        val bytes = ByteArray(24).also(random::nextBytes)
        val sid = bytes.joinToString("") { "%02x".format(it) }
        val old = sessionPrefs.getStringSet("sessions", emptySet()).orEmpty().toList()
        // 只留最近 10 台浏览器
        val next = (old + sha256(sid)).takeLast(MAX_SESSIONS).toSet()
        sessionPrefs.edit().putStringSet("sessions", next).apply()
        return sid
    }

    /** 常量时间比较，防止按响应时间猜配对码 */
    private fun sameCode(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    @Synchronized
    private fun tryPair(code: String): Boolean {
        if (sameCode(code, _code.value)) {
            fails = 0
            _code.value = newCode() // 用过就换，屏幕上的码不能被下一台设备复用
            return true
        }
        fails++
        if (fails >= MAX_FAILS) {
            fails = 0
            _code.value = newCode()
        }
        return false
    }

    // ------------------------------------------------------------ 路由

    private fun route(req: Req, out: OutputStream) {
        val isPost = req.method == "POST"
        // 自定义请求头会触发浏览器跨域预检，我们不响应预检，其他网站就发不出这个请求
        if (isPost && req.headers["x-tvhome"] != "1") {
            return json(out, 403, JSONObject().put("error", "缺少请求头"))
        }
        when {
            req.method == "GET" && (req.path == "/" || req.path == "/index.html") -> page(out, "settings.html")

            req.method == "GET" && req.path == "/cast" -> page(out, "cast.html")

            req.method == "GET" && req.path == "/api/session" ->
                json(out, 200, JSONObject().put("paired", paired(req)))

            isPost && req.path == "/api/pair" -> {
                val code = runCatching { JSONObject(req.body).optString("code") }.getOrDefault("").trim()
                if (tryPair(code)) {
                    val sid = addSession()
                    json(out, 200, JSONObject().put("paired", true),
                        listOf("Set-Cookie: tvhome_sid=$sid; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict"))
                } else {
                    json(out, 401, JSONObject().put("error", "配对码不对，请看电视屏幕上的 6 位数字"))
                }
            }

            req.path.startsWith("/api/") && !paired(req) ->
                json(out, 401, JSONObject().put("error", "请先输入配对码"))

            req.method == "GET" && req.path == "/api/config" -> json(out, 200, configJson())

            isPost && req.path == "/api/config" -> saveConfig(req, out)

            isPost && req.path == "/api/ha/entities" -> haEntities(req, out)

            isPost && req.path == "/api/cast/upload" -> castUpload(req, out)

            isPost && req.path == "/api/cast/control" -> castControl(req, out)

            req.method == "GET" && req.path == "/api/wallpaper" -> wallpaperJson(out)

            isPost && req.path == "/api/wallpaper" -> wallpaperChoose(req, out)

            isPost && req.path == "/api/wallpaper/upload" -> {
                if (!req.headers["content-type"].orEmpty().lowercase().startsWith("image/")) {
                    return json(out, 400, JSONObject().put("error", "只能上传照片"))
                }
                val err = runBlocking { WallpaperRepo.useUpload(req.length, req.input) }
                if (err != null) json(out, 400, JSONObject().put("error", err)) else wallpaperJson(out)
            }

            else -> json(out, 404, JSONObject().put("error", "没有这个地址"))
        }
    }

    private fun page(out: OutputStream, asset: String) {
        val html = appContext.assets.open(asset).use { it.readBytes() }
        send(out, 200, "text/html; charset=utf-8", html,
            listOf("Content-Security-Policy: default-src 'self'; img-src 'self' https://cn.bing.com; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; frame-ancestors 'none'"))
    }

    // ------------------------------------------------------------ 投屏

    private fun castUpload(req: Req, out: OutputStream) {
        val kind = CastRepo.kindOf(req.headers["content-type"].orEmpty().lowercase())
            ?: return json(out, 400, JSONObject().put("error", "只能投照片或视频"))
        // 多张照片一张张发：同一批带同一个编号，序号从 0 开始；不带就当成单独一个
        val batch = req.headers["x-cast-batch"].orEmpty().take(40)
        val index = req.headers["x-cast-index"]?.toIntOrNull()?.coerceIn(0, 999) ?: 0
        val total = req.headers["x-cast-total"]?.toIntOrNull()?.coerceIn(1, 999) ?: 1
        if (index > 0 && batch.isEmpty()) return json(out, 400, JSONObject().put("error", "缺少批次编号"))
        val err = CastRepo.receive(kind, req.length, req.input, batch, index, total)
        if (err != null) json(out, 400, JSONObject().put("error", err))
        else json(out, 200, JSONObject().put("status", CastRepo.statusText()))
    }

    private fun castControl(req: Req, out: OutputStream) {
        when (runCatching { JSONObject(req.body).optString("action") }.getOrDefault("")) {
            "pause" -> CastRepo.command(CastRepo.Command.Pause)
            "play" -> CastRepo.command(CastRepo.Command.Play)
            "prev" -> CastRepo.command(CastRepo.Command.Prev)
            "next" -> CastRepo.command(CastRepo.Command.Next)
            "stop" -> CastRepo.stop()
            else -> return json(out, 400, JSONObject().put("error", "不认识的操作"))
        }
        json(out, 200, JSONObject().put("status", CastRepo.statusText()))
    }

    // ------------------------------------------------------------ 壁纸

    private fun wallpaperJson(out: OutputStream) {
        val c = WallpaperRepo.choice.value
        val list = runCatching { runBlocking { WallpaperRepo.bingList() } }.getOrDefault(emptyList())
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("copyright", it.copyright).put("thumb", it.thumb)) }
        json(out, 200, JSONObject().put("mode", c.mode.key).put("bingId", c.bingId).put("title", c.title).put("bing", arr))
    }

    private fun wallpaperChoose(req: Req, out: OutputStream) {
        val o = runCatching { JSONObject(req.body) }.getOrDefault(JSONObject())
        val err = runBlocking {
            when (o.optString("mode")) {
                "builtin" -> WallpaperRepo.useBuiltin()
                "daily" -> WallpaperRepo.useDaily()
                "bing" -> WallpaperRepo.useBing(o.optString("id"))
                else -> "不认识的壁纸"
            }
        }
        if (err != null) json(out, 400, JSONObject().put("error", err)) else wallpaperJson(out)
    }

    private fun configJson(): JSONObject {
        val c = AppSettings.state.value
        return JSONObject()
            .put("printerHost", c.printerHost)
            .put("cityName", c.cityName)
            .put("weatherAuto", c.weatherAuto)
            .put("autoCity", c.autoCity)
            .put("haUrl", c.haUrl)
            .put("haUrlDefault", c.haUrlOrDefault)
            .put("haTokenSet", c.haToken.isNotBlank())
            .put("haTokenTail", c.haToken.takeLast(4).takeIf { c.haToken.length > 12 } ?: "")
            .put("haEntities", JSONArray(c.haEntities))
            .put("haEnv", JSONObject(c.haEnv as Map<*, *>))
    }

    private fun saveConfig(req: Req, out: OutputStream) {
        val o = runCatching { JSONObject(req.body) }.getOrNull()
            ?: return json(out, 400, JSONObject().put("error", "格式不对"))
        val c = AppSettings.state.value
        val host = o.optString("printerHost", c.printerHost).trim()
        val city = o.optString("cityName", c.cityName).trim()
        if (host.isBlank() || host.length > 100 || city.length > 40) {
            return json(out, 400, JSONObject().put("error", "Mac mini 地址或城市不对"))
        }
        val url = o.optString("haUrl", c.haUrl)
        if (url.length > 200 || (url.isNotBlank() && !validHaUrl(AppSettings.normalizeHaUrl(url)))) {
            return json(out, 400, JSONObject().put("error", "Home Assistant 地址不对"))
        }
        // 没传或传空 = 沿用已保存的令牌；传 clear=true 才清空
        val token = when {
            o.optBoolean("haTokenClear", false) -> ""
            o.optString("haToken").isNotBlank() -> o.optString("haToken").trim().take(1000)
            else -> null
        }
        val ents = o.optJSONArray("haEntities")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            ?: c.haEntities
        val weatherAuto = if (o.has("weatherAuto")) o.optBoolean("weatherAuto") else c.weatherAuto
        if (!weatherAuto && city.isBlank()) return json(out, 400, JSONObject().put("error", "手动设置时请填写城市"))
        if (host != c.printerHost || city != c.cityName || weatherAuto != c.weatherAuto) {
            AppSettings.save(host, city, weatherAuto = weatherAuto)
        }
        // haEnv：{"temperature":"sensor.x", "pm25":""}；"" = 不显示，"auto" 或不传 = 自动挑
        val env = o.optJSONObject("haEnv")?.let { e ->
            AppSettings.ENV_KINDS.mapNotNull { k ->
                if (!e.has(k)) return@mapNotNull null
                val v = e.optString(k).trim()
                if (v == "auto") null else k to v
            }.toMap()
        }
        AppSettings.saveHa(url, token, ents.take(40), env)
        json(out, 200, configJson())
    }

    /** 列出 Home Assistant 里的设备；顺便当「测试连接」用 */
    private fun haEntities(req: Req, out: OutputStream) {
        val o = runCatching { JSONObject(req.body) }.getOrDefault(JSONObject())
        val c = AppSettings.state.value
        val base = AppSettings.normalizeHaUrl(o.optString("haUrl", c.haUrl)).ifBlank { c.haUrlOrDefault }
        if (!validHaUrl(base)) return json(out, 400, JSONObject().put("error", "Home Assistant 地址不对"))
        val token = o.optString("haToken").trim().ifBlank { c.haToken }
        if (token.isBlank()) return json(out, 400, JSONObject().put("error", "请先填写令牌"))

        val result = runCatching { runBlocking { HomeRepo.listHaEntities(base, token) } }
        result.onSuccess { list ->
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().put("id", it.id).put("name", it.name).put("domain", it.domain)
                    .put("state", it.state).put("suggested", it.suggested).put("envKind", it.envKind ?: JSONObject.NULL))
            }
            json(out, 200, JSONObject().put("base", base).put("entities", arr))
        }.onFailure {
            json(out, 502, JSONObject().put("error", HomeRepo.haError(it, base)))
        }
    }

    /** 只允许 http(s)，主机名里不能夹带路径、账号之类 */
    private fun validHaUrl(u: String): Boolean =
        Regex("^https?://[A-Za-z0-9.\\-]+(:\\d{1,5})?$").matches(u)
}
