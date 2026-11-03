package com.zlaong.tvhome.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** 非 2xx 响应，带状态码，方便区分「令牌错」和「连不上」 */
class HttpStatusException(val code: Int) : IllegalStateException("HTTP $code")

/** 极简 HTTP，用系统自带 HttpURLConnection，不额外引三方库 */
object Http {

    suspend fun post(
        url: String,
        json: String,
        timeoutMs: Int = 8000,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        trace("POST", url)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) throw HttpStatusException(code)
            body
        } finally {
            conn.disconnect()
        }
    }

    /** 调试用：只记主机和路径，不记参数和请求头（里面有令牌）。adb logcat -s TvHomeNet */
    private fun trace(method: String, url: String) {
        if (com.zlaong.tvhome.BuildConfig.DEBUG) {
            val u = runCatching { URL(url) }.getOrNull() ?: return
            android.util.Log.d("TvHomeNet", "$method ${u.host}${u.path}")
        }
    }

    suspend fun get(
        url: String,
        timeoutMs: Int = 8000,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        trace("GET", url)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) throw HttpStatusException(code)
            body
        } finally {
            conn.disconnect()
        }
    }

    /** 下载图片之类的二进制内容，超过 maxBytes 直接放弃 */
    suspend fun bytes(url: String, maxBytes: Int, timeoutMs: Int = 15000): ByteArray = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            instanceFollowRedirects = false
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpStatusException(code)
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) error("文件太大")
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }
}
