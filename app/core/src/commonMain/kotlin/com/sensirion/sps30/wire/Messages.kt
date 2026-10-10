package com.sensirion.sps30.wire

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Decoded server -> client messages. Mirrors the `MSG_*` payloads documented in
 * `wire.py`. Parsing is tolerant (never throws): a malformed or unknown frame
 * yields `null` or [ServerMessage.Unknown] rather than crashing the read loop.
 */
sealed interface ServerMessage {

    data class Hello(
        val product: String,
        val serial: String,
        val firmware: String,
        val state: String,
        val csv: String?,
        val index: String,
        val detail: String = "",
        val phaseSeconds: Double? = null,
        val profile: AcquisitionProfile? = null,
        val power: PowerStatus? = null,
        /** False when the sensor firmware predates Sleep/Wake (< 2.0), so the UI
         *  can disable low-power mode instead of offering an impossible option. */
        val supportsSleep: Boolean = true,
        /** False on a controller with no operating system to halt, so the UI can
         *  leave the shutdown control out rather than offer one that only errors.
         *  Absent means true: every host that predates the flag is a Pi. */
        val canPowerOff: Boolean = true,
        /**
         * Whether the controller knows what time it is.
         *
         * Absent means yes: every controller that predates relative time had a
         * clock, and offering one to a host that never needed telling earns a
         * refusal this client would then have to explain away mid-backfill.
         */
        val hasClock: Boolean = true,
        /** This connection's id in the roster, so the client can spot itself. */
        val clientId: Int? = null,
        val maxClients: Int = 0,
        /** The link the daemon counted [maxClients] on (see [Wire.TRANSPORT_TCP]). */
        val transport: String? = null,
    ) : ServerMessage

    /** The daemon's active acquisition profile (mirrors `wire.DEFAULT_PROFILE`). */
    data class AcquisitionProfile(
        val mode: String,
        val intervalSeconds: Double,
        val cycleSeconds: Double,
        val warmupSeconds: Double,
        val averageSeconds: Double,
    ) : ServerMessage {
        val dutyCycling: Boolean get() = mode == Wire.MODE_DUTY_CYCLE
    }

    /**
     * Host power telemetry. On the mains-powered Pi most fields are null and the
     * useful signal is [undervoltage]; a battery ESP32-C6 host fills in the rest.
     */
    data class PowerStatus(
        val source: String,
        val charging: Boolean = false,
        val percent: Double? = null,
        val voltageV: Double? = null,
        val runtimeHours: Double? = null,
        /** "gauge" (a fuel-gauge IC) or "modelled" (duty-cycle arithmetic). */
        val method: String = "none",
        /** Modelled average sensor draw for the active profile, mA. */
        val averageMa: Double? = null,
        /** Hours of measurement before the next scheduled fan clean. */
        val cleanDueInHours: Double? = null,
        val undervoltage: Boolean = false,
    ) : ServerMessage {
        val onBattery: Boolean get() = source == "battery"
    }

    /**
     * The controller's answer to [Wire.CMD_GET_POWER_ESTIMATE]: what the board
     * is drawing under the firmware's power model, and what the other
     * acquisition modes would cost instead.
     *
     * Every figure is **modelled** - [basis] says so and the source counts say
     * from what - and the panel that renders this must say that once, plainly.
     * The app deliberately owns no power model of its own: the controller
     * computes, this client renders. See `power.py`'s module docstring.
     */
    data class PowerEstimate(
        /** [Wire.POWER_BASIS_MODELLED] today; never "measured" until a meter exists. */
        val basis: String,
        /** `power.sources_summary()`: what the model's constants rest on. */
        val sourcesDatasheet: Int = 0,
        val sourcesMeasured: Int = 0,
        val sourcesEstimate: Int = 0,
        /** The state that was priced, echoed normalized. */
        val profile: AcquisitionProfile? = null,
        /** Whether the sensor sleeps between duty cycles on this board. */
        val sleeps: Boolean = false,
        val totalMw: Double,
        /** Keyed "sensor"/"controller"/"radio"/"storage", in reading order. */
        val bySubsystemMw: Map<String, Double> = emptyMap(),
        val alternatives: List<PowerAlternative> = emptyList(),
        /** One of [Wire.POWER_RUNTIME_OK] and friends. */
        val runtimeStatus: String = Wire.POWER_RUNTIME_NO_CAPACITY,
        /** Estimated hours left, already derated. Non-null exactly when
         *  [runtimeStatus] is "ok" - "unknown" and "about to die" must never
         *  render the same, so this is never coerced to zero. */
        val runtimeHours: Double? = null,
        val runtimeModelledHours: Double? = null,
        val runtimeDerating: Double? = null,
    ) : ServerMessage {
        val runtimeKnown: Boolean get() =
            runtimeStatus == Wire.POWER_RUNTIME_OK && runtimeHours != null
    }

