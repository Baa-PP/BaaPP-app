package com.agrotech.oviapp.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agrotech.oviapp.android.notifications.WeaningNotifications
import com.agrotech.oviapp.notifications.WeaningAlert

class MainActivity : ComponentActivity() {
    private val openedAlert = mutableStateOf<WeaningAlert?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* sin permiso no se muestran avisos */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedAlert.value = WeaningNotifications.alertFrom(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme {
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        Text("BaaPP (OviApp) — Captura Offline en Corral")
                        openedAlert.value?.let { alert ->
                            // Hasta APP-16 no hay pantalla de destetes: se muestra qué animal abrió el aviso.
                            val folio = alert.folioInterno ?: stringResource(R.string.destete_sin_folio)
                            Text(stringResource(R.string.destete_abierto, folio))
                            Text(stringResource(R.string.destete_pantalla_pendiente))
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        WeaningNotifications.alertFrom(intent)?.let { alert ->
            // Sin setIntent, al recrear la Activity (rotación) onCreate volvería a leer el aviso anterior.
            setIntent(intent)
            openedAlert.value = alert
        }
    }
}
