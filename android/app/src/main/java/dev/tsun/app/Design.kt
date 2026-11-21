package dev.tsun.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class Inks(
    val paper: Color, val sheet: Color, val wash: Color, val ink: Color,
    val quiet: Color, val rule: Color, val pine: Color, val onPine: Color,
    val moss: Color, val rust: Color,
)

private val Day = Inks(
    paper = Color(0xFFF7F4ED), sheet = Color(0xFFFFFCF6), wash = Color(0xFFEDE9DF),
    ink = Color(0xFF222A26), quiet = Color(0xFF62665D), rule = Color(0xFFD9D5CA),
    pine = Color(0xFF29594B), onPine = Color(0xFFFFFCF6), moss = Color(0xFFE5EBDF), rust = Color(0xFF994832),
)
private val Night = Inks(
    paper = Color(0xFF202622), sheet = Color(0xFF282F29), wash = Color(0xFF303831),
    ink = Color(0xFFEDEAE0), quiet = Color(0xFFB4BCB0), rule = Color(0xFF444F45),
    pine = Color(0xFFB7D2BE), onPine = Color(0xFF1F372B), moss = Color(0xFF33483A), rust = Color(0xFFF1AD97),
)
private val LocalInks = staticCompositionLocalOf { Day }
internal val inks: Inks @Composable get() = LocalInks.current

private val Reading = FontFamily(Font(R.font.newsreader))
private val Interface = FontFamily(
    Font(R.font.source_sans_3_regular), Font(R.font.source_sans_3_semibold, FontWeight.SemiBold),
)
private val Mono = FontFamily(Font(R.font.ibm_plex_mono))
private val Type = Typography(
    headlineLarge = TextStyle(fontFamily = Reading, fontSize = 43.sp, lineHeight = 47.sp, letterSpacing = (-1.0).sp),
    headlineMedium = TextStyle(fontFamily = Reading, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.55).sp),
    headlineSmall = TextStyle(fontFamily = Reading, fontSize = 27.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Interface, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontFamily = Interface, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontFamily = Interface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Interface, fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Interface, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Interface, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Interface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Interface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = Interface, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 1.45.sp),
)
internal val CodeType = TextStyle(fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 19.5.sp)
internal val Corner = RoundedCornerShape(15.dp)

@Composable
internal fun TsunTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) Night else Day
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val colors = base.copy(
        primary = palette.pine, onPrimary = palette.onPine,
        primaryContainer = palette.moss, onPrimaryContainer = palette.ink,
        secondary = palette.pine, onSecondary = palette.onPine,
        secondaryContainer = palette.moss, onSecondaryContainer = palette.ink,
        tertiary = palette.pine, onTertiary = palette.onPine,
        tertiaryContainer = palette.moss, onTertiaryContainer = palette.ink,
        background = palette.paper, onBackground = palette.ink,
        surface = palette.sheet, onSurface = palette.ink,
        surfaceVariant = palette.wash, onSurfaceVariant = palette.quiet,
        outline = palette.quiet, outlineVariant = palette.rule,
        surfaceContainerLowest = palette.paper, surfaceContainerLow = palette.paper,
        surfaceContainer = palette.sheet, surfaceContainerHigh = palette.wash, surfaceContainerHighest = palette.wash,
        surfaceTint = palette.pine, error = palette.rust,
    )
    CompositionLocalProvider(LocalInks provides palette) {
        MaterialTheme(colorScheme = colors, typography = Type, content = content)
    }
}

@Composable
internal fun PageWidth(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 720.dp).fillMaxWidth(), content = content)
    }
}

@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = inks.quiet) {
    Text(text, modifier, color = color, style = MaterialTheme.typography.labelSmall)
}

@Composable
internal fun Notice(message: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(inks.wash)) {
        Text(message, Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall, color = if (error) inks.rust else inks.quiet)
    }
}

@Composable
internal fun EmptyNote(title: String, message: String, modifier: Modifier = Modifier, action: String? = null,
                       onAction: () -> Unit = {}) {
    Column(modifier.padding(vertical = 15.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(message, Modifier.padding(top = 4.dp), color = inks.quiet)
        if (action != null) Button(onClick = onAction, modifier = Modifier.padding(top = 10.dp)) { Text(action) }
    }
}

@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).background(color, CircleShape))
}

