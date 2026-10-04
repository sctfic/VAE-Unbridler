package com.alban.ebike.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.alban.ebike.model.BikeTelemetry
import com.alban.ebike.model.BleProtocol
import java.util.UUID

class BikeGattClient(
    context: Context,
    private val onReady: (String) -> Unit,
    private val onTelemetry: (String, BikeTelemetry) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null
    private var configCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var stopped = false
    private var isReady = false
    private var configWritePending = false
    private val reconnect = Runnable { scanAndConnect() }

    private fun status(message: String) {
        Log.i("EBikeBLE", message)
        onStatus(message)
    }

    fun start() {
        stopped = false
        scanAndConnect()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        stopped = true
        handler.removeCallbacks(reconnect)
        isReady = false
        if (hasBluetoothPermission()) {
            runCatching {
                if (scanning) scanner?.stopScan(scanCallback)
                gatt?.close()
            }
        }
        scanning = false
        gatt = null
    }

    @SuppressLint("MissingPermission")
    fun setWheelCircumference(circumferenceMm: Int, speedMode: Boolean? = null, expectedAddress: String? = null) {
        handler.post {
            val characteristic = configCharacteristic ?: return@post
            val currentGatt = gatt ?: return@post
            if (expectedAddress == null || !currentGatt.device.address.equals(expectedAddress, ignoreCase = true)) return@post
            if (!isReady || configWritePending) {
                status("Réglage occupé · réessayez")
                return@post
            }
            characteristic.value = BleProtocol.encodeConfig(circumferenceMm, speedMode)
            configWritePending = currentGatt.writeCharacteristic(characteristic)
            if (!configWritePending) status("Échec de l’envoi du réglage · réessayez")
        }
    }

    @SuppressLint("MissingPermission")
    private fun scanAndConnect() {
        if (stopped || scanning || gatt != null) return
        if (!hasBluetoothPermission()) {
            status("Autorisation Bluetooth nécessaire")
            scheduleReconnect()
            return
        }
        if (adapter?.isEnabled != true) {
            status("Activer le Bluetooth")
            scheduleReconnect()
            return
        }
        scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleProtocol.serviceUuid))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        status("Recherche ESP32")
        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (stopped || gatt != null) return
            scanner?.stopScan(this)
            scanning = false
            status("ESP32 détecté · connexion")
            gatt = result.device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            status("Recherche Bluetooth échouée ($errorCode) · nouvelle tentative")
            scheduleReconnect()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (stopped || this@BikeGattClient.gatt !== gatt) {
                gatt.close()
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothGatt.STATE_CONNECTED) {
                configWritePending = false
                status("ESP32 connecté · préparation")
                // Telemetry is 32 bytes; the default ATT payload is only 20.
                if (!gatt.requestMtu(64)) gatt.disconnect()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                isReady = false
                telemetryCharacteristic = null
                configCharacteristic = null
                gatt.close()
                if (this@BikeGattClient.gatt === gatt) this@BikeGattClient.gatt = null
                onDisconnected()
                status("ESP32 déconnecté ($status)")
                scheduleReconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (stopped || this@BikeGattClient.gatt !== gatt) return
            if (mtu >= 35) gatt.discoverServices()
            else {
                status("Connexion incompatible · paquet Bluetooth trop court")
                gatt.disconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (stopped || this@BikeGattClient.gatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                gatt.disconnect()
                return
            }
            val service = gatt.getService(BleProtocol.serviceUuid) ?: run { gatt.disconnect(); return }
            telemetryCharacteristic = service.getCharacteristic(BleProtocol.telemetryUuid)
            configCharacteristic = service.getCharacteristic(BleProtocol.configUuid)
            val telemetry = telemetryCharacteristic ?: run { gatt.disconnect(); return }
            gatt.setCharacteristicNotification(telemetry, true)
            val descriptor = telemetry.getDescriptor(CLIENT_CONFIGURATION_UUID) ?: run { gatt.disconnect(); return }
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (stopped || this@BikeGattClient.gatt !== gatt) return
            if (descriptor.uuid == CLIENT_CONFIGURATION_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                isReady = true
                status("ESP32 connecté")
                onReady(gatt.device.address)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (this@BikeGattClient.gatt !== gatt) return@post
                configWritePending = false
                if (status != BluetoothGatt.GATT_SUCCESS) status("Réglage refusé par l’ESP32 ($status)")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (stopped || this@BikeGattClient.gatt !== gatt) return
            consumeTelemetry(gatt.device.address, characteristic.uuid, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (stopped || this@BikeGattClient.gatt !== gatt) return
            consumeTelemetry(gatt.device.address, characteristic.uuid, value)
        }
    }

    private fun consumeTelemetry(address: String, uuid: UUID, value: ByteArray) {
        if (uuid == BleProtocol.telemetryUuid) BleProtocol.decodeTelemetry(value)?.let { onTelemetry(address, it) }
    }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnect)
        if (!stopped) handler.postDelayed(reconnect, RECONNECT_DELAY_MS)
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        val CLIENT_CONFIGURATION_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val RECONNECT_DELAY_MS = 4_000L
    }
}
