package com.sensirion.sps30.net

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
import android.os.ParcelUuid
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.mustNotBeDropped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Bluetooth-LE transport carrying the SAME newline-delimited JSONL as the TCP path,
 * over a Nordic-UART-Service (NUS) characteristic pair. Chosen so it works on both the
 * Pi (BlueZ) and the future ESP32-C6 (BLE-only). See BLUETOOTH_DESIGN.md.
 *
 * Uses the deprecated (but still functional through API 35) synchronous GATT value
 * methods to avoid API-33 vs older branching; the app gates the BLUETOOTH_SCAN/CONNECT
 * permissions before this runs, hence @SuppressLint("MissingPermission").
 */
@SuppressLint("MissingPermission")
class BleTransport(private val appContext: Context) : Transport {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<ServerMessage>(replay = 0, extraBufferCapacity = 512)
    override val messages: SharedFlow<ServerMessage> = _messages.asSharedFlow()

    private val outbound = Channel<String>(capacity = 64)
    override fun send(line: String) { outbound.trySend(line) }
    override suspend fun sendAwait(line: String) { outbound.send(line) }

    private val bluetooth by lazy {
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    /**
     * Whether this link has ever had a sensor on the other end of it.
     *
     * It is what decides whether the search is bounded. A controller that has
     * answered once is known to be real, so a link that is re-establishing itself
     * keeps trying for as long as the app wants it - being carried out of range for
     * a minute must not end the connection. A link that has never answered is the
     * case the bound exists for: nothing is known to be there at all.
     */
    @Volatile private var everAnswered = false

    override suspend fun run() {
        val adapter = bluetooth.adapter
        while (currentCoroutineContext().isActive) {
            val device = search(adapter, if (everAnswered) null else SEARCH_BUDGET_MS)
            if (device == null) {
                // The budget is spent and nothing answered. Stop scanning, and say
                // so in a way the caller can act on rather than simply returning:
                // `stopped` is what keeps it from starting the whole search again.
                _state.value = ConnectionState.NotFound(NOTHING_ANSWERED, stopped = true)
                return
            }
            _state.value = ConnectionState.Connecting(device.name ?: device.address)
            try {
                connectAndStream(device)
                _state.value = ConnectionState.Reconnecting("disconnected")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ConnectionState.Reconnecting(e.message ?: "BLE error")
            }
            delay(RETRY_DELAY_MS)
        }
    }

    /**
     * Scan in bursts until the sensor answers or [budgetMs] runs out.
     *
     * Null [budgetMs] means "until cancelled"; the rule and the reasoning behind
     * both are in [searchWithin].
     */
    private suspend fun search(
        adapter: android.bluetooth.BluetoothAdapter?,
        budgetMs: Long?,
    ): BluetoothDevice? = searchWithin(
        budgetMs = budgetMs,
        scanWindowMs = SCAN_TIMEOUT_MS,
        firstPauseMs = FIRST_PAUSE_MS,
        maxPauseMs = MAX_PAUSE_MS,
        now = System::currentTimeMillis,
        pause = { delay(it) },
    ) { window ->
        // A radio that is switched off spends the budget the same way an empty
        // scan does, so this cannot become its own endless loop either.
        if (adapter == null || !adapter.isEnabled) {
            _state.value = ConnectionState.NotFound("Bluetooth is off")
            null
        } else {
            _state.value = ConnectionState.Searching("scanning for the sensor")
            scanForDevice(adapter.bluetoothLeScanner, window).also {
                if (it == null) _state.value = ConnectionState.NotFound(NOTHING_ANSWERED)
            }
        }
    }

    /** Scan for the first advertiser exposing the NUS service; null on timeout. */
    private suspend fun scanForDevice(
        scanner: android.bluetooth.le.BluetoothLeScanner?,
        windowMs: Long,
    ): BluetoothDevice? {
        if (scanner == null) return null
        return withTimeoutOrNull(windowMs) {
            suspendCancellableCoroutine { cont ->
                val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(NUS_SERVICE)).build()
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (cont.isActive) {
                            runCatching { scanner.stopScan(this) }
                            cont.resume(result.device)
                        }
                    }
                }
                scanner.startScan(listOf(filter), settings, cb)
                cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
            }
        }
    }

    /** Connect, discover NUS, raise MTU, enable notifications, then pump both ways
     *  until the peer disconnects or the coroutine is cancelled. */
    private suspend fun connectAndStream(device: BluetoothDevice) = coroutineScope {
        val events = Channel<Ev>(Channel.UNLIMITED)
        // Write completions go to the writer, not through `events`, because the
        // reader loop consumes that channel and would swallow them.
        val writeDone = Channel<Int>(Channel.CONFLATED)
        var rx: BluetoothGattCharacteristic? = null
        var tx: BluetoothGattCharacteristic? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                events.trySend(if (newState == BluetoothProfile.STATE_CONNECTED) Ev.Connected else Ev.Disconnected)
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { events.trySend(Ev.Services) }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { events.trySend(Ev.Mtu) }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                events.trySend(Ev.NotifyReady)
            }
            /*
             * Two overloads, and the difference is not cosmetic.
             *
             * The deprecated one hands over the characteristic and expects the
             * value to be read back off it. That value is a single mutable
             * buffer the stack overwrites with the next notification, so when
             * frames arrive back to back - which is every bulk transfer - a
             * frame can be replaced before this callback has read it. The line
             * it belonged to then fails to parse and `JsonlReassembler` drops
             * it, silently, because a frame that will not parse is
             * indistinguishable from noise.
             *
             * Measured: a copy over Bluetooth lost 13 rows of one session -
             * one 2 KiB `file_data` frame - with everything else byte-perfect.
             *
             * API 33 added the overload that delivers an immutable copy. The
             * tablet is API 33; older devices keep the deprecated path, where
             * the race exists but the transfer rate that provokes it does not.
             */
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (c.uuid == NUS_TX) events.trySend(Ev.Rx(value))
            }

            @Deprecated("superseded on API 33+ by the overload that copies the value")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                // Only reached below API 33: on 33+ the overload above is called
                // instead, and it does not delegate here.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) return
                if (c.uuid == NUS_TX) c.value?.let { events.trySend(Ev.Rx(it)) }
            }
            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (c.uuid == NUS_RX) writeDone.trySend(status)
            }
        }

        val gatt = device.connectGatt(appContext, false, callback)
        try {
            suspend fun await(target: Ev, timeout: Long = OP_TIMEOUT_MS): Boolean {
                val ev = withTimeoutOrNull(timeout) {
                    var e = events.receive()
                    while (e != target && e !is Ev.Disconnected) e = events.receive()
                    e
                }
                if (ev is Ev.Disconnected) throw java.io.IOException("disconnected during setup")
                return ev != null
            }

            if (!await(Ev.Connected, CONNECT_TIMEOUT_MS)) throw java.io.IOException("connect timeout")
            if (!gatt.discoverServices()) throw java.io.IOException("discoverServices failed")
            if (!await(Ev.Services)) throw java.io.IOException("service discovery timeout")

            val svc = gatt.getService(NUS_SERVICE) ?: throw java.io.IOException("NUS service not found")
            rx = svc.getCharacteristic(NUS_RX); tx = svc.getCharacteristic(NUS_TX)
            if (rx == null || tx == null) throw java.io.IOException("NUS characteristics missing")

            gatt.requestMtu(PREFERRED_MTU); await(Ev.Mtu) // best-effort; ignore result

            gatt.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD) ?: throw java.io.IOException("no CCCD on TX")
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
            if (!await(Ev.NotifyReady)) throw java.io.IOException("enable-notify timeout")

            everAnswered = true
            _state.value = ConnectionState.Connected(device.name ?: device.address)

            // writer: drain outbound command lines to the RX characteristic
            val writer = launch {
                for (line in outbound) writeLine(gatt, rx!!, line, writeDone)
            }
            try {
                /*
                 * `tryEmit` alone drops a message when the collector is behind,
                 * and that is right for the live stream: a skipped reading is
                 * invisible a second later, and stalling the link to deliver a
                 * stale one would be worse. It is wrong for archive content,
                 * where a skipped frame is rows missing from the user's file,
                 * arriving silently. `mustNotBeDropped` draws that line, and
                 * `DaemonClient` has honoured it on the TCP path since the
                 * transfer work landed. This did not, and the difference showed
                 * on hardware: a copy over Bluetooth reported success having
                 * written 306 of one session's 1,509 rows.
                 *
                 * Bluetooth makes it worse than TCP rather than differently:
                 * the board's notifications back up while the mbuf pool is
                 * full, then arrive in a burst when it clears, so the collector
                 * falls behind exactly when the frames matter most.
                 *
                 * `emit` suspends this reader instead of discarding. Nothing is
                 * lost by waiting - the events channel is unbounded and the
                 * board is paced by the link either way.
                 */
                val pending = ArrayList<ServerMessage>()
                val reassembler = JsonlReassembler { pending.add(it) }
                while (true) {
                    when (val ev = events.receive()) {
                        is Ev.Rx -> {
                            reassembler.feed(ev.bytes)
                            for (m in pending) {
                                if (m.mustNotBeDropped()) _messages.emit(m)
                                else _messages.tryEmit(m)
                            }
                            pending.clear()
                        }
                        is Ev.Disconnected -> break
                        else -> Unit
                    }
                }
            } finally {
                writer.cancel()
            }
        } finally {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    /**
     * Write one command line, split into MTU-sized chunks if needed.
     *
     * **One GATT operation at a time.** Android's stack permits a single
     * outstanding characteristic operation per connection; ask for a second
     * while the first is in flight and `writeCharacteristic` returns `false`
     * and does nothing at all. It reports that in a boolean and nowhere else.
     *
     * Ignoring that return - which this did - loses whole commands, silently,
     * and only sometimes: a command sent in the quiet moment after subscribing
     * goes out, and one sent while a reading is arriving does not. That is
     * exactly the shape of the bug it caused. `identify` worked on every
     * connection, so the link looked healthy, while `list_files` tapped a
     * minute later vanished and the app sat on "Asking the sensor what it
     * has..." forever.
     *
     * So: retry while the stack says busy, and wait for the completion callback
     * before offering the next chunk. A dropped command is not recoverable at
     * any higher layer - nothing acknowledges commands - so it has to not
     * happen here.
     */
    @Suppress("DEPRECATION")
    private suspend fun writeLine(
        gatt: BluetoothGatt,
        rx: BluetoothGattCharacteristic,
        line: String,
        writeDone: Channel<Int>,
    ) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        var off = 0
        while (off < bytes.size) {
            val end = minOf(off + WRITE_CHUNK, bytes.size)
            rx.value = bytes.copyOfRange(off, end)
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            // A completion left over from an abandoned write would otherwise be
            // mistaken for this one's.
            while (writeDone.tryReceive().isSuccess) { /* drain */ }

            var attempts = 0
            while (!gatt.writeCharacteristic(rx)) {
                if (++attempts >= WRITE_ATTEMPTS) {
                    throw java.io.IOException("the Bluetooth stack stayed busy for a write")
                }
                delay(WRITE_BUSY_RETRY_MS)
            }
            // Not fatal if it times out: the write may still land, and dropping
            // the connection over it would be worse than a slow command.
            withTimeoutOrNull(OP_TIMEOUT_MS) { writeDone.receive() }
            off = end
        }
    }

    private sealed interface Ev {
        data object Connected : Ev
        data object Disconnected : Ev
        data object Services : Ev
        data object Mtu : Ev
        data object NotifyReady : Ev
        data class Rx(val bytes: ByteArray) : Ev
    }

    companion object {
        // Nordic UART Service (the de-facto "BLE serial"); identical on Pi + ESP32-C6.
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // write (phone->host)
        val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // notify (host->phone)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val PREFERRED_MTU = 247
        const val WRITE_CHUNK = 180 // conservative < negotiated MTU
        /** How many times to re-offer a write the stack refused as busy. */
        const val WRITE_ATTEMPTS = 40
        const val WRITE_BUSY_RETRY_MS = 25L
        /**
         * One scan window - a maximum, not a cost: a scan ends the moment the
         * sensor advertises, so this only bounds the case where nothing is there.
         *
         * Eight seconds rather than the fifteen this used to be, because with a
         * bounded search the window is no longer the whole story. An advertiser
         * that is present at all is seen within a second or two (BLE advertising
         * intervals are tens to hundreds of milliseconds); the reason to keep
         * looking is a controller that has not started advertising *yet*, and four
         * short windows spread across the minute catch that better than two long
         * ones while running the radio for half as long.
         */
        const val SCAN_TIMEOUT_MS = 8_000L

        /**
         * How long a first search may keep going before it stops.
         *
         * A minute, and the figure is one the captain named: long enough to ride
         * out a controller that is slow to advertise or drifting in and out of
         * range, short enough that choosing Bluetooth with nothing to find does not
         * leave the radio scanning until the app is closed.
         *
         * The minute is not spent scanning. With the window and pauses below it
         * comes out as four scans of 8 s separated by 4, 8 and 12 s of quiet - 32
         * seconds of radio in 60, and four separate chances to see a late
         * advertiser. See [searchWithin].
         */
        const val SEARCH_BUDGET_MS = 60_000L

        /** The gap after the first fruitless scan; it doubles up to [MAX_PAUSE_MS]. */
        const val FIRST_PAUSE_MS = 4_000L
        const val MAX_PAUSE_MS = 12_000L

        /** The gap after an established link drops, before looking again. */
        const val RETRY_DELAY_MS = 1_000L

        /**
         * What an empty search is called. Deliberately not "no SPS30 device found":
         * the banner already leads with the transport, so naming the sensor rather
         * than the part number is both shorter and what the user is looking for.
         */
        const val NOTHING_ANSWERED = "no sensor answered"

        const val CONNECT_TIMEOUT_MS = 12_000L
        const val OP_TIMEOUT_MS = 8_000L
    }
}
