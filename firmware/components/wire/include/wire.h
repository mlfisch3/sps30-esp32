/**
 * @file wire.h
 * @brief The JSONL protocol the Android app already speaks, encoded and decoded.
 *
 * A port of the parts of the repository's `wire.py` that a controller needs.
 * `wire.py` is the single source of truth for message types, field names and the
 * CSV/session naming; this is a third implementation of it alongside the Python
 * and the Kotlin mirror in `apk/.../wire/`, and like the Kotlin it **follows**
 * rather than defines. When they disagree, `wire.py` wins.
 *
 * Pure C, by the same rule the rest of this firmware follows: **no ESP-IDF, no
 * sockets, no allocation, no clock**. Every entry point writes into a buffer the
 * caller owns and says how many bytes it used. That is the only reason the host
 * tests can compile this exact translation unit and cross-check what it emits
 * against what `wire.py` emits for the same inputs.
 *
 * The one dependency is `sps30_codec.h`, which is itself pure: the reading
 * encoder takes an ::sps30_measurement_t and names its fields from
 * ::SPS30_FIELD_NAMES, so the ten-value order is shared with the decoder by
 * construction instead of being retyped here and left to drift.
 *
 * ## What every encoder promises
 *
 * On success it returns the number of bytes written, not counting the
 * terminating NUL, and the buffer holds one complete JSONL frame ending in
 * `\n`. If the result would not fit it returns -1 and the buffer is left
 * holding an empty string - never a truncated line, because half a JSON object
 * followed by a newline is a frame the peer will try to parse.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "sps30_codec.h"

#ifdef __cplusplus
extern "C" {
#endif

/** `wire.DEFAULT_PORT`. The app's LAN sweep looks for a `hello` on this port. */
#define WIRE_TCP_PORT 8730

/**
 * `wire.MAX_CLIENTS_TCP`. Set on the Pi to what a constrained controller can
 * serve, so behaviour would not change when the controller moved. This is that
 * controller, and the number is unchanged.
 *
 * It sizes the socket-server's arrays, which is why it keeps the plain name.
 * What a *client* is told is ::wire_max_clients_for, because Bluetooth does not
 * serve eight.
 */
#define WIRE_MAX_CLIENTS 8

/** `wire.TRANSPORT_TCP` / `wire.TRANSPORT_BLE`: the value ::net_link_t.transport
 *  carries, and what `hello` and `clients` now name their figure against. */
#define WIRE_TRANSPORT_TCP "tcp"
#define WIRE_TRANSPORT_BLE "ble"

/** `wire.MAX_CLIENTS_TCP` / `wire.MAX_CLIENTS_BLE`. */
#define WIRE_MAX_CLIENTS_TCP WIRE_MAX_CLIENTS
/** One, and it must stay equal to ::BLE_NUS_MAX_LINKS. Bluetooth serves a single
 *  phone; telling one "1 of 8" promises a second device that can never connect. */
#define WIRE_MAX_CLIENTS_BLE 1

/**
 * @brief `wire.max_clients_for`: how many clients @p transport can really serve.
 *
 * @param transport ::WIRE_TRANSPORT_TCP, ::WIRE_TRANSPORT_BLE, or NULL. Anything
 *        it does not recognise gets the TCP figure, which is what every client
 *        was being told before this existed.
 */
unsigned wire_max_clients_for(const char *transport);

/** `wire.INDEX_WIDTH`: the zero-padded reading counter, e.g. 1 -> "0000001". */
#define WIRE_INDEX_WIDTH 7

/** Bytes for a formatted index, including the NUL. */
#define WIRE_INDEX_LEN (WIRE_INDEX_WIDTH + 1)

/** Bytes for "SPS30-YYYYmmdd_HHMMSS.csv", including the NUL. */
#define WIRE_CSV_NAME_LEN 32

/** Bytes for "YYYY-MM-DDTHH:MM:SS+hh:mm", including the NUL. */
#define WIRE_TIMESTAMP_LEN 32

/** `ClientHandler.name` is capped at 40 characters by the daemon. */
#define WIRE_NAME_LEN 41

/**
 * @brief The longest frame this controller emits, including `\n` and the NUL.
 *
 * The two candidates are `hello` (~460 bytes with a full profile and a long
 * state detail) and `clients` with eight 40-character names (~500). 640 leaves
 * room for both without making the per-client output buffer a memory question:
 * eight of them is 5 KiB.
 */
#define WIRE_LINE_MAX 640

/**
 * @brief The longest raw SHDLC frame the `raw` console may send or receive.
 *
 * `wire.py` puts no bound on the hex string, because the daemon hands it to a
 * serial port on a machine with a gigabyte of memory. Here the request has to
 * fit a static buffer and the answer has to fit ::WIRE_LINE_MAX alongside the
 * echoed request - 2 x 128 bytes is 512 hex characters, which leaves ample room
 * in a 640-byte line.
 *
 * It is a bound on the wire format, not a restriction on the sensor: the
 * longest frame an SPS30 can produce is the 40-byte float measurement, 45 bytes
 * framed and at most 85 with every payload byte escaped. 128 is half as much
 * again as the worst case the part is capable of.
 */
#define WIRE_RAW_MAX_BYTES 128

/* Server -> client message types, from `wire.py`. */
#define WIRE_MSG_HELLO "hello"
#define WIRE_MSG_READING "reading"
#define WIRE_MSG_STATE "state"
#define WIRE_MSG_ERROR "error"
#define WIRE_MSG_PROFILE "profile"
#define WIRE_MSG_CONTROL "control"
#define WIRE_MSG_CLIENTS "clients"
#define WIRE_MSG_POWER "power"
#define WIRE_MSG_FILES "file_list"
#define WIRE_MSG_FILE_DATA "file_data"
#define WIRE_MSG_RAW "raw_response"
#define WIRE_MSG_TIME_MAP "time_map"

/*
 * The transfer half.
 *
 * The daemon never needed these: a Raspberry Pi's session files are already on a
 * filesystem the user can reach, so "get the data off" is a file copy and "put
 * it back" is not a thing anyone asks for. On a controller whose entire archive
 * is a flash partition, both are protocol. They were added here first and
 * `wire.py` now names them too, which is the right way round - it is the
 * authority, and a vocabulary that lived only in this file was a vocabulary
 * three implementations could quietly disagree about.
 */
