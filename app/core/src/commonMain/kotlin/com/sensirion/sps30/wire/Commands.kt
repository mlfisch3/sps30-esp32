package com.sensirion.sps30.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Client -> server command frames. Each returns a complete newline-terminated
 * JSONL line ready to write to the socket, matching `wire.encode({"cmd": ...})`.
 */
object Commands {
    private fun frame(obj: JsonObject): String =
        Json.encodeToString(JsonObject.serializer(), obj) + "\n"

    private fun simple(cmd: String): String = frame(buildJsonObject { put("cmd", cmd) })

    fun pause(): String = simple(Wire.CMD_PAUSE)
    fun resume(): String = simple(Wire.CMD_RESUME)
    fun clean(): String = simple(Wire.CMD_CLEAN)
    fun restart(): String = simple(Wire.CMD_RESTART)
    /**
     * Ask what the controller holds, from [offset] entries in.
     *
     * The listing is newest first and does not have to fit one frame - a board
     * that holds more sessions than a frame can name answers one page and says
     * where the rest begin. [offset] 0 asks for the newest page, which is what a
     * controller too old to page has always been given and still is: it ignores
     * the field. See `wire.file_list_page` and [ServerMessage.FileList.next].
     */
    fun listFiles(offset: Int = 0): String = frame(buildJsonObject {
        put("cmd", Wire.CMD_LIST_FILES)
        if (offset > 0) put(Wire.K_FILES_OFFSET, offset)
    })

    fun raw(hex: String): String = frame(buildJsonObject {
        put("cmd", Wire.CMD_RAW)
        put("hex", hex)
    })

    fun getFile(name: String, sinceIndex: Int? = null): String = frame(buildJsonObject {
        put("cmd", Wire.CMD_GET_FILE)
        put("name", name)
        if (sinceIndex != null) put("since_index", sinceIndex)
    })

    /**
     * Ask for the preserved alert segments again, or only the ones past [sinceSeq].
     *
     * Not needed to *start* the delivery: the controller sends it unasked on
     * connection, which is the point of the feature. This is for a device that
     * declined and changed its mind, or one that already holds segments up to a
     * sequence and wants only what came after.
     */
    fun getAlerts(sinceSeq: Int? = null): String = frame(buildJsonObject {
        put("cmd", Wire.CMD_GET_ALERTS)
        if (sinceSeq != null) put("since_seq", sinceSeq)
    })

    /**
     * Decline the automatic delivery, and/or move the controller's preservation line.
     *
     * [thresholds] is one number per field and **nothing else about a rule** - no
     * id, no label, no reaction. It is a retention policy, not a notification
     * setting: `AlertRule`'s thresholds still live only on the phone, and what
     * the controller is told is the lowest line below which a reading is not
     * worth keeping when the ring wraps. Send the *minimum* enabled threshold
     * per field, because keeping a superset of what any rule would fire on is
     * right and keeping less is not. See [Wire.CMD_SET_ALERTS] and `wire.py`.
     *
     * Sending a [thresholds] map replaces the policy whole - a field it does not
     * name stops being watched - which is the only way to express "the user
     * turned that rule off".
     */
    fun setAlerts(auto: Boolean? = null, thresholds: Map<String, Double>? = null): String =
        frame(buildJsonObject {
            put("cmd", Wire.CMD_SET_ALERTS)
            if (auto != null) put("auto", auto)
            if (thresholds != null) {
                put("thresholds", buildJsonObject {
                    for (field in Wire.FIELDS) {
                        val value = thresholds[field] ?: continue
                        if (value.isFinite() && value > 0.0) put(field, value)
                    }
                })
            }
        })

    /** Switch acquisition profile. The daemon clamps anything out of range.
     *
     *  The profile is the WHOLE profile, never a patch: a field left out resets to
     *  its default rather than keeping what the host is running. That is why all
     *  five [Wire.PROFILE_KEYS] are always sent - omitting one to "leave it alone"
     *  would silently change it. */
    fun setProfile(
        mode: String,
        intervalSeconds: Double,
        cycleSeconds: Double,
        warmupSeconds: Double,
        averageSeconds: Double,
    ): String = frame(buildJsonObject {
        put("cmd", Wire.CMD_SET_PROFILE)
        put("profile", buildJsonObject {
            put("mode", mode)
            put("interval", intervalSeconds)
            put("cycle_s", cycleSeconds)
            put("warmup_s", warmupSeconds)
            put("average_s", averageSeconds)
        })
    })

    /** Park the sensor, close the CSV and power the host down. */
    fun shutdown(): String = simple(Wire.CMD_SHUTDOWN)

    /** Announce a human-readable name so others can see who is connected. */
    fun identify(name: String): String = frame(buildJsonObject {
        put("cmd", Wire.CMD_IDENTIFY)
        put("name", name)
    })

    /** Take the control lock, including from an absent holder. */
    fun claim(): String = simple(Wire.CMD_CLAIM)

