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
import android.os.HandlerThread
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.alban.ebike.model.BikeTelemetry
import com.alban.ebike.model.BleProtocol
import com.alban.ebike.model.FirmwareInfo
import com.alban.ebike.model.FirmwareProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import com.alban.ebike.model.FirmwareProgress
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothThread = HandlerThread("BikeBLE").apply { start() }
    private val handler = Handler(bluetoothThread.looper)
    private val dispatcher = handler.asCoroutineDispatcher("BikeBLE")
    private val latestProgress = MutableStateFlow<FirmwareProgress?>(null)
    @Volatile private var txPhy = 1
    @Volatile private var rxPhy = 1
    val firmwareLinkDescription get() = "MTU $mtu · PHY $txPhy/$rxPhy · interval ${latestProgress.value?.intervalUnits?.times(1.25)} ms"
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null
    private var configCharacteristic: BluetoothGattCharacteristic? = null
    private var otaControl: BluetoothGattCharacteristic? = null
    private var otaData: BluetoothGattCharacteristic? = null
    private val operations = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher.immediate)
    private var pending: CompletableDeferred<ByteArray>? = null
    private var pendingUuid: UUID? = null
    private var lockedDevice: String? = null
    private var initialInfoPending = false
    private val eventDecoder = com.alban.ebike.model.WheelEventDecoder()
    private val eventClock = com.alban.ebike.model.WheelEventClock()
    private var initialTelemetry: BikeTelemetry? = null
    @Volatile private var mtu = 23
    val firmwareChunkSize get() = (mtu - 7).coerceIn(1, 508)
    val ready get() = isReady
    private val initializationTimeout = Runnable { initialInfoPending = false; gatt?.disconnect() }
    private var scanning = false
    private var stopped = false
    @Volatile private var isReady = false
    @Volatile var connectionEpoch = 0L
        private set
    private val reconnect = Runnable { scanAndConnect() }

    private fun publishDetected(value: Boolean) { mainHandler.post { onDetected(value) } }
    private fun publishFirmwareInfo(value: FirmwareInfo?) { mainHandler.post { onFirmwareInfo(value) } }
    private fun publishFirmwareVersion(value: String?) { mainHandler.post { onFirmwareVersion(value) } }
    private fun publishDisconnected() { mainHandler.post { onDisconnected() } }
    private fun publishReady(value: String) { mainHandler.post { onReady(value) } }
    private fun publishTelemetry(address: String, value: BikeTelemetry) { mainHandler.post { onTelemetry(address, value) } }
    private fun publishStatus(value: String) { mainHandler.post { onStatus(value) } }

    private fun status(message: String) {
        publishStatus(message)
    }

    fun start() { handler.post { stopped = false; scanAndConnect() } }

    @SuppressLint("MissingPermission")
    fun stop() { handler.post { stopInternal() } }

    @SuppressLint("MissingPermission")
    private fun stopInternal() {
        publishDetected(false)
        stopped = true
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(initializationTimeout)
        pending?.completeExceptionally(IllegalStateException("Connexion Bluetooth arrêtée."))
        publishFirmwareInfo(null)
        isReady = false
        eventClock.reset(); initialTelemetry = null
        initialInfoPending = false
        latestProgress.value = null
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

    fun close() { handler.post { stopInternal(); scope.cancel(); bluetoothThread.quitSafely() } }

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
    suspend fun lockFirmwareDevice(lock: Boolean) = withContext(dispatcher.immediate) {
        lockedDevice = if (lock) gatt?.device?.address ?: error("ESP32 déconnecté.") else null
    }

    suspend fun readFirmwareInfo(): FirmwareInfo? {
        val characteristic = otaControl ?: return null
        return FirmwareProtocol.decode(transact(characteristic, null))
            ?.also(::publishFirmwareInfo) ?: error("Réponse OTA invalide.")
    }

    suspend fun awaitFastFirmwareLink() = withTimeoutOrNull(3000) {
        latestProgress.first { it != null && it.intervalUnits == 6 && it.txPhy == 2 && it.rxPhy == 2 }
    }

    suspend fun awaitFirmwareProgress(code: Int, size: Int, after: Int): FirmwareProgress {
        val p = withTimeoutOrNull(1500) {
            latestProgress.first { p -> p != null && p.targetCode == code && p.size == size &&
                (p.received > after || p.state != 1 || p.error != 0) }
        }
        if (p != null) return p
        val info = readFirmwareInfo() ?: error("ESP32 déconnecté.")
        return FirmwareProgress(info.state, info.error, code, info.received, info.size)
    }

    suspend fun firmwareControl(bytes: ByteArray) {
        if (bytes.firstOrNull() == FirmwareProtocol.BEGIN_FAST) latestProgress.value = null
        transact(otaControl ?: error("Connexion OTA indisponible."), bytes)
    }

    suspend fun firmwareData(bytes: ByteArray) {
        transact(otaData ?: error("OTA indisponible."), bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }

    @SuppressLint("MissingPermission")
    suspend fun firmwareTransferPriority(high: Boolean) = withContext(dispatcher.immediate) {
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
        withContext(dispatcher.immediate) {
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
                        if (Build.VERSION.SDK_INT >= 33) {
                            connection.writeCharacteristic(characteristic, bytes, writeType) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                        } else {
                            characteristic.writeType = writeType; characteristic.value = bytes
                            connection.writeCharacteristic(characteristic)
                        }
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
        override fun onScanResult(callbackType: Int, result: ScanResult) { handler.post { connectResult(result) } }

        @SuppressLint("MissingPermission")
        private fun connectResult(result: ScanResult) {
            if (stopped || gatt != null) return
            if (lockedDevice != null && result.device.address != lockedDevice) return
            scanner?.stopScan(this)
            scanning = false
            publishDetected(true)
            status("ESP32 détecté · connexion")
            gatt = result.device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK, handler)
        }

        override fun onScanFailed(errorCode: Int) { handler.post {
            scanning = false
            status("Recherche Bluetooth échouée ($errorCode) · nouvelle tentative")
            scheduleReconnect()
        } }
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
                // Negotiate room for OTA metadata and 508-byte data chunks.
                if (!gatt.requestMtu(517)) gatt.disconnect()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                isReady = false
                initialInfoPending = false
                telemetryCharacteristic = null
                configCharacteristic = null
                otaControl = null; otaData = null
                handler.removeCallbacks(initializationTimeout)
                pending?.completeExceptionally(IllegalStateException("Connexion Bluetooth interrompue."))
                publishFirmwareInfo(null)
                gatt.close()
                if (this@BikeGattClient.gatt === gatt) this@BikeGattClient.gatt = null
                publishDetected(false)
                publishDisconnected()
                status("ESP32 déconnecté ($status)")
                scheduleReconnect()
            }
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (this@BikeGattClient.gatt === gatt && status == BluetoothGatt.GATT_SUCCESS) {
                this@BikeGattClient.txPhy = txPhy; this@BikeGattClient.rxPhy = rxPhy
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
            publishFirmwareVersion(null)
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
            if (status != BluetoothGatt.GATT_SUCCESS) { gatt.disconnect(); return }
            if (descriptor.characteristic.uuid == BleProtocol.eventsUuid) {
                val control = otaControl ?: run { gatt.disconnect(); return }
                val ackDescriptor = control.getDescriptor(CLIENT_CONFIGURATION_UUID) ?: run { gatt.disconnect(); return }
                if (!gatt.setCharacteristicNotification(control, true)) { gatt.disconnect(); return }
                ackDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!gatt.writeDescriptor(ackDescriptor)) gatt.disconnect()
                return
            }
            if (descriptor.characteristic.uuid != FirmwareProtocol.controlUuid) return
            initialInfoPending = true
            if (!gatt.readCharacteristic(otaControl!!)) gatt.disconnect()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            run {
                if (this@BikeGattClient.gatt !== gatt) return
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
        run {
            if (stopped || gatt !== connection) return
            if (initialInfoPending && uuid == FirmwareProtocol.controlUuid) {
                initialInfoPending = false
                if (result == BluetoothGatt.GATT_SUCCESS) {
                    val info = FirmwareProtocol.decode(value)
                    publishFirmwareInfo(info)
                    if (info == null || !info.supported) { connection.disconnect(); return }
                    publishFirmwareVersion(info.version)
                    finishReady(connection)
                } else connection.disconnect()
            } else if (pendingUuid == uuid) {
                if (result == BluetoothGatt.GATT_SUCCESS) pending?.complete(value)
                else pending?.completeExceptionally(IllegalStateException("Lecture OTA refusée ($result)."))
            }
        }
    }

    private fun consumeTelemetry(address: String, uuid: UUID, value: ByteArray) {
        if (uuid == FirmwareProtocol.controlUuid) {
            FirmwareProtocol.decodeProgress(value)?.let { latestProgress.value = it }
            return
        }
        if (uuid == BleProtocol.eventsUuid) {
            val now = android.os.SystemClock.elapsedRealtime()
            eventDecoder.accept(value)?.let { raw -> eventClock.accept(raw, now) }?.let {
                if (isReady) publishTelemetry(address, it) else initialTelemetry = it
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
        publishReady(connection.device.address)
        initialTelemetry?.let { publishTelemetry(connection.device.address, it) }
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
