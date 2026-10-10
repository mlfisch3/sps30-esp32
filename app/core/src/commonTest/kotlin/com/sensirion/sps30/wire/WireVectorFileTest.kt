package com.sensirion.sps30.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Holds this client to `vectors/wire_vectors.json` - the wire contract as
 * `wire.py` emits it, by machine instead of by memory.
 *
 * The other wire tests carry frames copied out of the generated C header by
 * hand, which is parity kept by remembering to copy. This one loads the
 * generated file itself, so a change to `wire.py` reaches this suite through
 * regeneration alone: the Python pack proves the file matches `wire.py`
 * (`tests/test_wire_vectors_json.py`), and this proves the Kotlin parses those
 * exact bytes back to the values `wire.py` was given. A Swift XCTest on the
 * iOS port is expected to consume the same file the same way - that is the
 * file's whole reason to exist.
 *
 * It lives in `commonTest`, so it runs identically on the JVM and on every
 * Kotlin/Native target: the same vectors that hold the Android client hold
 * the native compilation of the same code, which is the parity the shared
 * core exists to make structural. The file arrives through [wireVectorsText],
 * whose per-platform halves try the `WIRE_VECTORS_JSON` environment variable
 * and then walk up from the working directory. Missing means FAIL, not skip:
 * a pack may never look successful when it did not run.
 */
class WireVectorFileTest {

    private val doc: JsonObject by lazy {
        Json.parseToJsonElement(wireVectorsText()).jsonObject
    }