#define WIRE_MSG_IMPORT_RESULT "import_result"
#define WIRE_MSG_STORAGE "storage"
#define WIRE_MSG_ERASE_RESULT "erase_result"

/* The modelled power estimate: the reply to ::WIRE_CMD_GET_POWER_ESTIMATE.
 * Controller-only for a physical reason rather than a storage one - the model's
 * constants describe this board's rails and radios, so the Pi daemon has no
 * honest answer to give and returns its usual "unknown cmd". */
#define WIRE_MSG_POWER_ESTIMATE "power_estimate"

/*
 * The storage occupancy map: `wire.MSG_STORAGE_MAP`, one cell per flash page.
 *
 * The section ids are `wire.MAP_SECTION_*`. A client treats the list as open -
 * an id it does not know is still drawn, labelled by its id - so the tiered
 * sections can arrive one day without the protocol moving.
 */
#define WIRE_MSG_STORAGE_MAP "storage_map"
#define WIRE_MAP_SECTION_RING "ring"
#define WIRE_MAP_SECTION_ALERT_HEADERS "alert_headers"
#define WIRE_MAP_SECTION_ALERT_BODIES "alert_bodies"

/*
 * Preserved alert data: what the controller keeps when the ring wraps.
 *
 * `wire.MSG_ALERTS`, `wire.MSG_ALERT_SEGMENT`, `wire.MSG_ALERT_DATA`. A client
 * that models none of them is unharmed - it sees three frame types it does not
 * know and ignores them, which is exactly what happens today with an app built
 * before this existed.
 */
#define WIRE_MSG_ALERTS "alerts"
#define WIRE_MSG_ALERT_SEGMENT "alert_segment"
#define WIRE_MSG_ALERT_DATA "alert_data"

/*
 * Backing everything up and clearing the board, block by block.
 *
 * `wire.MSG_DRAIN_STATE` and `wire.MSG_DRAIN_DATA`. A separate path from
 * ::WIRE_CMD_ERASE and not a weakening of it: the rule that nothing is deleted
 * the asking device cannot prove it holds is the same rule, applied per block
 * rather than per session. See `store_drain.h` and BACKUP_AND_CLEAR.md.
 */
#define WIRE_MSG_DRAIN_STATE "drain_state"
#define WIRE_MSG_DRAIN_DATA "drain_data"

/* Client -> server commands, from `wire.py`. */
#define WIRE_CMD_PAUSE "pause"
#define WIRE_CMD_RESUME "resume"
#define WIRE_CMD_CLEAN "clean"
#define WIRE_CMD_RESTART "restart"
#define WIRE_CMD_RAW "raw"
#define WIRE_CMD_LIST_FILES "list_files"
#define WIRE_CMD_GET_FILE "get_file"
#define WIRE_CMD_SET_PROFILE "set_profile"
#define WIRE_CMD_SHUTDOWN "shutdown"
#define WIRE_CMD_IDENTIFY "identify"
#define WIRE_CMD_CLAIM "claim"
#define WIRE_CMD_RELEASE "release"
#define WIRE_CMD_BACKUP "backup"
#define WIRE_CMD_SET_TIME "set_time"
#define WIRE_CMD_PUT_FILE "put_file"
#define WIRE_CMD_ERASE "erase"
#define WIRE_CMD_STORAGE "storage"
#define WIRE_CMD_STORAGE_MAP "storage_map"
#define WIRE_CMD_GET_ALERTS "get_alerts"
#define WIRE_CMD_SET_ALERTS "set_alerts"
#define WIRE_CMD_DRAIN_BEGIN "drain_begin"
#define WIRE_CMD_DRAIN_NEXT "drain_next"
#define WIRE_CMD_DRAIN_CONFIRM "drain_confirm"
#define WIRE_CMD_DRAIN_CANCEL "drain_cancel"
#define WIRE_CMD_GET_POWER_ESTIMATE "get_power_estimate"

/** @brief `wire.DRAIN_KIND_*`: what a drained block is made of. */
#define WIRE_DRAIN_KIND_BODY "body"
#define WIRE_DRAIN_KIND_SEGMENTS "segments"
#define WIRE_DRAIN_KIND_RING "ring"

/** @brief `wire.DRAIN_STATES`. */
#define WIRE_DRAIN_STATE_IDLE "idle"
#define WIRE_DRAIN_STATE_OFFERED "offered"
#define WIRE_DRAIN_STATE_FREED "freed"
#define WIRE_DRAIN_STATE_KEPT "kept"
#define WIRE_DRAIN_STATE_REFUSED "refused"
#define WIRE_DRAIN_STATE_FINISHED "finished"
#define WIRE_DRAIN_STATE_ERROR "error"

/* --- relative time --------------------------------------------------------- */
/*
 * `wire.py`'s relative-time section, mirrored. A controller with no clock counts
 * on its own and says so; the client converts through equivalences the
 * controller appends. See `store.h` for the recording half and `wire.py` for the
 * argument.
 */

/** Reading and row fields carrying the board's own clock. `timestamp` is then "". */
#define WIRE_COL_REL_RUN "rel_boot"
#define WIRE_COL_REL_MS "rel_ms"

/** One equivalence, as `time_map` carries it. */
typedef struct {
    uint32_t run;           /**< which run of the board; a reboot changes it */
    uint32_t rel_ms;        /**< what the board's clock read */
    int64_t epoch_ms;       /**< what the world clock read at the same moment */
    int16_t utc_offset_min; /**< the zone the supplied time was in */
} wire_anchor_t;

/**
 * @brief How full the store gets before everything is exported to the phone.
 *
 * `HANDOFF.md` item 13. The point of the threshold is that data leaves the
 * controller *before* there is any pressure to delete it: a ring that is 85 %
 * full still has a day of headroom at 1 Hz, which is time enough for a phone to
 * come into range and take a copy. It is a trigger for copying, never for
 * removing - nothing in this firmware deletes a reading because the store is
 * getting full except the ring's own oldest-first reuse, which is what the
 * export exists to get ahead of.
 */
#define WIRE_STORE_WARN_FRACTION 0.85

/** `wire.CONTROL_TTL_SECONDS`: how long an unused control claim survives. */
#define WIRE_CONTROL_TTL_SECONDS 120.0