    /** Give up the control lock. */
    fun release(): String = simple(Wire.CMD_RELEASE)

    /** Ask for every recorded session as one archive. Needs no control claim. */
    fun backup(): String = simple(Wire.CMD_BACKUP)

    /** Ask how full the controller's store is. It also volunteers this. */
    fun storage(): String = simple(Wire.CMD_STORAGE)

    /**
     * Ask for the per-page occupancy map. Never volunteered: only the open
     * map dialog asks, and it asks again while it stays open.
     */
    fun storageMap(): String = simple(Wire.CMD_STORAGE_MAP)

    /**
     * Ask what the board is drawing under its power model, and what the other
     * acquisition modes would cost. Controller-only: the Pi daemon answers
     * "unknown cmd" because the model's constants describe the ESP32 board.
     */
    fun getPowerEstimate(): String = simple(Wire.CMD_GET_POWER_ESTIMATE)

    /**
     * Give a controller that has no clock the time.
     *
     * Sent on every connection, unasked. A controller that already has a clock
     * says so and nothing changes; one that does not appends an equivalence and
     * every relative reading it holds becomes placeable. Sending it always -
     * rather than only when the controller looks clockless - is what makes a
     * board collected over Bluetooth work on the first connection instead of the
     * second, and costs one small line.
     *
     * Not a mutating command: it changes nothing about what the sensor is doing
     * and cannot alter a reading already recorded, so it needs no control claim.
     */
    fun setTime(epochMillis: Long, utcOffsetMinutes: Int): String =
        frame(buildJsonObject {
            put("cmd", Wire.CMD_SET_TIME)
            put("epoch_ms", epochMillis)
            put("utc_offset_min", utcOffsetMinutes)
        })

    /**
     * Hand a chunk of a session back to the controller.
     *
     * [rows] is newline-separated CSV in [Wire.CSV_COLUMNS] order - not a JSON
     * array of arrays, which is what `file_data` uses in the other direction.
     * The controller reads flat objects with short string fields by design, and
     * this is the one payload big enough that the difference matters: a nested
     * parser on the far side would be new code standing between a file and the
     * data it is being restored from.
     *
     * [done] closes the transfer and is what makes the controller answer with an
     * [ServerMessage.ImportResult]. Every chunk before it is silent, **including
     * one the controller cannot serve**: a transfer that arrives while another
     * device is importing is counted, discarded and answered once at the end
     * with `state: "busy"`. It used to be refused per chunk, and since neither
     * end reads while it is sending, that stopped both of them dead - see
     * `firmware/components/net/net_import.h`.
     */
    fun putFile(name: String, rows: String, seq: Int, done: Boolean): String =
        frame(buildJsonObject {
            put("cmd", Wire.CMD_PUT_FILE)
            put("name", name)
            put("rows", rows)
            put("seq", seq)
            put("done", done)
        })

    /**
     * Ask the controller to erase what this device can prove it already holds.
     *
     * [backup] is one `name,readings,digest` line per session, the digest in
     * hex. The controller recomputes both figures from its own pages and keeps
     * every session whose claim does not match exactly - so an out-of-date
     * backup does not erase anything, it just fails to confirm.
     *
     * [override] skips the proof and is behind a deliberately obscure gesture.
     */
    fun erase(backup: String, override: Boolean = false): String = frame(buildJsonObject {
        put("cmd", Wire.CMD_ERASE)
        put("backup", backup)
        put("override", override)
    })

    // --- backing everything up and clearing the sensor ------------------------
    //
    // Four commands and one rule: the controller frees a block only after this
    // device has proved it holds it, so every one of these is a question and the
    // controller's answer is the only thing that changes what is on the sensor.
    // Nothing here can be sent by accident - see `BackupAndClearRow`.

    /** Take the drain claim. Nothing is read, nothing is freed. */
    fun drainBegin(): String = simple(Wire.CMD_DRAIN_BEGIN)

    /** Ask for the oldest block the sensor still holds. */
    fun drainNext(): String = simple(Wire.CMD_DRAIN_NEXT)

    /**
     * Tell the sensor this device holds block [block], and how to check.
     *
     * [rows] and [digest] are computed from what is **written down on this
     * device**, never from what the controller said it sent - a claim echoed
     * back would prove that a number equals itself. The controller recomputes
     * both from its own flash and frees nothing that does not match exactly.
     *
     * The digest is hex because it is a bit pattern rather than a quantity, the
     * same way an erase's claims carry theirs.
     */
    fun drainConfirm(block: Long, rows: Int, digest: Int): String = frame(buildJsonObject {
        put("cmd", Wire.CMD_DRAIN_CONFIRM)
        put("block", block)
        put("rows", rows)
        put("digest", Wire.digestHex(digest))
    })

    /** Give the claim back. Safe at any moment; the sensor frees nothing. */
    fun drainCancel(): String = simple(Wire.CMD_DRAIN_CANCEL)
}
