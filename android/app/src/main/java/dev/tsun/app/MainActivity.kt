package dev.tsun.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("local_pairing", Context.MODE_PRIVATE)
        val saved = Pairing.fromFields(prefs.getInt("port", 8787), prefs.getString("key", "").orEmpty(),
            prefs.getString("pin", "").orEmpty())
        setContent {
            TsunTheme {
                var pairing by remember { mutableStateOf(saved) }
                var workspaces by remember { mutableStateOf(emptyList<Workspace>()) }
                Surface(Modifier.fillMaxSize(), color = inks.paper, contentColor = inks.ink) {
                    Box(Modifier.fillMaxSize().background(inks.paper).safeDrawingPadding()) {
                        val local = pairing
                        if (local == null) {
                            PairingScreen { newPairing, catalog ->
                                prefs.edit().putInt("port", newPairing.port).putString("key", newPairing.key)
                                    .putString("pin", newPairing.fingerprint).apply()
                                workspaces = catalog
                                pairing = newPairing
                            }
                        } else key(local.port) {
                            val api = remember(local) { DaemonApi(local) }
                            Workbench(api, local.port, workspaces) {
                                prefs.edit().clear().apply()
                                workspaces = emptyList()
                                pairing = null
                            }
                        }
                    }
                }
            }
        }
    }
}
