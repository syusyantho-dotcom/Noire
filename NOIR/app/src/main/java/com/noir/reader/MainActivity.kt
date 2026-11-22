package com.noir.reader

import android.os.Bundle
import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.clipToBounds
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.togetherWith
import androidx.compose.ui.text.withStyle
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
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.animation.core.animateDecay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as rotateDraw
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

private val Black = Color(0xFF070708)
private val Panel = Color(0xFF17171A)
private val White = Color(0xFFF4F4F5)
private val Muted = Color(0xFF99999F)
private val Silver = Color(0xFFC6C6CC)

/** Index of the chapter that follows [currentId] in reading order, whichever way the source orders its list. */
private fun nextChapterIndex(list: List<Chapter>, currentId: String): Int? {
    val i = list.indexOfFirst { it.id == currentId }
    if (i < 0 || list.size < 2) return null
    val first = list.first().number.toFloatOrNull()
    val last = list.last().number.toFloatOrNull()
    val descending = if (first != null && last != null && first != last) first > last
        else list.last().sourceUploadDate > 0L && list.first().sourceUploadDate > list.last().sourceUploadDate
    return (if (descending) i - 1 else i + 1).takeIf { it in list.indices }
}

@Composable
private fun rememberAppVersion(): String {
    val context = LocalContext.current
    return remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
}

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
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(applicationContext)
                .okHttpClient(NoirNetwork.client)
                .memoryCache { coil.memory.MemoryCache.Builder(applicationContext).maxSizePercent(0.28).build() }
                .diskCache { coil.disk.DiskCache.Builder().directory(applicationContext.cacheDir.resolve("noir_images")).maxSizePercent(0.04).build() }
                .respectCacheHeaders(false)
                .crossfade(160)
                .components { if (Build.VERSION.SDK_INT >= 28) add(coil.decode.ImageDecoderDecoder.Factory()) else add(coil.decode.GifDecoder.Factory()) }
                .build()
        )
        val radarConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val radarWork = PeriodicWorkRequestBuilder<ReleaseRadarWorker>(12, TimeUnit.HOURS)
            .setConstraints(radarConstraints)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "noir_release_radar_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            radarWork
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("noir_preferences", Context.MODE_PRIVATE).getBoolean("notification_permission_asked", false)
        ) {
            getSharedPreferences("noir_preferences", Context.MODE_PRIVATE).edit()
                .putBoolean("notification_permission_asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1601)
        }
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

private data class RadarSeriesResult(
    val series: Series,
    val chapters: List<Chapter>,
    val newChapters: List<Chapter>,
    val checkedAt: Long,
    val error: String? = null
)

@Composable
private fun NoirApp() {
    val context = LocalContext.current
    val backupPrefsNames = listOf("noir_preferences", "noir_reader_settings", "noir_sources", "noir_discovery", "noir_customization")
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            val root = JSONObject()
            backupPrefsNames.forEach { name ->
                val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                val section = JSONObject()
                values.forEach { (key, value) ->
                    when (value) {
                        is Set<*> -> section.put(key, JSONArray(value.map { it.toString() }))
                        is String, is Boolean, is Int, is Long, is Float -> section.put(key, value)
                    }
                }
                root.put(name, section)
            }
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(root.toString(2)) }
                ?: error("Could not open backup destination")
        }.onSuccess {
            android.widget.Toast.makeText(context, "Backup exported", android.widget.Toast.LENGTH_SHORT).show()
        }.onFailure { android.widget.Toast.makeText(context, "Backup failed: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Could not read backup file")
            val root = JSONObject(raw)
            backupPrefsNames.forEach { name ->
                val section = root.optJSONObject(name) ?: return@forEach
                val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                section.keys().forEach { key ->
                    when (val value = section.opt(key)) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Double -> editor.putFloat(key, value.toFloat())
                        is JSONArray -> editor.putStringSet(key, buildSet { for (i in 0 until value.length()) add(value.optString(i)) })
                    }
                }
                editor.apply()
            }
            (context as? Activity)?.recreate()
        }.onFailure { android.widget.Toast.makeText(context, "Restore failed: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
    }
    val prefs = remember { context.getSharedPreferences("noir_preferences", Context.MODE_PRIVATE) }
    var background by remember { mutableStateOf(NoirBackground(prefs.getString("bg_uri", null), prefs.getBoolean("bg_video", false))) }
    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val video = context.contentResolver.getType(uri).orEmpty().startsWith("video/")
            background = NoirBackground(uri.toString(), video)
            prefs.edit().putString("bg_uri", uri.toString()).putBoolean("bg_video", video).apply()
        }
    }
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
    val activeSources = SourceRegistry.sources.filter {
        it.id in activeSourceIds && (!it.isAdultOnly || prefs.getBoolean("mature_content_18_plus", false))
    }
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
    var studioAccent by remember { mutableStateOf(prefs.getString("studio_accent", "violet") ?: "violet") }
    var studioBackdrop by remember { mutableStateOf(prefs.getString("studio_backdrop", "AMOLED") ?: "AMOLED") }
    var studioFontScale by remember { mutableFloatStateOf(prefs.getFloat("studio_font_scale", 1f).coerceIn(.85f, 1.2f)) }
    var studioMotion by remember { mutableStateOf(prefs.getBoolean("studio_motion", true)) }
    var studioGestures by remember { mutableStateOf(prefs.getString("studio_gestures", "Standard") ?: "Standard") }
    var studioLayout by remember { mutableStateOf(prefs.getString("studio_layout", "Comfortable") ?: "Comfortable") }
    LaunchedEffect(studioAccent, studioBackdrop, studioFontScale, studioMotion, studioGestures, studioLayout) {
        prefs.edit()
            .putString("studio_accent", studioAccent)
            .putString("studio_backdrop", studioBackdrop)
            .putFloat("studio_font_scale", studioFontScale)
            .putBoolean("studio_motion", studioMotion)
            .putString("studio_gestures", studioGestures)
            .putString("studio_layout", studioLayout)
            .apply()
    }
    LaunchedEffect(studioLayout) {
        discoveryCompactGrid = studioLayout == "Compact"
    }
    val noirAccent = when (studioAccent) {
        "ice" -> Color(0xFF9BD8F5)
        "crimson" -> Color(0xFFF09AA9)
        "emerald" -> Color(0xFF9BD8B5)
        "amber" -> Color(0xFFF0C58C)
        "mono" -> Color(0xFFC6C6CC)
        else -> Color(0xFFD6C5F2)
    }
    var introSound by remember { mutableStateOf(prefs.getBoolean("intro_sound", true)) }
    var launchIntro by remember { mutableStateOf(prefs.getBoolean("launch_intro", true)) }
    var introStyle by remember { mutableStateOf(prefs.getString("intro_style", null) ?: if (prefs.getBoolean("intro_aether", true)) "panels" else "glow") }
    var matureContentEnabled by remember { mutableStateOf(prefs.getBoolean("mature_content_18_plus", false)) }
    SideEffect { MatureContentAccess.enabled = matureContentEnabled }
    var showAbout by remember { mutableStateOf(false) }
    var showWhatsNew by remember { mutableStateOf(false) }
    val whatsNewVersion = "1.6.1"
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
                            sourcePublicUrl = item.optString("sourcePublicUrl").takeIf { it.isNotBlank() },
                            tags = item.optJSONArray("tags")?.let { array -> (0 until array.length()).map { index -> array.optString(index) } }.orEmpty(),
                            status = item.optString("status").takeIf { it.isNotBlank() },
                            originalLanguage = item.optString("originalLanguage").takeIf { it.isNotBlank() },
                            year = item.optInt("year", 0).takeIf { it > 0 }
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
                    .put("tags", JSONArray(item.tags))
                    .put("status", item.status ?: "")
                    .put("originalLanguage", item.originalLanguage ?: "")
                    .put("year", item.year ?: 0)
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
                        item.optString("sourcePublicUrl").takeIf { it.isNotBlank() },
                        tags = item.optJSONArray("tags")?.let { array -> (0 until array.length()).map { index -> array.optString(index) } }.orEmpty(),
                        status = item.optString("status").takeIf { it.isNotBlank() },
                        originalLanguage = item.optString("originalLanguage").takeIf { it.isNotBlank() },
                        year = item.optInt("year", 0).takeIf { it > 0 }
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
                .put("sourceUrl", item.sourceUrl ?: "").put("sourcePublicUrl", item.sourcePublicUrl ?: "")
                .put("tags", JSONArray(item.tags)).put("status", item.status ?: "")
                .put("originalLanguage", item.originalLanguage ?: "").put("year", item.year ?: 0))
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
    LaunchedEffect(Unit) {
        // Observed outside composition and written off the main thread, so turning pages never stalls scrolling.
        snapshotFlow { readingProgress.toMap() }.collectLatest { snapshot ->
            delay(900)
            val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val stored = JSONObject()
                snapshot.forEach { (key, value) -> stored.put(key, value) }
                stored.toString()
            }
            prefs.edit().putString("reading_progress", json).apply()
        }
    }
    val seriesProgress = remember {
        mutableStateMapOf<String, Float>().apply {
            runCatching {
                val stored = JSONObject(prefs.getString("series_reading_progress", "{}"))
                stored.keys().forEach { key -> put(key, stored.optDouble(key, 0.0).toFloat().coerceIn(0f, 1f)) }
            }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { seriesProgress.toMap() }.collectLatest { snapshot ->
            delay(900)
            val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val stored = JSONObject()
                snapshot.forEach { (key, value) -> stored.put(key, value.toDouble()) }
                stored.toString()
            }
            prefs.edit().putString("series_reading_progress", json).apply()
        }
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
    LaunchedEffect(Unit) {
        snapshotFlow { readingHistory.toMap() }.collectLatest { snapshot ->
            delay(900)
            val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val stored = JSONObject()
                snapshot.forEach { (key, entry) ->
                    stored.put(key, JSONObject()
                        .put("chapterId", entry.chapterId)
                        .put("chapterName", entry.chapterName)
                        .put("pageNumber", entry.pageNumber)
                        .put("pageCount", entry.pageCount)
                        .put("updatedAt", entry.updatedAt))
                }
                stored.toString()
            }
            prefs.edit().putString("reading_history", json).apply()
        }
    }
    var radarResults by remember { mutableStateOf<List<RadarSeriesResult>>(emptyList()) }
    var radarLoading by remember { mutableStateOf(false) }
    var radarLastChecked by remember { mutableLongStateOf(prefs.getLong("radar_last_checked", 0L)) }
    var radarError by remember { mutableStateOf<String?>(null) }
    val radarSnapshots = remember {
        mutableStateMapOf<String, Set<String>>().apply {
            runCatching {
                val stored = JSONObject(prefs.getString("radar_snapshots", "{}"))
                stored.keys().forEach { key ->
                    val ids = stored.optJSONArray(key) ?: return@forEach
                    put(key, buildSet { for (index in 0 until ids.length()) add(ids.optString(index)) })
                }
            }
        }
    }
    LaunchedEffect(radarSnapshots.toMap()) {
        val stored = JSONObject()
        radarSnapshots.forEach { (key, ids) ->
            val array = JSONArray()
            ids.forEach(array::put)
            stored.put(key, array)
        }
        prefs.edit().putString("radar_snapshots", stored.toString()).apply()
    }
    val refreshReleaseRadar: () -> Unit = {
        if (!radarLoading) {
            scope.launch {
                radarLoading = true
                radarError = null
                val targets = saved.toList()
                val collected = mutableListOf<RadarSeriesResult>()
                try {
                    targets.chunked(4).forEach { batch ->
                        val batchResults = coroutineScope {
                            batch.map { item ->
                                async {
                                    val key = "${item.sourceId}:${item.id}"
                                    try {
                                        val latest = SourceRegistry.byId(item.sourceId)?.chapters(item).orEmpty()
                                        val previous = radarSnapshots[key]
                                        val fresh = if (previous == null) emptyList() else latest.filter { it.id !in previous }
                                        radarSnapshots[key] = latest.take(60).map { it.id }.toSet()
                                        RadarSeriesResult(item, latest, fresh, System.currentTimeMillis())
                                    } catch (e: Exception) {
                                        RadarSeriesResult(item, emptyList(), emptyList(), System.currentTimeMillis(), e.message ?: "Source unavailable")
                                    }
                                }
                            }.awaitAll()
                        }
                        collected.addAll(batchResults)
                        radarResults = collected.toList()
                    }
                    radarLastChecked = System.currentTimeMillis()
                    prefs.edit().putLong("radar_last_checked", radarLastChecked).apply()
                    if (targets.isEmpty()) radarError = "Save a few series to start tracking new chapters."
                } catch (e: Exception) {
                    radarError = e.message ?: "Could not check followed sources."
                } finally {
                    radarLoading = false
                }
            }
        }
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
    val enteringReader = selectedChapter != null
    val routeDepth = when { selectedChapter != null -> 3; selectedSource != null || selected != null -> 2; else -> 1 }
    val depthHolder = remember { intArrayOf(routeDepth) }
    // +1 = pushing deeper (detail/reader), -1 = going back, 0 = switching tabs.
    val animKey = if (selectedChapter != null) "reader:" + selected?.sourceId + ":" + selected?.id else routeKey
    val routeDirection = remember(animKey) { (routeDepth - depthHolder[0]).also { depthHolder[0] = routeDepth } }
    val enter = remember(animKey) { Animatable(if (studioMotion) 0f else 1f) }
    LaunchedEffect(animKey) {
        if (studioMotion) enter.animateTo(1f, tween(if (enteringReader) 480 else 380, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)))
    }

    var serverGenre by remember { mutableStateOf<String?>(null) }
    var incomingGenre by remember { mutableStateOf<String?>(null) }
    var sourceGenres by remember { mutableStateOf<List<String>>(emptyList()) }
    val filterOptionsCache = remember { mutableMapOf<String, SourceFilterOptions>() }
    suspend fun optionsFor(provider: MangaSource): SourceFilterOptions =
        filterOptionsCache[provider.id] ?: runCatching { provider.filterOptions() }
            .getOrDefault(SourceFilterOptions()).also { filterOptionsCache[provider.id] = it }
    // Genre filtering happens on the source itself when it supports it, so every source can be browsed by genre.
    suspend fun fetchCataloguePage(provider: MangaSource, q: String, page: Int): List<Series> {
        val genre = serverGenre
        if (genre != null) {
            val options = optionsFor(provider)
            if (options.tags.isNotEmpty()) {
                val key = options.tags.firstOrNull { it.title.equals(genre, ignoreCase = true) }?.key ?: return emptyList()
                return provider.browse(q, page, "", null, key)
            }
        }
        return if (q.isBlank()) provider.popular(page) else provider.search(q, page)
    }
    LaunchedEffect(activeSourceIds) {
        val titles = coroutineScope { activeSources.map { async { optionsFor(it).tags.map { tag -> tag.title } } }.awaitAll() }
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        titles.forEach { list -> list.distinctBy { it.lowercase() }.forEach { title ->
            val k = title.lowercase()
            counts[k] = (counts[k]?.first ?: title) to ((counts[k]?.second ?: 0) + 1)
        } }
        sourceGenres = counts.values.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() }).map { it.first }
    }
    LaunchedEffect(query, tab, activeSourceIds, serverGenre) {
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
                val sourceResults = coroutineScope {
                    activeSources.map { provider ->
                        async {
                            provider to runCatching {
                                fetchCataloguePage(provider, query, 0)
                            }
                        }
                    }.awaitAll()
                }
                sourceResults.forEach { (provider, result) ->
                    result.onSuccess { combined.addAll(it) }
                        .onFailure { failures.add("${provider.name}: ${it.message ?: "unavailable"}") }
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
                val sourceResults = coroutineScope {
                    activeSources.map { provider ->
                        async {
                            provider to runCatching {
                                fetchCataloguePage(provider, query, nextPage)
                            }
                        }
                    }.awaitAll()
                }
                sourceResults.forEach { (provider, result) ->
                    result.onSuccess { combined.addAll(it) }
                        .onFailure { failures.add("${provider.name}: ${it.message ?: "unavailable"}") }
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
        try {
            val loaded = SourceRegistry.byId(item.sourceId)?.chapters(item).orEmpty()
            chapters = loaded
            SourceRegistry.byId(item.sourceId)?.cachedTags(item)?.takeIf { it.isNotEmpty() && item.tags.isEmpty() }?.let { learned ->
                if (selected?.id == item.id && selected?.sourceId == item.sourceId) selected = selected!!.copy(tags = learned)
            }
            if (loaded.isNotEmpty() && downloadedChapterKeys.any { it.startsWith("${item.sourceId}:${item.id}:") }) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    OfflineChapterStore.saveChapterList(context.applicationContext, item, loaded)
                }
            }
        }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            // Offline: fall back to the saved chapter list so downloaded chapters still open.
            val cached = OfflineChapterStore.cachedChapterList(context.applicationContext, item)
            if (cached.isNotEmpty()) chapters = cached else error = e.message ?: "Could not load chapters."
        }
        finally { loading = false }
    }
    val refreshChapters: () -> Unit = {
        val item = selected
        if (item != null && !loading) {
            scope.launch {
                loading = true
                error = null
                try {
                    // Keep the existing list visible until the source returns its latest feed.
                    chapters = SourceRegistry.byId(item.sourceId)?.chapters(item).orEmpty()
                } catch (e: Exception) {
                    error = e.message ?: "Could not refresh chapters."
                } finally {
                    loading = false
                }
            }
        }
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
    var handoffId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedChapter?.id) {
        val chapter = selectedChapter ?: return@LaunchedEffect
        if (handoffId == chapter.id) { handoffId = null; return@LaunchedEffect }
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
        val next = nextChapterIndex(chapters, current.id)?.let { chapters[it] } ?: return@LaunchedEffect
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
        nextChapterIndex(chapters, current.id)?.let { chapters[it] }
    }
    val isNextChapterPreloaded = selected?.let { item ->
        nextChapterForReader?.let { preloadedChapterPages.containsKey(OfflineChapterStore.key(item, it)) }
    } ?: false

    MaterialTheme(colorScheme = darkColorScheme(background = Black, surface = Panel, primary = noirAccent, secondary = noirAccent, onBackground = White, onSurface = White)) {
        Surface(Modifier.fillMaxSize(), color = Black) {
          Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                val p = enter.value
                alpha = (p * 1.7f).coerceAtMost(1f)
                val s = if (routeDirection > 0) 0.965f + 0.035f * p else 0.985f + 0.015f * p
                scaleX = s
                scaleY = s
                // iOS-style push from the right, gentle drift when returning, pure fade between tabs.
                translationX = (1f - p) * size.width * when { routeDirection > 0 && !enteringReader -> 0.16f; routeDirection < 0 -> -0.07f; else -> 0f }
                translationY = if (enteringReader) (1f - p) * 38f * density else 0f
                if (enteringReader) {
                    shape = RoundedCornerShape(((1f - p) * 28f).dp)
                    clip = true
                    cameraDistance = 32f * density
                }
                rotationX = if (studioMotion && enteringReader) (1f - p) * 1.6f else 0f
            }) {
            key(routeKey) {
            when {
                selectedChapter != null -> ReaderScreen(
                    selected, selectedChapter!!, chapters, pages, loading, error,
                    remember(selectedChapter!!.id) {
                        androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
                            readingProgress[selectedChapter!!.id]
                                ?: readingHistory["${selected!!.sourceId}:${selected!!.id}"]
                                    ?.takeIf { it.chapterId == selectedChapter!!.id }
                                    ?.let { (it.pageNumber - 1).coerceAtLeast(0) }
                                ?: 0
                        }
                    },
                    nextChapterReady = isNextChapterPreloaded,
                    nextPages = selected?.let { item -> nextChapterForReader?.let { preloadedChapterPages[OfflineChapterStore.key(item, it)] } },
                    onSeamlessNext = { startPage, offsetPx ->
                        val item = selected
                        val next = nextChapterForReader
                        val prepared = if (item != null && next != null) preloadedChapterPages.remove(OfflineChapterStore.key(item, next)) else null
                        if (next != null && prepared != null) {
                            carryScrollOffset = offsetPx
                            readingProgress[next.id] = startPage
                            pages = prepared
                            loading = false
                            error = null
                            handoffId = next.id
                            selectedChapter = next
                        }
                    },
                    readerMode = readerMode,
                    gesturePreset = studioGestures,
                    motionEnabled = studioMotion,
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
                        nextChapterIndex(chapters, selectedChapter!!.id)?.let { selectedChapter = chapters[it] }
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
                    downloadingChapterKey, downloadProgress,
                    readingHistory["${selected!!.sourceId}:${selected!!.id}"], loading, error,
                    onRefreshChapters = refreshChapters,
                    onBack = { goBack() },
                    onGenreClick = { genre ->
                        incomingGenre = genre
                        query = ""
                        navigationStack.clear()
                        selected = null
                        selectedSource = null
                        selectedChapter = null
                        if (tab != "Discover") { tabBackStack.add(tab); tab = "Discover" }
                    },
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
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        OfflineChapterStore.saveChapterList(context.applicationContext, item, chapters)
                                    }
                                } catch (e: Exception) {
                                    error = "Download failed: ${e.message ?: "Check your connection."}"
                                } finally {
                                    downloadingChapterKey = null
                                    downloadProgress = 0
                                }
                            }
                        }
                    },
                    onDeleteDownload = { chapter ->
                        val item = selected!!
                        scope.launch {
                            OfflineChapterStore.delete(context.applicationContext, item, chapter)
                            downloadedChapterKeys.remove(OfflineChapterStore.key(item, chapter))
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
                tab == "Studio" -> NoirCustomizationStudio(
                    accent = studioAccent,
                    onAccent = { studioAccent = it },
                    backdrop = studioBackdrop,
                    onBackdrop = {
                        studioBackdrop = it
                        context.getSharedPreferences("noir_reader_settings", Context.MODE_PRIVATE)
                            .edit().putString("reader_background", it).apply()
                    },
                    fontScale = studioFontScale,
                    onFontScale = { studioFontScale = it },
                    motionEnabled = studioMotion,
                    onMotionEnabled = { studioMotion = it },
                    gestures = studioGestures,
                    onGestures = { studioGestures = it },
                    layout = studioLayout,
                    onLayout = { studioLayout = it },
                    onBack = { navigateTab("Profile") },
                    onTab = { navigateTab(it) }
                )
                tab == "Radar" -> ReleaseRadarScreen(
                    saved = saved.toList(),
                    catalogue = catalogue,
                    results = radarResults,
                    loading = radarLoading,
                    lastChecked = radarLastChecked,
                    error = radarError,
                    onRefresh = refreshReleaseRadar,
                    onOpen = openSeries,
                    onBack = { navigateTab("Profile") }
                )
                tab == "Profile" -> {
                    val latestReading = readingHistory.values.maxByOrNull { it.updatedAt }
                    val latestSeries = (saved.toList() + recentlyRead.toList()).firstOrNull { item ->
                        readingHistory["${item.sourceId}:${item.id}"]?.updatedAt == latestReading?.updatedAt
                    }
                    val averageProgress = if (seriesProgress.isEmpty()) 0 else
                        (seriesProgress.values.average().coerceIn(0.0, 1.0) * 100).toInt()
                    val activityLabel = latestReading?.let { entry ->
                        val days = ((System.currentTimeMillis() - entry.updatedAt).coerceAtLeast(0L) / 86_400_000L).toInt()
                        when (days) { 0 -> "TODAY"; 1 -> "YESTERDAY"; else -> "$days DAYS AGO" }
                    } ?: "NO ACTIVITY YET"
                    val weeklyActivity = (6 downTo 0).map { offset ->
                        val start = System.currentTimeMillis() - (offset + 1L) * 86_400_000L
                        val end = start + 86_400_000L
                        readingHistory.values.count { it.updatedAt in start until end }
                    }
                    ProfileScreen(introSound, launchIntro, matureContentEnabled, saved.size,
                        readingHistory.size, averageProgress, latestSeries?.title ?: latestReading?.chapterName.orEmpty(),
                        activityLabel, weeklyActivity,
                        onRadar = { navigateTab("Radar") },
                        onExportBackup = { exportBackup.launch("NOIR-backup.json") },
                        onImportBackup = { importBackup.launch(arrayOf("application/json", "text/json")) },
                        { introSound = it; prefs.edit().putBoolean("intro_sound", it).apply() },
                        { launchIntro = it; prefs.edit().putBoolean("launch_intro", it).apply() },
                        { matureContentEnabled = it; prefs.edit().putBoolean("mature_content_18_plus", it).apply() },
                        { showAbout = true }, { showWhatsNew = true }, { navigateTab(it) },
                        backgroundLabel = when { background.uri == null -> "Starry night (default)"; background.isVideo -> "Custom looping video"; else -> "Custom image / GIF" },
                        onPickBackground = { pickBackground.launch(arrayOf("image/*", "video/*")) },
                        onResetBackground = { background = NoirBackground(); prefs.edit().remove("bg_uri").remove("bg_video").apply() },
                        introStyle = introStyle, onIntroStyle = { introStyle = it; prefs.edit().putString("intro_style", it).apply() })
                }
                tab == "Home" -> HomeScreen(catalogue, loading, error, recentlyRead.toList(), saved.toList(), seriesProgress.toMap(), readingHistory.toMap(), openSeries, { item -> pendingResumeKey = "${item.sourceId}:${item.id}"; openSeries(item) }, { navigateTab(it) }, background)
                else -> CatalogueScreen("Discover", query, { query = it }, catalogue, loading, error, hasMoreCatalogue, loadingMoreCatalogue, loadMoreCatalogue, openSeries, discoveryCompactGrid, { discoveryCompactGrid = it }, matureContentEnabled, { navigateTab(it) },
                    sourceGenres = sourceGenres, onServerGenre = { serverGenre = it }, incomingGenre = incomingGenre, onIncomingGenreConsumed = { incomingGenre = null }, background = background)
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
                text = { Text("NOIR 1.6.1 adds scheduled release alerts, weekly reading insights, and portable backup/restore, alongside Release Radar and smart picks. The Kotatsu community parser library is included under GPL-3.0. NO WARRANTY. You may copy and modify this app under that license. Full license and corresponding source: github.com/syusyantho-dotcom/Noire (NOIR/LICENSE and THIRD_PARTY_NOTICES.md).", color = Muted) },
                confirmButton = { TextButton(onClick = { showAbout = false }) { Text("CLOSE", color = Silver) } },
                containerColor = Panel
            )
            if (showIntro) {
                val finishIntro: () -> Unit = {
                    showIntro = false
                    prefs.edit().putBoolean("intro_seen_once", true).apply()
                    if (!prefs.getBoolean("welcome_seen", false)) showWelcome = true
                }
                when (introStyle) {
                    "horizon" -> HorizonIntro(introSound, finishIntro)
                    "glow" -> IntroOverlay(introSound, finishIntro)
                    else -> PanelsIntro(introSound, finishIntro)
                }
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

private val launchSeed: Long = java.util.Random().nextLong()

data class NoirBackground(val uri: String? = null, val isVideo: Boolean = false)

private class StarSpec(val x: Float, val y: Float, val size: Float, val phase: Float, val twinkle: Int, val drift: Int, val tint: Color)

@Composable
private fun StarryNightBackdrop(modifier: Modifier = Modifier) {
    val stars = remember {
        val r = java.util.Random(7)
        val tints = listOf(Color.White, Color(0xFFCFE3FF), Color(0xFFFFE6F7), Color(0xFFD9CCFF), Color(0xFFBFFAF4))
        List(120) {
            StarSpec(r.nextFloat(), r.nextFloat(), 0.5f + r.nextFloat() * 1.5f + if (r.nextInt(9) == 0) 1.4f else 0f,
                r.nextFloat(), 6 + r.nextInt(14), 1 + r.nextInt(2), tints[r.nextInt(tints.size)])
        }
    }
    val transition = rememberInfiniteTransition(label = "starry-night")
    val t = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(48000, easing = LinearEasing)), label = "starry-t")
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val time = t.value
        val tau = 2f * Math.PI.toFloat()
        drawRect(Brush.verticalGradient(listOf(Color(0xFF05030F), Color(0xFF0B0724), Color(0xFF050309))))
        fun nebula(cx: Float, cy: Float, r: Float, color: Color, a: Float) {
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a), Color.Transparent), center = Offset(cx, cy), radius = r), radius = r, center = Offset(cx, cy))
        }
        nebula(w * (0.22f + 0.10f * kotlin.math.sin(time * tau)), h * (0.18f + 0.05f * kotlin.math.cos(time * tau)), w * 0.85f, Color(0xFF6A3DFF), 0.42f)
        nebula(w * (0.80f + 0.08f * kotlin.math.cos(time * tau * 2f)), h * (0.30f + 0.06f * kotlin.math.sin(time * tau * 2f)), w * 0.75f, Color(0xFFE23CC0), 0.28f)
        nebula(w * (0.55f + 0.12f * kotlin.math.sin(time * tau + 2f)), h * (0.55f + 0.05f * kotlin.math.cos(time * tau + 1f)), w * 0.8f, Color(0xFF2B6BFF), 0.26f)
        nebula(w * (0.10f + 0.06f * kotlin.math.cos(time * tau + 3f)), h * (0.70f + 0.04f * kotlin.math.sin(time * tau)), w * 0.7f, Color(0xFF16C6D6), 0.20f)
        stars.forEach { s ->
            val tw = 0.5f + 0.5f * kotlin.math.sin((time * s.twinkle + s.phase) * tau)
            val a = 0.22f + 0.78f * tw
            val y = ((s.y - time * s.drift * 0.02f) % 1f + 1f) % 1f
            val c = Offset(s.x * w, y * h)
            val r = s.size.dp.toPx() * 0.6f
            drawCircle(s.tint.copy(alpha = a), radius = r, center = c)
            if (s.size > 2.2f) {
                drawCircle(s.tint.copy(alpha = a * 0.22f), radius = r * 4.5f, center = c)
                val arm = r * 5f * tw
                drawLine(s.tint.copy(alpha = a * 0.7f), c - Offset(arm, 0f), c + Offset(arm, 0f), strokeWidth = 1f)
                drawLine(s.tint.copy(alpha = a * 0.7f), c - Offset(0f, arm), c + Offset(0f, arm), strokeWidth = 1f)
            }
        }
        // One shooting star per loop.
        val shoot = ((time - 0.30f) / 0.04f).coerceIn(0f, 1f)
        if (shoot > 0f && shoot < 1f) {
            val head = Offset(w * (0.9f - 0.5f * shoot), h * (0.05f + 0.22f * shoot))
            val tail = head + Offset(w * 0.12f, -h * 0.045f)
            drawLine(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.9f * (1f - shoot)), Color.Transparent), start = head, end = tail), head, tail, strokeWidth = 2.dp.toPx())
        }
    }
}

