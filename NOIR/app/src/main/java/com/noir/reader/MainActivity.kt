package com.noir.reader

import android.os.Bundle
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(7, 7, 8)
        window.navigationBarColor = android.graphics.Color.rgb(7, 7, 8)
        setContent { NoirApp() }
    }
}

@Composable
private fun NoirApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("noir_preferences", Context.MODE_PRIVATE) }
    var tab by remember { mutableStateOf("Home") }
    var introSound by remember { mutableStateOf(prefs.getBoolean("intro_sound", true)) }
    var launchIntro by remember { mutableStateOf(prefs.getBoolean("launch_intro", true)) }
    var showAbout by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Series?>(null) }
    var selectedChapter by remember { mutableStateOf<Chapter?>(null) }
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var pages by remember { mutableStateOf<List<String>>(emptyList()) }
    var catalogue by remember { mutableStateOf<List<Series>>(emptyList()) }
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
                            sourceName = item.optString("sourceName")
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
            )
        }
        prefs.edit().putString("saved_series", stored.toString()).apply()
    }
    var loading by remember { mutableStateOf(false) }
    var showIntro by remember { mutableStateOf(launchIntro) }
    var error by remember { mutableStateOf<String?>(null) }
    val source = SourceRegistry.sources.first()

    LaunchedEffect(query, tab) {
        if (selected == null && selectedChapter == null && (tab == "Discover" || tab == "Home")) {
            delay(if (query.isBlank()) 100 else 450)
            loading = true
            error = null
            try {
                catalogue = if (query.isBlank()) source.popular() else source.search(query)
            } catch (e: Exception) {
                error = e.message ?: "Could not load catalogue. Check your connection."
            } finally { loading = false }
        }
    }
    LaunchedEffect(selected?.id) {
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
        try { pages = SourceRegistry.byId(selected?.sourceId ?: "")?.pages(chapter)?.pages.orEmpty() }
        catch (e: Exception) { error = e.message ?: "Could not load chapter pages." }
        finally { loading = false }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Black, surface = Panel, primary = Silver, onBackground = White, onSurface = White)) {
        Surface(Modifier.fillMaxSize(), color = Black) {
          Box(Modifier.fillMaxSize()) {
            when {
                selectedChapter != null -> ReaderScreen(selected, selectedChapter!!, pages, loading, error) { selectedChapter = null }
                selected != null -> DetailScreen(
                    selected!!, chapters, saved.any { it.id == selected!!.id }, loading, error,
                    onBack = { selected = null },
                    onSave = {
                        val item = selected!!
                        if (saved.any { it.id == item.id }) saved.removeAll { it.id == item.id } else saved.add(item)
                    },
                    onChapter = { selectedChapter = it }
                )
                tab == "Sources" -> SourcesScreen { tab = it }
                tab == "Library" -> LibraryScreen(saved.toList(), { selected = it }, { tab = it })
                tab == "Profile" -> ProfileScreen(introSound, launchIntro,
                    { introSound = it; prefs.edit().putBoolean("intro_sound", it).apply() },
                    { launchIntro = it; prefs.edit().putBoolean("launch_intro", it).apply() },
                    { showAbout = true }, { tab = it })
                tab == "Home" -> HomeScreen(catalogue, loading, error, { selected = it }, { tab = it })
                else -> CatalogueScreen("Discover", query, { query = it }, catalogue, loading, error, { selected = it }, { tab = it })
            }
            if (showAbout) AlertDialog(
                onDismissRequest = { showAbout = false },
                title = { Text("About NOIR", color = White) },
                text = { Text("Your dark, focused manga and manhwa reading space. Version 0.5.0.", color = Muted) },
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
    loading: Boolean, error: String?, onOpen: (Series) -> Unit, onTab: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("NOIR  /  EXPLORE", color = Muted, fontSize = 9.sp, letterSpacing = 2.5.sp)
            Text("Discover", color = White, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search titles, manga, manhwa", color = Muted, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Silver) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, null, tint = Muted) } },
                singleLine = true, shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF827A9B), unfocusedBorderColor = Color(0xFF29272F),
                    focusedContainerColor = Color(0xFF121116), unfocusedContainerColor = Color(0xFF121116),
                    focusedTextColor = White, unfocusedTextColor = White, cursorColor = Silver
                )
            )
            Spacer(Modifier.height(19.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(if (query.isBlank()) "POPULAR COLLECTION" else "SEARCH RESULTS", color = White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
                    Text("Curated from your active sources", color = Muted, fontSize = 9.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("SAFE-RATED", color = Color(0xFFB9B0D1), fontSize = 8.sp, letterSpacing = 1.2.sp)
            }
            Spacer(Modifier.height(13.dp))
            when {
                loading && catalogue.isEmpty() -> LoadingState("Loading catalogue…")
                error != null && catalogue.isEmpty() -> ErrorState(error)
                catalogue.isEmpty() -> EmptyState("No titles found", "Try another title or check your connection.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(17.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(catalogue, key = { it.id }) { item -> CoverTile(item) { onOpen(item) } }
                }
            }
        }
        BottomNav("Discover", onTab)
    }
}

@Composable
private fun HomeScreen(catalogue: List<Series>, loading: Boolean, error: String?, onOpen: (Series) -> Unit, onTab: (String) -> Unit) {
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
                    featured?.coverUrl?.let { url -> AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
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
                        items(catalogue.take(12), key = { it.id }) { series ->
                            Column(Modifier.width(132.dp).clickable { onOpen(series) }) {
                                Box(Modifier.fillMaxWidth().height(178.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF201D26))) {
                                    Cover(series.coverUrl, Modifier.fillMaxSize())
                                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x44000000), Color(0xAA000000)))))
                                    Text(series.sourceName.uppercase(), color = Color(0xFFE4DDEC), fontSize = 7.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                                }
                                Spacer(Modifier.height(9.dp))
                                Text(series.title, color = White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 14.sp, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(3.dp))
                                Text("DISCOVER MORE", color = Muted, fontSize = 7.sp, letterSpacing = 1.sp)
                            }
                        }
                    }
                }
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
private fun CoverTile(series: Series, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().animateContentSize(tween(220)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.70f).clip(RoundedCornerShape(17.dp)).background(Color(0xFF1C1A21))) {
            Cover(series.coverUrl, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x22000000), Color(0x99000000)))))
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(20.dp)).background(Color(0x9909080D)).padding(horizontal = 8.dp, vertical = 5.dp)) {
                Text(series.sourceName.uppercase(), color = Color(0xFFE0D9E9), fontSize = 7.sp, letterSpacing = 1.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(series.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 15.sp, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(3.dp))
        Text("MANGA  /  MANHWA", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun HomeFeatureRow(series: Series, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(series.coverUrl, Modifier.size(65.dp, 84.dp))
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
private fun ProfileScreen(introSound: Boolean, launchIntro: Boolean, onIntroSound: (Boolean) -> Unit, onLaunchIntro: (Boolean) -> Unit, onAbout: () -> Unit, onTab: (String) -> Unit) {
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
            Text("0.5.0", color = Silver, fontSize = 9.sp)
        }
        Spacer(Modifier.height(26.dp))
        Text("APP SETTINGS", color = Silver, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        SettingSwitch("Intro sound", "Play the short chime when NOIR opens", introSound, onIntroSound)
        SettingSwitch("Launch animation", "Show the NOIR intro on app launch", launchIntro, onLaunchIntro)
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
private fun SeriesRow(series: Series, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(series.coverUrl, Modifier.size(82.dp, 112.dp))
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
    series: Series, chapters: List<Chapter>, saved: Boolean, loading: Boolean, error: String?,
    onBack: () -> Unit, onSave: () -> Unit, onChapter: (Chapter) -> Unit
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
                    Cover(series.coverUrl, Modifier.fillMaxSize())
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
                        if (chapters.isNotEmpty()) "${chapters.size} AVAILABLE" else "MANGADEX",
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
                item { EmptyState("No chapters available", "There are no English chapters listed for this series yet.") }
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
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color(0xFFD6C5F2), modifier = Modifier.size(23.dp))
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
private fun ReaderScreen(series: Series?, chapter: Chapter, pages: List<String>, loading: Boolean, error: String?, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().background(Black).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.Close, null, tint = White) }
            Column(Modifier.weight(1f)) {
                Text(series?.title ?: "NOIR READER", color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(chapter.name, color = Muted, fontSize = 9.sp)
            }
            Text("VERTICAL", color = Silver, fontSize = 8.sp, letterSpacing = 1.sp)
        }
        when {
            loading && pages.isEmpty() -> LoadingState("Loading chapter pages…")
            error != null -> ErrorState(error)
            pages.isEmpty() -> EmptyState("No pages available", "This chapter may not have readable pages.")
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                items(pages) { page -> AsyncImage(model = page, contentDescription = "Comic page", modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun SourcesScreen(onTab: (String) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onTab("Profile") }) { Icon(Icons.Rounded.ArrowBack, null, tint = White) }
            Column {
                Text("NOIR / SOURCE MANAGER", color = Muted, fontSize = 9.sp, letterSpacing = 1.8.sp)
                Text("Manage sources", color = White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp)) {
            item {
                Text("YOUR CATALOGUES", color = Silver, fontSize = 9.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
            }
            items(SourceRegistry.sources, key = { it.id }) { source ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).border(1.dp, Color(0xFF302C37), RoundedCornerShape(18.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(43.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF292432)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Public, null, tint = Color(0xFFD6C9E7), modifier = Modifier.size(23.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 13.dp, end = 8.dp)) {
                        Text(source.name, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        Text(source.description, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("ACTIVE", color = Color(0xFFC8D8C5), fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                        Text("●", color = Color(0xFF9CB895), fontSize = 8.sp)
                    }
                }
                Spacer(Modifier.height(11.dp))
            }
            item {
                Spacer(Modifier.height(10.dp))
                Text("ABOUT SOURCES", color = Silver, fontSize = 9.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(9.dp))
                Text("NOIR currently connects to the MangaDex catalogue. The source interface is designed to support additional providers, but installing third-party extensions is not available in this build.", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(13.dp))
                Text("More source options can be added in a future update.", color = Color(0xFFC8BED8), fontSize = 10.sp)
            }
        }
        BottomNav("Profile", onTab)
    }
}

@Composable
private fun LibraryScreen(items: List<Series>, onOpen: (Series) -> Unit, onTab: (String) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("YOUR COLLECTION", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
            Text("Library", color = White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text("SAVED TITLES", color = Color(0xFFB8A9D0), fontSize = 8.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(18.dp))
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState("Your library is waiting", "Open a series and tap the bookmark to keep it here.")
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                    items(items, key = { it.id }) { SeriesRow(it) { onOpen(it) } }
                }
            }
        }
        BottomNav("Library", onTab)
    }
}

@Composable
private fun BottomNav(tab: String, onTab: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 7.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(Brush.verticalGradient(listOf(Color(0xE52B2931), Color(0xF20F0E13))))
            .border(1.dp, Color(0xFF46414F).copy(alpha = .72f), RoundedCornerShape(30.dp))
            .padding(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf("Home" to Icons.Rounded.Home, "Discover" to Icons.Rounded.Explore, "Library" to Icons.Rounded.Bookmarks, "Profile" to Icons.Rounded.Person).forEach { (label, icon) ->
            val active = tab == label
            val pillColor by animateColorAsState(
                targetValue = if (active) Color(0xFF393342) else Color.Transparent,
                animationSpec = tween(260), label = "nav-pill-$label"
            )
            val iconScale by animateFloatAsState(
                targetValue = if (active) 1.08f else 1f,
                animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
                label = "nav-icon-$label"
            )
            val labelColor by animateColorAsState(
                targetValue = if (active) White else Color(0xFF89858F),
                animationSpec = tween(220), label = "nav-label-$label"
            )
            Column(
                Modifier.weight(1f).clip(CircleShape)
                    .background(pillColor)
                    .clickable { onTab(label) }.padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, null, tint = labelColor, modifier = Modifier.size(18.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale })
                Spacer(Modifier.height(3.dp))
                Text(label, color = labelColor, fontSize = 8.sp, letterSpacing = .3.sp)
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

