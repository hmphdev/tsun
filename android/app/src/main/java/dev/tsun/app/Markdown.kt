package dev.tsun.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The subset of Markdown agents actually write in chat replies. */
private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Bullet(val marker: String, val text: String, val depth: Int) : Block
    data class Quote(val text: String) : Block
    data class Code(val language: String, val text: String) : Block
    data class Table(val header: List<String>, val aligns: List<CellAlign>, val rows: List<List<String>>) : Block
    data object Rule : Block
}

private enum class CellAlign { Start, Center, End }

private val orderedItem = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
private val bulletItem = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val heading = Regex("^(#{1,4})\\s+(.*)$")
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
    fun flush() {
        if (paragraph.isNotBlank()) blocks += Block.Paragraph(paragraph.toString().trimEnd())
        paragraph.clear()
    }
    val lines = markdown.replace("\r", "").split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
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
            heading.matches(trimmed) -> {
                flush()
                val match = heading.find(trimmed)!!
                blocks += Block.Heading(match.groupValues[1].length, match.groupValues[2])
            }
            trimmed == "---" || trimmed == "***" -> { flush(); blocks += Block.Rule }
            tableRow(line) && i + 1 < lines.size && tableDivider.matches(lines[i + 1].trim()) -> {
                flush()
                val header = splitRow(line)
                val spans = splitRow(lines[i + 1])
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
            bulletItem.matches(line) -> {
                flush()
                val match = bulletItem.find(line)!!
                val body = match.groupValues[2]
                val marker = when {
                    body.startsWith("[x] ", ignoreCase = true) -> "☑"
                    body.startsWith("[ ] ") -> "☐"
                    else -> "•"
                }
                blocks += Block.Bullet(marker, if (marker == "•") body else body.drop(4), match.groupValues[1].length / 2)
            }
            orderedItem.matches(line) -> {
                flush()
                val match = orderedItem.find(line)!!
                blocks += Block.Bullet(match.groupValues[2] + ".", match.groupValues[3], match.groupValues[1].length / 2)
            }
            trimmed.startsWith(">") -> { flush(); blocks += Block.Quote(trimmed.removePrefix(">").trim()) }
            else -> {
                // A continuation line of a list item stays with that item.
                val last = blocks.lastOrNull()
                if (paragraph.isEmpty() && last is Block.Bullet && line.startsWith("  ")) {
                    blocks[blocks.lastIndex] = last.copy(text = last.text + " " + trimmed)
                } else {
                    if (paragraph.isNotEmpty() && paragraph.last() != '\n') paragraph.append(' ')
                    paragraph.append(trimmed.trimEnd())
                    if (line.endsWith("  ")) paragraph.append('\n')
                }
            }
        }
        i++
    }
    flush()
    return blocks
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
                    if ((url.startsWith("https://") || url.startsWith("http://")) && url.none { it.isWhitespace() || it.isISOControl() }) {
                        withLink(LinkAnnotation.Url(url, TextLinkStyles(style = style))) { append(label) }
                    } else withStyle(style) { append(label) }
                    i = end + 1
                } else { append(c); i++ }
            }
            else -> { append(c); i++ }
        }
    }
}

@Composable
internal fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val palette = inks
    val blocks by produceState<List<Pair<Block, AnnotatedString>>>(emptyList(), markdown, palette) {
        value = withContext(Dispatchers.Default) {
            parse(markdown).map { block ->
                val text = when (block) {
                    is Block.Paragraph -> block.text
                    is Block.Heading -> block.text
                    is Block.Bullet -> block.text
                    is Block.Quote -> block.text
                    is Block.Code, Block.Rule, is Block.Table -> null
                }
                block to (text?.let { inline(it, palette.ink, palette.wash, palette.accent) } ?: AnnotatedString(""))
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((block, styled) in blocks) when (block) {
            is Block.Paragraph -> Text(styled, style = ProseType, color = palette.ink)
            is Block.Heading -> Text(styled, Modifier.padding(top = 4.dp),
                style = ProseType.copy(fontSize = if (block.level <= 2) 20.sp else 18.sp, fontWeight = FontWeight.SemiBold),
                color = palette.ink)
            is Block.Bullet -> Row(Modifier.fillMaxWidth().padding(start = (block.depth.coerceAtMost(4) * 16).dp)) {
                Text(block.marker, Modifier.padding(end = 8.dp), style = ProseType, color = palette.quiet, softWrap = false)
                Text(styled, Modifier.weight(1f), style = ProseType, color = palette.ink)
            }
            is Block.Quote -> Text(styled, Modifier.fillMaxWidth().drawBehind {
                drawRect(palette.rule, size = Size(2.dp.toPx(), size.height))
            }.padding(start = 12.dp), style = ProseType.copy(fontStyle = FontStyle.Italic), color = palette.quiet)
            is Block.Code -> CodeBlock(block.text, block.language)
            is Block.Table -> TableBlock(block)
            Block.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(palette.rule))
        }
    }
}

@Composable
private fun TableBlock(table: Block.Table) {
    val palette = inks
    val header = remember(table, palette) { table.header.map { inline(it, palette.ink, palette.wash, palette.accent) } }
    val rows = remember(table, palette) {
        table.rows.map { row -> row.map { inline(it, palette.ink, palette.wash, palette.accent) } }
    }
    val weights = remember(table) { columnWeights(table.header, table.rows) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, palette.rule, shape)) {
        TableRow(header, table.aligns, weights, palette, header = true)
        for (row in rows) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.rule))
            TableRow(row, table.aligns, weights, palette, header = false)
        }
    }
}

@Composable
private fun TableRow(
    cells: List<AnnotatedString>, aligns: List<CellAlign>, weights: List<Float>, palette: Inks, header: Boolean,
) {
    Row(Modifier.fillMaxWidth().background(if (header) palette.wash else Color.Transparent)
        .padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.forEachIndexed { column, text ->
            val align = aligns.getOrElse(column) { CellAlign.Start }
            Text(text, Modifier.weight(weights.getOrElse(column) { 1f }),
                style = ProseType.copy(
                    fontSize = 14.5.sp, lineHeight = 20.sp,
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                ),
                textAlign = when (align) {
                    CellAlign.Start -> TextAlign.Start
                    CellAlign.Center -> TextAlign.Center
                    CellAlign.End -> TextAlign.End
                },
                color = if (header) palette.ink else palette.quiet)
        }
    }
}

/** Wider columns get proportionally more room, so a "file | status" table does not wrap its names. */
private fun columnWeights(header: List<String>, rows: List<List<String>>): List<Float> {
    if (header.isEmpty()) return emptyList()
    return header.indices.map { column ->
        (rows.mapNotNull { it.getOrNull(column)?.length } + header[column].length)
            .maxOrNull()?.toFloat()?.coerceIn(6f, 36f) ?: 6f
    }
}

@Composable
internal fun CodeBlock(code: String, language: String = "", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(inks.sheet)) {
        if (language.isNotEmpty()) {
            Text(language, Modifier.padding(start = 12.dp, top = 8.dp), style = SmallCode, color = inks.faint)
        }
        Text(code, Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), style = CodeType, color = inks.ink,
            softWrap = false)
    }
}