private fun cropVideoToView(view: TextureView, vw: Int, vh: Int) {
    val w = view.width.toFloat()
    val h = view.height.toFloat()
    if (w <= 0f || h <= 0f || vw <= 0 || vh <= 0) return
    val videoAspect = vw.toFloat() / vh
    val viewAspect = w / h
    val m = android.graphics.Matrix()
    if (videoAspect > viewAspect) m.setScale(videoAspect / viewAspect, 1f, w / 2f, h / 2f)
    else m.setScale(1f, viewAspect / videoAspect, w / 2f, h / 2f)
    view.setTransform(m)
}

/** Muted, looping, centre-cropped video background. */
@Composable
private fun VideoBackdrop(uri: String, modifier: Modifier = Modifier) {
    val ctxForOwner = LocalContext.current
    val owner = remember(ctxForOwner) {
        var c: Context = ctxForOwner
        while (c is android.content.ContextWrapper && c !is androidx.lifecycle.LifecycleOwner) c = c.baseContext
        c as? androidx.lifecycle.LifecycleOwner
    }
    val player = remember(uri) { arrayOfNulls<MediaPlayer>(1) }
    DisposableEffect(owner, uri) {
        if (owner == null) return@DisposableEffect onDispose { }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            runCatching {
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> player[0]?.pause()
                    androidx.lifecycle.Lifecycle.Event.ON_RESUME -> player[0]?.start()
                    else -> {}
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                var surface: Surface? = null
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        runCatching {
                            val mp = MediaPlayer()
                            player[0] = mp
                            surface = Surface(st)
                            mp.setSurface(surface)
                            mp.setDataSource(ctx, Uri.parse(uri))
                            mp.isLooping = true
                            mp.setVolume(0f, 0f)
                            mp.setOnVideoSizeChangedListener { _, vw, vh ->
                                tag = intArrayOf(vw, vh)
                                cropVideoToView(this@apply, vw, vh)
                            }
                            mp.setOnPreparedListener { it.start() }
                            mp.prepareAsync()
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                        (tag as? IntArray)?.let { cropVideoToView(this@apply, it[0], it[1]) }
                    }
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        runCatching { player[0]?.release() }
                        player[0] = null
                        runCatching { surface?.release() }
                        surface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        }
    )
}

/** Shared page backdrop: starry night by default, or the user's own image, GIF or looping video. */
@Composable
private fun NoirBackdrop(background: NoirBackground, scroll: () -> Float, height: androidx.compose.ui.unit.Dp) {
    Box(Modifier.fillMaxWidth().height(height).clipToBounds().offset { IntOffset(0, (-scroll() * 0.28f).roundToInt()) }) {
        val mediaUri = background.uri
        when {
            mediaUri == null -> StarryNightBackdrop(Modifier.fillMaxSize())
            background.isVideo -> VideoBackdrop(mediaUri, Modifier.fillMaxSize())
            else -> AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(Uri.parse(mediaUri)).crossfade(true).build(),
                contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        }
        // Readability scrim: lighter over the colourful sky, fading fully to black toward the content.
        Canvas(Modifier.fillMaxSize()) {
            val dim = if (background.uri == null) 0.10f else 0.38f
            drawRect(Color.Black.copy(alpha = dim))
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color(0x99070709), 1f to Color.Black))
            drawRect(Color.Black, alpha = (scroll() / (size.height * 0.9f)).coerceIn(0f, 1f))
        }
    }
}

@Composable
private fun NoirGenreChip(
    label: String, selected: Boolean, excluded: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(12.dp)
    val fill by animateColorAsState(when { excluded -> Color(0x33D45A74); selected -> Color(0x40A58BD6); else -> Color.Transparent }, tween(160), label = "genre-fill")
    val edge by animateColorAsState(when { excluded -> Color(0xFFB4546B); selected -> Color(0xFFB59BE3); else -> Color(0xFF6B6275) }, tween(160), label = "genre-edge")
    Box(
        Modifier.clip(shape).background(fill).border(1.dp, edge, shape)
            .pointerInput(onClick, onLongClick) { detectTapGestures(onTap = { onClick() }, onLongPress = onLongClick?.let { lp -> { _ -> lp() } }) }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            label, fontSize = 14.sp, maxLines = 1,
            color = when { excluded -> Color(0xFFFFB9C6); selected -> Color(0xFFF1E8FA); else -> Color(0xFFE6E0EC) },
            textDecoration = if (excluded) androidx.compose.ui.text.style.TextDecoration.LineThrough else androidx.compose.ui.text.style.TextDecoration.None
        )
    }
}

/** Ultra-light blurred backdrop: a tiny decode scaled up gives the soft look without a GPU blur pass. */
@Composable
private fun SoftBackdrop(url: String?, modifier: Modifier = Modifier) {
    if (url == null) { Box(modifier); return }
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context).data(url).size(coil.size.Size(64, 96)).crossfade(false).build()
    }
    AsyncImage(model = request, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}

