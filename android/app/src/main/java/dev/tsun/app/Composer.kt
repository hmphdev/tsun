package dev.tsun.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal const val MessageLimit = 32_000

/**
 * The rounded message box at the bottom of every conversation: text first, then a
 * row of quiet chips (permission mode, model) and one round action on the right.
 */
@Composable
internal fun Composer(
    text: String,
    onText: (String) -> Unit,
    placeholder: String,
    mode: Mode,
    onMode: (Mode) -> Unit,
    providers: List<ModelProvider>,
    provider: ModelProvider?,
    onProvider: (ModelProvider) -> Unit,
    working: Boolean,
    canSend: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = inks.raised,
        border = BorderStroke(1.dp, inks.rule)) {
        Column(Modifier.padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
            BasicTextField(
                value = text,
                onValueChange = { if (it.length <= MessageLimit) onText(it) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Message" },
                enabled = !busy,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = inks.ink),
                cursorBrush = SolidColor(inks.accent),
                maxLines = 8,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { input ->
                    Box(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 12.dp, vertical = 11.dp),
                        contentAlignment = Alignment.TopStart) {
                        if (text.isEmpty()) Text(placeholder, color = inks.faint, style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        input()
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    leading()
                    ModePicker(mode, onMode)
                    if (providers.isNotEmpty()) ModelPicker(providers, provider, onProvider, enabled = !working)
                }
                Spacer(Modifier.width(8.dp))
                if (working) RoundAction(Glyph.Stop, "Stop the agent", enabled = !busy, filled = false, onClick = onStop)
                else RoundAction(Glyph.Up, "Send", enabled = canSend && !busy && text.isNotBlank(), filled = true, onClick = onSend)
            }
        }
    }
}

@Composable
private fun ModePicker(mode: Mode, onMode: (Mode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip(mode.label, glyph = Glyph.Shield, tint = if (mode == Mode.Full) inks.amber else inks.quiet,
            trailing = Glyph.Down, onClick = { open = true })
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
                        enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip(provider?.label ?: "Choose a model", enabled = enabled, trailing = Glyph.Down,
            tint = if (provider?.enabled == false) inks.danger else inks.quiet, onClick = { open = true })
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
