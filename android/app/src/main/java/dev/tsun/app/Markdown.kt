package dev.tsun.app

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading as headingSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The subset of Markdown agents actually write in chat replies. */
private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class ListItem(val marker: String, val text: String, val depth: Int)
    data class ListBlock(val items: List<ListItem>) : Block
    data class Quote(val text: String) : Block
    data class Code(val language: String, val text: String) : Block
    data class Table(val header: List<String>, val aligns: List<CellAlign>, val rows: List<List<String>>) : Block
    data object Rule : Block
}

private enum class CellAlign { Start, Center, End }

private val orderedItem = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
private val bulletItem = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val headingPattern = Regex("^(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$")
private val setextHeading = Regex("^(=+|-+)\\s*$")
private val quoteLine = Regex("^>[ \\t]?(.*)$")
private val tableDivider = Regex("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?$")

/** True when the line looks like a table row rather than prose that happens to hold a pipe. */
private fun tableRow(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.contains('|') && trimmed.isNotEmpty()
}

/** Splits on unescaped pipes; `\|` stays a literal pipe inside the cell. */
private fun splitRow(line: String): List<String> {
    val body = line.trim().removePrefix("|").removeSuffix("|")
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var i = 0
    while (i < body.length) {
        when {
            body[i] == '\\' && i + 1 < body.length && body[i + 1] == '|' -> { cell.append('|'); i += 2 }
            body[i] == '|' -> { cells += cell.toString().trim(); cell.clear(); i++ }
            else -> { cell.append(body[i]); i++ }
        }
    }
    cells += cell.toString().trim()
    return cells
}

private fun alignment(cell: String): CellAlign = when {
    cell.startsWith(":") && cell.endsWith(":") -> CellAlign.Center
    cell.endsWith(":") -> CellAlign.End
    else -> CellAlign.Start
}

private fun parse(markdown: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = StringBuilder()
    val items = mutableListOf<Block.ListItem>()
    val quote = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += Block.Paragraph(paragraph.toString().trimEnd())
        paragraph.clear()
    }
    fun flushList() {
        if (items.isNotEmpty()) blocks += Block.ListBlock(items.map { it.copy(text = it.text.trimEnd()) })
        items.clear()
    }
    fun flushQuote() {
        if (quote.isNotBlank()) blocks += Block.Quote(quote.toString().trimEnd())
        quote.clear()
    }
    fun flush() {
        flushParagraph()
        flushList()
        flushQuote()
    }

    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        val heading = headingPattern.matchEntire(trimmed)
        val quoted = quoteLine.matchEntire(trimmed)
        val bullet = bulletItem.matchEntire(line)
        val ordered = orderedItem.matchEntire(line)
        // GFM rejects a delimiter row whose cell count differs from the header's. Checking it here also
        // keeps prose that mentions a pipe, followed by a "---" rule, from being read as a one-row table.
        val table = if (tableRow(line) && i + 1 < lines.size && tableDivider.matches(lines[i + 1].trim())) {
            val header = splitRow(line)
            val spans = splitRow(lines[i + 1])
            if (spans.size == header.size) header to spans else null
        } else null
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                flush()
                val fence = trimmed.take(3)
                val language = trimmed.drop(3).trim()
                val start = ++i
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) i++
                blocks += Block.Code(language, lines.subList(start, i).joinToString("\n"))
            }
            trimmed.isEmpty() -> flush()
            heading != null -> {
                flush()
                blocks += Block.Heading(heading.groupValues[1].length, heading.groupValues[2])
            }
            paragraph.isNotEmpty() && setextHeading.matches(trimmed) -> {
                blocks += Block.Heading(if (trimmed.startsWith('=')) 1 else 2, paragraph.toString().trimEnd())
                paragraph.clear()
            }
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> { flush(); blocks += Block.Rule }
            table != null -> {
                flush()
                val (header, spans) = table
                val aligns = List(header.size) { c -> alignment(spans.getOrElse(c) { "" }) }
                val rows = mutableListOf<List<String>>()
                var j = i + 2
                while (j < lines.size && tableRow(lines[j]) && !tableDivider.matches(lines[j].trim())) {
                    val cells = splitRow(lines[j])
                    rows += List(header.size) { c -> cells.getOrElse(c) { "" } }
                    j++
                }
                blocks += Block.Table(header, aligns, rows)
                i = j - 1
            }
            bullet != null -> {
                flushParagraph()
                flushQuote()
                val body = bullet.groupValues[2]
                val marker = when {
                    body.startsWith("[x] ", ignoreCase = true) -> "☑"
                    body.startsWith("[ ] ") -> "☐"
                    else -> "•"
                }
                items += Block.ListItem(marker, proseLine(if (marker == "•") body else body.drop(4)), listDepth(bullet))
            }
            ordered != null -> {
                flushParagraph()
                flushQuote()
                items += Block.ListItem(ordered.groupValues[2] + ".", proseLine(ordered.groupValues[3]), listDepth(ordered))
            }
            quoted != null -> {
                flushParagraph()
                flushList()
                val body = quoted.groupValues[1]
                if (body.isBlank()) {
                    if (quote.isNotEmpty() && quote.last() != '\n') quote.append('\n')
                    if (quote.isNotEmpty()) quote.append('\n')
                } else quote.appendProseLine(body)
            }
            else -> {
                flushQuote()
                val last = items.lastOrNull()
                if (last != null && (line.startsWith("  ") || line.startsWith('\t'))) {
                    val text = StringBuilder(last.text).apply { appendProseLine(line) }.toString()
                    items[items.lastIndex] = last.copy(text = text)
                } else {
                    flushList()
                    paragraph.appendProseLine(line)
                }
            }
        }
        i++
    }
    flush()
    return blocks
}

