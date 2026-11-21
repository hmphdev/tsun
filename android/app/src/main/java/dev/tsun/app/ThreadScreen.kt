package dev.tsun.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ---- A live agent thread -----------------------------------------------------

private data class TranscriptRow(val id: String, val entry: MutableState<ThreadItem>)

@Composable
internal fun ThreadScreen(
    api: DaemonApi,
    threadId: String,
    initial: AgentThread?,
    providers: List<ModelProvider>,
    handlesBack: Boolean,
    onBack: () -> Unit,
    onThread: (AgentThread) -> Unit,
    onMode: (Mode) -> Unit,
    onReview: () -> Unit,
    onFiles: () -> Unit,
) {
    BackHandler(enabled = handlesBack, onBack = onBack)
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var thread by remember(threadId) { mutableStateOf(initial) }
    val rows = remember(threadId) { mutableStateListOf<TranscriptRow>() }
    val byId = remember(threadId) { mutableMapOf<String, TranscriptRow>() }
    var cursor by remember(threadId) { mutableLongStateOf(0L) }
    var actionVersion by remember(threadId) { mutableLongStateOf(0L) }
    val publishThread by rememberUpdatedState(onThread)
    var loaded by remember(threadId) { mutableStateOf(false) }
    var offline by remember(threadId) { mutableStateOf<String?>(null) }
    var actionError by remember(threadId) { mutableStateOf<String?>(null) }
    var busy by remember(threadId) { mutableStateOf(false) }
    val draft = rememberTextFieldState()
    var providerId by rememberSaveable(threadId) { mutableStateOf<String?>(null) }

    LaunchedEffect(api, threadId, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val version = actionVersion
                    val page = api.thread(threadId, cursor, if (loaded) 2000 else 0)
                    if (page.reset || page.items.isNotEmpty()) Snapshot.withMutableSnapshot {
                        if (page.reset) {
                            rows.clear()
                            byId.clear()
                        }
                        for (entry in page.items) {
                            val row = byId[entry.id]
                            if (row != null) row.entry.value = entry
                            else {
                                val added = TranscriptRow(entry.id, mutableStateOf(entry))
                                byId[entry.id] = added
                                rows.add(added)
                            }
                        }
                    }
                    cursor = page.rev
                    // A poll started before a local action can carry an older summary.
                    if (version == actionVersion && thread != page.thread) {
                        thread = page.thread
                        publishThread(page.thread)
                    }
                    loaded = true
                    offline = null
                    // Batch bursty deltas instead of repainting once per token.
                    if (page.thread.active && page.items.isNotEmpty()) delay(80)
                } catch (e: Exception) {
                    offline = e.userMessage()
                    delay(1500)
                }
            }
        }
    }

    val current = thread
    val provider = providers.firstOrNull { it.id == (providerId ?: current?.provider) }
    fun act(block: suspend () -> AgentThread) {
        if (busy) return
        busy = true
        actionError = null
        scope.launch {
            try {
                val updated = block()
                actionVersion++
                thread = updated
                publishThread(updated)
            } catch (e: Exception) { actionError = e.userMessage() }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (current != null) ThreadChips(current, onReview, onFiles)
        Conversation(rows, current, loaded, Modifier.weight(1f),
            onDecide = { call, allow -> act { api.decide(threadId, call, allow) } },
            onReview = onReview)
        (offline ?: actionError)?.let {
            Notice(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), error = true)
        }
        Composer(
            draft = draft,
            placeholder = if (current?.active == true) "The agent is working…" else "Reply…",
            mode = current?.mode ?: Mode.Edits,
            onMode = { mode -> act { api.setMode(threadId, mode).also { onMode(it.mode) } } },
            providers = providers, provider = provider, onProvider = { providerId = it.id },
            working = current?.active == true, canSend = current != null && provider?.enabled != false, busy = busy,
            onSend = { text ->
                val switch = providerId?.takeIf { it != current?.provider }
                act {
                    api.reply(threadId, text, switch).also { draft.clearText() }
                }
            },
            onStop = { act { api.interrupt(threadId) } },
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        )
    }
}

