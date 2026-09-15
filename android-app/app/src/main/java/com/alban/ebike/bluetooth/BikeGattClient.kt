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
import androidx.core.content.ContextCompat
import com.alban.ebike.model.BikeTelemetry
import com.alban.ebike.model.BleProtocol
import java.util.UUID

class BikeGattClient(
    context: Context,
    private val onReady: () -> Unit,
    private val onTelemetry: (BikeTelemetry) -> Unit,
    private val onDisconnected: () -> Unit,
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

    fun start() {
        stopped = false
        scanAndConnect()
    }

    fun stop() {
        stopped = true
        isReady = false
        if (scanning) scanner?.stopScan(scanCallback)
        scanning = false
        gatt?.close()
        gatt = null
    }

    @SuppressLint("MissingPermission")
    fun setWheelCircumference(circumferenceMm: Int) {
        val characteristic = configCharacteristic ?: return
        val currentGatt = gatt ?: return
        characteristic.value = BleProtocol.encodeConfig(circumferenceMm)
        currentGatt.writeCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    private fun scanAndConnect() {
        if (stopped || scanning || !hasBluetoothPermission() || adapter?.isEnabled != true || gatt != null) return
        scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleProtocol.serviceUuid))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (stopped || gatt != null) return
            scanner?.stopScan(this)
            scanning = false
            gatt = result.device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            scheduleReconnect()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothGatt.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                isReady = false
                telemetryCharacteristic = null
                configCharacteristic = null
                gatt.close()
                if (this@BikeGattClient.gatt === gatt) this@BikeGattClient.gatt = null
                onDisconnected()
                scheduleReconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
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

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CLIENT_CONFIGURATION_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                isReady = true
                onReady()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            consumeTelemetry(characteristic.uuid, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            consumeTelemetry(characteristic.uuid, value)
        }
    }

    private fun consumeTelemetry(uuid: UUID, value: ByteArray) {
        if (uuid == BleProtocol.telemetryUuid) BleProtocol.decodeTelemetry(value)?.let(onTelemetry)
    }

    private fun scheduleReconnect() {
        if (!stopped) handler.postDelayed(::scanAndConnect, RECONNECT_DELAY_MS)
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