@Composable
private fun NoirChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    excluded: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    val container by animateColorAsState(
        when {
            excluded -> Color(0xFF3A1E26)
            selected -> Color(0xFF3B2F4A)
            else -> Color(0x33FFFFFF)
        }, tween(180), label = "chip-bg"
    )
    val edge by animateColorAsState(
        when {
            excluded -> Color(0xFF8E4457)
            selected -> Color(0xFF8D74B5)
            else -> Color(0x26FFFFFF)
        }, tween(180), label = "chip-edge"
    )
    val textColor = when {
        excluded -> Color(0xFFFFB9C6)
        selected -> Color(0xFFF1E8FA)
        else -> Color(0xFFC9C3D1)
    }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        modifier.clip(CircleShape).background(container).border(1.dp, edge, CircleShape)
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = onLongClick?.let { lp ->
                        {
                            runCatching { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
                            lp()
                        }
                    }
                )
            }
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, color = textColor, fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.Medium,
            textDecoration = if (excluded) androidx.compose.ui.text.style.TextDecoration.LineThrough else androidx.compose.ui.text.style.TextDecoration.None
        )
        if (trailing != null) { Spacer(Modifier.width(6.dp)); trailing() }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CatalogueScreen(
    tab: String, query: String, onQuery: (String) -> Unit, catalogue: List<Series>,
    loading: Boolean, error: String?, hasMore: Boolean, loadingMore: Boolean, onLoadMore: () -> Unit,
    onOpen: (Series) -> Unit, compactGrid: Boolean, onCompactGridChange: (Boolean) -> Unit,
    matureContentEnabled: Boolean, onTab: (String) -> Unit,
    sourceGenres: List<String> = emptyList(), onServerGenre: (String?) -> Unit = {},
    incomingGenre: String? = null, onIncomingGenreConsumed: () -> Unit = {},
    background: NoirBackground = NoirBackground()
) {
    val context = LocalContext.current
    val discoveryPrefs = remember(context) { context.getSharedPreferences("noir_discovery", Context.MODE_PRIVATE) }
    var sortMode by remember { mutableStateOf("FOR YOU") }
    var sourceFilter by remember { mutableStateOf("ALL SOURCES") }
    var selectedStatus by remember { mutableStateOf("ANY STATUS") }
    var selectedLanguage by remember { mutableStateOf("ANY LANGUAGE") }
    var selectedGenres by remember { mutableStateOf(setOf<String>()) }
    var excludedGenres by remember { mutableStateOf(setOf<String>()) }
    var excludedWords by remember { mutableStateOf("") }
    var showAdvancedFilters by remember { mutableStateOf(false) }
    val searchHistory = remember {
        mutableStateListOf<String>().apply {
            runCatching {
                val stored = JSONArray(discoveryPrefs.getString("search_history", "[]"))
                for (index in 0 until stored.length()) {
                    val value = stored.optString(index)
                    if (value.isNotBlank()) add(value)
                }
            }
        }
    }
    LaunchedEffect(query) {
        val term = query.trim()
        if (term.length >= 2) {
            delay(900)
            searchHistory.remove(term)
            searchHistory.add(0, term)
            while (searchHistory.size > 8) searchHistory.removeAt(searchHistory.lastIndex)
            val stored = JSONArray()
            searchHistory.forEach(stored::put)
            discoveryPrefs.edit().putString("search_history", stored.toString()).apply()
        }
    }
    val savedPresets = remember {
        mutableStateListOf<String>().apply {
            runCatching {
                val stored = JSONArray(discoveryPrefs.getString("saved_searches", "[]"))
                for (index in 0 until stored.length()) {
                    val value = stored.optString(index)
                    if (value.isNotBlank()) add(value)
                }
            }
        }
    }
    LaunchedEffect(savedPresets.toList()) {
        val stored = JSONArray()
        savedPresets.forEach(stored::put)
        discoveryPrefs.edit().putString("saved_searches", stored.toString()).apply()
    }
    val availableSources = remember(catalogue) { catalogue.map { it.sourceName }.distinct().sorted() }
    // Genres come from every active source's own genre list, plus whatever the loaded titles carry.
    val availableGenres = remember(catalogue, sourceGenres, matureContentEnabled) {
        val merged = sourceGenres.toMutableList()
        catalogue.flatMap { it.tags }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key }
            .forEach { tag -> if (merged.none { it.equals(tag, ignoreCase = true) }) merged.add(tag) }
        val hidden = if (matureContentEnabled) emptySet() else setOf("hentai", "adult", "smut", "erotica", "pornographic", "sexual violence")
        merged.filter { it.lowercase() !in hidden }.take(90)
    }
    val availableStatuses = remember(catalogue) { catalogue.mapNotNull { it.status }.distinct().sorted() }
    val availableLanguages = remember(catalogue) { catalogue.mapNotNull { it.originalLanguage }.distinct().sorted() }
    LaunchedEffect(availableSources) {
        if (sourceFilter != "ALL SOURCES" && sourceFilter !in availableSources) sourceFilter = "ALL SOURCES"
    }
    val wordList = remember(excludedWords) { excludedWords.split(",").map { it.trim() }.filter { it.isNotBlank() } }
    val sourceFiltered = remember(catalogue, sourceFilter, selectedStatus, selectedLanguage, selectedGenres, excludedGenres, wordList) {
        catalogue.filter { item ->
            val sourceMatch = sourceFilter == "ALL SOURCES" || item.sourceName == sourceFilter
            val statusMatch = selectedStatus == "ANY STATUS" || item.status.equals(selectedStatus, ignoreCase = true)
            val languageMatch = selectedLanguage == "ANY LANGUAGE" || item.originalLanguage.equals(selectedLanguage, ignoreCase = true)
            // Titles without genre metadata were already narrowed by the source itself, so they stay visible.
            val genreMatch = selectedGenres.isEmpty() || selectedGenres.all { selected -> item.tags.isEmpty() || item.tags.any { it.equals(selected, true) } }
            val excludedGenreMatch = excludedGenres.none { excluded -> item.tags.any { it.equals(excluded, true) } }
            val excludedWordMatch = wordList.none { item.title.contains(it, true) || item.description.contains(it, true) }
            sourceMatch && statusMatch && languageMatch && genreMatch && excludedGenreMatch && excludedWordMatch
        }
    }
    val sortedCatalogue = remember(sourceFiltered, sortMode) {
        when (sortMode) {
            "A–Z" -> sourceFiltered.sortedBy { it.title.lowercase() }
            "SOURCE" -> sourceFiltered.sortedBy { it.sourceName.lowercase() + it.title.lowercase() }
            "NEWEST" -> sourceFiltered.sortedByDescending { it.year ?: 0 }
            else -> sourceFiltered
        }
    }
    val featured = if (query.isBlank() && sortMode == "FOR YOU" && sourceFilter == "ALL SOURCES") sortedCatalogue.firstOrNull() else null
    val remaining = remember(sortedCatalogue, featured) { if (featured != null) sortedCatalogue.drop(1) else sortedCatalogue }
    val columns = if (compactGrid) 3 else 2
    val rows = remember(remaining, columns) { remaining.chunked(columns) }
    val activeFilterCount = (if (sourceFilter != "ALL SOURCES") 1 else 0) + (if (selectedStatus != "ANY STATUS") 1 else 0) +
        (if (selectedLanguage != "ANY LANGUAGE") 1 else 0) + selectedGenres.size + excludedGenres.size + (if (wordList.isNotEmpty()) 1 else 0)
    val clearFilters = {
        selectedGenres = emptySet(); excludedGenres = emptySet(); selectedStatus = "ANY STATUS"
        selectedLanguage = "ANY LANGUAGE"; excludedWords = ""; sourceFilter = "ALL SOURCES"
    }
    // Tap: include. Tap again: exclude. Tap again: neutral.
    val cycleGenre: (String) -> Unit = { genre ->
        when {
            genre in selectedGenres -> { selectedGenres = selectedGenres - genre; excludedGenres = excludedGenres + genre }
            genre in excludedGenres -> excludedGenres = excludedGenres - genre
            else -> selectedGenres = selectedGenres + genre
        }
    }

    val listState = rememberLazyListState()
    val stuck by remember { derivedStateOf { listState.firstVisibleItemIndex >= 2 } }
    val stuckAlpha = animateFloatAsState(if (stuck) 1f else 0f, tween(160), label = "strip-bg")
    LaunchedEffect(selectedGenres) { onServerGenre(selectedGenres.firstOrNull()) }
    LaunchedEffect(incomingGenre) {
        incomingGenre?.let { g ->
            excludedGenres = excludedGenres - g
            selectedGenres = setOf(g)
            onIncomingGenreConsumed()
        }
    }
    DisposableEffect(Unit) { onDispose { onServerGenre(null) } }
    val scrollPx = remember { derivedStateOf { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 100000f } }
    val scrolling = remember { derivedStateOf { listState.isScrollInProgress } }
    val glowTransition = rememberInfiniteTransition(label = "discover-glow")
    val glowProgress = glowTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "discover-glow-progress"
    )
    // Fetch the next page before the reader reaches the bottom, so scrolling never stops at a button.
    val nearEnd by remember { derivedStateOf {
        val info = listState.layoutInfo
        (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
    } }
    LaunchedEffect(nearEnd, hasMore, loadingMore, rows.size) {
        if (nearEnd && hasMore && !loadingMore && catalogue.isNotEmpty() && rows.isNotEmpty()) onLoadMore()
    }
    val backdropStory = featured ?: sortedCatalogue.firstOrNull()

    Column(Modifier.fillMaxSize().background(Black).statusBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            NoirBackdrop(background, { scrollPx.value }, 420.dp)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("NOIR  /  YOUR READING UNIVERSE", color = Color(0xFFC9B9E2), fontSize = 9.sp, letterSpacing = 2.2.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("Discover", color = White, fontSize = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp)
                        }
                        Box(Modifier.noirGlass(CircleShape, 0.8f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(if (matureContentEnabled) "18+ ON" else "SAFE MODE", color = Color(0xFFE0D5EC), fontSize = 9.sp, letterSpacing = .9.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                item(key = "search") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search stories, manga, manhwa…", color = Color(0xFFA8A3B0), fontSize = 14.sp) },
                            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Color(0xFFD0C4E2)) },
                            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, null, tint = Silver) } },
                            singleLine = true, shape = RoundedCornerShape(22.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF9A82C2), unfocusedBorderColor = Color(0x33FFFFFF),
                                focusedContainerColor = Color(0xB3141218), unfocusedContainerColor = Color(0x80141218),
                                focusedTextColor = White, unfocusedTextColor = White, cursorColor = Silver
                            )
                        )
                        if (searchHistory.isNotEmpty() && query.isBlank()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { Text("RECENT", color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(end = 2.dp, top = 9.dp)) }
                                items(searchHistory.toList()) { term -> NoirChip(term, false, { onQuery(term) }) }
                            }
                        }
                    }
                }
                // Sticky quick-filter strip: Filters button + genres, always one tap away.
                stickyHeader(key = "quick-filters") {
                    Row(
                        Modifier.fillMaxWidth().drawBehind { drawRect(Color(0xFF050507), alpha = 0.96f * stuckAlpha.value) }.padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.clip(CircleShape)
                                .background(if (activeFilterCount > 0) Color(0xFF3B2F4A) else Color(0x33FFFFFF))
                                .border(1.dp, if (activeFilterCount > 0) Color(0xFF8D74B5) else Color(0x26FFFFFF), CircleShape)
                                .clickable { showAdvancedFilters = true }.padding(horizontal = 13.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Tune, null, tint = Color(0xFFEDE3F7), modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (activeFilterCount > 0) "Filters · $activeFilterCount" else "Filters", color = Color(0xFFEDE3F7), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(availableGenres.take(30), key = { it }) { genre ->
                                NoirChip(
                                    label = genre,
                                    selected = genre in selectedGenres,
                                    excluded = genre in excludedGenres,
                                    onClick = { cycleGenre(genre) },
                                    onLongClick = {
                                        if (genre in excludedGenres) excludedGenres = excludedGenres - genre
                                        else { excludedGenres = excludedGenres + genre; selectedGenres = selectedGenres - genre }
                                    }
                                )
                            }
                        }
                    }
                }
                if (activeFilterCount > 0) {
                    item(key = "active-filters") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                NoirChip("Clear all", false, { clearFilters() }, trailing = { Icon(Icons.Rounded.Close, null, tint = Color(0xFFC9C3D1), modifier = Modifier.size(13.dp)) })
                            }
                            if (sourceFilter != "ALL SOURCES") item { NoirChip(sourceFilter, true, { sourceFilter = "ALL SOURCES" }, trailing = { Icon(Icons.Rounded.Close, null, tint = Color(0xFFE0D5EC), modifier = Modifier.size(13.dp)) }) }
                            if (selectedStatus != "ANY STATUS") item { NoirChip(selectedStatus.replaceFirstChar { it.uppercase() }, true, { selectedStatus = "ANY STATUS" }, trailing = { Icon(Icons.Rounded.Close, null, tint = Color(0xFFE0D5EC), modifier = Modifier.size(13.dp)) }) }
                            if (selectedLanguage != "ANY LANGUAGE") item { NoirChip(selectedLanguage.uppercase(), true, { selectedLanguage = "ANY LANGUAGE" }, trailing = { Icon(Icons.Rounded.Close, null, tint = Color(0xFFE0D5EC), modifier = Modifier.size(13.dp)) }) }
                            items(selectedGenres.toList(), key = { "in-$it" }) { g -> NoirChip("+ $g", true, { selectedGenres = selectedGenres - g }, trailing = { Icon(Icons.Rounded.Close, null, tint = Color(0xFFE0D5EC), modifier = Modifier.size(13.dp)) }) }
                            items(excludedGenres.toList(), key = { "ex-$it" }) { g -> NoirChip(g, false, { excludedGenres = excludedGenres - g }, excluded = true, trailing = { Icon(Icons.Rounded.Close, null, tint = Color(0xFFFFB9C6), modifier = Modifier.size(13.dp)) }) }
                            if (wordList.isNotEmpty()) item { NoirChip("No: ${wordList.joinToString(", ")}".take(28), true, { excludedWords = "" }, trailing = { Icon(Icons.Rounded.Close, null, tint = Color(0xFFE0D5EC), modifier = Modifier.size(13.dp)) }) }
                        }
                    }
                }
                if (savedPresets.isNotEmpty()) {
                    item(key = "presets") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("SAVED SEARCHES  ·  HOLD TO REMOVE", color = Muted, fontSize = 9.sp, letterSpacing = 1.3.sp)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(savedPresets.toList()) { raw ->
                                    val preset = runCatching { JSONObject(raw) }.getOrNull()
                                    val label = preset?.optString("name")?.takeIf { it.isNotBlank() } ?: "Saved search"
                                    NoirChip(label, false, onClick = {
                                        if (preset != null) {
                                            onQuery(preset.optString("query"))
                                            sourceFilter = preset.optString("source", "ALL SOURCES")
                                            selectedStatus = preset.optString("status", "ANY STATUS")
                                            selectedLanguage = preset.optString("language", "ANY LANGUAGE")
                                            excludedWords = preset.optString("exclude")
                                            selectedGenres = preset.optString("genres").split("|").filter { it.isNotBlank() }.toSet()
                                            excludedGenres = preset.optString("excluded").split("|").filter { it.isNotBlank() }.toSet()
                                        }
                                    }, onLongClick = { savedPresets.remove(raw) })
                                }
                            }
                        }
                    }
                }
                item(key = "sources") {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("EXPLORE BY SOURCE", color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf("ALL SOURCES") + availableSources, key = { it }) { sourceName ->
                                NoirChip(sourceName, sourceFilter == sourceName, { sourceFilter = sourceName })
                            }
                        }
                    }
                }
                item(key = "count") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (query.isBlank()) "Made for your next obsession" else "Your search", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.3).sp)
                            Spacer(Modifier.height(2.dp))
                            Text("A universe of stories, gathered in one place", color = Muted, fontSize = 11.sp)
                        }
                        Text("${sortedCatalogue.size} TITLES", color = Color(0xFFCFC0E4), fontSize = 10.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (loading && catalogue.isEmpty()) {
                    item(key = "loading") { LoadingState("Curating your discovery feed…") }
                } else if (error != null && catalogue.isEmpty()) {
                    item(key = "error") { ErrorState(error) }
                } else if (catalogue.isEmpty()) {
                    item(key = "empty") { EmptyState("No titles found", "Try another title or check your connection.") }
                } else {
                    if (featured != null) {
                        item(key = "featured") {
                            val featuredShape = RoundedCornerShape(30.dp)
                            Box(
                                Modifier.fillMaxWidth().height(300.dp).clip(featuredShape)
                                    .background(Brush.linearGradient(listOf(Color(0xFF30263A), Color(0xFF111014))))
                                    .noirPressScale { onOpen(featured) }
                                    .drawWithContent {
                                        drawContent()
                                        drawRoundRect(Color.White.copy(alpha = 0.14f), cornerRadius = CornerRadius(30.dp.toPx()), style = Stroke(1.dp.toPx()))
                                        if (!scrolling.value) {
                                            drawTravelingOutlineGlow(30.dp.toPx(), glowProgress.value, 1.5.dp.toPx(), 0.14f, 1.4.dp.toPx())
                                        }
                                    }
                            ) {
                                SeriesCover(featured, Modifier.fillMaxSize())
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x1A070709), 0.5f to Color.Transparent, 1f to Color(0xCC070709))))
                                Row(
                                    Modifier.align(Alignment.TopStart).padding(14.dp).noirGlass(CircleShape, 0.8f).padding(horizontal = 11.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFD9C8F0)))
                                    Spacer(Modifier.width(7.dp))
                                    Text("FEATURED DISCOVERY", color = Color(0xFFEFE8F7), fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium)
                                }
                                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp).noirGlass(RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 14.dp)) {
                                    Text(featured.sourceName.uppercase(), color = Color(0xFFD6CAE8), fontSize = 9.sp, letterSpacing = 1.6.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Text(featured.title, color = White, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.3).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Enter the story", color = Color(0xFFF0EAF5), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.width(5.dp))
                                        Icon(Icons.Rounded.ArrowOutward, null, tint = Color(0xFFF0EAF5), modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                    item(key = "collection-header") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("The collection", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.3).sp)
                                    Text(if (compactGrid) "Every story, at a glance" else "Curated finds, waiting to be opened", color = Muted, fontSize = 11.sp)
                                }
                                Row(Modifier.noirGlass(RoundedCornerShape(15.dp), 0.7f).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(width = 40.dp, height = 34.dp).clip(RoundedCornerShape(12.dp))
                                        .background(if (!compactGrid) Color(0xFF433752) else Color.Transparent)
                                        .clickable { onCompactGridChange(false) }, contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.GridView, null, tint = if (!compactGrid) Color(0xFFEDE3F7) else Muted, modifier = Modifier.size(18.dp))
                                    }
                                    Box(Modifier.size(width = 40.dp, height = 34.dp).clip(RoundedCornerShape(12.dp))
                                        .background(if (compactGrid) Color(0xFF433752) else Color.Transparent)
                                        .clickable { onCompactGridChange(true) }, contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.ViewModule, null, tint = if (compactGrid) Color(0xFFEDE3F7) else Muted, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(listOf("FOR YOU", "A–Z", "SOURCE", "NEWEST")) { mode ->
                                    NoirChip(mode, sortMode == mode, { sortMode = mode })
                                }
                            }
                        }
                    }
                    if (rows.isEmpty()) {
                        item(key = "no-match") {
                            Column(Modifier.fillMaxWidth().noirGlass(RoundedCornerShape(24.dp), 0.7f).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                NoirMascot(NoirMascotKind.STAR, 110.dp)
                                Spacer(Modifier.height(8.dp))
                                Text("Nothing matches these filters", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Text("Loosen a genre or clear everything to see the full catalogue.", color = Muted, fontSize = 12.sp)
                                Spacer(Modifier.height(14.dp))
                                NoirChip("Clear all filters", true, { clearFilters() })
                            }
                        }
                    }
                    itemsIndexed(rows, key = { _, row -> row.joinToString("|") { "${it.sourceId}:${it.id}" } }) { rowIndex, row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compactGrid) 8.dp else 12.dp), verticalAlignment = Alignment.Top) {
                            row.forEachIndexed { index, series ->
                                Box(Modifier.weight(1f)) {
                                    DiscoveryCoverTile(series, compactGrid, !compactGrid && ((index + rowIndex * columns) % 3 == 1), glowProgress, scrolling) { onOpen(series) }
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                if (hasMore || loadingMore) {
                    item(key = "more") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            Text(if (loadingMore) "LOADING MORE…" else "LOAD MORE STORIES  ↓",
                                color = Color(0xFFE9DFF2), fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.noirGlass(CircleShape, 0.8f)
                                    .clickable(enabled = !loadingMore, onClick = onLoadMore).padding(horizontal = 22.dp, vertical = 14.dp))
                        }
                    }
                }
            }
        }
        BottomNav("Discover", onTab)
    }
    if (showAdvancedFilters) {
        var genresExpanded by remember { mutableStateOf(false) }
        var sortMenu by remember { mutableStateOf(false) }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showAdvancedFilters = false },
            sheetState = sheetState,
            containerColor = Color(0xFF121016),
            contentColor = White,
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Sorting order", color = Color(0xFFE6DEEF), fontSize = 15.sp)
                    Box {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .border(1.dp, Color(0xFF5A5164), RoundedCornerShape(16.dp))
                                .clickable { sortMenu = true }.padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(sortMode.lowercase().replaceFirstChar { it.uppercase() }, color = White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Rounded.ArrowDropDown, null, tint = Color(0xFFD0C4E2))
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf("FOR YOU", "A–Z", "SOURCE", "NEWEST").forEach { mode ->
                                DropdownMenuItem(text = { Text(mode) }, onClick = { sortMode = mode; sortMenu = false })
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Genres", color = Color(0xFFE6DEEF), fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (availableGenres.size > 12) {
                            Text(
                                if (genresExpanded) "Less" else "More", color = Color(0xFFD6C5F2), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.clip(CircleShape).clickable { genresExpanded = !genresExpanded }.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (availableGenres.isEmpty()) {
                        Text("Genres are loading from your sources…", color = Muted, fontSize = 12.sp)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            (if (genresExpanded) availableGenres else availableGenres.take(12)).forEach { genre ->
                                NoirGenreChip(
                                    genre, selected = genre in selectedGenres, excluded = genre in excludedGenres,
                                    onClick = { cycleGenre(genre) }
                                )
                            }
                        }
                        Text("Tap once to include, again to exclude, a third time to clear.", color = Muted, fontSize = 11.sp)
                    }
                    Text("Source", color = Color(0xFFE6DEEF), fontSize = 15.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf("ALL SOURCES") + availableSources).forEach { option -> NoirGenreChip(option, sourceFilter == option, false, { sourceFilter = option }) }
                    }
                    if (availableStatuses.isNotEmpty()) {
                        Text("Publication status", color = Color(0xFFE6DEEF), fontSize = 15.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            (listOf("ANY STATUS") + availableStatuses).forEach { option ->
                                NoirGenreChip(option.replaceFirstChar { it.uppercase() }, selectedStatus == option, false, { selectedStatus = option })
                            }
                        }
                    }
                    if (availableLanguages.isNotEmpty()) {
                        Text("Original language", color = Color(0xFFE6DEEF), fontSize = 15.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            (listOf("ANY LANGUAGE") + availableLanguages).forEach { option ->
                                NoirGenreChip(option.uppercase(), selectedLanguage == option, false, { selectedLanguage = option })
                            }
                        }
                    }
                    Text("Exclude words (comma separated)", color = Color(0xFFE6DEEF), fontSize = 15.sp)
                    OutlinedTextField(
                        value = excludedWords, onValueChange = { excludedWords = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. harem, horror", color = Muted) }, singleLine = true, shape = RoundedCornerShape(16.dp)
                    )
                    Text("Genres are matched by each source. Sources that don't publish a genre list may not match every filter.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).clip(CircleShape).border(1.dp, Color(0xFF4A4254), CircleShape)
                            .clickable {
                                val presetName = query.takeIf { it.isNotBlank() } ?: selectedGenres.firstOrNull() ?: "Custom filters"
                                val preset = JSONObject()
                                    .put("name", presetName.take(24)).put("query", query).put("source", sourceFilter)
                                    .put("status", selectedStatus).put("language", selectedLanguage)
                                    .put("genres", selectedGenres.joinToString("|")).put("excluded", excludedGenres.joinToString("|"))
                                    .put("exclude", excludedWords)
                                val raw = preset.toString()
                                if (raw !in savedPresets) savedPresets.add(raw)
                            }.padding(vertical = 15.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Save", color = Color(0xFFD6C5F2), fontSize = 16.sp) }
                    Box(
                        Modifier.weight(1f).clip(CircleShape).clickable { clearFilters() }.border(1.dp, Color(0xFF4A4254), CircleShape).padding(vertical = 15.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Reset", color = Muted, fontSize = 16.sp) }
                    Box(
                        Modifier.weight(1.4f).clip(CircleShape).background(Color(0xFFF1EDF6)).clickable { showAdvancedFilters = false }.padding(vertical = 15.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Done · ${sortedCatalogue.size}", color = Color(0xFF17131D), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
                Spacer(Modifier.navigationBarsPadding().height(14.dp))
            }
        }
    }
}

@Composable
private fun DiscoveryCoverTile(
    series: Series, compact: Boolean, alternateHeight: Boolean,
    glowProgress: State<Float>, glowPaused: State<Boolean>, onClick: () -> Unit
) {
    val imageRatio = when {
        compact -> 0.72f
        alternateHeight -> 0.78f
        else -> 0.62f
    }
    val coverShape = RoundedCornerShape(if (compact) 14.dp else 20.dp)
    Column(Modifier.fillMaxWidth().noirPressScale(onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(imageRatio).clip(coverShape)
            .background(Color(0xFF1C1A21))
            .drawWithContent {
                drawContent()
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.10f),
                    cornerRadius = CornerRadius((if (compact) 14.dp else 20.dp).toPx()),
                    style = Stroke(width = 1.dp.toPx())
                )
                // The traveling glow is paused while the list moves; reading state here keeps it out of composition.
                if (!compact && !glowPaused.value) {
                    drawTravelingOutlineGlow(
                        cornerRadius = 20.dp.toPx(),
                        progress = glowProgress.value,
                        inset = 1.5.dp.toPx(),
                        arcFraction = 0.18f
                    )
                }
            }) {
            SeriesCover(series, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0x66000000))))
            if (!compact) {
                SourceRegistry.byId(series.sourceId)?.let { source ->
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp).noirGlass(CircleShape, 0.8f).padding(6.dp)) {
                        SourceIcon(source, Modifier.size(22.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(if (compact) 7.dp else 9.dp))
        Text(series.title, color = White, fontSize = if (compact) 11.sp else 13.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 2,
            lineHeight = if (compact) 13.sp else 16.sp, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 3.dp))
        if (!compact) {
            Spacer(Modifier.height(3.dp))
            Text(series.sourceName, color = Color(0xFFB8B0C2), fontSize = 10.sp,
                letterSpacing = .3.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 3.dp))
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Frosted-glass surface: translucent dark base, soft top light and a hairline edge. */
private fun Modifier.noirGlass(shape: androidx.compose.ui.graphics.Shape, strength: Float = 1f): Modifier =
    this.clip(shape)
        .background(Color(0x66060508))
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f * strength), Color.White.copy(alpha = 0.05f * strength))))
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.07f))), shape)

