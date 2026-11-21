package com.noir.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class NoirAccentPreset(val id: String, val label: String, val color: Color)
private val noirAccentPresets = listOf(
    NoirAccentPreset("violet", "Violet", Color(0xFFD6C5F2)),
    NoirAccentPreset("ice", "Ice", Color(0xFF9BD8F5)),
    NoirAccentPreset("crimson", "Crimson", Color(0xFFF09AA9)),
    NoirAccentPreset("emerald", "Emerald", Color(0xFF9BD8B5)),
    NoirAccentPreset("amber", "Amber", Color(0xFFF0C58C)),
    NoirAccentPreset("mono", "Silver", Color(0xFFC6C6CC))
)

@Composable
internal fun NoirCustomizationStudio(
    accent: String,
    onAccent: (String) -> Unit,
    backdrop: String,
    onBackdrop: (String) -> Unit,
    fontScale: Float,
    onFontScale: (Float) -> Unit,
    motionEnabled: Boolean,
    onMotionEnabled: (Boolean) -> Unit,
    gestures: String,
    onGestures: (String) -> Unit,
    layout: String,
    onLayout: (String) -> Unit,
    onBack: () -> Unit,
    onTab: (String) -> Unit
) {
    val selectedColor = noirAccentPresets.firstOrNull { it.id == accent }?.color ?: noirAccentPresets.first().color
    Column(Modifier.fillMaxSize().background(Color(0xFF070708)).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null, tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text("NOIR / PERSONALIZATION", color = Color(0xFF99999F), fontSize = 9.sp, letterSpacing = 2.sp)
                Text("Customization Studio", color = Color(0xFFF4F4F5), fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            }
            Icon(Icons.Rounded.Tune, null, tint = selectedColor)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(25.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF29212F), Color(0xFF111014), Color(0xFF17141D))))
                    .border(1.dp, selectedColor.copy(alpha = .35f), RoundedCornerShape(25.dp)).padding(19.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("LIVE APPEARANCE", color = selectedColor, fontSize = 8.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
                        Text("Your NOIR preview", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.size(38.dp).clip(CircleShape).background(selectedColor.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = selectedColor)
                    }
                }
                Spacer(Modifier.height(17.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Color(0xFF09090B)).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(43.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(selectedColor.copy(alpha = .45f), Color(0xFF24202A)))), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.AutoStories, null, tint = Color.White)
                    }
                    Column(Modifier.weight(1f).padding(start = 11.dp)) {
                        Text("NOIR", color = Color.White, fontSize = 14.sp, letterSpacing = 2.2.sp, fontWeight = FontWeight.Bold)
                        Text("A universe between the pages", color = Color(0xFF99999F), fontSize = 9.sp)
                    }
                    Text("PREVIEW", color = selectedColor, fontSize = 7.sp, letterSpacing = 1.sp)
                }
                Spacer(Modifier.height(11.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color(0xFF343039))) {
                    Box(Modifier.fillMaxWidth(.68f).fillMaxHeight().background(Brush.horizontalGradient(listOf(selectedColor.copy(alpha = .5f), selectedColor)), CircleShape))
                }
                Spacer(Modifier.height(8.dp))
                Text("Changes are saved automatically on this device.", color = Color(0xFF99999F), fontSize = 9.sp)
            }

            StudioSection("ACCENT COLOR", "Choose the signature color used by the app theme.") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    noirAccentPresets.forEach { preset ->
                        Column(Modifier.weight(1f).clickable { onAccent(preset.id) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.size(35.dp).clip(CircleShape).background(preset.color)
                                .then(if (accent == preset.id) Modifier.border(2.dp, Color.White, CircleShape) else Modifier),
                                contentAlignment = Alignment.Center) {
                                if (accent == preset.id) Icon(Icons.Rounded.Check, null, tint = Color(0xFF17131D), modifier = Modifier.size(17.dp))
                            }
                            Text(preset.label, color = if (accent == preset.id) Color.White else Color(0xFF99999F), fontSize = 8.sp)
                        }
                    }
                }
            }

            StudioSection("READER CANVAS", "Choose the background behind chapter pages.") {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    listOf("AMOLED", "CHARCOAL", "WARM", "PAPER").forEach { option ->
                        val active = backdrop == option
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(13.dp))
                            .border(1.dp, if (active) selectedColor else Color(0xFF343039), RoundedCornerShape(13.dp))
                            .clickable { onBackdrop(option) }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(when(option) {
                                "AMOLED" -> Color.Black
                                "CHARCOAL" -> Color(0xFF24242A)
                                "WARM" -> Color(0xFF29241F)
                                else -> Color(0xFFE8E3D9)
                            }).border(1.dp, Color(0xFF55515A), RoundedCornerShape(7.dp)))
                            Spacer(Modifier.height(7.dp))
                            Text(option, color = if (active) Color.White else Color(0xFF99999F), fontSize = 7.sp)
                        }
                    }
                }
            }

            StudioSection("TYPOGRAPHY SCALE", "Adjust text sizing for NOIR's reading interface.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("A", color = Color(0xFF99999F), fontSize = 10.sp)
                    Slider(value = fontScale, onValueChange = onFontScale, valueRange = .85f..1.2f, modifier = Modifier.weight(1f))
                    Text("A", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
                Text("${(fontScale * 100).toInt()}% scale", color = selectedColor, fontSize = 10.sp)
            }

            StudioSection("MOTION & TRANSITIONS", "Reduce or enable interface motion effects.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Interface animations", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text("Transitions, subtle movement and animated feedback", color = Color(0xFF99999F), fontSize = 9.sp)
                    }
                    Switch(checked = motionEnabled, onCheckedChange = onMotionEnabled)
                }
            }

            StudioSection("GESTURE PROFILE", "Choose a preset for common reader gestures.") {
                listOf("Standard", "Minimal", "One-handed").forEach { option ->
                    StudioChoice(option, gestures == option, selectedColor) { onGestures(option) }
                }
            }

            StudioSection("LAYOUT PRESET", "Change the density of the discovery and library grids.") {
                listOf("Comfortable", "Compact", "Cinematic").forEach { option ->
                    StudioChoice(option, layout == option, selectedColor) { onLayout(option) }
                }
            }

            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Color(0xFF17151C)).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, null, tint = selectedColor)
                Text("Theme profiles are stored locally. Cloud sharing and community theme imports can be added later.", color = Color(0xFF99999F), fontSize = 9.sp, lineHeight = 14.sp, modifier = Modifier.padding(start = 10.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
        BottomNav("Profile", onTab)
    }
}

@Composable
private fun StudioSection(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(Color(0xFF141318))
            .border(1.dp, Color(0xFF302C35), RoundedCornerShape(19.dp)).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, color = Color(0xFFC6C6CC), fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Color(0xFF99999F), fontSize = 9.sp, lineHeight = 14.sp)
        content()
    }
}

@Composable
private fun StudioChoice(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if (selected) accent.copy(alpha = .13f) else Color(0xFF1B191F))
        .border(1.dp, if (selected) accent.copy(alpha = .65f) else Color(0xFF302C35), RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = accent, modifier = Modifier.size(17.dp))
    }
}
