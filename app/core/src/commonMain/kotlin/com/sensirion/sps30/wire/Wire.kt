package com.sensirion.sps30.wire

/**
 * Kotlin port of the constants in `wire.py` - the single source of truth for the
 * SPS30 daemon/client line protocol. Keep this in lockstep with `wire.py`; the
 * round-trip tests in `WireTest` guard against drift.
 */
object Wire {
    const val DEFAULT_PORT = 8730

    // --- being found: the mDNS / DNS-SD advertisement ----------------------
    // `wire.MDNS_*`, mirrored here and into the firmware's
    // `net_mdns_record.h`. A browsing app and an advertising controller have to
    // agree on these strings with nothing else in common, so they are the one
    // part of the protocol where a single wrong character means "no sensor on
    // this network" rather than a error anybody can read.
    //
    // What the controller does NOT put in the advertisement - no serial number,
    // no MAC-derived name, no session, no client count - is documented at the
    // definition, in `wire.py` and `net_mdns_record.h`. Nothing here should
    // start depending on more than these carry.

    /** `wire.MDNS_SERVICE_TYPE`, in the form Android's NsdManager wants it. */
    const val MDNS_SERVICE_TYPE = "_sensorq._tcp"

    /** `wire.MDNS_TXT_VERSION`: the layout version of the TXT record itself. */
    const val MDNS_TXT_VERSION = "txtvers"

    /** `wire.MDNS_TXT_PROTOCOL`: which version of this protocol the host speaks. */
    const val MDNS_TXT_PROTOCOL = "proto"

    /** The one protocol version that exists. See `wire.MDNS_TXT_PROTOCOL_VALUE`. */
    const val MDNS_PROTOCOL_VERSION = "1"

    /** The ten SPS30 outputs, in fixed order (matches `wire.FIELDS`). */
    val FIELDS: List<String> = listOf(
        "pm1_0", "pm2_5", "pm4_0", "pm10",
        "nc0_5", "nc1_0", "nc2_5", "nc4_0", "nc10",
        "typical_size",
    )

    /** Non-blank flags a reading disturbed by a (re)start/clean spike (see wire.py). */
    const val COL_EVENT = "event"

    /**
     * The classic thirteen, which never change.
     *
     * A session recorded without a clock needs two more and gets them by
     * appending - see [CSV_COLUMNS_RELATIVE], not by rewriting this.
     */
    val CSV_COLUMNS: List<String> = listOf("index", "timestamp") + FIELDS + COL_EVENT

    const val INDEX_WIDTH = 7

    // Server -> client message "type" values.
    const val MSG_HELLO = "hello"
    const val MSG_READING = "reading"
    const val MSG_STATE = "state"
    const val MSG_RAW = "raw_response"
    const val MSG_FILES = "file_list"
    const val MSG_FILE_DATA = "file_data"

    // Walking a listing that does not fit one frame (`wire.file_list_page`).
    //
    // A `file_list` names as many sessions as the sender can carry in one frame
    // and says where the page sits and where the rest begin. The board holds far
    // more than a frame can name - 84 against 64, measured - and a session
    // nobody can name is one nobody can fetch, plot or offer to the erase gate,
    // so a copy that stops at one frame is not a backup however confident it
    // looks. `list_files` carries [K_FILES_OFFSET] to continue the walk.
    const val K_FILES_OFFSET = "offset"
    const val K_FILES_TOTAL = "total"
    const val K_FILES_NEXT = "next"
    const val MSG_ERROR = "error"
    const val MSG_PROFILE = "profile"
    const val MSG_POWER = "power"
    const val MSG_CONTROL = "control"
    const val MSG_CLIENTS = "clients"
    const val MSG_BACKUP_META = "backup_meta"
    const val MSG_BACKUP_CHUNK = "backup_chunk"
    const val MSG_TIME_MAP = "time_map"

    // The transfer half, which the ESP32 controller adds above `wire.py`. The Pi
    // daemon does not know these and answers its usual error, which is why every
    // caller of them checks what it is connected to first.
    const val MSG_IMPORT_RESULT = "import_result"
    const val MSG_STORAGE = "storage"
    const val MSG_ERASE_RESULT = "erase_result"

