package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Commands
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import java.util.zip.CRC32

/**
 * Backing everything the sensor holds up to this device, and clearing it, block
 * by block.
 *
 * A **separate** path from the erase gate, which is unchanged. The rule that gate
 * is built on is not weakened anywhere here - the sensor still deletes nothing it
 * cannot itself confirm is on this device - but it is applied per block instead
 * of per session, and the two are interleaved:
 *
 * 1. the sensor sends the oldest block it holds
 * 2. this device writes it down and makes it durable
 * 3. this device proves it, by a digest computed from what is now on disk
 * 4. only then does the sensor free that block
 * 5. and on to the next
 *
 * Because space comes back **ahead of the write head**, acquisition carries on
 * throughout into slots it would have used anyway. There is no stop-the-world
 * moment and no window in which readings are dropped.
 *
 * ## The end of it
 *
 * Eventually the oldest block is the one the sensor is writing into. That one is
 * copied and proved like every other and then deliberately **kept** - the sensor
 * answers `kept` rather than `freed` - and becomes the start of its storage. What
 * is left afterwards is a clean, freshly initialised store plus the readings that
 * arrived while the copy was running.
 *
 * ## Interruptions
 *
 * There is nothing to roll back, at any point, by construction:
 *
 * - interrupted **before** [DrainSink.seal] - the block was never confirmed and is
 *   still on the sensor. The next run copies it again.
 * - interrupted **between** sealing and confirming - the block is durable here and
 *   still on the sensor. The next run copies it again, and this device ends up
 *   holding it twice, which costs a little space and loses nothing.
 * - interrupted **after** confirming - the block is durable here, and whether the
 *   sensor got as far as freeing it changes nothing about what exists.
 *
 * The sensor keeps no drain state across a reboot either: "what is the oldest
 * block" is a question it answers from its own flash, so a resumed run continues
 * rather than starting over or redoing a deletion. See `store_drain.h`.
 *
 * ## What this is not
 *
 * It is **not encrypted**, and neither is anything else this app sends or
 * receives. The security design exists (`data/sensirion-security-design-v2`) and
 * is not implemented; this transfer rides on the ordinary transport and is
 * exactly as exposed as the live readings, a backfill or a session copy. It is
 * built to ride on the sealed channel when that lands rather than around it -
 * there is no side channel here, no second socket, and no bespoke framing.
 */
class DeviceDrain(private val sink: DrainSink) {

    /** Where a run has got to, and everything the screen needs to say so. */
    data class Status(
        val phase: Phase = Phase.IDLE,
        /** The sensor's own words, when it had any. */
        val message: String = "",
        /** Why it stopped, when it stopped badly. */
        val reason: String = "",
        val block: Long = 0,
        val kind: String = "",
        /** Blocks the sensor still holds, from its own count of its flash. */
        val blocksLeft: Int = 0,
        /** The most blocks ever seen outstanding, so a bar has a denominator. */
        val blocksAtStart: Int = 0,
        val blocksFreed: Int = 0,
        val readingsCopied: Int = 0,
        val segmentsCopied: Int = 0,
        /** Rows this device has actually written down and made durable. */
        val rowsWritten: Int = 0,
        /** Where the copy is going, once there is somewhere to name. */
        val location: String = "",
    ) {
        val running: Boolean
            get() = phase == Phase.STARTING || phase == Phase.COPYING || phase == Phase.CONFIRMING

        /**
         * 0 to 1, or null before the sensor has said how much there is.
         *
         * Measured against the most blocks ever outstanding rather than a total
         * taken at the start: the sensor keeps recording throughout, so the
         * figure can genuinely go up, and a bar that went backwards would be
         * telling the truth in the least useful way available.
         */
        val fraction: Float?
            get() = if (blocksAtStart <= 0) null else {
                ((blocksAtStart - blocksLeft).toFloat() / blocksAtStart).coerceIn(0f, 1f)
            }
    }

    enum class Phase {
        IDLE,
        /** The claim has been asked for. */
        STARTING,
        /** A block's rows are arriving. */
        COPYING,
        /** A block is written down and the sensor is being told so. */
        CONFIRMING,
        /** Everything is here and the sensor is clear. */
        DONE,
        /** The user stopped it. Whatever is still on the sensor is untouched. */
        CANCELLED,
        /** It stopped badly. Nothing was removed that is not here. */
        FAILED,
    }

    var status: Status = Status()
        private set