    private fun obj(key: String): JsonObject = doc[key]!!.jsonObject
    private fun JsonObject.s(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.i(key: String): Int = this[key]!!.jsonPrimitive.content.toInt()
    private fun JsonObject.d(key: String): Double = this[key]!!.jsonPrimitive.content.toDouble()
    private fun JsonObject.strings(key: String): List<String> =
        this[key]!!.jsonArray.map { it.jsonPrimitive.content }

    /** The float32 bit pattern of a parsed value, in the file's 8-hex-digit form. */
    private fun bitsOf(value: Double): String =
        value.toFloat().toRawBits().toUInt().toString(16).padStart(8, '0')

    // --- the vocabulary -----------------------------------------------------

    @Test
    fun every_mirrored_message_type_is_wire_pys_character_for_character() {
        val messages = obj("messages")
        val mirrored = mapOf(
            "hello" to Wire.MSG_HELLO, "reading" to Wire.MSG_READING,
            "state" to Wire.MSG_STATE, "raw_response" to Wire.MSG_RAW,
            "file_list" to Wire.MSG_FILES, "file_data" to Wire.MSG_FILE_DATA,
            "error" to Wire.MSG_ERROR, "profile" to Wire.MSG_PROFILE,
            "power" to Wire.MSG_POWER, "control" to Wire.MSG_CONTROL,
            "backup_meta" to Wire.MSG_BACKUP_META,
            "backup_chunk" to Wire.MSG_BACKUP_CHUNK,
            "clients" to Wire.MSG_CLIENTS, "time_map" to Wire.MSG_TIME_MAP,
            "storage" to Wire.MSG_STORAGE,
            "import_result" to Wire.MSG_IMPORT_RESULT,
            "erase_result" to Wire.MSG_ERASE_RESULT,
            "storage_map" to Wire.MSG_STORAGE_MAP, "alerts" to Wire.MSG_ALERTS,
            "alert_segment" to Wire.MSG_ALERT_SEGMENT,
            "alert_data" to Wire.MSG_ALERT_DATA,
            "drain_state" to Wire.MSG_DRAIN_STATE,
            "drain_data" to Wire.MSG_DRAIN_DATA,
            "power_estimate" to Wire.MSG_POWER_ESTIMATE,
        )
        // Every type the file names is mirrored here, and no mirror is missing:
        // a message this client has never heard of would otherwise sit in the
        // contract unparsed for as long as nobody happened to notice.
        assertEquals(messages.keys, mirrored.keys)
        for ((key, constant) in mirrored) assertEquals(messages.s(key), constant, key)
    }

    @Test
    fun every_mirrored_command_is_wire_pys_character_for_character() {
        val commands = obj("commands")
        val mirrored = mapOf(
            "pause" to Wire.CMD_PAUSE, "resume" to Wire.CMD_RESUME,
            "clean" to Wire.CMD_CLEAN, "restart" to Wire.CMD_RESTART,
            "raw" to Wire.CMD_RAW, "list_files" to Wire.CMD_LIST_FILES,
            "get_file" to Wire.CMD_GET_FILE,
            "set_profile" to Wire.CMD_SET_PROFILE,
            "shutdown" to Wire.CMD_SHUTDOWN, "identify" to Wire.CMD_IDENTIFY,
            "claim" to Wire.CMD_CLAIM, "release" to Wire.CMD_RELEASE,
            "backup" to Wire.CMD_BACKUP, "set_time" to Wire.CMD_SET_TIME,
            "put_file" to Wire.CMD_PUT_FILE, "erase" to Wire.CMD_ERASE,
            "storage" to Wire.CMD_STORAGE,
            "storage_map" to Wire.CMD_STORAGE_MAP,
            "get_alerts" to Wire.CMD_GET_ALERTS,
            "set_alerts" to Wire.CMD_SET_ALERTS,
            "get_power_estimate" to Wire.CMD_GET_POWER_ESTIMATE,
            "drain_begin" to Wire.CMD_DRAIN_BEGIN,
            "drain_next" to Wire.CMD_DRAIN_NEXT,
            "drain_confirm" to Wire.CMD_DRAIN_CONFIRM,
            "drain_cancel" to Wire.CMD_DRAIN_CANCEL,
        )
        assertEquals(commands.keys, mirrored.keys)
        for ((key, constant) in mirrored) assertEquals(commands.s(key), constant, key)
    }

    @Test
    fun states_modes_transports_and_vocabulary_constants_match() {
        val states = obj("states")
        assertEquals(Wire.STATE_CHECKING, states.s("checking"))
        assertEquals(Wire.STATE_MEASURING, states.s("measuring"))
        assertEquals(Wire.STATE_PAUSED, states.s("paused"))
        assertEquals(Wire.STATE_CLEANING, states.s("cleaning"))
        assertEquals(Wire.STATE_SLEEPING, states.s("sleeping"))
        assertEquals(Wire.STATE_WARMUP, states.s("warmup"))
        assertEquals(Wire.STATE_SHUTDOWN, states.s("shutting_down"))

        val modes = obj("modes")
        assertEquals(Wire.MODE_CONTINUOUS, modes.s("continuous"))
        assertEquals(Wire.MODE_DUTY_CYCLE, modes.s("duty_cycle"))

        val transports = obj("transports")
        assertEquals(Wire.TRANSPORT_TCP, transports.s("tcp"))
        assertEquals(Wire.TRANSPORT_BLE, transports.s("ble"))

        val constants = obj("constants")
        assertEquals(Wire.DEFAULT_PORT, constants.i("default_port"))
        assertEquals(Wire.INDEX_WIDTH, constants.i("index_width"))
        assertEquals(Wire.MAX_CLIENTS_TCP, constants.i("max_clients_tcp"))
        assertEquals(Wire.MAX_CLIENTS_BLE, constants.i("max_clients_ble"))
        assertEquals(Wire.ERROR_REF_NONE, constants.s("error_ref_none"))

        val drain = obj("drain")
        val kinds = drain["kinds"]!!.jsonObject
        assertEquals(Wire.DRAIN_KIND_BODY, kinds.s("body"))
        assertEquals(Wire.DRAIN_KIND_SEGMENTS, kinds.s("segments"))
        assertEquals(Wire.DRAIN_KIND_RING, kinds.s("ring"))
        assertEquals(
            listOf(
                Wire.DRAIN_IDLE, Wire.DRAIN_OFFERED, Wire.DRAIN_FREED,
                Wire.DRAIN_KEPT, Wire.DRAIN_REFUSED, Wire.DRAIN_FINISHED,
                Wire.DRAIN_ERROR,
            ),
            drain.strings("drain_states"),
        )

        val pageStates = obj("page_states")
        assertEquals(Wire.PAGE_STATE_EMPTY, pageStates.i("empty"))
        assertEquals(Wire.PAGE_STATE_DATA, pageStates.i("data"))
        assertEquals(Wire.PAGE_STATE_ANCHOR, pageStates.i("anchor"))
        assertEquals(Wire.PAGE_STATE_BAD, pageStates.i("bad"))

        val sections = obj("map_sections")
        assertEquals(Wire.MAP_SECTION_RING, sections.s("ring"))
        assertEquals(Wire.MAP_SECTION_ALERT_HEADERS, sections.s("alert_headers"))
        assertEquals(Wire.MAP_SECTION_ALERT_BODIES, sections.s("alert_bodies"))

        val timeSources = obj("time_sources")
        assertEquals(Wire.TIME_MEASURED, timeSources.s("measured"))
        assertEquals(Wire.TIME_DERIVED, timeSources.s("derived"))
        assertEquals(Wire.TIME_UNANCHORED, timeSources.s("unanchored"))
        assertEquals(Wire.TIME_MANUAL, timeSources.s("manual"))
        assertEquals(Wire.TIME_SOURCE_COLUMN, timeSources.s("column"))

        val power = obj("power")
        assertEquals(Wire.POWER_BASIS_MODELLED, power.s("basis_modelled"))
        assertEquals(
            listOf(
                Wire.POWER_RUNTIME_OK, Wire.POWER_RUNTIME_NO_CAPACITY,
                Wire.POWER_RUNTIME_UNUSABLE_CAPACITY, Wire.POWER_RUNTIME_NO_DRAW,
            ),
            power.strings("runtime_statuses"),
        )
    }

    @Test
    fun the_column_lists_match_order_included() {
        val columns = obj("columns")
        assertEquals(Wire.FIELDS, columns.strings("fields"))
        assertEquals(Wire.CSV_COLUMNS, columns.strings("csv"))
        assertEquals(Wire.CSV_COLUMNS_RELATIVE, columns.strings("csv_relative"))
        assertEquals(Wire.DRAIN_READING_COLUMNS, columns.strings("drain_reading"))
        assertEquals(Wire.DRAIN_SEGMENT_COLUMNS, columns.strings("drain_segment"))
        assertEquals(Wire.COL_REL_BOOT, columns.s("rel_boot"))
        assertEquals(Wire.COL_REL_MS, columns.s("rel_ms"))

        val keys = obj("file_list_keys")
        assertEquals(Wire.K_FILES_OFFSET, keys.s("offset"))
        assertEquals(Wire.K_FILES_TOTAL, keys.s("total"))
        assertEquals(Wire.K_FILES_NEXT, keys.s("next"))
    }

    @Test
    fun the_advertisement_is_the_one_this_client_browses_for() {
        val mdns = obj("mdns")
        assertEquals(Wire.MDNS_SERVICE_TYPE, mdns.s("service_type"))
        assertEquals(Wire.MDNS_TXT_VERSION, mdns.s("txt_version_key"))
        assertEquals(Wire.MDNS_TXT_PROTOCOL, mdns.s("txt_protocol_key"))
        val txt = mdns["txt"]!!.jsonArray.map { pair ->
            pair.jsonArray.map { it.jsonPrimitive.content }
        }
        assertEquals(
            Wire.MDNS_PROTOCOL_VERSION,
            txt.first { it[0] == Wire.MDNS_TXT_PROTOCOL }[1],
        )
    }

    // --- readings -----------------------------------------------------------

    @Test
    fun every_reading_line_parses_back_to_exactly_what_wire_py_was_given() {
        val readings = doc["readings"]!!.jsonArray.map { it.jsonObject }
        assertTrue(readings.size >= 16)
        for (vector in readings) {
            val name = vector.s("name")
            val msg = Messages.parse(vector.s("line"))
            assertIs<ServerMessage.Reading>(msg, name)
            if (vector["csv"] is JsonNull) assertNull(msg.csv, name)
            else assertEquals(vector.s("csv"), msg.csv, name)
            assertEquals(vector.s("index_text"), msg.index, name)
            assertEquals(vector.s("timestamp"), msg.timestamp, name)
            assertEquals(vector.s("event"), msg.event, name)
            assertFalse(msg.relative, name)

            // Bit for bit: the decimal in the frame is the shortest round-trip
            // form of a float32-promoted double, so parsing it and narrowing
            // must land on the identical bit pattern the Python packed.
            val expectedBits = vector.strings("values_hex")
            assertEquals(Wire.FIELDS.size, msg.values.size, name)
            Wire.FIELDS.forEachIndexed { i, field ->
                assertEquals(
                    expectedBits[i], bitsOf(msg.values[field]!!), "$field of $name",
                )
            }
        }
    }

    @Test
    fun a_clockless_reading_carries_the_boards_clock_and_no_invented_instant() {
        val vector = obj("relative_reading")
        val msg = Messages.parse(vector.s("line"))
        assertIs<ServerMessage.Reading>(msg)
        assertEquals(vector.s("csv"), msg.csv)
        assertEquals("", msg.timestamp)
        assertTrue(msg.relative)
        assertEquals(vector.i("rel_boot"), msg.relBoot)
        assertEquals(vector.i("rel_ms").toLong(), msg.relMs)
        val expectedBits = vector.strings("values_hex")
        Wire.FIELDS.forEachIndexed { i, field ->
            assertEquals(expectedBits[i], bitsOf(msg.values[field]!!), field)
        }
        assertTrue(Wire.isRelativeSessionName(msg.csv!!))
    }

    // --- pure functions of names and indices --------------------------------

    @Test
    fun format_index_matches_for_every_index_this_client_can_hold() {
        for (vector in doc["indices"]!!.jsonArray.map { it.jsonObject }) {
            val index = vector["index"]!!.jsonPrimitive.content.toLong()
            // Wire.formatIndex takes an Int because every index this client
            // formats came off this wire as one; the uint32 ceiling case is in
            // the file for implementations that format wider.
            if (index > Int.MAX_VALUE) continue
            assertEquals(vector.s("expected"), Wire.formatIndex(index.toInt()))
        }
    }

    @Test
    fun relative_session_names_are_recognised_and_pick_the_wider_header() {
        for (vector in doc["rel_session_names"]!!.jsonArray.map { it.jsonObject }) {
            val name = vector.s("expected")
            assertTrue(Wire.isRelativeSessionName(name), name)
            assertEquals(Wire.CSV_COLUMNS_RELATIVE, Wire.csvColumnsFor(name), name)
        }
        // And a dated name from the same file gets the classic thirteen.
        val dated = doc["times"]!!.jsonArray.first().jsonObject.s("csv")
        assertFalse(Wire.isRelativeSessionName(dated))
        assertEquals(Wire.CSV_COLUMNS, Wire.csvColumnsFor(dated))
    }

    // --- the storage occupancy map ------------------------------------------

    @Test
    fun both_storage_maps_unpack_to_the_exact_per_page_states_that_were_packed() {
        for (which in listOf("small", "full")) {
            val vector = obj("storage_map")[which]!!.jsonObject
            val msg = Messages.parse(vector.s("line"))
            assertIs<ServerMessage.StorageMap>(msg, which)
            assertEquals(vector.i("page_bytes"), msg.pageBytes, which)
            assertEquals(vector.i("pages"), msg.pages, which)
            val sections = vector["sections"]!!.jsonArray.map { it.jsonObject }
            assertEquals(sections.size, msg.sections.size, which)
            sections.zip(msg.sections).forEach { (stated, parsed) ->
                assertEquals(stated.s("id"), parsed.id)
                assertEquals(stated.i("start"), parsed.start)
                assertEquals(stated.i("pages"), parsed.pages)
                assertEquals(stated.i("head"), parsed.head)
                assertEquals(stated.i("wraps"), parsed.wraps)
                assertEquals(
                    stated.s("states"),
                    parsed.states.joinToString(""),
                    "$which/${stated.s("id")}",
                )
            }
        }
    }

    @Test
    fun the_packing_vectors_decode_through_this_parser_at_every_padding_edge() {
        // The four cells cases cover 1, 4, 5 and 7 pages - a full byte, partial
        // bytes both sides of one, and base64 padding. This client has no
        // standalone unpacker; the parser is the unpacker, so each case is
        // framed as a minimal storage_map and pushed through it.
        for (vector in doc["cells"]!!.jsonArray.map { it.jsonObject }) {
            val states = vector.s("states")
            val line = """{"type":"storage_map","page_bytes":512,"pages":${states.length},""" +
                """"sections":[{"id":"ring","start":0,"pages":${states.length},""" +
                """"head":0,"wraps":0,"cells":"${vector.s("cells")}"}]}"""
            val msg = Messages.parse(line)
            assertIs<ServerMessage.StorageMap>(msg, states)
            assertEquals(states, msg.sections.single().states.joinToString(""))
        }
    }

    // --- fan-clean accounting -----------------------------------------------

    @Test
    fun every_clean_vectors_power_line_carries_the_hours_wire_py_computed() {
        for (vector in doc["clean"]!!.jsonArray.map { it.jsonObject }) {
            val msg = Messages.parse(vector.s("line"))
            assertIs<ServerMessage.PowerStatus>(msg)
            assertEquals(vector.d("expected_hours"), msg.cleanDueInHours!!, 0.0)
        }
    }

    // --- profiles -----------------------------------------------------------

    @Test
    fun the_default_profile_line_parses_to_wire_pys_defaults() {
        val msg = Messages.parse(doc["profile_line"]!!.jsonPrimitive.content)
        assertIs<ServerMessage.AcquisitionProfile>(msg)
        val defaults = obj("default_profile")
        assertEquals(defaults.s("mode"), msg.mode)
        assertEquals(defaults.d("interval"), msg.intervalSeconds, 0.0)
        assertEquals(defaults.d("cycle_s"), msg.cycleSeconds, 0.0)
        assertEquals(defaults.d("warmup_s"), msg.warmupSeconds, 0.0)
        assertEquals(defaults.d("average_s"), msg.averageSeconds, 0.0)
    }

    // --- the paged listing --------------------------------------------------

    @Test
    fun the_three_listing_pages_parse_with_the_walks_one_end_condition_intact() {
        val fileList = obj("file_list")
        val listing = fileList["listing"]!!.jsonArray.map { it.jsonObject }

        val empty = Messages.parse(fileList["empty"]!!.jsonObject.s("line"))
        assertIs<ServerMessage.FileList>(empty)
        assertEquals(0, empty.files.size)
        assertEquals(0, empty.total)
        assertNull(empty.next)

        val whole = Messages.parse(fileList["whole"]!!.jsonObject.s("line"))
        assertIs<ServerMessage.FileList>(whole)
        assertEquals(2, whole.files.size)
        assertEquals(2, whole.total)
        assertNull(whole.next)
        whole.files.zip(listing.take(2)).forEach { (entry, stated) ->
            assertEquals(stated.s("name"), entry.name)
            assertEquals(stated.i("rows"), entry.rows)
            assertEquals(stated["bytes"]!!.jsonPrimitive.content.toLong(), entry.bytes)
            assertEquals(stated.s("start"), entry.start)
        }

        val page = Messages.parse(fileList["page"]!!.jsonObject.s("line"))
        assertIs<ServerMessage.FileList>(page)
        assertEquals(1, page.offset)
        assertEquals(3, page.total)
        assertEquals(2, page.next)
        assertEquals(listing[1].s("name"), page.files.single().name)
    }

    // --- preserved alert data -----------------------------------------------

    @Test
    fun the_preserved_alert_lines_parse_to_the_figures_wire_py_encoded() {
        val alerts = obj("alerts")

        val summary = Messages.parse(alerts.s("alerts_line"))
        assertIs<ServerMessage.Alerts>(summary)
        assertTrue(summary.ready)
        assertEquals(7, summary.segments)
        assertEquals(0.625, summary.pressure, 0.0)
        assertEquals(60.0, summary.rateFloorSeconds, 0.0)
        assertEquals(11, summary.wraps)
        assertEquals(alerts.d("default_pm2_5"), summary.thresholds["pm2_5"]!!, 0.0)

        val segment = Messages.parse(alerts.s("segment_line"))
        assertIs<ServerMessage.AlertSegment>(segment)
        assertEquals(42, segment.seq)
        assertEquals(alerts.s("session"), segment.session)
        assertEquals("pm2_5", segment.field)
        assertEquals(alerts.s("start"), segment.start)
        assertEquals(alerts.s("stop"), segment.stop)
        assertEquals(100, segment.points)
        assertEquals(3745, segment.firstIndex)
        assertEquals(3844, segment.lastIndex)
        assertEquals(11, segment.wrap)
        assertTrue(segment.hasBody)

        val reduced = Messages.parse(alerts.s("segment_reduced_line"))
        assertIs<ServerMessage.AlertSegment>(reduced)
        assertEquals(43, reduced.seq)
        assertFalse(reduced.hasBody)

        val data = Messages.parse(alerts.s("data_line"))
        assertIs<ServerMessage.AlertData>(data)
        assertEquals(42, data.seq)
        assertEquals(alerts.s("session"), data.name)
        assertEquals(Wire.CSV_COLUMNS, data.columns)
        assertTrue(data.done)
        assertEquals(Wire.formatIndex(3745), data.rows.single().first())
    }

    // --- the drain ----------------------------------------------------------

    @Test
    fun the_drain_lines_parse_whole_digest_and_both_row_shapes_included() {
        val drain = obj("drain")

        val state = Messages.parse(drain.s("state_line"))
        assertIs<ServerMessage.DrainState>(state)
        assertEquals(Wire.DRAIN_OFFERED, state.state)
        assertEquals(17L, state.block)
        assertEquals(Wire.DRAIN_KIND_RING, state.kind)
        assertFalse(state.final)
        assertEquals(328, state.rows)
        assertEquals("0a1b2c3d", Wire.digestHex(state.digest))
        assertEquals(461, state.blocksLeft)
        assertEquals(99, state.blocksFreed)
        assertEquals(32472, state.readingsCopied)
        assertEquals(6, state.segmentsCopied)
        assertEquals(32144, state.readingsFreed)

        val data = Messages.parse(drain.s("data_line"))
        assertIs<ServerMessage.DrainData>(data)
        assertEquals(17L, data.block)
        assertEquals(Wire.DRAIN_KIND_RING, data.kind)
        assertEquals(Wire.DRAIN_READING_COLUMNS, data.columns)
        assertTrue(data.done)
        assertEquals(2, data.rows.size)
        // A dated row: the session, the index, the instant, and empty clock cells.
        assertEquals(drain.s("session"), data.rows[0][0])
        assertEquals(drain.s("timestamp"), data.rows[0][2])
        assertEquals("", data.rows[0].last())
        // A clockless row: no instant, and the board's clock in the last two cells.
        assertTrue(Wire.isRelativeSessionName(data.rows[1][0]))
        assertEquals("", data.rows[1][2])
        assertEquals("7", data.rows[1][data.columns.indexOf(Wire.COL_REL_BOOT)])
        assertEquals("1235009", data.rows[1][data.columns.indexOf(Wire.COL_REL_MS)])

        val segments = Messages.parse(drain.s("segments_line"))
        assertIs<ServerMessage.DrainData>(segments)
        assertEquals(18L, segments.block)
        assertEquals(Wire.DRAIN_KIND_SEGMENTS, segments.kind)
        assertEquals(Wire.DRAIN_SEGMENT_COLUMNS, segments.columns)
        // Whole seconds where the segment message carries timestamps: the cell
        // under `start_s` must parse as an integer, not read as a date.
        val startCell = segments.rows.single()[segments.columns.indexOf("start_s")]
        assertEquals(drain["start_s"]!!.jsonPrimitive.content, startCell)
    }

    // --- the raw console and the time map ------------------------------------

    @Test
    fun the_raw_response_line_carries_the_exact_frames_sps30_py_built() {
        val raw = obj("raw")
        val msg = Messages.parse(raw.s("response_line"))
        assertIs<ServerMessage.RawResponse>(msg)
        assertEquals(raw.s("request_hex"), msg.request)
        assertEquals(raw.s("response_hex"), msg.response)
    }

    @Test
    fun the_time_map_parses_to_the_exact_equivalences_the_controller_sent() {
        val timeMap = obj("time_map")
        val msg = Messages.parse(timeMap.s("line"))
        assertIs<ServerMessage.TimeMap>(msg)
        assertEquals(timeMap.i("boot"), msg.boot)
        val anchors = timeMap["anchors"]!!.jsonArray.map { it.jsonObject }
        assertEquals(anchors.size, msg.anchors.size)
        anchors.zip(msg.anchors).forEach { (stated, parsed) ->
            assertEquals(stated.i("boot"), parsed.boot)
            assertEquals(stated["rel_ms"]!!.jsonPrimitive.content.toLong(), parsed.relMs)
            assertEquals(stated["epoch_ms"]!!.jsonPrimitive.content.toLong(), parsed.epochMs)
            assertEquals(stated.i("utc_offset_min"), parsed.utcOffsetMinutes)
        }
    }
}
