package com.sensirion.sps30.data

import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * The plot, on this device, so that closing the app does not lose it.
 *
 * ## What this is, and what it deliberately is not
 *
 * It is a **cache**, not a record. The controller is the record: it holds days
 * of readings in a ring and hands back whatever this device asks for. What this
 * file changes is only where the app *starts* from - with it, reopening the app
 * shows what was on screen immediately instead of waiting on a transfer, and the
 * reconnect backfill then fills what is genuinely missing exactly as it did
 * before. [TimeSeriesHistory.coverage] and [TimeSeriesHistory.receivedRows] are
 * what that backfill reasons from, so both are restored here; a cache that put
 * the readings back but not the totals would make a complete session look
 * incomplete and get it asked for again on every connect, which is a bug this
 * project has already had once.
 *
 * ## The format, and why it is a journal
 *
 * An **append-only journal of CRC-guarded records**, compacted by writing a
 * fresh file and renaming it over this one. That shape is chosen for one
 * property: *no write can damage what is already there*.
 *
 * - A reading costs one small append. Nothing already written is read, moved or
 *   rewritten, so there is no window in which the file is half-updated.
 * - Every record carries a CRC-32 over its own bytes. A write cut off by a crash
 *   or a full disk leaves a torn record at the end, which fails its checksum and
 *   is discarded on the next read; everything before it is untouched. That is
 *   the whole of "a partial write leaves the previous state intact".
 * - Compaction never writes into this file. It builds a complete new one beside
 *   it and renames, which is atomic within a directory: a crash at any point
 *   leaves either the old journal or the new one, and both are whole.
 * - A write that fails - the disk is full - truncates back to the last committed
 *   length and gives up caching for the rest of the session ([degraded]). It
 *   never tries to repair the file by rewriting it, because rewriting is the one
 *   operation that could lose what was already safe.
 *
 * This mirrors the rule the controller's own storage is built on, for the same
 * reason: consumers pull plugs, and the answer is a shape where power-fail
 * safety is a property of the format rather than of a shutdown ritual.
 *
 * ## Time that is not yet known
 *
 * A controller with no clock records against its own, and those readings are
 * re-placed whenever an equivalence arrives. So the journal stores the raw
 * `(boot, relMs)` beside the epoch and keeps [TimeBasis] with it, and on restore
 * the epoch is *recomputed* rather than trusted. A cached relative session
 * therefore survives being re-anchored later, which a file of baked timestamps
 * could not.
 *
 * Not thread-safe. It is driven from the same single-threaded dispatcher as the
 * history it mirrors, and it reads that history directly when it compacts.
 */
