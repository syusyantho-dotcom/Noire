package com.noir.reader

import android.os.Bundle
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay

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
    val saved = remember { mutableStateListOf<Series>() }
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
                tab == "Sources" -> SourcesScreen()
                tab == "Library" -> LibraryScreen(saved.toList()) { selected = it }
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
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(19.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("NOIR  /  YOUR READING ROOM", color = Muted, fontSize = 9.sp, letterSpacing = 2.1.sp)
                        Text("Good stories.\nNo distractions.", color = White, fontSize = 31.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold)
                    }
                    NoirLogoMark(43.dp)
                }
            }
            item {
                Box(Modifier.fillMaxWidth().height(222.dp).clip(RoundedCornerShape(25.dp)).background(Color(0xFF17141F))) {
                    catalogue.firstOrNull()?.coverUrl?.let { url ->
                        AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF5070709), Color(0x99070709), Color(0x22070709)))))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC070709)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                        Text("THE NOIR SELECTION", color = Color(0xFFD0C7E2), fontSize = 9.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(catalogue.firstOrNull()?.title ?: "A universe of stories", color = White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(5.dp))
                        Text("Discover something worth staying up for", color = Color(0xFFD0CDD4), fontSize = 10.sp)
                        Spacer(Modifier.height(11.dp))
                        Row(Modifier.clip(RoundedCornerShape(30.dp)).background(Color(0x553F394B)).border(1.dp, Color(0x66D6CDE5), RoundedCornerShape(30.dp)).clickable { catalogue.firstOrNull()?.let(onOpen) }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("EXPLORE FEATURE", color = White, fontSize = 8.sp, letterSpacing = 1.4.sp)
                            Spacer(Modifier.width(7.dp))
                            Icon(Icons.Rounded.ArrowForward, null, tint = White, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Popular right now", color = White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Stories selected from the catalogue", color = Muted, fontSize = 10.sp)
                    }
                    Text("VIEW ALL  →", color = Color(0xFFC9BEDC), fontSize = 8.sp, letterSpacing = 1.2.sp, modifier = Modifier.clickable { onTab("Discover") }.padding(8.dp))
                }
            }
            when {
                loading && catalogue.isEmpty() -> item { LoadingState("Curating your home…") }
                error != null && catalogue.isEmpty() -> item { ErrorState(error) }
                catalogue.isEmpty() -> item { EmptyState("Nothing to show yet", "Check your connection and try Discover.") }
                else -> items(catalogue.take(10), key = { it.id }) { series -> HomeFeatureRow(series) { onOpen(series) } }
            }
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Color(0xFF211D2B), Color(0xFF111014)))).clickable { onTab("Discover") }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("YOUR NEXT READ IS OUT THERE", color = Color(0xFFCFC5E0), fontSize = 8.sp, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(5.dp))
                        Text("Explore the full catalogue", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Icon(Icons.Rounded.ArrowForward, null, tint = Silver)
                }
            }
        }
        BottomNav("Home", onTab)
    }
}

@Composable
private fun CoverTile(series: Series, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
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
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null, tint = White) }
            Text("SERIES DETAILS", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSave) { Icon(if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, null, tint = Silver) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            item {
                Row(verticalAlignment = Alignment.Top) {
                    Cover(series.coverUrl, Modifier.size(126.dp, 176.dp))
                    Column(Modifier.weight(1f).padding(start = 15.dp)) {
                        Text(series.title, color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("SOURCE / ${series.sourceName.uppercase()}", color = Silver, fontSize = 9.sp, letterSpacing = 1.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(if (saved) "IN YOUR LIBRARY" else "NOT IN LIBRARY", color = Muted, fontSize = 9.sp)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("SYNOPSIS", color = Silver, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(series.description, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(24.dp))
                Text("CHAPTERS", color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(12.dp))
            }
            if (loading && chapters.isEmpty()) item { LoadingState("Loading chapters…") }
            if (error != null) item { ErrorState(error) }
            items(chapters, key = { it.id }) { chapter ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(13.dp)).background(Panel).clickable { onChapter(chapter) }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(chapter.name, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text("ENGLISH · ${series.sourceName}", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
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
private fun SourcesScreen() {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(22.dp)) {
        Text("NOIR / SOURCES", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
        Text("Catalogues", color = White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(20.dp))
        SourceRegistry.sources.forEach { source ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Public, null, tint = Silver, modifier = Modifier.size(34.dp))
                Column(Modifier.weight(1f).padding(start = 13.dp)) {
                    Text(source.name, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(source.description, color = Muted, fontSize = 10.sp)
                }
                Text("ACTIVE", color = Color(0xFFBFC9BD), fontSize = 8.sp, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("The source engine is modular: each catalogue is an independent provider, and the reader uses the same chapter and page interface for every provider.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(12.dp))
        Text("This build requests safe-rated catalogue entries only.", color = Silver, fontSize = 10.sp)
    }
}

@Composable
private fun LibraryScreen(items: List<Series>, onOpen: (Series) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
        Text("YOUR COLLECTION", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
        Text("Library", color = White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        if (items.isEmpty()) EmptyState("Your library is empty", "Open a series and tap the bookmark to save it.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) { items(items, key = { it.id }) { SeriesRow(it) { onOpen(it) } } }
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
            Column(
                Modifier.weight(1f).clip(CircleShape)
                    .background(if (active) Brush.verticalGradient(listOf(Color(0xFF51495F), Color(0xFF292531))) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { onTab(label) }.padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, null, tint = if (active) White else Color(0xFF89858F), modifier = Modifier.size(18.dp))
                Spacer(Modifier.height(3.dp))
                Text(label, color = if (active) White else Color(0xFF89858F), fontSize = 8.sp, letterSpacing = .3.sp)
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
private fun NoirLogoMark(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(Color(0xFF393542), Color(0xFF111016), Color(0xFF070708))))
            .border(1.dp, Color(0xFFAAA2B9).copy(alpha = .58f), RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x665D566F), Color.Transparent), radius = size.value * 0.85f)))
        Text("N", color = Color(0xFFF0EDF5), fontSize = (size.value * .73f).sp, fontWeight = FontWeight.Normal,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif)
        Box(Modifier.width(size * 1.02f).height(size * .43f).rotate(-25f)
            .border((size.value / 85f).coerceAtLeast(.8f).dp, Color(0xFFD2C9E6).copy(alpha = .88f), CircleShape))
        Text("✦", color = Color(0xFFF3F0FA), fontSize = (size.value * .18f).sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = size * .14f, end = size * .16f))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(.58f).height(size * .13f)
            .rotate(-25f).background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x557F7694), Color.Transparent))))
    }
}

@Composable
private fun IntroOverlay(playSound: Boolean, onFinished: () -> Unit) {
    val context = LocalContext.current
    val alpha = remember { Animatable(0f) }
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
            alpha.animateTo(1f, tween(520))
            delay(780)
            alpha.animateTo(0f, tween(380))
        } catch (_: Exception) {
        } finally {
            player?.let { runCatching { it.setOnCompletionListener(null); it.release() } }
            onFinished()
        }
    }
    Box(Modifier.fillMaxSize().background(Black).graphicsLayer { this.alpha = alpha.value }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NoirLogoMark(104.dp)
            Spacer(Modifier.height(22.dp))
            Text("NOIR", color = White, fontSize = 29.sp, fontWeight = FontWeight.Normal, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, letterSpacing = 7.sp)
            Spacer(Modifier.height(9.dp))
            Text("MANGA  •  MANHWA  •  MORE", color = Muted, fontSize = 8.sp, letterSpacing = 2.4.sp)
        }
    }
}

