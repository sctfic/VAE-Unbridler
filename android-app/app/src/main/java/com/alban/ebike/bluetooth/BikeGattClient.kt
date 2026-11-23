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
import com.alban.ebike.model.FirmwareInfo
import com.alban.ebike.model.FirmwareProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import com.alban.ebike.firmware.FirmwareUpdateStore

class BikeGattClient(
    context: Context,
    private val onReady: (String) -> Unit,
    private val onTelemetry: (String, BikeTelemetry) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFirmwareVersion: (String?) -> Unit = {},
    private val onFirmwareInfo: (FirmwareInfo?) -> Unit = {},
    private val onDetected: (Boolean) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null
    private var configCharacteristic: BluetoothGattCharacteristic? = null
    private var otaControl: BluetoothGattCharacteristic? = null
    private var otaData: BluetoothGattCharacteristic? = null
    private val operations = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: CompletableDeferred<ByteArray>? = null
    private var pendingUuid: UUID? = null
    private var lockedDevice: String? = null
    private var initialInfoPending = false
    private val eventDecoder = com.alban.ebike.model.WheelEventDecoder()
    private val eventClock = com.alban.ebike.model.WheelEventClock()
    private var initialTelemetry: BikeTelemetry? = null
    private var mtu = 23
    val firmwareChunkSize get() = (mtu - 7).coerceIn(1, 240)
    val ready get() = isReady
    private val initializationTimeout = Runnable { initialInfoPending = false; gatt?.disconnect() }
    private var scanning = false
    private var stopped = false
    @Volatile private var isReady = false
    @Volatile var connectionEpoch = 0L
        private set
    private val reconnect = Runnable { scanAndConnect() }

    private fun status(message: String) {
        onStatus(message)
    }

    fun start() {
        stopped = false
        scanAndConnect()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        onDetected(false)
        stopped = true
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(initializationTimeout)
        pending?.completeExceptionally(IllegalStateException("Connexion Bluetooth arrêtée."))
        onFirmwareInfo(null)
        isReady = false
        eventClock.reset(); initialTelemetry = null
        initialInfoPending = false
        if (hasBluetoothPermission()) {
            runCatching {
                if (scanning) scanner?.stopScan(scanCallback)
                gatt?.close()
            }
        }
        scanning = false
        gatt = null
        telemetryCharacteristic = null; configCharacteristic = null; otaControl = null; otaData = null
    }

    fun close() { stop(); scope.cancel() }

    @SuppressLint("MissingPermission")
    fun setWheelCircumference(circumferenceMm: Int, speedMode: Boolean? = null, expectedAddress: String? = null) {
        scope.launch {
            if (FirmwareUpdateStore.state.value.busy) return@launch
            val connection = gatt ?: return@launch
            if (expectedAddress == null || !connection.device.address.equals(expectedAddress, ignoreCase = true)) return@launch
            val characteristic = configCharacteristic ?: return@launch
            runCatching { transact(characteristic, BleProtocol.encodeConfig(circumferenceMm, speedMode)) }
                .onFailure { status("Réglage refusé · ${it.message}") }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun lockFirmwareDevice(lock: Boolean) = withContext(Dispatchers.Main.immediate) {
        lockedDevice = if (lock) gatt?.device?.address ?: error("ESP32 déconnecté.") else null
    }

    suspend fun readFirmwareInfo(): FirmwareInfo? {
        val characteristic = otaControl ?: return null
        return FirmwareProtocol.decode(transact(characteristic, null))
            ?.also(onFirmwareInfo) ?: error("Réponse OTA invalide.")
    }

    suspend fun firmwareControl(bytes: ByteArray) {
        transact(otaControl ?: error("Connexion OTA indisponible."), bytes)
    }

    suspend fun firmwareData(bytes: ByteArray) {
        transact(otaData ?: error("OTA indisponible."), bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }

    @SuppressLint("MissingPermission")
    suspend fun firmwareTransferPriority(high: Boolean) = withContext(Dispatchers.Main.immediate) {
        val connection = gatt ?: return@withContext
        connection.requestConnectionPriority(if (high) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        if (high && adapter?.isLe2MPhySupported == true) {
            connection.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK,
                BluetoothDevice.PHY_OPTION_NO_PREFERRED)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun transact(characteristic: BluetoothGattCharacteristic, bytes: ByteArray?,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT): ByteArray =
        withContext(Dispatchers.Main.immediate) {
            operations.withLock {
                val connection = gatt ?: error("ESP32 déconnecté.")
                check(isReady) { "Connexion ESP32 en préparation." }
                check(connection.getService(BleProtocol.serviceUuid)?.getCharacteristic(characteristic.uuid) === characteristic) {
                    "La connexion ESP32 a changé."
                }
                val result = CompletableDeferred<ByteArray>()
                pending = result; pendingUuid = characteristic.uuid
                try {
                    val started = if (bytes == null) connection.readCharacteristic(characteristic) else {
                        characteristic.writeType = writeType
                        characteristic.value = bytes
                        connection.writeCharacteristic(characteristic)
                    }
                    check(started) { "Opération Bluetooth refusée." }
                    withTimeout(15_000) { result.await() }
                } catch (e: Exception) {
                    // A timed out/cancelled operation may still deliver a callback. Reconnect
                    // before issuing another command so that it cannot acknowledge a new write.
                    if (e is CancellationException) { isReady = false; initialInfoPending = false; connection.disconnect() }
                    throw e
                } finally { pending = null; pendingUuid = null }
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
            .apply { lockedDevice?.let { setDeviceAddress(it) } }
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
            if (lockedDevice != null && result.device.address != lockedDevice) return
            scanner?.stopScan(this)
            scanning = false
            onDetected(true)
            status("ESP32 détecté · connexion")
            gatt = result.device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK, handler)
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
                eventClock.reset(); initialTelemetry = null
                status("ESP32 connecté · préparation")
                handler.postDelayed(initializationTimeout, 20_000)
                // Negotiate room for OTA metadata and 240-byte data chunks.
                if (!gatt.requestMtu(247)) gatt.disconnect()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                isReady = false
                initialInfoPending = false
                telemetryCharacteristic = null
                configCharacteristic = null
                otaControl = null; otaData = null
                handler.removeCallbacks(initializationTimeout)
                pending?.completeExceptionally(IllegalStateException("Connexion Bluetooth interrompue."))
                onFirmwareInfo(null)
                gatt.close()
                if (this@BikeGattClient.gatt === gatt) this@BikeGattClient.gatt = null
                onDetected(false)
                onDisconnected()
                status("ESP32 déconnecté ($status)")
                scheduleReconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (stopped || this@BikeGattClient.gatt !== gatt) return
            this@BikeGattClient.mtu = mtu
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 55) gatt.discoverServices()
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
            telemetryCharacteristic = service.getCharacteristic(BleProtocol.eventsUuid)
            configCharacteristic = service.getCharacteristic(BleProtocol.configUuid)
            otaControl = service.getCharacteristic(FirmwareProtocol.controlUuid)
            otaData = service.getCharacteristic(FirmwareProtocol.dataUuid)
            if (configCharacteristic == null || otaControl == null || otaData == null ||
                otaData!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0) {
                gatt.disconnect(); return
            }
            onFirmwareVersion(null)
            val telemetry = telemetryCharacteristic ?: run { gatt.disconnect(); return }
            gatt.setCharacteristicNotification(telemetry, true)
            val descriptor = telemetry.getDescriptor(CLIENT_CONFIGURATION_UUID) ?: run { gatt.disconnect(); return }
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (stopped || this@BikeGattClient.gatt !== gatt) return
            if (descriptor.uuid != CLIENT_CONFIGURATION_UUID) return
            if (descriptor.characteristic.uuid != BleProtocol.eventsUuid) return
            if (status != BluetoothGatt.GATT_SUCCESS) { gatt.disconnect(); return }
            initialInfoPending = true
            if (!gatt.readCharacteristic(otaControl!!)) gatt.disconnect()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (this@BikeGattClient.gatt !== gatt) return@post
                if (pendingUuid == characteristic.uuid) {
                    if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(byteArrayOf())
                    else pending?.completeExceptionally(IllegalStateException("Commande refusée par l’ESP32 ($status)."))
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            consumeRead(gatt, characteristic.uuid, characteristic.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            consumeRead(gatt, characteristic.uuid, value, status)
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

    private fun consumeRead(connection: BluetoothGatt, uuid: UUID, value: ByteArray, result: Int) {
        handler.post {
            if (stopped || gatt !== connection) return@post
            if (initialInfoPending && uuid == FirmwareProtocol.controlUuid) {
                initialInfoPending = false
                if (result == BluetoothGatt.GATT_SUCCESS) {
                    val info = FirmwareProtocol.decode(value)
                    onFirmwareInfo(info)
                    if (info == null || !info.supported) { connection.disconnect(); return@post }
                    onFirmwareVersion(info.version)
                    finishReady(connection)
                } else connection.disconnect()
            } else if (pendingUuid == uuid) {
                if (result == BluetoothGatt.GATT_SUCCESS) pending?.complete(value)
                else pending?.completeExceptionally(IllegalStateException("Lecture OTA refusée ($result)."))
            }
        }
    }

    private fun consumeTelemetry(address: String, uuid: UUID, value: ByteArray) {
        if (uuid == BleProtocol.eventsUuid) {
            val now = android.os.SystemClock.elapsedRealtime()
            eventDecoder.accept(value)?.let { raw -> eventClock.accept(raw, now) }?.let {
                if (isReady) onTelemetry(address, it) else initialTelemetry = it
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun finishReady(connection: BluetoothGatt) {
        if (isReady || gatt !== connection || stopped) return
        handler.removeCallbacks(initializationTimeout)
        connectionEpoch++
        isReady = true
        status("ESP32 connecté")
        onReady(connection.device.address)
        initialTelemetry?.let { onTelemetry(connection.device.address, it) }
        initialTelemetry = null
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
