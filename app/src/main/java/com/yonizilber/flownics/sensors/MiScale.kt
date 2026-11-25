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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.yonizilber.flownics.data.repository.WeightRepository
import com.yonizilber.flownics.domain.body.MiScaleProtocol
import com.yonizilber.flownics.domain.body.WeightEntry
import com.yonizilber.flownics.domain.body.WeightSource
import com.yonizilber.flownics.domain.body.Weights
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** How a weigh-in with the scale goes, step by step, for the screen. */
sealed interface ScaleEvent {
    data object Searching : ScaleEvent
    /** What the scale shows right now; [settled] once the weight stopped moving. */
    data class Live(val kg: Double, val settled: Boolean) : ScaleEvent
    data object ReadingHistory : ScaleEvent
    data class Done(val result: ScaleResult) : ScaleEvent
    data class Failed(val problem: ScaleProblem) : ScaleEvent
}

enum class ScaleProblem { BLUETOOTH_OFF, NO_PERMISSION, NOT_FOUND, NOT_SETTLED, SCAN_FAILED }

/** Why the stored weigh-ins couldn't be read; the weighing just done is kept anyway. */
enum class HistoryProblem { CONNECT_FAILED, NO_HISTORY_ON_SCALE, WRITE_REFUSED, NO_END }

/**
 * Everything a weigh-in brought, as it happened. Records left out are counted with the reason
 * (still moving, a date the scale's clock got wrong), and bytes the app didn't understand are
 * kept in hex to show, so nothing is dropped without a word.
 */
data class ScaleResult(
    val live: WeightEntry?,
    /** The scale's clock was off, so the weighing just done is dated by the phone. */
    val clockWasOff: Boolean,
    val fromHistory: List<WeightEntry>,
    /** New rows stored (a weigh-in stored before isn't stored twice). */
    val saved: Int,
    val announced: Int?,
    val notSettled: Int,
    val badDates: Int,
    val notUnderstood: List<String>,
    val historyProblem: HistoryProblem?,
    /** The scale was told the weigh-ins arrived, so it won't send them again. */
    val confirmed: Boolean,
)

/**
 * The Xiaomi Mi Smart Scale 2 over Bluetooth: its live weight from what it advertises, then its
 * stored weigh-ins (the ones made while the phone wasn't around) through a short connection.
 * The scale is only awake for a while after someone steps on it, so a weigh-in always starts
 * with stepping on. The app has its own user id on the scale, so the weigh-ins it confirms are
 * separate from what Xiaomi's app (Zepp Life) reads.
 */
