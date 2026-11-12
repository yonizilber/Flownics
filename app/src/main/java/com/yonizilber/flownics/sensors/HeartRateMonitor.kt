package com.yonizilber.flownics.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.yonizilber.flownics.domain.fit.HeartPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A heart-rate sensor seen while scanning (a chest strap, or a watch broadcasting its heart rate). */
data class FoundSensor(val address: String, val name: String)

sealed interface SensorState {
    data object Off : SensorState
    data object Connecting : SensorState
    data class Live(val bpm: Int?, val name: String) : SensorState
}

/**
 * Live heart rate over Bluetooth, from any device that speaks the standard Heart Rate profile:
 * chest straps, and Garmin watches with "Broadcast heart rate" turned on. Only the heart rate is
 * read; nothing is written to the device. Readings are kept while a workout records them.
 */
@Singleton
class HeartRateMonitor @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<SensorState>(SensorState.Off)
    val state: StateFlow<SensorState> = _state.asStateFlow()
    private val _saved = MutableStateFlow(load())
    val saved: StateFlow<FoundSensor?> = _saved.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private val samples = mutableListOf<HeartPoint>()
    @Volatile private var recording = false

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermission(): Boolean = permissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun bluetoothOn(): Boolean = adapter?.isEnabled == true

    /** Sensors around, until the flow is cancelled. */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<FoundSensor> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !hasPermission()) {
            close()
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(FoundSensor(result.device.address, result.scanRecord?.deviceName ?: result.device.name ?: result.device.address))
            }
        }
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            callback,
        )
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    fun choose(sensor: FoundSensor) {
        prefs.edit().putString(KEY_ADDRESS, sensor.address).putString(KEY_NAME, sensor.name).apply()
        _saved.value = sensor
    }

    fun forget() {
        disconnect()
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply()
        _saved.value = null
    }

    /** Connects to the chosen sensor, and keeps reconnecting if it drops (Android's auto-connect). */
    @SuppressLint("MissingPermission")
    fun connect() {
        val sensor = _saved.value ?: return
        if (gatt != null || !hasPermission() || !bluetoothOn()) return
        val device: BluetoothDevice = runCatching { adapter?.getRemoteDevice(sensor.address) }.getOrNull() ?: return
        _state.value = SensorState.Connecting
        gatt = device.connectGatt(context, true, callback(sensor.name), BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        _state.value = SensorState.Off
    }

    fun startRecording() {
        synchronized(samples) { samples.clear() }
        recording = true
    }

    /** What was recorded since [startRecording], and stops recording. */
    fun stopRecording(): List<HeartPoint> {
        recording = false
        return synchronized(samples) { samples.toList().also { samples.clear() } }
    }

    private fun callback(name: String) = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = SensorState.Live(null, name)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> if (gatt != null) _state.value = SensorState.Connecting
            }
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT) ?: return
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) = reading(name, value)

        @Deprecated("Before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) reading(name, ch.value ?: return)
        }
    }

    private fun reading(name: String, value: ByteArray) {
        val bpm = parse(value) ?: return
        _state.value = SensorState.Live(bpm, name)
        if (recording) synchronized(samples) { samples += HeartPoint(System.currentTimeMillis(), bpm) }
    }

    private fun load(): FoundSensor? = prefs.getString(KEY_ADDRESS, null)?.let { FoundSensor(it, prefs.getString(KEY_NAME, it).orEmpty()) }

    companion object {
        private const val PREFS = "hr_sensor"
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** The Heart Rate Measurement value: flags, then the rate as 8 or 16 bits. Null when it makes no sense. */
        fun parse(value: ByteArray): Int? {
            if (value.size < 2) return null
            val sixteen = value[0].toInt() and 0x01 != 0
            val bpm = if (sixteen) {
                if (value.size < 3) return null
                (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
            } else {
                value[1].toInt() and 0xFF
            }
            return bpm.takeIf { it in 25..250 }
        }
    }
}
