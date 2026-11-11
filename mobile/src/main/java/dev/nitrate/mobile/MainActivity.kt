package dev.nitrate.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.nitrate.mobile.ui.MobileApp
import dev.nitrate.mobile.ui.NitrateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as NitrateApp).container
        setContent {
            NitrateTheme {
                MobileApp(container)
            }
        }
    }
}
