package com.noir.reader

import android.os.Bundle
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

private val Ink = Color(0xFF050505)
private val Panel = Color(0xFF141416)
private val Muted = Color(0xFF929297)
private val White = Color(0xFFF5F5F5)
private val Silver = Color(0xFFBFC0C5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(5, 5, 5)
        window.navigationBarColor = android.graphics.Color.rgb(5, 5, 5)
        setContent { NoirApp() }
    }
}

data class Manga(val title: String, val genre: String, val chapters: String, val accent: Color, val symbol: String, val description: String)

private val catalog = listOf(
    Manga("The Beginning After the End", "Fantasy · Adventure", "Ch. 214", Color(0xFF5D7180), "TBATE", "A king is given another chance at life in a world of magic, mystery and dangerous choices."),
    Manga("Solo Leveling", "Action · Fantasy", "Ch. 200", Color(0xFF35465C), "LEVEL", "A once-weak hunter discovers a system that lets him grow beyond every limit."),
    Manga("The Boxer", "Drama · Sports", "Ch. 123", Color(0xFF75675D), "BOXER", "A quiet, unsettling story about talent, ambition and the cost of winning."),
    Manga("Omniscient Reader", "Action · Fantasy", "Ch. 198", Color(0xFF675B75), "ORV", "The world becomes the novel only one reader knows how to survive."),
    Manga("Berserk", "Dark Fantasy", "Vol. 42", Color(0xFF554C48), "BERSERK", "A lone swordsman walks a brutal path through a world consumed by darkness."),
    Manga("Blue Lock", "Sports · Drama", "Ch. 287", Color(0xFF365B78), "BLUE LOCK", "Strikers compete to become the world's most decisive football player.")
)

@Composable
fun NoirApp() {
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<Manga?>(null) }
    var reading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var showIntro by remember { mutableStateOf(true) }
    val saved = remember { mutableStateListOf<String>().apply { add("Solo Leveling"); add("Omniscient Reader") } }
    val progress = remember { mutableStateMapOf("Solo Leveling" to "Chapter 198", "The Boxer" to "Chapter 42") }

    MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, primary = Silver, onBackground = White, onSurface = White)) {
        Box(Modifier.fillMaxSize().background(Ink)) {
            AnimatedContent(
                targetState = when { reading -> "Reader"; selected != null -> "Detail"; else -> tab },
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(110)) },
                label = "screen"
            ) { screen ->
                when (screen) {
                    "Home" -> HomeScreen(onOpen = { selected = it }, onContinue = { selected = it }, progress = progress)
                    "Discover" -> DiscoverScreen(query, { query = it }, onOpen = { selected = it })
                    "Library" -> LibraryScreen(saved, filter, { filter = it }, onOpen = { selected = it })
                    "Downloads" -> DownloadsScreen()
                    "Profile" -> ProfileScreen()
                    "Detail" -> selected?.let { DetailScreen(it, saved.contains(it.title), { if (saved.contains(it.title)) saved.remove(it.title) else saved.add(it.title) }, { reading = true }, { selected = null }) }
                    "Reader" -> ReaderScreen(selected, { reading = false })
                }
            }
            if (selected == null && !reading) GlassNav(tab, { tab = it }, Modifier.align(Alignment.BottomCenter).padding(start = 22.dp, end = 22.dp, bottom = 18.dp))
            if (showIntro) IntroOverlay(onFinished = { showIntro = false })
        }
    }
}