/**
 * @brief The `event` string on a reading taken while the measurement is
 *        disturbed.
 *
 * `wire.py` documents "startup"/"resume"/"restart"/"clean" and the clients treat
 * any non-blank value as "exclude this from the plot", which is the whole of the
 * contract. This controller says `settling` because ::acq_reading_t carries a
 * flag and not a cause: the acquisition layer knows the reading is disturbed but
 * not which of the four disturbed it, and inventing one of the daemon's four
 * words would be a guess dressed as a fact.
 */
#define WIRE_EVENT_SETTLING "settling"

/** `wire.MODE_CONTINUOUS` / `wire.MODE_DUTY_CYCLE`. */
#define WIRE_MODE_CONTINUOUS "continuous"
#define WIRE_MODE_DUTY_CYCLE "duty_cycle"

/** `wire.HELLO_SUPPORTS_SLEEP` / `wire.HELLO_CAN_POWER_OFF`: the capability
 *  flags `hello` carries, so a client offers only what this host can do.
 *  Named rather than spelled inline so `test_wire.c` can hold them against
 *  `wire.py`'s own strings. */
#define WIRE_HELLO_SUPPORTS_SLEEP "supports_sleep"
#define WIRE_HELLO_CAN_POWER_OFF "can_power_off"
#define WIRE_HELLO_HAS_CLOCK "has_clock"

/**
 * @brief `wire.POWER_CLEAN_DUE_HOURS`: hours of measurement before a clean.
 *
 * Carried in the `power` payload, both standalone and in `hello`'s embedded
 * copy of it. Zero means due now.
 */
#define WIRE_POWER_CLEAN_DUE_HOURS "clean_due_in_hours"

/** `wire.CLEAN_AFTER_SECONDS`: the full scale the field above counts down. */
#define WIRE_CLEAN_AFTER_SECONDS 604800.0

/**
 * @brief `wire.clean_due_in_hours`: @p run_seconds as hours still to go.
 *
 * Floored at zero and rounded to a tenth of an hour, which is the daemon's
 * arithmetic exactly - a client cannot be shown two different numbers for the
 * same sensor depending on which controller it is plugged into.
 */
double wire_clean_due_in_hours(double run_seconds);

/** The acquisition profile, mirroring `wire.DEFAULT_PROFILE`. */
typedef struct {
    const char *mode;
    double interval;
    double cycle_s;
    double warmup_s;
    double average_s;
} wire_profile_t;

/** `wire.DEFAULT_PROFILE`. */
#define WIRE_DEFAULT_PROFILE()                                                 \
    ((wire_profile_t){                                                         \
        .mode = WIRE_MODE_CONTINUOUS,                                          \
        .interval = 1.0,                                                       \
        .cycle_s = 900.0,                                                      \
        .warmup_s = 30.0,                                                      \
        .average_s = 30.0,                                                     \
    })

/** One row of the `clients` roster. */
typedef struct {
    int id;
    const char *name;
} wire_client_t;

/** Everything `hello` carries. NULL strings are written as empty or null. */
typedef struct {
    const char *product;
    const char *serial;
    const char *firmware;
    const char *state;
    const char *detail;
    const char *csv;   /**< NULL -> JSON null, as on a daemon with no session yet */
    uint32_t index;    /**< readings so far in the session; formatted zero-padded */
    double seconds;    /**< expected length of this phase; negative to omit */
    wire_profile_t profile;
    /** Host power. "usb" here: mains-powered, no fuel gauge to read. */
    const char *power_source;
    /** Measured seconds since the last fan clean; rendered as
     *  ::WIRE_POWER_CLEAN_DUE_HOURS. Seconds rather than the reported hours so
     *  a caller cannot put an unrounded figure on the wire. */
    double clean_run_seconds;
    bool supports_sleep;
    /** `wire.HELLO_CAN_POWER_OFF`: whether `shutdown` has a host to halt.
     *  False here - see the note on the field in `wire.py`. */
    bool can_power_off;
    /**
     * Whether this controller knows what time it is.
     *
     * False asks to be told - see ::WIRE_CMD_SET_TIME. Sent always rather than
     * only when false, because a client that has to *infer* the absence of a
     * field to decide whether to offer a clock will get it wrong against some
     * controller that omits it for another reason.
     */
    bool has_clock;
    int client_id;
    /** What @ref transport can serve; see ::wire_max_clients_for. */
    unsigned max_clients;
    /**
     * The link this `hello` is going out on, so the client knows which capacity
     * `max_clients` is. NULL omits the field, which is what an older controller
     * effectively did.
     */
    const char *transport;
} wire_hello_t;

/** Everything a `reading` carries beyond the ten values. */
typedef struct {
    const char *csv;       /**< session key; the app de-duplicates on (csv, index) */
    uint32_t index;        /**< 1-based within the session */
    const char *timestamp; /**< ISO 8601 with offset, from ::wire_iso_timestamp */
    const char *event;     /**< "" for a normal reading, else ::WIRE_EVENT_SETTLING */
    const sps30_measurement_t *values;
    /**
     * This reading's session was recorded with no clock.
     *
     * `timestamp` is then empty - deliberately, because a fabricated one reads
     * as an instant everywhere downstream - and `rel_run` and `rel_ms` carry
     * what the board actually knew.
     */
    bool relative;
    uint32_t rel_run;
    uint32_t rel_ms;
} wire_reading_t;

/* --- encoders ------------------------------------------------------------- */

/** @brief Encode `hello`. @return bytes written, or -1 if it would not fit. */
int wire_hello_line(char *out, size_t cap, const wire_hello_t *hello);

/** @brief Encode `reading`. @return bytes written, or -1 if it would not fit. */
int wire_reading_line(char *out, size_t cap, const wire_reading_t *reading);

/**
 * @brief Encode `state`.
 * @param seconds how long the phase is expected to last; negative omits the
 *        field, which is what the daemon does when it does not know.
 */
int wire_state_line(char *out, size_t cap, const char *state, const char *detail,
                    double seconds);

/**
 * @brief Encode `control`, saying who may issue mutating commands.
 * @param holder the holding client's id, or <= 0 for nobody (JSON null).
 */
int wire_control_line(char *out, size_t cap, int holder, const char *holder_name,
                      double expires_in);

/**
 * @brief Encode `clients`, the roster of everyone connected.
 * @param transport which link @p max_clients is the capacity of; NULL omits it.
 */
int wire_clients_line(char *out, size_t cap, const wire_client_t *clients, size_t count,
                      unsigned max_clients, const char *transport);

