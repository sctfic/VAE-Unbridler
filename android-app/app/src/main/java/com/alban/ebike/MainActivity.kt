package com.alban.ebike

import android.Manifest
import android.app.Activity
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.view.WindowManager
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
import com.alban.ebike.ui.DashboardScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: BikeSettingsStore
    private lateinit var associationLauncher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        RideService.start(this)
    }
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { RideService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = BikeSettingsStore(this)
        enterDashboardMode()
        associationLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            // Successful association is persisted by Android. The foreground service
            // will discover and reconnect to the board from this point onward.
            RideService.start(this)
        }
        requestInitialPermissions()
        RideService.start(this)

        setContent {
            val rideState by RideStateStore.state.collectAsState()
            val circumference by settings.circumferenceMm.collectAsState(BleProtocol.defaultCircumferenceMm)
            DashboardScreen(
                state = rideState,
                circumferenceMm = circumference,
                onAssociate = ::associateBike,
                onSaveCircumference = { mm ->
                    scope.launch {
                        settings.setCircumferenceMm(mm)
                        RideService.setWheelCircumference(this@MainActivity, mm)
                    }
                },
            )
        }
    }

    private fun enterDashboardMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun requestInitialPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionsLauncher.launch(wanted.toTypedArray())
    }

    private fun associateBike() {
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
            override fun onAssociationPending(intentSender: IntentSender) {
                associationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onFailure(error: CharSequence?) = Unit
        }, null)
    }
}