    // The storage occupancy map: one 2-bit cell per 512-byte flash page, the
    // partition's regions in address order, each with its own write position.
    // See `wire.py` for the packing; `Messages.StorageMap` decodes it.
    const val MSG_STORAGE_MAP = "storage_map"
    const val MAP_SECTION_RING = "ring"
    const val MAP_SECTION_ALERT_HEADERS = "alert_headers"
    const val MAP_SECTION_ALERT_BODIES = "alert_bodies"
    const val PAGE_STATE_EMPTY = 0
    const val PAGE_STATE_DATA = 1
    const val PAGE_STATE_ANCHOR = 2
    const val PAGE_STATE_BAD = 3

    // The modelled power estimate: the reply to [CMD_GET_POWER_ESTIMATE].
    // Controller-only for a physical reason rather than a storage one - the
    // model's constants describe the ESP32 board, so the Pi daemon has no honest
    // answer and gives its usual "unknown cmd", which this client renders as
    // "no estimate here" rather than as a failure.
    const val MSG_POWER_ESTIMATE = "power_estimate"

    /** `wire.POWER_ESTIMATE_BASIS_MODELLED`: every figure modelled, none measured. */
    const val POWER_BASIS_MODELLED = "modelled"

    // `wire.POWER_RUNTIME_*`: why the runtime slot is or is not a number.
    // `hours` is present only under "ok", and no board today can say "ok" -
    // there is no battery - so today every reply says [POWER_RUNTIME_NO_CAPACITY].
    const val POWER_RUNTIME_OK = "ok"
    const val POWER_RUNTIME_NO_CAPACITY = "no_capacity"
    const val POWER_RUNTIME_UNUSABLE_CAPACITY = "unusable_capacity"
    const val POWER_RUNTIME_NO_DRAW = "no_draw"

    // Preserved alert data: what the controller keeps when its ring wraps.
    //
    // The controller's ring is allowed to wrap and ordinary history is allowed
    // to go with it, but readings that crossed the alert line are copied out of
    // the ring's way first and survive it. These carry them here, by default and
    // without being asked - see `wire.py` and `store_alert.h`.
    const val MSG_ALERTS = "alerts"
    const val MSG_ALERT_SEGMENT = "alert_segment"
    const val MSG_ALERT_DATA = "alert_data"

    // Backing everything up and clearing the board, block by block.
    //
    // A separate path from [CMD_ERASE], and not a weakening of it: the rule that
    // the controller deletes nothing this device cannot prove it holds is the
    // same rule, applied per block instead of per session. This device drives
    // every step and the controller only ever answers, which is what makes an
    // interruption at any point safe. See BACKUP_AND_CLEAR.md.
    const val MSG_DRAIN_STATE = "drain_state"
    const val MSG_DRAIN_DATA = "drain_data"

    /** What a drained block is made of (`wire.DRAIN_KIND_*`). */
    const val DRAIN_KIND_BODY = "body"
    const val DRAIN_KIND_SEGMENTS = "segments"
    const val DRAIN_KIND_RING = "ring"

