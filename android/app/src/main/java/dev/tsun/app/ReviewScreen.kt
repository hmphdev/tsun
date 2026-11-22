package dev.tsun.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private sealed interface PatchLine {
    data class Header(val path: String, val fileIndex: Int) : PatchLine
    data class Hunk(val text: String) : PatchLine
    data class Code(val sign: Char, val text: String) : PatchLine
}

private fun parsePatch(diff: String): List<PatchLine> {
    val lines = mutableListOf<PatchLine>()
    var file = -1
    for (raw in diff.lineSequence().take(12_000)) {
        when {
            raw.startsWith("diff --git ") -> {
                file++
                lines += PatchLine.Header(raw.substringAfter(" b/", raw.removePrefix("diff --git ")), file)
            }
            raw.startsWith("index ") || raw.startsWith("--- ") || raw.startsWith("+++ ") ||
                raw.startsWith("new file mode") || raw.startsWith("deleted file mode") ||
                raw.startsWith("similarity index") || raw.startsWith("rename ") -> Unit
            raw.startsWith("@@") -> lines += PatchLine.Hunk(raw.substringAfter("@@ ").substringBefore(" @@"))
            raw.startsWith("Binary files") -> lines += PatchLine.Hunk("Binary file changed")
            raw.isNotEmpty() && (raw[0] == '+' || raw[0] == '-' || raw[0] == ' ') -> lines += PatchLine.Code(raw[0], raw.substring(1))
            raw.startsWith("\\") -> Unit
        }
    }
    return lines
}

/** Everything the thread changed relative to the commit it branched from. */
@Composable
internal fun ReviewScreen(api: DaemonApi, thread: AgentThread, handlesBack: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = handlesBack, onBack = onBack)
    val scope = rememberCoroutineScope()
    var review by remember(thread.id) { mutableStateOf<Review?>(null) }
    var error by remember(thread.id) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(api, thread.id, reload) {
        error = null
        try { review = api.review(thread.id) } catch (e: Exception) { error = e.userMessage() }
    }
    val current = review
    val patch = remember(current?.diff) { parsePatch(current?.diff.orEmpty()) }
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyph.Back, "Back to the conversation", onClick = onBack)
            Column(Modifier.weight(1f)) {
                Text("Changes", style = MaterialTheme.typography.headlineSmall, color = inks.ink)
                Text(thread.branch.orEmpty(), style = SmallCode, color = inks.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (current != null && current.additions + current.deletions > 0) {
                DiffStat(current.additions, current.deletions, Modifier.padding(end = 4.dp))
            }
            if (current != null && current.copiedFiles > 0) CopiedNote(current.copiedFiles, Modifier.padding(end = 4.dp))
            GlyphButton(Glyph.Refresh, "Refresh the diff") { reload++ }
        }
        error?.let { Notice(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), error = true) }
        when {
            current == null && error == null -> Text("Reading the worktree…", Modifier.padding(20.dp), color = inks.faint)
            current != null && current.files.isEmpty() -> EmptyNote("No changes yet",
                "The agent hasn't changed any files on this branch.", Modifier.padding(horizontal = 20.dp))
            current != null -> LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        for ((index, file) in current.files.withIndex()) {
                            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                val target = patch.indexOfFirst { it is PatchLine.Header && it.fileIndex == index }
                                if (target >= 0) scope.launch { list.animateScrollToItem(target + 1) }
                            }.heightIn(min = 38.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                LineIcon(Glyph.File, size = 15, color = inks.faint)
                                Text(file.path, Modifier.weight(1f), style = SmallCode, color = inks.ink, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                if (file.copied) Text(
                                    "copied · ${file.additions} ${if (file.additions == 1) "line" else "lines"}",
                                    style = SmallCode, color = inks.faint)
                                else if (file.binary) Text("binary", style = SmallCode, color = inks.faint)
                                else DiffStat(file.additions, file.deletions)
                            }
                        }
                    }
                }
                itemsIndexed(patch) { _, line -> PatchRow(line) }
                if (current.truncated) item {
                    Notice("The diff is too large to show in full.", Modifier.fillMaxWidth().padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun PatchRow(line: PatchLine) {
    when (line) {
        is PatchLine.Header -> Row(Modifier.fillMaxWidth().padding(top = 14.dp).background(inks.raised)
            .padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            LineIcon(Glyph.File, size = 14, color = inks.accent)
            Text(line.path, Modifier.padding(start = 8.dp), style = SmallCode, color = inks.ink)
        }
        is PatchLine.Hunk -> Text(line.text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
            style = SmallCode, color = inks.faint)
        is PatchLine.Code -> {
            val color = when (line.sign) {
                '+' -> inks.sage
                '-' -> inks.danger
                else -> Color.Transparent
            }
            Row(Modifier.fillMaxWidth().background(color.copy(alpha = if (line.sign == ' ') 0f else 0.09f))
                .horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Text(if (line.sign == ' ') "" else line.sign.toString(), Modifier.width(14.dp), style = SmallCode, color = color)
                Text(line.text, style = SmallCode, color = if (line.sign == ' ') inks.quiet else inks.ink, softWrap = false)
            }
        }
    }
}
