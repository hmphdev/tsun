package dev.tsun.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SkillPicker(api: DaemonApi, workspace: String, thread: String?, enabled: Boolean,
                         onPick: (SkillInfo) -> Unit) {
    var open by remember(api, workspace, thread) { mutableStateOf(false) }
    Chip("Skills", enabled = enabled, trailing = Glyph.Down, onClick = { open = true })
    if (!open) return

    var catalog by remember { mutableStateOf<SkillCatalog?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<SkillInfo?>(null) }
    LaunchedEffect(api, workspace, thread) {
        try {
            catalog = api.skills(workspace, thread)
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }
    val current = catalog
    val skill = selected
    if (skill != null) {
        SkillPreview(api, workspace, thread, skill, enabled, onBack = { selected = null }) {
            open = false
            onPick(skill)
        }
        return
    }
    AlertDialog(
        onDismissRequest = { open = false },
        containerColor = inks.raised,
        title = { Text("Skills") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    error != null -> Notice(error.orEmpty(), error = true)
                    current == null -> Text("Looking for skills in Debian…")
                    else -> {
                        if (current.skills.isNotEmpty()) {
                            Text("Open a skill to review it before adding it to your draft.")
                            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                                label = { Text("Find a skill") })
                        }
                        val matches = current.skills.filter {
                            it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
                        }
                        LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (current.skills.isEmpty()) item {
                                Text("No skills found. Add a skill folder with SKILL.md under .agents/skills in your project.")
                            } else if (matches.isEmpty()) item { Text("No matching skills.") }
                            items(matches, key = { it.name }) { skill ->
                                TextButton(onClick = { selected = skill },
                                    modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(skill.name, color = inks.accent, style = MaterialTheme.typography.titleSmall)
                                        Text(skill.description, color = inks.ink, style = MaterialTheme.typography.bodySmall)
                                        Text(skill.path + if (skill.manual) " · Manual only" else "",
                                            color = inks.quiet, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            if (current.warnings.isNotEmpty()) {
                                item { Text("Discovery warnings", style = MaterialTheme.typography.titleSmall) }
                                items(current.warnings) { Text(it, color = inks.quiet, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
    )
}

@Composable
private fun SkillPreview(api: DaemonApi, workspace: String, thread: String?, skill: SkillInfo, enabled: Boolean,
                         onBack: () -> Unit, onUse: () -> Unit) {
    var document by remember(skill) { mutableStateOf<SkillDocument?>(null) }
    var error by remember(skill) { mutableStateOf<String?>(null) }
    LaunchedEffect(api, workspace, thread, skill.name) {
        try {
            document = api.skillDocument(workspace, thread, skill.name)
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }
    val loaded = document
    AlertDialog(
        onDismissRequest = onBack,
        containerColor = inks.raised,
        title = { Text(skill.name) },
        text = {
            when {
                error != null -> Notice(error.orEmpty(), error = true)
                loaded == null -> Text("Reading SKILL.md…")
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(loaded.path, color = inks.quiet, style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Text(loaded.text, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall, color = inks.ink)
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onBack) { Text("Back") } },
        confirmButton = { TextButton(onClick = onUse, enabled = enabled && loaded != null) { Text("Add to draft") } },
    )
}