    /** The block whose rows are being written, and how many have gone in. */
    private var openBlock: Long = 0
    private var openKind: String = ""

    /** Begin. Nothing on the sensor changes until a block has been proved. */
    fun start(): List<String> {
        if (status.running) return emptyList()
        status = Status(phase = Phase.STARTING, location = sink.location)
        openBlock = 0
        return listOf(Commands.drainBegin())
    }

    /**
     * Stop, at any moment, safely.
     *
     * Whatever the sensor has not been told about is exactly where it was, and
     * the block half-written here is thrown away rather than left to look like a
     * copy. What was already confirmed stays on this device and stays gone from
     * the sensor, which is the operation having done part of its job rather than
     * a half-finished state to recover from.
     */
    fun cancel(): List<String> {
        if (!status.running) return emptyList()
        discardOpenBlock()
        sink.close()
        status = status.copy(
            phase = Phase.CANCELLED,
            reason = "stopped. Everything still on the sensor is exactly where it was",
        )
        return listOf(Commands.drainCancel())
    }

    /**
     * The link went, the app was backgrounded to death, the sensor rebooted.
     *
     * No commands: there may be nothing at the other end to send them to. The
     * block being written is discarded because it was never proved, and the next
     * run will be offered it again.
     */
    fun interrupted(reason: String) {
        if (!status.running) return
        discardOpenBlock()
        sink.close()
        status = status.copy(phase = Phase.FAILED, reason = reason)
    }

    /** One block's rows. */
    fun onData(msg: ServerMessage.DrainData): List<String> {
        if (status.phase != Phase.COPYING && status.phase != Phase.STARTING) return emptyList()
        if (msg.block == 0L) return emptyList()

        if (openBlock != msg.block) {
            // A frame for a block this device is not writing means the previous
            // one never finished. Throw it away rather than splicing two blocks
            // into one file: an unconfirmed block is still on the sensor.
            discardOpenBlock()
            try {
                sink.open(msg.block, msg.kind, columnsFor(msg.kind, msg.columns))
            } catch (e: Exception) {
                return failWrite(e)
            }
            openBlock = msg.block
            openKind = msg.kind
        }

        try {
            for (row in msg.rows) sink.row(row)
        } catch (e: Exception) {
            return failWrite(e)
        }

        if (!msg.done) {
            status = status.copy(phase = Phase.COPYING, block = msg.block, kind = msg.kind)
            return emptyList()
        }

        // The whole block has arrived. Make it durable and read it back, then -
        // and only then - tell the sensor. The digest is computed from what is
        // on this device's disk, never from what the sensor said it sent: a
        // claim echoed back would prove that a number equals itself.
        val sealed = try {
            sink.seal()
        } catch (e: Exception) {
            return failWrite(e)
        }
        openBlock = 0
        status = status.copy(
            phase = Phase.CONFIRMING,
            block = msg.block,
            kind = msg.kind,
            rowsWritten = status.rowsWritten + sealed.rows,
        )
        return listOf(Commands.drainConfirm(msg.block, sealed.rows, sealed.digest))
    }

    /** The sensor's answer, and the only thing it ever volunteers. */
    fun onState(msg: ServerMessage.DrainState): List<String> {
        if (!status.running) return emptyList()
        val seen = maxOf(status.blocksAtStart, msg.blocksLeft)
        status = status.copy(
            blocksLeft = msg.blocksLeft,
            blocksAtStart = seen,
            blocksFreed = msg.blocksFreed,
            readingsCopied = msg.readingsCopied,
            segmentsCopied = msg.segmentsCopied,
            message = msg.message,
        )
        return when (msg.state) {
            Wire.DRAIN_IDLE -> {
                // The claim is ours. Ask for the first block.
                if (status.phase == Phase.STARTING) {
                    status = status.copy(phase = Phase.COPYING)
                    listOf(Commands.drainNext())
                } else {
                    emptyList()
                }
            }

            // Informational: the rows have all gone and the sensor is saying what
            // they were. The confirmation was already sent, on the frame that
            // said `done` - waiting for this would only add a round trip.
            Wire.DRAIN_OFFERED -> emptyList()

            Wire.DRAIN_FREED -> {
                sink.freed(msg.block)
                status = status.copy(phase = Phase.COPYING)
                listOf(Commands.drainNext())
            }

            // The last block: copied, proved, and deliberately left on the sensor
            // because it holds the readings arriving right now. It becomes the
            // start of the sensor's storage.
            Wire.DRAIN_KEPT, Wire.DRAIN_FINISHED -> finish(msg.message)

            Wire.DRAIN_REFUSED -> {
                // Never carry on past one of these. A block the sensor could not
                // confirm is a block that is still on it, and the honest thing is
                // to say so and stop rather than to skip it and call the sensor
                // clear.
                discardOpenBlock()
                sink.close()
                status = status.copy(
                    phase = Phase.FAILED,
                    reason = msg.message.ifEmpty {
                        "the sensor could not confirm the copy of one block, so nothing " +
                            "more was removed"
                    },
                )
                listOf(Commands.drainCancel())
            }

            else -> {
                discardOpenBlock()
                sink.close()
                status = status.copy(
                    phase = Phase.FAILED,
                    reason = msg.message.ifEmpty { "the sensor stopped the copy" },
                )
                listOf(Commands.drainCancel())
            }
        }
    }

