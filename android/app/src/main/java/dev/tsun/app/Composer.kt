package dev.tsun.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal const val MessageLimit = 32_000

/** The daemon's slash commands, handled in Agents::reply before the message reaches the model. */
internal data class SlashCommand(val command: String, val hint: String, val description: String) {
    val label: String get() = command
}

internal val SlashCommands = listOf(
    SlashCommand("/effort",
        "/effort <level>",
        "Set this session's reasoning effort: minimal, low, medium, high, xhigh, or max. " +
            "/effort default resets it; /effort alone shows the current level."),
    SlashCommand("/compact",
        "/compact <focus>",
        "Summarize the older turns of this session and keep the newest ones word for word, " +
            "so a long conversation keeps working. Anything after the command steers the summary."),
)

/**
 * Keep send/stop next to the draft and give the settings their own row, so they
 * stay visible and in the same place when the agent starts or stops working.
 */
@Composable
internal fun Composer(
    draft: TextFieldState,
    placeholder: String,
    mode: Mode,
    onMode: (Mode) -> Unit,
    providers: List<ModelProvider>,
    provider: ModelProvider?,
    onProvider: (ModelProvider) -> Unit,
    working: Boolean,
    canSend: Boolean,
    busy: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    canSteer: Boolean = false,
    canStop: Boolean = working,
    tools: @Composable RowScope.() -> Unit = {},
    commands: List<SlashCommand> = emptyList(),
) {
    var dismissed by remember(draft) { mutableStateOf("") }
    val suggestions = remember(draft, commands) {
        derivedStateOf { matchingCommands(draft, commands, dismissed) }
    }
    var selected by remember(draft) { mutableStateOf(0) }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = inks.raised,
        border = BorderStroke(1.dp, inks.rule)) {
        Column(Modifier.padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
            if (suggestions.value.isNotEmpty()) {
                LaunchedEffect(suggestions.value) { selected = 0 }
                CommandSuggestions(draft, suggestions.value, selected,
                    onSelect = { selected = it }, onDismiss = { dismissed = draft.text.toString() })
            }
            val empty by remember(draft) { derivedStateOf { draft.text.isEmpty() } }
            val hasText by remember(draft) { derivedStateOf { draft.text.isNotBlank() } }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicTextField(
                    state = draft,
                    inputTransformation = remember { InputTransformation.maxLength(MessageLimit) },
                    modifier = Modifier.weight(1f)
                        .onFocusChanged { if (it.hasFocus) dismissed = "" }
                        .onKeyEvent { event ->
                            val matches = suggestions.value
                            commandKeyevent(event, matches,
                                onMove = { selected = (selected + it).coerceIn(0, matches.lastIndex) },
                                onDismiss = { dismissed = draft.text.toString() },
                                onComplete = { completeCommand(draft, matches[selected.coerceAtMost(matches.lastIndex)]) })
                        }
                        .semantics { contentDescription = "Message" },
                    enabled = !busy,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = inks.ink),
                    cursorBrush = SolidColor(inks.accent),
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 8),
                    keyboardOptions = remember { KeyboardOptions(capitalization = KeyboardCapitalization.Sentences) },
                    decorator = { input ->
                        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 11.dp),
                            contentAlignment = Alignment.TopStart) {
                            if (empty) Text(placeholder, color = inks.faint, style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            input()
                        }
                    },
                )
                if (canStop) RoundAction(Glyph.Stop, if (working) "Stop the agent" else "Stop background work",
                    enabled = !busy, filled = false, onClick = onStop)
                if (!working || canSteer) {
                    RoundAction(Glyph.Up, if (working) "Steer the agent" else "Send",
                        enabled = canSend && !busy && hasText, filled = true) {
                        onSend(draft.text.toString().trim())
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ModePicker(mode, onMode, enabled = !busy)
                if (providers.isNotEmpty()) {
                    ModelPicker(providers, provider, onProvider, enabled = !working && !busy, modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                tools()
            }
        }
    }
}

@Composable
private fun ModePicker(mode: Mode, onMode: (Mode) -> Unit, enabled: Boolean) {
    var open by remember(enabled) { mutableStateOf(false) }
    Box(Modifier.widthIn(max = 144.dp)) {
        PickerButton(mode.label, "Permissions: ${mode.label}", enabled,
            color = if (mode == Mode.Full) inks.amber else inks.quiet, glyph = Glyph.Shield) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = inks.raised) {
            for (choice in Mode.entries) {
                DropdownMenuItem(
                    text = {
                        Column(Modifier.padding(vertical = 2.dp)) {
                            Text(choice.label, style = MaterialTheme.typography.titleSmall,
                                color = if (choice == mode) inks.accent else inks.ink)
                            Text(choice.hint, style = MaterialTheme.typography.bodySmall, color = inks.quiet)
                        }
                    },
                    onClick = { open = false; onMode(choice) },
                    trailingIcon = { if (choice == mode) LineIcon(Glyph.Check, color = inks.accent, size = 16) },
                )
            }
        }
    }
}