    /**
     * One priced alternative mode. [cycleSeconds] and [sleeps] are present only
     * for duty-cycle entries; [hours] is estimated remaining life under this
     * mode and appears only once a battery capacity is known, which no board
     * can say today.
     */
    data class PowerAlternative(
        val mode: String,
        val cycleSeconds: Double? = null,
        val sleeps: Boolean = false,
        val totalMw: Double,
        val hours: Double? = null,
    ) {
        val dutyCycling: Boolean get() = mode == Wire.MODE_DUTY_CYCLE
    }

    /** A live measurement. `values` maps each present [Wire.FIELDS] name to its number. */
    data class Reading(
        val csv: String?,
        val index: String,
        val timestamp: String,
        val values: Map<String, Double>,
        /** Non-blank => disturbed/unreliable settling spike (see [Wire.COL_EVENT]). */
        val event: String = "",
        /**
         * The board's own clock, when the controller had no other.
         *
         * Non-null exactly when [timestamp] is empty. This is not a degraded
         * reading - the interval between it and its neighbours is known and
         * correct - it is a reading whose *origin* is not yet on a calendar.
         */
        val relBoot: Int? = null,
        val relMs: Long? = null,
    ) : ServerMessage {
        val relative: Boolean get() = relBoot != null && relMs != null
    }

    /**
     * One equivalence: the board's clock and the world's, at the same instant.
     *
     * Only ever compared within its own [boot]. A reboot restarts the board's
     * count by an amount nobody measured, so an equivalence from another run
     * says nothing at all about this one.
     */
    data class TimeAnchor(
        val boot: Int,
        val relMs: Long,
        val epochMs: Long,
        val utcOffsetMinutes: Int,
    )

    /**
     * Every equivalence the controller holds, and which run it is on now.
     *
     * The controller sends the raw pairs and does no conversion. That is what
     * lets this client redo the conversion if a better time source turns up,
     * which is impossible once a timestamp has been baked into flash.
     */
    data class TimeMap(
        val boot: Int,
        val anchors: List<TimeAnchor>,
    ) : ServerMessage

    /** [phaseSeconds] is how long this phase is expected to last, when the daemon
     *  knows - the duty-cycle phases. The client counts it down locally rather
     *  than the daemon ticking once a second, which would defeat the point of a
     *  mode whose whole purpose is that nothing runs between measurements. */
    data class StateMsg(
        val state: String,
        val detail: String,
        val phaseSeconds: Double? = null,
    ) : ServerMessage

    data class RawResponse(val request: String, val response: String) : ServerMessage

    data class FileEntry(
        val name: String,
        val rows: Int,
        val bytes: Long,
        val start: String?,
        /** Recorded with no clock: [start] is empty and its rows carry rel_boot/rel_ms. */
        val relative: Boolean = false,
    )

    /**
     * One page of what the controller holds, newest first.
     *
     * A `file_list` is bounded by one frame, and the board holds more sessions
     * than one frame can name - 84 against 64, measured on 2026-08-04. So the
     * listing is walked: [next] says where the rest begin, and null means the
     * walk is over. See `wire.file_list_page`.
     *
     * [total] is what the controller holds in all, the same figure `storage`
     * reports, and it is what tells a copy whether it got everything.
     *
     * A controller too old to page sends neither, which reads here as offset 0,
     * a total of however many it named, and nothing following - exactly what
     * those controllers have always meant.
     */
    data class FileList(
        val files: List<FileEntry>,
        val offset: Int = 0,
        val total: Int = files.size,
        /**
         * The offset to ask for to continue, or null when the listing is done.
         *
         * Never derived from `offset + files.size`: a controller skips entries
         * it will not serve, so a page can be shorter than the run of the
         * listing it consumed, and a client adding lengths would ask for the
         * same page for ever.
         */
        val next: Int? = null,
    ) : ServerMessage

    data class FileData(
        val name: String,
        val columns: List<String>,
        val rows: List<List<String>>,
        val done: Boolean,
    ) : ServerMessage

    /** [needsControl] marks a refusal caused by someone else holding the lock,
     *  so the UI can offer to take control instead of just showing an error.
     *
     *  [ref] is the command this error answers, so a client can tell whether it
     *  belongs to its own in-flight transfer. Blank means the daemon did not say
     *  - older daemons never do - and is treated as concerning everything, which
     *  is exactly how errors behaved before the field existed. Use
     *  [Wire.errorConcerns]. */
    data class ErrorMsg(
        val message: String,
        val needsControl: Boolean = false,
        val atCapacity: Boolean = false,
        val ref: String = Wire.ERROR_REF_NONE,
    ) : ServerMessage {
        /** True if this error is about [command], or names nothing at all. */
        fun concerns(command: String): Boolean = Wire.errorConcerns(ref, command)
    }

    /** Who currently may change the sensor's state. Null holder means nobody. */
    data class Control(
        val holder: Int?,
        val holderName: String?,
        val expiresIn: Double,
    ) : ServerMessage

    data class Watcher(val id: Int, val name: String)

    /** Everyone connected to the sensor right now. [transport] names the link the
     *  daemon counted [max] on; resolve the honest figure for this client's own
     *  link with [Wire.effectiveMaxClients]. */
    data class Clients(
        val clients: List<Watcher>,
        val max: Int,
        val transport: String? = null,
    ) : ServerMessage