private fun listDepth(match: MatchResult): Int = match.groupValues[1].sumOf { if (it == '\t') 4L else 1L }
    .div(2).coerceAtMost(3).toInt()

private fun proseLine(line: String): String = buildString { appendProseLine(line) }

/** Soft Markdown breaks reflow on the phone; two spaces or a backslash request a hard break. */
private fun StringBuilder.appendProseLine(line: String) {
    val body = line.trim()
    if (isNotEmpty() && last() != '\n') append(' ')
    val escapedBreak = body.endsWith('\\') && body.takeLastWhile { it == '\\' }.length % 2 == 1
    append(if (escapedBreak) body.dropLast(1) else body)
    if (escapedBreak || line.endsWith("  ")) append('\n')
}

/** Inline Markdown rendered as Compose text, not HTML. */
private fun inline(text: String, code: Color, codeBackground: Color, link: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = CodeType.fontFamily, fontSize = 0.88.em, color = code,
                        background = codeBackground)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(c); i++ }
            }
            text.startsWith("**", i) || text.startsWith("__", i) -> {
                val marker = text.substring(i, i + 2)
                val end = text.indexOf(marker, i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(text.substring(i + 2, end), code, codeBackground, link)) }
                    i = end + 2
                } else { append(c); i++ }
            }
            (c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() &&
                (i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                val end = text.indexOf(c, i + 1)
                if (end > i + 1 && !text[end - 1].isWhitespace()) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(c); i++ }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(c); i++ }
            }
            c == '[' -> {
                val close = text.indexOf("](", i + 1)
                val end = if (close > i) text.indexOf(')', close) else -1
                if (close > i + 1 && end > close) {
                    val label = text.substring(i + 1, close)
                    val url = text.substring(close + 2, end)
                    val style = SpanStyle(color = link, textDecoration = TextDecoration.Underline)
                    if (isWebLink(url)) {
                        withLink(LinkAnnotation.Url(url, TextLinkStyles(style = style))) { append(label) }
                    } else withStyle(style) { append(label) }
                    i = end + 1
                } else { append(c); i++ }
            }
            // Bare URLs matter as much as [label](url) links: agents paste them as plain text, and
            // an unannotated URL is neither tappable nor worth retyping on a phone.
            else -> {
                val bare = bareUrl(text, i)
                if (bare != null) {
                    val style = SpanStyle(color = link, textDecoration = TextDecoration.Underline)
                    withLink(LinkAnnotation.Url(webUrl(bare), TextLinkStyles(style = style))) { append(bare) }
                    i += bare.length
                } else { append(c); i++ }
            }
        }
    }
}