@Composable
private fun IntroOverlay(onFinished: () -> Unit) {
    val context = LocalContext.current
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        var player: MediaPlayer? = null
        try {
            // Use the application context and keep one owner for MediaPlayer cleanup.
            // Releasing it in both the completion callback and finally can crash on some devices.
            val appContext = context.applicationContext
            val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
                player = runCatching { MediaPlayer.create(appContext, R.raw.noir_chime) }.getOrNull()
                player?.let { mediaPlayer ->
                    runCatching {
                        mediaPlayer.setVolume(0.72f, 0.72f)
                        mediaPlayer.start()
                    }.onFailure {
                        runCatching { mediaPlayer.release() }
                        player = null
                    }
                }
            }
            alpha.animateTo(1f, tween(520))
            delay(780)
            alpha.animateTo(0f, tween(380))
        } catch (_: Exception) {
            // Audio must never prevent the app from opening.
        } finally {
            // Release exactly once; do not query isPlaying after release/completion.
            player?.let { runCatching { it.setOnCompletionListener(null); it.release() } }
            onFinished()
        }
    }
    Box(Modifier.fillMaxSize().background(Ink).graphicsLayer { this.alpha = alpha.value }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(104.dp).clip(RoundedCornerShape(30.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF292832), Color(0xFF09090D))))
                .border(1.dp, Silver.copy(alpha = .52f), RoundedCornerShape(30.dp)), contentAlignment = Alignment.Center) {
                Text("N", color = White, fontSize = 72.sp, fontWeight = FontWeight.Light, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif)
                Box(Modifier.width(106.dp).height(48.dp).rotate(-25f).border(1.2.dp, Color(0xFFB9B2D2).copy(alpha = .82f), CircleShape))
                Text("✦", color = Color(0xFFE8E4F5), fontSize = 17.sp, modifier = Modifier.align(Alignment.TopEnd).padding(top = 15.dp, end = 18.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text("NOIR", color = White, fontSize = 29.sp, fontWeight = FontWeight.Normal, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, letterSpacing = 7.sp)
            Spacer(Modifier.height(9.dp))
            Text("MANGA  •  MANHWA  •  MORE", color = Muted, fontSize = 8.sp, letterSpacing = 2.4.sp)
        }
    }
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), content = content)
}

@Composable
private fun Eyebrow(text: String) { Text(text.uppercase(), color = Muted, fontSize = 10.sp, letterSpacing = 2.2.sp, fontWeight = FontWeight.Medium) }

