package dev.tsun.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
        val savedMode = if (saved == null) Mode.Edits else Mode.of(prefs.getString("mode", "edits").orEmpty())
        val savedRootLaunch = saved != null && prefs.getBoolean("root_launch", false)
        setContent {
            TsunTheme {
                var pairing by remember { mutableStateOf(saved) }
                var overview by remember { mutableStateOf<Overview?>(null) }
                var preferredMode by remember { mutableStateOf(savedMode) }
                var rootLaunch by remember { mutableStateOf(savedRootLaunch) }
                val palette = inks
                Surface(Modifier.fillMaxSize(), color = palette.paper, contentColor = palette.ink) {
                    Box(Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                        val local = pairing
                        if (local == null) {
                            PairingScreen { newPairing, loaded, viaRoot ->
                                prefs.edit().clear().putInt("port", newPairing.port).putString("key", newPairing.key)
                                    .putString("pin", newPairing.fingerprint).putBoolean("root_launch", viaRoot).apply()
                                overview = loaded
                                rootLaunch = viaRoot
                                pairing = newPairing
                            }
                        } else key(local.port, local.fingerprint) {
                            val api = remember(local) { DaemonApi(local) }
                            val startDebian = remember(local, api, rootLaunch) {
                                if (rootLaunch) suspend { RootLauncher.start(local, api) } else null
                            }
                            Workbench(api, local.port, overview, preferredMode, startDebian, onMode = { mode ->
                                preferredMode = mode
                                prefs.edit().putString("mode", mode.wire).apply()
                            }) {
                                prefs.edit().clear().apply()
                                preferredMode = Mode.Edits
                                rootLaunch = false
                                overview = null
                                pairing = null
                            }
                        }
                    }
                }
            }
        }
    }
}