private fun isWebLink(url: String) =
    (url.startsWith("https://") || url.startsWith("http://")) && url.none { it.isWhitespace() || it.isISOControl() }

/** `www.example.com` is common in prose; give it the scheme Android needs to open it. */
private fun webUrl(url: String) = if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url

/**
 * The URL starting at [start], or null. Trailing punctuation belongs to the sentence rather than the
 * link, and a closing bracket only counts when it balances an opening one inside the URL.
 */
private fun bareUrl(text: String, start: Int): String? {
    val first = text[start].lowercaseChar()
    if (first != 'h' && first != 'w') return null
    if (start > 0) {
        val previous = text[start - 1]
        if (previous.isLetterOrDigit() || previous in "./\\@-_") return null
    }
    if (!text.startsWith("http://", start, true) && !text.startsWith("https://", start, true) &&
        !text.startsWith("www.", start, true)) return null
    var end = start
    while (end < text.length && !text[end].isWhitespace() && text[end] !in "<>\"`|") end++
    while (end > start && text[end - 1] in ".,;:!?*_~'\u2019\u201D") end--
    while (end > start && text[end - 1] == ')') {
        val body = text.substring(start, end)
        if (body.count { it == '(' } < body.count { it == ')' }) end-- else break
    }
    val raw = text.substring(start, end)
    val host = raw.substringAfter("://").substringBefore('/')
    return if (end > start && host.contains('.')) raw else null
}

@Composable
internal fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val palette = inks
    val blocks by produceState<List<DisplayBlock>>(emptyList(), markdown, palette) {
        value = withContext(Dispatchers.Default) {
            fun style(text: String) = inline(text, palette.ink, palette.wash, palette.accent)
            parse(markdown).map { block ->
                val text = when (block) {
                    is Block.Paragraph -> listOf(style(block.text))
                    is Block.Heading -> listOf(style(block.text))
                    is Block.ListBlock -> block.items.map { style(it.text) }
                    is Block.Quote -> listOf(style(block.text))
                    is Block.Table -> (listOf(block.header) + block.rows).flatMap { row -> row.map { style(it) } }
                    is Block.Code, Block.Rule -> emptyList()
                }
                DisplayBlock(block, text)
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for ((index, display) in blocks.withIndex()) {
            val (block, styled) = display
            when (block) {
                is Block.Paragraph -> Text(styled.single(), style = ProseType, color = palette.ink)
                is Block.Heading -> Text(styled.single(), Modifier.padding(top = if (index == 0) 0.dp else 8.dp)
                    .semantics { headingSemantics() },
                    style = ProseType.copy(
                        fontSize = if (block.level <= 2) 21.sp else 18.sp,
                        lineHeight = if (block.level <= 2) 29.sp else 25.sp,
                        fontWeight = FontWeight.SemiBold, hyphens = Hyphens.None,
                    ), color = palette.ink)
                is Block.ListBlock -> MarkdownList(block, styled)
                is Block.Quote -> QuoteBlock(styled.single())
                is Block.Code -> CodeBlock(block.text, block.language)
                is Block.Table -> TableBlock(block, styled)
                Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(palette.rule))
            }
        }
    }
}

private data class DisplayBlock(val block: Block, val styled: List<AnnotatedString>)

@Composable
private fun MarkdownList(list: Block.ListBlock, styled: List<AnnotatedString>) {
    val fontScale = LocalDensity.current.fontScale
    val indent = if (fontScale >= 1.3f) 8.dp else 12.dp
    val markerStyle = ProseType.copy(fontWeight = FontWeight.SemiBold, hyphens = Hyphens.None)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        list.items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth().padding(start = indent * item.depth),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.marker, Modifier.alignByBaseline(), style = markerStyle, color = inks.accent, softWrap = false)
                Text(styled[index], Modifier.weight(1f).alignByBaseline(), style = ProseType, color = inks.ink)
            }
        }
    }
}

@Composable
private fun QuoteBlock(text: AnnotatedString) {
    val palette = inks
    val direction = LocalLayoutDirection.current
    Text(text, Modifier.fillMaxWidth().drawBehind {
        val width = 2.dp.toPx()
        val origin = if (direction == LayoutDirection.Ltr) Offset.Zero else Offset(size.width - width, 0f)
        drawRect(palette.accent.copy(alpha = 0.45f), topLeft = origin, size = Size(width, size.height))
    }.padding(start = 12.dp, top = 2.dp, bottom = 2.dp), style = ProseType, color = palette.quiet)
}