    /** An `error` frame that answered one of the drain commands. */
    fun onError(reason: String): List<String> {
        if (!status.running) return emptyList()
        discardOpenBlock()
        sink.close()
        status = status.copy(phase = Phase.FAILED, reason = reason)
        return emptyList()
    }

    /** Clear a finished run off the screen. Never touches what was written. */
    fun dismiss() {
        if (status.running) return
        status = Status()
    }

    private fun finish(message: String): List<String> {
        val location = try {
            sink.finish()
        } catch (e: Exception) {
            sink.close()
            status = status.copy(
                phase = Phase.FAILED,
                reason = "the copy is on this device but could not be filed: ${e.message}",
            )
            return listOf(Commands.drainCancel())
        }
        status = status.copy(
            phase = Phase.DONE,
            blocksLeft = 0,
            message = message,
            location = location,
        )
        // Give the claim back rather than leaving it held: the sensor frees
        // nothing on this, and a claim left behind would refuse the next one.
        return listOf(Commands.drainCancel())
    }

    private fun discardOpenBlock() {
        if (openBlock != 0L) {
            sink.discard()
            openBlock = 0
        }
    }

    private fun failWrite(e: Exception): List<String> {
        discardOpenBlock()
        sink.close()
        status = status.copy(
            phase = Phase.FAILED,
            reason = "this device could not write the copy down: ${e.message}. Nothing was " +
                "removed from the sensor",
        )
        return listOf(Commands.drainCancel())
    }

    /**
     * The header a block's file gets.
     *
     * The sensor's own column list when it sent one, and this build's otherwise.
     * Taking the sensor's is deliberate: a controller that ever added a column
     * would write a file whose header and rows agree, where a fixed list would
     * write one whose header lied about the cells under it - the one corruption
     * a CSV cannot survive.
     */
    private fun columnsFor(kind: String, offered: List<String>): List<String> = when {
        offered.isNotEmpty() -> offered
        kind == Wire.DRAIN_KIND_SEGMENTS -> Wire.DRAIN_SEGMENT_COLUMNS
        else -> Wire.DRAIN_READING_COLUMNS
    }

