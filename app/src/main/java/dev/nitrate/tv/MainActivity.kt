package dev.nitrate.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.nitrate.tv.ui.NitrateNavHost
import dev.nitrate.tv.ui.theme.NitrateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as NitrateApp).container
        setContent {
            NitrateTheme {
                NitrateNavHost(container)
            }
        }
    }
}