@Composable
private fun ModelPicker(providers: List<ModelProvider>, provider: ModelProvider?, onProvider: (ModelProvider) -> Unit,
                        enabled: Boolean, modifier: Modifier = Modifier) {
    var open by remember(enabled) { mutableStateOf(false) }
    val label = provider?.label ?: "Model"
    Box(modifier) {
        PickerButton(label, provider?.let { "Model: ${it.label}" } ?: "Choose a model", enabled,
            color = if (provider?.enabled == false) inks.danger else inks.quiet) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = inks.raised) {
            for (choice in providers) {
                DropdownMenuItem(
                    text = {
                        Column(Modifier.padding(vertical = 2.dp)) {
                            Text(choice.label, style = MaterialTheme.typography.titleSmall,
                                color = if (!choice.enabled) inks.faint else if (choice.id == provider?.id) inks.accent else inks.ink)
                            Text(choice.reason ?: "${choice.model} · ${if (choice.kind == "anthropic") "Anthropic API" else "OpenAI-compatible"}",
                                style = MaterialTheme.typography.bodySmall, color = inks.quiet, maxLines = 2)
                        }
                    },
                    enabled = choice.enabled,
                    onClick = { open = false; onProvider(choice) },
                    trailingIcon = { if (choice.id == provider?.id) LineIcon(Glyph.Check, color = inks.accent, size = 16) },
                )
            }
        }
    }
}

@Composable
private fun PickerButton(label: String, description: String, enabled: Boolean, color: Color,
                         glyph: Glyph? = null, onClick: () -> Unit) {
    val tint = if (enabled) color else inks.faint
    TextButton(onClick, Modifier.semantics { contentDescription = description }, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (glyph != null) LineIcon(glyph, color = tint, size = 14)
            Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium,
                color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LineIcon(Glyph.Down, color = tint, size = 12)
        }
    }
}

// ---- Slash command autocomplete ----------------------------------------------

/**
 * The commands that match the draft right now. Suggestions appear only when the text starts
 * with "/" (plus optional leading whitespace) and is still on its first line, so an ordinary
 * sentence that happens to contain a slash never triggers the panel. A completion the user
 * dismissed stays dismissed until they type or focus again.
 */
private fun matchingCommands(draft: TextFieldState, commands: List<SlashCommand>, dismissed: String): List<SlashCommand> {
    if (commands.isEmpty()) return emptyList()
    val text = draft.text.toString()
    if (!text.startsWith("/")) return emptyList()
    // A command is a single line; once the draft continues past it, the panel would be noise.
    if ('\n' in text) return emptyList()
    if (text == dismissed) return emptyList()
    return commands.filter { it.command.startsWith(text, ignoreCase = true) }
}

/** Completes the draft with [command], keeping any arguments the user already typed. */
private fun completeCommand(draft: TextFieldState, command: SlashCommand) {
    val text = draft.text.toString()
    val args = text.trim().removePrefix("/").let { token ->
        if (token.isEmpty()) "" else token.dropWhile { it != ' ' }.trim()
    }
    draft.edit {
        val completed = command.command + if (args.isEmpty()) " " else " $args"
        replace(0, length, completed)
        selection = TextRange(completed.length)
    }
}

/** Arrow keys and Tab move through the panel; Enter or Tab completes; Escape dismisses. */
private fun commandKeyevent(event: KeyEvent, suggestions: List<SlashCommand>,
                            onMove: (Int) -> Unit, onDismiss: () -> Unit, onComplete: () -> Unit): Boolean {
    if (suggestions.isEmpty() || event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.DirectionUp -> { onMove(-1); true }
        Key.DirectionDown -> { onMove(+1); true }
        Key.Tab, Key.Enter, Key.NumPadEnter -> { onComplete(); true }
        Key.Escape -> { onDismiss(); true }
        else -> false
    }
}

/** The panel of matching commands above the input. Tap a row to complete it. */
@Composable
private fun CommandSuggestions(draft: TextFieldState, suggestions: List<SlashCommand>, selected: Int,
                               onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())
        .padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((index, suggestion) in suggestions.withIndex()) {
            val highlighted = index == selected
            Row(Modifier.fillMaxWidth().clip(Pill)
                .background(if (highlighted) inks.wash else Color.Transparent)
                .clickable {
                    onSelect(index)
                    completeCommand(draft, suggestion)
                    onDismiss()
                }
                .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(suggestion.label, style = SmallCode, color = inks.accent)
                Column {
                    Text(suggestion.hint, style = SmallCode, color = inks.quiet, maxLines = 1)
                    Text(suggestion.description, style = MaterialTheme.typography.bodySmall, color = inks.faint,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