@Composable
private fun HomeScreen(onOpen: (Manga) -> Unit, onContinue: (Manga) -> Unit, progress: Map<String, String>) {
    ScreenColumn {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Eyebrow("Your private reading room"); Text("NOIR", fontSize = 39.sp, fontWeight = FontWeight.Normal, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, letterSpacing = 4.sp, color = White) }
            Box(Modifier.size(42.dp).clip(CircleShape).background(Panel).border(1.dp, Color.White.copy(.12f), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Search, null, tint = Silver) }
        }
        Spacer(Modifier.height(28.dp))
        Eyebrow("Curated for tonight")
        FeaturedCard(catalog[0], onClick = { onOpen(catalog[0]) })
        Spacer(Modifier.height(30.dp))
        SectionTitle("Pick up where you left off", "VIEW ALL")
        val recent = catalog.filter { progress.containsKey(it.title) }
        recent.forEach { manga -> ContinueRow(manga, progress[manga.title] ?: "", { onContinue(manga) }) }
        Spacer(Modifier.height(24.dp))
        SectionTitle("Popular right now", "EXPLORE")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(catalog, key = { it.title }) { manga -> PosterCard(manga, Modifier.width(132.dp), { onOpen(manga) }) } }
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun FeaturedCard(manga: Manga, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(410.dp).padding(top = 14.dp).clip(RoundedCornerShape(28.dp))
        .background(Brush.verticalGradient(listOf(Color(0xFF71818C), Color(0xFF303A42), Color(0xFF090A0C))))
        .clickable { onClick() }) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawCircle(Brush.radialGradient(listOf(manga.accent.copy(alpha = .72f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(w * .72f, h * .36f), radius = w * .78f), radius = w * .78f, center = androidx.compose.ui.geometry.Offset(w * .72f, h * .36f))
            for (i in 0..7) {
                drawCircle(Color.White.copy(alpha = .035f - i * .003f), radius = w * (.18f + i * .075f), center = androidx.compose.ui.geometry.Offset(w * .72f, h * .37f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx()))
            }
            val silhouette = Path().apply {
                moveTo(w * .48f, h * .70f); cubicTo(w * .43f, h * .55f, w * .52f, h * .34f, w * .67f, h * .30f)
                cubicTo(w * .84f, h * .25f, w * .96f, h * .42f, w * .91f, h * .62f)
                lineTo(w * 1.02f, h * .83f); lineTo(w * .38f, h * .83f); close()
            }
            drawPath(silhouette, Brush.verticalGradient(listOf(Color(0xFFCBD0D2).copy(.32f), Color(0xFF15191D).copy(.08f))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.94f)), startY = h * .48f, endY = h), size = size)
        }
        Column(Modifier.align(Alignment.TopStart).padding(23.dp)) {
            Text("NO. 001     /     EDITOR'S CHOICE", color = White.copy(.76f), fontSize = 9.sp, letterSpacing = 2.1.sp)
            Spacer(Modifier.height(24.dp))
            Text("N O I R", color = White.copy(.13f), fontSize = 45.sp, fontWeight = FontWeight.Black, letterSpacing = 7.sp)
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(23.dp)) {
            Eyebrow("FEATURED SERIES")
            Spacer(Modifier.height(7.dp))
            Text(manga.title, color = White, fontSize = 27.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(15.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(manga.genre, color = White.copy(.72f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Row(Modifier.clip(CircleShape).background(White).clickable { onClick() }.padding(horizontal = 17.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("EXPLORE", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.width(8.dp)); Icon(Icons.Rounded.ArrowForward, null, tint = Ink, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, action: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(action, color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp) }
    Spacer(Modifier.height(15.dp))
}

@Composable
private fun ContinueRow(manga: Manga, chapter: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(16.dp)).background(Panel).clickable { onClick() }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        PosterArt(manga, Modifier.size(width = 58.dp, height = 76.dp), false)
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) { Text(manga.title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(5.dp)); Text(chapter, color = Muted, fontSize = 11.sp); Spacer(Modifier.height(9.dp)); Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(Color.White.copy(.12f))) { Box(Modifier.fillMaxWidth(.64f).fillMaxHeight().background(Silver)) } }
        Icon(Icons.Rounded.PlayArrow, null, tint = Silver, modifier = Modifier.padding(horizontal = 7.dp))
    }
}

@Composable
private fun DiscoverScreen(query: String, onQuery: (String) -> Unit, onOpen: (Manga) -> Unit) {
    ScreenColumn {
        Spacer(Modifier.height(32.dp)); Eyebrow("Find your next world"); Text("Discover", color = White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), placeholder = { Text("Titles, genres, creators…", color = Muted) }, leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Muted) }, singleLine = true, shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Panel, focusedBorderColor = Silver, unfocusedContainerColor = Panel, focusedContainerColor = Panel, focusedTextColor = White, unfocusedTextColor = White))
        Spacer(Modifier.height(25.dp)); SectionTitle("Trending selection", "THIS WEEK")
        catalog.filter { it.title.contains(query, true) || it.genre.contains(query, true) }.forEach { manga -> ContinueRow(manga, manga.chapters, { onOpen(manga) }) }
        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun LibraryScreen(saved: List<String>, filter: String, onFilter: (String) -> Unit, onOpen: (Manga) -> Unit) {
    ScreenColumn {
        Spacer(Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Eyebrow("Your collection"); Text("Library", color = White, fontSize = 36.sp, fontWeight = FontWeight.Bold) }; Icon(Icons.Rounded.Download, null, tint = Silver, modifier = Modifier.size(25.dp)) }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Saved", "Reading", "Completed").forEach { label -> FilterPill(label, filter == label) { onFilter(label) } } }
        Spacer(Modifier.height(22.dp))
        val items = when (filter) { "Reading" -> catalog.filter { it.title == "Solo Leveling" || it.title == "The Boxer" }; "Completed" -> catalog.filter { it.title == "Berserk" }; else -> catalog.filter { saved.contains(it.title) } }
        Text("${items.size} TITLES", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) { items.chunked(2).forEach { rowItems -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { rowItems.forEach { manga -> Column(Modifier.weight(1f).clickable { onOpen(manga) }) { PosterArt(manga, Modifier.fillMaxWidth().height(210.dp), true); Spacer(Modifier.height(9.dp)); Text(manga.title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis); Text(manga.chapters, color = Muted, fontSize = 11.sp) } }; if (rowItems.size == 1) Spacer(Modifier.weight(1f)) } } }
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun FilterPill(text: String, active: Boolean, onClick: () -> Unit) { Text(text, Modifier.clip(CircleShape).background(if (active) Color(0xFF303034) else Panel).border(1.dp, if (active) Silver.copy(.5f) else Color.Transparent, CircleShape).clickable { onClick() }.padding(horizontal = 17.dp, vertical = 10.dp), color = if (active) White else Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium) }

@Composable
private fun PosterCard(manga: Manga, modifier: Modifier, onClick: () -> Unit) { Column(modifier.clickable { onClick() }) { PosterArt(manga, Modifier.fillMaxWidth().height(190.dp), true); Spacer(Modifier.height(9.dp)); Text(manga.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis); Text(manga.chapters, color = Muted, fontSize = 10.sp) } }

@Composable
private fun PosterArt(manga: Manga, modifier: Modifier, tall: Boolean) {
    Box(modifier.clip(RoundedCornerShape(17.dp)).background(Brush.linearGradient(listOf(manga.accent, Color(0xFF151517), Ink)))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawCircle(Brush.radialGradient(listOf(manga.accent.copy(.95f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(w * .68f, h * .32f), radius = w * .95f), radius = w * .95f, center = androidx.compose.ui.geometry.Offset(w * .68f, h * .32f))
            when (manga.symbol) {
                "LEVEL" -> {
                    for (i in 0..5) drawLine(Color(0xFFB9D2E7).copy(.22f), androidx.compose.ui.geometry.Offset(w * (.15f + i * .12f), h * .08f), androidx.compose.ui.geometry.Offset(w * (.02f + i * .15f), h * .78f), strokeWidth = 1.2.dp.toPx())
                    drawCircle(Color(0xFFB9D2E7).copy(.24f), w * .23f, androidx.compose.ui.geometry.Offset(w * .52f, h * .38f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
                "BOXER" -> {
                    drawCircle(Color(0xFFD8C6B5).copy(.2f), w * .27f, androidx.compose.ui.geometry.Offset(w * .52f, h * .35f))
                    drawLine(Color.White.copy(.22f), androidx.compose.ui.geometry.Offset(w * .12f, h * .68f), androidx.compose.ui.geometry.Offset(w * .88f, h * .48f), 3.dp.toPx())
                }
                "ORV" -> {
                    for (i in 0..4) drawArc(Color.White.copy(.18f - i * .025f), 190f, 160f, false, androidx.compose.ui.geometry.Offset(w * .08f, h * .13f), androidx.compose.ui.geometry.Size(w * .84f, h * .62f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                }
                else -> {
                    drawCircle(Color.White.copy(.13f), w * .3f, androidx.compose.ui.geometry.Offset(w * .55f, h * .34f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()))
                    drawLine(Color.White.copy(.18f), androidx.compose.ui.geometry.Offset(w * .12f, h * .12f), androidx.compose.ui.geometry.Offset(w * .88f, h * .72f), 1.dp.toPx())
                }
            }
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.88f)), startY = h * .42f, endY = h), size = size)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(if (tall) 12.dp else 8.dp)) {
            Text(manga.symbol, color = White.copy(.94f), fontSize = if (tall) 17.sp else 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text("NOIR  /  SERIES", color = White.copy(.62f), fontSize = 7.sp, letterSpacing = 1.4.sp)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(9.dp).size(6.dp).clip(CircleShape).background(White.copy(.72f)))
    }
}

@Composable
private fun DetailScreen(manga: Manga, isSaved: Boolean, onSave: () -> Unit, onRead: () -> Unit, onBack: () -> Unit) {
    ScreenColumn {
        Spacer(Modifier.height(18.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.ArrowBack, null, tint = White, modifier = Modifier.clickable { onBack() }.size(25.dp)); Spacer(Modifier.width(12.dp)); Eyebrow("SERIES / OVERVIEW") }
        Spacer(Modifier.height(20.dp)); PosterArt(manga, Modifier.fillMaxWidth().height(330.dp), true)
        Spacer(Modifier.height(22.dp)); Eyebrow(manga.genre); Text(manga.title, color = White, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp)); Text(manga.description, color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(22.dp)); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRead, modifier = Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = White, contentColor = Ink), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Rounded.MenuBook, null); Spacer(Modifier.width(8.dp)); Text("START READING", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
            OutlinedButton(onClick = onSave, modifier = Modifier.size(52.dp), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Silver.copy(.35f))) { Icon(if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, null, tint = White) }
        }
        Spacer(Modifier.height(28.dp)); SectionTitle("Chapters", manga.chapters.uppercase()); repeat(8) { index -> Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text("Chapter ${manga.chapters.substringAfter(" ").toIntOrNull()?.minus(index) ?: (index + 1)}", color = White, modifier = Modifier.weight(1f), fontSize = 13.sp); Text("AVAILABLE", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp); Icon(Icons.Rounded.ChevronRight, null, tint = Muted) }; Divider(color = Panel) }
        Spacer(Modifier.height(100.dp))
    }
}

