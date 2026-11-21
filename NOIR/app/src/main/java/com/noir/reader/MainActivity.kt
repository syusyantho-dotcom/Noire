package com.noir.reader

import android.os.Bundle
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as rotateDraw
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Black = Color(0xFF070708)
private val Panel = Color(0xFF17171A)
private val White = Color(0xFFF4F4F5)
private val Muted = Color(0xFF99999F)
private val Silver = Color(0xFFC6C6CC)

private object SourceIconCache {
    private val icons = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun get(sourceId: String): String? = icons[sourceId]
    fun contains(sourceId: String): Boolean = icons.containsKey(sourceId)
    fun put(sourceId: String, url: String) { icons[sourceId] = url }
}

@Composable
private fun SourceIcon(source: MangaSource, modifier: Modifier = Modifier) {
    var iconUrl by remember(source.id) {
        mutableStateOf(SourceIconCache.get(source.id)?.takeIf { it.isNotBlank() })
    }
    var resolved by remember(source.id) { mutableStateOf(SourceIconCache.contains(source.id)) }

    LaunchedEffect(source.id) {
        if (!resolved) {
            val found = runCatching { source.iconUrl() }.getOrNull()
            SourceIconCache.put(source.id, found.orEmpty())
            iconUrl = found
            resolved = true
        }
    }

    Box(
        modifier.clip(RoundedCornerShape(13.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF302A39), Color(0xFF17151C)))),
        contentAlignment = Alignment.Center
    ) {
        if (!iconUrl.isNullOrBlank()) {
            AsyncImage(
                model = iconUrl,
                contentDescription = source.name + " icon",
                modifier = Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                source.name.take(2).uppercase(),
                color = Color(0xFFE6DDF0),
                fontSize = 15.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(7, 7, 8)
        window.navigationBarColor = android.graphics.Color.rgb(7, 7, 8)
        SourceRegistry.initialize(applicationContext)
        setContent { NoirApp() }
    }
}

private data class NoirNavigationState(
    val tab: String,
    val selected: Series?,
    val selectedSource: MangaSource?,
    val selectedChapter: Chapter?
)

private data class ReadingHistoryEntry(
    val chapterId: String,
    val chapterName: String,
    val pageNumber: Int,
    val pageCount: Int,
    val updatedAt: Long
)

@Composable
private fun NoirApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("noir_preferences", Context.MODE_PRIVATE) }
    val sourcePrefs = remember { context.getSharedPreferences("noir_sources", Context.MODE_PRIVATE) }
    val enabledSourceIds = remember {
        mutableStateListOf<String>().apply {
            val raw = sourcePrefs.getString("enabled_sources", null)
            if (raw == null) {
                SourceRegistry.sources.firstOrNull {
                    it.name.contains("Ainzscan", ignoreCase = true) ||
                        it.id.contains("ainzscan", ignoreCase = true)
                }?.let { add(it.id) }
            } else {
                runCatching {
                    val stored = JSONArray(raw)
                    for (index in 0 until stored.length()) {
                        val id = stored.optString(index)
                        if (id.isNotBlank()) add(id)
                    }
                }
                // Migrate the previous first-install default from MangaDex to Ainzscan.
                if (size == 1 && firstOrNull() == "mangadex") {
                    clear()
                    SourceRegistry.sources.firstOrNull {
                        it.name.contains("Ainzscan", ignoreCase = true) ||
                            it.id.contains("ainzscan", ignoreCase = true)
                    }?.let { add(it.id) }
                }
            }
        }
    }
    val activeSourceIds = enabledSourceIds.toList()
    val activeSources = SourceRegistry.sources.filter { it.id in activeSourceIds }
    LaunchedEffect(activeSourceIds) {
        val stored = JSONArray()
        activeSourceIds.forEach { stored.put(it) }
        sourcePrefs.edit().putString("enabled_sources", stored.toString()).apply()
    }
    var tab by remember { mutableStateOf("Home") }
    var discoveryCompactGrid by remember { mutableStateOf(prefs.getBoolean("discovery_compact_grid", false)) }
    LaunchedEffect(discoveryCompactGrid) {
        prefs.edit().putBoolean("discovery_compact_grid", discoveryCompactGrid).apply()
    }
    var introSound by remember { mutableStateOf(prefs.getBoolean("intro_sound", true)) }
    var launchIntro by remember { mutableStateOf(prefs.getBoolean("launch_intro", true)) }
    var matureContentEnabled by remember { mutableStateOf(prefs.getBoolean("mature_content_18_plus", false)) }
    SideEffect { MatureContentAccess.enabled = matureContentEnabled }
    var showAbout by remember { mutableStateOf(false) }
    var showWhatsNew by remember { mutableStateOf(false) }
    val whatsNewVersion = "1.5.0"
    val shouldShowWhatsNew = remember { prefs.getString("seen_whats_new", "") != whatsNewVersion }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Series?>(null) }
    var selectedSource by remember { mutableStateOf<MangaSource?>(null) }
    var selectedChapter by remember { mutableStateOf<Chapter?>(null) }
    var pendingResumeKey by remember { mutableStateOf<String?>(null) }
    val navigationStack = remember { mutableStateListOf<NoirNavigationState>() }
    val tabBackStack = remember { mutableStateListOf<String>() }
    val currentNavigationState = { NoirNavigationState(tab, selected, selectedSource, selectedChapter) }
    fun goBack() {
        if (navigationStack.isNotEmpty()) {
            val previous = navigationStack.removeAt(navigationStack.lastIndex)
            tab = previous.tab
            selected = previous.selected
            selectedSource = previous.selectedSource
            selectedChapter = previous.selectedChapter
        } else when {
            selectedChapter != null -> selectedChapter = null
            selectedSource != null -> selectedSource = null
            selected != null -> selected = null
            tabBackStack.isNotEmpty() -> tab = tabBackStack.removeAt(tabBackStack.lastIndex)
            tab != "Home" -> tab = "Home"
        }
    }
    fun navigateTab(destination: String) {
        if (destination != tab) {
            tabBackStack.add(tab)
            navigationStack.clear()
            selected = null
            selectedSource = null
            selectedChapter = null
            tab = destination
        }
    }
    var readerMode by remember { mutableStateOf(prefs.getString("reader_mode", "Vertical") ?: "Vertical") }
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var pages by remember { mutableStateOf<List<String>>(emptyList()) }
    val preloadedChapterPages = remember { mutableStateMapOf<String, List<String>>() }
    var catalogue by remember { mutableStateOf<List<Series>>(emptyList()) }
    var cataloguePage by remember { mutableIntStateOf(0) }
    var hasMoreCatalogue by remember { mutableStateOf(false) }
    var loadingMoreCatalogue by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val saved = remember {
        mutableStateListOf<Series>().apply {
            runCatching {
                val stored = JSONArray(prefs.getString("saved_series", "[]"))
                for (index in 0 until stored.length()) {
                    val item = stored.optJSONObject(index) ?: continue
                    add(
                        Series(
                            id = item.optString("id"),
                            title = item.optString("title"),
                            description = item.optString("description"),
                            coverUrl = item.optString("coverUrl").takeIf { it.isNotBlank() },
                            sourceId = item.optString("sourceId"),
                            sourceName = item.optString("sourceName"),
                            sourceUrl = item.optString("sourceUrl").takeIf { it.isNotBlank() },
                            sourcePublicUrl = item.optString("sourcePublicUrl").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }
    }
    LaunchedEffect(saved.toList()) {
        val stored = JSONArray()
        saved.forEach { item ->
            stored.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("description", item.description)
                    .put("coverUrl", item.coverUrl ?: "")
                    .put("sourceId", item.sourceId)
                    .put("sourceName", item.sourceName)
                    .put("sourceUrl", item.sourceUrl ?: "")
                    .put("sourcePublicUrl", item.sourcePublicUrl ?: "")
            )
        }
        prefs.edit().putString("saved_series", stored.toString()).apply()
    }
    val recentlyRead = remember {
        mutableStateListOf<Series>().apply {
            runCatching {
                val stored = JSONArray(prefs.getString("recent_series", "[]"))
                for (index in 0 until stored.length()) {
                    val item = stored.optJSONObject(index) ?: continue
                    add(Series(
                        item.optString("id"),
                        item.optString("title"),
                        item.optString("description"),
                        item.optString("coverUrl").takeIf { it.isNotBlank() },
                        item.optString("sourceId"),
                        item.optString("sourceName"),
                        item.optString("sourceUrl").takeIf { it.isNotBlank() },
                        item.optString("sourcePublicUrl").takeIf { it.isNotBlank() }
                    ))
                }
            }
        }
    }
    LaunchedEffect(recentlyRead.toList()) {
        val stored = JSONArray()
        recentlyRead.take(30).forEach { item ->
            stored.put(JSONObject().put("id", item.id).put("title", item.title).put("description", item.description)
                .put("coverUrl", item.coverUrl ?: "").put("sourceId", item.sourceId).put("sourceName", item.sourceName)
                .put("sourceUrl", item.sourceUrl ?: "").put("sourcePublicUrl", item.sourcePublicUrl ?: ""))
        }
        prefs.edit().putString("recent_series", stored.toString()).apply()
    }
    val libraryCategories = remember {
        mutableStateMapOf<String, String>().apply {
            runCatching {
                val stored = JSONObject(prefs.getString("library_categories", "{}"))
                stored.keys().forEach { key -> put(key, stored.optString(key, "Reading")) }
            }
        }
    }
    LaunchedEffect(libraryCategories.toMap()) {
        val stored = JSONObject()
        libraryCategories.forEach { (key, value) -> stored.put(key, value) }
        prefs.edit().putString("library_categories", stored.toString()).apply()
    }
    val bookmarkedChapterKeys = remember {
        mutableStateListOf<String>().apply {
            runCatching {
                val stored = JSONArray(prefs.getString("bookmarked_chapters", "[]"))
                for (index in 0 until stored.length()) {
                    val key = stored.optString(index)
                    if (key.isNotBlank()) add(key)
                }
            }
        }
    }
    LaunchedEffect(bookmarkedChapterKeys.toList()) {
        val stored = JSONArray()
        bookmarkedChapterKeys.forEach { stored.put(it) }
        prefs.edit().putString("bookmarked_chapters", stored.toString()).apply()
    }
    val readingProgress = remember {
        mutableStateMapOf<String, Int>().apply {
            runCatching {
                val stored = JSONObject(prefs.getString("reading_progress", "{}"))
                stored.keys().forEach { key -> put(key, stored.optInt(key, 0)) }
            }
        }
    }
    LaunchedEffect(readingProgress.toMap()) {
        val stored = JSONObject()
        readingProgress.forEach { (key, value) -> stored.put(key, value) }
        prefs.edit().putString("reading_progress", stored.toString()).apply()
    }
    val seriesProgress = remember {
        mutableStateMapOf<String, Float>().apply {
            runCatching {
                val stored = JSONObject(prefs.getString("series_reading_progress", "{}"))
                stored.keys().forEach { key -> put(key, stored.optDouble(key, 0.0).toFloat().coerceIn(0f, 1f)) }
            }
        }
    }
    LaunchedEffect(seriesProgress.toMap()) {
        val stored = JSONObject()
        seriesProgress.forEach { (key, value) -> stored.put(key, value.toDouble()) }
        prefs.edit().putString("series_reading_progress", stored.toString()).apply()
    }
    val readingHistory = remember {
        mutableStateMapOf<String, ReadingHistoryEntry>().apply {
            runCatching {
                val stored = JSONObject(prefs.getString("reading_history", "{}"))
                stored.keys().forEach { key ->
                    val item = stored.optJSONObject(key) ?: return@forEach
                    val chapterId = item.optString("chapterId")
                    if (chapterId.isNotBlank()) put(key, ReadingHistoryEntry(
                        chapterId = chapterId,
                        chapterName = item.optString("chapterName", "Chapter"),
                        pageNumber = item.optInt("pageNumber", 1).coerceAtLeast(1),
                        pageCount = item.optInt("pageCount", 1).coerceAtLeast(1),
                        updatedAt = item.optLong("updatedAt", 0L)
                    ))
                }
            }
        }
    }
    LaunchedEffect(readingHistory.toMap()) {
        val stored = JSONObject()
        readingHistory.forEach { (key, entry) ->
            stored.put(key, JSONObject()
                .put("chapterId", entry.chapterId)
                .put("chapterName", entry.chapterName)
                .put("pageNumber", entry.pageNumber)
                .put("pageCount", entry.pageCount)
                .put("updatedAt", entry.updatedAt))
        }
        prefs.edit().putString("reading_history", stored.toString()).apply()
    }
    val openSeries: (Series) -> Unit = { item ->
        navigationStack.add(currentNavigationState())
        selectedSource = null
        recentlyRead.removeAll { it.id == item.id && it.sourceId == item.sourceId }
        recentlyRead.add(0, item)
        selected = item
    }
    var loading by remember { mutableStateOf(false) }
    var showIntro by remember { mutableStateOf(launchIntro && !prefs.getBoolean("intro_seen_once", false)) }
    var showWelcome by remember { mutableStateOf(!prefs.getBoolean("welcome_seen", false) && (prefs.getBoolean("intro_seen_once", false) || !launchIntro)) }
    LaunchedEffect(showIntro, showWelcome) {
        if (!showIntro && !showWelcome && shouldShowWhatsNew) {
            delay(420)
            showWhatsNew = true
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadingChapterKey by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    val downloadedChapterKeys = remember {
        mutableStateListOf<String>().apply { addAll(OfflineChapterStore.downloadedKeys(context)) }
    }

    BackHandler(enabled = showIntro || showWelcome || showAbout || showWhatsNew || navigationStack.isNotEmpty() || tabBackStack.isNotEmpty() || selectedChapter != null || selectedSource != null || selected != null || tab != "Home") {
        when {
            showIntro -> { showIntro = false; prefs.edit().putBoolean("intro_seen_once", true).apply(); if (!prefs.getBoolean("welcome_seen", false)) showWelcome = true }
            showWelcome -> { showWelcome = false; prefs.edit().putBoolean("welcome_seen", true).apply() }
            showAbout -> showAbout = false
            showWhatsNew -> { showWhatsNew = false; prefs.edit().putString("seen_whats_new", whatsNewVersion).apply() }
            else -> goBack()
        }
    }

    val routeKey = when {
        selectedChapter != null -> "reader:" + selected?.sourceId + ":" + selectedChapter?.id
        selectedSource != null -> "source:" + selectedSource?.id
        selected != null -> "detail:" + selected?.sourceId + ":" + selected?.id
        else -> "tab:" + tab
    }
    var routeVisible by remember(routeKey) { mutableStateOf(false) }
    LaunchedEffect(routeKey) { routeVisible = true }
    val routeAlpha by animateFloatAsState(
        if (routeVisible) 1f else 0f,
        tween(230, easing = FastOutSlowInEasing),
        label = "noir-route-fade"
    )
    val routeScale by animateFloatAsState(
        if (routeVisible) 1f else 0.982f,
        spring(dampingRatio = 0.88f, stiffness = 280f),
        label = "noir-route-scale"
    )
    val routeOffset by animateFloatAsState(
        if (routeVisible) 0f else 16f,
        tween(280, easing = FastOutSlowInEasing),
        label = "noir-route-slide"
    )

    LaunchedEffect(query, tab, activeSourceIds) {
        if (selected == null && selectedChapter == null && (tab == "Discover" || tab == "Home")) {
            hasMoreCatalogue = false
            loadingMoreCatalogue = false
            delay(if (query.isBlank()) 100 else 450)
            loading = true
            error = null
            try {
                catalogue = emptyList()
                cataloguePage = 0
                hasMoreCatalogue = false
                val combined = mutableListOf<Series>()
                val failures = mutableListOf<String>()
                for (provider in activeSources) {
                    try {
                        combined.addAll(if (query.isBlank()) provider.popular(0) else provider.search(query, 0))
                    } catch (providerError: Exception) {
                        failures.add("${provider.name}: ${providerError.message ?: "unavailable"}")
                    }
                }
                catalogue = combined.distinctBy { "${it.sourceId}:${it.id}" }
                hasMoreCatalogue = combined.isNotEmpty()
                if (catalogue.isEmpty() && failures.isNotEmpty()) error = failures.first()
                if (activeSources.isEmpty()) error = "Enable at least one source in the Source library."
            } catch (e: Exception) {
                error = e.message ?: "Could not load catalogue. Check your connection."
            } finally { loading = false }
        }
    }
    val loadMoreCatalogue: () -> Unit = {
        if (!loading && !loadingMoreCatalogue && hasMoreCatalogue) {
            scope.launch {
                loadingMoreCatalogue = true
                val nextPage = cataloguePage + 1
                val combined = mutableListOf<Series>()
                val failures = mutableListOf<String>()
                for (provider in activeSources) {
                    try {
                        combined.addAll(if (query.isBlank()) provider.popular(nextPage) else provider.search(query, nextPage))
                    } catch (providerError: Exception) {
                        failures.add("${provider.name}: ${providerError.message ?: "unavailable"}")
                    }
                }
                val merged = (catalogue + combined).distinctBy { "${it.sourceId}:${it.id}" }
                catalogue = merged
                cataloguePage = nextPage
                hasMoreCatalogue = combined.isNotEmpty()
                if (combined.isEmpty() && failures.isNotEmpty()) error = failures.first()
                loadingMoreCatalogue = false
            }
        }
    }

    LaunchedEffect(selected?.id, selected?.sourceId) {
        val item = selected ?: return@LaunchedEffect
        chapters = emptyList()
        loading = true
        error = null
        try { chapters = SourceRegistry.byId(item.sourceId)?.chapters(item).orEmpty() }
        catch (e: Exception) { error = e.message ?: "Could not load chapters." }
        finally { loading = false }
    }
    LaunchedEffect(pendingResumeKey, selected?.id, selected?.sourceId, chapters) {
        val item = selected ?: return@LaunchedEffect
        val key = "${item.sourceId}:${item.id}"
        if (pendingResumeKey != key || chapters.isEmpty()) return@LaunchedEffect
        val savedChapterId = readingHistory[key]?.chapterId
        val chapterToResume = chapters.firstOrNull { it.id == savedChapterId }
        if (chapterToResume != null) selectedChapter = chapterToResume
        pendingResumeKey = null
    }
    LaunchedEffect(selectedChapter?.id) {
        val chapter = selectedChapter ?: return@LaunchedEffect
        pages = emptyList()
        loading = true
        error = null
        try {
            val currentSeries = selected
            val chapterKey = currentSeries?.let { OfflineChapterStore.key(it, chapter) }
            val offlinePages = currentSeries?.let { OfflineChapterStore.localPages(context, it, chapter) }
            val preparedPages = chapterKey?.let { preloadedChapterPages.remove(it) }
            pages = offlinePages ?: preparedPages ?: SourceRegistry.byId(currentSeries?.sourceId ?: "")?.pages(chapter)?.pages.orEmpty()
        }
        catch (e: Exception) { error = e.message ?: "Could not load chapter pages." }
        finally { loading = false }
    }
    LaunchedEffect(selected?.id, selected?.sourceId, selectedChapter?.id, chapters) {
        val item = selected ?: return@LaunchedEffect
        val current = selectedChapter ?: return@LaunchedEffect
        val currentIndex = chapters.indexOfFirst { it.id == current.id }
        val next = chapters.getOrNull(currentIndex + 1) ?: return@LaunchedEffect
        val key = OfflineChapterStore.key(item, next)
        if (key in preloadedChapterPages) return@LaunchedEffect
        if (OfflineChapterStore.localPages(context, item, next) != null) return@LaunchedEffect
        val source = SourceRegistry.byId(item.sourceId) ?: return@LaunchedEffect
        runCatching { source.pages(next).pages }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { preloadedChapterPages[key] = it }
    }
    val nextChapterForReader = selectedChapter?.let { current ->
        val index = chapters.indexOfFirst { it.id == current.id }
        chapters.getOrNull(index + 1)
    }
    val isNextChapterPreloaded = selected?.let { item ->
        nextChapterForReader?.let { preloadedChapterPages.containsKey(OfflineChapterStore.key(item, it)) }
    } ?: false

    MaterialTheme(colorScheme = darkColorScheme(background = Black, surface = Panel, primary = Silver, onBackground = White, onSurface = White)) {
        Surface(Modifier.fillMaxSize(), color = Black) {
          Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = routeAlpha
                scaleX = routeScale
                scaleY = routeScale
                translationY = routeOffset * density
            }) {
            key(routeKey) {
            when {
                selectedChapter != null -> ReaderScreen(
                    selected, selectedChapter!!, chapters, pages, loading, error,
                    readingProgress[selectedChapter!!.id]
                        ?: readingHistory["${selected!!.sourceId}:${selected!!.id}"]
                            ?.takeIf { it.chapterId == selectedChapter!!.id }
                            ?.let { (it.pageNumber - 1).coerceAtLeast(0) }
                        ?: 0,
                    nextChapterReady = isNextChapterPreloaded,
                    readerMode = readerMode,
                    onModeChange = { mode -> readerMode = mode; prefs.edit().putString("reader_mode", mode).apply() },
                    onProgress = { page ->
                        val activeChapter = selectedChapter
                        if (activeChapter != null) readingProgress[activeChapter.id] = page
                        selected?.let { series ->
                            if (pages.isNotEmpty() && activeChapter != null) {
                                val key = "${series.sourceId}:${series.id}"
                                seriesProgress[key] = ((page + 1).toFloat() / pages.size).coerceIn(0f, 1f)
                                readingHistory[key] = ReadingHistoryEntry(
                                    chapterId = activeChapter.id,
                                    chapterName = activeChapter.name,
                                    pageNumber = (page + 1).coerceAtMost(pages.size),
                                    pageCount = pages.size,
                                    updatedAt = System.currentTimeMillis()
                                )
                            }
                        }
                    },
                    onNextChapter = {
                        val chapterIndex = chapters.indexOfFirst { it.id == selectedChapter!!.id }
                        chapters.getOrNull(chapterIndex + 1)?.let { selectedChapter = it }
                    },
                    onBack = { goBack() }
                )
                selectedSource != null -> SourceBrowseScreen(
                    source = selectedSource!!,
                    enabled = selectedSource!!.id in activeSourceIds,
                    onToggleEnabled = {
                        val id = selectedSource!!.id
                        if (id in enabledSourceIds) enabledSourceIds.remove(id) else enabledSourceIds.add(id)
                    },
                    onBack = { goBack() },
                    onOpen = { item -> openSeries(item) },
                    matureContentEnabled = matureContentEnabled
                )
                selected != null -> DetailScreen(
                    selected!!, chapters, saved.any { it.id == selected!!.id && it.sourceId == selected!!.sourceId },
                    bookmarkedChapterKeys.toSet(), downloadedChapterKeys.toSet(),
                    downloadingChapterKey, downloadProgress, loading, error,
                    onBack = { goBack() },
                    onSave = {
                        val item = selected!!
                        if (saved.any { it.id == item.id && it.sourceId == item.sourceId }) saved.removeAll { it.id == item.id && it.sourceId == item.sourceId } else saved.add(item)
                    },
                    onToggleChapterBookmark = { chapter ->
                        val key = "${selected!!.sourceId}:${chapter.id}"
                        if (key in bookmarkedChapterKeys) bookmarkedChapterKeys.remove(key) else bookmarkedChapterKeys.add(key)
                    },
                    onChapter = { chapter -> navigationStack.add(currentNavigationState()); selectedChapter = chapter },
                    onDownloadChapter = { chapter ->
                        val item = selected!!
                        val key = OfflineChapterStore.key(item, chapter)
                        if (key !in downloadedChapterKeys && downloadingChapterKey == null) {
                            downloadingChapterKey = key
                            downloadProgress = 0
                            scope.launch {
                                try {
                                    OfflineChapterStore.download(context.applicationContext, item, chapter) { done, total ->
                                        downloadProgress = ((done * 100f) / total).toInt()
                                    }
                                    if (key !in downloadedChapterKeys) downloadedChapterKeys.add(key)
                                } catch (e: Exception) {
                                    error = "Download failed: ${e.message ?: "Check your connection."}"
                                } finally {
                                    downloadingChapterKey = null
                                    downloadProgress = 0
                                }
                            }
                        }
                    }
                )
                tab == "Sources" -> SourcesScreen(
                    enabledSourceIds = activeSourceIds.toSet(),
                    onToggleEnabled = { sourceId ->
                        if (sourceId in enabledSourceIds) enabledSourceIds.remove(sourceId)
                        else enabledSourceIds.add(sourceId)
                    },
                    onOpenSource = { source -> navigationStack.add(currentNavigationState()); selectedSource = source },
                    onTab = { navigateTab(it) }
                )
                tab == "Library" -> LibraryScreen(
                    saved.toList(), recentlyRead.toList(), libraryCategories.toMap(), seriesProgress.toMap(), readingHistory.toMap(),
                    onCategoryChange = { series, category ->
                        val key = "${series.sourceId}:${series.id}"
                        if (category == "Reading") libraryCategories.remove(key) else libraryCategories[key] = category
                    },
                    onOpen = openSeries,
                    onResume = { item ->
                        pendingResumeKey = "${item.sourceId}:${item.id}"
                        openSeries(item)
                    },
                    onTab = { navigateTab(it) }
                )
                tab == "Profile" -> ProfileScreen(introSound, launchIntro, matureContentEnabled,
                    { introSound = it; prefs.edit().putBoolean("intro_sound", it).apply() },
                    { launchIntro = it; prefs.edit().putBoolean("launch_intro", it).apply() },
                    { matureContentEnabled = it; prefs.edit().putBoolean("mature_content_18_plus", it).apply() },
                    { showAbout = true }, { showWhatsNew = true }, { navigateTab(it) })
                tab == "Home" -> HomeScreen(catalogue, loading, error, recentlyRead.toList(), saved.toList(), seriesProgress.toMap(), readingHistory.toMap(), openSeries, { item -> pendingResumeKey = "${item.sourceId}:${item.id}"; openSeries(item) }, { navigateTab(it) })
                else -> CatalogueScreen("Discover", query, { query = it }, catalogue, loading, error, hasMoreCatalogue, loadingMoreCatalogue, loadMoreCatalogue, openSeries, discoveryCompactGrid, { discoveryCompactGrid = it }, matureContentEnabled, { navigateTab(it) })
            }
            }
            }
            if (showWhatsNew) WhatsNewDialog(onDismiss = {
                showWhatsNew = false
                prefs.edit().putString("seen_whats_new", whatsNewVersion).apply()
            })
            if (showAbout) AlertDialog(
                onDismissRequest = { showAbout = false },
                title = { Text("About NOIR", color = White) },
                text = { Text("NOIR 1.5.0 includes the Kotatsu community parser library under GPL-3.0. NO WARRANTY. You may copy and modify this app under that license. Full license and corresponding source: github.com/syusyantho-dotcom/Noire (NOIR/LICENSE and THIRD_PARTY_NOTICES.md).", color = Muted) },
                confirmButton = { TextButton(onClick = { showAbout = false }) { Text("CLOSE", color = Silver) } },
                containerColor = Panel
            )
            if (showIntro) IntroOverlay(introSound) {
                showIntro = false
                prefs.edit().putBoolean("intro_seen_once", true).apply()
                if (!prefs.getBoolean("welcome_seen", false)) showWelcome = true
            }
            if (showWelcome && !showIntro) NoirWelcomeWalkthrough(
                onFinish = {
                    showWelcome = false
                    prefs.edit().putBoolean("welcome_seen", true).apply()
                }
            )
          }
        }
    }
}

@Composable
private fun CatalogueScreen(
    tab: String, query: String, onQuery: (String) -> Unit, catalogue: List<Series>,
    loading: Boolean, error: String?, hasMore: Boolean, loadingMore: Boolean, onLoadMore: () -> Unit,
    onOpen: (Series) -> Unit, compactGrid: Boolean, onCompactGridChange: (Boolean) -> Unit,
    matureContentEnabled: Boolean, onTab: (String) -> Unit
) {
    var sortMode by remember { mutableStateOf("FOR YOU") }
    var sourceFilter by remember { mutableStateOf("ALL SOURCES") }
    val availableSources = remember(catalogue) { catalogue.map { it.sourceName }.distinct().sorted() }
    LaunchedEffect(availableSources) {
        if (sourceFilter != "ALL SOURCES" && sourceFilter !in availableSources) sourceFilter = "ALL SOURCES"
    }
    val sourceFiltered = remember(catalogue, sourceFilter) {
        if (sourceFilter == "ALL SOURCES") catalogue else catalogue.filter { it.sourceName == sourceFilter }
    }
    val sortedCatalogue = remember(sourceFiltered, sortMode) {
        when (sortMode) {
            "A–Z" -> sourceFiltered.sortedBy { it.title.lowercase() }
            "SOURCE" -> sourceFiltered.sortedBy { it.sourceName.lowercase() + it.title.lowercase() }
            else -> sourceFiltered
        }
    }
    val featured = if (query.isBlank() && sortMode == "FOR YOU" && sourceFilter == "ALL SOURCES") sortedCatalogue.firstOrNull() else null
    val remaining = if (featured != null) sortedCatalogue.drop(1) else sortedCatalogue
    val columns = if (compactGrid) 3 else 2

    Column(Modifier.fillMaxSize().background(Black).statusBarsPadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 17.dp, end = 17.dp, top = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(17.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("NOIR  /  YOUR READING UNIVERSE", color = Color(0xFFB8A9D0), fontSize = 8.sp, letterSpacing = 2.1.sp)
                        Spacer(Modifier.height(5.dp))
                        Text("Discover", color = White, fontSize = 35.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.clip(RoundedCornerShape(30.dp)).background(Color(0xFF211D28))
                        .border(1.dp, Color(0xFF403747), RoundedCornerShape(30.dp)).padding(horizontal = 11.dp, vertical = 8.dp)) {
                        Text(if (matureContentEnabled) "18+ ON" else "SAFE MODE", color = Color(0xFFD8CBE5), fontSize = 8.sp, letterSpacing = .8.sp)
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search stories, manga, manhwa…", color = Muted, fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Silver) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, null, tint = Muted) } },
                    singleLine = true, shape = RoundedCornerShape(21.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF8B7A9B), unfocusedBorderColor = Color(0xFF39333F),
                        focusedContainerColor = Color(0xCC19171E), unfocusedContainerColor = Color(0xCC141318),
                        focusedTextColor = White, unfocusedTextColor = White, cursorColor = Silver
                    )
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("EXPLORE BY SOURCE", color = Muted, fontSize = 8.sp, letterSpacing = 1.2.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(listOf("ALL SOURCES") + availableSources) { sourceName ->
                            val active = sourceFilter == sourceName
                            Box(Modifier.clip(RoundedCornerShape(30.dp))
                                .background(if (active) Color(0xFF342B3D) else Color(0xFF151419))
                                .border(1.dp, if (active) Color(0xFF665572) else Color(0xFF302C35), RoundedCornerShape(30.dp))
                                .clickable { sourceFilter = sourceName }.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                Text(sourceName, color = if (active) Color(0xFFE9DFF2) else Muted, fontSize = 8.sp, maxLines = 1, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (query.isBlank()) "MADE FOR YOUR NEXT OBSESSION" else "YOUR SEARCH",
                            color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.25.sp)
                        Spacer(Modifier.height(3.dp))
                        Text("A universe of stories, gathered in one place", color = Muted, fontSize = 9.sp)
                    }
                    Text("${sortedCatalogue.size} TITLES", color = Color(0xFFBDAFCB), fontSize = 8.sp, letterSpacing = 1.sp)
                }
            }
            if (loading && catalogue.isEmpty()) {
                item { LoadingState("Curating your discovery feed…") }
            } else if (error != null && catalogue.isEmpty()) {
                item { ErrorState(error) }
            } else if (catalogue.isEmpty()) {
                item { EmptyState("No titles found", "Try another title or check your connection.") }
            } else {
                if (featured != null) {
                    item {
                        Box(Modifier.fillMaxWidth().height(224.dp).clip(RoundedCornerShape(25.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF30263A), Color(0xFF111014))))
                            .noirPressScale { onOpen(featured) }) {
                            SeriesCover(featured, Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                                listOf(Color(0x08070709), Color(0x22070709), Color(0xF5070709)))))
                            Row(Modifier.align(Alignment.TopStart).padding(14.dp).clip(RoundedCornerShape(30.dp))
                                .background(Color(0xA51A1720)).border(1.dp, Color(0x667F7390), RoundedCornerShape(30.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFD9C8F0)))
                                Spacer(Modifier.width(7.dp))
                                Text("FEATURED DISCOVERY", color = Color(0xFFE7DDED), fontSize = 8.sp, letterSpacing = 1.sp)
                            }
                            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(17.dp)) {
                                Text(featured.sourceName.uppercase(), color = Color(0xFFD0C4E2), fontSize = 8.sp, letterSpacing = 1.5.sp)
                                Spacer(Modifier.height(5.dp))
                                Text(featured.title, color = White, fontSize = 22.sp, lineHeight = 26.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("ENTER THE STORY", color = Color(0xFFF0EAF5), fontSize = 8.sp,
                                        letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Rounded.ArrowOutward, null, tint = Color(0xFFF0EAF5), modifier = Modifier.size(13.dp))
                                }
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("THE COLLECTION", color = White, fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                                Text(if (compactGrid) "Every story, at a glance" else "Curated finds, waiting to be opened",
                                    color = Muted, fontSize = 9.sp)
                            }
                            Row(Modifier.clip(RoundedCornerShape(13.dp)).background(Color(0xFF17151B))
                                .border(1.dp, Color(0xFF39323F), RoundedCornerShape(13.dp)).padding(3.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(width = 38.dp, height = 34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (!compactGrid) Color(0xFF393044) else Color.Transparent)
                                    .clickable { onCompactGridChange(false) }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.GridView, null,
                                        tint = if (!compactGrid) Color(0xFFE8DDF2) else Muted, modifier = Modifier.size(18.dp))
                                }
                                Box(Modifier.size(width = 38.dp, height = 34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (compactGrid) Color(0xFF393044) else Color.Transparent)
                                    .clickable { onCompactGridChange(true) }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.ViewModule, null,
                                        tint = if (compactGrid) Color(0xFFE8DDF2) else Muted, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("FOR YOU", "A–Z", "SOURCE").forEach { mode ->
                                val selected = sortMode == mode
                                Box(Modifier.clip(RoundedCornerShape(30.dp))
                                    .background(if (selected) Color(0xFF342B3D) else Color(0xFF151419))
                                    .border(1.dp, if (selected) Color(0xFF665572) else Color(0xFF302C35), RoundedCornerShape(30.dp))
                                    .clickable { sortMode = mode }.padding(horizontal = 13.dp, vertical = 8.dp)) {
                                    Text(mode, color = if (selected) Color(0xFFE9DFF2) else Muted,
                                        fontSize = 8.sp, letterSpacing = .8.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                items(remaining.chunked(columns), key = { row -> row.joinToString("|") { "${it.sourceId}:${it.id}" } }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compactGrid) 8.dp else 11.dp),
                        verticalAlignment = Alignment.Top) {
                        row.forEachIndexed { index, series ->
                            Box(Modifier.weight(1f)) {
                                DiscoveryCoverTile(series, compactGrid, !compactGrid && ((index + remaining.indexOf(series)) % 3 == 1)) {
                                    onOpen(series)
                                }
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (hasMore || loadingMore) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Text(if (loadingMore) "LOADING MORE…" else "LOAD MORE STORIES  ↓",
                            color = Color(0xFFE3D9EA), fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(30.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFF342A3D), Color(0xFF1C1921))))
                                .border(1.dp, Color(0xFF55465F), RoundedCornerShape(30.dp))
                                .clickable(enabled = !loadingMore, onClick = onLoadMore).padding(horizontal = 20.dp, vertical = 13.dp))
                    }
                }
            }
        }
        BottomNav("Discover", onTab)
    }
}