    companion object {
        /**
         * One drained row's digest, mirroring `store_drain_digest_reading`.
         *
         * `SessionArchive.digestReading`'s canonical bytes with a CRC of the
         * session's name in front of them. The name is there because a block is a
         * slice of the sensor's *flash*, not of one session, and may hold pages of
         * several - a digest that ignored it could not tell rows filed under the
         * wrong session from rows filed right.
         *
         * @return null for a row that carries no index or no time of either kind.
         *         Such a row is not counted, on this side or the sensor's.
         */
        fun digestReadingRow(cells: List<String>): Int? {
            val session = cells.getOrNull(SESSION_AT)?.trim().orEmpty()
            val index = cells.getOrNull(INDEX_AT)?.trim()?.toIntOrNull() ?: return null
            if (index <= 0) return null
            val seconds = rowSeconds(cells) ?: return null
            val quanta = IntArray(Wire.FIELDS.size) { f ->
                SessionArchive.quantise(
                    cells.getOrNull(FIRST_VALUE_AT + f)?.trim()?.toDoubleOrNull() ?: 0.0, f,
                )
            }
            val settling = (cells.getOrNull(EVENT_AT) ?: "").isNotEmpty()

            val canonical = ByteArray(4 + 4 + 8 + 1 + Wire.FIELDS.size * 4)
            putLe32(canonical, 0, crc32(session))
            putLe32(canonical, 4, index)
            putLe32(canonical, 8, (seconds and 0xFFFFFFFFL).toInt())
            putLe32(canonical, 12, (seconds ushr 32).toInt())
            canonical[16] = if (settling) 1 else 0
            for (f in quanta.indices) putLe32(canonical, 17 + f * 4, quanta[f])
            return crc(canonical)
        }

        /**
         * Whole seconds of whichever clock the row carries, or null for neither.
         *
         * The board's own clock wins when it is there, packed exactly as
         * `store_reading_t.epoch_ms` holds it for a clockless session - which is
         * what the sensor digests, so digesting the instant instead would fail a
         * perfectly good copy.
         */
        private fun rowSeconds(cells: List<String>): Long? {
            val boot = cells.getOrNull(REL_BOOT_AT)?.trim()?.toIntOrNull()
            val ms = cells.getOrNull(REL_MS_AT)?.trim()?.toLongOrNull()
            if (boot != null && ms != null) return Wire.relPack(boot, ms) / 1000L
            val epoch = SessionCsv.parseEpoch(cells.getOrNull(TIMESTAMP_AT) ?: return null)
                ?: return null
            return Math.floor(epoch).toLong()
        }

        /**
         * One preserved segment's digest, mirroring `store_drain_digest_segment`.
         *
         * The statistics go in as their raw IEEE-754 bit patterns rather than as
         * quantised integers: the sensor writes each of them as the shortest text
         * that reads back as the same number, so both ends can reconstruct the
         * identical float and there is nothing to disagree about. A cell that is
         * empty or unreadable digests as zero, which is what the sensor does with
         * a value it had no number for.
         *
         * `has_body` is deliberately not in it. Whether a segment's samples are
         * still on the sensor's flash is worked out when the record is read and
         * changes as its preservation area ages, so a digest that included it
         * would refuse a perfectly good copy an hour later.
         */
        fun digestSegmentRow(cells: List<String>): Int? {
            fun at(name: String) = Wire.DRAIN_SEGMENT_COLUMNS.indexOf(name)
            val seq = cells.getOrNull(at("seq"))?.trim()?.toIntOrNull() ?: return null
            val session = cells.getOrNull(at("session"))?.trim().orEmpty()
            val fieldName = cells.getOrNull(at("field"))?.trim().orEmpty()
            // A field this build does not know is not mapped onto field 0: the
            // digest simply will not match, the sensor refuses, and nothing is
            // removed. That is the safe direction for an unknown.
            val field = Wire.FIELDS.indexOf(fieldName).let { if (it < 0) 0xFF else it }
            val startS = cells.getOrNull(at("start_s"))?.trim()?.toLongOrNull() ?: return null
            val stopS = cells.getOrNull(at("stop_s"))?.trim()?.toLongOrNull() ?: return null
            val points = cells.getOrNull(at("points"))?.trim()?.toIntOrNull() ?: return null
            val firstIndex = cells.getOrNull(at("first_index"))?.trim()?.toIntOrNull()
                ?: return null
            val lastIndex = cells.getOrNull(at("last_index"))?.trim()?.toIntOrNull() ?: return null
            val wrap = cells.getOrNull(at("wrap"))?.trim()?.toIntOrNull() ?: return null
            // threshold, then first, last, midpoint, mean, variance, minimum,
            // maximum - the order `store_drain_digest_segment` folds them in, and
            // by name for the same reason the reading row's cells are.
            val statsAt = intArrayOf(
                at("threshold"), at("first"), at("last"), at("midpoint"),
                at("mean"), at("variance"), at("minimum"), at("maximum"),
            )

            val canonical = ByteArray(4 + 4 + 1 + 8 + 8 + 4 * 4 + 8 * 4)
            var at = 0
            putLe32(canonical, at, crc32(session)); at += 4
            putLe32(canonical, at, seq); at += 4
            canonical[at++] = (field and 0xFF).toByte()
            putLe64(canonical, at, startS); at += 8
            putLe64(canonical, at, stopS); at += 8
            putLe32(canonical, at, firstIndex); at += 4
            putLe32(canonical, at, lastIndex); at += 4
            putLe32(canonical, at, points); at += 4
            putLe32(canonical, at, wrap); at += 4
            for (i in statsAt) {
                putLe32(canonical, at, floatBits(cells.getOrNull(i)?.trim()))
                at += 4
            }
            return crc(canonical)
        }

        /** One row's digest, by the kind of block it came out of. */
        fun digestRow(kind: String, cells: List<String>): Int? =
            if (kind == Wire.DRAIN_KIND_SEGMENTS) digestSegmentRow(cells)
            else digestReadingRow(cells)

        /** Order-independent, so the order rows are read back in cannot matter. */
        fun fold(accumulator: Int, one: Int): Int = accumulator + one

        /*
         * Every cell is found by name rather than by a number written down here.
         *
         * A drained row is `CSV_COLUMNS_RELATIVE` with the session's name in
         * front, so every offset an export uses is one greater here - and reading
         * the values one cell to the left is a digest that is wrong in a way
         * nothing else notices, because the numbers are still numbers. Asking the
         * column list makes that impossible rather than careful.
         */
        private val SESSION_AT = Wire.DRAIN_READING_COLUMNS.indexOf("session")
        private val INDEX_AT = Wire.DRAIN_READING_COLUMNS.indexOf("index")
        private val TIMESTAMP_AT = Wire.DRAIN_READING_COLUMNS.indexOf("timestamp")
        private val FIRST_VALUE_AT = Wire.DRAIN_READING_COLUMNS.indexOf(Wire.FIELDS.first())
        private val EVENT_AT = Wire.DRAIN_READING_COLUMNS.indexOf(Wire.COL_EVENT)
        private val REL_BOOT_AT = Wire.DRAIN_READING_COLUMNS.indexOf(Wire.COL_REL_BOOT)
        private val REL_MS_AT = Wire.DRAIN_READING_COLUMNS.indexOf(Wire.COL_REL_MS)

        /** The bits the sensor digested, from the text it sent. Empty is zero. */
        private fun floatBits(cell: String?): Int {
            val value = cell?.toDoubleOrNull() ?: return 0
            if (!value.isFinite()) return 0
            return java.lang.Float.floatToRawIntBits(value.toFloat())
        }

        private fun crc32(text: String): Int {
            val crc = CRC32()
            crc.update(text.toByteArray(Charsets.UTF_8))
            return crc.value.toInt()
        }

        private fun crc(bytes: ByteArray): Int {
            val crc = CRC32()
            crc.update(bytes)
            return crc.value.toInt()
        }

        private fun putLe32(out: ByteArray, at: Int, value: Int) {
            out[at] = (value and 0xFF).toByte()
            out[at + 1] = ((value ushr 8) and 0xFF).toByte()
            out[at + 2] = ((value ushr 16) and 0xFF).toByte()
            out[at + 3] = ((value ushr 24) and 0xFF).toByte()
        }

        private fun putLe64(out: ByteArray, at: Int, value: Long) {
            putLe32(out, at, (value and 0xFFFFFFFFL).toInt())
            putLe32(out, at + 4, (value ushr 32).toInt())
        }
    }
}

