package com.alban.ebike.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.alban.ebike.firmware.FirmwareUpdateUi
import com.alban.ebike.firmware.UpdatePhase
import com.alban.ebike.model.RideUiState

@Composable
fun Esp32UpdateButton(connected: Boolean, firmware: FirmwareUpdateUi, onClick: () -> Unit, name: String = "ESP32") {
    val transition = rememberInfiniteTransition(label = "ESP32 update")
    val pulse by transition.animateFloat(1f, .3f,
        infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "update blink")
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text(if (firmware.available) "● $name · MAJ" else "●  $name",
            modifier = Modifier.alpha(if (firmware.available && !firmware.busy) pulse else 1f),
            color = if (firmware.available) Color(0xFFFFCB69) else if (connected) Color(0xFF69E3F5) else Color.Gray,
            fontSize = 11.sp, fontWeight = if (connected || firmware.available) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun FirmwareUpdateDialog(firmware: FirmwareUpdateUi, ride: RideUiState, onDismiss: () -> Unit,
    onInstall: () -> Unit, onAssociate: () -> Unit) {
    val stages = listOf(
        UpdatePhase.WAKING to "1. Contrôles de démarrage",
        UpdatePhase.DOWNLOADING to "2. Téléchargement GitHub et contrôle SHA-256",
        UpdatePhase.PREPARING to "3. Préparation de l’installation",
        UpdatePhase.TRANSFERRING to "4. Transfert Bluetooth et écriture flash",
        UpdatePhase.VERIFYING to "5. Vérification intégrale sur l’ESP32",
        UpdatePhase.RESTARTING to "6. Redémarrage de l’ESP32",
        UpdatePhase.CONFIRMING to "7. Reconnexion et confirmation du firmware",
    )
    AlertDialog(
        onDismissRequest = { if (!firmware.busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !firmware.busy, dismissOnClickOutside = !firmware.busy),
        title = { Text("Mise à jour ESP32 · matériel V1.0") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("NE PAS UTILISER LE VAE pendant la mise à jour. Gardez la roue immobile, le vélo alimenté et le téléphone à proximité. Ne coupez pas l’alimentation.",
                    color = Color(0xFFFFCB69), fontWeight = FontWeight.Bold)
                Text("Version installée : ${firmware.info?.version ?: "inconnue (connexion ou installation USB nécessaire)"}\n" +
                    "Version GitHub : ${firmware.release?.version ?: "aucune détectée"}")
                if (!firmware.busy) {
                    Text(when {
                        !ride.bluetoothReady -> "Connectez l’ESP32 pour installer une mise à jour."
                        firmware.info?.supported != true -> "Ce firmware ne permet pas encore l’OTA. Une première installation par USB est nécessaire."
                        !firmware.info.bootConfirmed -> "Validation du démarrage ESP32 en cours…"
                        !firmware.info.stationary -> "Installation bloquée : immobilisez la roue pendant dix secondes."
                        ride.gpsSpeedValid && ride.gpsSpeedKmh >= 1f -> "Installation bloquée : mouvement détecté par le GPS."
                        firmware.available -> "VAE immobile : vous pouvez installer la mise à jour."
                        else -> "Aucune version plus récente à installer."
                    })
                }
                if (firmware.busy || firmware.phase == UpdatePhase.SUCCESS) {
                    stages.forEach { (phase, label) ->
                        val complete = firmware.phase == UpdatePhase.SUCCESS || firmware.phase.ordinal > phase.ordinal
                        val active = firmware.phase == phase
                        Text("${if (complete) "✓" else if (active) "➜" else "·"} $label",
                            fontSize = 12.sp, color = if (complete || active) Color(0xFF69E3F5) else Color.Gray,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                    }
                    if (firmware.phase == UpdatePhase.DOWNLOADING || firmware.phase == UpdatePhase.TRANSFERRING)
                        LinearProgressIndicator(progress = { firmware.progress }, modifier = Modifier.fillMaxWidth())
                    else if (firmware.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(firmware.detail, color = if (firmware.phase == UpdatePhase.ERROR) Color(0xFFFF7886) else Color.Unspecified)
                if (firmware.checking) Text("Recherche automatique de mise à jour…", fontSize = 12.sp)
                if (!firmware.busy && !ride.espDetected && !ride.bluetoothReady) {
                    TextButton(onClick = onAssociate) { Text("Associer un ESP32") }
                }
            }
        },
        confirmButton = {
            if (!firmware.busy && firmware.available) TextButton(onClick = onInstall,
                enabled = firmware.canInstall && ride.bluetoothReady && (!ride.gpsSpeedValid || ride.gpsSpeedKmh < 1f)) {
                Text("Installer ${firmware.release?.version}")
            }
        },
        dismissButton = { if (!firmware.busy) TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}
