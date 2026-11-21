package dev.tsun.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
        if (paragraph.isNotBlank()) blocks += Block.Paragraph(paragraph.toString().trim())
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
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                blocks += Block.Code(language, code.toString())
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
                blocks += Block.Bullet("•", match.groupValues[2], match.groupValues[1].length / 2)
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
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(trimmed)
                }
            }
        }
        i++
    }
    flush()
    return blocks
}

/** Inline styles: `code`, **bold**, *italic* / _italic_, and [links](url) shown as text. */
internal fun inline(text: String, code: Color, codeBackground: Color, link: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = CodeType.fontFamily, fontSize = 0.88.em(), color = code,
                        background = codeBackground)) { append(" ${text.substring(i + 1, end)} ") }
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
            c == '[' -> {
                val close = text.indexOf("](", i)
                val end = if (close > i) text.indexOf(')', close) else -1
                if (close > i && end > close) {
                    withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) { append(text.substring(i + 1, close)) }
                    i = end + 1
                } else { append(c); i++ }
            }
            else -> { append(c); i++ }
        }
    }
}

private fun Double.em() = (this * 17).sp

@Composable
internal fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parse(markdown) }
    val palette = inks
    val styled: (String) -> AnnotatedString = { inline(it, palette.ink, palette.wash, palette.accent) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (block in blocks) when (block) {
            is Block.Paragraph -> Text(styled(block.text), style = ProseType, color = palette.ink)
            is Block.Heading -> Text(styled(block.text), Modifier.padding(top = 4.dp),
                style = ProseType.copy(fontSize = if (block.level <= 2) 20.sp else 18.sp, fontWeight = FontWeight.SemiBold),
                color = palette.ink)
            is Block.Bullet -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
                Text(block.marker, Modifier.width(if (block.marker == "•") 16.dp else 24.dp), style = ProseType, color = palette.quiet)
                Text(styled(block.text), style = ProseType, color = palette.ink)
            }
            is Block.Quote -> Row {
                Box(Modifier.width(2.dp).background(palette.rule))
                Text(styled(block.text), Modifier.padding(start = 12.dp), style = ProseType.copy(fontStyle = FontStyle.Italic),
                    color = palette.quiet)
            }
            is Block.Code -> CodeBlock(block.text, block.language)
            Block.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(palette.rule).padding(top = 1.dp))
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