@Composable
private fun HomeSectionHeader(eyebrow: String, title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow, color = Color(0xFFB8A9D0), fontSize = 9.sp, letterSpacing = 2.2.sp)
            Spacer(Modifier.height(5.dp))
            Text(title, color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp)
        }
        if (action != null) {
            Row(
                Modifier.clip(CircleShape).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(action, color = Color(0xFFD6C5F2), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(3.dp))
                Icon(Icons.Rounded.ArrowForward, null, tint = Color(0xFFD6C5F2), modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun HomeScreen(catalogue: List<Series>, loading: Boolean, error: String?, recentlyRead: List<Series>, saved: List<Series>, progressBySeries: Map<String, Float>, readingHistory: Map<String, ReadingHistoryEntry>, onOpen: (Series) -> Unit, onResume: (Series) -> Unit, onTab: (String) -> Unit, background: NoirBackground = NoirBackground()) {
    // A fresh random set of popular manhwa every launch (falls back to anything popular if none are loaded).
    val manhwaPool = remember(catalogue) {
        catalogue.filter { item ->
            SourceRegistry.byId(item.sourceId)?.category?.contains("MANHWA", ignoreCase = true) == true ||
                item.tags.any { it.equals("manhwa", true) || it.equals("webtoon", true) }
        }
    }
    val heroIsManhwa = manhwaPool.size >= 3
    val featuredStories = remember(catalogue) { (if (heroIsManhwa) manhwaPool else catalogue).shuffled(java.util.Random(launchSeed)).take(5) }
    val heroPagerState = rememberPagerState(pageCount = { featuredStories.size.coerceAtLeast(1) })
    LaunchedEffect(featuredStories.size) {
        if (featuredStories.size > 1) {
            while (true) {
                delay(6500)
                heroPagerState.animateScrollToPage((heroPagerState.currentPage + 1) % featuredStories.size)
            }
        }
    }
    val listState = rememberLazyListState()
    val scrollPx by remember {
        derivedStateOf { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 100000f }
    }
    val glowLoop = rememberInfiniteTransition(label = "home-glow")
    val glowTravel by glowLoop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing)),
        label = "home-glow-progress"
    )
    val greeting = remember {
        when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Late night reading"
        }
    }
    val backdropStory = featuredStories.getOrNull(heroPagerState.currentPage) ?: recentlyRead.firstOrNull()
    val heroShape = RoundedCornerShape(32.dp)
    val seenReveals = remember { mutableSetOf<String>() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            NoirBackdrop(background, { scrollPx }, 560.dp)

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp, bottom = 34.dp),
                verticalArrangement = Arrangement.spacedBy(30.dp)
            ) {
                item {
                    Column(Modifier.noirReveal(seenReveals, "header", 40).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                NoirLogoMark(30.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("NOIR", color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.4.sp)
                            }
                            Box(
                                Modifier.size(42.dp).noirGlass(CircleShape).clickable { onTab("Profile") },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Person, contentDescription = "Profile", tint = Color(0xFFEDE6F4), modifier = Modifier.size(20.dp))
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(greeting.uppercase(), color = Color(0xFFC9B9E2), fontSize = 10.sp, letterSpacing = 2.6.sp)
                            Text(
                                "Find your\nnext world.", color = White, fontSize = 42.sp, lineHeight = 45.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().noirGlass(RoundedCornerShape(20.dp), 0.7f)
                                .clickable { onTab("Discover") }
                                .padding(horizontal = 16.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFFD0C4E2), modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(11.dp))
                            Text("Search stories, worlds, characters", color = Color(0xFFA8A3B0), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
                item {
                    HorizontalPager(
                        state = heroPagerState,
                        modifier = Modifier.fillMaxWidth().noirReveal(seenReveals, "hero", 160),
                        pageSpacing = 12.dp,
                        contentPadding = PaddingValues(horizontal = 20.dp)
                    ) { page ->
                        val story = featuredStories.getOrNull(page)
                        Box(
                            Modifier.fillMaxWidth().height(430.dp)
                                .graphicsLayer {
                                    val offset = (heroPagerState.currentPage - page) + heroPagerState.currentPageOffsetFraction
                                    val distance = kotlin.math.abs(offset).coerceIn(0f, 1f)
                                    alpha = 1f - distance * 0.28f
                                    val settle = 1f - distance * 0.05f
                                    scaleX = settle
                                    scaleY = settle
                                }
                                .clip(heroShape)
                                .background(Brush.linearGradient(listOf(Color(0xFF30283B), Color(0xFF111014))))
                                .clickable { story?.let(onOpen) }
                                .drawWithContent {
                                    drawContent()
                                    drawRoundRect(
                                        color = Color.White.copy(alpha = 0.14f),
                                        cornerRadius = CornerRadius(32.dp.toPx()),
                                        style = Stroke(width = 1.dp.toPx())
                                    )
                                    if (page == heroPagerState.currentPage) {
                                        drawTravelingOutlineGlow(
                                            cornerRadius = 32.dp.toPx(), progress = glowTravel,
                                            inset = 1.5.dp.toPx(), arcFraction = 0.14f, strokeWidth = 1.4.dp.toPx()
                                        )
                                    }
                                }
                        ) {
                            story?.let {
                                SeriesCover(
                                    it,
                                    Modifier.fillMaxSize().graphicsLayer {
                                        val offset = (heroPagerState.currentPage - page) + heroPagerState.currentPageOffsetFraction
                                        scaleX = 1.18f
                                        scaleY = 1.18f
                                        translationX = offset * size.width * 0.16f
                                    }
                                )
                            }
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x26070709), 0.45f to Color.Transparent, 1f to Color(0xCC070709))))
                            Row(
                                Modifier.align(Alignment.TopStart).padding(16.dp)
                                    .noirGlass(RoundedCornerShape(30.dp), 0.8f)
                                    .padding(horizontal = 11.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFFD6C4F1)))
                                Spacer(Modifier.width(7.dp))
                                Text(if (heroIsManhwa) "POPULAR MANHWA" else "EDITOR'S PICK", color = Color(0xFFEFE8F7), fontSize = 9.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium)
                            }
                            Column(
                                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp)
                                    .noirGlass(RoundedCornerShape(24.dp))
                                    .padding(horizontal = 17.dp, vertical = 16.dp)
                            ) {
                                Text(
                                    story?.title ?: "A universe beyond the page",
                                    color = White, fontSize = 23.sp, lineHeight = 27.sp,
                                    fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    story?.description?.takeIf { it.isNotBlank() }?.let {
                                        if (it.length > 110) it.take(110).substringBeforeLast(" ") + "…" else it
                                    } ?: "One story. A thousand places to disappear.",
                                    color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, lineHeight = 17.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(14.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Row(
                                        Modifier.clip(CircleShape).background(Color(0xFFF1EDF6))
                                            .clickable { story?.let(onOpen) }
                                            .padding(horizontal = 18.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, null, tint = Color(0xFF17131D), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Read now", color = Color(0xFF17131D), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                        repeat(featuredStories.size.coerceAtLeast(1)) { index ->
                                            val active = heroPagerState.currentPage == index
                                            Box(
                                                Modifier.width(if (active) 18.dp else 5.dp).height(5.dp)
                                                    .animateContentSize(tween(260))
                                                    .clip(CircleShape)
                                                    .background(if (active) Color.White else Color.White.copy(alpha = 0.3f))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (recentlyRead.isNotEmpty()) {
                    item {
                        val latest = recentlyRead.first()
                        val history = readingHistory["${latest.sourceId}:${latest.id}"]
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            HomeContinueCard(latest, history, progressBySeries["${latest.sourceId}:${latest.id}"] ?: 0f, onResume = { onResume(latest) }, onOpenDetail = { onOpen(latest) })
                        }
                    }
                    if (recentlyRead.size > 1) item {
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            HomeSeriesShelf("YOUR READING LIST", "MORE FROM YOUR JOURNEY", recentlyRead.drop(1).take(9), "CONTINUE", progressBySeries, onResume)
                        }
                    }
                }
                item {
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        HomeSectionHeader("THE CURRENT", "Stories in motion", "See all") { onTab("Discover") }
                    }
                }
                when {
                    loading && catalogue.isEmpty() -> item { Box(Modifier.padding(horizontal = 20.dp)) { LoadingState("Preparing your reading room…") } }
                    error != null && catalogue.isEmpty() -> item { Box(Modifier.padding(horizontal = 20.dp)) { ErrorState(error) } }
                    catalogue.isEmpty() -> item { Box(Modifier.padding(horizontal = 20.dp)) { EmptyState("The shelves are quiet", "Open Discover to explore the catalogue.") } }
                    else -> item {
                        val shelfState = rememberLazyListState()
                        LazyRow(
                            modifier = Modifier.noirReveal(seenReveals, "shelf", 280),
                            state = shelfState,
                            flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(shelfState),
                            horizontalArrangement = Arrangement.spacedBy(15.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp)
                        ) {
                            items(catalogue.take(12), key = { "${it.sourceId}-${it.id}" }) { series ->
                                Column(Modifier.width(150.dp).noirPressScale { onOpen(series) }) {
                                    Box(
                                        Modifier.fillMaxWidth().height(214.dp)
                                            .clip(RoundedCornerShape(24.dp))
                                            .background(Color(0xFF201D26))
                                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
                                    ) {
                                        SeriesCover(series, Modifier.fillMaxSize())
                                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0x99000000))))
                                        SourceRegistry.byId(series.sourceId)?.let { source ->
                                            Row(
                                                Modifier.align(Alignment.BottomStart).padding(9.dp)
                                                    .noirGlass(RoundedCornerShape(20.dp), 0.8f)
                                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                SourceIcon(source, Modifier.size(13.dp))
                                                Spacer(Modifier.width(5.dp))
                                                Text(series.sourceName, color = Color.White.copy(alpha = 0.9f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 90.dp))
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Text(series.title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 17.sp, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if (saved.isNotEmpty()) item {
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        HomeSeriesShelf("YOUR COLLECTION", "SAVED TO YOUR LIBRARY", saved.take(10), "VIEW TITLE", progressBySeries, onOpen)
                    }
                }
                item {
                    Row(
                        Modifier.padding(horizontal = 20.dp).fillMaxWidth()
                            .noirGlass(RoundedCornerShape(26.dp), 0.9f)
                            .clickable { onTab("Discover") }
                            .padding(21.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("BEYOND THE LAST PAGE", color = Color(0xFFD0C4E2), fontSize = 9.sp, letterSpacing = 2.sp)
                            Spacer(Modifier.height(7.dp))
                            Text("Find your next obsession.", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.3).sp)
                            Spacer(Modifier.height(4.dp))
                            Text("Explore the NOIR catalogue", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                        NoirMascot(NoirMascotKind.SCARF, 64.dp)
                    }
                }
                item { Text("NOIR  •  MANGA  •  MANHWA  •  MORE", color = Color(0xFF625D69), fontSize = 8.sp, letterSpacing = 2.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            }

            // Compact title bar that fades in once the large header has scrolled away.
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(46.dp)
                    .graphicsLayer { alpha = ((scrollPx - 150f) / 120f).coerceIn(0f, 1f) }
                    .background(Color(0xEB070709)),
                contentAlignment = Alignment.Center
            ) {
                Text("NOIR", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.6.sp)
            }
        }
        BottomNav("Home", onTab)
    }
}


private val GlowLeadColor = Color(0xFF6F7CFF)
private val GlowTailColor = Color(0xFFD24DFF)

/**
 * Draws a lens-shaped highlight that travels along a rounded-rectangle border:
 * a white-hot core that tapers into blue and magenta ends, with a soft halo.
 * Only the highlight moves; the content itself never rotates.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTravelingOutlineGlow(
    cornerRadius: Float,
    progress: Float,
    inset: Float,
    arcFraction: Float = 0.22f,
    strokeWidth: Float = 1.5.dp.toPx()
) {
    val left = inset
    val top = inset
    val right = size.width - inset
    val bottom = size.height - inset
    if (right <= left || bottom <= top) return
    val radius = cornerRadius.coerceIn(0f, minOf(right - left, bottom - top) / 2f)

    val outline = android.graphics.Path().apply {
        addRoundRect(android.graphics.RectF(left, top, right, bottom), radius, radius, android.graphics.Path.Direction.CW)
    }
    val measure = android.graphics.PathMeasure(outline, false)
    val perimeter = measure.length
    if (perimeter <= 0f) return

    val arcLength = perimeter * arcFraction.coerceIn(0.05f, 0.6f)
    val segments = 16
    val segmentLength = arcLength / segments
    val startDistance = progress * perimeter - arcLength / 2f
    val segment = android.graphics.Path()
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.BUTT
    }
    val canvas = drawContext.canvas.nativeCanvas

    for (i in 0 until segments) {
        val t = (i + 0.5f) / segments
        val lens = kotlin.math.sin(Math.PI * t).toFloat()
        if (lens < 0.02f) continue
        val from = (((startDistance + i * segmentLength) % perimeter) + perimeter) % perimeter
        val to = from + segmentLength
        segment.reset()
        if (to <= perimeter) {
            measure.getSegment(from, to, segment, true)
        } else {
            measure.getSegment(from, perimeter, segment, true)
            measure.getSegment(0f, to - perimeter, segment, true)
        }
        val tint = androidx.compose.ui.graphics.lerp(GlowTailColor, GlowLeadColor, t)
        val hot = androidx.compose.ui.graphics.lerp(tint, Color.White, Math.pow(lens.toDouble(), 2.5).toFloat())

        paint.strokeWidth = strokeWidth * 6f
        paint.color = tint.copy(alpha = 0.10f * lens).toArgb()
        canvas.drawPath(segment, paint)
        paint.strokeWidth = strokeWidth * 3f
        paint.color = tint.copy(alpha = 0.24f * lens).toArgb()
        canvas.drawPath(segment, paint)
        paint.strokeWidth = strokeWidth * (0.7f + 0.5f * lens)
        paint.color = hot.copy(alpha = 0.25f + 0.75f * lens).toArgb()
        canvas.drawPath(segment, paint)
    }
}

@Composable
private fun NoirGlowButton(
    label: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    // All motion here uses stable Compose animation and drawing APIs:
    // an endlessly moving, narrow sweep highlight with a soft violet edge and bright core.
    val transition = rememberInfiniteTransition(label = "noir-glow-border")
    val glowProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "noir-glow-travel-progress"
    )
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF08070B))
            .drawBehind {
                val radius = CornerRadius(size.height / 2f - 1.dp.toPx())
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.09f),
                    topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()),
                    cornerRadius = radius,
                    style = Stroke(width = 1.dp.toPx())
                )
                drawTravelingOutlineGlow(
                    cornerRadius = size.height / 2f - 1.5.dp.toPx(),
                    progress = glowProgress,
                    inset = 1.5.dp.toPx(),
                    arcFraction = 0.26f,
                    strokeWidth = 1.4.dp.toPx()
                )
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
        translationY = if (pressed) 1.5f else 0f
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
private fun ReleaseRadarScreen(
    saved: List<Series>,
    catalogue: List<Series>,
    results: List<RadarSeriesResult>,
    loading: Boolean,
    lastChecked: Long,
    error: String?,
    onRefresh: () -> Unit,
    onOpen: (Series) -> Unit,
    onBack: () -> Unit
) {
    val followedKeys = saved.map { "${it.sourceId}:${it.id}" }.toSet()
    val likedTags = saved.flatMap { it.tags }.map { it.lowercase() }.toSet()
    val recommendations = remember(catalogue, followedKeys, likedTags) {
        catalogue.asSequence()
            .filter { "${it.sourceId}:${it.id}" !in followedKeys }
            .distinctBy { "${it.sourceId}:${it.id}" }
            .map { series ->
                val overlap = series.tags.count { it.lowercase() in likedTags }
                series to overlap
            }
            .sortedWith(compareByDescending<Pair<Series, Int>> { it.second }.thenBy { it.first.title })
            .take(8)
            .toList()
    }
    val freshCount = results.sumOf { it.newChapters.size }
    val checkedLabel = if (lastChecked > 0L) {
        java.text.SimpleDateFormat("MMM d · HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastChecked))
    } else "NOT CHECKED YET"

    Column(Modifier.fillMaxSize().background(Black).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null, tint = White) }
            Column(Modifier.weight(1f)) {
                Text("NOIR / FOLLOWING", color = Muted, fontSize = 8.sp, letterSpacing = 1.8.sp)
                Text("Release Radar", color = White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            }
            Icon(Icons.Rounded.NotificationsActive, null, tint = Color(0xFFD6C5F2), modifier = Modifier.padding(end = 8.dp))
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF30243D), Color(0xFF17141D), Color(0xFF101015))))
                        .border(1.dp, Color(0xFF57446B), RoundedCornerShape(23.dp)).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    Text("YOUR SERIES, IN SYNC", color = Color(0xFFD6C5F2), fontSize = 8.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(freshCount.toString(), color = White, fontSize = 42.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(9.dp))
                        Text("NEW CHAPTERS FOUND", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    Text("${saved.size} followed series · Last check: $checkedLabel", color = Muted, fontSize = 9.sp)
                    Button(
                        onClick = onRefresh,
                        enabled = !loading && saved.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD6C5F2), contentColor = Color(0xFF17121F))
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Color(0xFF17121F), strokeWidth = 2.dp)
                            Spacer(Modifier.width(9.dp))
                            Text("CHECKING YOUR SOURCES", fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("CHECK FOR NEW CHAPTERS", fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("Checks each followed title against its source when you ask. It does not run background polling.", color = Color(0xFFAAA2B4), fontSize = 8.sp, lineHeight = 13.sp)
                }
            }
            if (error != null) item {
                Text(error, color = Color(0xFFFFB4AB), fontSize = 10.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF321C20)).padding(12.dp))
            }
            if (saved.isEmpty()) item {
                EmptyState("Your radar is quiet", "Save a few series first. NOIR will compare their latest chapter lists when you run a check.")
            }
            if (results.isNotEmpty()) {
                item {
                    Text("FOLLOWED SERIES", color = Silver, fontSize = 9.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
                }
                items(results, key = { "radar-${it.series.sourceId}-${it.series.id}" }) { result ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
                            .background(Panel).border(1.dp, Color(0xFF302C35), RoundedCornerShape(17.dp))
                            .clickable { onOpen(result.series) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(52.dp, 68.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF28232F))) {
                            SeriesCover(result.series, Modifier.fillMaxSize())
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(result.series.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(result.series.sourceName.uppercase(), color = Muted, fontSize = 7.sp, letterSpacing = 1.sp)
                            when {
                                result.error != null -> Text("SOURCE UNAVAILABLE · ${result.error}", color = Color(0xFFFFB4AB), fontSize = 8.sp, maxLines = 2)
                                result.newChapters.isNotEmpty() -> Text("${result.newChapters.size} NEW · ${result.newChapters.take(2).joinToString { it.name }}", color = Color(0xFFD6C5F2), fontSize = 8.sp, maxLines = 2)
                                result.chapters.isNotEmpty() -> Text("UP TO DATE · ${result.chapters.size} CHAPTERS", color = Color(0xFF9ECAA9), fontSize = 8.sp)
                                else -> Text("NO CHAPTER DATA RETURNED", color = Muted, fontSize = 8.sp)
                            }
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
                    }
                }
            }
            if (recommendations.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("PICKS FOR YOUR NEXT READ", color = Silver, fontSize = 9.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
                        Text(if (likedTags.isNotEmpty()) "Ranked by genre overlap with your followed titles." else "Explore more from the catalogue. Genre matching improves as source metadata becomes available.", color = Muted, fontSize = 8.sp)
                    }
                }
                items(recommendations, key = { "radar-pick-${it.first.sourceId}-${it.first.id}" }) { (series, overlap) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Color(0xFF121216))
                            .clickable { onOpen(series) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(45.dp, 59.dp).clip(RoundedCornerShape(7.dp)).background(Panel)) {
                            SeriesCover(series, Modifier.fillMaxSize())
                        }
                        Column(Modifier.weight(1f).padding(start = 11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(series.title, color = White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (overlap > 0) "$overlap SHARED GENRES" else series.sourceName.uppercase(), color = Color(0xFFBFAED3), fontSize = 7.sp, letterSpacing = .8.sp)
                        }
                        Icon(Icons.Rounded.ArrowForward, null, tint = Muted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun WeeklyActivityChart(activity: List<Int>) {
    val labels = listOf("M", "T", "W", "T", "F", "S", "S")
    val maxValue = (activity.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF121216)).border(1.dp, Color(0xFF2B2930), RoundedCornerShape(18.dp))
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("WEEKLY READING ACTIVITY", color = Silver, fontSize = 8.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                Text("${activity.sum()} reading sessions logged", color = Muted, fontSize = 9.sp)
            }
            Icon(Icons.Rounded.QueryStats, null, tint = Color(0xFFD6C5F2), modifier = Modifier.size(20.dp))
        }
        Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            activity.forEachIndexed { index, value ->
                val fraction = (value.toFloat() / maxValue).coerceIn(0f, 1f)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            Modifier.fillMaxWidth(0.62f).fillMaxHeight(fraction.coerceAtLeast(if (value > 0) 0.12f else 0f))
                                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                .then(
                                    if (value > 0) Modifier.background(Brush.verticalGradient(listOf(Color(0xFFD6C5F2), Color(0xFF65527D))))
                                    else Modifier.background(Color(0xFF29262F))
                                )
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(labels[index], color = Muted, fontSize = 8.sp)
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(introSound: Boolean, launchIntro: Boolean, matureContentEnabled: Boolean, savedCount: Int, trackedCount: Int, averageProgress: Int, latestTitle: String, activityLabel: String, weeklyActivity: List<Int>, onRadar: () -> Unit, onExportBackup: () -> Unit, onImportBackup: () -> Unit, onIntroSound: (Boolean) -> Unit, onLaunchIntro: (Boolean) -> Unit, onMatureContent: (Boolean) -> Unit, onAbout: () -> Unit, onWhatsNew: () -> Unit, onTab: (String) -> Unit,
    backgroundLabel: String = "Starry night", onPickBackground: () -> Unit = {}, onResetBackground: () -> Unit = {},
    introStyle: String = "panels", onIntroStyle: (String) -> Unit = {}) {
    var showMatureConfirmation by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
      Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
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
            Text(rememberAppVersion(), color = Silver, fontSize = 9.sp)
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(21.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF241D2C), Color(0xFF151319))))
            .border(1.dp, Color(0xFF403447), RoundedCornerShape(21.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("READING INTELLIGENCE", color = Color(0xFFCAB6E0), fontSize = 8.sp,
                        letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
                    Text("Your reading snapshot", color = White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Icon(Icons.Rounded.AutoGraph, null, tint = Color(0xFFD9C8EF), modifier = Modifier.size(22.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                ProfileStatTile("SAVED TITLES", savedCount.toString(), Modifier.weight(1f))
                ProfileStatTile("TRACKED STORIES", trackedCount.toString(), Modifier.weight(1f))
                ProfileStatTile("AVG. PROGRESS", "$averageProgress%", Modifier.weight(1f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("LAST READING ACTIVITY", color = Muted, fontSize = 7.sp, letterSpacing = 1.2.sp)
                Text(if (latestTitle.isBlank()) "Your next chapter starts here" else latestTitle,
                    color = White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(activityLabel, color = Color(0xFFC9B4E0), fontSize = 8.sp, letterSpacing = .8.sp)
            }
        }
        Spacer(Modifier.height(13.dp))
        WeeklyActivityChart(weeklyActivity)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(Color(0xFF252033), Color(0xFF17151D)))).border(1.dp, Color(0xFF40344F), RoundedCornerShape(15.dp)).clickable(onClick = onRadar).padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.NotificationsActive, null, tint = Color(0xFFD6C5F2))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Release Radar", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Check followed series for newly released chapters", color = Muted, fontSize = 9.sp)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
        }
        Spacer(Modifier.height(12.dp))
        Spacer(Modifier.height(22.dp))
        Text("APP SETTINGS", color = Silver, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Home & Discover background", color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(backgroundLabel + "  ·  image, GIF or looping video", color = Muted, fontSize = 10.sp)
            }
            Text("Choose", color = Color(0xFFD6C5F2), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(Color(0x33FFFFFF)).clickable(onClick = onPickBackground).padding(horizontal = 14.dp, vertical = 8.dp))
            Spacer(Modifier.width(8.dp))
            Text("Reset", color = Muted, fontSize = 12.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onResetBackground).padding(horizontal = 10.dp, vertical = 8.dp))
        }
        SettingSwitch("Intro sound", "Play the short chime when NOIR opens", introSound, onIntroSound)
        SettingSwitch("Launch intro", "Play the NOIR intro every time the app opens", launchIntro, onLaunchIntro)
        Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
            Text("Intro style", color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("Choose how NOIR opens", color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("panels" to "Manga page", "horizon" to "Horizon", "glow" to "Glow sheet").forEach { (key, label) ->
                    NoirChip(label, introStyle == key, { onIntroStyle(key) })
                }
            }
        }
        SettingSwitch("Mature catalogues (18+)", "Show adult-rated titles from sources that provide them", matureContentEnabled, { enabled -> if (enabled) showMatureConfirmation = true else onMatureContent(false) })
        Spacer(Modifier.height(22.dp))
        Text("BACKUP & RESTORE", color = Silver, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedButton(onClick = onExportBackup, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Silver)) {
                Icon(Icons.Rounded.FileUpload, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(7.dp))
                Text("EXPORT", fontSize = 9.sp, letterSpacing = 1.sp)
            }
            OutlinedButton(onClick = onImportBackup, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Silver)) {
                Icon(Icons.Rounded.FileDownload, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(7.dp))
                Text("RESTORE", fontSize = 9.sp, letterSpacing = 1.sp)
            }
        }
        Text("Backs up NOIR library, progress, reader settings, source settings and discovery preferences. Restore replaces those local preferences.", color = Muted, fontSize = 8.sp, lineHeight = 13.sp)
        Spacer(Modifier.height(22.dp))
        Text("INFORMATION", color = Silver, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Panel).clickable { onTab("Studio") }.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Palette, null, tint = Color(0xFFD6C5F2))
            Text("Customization Studio", color = White, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Panel).clickable(onClick = onWhatsNew).padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Color(0xFFD6C5F2))
            Text("What’s New in NOIR", color = White, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Text(rememberAppVersion(), color = Color(0xFFD6C5F2), fontSize = 9.sp)
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
    }
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
private fun ProfileStatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(13.dp)).background(Color(0xFF17141C))
        .border(1.dp, Color(0xFF342B3B), RoundedCornerShape(13.dp)).padding(horizontal = 9.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(value, color = White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Muted, fontSize = 6.sp, letterSpacing = .55.sp, maxLines = 1)
    }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailScreen(
    series: Series, chapters: List<Chapter>, saved: Boolean, bookmarkedChapterKeys: Set<String>,
    downloadedChapterKeys: Set<String>, downloadingChapterKey: String?, downloadProgress: Int,
    readingHistoryEntry: ReadingHistoryEntry?, loading: Boolean, error: String?,
    onRefreshChapters: () -> Unit,
    onBack: () -> Unit, onSave: () -> Unit, onToggleChapterBookmark: (Chapter) -> Unit,
    onChapter: (Chapter) -> Unit, onDownloadChapter: (Chapter) -> Unit,
    onDeleteDownload: (Chapter) -> Unit,
    onGenreClick: (String) -> Unit = {}
) {
    var pendingDelete by remember(series.id) { mutableStateOf<Chapter?>(null) }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove download?") },
            text = { Text("\"${target.name}\" will be deleted from this device. You can download it again any time.", color = Muted) },
            confirmButton = { TextButton(onClick = { onDeleteDownload(target); pendingDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
            containerColor = Panel
        )
    }
    var synopsisExpanded by remember(series.id) { mutableStateOf(false) }
    var chapterQuery by remember(series.id) { mutableStateOf("") }
    var chapterLanguage by remember(series.id) { mutableStateOf("ALL") }
    var chapterSort by remember(series.id) { mutableStateOf("SOURCE ORDER") }
    var showBookmarkedOnly by remember(series.id) { mutableStateOf(false) }
    val bookmarkedCount = chapters.count { "${series.sourceId}:${it.id}" in bookmarkedChapterKeys }
    val availableChapterLanguages = remember(chapters) { chapters.map { it.language }.filter { it.isNotBlank() }.distinct().sorted() }
    val visibleChapters = remember(chapters, chapterQuery, chapterLanguage, chapterSort, showBookmarkedOnly, bookmarkedChapterKeys) {
        val matching = chapters.filter { chapter ->
            (!showBookmarkedOnly || "${series.sourceId}:${chapter.id}" in bookmarkedChapterKeys) &&
                (chapterLanguage == "ALL" || chapter.language.equals(chapterLanguage, ignoreCase = true)) &&
                (chapterQuery.isBlank() || chapter.name.contains(chapterQuery, ignoreCase = true) ||
                    chapter.number.contains(chapterQuery, ignoreCase = true) || chapter.language.contains(chapterQuery, ignoreCase = true))
        }
        when (chapterSort) {
            "NEWEST" -> matching.sortedWith(compareByDescending<Chapter> { it.sourceUploadDate > 0L }.thenByDescending { it.sourceUploadDate })
            "OLDEST" -> matching.sortedWith(compareByDescending<Chapter> { it.sourceUploadDate > 0L }.thenBy { it.sourceUploadDate })
            else -> matching
        }
    }
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
                val heroGlow = rememberInfiniteTransition(label = "hero-cover-glow")
                val heroGlowProgress by heroGlow.animateFloat(
                    initialValue = 0f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing)),
                    label = "hero-cover-glow-progress"
                )
                Box(
                    Modifier.fillMaxWidth().height(340.dp)
                        .background(Color(0xFF17151C))
                        .drawWithContent {
                            drawContent()
                            drawTravelingOutlineGlow(
                                cornerRadius = 28.dp.toPx(),
                                progress = heroGlowProgress,
                                inset = 9.dp.toPx(),
                                arcFraction = 0.2f,
                                strokeWidth = 1.6.dp.toPx()
                            )
                        }
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
                            val resumeChapter = readingHistoryEntry?.let { history ->
                                chapters.firstOrNull { it.id == history.chapterId }
                            }
                            NoirGlowButton(
                                if (resumeChapter != null) "CONTINUE READING" else "OPEN FIRST LISTED CHAPTER",
                                onClick = { onChapter(resumeChapter ?: chapters.first()) }
                            )
                            if (resumeChapter != null && readingHistoryEntry != null) {
                                Spacer(Modifier.height(7.dp))
                                val chapterProgress = (readingHistoryEntry.pageNumber.toFloat() / readingHistoryEntry.pageCount.coerceAtLeast(1)).coerceIn(0f, 1f)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${readingHistoryEntry.chapterName.uppercase()}  ·  PAGE ${readingHistoryEntry.pageNumber}/${readingHistoryEntry.pageCount}",
                                        color = Color(0xFFB8A9C8), fontSize = 8.sp, letterSpacing = .7.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text("${(chapterProgress * 100).roundToInt()}%", color = Color(0xFFD6C5F2), fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(Modifier.height(7.dp))
                                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF39333F))) {
                                    Box(Modifier.fillMaxWidth(chapterProgress).fillMaxHeight().background(
                                        Brush.horizontalGradient(listOf(Color(0xFF78618E), Color(0xFFD6C5F2))), RoundedCornerShape(3.dp)
                                    ))
                                }
                            }
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
            if (series.tags.isNotEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 25.dp).padding(bottom = 20.dp)) {
                        Text("GENRES  ·  TAP TO BROWSE", color = Color(0xFFD6C5F2), fontSize = 9.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(11.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            series.tags.forEach { tag -> NoirGenreChip(tag, selected = false, excluded = false, onClick = { onGenreClick(tag) }) }
                        }
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
                        Spacer(Modifier.height(5.dp))
                        Row(
                            modifier = Modifier.clip(RoundedCornerShape(18.dp))
                                .background(Color(0xFF211D28))
                                .border(1.dp, Color(0xFF51445F), RoundedCornerShape(18.dp))
                                .clickable(enabled = !loading, onClick = onRefreshChapters)
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (loading && chapters.isNotEmpty()) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    color = Color(0xFFD6C5F2),
                                    strokeWidth = 1.5.dp
                                )
                            } else {
                                Icon(Icons.Rounded.Refresh, null, tint = Color(0xFFD6C5F2), modifier = Modifier.size(11.dp))
                            }
                            Text(
                                if (loading && chapters.isNotEmpty()) "CHECKING" else "REFRESH",
                                color = Color(0xFFD6C5F2), fontSize = 7.sp, letterSpacing = .7.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    OutlinedTextField(
                        value = chapterQuery, onValueChange = { chapterQuery = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Find a chapter…", color = Muted, fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Silver, modifier = Modifier.size(18.dp)) },
                        trailingIcon = { if (chapterQuery.isNotEmpty()) IconButton(onClick = { chapterQuery = "" }) { Icon(Icons.Rounded.Close, null, tint = Muted) } },
                        singleLine = true, shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF8B7A9B), unfocusedBorderColor = Color(0xFF39333F),
                            focusedContainerColor = Color(0xCC19171E), unfocusedContainerColor = Color(0xCC141318),
                            focusedTextColor = White, unfocusedTextColor = White, cursorColor = Silver)
                    )
                    if (availableChapterLanguages.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            items(listOf("ALL") + availableChapterLanguages) { language ->
                                val active = chapterLanguage.equals(language, ignoreCase = true)
                                Box(Modifier.clip(RoundedCornerShape(30.dp))
                                    .background(if (active) Color(0xFF342B3D) else Color(0xFF151419))
                                    .border(1.dp, if (active) Color(0xFF665572) else Color(0xFF302C35), RoundedCornerShape(30.dp))
                                    .clickable { chapterLanguage = language }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(if (language == "ALL") "ALL LANGUAGES" else language.uppercase(),
                                        color = if (active) Color(0xFFE9DFF2) else Muted, fontSize = 8.sp, letterSpacing = .6.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SORT BY", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
                        Spacer(Modifier.width(9.dp))
                        listOf("SOURCE ORDER", "NEWEST", "OLDEST").forEach { mode ->
                            val active = chapterSort == mode
                            Box(Modifier.padding(end = 6.dp).clip(RoundedCornerShape(20.dp))
                                .background(if (active) Color(0xFF30283A) else Color(0xFF151419))
                                .clickable { chapterSort = mode }.padding(horizontal = 9.dp, vertical = 7.dp)) {
                                Text(mode, color = if (active) Color(0xFFE4D8F0) else Muted, fontSize = 7.sp, letterSpacing = .45.sp)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clip(RoundedCornerShape(20.dp))
                            .background(if (showBookmarkedOnly) Color(0xFF342B3D) else Color(0xFF151419))
                            .border(1.dp, if (showBookmarkedOnly) Color(0xFF665572) else Color(0xFF302C35), RoundedCornerShape(20.dp))
                            .clickable { showBookmarkedOnly = !showBookmarkedOnly }
                            .padding(horizontal = 11.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Rounded.Bookmark, null,
                                    tint = if (showBookmarkedOnly) Color(0xFFE9DFF2) else Muted,
                                    modifier = Modifier.size(12.dp))
                                Text("BOOKMARKED ONLY  ·  $bookmarkedCount",
                                    color = if (showBookmarkedOnly) Color(0xFFE9DFF2) else Muted,
                                    fontSize = 7.sp, letterSpacing = .55.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Text("${visibleChapters.size} OF ${chapters.size} CHAPTERS", color = Color(0xFF77717F), fontSize = 7.sp, letterSpacing = 1.sp)
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
            } else if (!loading && error == null && visibleChapters.isEmpty()) {
                item {
                    EmptyState(
                        if (showBookmarkedOnly) "No bookmarked chapters" else "No matching chapters",
                        if (showBookmarkedOnly) "Bookmark a chapter with the icon beside it, then find it here." else "Try a different search or language filter."
                    )
                }
            }
            items(visibleChapters, key = { it.id }) { chapter ->
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
                            onClick = { if (isDownloaded) pendingDelete = chapter else onDownloadChapter(chapter) },
                            enabled = !isDownloading && (isDownloaded || downloadingChapterKey == null)
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

/** Remembers each page's real aspect ratio so its slot keeps the right height before and after it loads. */
private object PageAspectCache {
    private val ratios = java.util.concurrent.ConcurrentHashMap<String, Float>()
    fun get(url: String): Float? = ratios[url]
    fun put(url: String, ratio: Float) { if (ratio > 0.05f && ratio < 20f) ratios[url] = ratio }
}

private fun readerPageRequest(context: Context, url: String, widthPx: Int): ImageRequest =
    ImageRequest.Builder(context).data(url)
        .size(coil.size.Size(coil.size.Dimension.Pixels(widthPx), coil.size.Dimension.Undefined))
        .build()

/** A longer, steadier glide after the finger lifts (exponential decay with reduced friction). */
private class NoirFlingBehavior(
    private val decay: androidx.compose.animation.core.DecayAnimationSpec<Float>
) : androidx.compose.foundation.gestures.FlingBehavior {
    override suspend fun androidx.compose.foundation.gestures.ScrollScope.performFling(initialVelocity: Float): Float {
        if (kotlin.math.abs(initialVelocity) <= 1f) return initialVelocity
        var lastValue = 0f
        var remaining = initialVelocity
        androidx.compose.animation.core.AnimationState(0f, initialVelocity).animateDecay(decay) {
            val delta = value - lastValue
            val consumed = scrollBy(delta)
            lastValue = value
            remaining = velocity
            if (kotlin.math.abs(delta - consumed) > 0.5f) cancelAnimation()
        }
        return remaining
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderPageImage(pageUrl: String, modifier: Modifier = Modifier, contentScale: ContentScale, reserveHeight: Boolean = false, onTap: () -> Unit = {}, onDoubleTap: (() -> Unit)? = null) {
    var zoom by remember(pageUrl) { mutableFloatStateOf(1f) }
    var panX by remember(pageUrl) { mutableFloatStateOf(0f) }
    var panY by remember(pageUrl) { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    var ratio by remember(pageUrl) { mutableStateOf(PageAspectCache.get(pageUrl)) }
    var attempt by remember(pageUrl) { mutableIntStateOf(0) }
    var failure by remember(pageUrl) { mutableStateOf<String?>(null) }
    // Attempt 0 is the fast sized request; later attempts use a plain, software-decoded request as a fallback.
    val model: Any = remember(pageUrl, attempt) {
        if (attempt == 0) {
            if (reserveHeight) readerPageRequest(context, pageUrl, context.resources.displayMetrics.widthPixels) else pageUrl
        } else {
            ImageRequest.Builder(context).data(pageUrl).allowHardware(false).setParameter("noir_attempt", attempt).build()
        }
    }
    Box {
    AsyncImage(model = model, contentDescription = "Comic page", contentScale = contentScale,
        onSuccess = { state ->
            failure = null
            val drawable = state.result.drawable
            if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                val measured = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                PageAspectCache.put(pageUrl, measured)
                ratio = measured
            }
        },
        onError = { state ->
            if (attempt == 0) attempt = 1
            else failure = state.result.throwable.message?.take(110) ?: "Unknown error"
        },
        modifier = modifier
            .then(
                if (reserveHeight) Modifier.aspectRatio(ratio ?: 0.72f)
                    .then(if (ratio == null) Modifier.background(Color(0xFF0E0D12)) else Modifier)
                else Modifier
            )
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
            .combinedClickable(
                onClick = onTap,
                onDoubleClick = {
                    if (onDoubleTap != null) onDoubleTap()
                    else if (zoom > 1.05f) { zoom = 1f; panX = 0f; panY = 0f }
                    else zoom = 2.25f
                }
            ))
    if (failure != null) {
        Column(
            Modifier.matchParentSize().background(Color(0xFF14111A)).clickable { failure = null; attempt += 1 }.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            NoirMascot(NoirMascotKind.SIT, 84.dp)
            Spacer(Modifier.height(10.dp))
            Text("This page couldn't load", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(failure.orEmpty(), color = Muted, fontSize = 11.sp, lineHeight = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text("TAP TO RETRY", color = Color(0xFFD6C5F2), fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
        }
    }
    }
}

private var carryScrollOffset = 0

@Composable
private fun ReaderScreen(
    series: Series?, chapter: Chapter, chapterList: List<Chapter>, pages: List<String>, loading: Boolean, error: String?,
    initialPage: Int, nextChapterReady: Boolean, nextPages: List<String>?, onSeamlessNext: (Int, Int) -> Unit, readerMode: String, gesturePreset: String, onModeChange: (String) -> Unit,
    onProgress: (Int) -> Unit, onNextChapter: () -> Unit, onBack: () -> Unit,
    motionEnabled: Boolean
) {
    val startOffset = remember { carryScrollOffset.also { carryScrollOffset = 0 } }
    val firstRestore = remember { booleanArrayOf(true) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage.coerceAtLeast(0), initialFirstVisibleItemScrollOffset = startOffset)
    val hasNextChapter = nextChapterIndex(chapterList, chapter.id) != null
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
    val readerCanvas = when (readerPrefs.getString("reader_background", "AMOLED")) {
        "CHARCOAL" -> Color(0xFF24242A)
        "WARM" -> Color(0xFF29241F)
        "PAPER" -> Color(0xFFE8E3D9)
        else -> Color.Black
    }
    val readerFontScale = context.getSharedPreferences("noir_preferences", Context.MODE_PRIVATE)
        .getFloat("studio_font_scale", 1f).coerceIn(.85f, 1.2f)
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
    val isFirstChapter = remember(chapter.id, chapterList) {
        val numberIsOne = chapter.number.toFloatOrNull() == 1f
        val hasPrevious = chapterList.any { c -> nextChapterIndex(chapterList, c.id)?.let { chapterList[it].id } == chapter.id }
        numberIsOne || !hasPrevious
    }
    LaunchedEffect(chapter.id, pages.size, loading) {
        if (pages.isNotEmpty() && !loading && initialPage <= 0 && isFirstChapter) {
            showChapterCinema = true
            delay(3000)
            showChapterCinema = false
        } else if (initialPage > 0) {
            showChapterCinema = false
        }
    }

    val latestOnProgress by rememberUpdatedState(onProgress)
    var lastKnownPage by remember { mutableIntStateOf(initialPage.coerceAtLeast(0)) }
    // Restore the position once when pages load or the mode changes, then track progress without
    // re-seeking: the old live `initialPage` key re-ran scrollToItem on every page and cut flings short.
    LaunchedEffect(pages.size, readerMode) {
        if (pages.isEmpty()) return@LaunchedEffect
        val target = lastKnownPage.coerceIn(0, pages.lastIndex)
        if (isPaged) pagerState.scrollToPage(target) else {
            listState.scrollToItem(target, if (firstRestore[0]) startOffset else 0)
            firstRestore[0] = false
        }
        snapshotFlow { if (isPaged) pagerState.currentPage else listState.firstVisibleItemIndex }
            .collect { current ->
                lastKnownPage = current.coerceIn(0, pages.lastIndex)
                latestOnProgress(lastKnownPage)
            }
    }
    val noirFling = remember { NoirFlingBehavior(androidx.compose.animation.core.exponentialDecay(frictionMultiplier = 0.6f)) }
    val currentPage = if (isPaged) pagerState.currentPage else listState.firstVisibleItemIndex
    var scrubPage by remember { mutableFloatStateOf(initialPage.coerceAtLeast(0).toFloat()) }
    LaunchedEffect(currentPage) { scrubPage = currentPage.toFloat() }
    val jumpToPage: (Int) -> Unit = { page ->
        if (pages.isNotEmpty()) readerScope.launch {
            val target = page.coerceIn(0, pages.lastIndex)
            if (isPaged) pagerState.animateScrollToPage(target) else listState.animateScrollToItem(target)
        }
    }

    // Keep nearby pages in Coil's cache so fast scrolling is less likely to show blanks.
    LaunchedEffect(currentPage, pages, isPaged) {
        if (pages.isNotEmpty()) {
            // Keep a wider reading buffer warm, especially for long webtoon strips.
            // This follows Kotatsu's page-loader approach: prepare nearby pages before
            // the user reaches them instead of making the scroll wait on network fetches.
            val widthPx = context.resources.displayMetrics.widthPixels
            val ahead = if (isPaged) 3 else 4
            val behind = 1
            val indices = buildList {
                for (index in (currentPage + 1)..minOf(pages.lastIndex, currentPage + ahead)) add(index)
                for (index in (currentPage - behind) until currentPage) if (index >= 0) add(index)
            }.distinct()
            // Load nearest-first and record real page heights before they scroll into view,
            // so pages never pop in and shift the layout mid-fling.
            indices.forEach { index ->
                val url = pages[index]
                val request = if (isPaged) ImageRequest.Builder(context).data(url).build() else readerPageRequest(context, url, widthPx)
                val result = imageLoader.execute(request)
                if (result is coil.request.SuccessResult) {
                    val d = result.drawable
                    if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) PageAspectCache.put(url, d.intrinsicWidth.toFloat() / d.intrinsicHeight)
                }
            }
        }
    }

    // In vertical/webtoon mode, advance only after the reader reaches the actual
    // bottom during a user-driven scroll. A per-chapter guard prevents duplicate loads.
    val nextChapterModel = nextChapterIndex(chapterList, chapter.id)?.let { chapterList[it] }
    LaunchedEffect(chapter.id, pages.size, nextPages?.size, isPaged) {
        if (!isPaged && !nextPages.isNullOrEmpty() && pages.isNotEmpty()) {
            snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress) }
                .collect { (index, offset, scrolling) ->
                    if (!scrolling && index > pages.size) onSeamlessNext(index - pages.size - 1, offset)
                }
        }
    }
    LaunchedEffect(chapter.id, pages.size, isPaged, hasNextChapter, nextPages == null) {
        if (!isPaged && pages.size > 1 && hasNextChapter && nextPages == null) {
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

    val movePage: (Int) -> Unit = { direction ->
        if (pages.isNotEmpty()) readerScope.launch {
            val target = (currentPage + direction).coerceIn(0, pages.lastIndex)
            if (isPaged) pagerState.animateScrollToPage(target) else listState.animateScrollToItem(target)
        }
    }

    Column(Modifier.fillMaxSize().background(readerCanvas).statusBarsPadding()) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
        ) {
            when {
                loading && pages.isEmpty() -> LoadingState("Loading chapter pages…")
                error != null -> ErrorState(error)
                pages.isEmpty() -> EmptyState("No pages available", "This chapter may not have readable pages.")
                isPaged -> androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState, modifier = Modifier.fillMaxSize(),
                    pageSpacing = 10.dp,
                    reverseLayout = readerMode == "Right to left", key = { index -> "reader-page-$index" }
                ) { index ->
                    val pageOffset = ((pagerState.currentPage - index) + pagerState.currentPageOffsetFraction).coerceIn(-1f, 1f)
                    val pageDistance = kotlin.math.abs(pageOffset)
                    ReaderPageImage(
                        pageUrl = pages[index],
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            alpha = 1f - (pageDistance * 0.12f)
                            scaleX = 1f - (pageDistance * 0.035f)
                            scaleY = 1f - (pageDistance * 0.035f)
                            translationX = pageOffset * -12.dp.toPx()
                            rotationY = if (motionEnabled) pageOffset * 5.5f else 0f
                            cameraDistance = 28f * density
                        }.clip(RoundedCornerShape(5.dp)),
                        contentScale = if (fitToWidth) ContentScale.FillWidth else ContentScale.Fit,
                        onTap = { if (!showChapterCinema && !showSettings && !showModeMenu) showReaderControls = !showReaderControls }
                    )
                }
                else -> {
                    var gZoom by remember { mutableFloatStateOf(1f) }
                    var gPanX by remember { mutableFloatStateOf(0f) }
                    val toggleZoom = { gZoom = if (gZoom > 1.05f || gZoom < 0.95f) 1f else 2f; gPanX = 0f }
                    Box(
                        Modifier.fillMaxSize().clipToBounds()
                            .pointerInput(Unit) {
                                androidx.compose.foundation.gestures.awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    do {
                                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                        val pressed = event.changes.count { it.pressed }
                                        if (pressed >= 2) {
                                            val z = event.calculateZoom()
                                            val pan = event.calculatePan()
                                            gZoom = (gZoom * z).coerceIn(0.7f, 4f)
                                            val maxPan = (size.width * (gZoom - 1f) / 2f).coerceAtLeast(0f)
                                            gPanX = (gPanX + pan.x).coerceIn(-maxPan, maxPan)
                                            event.changes.forEach { it.consume() }
                                        } else if (pressed == 1 && gZoom > 1.01f) {
                                            val pan = event.calculatePan()
                                            val maxPan = size.width * (gZoom - 1f) / 2f
                                            gPanX = (gPanX + pan.x).coerceIn(-maxPan, maxPan)
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            .graphicsLayer { scaleX = gZoom; scaleY = gZoom; translationX = gPanX }
                    ) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                            flingBehavior = noirFling,
                            userScrollEnabled = true,
                            verticalArrangement = Arrangement.spacedBy(if (readerMode == "Webtoon") 0.dp else pageGap.dp),
                            contentPadding = PaddingValues(bottom = 18.dp)) {
                            items(count = pages.size, key = { index -> "reader-page-$index" }, contentType = { "reader-page" }) { index ->
                                ReaderPageImage(pageUrl = pages[index], modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth,
                                    reserveHeight = true,
                                    onDoubleTap = toggleZoom,
                                    onTap = { if (!showChapterCinema && !showSettings && !showModeMenu) showReaderControls = !showReaderControls })
                            }
                            if (!nextPages.isNullOrEmpty()) {
                                item(key = "chapter-divider", contentType = "divider") {
                                    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            "NEXT  ·  " + (nextChapterModel?.name ?: "Next chapter").uppercase(),
                                            color = Color(0xFFD6C5F2), fontSize = 9.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.clip(RoundedCornerShape(30.dp)).background(Color(0xCC19171F))
                                                .border(1.dp, Color(0x557F788B), RoundedCornerShape(30.dp)).padding(horizontal = 14.dp, vertical = 7.dp)
                                        )
                                    }
                                }
                                items(count = nextPages.size, key = { index -> "reader-next-$index" }, contentType = { "reader-page" }) { index ->
                                    ReaderPageImage(pageUrl = nextPages[index], modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth,
                                        reserveHeight = true, onDoubleTap = toggleZoom,
                                        onTap = { if (!showChapterCinema && !showSettings && !showModeMenu) showReaderControls = !showReaderControls })
                                }
                            }
                        }
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
                    Text("${(currentPage + 1).coerceAtMost(pages.size)} / ${pages.size}", color = if (readerCanvas == Color(0xFFE8E3D9)) Color(0xFF242020) else Color(0xFFE8E4EC), fontSize = (9 * readerFontScale).sp, letterSpacing = .8.sp)
                }
            }
            if (pages.size > 1 && showReaderControls && !showChapterCinema) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)
                        .graphicsLayer { alpha = readerControlsAlpha }
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xD916151B))
                        .border(1.dp, Color(0x557F788B), RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PAGE", color = Color(0xFFAAA2B2), fontSize = 8.sp, letterSpacing = 1.4.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${(scrubPage.toInt() + 1).coerceIn(1, pages.size)} / ${pages.size}",
                            color = Color(0xFFD6C5F2), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Slider(
                        value = scrubPage.coerceIn(0f, pages.lastIndex.toFloat()),
                        onValueChange = { scrubPage = it },
                        onValueChangeFinished = { jumpToPage(scrubPage.toInt()) },
                        valueRange = 0f..pages.lastIndex.toFloat(),
                        modifier = Modifier.fillMaxWidth().height(25.dp)
                    )
                }
            }
            Column(Modifier.align(if (gesturePreset == "One-handed") Alignment.CenterEnd else Alignment.CenterStart)
                .padding(start = if (gesturePreset == "One-handed") 0.dp else 7.dp, end = if (gesturePreset == "One-handed") 7.dp else 0.dp)) {
                AnimatedVisibility(
                    visible = showReaderControls,
                    enter = fadeIn(tween(if (motionEnabled) 260 else 0)) +
                        slideInHorizontally(tween(if (motionEnabled) 340 else 0, easing = FastOutSlowInEasing)) { if (gesturePreset == "One-handed") it / 3 else -it / 3 },
                    exit = fadeOut(tween(if (motionEnabled) 190 else 0)) +
                        slideOutHorizontally(tween(if (motionEnabled) 240 else 0, easing = FastOutSlowInEasing)) { if (gesturePreset == "One-handed") it / 4 else -it / 4 }
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
            if (cinemaAlpha > 0.01f && pages.isNotEmpty()) {
                NoirChapterCinema(
                    series = series, chapter = chapter, visible = showChapterCinema,
                    alpha = cinemaAlpha, progress = cinemaProgress, motionEnabled = motionEnabled,
                    onBegin = { showChapterCinema = false }
                )
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
}@OptIn(ExperimentalFoundationApi::class)
@Composable
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
    var verifiedOk by remember {
        mutableStateOf<Set<String>>(runCatching<Set<String>> {
            val stored = JSONArray(sourcePrefs.getString("verified_ok", "[]"))
            (0 until stored.length()).map { stored.optString(it) }.toSet()
        }.getOrDefault(emptySet()))
    }
    var selectedFilter by remember { mutableStateOf("All") }
    var selectedCategory by remember { mutableStateOf("All") }
    var sourceQuery by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<String?>(null) }
    val sourceChecks = remember { mutableStateMapOf<String, String>() }
    val sourceScope = rememberCoroutineScope()

    val entries = remember {
        SourceRegistry.sources.filter { MatureContentAccess.enabled || !it.isAdultOnly }.sortedWith(
            compareBy<MangaSource> { it.isBroken }.thenBy { it.name.lowercase() }
        )
    }
    val categories = remember(entries) { listOf("All") + entries.map { it.category }.filter { it.isNotBlank() }.distinct().sorted() }
    val filtered = remember(entries, sourceQuery, selectedCategory, selectedFilter, enabledSourceIds, pinned) {
        entries.filter { entry ->
            val matchesQuery = sourceQuery.isBlank() || entry.name.contains(sourceQuery, ignoreCase = true) ||
                entry.id.contains(sourceQuery, ignoreCase = true) ||
                entry.language.contains(sourceQuery, ignoreCase = true) ||
                entry.category.contains(sourceQuery, ignoreCase = true)
            matchesQuery && (selectedCategory == "All" || entry.category.equals(selectedCategory, ignoreCase = true)) && when (selectedFilter) {
                "Enabled" -> entry.id in enabledSourceIds
                "Pinned" -> entry.id in pinned
                else -> true
            }
        }.sortedWith(compareBy<MangaSource> { it.isBroken }.thenBy { if (it.id in pinned) 0 else 1 }.thenBy { it.name.lowercase() })
    }
    val enabledCount = enabledSourceIds.count { id -> entries.any { it.id == id } }
    val shownEditable = filtered.filter { !it.isBroken }

    suspend fun runCheck(entry: MangaSource): String = try {
        // Full chain: titles -> chapters -> pages -> a real image fetch.
        val titles = entry.popular(0)
        if (titles.isEmpty()) "EMPTY" else {
            val chapterList = entry.chapters(titles.first())
            if (chapterList.isEmpty()) "NO_CHAPTERS" else {
                val pageList = entry.pages(chapterList.first()).pages
                if (pageList.isEmpty()) "NO_PAGES"
                else if (NoirNetwork.probeImage(pageList.first())) "ONLINE" else "IMAGES_BLOCKED"
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "ERROR" }

    val animatedScan = remember { mutableStateMapOf<String, String>() }
    var scanning by remember { mutableStateOf(false) }
    suspend fun checkAndRecord(entry: MangaSource) {
        sourceChecks[entry.id] = "CHECKING"
        val result = runCheck(entry)
        sourceChecks[entry.id] = result
        verifiedOk = if (result == "ONLINE") verifiedOk + entry.id else verifiedOk - entry.id
        val stored = JSONArray()
        verifiedOk.forEach { stored.put(it) }
        sourcePrefs.edit().putString("verified_ok", stored.toString()).apply()
    }
    val recommended = remember(selectedCategory, entries, verifiedOk, pinned) {
        if (selectedCategory == "All") emptyList() else entries
            .filter { it.category.equals(selectedCategory, ignoreCase = true) && !it.isBroken }
            .sortedWith(compareBy<MangaSource>({ if (it.id in verifiedOk) 0 else 1 }, { if (it.id in pinned) 0 else 1 }, { it.name.lowercase() }))
            .take(4)
    }

    Column(Modifier.fillMaxSize().background(Black).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onTab("Profile") }) { Icon(Icons.Rounded.ArrowBack, "Back", tint = White) }
            Column(Modifier.weight(1f)) {
                Text("NOIR  /  SETTINGS", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
                Text("Manage sources", color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "summary") {
                Row(
                    Modifier.fillMaxWidth().noirGlass(RoundedCornerShape(22.dp), 0.8f).padding(horizontal = 6.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("ENABLED" to enabledCount, "PINNED" to pinned.count { id -> entries.any { it.id == id } }, "AVAILABLE" to entries.size).forEach { (label, value) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(value.toString(), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text(label, color = Color(0xFFB9AECB), fontSize = 9.sp, letterSpacing = 1.4.sp)
                        }
                    }
                }
            }
            item(key = "hint") {
                Text(
                    "Turn on the catalogues you want NOIR to search. Tap a source for tests and its catalogue. Pinned sources stay at the top.",
                    color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            stickyHeader(key = "controls") {
                Column(Modifier.fillMaxWidth().background(Color(0xF2050507)).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(
                        value = sourceQuery, onValueChange = { sourceQuery = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("Search by name, language or type…", color = Muted, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Muted) },
                        trailingIcon = { if (sourceQuery.isNotEmpty()) IconButton(onClick = { sourceQuery = "" }) { Icon(Icons.Rounded.Close, "Clear", tint = Silver) } },
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = White, unfocusedTextColor = White,
                            focusedBorderColor = Color(0xFF9A82C2), unfocusedBorderColor = Color(0x33FFFFFF),
                            cursorColor = White, focusedContainerColor = Panel, unfocusedContainerColor = Panel
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("All", "Enabled", "Pinned").forEach { filter -> NoirChip(filter, selectedFilter == filter, { selectedFilter = filter }) }
                        Spacer(Modifier.weight(1f))
                        Text("${filtered.size} shown", color = Muted, fontSize = 11.sp)
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(categories, key = { it }) { category ->
                            NoirChip(if (category == "All") "All types" else category.lowercase().replaceFirstChar { it.uppercase() }, selectedCategory == category, { selectedCategory = category })
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { NoirChip("Enable all shown", false, { shownEditable.filter { it.id !in enabledSourceIds }.forEach { onToggleEnabled(it.id) } }) }
                        item { NoirChip("Disable all shown", false, { shownEditable.filter { it.id in enabledSourceIds }.forEach { onToggleEnabled(it.id) } }) }
                        item {
                            NoirChip(if (scanning) "Scanning…" else "Scan animated covers", false, {
                                if (!scanning) sourceScope.launch {
                                    scanning = true
                                    animatedScan.clear()
                                    try {
                                        val gate = kotlinx.coroutines.sync.Semaphore(3)
                                        coroutineScope {
                                            entries.filter { it.id in enabledSourceIds && !it.isBroken }.map { entry ->
                                                async {
                                                    gate.acquire()
                                                    try {
                                                        val covers = try { entry.popular(0).mapNotNull { it.coverUrl }.take(12) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                                                        animatedScan[entry.name] = if (covers == null) "could not load"
                                                        else "${covers.map { c -> async { NoirNetwork.coverIsAnimated(c) } }.awaitAll().count { it }} of ${covers.size} animated"
                                                    } finally { gate.release() }
                                                }
                                            }.awaitAll()
                                        }
                                    } finally { scanning = false }
                                }
                            })
                        }
                        item {
                            NoirChip("Test enabled", false, {
                                sourceScope.launch {
                                    val gate = kotlinx.coroutines.sync.Semaphore(3)
                                    coroutineScope {
                                        entries.filter { it.id in enabledSourceIds && !it.isBroken }.map { entry ->
                                            async {
                                                gate.acquire()
                                                try { checkAndRecord(entry) } finally { gate.release() }
                                            }
                                        }.awaitAll()
                                    }
                                }
                            })
                        }
                    }
                }
            }
            if (animatedScan.isNotEmpty()) {
                item(key = "animated-scan") {
                    Column(Modifier.fillMaxWidth().noirGlass(RoundedCornerShape(22.dp), 0.8f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("ANIMATED COVERS", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
                        val hits = animatedScan.entries.filter { !it.value.startsWith("0 of") && !it.value.startsWith("could") }.sortedBy { it.key }
                        if (!scanning && hits.isEmpty()) Text("None of the scanned sources served animated covers on their first page.", color = White, fontSize = 12.sp)
                        hits.forEach { Text("${it.key}  ·  ${it.value}", color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                        val rest = animatedScan.size - hits.size
                        if (rest > 0) Text("$rest other sources: none found or not reachable", color = Muted, fontSize = 10.sp)
                    }
                }
            }
            if (selectedCategory != "All") {
                item(key = "guide-$selectedCategory") {
                    val kind = selectedCategory.uppercase()
                    val title = selectedCategory.lowercase().replaceFirstChar { it.uppercase() }
                    val blurb = when {
                        kind.contains("MANHWA") -> "Korean webcomics, made to be read as one long vertical scroll. They update often and are usually in colour. Choose sources that load full chapters and sharp images, and keep two or three on so a missing chapter on one can be found on another."
                        kind.contains("MANHUA") -> "Chinese comics, also built for vertical scrolling and often long-running cultivation, fantasy and romance series. Pick a source with regular updates, and keep one backup enabled because chapter coverage differs between sites."
                        kind.contains("MANGA") -> "Japanese comics, usually read page by page, right to left. MangaDex-style sources tend to carry the widest, cleanest catalogue with community translations. Keep one main source and one fallback."
                        kind.contains("COMIC") -> "Western comics and graphic novels, normally read left to right, page by page. Fewer sources cover these, so enable a couple and test them."
                        kind.contains("NOVEL") -> "Text-based stories rather than images. Catalogue coverage varies, so test a source before relying on it."
                        else -> "A mixed group of sources. Test each one to see whether titles, chapters, pages and images all load before you rely on it."
                    }
                    Column(
                        Modifier.fillMaxWidth().noirGlass(RoundedCornerShape(24.dp), 0.9f).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NoirMascot(NoirMascotKind.STAR, 48.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("GUIDE", color = Color(0xFFD6C5F2), fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                                Text("Recommended for $title", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.3).sp)
                            }
                        }
                        Text(blurb, color = Color(0xFFC5BFCC), fontSize = 13.sp, lineHeight = 19.sp)
                        if (recommended.isEmpty()) {
                            Text("No working sources of this type are available right now.", color = Muted, fontSize = 12.sp)
                        } else {
                            Text(
                                if (recommended.any { it.id in verifiedOk }) "Tested and working sources come first:" else "Start with these, then tap Test source on each:",
                                color = Color(0xFFD6C5F2), fontSize = 12.sp, fontWeight = FontWeight.Medium
                            )
                            recommended.forEach { entry ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (entry.id in verifiedOk) Color(0xFF9FCB9A) else Color(0xFF77717D)))
                                    Spacer(Modifier.width(9.dp))
                                    Text(entry.name, color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (entry.id in verifiedOk) "Verified" else "Not tested", color = if (entry.id in verifiedOk) Color(0xFF9FCB9A) else Muted, fontSize = 11.sp)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF1EDF6))
                                        .clickable { recommended.filter { it.id !in enabledSourceIds }.forEach { onToggleEnabled(it.id) } }.padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) { Text("Enable these", color = Color(0xFF17131D), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                                Box(
                                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF2A2531))
                                        .clickable {
                                            sourceScope.launch {
                                                val gate = kotlinx.coroutines.sync.Semaphore(2)
                                                coroutineScope {
                                                    recommended.map { entry -> async { gate.acquire(); try { checkAndRecord(entry) } finally { gate.release() } } }.awaitAll()
                                                }
                                            }
                                        }.padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) { Text("Test these", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                }
            }
            if (filtered.isEmpty()) {
                item(key = "none") { EmptyState("No sources match", "Try a different search or clear the filters.") }
            }
            items(filtered, key = { it.id }) { entry ->
                val active = entry.id in enabledSourceIds
                val isPinned = entry.id in pinned
                val check = sourceChecks[entry.id]
                val expanded = expandedId == entry.id
                val bad = entry.isBroken || check in setOf("ERROR", "NO_CHAPTERS", "NO_PAGES", "IMAGES_BLOCKED", "EMPTY")
                val dot = when { bad -> Color(0xFFE0A0A0); check == "ONLINE" -> Color(0xFF9FCB9A); check == "CHECKING" -> Color(0xFFE3C98B); active -> Color(0xFF9FC29A); else -> Color(0xFF77717D) }
                val shortStatus = when {
                    entry.isBroken -> "Flagged as unavailable"
                    check == "CHECKING" -> "Checking…"
                    check == "ONLINE" -> "Verified working"
                    check != null && bad -> "Problem found"
                    active -> "Enabled"
                    else -> "Off"
                }
                val detailStatus = when {
                    entry.isBroken -> "Flagged by the parser library"
                    check == "CHECKING" -> "Testing titles, chapters, pages and images…"
                    check == "ONLINE" -> "Titles, chapters, pages and images all loaded"
                    check == "EMPTY" -> "The source returned no titles"
                    check == "NO_CHAPTERS" -> "Titles load, but no chapters were found"
                    check == "NO_PAGES" -> "Chapters load, but no pages were found"
                    check == "IMAGES_BLOCKED" -> "Pages load, but the images were blocked"
                    check == "ERROR" -> "Connection failed"
                    else -> "Not tested yet"
                }
                Column(
                    Modifier.fillMaxWidth().animateContentSize(tween(220))
                        .clip(RoundedCornerShape(22.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF1E1A25), Color(0xFF111014))))
                        .border(1.dp, if (active) Color(0xFF6A5780) else Color(0xFF2E2A34), RoundedCornerShape(22.dp))
                        .clickable { expandedId = if (expanded) null else entry.id }
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SourceIcon(entry, Modifier.size(48.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text("${entry.category.lowercase().replaceFirstChar { it.uppercase() }} · ${entry.language.uppercase()}", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(5.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                                Spacer(Modifier.width(6.dp))
                                Text(shortStatus, color = if (bad) Color(0xFFE0A0A0) else Color(0xFFBDB6C4), fontSize = 11.sp)
                            }
                        }
                        IconButton(onClick = { pinned = if (isPinned) pinned - entry.id else pinned + entry.id }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.PushPin, if (isPinned) "Unpin source" else "Pin source", tint = if (isPinned) Color(0xFFD9C4E9) else Muted, modifier = Modifier.size(20.dp))
                        }
                        Switch(checked = active, onCheckedChange = { onToggleEnabled(entry.id) }, enabled = !entry.isBroken)
                    }
                    if (expanded) {
                        Spacer(Modifier.height(12.dp))
                        Text(detailStatus, color = Color(0xFFB9B2C0), fontSize = 12.sp, lineHeight = 17.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF2A2531))
                                    .clickable(enabled = check != "CHECKING" && !entry.isBroken) {
                                        sourceScope.launch { checkAndRecord(entry) }
                                    }.padding(vertical = 13.dp),
                                contentAlignment = Alignment.Center
                            ) { Text(if (check == "CHECKING") "Testing…" else "Test source", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF1EDF6))
                                    .clickable(enabled = !entry.isBroken) { onOpenSource(entry) }.padding(vertical = 13.dp),
                                contentAlignment = Alignment.Center
                            ) { Text("Open catalogue", color = Color(0xFF17131D), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(CircleShape).background(Color(0xFFF1EDF6)).clickable { onTab("Discover") }.padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Browse enabled sources", color = Color(0xFF17131D), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(7.dp))
                Icon(Icons.Rounded.ArrowForward, null, tint = Color(0xFF17131D), modifier = Modifier.size(16.dp))
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
    var selectedSort by remember { mutableStateOf("Recently added") }
    var showSortMenu by remember { mutableStateOf(false) }
    val filtered = remember(items, categories, selectedCategory, selectedSort, progressBySeries) {
        val categoryItems = if (selectedCategory == "All") items
            else items.filter { (categories["${it.sourceId}:${it.id}"] ?: "Reading") == selectedCategory }
        when (selectedSort) {
            "Title A–Z" -> categoryItems.sortedBy { it.title.lowercase() }
            "Most progress" -> categoryItems.sortedByDescending { progressBySeries["${it.sourceId}:${it.id}"] ?: 0f }
            "Least progress" -> categoryItems.sortedBy { progressBySeries["${it.sourceId}:${it.id}"] ?: 0f }
            else -> categoryItems
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("YOUR COLLECTION", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
            Text("Library", color = White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${items.size} SAVED TITLES", color = Color(0xFFB8A9D0), fontSize = 8.sp, letterSpacing = 1.5.sp)
                Spacer(Modifier.weight(1f))
                Box {
                    Text("SORT  ·  ${selectedSort.uppercase()}  ▾", color = Color(0xFFD6C5F2), fontSize = 7.sp,
                        letterSpacing = .7.sp, modifier = Modifier.clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF19171E)).clickable { showSortMenu = true }
                            .padding(horizontal = 10.dp, vertical = 7.dp))
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }, containerColor = Panel) {
                        listOf("Recently added", "Title A–Z", "Most progress", "Least progress").forEach { sort ->
                            DropdownMenuItem(
                                text = { Text(sort, color = if (selectedSort == sort) Color(0xFFD6C5F2) else White) },
                                onClick = { selectedSort = sort; showSortMenu = false }
                            )
                        }
                    }
                }
            }
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
internal fun BottomNav(tab: String, onTab: (String) -> Unit) {
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
private fun NoirChapterCinema(
    series: Series?,
    chapter: Chapter,
    visible: Boolean,
    alpha: Float,
    progress: Float,
    motionEnabled: Boolean,
    onBegin: () -> Unit
) {
    // Staggered reveal: elements ease in one after another once the scene is on screen.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { shown = visible }
    @Composable
    fun reveal(delayMs: Int): Float {
        val value by animateFloatAsState(
            targetValue = if (shown) 1f else 0f,
            animationSpec = tween(if (shown) 850 else 240, delayMillis = if (shown) delayMs else 0, easing = FastOutSlowInEasing),
            label = "cinema-reveal-$delayMs"
        )
        return value
    }
    val posterReveal = reveal(120)
    val labelReveal = reveal(420)
    val titleReveal = reveal(560)
    val buttonReveal = reveal(800)

    val loop = rememberInfiniteTransition(label = "noir-cinema-loop")
    val drift by loop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(10000, easing = LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "cinema-drift"
    )
    val tilt by loop.animateFloat(
        initialValue = 0f, targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "cinema-tilt"
    )
    val glowTravel by loop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "cinema-glow"
    )
    val motion = if (motionEnabled) 1f else 0f
    val posterShape = RoundedCornerShape(24.dp)
    val subtitle = chapter.name.let { raw ->
        val tail = if (raw.contains("\u00B7")) raw.substringAfter("\u00B7").trim() else raw.trim()
        if (tail.isBlank() || tail.equals("Chapter ${chapter.number}", ignoreCase = true)) null else tail
    }

    Box(
        Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }
            .background(Color(0xFF050407))
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        // Slowly drifting, softly blurred cover as the backdrop (blur needs Android 12+; older devices get the dimmed art).
        series?.let { item ->
            SeriesCover(
                item,
                Modifier.fillMaxSize()
                    .blur(30.dp)
                    .graphicsLayer {
                        this.alpha = 0.66f
                        val zoom = 1.2f + 0.12f * drift * motion
                        scaleX = zoom
                        scaleY = zoom
                        translationX = (drift - 0.5f) * 46.dp.toPx() * motion
                        translationY = (0.5f - drift) * 22.dp.toPx() * motion
                    }
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0xB3050407),
                    .38f to Color(0x33050407),
                    .72f to Color(0xCC050407),
                    1f to Color(0xFF050407)
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(Color(0x558C6CFF), Color.Transparent),
                    center = Offset.Unspecified, radius = 900f
                )
            )
        )

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 32.dp).padding(top = 14.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "NOIR  ORIGINAL", color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp,
                letterSpacing = 3.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.graphicsLayer { this.alpha = labelReveal }
            )
            Spacer(Modifier.weight(0.7f))

            Box(
                Modifier.fillMaxWidth(0.58f).aspectRatio(0.68f)
                    .graphicsLayer {
                        this.alpha = posterReveal
                        val settle = 0.93f + 0.07f * posterReveal
                        scaleX = settle
                        scaleY = settle
                        translationY = (1f - posterReveal) * 28.dp.toPx()
                        rotationY = 5f * kotlin.math.sin(tilt) * motion
                        rotationX = 2.5f * kotlin.math.cos(tilt) * motion
                        cameraDistance = 16f * density
                    }
                    .shadow(34.dp, posterShape, ambientColor = Color(0xFF8C6CFF), spotColor = Color(0xFF8C6CFF))
                    .background(Color(0xFF16141B))
                    .drawWithContent {
                        drawContent()
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.16f),
                            cornerRadius = CornerRadius(24.dp.toPx()),
                            style = Stroke(width = 1.dp.toPx())
                        )
                        drawTravelingOutlineGlow(
                            cornerRadius = 24.dp.toPx(), progress = glowTravel,
                            inset = 1.5.dp.toPx(), arcFraction = 0.24f, strokeWidth = 1.5.dp.toPx()
                        )
                    }
            ) {
                if (series?.coverUrl != null) {
                    AsyncImage(
                        model = series.coverUrl, contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(posterShape), contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Rounded.MenuBook, null, tint = Muted, modifier = Modifier.align(Alignment.Center).size(40.dp))
                }
            }

            Spacer(Modifier.height(30.dp))
            Text(
                "CHAPTER " + chapter.number.ifBlank { chapter.name }.uppercase(),
                color = Color(0xFFD6C5F2), fontSize = 11.sp, letterSpacing = 3.2.sp, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer {
                    this.alpha = labelReveal
                    translationY = (1f - labelReveal) * 14.dp.toPx()
                }
            )
            Spacer(Modifier.height(10.dp))
            Text(
                series?.title ?: "Your story", color = Color.White, fontSize = 28.sp, lineHeight = 33.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-.5).sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer {
                    this.alpha = titleReveal
                    translationY = (1f - titleReveal) * 16.dp.toPx()
                }
            )
            if (subtitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    subtitle, color = Color.White.copy(alpha = 0.58f), fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { this.alpha = titleReveal }
                )
            }

            Spacer(Modifier.weight(1f))
            Column(
                Modifier.fillMaxWidth().graphicsLayer {
                    this.alpha = buttonReveal
                    translationY = (1f - buttonReveal) * 20.dp.toPx()
                },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.fillMaxWidth().height(56.dp).clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.24f), Color.White.copy(alpha = 0.10f))))
                        .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape)
                        .drawBehind {
                            drawTravelingOutlineGlow(
                                cornerRadius = size.height / 2f - 1.5.dp.toPx(), progress = (glowTravel + 0.5f) % 1f,
                                inset = 1.5.dp.toPx(), arcFraction = 0.24f, strokeWidth = 1.4.dp.toPx()
                            )
                        }
                        .clickable(onClick = onBegin),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Begin reading", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Rounded.ArrowForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth(0.26f).height(2.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Color.White.copy(alpha = 0.78f)))
                }
                Spacer(Modifier.height(9.dp))
                Text("Starting automatically", color = Color.White.copy(alpha = 0.38f), fontSize = 10.sp, letterSpacing = 0.6.sp)
            }
        }
    }
}