@Composable
private fun ReaderScreen(manga: Manga?, onClose: () -> Unit) {
    var vertical by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Close, null, tint = White, modifier = Modifier.clickable { onClose() }); Column(Modifier.weight(1f).padding(start = 16.dp)) { Text(manga?.title ?: "NOIR Reader", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text("CHAPTER 01  ·  DEMO READER", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp) }; Text(if (vertical) "VERTICAL" else "PAGED", color = Silver, fontSize = 9.sp, modifier = Modifier.clickable { vertical = !vertical }.border(1.dp, Panel, CircleShape).padding(9.dp)) }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Brush.verticalGradient(listOf(manga?.accent ?: Panel, Color(0xFF101012), Ink))), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(manga?.symbol ?: "NOIR", color = White.copy(.75f), fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp); Spacer(Modifier.height(14.dp)); Text("READER PREVIEW", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp); Spacer(Modifier.height(28.dp)); Text("Add a manga source or local CBZ/CBR chapters to begin reading.", color = White.copy(.65f), fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 28.dp)) } }
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Text("‹ PREVIOUS", color = Muted, fontSize = 10.sp); Spacer(Modifier.weight(1f)); Text("1 / 1", color = White, fontSize = 11.sp); Spacer(Modifier.weight(1f)); Text("NEXT ›", color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun DownloadsScreen() { ScreenColumn { Spacer(Modifier.height(32.dp)); Eyebrow("Available offline"); Text("Downloads", color = White, fontSize = 36.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(90.dp)); Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Rounded.Download, null, tint = Muted, modifier = Modifier.size(42.dp)); Spacer(Modifier.height(16.dp)); Text("Nothing downloaded yet", color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold); Text("Your offline chapters will appear here.", color = Muted, fontSize = 12.sp) } }; Spacer(Modifier.height(130.dp)) } }

