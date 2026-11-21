package dev.tsun.app

import androidx.compose.foundation.background
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
    data object Rule : Block
}

private val orderedItem = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
private val bulletItem = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val heading = Regex("^(#{1,4})\\s+(.*)$")

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
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(text.substring(i + 2, end), code, codeBackground, link)) }
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
                    is Block.Code, Block.Rule -> null
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
            Block.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(palette.rule))
        }
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
