package com.noir.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
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
    var tab by remember { mutableStateOf("Discover") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Series?>(null) }
    var selectedChapter by remember { mutableStateOf<Chapter?>(null) }
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var pages by remember { mutableStateOf<List<String>>(emptyList()) }
    var catalogue by remember { mutableStateOf<List<Series>>(emptyList()) }
    val saved = remember { mutableStateListOf<Series>() }
    var loading by remember { mutableStateOf(false) }
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
                else -> CatalogueScreen(tab, query, { query = it }, catalogue, loading, error, { selected = it }, { tab = it })
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
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(22.dp))
            Text("NOIR / YOUR READING ROOM", color = Muted, fontSize = 10.sp, letterSpacing = 2.2.sp)
            Text(if (tab == "Home") "NOIR" else "Discover", color = White, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search manga and manhwa", color = Muted) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Muted) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, null, tint = Muted) } },
                singleLine = true, shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Silver, unfocusedBorderColor = Panel,
                    focusedContainerColor = Panel, unfocusedContainerColor = Panel,
                    focusedTextColor = White, unfocusedTextColor = White
                )
            )
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (query.isBlank()) "POPULAR TITLES" else "SEARCH RESULTS", color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.weight(1f))
                Text("SAFE-RATED", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(12.dp))
            when {
                loading && catalogue.isEmpty() -> LoadingState("Loading catalogue…")
                error != null && catalogue.isEmpty() -> ErrorState(error)
                catalogue.isEmpty() -> EmptyState("No titles found", "Try another title or check your connection.")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                    items(catalogue, key = { it.id }) { SeriesRow(it) { onOpen(it) } }
                }
            }
        }
        BottomNav(tab, onTab)
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
    if (url != null) AsyncImage(model = url, contentDescription = "Manga cover", modifier = modifier.clip(RoundedCornerShape(12.dp)))
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
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 12.dp).clip(RoundedCornerShape(30.dp)).background(Color(0xFF202024)).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("Home" to Icons.Rounded.Home, "Discover" to Icons.Rounded.Explore, "Library" to Icons.Rounded.Bookmarks, "Sources" to Icons.Rounded.Extension).forEach { (label, icon) ->
            val active = tab == label
            Column(Modifier.weight(1f).clip(CircleShape).background(if (active) Color(0xFF414146) else Color.Transparent).clickable { onTab(label) }.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, tint = if (active) White else Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.height(3.dp))
                Text(label, color = if (active) White else Muted, fontSize = 8.sp)
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
