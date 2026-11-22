package com.agrotech.oviapp.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.agrotech.oviapp.android.ui.theme.OviTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OviTheme {
                Surface {
                    Text("BaaPP (OviApp) — Captura Offline en Corral", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}