@Composable
private fun ThreadChips(thread: AgentThread, onReview: () -> Unit, onFiles: () -> Unit) {
    val context = LocalContext.current
    val copyId = {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Tsun session ID", thread.id))
        Unit
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip(thread.branch?.removePrefix("tsun/") ?: "Session ID", glyph = Glyph.Branch, mono = true,
            modifier = Modifier.semantics { contentDescription = "Copy session ID" }, onClick = copyId)
        if (thread.branch != null) {
            Row(Modifier.clip(Pill).background(inks.wash).clickable(role = Role.Button, onClick = onReview)
                .heightIn(min = 32.dp).padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                LineIcon(Glyph.Diff, color = inks.quiet, size = 14)
                if (thread.filesChanged == 0) Text("No changes", style = MaterialTheme.typography.labelMedium, color = inks.quiet)
                else {
                    Text("${thread.filesChanged} ${if (thread.filesChanged == 1) "file" else "files"}",
                        style = MaterialTheme.typography.labelMedium, color = inks.quiet)
                    DiffStat(thread.additions, thread.deletions)
                }
            }
        }
        if (thread.filesReady) Chip("Files", glyph = Glyph.Folder, onClick = onFiles)
    }
}

@Composable
private fun Conversation(
    entries: List<TranscriptRow>, thread: AgentThread?, loaded: Boolean, modifier: Modifier,
    onDecide: (String, Boolean) -> Unit, onReview: () -> Unit,
) {
    val list = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    LaunchedEffect(list) {
        snapshotFlow { !list.canScrollForward && !list.isScrollInProgress }.collect { atEnd ->
            if (atEnd) follow = true
        }
    }
    val active by rememberUpdatedState(thread?.active == true)
    val ready by rememberUpdatedState(loaded)
    LaunchedEffect(list, entries) {
        snapshotFlow {
            if (!follow || entries.isEmpty()) null
            else {
                val tail = entries.last()
                val end = entries.size + (if (active) 1 else 0) + (if (ready) 0 else 1)
                val tailHeight = list.layoutInfo.visibleItemsInfo.lastOrNull { it.key == tail.id }?.size
                Triple(end, tail.entry.value, tailHeight)
            }
        }.collect { change ->
            if (change != null && follow && !list.isScrollInProgress) {
                // Clamp like the tail effect: an item's animated height can make the count read stale.
                list.scrollToItem(change.first.coerceAtMost(list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            }
        }
    }
    LaunchedEffect(list, entries) {
        // Let the keyboard resize the pane without scheduling a scroll on every animation frame.
        snapshotFlow { list.layoutInfo.viewportSize.height }.collectLatest {
            delay(100)
            // Re-read the state after the delay: entries animate their height while streaming, and a
            // jump computed from a half-grown item parks the list past its own content, which paints
            // rows outside the pane. scrollToItem clamps on measure where requestScrollToItem does not.
            if (follow && !list.isScrollInProgress && list.canScrollForward && entries.isNotEmpty()) {
                val end = entries.size + (if (active) 1 else 0) + (if (ready) 0 else 1)
                list.scrollToItem(end.coerceAtMost(list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            }
        }
    }
    Box(modifier.fillMaxWidth().clipToBounds()) {
        LazyColumn(Modifier.fillMaxSize(), state = list,
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!loaded) item { Text("Loading…", color = inks.faint, style = MaterialTheme.typography.bodyMedium) }
            items(entries, key = { it.id }) { row ->
                when (val entry = row.entry.value) {
                    is ThreadItem.User -> UserTurn(entry.text)
                    is ThreadItem.Text -> if (entry.text.isNotBlank()) {
                        SelectionContainer { MarkdownText(entry.text, Modifier.fillMaxWidth()) }
                    }
                    is ThreadItem.Thinking -> ThinkingRow(entry)
                    is ThreadItem.Tool -> ToolRow(entry, waiting = thread?.pendingCall == entry.id, onDecide)
                    is ThreadItem.Note -> Text(entry.text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                        color = if (entry.error) inks.danger else inks.faint)
                    is ThreadItem.RunEnd -> RunSummary(entry, onReview)
                }
            }
            if (thread != null && thread.active) item(key = "working") {
                WorkingLine(thread, entries.lastOrNull()?.entry?.value)
            }
            item(key = "end") { Spacer(Modifier.height(1.dp)) }
        }
        if (!follow && entries.isNotEmpty()) {
            Chip("Latest", glyph = Glyph.Down, tint = inks.ink, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                onClick = { follow = true })
        }
    }
}

@Composable
private fun UserTurn(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.CenterEnd) {
        SelectionContainer {
            Text(text, Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                .background(inks.raised).border(1.dp, inks.rule, RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                .padding(horizontal = 15.dp, vertical = 11.dp), style = MaterialTheme.typography.bodyLarge, color = inks.ink)
        }
    }
}

@Composable
private fun ThinkingRow(entry: ThreadItem.Thinking) {
    if (!entry.streaming && entry.text.isBlank()) return
    var open by remember(entry.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = entry.text.isNotBlank(), role = Role.Button) { open = !open }
            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(if (entry.streaming) inks.amber else inks.faint, pulsing = entry.streaming, size = 6)
            Text(if (entry.streaming) "Thinking" else "Thought", style = MaterialTheme.typography.labelMedium, color = inks.quiet)
            if (entry.text.isNotBlank()) LineIcon(if (open) Glyph.Down else Glyph.Chevron, color = inks.faint, size = 12)
        }
        if (open) {
            Text(entry.text.trim(), Modifier.padding(start = 14.dp, top = 4.dp),
                style = ProseType.copy(fontStyle = FontStyle.Italic, fontSize = ProseType.fontSize * 0.88f), color = inks.quiet)
        }
    }
}

private fun toolGlyph(name: String) = when (name) {
    "bash" -> Glyph.Terminal
    "edit_file", "write_file" -> Glyph.Pencil
    "read_file" -> Glyph.Eye
    "search" -> Glyph.Search
    "list_files" -> Glyph.Folder
    "delegate_task", "message_session" -> Glyph.Branch
    "web_fetch", "web_search" -> Glyph.Search
    else -> Glyph.File
}

@Composable
private fun statusColor(status: String): Color = when (status) {
    "running", "streaming" -> inks.amber
    "waiting" -> inks.accent
    "done" -> inks.sage
    "error" -> inks.danger
    else -> inks.faint
}

@Composable
private fun ToolRow(entry: ThreadItem.Tool, waiting: Boolean, onDecide: (String, Boolean) -> Unit) {
    var open by remember(entry.id) { mutableStateOf(false) }
    val expandable = entry.output != null || entry.after != null
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(if (waiting) inks.accentWash else inks.sheet.copy(alpha = 0.7f))
        .border(1.dp, if (waiting) inks.accent.copy(alpha = 0.55f) else inks.rule, shape).animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable(enabled = expandable, role = Role.Button) { open = !open }
            .heightIn(min = 42.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            LineIcon(toolGlyph(entry.name), color = statusColor(entry.status), size = 16)
            Text(entry.title, style = MaterialTheme.typography.titleSmall, color = inks.ink)
            Text(entry.detail.substringBefore('\n'), Modifier.weight(1f), style = SmallCode, color = inks.quiet,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (entry.status) {
                "running", "streaming" -> StatusDot(inks.amber, pulsing = true, size = 6)
                "denied" -> Text("Declined", style = MaterialTheme.typography.bodySmall, color = inks.faint)
                "cancelled" -> Text("Stopped", style = MaterialTheme.typography.bodySmall, color = inks.faint)
                else -> if (entry.exitCode != null && entry.exitCode != 0) {
                    Text("exit ${entry.exitCode}", style = SmallCode, color = inks.danger)
                } else if (entry.status == "error") {
                    Text("Failed", style = MaterialTheme.typography.bodySmall, color = inks.danger)
                }
            }
            if (expandable) LineIcon(if (open) Glyph.Down else Glyph.Chevron, color = inks.faint, size = 12)
        }
        if (waiting) Approval(entry, onDecide)
        if (open) ToolDetail(entry)
    }
}

@Composable
private fun Approval(entry: ThreadItem.Tool, onDecide: (String, Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (entry.name == "bash") CodeBlock(entry.detail)
        if (entry.name == "message_session" || entry.name == "web_search" || entry.name == "web_fetch") {
            SelectionContainer { Text(entry.detail, style = SmallCode, color = inks.quiet) }
        }
        Text(when (entry.name) {
            "bash" -> "Run this command in the worktree?"
            "message_session" -> "Send this question to the other session?"
            "web_search" -> "Send this search query to Brave?"
            "web_fetch" -> "Fetch this public web page?"
            "write_file" -> "Write this file?"
            else -> "Apply this edit?"
        }, style = MaterialTheme.typography.bodyMedium, color = inks.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ onDecide(entry.id, false) }) { Text("Decline", color = inks.quiet) }
            Spacer(Modifier.weight(1f))
            Button({ onDecide(entry.id, true) }, shape = Pill,
                colors = ButtonDefaults.buttonColors(containerColor = inks.accent, contentColor = inks.onAccent),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
                LineIcon(Glyph.Check, color = inks.onAccent, size = 15)
                Spacer(Modifier.width(6.dp))
                Text("Allow")
            }
        }
    }
}