@Composable
private fun DiscoveryCoverTile(series: Series, compact: Boolean, alternateHeight: Boolean, onClick: () -> Unit) {
    val imageRatio = when {
        compact -> 0.72f
        alternateHeight -> 0.78f
        else -> 0.62f
    }
    Column(Modifier.fillMaxWidth().animateContentSize(tween(260))
        .clip(RoundedCornerShape(if (compact) 14.dp else 19.dp))
        .background(Color(0xFF121116)).noirPressScale(onClick).padding(bottom = if (compact) 8.dp else 11.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(imageRatio).clip(RoundedCornerShape(if (compact) 13.dp else 17.dp))
            .background(Color(0xFF1C1A21))) {
            SeriesCover(series, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x24000000)))))
            if (!compact) {
                SourceRegistry.byId(series.sourceId)?.let { source ->
                    Box(Modifier.align(Alignment.TopEnd).padding(7.dp).clip(CircleShape)
                        .background(Color(0xB51A1720)).border(1.dp, Color(0x557F7390), CircleShape).padding(5.dp)) {
                        SourceIcon(source, Modifier.size(18.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(if (compact) 7.dp else 10.dp))
        Text(series.title, color = White, fontSize = if (compact) 10.sp else 12.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 2,
            lineHeight = if (compact) 12.sp else 15.sp, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = if (compact) 7.dp else 10.dp))
        if (!compact) {
            Spacer(Modifier.height(7.dp))
            Text(series.sourceName, color = Color(0xFFB8B0C2), fontSize = 8.sp,
                letterSpacing = .35.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp))
        }
    }
}