@Composable
private fun NoirMotionAtmosphere(modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "noir-motion-atmosphere")
    val driftA by motion.animateFloat(
        initialValue = -0.08f, targetValue = 0.12f,
        animationSpec = infiniteRepeatable(tween(7200, easing = FastOutSlowInEasing), repeatMode = androidx.compose.animation.core.RepeatMode.Reverse),
        label = "noir-orb-drift-a"
    )
    val driftB by motion.animateFloat(
        initialValue = 0.10f, targetValue = -0.09f,
        animationSpec = infiniteRepeatable(tween(9100, easing = FastOutSlowInEasing), repeatMode = androidx.compose.animation.core.RepeatMode.Reverse),
        label = "noir-orb-drift-b"
    )
    val pulse by motion.animateFloat(
        initialValue = 0.62f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), repeatMode = androidx.compose.animation.core.RepeatMode.Reverse),
        label = "noir-orb-pulse"
    )
    Canvas(modifier) {
        val violet = Color(0xFF8E5BCB)
        val ice = Color(0xFFB7A3D8)
        drawCircle(violet.copy(alpha = 0.10f * pulse), radius = size.minDimension * 0.34f,
            center = Offset(size.width * (0.12f + driftA), size.height * 0.30f))
        drawCircle(ice.copy(alpha = 0.065f * pulse), radius = size.minDimension * 0.26f,
            center = Offset(size.width * (0.88f + driftB), size.height * 0.68f))
        drawCircle(violet.copy(alpha = 0.045f * pulse), radius = size.minDimension * 0.18f,
            center = Offset(size.width * 0.52f, size.height * (0.18f - driftB * 0.35f)))
        val specks = listOf(
            Offset(0.18f, 0.22f), Offset(0.79f, 0.17f), Offset(0.72f, 0.78f),
            Offset(0.27f, 0.73f), Offset(0.91f, 0.43f), Offset(0.09f, 0.56f)
        )
        specks.forEachIndexed { index, point ->
            val alpha = (0.16f + 0.22f * pulse) * if (index % 2 == 0) 1f else 0.65f
            drawCircle(Color(0xFFDCCBFA).copy(alpha = alpha), radius = (1.2f + (index % 3) * 0.55f).dp.toPx(),
                center = Offset(size.width * point.x, size.height * point.y))
        }
    }
}