    /** Header for a session-archive transfer: what is coming and how much. */
    data class BackupMeta(
        val name: String,
        val bytes: Long,
        val sessions: Int,
        val rows: Long,
    ) : ServerMessage

    /** One base64 slice of the archive. [done] marks the end of the stream. */
    data class BackupChunk(
        val seq: Int,
        val data: String,
        val done: Boolean,
    ) : ServerMessage

    /**
     * What an import did to the controller's stored data.
     *
     * [added] is the only number that means anything changed. [duplicate] is
     * readings the controller already had and kept - a re-import of a file the
     * controller is the source of is all duplicates and no additions, which is
     * what makes the round trip safe to repeat.
     */
    data class ImportResult(
        val name: String,
        val added: Int,
        val duplicate: Int,
        val rejected: Int,
        val sessions: Int,
        /**
         * `ok`, `full` (the controller ran out of room), `busy` (another device
         * was importing and none of this was taken) or `error`.
         */
        val state: String,
        val message: String,
    ) : ServerMessage {
        val ok: Boolean get() = state == "ok"
        val full: Boolean get() = state == "full"

        /**
         * The controller stored none of it, and this is not a success.
         *
         * Worth its own name because the numbers cannot tell the two apart: a
         * refused transfer and a transfer of readings the controller already
         * held both report `added = 0`, and only one of them means the data is
         * safely on the controller.
         */
        val tookNothing: Boolean get() = state == "busy" || state == "error"
    }

    /**
     * How full the controller's store is.
     *
     * Sent unasked when [warn] changes, which is the trigger for copying
     * everything to this device: the point of the threshold is that the data
     * leaves before there is any pressure to delete it.
     */
    data class Storage(
        val usedPages: Int,
        val totalPages: Int,
        val readings: Int,
        val sessions: Int,
        val fraction: Double,
        val warnAt: Double,
        val warn: Boolean,
        val ready: Boolean,
    ) : ServerMessage

    /**
     * The per-page occupancy map of the controller's store partition.
     *
     * One cell per 512-byte flash page, the partition's regions in address
     * order, each with its own write position - the seat map behind the
     * storage icon. Answered on [Wire.CMD_STORAGE_MAP], never volunteered:
     * only the open dialog asks, and it asks again while it is open, so the
     * map is as live as its refresh and no fresher than it claims.
     *
     * The section list is open on purpose. Today it is the ring and the two
     * preserved-alert regions; when the tiered generations land they arrive
     * as more sections, and a section whose [MapSection.id] this build does
     * not know is still drawn, labelled by its id.
     */
    data class StorageMap(
        val pageBytes: Int,
        val pages: Int,
        val sections: List<MapSection>,
    ) : ServerMessage

    /**
     * One region of the partition: where it sits, how far its write position
     * has got ([head], plus [wraps] completed passes), and a state per page.
     *
     * [states] holds [Wire.PAGE_STATE_EMPTY] / [Wire.PAGE_STATE_DATA] /
     * [Wire.PAGE_STATE_ANCHOR] / [Wire.PAGE_STATE_BAD], one per page, decoded
     * from the wire's packed cells at parse time - and validated there: a map
     * whose cells cannot land on the right pages is dropped whole rather than
     * drawn shifted, which is the one place this parser is strict where the
     * rest of it is lenient.
     */
    data class MapSection(
        val id: String,
        val start: Int,
        val pages: Int,
        val head: Int,
        val wraps: Int,
        val states: List<Int>,
    )

    /**
     * What the controller has preserved against the ring wrapping.
     *
     * Sent unasked on connection and whenever it changes, like [Storage] and
     * deliberately not folded into it: [Storage] is about the ring, whose answer
     * to being full is to wrap, and this is about the area outside it, whose
     * answer to being full is the only rate reduction the controller has. One
     * figure for both would hide which situation the board is in.
     */
    data class Alerts(
        val ready: Boolean,
        /** Preserved segments the controller holds. */
        val segments: Int,
        /** How full the preserved-samples area is, 0 to 1. */
        val pressure: Double,
        /**
         * The recording interval the controller is insisting on, in seconds.
         *
         * Zero normally. Non-zero only when the *preserved* data is under
         * pressure - a full ring never causes one - and worth showing, because a
         * board that has moved to one sample a minute must not look like a board
         * recording every second.
         */
        val rateFloorSeconds: Double,
        /** Completed passes of the controller's ring. Keeps rising across wraps. */
        val wraps: Int,
        /** The line per field the controller is preserving against. */
        val thresholds: Map<String, Double>,
    ) : ServerMessage {
        val rateReduced: Boolean get() = rateFloorSeconds > 0.0
    }