@Composable
private fun ToolDetail(entry: ThreadItem.Tool) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            (entry.name == "delegate_task" || entry.name == "message_session") && entry.output != null ->
                SelectionContainer { MarkdownText(entry.output) }
            entry.name == "edit_file" && entry.after != null -> EditPreview(entry.before.orEmpty(), entry.after)
            entry.name == "write_file" && entry.after != null ->
                if (entry.created) EditPreview("", entry.after) else EditPreview(entry.before.orEmpty(), entry.after)
            else -> {
                if (entry.name == "bash" && entry.detail.contains('\n')) CodeBlock(entry.detail)
                entry.output?.let { OutputBlock(it) }
            }
        }
        if (entry.status == "error" && entry.after != null) entry.output?.let { OutputBlock(it) }
    }
}

@Composable
private fun OutputBlock(text: String) {
    var all by remember(text) { mutableStateOf(false) }
    val lines = remember(text) { text.trimEnd().lines() }
    val shown = if (all || lines.size <= 40) lines else lines.take(40)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(inks.paper.copy(alpha = 0.6f))) {
        SelectionContainer {
            Text(shown.joinToString("\n"), Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                style = SmallCode, color = inks.quiet, softWrap = false)
        }
        if (shown.size < lines.size) {
            Text("Show ${lines.size - shown.size} more lines", Modifier.clickable(role = Role.Button) { all = true }
                .padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = inks.accent)
        }
    }
}

