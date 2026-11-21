package dev.tsun.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the main pane points. Kept as plain strings so it survives process death. */
private object Route {
    const val Home = "home"
    const val Thread = "thread"
    const val Run = "run"
    const val Files = "files"
    const val Review = "review"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Workbench(api: DaemonApi, port: Int, initialCatalog: Catalog?, onForget: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var catalog by remember(api) { mutableStateOf(initialCatalog) }
    var runs by remember(api) { mutableStateOf(RunList(emptyList(), 2)) }
    var threads by remember(api) { mutableStateOf(ThreadList(emptyList(), 3)) }
    var offline by remember(api) { mutableStateOf<String?>(null) }
    var online by remember(api) { mutableStateOf(initialCatalog != null) }
    var refresh by remember { mutableIntStateOf(0) }
    var route by rememberSaveable { mutableStateOf(Route.Home) }
    var routeId by rememberSaveable { mutableStateOf<String?>(null) }   // thread or run id
    var fileOwner by rememberSaveable { mutableStateOf<String?>(null) } // thread or run whose worktree is open
    var fileIsThread by rememberSaveable { mutableStateOf(false) }
    var workspaceId by rememberSaveable { mutableStateOf<String?>(null) }
    val screenState = rememberSaveableStateHolder()
    var dirty by remember { mutableStateOf(false) }
    var fileBusy by remember { mutableStateOf(false) }
    var discardTo by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmForget by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    val workspaces = catalog?.workspaces.orEmpty()
    val providers = catalog?.providers.orEmpty()
    val workspace = workspaces.firstOrNull { it.id == workspaceId } ?: workspaces.firstOrNull()
    val thread = threads.items.firstOrNull { it.id == routeId }
    val run = runs.items.firstOrNull { it.id == routeId }

    LaunchedEffect(api, lifecycle, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var readCatalog = catalog == null
            while (true) {
                try {
                    if (readCatalog) catalog = api.catalog()
                    runs = api.runs()
                    threads = api.threads()
                    online = true
                    offline = null
                    readCatalog = false
                } catch (e: Exception) {
                    offline = e.userMessage()
                    online = false
                    readCatalog = true
                }
                val busy = runs.items.any { it.active } || threads.items.any { it.active }
                delay(if (busy) 1200L else 3000L)
            }
        }
    }

    fun navigate(change: () -> Unit) {
        if (fileBusy) return
        if (drawer.isOpen) scope.launch { drawer.close() }
        if (dirty) discardTo = change else change()
    }

    fun go(next: String, id: String? = null) = navigate {
        route = next
        routeId = id
        actionError = null
    }

    fun openThread(target: AgentThread) = navigate {
        workspaceId = target.workspace
        route = Route.Thread
        routeId = target.id
        actionError = null
    }

    fun openRun(target: Run) = navigate {
        workspaceId = target.workspace
        route = Route.Run
        routeId = target.id
        actionError = null
    }

    fun openFiles(owner: String?, isThread: Boolean, project: Workspace? = workspace) = navigate {
        if (project != null) workspaceId = project.id
        fileOwner = owner
        fileIsThread = isThread
        // Remember where to come back to.
        if (route != Route.Files) {
            if (owner == null) routeId = null
        }
        route = Route.Files
        actionError = null
    }

    fun upsert(updated: AgentThread) {
        threads = threads.copy(items = listOf(updated) + threads.items.filterNot { it.id == updated.id })
    }

    fun startThread(prompt: String, mode: Mode, provider: String?) {
        val target = workspace ?: return
        if (submitting || prompt.isBlank()) return
        submitting = true
        actionError = null
        scope.launch {
            try {
                val created = api.startThread(target.id, prompt, mode, provider)
                upsert(created)
                openThread(created)
            } catch (e: Exception) { actionError = e.userMessage() }
            finally { submitting = false }
        }
    }

    fun launchPreset(preset: Preset) {
        val target = workspace ?: return
        if (submitting) return
        submitting = true
        actionError = null
        scope.launch {
            try {
                val started = api.launch(target.id, preset.id, "")
                runs = runs.copy(items = (listOf(started) + runs.items.filterNot { it.id == started.id }).take(32))
                openRun(started)
            } catch (e: Exception) { actionError = e.userMessage() }
            finally { submitting = false; refresh++ }
        }
    }