private enum class NoirMascotKind(val res: Int) {
    SIT(R.drawable.mascot_sit), SLEEP(R.drawable.mascot_sleep), PEEK(R.drawable.mascot_peek),
    STAR(R.drawable.mascot_star), SCARF(R.drawable.mascot_scarf)
}

@Composable
private fun NoirMascot(kind: NoirMascotKind, width: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, bob: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "mascot-bob")
    val lift = transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "mascot-lift"
    )
    Image(
        painter = painterResource(kind.res), contentDescription = null,
        modifier = modifier.width(width).graphicsLayer { if (bob) translationY = -lift.value * 4.dp.toPx() },
        contentScale = ContentScale.Fit
    )
}

/** Soft one-time entrance for a section: fades up with a small stagger. */
@Composable
private fun Modifier.noirReveal(seen: MutableSet<String>, key: String, delayMs: Int): Modifier {
    val already = remember { key in seen }
    val p = remember { Animatable(if (already) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!already) {
            delay(delayMs.toLong())
            seen.add(key)
            p.animateTo(1f, tween(560, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)))
        }
    }
    return this.graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * 26.dp.toPx()
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
            NoirMascot(NoirMascotKind.PEEK, 124.dp)
            Spacer(Modifier.height(8.dp))
            NoirLoadingMark()
            Spacer(Modifier.height(12.dp))
            Text(message, color = Muted, fontSize = 11.sp)
        }
    }
}
@Composable
private fun ErrorState(message: String) {
    Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        NoirMascot(NoirMascotKind.SIT, 92.dp)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Color(0xFFE3A4A4), fontSize = 12.sp)
    }
}
@Composable
private fun EmptyState(title: String, message: String) {
    Box(Modifier.fillMaxWidth().padding(25.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NoirMascot(NoirMascotKind.SLEEP, 132.dp)
            Spacer(Modifier.height(10.dp))
            Text(title, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text(message, color = Muted, fontSize = 11.sp)
        }
    }
}


private data class NoirWelcomePage(
    val lead: String,
    val accent: String,
    val description: String
)

private data class NoirWelcomePalette(val main: Color, val light: Color, val deep: Color, val accent: Color)

@Composable
private fun NoirWelcomeWalkthrough(onFinish: () -> Unit) {
    val pages = remember {
        listOf(
            NoirWelcomePage("Every story\nbegins ", "here.", "Step into a universe of manga, manhwa and unforgettable worlds. Your next favourite story is waiting."),
            NoirWelcomePage("Find your\nnext ", "obsession.", "Explore community sources, discover new series and follow the stories that keep you turning pages."),
            NoirWelcomePage("Read without\n", "limits.", "Choose your reading direction, make every page yours and continue right where you left off."),
            NoirWelcomePage("Keep stories\n", "close.", "Save favourites, track your progress and download supported chapters for offline reading.")
        )
    }
    val palettes = remember {
        listOf(
            NoirWelcomePalette(Color(0xFF2B6BFF), Color(0xFFC3D3FF), Color(0xFF1B3FA8), Color(0xFF2F7BFF)),
            NoirWelcomePalette(Color(0xFFFF4B2B), Color(0xFFFFB03A), Color(0xFFC22A12), Color(0xFFFF5238)),
            NoirWelcomePalette(Color(0xFF00B3A6), Color(0xFF9DF3D6), Color(0xFF0B5E8C), Color(0xFF2DD4BF)),
            NoirWelcomePalette(Color(0xFF9B4DFF), Color(0xFFFF93CC), Color(0xFF4B2BB0), Color(0xFFB878FF))
        )
    }
    var page by remember { mutableIntStateOf(0) }
    val current = pages[page]
    val isLast = page == pages.lastIndex
    val target = palettes[page]
    val mainColor by animateColorAsState(target.main, tween(800), label = "welcome-main")
    val lightColor by animateColorAsState(target.light, tween(800), label = "welcome-light")
    val deepColor by animateColorAsState(target.deep, tween(800), label = "welcome-deep")
    val accentColor by animateColorAsState(target.accent, tween(800), label = "welcome-accent")

    // Angles are whole multiples of one full turn, so every loop is seamless (no jump on repeat).
    val wave = rememberInfiniteTransition(label = "noir-walkthrough-wave")
    val phase by wave.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing)),
        label = "noir-wave-phase"
    )
    val base = Color(0xFF04050A)

    Box(Modifier.fillMaxSize().background(base)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val a = phase
            fun blob(color: Color, alpha: Float, cx: Float, cy: Float, radius: Float) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * .55f), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = radius
                    ),
                    radius = radius,
                    center = Offset(cx, cy)
                )
            }
            blob(mainColor, .95f, w * (.30f + .22f * kotlin.math.cos(a + 1f)), h * (.26f + .05f * kotlin.math.sin(a)), w * 1.08f)
            blob(mainColor, .80f, w * (.62f + .26f * kotlin.math.sin(a * 2f + 2f)), h * (.17f + .04f * kotlin.math.cos(a + 2f)), w * .95f)
            blob(deepColor, .85f, w * (.50f + .30f * kotlin.math.sin(a + 3.4f)), h * (.40f + .05f * kotlin.math.cos(a * 2f)), w * .95f)
            blob(lightColor, .95f, w * (.78f + .18f * kotlin.math.sin(a + .6f)), h * (.07f + .035f * kotlin.math.cos(a * 2f + 1f)), w * .78f)
            // Fade the colour into black toward the copy area.
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    .40f to Color.Transparent,
                    .64f to base.copy(alpha = .96f),
                    1f to base
                )
            )
        }

        Column(
            Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 28.dp).padding(bottom = 18.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                NoirLogoMark(24.dp)
                Text("NOIR", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
            }
            Spacer(Modifier.height(18.dp))
            androidx.compose.animation.AnimatedContent(
                targetState = page,
                transitionSpec = {
                    (fadeIn(tween(420)) + androidx.compose.animation.slideInVertically(tween(420)) { it / 10 }) togetherWith
                        fadeOut(tween(160))
                },
                label = "welcome-copy"
            ) { index ->
                val item = pages[index]
                Column {
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append(item.lead)
                            withStyle(androidx.compose.ui.text.SpanStyle(color = accentColor)) { append(item.accent) }
                        },
                        color = Color.White, fontSize = 36.sp, lineHeight = 42.sp,
                        fontWeight = FontWeight.Medium, letterSpacing = (-.8).sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(item.description, color = Color.White.copy(alpha = .62f), fontSize = 14.sp, lineHeight = 21.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                pages.indices.forEach { index ->
                    val active = index == page
                    val width by animateDpAsState(if (active) 26.dp else 5.dp, tween(300), label = "welcome-dot")
                    Box(
                        Modifier.width(width).height(5.dp).clip(CircleShape)
                            .background(if (active) accentColor else Color.White.copy(alpha = .28f))
                    )
                }
            }
            Spacer(Modifier.height(26.dp))
            Box(
                Modifier.fillMaxWidth().height(54.dp).clip(CircleShape)
                    .background(accentColor)
                    .drawBehind {
                        val cx = size.width * (.5f + .30f * kotlin.math.sin(phase * 2f))
                        val radius = size.width * .34f
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(Color.White.copy(alpha = .62f), Color.White.copy(alpha = .16f), Color.Transparent),
                                center = Offset(cx, size.height / 2f), radius = radius
                            ),
                            radius = radius, center = Offset(cx, size.height / 2f)
                        )
                    }
                    .clickable { if (isLast) onFinish() else page += 1 },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(if (isLast) "Enter NOIR" else "Next", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Rounded.ArrowForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                if (isLast) {
                    Text("Your next chapter starts now.", color = Color.White.copy(alpha = .5f), fontSize = 12.sp)
                } else {
                    TextButton(onClick = onFinish) { Text("Skip", color = Color.White, fontSize = 15.sp) }
                }
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

private class MotePoint(val x: Float, val y: Float, val size: Float, val speed: Float, val phase: Float)

private class PanelGeo(val pts: List<Offset>, val path: androidx.compose.ui.graphics.Path, val center: Offset, val minX: Float, val maxX: Float, val minY: Float, val maxY: Float)

private fun buildPanels(x0: Float, y0: Float, pw: Float, ph: Float, inset: Float): List<PanelGeo> {
    val fr = listOf(
        listOf(0f to 0f, 0.62f to 0f, 0.58f to 0.27f, 0f to 0.27f),
        listOf(0.62f to 0f, 1f to 0f, 1f to 0.27f, 0.58f to 0.27f),
        listOf(0f to 0.27f, 1f to 0.27f, 1f to 0.52f, 0f to 0.52f),
        listOf(0f to 0.52f, 0.45f to 0.52f, 0.49f to 0.76f, 0f to 0.76f),
        listOf(0.45f to 0.52f, 1f to 0.52f, 1f to 0.76f, 0.49f to 0.76f),
        listOf(0f to 0.76f, 1f to 0.76f, 1f to 1f, 0f to 1f)
    )
    return fr.map { poly ->
        val raw = poly.map { (fx, fy) -> Offset(x0 + fx * pw, y0 + fy * ph) }
        val c = Offset(raw.map { it.x }.average().toFloat(), raw.map { it.y }.average().toFloat())
        val pts = raw.map { p ->
            val dx = c.x - p.x
            val dy = c.y - p.y
            val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            Offset(p.x + dx / len * inset, p.y + dy / len * inset)
        }
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            close()
        }
        PanelGeo(pts, path, c, pts.minOf { it.x }, pts.maxOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.y })
    }
}