/**
 * @brief Encode `error`.
 * @param needs_control marks a refusal caused by someone else holding the lock,
 *        so the app offers to take control rather than showing a dead end.
 * @param at_capacity marks a refusal to accept an over-cap connection.
 * @param ref `wire.py`'s error `ref`: the `WIRE_CMD_*` this error answers, so a
 *        client can tell it apart from a failure of something else it has in
 *        flight. NULL or "" omits the field, and an omitted `ref` means
 *        "concerns everything" - which is how every error behaved before it
 *        existed, so a reader that does not know it is unaffected.
 */
int wire_error_line(char *out, size_t cap, const char *message, bool needs_control,
                    bool at_capacity, const char *ref);

/** @brief Encode `profile`. */
int wire_profile_line(char *out, size_t cap, const wire_profile_t *profile);

/**
 * @brief Encode `raw_response`, the answer to a `raw` console command.
 *
 * Both strings are lower-case hex of a complete SHDLC frame, delimiters and
 * byte-stuffing intact, exactly as the daemon's `_cmd_raw` sends them. The
 * request is echoed because the console is asynchronous: an answer that did not
 * say what it was answering could not be lined up with what was typed.
 */
int wire_raw_response_line(char *out, size_t cap, const char *request_hex,
                           const char *response_hex);

/**
 * @brief Lower-case hex of @p len bytes, NUL-terminated.
 *
 * @return the characters written excluding the NUL, or -1 if @p cap could not
 *         hold `2 * len + 1`.
 */
int wire_hex_encode(const uint8_t *bytes, size_t len, char *out, size_t cap);

/**
 * @brief Decode a hex string into bytes, ignoring spaces.
 *
 * Spaces are skipped because the raw console lets a human group a frame as
 * `7e 00 00 03 00 02 fd 7e`, and the daemon's `_raw_transaction` strips them
 * the same way before `bytes.fromhex`. Either case of hex digit is accepted.
 *
 * @return the number of bytes written, or -1 for a non-hex character, an odd
 *         number of digits, or more bytes than @p cap holds.
 */
int wire_hex_decode(const char *hex, uint8_t *out, size_t cap);

/**
 * @brief Encode `power`, carrying the same payload `hello` embeds.
 *
 * Sent again whenever ::WIRE_POWER_CLEAN_DUE_HOURS changes, and not otherwise.
 * A client that only ever read it from `hello` would show the figure it had at
 * connect time for as long as it stayed connected, which for a controller left
 * running is exactly the case the warning exists for.
 *
 * @param clean_run_seconds measured seconds since the last fan clean.
 */
int wire_power_line(char *out, size_t cap, const char *source, double clean_run_seconds);

/* --- the modelled power estimate ---------------------------------------- */

/** `wire.POWER_ESTIMATE_BASIS_MODELLED`: every figure is modelled, none
 *  measured. The only value `basis` has today, carried so the app's one
 *  honesty sentence is driven by the wire rather than hardcoded. */
#define WIRE_POWER_BASIS_MODELLED "modelled"

/** `wire.POWER_RUNTIME_*`: why the runtime slot is or is not a number.
 *  `hours` is present only under "ok", and there is no battery on any board
 *  today, so today every reply says `no_capacity`. */
#define WIRE_POWER_RUNTIME_OK "ok"
#define WIRE_POWER_RUNTIME_NO_CAPACITY "no_capacity"
#define WIRE_POWER_RUNTIME_UNUSABLE_CAPACITY "unusable_capacity"
#define WIRE_POWER_RUNTIME_NO_DRAW "no_draw"

/** `wire.POWER_SUBSYSTEMS`: the keys of `current.by_subsystem`, in emission
 *  order - which is `power_subsystem_t` order, and the order both mirrors
 *  accumulate in. */
#define WIRE_POWER_SUBSYSTEM_COUNT 4
extern const char *const WIRE_POWER_SUBSYSTEMS[WIRE_POWER_SUBSYSTEM_COUNT];

/** `wire.POWER_COMPARE_CYCLES`: the duty-cycle periods a comparison prices,
 *  smallest first - the same six the app's mode picker offers. */
#define WIRE_POWER_COMPARE_CYCLE_COUNT 6
extern const double WIRE_POWER_COMPARE_CYCLES[WIRE_POWER_COMPARE_CYCLE_COUNT];

/** Every alternative a reply prices: continuous plus the six cycles above. */
#define WIRE_POWER_ALTERNATIVE_COUNT (1 + WIRE_POWER_COMPARE_CYCLE_COUNT)

/**
 * @brief One priced alternative: `wire.power_alternative_payload`'s shape.
 *
 * ::cycle_s and ::sleeps are read only when ::mode is ::WIRE_MODE_DUTY_CYCLE,
 * because a continuous entry has neither a period nor a between-cycles state.
 * ::hours is the estimated remaining life under this mode and rides only under
 * a runtime status of "ok" - the slot exists so capacity information arrives
 * as a value in an existing place rather than as a new message.
 */
typedef struct {
    const char *mode; /**< ::WIRE_MODE_CONTINUOUS or ::WIRE_MODE_DUTY_CYCLE */
    double cycle_s;
    bool sleeps;
    double total_mw;
    bool has_hours;
    double hours;
} wire_power_alternative_t;

/**
 * @brief Everything a `power_estimate` reply carries.
 *
 * The runtime slot follows `wire.power_runtime_payload`'s rule exactly: a
 * status nobody recognises is written as `no_capacity`, and so is an "ok"
 * arriving without a usable number - the slot must never claim to know while
 * carrying nothing. ::runtime_modelled_hours and ::runtime_derating are
 * written only under "ok", so the conservatism can be seen rather than
 * trusted.
 */
typedef struct {
    wire_profile_t profile; /**< the state being priced, echoed normalized */
    bool sleeps;            /**< whether the sensor sleeps between duty cycles */
    double total_mw;
    double by_subsystem_mw[WIRE_POWER_SUBSYSTEM_COUNT];
    const wire_power_alternative_t *alternatives;
    size_t alternative_count;
    const char *runtime_status; /**< one of the WIRE_POWER_RUNTIME_* strings */
    bool runtime_has_hours;     /**< only honoured under "ok" */
    double runtime_hours;
    double runtime_modelled_hours;
    double runtime_derating;
    unsigned sources_datasheet; /**< `power.sources_summary()`'s three counts */
    unsigned sources_measured;
    unsigned sources_estimate;
} wire_power_estimate_t;