@Composable
private fun HomeScreen(catalogue: List<Series>, loading: Boolean, error: String?, recentlyRead: List<Series>, saved: List<Series>, progressBySeries: Map<String, Float>, readingHistory: Map<String, ReadingHistoryEntry>, onOpen: (Series) -> Unit, onResume: (Series) -> Unit, onTab: (String) -> Unit) {
    val featuredStories = catalogue.take(5)
    val heroPagerState = rememberPagerState(pageCount = { featuredStories.size.coerceAtLeast(1) })
    LaunchedEffect(featuredStories.size) {
        if (featuredStories.size > 1) {
            while (true) {
                delay(6500)
                heroPagerState.animateScrollToPage((heroPagerState.currentPage + 1) % featuredStories.size)
            }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 15.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(25.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(19.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            NoirLogoMark(34.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("NOIR", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.2.sp)
                                Text("YOUR READING UNIVERSE", color = Muted, fontSize = 7.sp, letterSpacing = 1.35.sp)
                            }
                        }
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color(0xFF302A38), Color(0xFF17151B))))
                                .border(1.dp, Color(0xFF49414F), CircleShape)
                                .clickable { onTab("Profile") },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Person, contentDescription = "Profile", tint = Color(0xFFE8E0EE), modifier = Modifier.size(19.dp))
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("A QUIETER KIND OF ESCAPE", color = Color(0xFFB8A9D0), fontSize = 8.sp, letterSpacing = 2.1.sp)
                        Text("Find your\nnext world.", color = White, fontSize = 34.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp)
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
                            .background(Color(0xFF151419))
                            .border(1.dp, Color(0xFF302C35), RoundedCornerShape(17.dp))
                            .clickable { onTab("Discover") }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFFB8A9C5), modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Search stories, worlds, characters", color = Color(0xFF85818B), fontSize = 10.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.ArrowForward, contentDescription = null, tint = Color(0xFF77717E), modifier = Modifier.size(15.dp))
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SELECTED FOR YOU", color = Color(0xFFB8A9D0), fontSize = 8.sp, letterSpacing = 1.8.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${String.format("%02d", heroPagerState.currentPage + 1)} / ${String.format("%02d", featuredStories.size.coerceAtLeast(1))}", color = Color(0xFF77717E), fontSize = 8.sp, letterSpacing = 1.sp)
                    }
                    HorizontalPager(
                        state = heroPagerState,
                        modifier = Modifier.fillMaxWidth(),
                        pageSpacing = 12.dp,
                        contentPadding = PaddingValues(end = 22.dp)
                    ) { page ->
                        val story = featuredStories.getOrNull(page)
                        Box(
                            Modifier.fillMaxWidth().height(326.dp)
                                .graphicsLayer {
                                    val offset = (heroPagerState.currentPage - page) + heroPagerState.currentPageOffsetFraction
                                    val distance = kotlin.math.abs(offset).coerceIn(0f, 1f)
                                    alpha = 1f - distance * 0.18f
                                    scaleX = 1f - distance * 0.035f
                                    scaleY = 1f - distance * 0.035f
                                }
                                .clip(RoundedCornerShape(28.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFF30283B), Color(0xFF111014))))
                                .clickable { story?.let(onOpen) }
                        ) {
                            story?.let { SeriesCover(it, Modifier.fillMaxSize()) }
                            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF5070709), Color(0xA8070709), Color(0x12070709)))))
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x12070709), Color(0x08070709), Color(0xF5070709)))))
                            Column(Modifier.align(Alignment.TopStart).padding(18.dp)) {
                                Row(
                                    Modifier.clip(RoundedCornerShape(30.dp))
                                        .background(Color(0x773A3347))
                                        .border(1.dp, Color(0x557F7397), RoundedCornerShape(30.dp))
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFD6C4F1)))
                                    Spacer(Modifier.width(7.dp))
                                    Text("NOIR / EDITOR'S PICK", color = Color(0xFFE5DDF0), fontSize = 8.sp, letterSpacing = 1.2.sp)
                                }
                            }
                            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp)) {
                                Text(
                                    "FEATURED STORY  /  ${String.format("%02d", page + 1)}",
                                    color = Color(0xFFD0C4E2), fontSize = 8.sp, letterSpacing = 2.sp
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    story?.title ?: "A universe beyond the page",
                                    color = White, fontSize = 26.sp, lineHeight = 30.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(7.dp))
                                Text(
                                    story?.description?.takeIf { it.isNotBlank() }?.let {
                                        if (it.length > 105) it.take(105).substringBeforeLast(" ") + "…" else it
                                    } ?: "One story. A thousand places to disappear.",
                                    color = Color(0xFFD0CDD4), fontSize = 10.sp, lineHeight = 15.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(15.dp))
                                Row(
                                    Modifier.clip(RoundedCornerShape(30.dp))
                                        .background(Color(0xFFE9E4EF))
                                        .clickable { story?.let(onOpen) }
                                        .padding(horizontal = 15.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("ENTER THE STORY", color = Color(0xFF17131D), fontSize = 8.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Rounded.ArrowOutward, null, tint = Color(0xFF17131D), modifier = Modifier.size(13.dp))
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(featuredStories.size.coerceAtLeast(1)) { index ->
                            Box(
                                Modifier.padding(horizontal = 3.dp)
                                    .width(if (heroPagerState.currentPage == index) 19.dp else 5.dp)
                                    .height(5.dp).animateContentSize(tween(260))
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (heroPagerState.currentPage == index) Color(0xFFD8C5F0) else Color(0xFF48434E))
                            )
                        }
                    }
                }
            }
            if (recentlyRead.isNotEmpty()) {
                item {
                    val latest = recentlyRead.first()
                    val history = readingHistory["${latest.sourceId}:${latest.id}"]
                    HomeContinueCard(latest, history, progressBySeries["${latest.sourceId}:${latest.id}"] ?: 0f, onResume = { onResume(latest) }, onOpenDetail = { onOpen(latest) })
                }
                if (recentlyRead.size > 1) item {
                    HomeSeriesShelf("YOUR READING LIST", "MORE FROM YOUR JOURNEY", recentlyRead.drop(1).take(9), "CONTINUE", progressBySeries, onResume)
                }
            }
            item {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("THE CURRENT", color = Color(0xFFB8A9D0), fontSize = 8.sp, letterSpacing = 2.2.sp)
                        Spacer(Modifier.height(5.dp))
                        Text("Stories in motion", color = White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("ALL TITLES  ↗", color = Silver, fontSize = 8.sp, letterSpacing = 1.2.sp, modifier = Modifier.clickable { onTab("Discover") }.padding(bottom = 3.dp))
                }
            }
            when {
                loading && catalogue.isEmpty() -> item { LoadingState("Preparing your reading room…") }
                error != null && catalogue.isEmpty() -> item { ErrorState(error) }
                catalogue.isEmpty() -> item { EmptyState("The shelves are quiet", "Open Discover to explore the catalogue.") }
                else -> item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 4.dp)) {
                        items(catalogue.take(12), key = { "${it.sourceId}-${it.id}" }) { series ->
                            Column(Modifier.width(132.dp).noirPressScale { onOpen(series) }) {
                                Box(Modifier.fillMaxWidth().height(178.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF201D26))) {
                                    SeriesCover(series, Modifier.fillMaxSize())
                                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x22000000)))))
                                }
                                Spacer(Modifier.height(9.dp))
                                Text(series.title, color = White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 14.sp, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(5.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SourceRegistry.byId(series.sourceId)?.let { source ->
                                        SourceIcon(source, Modifier.size(15.dp))
                                        Spacer(Modifier.width(5.dp))
                                    }
                                    Text(series.sourceName, color = Muted, fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            if (saved.isNotEmpty()) item {
                HomeSeriesShelf("YOUR COLLECTION", "SAVED TO YOUR LIBRARY", saved.take(10), "VIEW TITLE", progressBySeries, onOpen)
            }
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFF262131), Color(0xFF111014)))).border(1.dp, Color(0xFF393342), RoundedCornerShape(22.dp)).clickable { onTab("Discover") }.padding(19.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("BEYOND THE LAST PAGE", color = Color(0xFFD0C4E2), fontSize = 8.sp, letterSpacing = 1.8.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("Find your next obsession.", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("Explore the NOIR catalogue", color = Muted, fontSize = 10.sp)
                    }
                    Icon(Icons.Rounded.ArrowForward, null, tint = Silver, modifier = Modifier.size(20.dp))
                }
            }
            item { Text("NOIR  •  MANGA  •  MANHWA  •  MORE", color = Color(0xFF625D69), fontSize = 8.sp, letterSpacing = 2.sp, modifier = Modifier.fillMaxWidth().padding(top = 1.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        BottomNav("Home", onTab)
    }
}


@Composable
private fun NoirGlowButton(
    label: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "noir-glow-border")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing)),
        label = "noir-glow-rotation"
    )
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF08070B))
            .drawBehind {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(
                    color = Color(0xFF8C55FF).copy(alpha = 0.13f),
                    cornerRadius = radius,
                    style = Stroke(width = 5.dp.toPx())
                )
                rotateDraw(rotation) {
                    drawRoundRect(
                        brush = Brush.sweepGradient(
                            listOf(
                                Color.Transparent,
                                Color(0xFF713BFF).copy(alpha = 0.28f),
                                Color(0xFFF2E8FF),
                                Color(0xFF9A45FF),
                                Color.Transparent
                            ),
                            center = center
                        ),
                        cornerRadius = radius,
                        style = Stroke(width = 1.35.dp.toPx())
                    )
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 15.dp, vertical = if (compact) 8.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        if (!compact) Icon(Icons.Rounded.PlayArrow, null, tint = Color(0xFFE9DFFF), modifier = Modifier.size(15.dp))
        Text(
            label,
            color = White,
            fontSize = if (compact) 7.sp else 8.sp,
            letterSpacing = 1.0.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Icon(Icons.Rounded.ArrowForward, null, tint = Color(0xFFD6C5F2), modifier = Modifier.size(13.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeContinueCard(series: Series, history: ReadingHistoryEntry?, overallProgress: Float, onResume: () -> Unit, onOpenDetail: () -> Unit) {
    val pageProgress = history?.let { (it.pageNumber.toFloat() / it.pageCount.coerceAtLeast(1)).coerceIn(0f, 1f) } ?: overallProgress.coerceIn(0f, 1f)
    Column(Modifier.animateContentSize(tween(280)), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("YOUR STORY IS WAITING", color = Color(0xFFB8A9D0), fontSize = 8.sp, letterSpacing = 1.9.sp)
                Text("Continue reading", color = White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.35).sp)
            }
            NoirGlowButton("RESUME", Modifier.padding(bottom = 2.dp), compact = true, onClick = onResume)
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF24212A), Color(0xFF121116), Color(0xFF19161E)))).border(1.dp, Color(0xFF3A3541), RoundedCornerShape(24.dp)).combinedClickable(onClick = onResume, onLongClick = onOpenDetail).padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(88.dp).height(116.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFF28232E))) {
                SeriesCover(series, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x55000000)))))
                Box(Modifier.align(Alignment.TopStart).padding(7.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xCC111015)).padding(horizontal = 7.dp, vertical = 4.dp)) { Text("READING", color = Color(0xFFE4D8F0), fontSize = 6.sp, letterSpacing = .8.sp, fontWeight = FontWeight.Bold) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("PICK UP WHERE YOU LEFT OFF", color = Color(0xFFB8A9D0), fontSize = 7.sp, letterSpacing = 1.1.sp, maxLines = 1)
                Text(series.title, color = White, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(history?.chapterName?.takeIf { it.isNotBlank() } ?: "Your reading journey", color = Color(0xFFC3BEC9), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(history?.let { "PAGE ${it.pageNumber.coerceAtMost(it.pageCount)} / ${it.pageCount}" } ?: "READY WHEN YOU ARE", color = Muted, fontSize = 8.sp, letterSpacing = .7.sp)
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF46414C))) { Box(Modifier.fillMaxWidth(pageProgress).fillMaxHeight().background(Color(0xFFD8C5F0))) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("CONTINUE", color = Color(0xFFE8DDF3), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp); Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.ArrowForward, null, tint = Color(0xFFE8DDF3), modifier = Modifier.size(13.dp)) }
            }
        }
    }
}
@Composable
private fun HomeSeriesShelf(
    title: String,
    eyebrow: String,
    seriesItems: List<Series>,
    actionLabel: String,
    progressBySeries: Map<String, Float>,
    onOpen: (Series) -> Unit
) {
    Column(Modifier.animateContentSize(tween(280)), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(eyebrow, color = Color(0xFFB8A9D0), fontSize = 8.sp, letterSpacing = 2.sp)
                Text(title, color = White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.35).sp)
            }
            Box(
                Modifier.clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF19171E))
                    .border(1.dp, Color(0xFF35303C), RoundedCornerShape(20.dp))
                    .padding(horizontal = 9.dp, vertical = 6.dp)
            ) {
                Text("${seriesItems.size.toString().padStart(2, '0')} TITLES", color = Color(0xFFBEB5C9), fontSize = 7.sp, letterSpacing = 1.sp)
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 4.dp)) {
            items(seriesItems, key = { "shelf-${it.sourceId}-${it.id}" }) { series ->
                Column(
                    Modifier.width(132.dp).clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF141318)).noirPressScale { onOpen(series) }
                ) {
                    Box(Modifier.fillMaxWidth().height(178.dp).background(Color(0xFF211D27))) {
                        SeriesCover(series, Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))))
                        CoverProgressOverlay(progressBySeries["${series.sourceId}:${series.id}"] ?: 0f, Modifier.align(Alignment.BottomCenter))
                    }
                    Column(Modifier.padding(horizontal = 9.dp, vertical = 9.dp)) {
                        Text(series.title, color = White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 13.sp, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(5.dp))
                        Text(actionLabel, color = Color(0xFFC8B8D8), fontSize = 7.sp, letterSpacing = .8.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.noirPressScale(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 520f),
        label = "noir-press-scale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.clickable(
        interactionSource = interactionSource,
        indication = null, // Press feedback is provided by the spring scale animation.
        onClick = onClick
    )
}