/**
 * Where a drained block is written down, and what proves it landed.
 *
 * Behind an interface because the proof has to be about *storage*, not about
 * memory: [seal] must not return until the block is durable, because the very
 * next thing that happens is the sensor being told it may free it. A sink that
 * buffered would turn a crash into lost readings, which is the one failure this
 * whole design exists to make impossible.
 */
interface DrainSink {
    /** Where the copy is going, in words a user can act on. */
    val location: String

    /** Start block [block]; [columns] is the header its file gets. */
    fun open(block: Long, kind: String, columns: List<String>)

    /** One row of it. */
    fun row(cells: List<String>)

    /**
     * Make the open block durable, read it back, and say what is now held.
     *
     * The digest must be computed from what was read back rather than from what
     * was written, so that a short write is caught here rather than by the sensor
     * refusing a confirmation it cannot explain.
     *
     * @throws Exception if the block could not be written or read back, in which
     *         case nothing may be confirmed.
     */
    fun seal(): Sealed

    /** The sensor freed [block]. This device's copy of it is now the only one. */
    fun freed(block: Long)

    /** Throw the open block away. It was never confirmed, so the sensor has it. */
    fun discard()

    /** Everything is copied. @return where it ended up, for the user. */
    fun finish(): String

    /** Give up whatever is open. Safe to call twice. */
    fun close()

    /** What one block turned out to be, once it was on disk. */
    data class Sealed(val rows: Int, val digest: Int)
}