/**
 * @brief Encode a whole `power_estimate` reply.
 *
 * The byte-for-byte mirror of `power.estimate_message` passed through
 * `wire.encode`; the generated vectors hold the two together. The composition
 * itself - which alternatives, whose sleeps, which runtime status - lives in
 * `components/power`'s `power_report.c`, beside the model it reads.
 */
int wire_power_estimate_line(char *out, size_t cap, const wire_power_estimate_t *e);

/**
 * @brief A writer over the caller's buffer that gives up instead of truncating.
 *
 * Once @c ok goes false every further append is a no-op, so the overflow check
 * happens once at the end rather than at forty call sites. A half-written JSON
 * object is worse than none: it would still end in the `\n` the peer frames on,
 * and would be parsed as a corrupt message rather than ignored.
 *
 * Public only because `file_data` is assembled a row at a time and its caller
 * has to hold one between calls. Treat the fields as private.
 */
typedef struct {
    char *buf;
    size_t cap;
    size_t len;
    bool ok;
} wire_writer_t;

/** @brief One entry in a `file_list`, mirroring the daemon's `scan_files`. */
typedef struct {
    const char *name;  /**< "SPS30-YYYYmmdd_HHMMSS.csv", the session key */
    uint32_t rows;     /**< readings the controller still holds for it */
    uint32_t bytes;    /**< what it would be as CSV, which is what the app shows */
    const char *start; /**< ISO 8601 timestamp of its first reading; "" if relative */
    /** Recorded with no clock: its rows carry the board's own clock, not instants. */
    bool relative;
} wire_file_entry_t;

/**
 * @brief Where one `file_list` frame sits in a listing too long for one frame.
 *
 * Mirrors `wire.file_list_page`. A frame is 8 KiB here and a session costs about
 * 111 bytes of it, so a board that holds more sessions than that fits cannot name
 * them all at once - and a name is the only way a session can be fetched, plotted
 * or offered to the erase gate. Raising the number is the mistake the session
 * table already made twice; saying where the rest are is the fix.
 *
 * @c more is the whole end condition. A client must not derive it from
 * `offset + count`, because entries are skipped on the way out (an empty session,
 * a name that will not encode) and a page can be shorter than the run of the
 * listing it consumed. @c next must always be greater than @c offset.
 */
typedef struct {
    uint32_t offset; /**< newest-first index of `files[0]` in the whole listing */
    uint32_t total;  /**< sessions held, however many frames naming them takes */
    bool more;       /**< true when @c next names where the rest begin */
    uint32_t next;   /**< the `offset` to ask for to continue */
} wire_file_page_t;

/**
 * @brief Encode `file_list`.
 *
 * Empty is not an error: a controller with nothing stored says so, and the app
 * shows "no stored sessions" rather than a fault. It is now genuinely possible
 * for the list to be non-empty - see `store.h`.
 *
 * @param page where this frame sits in the listing, or NULL for a controller
 *        that fits the whole of it in one frame - offset 0, @p count sessions
 *        held and nothing following.
 */
int wire_file_list_line(char *out, size_t cap, const wire_file_entry_t *files,
                        size_t count, const wire_file_page_t *page);

/**
 * @brief A `file_data` message being built a row at a time.
 *
 * `get_file` answers with the session's readings as CSV-shaped rows, and a
 * session is far larger than any one frame, so it is sent as a run of messages
 * that the app folds together. Building one row at a time - rather than
 * formatting a batch and then finding it does not fit - is what lets the
 * encoder fill each frame to just under its buffer without ever emitting a
 * partial row.
 */
typedef struct {
    wire_writer_t w;
    uint32_t rows;
    /** The session is relative, so every row carries two extra cells. */
    bool relative;
} wire_file_data_t;

/**
 * @brief Start a `file_data` frame for @p name.
 * @param relative the session was recorded with no clock, so the column list
 *        gains ::WIRE_COL_REL_RUN and ::WIRE_COL_REL_MS and `timestamp` is
 *        empty on every row.
 * @return 0, or -1 if it will not fit.
 */
int wire_file_data_open(wire_file_data_t *fd, char *out, size_t cap, const char *name,
                        bool relative);

/** @brief Whether another row would fit. Check before reading one from the store. */
bool wire_file_data_room(const wire_file_data_t *fd);

/**
 * @brief Append one row. False if it did not fit; the frame is still valid.
 * @param rel_run,rel_ms the board's own clock; written only on a relative frame.
 */
bool wire_file_data_row(wire_file_data_t *fd, uint32_t index, const char *timestamp,
                        const sps30_measurement_t *values, const char *event,
                        uint32_t rel_run, uint32_t rel_ms);

/**
 * @brief Close the frame. @p done marks the last one of a transfer.
 * @return the length written, or -1 on overflow.
 */
int wire_file_data_close(wire_file_data_t *fd, bool done);

/* --- formatting ----------------------------------------------------------- */

/** @brief `wire.format_index`: zero-pad to ::WIRE_INDEX_WIDTH. */
void wire_format_index(uint32_t index, char *out, size_t cap);

/**
 * @brief `printf("%.*f", decimals, (double)value)`, in integer arithmetic.
 *
 * Byte-for-byte what `snprintf` writes, for every one of the 4,294,967,296
 * float32 values, at two and three decimals - checked exhaustively by
 * `slow_wire_format` in the host pack, which is why it is a slow suite.
 *
 * It exists because a backfilled row costs **ten** of these, and on the ESP32
 * every one of them widens a float32 to a double and hands it to a software
 * floating-point `printf`. On x86, where doubles are hardware, the ten were
 * already 59% of the work of encoding a row.
 *
 * The arithmetic is exact rather than approximate, which is what makes it a
 * formatting change and not a protocol change: a float32 is `m * 2^e` with
 * `m < 2^24`, so `value * 10^decimals` is either an exact 64-bit integer or an
 * exact 64-bit quotient with a remainder, and the remainder decides the rounding
 * outright. Ties go to even, as `printf` under the default rounding mode does.
 *
 * @param decimals 0 to 3.
 * @return false when the value cannot be done exactly here - `inf`, `nan`, or a
 *         magnitude past 2^44, which is eight orders of magnitude above anything
 *         this sensor reports. The caller must then use `snprintf`, and that
 *         fallback is why the bytes on the wire cannot change.
 */