/** Old text in rust, new text in sage, like a small unified diff. */
@Composable
internal fun EditPreview(before: String, after: String) {
    val removed = if (before.isEmpty()) emptyList() else before.trimEnd('\n').lines()
    val added = if (after.isEmpty()) emptyList() else after.trimEnd('\n').lines()
    val limit = 60
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(inks.paper.copy(alpha = 0.6f))
        .horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        for (line in removed.take(limit)) DiffLine("-", line, inks.danger)
        for (line in added.take(limit)) DiffLine("+", line, inks.sage)
        if (removed.size > limit || added.size > limit) {
            Text("…", Modifier.padding(horizontal = 10.dp), style = SmallCode, color = inks.faint)
        }
    }
}

@Composable
internal fun DiffLine(sign: String, text: String, color: Color) {
    Row(Modifier.background(color.copy(alpha = 0.09f)).padding(horizontal = 10.dp)) {
        Text(sign, Modifier.width(14.dp), style = SmallCode, color = color)
        Text(text, style = SmallCode, color = inks.ink, softWrap = false)
    }
}

@Composable
private fun RunSummary(entry: ThreadItem.RunEnd, onReview: () -> Unit) {
    val changed = entry.additions + entry.deletions > 0
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = changed, role = Role.Button, onClick = onReview)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).height(1.dp).background(inks.rule))
        Text((if (entry.stopped) "Stopped after " else "Worked ") + duration(entry.durationMs) +
            if (changed) " · ${entry.files.size} ${if (entry.files.size == 1) "file" else "files"}" else "",
            style = MaterialTheme.typography.bodySmall, color = inks.faint)
        if (changed) DiffStat(entry.additions, entry.deletions)
        Box(Modifier.weight(1f).height(1.dp).background(inks.rule))
    }
}

@Composable
private fun WorkingLine(thread: AgentThread, tail: ThreadItem?) {
    val streamingText = tail is ThreadItem.Text && tail.streaming
    if (streamingText) return
    val label = when {
        thread.state == "preparing" -> "Preparing the worktree…"
        thread.state == "waiting" -> "Waiting for your approval"
        tail is ThreadItem.Tool && tail.status == "running" -> "Running ${tail.title.lowercase()}…"
        else -> "Working…"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier.padding(vertical = 2.dp)) {
        StatusDot(if (thread.state == "waiting") inks.accent else inks.amber, pulsing = true)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = inks.quiet)
    }
}

internal fun duration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(1)
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"
}

// ---- New session -------------------------------------------------------------

