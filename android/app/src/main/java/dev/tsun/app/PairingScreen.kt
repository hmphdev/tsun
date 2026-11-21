package dev.tsun.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun PairingScreen(onPair: (Pairing, List<Workspace>) -> Unit) {
    // Don't put the unsubmitted code in Activity saved state.
    var code by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val pairing = remember(code) { Pairing.parse(code) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 25.dp, vertical = 25.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                TsunMark(Modifier.size(23.dp))
                Text("Tsun", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(39.dp))
            Eyebrow("LOCAL WORKBENCH", color = inks.pine)
            Spacer(Modifier.height(9.dp))
            Text("All on this phone.", style = MaterialTheme.typography.headlineLarge)
            Text("Your files and tools stay in Debian. Pair Tsun with the daemon to work with them here.",
                Modifier.padding(top = 7.dp), color = inks.quiet, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(26.dp))
            Surface(Modifier.fillMaxWidth(), shape = Corner, color = inks.sheet, border = BorderStroke(1.dp, inks.rule)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusDot(inks.pine)
                        Text("Pair with Debian", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("127.0.0.1", style = CodeType, color = inks.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider(color = inks.rule)
                    OutlinedTextField(code, { code = it.take(200); error = null }, Modifier.fillMaxWidth(),
                        label = { Text("Pairing code") }, singleLine = true, shape = Corner, enabled = !connecting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = { Text("Paste the private code printed by mobile-harness --pairing-code.") })
                    error?.let { Notice(it, Modifier.fillMaxWidth(), error = true) }
                    Button(
                        onClick = {
                            val candidate = pairing ?: return@Button
                            scope.launch {
                                error = null
                                connecting = true
                                try { onPair(candidate, DaemonApi(candidate).catalog()) }
                                catch (e: Exception) { error = e.userMessage() }
                                finally { connecting = false }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(49.dp), enabled = pairing != null && !connecting,
                        shape = Corner,
                    ) {
                        if (connecting) CircularProgressIndicator(Modifier.padding(end = 9.dp).size(16.dp), strokeWidth = 2.dp)
                        Text(if (connecting) "Pairing…" else "Connect to Debian")
                    }
                }
            }
        }
    }
}