bool wire_format_fixed(float value, unsigned decimals, char *out, size_t cap);

/**
 * @brief `wire.iso_timestamp`: ISO 8601 with a `+hh:mm` offset.
 *
 * Pure: the caller supplies both the instant and the zone's offset at that
 * instant, so no libc time zone machinery is involved and the result is
 * reproducible on the host. The colon in the offset is not decoration - the
 * app parses with `OffsetDateTime.parse`, which is ISO_OFFSET_DATE_TIME and
 * rejects the `+hhmm` form that `strftime("%z")` produces.
 *
 * @param epoch_s seconds since the Unix epoch, UTC.
 * @param utc_offset_s the local zone's offset at that instant, e.g. -14400.
 * @return bytes written, or -1 if @p cap is too small.
 */
int wire_iso_timestamp(int64_t epoch_s, int utc_offset_s, char *out, size_t cap);

/**
 * @brief `wire.csv_filename`: "SPS30-YYYYmmdd_HHMMSS.csv" in local time.
 *
 * The controller writes no CSV, but the name is the protocol's **session key**:
 * the app de-duplicates on `(csv, index)` and resets its watermark when the name
 * changes, so per-session indices that restart at 1 do not look already-seen.
 * Naming sessions the way the daemon names its files is what lets the app treat
 * both controllers identically.
 */
int wire_csv_name(int64_t epoch_s, int utc_offset_s, char *out, size_t cap);

/**
 * @brief `wire.rel_session_name`: the session key when there was no clock.
 *
 * "SPS30-Rxxxxxxxx-xxxxxxxx.csv" - the run and the position in it, in hex. Like
 * the dated form it is a pure function of the page header, which is what lets a
 * session be given the same name again after a reboot; unlike the dated form it
 * claims nothing about when it happened, because nothing knew.
 */
int wire_rel_csv_name(uint32_t run, uint32_t start_ms, char *out, size_t cap);

/**
 * @brief ::wire_rel_csv_name read back. @p run and @p start_ms may be NULL.
 * @return false if @p name is not a relative session key.
 */
bool wire_parse_rel_csv_name(const char *name, uint32_t *run, uint32_t *start_ms);

/**
 * @brief Encode `time_map`: every equivalence the controller holds.
 *
 * The client's half of relative time. The controller does no conversion on the
 * client's behalf - it sends the raw equivalences and the client picks the one
 * nearest whatever it is placing, which is what bounds the error by the crystal
 * drift between two of them rather than by the whole run.
 *
 * @param run this board's current run marker, so a client can tell live readings
 *        from ones recorded before the last reboot.
 */
int wire_time_map_line(char *out, size_t cap, uint32_t run, const wire_anchor_t *anchors,
                       size_t count);

/* --- decoding ------------------------------------------------------------- */

/**
 * @brief Read a top-level string field out of one JSON object line.
 *
 * Deliberately not a JSON parser. The commands this controller accepts are flat
 * objects with a handful of short string fields, and the alternative - pulling
 * in a parser and an allocator to read `{"cmd":"pause"}` - costs far more than
 * it is worth. Nested objects and arrays are skipped over rather than
 * descended, so a key inside `{"profile":{...}}` is not mistaken for a
 * top-level one.
 *
 * Unescapes `\"`, `\\`, `\/`, `\b`, `\f`, `\n`, `\r`, `\t` and `\uXXXX` (as
 * UTF-8, with surrogate pairs). A value longer than @p cap is truncated at a
 * character boundary rather than rejected: these feed a display name, and a
 * clipped name beats a dropped command.
 *
 * @return true if the key was found, in which case @p out holds its value.
 */
bool wire_json_string(const char *line, size_t len, const char *key, char *out, size_t cap);

/**
 * @brief Read a top-level integer field out of one JSON object line.
 *
 * The same deliberately-not-a-parser as ::wire_json_string, for the one numeric
 * field this controller reads: `get_file`'s `since_index`. A quoted number is
 * accepted as well as a bare one; anything that is not an integer is refused
 * rather than coerced, because a `since_index` that silently became zero would
 * resend a whole session.
 *
 * @return true if the key was found and held an integer.
 */
bool wire_json_int(const char *line, size_t len, const char *key, int64_t *out);

/**
 * @brief Read a top-level fractional number out of one JSON object line.
 *
 * The same deliberately-not-a-parser as ::wire_json_string, for the acquisition
 * profile, whose five fields are seconds and are all allowed to be fractional.
 * ::wire_json_int is no use for them: `{"interval":0.5}` is a perfectly good
 * profile and reading it as an integer would refuse it, while reading `1.0` as
 * `1` would work by luck until the first non-integral value arrived.
 *
 * A quoted number is accepted, as ::wire_json_int accepts one. `NaN` and
 * infinity are refused: they are not durations, and one reaching the state
 * machine would poison every comparison it took part in.
 *
 * @return true if the key was found and held a finite number.
 */
bool wire_json_double(const char *line, size_t len, const char *key, double *out);

/**
 * @brief Read a top-level boolean field out of one JSON object line.
 *
 * The same deliberately-not-a-parser as ::wire_json_string. It exists because
 * `put_file`'s `done` and `erase`'s `override` are booleans, and a controller
 * that could only read integers and strings read `true` as *neither* - so a
 * transfer's last frame never closed the import and the controller never
 * answered. Found on hardware, with a session that transferred perfectly and
 * then hung.
 *
 * Bare `true`/`false` only. A quoted `"true"` is **not** accepted: these fields
 * decide whether a transfer is finished and whether an erase skips its safety
 * check, and coercing a string into either is exactly the kind of helpfulness
 * that should not be applied to them.
 *
 * @return true if the key was found and held a boolean.
 */
bool wire_json_bool(const char *line, size_t len, const char *key, bool *out);

/**
 * @brief Borrow the raw bytes of a top-level object-valued field.
 *
 * `set_profile` carries its settings one level down, in `{"profile":{...}}`, and
 * the accessors above deliberately refuse to descend. This hands back the nested
 * object's span - from its `{` to its `}` inclusive - so those same accessors
 * can be run against it, which is the whole of what descending one level costs.
 *
 * @p out points into @p line and is valid only for as long as @p line is; it is
 * not NUL-terminated, which is why @p out_len comes with it.
 *
 * @return true if the key was found and its value is an object.
 */
