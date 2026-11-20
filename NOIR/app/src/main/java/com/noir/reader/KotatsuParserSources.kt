package com.noir.reader

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.HttpUrl
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONTokener
import org.koitharu.kotatsu.parsers.DirectParsersAccess
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.bitmap.Bitmap as ParserBitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect as ParserRect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.core.ParsersBundleImpl
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource as ParserMangaSource
import org.koitharu.kotatsu.parsers.model.ParserMangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.webview.InterceptedRequest
import org.koitharu.kotatsu.parsers.webview.InterceptionConfig
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Adapter between Kotatsu's GPL-3.0 parser contract and NOIR's source-neutral reader model.
 *
 * NOIR keeps its own UI, library and reader. The parser library supplies website adapters only.
 */
internal object KotatsuParserSources {
    @OptIn(DirectParsersAccess::class)
    fun create(context: Context): List<MangaSource> {
        val host = NoirParserContext(context)
        return ParsersBundleImpl.sources
            .asSequence()
            .filterNot { it.name.equals("MANGADEX", ignoreCase = true) || it.title.equals("MangaDex", ignoreCase = true) }
            .map { KotatsuMangaSource(it, host) }
            .toList()
    }
}

private class KotatsuMangaSource(
    private val parserSource: ParserMangaSource,
    private val host: NoirParserContext
) : MangaSource {
    override val id: String = "kotatsu:${parserSource.name}"
    override val name: String = parserSource.title.ifBlank { parserSource.name }
    override val description: String = "Kotatsu community parser"
    override val language: String = parserSource.locale.ifBlank { "Multi" }
    override val category: String = parserSource.contentType.name.replace('_', ' ')
    override val isBroken: Boolean = parserSource.isBroken

    override suspend fun popular(page: Int): List<Series> = load(null, page)

    override suspend fun search(query: String, page: Int): List<Series> = load(query, page)

    private suspend fun load(query: String?, page: Int): List<Series> = withContext(Dispatchers.IO) {
        val parser = host.newParserInstance(parserSource)
        val order = parser.availableSortOrders.firstOrNull { it == SortOrder.POPULARITY }
            ?: parser.availableSortOrders.firstOrNull()
            ?: SortOrder.UPDATED
        val filter = MangaListFilter(
            query = query?.takeIf { it.isNotBlank() },
            contentRating = setOf(ContentRating.SAFE, ContentRating.SUGGESTIVE)
        )
        parser.getList(
            offset = (page.coerceAtLeast(0) * PAGE_SIZE),
            order = order,
            filter = filter
        ).asSequence()
            .filterNot { it.contentRating == ContentRating.ADULT }
            .map { manga ->
                Series(
                    id = manga.id.toString(),
                    title = manga.title,
                    description = manga.description.orEmpty(),
                    coverUrl = manga.coverUrl,
                    sourceId = id,
                    sourceName = name,
                    sourceUrl = manga.url,
                    sourcePublicUrl = manga.publicUrl
                )
            }
            .toList()
    }

    override suspend fun chapters(series: Series): List<Chapter> = withContext(Dispatchers.IO) {
        val parser = host.newParserInstance(parserSource)
        val details = parser.getDetails(series.toParserManga())
        details.chapters.orEmpty().map { item ->
            val number = item.numberString() ?: "?"
            Chapter(
                id = item.id.toString(),
                name = item.title?.takeIf { it.isNotBlank() } ?: "Chapter $number",
                number = number,
                language = item.branch ?: parserSource.locale.ifBlank { "Unknown" },
                sourceUrl = item.url,
                sourceTitle = item.title,
                sourceBranch = item.branch,
                sourceUploadDate = item.uploadDate,
                sourceVolume = item.volume,
                sourceScanlator = item.scanlator
            )
        }
    }

    override suspend fun pages(chapter: Chapter): ChapterPages = withContext(Dispatchers.IO) {
        val parser = host.newParserInstance(parserSource)
        val model = MangaChapter(
            id = chapter.id.toLong(),
            title = chapter.sourceTitle,
            number = chapter.number.toFloatOrNull() ?: 0f,
            volume = chapter.sourceVolume,
            url = chapter.sourceUrl.orEmpty(),
            scanlator = chapter.sourceScanlator,
            uploadDate = chapter.sourceUploadDate,
            branch = chapter.sourceBranch,
            source = parserSource
        )
        val urls = parser.getPages(model).map { page -> parser.getPageUrl(page) }
        ChapterPages(chapter.id, urls)
    }

    private fun Series.toParserManga(): Manga {
        val numericId = id.toLongOrNull()
            ?: throw IllegalArgumentException("This title has an invalid Kotatsu parser ID.")
        return Manga(
            id = numericId,
            title = title,
            altTitles = emptySet(),
            url = sourceUrl.orEmpty(),
            publicUrl = sourcePublicUrl ?: sourceUrl.orEmpty(),
            rating = 0f,
            contentRating = ContentRating.SAFE,
            coverUrl = coverUrl,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            description = description,
            source = parserSource
        )
    }

    private companion object {
        const val PAGE_SIZE = 24
    }
}

private class NoirParserContext(context: Context) : MangaLoaderContext() {
    private val appContext = context.applicationContext
    override val cookieJar: CookieJar = NoirCookieJar()
    override val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @OptIn(DirectParsersAccess::class)
    override val parsers: ParsersBundle
        get() = ParsersBundleImpl

    override fun getConfig(source: ParserMangaSource): MangaSourceConfig = DefaultParserConfig

    override fun getDefaultUserAgent(): String =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @Deprecated("Provide a base url")
    override suspend fun evaluateJs(script: String): String? =
        evaluateJs("", script, 10000L)

