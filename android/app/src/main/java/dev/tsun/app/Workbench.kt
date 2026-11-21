package dev.tsun.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Workbench(api: DaemonApi, port: Int, initialWorkspaces: List<Workspace>, onForget: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var workspaces by remember(api) { mutableStateOf(initialWorkspaces) }
    var runs by remember(api) { mutableStateOf(RunList(emptyList(), 2)) }
    var offline by remember(api) { mutableStateOf<String?>(null) }
    var online by remember(api) { mutableStateOf(initialWorkspaces.isNotEmpty()) }
    var refresh by remember { mutableIntStateOf(0) }
    var workspaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var runId by rememberSaveable { mutableStateOf<String?>(null) }
    var showFiles by rememberSaveable { mutableStateOf(false) }
    val screenState = rememberSaveableStateHolder()
    var dirty by remember { mutableStateOf(false) }
    var fileBusy by remember { mutableStateOf(false) }
    var discardTo by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmForget by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val workspace = workspaces.firstOrNull { it.id == workspaceId } ?: workspaces.firstOrNull()
    val selected = runs.items.firstOrNull { it.id == runId }

    LaunchedEffect(api, lifecycle, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var readCatalog = true
            while (true) {
                try {
                    if (readCatalog) workspaces = api.catalog()
                    runs = api.runs()
                    online = true
                    offline = null
                    readCatalog = false
                } catch (e: Exception) {
                    offline = e.userMessage()
                    online = false
                    readCatalog = true
                }
                delay(if (runs.items.any { it.active }) 850L else 2300L)
            }
        }
    }

    fun navigate(change: () -> Unit) {
        if (fileBusy) return
        if (drawer.isOpen) scope.launch { drawer.close() }
        if (dirty) discardTo = change else change()
    }

    fun selectRun(run: Run?) = navigate {
        showFiles = false
        runId = run?.id
        if (run != null) workspaceId = run.workspace
        actionError = null
    }

    fun launchPreset(preset: Preset) {
        val target = workspace ?: return
        if (submitting) return
        submitting = true
        actionError = null
        scope.launch {
            try {
                val run = api.launch(target.id, preset.id)
                runs = runs.copy(items = (listOf(run) + runs.items.filterNot { it.id == run.id }).take(32))
                selectRun(run)
            } catch (e: Exception) { actionError = e.userMessage() }
            finally { submitting = false; refresh++ }
        }
    }

    fun stopRun(run: Run) {
        if (submitting) return
        submitting = true
        actionError = null
        scope.launch {
            try {
                val updated = api.stop(run.id)
                runs = runs.copy(items = runs.items.map { if (it.id == updated.id) updated else it })
            } catch (e: Exception) { actionError = e.userMessage() }
            finally { submitting = false; refresh++ }
        }
    }

    discardTo?.let { change ->
        AlertDialog(
            onDismissRequest = { discardTo = null },
            title = { Text("Discard unsaved edits?") },
            text = { Text("The file in Debian will keep its last saved version.") },
            confirmButton = { TextButton({ discardTo = null; dirty = false; change() }) { Text("Discard") } },
            dismissButton = { TextButton({ discardTo = null }) { Text("Keep editing") } },
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this pairing?") },
            text = { Text("This removes the key from the app. Running jobs in Debian won't stop.") },
            confirmButton = { TextButton({ confirmForget = false; navigate(onForget) }) { Text("Forget") } },
            dismissButton = { TextButton({ confirmForget = false }) { Text("Cancel") } },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        scrimColor = Color.Black.copy(alpha = 0.33f),
        drawerContent = {
            ModalDrawerSheet(
                drawerState = drawer,
                modifier = Modifier.widthIn(max = 318.dp),
                drawerShape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
                drawerContainerColor = inks.sheet,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                TaskDrawer(workspaces, workspace, runs.items, selected, showFiles, online, port,
                    onNew = { selectRun(null) },
                    onWorkspace = { chosen ->
                        if (!showFiles || workspace?.id != chosen.id) navigate {
                            if (workspace?.id != chosen.id) runId = null
                            workspaceId = chosen.id
                            showFiles = true
                            actionError = null
                        } else scope.launch { drawer.close() }
                    },
                    onRun = { chosen -> selectRun(chosen) },
                    onForget = { scope.launch { drawer.close() }; confirmForget = true },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 55.dp).padding(start = 5.dp, end = 13.dp),
                verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(Glyph.Menu, "Open workspace and run drawer", enabled = !fileBusy) {
                    scope.launch { drawer.open() }
                }
                TsunMark(Modifier.size(20.dp))
                Text("Tsun", Modifier.padding(start = 7.dp).weight(1f),
                    style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                TextButton(onClick = { navigate { showFiles = !showFiles; actionError = null } },
                    enabled = workspace != null && !fileBusy) {
                    LineIcon(if (showFiles) Glyph.Runs else Glyph.Folder, color = inks.pine, size = 17)
                    Spacer(Modifier.width(7.dp))
                    Text(if (showFiles) "Runs" else "Files")
                }
            }
            HorizontalDivider(color = inks.rule)
            offline?.let {
                PageWidth {
                    Notice(it, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 9.dp), error = true)
                }
            }
            PageWidth(Modifier.weight(1f)) {
                val current = workspace
                if (showFiles && current != null) {
                    screenState.SaveableStateProvider("files:${current.id}") {
                        FilesScreen(api, current, handlesBack = drawer.isClosed,
                            onDirtyChange = { dirty = it }, onBusyChange = { fileBusy = it },
                            onHome = { navigate { showFiles = false } })
                    }
                } else {
                    screenState.SaveableStateProvider("runs") {
                        RunsScreen(api, workspace, workspaces, runs, selected, online, submitting, actionError,
                            handlesBack = drawer.isClosed, onSelect = { selectRun(it) }, onNew = { selectRun(null) },
                            onLaunch = ::launchPreset, onStop = ::stopRun)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskDrawer(
    workspaces: List<Workspace>, workspace: Workspace?, runs: List<Run>, selected: Run?, files: Boolean,
    online: Boolean, port: Int, onNew: () -> Unit, onWorkspace: (Workspace) -> Unit,
    onRun: (Run) -> Unit, onForget: () -> Unit,
) {
    Column(Modifier.fillMaxHeight().padding(horizontal = 15.dp)) {
        Row(Modifier.padding(start = 11.dp, top = 25.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            TsunMark(Modifier.size(29.dp))
            Column {
                Text("Tsun", style = MaterialTheme.typography.headlineMedium)
                Eyebrow("LOCAL WORKBENCH")
            }
        }
        DrawerRow("New run", Glyph.Plus, false, onNew)
        HorizontalDivider(Modifier.padding(top = 9.dp), color = inks.rule)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { Eyebrow("WORKSPACES", Modifier.padding(start = 11.dp, top = 19.dp, bottom = 6.dp)) }
            if (workspaces.isEmpty()) item {
                Text("No workspaces loaded", Modifier.padding(start = 11.dp, top = 4.dp), color = inks.quiet)
            }
            items(workspaces, key = { "workspace:${it.id}" }) { item ->
                DrawerRow(item.label, Glyph.Folder, files && workspace?.id == item.id, { onWorkspace(item) })
            }
            item { Eyebrow("RECENT RUNS", Modifier.padding(start = 11.dp, top = 22.dp, bottom = 6.dp)) }
            if (runs.isEmpty()) item {
                Text("Nothing has run yet", Modifier.padding(start = 11.dp, top = 4.dp), color = inks.quiet)
            }
            items(runs, key = { "run:${it.id}" }) { run ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                    .background(if (!files && selected?.id == run.id) inks.moss else Color.Transparent)
                    .clickable(role = Role.Button) { onRun(run) }
                    .heightIn(min = 48.dp).padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusDot(if (run.state == "failed") inks.rust else if (run.active) inks.pine else inks.quiet)
                    Column {
                        Text(run.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(workspaces.firstOrNull { it.id == run.workspace }?.label ?: run.workspace,
                            style = MaterialTheme.typography.bodySmall, color = inks.quiet, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        HorizontalDivider(color = inks.rule)
        Row(Modifier.padding(start = 11.dp, top = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (online) inks.pine else inks.rust)
            Text(if (online) "Debian · localhost:$port" else "Debian · disconnected",
                Modifier.padding(start = 8.dp), color = inks.quiet, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onForget, modifier = Modifier.padding(bottom = 5.dp)) { Text("Forget pairing") }
    }
}

@Composable
private fun DrawerRow(label: String, glyph: Glyph, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
        .background(if (selected) inks.moss else Color.Transparent).clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 46.dp).padding(horizontal = 11.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LineIcon(glyph, color = if (selected || glyph == Glyph.Plus) inks.pine else inks.quiet, size = 18)
        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
