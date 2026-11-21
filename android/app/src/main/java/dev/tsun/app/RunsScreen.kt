package dev.tsun.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** Output of a preset command: a fixed argv run, not an agent conversation. */
@Composable
internal fun RunScreen(
    api: DaemonApi, run: Run, workspace: String?, online: Boolean, busy: Boolean, error: String?,
    handlesBack: Boolean, onBack: () -> Unit, onStop: (Run) -> Unit, onFiles: () -> Unit,
) {
    BackHandler(enabled = handlesBack, onBack = onBack)
    var transcript by remember(api, run.id) { mutableStateOf<Transcript?>(null) }
    var loadError by remember(api, run.id) { mutableStateOf<String?>(null) }
    var follow by rememberSaveable(run.id) { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(api, run.id, run.state, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val next = api.transcript(run.id)
                    transcript = next
                    loadError = null
                    if (!next.run.active) break
                } catch (e: Exception) { loadError = e.userMessage() }
                delay(if (loadError == null) 800L else 2200L)
            }
        }
    }
    val snapshot = transcript
    val current = snapshot?.run ?: run
    val chunks = remember(snapshot?.log) { logBlocks(snapshot?.log.orEmpty()) }
    val scroll = rememberLazyListState()
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    LaunchedEffect(run.id, chunks, follow) {
        if (follow && chunks.isNotEmpty()) scroll.scrollToItem(chunks.size)
    }
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Preset", glyph = Glyph.Terminal)
            current.branch?.let { Chip(it.removePrefix("tsun/"), glyph = Glyph.Branch, mono = true) }
            if (current.filesReady && workspace != null) Chip("Files", glyph = Glyph.Folder, onClick = onFiles)
        }
        Spacer(Modifier.height(10.dp))
        Surface(Modifier.weight(1f).fillMaxWidth(), shape = Corner, color = inks.sheet.copy(alpha = 0.85f),
            border = BorderStroke(1.dp, inks.rule)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RunState(current, Modifier.weight(1f))
                    current.exitCode?.let { Text("exit $it", style = SmallCode, color = if (it == 0) inks.faint else inks.danger) }
                    TextButton({ follow = !follow }, enabled = chunks.isNotEmpty()) {
                        Text(if (follow) "Following" else "Follow", color = if (follow) inks.accent else inks.quiet)
                    }
                }
                HorizontalDivider(color = inks.rule)
                if (snapshot?.truncated == true) {
                    Text("Showing the last 64 KiB of output", Modifier.padding(start = 14.dp, top = 6.dp),
                        color = inks.faint, style = MaterialTheme.typography.bodySmall)
                }
                (loadError ?: error)?.let { Notice(it, Modifier.fillMaxWidth().padding(9.dp), error = true) }
                if (chunks.isEmpty()) {
                    Text(when {
                        snapshot == null -> "Loading output…"
                        snapshot.run.active -> "Waiting for output…"
                        else -> "This run wrote no output."
                    }, Modifier.padding(14.dp), color = inks.faint)
                } else {
                    SelectionContainer(Modifier.weight(1f)) {
                        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp)) {
                            items(chunks) { block -> Text(block, style = CodeType, color = inks.ink) }
                            item { Spacer(Modifier.height(1.dp)) }
                        }
                    }
                }
            }
        }
        if (current.active) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton({ onStop(current) }, enabled = online && !busy && current.state != "stopping") {
                    LineIcon(Glyph.Stop, size = 14, color = inks.danger)
                    Spacer(Modifier.width(6.dp))
                    Text("Stop", color = inks.danger, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private val terminalEscape = Regex("\u001b\\[[0-?]*[ -/]*[@-~]")

private fun logBlocks(log: String): List<String> {
    if (log.isEmpty()) return emptyList()
    return terminalEscape.replace(log, "").replace("\r", "").removeSuffix("\n").split('\n')
        .flatMap { it.chunked(480).ifEmpty { listOf("") } }
        .chunked(8).map { it.joinToString("\n") }
}