bool wire_json_object(const char *line, size_t len, const char *key, const char **out,
                      size_t *out_len);

/**
 * @brief Length of the first complete JSONL frame in @p buf, including the `\n`.
 * @return 0 if no complete line is buffered yet.
 */
size_t wire_line_length(const char *buf, size_t len);

/**
 * @brief ::wire_iso_timestamp read back: the instant, and the zone it was in.
 *
 * Exact for anything this protocol emits. The offset is returned separately
 * rather than folded in because it is half of a session's identity: the CSV file
 * name is local time, so reconstructing the name a session had needs the zone it
 * was recorded in as well as the instant.
 *
 * @return false unless @p text is exactly `YYYY-MM-DDTHH:MM:SS±hh:mm`. Nothing
 *         looser is accepted: an import that guessed at a timestamp it could not
 *         read would file readings under the wrong session.
 */
bool wire_parse_iso(const char *text, int64_t *epoch_s, int *utc_offset_s);

/**
 * @brief ::wire_csv_name read back, as **local** wall-clock seconds.
 *
 * The name carries no zone, so what comes back is the reading on the clock that
 * wrote it, expressed as seconds since the epoch *as if that clock were UTC*.
 * Subtracting the session's real offset turns it into the instant, and that
 * instant fed back through ::wire_csv_name reproduces this name exactly - which
 * is what lets an imported session be filed under the name it already had
 * without a lookup table that a reboot would lose.
 *
 * @return false if @p name is not `SPS30-YYYYmmdd_HHMMSS.csv`.
 */
bool wire_parse_csv_name(const char *name, int64_t *local_s);

/** @brief One CSV row of a session, decoded. */
typedef struct {
    uint32_t index;
    int64_t epoch_s;
    int utc_offset_s;
    sps30_measurement_t values;
    /** True when the `event` column was not empty. See ::WIRE_EVENT_SETTLING. */
    bool settling;
} wire_row_t;

/**
 * @brief Decode one row in ::WIRE_CSV_COLUMNS order.
 *
 * `index,timestamp,pm1_0,...,typical_size,event` - the daemon's own column
 * order, which is what an exported file holds and therefore what an imported one
 * must be reduced to before it gets here. Position, not header lookup: the
 * sender has already mapped whatever the file's header said onto this order, and
 * a controller re-deriving that per chunk would be doing the work twice.
 *
 * Strict on the two fields that decide where a reading is filed - a row without
 * a positive index and a readable timestamp is refused - and forgiving about the
 * rest: a missing or unparseable value reads as zero, the way a short row from
 * an interrupted transfer already does everywhere else in this project.
 *
 * @param len bytes of @p row, which need not be NUL-terminated.
 * @return false if the row cannot be placed. Such a row is counted and dropped,
 *         never stored under a guessed index.
 */
bool wire_parse_row(const char *row, size_t len, wire_row_t *out);

/* --- the transfer messages ------------------------------------------------- */

/** @brief What an import did, in the terms the user asked the question in. */
typedef struct {
    const char *name;
    uint32_t added;     /**< readings the controller did not have */
    uint32_t duplicate; /**< already held; the held copy was kept */
    uint32_t rejected;  /**< rows that could not be placed */
    uint32_t sessions;  /**< sessions this transfer touched */
    /**
     * `ok`, `full` (the ring had no room), `busy` (another device held the
     * import claim and none of this was taken - sending it again is the cure)
     * or `error`.
     */
    const char *state;
    const char *message;
} wire_import_result_t;

/** @brief Encode `import_result`. */
int wire_import_result_line(char *out, size_t cap, const wire_import_result_t *result);

/** @brief How much of the ring is spoken for. */
typedef struct {
    uint32_t used_pages;
    uint32_t total_pages;
    uint32_t readings;
    uint32_t sessions;
    double fraction;
    /** At or past ::WIRE_STORE_WARN_FRACTION: the phone should take a copy now. */
    bool warn;
    bool ready;
} wire_storage_t;

/** @brief Encode `storage`. */
int wire_storage_line(char *out, size_t cap, const wire_storage_t *storage);

/**
 * @brief One region of the store partition, as the occupancy map draws it.
 *
 * `cells` is the per-page states packed two bits a page, four pages a byte -
 * `store_map.h` owns the packing and the state values, and the wire vectors
 * hold both to `wire.pack_page_states`. This layer treats the bytes as opaque
 * and base64s them into the frame.
 */
typedef struct {
    const char *id;       /**< a `WIRE_MAP_SECTION_*`, or a future one */
    uint32_t start;       /**< first page, in whole-partition page numbers */
    uint32_t pages;       /**< pages in this section */
    uint32_t head;        /**< page the next write goes into, within the section */
    uint32_t wraps;       /**< completed passes of this section's write position */
    const uint8_t *cells; /**< (pages + 3) / 4 packed bytes */
} wire_map_section_t;

/** @brief The whole partition, section by section, in address order. */
typedef struct {
    uint32_t page_bytes;
    uint32_t pages; /**< pages in the whole partition */
    const wire_map_section_t *sections;
    size_t section_count;
} wire_storage_map_t;

/**
 * @brief Encode `storage_map`.
 *
 * At the real geometry the line runs about 1.9 KiB - past ::WIRE_LINE_MAX,
 * inside the 8 KiB big buffer `file_list` already borrows. Like every encoder
 * here it returns -1 rather than a truncated line.
 */
int wire_storage_map_line(char *out, size_t cap, const wire_storage_map_t *map);

/** @brief What an erase did, or why it would not. */
typedef struct {
    /** `done`, or `refused` when the backup could not be confirmed. */
    const char *state;
    const char *message;
    uint32_t sessions_confirmed;
    uint32_t sessions_refused;
    uint32_t pages_erased;
    uint32_t readings_erased;
    /** True when the erase went ahead on the override rather than on proof. */
    bool overridden;
} wire_erase_result_t;

/** @brief Encode `erase_result`. */
int wire_erase_result_line(char *out, size_t cap, const wire_erase_result_t *result);

/* --- preserved alert data -------------------------------------------------- */

/**
 * @brief What the controller is keeping, and what it is doing about it.
 *
 * Sent unasked on connection and whenever it changes, for the same reason
 * ::wire_storage_t is: the interesting news here is that alert data is waiting,
 * and a client that has to ask will not be asking on the day it matters.
 */