@Composable
internal fun NewSessionScreen(
    workspace: Workspace?,
    workspaces: List<Workspace>,
    providers: List<ModelProvider>,
    defaultProvider: String?,
    recent: List<AgentThread>,
    online: Boolean,
    busy: Boolean,
    error: String?,
    mode: Mode,
    onMode: (Mode) -> Unit,
    onWorkspace: (Workspace) -> Unit,
    onStart: (prompt: String, mode: Mode, provider: String?) -> Unit,
    onPreset: (Preset) -> Unit,
    onOpen: (AgentThread) -> Unit,
) {
    val draft = rememberTextFieldState()
    var providerId by rememberSaveable { mutableStateOf<String?>(null) }
    val provider = providers.firstOrNull { it.id == (providerId ?: defaultProvider) }
        ?: providers.firstOrNull { it.enabled }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp)) {
            item {
                Spacer(Modifier.height(36.dp))
                TsunMark(Modifier.size(30.dp))
                Spacer(Modifier.height(20.dp))
                Text("What should we work on?", style = MaterialTheme.typography.headlineLarge, color = inks.ink)
                Text(when {
                    workspace == null -> "Connect to Debian to load your projects."
                    provider == null -> "Add a model provider to the daemon's config.json to start a session."
                    !provider.enabled -> provider.reason ?: "This model isn't available."
                    workspace.worktrees -> "The agent works in ${workspace.label} on its own branch. Your checkout stays as it is."
                    else -> "The agent works directly in ${workspace.label}."
                }, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge, color = inks.quiet)
            }
            if (recent.isNotEmpty()) {
                item { Eyebrow("RECENT", Modifier.padding(top = 34.dp, bottom = 6.dp)) }
                items(recent.take(5), key = { it.id }) { thread -> ThreadRow(thread, selected = false) { onOpen(thread) } }
            }
        }
        error?.let { Notice(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), error = true) }
        Composer(
            draft = draft,
            placeholder = if (workspace != null) "Describe a task for ${workspace.label}…" else "Describe a task…",
            mode = mode, onMode = onMode,
            providers = providers, provider = provider, onProvider = { providerId = it.id },
            working = false, canSend = online && workspace != null && provider?.enabled == true, busy = busy,
            onSend = { onStart(it, mode, provider?.id) },
            onStop = {},
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            leading = {
                ProjectPicker(workspace, workspaces, onWorkspace)
                if (workspace != null && workspace.presets.isNotEmpty()) PresetPicker(workspace.presets, online && !busy, onPreset)
            },
        )
    }
}

@Composable
private fun ProjectPicker(workspace: Workspace?, workspaces: List<Workspace>, onWorkspace: (Workspace) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip(workspace?.label ?: "No project", glyph = Glyph.Folder, tint = inks.ink,
            trailing = if (workspaces.size > 1) Glyph.Down else null,
            onClick = if (workspaces.size > 1) ({ open = true }) else null)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = inks.raised) {
            for (choice in workspaces) {
                DropdownMenuItem(text = { Text(choice.label, color = if (choice.id == workspace?.id) inks.accent else inks.ink) },
                    onClick = { open = false; onWorkspace(choice) })
            }
        }
    }
}

@Composable
private fun PresetPicker(presets: List<Preset>, enabled: Boolean, onPreset: (Preset) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip("Presets", glyph = Glyph.Terminal, enabled = enabled, trailing = Glyph.Down, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = inks.raised) {
            Text("Run a fixed command instead", Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = inks.faint)
            for (preset in presets) {
                DropdownMenuItem(text = { Text(preset.label) }, leadingIcon = { LineIcon(Glyph.Terminal, size = 16) },
                    onClick = { open = false; onPreset(preset) })
            }
        }
    }
}

@Composable
internal fun ThreadRow(thread: AgentThread, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(if (selected) inks.accentWash else Color.Transparent)
        .clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp)
        .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusDot(threadColor(thread), pulsing = thread.active)
        Column(Modifier.weight(1f)) {
            Text(thread.title, style = MaterialTheme.typography.titleSmall, color = inks.ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(when (thread.state) {
                "waiting" -> "Needs approval"
                "preparing" -> "Preparing…"
                "running" -> "Working…"
                else -> thread.error?.let { "Error" } ?: thread.providerLabel
            }, style = MaterialTheme.typography.bodySmall,
                color = if (thread.state == "waiting") inks.accent else if (thread.error != null && !thread.active) inks.danger else inks.faint,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (thread.additions + thread.deletions > 0) DiffStat(thread.additions, thread.deletions)
    }
}

@Composable
internal fun threadColor(thread: AgentThread): Color = when {
    thread.state == "waiting" -> inks.accent
    thread.active -> inks.amber
    thread.error != null -> inks.danger
    else -> inks.faint
}
