package com.alban.ebike

import android.Manifest
import android.app.Activity
import android.companion.AssociationRequest
import android.companion.AssociationInfo
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.alban.ebike.data.BikeSettingsStore
import com.alban.ebike.data.RideStateStore
import com.alban.ebike.model.BleProtocol
import com.alban.ebike.service.RideService
import com.alban.ebike.companion.BikeArrivalNotification
import com.alban.ebike.ui.DashboardScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: BikeSettingsStore
    private lateinit var associationLauncher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>
    private var associationInProgress = false
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { requestBackgroundLocationOrStart() }
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        observeExistingAssociations()
        startAndOfferAssociation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = BikeSettingsStore(this)
        enterDashboardMode()
        associationLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            associationInProgress = false
            if (it.resultCode == Activity.RESULT_OK) {
                registerPresenceObservation(it.data)
                observeExistingAssociations()
            }
            RideService.resumeAfterAssociation(this)
        }
        requestInitialPermissions()
        observeExistingAssociations()

        setContent {
            val rideState by RideStateStore.state.collectAsState()
            val circumference by settings.circumferenceMm.collectAsState(BleProtocol.defaultCircumferenceMm)
            DashboardScreen(
                state = rideState,
                circumferenceMm = circumference,
                onAssociate = ::associateBike,
                onToggleMode = {
                    when {
                        !rideState.bluetoothReady -> Toast.makeText(this, "Connectez l’ESP32 pour changer de mode", Toast.LENGTH_SHORT).show()
                        !rideState.modeSupported -> Toast.makeText(this, "Mise à jour du firmware ESP32 nécessaire", Toast.LENGTH_LONG).show()
                        else -> RideService.toggleMode(this)
                    }
                },
                onSaveCircumference = { mm ->
                    scope.launch {
                        settings.setCircumferenceMm(mm)
                        RideService.setWheelCircumference(this@MainActivity, mm)
                    }
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        BikeArrivalNotification.dismiss(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        BikeArrivalNotification.dismiss(this)
        enterDashboardMode()
        if (!associationInProgress) RideService.start(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterDashboardMode()
    }

    private fun enterDashboardMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun requestInitialPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) {
            permissionsLauncher.launch(wanted.toTypedArray())
        } else {
            requestBackgroundLocationOrStart()
        }
    }

    private fun requestBackgroundLocationOrStart() {
        val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val needsBackgroundLocation = fineGranted &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        if (needsBackgroundLocation) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            observeExistingAssociations()
            startAndOfferAssociation()
        }
    }

    @Suppress("DEPRECATION")
    private fun startAndOfferAssociation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            RideService.start(this)
            return
        }
        val preferences = getSharedPreferences("auto_open_setup", MODE_PRIVATE)
        val manager = getSystemService(CompanionDeviceManager::class.java)
        if (manager.associations.isEmpty() && !preferences.getBoolean("offered_v2", false)) {
            preferences.edit().putBoolean("offered_v2", true).apply()
            Toast.makeText(this, "Associer l’ESP32 une fois pour activer l’ouverture automatique.",
                Toast.LENGTH_LONG).show()
            associateBike()
        } else {
            RideService.start(this)
        }
    }

    private fun associateBike() {
        if (associationInProgress) return
        associationInProgress = true
        RideService.prepareAssociation(this)
        val manager = getSystemService(CompanionDeviceManager::class.java)
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(
                android.bluetooth.le.ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(BleProtocol.serviceUuid))
                    .build(),
            )
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).build()
        manager.associate(request, object : CompanionDeviceManager.Callback() {
            @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                runOnUiThread {
                    associationInfo.deviceMacAddress?.toString()?.let(::observePresence)
                    associationInProgress = false
                    RideService.resumeAfterAssociation(this@MainActivity)
                }
            }

            @Deprecated("Compatibility with Android 10–12")
            override fun onDeviceFound(intentSender: IntentSender) {
                associationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onAssociationPending(intentSender: IntentSender) {
                associationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onFailure(error: CharSequence?) {
                runOnUiThread {
                    associationInProgress = false
                    RideService.resumeAfterAssociation(this@MainActivity)
                    Toast.makeText(this@MainActivity,
                        error ?: "Aucun ESP32 trouvé. Vérifier son alimentation et sa LED.",
                        Toast.LENGTH_LONG).show()
                }
            }
        }, null)
    }

    @Suppress("DEPRECATION")
    private fun registerPresenceObservation(data: Intent?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data?.getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                AssociationInfo::class.java,
            )?.deviceMacAddress?.toString()
        } else {
            data?.getParcelableExtra<android.bluetooth.le.ScanResult>(CompanionDeviceManager.EXTRA_DEVICE)
                ?.device?.address
        }
        address?.let(::observePresence)
    }

    @Suppress("DEPRECATION")
    private fun observeExistingAssociations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = getSystemService(CompanionDeviceManager::class.java)
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.myAssociations.mapNotNull { it.deviceMacAddress?.toString() }
        } else {
            manager.associations
        }
        addresses.forEach(::observePresence)
    }

    @Suppress("DEPRECATION")
    private fun observePresence(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            getSystemService(CompanionDeviceManager::class.java)
                .startObservingDevicePresence(address)
        }.onSuccess {
            android.util.Log.i("EBikeAutoOpen", "Companion presence observation registered")
        }.onFailure {
            android.util.Log.e("EBikeAutoOpen", "Unable to observe companion presence", it)
            Toast.makeText(this, "Ouverture automatique non activée : réassocier l’ESP32.", Toast.LENGTH_LONG).show()
        }
    }
}