    override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? =
        withTimeout(timeout.coerceAtLeast(1L)) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    val webView = WebView(appContext)
                    var evaluated = false
                    fun destroy() {
                        runCatching { webView.stopLoading() }
                        runCatching { webView.destroy() }
                    }
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.userAgentString = getDefaultUserAgent()
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            if (evaluated || !continuation.isActive) return
                            evaluated = true
                            view.evaluateJavascript(script) { raw ->
                                if (continuation.isActive) continuation.resume(decodeJsValue(raw))
                                view.postDelayed({ destroy() }, 100L)
                            }
                        }
                    }
                    continuation.invokeOnCancellation {
                        webView.post { destroy() }
                    }
                    if (baseUrl.isBlank()) {
                        webView.loadDataWithBaseURL("https://noir.local/", "", "text/html", "UTF-8", null)
                    } else {
                        webView.loadUrl(baseUrl)
                    }
                }
            }
        }

    override fun redrawImageResponse(response: Response, redraw: (ParserBitmap) -> ParserBitmap): Response {
        val body = response.body ?: return response
        val contentType = body.contentType()
        val bytes = body.bytes()
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return response.newBuilder().body(bytes.toResponseBody(contentType)).build()
        val source = NoirParserBitmap(decoded)
        val result = runCatching { redraw(source) }.getOrDefault(source)
        val outputBitmap = (result as? NoirParserBitmap)?.bitmap ?: decoded
        val output = ByteArrayOutputStream()
        outputBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, output)
        if (outputBitmap !== decoded) outputBitmap.recycle()
        decoded.recycle()
        return response.newBuilder().body(output.toByteArray().toResponseBody(contentType)).build()
    }

    override fun createBitmap(width: Int, height: Int): ParserBitmap =
        NoirParserBitmap(android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888))

    override suspend fun interceptWebViewRequests(
        url: String,
        interceptorScript: String,
        timeout: Long
    ): List<InterceptedRequest> = captureRequests(url, InterceptionConfig(timeoutMs = timeout))

    override suspend fun interceptWebViewRequests(
        url: String,
        config: InterceptionConfig
    ): List<InterceptedRequest> = captureRequests(url, config)

    override suspend fun captureWebViewUrls(
        pageUrl: String,
        urlPattern: Regex,
        timeout: Long
    ): List<String> = captureRequests(
        pageUrl,
        InterceptionConfig(timeoutMs = timeout, urlPattern = urlPattern)
    ).map { it.url }

    private suspend fun captureRequests(
        pageUrl: String,
        config: InterceptionConfig
    ): List<InterceptedRequest> = withTimeout(config.timeoutMs.coerceAtLeast(1L)) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(appContext)
                val handler = Handler(Looper.getMainLooper())
                val captured = Collections.synchronizedList(mutableListOf<InterceptedRequest>())
                var finishRunnable: Runnable? = null
                fun cleanup() {
                    finishRunnable?.let(handler::removeCallbacks)
                    runCatching { webView.stopLoading() }
                    runCatching { webView.destroy() }
                }
                fun scheduleFinish(delayMs: Long = 850L) {
                    finishRunnable?.let(handler::removeCallbacks)
                    val task = Runnable {
                        if (continuation.isActive) continuation.resume(captured.toList())
                        cleanup()
                    }
                    finishRunnable = task
                    handler.postDelayed(task, delayMs)
                }
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = getDefaultUserAgent()
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val requestUrl = request.url.toString()
                        val pattern = config.urlPattern
                        if ((pattern == null || pattern.containsMatchIn(requestUrl)) &&
                            captured.size < config.maxRequests.coerceAtLeast(1)
                        ) {
                            captured.add(
                                InterceptedRequest(
                                    url = requestUrl,
                                    method = request.method,
                                    headers = request.requestHeaders,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                            handler.post {
                                if (captured.size >= config.maxRequests.coerceAtLeast(1)) scheduleFinish(150L)
                            }
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        val script = config.pageScript
                        if (script.isNullOrBlank()) {
                            scheduleFinish()
                        } else {
                            view.evaluateJavascript(script) { scheduleFinish() }
                        }
                    }
                }
                continuation.invokeOnCancellation { handler.post { cleanup() } }
                webView.loadUrl(pageUrl)
            }
        }
    }

    private fun decodeJsValue(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return runCatching {
            val parsed = JSONTokener(raw).nextValue()
            if (parsed is String) parsed else parsed.toString()
        }.getOrDefault(raw)
    }

    private object DefaultParserConfig : MangaSourceConfig {
        override fun <T> get(key: ConfigKey<T>): T = key.defaultValue
    }
}

private class NoirParserBitmap(
    val bitmap: android.graphics.Bitmap
) : ParserBitmap {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun drawBitmap(sourceBitmap: ParserBitmap, src: ParserRect, dst: ParserRect) {
        val source = (sourceBitmap as? NoirParserBitmap)?.bitmap ?: return
        val sourceRect = AndroidRect(src.left, src.top, src.right, src.bottom)
        val targetRect = AndroidRect(dst.left, dst.top, dst.right, dst.bottom)
        Canvas(bitmap).drawBitmap(source, sourceRect, targetRect, Paint(Paint.FILTER_BITMAP_FLAG))
    }
}

private class NoirCookieJar : CookieJar {
    private val lock = Any()
    private val cookies = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            this.cookies.removeAll { old ->
                cookies.any { fresh ->
                    old.name == fresh.name && old.domain == fresh.domain && old.path == fresh.path
                }
            }
            this.cookies.addAll(cookies)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        cookies.filter { it.matches(url) }
    }
}
