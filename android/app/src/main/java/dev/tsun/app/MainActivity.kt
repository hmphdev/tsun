package dev.tsun.app

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val prefs = getSharedPreferences("local_pairing", Context.MODE_PRIVATE)
        val saved = Pairing.fromFields(prefs.getInt("port", 8787), prefs.getString("key", "").orEmpty(),
            prefs.getString("pin", "").orEmpty())
        setContent {
            TsunTheme {
                var pairing by remember { mutableStateOf(saved) }
                var catalog by remember { mutableStateOf<Catalog?>(null) }
                val palette = inks
                Surface(Modifier.fillMaxSize(), color = palette.paper, contentColor = palette.ink) {
                    Box(Modifier.fillMaxSize().emberBackground(palette)
                        .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).imePadding()) {
                        val local = pairing
                        if (local == null) {
                            PairingScreen { newPairing, loaded ->
                                prefs.edit().putInt("port", newPairing.port).putString("key", newPairing.key)
                                    .putString("pin", newPairing.fingerprint).apply()
                                catalog = loaded
                                pairing = newPairing
                            }
                        } else key(local.port) {
                            val api = remember(local) { DaemonApi(local) }
                            Workbench(api, local.port, catalog) {
                                prefs.edit().clear().apply()
                                catalog = null
                                pairing = null
                            }
                        }
                    }
                }
            }
        }
    }
}