@Composable
private fun ProfileScreen() { ScreenColumn { Spacer(Modifier.height(32.dp)); Eyebrow("Personal space"); Text("Profile", color = White, fontSize = 36.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(28.dp)); Box(Modifier.size(76.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF55555A), Panel))), contentAlignment = Alignment.Center) { Text("N", color = White, fontSize = 32.sp, fontWeight = FontWeight.Light) }; Spacer(Modifier.height(14.dp)); Text("NOIR READER", color = White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Text("Your reading journey, in one place.", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(30.dp)); listOf("Appearance", "Reader settings", "Manga sources", "About NOIR", "Version 0.1.0").forEach { Row(Modifier.fillMaxWidth().padding(vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) { Text(it, color = White, modifier = Modifier.weight(1f), fontSize = 14.sp); Icon(Icons.Rounded.ChevronRight, null, tint = Muted) }; Divider(color = Panel) }; Spacer(Modifier.height(120.dp)) } }

@Composable
private fun GlassNav(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf("Home" to Icons.Rounded.Home, "Discover" to Icons.Rounded.Explore, "Library" to Icons.Rounded.CollectionsBookmark, "Downloads" to Icons.Rounded.Download, "Profile" to Icons.Rounded.Person)
    Row(modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(36.dp)).background(Brush.verticalGradient(listOf(Color(0xDD29292C), Color(0xEE101012)))).border(1.dp, Brush.linearGradient(listOf(Color.White.copy(.28f), Color.White.copy(.04f), Color(0xFF6A514A).copy(.35f))), RoundedCornerShape(36.dp)).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (label, icon) -> val active = selected == label
            Column(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(if (active) Brush.verticalGradient(listOf(Color(0xFF4A4A4E), Color(0xFF28282B))) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))).clickable { onSelect(label) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(icon, null, tint = if (active) White else Muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.height(4.dp)); Text(label, color = if (active) White else Muted, fontSize = 9.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
