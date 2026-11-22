package com.noir.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * One shared HTTP client for images and downloads. Many community sites refuse images that arrive
 * without the site's own Referer or with a bare "okhttp" user agent, so both are supplied here.
 */
object NoirNetwork {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private val refererByHost = ConcurrentHashMap<String, String>()

    fun originOf(url: String?): String? {
        val parsed = url?.toHttpUrlOrNull() ?: return null
        return "${parsed.scheme}://${parsed.host}/"
    }

    /** Learns which site an image host belongs to, so its Referer points at the reading site. */
    fun learn(imageUrl: String?, siteOrigin: String?) {
        val host = imageUrl?.toHttpUrlOrNull()?.host ?: return
        if (siteOrigin != null) refererByHost[host] = siteOrigin
    }

    private val headerInterceptor = Interceptor { chain ->
        val request = chain.request()
        val host = request.url.host
        if (host.endsWith("mangadex.org") || host.endsWith("mangadex.network")) {
            chain.proceed(request)
        } else {
            val builder = request.newBuilder()
            if (request.header("Referer") == null) {
                builder.header("Referer", refererByHost[host] ?: "${request.url.scheme}://$host/")
            }
            val agent = request.header("User-Agent")
            if (agent == null || agent.startsWith("okhttp")) builder.header("User-Agent", USER_AGENT)
            chain.proceed(builder.build())
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .addInterceptor(headerInterceptor)
            .build()
    }

    /** True when the first bytes of an image can really be fetched with the headers NOIR sends. */
    suspend fun probeImage(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("Range", "bytes=0-1023").build()
            client.newCall(request).execute().use { it.code in 200..299 }
        }.getOrDefault(false)
    }

    /** True when the cover is an animated GIF, animated WebP or APNG, judged from its first bytes. */
    suspend fun coverIsAnimated(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("Range", "bytes=0-4095").build()
            client.newCall(request).execute().use { r ->
                if (r.code !in 200..299) return@use false
                val b = r.body?.source()?.let { src -> src.request(4096); src.buffer.snapshot().toByteArray() } ?: return@use false
                if (b.size < 16) return@use false
                val head = String(b, 0, 12, Charsets.ISO_8859_1)
                when {
                    head.startsWith("GIF8") -> true
                    head.startsWith("RIFF") && head.substring(8, 12) == "WEBP" ->
                        String(b, Charsets.ISO_8859_1).contains("ANIM") ||
                            (String(b, 12, 4, Charsets.ISO_8859_1) == "VP8X" && b.size > 20 && (b[20].toInt() and 0x02) != 0)
                    b[0] == 0x89.toByte() && head.substring(1, 4) == "PNG" -> String(b, Charsets.ISO_8859_1).contains("acTL")
                    else -> false
                }
            }
        }.getOrDefault(false)
    }
}