@Composable
private fun NoirReveal(
    delayMillis: Int = 0,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(360, delayMillis = delayMillis)) +
            slideInVertically(tween(420, delayMillis = delayMillis, easing = FastOutSlowInEasing)) { it / 14 }
    ) {
        content()
    }
}

@Composable
private fun SeriesCover(series: Series, modifier: Modifier = Modifier) {
    Cover(series.coverUrl, modifier)
}

@Composable
private fun CoverProgressOverlay(progress: Float, modifier: Modifier = Modifier) {
    if (progress > 0f) {
        Column(modifier.fillMaxWidth().background(Color(0xC9070709)).padding(horizontal = 7.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CHAPTER", color = Color(0xFFE9DDF5), fontSize = 7.sp, letterSpacing = .9.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%", color = White, fontSize = 7.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x665D5665))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Color(0xFFD8C5F0)))
            }
        }
    }
}

@Composable
private fun CoverTile(series: Series, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().animateContentSize(tween(220))
        .clip(RoundedCornerShape(19.dp)).background(Color(0xFF121116))
        .noirPressScale(onClick).padding(bottom = 11.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.66f).clip(RoundedCornerShape(17.dp)).background(Color(0xFF1C1A21))) {
            SeriesCover(series, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x18000000)))))
        }
        Spacer(Modifier.height(10.dp))
        Text(series.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, lineHeight = 15.sp, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SourceRegistry.byId(series.sourceId)?.let { source ->
                SourceIcon(source, Modifier.size(22.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(series.sourceName, color = Color(0xFFB8B0C2), fontSize = 9.sp,
                letterSpacing = .45.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HomeFeatureRow(series: Series, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        SeriesCover(series, Modifier.size(65.dp, 84.dp))
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            Text(series.title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Text(series.description, color = Muted, fontSize = 9.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(series.sourceName.uppercase(), color = Color(0xFFC8BED8), fontSize = 8.sp, letterSpacing = 1.2.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
    }
}

@Composable
private fun ProfileScreen(introSound: Boolean, launchIntro: Boolean, matureContentEnabled: Boolean, onIntroSound: (Boolean) -> Unit, onLaunchIntro: (Boolean) -> Unit, onMatureContent: (Boolean) -> Unit, onAbout: () -> Unit, onWhatsNew: () -> Unit, onTab: (String) -> Unit) {
    var showMatureConfirmation by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("NOIR / PERSONAL SPACE", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
        Text("Profile", color = White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            NoirLogoMark(54.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("NOIR READER", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Your personal reading space", color = Muted, fontSize = 10.sp)
            }
            Text("1.4.0", color = Silver, fontSize = 9.sp)
        }
        Spacer(Modifier.height(26.dp))
        Text("APP SETTINGS", color = Silver, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        SettingSwitch("Intro sound", "Play the short chime when NOIR opens", introSound, onIntroSound)
        SettingSwitch("One-time intro", "Play the NOIR welcome animation once on first launch", launchIntro, onLaunchIntro)
        SettingSwitch("Mature catalogues (18+)", "Show adult-rated titles from sources that provide them", matureContentEnabled, { enabled -> if (enabled) showMatureConfirmation = true else onMatureContent(false) })
        Spacer(Modifier.height(22.dp))
        Text("INFORMATION", color = Silver, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Panel).clickable(onClick = onWhatsNew).padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Color(0xFFD6C5F2))
            Text("What’s New in NOIR", color = White, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Text("1.4.0", color = Color(0xFFD6C5F2), fontSize = 9.sp)
            Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Panel).clickable(onClick = onAbout).padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Info, null, tint = Silver)
            Text("About NOIR", color = White, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Panel).clickable { onTab("Sources") }.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Extension, null, tint = Silver)
            Text("Manage sources", color = White, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
        }
        Spacer(Modifier.height(14.dp))
        Text("Settings are saved on this device. Only implemented settings are shown here.", color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
        Spacer(Modifier.weight(1f))
        BottomNav("Profile", onTab)
    }
    if (showMatureConfirmation) AlertDialog(
        onDismissRequest = { showMatureConfirmation = false },
        title = { Text("Adults only (18+)", color = White) },
        text = { Text("Mature catalogues may contain sexually explicit material. Enable them only if you are 18 or older and it is legal for you to access this content. You can turn this off at any time.", color = Muted) },
        confirmButton = { TextButton(onClick = { onMatureContent(true); showMatureConfirmation = false }) { Text("I AM 18+", color = Silver) } },
        dismissButton = { TextButton(onClick = { showMatureConfirmation = false }) { Text("CANCEL", color = Muted) } },
        containerColor = Panel
    )
}

@Composable
private fun WhatsNewDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151419),
        shape = RoundedCornerShape(26.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF30263B)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = Color(0xFFE1D2F5), modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("NOIR  /  RELEASE NOTES", color = Color(0xFFBFAED5), fontSize = 8.sp, letterSpacing = 1.7.sp)
                        Text("What’s New", color = White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF30263B)).padding(horizontal = 9.dp, vertical = 5.dp)) {
                        Text("v1.4.0", color = Color(0xFFE0D1F3), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text("A smoother reading experience starts here.", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Normal)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
                WhatsNewItem("CINEMA CHAPTER OPENING", "Every new chapter begins with an atmospheric full-screen title reveal, artwork backdrop, and a seamless transition into reading.", "01")
                WhatsNewItem("SMART READER", "Predictive chapter loading prepares the next chapter in the background, while nearby pages are warmed in the image cache.", "02")
                WhatsNewItem("CONTINUE READING", "Your Library now remembers the exact chapter and page, with one-tap resume.", "03")
                WhatsNewItem("YOUR PROGRESS, SAVED", "Reading history and page position stay on this device, ready when you return.", "04")
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Color(0xFF211D28)).padding(12.dp)) {
                    Text("Made for the moments when one more chapter turns into five.", color = Color(0xFFD2C4E2), fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("LET’S READ", color = Color(0xFFE0D1F3), fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
        }
    )
}

@Composable
private fun WhatsNewItem(title: String, description: String, number: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.size(29.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF292330)), contentAlignment = Alignment.Center) {
            Text(number, color = Color(0xFFDCCBEF), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(description, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 10.sp)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SeriesRow(series: Series, progress: Float = 0f, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(82.dp, 112.dp).clip(RoundedCornerShape(12.dp))) {
            SeriesCover(series, Modifier.fillMaxSize())
            CoverProgressOverlay(progress, Modifier.align(Alignment.BottomCenter))
        }
        Column(Modifier.weight(1f).padding(start = 13.dp, end = 7.dp)) {
            Text(series.title, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(7.dp))
            Text(series.description, color = Muted, fontSize = 10.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(series.sourceName.uppercase(), color = Silver, fontSize = 8.sp, letterSpacing = 1.3.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
    }
}

@Composable
private fun Cover(url: String?, modifier: Modifier = Modifier) {
    if (url != null) AsyncImage(model = url, contentDescription = "Manga cover", modifier = modifier.clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
    else Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF28282D)), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MenuBook, null, tint = Muted, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun DetailScreen(
    series: Series, chapters: List<Chapter>, saved: Boolean, bookmarkedChapterKeys: Set<String>,
    downloadedChapterKeys: Set<String>, downloadingChapterKey: String?, downloadProgress: Int,
    loading: Boolean, error: String?,
    onBack: () -> Unit, onSave: () -> Unit, onToggleChapterBookmark: (Chapter) -> Unit,
    onChapter: (Chapter) -> Unit, onDownloadChapter: (Chapter) -> Unit
) {
    var synopsisExpanded by remember(series.id) { mutableStateOf(false) }
    val synopsis = series.description.ifBlank { "No synopsis is available for this title yet." }
    val synopsisNeedsMore = synopsis.length > 190
    val synopsisText = if (!synopsisExpanded && synopsisNeedsMore) synopsis.take(190).substringBeforeLast(" ") + "…" else synopsis

    Column(Modifier.fillMaxSize().background(Black).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, null, tint = White)
            }
            Spacer(Modifier.weight(1f))
            Text(
                series.title.uppercase(),
                color = White,
                fontSize = 10.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(5f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSave) {
                Icon(if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, null, tint = if (saved) Color(0xFFD6C5F2) else White)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Box(
                    Modifier.fillMaxWidth().height(340.dp)
                        .background(Color(0xFF17151C))
                ) {
                    SeriesCover(series, Modifier.fillMaxSize())
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(Color(0x22000000), Color(0x12000000), Color(0xB8070708), Black)
                            )
                        )
                    )
                    Column(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .padding(horizontal = 25.dp, vertical = 22.dp)
                    ) {
                        Text(
                            "MANGA  /  MANHWA  /  SERIES",
                            color = Color(0xFFD6C5F2),
                            fontSize = 8.sp,
                            letterSpacing = 2.2.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(9.dp))
                        Text(
                            series.title,
                            color = White,
                            fontSize = 27.sp,
                            lineHeight = 32.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.clip(RoundedCornerShape(30.dp))
                                    .background(Color(0x88342B40))
                                    .border(1.dp, Color(0x667E6B91), RoundedCornerShape(30.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(series.sourceName.uppercase(), color = Color(0xFFE0D4EE), fontSize = 8.sp, letterSpacing = 1.2.sp)
                            }
                            Spacer(Modifier.width(9.dp))
                            Text(if (saved) "SAVED TO LIBRARY" else "NOT IN LIBRARY", color = Color(0xFFCAC4D0), fontSize = 8.sp, letterSpacing = .8.sp)
                        }
                        if (chapters.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            NoirGlowButton(
                                "OPEN FIRST LISTED CHAPTER",
                                onClick = { onChapter(chapters.first()) }
                            )
                        }
                    }
                }
            }

            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 25.dp, vertical = 22.dp).animateContentSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("THE STORY", color = Color(0xFFD6C5F2), fontSize = 9.sp, letterSpacing = 2.2.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("SYNOPSIS", color = Color(0xFF625D69), fontSize = 8.sp, letterSpacing = 1.2.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        synopsisText,
                        color = Color(0xFFB5B2BA),
                        fontSize = 13.sp,
                        lineHeight = 21.sp
                    )
                    if (synopsisNeedsMore) {
                        Text(
                            if (synopsisExpanded) "SHOW LESS  ▴" else "SHOW MORE  ▾",
                            color = White,
                            fontSize = 9.sp,
                            letterSpacing = 1.4.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { synopsisExpanded = !synopsisExpanded }.padding(top = 9.dp)
                        )
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 25.dp, end = 25.dp, top = 5.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("YOUR NEXT PAGE", color = Color(0xFFD6C5F2), fontSize = 8.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(5.dp))
                        Text("Chapter list", color = White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(chapters.size.toString().padStart(2, '0'), color = White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text("AVAILABLE", color = Muted, fontSize = 7.sp, letterSpacing = 1.2.sp)
                    }
                }
            }

            if (loading && chapters.isEmpty()) {
                item { LoadingState("Loading chapters…") }
            }
            if (error != null) {
                item { ErrorState(error) }
            }
            if (!loading && error == null && chapters.isEmpty()) {
                item { EmptyState("No chapters available", "There are no English or Indonesian chapters listed for this series yet.") }
            }
            items(chapters, key = { it.id }) { chapter ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF201D25), Color(0xFF141316))))
                        .border(1.dp, Color(0xFF302B35), RoundedCornerShape(17.dp))
                        .clickable { onChapter(chapter) }
                        .padding(horizontal = 13.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(39.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF30283A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.MenuBook, null, tint = Color(0xFFD6C5F2), modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(chapter.name, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "${chapter.language.uppercase()}  ·  ${series.sourceName.uppercase()}",
                            color = Muted,
                            fontSize = 8.sp,
                            letterSpacing = 1.2.sp
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onToggleChapterBookmark(chapter) }) {
                            Icon(
                                if ("${series.sourceId}:${chapter.id}" in bookmarkedChapterKeys) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                contentDescription = "Bookmark chapter",
                                tint = if ("${series.sourceId}:${chapter.id}" in bookmarkedChapterKeys) Color(0xFFD6C5F2) else Muted
                            )
                        }
                        val downloadKey = OfflineChapterStore.key(series, chapter)
                        val isDownloaded = downloadKey in downloadedChapterKeys
                        val isDownloading = downloadKey == downloadingChapterKey
                        IconButton(
                            onClick = { onDownloadChapter(chapter) },
                            enabled = !isDownloaded && !isDownloading && downloadingChapterKey == null
                        ) {
                            when {
                                isDownloading -> CircularProgressIndicator(
                                    progress = { downloadProgress / 100f },
                                    color = Color(0xFFD6C5F2),
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(19.dp)
                                )
                                isDownloaded -> Icon(Icons.Rounded.CheckCircle, "Downloaded", tint = Color(0xFF9FC29A))
                                else -> Icon(Icons.Rounded.Download, "Download chapter", tint = Muted)
                            }
                        }
                        Icon(Icons.Rounded.PlayArrow, null, tint = Color(0xFFD6C5F2), modifier = Modifier.size(23.dp))
                    }
                }
            }

            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 25.dp, vertical = 24.dp)) {
                    Text("NOIR  •  MANGA  •  MANHWA  •  MORE", color = Color(0xFF625D69), fontSize = 8.sp, letterSpacing = 2.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ReaderPageImage(pageUrl: String, modifier: Modifier = Modifier, contentScale: ContentScale, onTap: () -> Unit = {}) {
    var zoom by remember(pageUrl) { mutableFloatStateOf(1f) }
    var panX by remember(pageUrl) { mutableFloatStateOf(0f) }
    var panY by remember(pageUrl) { mutableFloatStateOf(0f) }
    AsyncImage(model = pageUrl, contentDescription = "Comic page", contentScale = contentScale,
        modifier = modifier
            .then(if (zoom > 1.01f) Modifier.graphicsLayer {
                scaleX = zoom; scaleY = zoom; translationX = panX; translationY = panY
            } else Modifier)
            .pointerInput(pageUrl, zoom > 1.01f) {
                if (zoom > 1.01f) detectTransformGestures { _, pan, gestureZoom, _ ->
                    val nextZoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                    zoom = nextZoom
                    if (nextZoom <= 1.01f) { panX = 0f; panY = 0f }
                    else { panX += pan.x; panY += pan.y }
                }
            }
            .pointerInput(pageUrl) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = {
                    if (zoom > 1.05f) { zoom = 1f; panX = 0f; panY = 0f }
                    else zoom = 2.25f
                })
            })
}