@Composable
private fun TableBlock(table: Block.Table, styled: List<AnnotatedString>) {
    val palette = inks
    val columns = table.header.size
    val preferredWidths = remember(table) { preferredColumnWidths(table) }
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val shape = RoundedCornerShape(10.dp)
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Keep enough room for words without making any one cell wider than the phone.
        val minimum = minOf(TableColumnMinimum.dp * scale, maxWidth)
        val preferred = preferredWidths.map { minOf(it.dp * scale, maxWidth).value }
        val total = preferred.sum().dp
        val needsScroll = maxWidth < minimum * columns
        val tableWidth = if (needsScroll) total else minOf(maxWidth, total)
        val extra = (tableWidth - minimum * columns).value.coerceAtLeast(0f)
        val demand = preferred.map { it - minimum.value }
        val totalDemand = demand.sum()
        val widths = demand.map { (minimum.value + if (totalDemand > 0f) extra * it / totalDemand else 0f)
            .coerceAtLeast(1f) }
        Column {
            val scrollModifier = if (needsScroll) Modifier.horizontalScroll(scroll) else Modifier
            Column(Modifier.widthIn(max = tableWidth).fillMaxWidth().clip(shape).border(1.dp, palette.rule, shape)
                .then(scrollModifier).width(tableWidth)) {
                for (row in 0..table.rows.size) {
                    if (row > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.rule))
                    TableRow(styled.subList(row * columns, (row + 1) * columns), table.aligns, widths, palette, row)
                }
            }
            if (needsScroll) {
                Text("Scroll sideways for more columns", Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = palette.quiet)
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<AnnotatedString>, aligns: List<CellAlign>, widths: List<Float>, palette: Inks, row: Int,
) {
    val header = row == 0
    val background = when {
        header -> palette.wash
        row % 2 == 0 -> palette.sheet
        else -> Color.Transparent
    }
    Row(Modifier.fillMaxWidth().background(background)) {
        cells.forEachIndexed { column, text ->
            Text(text, Modifier.weight(widths[column]).padding(horizontal = 10.dp, vertical = 9.dp),
                style = ProseType.copy(
                    fontSize = 15.sp, lineHeight = 22.sp,
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                ),
                textAlign = when (aligns[column]) {
                    CellAlign.Start -> TextAlign.Start
                    CellAlign.Center -> TextAlign.Center
                    CellAlign.End -> TextAlign.End
                }, color = palette.ink)
        }
    }
}

private const val TableColumnMinimum = 112f

/** Give descriptive columns more space without letting one huge cell dominate the table. */
private fun preferredColumnWidths(table: Block.Table): List<Float> = table.header.mapIndexed { column, title ->
    val length = maxOf(title.length, table.rows.maxOfOrNull { it[column].length } ?: 0)
    (length * 5f + 52f).coerceIn(TableColumnMinimum, 192f)
}

@Composable
private fun CodeText(code: String, wrap: Boolean, scroll: ScrollState) {
    val body = if (wrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(scroll)
    Text(code, body.padding(12.dp), style = CodeType, color = inks.ink, softWrap = wrap)
}

/** Wrapping only changes layout; selection and copy still see the original text. */
@Composable
internal fun CodeBlock(
    code: String, language: String = "", modifier: Modifier = Modifier, label: String = language.ifBlank { "Code" },
    selectable: Boolean = false, footer: @Composable () -> Unit = {},
) {
    var wrap by rememberSaveable { mutableStateOf(true) }
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(inks.sheet).border(1.dp, inks.rule, shape)) {
        Row(Modifier.fillMaxWidth().background(inks.wash).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f).padding(start = 4.dp),
                style = MaterialTheme.typography.labelMedium, color = inks.quiet)
            TextButton(onClick = { wrap = !wrap }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (wrap) "Unwrap" else "Wrap", color = inks.accent)
            }
        }
        if (selectable) {
            SelectionContainer { CodeText(code, wrap, scroll) }
        } else CodeText(code, wrap, scroll)
        footer()
    }
}
