package dev.tsun.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tsun's paper and ink palettes, with pine for actions and separate status colors. */
internal data class Inks(
    val paper: Color,      // deepest background
    val sheet: Color,      // drawer and cards
    val raised: Color,     // composer, user turns
    val wash: Color,       // chips, pressed rows
    val rule: Color,       // hairlines
    val ink: Color,        // primary text
    val quiet: Color,      // secondary text
    val faint: Color,      // tertiary text, idle dots
    val accent: Color,     // pine: primary actions and focus
    val onAccent: Color,
    val accentWash: Color, // selected rows, approval tint
    val amber: Color,      // live work
    val sage: Color,       // success, additions
    val danger: Color,     // errors, deletions
)

private val Day = Inks(
    paper = Color(0xFFF7F4ED), sheet = Color(0xFFFFFCF6), raised = Color(0xFFFBF8F1), wash = Color(0xFFEDE9DF),
    rule = Color(0xFFD9D5CA), ink = Color(0xFF222A26), quiet = Color(0xFF62665D), faint = Color(0xFF64685E),
    accent = Color(0xFF29594B), onAccent = Color(0xFFFFFCF6), accentWash = Color(0xFFE5EBDF),
    amber = Color(0xFF78551F), sage = Color(0xFF416C52), danger = Color(0xFF994832),
)
private val Night = Inks(
    paper = Color(0xFF202622), sheet = Color(0xFF282F29), raised = Color(0xFF2C342D), wash = Color(0xFF303831),
    rule = Color(0xFF444F45), ink = Color(0xFFEDEAE0), quiet = Color(0xFFB4BCB0), faint = Color(0xFFA9B3A7),
    accent = Color(0xFFB7D2BE), onAccent = Color(0xFF1F372B), accentWash = Color(0xFF33483A),
    amber = Color(0xFFCEB07A), sage = Color(0xFFA6C5A8), danger = Color(0xFFF1AD97),
)
private val LocalInks = staticCompositionLocalOf { Day }
internal val inks: Inks @Composable get() = LocalInks.current

/**
 * Newsreader ships as a single variable file (wght 200–800, opsz 6–72). Registering it once would
 * let Compose resolve every bold request back to that one 400 face, so bold text and headings would
 * render flat: the plain `Font(resId, …)` overload passes empty variation settings, so the `wght`
 * axis is never touched. Registering the same file at each weight we use makes Android instantiate
 * the axis per weight, and the entries stay distinct because [Font] equality includes them.
 */
@OptIn(ExperimentalTextApi::class)
private fun reading(weight: Int) = Font(
    R.font.newsreader, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)
private val Reading = FontFamily(reading(400), reading(500), reading(600), reading(700))
private val Interface = FontFamily(
    Font(R.font.source_sans_3_regular), Font(R.font.source_sans_3_semibold, FontWeight.SemiBold),
)
private val Mono = FontFamily(Font(R.font.ibm_plex_mono))
private val Type = Typography(
    headlineLarge = TextStyle(fontFamily = Reading, fontSize = 36.sp, lineHeight = 41.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = Reading, fontSize = 29.sp, lineHeight = 34.sp, letterSpacing = (-0.45).sp),
    headlineSmall = TextStyle(fontFamily = Reading, fontSize = 23.sp, lineHeight = 28.sp, letterSpacing = (-0.25).sp),
    titleLarge = TextStyle(fontFamily = Interface, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontFamily = Interface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Interface, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Interface, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Interface, fontSize = 14.5.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Interface, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Interface, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Interface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = Interface, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 1.2.sp),
)
/** Assistant prose: a reading serif, like a reply rather than a log. */
internal val ProseType = TextStyle(fontFamily = Reading, fontSize = 17.sp, lineHeight = 26.sp, lineBreak = LineBreak.Paragraph)
internal val CodeType = TextStyle(fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 19.sp)
internal val SmallCode = TextStyle(fontFamily = Mono, fontSize = 11.5.sp, lineHeight = 17.sp)
internal val Corner = RoundedCornerShape(14.dp)
internal val Pill = RoundedCornerShape(50)

@Composable
internal fun TsunTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) Night else Day
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val colors = base.copy(
        primary = palette.accent, onPrimary = palette.onAccent,
        primaryContainer = palette.accentWash, onPrimaryContainer = palette.ink,
        secondary = palette.accent, onSecondary = palette.onAccent,
        secondaryContainer = palette.accentWash, onSecondaryContainer = palette.ink,
        tertiary = palette.accent, onTertiary = palette.onAccent,
        tertiaryContainer = palette.accentWash, onTertiaryContainer = palette.ink,
        background = palette.paper, onBackground = palette.ink,
        surface = palette.sheet, onSurface = palette.ink,
        surfaceVariant = palette.wash, onSurfaceVariant = palette.quiet,
        outline = palette.faint, outlineVariant = palette.rule,
        surfaceContainerLowest = palette.paper, surfaceContainerLow = palette.sheet,
        surfaceContainer = palette.sheet, surfaceContainerHigh = palette.raised, surfaceContainerHighest = palette.wash,
        surfaceTint = Color.Transparent,
        error = palette.danger, onError = if (dark) palette.paper else palette.sheet,
        errorContainer = lerp(palette.sheet, palette.danger, 0.10f), onErrorContainer = palette.danger,
        inverseSurface = palette.ink, inverseOnSurface = palette.paper,
        inversePrimary = if (dark) Day.accent else Night.accent, scrim = Color.Black,
    )
    CompositionLocalProvider(LocalInks provides palette) {
        MaterialTheme(colorScheme = colors, typography = Type, content = content)
    }
}

