package dev.tsun.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

@Composable
internal fun RunsScreen(
    api: DaemonApi, workspace: Workspace?, catalog: List<Workspace>, runs: RunList, selected: Run?,
    online: Boolean, busy: Boolean, error: String?, handlesBack: Boolean,
    onSelect: (Run) -> Unit, onNew: () -> Unit, onLaunch: (Preset) -> Unit, onStop: (Run) -> Unit,
) {
    BackHandler(enabled = handlesBack && selected != null, onBack = onNew)
    val positions = rememberSaveableStateHolder()
    val active = runs.items.filter { it.active }.sortedBy { it.id.toLongOrNull() ?: 0L }
    Column(Modifier.fillMaxSize()) {
        if (selected == null) {
            Welcome(workspace, runs.items, onSelect, Modifier.weight(1f))
        } else {
            positions.SaveableStateProvider(selected.id) {
                RunTranscript(api, selected, catalog.firstOrNull { it.id == selected.workspace }?.label,
                    onNew, Modifier.weight(1f))
            }
            if (active.any { it.id != selected.id }) ActiveRuns(active, selected.id, onSelect)
        }
        error?.let { Notice(it, Modifier.fillMaxWidth().padding(horizontal = 20.dp), error = true) }
        RunDock(workspace, selected, active.size, runs.capacity, online, busy, onLaunch, onStop)
    }
}

@Composable
private fun ActiveRuns(runs: List<Run>, selectedId: String, onSelect: (Run) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 19.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        for (run in runs) {
            val selected = run.id == selectedId
            Surface(Modifier.weight(1f), shape = RoundedCornerShape(9.dp),
                color = if (selected) inks.moss else inks.paper,
                border = BorderStroke(1.dp, if (selected) inks.pine else inks.rule)) {
                Row(Modifier.selectable(selected = selected, role = Role.Tab, onClick = { onSelect(run) })
                    .heightIn(min = 46.dp).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    StatusDot(if (run.state == "running") inks.pine else inks.quiet)
                    Column(Modifier.weight(1f)) {
                        Text(run.label, style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Run ${run.id}", style = MaterialTheme.typography.bodySmall, color = inks.quiet)
                    }
                }
            }
        }
    }
}