@Composable
private fun ReaderScreen(
    series: Series?, chapter: Chapter, chapterList: List<Chapter>, pages: List<String>, loading: Boolean, error: String?,
    initialPage: Int, nextChapterReady: Boolean, readerMode: String, onModeChange: (String) -> Unit,
    onProgress: (Int) -> Unit, onNextChapter: () -> Unit, onBack: () -> Unit
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage.coerceAtLeast(0))
    val hasNextChapter = chapterList.indexOfFirst { it.id == chapter.id }.let { it >= 0 && it < chapterList.lastIndex }
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = initialPage.coerceAtLeast(0), pageCount = { pages.size })
    var showModeMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showReaderControls by remember { mutableStateOf(true) }
    val readerControlsAlpha by animateFloatAsState(
        targetValue = if (showReaderControls) 1f else 0f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "noir-reader-controls-fade"
    )
    val context = LocalContext.current
    val readerPrefs = remember(context) { context.getSharedPreferences("noir_reader_settings", Context.MODE_PRIVATE) }
    var dimAmount by remember { mutableFloatStateOf(readerPrefs.getFloat("screen_dim", 0f).coerceIn(0f, 0.65f)) }
    var fitToWidth by remember { mutableStateOf(readerPrefs.getBoolean("fit_to_width", true)) }
    var pageGap by remember { mutableFloatStateOf(readerPrefs.getFloat("page_gap", 1f).coerceIn(0f, 24f)) }
    val readerScope = rememberCoroutineScope()
    val imageLoader = remember(context) { context.imageLoader }
    LaunchedEffect(dimAmount) { readerPrefs.edit().putFloat("screen_dim", dimAmount).apply() }
    LaunchedEffect(fitToWidth) { readerPrefs.edit().putBoolean("fit_to_width", fitToWidth).apply() }
    LaunchedEffect(pageGap) { readerPrefs.edit().putFloat("page_gap", pageGap).apply() }
    val isPaged = readerMode == "Horizontal" || readerMode == "Right to left"
    var showChapterCinema by remember(chapter.id) { mutableStateOf(false) }
    val cinemaProgress by animateFloatAsState(
        targetValue = if (showChapterCinema) 1f else 0f,
        animationSpec = tween(3000, easing = FastOutSlowInEasing),
        label = "noir-cinema-progress"
    )
    val cinemaAlpha by animateFloatAsState(
        targetValue = if (showChapterCinema) 1f else 0f,
        animationSpec = tween(520, easing = FastOutSlowInEasing),
        label = "noir-cinema-alpha"
    )
    LaunchedEffect(chapter.id, pages.size, loading) {
        if (pages.isNotEmpty() && !loading && initialPage <= 0) {
            showChapterCinema = true
            delay(3000)
            showChapterCinema = false
        } else if (initialPage > 0) {
            showChapterCinema = false
        }
    }

    LaunchedEffect(pages.size, initialPage, readerMode) {
        if (pages.isNotEmpty()) {
            val target = initialPage.coerceIn(0, pages.lastIndex)
            if (isPaged) pagerState.scrollToPage(target) else listState.scrollToItem(target)
        }
    }
    LaunchedEffect(readerMode, listState.firstVisibleItemIndex, pagerState.currentPage, pages.size) {
        if (pages.isNotEmpty()) {
            val current = if (isPaged) pagerState.currentPage else listState.firstVisibleItemIndex
            onProgress(current.coerceIn(0, pages.lastIndex))
        }
    }
    val currentPage = if (isPaged) pagerState.currentPage else listState.firstVisibleItemIndex

    // Keep nearby pages in Coil's cache so fast scrolling is less likely to show blanks.
    LaunchedEffect(currentPage, pages, isPaged) {
        if (pages.isNotEmpty()) {
            val ahead = if (isPaged) 2 else 3
            val indices = buildList {
                for (index in (currentPage + 1)..minOf(pages.lastIndex, currentPage + ahead)) add(index)
                if (currentPage > 0) add(currentPage - 1)
            }.distinct()
            indices.forEach { index ->
                imageLoader.enqueue(ImageRequest.Builder(context).data(pages[index]).build())
            }
        }
    }

    // In vertical/webtoon mode, advance only after the reader reaches the actual
    // bottom during a user-driven scroll. A per-chapter guard prevents duplicate loads.
    LaunchedEffect(chapter.id, pages.size, isPaged, hasNextChapter) {
        if (!isPaged && pages.size > 1 && hasNextChapter) {
            var advanced = false
            snapshotFlow {
                listState.isScrollInProgress && !listState.canScrollForward &&
                    listState.layoutInfo.totalItemsCount > 1
            }.collect { reachedEnd ->
                if (reachedEnd && !advanced) {
                    advanced = true
                    onNextChapter()
                }
            }
        }
    }

    val baseFlingBehavior = androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior()
    val readerFlingBehavior = remember(baseFlingBehavior) {
        object : androidx.compose.foundation.gestures.FlingBehavior {
            override suspend fun androidx.compose.foundation.gestures.ScrollScope.performFling(initialVelocity: Float): Float {
                return with(baseFlingBehavior) { performFling(initialVelocity * 1.18f) }
            }
        }
    }
    val movePage: (Int) -> Unit = { direction ->
        if (pages.isNotEmpty()) readerScope.launch {
            val target = (currentPage + direction).coerceIn(0, pages.lastIndex)
            if (isPaged) pagerState.animateScrollToPage(target) else listState.animateScrollToItem(target)
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .pointerInput(showChapterCinema, showSettings, showModeMenu) {
                    detectTapGestures(onTap = {
                        if (!showChapterCinema && !showSettings && !showModeMenu && pages.isNotEmpty()) {
                            showReaderControls = !showReaderControls
                        }
                    })
                }
        ) {
            when {
                loading && pages.isEmpty() -> LoadingState("Loading chapter pages…")
                error != null -> ErrorState(error)
                pages.isEmpty() -> EmptyState("No pages available", "This chapter may not have readable pages.")
                isPaged -> androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState, modifier = Modifier.fillMaxSize(),
                    reverseLayout = readerMode == "Right to left", key = { index -> "reader-page-$index" }
                ) { index ->
                    ReaderPageImage(pageUrl = pages[index], modifier = Modifier.fillMaxSize(),
                        contentScale = if (fitToWidth) ContentScale.FillWidth else ContentScale.Fit,
                        onTap = { if (!showChapterCinema && !showSettings && !showModeMenu) showReaderControls = !showReaderControls })
                }
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                    flingBehavior = readerFlingBehavior,
                    verticalArrangement = Arrangement.spacedBy(if (readerMode == "Webtoon") 0.dp else pageGap.dp),
                    contentPadding = PaddingValues(bottom = 18.dp)) {
                    items(count = pages.size, key = { index -> "reader-page-$index" }, contentType = { "reader-page" }) { index ->
                        ReaderPageImage(pageUrl = pages[index], modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth,
                            onTap = { if (!showChapterCinema && !showSettings && !showModeMenu) showReaderControls = !showReaderControls })
                    }
                }
            }
            if (dimAmount > 0f && pages.isNotEmpty()) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimAmount)))
            if (nextChapterReady && hasNextChapter) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).graphicsLayer { alpha = readerControlsAlpha }.clip(RoundedCornerShape(30.dp))
                    .background(Color(0xCC19171F)).border(1.dp, Color(0x557F788B), RoundedCornerShape(30.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Text("NEXT CHAPTER READY", color = Color(0xFFD6C5F2), fontSize = 8.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (pages.isNotEmpty()) {
                Box(Modifier.align(Alignment.TopEnd).padding(12.dp).graphicsLayer { alpha = readerControlsAlpha }.clip(RoundedCornerShape(30.dp))
                    .background(Color(0xB51A1920)).border(1.dp, Color(0x557F788B), RoundedCornerShape(30.dp))
                    .padding(horizontal = 11.dp, vertical = 7.dp)) {
                    Text("${(currentPage + 1).coerceAtMost(pages.size)} / ${pages.size}", color = Color(0xFFE8E4EC), fontSize = 9.sp, letterSpacing = .8.sp)
                }
            }
            Column(Modifier.align(Alignment.CenterStart).padding(start = 7.dp)) {
                AnimatedVisibility(
                    visible = showReaderControls,
                    enter = fadeIn(tween(240)),
                    exit = fadeOut(tween(240))
                ) {
                Column(Modifier.clip(RoundedCornerShape(28.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xE83A3442), Color(0xE8191820), Color(0xE82A2632))))
                    .border(1.dp, Color(0x777F788B), RoundedCornerShape(28.dp))
                    .padding(horizontal = 3.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { showReaderControls = false }) { Icon(Icons.Rounded.ChevronLeft, "Hide reader controls", tint = Color(0xFFF0ECF4)) }
                    HorizontalDivider(color = Color(0x557F788B), modifier = Modifier.width(27.dp))
                    IconButton(onClick = { movePage(-1) }, enabled = currentPage > 0) {
                        Icon(if (isPaged) Icons.Rounded.ChevronLeft else Icons.Rounded.KeyboardArrowUp, "Previous page", tint = if (currentPage > 0) White else Muted)
                    }
                    IconButton(
                        onClick = {
                            if (currentPage < pages.lastIndex) movePage(1)
                            else if (hasNextChapter) onNextChapter()
                        },
                        enabled = pages.isNotEmpty() && (currentPage < pages.lastIndex || hasNextChapter)
                    ) {
                        Icon(if (isPaged) Icons.Rounded.ChevronRight else Icons.Rounded.KeyboardArrowDown,
                            if (currentPage < pages.lastIndex) "Next page" else "Next chapter",
                            tint = if (currentPage < pages.lastIndex || hasNextChapter) White else Muted)
                    }
                    Box {
                        IconButton(onClick = { showModeMenu = true }) { Icon(Icons.Rounded.ViewCarousel, "Reading mode", tint = Silver) }
                        DropdownMenu(expanded = showModeMenu, onDismissRequest = { showModeMenu = false }, containerColor = Panel) {
                            listOf("Vertical", "Horizontal", "Right to left", "Webtoon").forEach { mode ->
                                DropdownMenuItem(text = { Text(mode, color = if (readerMode == mode) Color(0xFFD6C5F2) else White) },
                                    onClick = { onModeChange(mode); showModeMenu = false })
                            }
                        }
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.Tune, "Reader settings", tint = Silver) }
                    HorizontalDivider(color = Color(0x557F788B), modifier = Modifier.width(27.dp))
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.Close, "Close reader", tint = Color(0xFFE4DCEB)) }
                }
                }
            }
            if (!showReaderControls) {
                Box(Modifier.align(Alignment.CenterStart).padding(start = 3.dp).size(width = 27.dp, height = 58.dp)
                    .graphicsLayer { alpha = 0.28f + readerControlsAlpha * 0.72f }
                    .clip(RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp)).background(Color(0xB51A1920))
                    .border(1.dp, Color(0x557F788B), RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp))
                    .clickable { showReaderControls = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ChevronRight, "Show reader controls", tint = Color(0xFFE9E4EF), modifier = Modifier.size(18.dp))
                }
            }
            if (showChapterCinema && pages.isNotEmpty()) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = cinemaAlpha }.background(Color(0xFF08070A))) {
                    series?.let { item ->
                        SeriesCover(item, Modifier.fillMaxSize().graphicsLayer {
                            alpha = 0.28f
                            scaleX = 1.08f
                            scaleY = 1.08f
                        })
                    }
                    Box(Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(
                            Color(0xAA070609), Color(0xCC08070A),
                            Color(0xF2070608), Color(0xFF070608)
                        ))
                    ))
                    Column(
                        Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 34.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("NOIR ORIGINAL PRESENTATION", color = Color(0xFFD6C5F2),
                            fontSize = 8.sp, letterSpacing = 2.8.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(27.dp))
                        Text(series?.title?.uppercase() ?: "YOUR STORY",
                            color = Color(0xFFF4F0F7), fontSize = 25.sp, lineHeight = 31.sp,
                            letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(15.dp))
                        Box(Modifier.width(42.dp).height(1.dp).background(Color(0xFFD6C5F2)))
                        Spacer(Modifier.height(15.dp))
                        Text("CHAPTER " + chapter.number.ifBlank { chapter.name },
                            color = Color(0xFFE4DCEB), fontSize = 11.sp,
                            letterSpacing = 2.1.sp, fontWeight = FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Text(chapter.name, color = Color(0xFFAAA2B2), fontSize = 10.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(35.dp))
                        Box(Modifier.fillMaxWidth(0.62f).height(2.dp)
                            .clip(RoundedCornerShape(2.dp)).background(Color(0x554D4656))) {
                            Box(Modifier.fillMaxWidth(cinemaProgress.coerceIn(0f, 1f)).fillMaxHeight()
                                .background(Color(0xFFD6C5F2)))
                        }
                        Spacer(Modifier.height(28.dp))
                        Row(
                            Modifier.clip(RoundedCornerShape(30.dp))
                                .background(Color(0xFFEAE3F0))
                                .clickable { showChapterCinema = false }
                                .padding(horizontal = 22.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("BEGIN READING", color = Color(0xFF17131D),
                                fontSize = 9.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(9.dp))
                            Icon(Icons.Rounded.ArrowForward, null,
                                tint = Color(0xFF17131D), modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.height(17.dp))
                        Text("YOUR STORY CONTINUES", color = Color(0xFF8E8796),
                            fontSize = 7.sp, letterSpacing = 2.sp)
                    }
                    Text("NOIR  /  THE ART OF GETTING LOST",
                        color = Color(0xFF8C8494), fontSize = 7.sp, letterSpacing = 1.6.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
                }
            }
        }
    }

    if (showSettings) AlertDialog(onDismissRequest = { showSettings = false }, containerColor = Panel,
        title = { Text("Reader settings", color = White, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("PAGE DISPLAY", color = Color(0xFFD6C5F2), fontSize = 9.sp, letterSpacing = 1.4.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Fit page to width", color = White, fontSize = 12.sp)
                        Text("Scale pages to the available reader width", color = Muted, fontSize = 10.sp)
                    }
                    Switch(checked = fitToWidth, onCheckedChange = { fitToWidth = it })
                }
                Text("PAGE SPACING", color = Color(0xFFD6C5F2), fontSize = 9.sp, letterSpacing = 1.4.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Gap", color = White, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${pageGap.toInt()} dp", color = Color(0xFFD6C5F2), fontSize = 11.sp)
                }
                Slider(value = pageGap, onValueChange = { pageGap = it }, valueRange = 0f..24f)
                Text("Webtoon mode keeps pages seamlessly connected.", color = Muted, fontSize = 10.sp)
                Text("ZOOM", color = Color(0xFFD6C5F2), fontSize = 9.sp, letterSpacing = 1.4.sp)
                Text("Double-tap a page to zoom in or reset. Drag to pan while zoomed.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                Text("SCREEN DIMMING", color = Color(0xFFD6C5F2), fontSize = 9.sp, letterSpacing = 1.4.sp)
                Slider(value = dimAmount, onValueChange = { dimAmount = it }, valueRange = 0f..0.65f)
            }
        },
        confirmButton = { TextButton(onClick = { showSettings = false }) { Text("DONE", color = Color(0xFFD6C5F2)) } })
}@Composable
private fun SourcesScreen(
    enabledSourceIds: Set<String>,
    onToggleEnabled: (String) -> Unit,
    onOpenSource: (MangaSource) -> Unit,
    onTab: (String) -> Unit
) {
    val context = LocalContext.current
    val sourcePrefs = remember { context.getSharedPreferences("noir_sources", Context.MODE_PRIVATE) }
    var pinned by remember {
        mutableStateOf<Set<String>>(runCatching<Set<String>> {
            val raw = sourcePrefs.getString("pinned_sources", null)
            if (raw.isNullOrBlank()) {
                setOf("mangadex")
            } else {
                val stored = JSONArray(raw)
                (0 until stored.length()).map { index -> stored.optString(index) }.toSet()
            }
        }.getOrDefault(setOf("mangadex")))
    }
    LaunchedEffect(pinned) {
        val stored = JSONArray()
        pinned.forEach { stored.put(it) }
        sourcePrefs.edit().putString("pinned_sources", stored.toString()).apply()
    }
    var selectedFilter by remember { mutableStateOf("All") }
    var selectedCategory by remember { mutableStateOf("All") }
    var sourceQuery by remember { mutableStateOf("") }
    val sourceChecks = remember { mutableStateMapOf<String, String>() }
    val sourceScope = rememberCoroutineScope()

    val entries = remember {
        SourceRegistry.sources.sortedWith(
            compareBy<MangaSource> { it.isBroken }
                .thenBy { it.name.lowercase() }
        )
    }
    val categories = listOf("All") + entries.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    val filtered = entries.filter { entry ->
        val matchesQuery = entry.name.contains(sourceQuery, ignoreCase = true) ||
            entry.id.contains(sourceQuery, ignoreCase = true) ||
            entry.language.contains(sourceQuery, ignoreCase = true) ||
            entry.category.contains(sourceQuery, ignoreCase = true)
        matchesQuery && (selectedCategory == "All" || entry.category.equals(selectedCategory, ignoreCase = true)) && when (selectedFilter) {
            "Enabled" -> entry.id in enabledSourceIds
            "Pinned" -> entry.id in pinned
            else -> true
        }
    }.sortedWith(compareBy<MangaSource> { it.isBroken }.thenBy { if (it.id in pinned) 0 else 1 }.thenBy { it.name.lowercase() })
    val enabledCount = enabledSourceIds.count { id -> entries.any { it.id == id } }

    Column(Modifier.fillMaxSize().background(Black).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onTab("Profile") }) {
                Icon(Icons.Rounded.ArrowBack, null, tint = White)
            }
            Column(Modifier.weight(1f)) {
                Text("NOIR  /  DISCOVER", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
                Text("Source library", color = White, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
            }
            Icon(Icons.Rounded.Tune, null, tint = Silver, modifier = Modifier.size(21.dp))
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF30263B), Color(0xFF17151D), Color(0xFF101012))))
                    .border(1.dp, Color(0xFF51445E), RoundedCornerShape(24.dp))
                    .padding(19.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF51405F)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Explore, null, tint = Color(0xFFF0E7F7), modifier = Modifier.size(21.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("KOTATSU COMMUNITY NETWORK", color = Color(0xFFE2D6EA), fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("${enabledCount.toString().padStart(2, '0')} ENABLED", color = Color(0xFFB9D3B1), fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(17.dp))
                    Text("Read beyond\nthe boundaries.", color = White, fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "Browse ${entries.size} community source parsers. Enable only the catalogues you want NOIR to search.",
                        color = Color(0xFFB7B0BE), fontSize = 11.sp, lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFEEE7F2))
                            .clickable { onTab("Discover") }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Browse enabled sources", color = Color(0xFF17121C), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(7.dp))
                        Icon(Icons.Rounded.ArrowForward, null, tint = Color(0xFF17121C), modifier = Modifier.size(14.dp))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("COMMUNITY SOURCES", color = Silver, fontSize = 9.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${entries.size} PARSERS", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = sourceQuery,
                onValueChange = { sourceQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Find a source or language…", color = Muted, fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Muted) },
                shape = RoundedCornerShape(15.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = White, unfocusedTextColor = White,
                    focusedBorderColor = Color(0xFF786487), unfocusedBorderColor = Color(0xFF343039),
                    cursorColor = White, focusedContainerColor = Panel, unfocusedContainerColor = Panel
                )
            )
            Spacer(Modifier.height(11.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Enabled", "Pinned").forEach { filter ->
                    val active = selectedFilter == filter
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(if (active) Color(0xFFE8DDEC) else Panel)
                            .clickable { selectedFilter = filter }
                            .padding(horizontal = 15.dp, vertical = 8.dp)
                    ) {
                        Text(filter, color = if (active) Color(0xFF211927) else Silver, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(categories) { category ->
                    val active = selectedCategory == category
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (active) Color(0xFF3A3044) else Color(0xFF17161B))
                        .border(1.dp, if (active) Color(0xFF806D91) else Color(0xFF302C35), RoundedCornerShape(50))
                        .clickable { selectedCategory = category }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(category.uppercase(), color = if (active) Color(0xFFEDE3F4) else Muted, fontSize = 8.sp, letterSpacing = .7.sp)
                    }
                }
            }
            Spacer(Modifier.height(13.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 15.dp, end = 15.dp, top = 2.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            items(filtered, key = { it.id }) { entry ->
                val active = entry.id in enabledSourceIds
                val isPinned = entry.id in pinned
                val check = sourceChecks[entry.id]
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF201C26), Color(0xFF111014))))
                        .border(1.dp, if (active) Color(0xFF62516F) else Color(0xFF302C35), RoundedCornerShape(18.dp))
                        .padding(11.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SourceIcon(entry, Modifier.size(44.dp))
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, color = White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${entry.category} · ${entry.language.uppercase()}", color = Muted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { pinned = if (isPinned) pinned - entry.id else pinned + entry.id }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Rounded.PushPin, if (isPinned) "Unpin source" else "Pin source",
                                tint = if (isPinned) Color(0xFFD9C4E9) else Muted, modifier = Modifier.size(16.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(
                            when { entry.isBroken -> Color(0xFFE0A0A0); check == "ONLINE" -> Color(0xFF9FCB9A); check == "ERROR" -> Color(0xFFE0A0A0); else -> if (active) Color(0xFF9FC29A) else Color(0xFF77717D) }
                        ))
                        Spacer(Modifier.width(6.dp))
                        Text(when {
                            entry.isBroken -> "FLAGGED"
                            check == "CHECKING" -> "CHECKING…"
                            check == "ONLINE" -> "RESPONDING"
                            check == "EMPTY" -> "NO TITLES RETURNED"
                            check == "ERROR" -> "CONNECTION FAILED"
                            active -> "ENABLED · NOT TESTED"
                            else -> "AVAILABLE"
                        }, color = when { check == "ERROR" || entry.isBroken -> Color(0xFFE0A0A0); check == "ONLINE" -> Color(0xFF9FCB9A); else -> Muted },
                            fontSize = 7.sp, letterSpacing = .5.sp, modifier = Modifier.weight(1f))
                        Text(if (isPinned) "PINNED" else "", color = Color(0xFFD0C0DE), fontSize = 6.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (active) Color(0xFF332A3B) else Color(0xFF29252E))
                            .clickable(enabled = !entry.isBroken) { onToggleEnabled(entry.id) }
                            .padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                            Text(if (active) "DISABLE" else "ENABLE", color = if (active) Color(0xFFD5C5E0) else White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF17151B))
                            .clickable(enabled = check != "CHECKING" && !entry.isBroken) {
                                sourceChecks[entry.id] = "CHECKING"
                                sourceScope.launch {
                                    sourceChecks[entry.id] = try {
                                        if (entry.popular(0).isNotEmpty()) "ONLINE" else "EMPTY"
                                    } catch (_: Exception) { "ERROR" }
                                }
                            }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                            Text(if (check == "CHECKING") "CHECKING…" else "CHECK", color = Color(0xFFE0D5E8), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("OPEN CATALOGUE  ↗", color = Color(0xFFC8B8D8), fontSize = 7.sp, letterSpacing = 1.sp,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !entry.isBroken) { onOpenSource(entry) }.padding(top = 1.dp))
                }
            }
        }
        BottomNav("Profile", onTab)
    }
}