    /**
     * One preserved excursion, summarised.
     *
     * Every figure here survives even when the samples do not, which is what
     * [hasBody] false means: the controller's preserved-sample area turned over
     * and this is all that is left of the event. That is the summary doing its
     * job, not a broken message.
     */
    data class AlertSegment(
        val seq: Int,
        /** The CSV name of the session these readings belong to. */
        val session: String,
        /** Which of [Wire.FIELDS] crossed the line. */
        val field: String,
        val threshold: Double,
        val start: String,
        val stop: String,
        val first: Double,
        val last: Double,
        /** The middle of the excursion, averaged over a local neighbourhood. */
        val midpoint: Double,
        val mean: Double,
        val variance: Double,
        val minimum: Double,
        val maximum: Double,
        val points: Int,
        val firstIndex: Int,
        val lastIndex: Int,
        /** The controller's ring wrap count when it was preserved. */
        val wrap: Int,
        /** False once the samples themselves have been reduced away. */
        val hasBody: Boolean,
    ) : ServerMessage

    /**
     * The samples of one preserved segment, chunked.
     *
     * Deliberately the same `columns`/`rows` shape as [FileData], so these fold
     * into the session's history through the code a backfill already uses - and
     * therefore through the same timestamp handling. Anything else would mean two
     * ways of placing a reading in time, which is how readings end up in the
     * wrong recording.
     */
    data class AlertData(
        val seq: Int,
        val name: String,
        val columns: List<String>,
        val rows: List<List<String>>,
        val done: Boolean,
    ) : ServerMessage

    /** What an erase did, or - much more often - why it would not. */
    data class EraseResult(
        val state: String,
        val message: String,
        val sessionsConfirmed: Int,
        val sessionsRefused: Int,
        val pagesErased: Int,
        val readingsErased: Int,
        val overridden: Boolean,
    ) : ServerMessage {
        val done: Boolean get() = state == "done"
    }

    /**
     * Where a back-up-and-clear has got to. The only thing the sensor volunteers
     * about one.
     *
     * Everything else is this device asking, which is what makes an interruption
     * safe at any point: a controller that is never mid-decision has nothing to
     * roll back. See BACKUP_AND_CLEAR.md.
     */
    data class DrainState(
        /** One of `Wire.DRAIN_*`. */
        val state: String,
        val message: String,
        /** The block this is about, or 0 when it is about the drain as a whole. */
        val block: Long,
        /** `Wire.DRAIN_KIND_*`, or "" when [block] is 0. */
        val kind: String,
        /** The block holding the write head: copied, proved, then deliberately kept. */
        val final: Boolean,
        /** What the sensor says it sent for [block], and its digest. */
        val rows: Int,
        val digest: Int,
        /** Blocks the sensor still holds, counted from its flash. */
        val blocksLeft: Int,
        val blocksFreed: Int,
        val blocksKept: Int,
        val blocksRefused: Int,
        val readingsCopied: Int,
        val segmentsCopied: Int,
        val readingsFreed: Int,
    ) : ServerMessage

    /**
     * One block's rows, chunked.
     *
     * Two shapes, chosen by [kind]: [Wire.DRAIN_READING_COLUMNS] for readings and
     * [Wire.DRAIN_SEGMENT_COLUMNS] for preserved segment summaries. Every cell is
     * a string, as in [FileData], so these are written down through code that
     * already writes CSV.
     *
     * These frames are the copy. Losing one silently would mean confirming a
     * block this device does not hold, so they carry [MustNotDrop] like every
     * other message that moves data.
     */
    data class DrainData(
        val block: Long,
        val kind: String,
        val columns: List<String>,
        val rows: List<List<String>>,
        val done: Boolean,
    ) : ServerMessage

    /** A well-formed frame whose `type` this client does not model. */
    data class Unknown(val type: String) : ServerMessage
}

/**
 * Messages that carry file content and so must never be dropped.
 *
 * The live stream is deliberately lossy - a slow UI must never stall the socket,
 * and a skipped reading is invisible a second later. A skipped archive chunk is
 * not: it silently corrupts the file. The daemon already sorts its own queue
 * this way; this is the client half of the same contract.
 */
fun ServerMessage.mustNotBeDropped(): Boolean =
    this is ServerMessage.BackupChunk ||
        this is ServerMessage.BackupMeta ||
        this is ServerMessage.FileData ||
        // Not file content, but the only account of what an import did to the
        // stored data. Dropping it leaves the user with a transfer that appears
        // to have gone nowhere, which is the one impression this feature must
        // never give.
        this is ServerMessage.ImportResult ||
        this is ServerMessage.EraseResult ||
        // Not file content either, but the only thing that turns relative
        // readings into times. Dropping it leaves a session on the screen with
        // no clock behind it and no way to acquire one until a reconnect.
        this is ServerMessage.TimeMap ||
        // The block being copied off the sensor before it is freed, and the
        // sensor's answer about whether it was. Dropping a data frame would mean
        // confirming a block this device does not hold; dropping a state frame
        // would leave a drain that has stopped looking like one still running.
        // This is the one place in the app where a lost frame could cost the
        // captain a reading.
        this is ServerMessage.DrainData ||
        this is ServerMessage.DrainState