@Composable
internal fun PageWidth(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 760.dp).fillMaxWidth(), content = content)
    }
}

@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = inks.faint) {
    Text(text, modifier, color = color, style = MaterialTheme.typography.labelSmall)
}

@Composable
internal fun Notice(message: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(if (error) inks.danger.copy(alpha = 0.10f) else inks.wash)
        .padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = if (error) inks.danger else inks.quiet)
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
internal fun StatusDot(color: Color, modifier: Modifier = Modifier, pulsing: Boolean = false, size: Int = 7) {
    val pulse = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val opacity = transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "alpha")
        Modifier.graphicsLayer { alpha = opacity.value }
    } else Modifier
    Box(modifier.size(size.dp).then(pulse).background(color, CircleShape))
}

/** Small rounded chip used for project, branch, model, and mode. */
@Composable
internal fun Chip(
    label: String, modifier: Modifier = Modifier, glyph: Glyph? = null, mono: Boolean = false,
    tint: Color = inks.quiet, enabled: Boolean = true, onClick: (() -> Unit)? = null, trailing: Glyph? = null,
) {
    val base = modifier.clip(Pill).background(inks.wash)
    Row((if (onClick != null) base.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else base)
        .heightIn(min = 32.dp).padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (glyph != null) LineIcon(glyph, color = tint, size = 14)
        Text(label, style = if (mono) SmallCode else MaterialTheme.typography.labelMedium, color = tint,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        if (trailing != null) LineIcon(trailing, color = inks.faint, size = 12)
    }
}

/** "+12 −3" in sage and rust. */
@Composable
internal fun DiffStat(additions: Int, deletions: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("+$additions", style = SmallCode, color = inks.sage)
        Text("−$deletions", style = SmallCode, color = inks.danger)
    }
}

/** Whole files that appeared without the agent writing them, e.g. copied in with a command. */
@Composable
internal fun CopiedNote(files: Int, modifier: Modifier = Modifier) {
    Text("$files copied", modifier, style = SmallCode, color = inks.faint, maxLines = 1)
}