/** "The Page": a manga page draws itself in light, then the NOIR crew fills the panels and the camera pushes through the title. */
@Composable
private fun PanelsIntro(playSound: Boolean, onFinished: () -> Unit) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val overall = remember { Animatable(0f) }
    val zoom = remember { Animatable(0f) }
    val kick = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val tag = remember { Animatable(0f) }
    val push = remember { Animatable(0f) }
    val draws = remember { List(6) { Animatable(0f) } }
    val fills = remember { List(6) { Animatable(0f) } }
    val pops = remember { List(5) { Animatable(0f) } }
    val loop = rememberInfiniteTransition(label = "panels-loop")
    val phase = loop.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "panels-phase")
    val motes = remember {
        val r = java.util.Random(33)
        List(48) { MotePoint(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 1.4f, 0.4f + r.nextFloat() * 0.9f, r.nextFloat()) }
    }

    LaunchedEffect(Unit) {
        var player: MediaPlayer? = null
        try {
            player = startIntroSound(context, playSound)
            kotlinx.coroutines.coroutineScope {
                launch { zoom.animateTo(1f, tween(3900, easing = LinearEasing)) }
                launch { overall.animateTo(1f, tween(350)) }
                draws.forEachIndexed { i, a -> launch { delay(i * 90L); a.animateTo(1f, tween(950, easing = CubicBezierEasing(0.3f, 0.7f, 0.2f, 1f))) } }
                launch {
                    delay(1250)
                    runCatching { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
                    kotlinx.coroutines.coroutineScope {
                        launch { kick.animateTo(1f, tween(90)); kick.animateTo(0f, tween(900)) }
                        launch { ring.animateTo(1f, tween(1100, easing = LinearOutSlowInEasing)) }
                        fills.forEachIndexed { i, a -> launch { delay(i * 90L); a.animateTo(1f, tween(700)) } }
                        pops.forEachIndexed { i, a -> launch { delay(220L + i * 110L); a.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 240f)) } }
                        launch { delay(150); word.animateTo(1f, tween(950, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))) }
                        launch { delay(650); tag.animateTo(1f, tween(700)) }
                    }
                    delay(800)
                    kotlinx.coroutines.coroutineScope {
                        launch { push.animateTo(1f, tween(720, easing = CubicBezierEasing(0.5f, 0f, 0.8f, 0.4f))) }
                        launch { overall.animateTo(0f, tween(720)) }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            player?.let { mp ->
                runCatching { if (mp.isPlaying) mp.stop() }
                runCatching { mp.release() }
            }
            onFinished()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF010102)).graphicsLayer { alpha = overall.value }) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val margin = with(density) { 16.dp.toPx() }
        val pageTop = with(density) { 44.dp.toPx() }
        val pageBottom = with(density) { 40.dp.toPx() }
        val panels = remember(wPx, hPx) { buildPanels(margin, pageTop, wPx - margin * 2f, hPx - pageTop - pageBottom, with(density) { 4.dp.toPx() }) }
        val titleCenter = panels[2].center
        val catPanels = remember { intArrayOf(0, 1, 3, 4, 5) }
        val cats = remember {
            listOf(
                NoirMascotKind.SIT to (381f / 420f), NoirMascotKind.PEEK to (223f / 420f), NoirMascotKind.STAR to (348f / 420f),
                NoirMascotKind.SCARF to (420f / 384f), NoirMascotKind.SLEEP to (304f / 420f)
            )
        }

        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val z = 1f + 0.05f * zoom.value + 0.012f * kick.value + 0.6f * push.value * push.value
                scaleX = z
                scaleY = z
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.40f)
                rotationZ = kotlin.math.sin(phase.value * 2f * Math.PI.toFloat()) * 0.35f
            }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val tau = 2f * Math.PI.toFloat()
                val violet = Color(0xFF9B7BFF)
                // Soft glow behind the page.
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFF4B2BC8).copy(alpha = 0.30f), Color.Transparent), center = Offset(size.width / 2f, size.height * 0.42f), radius = size.width * 1.1f),
                    radius = size.width * 1.1f, center = Offset(size.width / 2f, size.height * 0.42f)
                )
                val edge = androidx.compose.ui.graphics.lerp(violet, Color.White, kick.value)
                val measure = androidx.compose.ui.graphics.PathMeasure()
                panels.forEachIndexed { i, pl ->
                    val f = fills[i].value
                    if (f > 0.001f) {
                        drawPath(
                            pl.path,
                            Brush.linearGradient(listOf(if (i == 2) Color(0xFF241548) else Color(0xFF190F30), Color(0xFF06030C)), start = Offset(pl.minX, pl.minY), end = Offset(pl.maxX, pl.maxY)),
                            alpha = f
                        )
                        drawPath(
                            pl.path,
                            Brush.radialGradient(listOf(Color(0xFF7A55FF).copy(alpha = 0.26f), Color.Transparent), center = pl.center, radius = (pl.maxX - pl.minX).coerceAtLeast(pl.maxY - pl.minY) * 0.65f),
                            alpha = f * (0.8f + 0.2f * kotlin.math.sin((phase.value * 2f + i * 0.2f) * tau))
                        )
                    }
                    val d = draws[i].value
                    if (d > 0.001f) {
                        measure.setPath(pl.path, false)
                        val seg = androidx.compose.ui.graphics.Path()
                        measure.getSegment(0f, measure.length * d, seg, true)
                        val round = androidx.compose.ui.graphics.StrokeJoin.Round
                        drawPath(seg, edge.copy(alpha = 0.16f + 0.2f * kick.value), style = Stroke(8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = round))
                        drawPath(seg, edge.copy(alpha = 0.40f), style = Stroke(3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = round))
                        drawPath(seg, Color.White.copy(alpha = 0.92f), style = Stroke(1.2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = round))
                    }
                }
                if (ring.value in 0.001f..0.999f) {
                    val r = ring.value * size.width * 1.15f
                    drawCircle(Color(0xFFCDBBFF).copy(alpha = (1f - ring.value) * 0.55f), radius = r, center = titleCenter, style = Stroke(3.dp.toPx() * (1f - ring.value) + 1f))
                }
                // Sparkle beside the title.
                val sp = word.value
                if (sp > 0.01f) {
                    val c = Offset(titleCenter.x + size.width * 0.31f, titleCenter.y - size.height * 0.055f)
                    val s = 15.dp.toPx() * sp * (0.9f + 0.1f * kotlin.math.sin(phase.value * tau * 3f))
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(c.x, c.y - s)
                        quadraticBezierTo(c.x + 0.07f * s, c.y - 0.07f * s, c.x + s * 0.9f, c.y)
                        quadraticBezierTo(c.x + 0.07f * s, c.y + 0.07f * s, c.x, c.y + s)
                        quadraticBezierTo(c.x - 0.07f * s, c.y + 0.07f * s, c.x - s * 0.9f, c.y)
                        quadraticBezierTo(c.x - 0.07f * s, c.y - 0.07f * s, c.x, c.y - s)
                        close()
                    }
                    drawPath(path, Color.White.copy(alpha = sp.coerceAtMost(1f)))
                }
                if (kick.value > 0.001f) drawRect(Color.White, alpha = 0.10f * kick.value)
            }

            cats.forEachIndexed { i, (kind, aspect) ->
                val pl = panels[catPanels[i]]
                val availW = (pl.maxX - pl.minX) * 0.58f
                val availH = (pl.maxY - pl.minY) * 0.80f
                val catW = minOf(availW, availH / aspect)
                val catH = catW * aspect
                val bottomAligned = kind == NoirMascotKind.PEEK || kind == NoirMascotKind.SLEEP
                val left = pl.center.x - catW / 2f
                val top = if (bottomAligned) pl.maxY - catH - with(density) { 6.dp.toPx() } else pl.center.y - catH / 2f
                Image(
                    painter = painterResource(kind.res), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.width(with(density) { catW.toDp() })
                        .offset { IntOffset(left.roundToInt(), (top + kotlin.math.sin(phase.value * 2f * Math.PI.toFloat() + i * 1.2f) * 2.dp.toPx()).roundToInt()) }
                        .graphicsLayer {
                            val p = pops[i].value
                            alpha = p.coerceIn(0f, 1f)
                            val sc = 0.5f + 0.5f * p
                            scaleX = sc
                            scaleY = sc
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                        }
                )
            }

            Column(
                Modifier.fillMaxWidth().offset { IntOffset(0, (titleCenter.y - 44.dp.toPx()).roundToInt()) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val sweep = -500f + word.value * 2000f
                Text(
                    "NOIR",
                    modifier = Modifier.graphicsLayer {
                        alpha = word.value
                        translationY = (1f - word.value) * 18.dp.toPx()
                        val sc = 0.94f + 0.06f * word.value
                        scaleX = sc
                        scaleY = sc
                    },
                    style = androidx.compose.ui.text.TextStyle(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(0f to Color(0xFFB9ABEA), 0.5f to Color.White, 1f to Color(0xFFB9ABEA)),
                            start = Offset(sweep, 0f), end = Offset(sweep + 520f, 0f)
                        ),
                        fontSize = 62.sp, fontWeight = FontWeight.Bold, letterSpacing = 14.sp
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "A UNIVERSE BETWEEN THE PAGES", color = Color(0xFFC9BBEE), fontSize = 8.sp, letterSpacing = 2.8.sp,
                    modifier = Modifier.graphicsLayer { alpha = tag.value; translationY = (1f - tag.value) * 8.dp.toPx() }
                )
            }
        }

        // Drifting ink dust, outside the camera so it reads as depth.
        Canvas(Modifier.fillMaxSize()) {
            val tau = 2f * Math.PI.toFloat()
            motes.forEach { m ->
                val y = (((m.y - phase.value * m.speed) % 1f) + 1f) % 1f
                val x = m.x + kotlin.math.sin((phase.value + m.phase) * tau) * 0.012f
                val tw = 0.5f + 0.5f * kotlin.math.sin((phase.value * 3f + m.phase) * tau)
                drawCircle(Color(0xFFD7CCFF).copy(alpha = 0.10f + 0.40f * tw), radius = m.size.dp.toPx() * 0.6f, center = Offset(x * size.width, y * size.height))
            }
        }
    }
}

