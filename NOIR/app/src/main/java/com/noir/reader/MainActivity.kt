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
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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

@Composable
private fun NoirApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("noir_preferences", Context.MODE_PRIVATE) }
    val sourcePrefs = remember { context.getSharedPreferences("noir_sources", Context.MODE_PRIVATE) }
    val enabledSourceIds = remember {
        mutableStateListOf<String>().apply {
            val raw = sourcePrefs.getString("enabled_sources", null)
            if (raw == null) {
                add("mangadex")
            } else {
                runCatching {
                    val stored = JSONArray(raw)
                    for (index in 0 until stored.length()) {
                        val id = stored.optString(index)
                        if (id.isNotBlank()) add(id)
                    }
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
    var introSound by remember { mutableStateOf(prefs.getBoolean("intro_sound", true)) }
    var launchIntro by remember { mutableStateOf(prefs.getBoolean("launch_intro", true)) }
    var matureContentEnabled by remember { mutableStateOf(prefs.getBoolean("mature_content_18_plus", false)) }
    SideEffect { MatureContentAccess.enabled = matureContentEnabled }
    var showAbout by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Series?>(null) }
    var selectedSource by remember { mutableStateOf<MangaSource?>(null) }
    var selectedChapter by remember { mutableStateOf<Chapter?>(null) }
    val navigationStack = remember { mutableStateListOf<NoirNavigationState>() }
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
            tab != "Home" -> tab = "Home"
        }
    }
    fun navigateTab(destination: String) {
        if (destination != tab) {
            navigationStack.add(currentNavigationState())
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
    val openSeries: (Series) -> Unit = { item ->
        navigationStack.add(currentNavigationState())
        selectedSource = null
        recentlyRead.removeAll { it.id == item.id && it.sourceId == item.sourceId }
        recentlyRead.add(0, item)
        selected = item
    }
    var loading by remember { mutableStateOf(false) }
    var showIntro by remember { mutableStateOf(launchIntro) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadingChapterKey by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    val downloadedChapterKeys = remember {
        mutableStateListOf<String>().apply { addAll(OfflineChapterStore.downloadedKeys(context)) }
    }

    BackHandler(enabled = showIntro || showAbout || navigationStack.isNotEmpty() || selectedChapter != null || selectedSource != null || selected != null || tab != "Home") {
        when {
            showIntro -> showIntro = false
            showAbout -> showAbout = false
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
    val routeAlpha by animateFloatAsState(if (routeVisible) 1f else 0f, tween(190), label = "noir-route-fade")
    val routeScale by animateFloatAsState(if (routeVisible) 1f else 0.985f, tween(220), label = "noir-route-scale")

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
            }) {
            key(routeKey) {
            when {
                selectedChapter != null -> ReaderScreen(
                    selected, selectedChapter!!, chapters, pages, loading, error,
                    readingProgress[selectedChapter!!.id] ?: 0,
                    nextChapterReady = isNextChapterPreloaded,
                    readerMode = readerMode,
                    onModeChange = { mode -> readerMode = mode; prefs.edit().putString("reader_mode", mode).apply() },
                    onProgress = { page ->
                        readingProgress[selectedChapter!!.id] = page
                        selected?.let { series ->
                            if (pages.isNotEmpty()) {
                                val key = "${series.sourceId}:${series.id}"
                                seriesProgress[key] = ((page + 1).toFloat() / pages.size).coerceIn(0f, 1f)
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
                    saved.toList(), recentlyRead.toList(), libraryCategories.toMap(), seriesProgress.toMap(),
                    onCategoryChange = { series, category ->
                        val key = "${series.sourceId}:${series.id}"
                        if (category == "Reading") libraryCategories.remove(key) else libraryCategories[key] = category
                    },
                    onOpen = openSeries, onTab = { navigateTab(it) }
                )
                tab == "Profile" -> ProfileScreen(introSound, launchIntro, matureContentEnabled,
                    { introSound = it; prefs.edit().putBoolean("intro_sound", it).apply() },
                    { launchIntro = it; prefs.edit().putBoolean("launch_intro", it).apply() },
                    { matureContentEnabled = it; prefs.edit().putBoolean("mature_content_18_plus", it).apply() },
                    { showAbout = true }, { navigateTab(it) })
                tab == "Home" -> HomeScreen(catalogue, loading, error, recentlyRead.toList(), saved.toList(), openSeries, { navigateTab(it) })
                else -> CatalogueScreen("Discover", query, { query = it }, catalogue, loading, error, hasMoreCatalogue, loadingMoreCatalogue, loadMoreCatalogue, openSeries, matureContentEnabled, { tab = it })
            }
            }
            }
            if (showAbout) AlertDialog(
                onDismissRequest = { showAbout = false },
                title = { Text("About NOIR", color = White) },
                text = { Text("NOIR 1.3.0 includes the Kotatsu community parser library under GPL-3.0. NO WARRANTY. You may copy and modify this app under that license. Full license and corresponding source: github.com/syusyantho-dotcom/Noire (NOIR/LICENSE and THIRD_PARTY_NOTICES.md).", color = Muted) },
                confirmButton = { TextButton(onClick = { showAbout = false }) { Text("CLOSE", color = Silver) } },
                containerColor = Panel
            )
            if (showIntro) IntroOverlay(introSound) { showIntro = false }
          }
        }
    }
}

@Composable
private fun CatalogueScreen(
    tab: String, query: String, onQuery: (String) -> Unit, catalogue: List<Series>,
    loading: Boolean, error: String?, hasMore: Boolean, loadingMore: Boolean, onLoadMore: () -> Unit,
    onOpen: (Series) -> Unit, matureContentEnabled: Boolean, onTab: (String) -> Unit
) {
    val featured = catalogue.firstOrNull()
    val remaining = if (catalogue.size > 1) catalogue.drop(1) else emptyList()
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (query.isBlank()) "MADE FOR YOUR NEXT OBSESSION" else "YOUR SEARCH", color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.25.sp)
                        Text("A universe of stories, gathered in one place", color = Muted, fontSize = 9.sp)
                    }
                    Text("${catalogue.size} TITLES", color = Color(0xFFBDAFCB), fontSize = 8.sp, letterSpacing = 1.sp)
                }
            }
            when {
                loading && catalogue.isEmpty() -> item { LoadingState("Curating your discovery feed…") }
                error != null && catalogue.isEmpty() -> item { ErrorState(error) }
                catalogue.isEmpty() -> item { EmptyState("No titles found", "Try another title or check your connection.") }
                else -> {
                    item {
                        Box(Modifier.fillMaxWidth().height(218.dp).clip(RoundedCornerShape(25.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF30263A), Color(0xFF111014))))
                            .clickable { featured?.let(onOpen) }) {
                            featured?.let { SeriesCover(it, Modifier.fillMaxSize()) }
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x08070709), Color(0x22070709), Color(0xF5070709)))))
                            Row(Modifier.align(Alignment.TopStart).padding(14.dp).clip(RoundedCornerShape(30.dp))
                                .background(Color(0xA51A1720)).border(1.dp, Color(0x667F7390), RoundedCornerShape(30.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFD9C8F0)))
                                Spacer(Modifier.width(7.dp))
                                Text("FEATURED DISCOVERY", color = Color(0xFFE7DDED), fontSize = 8.sp, letterSpacing = 1.sp)
                            }
                            featured?.let { item ->
                                Row(Modifier.align(Alignment.TopEnd).padding(13.dp).clip(RoundedCornerShape(15.dp))
                                    .background(Color(0xB51A1720)).border(1.dp, Color(0x557F7390), RoundedCornerShape(15.dp))
                                    .padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    SourceRegistry.byId(item.sourceId)?.let { SourceIcon(it, Modifier.size(25.dp)) }
                                }
                                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(17.dp)) {
                                    Text(item.sourceName.uppercase(), color = Color(0xFFD0C4E2), fontSize = 8.sp, letterSpacing = 1.5.sp)
                                    Spacer(Modifier.height(5.dp))
                                    Text(item.title, color = White, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("EXPLORE STORY", color = Color(0xFFF0EAF5), fontSize = 8.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.width(6.dp))
                                        Icon(Icons.Rounded.ArrowOutward, null, tint = Color(0xFFF0EAF5), modifier = Modifier.size(13.dp))
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("THE COLLECTION", color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                            Spacer(Modifier.weight(1f))
                            Text("FRESH FINDS", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
                        }
                    }
                    items(remaining.chunked(2), key = { pair -> pair.joinToString("|") { "${it.sourceId}:${it.id}" } }) { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                            pair.forEach { item -> Box(Modifier.weight(1f)) { CoverTile(item) { onOpen(item) } } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
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
private fun HomeScreen(catalogue: List<Series>, loading: Boolean, error: String?, recentlyRead: List<Series>, saved: List<Series>, onOpen: (Series) -> Unit, onTab: (String) -> Unit) {
    val featured = catalogue.firstOrNull()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(25.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("THE ART OF GETTING LOST", color = Color(0xFFB8A9D0), fontSize = 8.sp, letterSpacing = 2.4.sp)
                        Spacer(Modifier.height(7.dp))
                        Text("Your next\nworld awaits.", color = White, fontSize = 34.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold)
                    }
                    NoirLogoMark(48.dp)
                }
            }
            item {
                Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(27.dp)).background(Brush.linearGradient(listOf(Color(0xFF282333), Color(0xFF111014))))) {
                    featured?.let { SeriesCover(it, Modifier.fillMaxSize()) }
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF5070709), Color(0xAA070709), Color(0x18070709)))))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x22070709), Color(0x22070709), Color(0xF5070709)))))
                    Column(Modifier.align(Alignment.TopStart).padding(19.dp)) {
                        Row(Modifier.clip(RoundedCornerShape(30.dp)).background(Color(0x773A3347)).border(1.dp, Color(0x557F7397), RoundedCornerShape(30.dp)).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFD6C4F1)))
                            Spacer(Modifier.width(7.dp))
                            Text("NOIR / EDITOR'S PICK", color = Color(0xFFE5DDF0), fontSize = 8.sp, letterSpacing = 1.2.sp)
                        }
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                        Text("01  —  FEATURED STORY", color = Color(0xFFD0C4E2), fontSize = 8.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(featured?.title ?: "A universe beyond the page", color = White, fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Text("One story. A thousand places to disappear.", color = Color(0xFFD0CDD4), fontSize = 10.sp)
                        Spacer(Modifier.height(15.dp))
                        Row(Modifier.clip(RoundedCornerShape(30.dp)).background(Color(0xFFE9E4EF)).clickable { featured?.let(onOpen) }.padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("ENTER THE STORY", color = Color(0xFF17131D), fontSize = 8.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Rounded.ArrowOutward, null, tint = Color(0xFF17131D), modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
            if (recentlyRead.isNotEmpty()) item {
                HomeSeriesShelf("PICK UP AGAIN", "YOUR READING JOURNEY", recentlyRead.take(10), "OPEN SERIES", onOpen)
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
                            Column(Modifier.width(132.dp).clickable { onOpen(series) }) {
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
                HomeSeriesShelf("YOUR COLLECTION", "SAVED TO YOUR LIBRARY", saved.take(10), "VIEW TITLE", onOpen)
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
private fun HomeSeriesShelf(
    title: String,
    eyebrow: String,
    seriesItems: List<Series>,
    actionLabel: String,
    onOpen: (Series) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(eyebrow, color = Color(0xFFB8A9D0), fontSize = 8.sp, letterSpacing = 2.sp)
            Text(title, color = White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 4.dp)) {
            items(seriesItems, key = { "shelf-${it.sourceId}-${it.id}" }) { series ->
                Column(
                    Modifier.width(132.dp).clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF141318)).clickable { onOpen(series) }
                ) {
                    Box(Modifier.fillMaxWidth().height(178.dp).background(Color(0xFF211D27))) {
                        SeriesCover(series, Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))))
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
private fun SeriesCover(series: Series, modifier: Modifier = Modifier) {
    Cover(series.coverUrl, modifier)
}

@Composable
private fun CoverProgressOverlay(progress: Float, modifier: Modifier = Modifier) {
    if (progress > 0f) {
        Column(modifier.fillMaxWidth().background(Color(0xC9070709)).padding(horizontal = 7.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("READING", color = Color(0xFFE9DDF5), fontSize = 7.sp, letterSpacing = .9.sp, fontWeight = FontWeight.Bold)
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
        .clickable(onClick = onClick).padding(bottom = 11.dp)) {
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
private fun ProfileScreen(introSound: Boolean, launchIntro: Boolean, matureContentEnabled: Boolean, onIntroSound: (Boolean) -> Unit, onLaunchIntro: (Boolean) -> Unit, onMatureContent: (Boolean) -> Unit, onAbout: () -> Unit, onTab: (String) -> Unit) {
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
            Text("1.3.0", color = Silver, fontSize = 9.sp)
        }
        Spacer(Modifier.height(26.dp))
        Text("APP SETTINGS", color = Silver, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        SettingSwitch("Intro sound", "Play the short chime when NOIR opens", introSound, onIntroSound)
        SettingSwitch("Launch animation", "Show the NOIR intro on app launch", launchIntro, onLaunchIntro)
        SettingSwitch("Mature catalogues (18+)", "Show adult-rated titles from sources that provide them", matureContentEnabled, { enabled -> if (enabled) showMatureConfirmation = true else onMatureContent(false) })
        Spacer(Modifier.height(22.dp))
        Text("INFORMATION", color = Silver, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
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
                            Text(series.sourceName.uppercase(), color = Silver, fontSize = 9.sp, letterSpacing = 1.5.sp)
                            Spacer(Modifier.width(9.dp))
                            Box(Modifier.size(3.dp).clip(CircleShape).background(Muted))
                            Spacer(Modifier.width(9.dp))
                            Text(if (saved) "IN YOUR LIBRARY" else "NOT IN LIBRARY", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
                        }
                    }
                }
            }

            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 25.dp, vertical = 22.dp)) {
                    Text("OVERVIEW", color = Color(0xFFD6C5F2), fontSize = 9.sp, letterSpacing = 2.2.sp, fontWeight = FontWeight.Bold)
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
                        Text("CONTINUE THE STORY", color = Color(0xFFD6C5F2), fontSize = 8.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(5.dp))
                        Text("Chapters", color = White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        if (chapters.isNotEmpty()) "${chapters.size} AVAILABLE" else series.sourceName.uppercase(),
                        color = Muted,
                        fontSize = 8.sp,
                        letterSpacing = 1.2.sp
                    )
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
                        .clip(RoundedCornerShape(15.dp))
                        .background(Color(0xFF17171A))
                        .clickable { onChapter(chapter) }
                        .padding(horizontal = 17.dp, vertical = 17.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
private fun ReaderPageImage(pageUrl: String, modifier: Modifier = Modifier, contentScale: ContentScale) {
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
                detectTapGestures(onDoubleTap = {
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
    var dimAmount by remember { mutableFloatStateOf(0f) }
    var fitToWidth by remember { mutableStateOf(true) }
    val readerScope = rememberCoroutineScope()
    val context = LocalContext.current
    val imageLoader = remember(context) { context.imageLoader }
    val isPaged = readerMode == "Horizontal" || readerMode == "Right to left"

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
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading && pages.isEmpty() -> LoadingState("Loading chapter pages…")
                error != null -> ErrorState(error)
                pages.isEmpty() -> EmptyState("No pages available", "This chapter may not have readable pages.")
                isPaged -> androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState, modifier = Modifier.fillMaxSize(),
                    reverseLayout = readerMode == "Right to left", key = { index -> "reader-page-$index" }
                ) { index ->
                    ReaderPageImage(pageUrl = pages[index], modifier = Modifier.fillMaxSize(),
                        contentScale = if (fitToWidth) ContentScale.FillWidth else ContentScale.Fit)
                }
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                    flingBehavior = readerFlingBehavior,
                    verticalArrangement = Arrangement.spacedBy(if (readerMode == "Webtoon") 0.dp else 1.dp),
                    contentPadding = PaddingValues(bottom = 18.dp)) {
                    items(count = pages.size, key = { index -> "reader-page-$index" }, contentType = { "reader-page" }) { index ->
                        ReaderPageImage(pageUrl = pages[index], modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    }
                }
            }
            if (dimAmount > 0f && pages.isNotEmpty()) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimAmount)))
            if (nextChapterReady && hasNextChapter) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clip(RoundedCornerShape(30.dp))
                    .background(Color(0xCC19171F)).border(1.dp, Color(0x557F788B), RoundedCornerShape(30.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Text("NEXT CHAPTER READY", color = Color(0xFFD6C5F2), fontSize = 8.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (pages.isNotEmpty()) {
                Box(Modifier.align(Alignment.TopEnd).padding(12.dp).clip(RoundedCornerShape(30.dp))
                    .background(Color(0xB51A1920)).border(1.dp, Color(0x557F788B), RoundedCornerShape(30.dp))
                    .padding(horizontal = 11.dp, vertical = 7.dp)) {
                    Text("${(currentPage + 1).coerceAtMost(pages.size)} / ${pages.size}", color = Color(0xFFE8E4EC), fontSize = 9.sp, letterSpacing = .8.sp)
                }
            }
            if (showReaderControls) {
                Column(Modifier.align(Alignment.CenterStart).padding(start = 7.dp)
                    .clip(RoundedCornerShape(28.dp))
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
            if (!showReaderControls) {
                Box(Modifier.align(Alignment.CenterStart).padding(start = 3.dp).size(width = 27.dp, height = 58.dp)
                    .clip(RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp)).background(Color(0xB51A1920))
                    .border(1.dp, Color(0x557F788B), RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp))
                    .clickable { showReaderControls = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ChevronRight, "Show reader controls", tint = Color(0xFFE9E4EF), modifier = Modifier.size(18.dp))
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
    onCategoryChange: (Series, String) -> Unit,
    onOpen: (Series) -> Unit,
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
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF17151C)).clickable { onOpen(series) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(54.dp, 68.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF28232F))) {
                                SeriesCover(series, Modifier.fillMaxSize())
                                CoverProgressOverlay(progressBySeries["${series.sourceId}:${series.id}"] ?: 0f, Modifier.align(Alignment.BottomCenter))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(series.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(series.sourceName.uppercase(), color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
                                Spacer(Modifier.height(7.dp))
                                Text("OPEN SERIES  ↗", color = Color(0xFFD6C5F2), fontSize = 8.sp, letterSpacing = 1.sp)
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
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 7.dp, bottom = 12.dp)
    ) {
        val itemWidth = maxWidth / destinations.size
        val itemWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { itemWidth.toPx() }
        Box(
            Modifier.fillMaxWidth().height(68.dp)
                // Transparent track: only the moving glass lens remains visible.
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
                    .padding(vertical = 5.dp)
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
private fun LoadingState(message: String) {
    Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Silver, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
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
    val alpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.84f) }
    val logoRotation = remember { Animatable(-4f) }
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
            kotlinx.coroutines.coroutineScope {
                launch { alpha.animateTo(1f, tween(520)) }
                launch { logoScale.animateTo(1f, tween(760)) }
                launch { logoRotation.animateTo(0f, tween(760)) }
            }
            delay(780)
            alpha.animateTo(0f, tween(380))
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NoirLogoMark(96.dp, Modifier.graphicsLayer { scaleX = logoScale.value; scaleY = logoScale.value; rotationZ = logoRotation.value })
            Spacer(Modifier.height(8.dp))
            Image(
                painter = painterResource(id = R.drawable.noir_intro_wordmark),
                contentDescription = "NOIR — Manga, Manhwa, More",
                modifier = Modifier.width(264.dp).height(156.dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}