class PlotCache(
    private val file: File,
    private val history: TimeSeriesHistory,
    private val basis: TimeBasis,
    /** See [MAX_POINTS]. */
    private val maxPoints: Int = MAX_POINTS,
    /** See [COMPACT_AT_BYTES]. */
    private val compactAtBytes: Long = COMPACT_AT_BYTES,
) {

    /**
     * True once a write has failed and caching has been given up on.
     *
     * The plot itself is unaffected - the readings are in memory and on the
     * controller - and the journal is left holding whatever it held before the
     * failed write.
     */
    var degraded: Boolean = false
        private set

    /** Why, for the log. */
    var lastError: String? = null
        private set

    /** Readings put back by the last [load]. */
    var restored: Int = 0
        private set

    private var out: RandomAccessFile? = null

    /** Length of the journal that has been written through to the file. */
    private var committed: Long = 0

    private val pending = ByteArrayOutputStream(WRITE_CHUNK_BYTES)

    /** Writer-side string dictionary: a name is written once and then referred to. */
    private val dict = HashMap<String, Int>()
    private var nextStringId = 0

    /** What has already been written, so only changes are appended. */
    private val writtenReceived = HashMap<String, Int>()
    private var writtenFloor: ByteArray? = null
    private var writtenBasis: ByteArray? = null

    private var pointsInFile: Long = 0
    private var bytesSinceSync: Long = 0

    // --- reading it back -----------------------------------------------------

    /**
     * Read the journal into the history, repairing a torn tail if there is one.
     *
     * Two passes over the file. The first collects the string dictionary, the
     * time basis, the retention floor and the per-session totals, and finds where
     * the good records stop; the second puts the readings back, now that
     * everything needed to place them is known. Two cheap sequential reads beat
     * holding a couple of hundred thousand decoded points on the heap while
     * waiting to find out what the last record says about time.
     *
     * A file that is not this format, or whose header does not match this build,
     * is discarded rather than guessed at. Nothing is lost by that: the
     * controller still holds the readings.
     *
     * @return how many readings were put back.
     */
    fun load(): Int {
        restored = 0
        // A compaction that was interrupted leaves one of these. It was never in
        // play - the rename is what would have made it the cache - so it is
        // debris, and leaving it costs the user however much it had written.
        File(file.parentFile, file.name + NEW_SUFFIX).delete()
        if (!file.exists() || !readableHeader()) {
            file.delete()
            openForAppend(fresh = true)
            history.onStored = listener
            return 0
        }

        val names = HashMap<Int, String>()
        val received = HashMap<String, Int>()
        var floor: Double? = null
        var basisState: TimeBasis.State? = null
        var points = 0L

        // Every branch checks its own length. A record that passed its checksum
        // and is still the wrong size is not something this writer produces, and
        // that is exactly why it is checked rather than assumed: the alternative
        // is an exception out of a file read, on the startup path.
        val good = scan { type, payload ->
            when {
                type == REC_STRING && payload.size >= 4 -> {
                    val id = ByteBuffer.wrap(payload).int
                    names[id] = String(payload, 4, payload.size - 4, Charsets.UTF_8)
                }
                type == REC_POINT && payload.size >= POINT_BYTES -> points++
                type == REC_RECEIVED && payload.size >= 8 -> {
                    val b = ByteBuffer.wrap(payload)
                    val name = names[b.int]
                    val total = b.int
                    if (name != null) received[name] = total
                }
                type == REC_FLOOR && payload.size >= 9 -> {
                    val b = ByteBuffer.wrap(payload)
                    floor = if (b.get().toInt() != 0) b.double else null
                }
                type == REC_BASIS -> basisState = decodeBasis(payload)
            }
        }

        basisState?.let { basis.restore(it) }
        // Oldest first is the order a bounded cache drops in, so a journal that
        // has outgrown the cap gives up its front. The compaction below then
        // rewrites it in time order, which is the exact version of the same bound.
        var skip = if (points > maxPoints) points - maxPoints else 0L
        val values = DoubleArray(FIELDS)
        scan { type, payload ->
            if (type != REC_POINT || payload.size < POINT_BYTES) return@scan
            if (skip > 0) {
                skip--
                return@scan
            }
            val b = ByteBuffer.wrap(payload)
            val epoch = b.double
            val csv = names[b.int] ?: return@scan
            val index = b.int
            val event = names[b.int] ?: ""
            val relBoot = b.int
            val relMs = b.long
            for (f in 0 until FIELDS) values[f] = b.double
            if (history.restore(csv, index, epoch, values, event, relBoot, relMs)) restored++
        }
        history.finishRestore(received, floor)

        openForAppend(fresh = false, truncateTo = good)
        // Everything above was read out of the file, so it is already written.
        for ((id, name) in names) dict[name] = id
        nextStringId = (names.keys.maxOrNull() ?: -1) + 1
        writtenReceived.putAll(received)
        writtenFloor = encodeFloor(floor)
        writtenBasis = encodeBasis(basisState ?: EMPTY_BASIS)
        pointsInFile = points

        history.onStored = listener
        if (points > maxPoints || committed > compactAtBytes) compact()
        return restored
    }

    /** True if the file starts with a header this build can read. */
    private fun readableHeader(): Boolean = try {
        FileInputStream(file).use { input ->
            val head = ByteArray(HEADER_BYTES)
            if (!readFully(input, head, head.size)) {
                false
            } else {
                var ok = true
                for (i in MAGIC.indices) if (head[i] != MAGIC[i]) ok = false
                ok && head[MAGIC.size].toInt() == VERSION && head[MAGIC.size + 1].toInt() == FIELDS
            }
        }
    } catch (e: IOException) {
        false
    }

    /**
     * Walk every intact record, and return the offset just past the last of them.
     *
     * Stops at the first record that is short, over-long or fails its checksum,
     * which is what a write cut off half way through leaves behind. Everything
     * before that point is as good as it ever was.
     */
    private fun scan(visit: (Int, ByteArray) -> Unit): Long {
        var offset = HEADER_BYTES.toLong()
        try {
            BufferedInputStream(FileInputStream(file), 64 * 1024).use { input ->
                if (!readFully(input, ByteArray(HEADER_BYTES), HEADER_BYTES)) return offset
                val frame = ByteArray(5)
                while (true) {
                    if (!readFully(input, frame, frame.size)) return offset
                    val type = frame[0].toInt()
                    val length = ByteBuffer.wrap(frame, 1, 4).int
                    if (length < 0 || length > MAX_RECORD_BYTES) return offset
                    val payload = ByteArray(length)
                    if (!readFully(input, payload, length)) return offset
                    val tail = ByteArray(4)
                    if (!readFully(input, tail, 4)) return offset
                    val crc = CRC32()
                    crc.update(frame)
                    crc.update(payload)
                    if (crc.value.toInt() != ByteBuffer.wrap(tail).int) return offset
                    visit(type, payload)
                    offset += 5L + length + 4L
                }
            }
        } catch (e: IOException) {
            return offset
        }
    }

    private fun readFully(input: InputStream, into: ByteArray, count: Int): Boolean {
        var read = 0
        while (read < count) {
            val n = input.read(into, read, count - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    // --- writing it ----------------------------------------------------------

    private val listener = TimeSeriesHistory.StoredListener {
            csv, index, epoch, values, event, relBoot, relMs ->
        if (!degraded) {
            val payload = ByteBuffer.allocate(POINT_BYTES)
            payload.putDouble(epoch)
            payload.putInt(stringId(csv))
            payload.putInt(index)
            payload.putInt(stringId(event))
            payload.putInt(relBoot)
            payload.putLong(relMs)
            for (f in 0 until FIELDS) payload.putDouble(values.getOrElse(f) { 0.0 })
            record(REC_POINT, payload.array())
            pointsInFile++
        }
    }

    /**
     * Write what has accumulated, and note anything else that has changed.
     *
     * Called on the same tick that redraws the chart. The per-session totals, the
     * retention floor and the time basis are each written only when they differ
     * from what is already in the file, so a second of live streaming costs one
     * reading record and one eight-byte total rather than a copy of everything
     * the app knows.
     *
     * `fsync` is not asked for on every call. Process death - the case this whole
     * feature exists for - is survived by the write itself, because the kernel
     * holds the page whether or not this process is still alive. Syncing only
     * buys the power-cut case, and buying it once a second for the life of the
     * app would cost far more of the tablet's flash than a few seconds of
     * readings are worth, which the controller hands straight back anyway.
     */
    fun flush() {
        if (degraded) return
        noteReceived()
        noteFloor()
        noteBasis()
        writeThrough(force = true)
        maybeCompact()
    }

    /**
     * Forget everything, because the plot was cleared.
     *
     * Truncating rather than deleting keeps the file's identity - and its header
     * - so nothing has to reason about a cache that half exists.
     */
    fun reset() {
        dict.clear()
        nextStringId = 0
        writtenReceived.clear()
        writtenFloor = null
        writtenBasis = null
        pending.reset()
        pointsInFile = 0
        if (degraded) return
        val handle = out ?: return
        try {
            handle.setLength(HEADER_BYTES.toLong())
            committed = HEADER_BYTES.toLong()
            handle.fd.sync()
            bytesSinceSync = 0
        } catch (e: IOException) {
            giveUp("could not clear the plot cache: ${e.message}", handle)
        }
    }

    /** Stop caching and let the file go. */
    fun close() {
        history.onStored = null
        closeFile()
    }

    private fun closeFile() {
        val handle = out ?: return
        try {
            handle.fd.sync()
        } catch (e: IOException) {
            // Nothing useful to do about it while letting the file go.
        }
        try {
            handle.close()
        } catch (e: IOException) {
            // As above.
        }
        out = null
    }

    // --- what changed since the last flush ------------------------------------

    private fun noteReceived() {
        for ((csv, total) in history.receivedCounts()) {
            if (writtenReceived[csv] == total) continue
            val payload = ByteBuffer.allocate(8)
            payload.putInt(stringId(csv))
            payload.putInt(total)
            record(REC_RECEIVED, payload.array())
            writtenReceived[csv] = total
        }
    }

    private fun noteFloor() {
        val encoded = encodeFloor(history.retentionFloor())
        if (encoded.contentEquals(writtenFloor)) return
        record(REC_FLOOR, encoded)
        writtenFloor = encoded
    }

    private fun noteBasis() {
        val encoded = encodeBasis(basis.snapshot())
        if (encoded.contentEquals(writtenBasis)) return
        record(REC_BASIS, encoded)
        writtenBasis = encoded
    }

    private fun stringId(value: String): Int = dict.getOrPut(value) {
        val id = nextStringId++
        val utf8 = value.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(4 + utf8.size)
        ByteBuffer.wrap(payload).putInt(id)
        System.arraycopy(utf8, 0, payload, 4, utf8.size)
        record(REC_STRING, payload)
        id
    }

    private fun record(type: Int, payload: ByteArray) {
        val frame = ByteArray(5)
        frame[0] = type.toByte()
        ByteBuffer.wrap(frame, 1, 4).putInt(payload.size)
        val crc = CRC32()
        crc.update(frame)
        crc.update(payload)
        pending.write(frame)
        pending.write(payload)
        val tail = ByteArray(4)
        ByteBuffer.wrap(tail).putInt(crc.value.toInt())
        pending.write(tail)
        // Bounded buffering, so compacting a full cache does not put nineteen
        // megabytes of records on the heap before any of them reach the file.
        writeThrough(force = false)
    }

    /** Append the buffered records. [force] writes whatever there is. */
    private fun writeThrough(force: Boolean) {
        if (degraded) return
        if (pending.size() == 0) return
        if (!force && pending.size() < WRITE_CHUNK_BYTES) return
        val handle = out ?: return
        val bytes = pending.toByteArray()
        pending.reset()
        try {
            handle.seek(committed)
            handle.write(bytes)
            committed += bytes.size
            bytesSinceSync += bytes.size
            if (bytesSinceSync >= SYNC_EVERY_BYTES) {
                handle.fd.sync()
                bytesSinceSync = 0
            }
        } catch (e: IOException) {
            giveUp("could not write the plot cache: ${e.message}", handle)
        }
    }

    // --- compaction ----------------------------------------------------------

    private fun maybeCompact() {
        if (degraded) return
        if (committed <= compactAtBytes && pointsInFile <= maxPoints * 2L) return
        compact()
    }

    /**
     * Write a fresh journal holding only what the plot still shows, and put it in
     * place with a rename.
     *
     * The old journal is not touched until the new one is complete and on disk,
     * so a crash anywhere in here leaves a whole file either way. Which of the
     * two survives a crash in the instant after the rename depends on the
     * filesystem's own ordering of the directory entry, and either answer is a
     * state the app can open.
     */
    private fun compact() {
        val temp = File(file.parentFile, file.name + NEW_SUFFIX)
        val fresh = PlotCache(temp, history, basis, maxPoints, compactAtBytes)
        try {
            temp.delete()
            fresh.openForAppend(fresh = true)
            if (fresh.degraded) throw IOException(fresh.lastError)
            fresh.noteBasis()
            fresh.noteFloor()
            fresh.noteReceived()
            history.forEachCacheable(maxPoints, fresh.listener)
            fresh.writeThrough(force = true)
            fresh.syncFile()
            if (fresh.degraded) throw IOException(fresh.lastError)
        } catch (e: IOException) {
            fresh.closeFile()
            temp.delete()
            lastError = "could not compact the plot cache: ${e.message}"
            return
        }
        val carriedDict = HashMap(fresh.dict)
        val carriedNextId = fresh.nextStringId
        val carriedReceived = HashMap(fresh.writtenReceived)
        val carriedFloor = fresh.writtenFloor
        val carriedBasis = fresh.writtenBasis
        val carriedPoints = fresh.pointsInFile
        val carriedLength = fresh.committed
        fresh.closeFile()

        closeFile()
        if (!temp.renameTo(file)) {
            temp.delete()
            lastError = "could not put the compacted plot cache in place"
            // The old journal is still exactly as it was, so carry on appending.
            openForAppend(fresh = false, truncateTo = committed)
            return
        }
        dict.clear()
        dict.putAll(carriedDict)
        nextStringId = carriedNextId
        writtenReceived.clear()
        writtenReceived.putAll(carriedReceived)
        writtenFloor = carriedFloor
        writtenBasis = carriedBasis
        pointsInFile = carriedPoints
        pending.reset()
        openForAppend(fresh = false, truncateTo = carriedLength)
    }

    /** Compaction has to know the new file is whole on disk before it renames. */
    private fun syncFile() {
        val handle = out ?: return
        try {
            handle.fd.sync()
            bytesSinceSync = 0
        } catch (e: IOException) {
            giveUp("could not sync the plot cache: ${e.message}", handle)
        }
    }

    // --- the file itself -----------------------------------------------------

    private fun openForAppend(fresh: Boolean, truncateTo: Long = 0) {
        try {
            file.parentFile?.mkdirs()
            val handle = RandomAccessFile(file, "rw")
            if (fresh) {
                handle.setLength(0)
                val head = ByteArray(HEADER_BYTES)
                System.arraycopy(MAGIC, 0, head, 0, MAGIC.size)
                head[MAGIC.size] = VERSION.toByte()
                head[MAGIC.size + 1] = FIELDS.toByte()
                handle.write(head)
                committed = HEADER_BYTES.toLong()
            } else {
                // Cut off the torn tail, if the last write did not finish. This is
                // the only place the file is ever shortened, and it removes only
                // bytes that failed their own checksum.
                if (handle.length() != truncateTo) handle.setLength(truncateTo)
                committed = truncateTo
            }
            out = handle
        } catch (e: IOException) {
            degraded = true
            lastError = "could not open the plot cache: ${e.message}"
            out = null
        }
    }

    /**
     * Stop caching, leaving the file holding exactly what it held before the
     * write that failed.
     *
     * Truncating back to [committed] is the only repair attempted, and it removes
     * only bytes this call was in the middle of writing. If even that fails the
     * torn record at the end fails its checksum on the next read, which is the
     * same outcome by a slower route.
     */
    private fun giveUp(why: String, handle: RandomAccessFile) {
        degraded = true
        lastError = why
        pending.reset()
        try {
            handle.setLength(committed)
            handle.fd.sync()
        } catch (e: IOException) {
            // See above: the checksum is the backstop.
        }
    }

    // --- record payloads -----------------------------------------------------

    private fun encodeFloor(floor: Double?): ByteArray {
        val payload = ByteBuffer.allocate(9)
        payload.put(if (floor == null) 0 else 1)
        payload.putDouble(floor ?: 0.0)
        return payload.array()
    }

    private fun encodeBasis(state: TimeBasis.State): ByteArray {
        val size = 4 + 4 + state.anchors.size * 24 +
            4 + state.manualOrigins.size * 12 +
            4 + state.firstRelMs.size * 12
        val payload = ByteBuffer.allocate(size)
        payload.putInt(state.currentBoot)
        payload.putInt(state.anchors.size)
        for (a in state.anchors) {
            payload.putInt(a.boot)
            payload.putLong(a.relMs)
            payload.putLong(a.epochMs)
            payload.putInt(a.utcOffsetMinutes)
        }
        payload.putInt(state.manualOrigins.size)
        for ((boot, epoch) in state.manualOrigins) {
            payload.putInt(boot)
            payload.putDouble(epoch)
        }
        payload.putInt(state.firstRelMs.size)
        for ((boot, relMs) in state.firstRelMs) {
            payload.putInt(boot)
            payload.putLong(relMs)
        }
        return payload.array()
    }

    private fun decodeBasis(payload: ByteArray): TimeBasis.State? = try {
        val b = ByteBuffer.wrap(payload)
        val currentBoot = b.int
        val anchors = ArrayList<ServerMessage.TimeAnchor>()
        repeat(b.int) {
            anchors.add(
                ServerMessage.TimeAnchor(
                    boot = b.int, relMs = b.long, epochMs = b.long, utcOffsetMinutes = b.int,
                )
            )
        }
        val manual = LinkedHashMap<Int, Double>()
        repeat(b.int) { manual[b.int] = b.double }
        val first = LinkedHashMap<Int, Long>()
        repeat(b.int) { first[b.int] = b.long }
        TimeBasis.State(currentBoot, anchors, manual, first)
    } catch (e: java.nio.BufferUnderflowException) {
        // A record that passed its checksum and still does not decode is not
        // something that happens; treating it as absent keeps that assumption
        // from being load-bearing.
        null
    }

    companion object {
        /** The file, inside the app's own storage. */
        const val FILE_NAME = "plot-cache.journal"

        /** What a half-written compaction is called, so it is never mistaken for one. */
        private const val NEW_SUFFIX = ".new"

        private val MAGIC = "SPS30PLOT".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        private val FIELDS = Wire.PM_FIELDS.size
        private val HEADER_BYTES = MAGIC.size + 2

        private const val REC_STRING = 1
        private const val REC_POINT = 2
        private const val REC_RECEIVED = 3
        private const val REC_FLOOR = 4
        private const val REC_BASIS = 5

        /** epoch, csv, index, event, boot, relMs, then the PM values. */
        private val POINT_BYTES = 8 + 4 + 4 + 4 + 4 + 8 + 8 * FIELDS

        /**
         * The most any single record may claim to be, so a corrupt length cannot
         * ask for an array the size of the heap. The largest record this writes
         * is the time basis, and a controller would need forty thousand
         * equivalences to reach even a megabyte.
         */
        private const val MAX_RECORD_BYTES = 4 * 1024 * 1024

        /**
         * The most readings kept on this device.
         *
         * 262,144 is the whole capacity of the ESP32's `store` partition - 2.5 MB
         * at the ten bytes a reading costs there (`STORAGE_FORMAT.md`) - so the
         * cache can hold *everything the controller could ever hand over*, and
         * reopening the app never needs a transfer to show what was on screen. It
         * is also three days of continuous 1 Hz recording against the plot's own
         * 48-hour retention window, so in ordinary use the bound that bites is
         * retention and not this.
         *
         * At [POINT_BYTES] plus nine bytes of framing that is about 19 MB of the
         * tablet's own storage, small beside the archives the app already writes
         * to Downloads. Past it the oldest readings are dropped: unlike the
         * controller's ring this is a cache, and the controller is the record.
         */
        const val MAX_POINTS = 262_144

        /**
         * Compact once the journal passes this, which is about twice what a full
         * cache compacts down to.
         *
         * A journal only grows: retention dropping a reading from the plot does
         * not remove it from the file. Compaction is what makes the bound real,
         * and it costs a full rewrite, so it wants to be rare - at a reading a
         * second this is days of streaming apart, and immediately after a "copy
         * everything" that fetched more than the cap.
         */
        const val COMPACT_AT_BYTES = 40L * 1024 * 1024

        /** How much is buffered before it is handed to the file. */
        private const val WRITE_CHUNK_BYTES = 64 * 1024

        /** How much may go unsynced; see [flush] for why this is not zero. */
        private const val SYNC_EVERY_BYTES = 512L * 1024

        private val EMPTY_BASIS = TimeBasis.State(0, emptyList(), emptyMap(), emptyMap())
    }
}