@Singleton
class MiScale @Inject constructor(
    @ApplicationContext private val context: Context,
    private val weights: WeightRepository,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val userId: Int
        get() = prefs.getInt(KEY_USER, 0).takeIf { it != 0 } ?: Random.nextInt(1, 0xFFFF).also { prefs.edit().putInt(KEY_USER, it).apply() }

    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermission(): Boolean = permissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Finds the scale as the user steps on it, keeps the weighing and the stored weigh-ins. Cancel to stop. */
    @SuppressLint("MissingPermission")
    fun weighIn(): Flow<ScaleEvent> = channelFlow {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            send(ScaleEvent.Failed(ScaleProblem.BLUETOOTH_OFF))
            return@channelFlow
        }
        if (!hasPermission()) {
            send(ScaleEvent.Failed(ScaleProblem.NO_PERMISSION))
            return@channelFlow
        }
        send(ScaleEvent.Searching)

        // 1. The live weight, from the scale's advertisement, until it settles.
        val seen = Channel<ScanResult>(Channel.UNLIMITED)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                seen.trySend(result)
            }

            override fun onScanFailed(errorCode: Int) {
                seen.close(IOException("Scan failed: $errorCode"))
            }
        }
        var device: BluetoothDevice? = null
        var settled: MiScaleProtocol.Reading? = null
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        try {
            withTimeout(SEARCH_MS) {
                for (r in seen) {
                    val record = r.scanRecord ?: continue
                    val data = record.getServiceData(ParcelUuid(MiScaleProtocol.WEIGHT_SERVICE))
                    if (data == null && !MiScaleProtocol.isScaleName(record.deviceName)) continue
                    val reading = data?.let(MiScaleProtocol::reading) ?: continue
                    device = r.device
                    val done = reading.stable && reading.kg in Weights.RANGE
                    send(ScaleEvent.Live(reading.kg, done))
                    if (done) {
                        settled = reading
                        break
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Unit
        } catch (e: IOException) {
            send(ScaleEvent.Failed(ScaleProblem.SCAN_FAILED))
            return@channelFlow
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        val scale = device
        if (scale == null) {
            send(ScaleEvent.Failed(ScaleProblem.NOT_FOUND))
            return@channelFlow
        }
        val weighing = settled
        if (weighing == null) {
            send(ScaleEvent.Failed(ScaleProblem.NOT_SETTLED))
            return@channelFlow
        }
        prefs.edit().putString(KEY_ADDRESS, scale.address).apply()

        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        fun millis(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()
        fun key(t: LocalDateTime) = "mi:${scale.address}:${millis(t) / 60_000}"
        // The weighing just done, by the scale's clock when it agrees with the phone's (then its
        // stored copy has the same key and isn't added twice); otherwise by the phone's.
        val scaleTime = weighing.time?.takeIf { Duration.between(it, now).abs() <= CLOCK_SLACK }
        val live = WeightEntry(
            atMillis = millis(scaleTime ?: now),
            kg = weighing.kg,
            source = WeightSource.SCALE,
            key = scaleTime?.let(::key) ?: "mi:${scale.address}:phone:${millis(now) / 60_000}",
        )

        // 2. The stored weigh-ins, through a short connection while the scale is still awake.
        send(ScaleEvent.ReadingHistory)
        val history = MiScaleProtocol.History()
        var problem: HistoryProblem? = null
        var confirmed = false
        val link = Link()
        try {
            val gatt = link.open(scale)
            val service = gatt?.getService(MiScaleProtocol.WEIGHT_SERVICE)
            val historyChar = service?.getCharacteristic(MiScaleProtocol.HISTORY)
            when {
                gatt == null -> problem = HistoryProblem.CONNECT_FAILED
                historyChar == null -> problem = HistoryProblem.NO_HISTORY_ON_SCALE
                else -> {
                    // Its clock first, so what it stores from now on is dated right.
                    service.getCharacteristic(MiScaleProtocol.CURRENT_TIME)?.let { link.write(it, MiScaleProtocol.currentTime(now)) }
                    val ok = link.write(historyChar, MiScaleProtocol.ENABLE_HISTORY) &&
                        link.enableNotifications(historyChar) &&
                        link.write(historyChar, MiScaleProtocol.requestNew(userId)) &&
                        link.write(historyChar, MiScaleProtocol.START)
                    if (!ok) {
                        problem = HistoryProblem.WRITE_REFUSED
                    } else {
                        withTimeoutOrNull(HISTORY_MS) {
                            while (!history.done) {
                                val bytes = withTimeoutOrNull(QUIET_MS) { link.notes.receive() } ?: break
                                history.feed(bytes)
                            }
                        }
                        if (!history.done) problem = HistoryProblem.NO_END
                    }
                }
            }

            // 3. Kept, then (only then) the scale is told they arrived.
            val earliest = now.minusYears(5)
            val latest = now.plusDays(1)
            var notSettled = 0
            var badDates = 0
            val stored = history.readings.mapNotNull { r ->
                when {
                    !r.final -> {
                        notSettled++
                        null
                    }
                    r.time == null || r.time.isBefore(earliest) || r.time.isAfter(latest) -> {
                        badDates++
                        null
                    }
                    else -> WeightEntry(atMillis = millis(r.time), kg = r.kg, source = WeightSource.SCALE, key = key(r.time))
                }
            }
            val saved = weights.add(listOf(live) + stored)
            if (history.done && historyChar != null) {
                confirmed = link.write(historyChar, MiScaleProtocol.STOP) && link.write(historyChar, MiScaleProtocol.confirm(userId))
            }
            send(
                ScaleEvent.Done(
                    ScaleResult(
                        live = live,
                        clockWasOff = scaleTime == null,
                        fromHistory = stored,
                        saved = saved,
                        announced = history.announced,
                        notSettled = notSettled,
                        badDates = badDates,
                        notUnderstood = history.notUnderstood.map { b -> b.joinToString(" ") { "%02x".format(it) } },
                        historyProblem = problem,
                        confirmed = confirmed,
                    ),
                ),
            )
        } finally {
            link.close()
        }
    }

    /** One connection to the scale, its operations one at a time (Android's Bluetooth allows no more). */
    @SuppressLint("MissingPermission")
    private inner class Link : BluetoothGattCallback() {
        private var gatt: BluetoothGatt? = null
        private val ready = CompletableDeferred<Boolean>()
        private val ops = Channel<Int>(Channel.UNLIMITED)
        val notes = Channel<ByteArray>(Channel.UNLIMITED)

        /** Connected with its services found, or null. */
        suspend fun open(device: BluetoothDevice): BluetoothGatt? {
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
            val ok = withTimeoutOrNull(CONNECT_MS) { ready.await() } == true
            return gatt.takeIf { ok }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> if (!g.discoverServices()) ready.complete(false)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    ready.complete(false)
                    ops.close()
                    notes.close()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            ready.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            ops.trySend(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ops.trySend(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            notes.trySend(value)
        }

        @Deprecated("Before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) notes.trySend(ch.value ?: return)
        }

        @Suppress("DEPRECATION")
        suspend fun write(ch: BluetoothGattCharacteristic, value: ByteArray): Boolean {
            val g = gatt ?: return false
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = value
                g.writeCharacteristic(ch)
            }
            return started && awaitOp()
        }

        @Suppress("DEPRECATION")
        suspend fun enableNotifications(ch: BluetoothGattCharacteristic): Boolean {
            val g = gatt ?: return false
            if (!g.setCharacteristicNotification(ch, true)) return false
            val cccd = ch.getDescriptor(MiScaleProtocol.CCCD) ?: return false
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
            return started && awaitOp()
        }

        private suspend fun awaitOp(): Boolean =
            withTimeoutOrNull(OP_MS) { ops.receiveCatching().getOrNull() } == BluetoothGatt.GATT_SUCCESS

        fun close() {
            gatt?.let { runCatching { it.disconnect(); it.close() } }
            gatt = null
        }
    }

    private companion object {
        const val PREFS = "mi_scale"
        const val KEY_USER = "user_id"
        const val KEY_ADDRESS = "address"
        const val SEARCH_MS = 90_000L
        const val CONNECT_MS = 15_000L
        const val OP_MS = 5_000L
        const val QUIET_MS = 6_000L
        const val HISTORY_MS = 40_000L
        val CLOCK_SLACK: Duration = Duration.ofMinutes(10)
    }
}