@Composable
private fun FilterChipLabel(label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF211D28))
            .border(1.dp, Color(0xFF403746), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFFE0D5E7), fontSize = 7.sp, letterSpacing = .45.sp, maxLines = 1)
        Spacer(Modifier.width(3.dp))
        Icon(Icons.Rounded.ExpandMore, null, tint = Muted, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun SourceBrowseScreen(
    source: MangaSource,
    enabled: Boolean,
    onToggleEnabled: () -> Unit,
    onBack: () -> Unit,
    onOpen: (Series) -> Unit,
    matureContentEnabled: Boolean
) {
    var query by remember(source.id) { mutableStateOf("") }
    var catalogue by remember(source.id) { mutableStateOf<List<Series>>(emptyList()) }
    var loading by remember(source.id) { mutableStateOf(false) }
    var error by remember(source.id) { mutableStateOf<String?>(null) }
    var page by remember(source.id) { mutableIntStateOf(0) }
    var hasMore by remember(source.id) { mutableStateOf(false) }
    var loadingMore by remember(source.id) { mutableStateOf(false) }
    var filterOptions by remember(source.id) { mutableStateOf(SourceFilterOptions()) }
    var selectedSort by remember(source.id) { mutableStateOf("POPULARITY") }
    var selectedState by remember(source.id) { mutableStateOf<String?>(null) }
    var selectedTagKey by remember(source.id) { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var stateMenu by remember { mutableStateOf(false) }
    var tagMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(source.id) {
        runCatching { source.filterOptions() }
            .onSuccess { options ->
                filterOptions = options
                if (selectedSort !in options.sortOrders && options.sortOrders.isNotEmpty()) selectedSort = options.sortOrders.first()
            }
    }

    LaunchedEffect(source.id, query, selectedSort, selectedState, selectedTagKey, matureContentEnabled) {
        delay(if (query.isBlank()) 80 else 400)
        loading = true
        error = null
        page = 0
        catalogue = emptyList()
        try {
            val result = source.browse(query, 0, selectedSort, selectedState, selectedTagKey)
            catalogue = result.distinctBy { it.id }
            hasMore = result.isNotEmpty()
            if (result.isEmpty()) error = "No titles match these catalogue filters."
        } catch (e: Exception) {
            error = e.message ?: "Could not load this source."
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().background(Black).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, null, tint = White)
            }
            Column(Modifier.weight(1f)) {
                Text("NOIR  /  SOURCES", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
                Text(source.name, color = White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier.clip(RoundedCornerShape(30.dp))
                    .background(if (enabled) Color(0xFF263128) else Color(0xFF2B2431))
                    .clickable(onClick = onToggleEnabled)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(if (enabled) "ENABLED" else "ENABLE", color = if (enabled) Color(0xFFB9D3B1) else Color(0xFFE7D9EE), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Text(source.description, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(source.category.uppercase(), color = Color(0xFFD4C4DE), fontSize = 8.sp, letterSpacing = 1.sp)
                Spacer(Modifier.width(10.dp))
                Text(source.language.uppercase(), color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search in ${source.name}…", color = Muted, fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Silver) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Rounded.Close, null, tint = Muted)
                    }
                },
                shape = RoundedCornerShape(15.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = White, unfocusedTextColor = White,
                    focusedBorderColor = Color(0xFF786487), unfocusedBorderColor = Color(0xFF343039),
                    cursorColor = White, focusedContainerColor = Panel, unfocusedContainerColor = Panel
                )
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    FilterChipLabel("SORT  ·  ${selectedSort.replace('_', ' ')}") { sortMenu = true }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }, containerColor = Panel) {
                        filterOptions.sortOrders.forEach { option ->
                            DropdownMenuItem(text = { Text(option.replace('_', ' '), color = if (selectedSort == option) Color(0xFFD6C5F2) else White) }, onClick = { selectedSort = option; sortMenu = false })
                        }
                    }
                }
                Box {
                    FilterChipLabel("STATUS  ·  ${selectedState?.replace('_', ' ') ?: "ANY"}") { stateMenu = true }
                    DropdownMenu(expanded = stateMenu, onDismissRequest = { stateMenu = false }, containerColor = Panel) {
                        DropdownMenuItem(text = { Text("Any status", color = White) }, onClick = { selectedState = null; stateMenu = false })
                        filterOptions.states.forEach { option ->
                            DropdownMenuItem(text = { Text(option.replace('_', ' '), color = if (selectedState == option) Color(0xFFD6C5F2) else White) }, onClick = { selectedState = option; stateMenu = false })
                        }
                    }
                }
                Box {
                    FilterChipLabel("GENRE  ·  ${filterOptions.tags.firstOrNull { it.key == selectedTagKey }?.title ?: "ANY"}") { tagMenu = true }
                    DropdownMenu(expanded = tagMenu, onDismissRequest = { tagMenu = false }, containerColor = Panel, modifier = Modifier.heightIn(max = 360.dp)) {
                        DropdownMenuItem(text = { Text("Any genre", color = White) }, onClick = { selectedTagKey = null; tagMenu = false })
                        filterOptions.tags.forEach { option ->
                            DropdownMenuItem(text = { Text(option.title, color = if (selectedTagKey == option.key) Color(0xFFD6C5F2) else White) }, onClick = { selectedTagKey = option.key; tagMenu = false })
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (query.isBlank()) "LATEST CATALOGUE" else "SEARCH RESULTS", color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                Spacer(Modifier.weight(1f))
                Text("${catalogue.size} TITLES", color = Muted, fontSize = 8.sp, letterSpacing = .8.sp)
            }
            Spacer(Modifier.height(10.dp))
        }
        when {
            loading && catalogue.isEmpty() -> LoadingState("Loading ${source.name}…")
            error != null && catalogue.isEmpty() -> ErrorState(error!!)
            catalogue.isEmpty() -> EmptyState("No titles found", "This source may be temporarily unavailable.")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(17.dp),
                horizontalArrangement = Arrangement.spacedBy(13.dp),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp)
            ) {
                items(catalogue, key = { "${source.id}-${it.id}" }) { item ->
                    CoverTile(item) { onOpen(item) }
                }
            }
        }
        if (hasMore || loadingMore) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
                Text(
                    if (loadingMore) "LOADING MORE…" else "LOAD MORE TITLES  ↓",
                    color = Color(0xFFE3D9EA), fontSize = 9.sp, letterSpacing = 1.2.sp,
                    modifier = Modifier.clip(RoundedCornerShape(30.dp)).background(Color(0xFF211D28))
                        .clickable(enabled = !loadingMore) {
                            scope.launch {
                                loadingMore = true
                                try {
                                    val nextPage = page + 1
                                    val next = source.browse(query, nextPage, selectedSort, selectedState, selectedTagKey)
                                    catalogue = (catalogue + next).distinctBy { it.id }
                                    page = nextPage
                                    hasMore = next.isNotEmpty()
                                } catch (e: Exception) {
                                    error = e.message ?: "Could not load more titles."
                                    hasMore = false
                                } finally {
                                    loadingMore = false
                                }
                            }
                        }.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    items: List<Series>,
    recent: List<Series>,
    categories: Map<String, String>,
    progressBySeries: Map<String, Float>,
    readingHistory: Map<String, ReadingHistoryEntry>,
    onCategoryChange: (Series, String) -> Unit,
    onOpen: (Series) -> Unit,
    onResume: (Series) -> Unit,
    onTab: (String) -> Unit
) {
    val categoryOptions = listOf("All", "Reading", "Plan to read", "Completed", "On hold", "Dropped")
    var selectedCategory by remember { mutableStateOf("All") }
    val filtered = remember(items, categories, selectedCategory) {
        if (selectedCategory == "All") items
        else items.filter { (categories["${it.sourceId}:${it.id}"] ?: "Reading") == selectedCategory }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("YOUR COLLECTION", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
            Text("Library", color = White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text("${items.size} SAVED TITLES", color = Color(0xFFB8A9D0), fontSize = 8.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(15.dp))
            val trackedTitles = readingHistory.size
            val averageProgress = if (progressBySeries.isEmpty()) 0 else
                (progressBySeries.values.average().coerceIn(0.0, 1.0) * 100).toInt()
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF211B29), Color(0xFF141217))))
                    .border(1.dp, Color(0xFF3A3042), RoundedCornerShape(20.dp))
                    .padding(horizontal = 15.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("YOUR READING PULSE", color = Color(0xFFBDA8D2), fontSize = 7.sp, letterSpacing = 1.5.sp)
                    Text("$trackedTitles stories in progress", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Your progress, gathered in one place", color = Muted, fontSize = 9.sp)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("$averageProgress%", color = Color(0xFFE7D9F2), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("AVG. PROGRESS", color = Muted, fontSize = 7.sp, letterSpacing = .8.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
                items(categoryOptions) { category ->
                    val count = when (category) {
                        "All" -> items.size
                        "Reading" -> items.count { (categories["${it.sourceId}:${it.id}"] ?: "Reading") == category }
                        else -> items.count { categories["${it.sourceId}:${it.id}"] == category }
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(24.dp))
                            .background(if (selectedCategory == category) Color(0xFFE7DCEC) else Panel)
                            .clickable { selectedCategory = category }
                            .padding(horizontal = 13.dp, vertical = 9.dp)
                    ) {
                        Text("$category  $count", color = if (selectedCategory == category) Color(0xFF211B27) else Silver, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                if (selectedCategory == "All" && recent.isNotEmpty()) {
                    item { Text("PICK UP WHERE YOU LEFT OFF", color = Color(0xFFD6C5F2), fontSize = 8.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold) }
                    items(recent.take(8), key = { "recent-${it.sourceId}-${it.id}" }) { series ->
                        val history = readingHistory["${series.sourceId}:${series.id}"]
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF17151C))
                            .clickable { if (history != null) onResume(series) else onOpen(series) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(54.dp, 68.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF28232F))) {
                                SeriesCover(series, Modifier.fillMaxSize())
                                CoverProgressOverlay(progressBySeries["${series.sourceId}:${series.id}"] ?: 0f, Modifier.align(Alignment.BottomCenter))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(series.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(series.sourceName.uppercase(), color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
                                Spacer(Modifier.height(5.dp))
                                if (history != null) {
                                    Text(history.chapterName, color = Color(0xFFE1D5EE), fontSize = 9.sp, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("PAGE ${history.pageNumber} / ${history.pageCount}", color = Muted, fontSize = 8.sp, letterSpacing = .7.sp)
                                    Spacer(Modifier.height(5.dp))
                                    Text("CONTINUE READING  ↗", color = Color(0xFFD6C5F2), fontSize = 8.sp, letterSpacing = .8.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Spacer(Modifier.height(7.dp))
                                    Text("OPEN SERIES  ↗", color = Color(0xFFD6C5F2), fontSize = 8.sp, letterSpacing = 1.sp)
                                }
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
                        }
                    }
                    item { Spacer(Modifier.height(7.dp)); Text("SAVED COLLECTION", color = Color(0xFFD6C5F2), fontSize = 8.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold) }
                }
                if (filtered.isEmpty()) {
                    item {
                        EmptyState(
                            if (items.isEmpty()) "Your library is waiting" else "Nothing in $selectedCategory",
                            if (items.isEmpty()) "Save a series to keep it in your collection." else "Change a title's category or choose another filter."
                        )
                    }
                } else {
                    items(filtered, key = { "saved-${it.sourceId}-${it.id}" }) { series ->
                        Column(Modifier.fillMaxWidth()) {
                            SeriesRow(series, progressBySeries["${series.sourceId}:${series.id}"] ?: 0f) { onOpen(series) }
                            var expanded by remember(series.id, series.sourceId) { mutableStateOf(false) }
                            Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(categories["${series.sourceId}:${series.id}"] ?: "Reading", color = Color(0xFFD6C5F2), fontSize = 8.sp, letterSpacing = .8.sp)
                                Spacer(Modifier.weight(1f))
                                Box {
                                    Text("MOVE TO  ▾", color = Muted, fontSize = 8.sp, modifier = Modifier.clickable { expanded = true }.padding(7.dp))
                                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = Panel) {
                                        categoryOptions.filter { it != "All" }.forEach { category ->
                                            DropdownMenuItem(
                                                text = { Text(category, color = White) },
                                                onClick = { onCategoryChange(series, category); expanded = false }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        BottomNav("Library", onTab)
    }
}

@Composable
private fun BottomNav(tab: String, onTab: (String) -> Unit) {
    val destinations = listOf(
        "Home" to Icons.Rounded.Home,
        "Discover" to Icons.Rounded.Explore,
        "Library" to Icons.Rounded.Bookmarks,
        "Profile" to Icons.Rounded.Person
    )
    val selectedIndex = destinations.indexOfFirst { it.first == tab }.coerceAtLeast(0)
    val scope = rememberCoroutineScope()
    val sliderPosition = remember { Animatable(selectedIndex.toFloat()) }

    LaunchedEffect(selectedIndex) {
        sliderPosition.animateTo(
            selectedIndex.toFloat(),
            animationSpec = spring(dampingRatio = 0.72f, stiffness = 330f)
        )
    }

    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(Brush.verticalGradient(listOf(Color(0xE51E1C23), Color(0xF20D0C10))))
            .border(1.dp, Color(0xFF3B3741), RoundedCornerShape(30.dp))
    ) {
        val itemWidth = maxWidth / destinations.size
        val itemWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { itemWidth.toPx() }
        Box(
            Modifier.fillMaxWidth().height(70.dp).padding(horizontal = 4.dp, vertical = 4.dp)
                // Translucent floating dock with a spring-driven active glass lens.
                .pointerInput(itemWidthPx, selectedIndex) {
                    var lastDragValue = sliderPosition.value
                    detectHorizontalDragGestures(
                        onDragStart = { lastDragValue = sliderPosition.value },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            lastDragValue = (lastDragValue + dragAmount / itemWidthPx).coerceIn(0f, 3f)
                            scope.launch { sliderPosition.snapTo(lastDragValue) }
                        },
                        onDragEnd = {
                            val destination = sliderPosition.value.roundToInt().coerceIn(0, 3)
                            onTab(destinations[destination].first)
                            scope.launch {
                                sliderPosition.animateTo(
                                    destination.toFloat(),
                                    spring(dampingRatio = 0.68f, stiffness = 380f)
                                )
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                sliderPosition.animateTo(
                                    selectedIndex.toFloat(),
                                    spring(dampingRatio = 0.72f, stiffness = 330f)
                                )
                            }
                        }
                    )
                }
        ) {
            // The lens glides between destinations and stretches subtly with finger movement.
            Box(
                Modifier
                    .width(itemWidth - 8.dp)
                    .fillMaxHeight()
                    .padding(vertical = 3.dp)
                    .graphicsLayer {
                        translationX = itemWidth.toPx() * sliderPosition.value + 4.dp.toPx()
                        shadowElevation = 15.dp.toPx()
                        shape = RoundedCornerShape(25.dp)
                        clip = false
                    }
                    .clip(RoundedCornerShape(25.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xB5D8D0E2), Color(0x735F536D), Color(0x8C302A3B))
                        )
                    )
                    .border(1.dp, Color(0xC9F4ECFA), RoundedCornerShape(25.dp))
            ) {
                Box(
                    Modifier.fillMaxWidth().height(1.dp)
                        .background(Color.White.copy(alpha = .72f))
                )
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().height(17.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0x55FFFFFF), Color(0x08FFFFFF), Color.Transparent)
                            )
                        )
                )
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(13.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0x554D3E5C))
                            )
                        )
                )
            }
            Row(Modifier.fillMaxSize().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                destinations.forEachIndexed { index, (label, icon) ->
                    val active = selectedIndex == index
                    val distance = kotlin.math.abs(sliderPosition.value - index)
                    val iconScale by animateFloatAsState(
                        targetValue = if (active) 1.13f else 1f,
                        animationSpec = spring(dampingRatio = 0.56f, stiffness = 430f),
                        label = "noir-nav-icon-$label"
                    )
                    val tint by animateColorAsState(
                        targetValue = if (distance < 0.45f) Color(0xFFFFFAFF) else Color(0xFFAAA5B1),
                        animationSpec = tween(170),
                        label = "noir-nav-tint-$label"
                    )
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .clip(RoundedCornerShape(26.dp))
                            .clickable { onTab(label) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            icon, contentDescription = label, tint = tint,
                            modifier = Modifier.size(19.dp).graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            }
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            label, color = tint, fontSize = 8.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            letterSpacing = .25.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoirLoadingMark() {
    val transition = rememberInfiniteTransition(label = "noir-loading-mark")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "noir-loading-rotation"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.62f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), repeatMode = androidx.compose.animation.core.RepeatMode.Reverse),
        label = "noir-loading-pulse"
    )
    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().rotate(rotation)
                .border(1.2.dp, Brush.sweepGradient(listOf(Color.Transparent, Color(0xFF8C55FF), Color(0xFFE9DFFF), Color.Transparent)), CircleShape)
        )
        Icon(Icons.Rounded.AutoStories, null, tint = Color(0xFFDCCBFA), modifier = Modifier.size(15.dp).graphicsLayer { alpha = pulse })
    }
}