    fun stopRun(target: Run) {
        if (submitting) return
        submitting = true
        actionError = null
        scope.launch {
            try {
                val updated = api.stop(target.id)
                runs = runs.copy(items = runs.items.map { if (it.id == updated.id) updated else it })
            } catch (e: Exception) { actionError = e.userMessage() }
            finally { submitting = false; refresh++ }
        }
    }

    discardTo?.let { change ->
        AlertDialog(
            onDismissRequest = { discardTo = null }, containerColor = inks.raised,
            title = { Text("Discard unsaved edits?") },
            text = { Text("The file in Debian will keep its last saved version.") },
            confirmButton = { TextButton({ discardTo = null; dirty = false; change() }) { Text("Discard") } },
            dismissButton = { TextButton({ discardTo = null }) { Text("Keep editing") } },
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false }, containerColor = inks.raised,
            title = { Text("Forget this pairing?") },
            text = { Text("This removes the key from the app. Agents and presets already running in Debian keep going.") },
            confirmButton = { TextButton({ confirmForget = false; navigate(onForget) }) { Text("Forget", color = inks.danger) } },
            dismissButton = { TextButton({ confirmForget = false }) { Text("Cancel") } },
        )
    }

    val title = when (route) {
        Route.Thread -> thread?.title ?: "Session"
        Route.Run -> run?.label ?: "Preset"
        Route.Files -> if (fileOwner == null) workspace?.label ?: "Files" else "Worktree files"
        Route.Review -> "Review changes"
        else -> "New session"
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        drawerContent = {
            ModalDrawerSheet(
                drawerState = drawer,
                modifier = Modifier.widthIn(max = 320.dp),
                drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
                drawerContainerColor = inks.sheet,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                SessionDrawer(
                    workspaces, threads.items, runs.items, current = routeId.takeIf { route == Route.Thread || route == Route.Run },
                    online = online, port = port,
                    onNew = { go(Route.Home) },
                    onThread = ::openThread, onRun = ::openRun,
                    onProjectFiles = { project -> openFiles(null, false, project) },
                    onForget = { scope.launch { drawer.close() }; confirmForget = true },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(Glyph.Menu, "Open sessions", enabled = !fileBusy) { scope.launch { drawer.open() } }
                Text(title, Modifier.padding(start = 4.dp).weight(1f), style = MaterialTheme.typography.titleMedium,
                    color = inks.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (route != Route.Home) {
                    GlyphButton(Glyph.Plus, "New session", enabled = !fileBusy, tint = inks.accent) { go(Route.Home) }
                }
            }
            offline?.let {
                PageWidth { Notice(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), error = true) }
            }
            PageWidth(Modifier.weight(1f)) {
                when (route) {
                    Route.Thread -> {
                        val id = routeId
                        if (id == null) {
                            EmptyNote("Session not found", "Start a new one from the menu.", Modifier.padding(20.dp))
                        } else screenState.SaveableStateProvider("thread:$id") {
                            ThreadScreen(api, id, thread, providers, handlesBack = drawer.isClosed,
                                onBack = { go(Route.Home) }, onThread = ::upsert,
                                onReview = { navigate { route = Route.Review } },
                                onFiles = { openFiles(id, true) })
                        }
                    }
                    Route.Review -> {
                        val target = thread
                        if (target == null) Text("Loading…", Modifier.padding(20.dp), color = inks.faint)
                        else ReviewScreen(api, target, handlesBack = drawer.isClosed,
                            onBack = { navigate { route = Route.Thread } })
                    }
                    Route.Run -> {
                        val target = run
                        if (target == null) {
                            EmptyNote("Run not found", "The daemon may have restarted.", Modifier.padding(20.dp),
                                action = "New session") { go(Route.Home) }
                        } else screenState.SaveableStateProvider("run:${target.id}") {
                            RunScreen(api, target, workspaces.firstOrNull { it.id == target.workspace }?.label, online,
                                submitting, actionError, handlesBack = drawer.isClosed, onBack = { go(Route.Home) },
                                onStop = ::stopRun, onFiles = { openFiles(target.id, false) })
                        }
                    }
                    Route.Files -> {
                        val current = workspace
                        if (current == null) {
                            EmptyNote("No project loaded", "Connect to Debian to open your files.", Modifier.padding(20.dp))
                        } else {
                            val owner = fileOwner
                            val fileScope = when {
                                owner == null -> FileScope(current.id)
                                fileIsThread -> FileScope(current.id, thread = owner)
                                else -> FileScope(current.id, run = owner)
                            }
                            val branch = if (fileIsThread) threads.items.firstOrNull { it.id == owner }?.branch
                                else runs.items.firstOrNull { it.id == owner }?.branch
                            screenState.SaveableStateProvider("files:${current.id}:${owner.orEmpty()}") {
                                FilesScreen(api, fileScope, if (owner == null) current.label else title, branch,
                                    handlesBack = drawer.isClosed, onDirtyChange = { dirty = it },
                                    onBusyChange = { fileBusy = it }, onHome = {
                                        navigate {
                                            route = when {
                                                owner == null -> Route.Home
                                                fileIsThread -> Route.Thread
                                                else -> Route.Run
                                            }
                                            routeId = owner
                                        }
                                    })
                            }
                        }
                    }
                    else -> screenState.SaveableStateProvider("home") {
                        NewSessionScreen(
                            workspace, workspaces, providers, catalog?.defaultProvider,
                            recent = threads.items.filter { it.workspace == workspace?.id },
                            online = online, busy = submitting, error = actionError,
                            onWorkspace = { workspaceId = it.id },
                            onStart = ::startThread, onPreset = ::launchPreset, onOpen = ::openThread,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionDrawer(
    workspaces: List<Workspace>, threads: List<AgentThread>, runs: List<Run>, current: String?,
    online: Boolean, port: Int, onNew: () -> Unit, onThread: (AgentThread) -> Unit, onRun: (Run) -> Unit,
    onProjectFiles: (Workspace) -> Unit, onForget: () -> Unit,
) {
    val threadsByProject = threads.groupBy { it.workspace }
    val runsByProject = runs.groupBy { it.workspace }
    Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
        Row(Modifier.padding(start = 10.dp, top = 22.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TsunMark(Modifier.size(24.dp))
            Text("Tsun", style = MaterialTheme.typography.headlineSmall, color = inks.ink)
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, inks.rule, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onNew).heightIn(min = 46.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LineIcon(Glyph.Plus, color = inks.accent, size = 17)
            Text("New session", style = MaterialTheme.typography.titleSmall, color = inks.ink)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
            if (workspaces.isEmpty()) item {
                Text("No projects loaded", Modifier.padding(start = 12.dp, top = 16.dp), color = inks.faint)
            }
            for (project in workspaces) {
                val projectThreads = threadsByProject[project.id].orEmpty()
                val projectRuns = runsByProject[project.id].orEmpty()
                item(key = "project:${project.id}") {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow(project.label.uppercase(), Modifier.padding(start = 12.dp).weight(1f))
                        GlyphButton(Glyph.Folder, "Browse ${project.label}", tint = inks.quiet) { onProjectFiles(project) }
                    }
                }
                if (projectThreads.isEmpty() && projectRuns.isEmpty()) item(key = "empty:${project.id}") {
                    Text("No sessions yet", Modifier.padding(start = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = inks.faint)
                }
                items(projectThreads, key = { "thread:${it.id}" }) { thread ->
                    ThreadRow(thread, selected = thread.id == current) { onThread(thread) }
                }
                items(projectRuns, key = { "run:${it.id}" }) { run ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (run.id == current) inks.accentWash else Color.Transparent)
                        .clickable(role = Role.Button) { onRun(run) }.heightIn(min = 44.dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LineIcon(Glyph.Terminal, size = 15, color = inks.faint)
                        Text(run.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = inks.quiet,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        RunState(run)
                    }
                }
            }
        }
        HorizontalDivider(color = inks.rule)
        Row(Modifier.padding(start = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(if (online) inks.sage else inks.danger)
            Text(if (online) "Debian · localhost:$port" else "Debian · disconnected", color = inks.faint,
                style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onForget, modifier = Modifier.padding(bottom = 6.dp)) {
            Text("Forget pairing", color = inks.quiet)
        }
    }
}