@Composable
private fun Welcome(workspace: Workspace?, runs: List<Run>, onRun: (Run) -> Unit, modifier: Modifier) {
    val active = runs.filter { it.active }
    val visible = active + runs.filterNot { it.active }.take(3 - active.size.coerceAtMost(3))
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 23.dp, end = 23.dp, top = 29.dp, bottom = 15.dp)) {
        item {
            Box(Modifier.size(47.dp).clip(RoundedCornerShape(12.dp)).background(inks.moss), contentAlignment = Alignment.Center) {
                TsunMark(Modifier.size(27.dp))
            }
            Spacer(Modifier.height(19.dp))
            Eyebrow("WORKBENCH", color = inks.pine)
            Text("Start with a run.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineLarge)
            Text("Pick a preset to run in Debian, or open Files to work on your project directly.",
                Modifier.padding(top = 5.dp), color = inks.quiet, style = MaterialTheme.typography.bodyLarge)
            if (workspace == null) {
                Text("Waiting for your workspaces…", Modifier.padding(top = 14.dp), color = inks.quiet,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (visible.isNotEmpty()) {
            item { Eyebrow("ACTIVITY", Modifier.padding(top = 31.dp, bottom = 7.dp)); HorizontalDivider(color = inks.rule) }
            items(visible, key = { it.id }) { run ->
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onRun(run) }
                    .heightIn(min = 51.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    LineIcon(Glyph.Runs, size = 17)
                    Text(run.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    RunState(run)
                    LineIcon(Glyph.Next, size = 14)
                }
                HorizontalDivider(color = inks.rule)
            }
        }
    }
}

@Composable
private fun RunTranscript(api: DaemonApi, run: Run, workspace: String?, onNew: () -> Unit, modifier: Modifier) {
    var transcript by remember(api, run.id) { mutableStateOf<Transcript?>(null) }
    var error by remember(api, run.id) { mutableStateOf<String?>(null) }
    var follow by rememberSaveable(run.id) { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(api, run.id, run.state, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val next = api.transcript(run.id)
                    transcript = next
                    error = null
                    if (!next.run.active) break
                } catch (e: Exception) { error = e.userMessage() }
                delay(if (error == null) 800L else 2200L)
            }
        }
    }
    val snapshot = transcript
    val chunks = remember(snapshot?.log) { logBlocks(snapshot?.log.orEmpty()) }
    val scroll = rememberLazyListState()
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) follow = false
        }
    }
    LaunchedEffect(run.id, chunks, follow) {
        if (follow && chunks.isNotEmpty()) scroll.scrollToItem(chunks.size)
    }
    Column(modifier.fillMaxWidth().padding(start = 21.dp, end = 21.dp, top = 20.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("RUN ${run.id}", color = inks.pine)
                Text(run.label, Modifier.padding(top = 5.dp), style = MaterialTheme.typography.headlineMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            GlyphButton(Glyph.Plus, "Open a new run") { onNew() }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RunState(snapshot?.run ?: run)
            (workspace ?: run.workspace).let {
                Text(it, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, color = inks.quiet,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            (snapshot?.run ?: run).exitCode?.let {
                Text("Exit $it", style = MaterialTheme.typography.bodySmall, color = inks.quiet, maxLines = 1)
            }
        }
        Spacer(Modifier.height(13.dp))
        Surface(Modifier.weight(1f).fillMaxWidth(), shape = Corner, color = inks.sheet, border = BorderStroke(1.dp, inks.rule)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 13.dp, end = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    LineIcon(Glyph.Runs, size = 16)
                    Text("Output", Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton({ follow = !follow }, enabled = chunks.isNotEmpty()) {
                        Text(if (follow) "Following" else "Follow", color = if (follow) inks.pine else inks.quiet)
                    }
                }
                HorizontalDivider(color = inks.rule)
                if (snapshot?.truncated == true) {
                    Text("Showing the last 64 KiB of output", Modifier.padding(start = 12.dp, top = 6.dp),
                        color = inks.quiet, style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Notice(it, Modifier.fillMaxWidth().padding(9.dp), error = true) }
                if (chunks.isEmpty()) {
                    val message = when {
                        snapshot == null -> "Loading output…"
                        snapshot.run.active -> "Waiting for output…"
                        else -> "This run wrote no output."
                    }
                    Text(message, Modifier.padding(14.dp), color = inks.quiet)
                } else {
                    SelectionContainer(Modifier.weight(1f)) {
                        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                            items(chunks) { block -> Text(block, style = CodeType, color = inks.ink) }
                            item { Spacer(Modifier.height(1.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunDock(
    workspace: Workspace?, run: Run?, running: Int, capacity: Int, online: Boolean, busy: Boolean,
    onLaunch: (Preset) -> Unit, onStop: (Run) -> Unit,
) {
    var presetId by rememberSaveable(workspace?.id) { mutableStateOf<String?>(null) }
    var choices by remember { mutableStateOf(false) }
    val presets = workspace?.presets.orEmpty()
    val preset = presets.firstOrNull { it.id == presetId } ?: presets.firstOrNull()
    Surface(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 12.dp),
        shape = Corner, color = inks.sheet, border = BorderStroke(1.dp, inks.rule)) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 11.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                LineIcon(Glyph.Folder, size = 15)
                Text(workspace?.label ?: "No workspace", Modifier.padding(start = 7.dp).weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = inks.quiet, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                if (run?.active == true) {
                    TextButton({ onStop(run) }, enabled = online && !busy && run.state == "running") {
                        LineIcon(Glyph.Stop, size = 13, color = inks.rust)
                        Spacer(Modifier.width(5.dp))
                        Text(if (run.state == "stopping") "Stopping…" else "Stop", color = inks.rust)
                    }
                } else {
                    Text("$running / $capacity running", style = MaterialTheme.typography.bodySmall, color = inks.quiet)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(inks.wash)
                        .clickable(enabled = presets.size > 1 && !busy, role = Role.Button) { choices = true }
                        .heightIn(min = 46.dp).padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(preset?.label ?: "No presets configured", Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (presets.size > 1) LineIcon(Glyph.Down, size = 16)
                    }
                    DropdownMenu(expanded = choices, onDismissRequest = { choices = false }) {
                        presets.forEach { choice ->
                            DropdownMenuItem(text = { Text(choice.label) }, onClick = { presetId = choice.id; choices = false })
                        }
                    }
                }
                Button(onClick = { preset?.let(onLaunch) }, enabled = online && !busy && running < capacity && preset != null,
                    modifier = Modifier.heightIn(min = 46.dp), shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 15.dp, vertical = 8.dp)) {
                    Text(if (running >= capacity) "Full" else "Run")
                    Spacer(Modifier.width(5.dp))
                    LineIcon(Glyph.Up, color = if (online && !busy && running < capacity && preset != null) inks.onPine else inks.quiet,
                        size = 16)
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