typedef struct {
    bool ready;
    uint32_t segments;   /**< preserved segments held */
    double pressure;     /**< how full the sample region is, 0 to 1 */
    /**
     * The recording interval the controller is insisting on, in seconds.
     *
     * Zero normally. Non-zero **only** under pressure on the preservation area -
     * a full ring never produces one - and the client shows it because a board
     * that has quietly moved to one sample a minute must not look like a board
     * that is recording every second.
     */
    double rate_floor_s;
    uint32_t wraps;      /**< completed passes of the ring's write head */
    /** The line per field, indexed as `wire.FIELDS`. Zero means not watched. */
    const float *thresholds;
    size_t threshold_count;
} wire_alerts_t;

/** @brief Encode `alerts`. */
int wire_alerts_line(char *out, size_t cap, const wire_alerts_t *alerts);

/**
 * @brief One preserved segment's header: `wire.ALERT_SEGMENT_KEYS`.
 *
 * Every figure the captain asked for, and it is a message of its own rather than
 * a preamble to the samples because it has to survive them: a segment whose
 * samples the area has since reclaimed still sends this, with ::has_body false.
 */
typedef struct {
    uint32_t seq;
    const char *session;   /**< the session's CSV name */
    const char *field;     /**< which of `wire.FIELDS` crossed the line */
    double threshold;
    const char *start;     /**< ISO timestamp, ::wire_iso_timestamp */
    const char *stop;
    double first;
    double last;
    double midpoint;
    double mean;
    double variance;
    double minimum;
    double maximum;
    uint32_t points;
    uint32_t first_index;
    uint32_t last_index;
    uint32_t wrap;
    bool has_body;
} wire_alert_segment_t;

/** @brief Encode `alert_segment`. */
int wire_alert_segment_line(char *out, size_t cap, const wire_alert_segment_t *segment);

/**
 * @brief A chunked `alert_data` line: one segment's samples.
 *
 * Deliberately the same `columns`/`rows` shape as `file_data`, down to every
 * cell being a string, so a client folds preserved rows in through the code that
 * already folds a backfill in - and therefore through the same timestamp
 * handling, which is what "timestamped like everything else" has to mean in
 * practice rather than as an intention.
 */
typedef struct {
    wire_writer_t w;
    uint32_t rows;
} wire_alert_data_t;

/** @brief Begin an `alert_data` line for segment @p seq of session @p name. */
int wire_alert_data_open(wire_alert_data_t *ad, char *out, size_t cap, uint32_t seq,
                         const char *name);
/** @brief Whether one more row fits. */
bool wire_alert_data_room(const wire_alert_data_t *ad);
/** @brief Append one row. @return false if it did not fit. */
bool wire_alert_data_row(wire_alert_data_t *ad, uint32_t index, const char *timestamp,
                         const sps30_measurement_t *values, const char *event);
/** @brief Close the line. @return its length, or -1. */
int wire_alert_data_close(wire_alert_data_t *ad, bool done);

/* --- backing everything up and clearing the board -------------------------- */

/**
 * @brief Where a drain has got to: `wire.MSG_DRAIN_STATE`.
 *
 * The only thing the controller ever volunteers about a drain. Everything else
 * is the device asking, which is what makes an interruption safe at any point:
 * a controller that is never mid-decision has nothing to roll back.
 */
typedef struct {
    /** `wire.DRAIN_STATES`. */
    const char *state;
    const char *message;
    /** The block this is about, or 0 when it is about the drain as a whole. */
    uint32_t block;
    /** `wire.DRAIN_KIND_*`, or "" when ::block is 0. */
    const char *kind;
    /** The block holds the write head: it is copied, proved and then kept. */
    bool final;
    /** Rows the controller sent for ::block, and their digest. */
    uint32_t rows;
    uint32_t digest;
    /** Blocks that still hold data, counted from flash. Includes the last one. */
    uint32_t blocks_left;
    uint32_t blocks_freed;
    uint32_t blocks_kept;
    uint32_t blocks_refused;
    uint32_t readings_copied;
    uint32_t segments_copied;
    uint32_t readings_freed;
} wire_drain_state_t;

/** @brief Encode `drain_state`. */
int wire_drain_state_line(char *out, size_t cap, const wire_drain_state_t *state);

/**
 * @brief A chunked `drain_data` line: one block's rows.
 *
 * Two column lists, chosen by the block's kind - `wire.DRAIN_READING_COLUMNS`
 * and `wire.DRAIN_SEGMENT_COLUMNS`. Every cell is a string, as in `file_data`
 * and `alert_data`, so a client folds these rows in through code it already has.
 */
typedef struct {
    wire_writer_t w;
    uint32_t rows;
    bool segments;
} wire_drain_data_t;

/** @brief Begin a `drain_data` line for @p block. @p kind is `WIRE_DRAIN_KIND_*`. */
int wire_drain_data_open(wire_drain_data_t *dd, char *out, size_t cap, uint32_t block,
                         const char *kind);
/** @brief Whether one more row fits. */
bool wire_drain_data_room(const wire_drain_data_t *dd);
/**
 * @brief Append one reading row.
 *
 * @param session the session the reading belongs to, which a block does not
 *        imply: a sector of the ring can hold pages of several.
 * @param timestamp the instant, or "" for a reading with no clock.
 * @param rel_run,rel_ms the board's own clock, or 0 and 0 for a dated reading,
 *        in which case both cells are written empty.
 */
bool wire_drain_data_row(wire_drain_data_t *dd, const char *session, uint32_t index,
                         const char *timestamp, const sps30_measurement_t *values,
                         const char *event, bool relative, uint32_t rel_run, uint32_t rel_ms);
/**
 * @brief Append one preserved-segment summary row.
 *
 * The float cells carry the shortest text that reads back as the same number,
 * not two decimals: the digest is taken over the values themselves rather than
 * over a quantised copy, so a cell that lost a digit would fail a good copy. A
 * value that is not finite is written as an empty cell.
 */
bool wire_drain_data_segment(wire_drain_data_t *dd, const wire_alert_segment_t *segment,
                             int64_t start_s, int64_t stop_s);
/** @brief Close the line. @return its length, or -1. */
int wire_drain_data_close(wire_drain_data_t *dd, bool done);

#ifdef __cplusplus
}
#endif