    /**
     * A digest as the wire carries it: unsigned, eight hex digits.
     *
     * A digest is a bit pattern rather than a quantity, and reading one as a
     * quantity loses the top bit to a sign somewhere along the way. Both the
     * erase gate's claims and a drain's confirmations carry theirs this way, and
     * they do it through this one function so they cannot come to disagree.
     */
    fun digestHex(digest: Int): String =
        (digest.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')

    /** `wire.DRAIN_STATES`. */
    const val DRAIN_IDLE = "idle"
    const val DRAIN_OFFERED = "offered"
    const val DRAIN_FREED = "freed"
    const val DRAIN_KEPT = "kept"
    const val DRAIN_REFUSED = "refused"
    const val DRAIN_FINISHED = "finished"
    const val DRAIN_ERROR = "error"

    // Client -> server command "cmd" values.
    const val CMD_PAUSE = "pause"
    const val CMD_RESUME = "resume"
    const val CMD_CLEAN = "clean"
    const val CMD_RESTART = "restart"
    const val CMD_RAW = "raw"
    const val CMD_LIST_FILES = "list_files"
    const val CMD_GET_FILE = "get_file"
    const val CMD_SET_PROFILE = "set_profile"
    const val CMD_SHUTDOWN = "shutdown"
    const val CMD_IDENTIFY = "identify"
    const val CMD_CLAIM = "claim"
    const val CMD_RELEASE = "release"
    const val CMD_BACKUP = "backup"
    const val CMD_SET_TIME = "set_time"
    const val CMD_PUT_FILE = "put_file"
    const val CMD_ERASE = "erase"
    const val CMD_STORAGE = "storage"
    const val CMD_STORAGE_MAP = "storage_map"
    const val CMD_GET_ALERTS = "get_alerts"
    const val CMD_SET_ALERTS = "set_alerts"
    const val CMD_GET_POWER_ESTIMATE = "get_power_estimate"

    // The four of the drain. All are held to the control claim by the
    // controller, including the two that free nothing: a drain another device
    // could interleave with is a drain neither of them can reason about.
    const val CMD_DRAIN_BEGIN = "drain_begin"
    const val CMD_DRAIN_NEXT = "drain_next"
    const val CMD_DRAIN_CONFIRM = "drain_confirm"
    const val CMD_DRAIN_CANCEL = "drain_cancel"

    // Daemon acquisition states.
    const val STATE_CHECKING = "checking"
    const val STATE_MEASURING = "measuring"
    const val STATE_PAUSED = "paused"
    const val STATE_CLEANING = "cleaning"
    const val STATE_SLEEPING = "sleeping"
    const val STATE_WARMUP = "warmup"
    const val STATE_SHUTDOWN = "shutting_down"

    // Acquisition modes (see `wire.py`). CONTINUOUS measures non-stop; raising its
    // interval saves data, not power. DUTY_CYCLE is Sensirion's low-power
    // recommendation: wake, warm up, average, sleep.
    const val MODE_CONTINUOUS = "continuous"
    const val MODE_DUTY_CYCLE = "duty_cycle"

    // --- relative time (see the section of the same name in `wire.py`) --------
    //
    // A controller with no clock counts on its own and says so, rather than
    // inventing a timestamp or dropping the reading. Its readings and rows carry
    // the pair below in place of an instant, and the controller appends
    // equivalences - "my clock read X when the world clock read Y" - that this
    // client converts through. The conversion lives here, not on the board,
    // which is what keeps it reversible.

    /** Which run of the board a relative time belongs to. A reboot changes it. */
    const val COL_REL_BOOT = "rel_boot"

    /** What the board's own clock read, in milliseconds within that run. */
    const val COL_REL_MS = "rel_ms"

    /**
     * `wire.CSV_COLUMNS_RELATIVE`: the header of a clockless session's file.
     *
     * [CSV_COLUMNS] with the board's own clock appended, which is exactly the
     * column list the controller already declares in `file_data` for such a
     * session. The file on disk is therefore the same shape as the frame, and a
     * session that goes out to a file and comes back is the session that went
     * out - where before it came back with neither an instant nor a position,
     * and every reader correctly kept nothing of it.
     *
     * Additive: the two columns go after `event`, so a reader that has never
     * heard of them finds the same thirteen columns in the same thirteen
     * positions. Nothing that writes an absolute session writes this header.
     */
    val CSV_COLUMNS_RELATIVE: List<String> = CSV_COLUMNS + COL_REL_BOOT + COL_REL_MS

    /**
     * `wire.csv_columns`: the header a session's file needs, from its name.
     *
     * A pure function of the name, so no caller has to track separately whether
     * a session was recorded against a clock, and none of them can disagree
     * about it.
     */
    fun csvColumnsFor(name: String): List<String> =
        if (isRelativeSessionName(name)) CSV_COLUMNS_RELATIVE else CSV_COLUMNS

    /**
     * `wire.DRAIN_READING_COLUMNS`: the cells of a drained reading row.
     *
     * [CSV_COLUMNS_RELATIVE] with the session's name in front. The name is there
     * because a block is a slice of the controller's *flash*, not of one
     * session, and may hold pages of several; both clock cells are always
     * present rather than added only for a relative session, because one block
     * can hold pages of both kinds and a single column list has to describe them
     * all. An absent cell is empty.
     */
    val DRAIN_READING_COLUMNS: List<String> = listOf("session") + CSV_COLUMNS_RELATIVE

    /**
     * `wire.DRAIN_SEGMENT_COLUMNS`: the cells of a drained segment summary.
     *
     * The `alert_segment` keys with the two ISO timestamps replaced by whole
     * seconds, because a segment of a session recorded with no clock holds a
     * packed board clock in those fields and formatting one as a date produces a
     * string that does not read back - which would fail the digest of a
     * perfectly good copy and stop the drain.
     */
    val DRAIN_SEGMENT_COLUMNS: List<String> = listOf(
        "seq", "session", "field", "threshold", "start_s", "stop_s",
        "first", "last", "midpoint", "mean", "variance", "minimum", "maximum",
        "points", "first_index", "last_index", "wrap", "has_body",
    )

    /**
     * `wire.rel_pack`: the board's clock as the single int64 its store keeps.
     *
     * The run rides in the top half and the milliseconds in the bottom. Mirrored
     * here because the archive digest is computed over what the controller
     * stored, and for a clockless session what it stored in the time field is
     * this packed value - see `SessionArchive.digestRow`.
     */
    fun relPack(boot: Int, ms: Long): Long =
        ((boot.toLong() and 0x7FFFFFFFL) shl 32) or (ms and 0xFFFFFFFFL)

    /** What an exported row says the time was actually derived from. */
    const val TIME_MEASURED = "measured"
    const val TIME_DERIVED = "derived"
    const val TIME_UNANCHORED = "unanchored"
    const val TIME_MANUAL = "manual"
    const val TIME_SOURCE_COLUMN = "time_source"

    /**
     * `wire.rel_session_name`: the session key when there was no clock.
     *
     * Recognised rather than produced by this client, but kept here beside the
     * dated form because both are the protocol's session key and a reader
     * looking for one will look for the other.
     */
    fun isRelativeSessionName(name: String): Boolean =
        name.length == 28 && name.startsWith("SPS30-R") && name.endsWith(".csv") &&
            name[15] == '-' &&
            name.substring(7, 15).all { it.isDigit() || it in 'A'..'F' } &&
            name.substring(16, 24).all { it.isDigit() || it in 'A'..'F' }

    // Transports and their real client capacity (mirrors `wire.py`). Wi-Fi serves
    // a room; Bluetooth serves one phone. Reporting a single fixed cap made the
    // app say "1 of 8" on Bluetooth, which promises a second device may connect
    // when it never can.
    const val TRANSPORT_TCP = "tcp"
    const val TRANSPORT_BLE = "ble"

    const val MAX_CLIENTS_TCP = 8
    const val MAX_CLIENTS_BLE = 1

    /** How many clients [transport] can actually serve at once (`wire.max_clients_for`). */
    fun maxClientsFor(transport: String?): Int = when (transport) {
        TRANSPORT_BLE -> MAX_CLIENTS_BLE
        else -> MAX_CLIENTS_TCP
    }

    /**
     * Reconcile a server-reported cap with what [transport] can carry
     * (`wire.effective_max_clients`).
     *
     * The daemon counts sockets, so it reports the TCP figure even to a client
     * arriving over the BLE bridge. The honest number is the smaller of the two;
     * a reported 0 means an older daemon did not say, so the transport stands alone.
     */
    fun effectiveMaxClients(reported: Int, transport: String?): Int {
        val limit = maxClientsFor(transport)
        return if (reported <= 0) limit else minOf(reported, limit)
    }

    /** The `set_profile` command carries the WHOLE profile: any field it omits
     *  goes back to its default rather than keeping what the host is running
     *  (mirrors `wire.apply_set_profile`). Always send all five. */
    val PROFILE_KEYS: List<String> = listOf("mode", "interval", "cycle_s", "warmup_s", "average_s")

    /** Blank [ServerMessage.ErrorMsg.ref]: the error names no particular request. */
    const val ERROR_REF_NONE = ""

    /** True if an error tagged [ref] is about [command] - or does not say what it
     *  is about, which is how every error behaved before the field existed
     *  (mirrors `wire.error_concerns`). */
    fun errorConcerns(ref: String, command: String): Boolean =
        ref.isEmpty() || ref == command

    /** Zero-padded measurement counter, e.g. 1 -> "0000001" (matches `format_index`). */
    fun formatIndex(n: Int): String = n.toString().padStart(INDEX_WIDTH, '0')

    /**
     * The PM mass-concentration fields shown as the primary chart series.
     *
     * A display list, not an export list. PM4.0 and PM10 were dropped from the
     * numeric readout because they track PM2.5 in clean air and look broken;
     * they stay in the chart and in every export, which is what [FIELDS] and
     * [CSV_COLUMNS] are for.
     */
    val PM_FIELDS: List<String> = listOf("pm1_0", "pm2_5", "pm4_0", "pm10")
}