@Composable
internal fun RunState(run: Run, modifier: Modifier = Modifier) {
    val label = when (run.state) {
        "running" -> "Running"
        "stopping" -> "Stopping"
        "succeeded" -> "Finished"
        "failed" -> "Failed"
        "stopped" -> "Stopped"
        else -> "Unknown"
    }
    val color = when (run.state) {
        "running", "succeeded" -> inks.pine
        "failed" -> inks.rust
        else -> inks.quiet
    }
    Row(modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color)
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

internal enum class Glyph { Menu, Runs, Folder, File, Image, Next, Down, Back, Up, Stop, Export, Plus, Refresh }

@Composable
internal fun GlyphButton(glyph: Glyph, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        LineIcon(glyph, color = if (enabled) inks.ink else inks.quiet)
    }
}

@Composable
internal fun TsunMark(modifier: Modifier = Modifier, color: Color = inks.pine) {
    // Two folds meeting at a cut. Deliberately asymmetric so it reads at icon size.
    Canvas(modifier) {
        scale(size.width / 32f, size.height / 32f, Offset.Zero) {
            drawPath(Path().apply { moveTo(3f, 6f); lineTo(28f, 6f); lineTo(19f, 15f); lineTo(3f, 15f); close() }, color)
            drawPath(Path().apply { moveTo(18f, 17f); lineTo(28f, 17f); lineTo(18f, 27f); lineTo(8f, 27f); close() }, color)
        }
    }
}

@Composable
internal fun LineIcon(glyph: Glyph, modifier: Modifier = Modifier, color: Color = inks.quiet, size: Int = 20) {
    Canvas(modifier.size(size.dp)) {
        scale(this.size.width / 24f, this.size.height / 24f, Offset.Zero) {
            val stroke = Stroke(width = 1.55f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(vararg coordinates: Float) {
                val path = Path().apply {
                    moveTo(coordinates[0], coordinates[1])
                    for (i in 2 until coordinates.size step 2) lineTo(coordinates[i], coordinates[i + 1])
                }
                drawPath(path, color, style = stroke)
            }
            when (glyph) {
                Glyph.Menu -> { line(4f, 6f, 20f, 6f); line(4f, 12f, 15f, 12f); line(4f, 18f, 20f, 18f) }
                Glyph.Runs -> { line(5f, 5f, 12f, 12f, 5f, 19f); line(13f, 19f, 20f, 19f) }
                Glyph.Folder -> line(3f, 19f, 3f, 6f, 10f, 6f, 12f, 8f, 21f, 8f, 21f, 19f, 3f, 19f)
                Glyph.File -> { line(6f, 3f, 14f, 3f, 19f, 8f, 19f, 21f, 6f, 21f, 6f, 3f); line(14f, 3f, 14f, 8f, 19f, 8f) }
                Glyph.Image -> {
                    drawRoundRect(color, Offset(3f, 4f), Size(18f, 16f), CornerRadius(2f), style = stroke)
                    line(4f, 17f, 9f, 12f, 12f, 15f, 16f, 11f, 20f, 15f)
                    drawCircle(color, 1.3f, Offset(8f, 8.5f))
                }
                Glyph.Next -> line(10f, 6f, 16f, 12f, 10f, 18f)
                Glyph.Down -> line(6f, 9f, 12f, 15f, 18f, 9f)
                Glyph.Back -> { line(13f, 6f, 7f, 12f, 13f, 18f); line(7f, 12f, 20f, 12f) }
                Glyph.Up -> { line(6f, 11f, 12f, 5f, 18f, 11f); line(12f, 5f, 12f, 19f) }
                Glyph.Stop -> drawRoundRect(color, Offset(6f, 6f), Size(12f, 12f), CornerRadius(1.5f))
                Glyph.Export -> { line(12f, 3f, 12f, 15f); line(7f, 11f, 12f, 16f, 17f, 11f); line(4f, 17f, 4f, 21f, 20f, 21f, 20f, 17f) }
                Glyph.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
                Glyph.Refresh -> {
                    drawArc(color, -45f, -285f, false, Offset(4.5f, 4.5f), Size(15f, 15f), style = stroke)
                    line(19f, 4f, 19f, 9f, 14f, 9f)
                }
            }
        }
    }
}