private class CityLight(val u: Float, val v: Float, val size: Float, val tint: Color, val phase: Float)

private fun startIntroSound(context: Context, playSound: Boolean): MediaPlayer? {
    return runCatching {
        val appContext = context.applicationContext
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!playSound || audio.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) return null
        val mp = MediaPlayer.create(appContext, R.raw.noir_intro) ?: return null
        mp.setVolume(0.85f, 0.85f)
        mp.start()
        mp
    }.getOrNull()
}

/** "Aether"-style launch: a quiet night sky, an Earth horizon rising with city lights, a sparkle and the NOIR script. */
@Composable
private fun HorizonIntro(playSound: Boolean, onFinished: () -> Unit) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val overall = remember { Animatable(0f) }
    val rise = remember { Animatable(0f) }
    val sparkle = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val tag = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    val pops = remember { List(5) { Animatable(0f) } }
    val loop = rememberInfiniteTransition(label = "horizon-loop")
    val phase = loop.animateFloat(0f, 1f, infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "horizon-phase")
    val stars = remember {
        val r = java.util.Random(21)
        List(90) { StarSpec(r.nextFloat(), r.nextFloat() * 0.78f, 0.5f + r.nextFloat() * 1.1f, r.nextFloat(), 5 + r.nextInt(10), 0, Color.White) }
    }
    val cities = remember {
        val r = java.util.Random(5)
        val tints = listOf(Color(0xFFFFE9B0), Color(0xFFFFD27A), Color(0xFFBFD8FF), Color.White, Color(0xFFFFF4D6))
        List(620) {
            val cluster = r.nextInt(5)
            val centers = floatArrayOf(0.18f, 0.40f, 0.58f, 0.78f, 0.92f)
            val u = (centers[cluster] + (r.nextGaussian().toFloat() * 0.11f)).coerceIn(-0.1f, 1.1f)
            val depth = r.nextFloat()
            CityLight(u, depth * depth, 0.5f + r.nextFloat() * 1.1f, tints[r.nextInt(tints.size)], r.nextFloat())
        }
    }

    LaunchedEffect(Unit) {
        var player: MediaPlayer? = null
        try {
            player = startIntroSound(context, playSound)
            kotlinx.coroutines.coroutineScope {
                launch { overall.animateTo(1f, tween(400)) }
                launch { rise.animateTo(1f, tween(1500, easing = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f))) }
                launch { delay(1250) }
            }
            runCatching { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
            kotlinx.coroutines.coroutineScope {
                launch { flash.animateTo(1f, tween(80)); flash.animateTo(0f, tween(700)) }
                launch { sparkle.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 170f)) }
                launch { delay(160); word.animateTo(1f, tween(1100, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))) }
                launch { delay(700); tag.animateTo(1f, tween(800)) }
                pops.forEachIndexed { i, a -> launch { delay(420L + i * 140L); a.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 240f)) } }
            }
            delay(1000)
            kotlinx.coroutines.coroutineScope {
                launch { exit.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
                launch { overall.animateTo(0f, tween(650)) }
            }
        } catch (_: Exception) {
        } finally {
            player?.let { mp ->
                runCatching { if (mp.isPlaying) mp.stop() }
                runCatching { mp.release() }
            }
            onFinished()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF010102)).graphicsLayer { alpha = overall.value }) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val radius = wPx * 1.9f
        // Where the planet's edge sits, as it rises into place.
        fun limbTop(): Float = hPx * (0.80f + 0.24f * (1f - rise.value))
        fun limbAt(x: Float): Float {
            val dx = x - wPx / 2f
            return limbTop() + radius - kotlin.math.sqrt((radius * radius - dx * dx).coerceAtLeast(0f))
        }

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val tau = 2f * Math.PI.toFloat()
            val time = phase.value
            val top = limbTop()
            val cx = w / 2f
            val cy = top + radius
            stars.forEach { s ->
                val tw = 0.5f + 0.5f * kotlin.math.sin((time * s.twinkle + s.phase) * tau)
                drawCircle(Color.White.copy(alpha = (0.12f + 0.6f * tw) * rise.value), radius = s.size.dp.toPx() * 0.6f, center = Offset(s.x * w, s.y * h))
            }
            // Atmosphere glow above the limb.
            val glowR = radius * 1.07f
            drawCircle(
                Brush.radialGradient(
                    colorStops = arrayOf(0f to Color.Transparent, 0.90f to Color.Transparent, 0.935f to Color(0xFFBFD6FF).copy(alpha = 0.55f), 0.965f to Color(0xFF6F8CFF).copy(alpha = 0.16f), 1f to Color.Transparent),
                    center = Offset(cx, cy), radius = glowR
                ), radius = glowR, center = Offset(cx, cy), alpha = rise.value
            )
            // Sun-lit glint toward the right.
            val gx = w * 0.80f
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color(0xFF9DB8FF).copy(alpha = 0.22f), Color.Transparent), center = Offset(gx, limbAt(gx)), radius = w * 0.42f),
                radius = w * 0.42f, center = Offset(gx, limbAt(gx)), alpha = rise.value
            )
            drawCircle(Color(0xFF020308), radius = radius, center = Offset(cx, cy))
            // Bright rim, strongest at the glint and fading toward the left.
            val rimBrush = Brush.horizontalGradient(0f to Color(0x11FFFFFF), 0.45f to Color(0x77DCE6FF), 0.8f to Color.White, 1f to Color(0xDDFFFFFF))
            listOf(34f to 0.07f, 16f to 0.14f, 7f to 0.28f, 2.2f to 0.95f).forEach { (wd, a) ->
                drawCircle(rimBrush, radius = radius, center = Offset(cx, cy), alpha = a * rise.value, style = Stroke(wd.dp.toPx()))
            }
            // City lights.
            cities.forEach { c ->
                val x = c.u * w
                val curve = cy - kotlin.math.sqrt((radius * radius - (x - cx) * (x - cx)).coerceAtLeast(0f))
                val y = curve + 3.dp.toPx() + c.v * (h - curve - 3.dp.toPx())
                if (y < h) {
                    val tw = 0.75f + 0.25f * kotlin.math.sin((time * 3f + c.phase) * tau)
                    val nearRim = 1f - c.v * 0.6f
                    drawCircle(c.tint.copy(alpha = (0.35f + 0.55f * nearRim) * tw * rise.value), radius = c.size.dp.toPx() * 0.55f, center = Offset(x, y))
                }
            }
            if (flash.value > 0.001f) drawRect(Color.White, alpha = 0.12f * flash.value)
            // The sparkle.
            val sp = sparkle.value
            if (sp > 0.01f) {
                val c = Offset(cx, h * 0.385f)
                val s = 24.dp.toPx() * sp * (0.92f + 0.08f * kotlin.math.sin(time * tau * 2f))
                drawCircle(Brush.radialGradient(listOf(Color(0xFFDCE6FF).copy(alpha = 0.35f * sp.coerceAtMost(1f)), Color.Transparent), center = c, radius = s * 3.2f), radius = s * 3.2f, center = c)
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x, c.y - s)
                    quadraticBezierTo(c.x + 0.07f * s, c.y - 0.07f * s, c.x + s * 0.9f, c.y)
                    quadraticBezierTo(c.x + 0.07f * s, c.y + 0.07f * s, c.x, c.y + s)
                    quadraticBezierTo(c.x - 0.07f * s, c.y + 0.07f * s, c.x - s * 0.9f, c.y)
                    quadraticBezierTo(c.x - 0.07f * s, c.y - 0.07f * s, c.x, c.y - s)
                    close()
                }
                drawPath(path, Color.White.copy(alpha = sp.coerceAtMost(1f)))
            }
        }

        Column(
            Modifier.fillMaxWidth().offset { IntOffset(0, (hPx * 0.43f).roundToInt()) },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "NOIR",
                modifier = Modifier.graphicsLayer {
                    alpha = word.value
                    translationY = (1f - word.value) * 14.dp.toPx()
                    val sc = 0.96f + 0.04f * word.value
                    scaleX = sc
                    scaleY = sc
                },
                style = androidx.compose.ui.text.TextStyle(
                    brush = Brush.horizontalGradient(listOf(Color(0xFF9E98AE), Color.White, Color(0xFFB9B2C8))),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    fontWeight = FontWeight.Light,
                    fontSize = 52.sp,
                    letterSpacing = 6.sp
                )
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "A UNIVERSE BETWEEN THE PAGES", color = Color(0xFFB5ABCB), fontSize = 8.sp, letterSpacing = 2.6.sp,
                modifier = Modifier.graphicsLayer { alpha = tag.value; translationY = (1f - tag.value) * 8.dp.toPx() }
            )
        }

        // The crew sits along the horizon.
        val crew = remember {
            listOf(
                Triple(NoirMascotKind.SIT, 0.03f, 66f to 381f / 420f),
                Triple(NoirMascotKind.STAR, 0.22f, 70f to 348f / 420f),
                Triple(NoirMascotKind.SLEEP, 0.40f, 92f to 304f / 420f),
                Triple(NoirMascotKind.PEEK, 0.63f, 74f to 223f / 420f),
                Triple(NoirMascotKind.SCARF, 0.82f, 58f to 420f / 384f)
            )
        }
        crew.forEachIndexed { i, (kind, xf, size) ->
            val widthDp = size.first
            val heightDp = widthDp * size.second
            Image(
                painter = painterResource(kind.res), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.width(widthDp.dp)
                    .offset {
                        val x = wPx * xf
                        val groundY = limbAt(x + widthDp.dp.toPx() / 2f) + 3.dp.toPx()
                        val bob = kotlin.math.sin(phase.value * 2f * Math.PI.toFloat() + i * 1.4f) * 1.5.dp.toPx()
                        IntOffset(x.roundToInt(), (groundY - heightDp.dp.toPx() + bob + exit.value * hPx * 0.1f).roundToInt())
                    }
                    .graphicsLayer {
                        val p = pops[i].value
                        alpha = p.coerceIn(0f, 1f)
                        val sc = 0.5f + 0.5f * p
                        scaleX = sc
                        scaleY = sc
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    }
            )
        }
    }
}

private fun introPalette(t: Float): Color {
    // White-hot → amber → coral → magenta → violet → lavender, cyclic.
    val stops = listOf(
        Color(0xFFFFF6B8), Color(0xFFFF9A3D), Color(0xFFFF4F5E), Color(0xFFFF5FC8),
        Color(0xFF7A4DFF), Color(0xFFC9B8FF), Color(0xFFFFF6B8)
    )
    val x = ((t % 1f) + 1f) % 1f * (stops.size - 1)
    val i = x.toInt().coerceIn(0, stops.size - 2)
    return androidx.compose.ui.graphics.lerp(stops[i], stops[i + 1], x - i)
}

@Composable
private fun IntroOverlay(playSound: Boolean, onFinished: () -> Unit) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val overall = remember { Animatable(0f) }
    val ringT = remember { Animatable(0f) }
    val wordT = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    val flare = remember { Animatable(0f) }
    val rise = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    val move = remember { Animatable(0f) }
    val tag = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    val pops = remember { List(5) { Animatable(0f) } }
    val drift = rememberInfiniteTransition(label = "intro-drift")
    val phase = drift.animateFloat(0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "intro-phase")

    LaunchedEffect(Unit) {
        var player: MediaPlayer? = null
        try {
            val appContext = context.applicationContext
            val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (playSound && audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
                player = runCatching { MediaPlayer.create(appContext, R.raw.noir_intro) }.getOrNull()
                player?.let { mp ->
                    runCatching { mp.setVolume(0.85f, 0.85f); mp.start() }
                        .onFailure { runCatching { mp.release() }; player = null }
                }
            }
            // 1) Quiet opening: a violet ring breathes outward while the wordmark warms up.
            kotlinx.coroutines.coroutineScope {
                launch { overall.animateTo(1f, tween(350)) }
                launch { ringT.animateTo(1f, tween(1250, easing = LinearOutSlowInEasing)) }
                launch { wordT.animateTo(1f, tween(1250, easing = LinearEasing)) }
            }
            // 2) Impact: light bursts in, the glowing sheet rises, the logo glides to its corner.
            runCatching { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
            kotlinx.coroutines.coroutineScope {
                launch { flash.animateTo(1f, tween(70)); flash.animateTo(0f, tween(520)) }
                launch { flare.animateTo(1f, tween(260)); flare.animateTo(0f, tween(950)) }
                launch { rise.animateTo(1f, tween(1050, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))) }
                launch { glow.animateTo(1f, tween(950)) }
                launch { move.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
                launch { delay(520); tag.animateTo(1f, tween(700)) }
                pops.forEachIndexed { i, a -> launch { delay(380L + i * 150L); a.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 260f)) } }
            }
            delay(1000)
            kotlinx.coroutines.coroutineScope {
                launch { exit.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
                launch { overall.animateTo(0f, tween(650)) }
            }
        } catch (_: Exception) {
        } finally {
            player?.let { mp ->
                runCatching { if (mp.isPlaying) mp.stop() }
                runCatching { mp.release() }
            }
            onFinished()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF010102)).graphicsLayer { alpha = overall.value }) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val rowW = 132.dp
        val rowWPx = with(density) { rowW.toPx() }
        val sheetLeftFrac = 0.06f
        val sheetTopFrac = 0.40f

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val breathe = kotlin.math.sin(phase.value * 2f * Math.PI.toFloat() * 2f) * 5.dp.toPx()
            val top = (h * 1.08f + (h * sheetTopFrac - h * 1.08f) * rise.value) + breathe * rise.value + exit.value * h * 0.22f
            val left = w * sheetLeftFrac
            val sw = w * 1.3f
            val sh = h * 1.3f
            val corner = 38.dp.toPx()
            val inten = glow.value * (1f - exit.value)

            // Opening ring.
            if (ringT.value < 1f) {
                val r = w * (0.10f + 0.70f * ringT.value)
                val a = (1f - ringT.value).coerceIn(0f, 1f)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.Transparent, 0.66f to Color.Transparent,
                        0.86f to Color(0xFF5A32D8).copy(alpha = 0.55f * a), 1f to Color.Transparent,
                        center = Offset(w / 2f, h / 2f), radius = r * 1.15f
                    ),
                    radius = r * 1.15f, center = Offset(w / 2f, h / 2f)
                )
            }
            if (inten > 0.001f) {
                // Light spilling onto the dark area above the sheet.
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFFFF4F5E).copy(alpha = 0.50f * inten), Color.Transparent), center = Offset(w * 0.98f, top * 0.15f), radius = w * 0.95f),
                    radius = w * 0.95f, center = Offset(w * 0.98f, top * 0.15f)
                )
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFF5B3BD8).copy(alpha = 0.50f * inten), Color.Transparent), center = Offset(0f, top * 0.30f), radius = w * 0.9f),
                    radius = w * 0.9f, center = Offset(0f, top * 0.30f)
                )
                // Rotating hot-spot around the sheet edge.
                val colors = List(33) { i -> introPalette(i / 32f + phase.value) }
                val sweep = Brush.sweepGradient(colors, center = Offset(left + sw * 0.4f, top + sh * 0.35f))
                for (i in 0 until 11) {
                    val f = 1f - i / 11f
                    drawRoundRect(
                        brush = sweep, topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(sw, sh),
                        cornerRadius = CornerRadius(corner), alpha = (0.55f * f * f * inten).coerceIn(0f, 1f),
                        style = Stroke(width = (2f + i * 9f).dp.toPx())
                    )
                }
                // Inner colour: pink-red pooling toward the bottom, white-hot along the left rim.
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent, 0.35f to Color.Transparent,
                        0.75f to Color(0xFFB23CFF).copy(alpha = 0.55f), 1f to Color(0xFFFF5878).copy(alpha = 0.85f),
                        startY = top, endY = h
                    ),
                    topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(sw, sh), cornerRadius = CornerRadius(corner), alpha = inten
                )
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.White.copy(alpha = 0.95f), 0.35f to Color(0xFFFFC07A).copy(alpha = 0.35f), 1f to Color.Transparent,
                        startX = left, endX = left + w * 0.22f
                    ),
                    topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(sw, sh), cornerRadius = CornerRadius(corner), alpha = 0.9f * inten
                )
                drawRoundRect(
                    color = Color.White, topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(sw, sh),
                    cornerRadius = CornerRadius(corner), alpha = 0.85f * inten, style = Stroke(1.6.dp.toPx())
                )
            }
            // Lens flare riding the sheet's left edge.
            if (flare.value > 0.01f) {
                val c = Offset(left + 4.dp.toPx(), top + (h - top) * 0.34f)
                val len = w * 0.55f * flare.value
                val a = flare.value
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f * a), Color(0xFFFFA24A).copy(alpha = 0.45f * a), Color.Transparent), center = c, radius = 110.dp.toPx()),
                    radius = 110.dp.toPx(), center = c
                )
                listOf(0f to 2.2f, 90f to 1.4f, 22f to 1.6f, 112f to 1.1f).forEach { (deg, wd) ->
                    val rad = Math.toRadians(deg.toDouble())
                    val dx = (kotlin.math.cos(rad) * len).toFloat()
                    val dy = (kotlin.math.sin(rad) * len).toFloat()
                    drawLine(
                        Brush.linearGradient(listOf(Color.Transparent, Color(0xFFFFB45C).copy(alpha = 0.9f * a), Color.Transparent), start = c - Offset(dx, dy), end = c + Offset(dx, dy)),
                        start = c - Offset(dx, dy), end = c + Offset(dx, dy), strokeWidth = wd.dp.toPx()
                    )
                }
            }
            if (flash.value > 0.001f) drawRect(Color.White, alpha = 0.22f * flash.value)
        }

        // The crew: five little NOIR cats pop in once the sheet has risen, so the glow is never empty.
        val crew = remember {
            listOf(
                Triple(NoirMascotKind.PEEK, floatArrayOf(0.60f, 0.495f), 128f),
                Triple(NoirMascotKind.SIT, floatArrayOf(0.05f, 0.565f), 118f),
                Triple(NoirMascotKind.SCARF, floatArrayOf(0.62f, 0.585f), 104f),
                Triple(NoirMascotKind.STAR, floatArrayOf(0.07f, 0.755f), 124f),
                Triple(NoirMascotKind.SLEEP, floatArrayOf(0.47f, 0.775f), 168f)
            )
        }
        crew.forEachIndexed { i, (kind, pos, sizeDp) ->
            Image(
                painter = painterResource(kind.res), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.width(sizeDp.dp)
                    .offset {
                        val bob = kotlin.math.sin(phase.value * 2f * Math.PI.toFloat() + i * 1.3f) * 4.dp.toPx()
                        IntOffset((wPx * pos[0]).roundToInt(), (hPx * pos[1] + bob + exit.value * hPx * 0.22f).roundToInt())
                    }
                    .graphicsLayer {
                        val p = pops[i].value
                        alpha = p.coerceIn(0f, 1f)
                        val sc = 0.55f + 0.45f * p
                        scaleX = sc
                        scaleY = sc
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                        translationY = (1f - p) * 36.dp.toPx()
                    }
            )
        }

        // Logo: starts centred and dim, ends white in the sheet's top-left corner.
        val m = move.value
        val sheetTopPx = hPx * 1.08f + (hPx * sheetTopFrac - hPx * 1.08f) * rise.value
        val cx = (wPx - rowWPx) / 2f
        val cy = hPx / 2f - with(density) { 16.dp.toPx() }
        val tx = wPx * sheetLeftFrac + with(density) { 24.dp.toPx() }
        val ty = sheetTopPx + with(density) { 26.dp.toPx() }
        val warm = androidx.compose.ui.graphics.lerp(
            androidx.compose.ui.graphics.lerp(Color(0xFF8B6BFF), Color(0xFFE060B8), (wordT.value * 2f).coerceIn(0f, 1f)),
            Color(0xFFFF6A4D), ((wordT.value - 0.5f) * 2f).coerceIn(0f, 1f)
        )
        val logoColor = androidx.compose.ui.graphics.lerp(warm, Color.White, m)
        Column(
            Modifier.width(rowW)
                .offset { IntOffset((cx + (tx - cx) * m).roundToInt(), (cy + (ty - cy) * m + exit.value * hPx * 0.22f * m).roundToInt()) }
                .graphicsLayer { alpha = (wordT.value * 3f).coerceIn(0f, 1f) }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp)) { NoirLogoMark(30.dp) }
                Spacer(Modifier.width(10.dp))
                Text("NOIR", color = logoColor, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp)
            }
            Text(
                "A UNIVERSE BETWEEN THE PAGES", color = Color.White.copy(alpha = 0.75f), fontSize = 7.sp, letterSpacing = 1.6.sp,
                maxLines = 1, softWrap = false,
                modifier = Modifier.padding(top = 9.dp, start = 2.dp).graphicsLayer { alpha = tag.value; translationY = (1f - tag.value) * 6.dp.toPx() }
            )
        }
    }
}