@Composable
private fun LoadingState(message: String) {
    Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NoirLoadingMark()
            Spacer(Modifier.height(12.dp))
            Text(message, color = Muted, fontSize = 11.sp)
        }
    }
}
@Composable
private fun ErrorState(message: String) { Text(message, color = Color(0xFFE3A4A4), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(18.dp)) }
@Composable
private fun EmptyState(title: String, message: String) {
    Box(Modifier.fillMaxWidth().padding(25.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.MenuBook, null, tint = Muted, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text(message, color = Muted, fontSize = 11.sp)
        }
    }
}


private data class NoirWelcomePage(
    val eyebrow: String,
    val title: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tag: String
)

@Composable
private fun NoirWelcomeWalkthrough(onFinish: () -> Unit) {
    val pages = remember {
        listOf(
            NoirWelcomePage("WELCOME TO YOUR NEXT ESCAPE", "Welcome to NOIR.", "A quieter place to discover stories, lose yourself in new worlds, and read at your own pace.", Icons.Rounded.AutoStories, "YOUR READING UNIVERSE"),
            NoirWelcomePage("FIND YOUR NEXT OBSESSION", "Discover new worlds.", "Explore manga and manhwa, browse different sources, and find a story that feels made for you.", Icons.Rounded.Explore, "MANGA  /  MANHWA  /  MORE"),
            NoirWelcomePage("MADE FOR THE WAY YOU READ", "Settle into every chapter.", "Choose your reading style, keep your place automatically, and pick up right where the story paused.", Icons.Rounded.MenuBook, "A READER THAT REMEMBERS"),
            NoirWelcomePage("KEEP EVERY STORY CLOSE", "Your library. Your worlds.", "Save favourites, organize your reading, and download supported chapters for offline moments.", Icons.Rounded.Bookmarks, "SAVE  /  ORGANIZE  /  READ")
        )
    }
    var page by remember { mutableIntStateOf(0) }
    val current = pages[page]
    val isLast = page == pages.lastIndex

    Box(
        Modifier.fillMaxSize().background(Black).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 25.dp, vertical = 12.dp)
    ) {
        Text("NOIR", modifier = Modifier.align(Alignment.TopStart).padding(top = 10.dp),
            color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
        TextButton(onClick = onFinish, modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp)) {
            Text("SKIP  ›", color = Silver, fontSize = 11.sp, letterSpacing = 1.sp)
        }
        Column(Modifier.fillMaxSize().padding(top = 54.dp, bottom = 10.dp), horizontalAlignment = Alignment.Start) {
            Box(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(30.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF211B29), Color(0xFF111114), Color(0xFF211B29))))
                    .border(1.dp, Color(0xFF38303F), RoundedCornerShape(30.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(210.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0x665F4778), Color(0x0017141C)))))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(112.dp).clip(RoundedCornerShape(30.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF33283F), Color(0xFF17151C))))
                        .border(1.dp, Color(0x665D4A70), RoundedCornerShape(30.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(current.icon, contentDescription = null, tint = Color(0xFFE8DDF4), modifier = Modifier.size(48.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(current.tag, color = Color(0xFFD2BFE8), fontSize = 8.sp, letterSpacing = 2.2.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        repeat(3) { index ->
                            Box(Modifier.size(if (index == 1) 50.dp else 38.dp, 54.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Brush.linearGradient(when ((index + page) % 3) {
                                    0 -> listOf(Color(0xFF41344F), Color(0xFF1A1720))
                                    1 -> listOf(Color(0xFF293A4B), Color(0xFF151A22))
                                    else -> listOf(Color(0xFF493A32), Color(0xFF1C1818))
                                })).border(1.dp, Color(0x557F7090), RoundedCornerShape(7.dp)))
                        }
                    }
                }
            }
            Spacer(Modifier.height(29.dp))
            Text(current.eyebrow, color = Color(0xFFBBA4D5), fontSize = 8.sp, letterSpacing = 2.1.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            key(page) {
                Column {
                    Text(current.title, color = White, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(9.dp))
                    Text(current.description, color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pages.indices.forEach { index ->
                    Box(Modifier.width(if (index == page) 23.dp else 6.dp).height(5.dp).clip(CircleShape)
                        .background(if (index == page) Color(0xFFD8C9E8) else Color(0xFF45414A)))
                }
                Spacer(Modifier.weight(1f))
                Text("${page + 1} / ${pages.size}", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(22.dp))
            Button(onClick = { if (isLast) onFinish() else page += 1 },
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF4F2F6), contentColor = Color(0xFF111014))) {
                Text(if (isLast) "Enter NOIR" else "Continue", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun NoirLogoMark(
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(id = R.drawable.noir_app_icon),
        contentDescription = "NOIR emblem",
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun IntroOverlay(playSound: Boolean, onFinished: () -> Unit) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val alpha = remember { Animatable(0f) }
    val panelsIn = remember { Animatable(0f) }
    val panelsOut = remember { Animatable(0f) }
    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.76f) }
    val panelSpecs = listOf(
        Triple(-112f, -108f, -13f),
        Triple(112f, -104f, 11f),
        Triple(-116f, 108f, 9f),
        Triple(116f, 110f, -10f),
        Triple(0f, -178f, 4f),
        Triple(0f, 178f, -5f)
    )
    LaunchedEffect(Unit) {
        var player: MediaPlayer? = null
        try {
            val appContext = context.applicationContext
            val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (playSound && audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
                player = runCatching { MediaPlayer.create(appContext, R.raw.noir_chime) }.getOrNull()
                player?.let { mediaPlayer ->
                    runCatching { mediaPlayer.setVolume(0.72f, 0.72f); mediaPlayer.start() }
                        .onFailure { runCatching { mediaPlayer.release() }; player = null }
                }
            }
            alpha.animateTo(1f, tween(380))
            panelsIn.animateTo(1f, tween(1050, easing = FastOutSlowInEasing))
            delay(280)
            kotlinx.coroutines.coroutineScope {
                launch { panelsOut.animateTo(1f, tween(720, easing = FastOutSlowInEasing)) }
                launch { logoAlpha.animateTo(1f, tween(560)) }
                launch { logoScale.animateTo(1f, tween(820, easing = FastOutSlowInEasing)) }
            }
            delay(620)
            alpha.animateTo(0f, tween(360))
        } catch (_: Exception) {
        } finally {
            player?.let { mediaPlayer ->
                runCatching { if (mediaPlayer.isPlaying) mediaPlayer.stop() }
                runCatching { mediaPlayer.release() }
            }
            onFinished()
        }
    }
    Box(Modifier.fillMaxSize().background(Black).graphicsLayer { this.alpha = alpha.value }, contentAlignment = Alignment.Center) {
        panelSpecs.forEachIndexed { index, spec ->
            NoirIntroPanel(
                index = index,
                targetX = spec.first,
                targetY = spec.second,
                tilt = spec.third,
                enter = panelsIn.value,
                dissolve = panelsOut.value,
                density = density
            )
        }
        Column(
            modifier = Modifier.graphicsLayer {
                this.alpha = logoAlpha.value
                scaleX = logoScale.value
                scaleY = logoScale.value
            },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            NoirLogoMark(88.dp)
            Spacer(Modifier.height(2.dp))
            Image(
                painter = painterResource(id = R.drawable.noir_intro_wordmark),
                contentDescription = "NOIR — Manga, Manhwa, More",
                modifier = Modifier.width(244.dp).height(138.dp),
                contentScale = ContentScale.Fit
            )
        }
        Text(
            "A UNIVERSE BETWEEN THE PAGES",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 54.dp)
                .graphicsLayer { this.alpha = logoAlpha.value * (1f - panelsOut.value * 0.15f) },
            color = Color(0xFFB9A7D2),
            fontSize = 8.sp,
            letterSpacing = 2.6.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun NoirIntroPanel(
    index: Int,
    targetX: Float,
    targetY: Float,
    tilt: Float,
    enter: Float,
    dissolve: Float,
    density: androidx.compose.ui.unit.Density
) {
    val fromX = if (targetX < 0f) -300f else if (targetX > 0f) 300f else 0f
    val fromY = if (targetY < 0f) -260f else if (targetY > 0f) 260f else -280f
    val stagger = (index % 3) * 0.08f
    val progress = ((enter - stagger) / (1f - stagger)).coerceIn(0f, 1f)
    val driftX = with(density) { (fromX * (1f - progress) - targetX * dissolve).dp.toPx() }
    val driftY = with(density) { (fromY * (1f - progress) - targetY * dissolve).dp.toPx() }
    val panelScale = (0.58f + 0.42f * progress) * (1f - 0.82f * dissolve)
    val panelAlpha = progress * (1f - dissolve)
    Box(
        Modifier
            .offset(x = targetX.dp, y = targetY.dp)
            .size(width = 104.dp, height = 142.dp)
            .graphicsLayer {
                translationX = driftX
                translationY = driftY
                rotationZ = tilt * (1f - dissolve) + (1f - progress) * tilt * 1.8f
                scaleX = panelScale
                scaleY = panelScale
                alpha = panelAlpha
            }
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(
                listOf(Color(0xFF29232F), Color(0xFF111014), Color(0xFF33263D))
            ))
            .border(1.dp, Color(0x887D6A91), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Box(Modifier.fillMaxSize()) {
            Text(
                listOf("CHAPTER 01", "UNKNOWN", "THE BEGINNING", "NO SIGNAL", "PAGE 001", "BEYOND")[index],
                modifier = Modifier.align(Alignment.TopStart),
                color = Color(0xFFD8CBE7),
                fontSize = 5.sp,
                letterSpacing = 1.sp
            )
            Box(
                Modifier.align(Alignment.Center).fillMaxWidth(0.72f).height(68.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.radialGradient(
                        listOf(Color(0xFF8C70AA).copy(alpha = 0.62f), Color(0xFF29232F), Color(0xFF151319))
                    ))
            )
            Box(Modifier.align(Alignment.Center).padding(top = 8.dp).size(30.dp)
                .border(1.dp, Color(0x99E5D9F0), CircleShape))
            Box(Modifier.align(Alignment.Center).padding(top = 8.dp).width(1.dp).height(62.dp)
                .background(Color(0x99D8C8E8)))
            Column(Modifier.align(Alignment.BottomStart), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { line ->
                    Box(Modifier.width((62 - line * 10).dp).height(2.dp)
                        .background(Color(0x668E8299), RoundedCornerShape(2.dp)))
                }
            }
            Box(Modifier.align(Alignment.BottomEnd).size(13.dp)
                .background(Color(0xFFBDA7D4), RoundedCornerShape(2.dp)))
        }
    }
}