object Messages {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Parse one JSONL frame into a [ServerMessage], or `null` if it is not usable. */
    fun parse(line: String): ServerMessage? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val obj = try {
            json.parseToJsonElement(trimmed).jsonObject
        } catch (e: Exception) {
            return null
        }
        return fromObject(obj)
    }

    /** Decode a nested acquisition-profile object, tolerating missing fields. */
    private fun JsonElement.asProfile(): ServerMessage.AcquisitionProfile? {
        val o = this as? JsonObject ?: return null
        fun num(key: String, fallback: Double) =
            o[key]?.jsonPrimitive?.doubleOrNull ?: fallback
        return ServerMessage.AcquisitionProfile(
            mode = o["mode"]?.jsonPrimitive?.contentOrNull ?: Wire.MODE_CONTINUOUS,
            intervalSeconds = num("interval", 1.0),
            cycleSeconds = num("cycle_s", 900.0),
            warmupSeconds = num("warmup_s", 30.0),
            averageSeconds = num("average_s", 30.0),
        )
    }

    private fun JsonElement.asPower(): ServerMessage.PowerStatus? {
        val o = this as? JsonObject ?: return null
        fun num(key: String) = o[key]?.jsonPrimitive?.doubleOrNull
        fun flag(key: String) = o[key]?.jsonPrimitive?.booleanOrNull ?: false
        return ServerMessage.PowerStatus(
            source = o["source"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            charging = flag("charging"),
            percent = num("percent"),
            voltageV = num("voltage_v"),
            runtimeHours = num("runtime_hours"),
            method = o["method"]?.jsonPrimitive?.contentOrNull ?: "none",
            averageMa = num("average_ma"),
            cleanDueInHours = num("clean_due_in_hours"),
            undervoltage = flag("undervoltage"),
        )
    }

    /**
     * Decode a whole `power_estimate` reply. Null when the frame carries no
     * usable current total, because a panel with no headline number has nothing
     * honest to show. `hours` fields are kept only under a runtime status of
     * "ok": a status that says "unknown" while a number rides beside it is a
     * contradiction, and the number is the half that has to lose.
     */
    private fun JsonObject.asPowerEstimate(): ServerMessage.PowerEstimate? {
        val current = this["current"] as? JsonObject ?: return null
        val totalMw = current["total_mw"]?.jsonPrimitive?.doubleOrNull ?: return null
        val sources = this["sources"] as? JsonObject
        fun sourceCount(key: String) = sources?.get(key)?.jsonPrimitive?.intOrNull ?: 0

        val bySubsystem = LinkedHashMap<String, Double>()
        (current["by_subsystem"] as? JsonObject)?.forEach { (key, value) ->
            value.jsonPrimitive.doubleOrNull?.let { bySubsystem[key] = it }
        }

        val runtime = this["runtime"] as? JsonObject
        val runtimeStatus = runtime?.get("status")?.jsonPrimitive?.contentOrNull
            ?: Wire.POWER_RUNTIME_NO_CAPACITY
        val runtimeKnown = runtimeStatus == Wire.POWER_RUNTIME_OK

        val alternatives = (this["alternatives"] as? JsonArray)
            ?.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                ServerMessage.PowerAlternative(
                    mode = o["mode"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    cycleSeconds = o["cycle_s"]?.jsonPrimitive?.doubleOrNull,
                    sleeps = o["sleeps"]?.jsonPrimitive?.booleanOrNull ?: false,
                    totalMw = o["total_mw"]?.jsonPrimitive?.doubleOrNull
                        ?: return@mapNotNull null,
                    hours = if (runtimeKnown) o["hours"]?.jsonPrimitive?.doubleOrNull else null,
                )
            } ?: emptyList()

        return ServerMessage.PowerEstimate(
            basis = str("basis") ?: Wire.POWER_BASIS_MODELLED,
            sourcesDatasheet = sourceCount("datasheet"),
            sourcesMeasured = sourceCount("measured"),
            sourcesEstimate = sourceCount("estimate"),
            profile = current["profile"]?.asProfile(),
            sleeps = current["sleeps"]?.jsonPrimitive?.booleanOrNull ?: false,
            totalMw = totalMw,
            bySubsystemMw = bySubsystem,
            alternatives = alternatives,
            runtimeStatus = runtimeStatus,
            runtimeHours = if (runtimeKnown) runtime?.get("hours")?.jsonPrimitive?.doubleOrNull else null,
            runtimeModelledHours =
                if (runtimeKnown) runtime?.get("modelled_hours")?.jsonPrimitive?.doubleOrNull else null,
            runtimeDerating =
                if (runtimeKnown) runtime?.get("derating")?.jsonPrimitive?.doubleOrNull else null,
        )
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.strOr(key: String, default: String = ""): String = str(key) ?: default

    /**
     * Decode a `storage_map`, strictly.
     *
     * The one message this parser refuses rather than repairs: every cell is a
     * claim about one specific flash page, so cells that cannot be placed -
     * bad base64, a length that disagrees with the geometry, a head outside
     * its section - drop the whole frame. A map drawn one page shifted would
     * be precise-looking and wrong, which is worse than no map and the exact
     * failure the per-page message exists to avoid.
     */
    // kotlin.io.encoding.Base64 in place of java.util.Base64: same strict
    // alphabet-and-padding refusal, and it exists on every KMP target. The
    // opt-in is scoped to this one decode.
    @OptIn(ExperimentalEncodingApi::class)
    private fun parseStorageMap(obj: JsonObject): ServerMessage? {
        val pageBytes = obj["page_bytes"]?.jsonPrimitive?.intOrNull ?: return null
        val pages = obj["pages"]?.jsonPrimitive?.intOrNull ?: return null
        val sections = obj["sections"] as? JsonArray ?: return null
        val decoded = ArrayList<ServerMessage.MapSection>(sections.size)
        for (element in sections) {
            val o = element as? JsonObject ?: return null
            val id = o.str("id") ?: return null
            val start = o["start"]?.jsonPrimitive?.intOrNull ?: return null
            val sectionPages = o["pages"]?.jsonPrimitive?.intOrNull ?: return null
            val head = o["head"]?.jsonPrimitive?.intOrNull ?: return null
            val wraps = o["wraps"]?.jsonPrimitive?.intOrNull ?: return null
            val cells = o.str("cells") ?: return null
            if (sectionPages < 0 || wraps < 0) return null
            if (head !in 0 until maxOf(1, sectionPages)) return null
            val packed = try {
                Base64.decode(cells)
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (packed.size != (sectionPages + 3) / 4) return null
            val states = ArrayList<Int>(sectionPages)
            for (i in 0 until sectionPages) {
                states.add((packed[i shr 2].toInt() shr ((i and 3) * 2)) and 0x3)
            }
            decoded.add(ServerMessage.MapSection(id, start, sectionPages, head, wraps, states))
        }
        return ServerMessage.StorageMap(pageBytes, pages, decoded)
    }

    private fun fromObject(obj: JsonObject): ServerMessage? {
        return when (val type = obj.str("type")) {
            Wire.MSG_HELLO -> ServerMessage.Hello(
                product = obj.strOr("product"),
                serial = obj.strOr("serial"),
                firmware = obj.strOr("firmware"),
                state = obj.strOr("state"),
                csv = obj.str("csv"),
                index = obj.strOr("index"),
                detail = obj.strOr("detail"),
                phaseSeconds = obj["seconds"]?.jsonPrimitive?.doubleOrNull,
                profile = obj["profile"]?.asProfile(),
                power = obj["power"]?.asPower(),
                // Absent on older daemons; assume capable rather than hiding the
                // feature from a sensor that supports it.
                supportsSleep = obj["supports_sleep"]?.jsonPrimitive?.booleanOrNull ?: true,
                canPowerOff = obj["can_power_off"]?.jsonPrimitive?.booleanOrNull ?: true,
                hasClock = obj["has_clock"]?.jsonPrimitive?.booleanOrNull ?: true,
                clientId = obj["client_id"]?.jsonPrimitive?.intOrNull,
                maxClients = obj["max_clients"]?.jsonPrimitive?.intOrNull ?: 0,
                transport = obj.str("transport"),
            )

            Wire.MSG_PROFILE -> obj["profile"]?.asProfile()

            Wire.MSG_POWER -> obj["power"]?.asPower()

            Wire.MSG_POWER_ESTIMATE -> obj.asPowerEstimate()

            Wire.MSG_READING -> {
                val values = LinkedHashMap<String, Double>()
                for (field in Wire.FIELDS) {
                    obj[field]?.jsonPrimitive?.doubleOrNull?.let { values[field] = it }
                }
                ServerMessage.Reading(
                    csv = obj.str("csv"),
                    index = obj.strOr("index"),
                    timestamp = obj.strOr("timestamp"),
                    values = values,
                    event = obj.strOr(Wire.COL_EVENT),
                    relBoot = obj[Wire.COL_REL_BOOT]?.jsonPrimitive?.intOrNull,
                    relMs = obj[Wire.COL_REL_MS]?.jsonPrimitive?.longOrNull,
                )
            }

            Wire.MSG_TIME_MAP -> ServerMessage.TimeMap(
                boot = obj["boot"]?.jsonPrimitive?.intOrNull ?: 0,
                anchors = obj["anchors"]?.jsonArray?.mapNotNull { element ->
                    val a = element as? JsonObject ?: return@mapNotNull null
                    ServerMessage.TimeAnchor(
                        boot = a["boot"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                        relMs = a[Wire.COL_REL_MS]?.jsonPrimitive?.longOrNull
                            ?: return@mapNotNull null,
                        epochMs = a["epoch_ms"]?.jsonPrimitive?.longOrNull
                            ?: return@mapNotNull null,
                        utcOffsetMinutes =
                            a["utc_offset_min"]?.jsonPrimitive?.intOrNull ?: 0,
                    )
                } ?: emptyList(),
            )

            Wire.MSG_STATE -> ServerMessage.StateMsg(
                state = obj.strOr("state"),
                detail = obj.strOr("detail"),
                phaseSeconds = obj["seconds"]?.jsonPrimitive?.doubleOrNull,
            )

            Wire.MSG_RAW -> ServerMessage.RawResponse(
                request = obj.strOr("request"),
                response = obj.strOr("response"),
            )

            Wire.MSG_FILES -> {
                val files = obj["files"]?.jsonArray?.mapNotNull { element ->
                    val fileObj = element as? JsonObject ?: return@mapNotNull null
                    ServerMessage.FileEntry(
                        name = fileObj.strOr("name"),
                        rows = fileObj["rows"]?.jsonPrimitive?.intOrNull ?: 0,
                        bytes = fileObj["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        start = fileObj.str("start"),
                        relative = fileObj["relative"]?.jsonPrimitive?.booleanOrNull ?: false,
                    )
                } ?: emptyList()
                val offset = obj[Wire.K_FILES_OFFSET]?.jsonPrimitive?.intOrNull ?: 0
                val following = obj[Wire.K_FILES_NEXT]?.jsonPrimitive?.intOrNull
                ServerMessage.FileList(
                    files = files,
                    offset = maxOf(0, offset),
                    // An older controller says nothing about a total, and what
                    // it named is all it has - that is what "no paging" meant.
                    total = obj[Wire.K_FILES_TOTAL]?.jsonPrimitive?.intOrNull ?: files.size,
                    // Never backwards and never standing still. Nothing on this
                    // wire is authenticated, so a controller that answered its
                    // own offset would put the walk in a loop that never ends -
                    // and the walk is what a full copy is made of.
                    next = following?.takeIf { it > maxOf(0, offset) },
                )
            }

            Wire.MSG_ALERTS -> ServerMessage.Alerts(
                ready = obj["ready"]?.jsonPrimitive?.booleanOrNull ?: false,
                segments = obj["segments"]?.jsonPrimitive?.intOrNull ?: 0,
                pressure = obj["pressure"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                rateFloorSeconds = obj["rate_floor_s"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                wraps = obj["wraps"]?.jsonPrimitive?.intOrNull ?: 0,
                thresholds = (obj["thresholds"] as? JsonObject)
                    ?.mapNotNull { (field, value) ->
                        value.jsonPrimitive.doubleOrNull?.let { field to it }
                    }?.toMap() ?: emptyMap(),
            )

            Wire.MSG_ALERT_SEGMENT -> ServerMessage.AlertSegment(
                seq = obj["seq"]?.jsonPrimitive?.intOrNull ?: 0,
                session = obj.strOr("session"),
                field = obj.strOr("field"),
                threshold = obj["threshold"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                start = obj.strOr("start"),
                stop = obj.strOr("stop"),
                first = obj["first"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                last = obj["last"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                midpoint = obj["midpoint"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                mean = obj["mean"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                variance = obj["variance"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                minimum = obj["minimum"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                maximum = obj["maximum"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                points = obj["points"]?.jsonPrimitive?.intOrNull ?: 0,
                firstIndex = obj["first_index"]?.jsonPrimitive?.intOrNull ?: 0,
                lastIndex = obj["last_index"]?.jsonPrimitive?.intOrNull ?: 0,
                wrap = obj["wrap"]?.jsonPrimitive?.intOrNull ?: 0,
                // Absent means present: an older controller that somehow sent one
                // of these would be one whose samples are still there.
                hasBody = obj["has_body"]?.jsonPrimitive?.booleanOrNull ?: true,
            )

            Wire.MSG_ALERT_DATA -> {
                val columns = obj["columns"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                val rows = obj["rows"]?.jsonArray?.map { rowElement ->
                    rowElement.jsonArray.map { cell -> cell.jsonPrimitive.contentOrNull ?: "" }
                } ?: emptyList()
                ServerMessage.AlertData(
                    seq = obj["seq"]?.jsonPrimitive?.intOrNull ?: 0,
                    name = obj.strOr("name"),
                    columns = columns,
                    rows = rows,
                    done = obj["done"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }

            Wire.MSG_DRAIN_STATE -> ServerMessage.DrainState(
                state = obj.strOr("state"),
                message = obj.strOr("message"),
                block = obj["block"]?.jsonPrimitive?.longOrNull ?: 0L,
                kind = obj.strOr("kind"),
                final = obj["final"]?.jsonPrimitive?.booleanOrNull ?: false,
                rows = obj["rows"]?.jsonPrimitive?.intOrNull ?: 0,
                // Hex, and unsigned, so the top bit survives the trip. Read
                // through a Long for exactly that reason - see [Wire.digestHex].
                digest = obj.strOr("digest").let { text ->
                    runCatching { text.toLong(16).toInt() }.getOrDefault(0)
                },
                blocksLeft = obj["blocks_left"]?.jsonPrimitive?.intOrNull ?: 0,
                blocksFreed = obj["blocks_freed"]?.jsonPrimitive?.intOrNull ?: 0,
                blocksKept = obj["blocks_kept"]?.jsonPrimitive?.intOrNull ?: 0,
                blocksRefused = obj["blocks_refused"]?.jsonPrimitive?.intOrNull ?: 0,
                readingsCopied = obj["readings_copied"]?.jsonPrimitive?.intOrNull ?: 0,
                segmentsCopied = obj["segments_copied"]?.jsonPrimitive?.intOrNull ?: 0,
                readingsFreed = obj["readings_freed"]?.jsonPrimitive?.intOrNull ?: 0,
            )

            Wire.MSG_DRAIN_DATA -> {
                val columns = obj["columns"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                val rows = obj["rows"]?.jsonArray?.map { rowElement ->
                    rowElement.jsonArray.map { cell -> cell.jsonPrimitive.contentOrNull ?: "" }
                } ?: emptyList()
                ServerMessage.DrainData(
                    block = obj["block"]?.jsonPrimitive?.longOrNull ?: 0L,
                    kind = obj.strOr("kind"),
                    columns = columns,
                    rows = rows,
                    done = obj["done"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }

            Wire.MSG_FILE_DATA -> {
                val columns = obj["columns"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                val rows = obj["rows"]?.jsonArray?.map { rowElement ->
                    rowElement.jsonArray.map { cell -> cell.jsonPrimitive.contentOrNull ?: "" }
                } ?: emptyList()
                ServerMessage.FileData(
                    name = obj.strOr("name"),
                    columns = columns,
                    rows = rows,
                    done = obj["done"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }

            Wire.MSG_BACKUP_META -> ServerMessage.BackupMeta(
                name = obj.strOr("name"),
                bytes = obj["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                sessions = obj["sessions"]?.jsonPrimitive?.intOrNull ?: 0,
                rows = obj["rows"]?.jsonPrimitive?.longOrNull ?: 0L,
            )

            Wire.MSG_BACKUP_CHUNK -> ServerMessage.BackupChunk(
                seq = obj["seq"]?.jsonPrimitive?.intOrNull ?: 0,
                data = obj.strOr("data"),
                done = obj["done"]?.jsonPrimitive?.booleanOrNull ?: false,
            )

            Wire.MSG_IMPORT_RESULT -> ServerMessage.ImportResult(
                name = obj.strOr("name"),
                added = obj["added"]?.jsonPrimitive?.intOrNull ?: 0,
                duplicate = obj["duplicate"]?.jsonPrimitive?.intOrNull ?: 0,
                rejected = obj["rejected"]?.jsonPrimitive?.intOrNull ?: 0,
                sessions = obj["sessions"]?.jsonPrimitive?.intOrNull ?: 0,
                state = obj.strOr("state"),
                message = obj.strOr("message"),
            )

            Wire.MSG_STORAGE -> ServerMessage.Storage(
                usedPages = obj["used_pages"]?.jsonPrimitive?.intOrNull ?: 0,
                totalPages = obj["total_pages"]?.jsonPrimitive?.intOrNull ?: 0,
                readings = obj["readings"]?.jsonPrimitive?.intOrNull ?: 0,
                sessions = obj["sessions"]?.jsonPrimitive?.intOrNull ?: 0,
                fraction = obj["fraction"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                warnAt = obj["warn_at"]?.jsonPrimitive?.doubleOrNull ?: 0.85,
                warn = obj["warn"]?.jsonPrimitive?.booleanOrNull ?: false,
                ready = obj["ready"]?.jsonPrimitive?.booleanOrNull ?: false,
            )

            Wire.MSG_STORAGE_MAP -> parseStorageMap(obj)

            Wire.MSG_ERASE_RESULT -> ServerMessage.EraseResult(
                state = obj.strOr("state"),
                message = obj.strOr("message"),
                sessionsConfirmed = obj["sessions_confirmed"]?.jsonPrimitive?.intOrNull ?: 0,
                sessionsRefused = obj["sessions_refused"]?.jsonPrimitive?.intOrNull ?: 0,
                pagesErased = obj["pages_erased"]?.jsonPrimitive?.intOrNull ?: 0,
                readingsErased = obj["readings_erased"]?.jsonPrimitive?.intOrNull ?: 0,
                overridden = obj["overridden"]?.jsonPrimitive?.booleanOrNull ?: false,
            )

            Wire.MSG_ERROR -> ServerMessage.ErrorMsg(
                message = obj.strOr("message"),
                needsControl = obj["needs_control"]?.jsonPrimitive?.booleanOrNull ?: false,
                atCapacity = obj["at_capacity"]?.jsonPrimitive?.booleanOrNull ?: false,
                ref = obj.strOr("ref", Wire.ERROR_REF_NONE),
            )

            Wire.MSG_CONTROL -> ServerMessage.Control(
                holder = obj["holder"]?.jsonPrimitive?.intOrNull,
                holderName = obj.str("holder_name"),
                expiresIn = obj["expires_in"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            )

            Wire.MSG_CLIENTS -> {
                val list = obj["clients"]?.jsonArray?.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val id = o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    ServerMessage.Watcher(id, o["name"]?.jsonPrimitive?.contentOrNull ?: "client")
                } ?: emptyList()
                ServerMessage.Clients(
                    list,
                    obj["max"]?.jsonPrimitive?.intOrNull ?: 0,
                    obj.str("transport"),
                )
            }

            null -> null
            else -> ServerMessage.Unknown(type)
        }
    }
}