@Composable
internal fun RunState(run: Run, modifier: Modifier = Modifier) {
    val label = when (run.state) {
        "preparing" -> "Preparing"
        "running" -> "Running"
        "stopping" -> "Stopping"
        "succeeded" -> "Finished"
        "failed" -> "Failed"
        "stopped" -> "Stopped"
        else -> "Unknown"
    }
    val color = when (run.state) {
        "preparing", "running", "stopping" -> inks.amber
        "succeeded" -> inks.sage
        "failed" -> inks.danger
        else -> inks.faint
    }
    Row(modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        StatusDot(color, pulsing = run.active)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

internal enum class Glyph {
    Menu, Runs, Folder, File, Image, Next, Down, Back, Up, Stop, Export, Plus, Refresh,
    Terminal, Pencil, Eye, Search, Branch, Diff, Check, Cross, Shield, Chevron, More,
}

@Composable
internal fun GlyphButton(glyph: Glyph, label: String, enabled: Boolean = true, tint: Color = inks.ink, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        LineIcon(glyph, color = if (enabled) tint else inks.faint)
    }
}

@Composable
internal fun TsunMark(modifier: Modifier = Modifier, color: Color = inks.accent) {
    // Two folds meeting at a cut. Deliberately asymmetric so it reads at icon size.
    Canvas(modifier) {
        scale(size.width / 32f, size.height / 32f, Offset.Zero) {
            drawPath(Path().apply { moveTo(3f, 6f); lineTo(28f, 6f); lineTo(19f, 15f); lineTo(3f, 15f); close() }, color)
            drawPath(Path().apply { moveTo(18f, 17f); lineTo(28f, 17f); lineTo(18f, 27f); lineTo(8f, 27f); close() }, color)
        }
    }
}

/** A round send/stop button for the composer. */
@Composable
internal fun RoundAction(glyph: Glyph, label: String, enabled: Boolean, filled: Boolean, onClick: () -> Unit) {
    val background = when {
        !enabled -> inks.wash
        filled -> inks.accent
        else -> inks.raised
    }
    Box(Modifier.size(38.dp).clip(CircleShape).background(background)
        .then(if (!filled && enabled) Modifier.border(1.dp, inks.rule, CircleShape) else Modifier)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        LineIcon(glyph, color = if (!enabled) inks.faint else if (filled) inks.onAccent else inks.ink, size = 18)
    }
}

@Composable
internal fun LineIcon(glyph: Glyph, modifier: Modifier = Modifier, color: Color = inks.quiet, size: Int = 20) {
    Canvas(modifier.size(size.dp)) {
        scale(this.size.width / 24f, this.size.height / 24f, Offset.Zero) {
            val stroke = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(vararg coordinates: Float) {
                val path = Path().apply {
                    moveTo(coordinates[0], coordinates[1])
                    for (i in 2 until coordinates.size step 2) lineTo(coordinates[i], coordinates[i + 1])
                }
                drawPath(path, color, style = stroke)
            }
            when (glyph) {
                Glyph.More -> for (x in listOf(5f, 12f, 19f)) drawCircle(color, 1.6f, Offset(x, 12f))
                Glyph.Menu -> { line(4f, 7f, 20f, 7f); line(4f, 12f, 14f, 12f); line(4f, 17f, 20f, 17f) }
                Glyph.Runs, Glyph.Terminal -> {
                    drawRoundRect(color, Offset(3f, 4.5f), Size(18f, 15f), CornerRadius(2.5f), style = stroke)
                    line(7f, 9.5f, 10f, 12f, 7f, 14.5f); line(12f, 15f, 16f, 15f)
                }
                Glyph.Folder -> line(3f, 19f, 3f, 6f, 10f, 6f, 12f, 8f, 21f, 8f, 21f, 19f, 3f, 19f)
                Glyph.File -> { line(6f, 3f, 14f, 3f, 19f, 8f, 19f, 21f, 6f, 21f, 6f, 3f); line(14f, 3f, 14f, 8f, 19f, 8f) }
                Glyph.Image -> {
                    drawRoundRect(color, Offset(3f, 4f), Size(18f, 16f), CornerRadius(2f), style = stroke)
                    line(4f, 17f, 9f, 12f, 12f, 15f, 16f, 11f, 20f, 15f)
                    drawCircle(color, 1.3f, Offset(8f, 8.5f))
                }
                Glyph.Next, Glyph.Chevron -> line(10f, 6f, 16f, 12f, 10f, 18f)
                Glyph.Down -> line(6f, 9f, 12f, 15f, 18f, 9f)
                Glyph.Back -> { line(13f, 6f, 7f, 12f, 13f, 18f); line(7f, 12f, 20f, 12f) }
                Glyph.Up -> { line(6f, 11f, 12f, 5f, 18f, 11f); line(12f, 5f, 12f, 19f) }
                Glyph.Stop -> drawRoundRect(color, Offset(7f, 7f), Size(10f, 10f), CornerRadius(2f))
                Glyph.Export -> { line(12f, 3f, 12f, 15f); line(7f, 11f, 12f, 16f, 17f, 11f); line(4f, 17f, 4f, 21f, 20f, 21f, 20f, 17f) }
                Glyph.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
                Glyph.Refresh -> {
                    drawArc(color, -45f, -285f, false, Offset(4.5f, 4.5f), Size(15f, 15f), style = stroke)
                    line(19f, 4f, 19f, 9f, 14f, 9f)
                }
                Glyph.Pencil -> { line(4f, 20f, 5f, 15f, 16f, 4f, 20f, 8f, 9f, 19f, 4f, 20f); line(13.5f, 6.5f, 17.5f, 10.5f) }
                Glyph.Eye -> {
                    line(2.5f, 12f, 6f, 7.5f, 12f, 5.5f, 18f, 7.5f, 21.5f, 12f, 18f, 16.5f, 12f, 18.5f, 6f, 16.5f, 2.5f, 12f)
                    drawCircle(color, 2.6f, Offset(12f, 12f), style = stroke)
                }
                Glyph.Search -> { drawCircle(color, 6f, Offset(10.5f, 10.5f), style = stroke); line(15f, 15f, 20f, 20f) }
                Glyph.Branch -> {
                    drawCircle(color, 2.2f, Offset(7f, 5.5f), style = stroke)
                    drawCircle(color, 2.2f, Offset(7f, 18.5f), style = stroke)
                    drawCircle(color, 2.2f, Offset(17f, 7.5f), style = stroke)
                    line(7f, 7.7f, 7f, 16.3f); line(17f, 9.7f, 17f, 11f, 13f, 14f, 7f, 15f)
                }
                Glyph.Diff -> { line(8f, 4f, 8f, 12f); line(4f, 8f, 12f, 8f); line(12f, 17f, 20f, 17f); line(14f, 4f, 9f, 20f) }
                Glyph.Check -> line(5f, 12.5f, 10f, 17f, 19f, 7f)
                Glyph.Cross -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
                Glyph.Shield -> line(12f, 3f, 19.5f, 6f, 19f, 12f, 16f, 17.5f, 12f, 21f, 8f, 17.5f, 5f, 12f, 4.5f, 6f, 12f, 3f)
            }
        }
    }
}
