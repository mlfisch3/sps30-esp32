/**
 * @file net_server.c
 * @brief JSONL over TCP, the protocol the app already speaks. See `net_server.h`.
 *
 * ## Shape
 *
 * One accept task, one task per connected client, and the acquisition task
 * touching neither. The acquisition task's entire contribution is
 * ::on_reading: take each client's mutex in turn, copy 56 bytes into its ring,
 * release. No socket call, no serialisation, no allocation, no wait that a
 * client can lengthen.
 *
 * A client task owns everything about its connection and does one thing at a
 * time. It never holds a lock while sending. It never reads a new command while
 * a line is still going out, which is what makes a single 640-byte output buffer
 * enough for a protocol that has to answer commands as well as stream: there is
 * never a second thing waiting to be written.
 *
 * ## What is coalesced and what is queued
 *
 * Readings are **queued**, because each one is a distinct fact and losing the
 * middle of a series leaves a hole. `state`, `control` and `clients` are
 * **coalesced**: each is a snapshot of something with exactly one current value,
 * so a client that has fallen behind wants the current one, not the four it
 * missed. Each is a version counter the client compares against what it has
 * been told, which cannot overflow a queue and cannot deliver a stale answer.
 *
 * The Pi solved the same problem by tagging every queued message lossy or
 * lossless. This has no flag to forget to set because readings and snapshots
 * travel by different mechanisms - the structural version of the same
 * invariant, as `acq_queue.h` argues for the archive path.
 *
 * ## Where the socket went
 *
 * A client is a ::net_link_t, not a file descriptor. Everything from `hello`
 * down is written against four operations - recv, send, wait, close - so the
 * same code serves a phone on Wi-Fi and a phone on Bluetooth, and a command
 * added to ::dispatch is answered on both without being written twice. The
 * socket implementation of those four is at the bottom of this file, next to the
 * accept task that creates them; the Bluetooth one is in `components/ble`.
 */

#include "net_server.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "acq.h"
#include "acq_queue.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "lwip/sockets.h"
#include "net_budget.h"
#include "net_import.h"
#include "net_link.h"
#include "net_time.h"
#include "net_wifi.h"
#include "power_report.h"
#include "store.h"
#include "wire.h"

static const char *TAG = "net";

/**
 * How long a client task sleeps in ::net_link_ops_t::wait before looking
 * around again, whether or not the link had anything to say.
 *
 * This is the *idle* poll, and only that. It is what bounds how long a client
 * can sit on a reading that arrived while it was asleep; it is deliberately not
 * what paces a bulk transfer, which is held up by room in the socket instead.
 * See `client_task` for what it cost when it was both.
 */
#define LINK_WAIT_MS 100

/**
 * The longest a client task may run without ever blocking, before it is made to.
 *
 * ::LINK_WAIT_MS stopped being a sleep for a client that is transferring, and
 * that was the point of the change - see `client_task`. But `tcp_wait` is
 * `select()` with the socket in `writefds`, and a socket with room returns from
 * it immediately, so for the whole of a `get_file` over TCP - twelve minutes for
 * a day of recording, half an hour for a full ring - the task became runnable
 * and never yielded.
 *
 * Acquisition is priority 5 and preempts this at 4, so readings are safe. What
 * is not is the **FreeRTOS idle task at priority 0**, which a runnable priority-4
 * task starves on whichever core it lands on. The generated `sdkconfig` for this
 * project, read rather than assumed:
 *
 *     CONFIG_ESP_TASK_WDT_INIT=y                 the watchdog is armed at boot
 *     CONFIG_ESP_TASK_WDT_TIMEOUT_S=5            and gives a task five seconds
 *     CONFIG_ESP_TASK_WDT_CHECK_IDLE_TASK_CPU0=y both idle tasks are subscribed
 *     CONFIG_ESP_TASK_WDT_CHECK_IDLE_TASK_CPU1=y
 *     CONFIG_FREERTOS_UNICORE is not set         two cores
 *     CONFIG_FREERTOS_HZ=100                     a tick is 10 ms
 *
 * So one saturating client leaves the other core's idle alive and only slows
 * things down. Two concurrent transfers reboot the board mid-transfer, which
 * reads as anything but what it is.
 *
 * The task therefore blocks for one tick if it has been running this long
 * without having done so: 10 ms in every 100 ms, at most a tenth of the
 * throughput the pacing fix bought back, and a watchdog budget it now meets
 * fifty times over. Bluetooth never had the exposure - `ble_wait` blocks on a
 * semaphore whatever it is asked - but the yield is on the shared loop because
 * a transport that can return from `wait` without blocking is the hazard, not
 * TCP as such.
 */
#define CLIENT_YIELD_EVERY_MS 100

/**
 * How long a client may fail to accept a single byte before it is disconnected.
 *
 * `acq_queue.h` makes the argument: a consumer that has not taken a byte in half
 * a minute is not busy, it is gone, and the cure is to notice rather than to
 * hold its data longer.
 *
 * **30 s is chosen against ::ACQ_CLIENT_QUEUE_MAX, not independently.** That
 * queue is 32 readings, which at the 1 Hz the SPS30 publishes is 32 seconds of
 * backlog, so a completely stopped client is disconnected two seconds *before*
 * its queue would begin dropping. Observed on hardware: two clients that
 * connected and never read were closed at 30 s with `dropped 0`. Ordering it the
 * other way round would mean quietly discarding a client's data for a while and
 * then dropping the client anyway.
 *
 * This is the *stopped* case. A client that is slow but not stopped keeps
 * resetting this clock while falling behind, and that one does overflow the
 * queue - which is what drop-oldest is for.
 */
#define CLIENT_STALL_MS 30000

/**
 * The per-client inbound buffer.
 *
 * 384 bytes held the longest command in the vocabulary - a raw frame as hex -
 * with room to spare. `put_file` is longer than all of them put together: it
 * carries a chunk of a session's rows, and a frame holding one row would spend
 * more bytes on JSON than on readings, exactly as ::CLIENT_OUT_MAX argues for
 * the other direction.
 *
 * 2 KiB, matching the output buffer, so a chunk in and a chunk out are the same
 * size and about seventeen rows each. Eight clients is 16 KiB of static DRAM
 * against 3 KiB before. A line that overruns it is still dropped with a
 * complaint rather than being silently re-framed mid-command.
 */
#define CLIENT_RX_MAX 2048

/**
 * Where this host's power comes from: mains, through USB.
 *
 * There is no fuel gauge and no host operating system to interrogate, so this
 * says that and claims nothing else. Named because two messages carry it -
 * `hello` embeds the `power` payload and `power` sends it again - and a board
 * that described itself two ways would be describing one of them wrongly.
 */
#define POWER_SOURCE "usb"

/**
 * The per-client output buffer.
 *
 * Every live message fits in ::WIRE_LINE_MAX. `file_data` does not: it carries
 * whole rows of a stored session and a frame holding one row at a time would
 * spend more bytes on JSON scaffolding than on readings, and would take a round
 * of the client loop per reading to send two days of them.
 *
 * 2 KiB holds about thirteen rows. Eight clients is 16 KiB of static DRAM
 * against 5 KiB before - paid once, visible here, and the alternative was a
 * second buffer allocated per transfer on a heap that a connection must never
 * fail on.
 *
 * One message does not fit it: `file_list` has to name every session the board
 * holds, and ::FILE_LIST_MAX of them measure up to 7.1 KB. That one **borrows**
 * a larger buffer for the length of the send - see ::CLIENT_OUT_BIG - which is
 * what ::CLIENT_SLOT_BUDGET's note asks for and what keeps a phone watching live
 * readings from carrying 2 KiB it uses once a connection.
 */
#define CLIENT_OUT_MAX 2048

/**
 * The buffer one oversized message is sent from, when 2 KiB will not do.
 *
 * Measured against the real encoder rather than estimated: an entry comes to
 * **111 bytes** at the worst this firmware can produce - a name in the dated
 * form, an ISO start present, and the row and byte counts at their 32-bit
 * maximum. ::FILE_LIST_MAX of those is 7,104 bytes, and 8 KiB is that with about
 * a kilobyte to spare.
 *
 * ::fill_file_list drops entries rather than losing the message if it is ever
 * not enough, and says in the log that it did - a listing that silently stopped
 * short is the failure `store_sessions.h` is about, arriving by another road.
 *
 * Borrowed, never held: it is taken when the line is built and given back when
 * its last byte is sent, so it costs a few milliseconds of heap on the one
 * command that needs it rather than 8 KiB in every client slot. The board has
 * about 33 KB of heap free with four clients connected (see `net_budget.h`), and
 * a borrow that cannot be met is refused in words with the connection untouched.
 */
#define CLIENT_OUT_BIG 8192

/** Keepalive, so a phone that leaves the network is reaped rather than holding
 *  a slot until TCP's default two hours are up. */
#define KEEPALIVE_IDLE_S 15
#define KEEPALIVE_INTERVAL_S 5
#define KEEPALIVE_COUNT 3

/** Sessions whose names are remembered, so readings queued across a session
 *  change still serialise with the name they were taken under. Two would do;
 *  four costs 160 bytes and covers a burst of pause/resume. */
#define SESSION_CACHE 4

/* --- per-client state ----------------------------------------------------- */

typedef struct {
    bool in_use;
    net_link_t *link;
    int id;
    TaskHandle_t task;

    /** Guards @c queue and @c name against the acquisition task and peers. */
    SemaphoreHandle_t lock;
    char name[WIRE_NAME_LEN];

    acq_queue_t queue;
    acq_reading_t slots[ACQ_CLIENT_QUEUE_MAX];

    char out[CLIENT_OUT_MAX];
    /**
     * A larger buffer, borrowed for the one message that will not fit @c out.
     *
     * NULL whenever @c out is enough, which is every message but `file_list`.
     * Taken when that line is built and given straight back when the last byte
     * of it has been accepted, so a client carries a pointer rather than a
     * buffer - the same trade as ::xfer and ::import_scratch, and the one
     * ::CLIENT_SLOT_BUDGET exists to keep the code making.
     */
    char *out_big;
    size_t out_len;  /**< bytes of a line waiting to go out; 0 when idle */
    size_t out_sent; /**< how many of them have been accepted by the socket */
    int64_t out_since_us;

    /**
     * An in-flight `get_file`: where it has got to, and whether it is running.
     *
     * 3.0 KB, of which 2.9 is a decoded page, and **borrowed for the length of a
     * transfer** exactly as ::alerts_cursor is and for the same measured reason.
     * A backfill is a few minutes of a connection that lasts hours; carrying its
     * page in the slot made every idle phone pay for it, and a client slot is
     * paid for out of the same 89 KB the radios are living in. See the note on
     * ::s_clients for what that arithmetic came to.
     *
     * NULL whenever @c xfer_active is false. A failed allocation refuses that
     * one `get_file` in words - the connection and everything else on it are
     * untouched.
     */
    store_cursor_t *xfer;
    char xfer_name[WIRE_CSV_NAME_LEN];
    bool xfer_active;
    /** The session being sent was recorded with no clock. */
    bool xfer_relative;
    uint32_t xfer_rows;

    /**
     * An in-flight `put_file`, running totals across every session in it.
     *
     * The store holds the merge itself; this is what the *user* is owed at the
     * end. A transfer covers many session files and the answer to "what
     * happened" is about the transfer, so the per-session counters are folded in
     * here as each file closes rather than reported one file at a time.
     *
     * A whole module rather than six fields, because the discipline it keeps -
     * one transfer, one message - is what stops a refusal wedging the board.
     * See `net_import.h`.
     */
    net_import_t import;
    /**
     * Where a `put_file` chunk's rows are decoded to, and an `erase`'s claims.
     *
     * Per client, not static. The client tasks are preemptive and two of them
     * can be inside `dispatch` at the same moment, so a shared scratch buffer
     * would let one transfer's rows overwrite another's while it was still
     * being read - which is a silent, data-losing bug in exactly the code that
     * exists so that data is not lost. 2 KiB each, matching the frame that
     * fills it.
     *
     * **Borrowed while a transfer is running**, like the two cursors: a phone
     * that is watching readings never sends a `put_file`, and holding 2 KiB
     * against the chance that it might is what a slot cannot afford. Taken by
     * the chunk that needs it and given back when the transfer answers.
     */
    char *import_scratch;

    char rx[CLIENT_RX_MAX];
    size_t rx_len;
    bool rx_overrun;

    uint32_t seen_state;
    uint32_t seen_control;
    uint32_t seen_clients;
    uint32_t seen_profile;
    /** Bumped whenever an equivalence is appended, so `time_map` is resent. */
    uint32_t seen_time_map;

    /**
     * Whether this client has been told the store is near full, and whether it
     * has been told at all.
     *
     * The trigger for the automatic export, so it is edge-driven rather than
     * repeated: a phone that has been told once and is already copying does not
     * need telling again every second, and a store that drops back below the
     * threshold has to be able to say so.
     */
    bool told_storage;
    bool told_storage_warn;

    /* --- preserved alert data owed to this client -------------------------- */
    /**
     * Whether the controller sends what it preserved without being asked.
     *
     * **True by default**, which is the whole of the captain's "automatic
     * delivery on first connection, with an opt-out". A client that does not
     * want it sends `set_alerts` with `auto: false`; a client that says nothing
     * gets it, which is also why an app built before any of this existed is
     * safe - it never asks, and receives three frame types it ignores.
     */
    bool alerts_auto;
    bool told_alerts;
    uint32_t told_alerts_segments;
    uint32_t told_alerts_rate_ms;

    /** Delivered up to this header sequence. Headers never repeat, so it only rises. */
    uint32_t alerts_since_seq;
    /** The segment being sent, and whether its header has gone yet. */
    store_alert_header_t alerts_header;
    uint32_t alerts_seq;
    bool alerts_header_sent;
    uint32_t alerts_rows;
    char alerts_name[WIRE_CSV_NAME_LEN];
    /**
     * The walk over one segment's samples, allocated only while one is running.
     *
     * 2.7 KiB, of which 2.6 is a decoded page. A client already costs 11 KiB and
     * eight of them nearly exhausted the heap once; a second permanent page
     * buffer each would put 21 KiB back on that number to serve something that
     * happens for a few seconds after a connection. So it is borrowed for the
     * duration of a segment and handed back, exactly as the client slot itself
     * is. A failed allocation skips the samples and still sends the header,
     * which is the part that has to arrive.
     */
    store_preserve_cursor_t *alerts_cursor;

    /* --- backing everything up and clearing the board ---------------------- */
    /**
     * The block in flight, and how much of it has gone.
     *
     * Forty-odd bytes and no buffer, which is why it can live in the slot when
     * the three above cannot: the drain re-walks the block from flash for each
     * frame rather than holding a cursor open across them. That costs a few
     * kilobytes of reads a frame and buys the property the whole feature is
     * built on - there is no state to lose, so an interruption anywhere resumes
     * by asking the same question again.
     *
     * The drain itself belongs to the controller rather than to this slot; see
     * `store.h`. What is here is only this client's half of the conversation.
     */
    store_drain_block_t drain_block;
    uint32_t drain_rows_sent; /**< rows of it already framed */
    uint32_t drain_rows;      /**< what the controller measured, once it is all sent */
    uint32_t drain_digest;
    bool drain_sending;
    /** The rows have all gone and the `drain_state` for them has not. */
    bool drain_offered;

    /**
     * The `clean_due_in_hours` this client has been told, and whether it has.
     *
     * Edge-driven like the storage warning above, and for the same reason: the
     * figure moves by a tenth of an hour every six minutes of measurement, so a
     * version counter would make `power` a message-per-tick rather than the
     * handful a week it should be. `hello` carries the payload too, so an
     * arriving client is already told and owes nothing until it changes.
     */
    bool told_clean;
    double told_clean_hours;
} client_t;

/**
 * What a merely-connected client may cost, and the guard that keeps it there.
 *
 * Measured on the board 2026-08-03: 91,484 B of heap once Wi-Fi, Bluetooth and
 * the store are up, and a client costs its slot plus a 7 KiB task stack. At the
 * 11,400 B this slot used to be that is 18.6 KB each and the board refused the
 * fifth connection - with the captain's tablet, his phone and a probe on it,
 * plus the app's own subnet sweep taking a slot as it goes past, four was
 * already marginal and it showed.
 *
 * Three buffers were the whole of it, and none of them is wanted by a client
 * that is doing what clients do: a backfill cursor, a chunk buffer and a
 * preserved-segment walk, none of which a phone watching live readings ever
 * touches. Borrowed while the transfer that needs them runs and given straight
 * back, the slot is 6,336 B and a client costs 13.2 KB, so the same heap holds
 * six or seven rather than four or five.
 *
 * This assertion is the regression test for that, and it is deliberately a
 * build-time one: there is no host test that can see this struct, and a growth
 * of two kilobytes here is invisible in every functional test right up to the
 * afternoon somebody spends looking for a leak that does not exist. Anything
 * that would break it belongs on the heap, borrowed, next to the three that
 * already are. Raise the number only with a heap figure measured on the board.
 */
#define CLIENT_SLOT_BUDGET 6656

_Static_assert(sizeof(client_t) <= CLIENT_SLOT_BUDGET,
               "a client slot has outgrown its heap budget; borrow the new buffer for the "
               "length of the operation that needs it, as xfer, import_scratch and "
               "alerts_cursor already are - see the note above");

/*
 * Eight slots, but only the connected ones cost anything.
 *
 * This was a static array, and the argument for that was real: a connection
 * must never fail on a fragmented heap while the captain is trying to look at
 * the sensor. It stopped being affordable. A client used to cost 2.8 KiB and
 * the transfer work took it to 11 KiB - a 2 KiB output buffer, a 2 KiB receive
 * buffer, a 2 KiB import scratch and a ~3 KiB page cursor each - so eight of
 * them reserve 87 KiB. Add the 56 KiB that enabling Bluetooth takes out of
 * `dram0_0_seg` and the Wi-Fi driver's own buffers, and the board booted with
 * **7 KiB of heap** and could not start a task for a single client: every
 * connection was refused. Measured on hardware, not predicted.
 *
 * So a slot is allocated when somebody connects and freed when they go. Idle
 * cost is eight pointers, and a board with one phone on it - which is every
 * board most of the time - keeps the rest for the radios that need it.
 *
 * That was necessary and not sufficient: at 11 KiB a slot, four simultaneous
 * clients still took 74 KiB of an 89 KiB heap and the next one was refused. The
 * three transfer buffers are now borrowed for the length of the transfer rather
 * than carried in the slot, which is what ::CLIENT_SLOT_BUDGET holds them to,
 * and a connected client costs 6.3 KiB again.
 *
 * What is given up is the guarantee that a connection cannot fail for want of
 * memory. What is gained is a connection that can succeed at all; the old
 * property was protecting a case that no longer existed. A failed allocation
 * refuses that one client, in words that say so, and leaves everyone else
 * untouched - see ::take_slot and ::net_server_attach.
 */
static client_t *s_clients[WIRE_MAX_CLIENTS];
static SemaphoreHandle_t s_registry_lock;
static int s_next_client_id;

/* --- state shared with acquisition ---------------------------------------- */

static struct {
    SemaphoreHandle_t lock;

    char state[16];
    char detail[80];
    uint32_t state_version;

    int control_holder; /**< client id, or 0 for nobody */
    char control_name[WIRE_NAME_LEN];
    int64_t control_expires_us;
    uint32_t control_version;

    uint32_t clients_version;

    /**
     * Bumped when the acquisition profile changes, so every client hears it.
     *
     * A profile change is not private to the phone that made it. Another viewer
     * showing "Continuous, every 1 s" while the sensor has been switched to a
     * fifteen-minute cycle is not stale by a second, it is wrong until someone
     * reconnects - and its readings arriving a quarter-hour apart look like a
     * fault rather than the setting they are.
     */
    uint32_t profile_version;

    /** Ring of session -> first reading uptime, for lazy CSV naming. */
    struct {
        uint32_t session;
        int64_t first_uptime_us;
        char csv[WIRE_CSV_NAME_LEN];
        bool named;
        /** Recorded against the board's own clock, so named for the run. */
        bool relative;
    } sessions[SESSION_CACHE];
    size_t session_next;

    uint32_t index; /**< the latest reading's sequence, for `hello` */
    /** Bumped when an equivalence is appended, so every client is resent the map. */
    uint32_t time_map_version;
} s_shared;

static net_server_config_t s_config;
static net_server_stats_t s_stats;

/** Why the last connection was turned away, so the message can say. */
typedef enum {
    REFUSED_AT_CAPACITY,
    REFUSED_NO_MEMORY,
} refusal_t;
static refusal_t s_last_refusal;

static void lock(SemaphoreHandle_t m)
{
    xSemaphoreTake(m, portMAX_DELAY);
}

static void unlock(SemaphoreHandle_t m)
{
    xSemaphoreGive(m);
}

static int64_t now_us(void)
{
    return esp_timer_get_time();
}

/* --- session naming ------------------------------------------------------- */

/**
 * Remember when a session's first reading was taken.
 *
 * The name cannot be made here, because at boot the clock is usually not set
 * yet. Recording the uptime instead means the name can be computed **later**
 * and still be right: ::net_time_epoch_us_for_uptime converts an old uptime
 * once the offset is known, so the session that started before SNTP answered
 * still gets the wall-clock name it deserves rather than one stamped from
 * whenever the clock happened to arrive.
 */
static void note_session(const acq_reading_t *r)
{
    /* Session 0 never reaches an observer - `acq` numbers from 1 - so it doubles
     * as the empty marker for a cache slot. */
    for (size_t i = 0; i < SESSION_CACHE; i++) {
        if (s_shared.sessions[i].session == r->session) {
            return;
        }
    }
    const size_t slot = s_shared.session_next % SESSION_CACHE;
    s_shared.session_next++;
    s_shared.sessions[slot].session = r->session;
    s_shared.sessions[slot].first_uptime_us = r->uptime_us;
    s_shared.sessions[slot].named = false;
    s_shared.sessions[slot].relative = false;
    s_shared.sessions[slot].csv[0] = '\0';
}

/** The session key for @p session, or "" while the clock is still unset. */
static void session_csv(uint32_t session, char *out, size_t cap)
{
    out[0] = '\0';
    if (session == 0) {
        return;
    }
    lock(s_shared.lock);
    for (size_t i = 0; i < SESSION_CACHE; i++) {
        if (s_shared.sessions[i].session != session) {
            continue;
        }
        if (!s_shared.sessions[i].named && s_shared.sessions[i].relative) {
            /* No clock ran while this session recorded, so there is no date to
             * name it after. The run and the position in it are what the board
             * does know, and they name it without claiming anything more. */
            wire_rel_csv_name(store_run_marker(),
                              (uint32_t)(s_shared.sessions[i].first_uptime_us / 1000),
                              s_shared.sessions[i].csv, sizeof(s_shared.sessions[i].csv));
            s_shared.sessions[i].named = true;
        } else if (!s_shared.sessions[i].named) {
            const int64_t epoch_us =
                net_time_epoch_us_for_uptime(s_shared.sessions[i].first_uptime_us);
            if (epoch_us == 0) {
                break; /* no clock yet; ask again next time */
            }
            const int64_t epoch_s = epoch_us / 1000000;
            wire_csv_name(epoch_s, net_time_utc_offset_s(epoch_s), s_shared.sessions[i].csv,
                          sizeof(s_shared.sessions[i].csv));
            s_shared.sessions[i].named = true;
        }
        snprintf(out, cap, "%s", s_shared.sessions[i].csv);
        break;
    }
    unlock(s_shared.lock);
}

/* --- readings taken before the clock was set ------------------------------ */

/**
 * How long a session waits for a clock before recording without one.
 *
 * Nothing on this board knows the date until SNTP answers, which is usually
 * seconds but was measured at ninety on one boot. Readings taken in that window
 * are held here and back-dated the moment the clock arrives:
 * `net_time_epoch_us_for_uptime` converts a *past* uptime, which is the same
 * trick that lets a session be named after the fact. That is worth waiting for -
 * a session with real timestamps is better than one that needs converting.
 *
 * It is not worth waiting for **forever**, and this used to. A board that never
 * joins Wi-Fi has no clock coming, and every reading past this buffer was
 * dropped: not stored, and not sent either, because the client loop holds
 * readings back until the clock is valid. Collection worked off-network and
 * viewing did not.
 *
 * So the buffer filling is now a decision rather than a loss. Ninety-six
 * readings is a minute and a half at 1 Hz; when they are gone the session
 * commits to the board's own clock, writes everything held, and goes on
 * recording. See ::settle_time_base.
 */
#define PRECLOCK_MAX 96

/** Touched only from the acquisition task, so it needs no lock. */
static struct {
    acq_reading_t items[PRECLOCK_MAX];
    size_t count;
    uint32_t dropped;
} s_preclock;

/* --- the time base a session records against ------------------------------- */
/*
 * Decided once, when a session opens, and never revisited. Either the board had
 * a clock and the session records instants exactly as it always has, or it did
 * not and the session records the board's own clock and says so.
 *
 * Deciding once is what makes a session usable. Half a session stamped from a
 * wall clock and half from a monotonic one would be a session whose readings
 * cannot be put in order at all, which is worse than either.
 *
 * The decision is deferred for as long as the pre-clock buffer holds, because on
 * a board that does have Wi-Fi the clock is usually seconds away and waiting for
 * it gives the session real timestamps. When the buffer fills, waiting has
 * stopped being a way of getting timestamps and started being a way of losing
 * readings, so the session commits to relative and everything held is written.
 * Nothing is dropped either way.
 */
static struct {
    uint32_t session;
    bool decided;
    bool relative;
} s_base;

/** The name of the session the newest reading belongs to. */
static void current_csv(char *out, size_t cap)
{
    uint32_t session = 0;
    lock(s_shared.lock);
    if (s_shared.session_next > 0) {
        session = s_shared.sessions[(s_shared.session_next - 1) % SESSION_CACHE].session;
    }
    unlock(s_shared.lock);
    if (session == 0) {
        out[0] = '\0';
        return;
    }
    session_csv(session, out, cap);
}

/* --- control arbitration -------------------------------------------------- */
/*
 * The daemon's rules, unchanged, because the app's UI is built on them:
 * everyone may watch, only the holder may change what the sensor is doing, an
 * uncontested command takes the lock silently so a single user never learns the
 * mechanism exists, and the lock expires so a phone that walks away cannot
 * strand everybody else.
 */

/** Caller holds the shared lock. */
static bool control_expired_locked(void)
{
    return s_shared.control_holder != 0 && now_us() >= s_shared.control_expires_us;
}

static void refresh_control_locked(int holder, const char *name)
{
    if (s_shared.control_holder != holder) {
        s_shared.control_version++;
    }
    s_shared.control_holder = holder;
    snprintf(s_shared.control_name, sizeof(s_shared.control_name), "%s", name);
    s_shared.control_expires_us = now_us() + (int64_t)(WIRE_CONTROL_TTL_SECONDS * 1000000.0);
}

/** True if @p c may issue mutating commands, taking a free lock on the way. */
static bool may_control(client_t *c, char *holder_name, size_t cap)
{
    bool granted;
    lock(s_shared.lock);
    if (control_expired_locked()) {
        s_shared.control_holder = 0;
        s_shared.control_version++;
    }
    if (s_shared.control_holder == 0 || s_shared.control_holder == c->id) {
        refresh_control_locked(c->id, c->name);
        granted = true;
    } else {
        granted = false;
        snprintf(holder_name, cap, "%s", s_shared.control_name);
    }
    unlock(s_shared.lock);
    return granted;
}

/**
 * Take the lock, even from a live holder.
 *
 * Deliberately a takeover rather than a request: the usual reason someone else
 * holds it is that they have put their phone in their pocket, and a lock only
 * its owner can free is a lock that strands people.
 */
static void claim_control(client_t *c)
{
    lock(s_shared.lock);
    refresh_control_locked(c->id, c->name);
    unlock(s_shared.lock);
    ESP_LOGI(TAG, "control claimed by client %d", c->id);
}

static void release_control(client_t *c)
{
    lock(s_shared.lock);
    if (s_shared.control_holder == c->id) {
        s_shared.control_holder = 0;
        s_shared.control_name[0] = '\0';
        s_shared.control_expires_us = 0;
        s_shared.control_version++;
    }
    unlock(s_shared.lock);
}

/* --- the acquisition observer --------------------------------------------- */

/**
 * Runs on the acquisition task. Everything it does is bounded and short.
 *
 * Note what is absent: no `snprintf`, no socket, no allocation, no unbounded
 * loop. Serialising here would put ten `printf`-formatted doubles on the
 * acquisition task's 4 KB stack once per client per second, and sending here
 * would let one unread socket set the sample rate.
 */
/** Mark the cached session as relative, so it is named for the run. */
static void mark_session_relative(uint32_t session)
{
    lock(s_shared.lock);
    for (size_t i = 0; i < SESSION_CACHE; i++) {
        if (s_shared.sessions[i].session == session) {
            s_shared.sessions[i].relative = true;
            s_shared.sessions[i].named = false; /* re-derive under the new rule */
            break;
        }
    }
    unlock(s_shared.lock);
}

/** Store one reading whose epoch is known. */
static void submit_to_store(const acq_reading_t *r, int64_t epoch_us)
{
    char csv[WIRE_CSV_NAME_LEN];
    session_csv(r->session, csv, sizeof(csv));
    const int64_t epoch_s = epoch_us / 1000000;
    /* The page header holds minutes, so ask for minutes rather than dividing
     * here: an open-coded /60 is exactly what was missing on the SNTP path. */
    store_submit(r, csv, epoch_us / 1000, (int16_t)net_time_utc_offset_min(epoch_s), false);
}

/**
 * Store one reading against the board's own clock, because there is no other.
 *
 * The packed form is what goes into the page header's time field, so the store's
 * per-page rebase, its delta arithmetic and its time markers all work on it
 * unchanged - the origin means something different, and nothing else does.
 */
static void submit_relative(const acq_reading_t *r)
{
    char csv[WIRE_CSV_NAME_LEN];
    session_csv(r->session, csv, sizeof(csv));
    const int64_t packed = store_rel_pack(store_run_marker(), (uint32_t)(r->uptime_us / 1000));
    /* Zero, not a guessed zone: nothing here knows one, and the equivalence that
     * arrives later carries the zone its time source was in. */
    store_submit(r, csv, packed, 0, true);
}

/** Keep a reading until the session's time base is settled. */
static void hold_preclock(const acq_reading_t *r)
{
    if (s_preclock.count < PRECLOCK_MAX) {
        s_preclock.items[s_preclock.count++] = *r;
        return;
    }
    /* Unreachable: the buffer filling is what commits the session to relative
     * time, and from then on nothing is held. Kept as the honest fallback, and
     * counted rather than silent, because "cannot happen" is a claim about code
     * that is a callback away from acquisition. */
    memmove(&s_preclock.items[0], &s_preclock.items[1],
            sizeof(s_preclock.items[0]) * (PRECLOCK_MAX - 1));
    s_preclock.items[PRECLOCK_MAX - 1] = *r;
    s_preclock.dropped++;
}

/** The time base is settled: write everything held, and stop holding. */
static void drain_preclock(bool relative)
{
    if (s_preclock.count == 0) {
        return;
    }
    const size_t held = s_preclock.count;
    /* Cleared first. Everything below can re-enter this observer's own helpers,
     * and a second drain of the same readings would file them twice. */
    s_preclock.count = 0;
    for (size_t i = 0; i < held; i++) {
        const acq_reading_t *r = &s_preclock.items[i];
        if (relative) {
            submit_relative(r);
            continue;
        }
        const int64_t epoch_us = net_time_epoch_us_for_uptime(r->uptime_us);
        if (epoch_us > 0) {
            submit_to_store(r, epoch_us);
        }
    }
    if (relative) {
        ESP_LOGW(TAG, "no clock after %u readings; this session records relative time",
                 (unsigned)held);
        ESP_LOGW(TAG, "nothing is lost: the app converts when an equivalence arrives");
    } else {
        ESP_LOGI(TAG, "clock arrived: stored %u readings taken before it%s", (unsigned)held,
                 s_preclock.dropped ? " (some older ones were dropped)" : "");
    }
}

/**
 * Settle what this session records against, if it is not settled already.
 *
 * @return true once the session has a time base, false while it is still worth
 *         waiting for a clock.
 */
static bool settle_time_base(const acq_reading_t *r)
{
    if (s_base.decided && s_base.session == r->session) {
        return true;
    }
    if (!s_base.decided || s_base.session != r->session) {
        /* A new session starts the question again: a board that gained a clock
         * between two sessions should record the second one absolutely. */
        s_base.session = r->session;
        s_base.decided = false;
        s_base.relative = false;
    }
    if (net_time_valid()) {
        s_base.decided = true;
        s_base.relative = false;
        drain_preclock(false);
        return true;
    }
    if (s_preclock.count >= PRECLOCK_MAX) {
        /* Waiting has stopped being a way of getting timestamps and started
         * being a way of losing readings. */
        s_base.decided = true;
        s_base.relative = true;
        mark_session_relative(r->session);
        drain_preclock(true);
        return true;
    }
    return false;
}

static void on_reading(const acq_reading_t *r, void *ctx)
{
    (void)ctx;

    lock(s_shared.lock);
    note_session(r);
    s_shared.index = r->sequence;
    unlock(s_shared.lock);


    /* The store is the second consumer, and the reason this observer knows the
     * wall clock at all: acquisition timestamps with uptime because nothing
     * below here knows the date, and a stored reading needs a time to be filed
     * against - either the world's or, failing that, the board's own.
     * `store_submit` copies and returns; it never blocks on flash, so this stays
     * a callback acquisition can afford. */
    if (!settle_time_base(r)) {
        hold_preclock(r);
    } else if (s_base.relative) {
        submit_relative(r);
    } else {
        const int64_t epoch_us = net_time_epoch_us_for_uptime(r->uptime_us);
        if (epoch_us > 0) {
            submit_to_store(r, epoch_us);
        }
    }

    lock(s_registry_lock);
    for (size_t i = 0; i < WIRE_MAX_CLIENTS; i++) {
        client_t *c = s_clients[i];
        if (c == NULL || !c->in_use) {
            continue;
        }
        lock(c->lock);
        if (!acq_queue_push(&c->queue, r)) {
            s_stats.readings_dropped++;
        }
        unlock(c->lock);
    }
    unlock(s_registry_lock);
}

/**
 * A time source turned up. Append the equivalence and tell everyone.
 *
 * Runs on whichever task learned the time - the SNTP callback, or a client task
 * handling `set_time`. `store_add_anchor` takes the store's lock and writes one
 * page, which is a flash write on a task that is not acquisition, so it is
 * affordable here in a way it would not be in the reading observer.
 *
 * Note what does **not** happen: nothing already recorded is touched. A session
 * that has been recording relative goes on recording relative. All that changes
 * is that the archive now contains the fact needed to place it on a calendar.
 */
static void on_clock_learned(int64_t uptime_us, int64_t epoch_ms, int utc_offset_min)
{
    if (store_add_anchor((uint32_t)(uptime_us / 1000), epoch_ms,
                         (int16_t)utc_offset_min) != ESP_OK) {
        return;
    }
    lock(s_shared.lock);
    s_shared.time_map_version++;
    unlock(s_shared.lock);
}

static void on_state(acq_state_t state, const char *detail, void *ctx)
{
    (void)ctx;
    lock(s_shared.lock);
    snprintf(s_shared.state, sizeof(s_shared.state), "%s", acq_state_str(state));
    snprintf(s_shared.detail, sizeof(s_shared.detail), "%s", detail != NULL ? detail : "");
    s_shared.state_version++;
    unlock(s_shared.lock);
}

/**
 * Acquisition has adopted a profile: mark every client as owing a `profile`.
 *
 * Only the version is recorded, not the profile itself. The line is built from
 * ::acq_get_profile when it goes out, so what a client is told is what the
 * state machine holds at that moment rather than a copy that could already be
 * one change behind.
 */
static void on_profile(const acq_profile_t *profile, void *ctx)
{
    (void)profile;
    (void)ctx;
    lock(s_shared.lock);
    s_shared.profile_version++;
    unlock(s_shared.lock);
}

/* --- serialising ---------------------------------------------------------- */

/**
 * The profile in force, in the protocol's shape.
 *
 * `mode` is a borrowed string literal, which is safe precisely because it is
 * one of two literals with static storage rather than anything derived from the
 * profile.
 */
static wire_profile_t live_profile(void)
{
    acq_profile_t p;
    acq_get_profile(&p);
    return (wire_profile_t){
        .mode = p.mode == ACQ_MODE_DUTY_CYCLE ? WIRE_MODE_DUTY_CYCLE : WIRE_MODE_CONTINUOUS,
        .interval = p.interval_s,
        .cycle_s = p.cycle_s,
        .warmup_s = p.warmup_s,
        .average_s = p.average_s,
    };
}

/** Where @p c's pending line lives: the slot's buffer, or the borrowed one. */
static char *out_buf(client_t *c) { return c->out_big != NULL ? c->out_big : c->out; }

/** Give the borrowed output buffer back. Safe to call when none is out. */
static void out_release(client_t *c)
{
    if (c->out_big != NULL) {
        free(c->out_big);
        c->out_big = NULL;
    }
}

/** Arm @p c's output buffer with a line, if one was produced. */
static bool arm(client_t *c, int written)
{
    if (written <= 0) {
        /* Nothing to send, so nothing to send it from. A borrowed buffer that
         * outlived a line that was never armed would be held until the client
         * disconnected. */
        out_release(c);
        return false;
    }
    c->out_len = (size_t)written;
    c->out_sent = 0;
    c->out_since_us = now_us();
    return true;
}

/**
 * How many clients to tell a client on @p transport this board can serve.
 *
 * The arithmetic is ::net_budget_advertised_clients, which the host pack drives;
 * this is the half that has to read the board - the heap and the roster - and so
 * cannot be tested anywhere but on it.
 *
 * It is deliberately read fresh on every `hello` and every roster change rather
 * than settled at boot. The bound is the heap, the heap moves as clients come and
 * go and as transfers borrow from it, and a figure fixed at boot is the same kind
 * of lie as the socket-array size was - just a slower one.
 */
static unsigned advertised_max_clients(const char *transport)
{
    return net_budget_advertised_clients(wire_max_clients_for(transport),
                                         (unsigned)s_stats.clients_now,
                                         heap_caps_get_free_size(MALLOC_CAP_8BIT),
                                         net_server_client_bytes(), NET_HEAP_RESERVE);
}

static bool fill_hello(client_t *c)
{
    acq_sensor_info_t info;
    acq_get_sensor_info(&info);
    char firmware[8] = "";
    if (info.valid) {
        snprintf(firmware, sizeof(firmware), "%u.%u", info.firmware_major, info.firmware_minor);
    }

    char csv[WIRE_CSV_NAME_LEN];
    current_csv(csv, sizeof(csv));

    char state[16];
    char detail[80];
    uint32_t index;
    lock(s_shared.lock);
    snprintf(state, sizeof(state), "%s", s_shared.state);
    snprintf(detail, sizeof(detail), "%s", s_shared.detail);
    index = s_shared.index;
    unlock(s_shared.lock);

    const wire_hello_t hello = {
        .product = info.product,
        .serial = info.serial,
        .firmware = firmware,
        .state = state,
        .detail = detail,
        .csv = csv,
        .index = index,
        /* Negative outside a timed phase, which is every continuous state; under
         * duty cycling it is what stops a fifteen-minute sleep looking like a
         * hung sensor. */
        .seconds = acq_phase_remaining(),
        .profile = live_profile(),
        .power_source = POWER_SOURCE,
        /* The one thing about power this board *can* measure: how long its own
         * fan has run since it was last cleaned. */
        .clean_run_seconds = acq_clean_seconds_since_clean(),
        .supports_sleep = acq_supports_sleep(),
        /* There is no operating system here to halt and no filesystem to
         * unmount: the store survives having the plug pulled by construction,
         * which is what made the shutdown ritual unnecessary rather than
         * unimplemented. Saying so lets the app leave the control out instead
         * of offering a button whose only outcome is the error below. */
        .can_power_off = false,
        /* False asks the client to send one. A board with no network never
         * gets a clock any other way, and the client offering unasked to a
         * controller that has one earns a refusal it would have to explain. */
        .has_clock = net_time_valid(),
        .client_id = c->id,
        /* What this board can really carry, not what the socket array holds.
         *
         * Two things bound it and both are real: the link, because Bluetooth
         * serves one and saying "8" to a phone on it promises room for a second
         * device that can never arrive; and the heap, because eight slots on a
         * part with 89 KB of it is five connections nobody can make. The board
         * was measured admitting three while this said eight - the refusal was
         * honest and only the advertised figure was not. See
         * ::net_budget_advertised_clients. */
        .max_clients = advertised_max_clients(c->link->transport),
        .transport = c->link->transport,
    };
    /* `hello` embeds the whole `power` payload, so this client is up to date on
     * the fan-clean figure and is owed a `power` line only when it moves. */
    c->told_clean = true;
    c->told_clean_hours = wire_clean_due_in_hours(hello.clean_run_seconds);
    return arm(c, wire_hello_line(c->out, sizeof(c->out), &hello));
}

/**
 * Whether this client's idea of when the fan is due a clean has gone stale.
 *
 * Compared on the *reported* figure rather than the raw seconds: the seconds
 * change every reading, and telling a phone about a number it rounds away would
 * be a message a second for a value that means "some time next week".
 */
static bool clean_due_changed(const client_t *c)
{
    if (!c->told_clean) {
        return true;
    }
    return c->told_clean_hours != wire_clean_due_in_hours(acq_clean_seconds_since_clean());
}

static bool fill_power(client_t *c)
{
    const double run_seconds = acq_clean_seconds_since_clean();
    c->told_clean = true;
    c->told_clean_hours = wire_clean_due_in_hours(run_seconds);
    return arm(c, wire_power_line(c->out, sizeof(c->out), POWER_SOURCE, run_seconds));
}

/**
 * The `power_estimate` reply: the model priced on what the board is doing at
 * this moment, read from the parts of this file that already know it.
 *
 * The radio states come from this server's own roster - the asking client is
 * in it, so a phone asking over Wi-Fi is priced with Wi-Fi serving, which is
 * what answering it costs. `sleeps` is ::acq_supports_sleep because that is
 * what decides whether a duty-cycled sensor on *this* board actually sleeps.
 * The battery is NULL: nothing on any board measures remaining capacity, and
 * the reply's runtime slot says so rather than inventing a number.
 */
static bool fill_power_estimate(client_t *c)
{
    bool wifi_client = false;
    bool ble_client = false;
    lock(s_registry_lock);
    for (size_t i = 0; i < WIRE_MAX_CLIENTS; i++) {
        if (s_clients[i] == NULL || !s_clients[i]->in_use) {
            continue;
        }
        const char *transport = s_clients[i]->link->transport;
        if (transport != NULL && strcmp(transport, WIRE_TRANSPORT_BLE) == 0) {
            ble_client = true;
        } else {
            wifi_client = true;
        }
    }
    unlock(s_registry_lock);

    const acq_state_t acq = acq_state();
    power_state_t state = POWER_DEFAULT_STATE();
    acq_get_profile(&state.profile);
    state.wifi = !net_wifi_connected() ? POWER_WIFI_OFF
                 : wifi_client        ? POWER_WIFI_STREAMING
                                      : POWER_WIFI_IDLE;
    state.ble = ble_client ? POWER_BLE_CONNECTED : POWER_BLE_ADVERTISING;
    state.cpu = POWER_CPU_ACTIVE; /* no esp_pm, no light sleep - see power.py */
    state.sensor_sleeps_between_cycles = acq_supports_sleep();
    state.sensor_present = acq != ACQ_STATE_STOPPED && acq != ACQ_STATE_CHECKING;
    return arm(c, power_report_line(c->out, sizeof(c->out), &state, NULL));
}

static bool fill_state(client_t *c)
{
    char state[16];
    char detail[80];
    lock(s_shared.lock);
    snprintf(state, sizeof(state), "%s", s_shared.state);
    snprintf(detail, sizeof(detail), "%s", s_shared.detail);
    c->seen_state = s_shared.state_version;
    unlock(s_shared.lock);
    /* The countdown belongs on the state that started it: `warmup` for 30 s and
     * `sleeping` for the rest of the cycle are the two the app draws a timer
     * for, and both are read from acquisition rather than remembered here. */
    return arm(c, wire_state_line(c->out, sizeof(c->out), state, detail,
                                  acq_phase_remaining()));
}

static bool fill_profile(client_t *c)
{
    lock(s_shared.lock);
    c->seen_profile = s_shared.profile_version;
    unlock(s_shared.lock);
    const wire_profile_t profile = live_profile();
    return arm(c, wire_profile_line(c->out, sizeof(c->out), &profile));
}

static bool fill_control(client_t *c)
{
    int holder;
    char name[WIRE_NAME_LEN];
    double expires_in;

    lock(s_shared.lock);
    if (control_expired_locked()) {
        s_shared.control_holder = 0;
        s_shared.control_version++;
    }
    holder = s_shared.control_holder;
    snprintf(name, sizeof(name), "%s", s_shared.control_name);
    expires_in = holder == 0
                     ? 0.0
                     : (double)(s_shared.control_expires_us - now_us()) / 1000000.0;
    c->seen_control = s_shared.control_version;
    unlock(s_shared.lock);

    if (expires_in < 0.0) {
        expires_in = 0.0;
    }
    /* One decimal place, as the daemon rounds it: the app counts down locally. */
    expires_in = (double)((int)(expires_in * 10.0 + 0.5)) / 10.0;
    return arm(c, wire_control_line(c->out, sizeof(c->out), holder, name, expires_in));
}

static bool fill_clients(client_t *c)
{
    wire_client_t roster[WIRE_MAX_CLIENTS];
    char names[WIRE_MAX_CLIENTS][WIRE_NAME_LEN];
    size_t count = 0;

    lock(s_shared.lock);
    c->seen_clients = s_shared.clients_version;
    unlock(s_shared.lock);

    lock(s_registry_lock);
    for (size_t i = 0; i < WIRE_MAX_CLIENTS; i++) {
        if (s_clients[i] == NULL || !s_clients[i]->in_use) {
            continue;
        }
        lock(s_clients[i]->lock);
        snprintf(names[count], WIRE_NAME_LEN, "%s", s_clients[i]->name);
        unlock(s_clients[i]->lock);
        roster[count].id = s_clients[i]->id;
        roster[count].name = names[count];
        count++;
    }
    unlock(s_registry_lock);

    /* The same figure `hello` gives, from the same place. The roster is where a
     * user watches "3 of 8" while the fourth device is being turned away, so a
     * cap here that disagreed with the accept path would be the lie arriving by
     * a second road. */
    return arm(c, wire_clients_line(c->out, sizeof(c->out), roster, count,
                                    advertised_max_clients(c->link->transport),
                                    c->link->transport));
}

/**
 * Send every equivalence the controller holds.
 *
 * The controller's whole contribution to converting relative time: it hands over
 * the raw pairs and the client does the arithmetic. That is deliberate and it is
 * not laziness - it keeps the conversion **reversible**. Raw relative readings
 * plus equivalences means a later or better time source can be applied again,
 * which is impossible once a timestamp has been baked into flash.
 */
static bool fill_time_map(client_t *c)
{
    lock(s_shared.lock);
    c->seen_time_map = s_shared.time_map_version;
    unlock(s_shared.lock);

    store_anchor_t held[STORE_MAX_ANCHORS];
    const size_t count = store_list_anchors(held, STORE_MAX_ANCHORS);
    wire_anchor_t out[STORE_MAX_ANCHORS];
    for (size_t i = 0; i < count; i++) {
        out[i].run = store_rel_run(held[i].rel_ms);
        out[i].rel_ms = store_rel_ms(held[i].rel_ms);
        out[i].epoch_ms = held[i].epoch_ms;
        out[i].utc_offset_min = held[i].utc_offset_min;
    }
    return arm(c, wire_time_map_line(c->out, sizeof(c->out), store_run_marker(), out, count));
}

/**
 * Refuse something, saying which command is being refused.
 *
 * @p ref is the `WIRE_CMD_*` this answers, and it is what lets a client tell a
 * refusal of its own transfer from a refusal of whatever else the user just
 * tapped - without it, a refused `clean` looked exactly like a failed `backup`
 * and tore one down. NULL keeps the pre-`ref` frame and its pre-`ref` meaning,
 * "concerns everything", which is right for a failure that answers no command.
 */
static bool fill_error_ref(client_t *c, const char *message, bool needs_control,
                           const char *ref)
{
    return arm(c, wire_error_line(c->out, sizeof(c->out), message, needs_control, false, ref));
}

static bool fill_error(client_t *c, const char *message, bool needs_control)
{
    return fill_error_ref(c, message, needs_control, NULL);
}

/** Pop one reading and serialise it, or report that there was nothing to send. */
static bool fill_reading(client_t *c)
{
    acq_reading_t r;
    bool have;

    lock(c->lock);
    have = acq_queue_pop(&c->queue, &r);
    unlock(c->lock);
    if (!have) {
        return false;
    }

    /* A session that committed to the board's own clock keeps it for life, even
     * if a clock turns up later - the readings already in flash are filed that
     * way and one session cannot be half in each. What the clock buys is an
     * equivalence, appended, which the app converts through. */
    bool relative = s_base.decided && s_base.relative && s_base.session == r.session;

    int64_t epoch_us = 0;
    if (!relative) {
        epoch_us = net_time_epoch_us_for_uptime(r.uptime_us);
        if (epoch_us == 0) {
            /* No clock, and this reading is already out of the queue. The only
             * way here is that the session settled on relative between the
             * reading being queued and being serialised - this runs on a client
             * task and the settling happens on the acquisition one. Send what
             * the board does know rather than dropping the reading, which is
             * what the old code did and what this whole change exists to stop. */
            relative = true;
        }
    }
    const int64_t epoch_s = epoch_us / 1000000;

    char timestamp[WIRE_TIMESTAMP_LEN];
    timestamp[0] = '\0';
    if (!relative) {
        wire_iso_timestamp(epoch_s, net_time_utc_offset_s(epoch_s), timestamp,
                           sizeof(timestamp));
    }

    char csv[WIRE_CSV_NAME_LEN];
    session_csv(r.session, csv, sizeof(csv));

    const wire_reading_t reading = {
        .csv = csv,
        .index = r.sequence,
        .timestamp = timestamp,
        .event = (r.flags & ACQ_FLAG_SETTLING) ? WIRE_EVENT_SETTLING : "",
        .values = &r.values,
        .relative = relative,
        .rel_run = store_run_marker(),
        .rel_ms = (uint32_t)(r.uptime_us / 1000),
    };
    if (!arm(c, wire_reading_line(c->out, sizeof(c->out), &reading))) {
        return false;
    }
    s_stats.readings_sent++;
    return true;
}

/* --- serving what is stored ----------------------------------------------- */

/**
 * Answer `list_files` with what the ring actually holds.
 *
 * This used to be hard-coded empty, and the message next door said the phone
 * was the record. Both were true of a firmware that stored nothing; neither is
 * true now, and leaving either in place would tell the app there is nothing to
 * fetch at the exact moment there is.
 */
/**
 * Sessions named in one `file_list`, newest first.
 *
 * `file_list` is the enumeration a **backup walks**, so a session the controller
 * holds and does not name is a session that silently does not get exported. It
 * used to be twelve, on the reasoning that the list is a display and twelve is
 * more than the app can usefully show; measured, not argued, an export taken
 * while the board held thirteen sessions produced an archive of twelve, and the
 * missing one was the oldest - the one most likely to be about to be
 * overwritten, and the one nobody would notice.
 *
 * It was then ::STORE_SESSION_INDEX_MAX, which was the same mistake at a
 * distance: the number of summaries RAM caches has nothing to do with how many
 * a frame can carry, and tying the two meant the board could hold a session it
 * was structurally unable to name. The store no longer has a ceiling on what it
 * holds (see `store_sessions.h`), so this is now what it should always have been
 * - **what one frame can carry**, twice the index's size, and nothing to do with
 * it.
 *
 * The cost is memory, and all of it is borrowed from the heap for the length of
 * this one call, exactly as `get_file`'s cursor and `put_file`'s scratch are and
 * for the same reason: a phone that is watching live readings should not carry a
 * buffer it uses once a connection. The stack is the one place it must not come
 * from - a client task has 7 KiB and these three arrays are 12 KB between them.
 *
 * A board holding more than this still holds, counts, serves and erases every
 * one of them, and it can now name them all as well - not by carrying more in one
 * frame, which is the mistake the session table made twice, but by answering one
 * page at a time. ::fill_file_list says where the page sits and where the rest
 * begin; `wire.file_list_page` is the protocol and `MonitorSession` walks it.
 */
#define FILE_LIST_MAX 64

/**
 * Answer one page of `list_files`, starting @p offset entries from the newest.
 *
 * The page is what one frame can carry; the walk is what makes the listing
 * complete. `next` is stated rather than left to the client to derive, because
 * the loop below skips empty sessions and so can emit fewer entries than the run
 * of the listing it consumed - a client adding lengths would ask for the same
 * page for ever.
 */
static bool fill_file_list(client_t *c, uint32_t offset)
{
    store_session_t *held = calloc(FILE_LIST_MAX, sizeof(*held));
    wire_file_entry_t *entries = calloc(FILE_LIST_MAX, sizeof(*entries));
    char (*starts)[WIRE_TIMESTAMP_LEN] = calloc(FILE_LIST_MAX, sizeof(*starts));
    char *line = malloc(CLIENT_OUT_BIG);
    if (held == NULL || entries == NULL || starts == NULL || line == NULL) {
        /* Refused in words, like every other borrowed buffer here. The
         * connection and everything on it are untouched, and the app asks
         * again. */
        free(held);
        free(entries);
        free(starts);
        free(line);
        return fill_error(c, "the sensor is out of memory and cannot list its sessions",
                          false);
    }

    /* No flush here. `store_list_sessions_page` counts the page still being
     * filled without sealing it, so a session that has only just started still
     * appears; sealing instead would burn a page slot every time any client
     * asked what was available. The flush happens when someone asks for rows. */
    uint32_t total = 0;
    const size_t count = store_list_sessions_page(held, FILE_LIST_MAX, offset, &total);

    size_t n = 0;
    for (size_t i = 0; i < count && n < FILE_LIST_MAX; i++) {
        if (held[i].readings == 0) {
            continue; /* the app counts an empty session as work it need not do */
        }
        starts[n][0] = '\0';
        if (!held[i].relative) {
            const int64_t epoch_s = held[i].start_ms / 1000;
            wire_iso_timestamp(epoch_s, held[i].utc_offset_min * 60, starts[n],
                               sizeof(starts[n]));
        }
        entries[n].relative = held[i].relative;
        entries[n].name = held[i].name;
        entries[n].rows = held[i].readings;
        /* What it would be as CSV. The app shows this to someone deciding
         * whether to copy a session; the flash figure would be a number about
         * our page format rather than about their data. 218 bytes a row is
         * measured, not guessed - see tools/measure_store.py. */
        entries[n].bytes = held[i].readings * 218u;
        entries[n].start = starts[n];
        n++;
    }

    /*
     * Newest first, so the entries that go if anything has to go are the oldest.
     *
     * ::CLIENT_OUT_BIG is sized for a full table of the longest names this
     * firmware can produce and this should never bite. If it ever does, the
     * encoder answers -1 for the *whole* line - and a list that disappears
     * entirely is worse than a list that is one session short and says so in the
     * log. So the tail is dropped until it fits rather than the message lost.
     */
    const size_t wanted = n;
    wire_file_page_t page = {
        .offset = offset,
        .total = total,
        /* What the *store* handed over, not what survived the loop above: the
         * next page has to begin after everything this one considered, or a
         * skipped empty session would be re-considered for ever. */
        .next = offset + (uint32_t)count,
    };
    page.more = page.next < total && count > 0;
    int written = wire_file_list_line(line, CLIENT_OUT_BIG, entries, n, &page);
    while (written < 0 && n > 0) {
        n--;
        /* A frame that will not fit its own page is short, and saying `next` is
         * past what it actually named would strand the difference. Continue from
         * the last entry this frame really carried. */
        page.next = offset + (uint32_t)n;
        page.more = page.next < total && n > 0;
        written = wire_file_list_line(line, CLIENT_OUT_BIG, entries, n, &page);
    }
    if (n != wanted) {
        ESP_LOGW(TAG, "client %d: %u of %u sessions did not fit one file_list frame", c->id,
                 (unsigned)(wanted - n), (unsigned)wanted);
    }
    free(held);
    free(entries);
    free(starts);
    out_release(c); /* nothing should be pending, but the buffer is ours now */
    c->out_big = line;
    if (!arm(c, written)) {
        return false; /* arm gave the buffer back */
    }
    return true;
}

/**
 * Stop serving a `get_file` and give its 3.0 KB page cursor back.
 *
 * Every way a transfer can end goes through here, which is the point: the
 * cursor is heap the *rest* of the board is waiting for, and a path that
 * cleared @c xfer_active without freeing it would look exactly like the leak
 * this change exists to stop being suspected of.
 */
static void end_file_transfer(client_t *c)
{
    c->xfer_active = false;
    if (c->xfer != NULL) {
        free(c->xfer);
        c->xfer = NULL;
    }
}

/**
 * Put the next frame of an in-flight `get_file` into the output buffer.
 *
 * One frame per call, filled until the next row would not fit. The transfer
 * lives across calls in the client's cursor, so a large session is sent without
 * ever holding more than one frame, and a client that goes away mid-transfer
 * costs nothing to abandon.
 *
 * @return false when there was nothing to send, which also ends the transfer.
 */
static bool fill_file_data(client_t *c)
{
    if (!c->xfer_active || c->xfer == NULL) {
        return false;
    }
    wire_file_data_t fd;
    if (wire_file_data_open(&fd, c->out, sizeof(c->out), c->xfer_name,
                            c->xfer_relative) != 0) {
        end_file_transfer(c);
        return false;
    }

    store_reading_t reading;
    bool more = true;
    uint32_t in_frame = 0;
    while (wire_file_data_room(&fd)) {
        if (!store_cursor_next(c->xfer, &reading)) {
            more = false;
            break;
        }
        char timestamp[WIRE_TIMESTAMP_LEN];
        timestamp[0] = '\0';
        if (!c->xfer_relative) {
            const int64_t epoch_s = reading.epoch_ms / 1000;
            wire_iso_timestamp(epoch_s, net_time_utc_offset_s(epoch_s), timestamp,
                               sizeof(timestamp));
        }
        sps30_measurement_t values;
        for (size_t f = 0; f < SPS30_MEASUREMENT_FIELDS; f++) {
            values.values[f] = reading.values[f];
        }
        /* On a relative session `epoch_ms` is the packed board clock, so the two
         * extra cells are simply it taken apart. No conversion happens here even
         * when this controller now has a clock: that is the client's job, and
         * doing it here would bake one answer into the transfer. */
        if (!wire_file_data_row(&fd, reading.index, timestamp, &values,
                                reading.settling ? WIRE_EVENT_SETTLING : "",
                                store_rel_run(reading.epoch_ms),
                                store_rel_ms(reading.epoch_ms))) {
            break;
        }
        in_frame++;
        c->xfer_rows++;
    }

    const bool done = !more;
    /* An empty final frame is still sent: `done` is what releases the app's
     * transfer, and swallowing the last message because it carries no rows
     * would leave it waiting forever. The line is built before the cursor goes
     * back, because closing the frame is the last thing that reads it. */
    const int len = wire_file_data_close(&fd, done);
    if (done) {
        end_file_transfer(c);
    }
    if (!arm(c, len)) {
        end_file_transfer(c);
        return false;
    }
    if (done) {
        ESP_LOGI(TAG, "client %d: sent %" PRIu32 " stored readings of %s", c->id,
                 c->xfer_rows, c->xfer_name);
    }
    (void)in_frame;
    return true;
}

/** Begin serving @p name past @p since_index, or say why not. */
static void start_file_transfer(client_t *c, const char *name, uint32_t since_index)
{
    /* Seal the page still being filled first, so the last minute of readings is
     * not missing from a backfill purely because its page is not full yet. */
    store_flush();

    /* Borrowed here rather than carried in the slot; see ::client_t::xfer. A
     * transfer already running keeps the cursor it has - `store_cursor_open`
     * below starts it over, which is what a second `get_file` asks for. */
    if (c->xfer == NULL) {
        c->xfer = calloc(1, sizeof(*c->xfer));
    }
    if (c->xfer == NULL) {
        /* Said in the same words the accept path uses, because it is the same
         * wall: the board is out of memory. Refusing the backfill and leaving
         * the connection alone is the graceful half of that - the live readings
         * this client came for keep arriving. */
        ESP_LOGW(TAG, "client %d: no memory for a transfer of %s (%u B); %u B of heap left", c->id,
                 name, (unsigned)sizeof(*c->xfer),
                 (unsigned)heap_caps_get_free_size(MALLOC_CAP_8BIT));
        fill_error_ref(c, "the sensor is out of memory and cannot send a stored session right "
                          "now; try again when fewer devices are connected",
                       false, WIRE_CMD_GET_FILE);
        return;
    }

    if (!store_cursor_open(c->xfer, name, since_index)) {
        char message[96];
        if (!store_ready()) {
            snprintf(message, sizeof(message),
                     "storage is not available on this controller");
        } else {
            snprintf(message, sizeof(message), "no stored session named '%s'", name);
        }
        /* Nothing is being served, so the 3.0 KB goes straight back rather than
         * sitting in a slot until the client happens to ask for something the
         * store does have. */
        end_file_transfer(c);
        fill_error_ref(c, message, false, WIRE_CMD_GET_FILE);
        return;
    }
    store_session_t session;
    c->xfer_relative = store_find_session(name, &session) && session.relative;
    snprintf(c->xfer_name, sizeof(c->xfer_name), "%s", name);
    c->xfer_rows = 0;
    c->xfer_active = true;
    /* The first frame goes out on the next turn of the client loop, alongside
     * everything else it has to weigh, rather than jumping the queue here. */
}


/* --- taking a session back in --------------------------------------------- */

/**
 * Borrow this client's chunk buffer, or NULL if the heap has none to lend.
 *
 * Held across the chunks of one transfer rather than taken and given back per
 * chunk, because a `put_file` is hundreds of frames and a 2 KiB allocation on
 * every one of them is how a heap becomes fragmented on a part with no MMU.
 * See ::client_t::import_scratch.
 */
static char *scratch_take(client_t *c)
{
    if (c->import_scratch == NULL) {
        c->import_scratch = malloc(CLIENT_RX_MAX);
    }
    return c->import_scratch;
}

/** Give the chunk buffer back, if one is out. */
static void scratch_release(client_t *c)
{
    if (c->import_scratch != NULL) {
        free(c->import_scratch);
        c->import_scratch = NULL;
    }
}

/**
 * Fill in `storage`: how much of the ring is spoken for.
 *
 * The controller says this rather than waiting to be asked, because the point of
 * the threshold is that the phone starts copying *before* the ring is under
 * pressure - and a phone that has to poll for it is a phone that will not be
 * polling on the day it matters.
 */
static bool fill_storage(client_t *c)
{
    store_stats_t stats;
    store_get_stats(&stats);
    const float fraction = store_fraction_used();

    /* Everything held, not everything that fits in a listing. Summing the list
     * instead made this report *less* than the board was holding at the exact
     * moment it started holding more - see ::store_held. */
    uint32_t readings = 0;
    uint32_t sessions_held = 0;
    store_held(&readings, &sessions_held);

    const wire_storage_t storage = {
        .used_pages = stats.slots_used,
        .total_pages = stats.slots,
        .readings = readings,
        .sessions = sessions_held,
        .fraction = (double)fraction,
        .warn = fraction >= (float)WIRE_STORE_WARN_FRACTION,
        .ready = stats.ready,
    };
    c->told_storage = true;
    c->told_storage_warn = storage.warn;
    return arm(c, wire_storage_line(c->out, sizeof(c->out), &storage));
}

/**
 * Fill in `storage_map`: the per-page seat map of the whole partition.
 *
 * Answered fresh from a header walk on every ask - nothing in RAM knows which
 * slots hold pages, and a map derived from the counters would be the faked
 * precision the message exists to avoid. The walk reads 48 bytes a page and
 * runs without the store's lock, so it costs tens of milliseconds here and
 * stalls nothing; see `store_map_report`.
 *
 * The line runs about 1.9 KiB at the shipping geometry, past `c->out`, so it
 * follows `fill_file_list`'s borrow exactly: heap for the length of the send,
 * refused in words when the heap cannot spare it, connection untouched.
 */
static bool fill_storage_map(client_t *c)
{
    if (!store_ready()) {
        return fill_error_ref(c, "storage is not available on this controller", false,
                              WIRE_CMD_STORAGE_MAP);
    }
    store_map_report_t *report = malloc(sizeof(*report));
    char *line = malloc(CLIENT_OUT_BIG);
    if (report == NULL || line == NULL) {
        free(report);
        free(line);
        return fill_error_ref(c, "the sensor is out of memory and cannot map its storage "
                                 "right now; try again when fewer devices are connected",
                              false, WIRE_CMD_STORAGE_MAP);
    }
    if (!store_map_report(report)) {
        free(report);
        free(line);
        return fill_error_ref(c, "storage is not available on this controller", false,
                              WIRE_CMD_STORAGE_MAP);
    }

    wire_map_section_t sections[3];
    const size_t count = report->region_count;
    for (size_t i = 0; i < count && i < 3; i++) {
        sections[i].id = report->regions[i].id;
        sections[i].start = report->regions[i].start;
        sections[i].pages = report->regions[i].pages;
        sections[i].head = report->regions[i].head;
        sections[i].wraps = report->regions[i].wraps;
        sections[i].cells = report->regions[i].cells;
    }
    const wire_storage_map_t map = {
        .page_bytes = report->page_bytes,
        .pages = report->pages,
        .sections = sections,
        .section_count = count < 3 ? count : 3,
    };
    const int written = wire_storage_map_line(line, CLIENT_OUT_BIG, &map);
    free(report);
    if (written <= 0) {
        /* The encoder refuses to truncate and the buffer is sized for the
         * geometry with room to spare, so this is a partition the build does
         * not expect - say so rather than send nothing silently. */
        free(line);
        return fill_error_ref(c, "the storage map does not fit a frame on this controller",
                              false, WIRE_CMD_STORAGE_MAP);
    }
    out_release(c); /* nothing should be pending, but the buffer is ours now */
    c->out_big = line;
    return arm(c, written);
}

/** Whether this client is owed a `storage` message it has not had. */
static bool storage_changed(const client_t *c)
{
    if (!c->told_storage) {
        return true;
    }
    return c->told_storage_warn != (store_fraction_used() >= (float)WIRE_STORE_WARN_FRACTION);
}

/* --- preserved alert data -------------------------------------------------- */

/**
 * Fill in `alerts`: what the controller kept when the ring wrapped.
 *
 * Sent unasked, like `storage` and for the same reason - and deliberately
 * separate from it. `storage` is about the ring, whose answer to being full is
 * to wrap; this is about the area outside it, whose answer to being full is the
 * only rate reduction in this firmware. A client that saw one figure for both
 * could not tell the two situations apart, and they call for opposite things.
 */
static bool fill_alerts(client_t *c)
{
    store_preserve_stats_t ps;
    store_alert_get_stats(&ps);
    store_alert_policy_t policy;
    store_alert_get_policy(&policy);
    const uint32_t rate_ms = store_rate_floor_ms();

    const wire_alerts_t alerts = {
        .ready = store_ready(),
        .segments = ps.segments,
        .pressure = (double)store_alert_pressure(),
        .rate_floor_s = (double)rate_ms / 1000.0,
        .wraps = store_wraps(),
        .thresholds = policy.threshold,
        .threshold_count = STORE_FIELDS,
    };
    c->told_alerts = true;
    c->told_alerts_segments = ps.segments;
    c->told_alerts_rate_ms = rate_ms;
    return arm(c, wire_alerts_line(c->out, sizeof(c->out), &alerts));
}

/** Whether this client is owed an `alerts` message it has not had. */
static bool alerts_changed(const client_t *c)
{
    if (!c->told_alerts) {
        return true;
    }
    store_preserve_stats_t ps;
    store_alert_get_stats(&ps);
    /* Edge-driven on the two things a client acts on: a new segment to collect,
     * and a rate floor to show. The pressure fraction moves continuously and
     * saying so every second would make this a message-per-tick. */
    return ps.segments != c->told_alerts_segments ||
           store_rate_floor_ms() != c->told_alerts_rate_ms;
}

/** Give back the walk over a segment's samples, if one is out. */
static void alerts_release_cursor(client_t *c)
{
    if (c->alerts_cursor != NULL) {
        free(c->alerts_cursor);
        c->alerts_cursor = NULL;
    }
}

/** Finish with the segment in hand and move the watermark past it. */
static void alerts_finish_segment(client_t *c)
{
    alerts_release_cursor(c);
    if (c->alerts_seq > c->alerts_since_seq) {
        c->alerts_since_seq = c->alerts_seq;
    }
    c->alerts_seq = 0;
    c->alerts_header_sent = false;
    c->alerts_rows = 0;
}

/**
 * Send the next thing this client is owed of the preserved alert data.
 *
 * One frame per turn, like every other filler here. The order within a segment
 * is header then samples, which is the order that matters: the header is the
 * part the captain requires to survive everything else, so it goes first and a
 * connection that drops halfway has still delivered the summary.
 *
 * Above the ordinary backfill in the client's turn order, and that is the point
 * of the whole feature: a backfill can be two days long, and the preserved data
 * is the part that cannot be fetched any other way once the ring has moved past
 * it.
 */
static bool fill_alert_data(client_t *c)
{
    if (!c->alerts_auto || !store_ready()) {
        return false;
    }

    if (c->alerts_seq == 0) {
        /* The cheap question first. ::store_alert_list reads the whole header
         * region, and this runs on every turn of every client loop - asking it
         * to discover there is nothing owed would put 64 KB of flash reads a
         * second under the store's lock, against the task writing the readings.
         * ::store_alert_newest_seq is a field read and answers it. */
        if (store_alert_newest_seq() <= c->alerts_since_seq) {
            return false; /* nothing owed */
        }
        /* The next segment this client has not had. It re-reads rather than
         * caching a list, so a segment written during a delivery is picked up
         * rather than missed. */
        if (store_alert_list(&c->alerts_header, 1, c->alerts_since_seq) == 0) {
            /* The header ring turned over past this client's watermark: the
             * segments it was owed are gone. Catching the watermark up is the
             * only honest move - leaving it behind would re-ask for records that
             * no longer exist, every turn, for ever. */
            c->alerts_since_seq = store_alert_newest_seq();
            return false;
        }
        c->alerts_seq = c->alerts_header.seq;
        c->alerts_header_sent = false;
        c->alerts_rows = 0;
        wire_csv_name(c->alerts_header.session_start_ms / 1000,
                      c->alerts_header.utc_offset_min * 60, c->alerts_name,
                      sizeof(c->alerts_name));
    }

    if (!c->alerts_header_sent) {
        const store_alert_header_t *h = &c->alerts_header;
        const bool has_body = (h->flags & STORE_ALERT_FLAG_BODY_GONE) == 0;

        char start[WIRE_TIMESTAMP_LEN];
        char stop[WIRE_TIMESTAMP_LEN];
        const int64_t start_s = h->start_ms / 1000;
        const int64_t stop_s = h->stop_ms / 1000;
        /* The same inferred-true-time path every other timestamp takes. The
         * controller does not stamp preserved data differently from live data;
         * it stores the instant and formats it here, once, like everything
         * else. */
        wire_iso_timestamp(start_s, net_time_utc_offset_s(start_s), start, sizeof(start));
        wire_iso_timestamp(stop_s, net_time_utc_offset_s(stop_s), stop, sizeof(stop));

        const wire_alert_segment_t segment = {
            .seq = h->seq,
            .session = c->alerts_name,
            .field = SPS30_FIELD_NAMES[h->field],
            .threshold = (double)h->threshold,
            .start = start,
            .stop = stop,
            .first = (double)h->first,
            .last = (double)h->last,
            .midpoint = (double)h->midpoint,
            .mean = (double)h->mean,
            .variance = (double)h->variance,
            .minimum = (double)h->minimum,
            .maximum = (double)h->maximum,
            .points = h->points,
            .first_index = h->first_index,
            .last_index = h->last_index,
            .wrap = h->wrap,
            .has_body = has_body,
        };
        c->alerts_header_sent = true;
        if (!has_body) {
            /* The samples are gone and the summary is the whole delivery. Not a
             * degraded case: it is the one this header exists for. */
            const bool sent = arm(c, wire_alert_segment_line(c->out, sizeof(c->out), &segment));
            alerts_finish_segment(c);
            return sent;
        }
        if (c->alerts_cursor == NULL) {
            c->alerts_cursor = calloc(1, sizeof(*c->alerts_cursor));
        }
        if (c->alerts_cursor == NULL || !store_alert_cursor_open(c->alerts_cursor, h->seq)) {
            ESP_LOGW(TAG, "client %d: sending alert segment %" PRIu32
                          " without its samples (no room, or they went)",
                     c->id, h->seq);
            const bool sent = arm(c, wire_alert_segment_line(c->out, sizeof(c->out), &segment));
            alerts_finish_segment(c);
            return sent;
        }
        return arm(c, wire_alert_segment_line(c->out, sizeof(c->out), &segment));
    }

    wire_alert_data_t ad;
    if (wire_alert_data_open(&ad, c->out, sizeof(c->out), c->alerts_seq, c->alerts_name) != 0) {
        alerts_finish_segment(c);
        return false;
    }
    store_reading_t reading;
    bool more = true;
    while (wire_alert_data_room(&ad)) {
        if (c->alerts_cursor == NULL || !store_alert_cursor_next(c->alerts_cursor, &reading)) {
            more = false;
            break;
        }
        char timestamp[WIRE_TIMESTAMP_LEN];
        const int64_t epoch_s = reading.epoch_ms / 1000;
        wire_iso_timestamp(epoch_s, net_time_utc_offset_s(epoch_s), timestamp, sizeof(timestamp));
        sps30_measurement_t values;
        for (size_t f = 0; f < SPS30_MEASUREMENT_FIELDS; f++) {
            values.values[f] = reading.values[f];
        }
        if (!wire_alert_data_row(&ad, reading.index, timestamp, &values,
                                 reading.settling ? WIRE_EVENT_SETTLING : "")) {
            break;
        }
        c->alerts_rows++;
    }

    const bool done = !more;
    /* An empty final frame still goes: `done` is what tells the client the
     * segment is complete, and swallowing it would leave one waiting. */
    const bool sent = arm(c, wire_alert_data_close(&ad, done));
    if (done) {
        ESP_LOGI(TAG, "client %d: sent alert segment %" PRIu32 ", %" PRIu32 " samples", c->id,
                 c->alerts_seq, c->alerts_rows);
        alerts_finish_segment(c);
    }
    return sent;
}

/**
 * `get_alerts` - ask for the preserved segments again, or from a watermark.
 *
 * A client that has already been sent everything and wants it again says so
 * here; one that holds segments up to N sends `since_seq: N` and is sent only
 * what it is missing. It also re-arms the automatic delivery, so a client that
 * declined and changed its mind has a way back.
 */
static void handle_get_alerts(client_t *c, const char *line, size_t len)
{
    if (!store_ready()) {
        fill_error(c, "storage is not available on this controller", false);
        return;
    }
    /* The open segment's samples, committed so a client asking now can have
     * them. It does not close the segment - see ::store_alert_flush. */
    store_alert_flush();

    int64_t since = 0;
    (void)wire_json_int(line, len, "since_seq", &since);
    alerts_release_cursor(c);
    c->alerts_seq = 0;
    c->alerts_header_sent = false;
    c->alerts_rows = 0;
    c->alerts_since_seq = since > 0 ? (uint32_t)since : 0;
    c->alerts_auto = true;
    fill_alerts(c);
}

/**
 * `set_alerts` - decline the automatic delivery, and move the preservation line.
 *
 * Both halves are deliberately outside the control claim. Declining your own
 * copy of the alert data changes nothing about what the sensor is doing, and a
 * client that had to take the lock to do it would be taking it away from
 * whoever holds it. The thresholds are a retention policy rather than a
 * notification setting - see `store_alert.h` for why the controller is told a
 * number at all - and one client narrowing what is *kept* is the only part that
 * affects anyone else, which is why it is logged.
 */
static void handle_set_alerts(client_t *c, const char *line, size_t len)
{
    bool auto_deliver = false;
    if (wire_json_bool(line, len, "auto", &auto_deliver)) {
        c->alerts_auto = auto_deliver;
        if (!auto_deliver) {
            /* Stop mid-segment rather than finishing the one in flight. The
             * client has said it does not want this; sending the rest of a
             * two-thousand-row segment first would be answering a different
             * question. */
            alerts_release_cursor(c);
            c->alerts_seq = 0;
            c->alerts_header_sent = false;
        }
        ESP_LOGI(TAG, "client %d: preserved alert data %s", c->id,
                 auto_deliver ? "on" : "declined");
    }

    /* One number per field and nothing else about a rule. A client with several
     * enabled rules sends the minimum for each field; see `wire.alert_thresholds`.
     *
     * A `thresholds` object that is present replaces the policy whole: a field
     * it does not name stops being watched. That is what makes "the user turned
     * a rule off" expressible at all - the alternative, treating an absent key
     * as "leave it alone", has no way to say "stop keeping this". */
    const char *body = NULL;
    size_t body_len = 0;
    bool moved = false;
    if (wire_json_object(line, len, "thresholds", &body, &body_len)) {
        for (size_t f = 0; f < SPS30_MEASUREMENT_FIELDS; f++) {
            double value = 0.0;
            if (!wire_json_double(body, body_len, SPS30_FIELD_NAMES[f], &value)) {
                value = 0.0;
            }
            if (store_alert_set_threshold((int)f, (float)value) == ESP_OK) {
                moved = true;
            }
        }
    }
    if (moved) {
        store_alert_policy_t policy;
        store_alert_get_policy(&policy);
        ESP_LOGW(TAG, "client %d moved the preservation line: pm2_5 now %.1f", c->id,
                 (double)policy.threshold[STORE_ALERT_FIELD_PM2_5]);
        /* Everyone is told, not just the client that asked: the line decides
         * what this controller keeps for all of them. */
        lock(s_registry_lock);
        for (size_t i = 0; i < WIRE_MAX_CLIENTS; i++) {
            if (s_clients[i] != NULL) {
                s_clients[i]->told_alerts = false;
            }
        }
        unlock(s_registry_lock);
    }
    fill_alerts(c);
}

/* --- backing everything up and clearing the board -------------------------- */
/*
 * The device drives every step of this and the controller only ever answers,
 * which is what makes an interruption at any point safe: a controller that is
 * never mid-decision has nothing to roll back. See `store_drain.h` for the block
 * rule and BACKUP_AND_CLEAR.md for the whole design.
 *
 * Deliberately none of this touches `handle_erase` or the transfer above. The
 * captain asked for a separate control and a separate path, and the reason is
 * worth stating: the erase gate is what every other feature has been tested
 * against for weeks, and the way not to break it is not to change it.
 */

/** The name of a block's kind, as `wire.DRAIN_KIND_*`. */
static const char *drain_kind_name(store_drain_kind_t kind)
{
    switch (kind) {
    case STORE_DRAIN_BODY:
        return WIRE_DRAIN_KIND_BODY;
    case STORE_DRAIN_SEGMENTS:
        return WIRE_DRAIN_KIND_SEGMENTS;
    case STORE_DRAIN_RING:
        return WIRE_DRAIN_KIND_RING;
    default:
        return "";
    }
}

/** Say where the drain has got to, in the one message that ever says it. */
static bool fill_drain_state(client_t *c, const char *state, const char *message, bool with_block)
{
    store_drain_progress_t p;
    store_drain_progress(&p);
    const wire_drain_state_t line = {
        .state = state,
        .message = message != NULL ? message : "",
        .block = with_block ? c->drain_block.id : 0,
        .kind = with_block ? drain_kind_name(c->drain_block.kind) : "",
        .final = with_block && c->drain_block.final,
        .rows = with_block ? c->drain_rows : 0,
        .digest = with_block ? c->drain_digest : 0,
        .blocks_left = p.blocks_left,
        .blocks_freed = p.blocks_freed,
        .blocks_kept = p.blocks_kept,
        .blocks_refused = p.blocks_refused,
        .readings_copied = p.readings_copied,
        .segments_copied = p.segments_copied,
        .readings_freed = p.readings_freed,
    };
    return arm(c, wire_drain_state_line(c->out, sizeof(c->out), &line));
}

/** What a row emitter is filling, and whether it ran out of room. */
typedef struct {
    wire_drain_data_t *dd;
    bool full;
} drain_fill_t;

static bool drain_emit_reading(void *ctx, const char *session, uint32_t page_seq,
                               const store_reading_t *reading, bool relative)
{
    (void)page_seq;
    drain_fill_t *f = (drain_fill_t *)ctx;
    if (!wire_drain_data_room(f->dd)) {
        f->full = true;
        return false;
    }
    char timestamp[WIRE_TIMESTAMP_LEN];
    timestamp[0] = '\0';
    if (!relative) {
        const int64_t epoch_s = reading->epoch_ms / 1000;
        wire_iso_timestamp(epoch_s, net_time_utc_offset_s(epoch_s), timestamp, sizeof(timestamp));
    }
    sps30_measurement_t values;
    for (size_t i = 0; i < SPS30_MEASUREMENT_FIELDS; i++) {
        values.values[i] = reading->values[i];
    }
    /* No conversion, even on a board that now has a clock: a relative reading
     * goes out as the board's own clock taken apart, and placing it is the
     * client's job. Doing it here would bake one answer into the only copy. */
    if (!wire_drain_data_row(f->dd, session, reading->index, timestamp, &values,
                             reading->settling ? WIRE_EVENT_SETTLING : "", relative,
                             store_rel_run(reading->epoch_ms),
                             store_rel_ms(reading->epoch_ms))) {
        f->full = true;
        return false;
    }
    return true;
}

static bool drain_emit_segment(void *ctx, const store_alert_header_t *header)
{
    drain_fill_t *f = (drain_fill_t *)ctx;
    if (!wire_drain_data_room(f->dd)) {
        f->full = true;
        return false;
    }
    char name[WIRE_CSV_NAME_LEN];
    /* The same name the preserved-segment delivery puts on a segment, from the
     * same two fields, so the two messages describe one session rather than two. */
    wire_csv_name(header->session_start_ms / 1000, header->utc_offset_min * 60, name,
                  sizeof(name));
    const wire_alert_segment_t segment = {
        .seq = header->seq,
        .session = name,
        .field = SPS30_FIELD_NAMES[header->field],
        .threshold = (double)header->threshold,
        .start = "",
        .stop = "",
        .first = (double)header->first,
        .last = (double)header->last,
        .midpoint = (double)header->midpoint,
        .mean = (double)header->mean,
        .variance = (double)header->variance,
        .minimum = (double)header->minimum,
        .maximum = (double)header->maximum,
        .points = header->points,
        .first_index = header->first_index,
        .last_index = header->last_index,
        .wrap = header->wrap,
        .has_body = (header->flags & STORE_ALERT_FLAG_BODY_GONE) == 0,
    };
    /* Whole seconds rather than the ISO timestamps `alert_segment` carries. A
     * segment of a session recorded with no clock holds a *packed* board clock
     * in those fields, and formatting one as a date produces a string that does
     * not read back - which would fail the digest of a perfectly good copy and
     * stop the drain. See `wire.DRAIN_SEGMENT_COLUMNS`. */
    if (!wire_drain_data_segment(f->dd, &segment, header->start_ms / 1000,
                                 header->stop_ms / 1000)) {
        f->full = true;
        return false;
    }
    return true;
}

/**
 * Put the next frame of the block in flight into the output buffer.
 *
 * One frame per turn, like every other filler here, and the block is re-walked
 * from `drain_rows` each time rather than held open in a cursor - which costs a
 * few kilobytes of flash reads a frame and buys the thing this whole feature is
 * built around: there is no state to lose, so an interruption anywhere resumes
 * by asking the same question again.
 */
static bool fill_drain_data(client_t *c)
{
    if (!c->drain_sending) {
        return false;
    }
    if (c->drain_offered) {
        /* The rows have all gone; say what the block was, so the client can show
         * a bar and knows what it is about to be asked to confirm. */
        c->drain_sending = false;
        c->drain_offered = false;
        return fill_drain_state(c, WIRE_DRAIN_STATE_OFFERED, "", true);
    }

    wire_drain_data_t dd;
    if (wire_drain_data_open(&dd, c->out, sizeof(c->out), c->drain_block.id,
                             drain_kind_name(c->drain_block.kind)) != 0) {
        c->drain_sending = false;
        return false;
    }
    drain_fill_t fill = {.dd = &dd, .full = false};
    const uint32_t sent = store_drain_rows(c->id, &c->drain_block, c->drain_rows_sent,
                                           drain_emit_reading, drain_emit_segment, &fill);
    c->drain_rows_sent += sent;

    if (sent == 0 && fill.full) {
        /*
         * A row that will not fit an empty frame. It cannot happen - see
         * DRAIN_READING_ROW_MAX, which is counted against this buffer - and if
         * it ever does the honest answer is to stop and say so. Carrying on
         * would send an empty frame every turn for ever, on a block the device
         * is waiting to be told about, which is a copy that has silently
         * stopped rather than one that failed.
         */
        c->drain_sending = false;
        ESP_LOGE(TAG, "client %d: a row of block %" PRIu32 " will not fit a frame", c->id,
                 c->drain_block.id);
        return fill_drain_state(c, WIRE_DRAIN_STATE_ERROR,
                                "the sensor could not fit a row of that block into a message",
                                true);
    }

    const bool done = !fill.full;
    if (done) {
        /* What the controller records as sent is measured from flash, not
         * counted as it went: a confirmation is held to that number, so a block
         * that was never fully read cannot be confirmed by a device that only
         * received part of it. */
        if (!store_drain_seal(c->id, &c->drain_block, &c->drain_rows, &c->drain_digest)) {
            c->drain_sending = false;
            return fill_drain_state(c, WIRE_DRAIN_STATE_ERROR,
                                    "the sensor could not read that block back", true);
        }
        c->drain_offered = true;
    }
    const int len = wire_drain_data_close(&dd, done);
    if (!arm(c, len)) {
        c->drain_sending = false;
        return false;
    }
    return true;
}

/** `drain_begin` - take the claim. Nothing is read and nothing is freed. */
static void handle_drain_begin(client_t *c)
{
    if (!store_ready()) {
        fill_error_ref(c, "storage is not available on this controller", false,
                       WIRE_CMD_DRAIN_BEGIN);
        return;
    }
    if (store_drain_open(c->id) != ESP_OK) {
        fill_error_ref(c,
                       "this sensor is busy with another device's transfer; try again in a "
                       "moment",
                       false, WIRE_CMD_DRAIN_BEGIN);
        return;
    }
    c->drain_sending = false;
    c->drain_offered = false;
    memset(&c->drain_block, 0, sizeof(c->drain_block));
    c->drain_rows = 0;
    c->drain_digest = 0;
    c->drain_rows_sent = 0;
    fill_drain_state(c, WIRE_DRAIN_STATE_IDLE, "ready to copy this sensor off and clear it",
                     false);
}

/** `drain_next` - offer the oldest block that is still held. */
static void handle_drain_next(client_t *c)
{
    if (!store_drain_running(c->id)) {
        fill_error_ref(c, "no back-up-and-clear is running for this device", false,
                       WIRE_CMD_DRAIN_NEXT);
        return;
    }
    if (!store_drain_take(c->id, &c->drain_block)) {
        c->drain_sending = false;
        c->drain_offered = false;
        fill_drain_state(c, WIRE_DRAIN_STATE_FINISHED,
                         "there is nothing left on this sensor to copy", false);
        return;
    }
    c->drain_rows_sent = 0;
    c->drain_rows = 0;
    c->drain_digest = 0;
    c->drain_offered = false;
    c->drain_sending = true;
    /* The first frame goes out on the next turn of the client loop, alongside
     * everything else it has to weigh, rather than jumping the queue here. */
}

/** `drain_confirm` - the device says it holds the block. Free it if that is so. */
static void handle_drain_confirm(client_t *c, const char *line, size_t len)
{
    if (!store_drain_running(c->id)) {
        fill_error_ref(c, "no back-up-and-clear is running for this device", false,
                       WIRE_CMD_DRAIN_CONFIRM);
        return;
    }
    int64_t block = 0;
    int64_t rows = 0;
    char digest_hex[16];
    (void)wire_json_int(line, len, "block", &block);
    (void)wire_json_int(line, len, "rows", &rows);
    uint32_t digest = 0;
    if (wire_json_string(line, len, "digest", digest_hex, sizeof(digest_hex))) {
        digest = (uint32_t)strtoul(digest_hex, NULL, 16);
    }

    const store_drain_verdict_t verdict =
        store_drain_answer(c->id, (uint32_t)block, (uint32_t)rows, digest);
    switch (verdict) {
    case STORE_DRAIN_FREED:
        fill_drain_state(c, WIRE_DRAIN_STATE_FREED, "", true);
        return;
    case STORE_DRAIN_KEPT:
        /* Everything the sensor held has gone to this device, and what is left
         * is what arrived while it was going. Say so plainly. */
        ESP_LOGW(TAG, "client %d finished a back-up-and-clear of this board", c->id);
        fill_drain_state(c, WIRE_DRAIN_STATE_KEPT,
                         "the readings that arrived during the copy were kept, and this "
                         "sensor is now clear",
                         true);
        return;
    case STORE_DRAIN_REFUSED:
        ESP_LOGW(TAG, "client %d: block %u was not confirmed; nothing was freed", c->id,
                 (unsigned)block);
        fill_drain_state(c, WIRE_DRAIN_STATE_REFUSED,
                         "that block did not match what this sensor holds; nothing was "
                         "removed",
                         true);
        return;
    case STORE_DRAIN_ERASE_FAILED:
        fill_drain_state(c, WIRE_DRAIN_STATE_ERROR,
                         "the sensor's flash would not erase that block; nothing was removed",
                         true);
        return;
    case STORE_DRAIN_NOT_OFFERED:
    default:
        fill_drain_state(c, WIRE_DRAIN_STATE_ERROR,
                         "that block is not the one this sensor is waiting to hear about",
                         false);
        return;
    }
}

/** `drain_cancel` - give the claim back. Nothing is freed, at any point. */
static void handle_drain_cancel(client_t *c)
{
    c->drain_sending = false;
    c->drain_offered = false;
    store_drain_close(c->id);
    fill_drain_state(c, WIRE_DRAIN_STATE_IDLE,
                     "stopped. Everything still on the sensor is exactly where it was", false);
}

/**
 * The zone this chunk's rows were recorded in, from the first of them.
 *
 * A session's identity is its start time, and the file name is local wall
 * clock, so the offset is what turns one into the other. Zero when there is no
 * readable row, which is what an empty leading chunk gives and is the same
 * answer the sender's own export would produce for a session with no zone.
 */
static int chunk_utc_offset_s(const char *rows, bool have_rows)
{
    if (!have_rows || rows[0] == '\0') {
        return 0;
    }
    const char *nl = strchr(rows, '\n');
    const size_t first_len = nl != NULL ? (size_t)(nl - rows) : strlen(rows);
    wire_row_t first;
    return wire_parse_row(rows, first_len, &first) ? first.utc_offset_s : 0;
}

/**
 * Claim the store for @p name, or record why it could not be claimed.
 *
 * Every failure is recorded on the transfer and **none of them answers**. See
 * `net_import.h`: an answer per chunk is what stopped the controller reading
 * and left two importers hanging.
 *
 * @return true when the store is open on @p name and its rows can be merged.
 */
static bool open_import(client_t *c, const char *name, const char *rows, bool have_rows)
{
    /* A new session needs an identity, and it has to be one that survives a
     * reboot: names are re-derived from a page's start time, so the start time
     * chosen here must be the one that produces this name. The file name is
     * local wall clock and the first row carries the zone, which together give
     * exactly that. */
    int64_t local_s = 0;
    if (!wire_parse_csv_name(name, &local_s)) {
        /* A relative session's name carries a run and a millisecond count
         * rather than a date, so it lands here. Refusing is the safe direction
         * and deliberately not a silent one: taking it in would mean rebuilding
         * relative pages from rows, and filing it under a guessed absolute
         * identity instead would put readings in a session they do not belong
         * to. See DEFERRED_FEATURES.md. */
        net_import_skipped(&c->import,
                           wire_parse_rel_csv_name(name, NULL, NULL)
                               ? "this controller cannot yet take back a session recorded "
                                 "without a clock"
                               : "that is not a session file name this controller can place");
        return false;
    }

    const int utc_offset_s = chunk_utc_offset_s(rows, have_rows);
    const int64_t start_ms = (local_s - utc_offset_s) * 1000;
    const esp_err_t err =
        store_import_open(c->id, name, start_ms, (int16_t)(utc_offset_s / 60));
    if (err == ESP_OK) {
        net_import_opened(&c->import);
        return true;
    }
    if (err == ESP_ERR_NO_MEM) {
        /* This session only. A different one in the same archive is a different
         * shape on flash and may merge perfectly well. */
        net_import_skipped(&c->import,
                           "that session is too fragmented to merge into safely; nothing "
                           "was changed");
        return false;
    }
    /* The claim is held by another device. Latched for the whole transfer, and
     * said once at the end - see ::net_import_refused. */
    net_import_refused(&c->import, "another device was already importing", true);
    return false;
}

/**
 * End the transfer in flight and send its one answer.
 *
 * Called by the chunk marked `done`, and by the client loop when a transfer has
 * gone quiet for ::NET_IMPORT_IDLE_MS. Both go through here so that "a transfer
 * ends with exactly one `import_result`" is true of every way one can end.
 *
 * @return true when a line was armed.
 */
static bool finish_import(client_t *c, bool cut_short)
{
    if (!c->import.in_flight) {
        return false;
    }

    /* The transfer is over however it got here, so the 2 KiB goes back before
     * the answer is built rather than waiting for the client to disconnect. */
    scratch_release(c);

    store_merge_stats_t stats;
    memset(&stats, 0, sizeof(stats));
    if (store_import_close(c->id, &stats)) {
        net_import_fold(&c->import, stats.added, stats.duplicate, stats.rejected, stats.no_room,
                        stats.fragmented);
    }

    net_import_answer_t answer;
    if (!net_import_answer(&c->import, cut_short, &answer)) {
        return false;
    }

    const wire_import_result_t result = {
        .name = answer.name,
        .added = answer.added,
        .duplicate = answer.duplicate,
        .rejected = answer.rejected,
        .sessions = answer.sessions,
        .state = answer.state,
        .message = answer.message,
    };
    ESP_LOGI(TAG,
             "client %d: import of %" PRIu32 " sessions is %s - %" PRIu32 " added, %" PRIu32
             " already held, %" PRIu32 " rejected%s",
             c->id, answer.sessions, answer.state, answer.added, answer.duplicate,
             answer.rejected, answer.resumed ? " (the remainder of a transfer already answered)"
                                             : "");
    if (arm(c, wire_import_result_line(c->out, sizeof(c->out), &result))) {
        return true;
    }
    /*
     * The answer was built and cannot be sent, and the transfer it belongs to
     * has already been reset - so this is the one moment at which it exists.
     * Silence here is what a sender experiences as a transfer that reaches a
     * hundred per cent and never ends, and it left nothing anywhere to say so:
     * the line above reports what the import *did*, and a reader with only that
     * would conclude the client had been told.
     */
    ESP_LOGE(TAG, "client %d: the import result did not fit %d bytes and was not sent", c->id,
             CLIENT_OUT_MAX);
    return false;
}

/**
 * Handle one `put_file` chunk.
 *
 * The mirror of `get_file`, and deliberately shaped like it: rows in the
 * daemon's own column order, a chunk at a time, `done` on the last one. The one
 * asymmetry is that the rows arrive as a single newline-separated string rather
 * than as a JSON array of arrays. That is not laziness - `wire.h` says this
 * controller reads flat objects with short string fields on purpose, and adding
 * a nested-array parser to accept data whose whole point is that it must not be
 * mangled would be the wrong place to start writing one.
 *
 * The import is opened by the first chunk and closed by the one marked `done`.
 * Between them the merge holds what the session already had, which is why the
 * chunking is invisible to the result: a session split across two hundred frames
 * de-duplicates exactly as one frame would.
 *
 * **A chunk that cannot be served answers nothing.** It is counted, discarded,
 * and the reason is carried to the end of the transfer and said once. That is
 * not politeness: the reply buffer is one line deep and a client cannot be read
 * while a line is waiting in it, so a refusal repeated per chunk stops the
 * controller reading, which stops the client sending, and both sit there until
 * something times out. Measured on the board, three runs - see `net_import.h`.
 */
static void handle_put_file(client_t *c, const char *line, size_t len)
{
    char name[WIRE_CSV_NAME_LEN];
    const bool named =
        wire_json_string(line, len, "name", name, sizeof(name)) && name[0] != '\0';
    if (!named) {
        name[0] = '\0';
    }

    /* A JSON boolean, which is what every client sends and what this could not
     * read until a transfer on the board hung waiting for a reply that the
     * controller was never going to send. */
    bool done = false;
    (void)wire_json_bool(line, len, "done", &done);

    /* The rows, as one string of newline-separated CSV, decoded into the buffer
     * this client borrows for the length of the transfer. */
    char *const rows = scratch_take(c);
    const bool have_rows =
        rows != NULL && wire_json_string(line, len, "rows", rows, CLIENT_RX_MAX);

    /*
     * A transfer says when it begins as well as when it ends.
     *
     * Only the end was ever logged, which is exactly the wrong half: a transfer
     * that ends leaves a line saying what it did, and a transfer that does not
     * end leaves the board's log looking like no transfer was ever attempted.
     * The pass of 2026-08-04 watched a send reach a hundred per cent and hang
     * with nothing on either side to say it had happened. This is the board's
     * half of that; the app's is in `MonitorSession.importArchive`.
     */
    const bool first_chunk = !c->import.in_flight;

    net_import_step_t step = net_import_chunk(&c->import, name, now_us());

    if (first_chunk) {
        ESP_LOGI(TAG, "client %d: import started, first session %s%s", c->id,
                 named ? name : "(unnamed)",
                 c->import.resumed ? " (resuming a transfer already closed out)" : "");
    }

    /* Two conditions that no amount of resending fixes, so the answer will say
     * `error` rather than `busy`. Latched like any other refusal rather than
     * answered here, for the reason in this function's header. */
    if (!named) {
        net_import_refused(&c->import, "a chunk arrived with no session name", false);
        step = NET_IMPORT_DISCARD;
    } else if (!store_ready()) {
        net_import_refused(&c->import, "storage is not available on this controller", false);
        step = NET_IMPORT_DISCARD;
    }

    /* No buffer, no rows - and saying nothing about it would be the silent data
     * loss this whole path exists to prevent. Last of the three, because a
     * refusal is latched first-wins and the two above can never be fixed by
     * resending, while this one is fixed by a client disconnecting. Retryable,
     * so the one answer at the end says `busy` and asks for the file again;
     * every row already stored comes back a duplicate, so a repeat costs
     * nothing. */
    if (rows == NULL) {
        ESP_LOGW(TAG, "client %d: no memory for a %u B transfer chunk; %u B of heap left", c->id,
                 (unsigned)CLIENT_RX_MAX, (unsigned)heap_caps_get_free_size(MALLOC_CAP_8BIT));
        net_import_refused(&c->import,
                           "the sensor ran out of memory part way through; nothing was lost, "
                           "send it again when fewer devices are connected",
                           true);
        step = NET_IMPORT_DISCARD;
    }

    if (step == NET_IMPORT_SWITCH) {
        /* A second session in the same transfer: close the one in progress and
         * open the next. The running totals carry across so the user is told
         * about the transfer, not about each file in it. */
        store_merge_stats_t part;
        if (store_import_close(c->id, &part)) {
            net_import_fold(&c->import, part.added, part.duplicate, part.rejected, part.no_room,
                            part.fragmented);
        }
        step = NET_IMPORT_OPEN;
    }
    if (step == NET_IMPORT_OPEN) {
        step = open_import(c, name, rows, have_rows) ? NET_IMPORT_CONTINUE : NET_IMPORT_DISCARD;
    }
    const bool storing = step == NET_IMPORT_CONTINUE;

    if (have_rows) {
        const char *p = rows;
        while (*p != '\0') {
            const char *nl = strchr(p, '\n');
            const size_t row_len = nl != NULL ? (size_t)(nl - p) : strlen(p);
            if (row_len > 0) {
                bool placed = false;
                wire_row_t row;
                if (storing && wire_parse_row(p, row_len, &row)) {
                    store_reading_t reading = {
                        .index = row.index,
                        /* Whole seconds: an exported row carries no more than
                         * that. A session the controller already holds keeps its
                         * own millisecond-accurate stamps, because those
                         * readings are duplicates and are never rewritten; only
                         * a session arriving for the first time is stored at
                         * this resolution. */
                        .epoch_ms = row.epoch_s * 1000,
                        .settling = row.settling,
                    };
                    for (int f = 0; f < STORE_FIELDS; f++) {
                        reading.values[f] = row.values.values[f];
                    }
                    /* The verdict is not decoration. Every other outcome is
                     * counted in the merge's own stats and comes back through
                     * ::store_import_close, but ::STORE_MERGE_NOT_OPEN never
                     * reaches the merge - the row is discarded on the way in.
                     * Casting this to `void` is what let a client whose import
                     * was not open be told "ok" with a short `added` count and
                     * no sign of the rows that went nowhere. */
                    placed = store_import_add(c->id, &reading) != STORE_MERGE_NOT_OPEN;
                }
                net_import_row(&c->import, placed);
            }
            if (nl == NULL) {
                break;
            }
            p = nl + 1;
        }
    }

    if (done) {
        (void)finish_import(c, false);
    }
}

/**
 * Handle `erase`: the one command that removes a reading.
 *
 * It refuses unless the device asking can prove it already has the data, and the
 * proof is per session: `name,readings,digest` a line, in the same
 * newline-separated string the rows use. The controller recomputes both figures
 * from its own pages, and a session whose claim does not match keeps everything.
 *
 * `override` skips the proof. That is `HANDOFF.md`'s obscure gesture, and it is
 * a separate field rather than an absent claim list so that no ordinary mistake
 * - an empty backup, a truncated line, a dropped frame - can ever be read as
 * one.
 */
static void handle_erase(client_t *c, const char *line, size_t len)
{
    if (!store_ready()) {
        fill_error(c, "storage is not available on this controller", false);
        return;
    }

    bool override = false;
    (void)wire_json_bool(line, len, "override", &override);

    /* 640 bytes on a 5 KiB task stack, and only ever one erase at a time - it
     * takes the control claim, and there is one holder. */
    store_claim_t claims[STORE_SESSION_INDEX_MAX];
    size_t claim_count = 0;
    char *const text = scratch_take(c);
    if (text == NULL) {
        /* Refusing is the safe direction and the only honest one: the claims
         * cannot be read, and an erase with no claims read is an erase of
         * everything the device could not prove it holds. */
        ESP_LOGW(TAG, "client %d: no memory to read an erase's claims; %u B of heap left", c->id,
                 (unsigned)heap_caps_get_free_size(MALLOC_CAP_8BIT));
        fill_error_ref(c, "the sensor is out of memory and will not erase without checking "
                          "your backup first; try again when fewer devices are connected",
                       false, WIRE_CMD_ERASE);
        return;
    }
    if (wire_json_string(line, len, "backup", text, CLIENT_RX_MAX)) {
        const char *p = text;
        while (*p != '\0' && claim_count < STORE_SESSION_INDEX_MAX) {
            const char *nl = strchr(p, '\n');
            const size_t row_len = nl != NULL ? (size_t)(nl - p) : strlen(p);
            /* name,readings,digest - the digest as hex, because it is a bit
             * pattern rather than a quantity and reading it as one loses the
             * top bit to a sign somewhere along the way. */
            char row[80];
            size_t take = row_len < sizeof(row) - 1 ? row_len : sizeof(row) - 1;
            memcpy(row, p, take);
            row[take] = '\0';
            char *first = strchr(row, ',');
            char *second = first != NULL ? strchr(first + 1, ',') : NULL;
            if (second != NULL) {
                *first = '\0';
                *second = '\0';
                /* A name too long to be a session name is dropped rather than
                 * clipped. A clipped one would match nothing and be counted as
                 * an unconfirmed session, which is the safe direction but the
                 * wrong explanation. */
                if (strlen(row) < sizeof(claims[claim_count].name)) {
                    memcpy(claims[claim_count].name, row, strlen(row) + 1);
                    claims[claim_count].readings = (uint32_t)strtoul(first + 1, NULL, 10);
                    claims[claim_count].digest = (uint32_t)strtoul(second + 1, NULL, 16);
                    claim_count++;
                }
            }
            if (nl == NULL) {
                break;
            }
            p = nl + 1;
        }
    }

    /* The claims are parsed out into `claims` above, so the buffer's work is
     * done and it goes back before the erase itself - which is the slow part,
     * and the part a second client is most likely to want memory during. */
    scratch_release(c);

    store_erase_result_t erased;
    const esp_err_t err = store_erase_confirmed(claims, claim_count, override, &erased);
    if (err != ESP_OK) {
        fill_error(c, erased.message[0] != '\0' ? erased.message : "storage is busy", false);
        return;
    }

    const wire_erase_result_t result = {
        .state = erased.pages_erased > 0 ? "done" : "refused",
        .message = erased.message,
        .sessions_confirmed = erased.sessions_confirmed,
        .sessions_refused = erased.sessions_refused,
        .pages_erased = erased.pages_erased,
        .readings_erased = erased.readings_erased,
        .overridden = override,
    };
    arm(c, wire_erase_result_line(c->out, sizeof(c->out), &result));
}

/**
 * The raw SHDLC console: `{"cmd":"raw","hex":"7e0000030002fd7e"}`.
 *
 * A port of the daemon's `_cmd_raw`, and it exists for the same reason: when a
 * sensor misbehaves, the question is always what it actually put on the wire,
 * and every layer between the person and the UART is a layer that could be the
 * one lying. So the bytes go out verbatim and the reply comes back verbatim -
 * delimiters, stuffing and all - and this function does not try to interpret
 * either.
 *
 * It is behind the control claim like every other command that touches the
 * sensor, because it plainly can change what the sensor is doing; `restart` is
 * the way back from a frame that confused it.
 *
 * The one thing added over the daemon is the bound: ::WIRE_RAW_MAX_BYTES, so a
 * frame cannot outgrow the static buffers or the line it has to be reported in.
 * It is well above anything an SPS30 can say - see the constant's own note.
 */
static void handle_raw(client_t *c, const char *line, size_t len)
{
    /* Room for the hex plus the spaces a person may have typed between bytes. */
    char hex[3 * WIRE_RAW_MAX_BYTES + 2];
    if (!wire_json_string(line, len, "hex", hex, sizeof(hex)) || hex[0] == '\0') {
        /* Also the path an over-long string takes, since it cannot be read into
         * the buffer at all - and "too long" is one of the two things a person
         * needs to hear here, so both are said. */
        fill_error(c,
                   "raw needs a frame as hex and no longer than 128 bytes, "
                   "e.g. 7e0000030002fd7e",
                   false);
        return;
    }

    uint8_t request[WIRE_RAW_MAX_BYTES];
    const int request_len = wire_hex_decode(hex, request, sizeof(request));
    if (request_len <= 0) {
        char message[96];
        snprintf(message, sizeof(message),
                 "that is not a frame this controller can send: hex digits in "
                 "pairs, at most %d bytes", (int)sizeof(request));
        fill_error(c, message, false);
        return;
    }

    uint8_t response[WIRE_RAW_MAX_BYTES];
    size_t response_len = 0;
    const esp_err_t err = acq_raw_transaction(request, (size_t)request_len, response,
                                              sizeof(response), &response_len);
    if (err != ESP_OK) {
        /* Each of these sends the user somewhere different, so none of them is
         * folded into a generic failure. */
        const char *message;
        switch (err) {
        case ESP_ERR_INVALID_STATE:
            message = "no sensor to send a raw frame to";
            break;
        case ESP_ERR_NOT_ALLOWED:
            message = "the sensor is asleep between duty cycles and its serial "
                      "receiver is off; pause or switch to continuous first";
            break;
        case ESP_ERR_INVALID_SIZE:
            message = "the reply did not end within the frame this controller "
                      "can hold";
            break;
        default:
            message = "no reply to that frame";
            break;
        }
        fill_error(c, message, false);
        return;
    }

    /* Echo the request as it went out - decoded and re-encoded - rather than as
     * it was typed, so what is reported is the bytes on the wire and not the
     * spacing somebody used. */
    char request_hex[2 * WIRE_RAW_MAX_BYTES + 1];
    char response_hex[2 * WIRE_RAW_MAX_BYTES + 1];
    wire_hex_encode(request, (size_t)request_len, request_hex, sizeof(request_hex));
    wire_hex_encode(response, response_len, response_hex, sizeof(response_hex));
    arm(c, wire_raw_response_line(c->out, sizeof(c->out), request_hex, response_hex));
}

/**
 * Switch acquisition profile: `{"cmd":"set_profile","profile":{...}}`.
 *
 * The settings are one level down, and the JSON reader deliberately does not
 * descend, so this borrows the inner object with ::wire_json_object and runs the
 * same flat accessors over that.
 *
 * Missing fields are not an error, and they are not "leave the rest alone"
 * either: the object is the whole profile, so what it omits returns to its
 * default. That is `wire.apply_set_profile`, and the Pi is ground truth for it.
 * Merging against the profile in force - which this used to do - meant the same
 * command left the two hosts running different profiles, each believing it had
 * obeyed. `mode` is the exception worth naming: an unrecognised mode string is
 * refused rather than quietly reset, because a client that asked for something
 * this controller does not have should hear so.
 *
 * A field the client did not send is left at zero here, and
 * ::acq_profile_normalize turns zero into that field's default - so the reset is
 * the normalizer's existing behaviour, reached by starting from the defaults
 * instead of from the running profile.
 */
static void handle_set_profile(client_t *c, const char *line, size_t len)
{
    const char *body = NULL;
    size_t body_len = 0;
    if (!wire_json_object(line, len, "profile", &body, &body_len)) {
        fill_error_ref(c, "set_profile needs a profile object", false, WIRE_CMD_SET_PROFILE);
        return;
    }

    acq_profile_t profile = ACQ_DEFAULT_PROFILE();

    char mode[16];
    if (wire_json_string(body, body_len, "mode", mode, sizeof(mode))) {
        if (strcmp(mode, WIRE_MODE_CONTINUOUS) == 0) {
            profile.mode = ACQ_MODE_CONTINUOUS;
        } else if (strcmp(mode, WIRE_MODE_DUTY_CYCLE) == 0) {
            profile.mode = ACQ_MODE_DUTY_CYCLE;
        } else {
            char message[80];
            snprintf(message, sizeof(message), "unknown mode '%s'", mode);
            fill_error_ref(c, message, false, WIRE_CMD_SET_PROFILE);
            return;
        }
    }
    (void)wire_json_double(body, body_len, "interval", &profile.interval_s);
    (void)wire_json_double(body, body_len, "cycle_s", &profile.cycle_s);
    (void)wire_json_double(body, body_len, "warmup_s", &profile.warmup_s);
    (void)wire_json_double(body, body_len, "average_s", &profile.average_s);

    /* Refused here rather than in acquisition, because this is the layer that
     * holds the sensor's identity and can therefore say *which* firmware it
     * found. Without Sleep/Wake a duty cycle would run with the fan spinning
     * through every "sleep", which costs the power the mode exists to save and
     * would look to the user as though low power simply did nothing - the very
     * symptom being fixed. */
    if (profile.mode == ACQ_MODE_DUTY_CYCLE && !acq_supports_sleep()) {
        acq_sensor_info_t info;
        acq_get_sensor_info(&info);
        char message[128];
        if (info.valid) {
            snprintf(message, sizeof(message),
                     "low power needs sensor firmware 2.0 or newer; this one reports %u.%u",
                     info.firmware_major, info.firmware_minor);
        } else {
            snprintf(message, sizeof(message),
                     "low power needs a sensor to ask, and none is answering yet");
        }
        fill_error(c, message, false);
        return;
    }

    const esp_err_t err = acq_set_profile(&profile);
    if (err != ESP_OK) {
        fill_error_ref(c, "acquisition is not accepting commands", false, WIRE_CMD_SET_PROFILE);
        return;
    }

    /* Nothing is broadcast here on purpose. `acq_set_profile` only *queues* the
     * change, so the profile this task can see is still the old one; announcing
     * now would tell every client the setting they just replaced. ::on_profile
     * fires when the state machine has actually adopted it, with the clamped
     * values, and that is what goes out. */
}

/* --- commands ------------------------------------------------------------- */

/**
 * Handle one command line, filling the output buffer if it needs an answer.
 *
 * Called only when the buffer is free, which is why it can write into it
 * without asking. The commands this controller cannot honour answer with an
 * error that names what is missing: a client that asks for a file list and
 * hears nothing cannot tell a controller without storage from a broken one.
 */
static void dispatch(client_t *c, const char *line, size_t len)
{
    char cmd[24];
    if (!wire_json_string(line, len, "cmd", cmd, sizeof(cmd))) {
        return; /* a message with no command: the daemon ignores these too */
    }
    s_stats.commands++;

    if (strcmp(cmd, WIRE_CMD_IDENTIFY) == 0) {
        char name[WIRE_NAME_LEN];
        if (wire_json_string(line, len, "name", name, sizeof(name)) && name[0] != '\0') {
            lock(c->lock);
            snprintf(c->name, sizeof(c->name), "%s", name);
            unlock(c->lock);
            lock(s_shared.lock);
            s_shared.clients_version++;
            unlock(s_shared.lock);
        }
        return;
    }
    if (strcmp(cmd, WIRE_CMD_CLAIM) == 0) {
        claim_control(c);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_RELEASE) == 0) {
        release_control(c);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_LIST_FILES) == 0) {
        /* Absent means the newest page, which is what every client that has
         * never heard of paging asks for and what it has always been given. */
        int64_t offset = 0;
        (void)wire_json_int(line, len, "offset", &offset);
        fill_file_list(c, offset > 0 ? (uint32_t)offset : 0);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_STORAGE) == 0) {
        fill_storage(c);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_STORAGE_MAP) == 0) {
        fill_storage_map(c);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_GET_POWER_ESTIMATE) == 0) {
        fill_power_estimate(c);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_GET_ALERTS) == 0) {
        handle_get_alerts(c, line, len);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_SET_ALERTS) == 0) {
        handle_set_alerts(c, line, len);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_SET_TIME) == 0) {
        /* Not behind the control claim, deliberately. The claim guards what the
         * *sensor* does; this changes nothing about acquisition and cannot alter
         * a reading already written - all it can do is add an equivalence. And
         * the situation it exists for is a board found over Bluetooth by whoever
         * walked up to it, so requiring the lock would mean the one phone able
         * to hand over a clock is the one that first took control from everyone
         * else.
         *
         * Not unchecked, though: `net_time_set` puts a client's time through the
         * same two rules as a time from a server. Being connected is not a
         * reason to be believed. */
        int64_t epoch_ms = 0;
        if (!wire_json_int(line, len, "epoch_ms", &epoch_ms)) {
            fill_error_ref(c, "set_time needs epoch_ms", false, WIRE_CMD_SET_TIME);
            return;
        }
        int64_t offset_min = 0;
        (void)wire_json_int(line, len, "utc_offset_min", &offset_min);
        if (!net_time_set(epoch_ms, (int)offset_min)) {
            fill_error_ref(c, "that time was refused; the controller kept its own clock",
                           false, WIRE_CMD_SET_TIME);
            return;
        }
        /* The equivalence is appended by the clock observer, which is the same
         * path SNTP takes - so a time from a phone and a time from the network
         * are recorded identically, and neither is special-cased here. */
        fill_time_map(c);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_PUT_FILE) == 0) {
        /* Not behind the control claim. The claim guards what the *sensor* does,
         * and an import cannot change a reading, remove one, or touch
         * acquisition - it can only add readings this controller did not have.
         * Requiring control to hand data back would mean a second phone holding
         * the lock could stop the first one restoring an archive. */
        handle_put_file(c, line, len);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_GET_FILE) == 0) {
        char name[WIRE_CSV_NAME_LEN];
        if (!wire_json_string(line, len, "name", name, sizeof(name)) || name[0] == '\0') {
            fill_error_ref(c, "get_file needs a session name", false, WIRE_CMD_GET_FILE);
            return;
        }
        int64_t since = 0;
        (void)wire_json_int(line, len, "since_index", &since);
        start_file_transfer(c, name, since > 0 ? (uint32_t)since : 0);
        return;
    }

    /* `erase` is the exception to the paragraph above: it is the one command
     * that removes data, so it takes the control claim like any other command
     * that changes what the controller is doing. The four `drain_*` commands are
     * the same exception for the same reason - `drain_confirm` is the only other
     * thing in this firmware that frees a reading on purpose, and the three
     * around it are the conversation it belongs to. All four are held to the
     * claim, including the two that free nothing, because a drain another device
     * could interleave with is a drain neither of them can reason about. */
    const bool mutating = strcmp(cmd, WIRE_CMD_ERASE) == 0 ||
                          strcmp(cmd, WIRE_CMD_PAUSE) == 0 ||
                          strcmp(cmd, WIRE_CMD_RESUME) == 0 ||
                          strcmp(cmd, WIRE_CMD_CLEAN) == 0 ||
                          strcmp(cmd, WIRE_CMD_RESTART) == 0 ||
                          strcmp(cmd, WIRE_CMD_RAW) == 0 ||
                          strcmp(cmd, WIRE_CMD_SET_PROFILE) == 0 ||
                          strcmp(cmd, WIRE_CMD_DRAIN_BEGIN) == 0 ||
                          strcmp(cmd, WIRE_CMD_DRAIN_NEXT) == 0 ||
                          strcmp(cmd, WIRE_CMD_DRAIN_CONFIRM) == 0 ||
                          strcmp(cmd, WIRE_CMD_DRAIN_CANCEL) == 0 ||
                          strcmp(cmd, WIRE_CMD_SHUTDOWN) == 0;
    if (mutating) {
        char holder[WIRE_NAME_LEN];
        if (!may_control(c, holder, sizeof(holder))) {
            char message[96];
            snprintf(message, sizeof(message), "%s has control of the sensor",
                     holder[0] != '\0' ? holder : "another client");
            /* Whichever command was refused; the client sent it and can match it. */
            fill_error_ref(c, message, true, cmd);
            return;
        }
    }

    if (strcmp(cmd, WIRE_CMD_PAUSE) == 0) {
        (void)acq_pause();
        return;
    }
    if (strcmp(cmd, WIRE_CMD_RESUME) == 0) {
        (void)acq_resume();
        return;
    }
    if (strcmp(cmd, WIRE_CMD_CLEAN) == 0) {
        (void)acq_clean();
        return;
    }
    if (strcmp(cmd, WIRE_CMD_SET_PROFILE) == 0) {
        handle_set_profile(c, line, len);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_RAW) == 0) {
        handle_raw(c, line, len);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_RESTART) == 0) {
        /* The daemon tears the sensor down and re-probes. The nearest thing the
         * state machine offers is a pause followed by a resume, which stops
         * measurement, re-issues Start from a known idle state and opens a new
         * session - the three effects a client asking for a restart is after.
         * Both commands land in the same queue and are drained together. */
        (void)acq_pause();
        (void)acq_resume();
        return;
    }

    /* Everything below needs something this controller has not got. Saying so
     * beats a silent no-op, which reads to a user as the sensor ignoring them. */
    if (strcmp(cmd, WIRE_CMD_ERASE) == 0) {
        handle_erase(c, line, len);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_DRAIN_BEGIN) == 0) {
        handle_drain_begin(c);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_DRAIN_NEXT) == 0) {
        handle_drain_next(c);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_DRAIN_CONFIRM) == 0) {
        handle_drain_confirm(c, line, len);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_DRAIN_CANCEL) == 0) {
        handle_drain_cancel(c);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_BACKUP) == 0) {
        /* `backup` is the daemon's zip-of-CSVs, which needs a filesystem, a
         * compressor and room for an archive - three things this part does not
         * have. What the captain is asking for when he taps it is every stored
         * session on his device, and that is `list_files` followed by a
         * `get_file` each, which this controller does serve. Saying so names
         * the route rather than refusing the errand. */
        fill_error_ref(c,
                       "ask for the sessions one at a time: this controller streams "
                       "them rather than building an archive",
                       false, WIRE_CMD_BACKUP);
        return;
    }
    if (strcmp(cmd, WIRE_CMD_SHUTDOWN) == 0) {
        fill_error_ref(c, "there is no host to power off; unplug the board", false,
                       WIRE_CMD_SHUTDOWN);
        return;
    }

    char message[64];
    snprintf(message, sizeof(message), "unknown cmd '%s'", cmd);
    fill_error_ref(c, message, false, cmd);
}

/* --- the client task ------------------------------------------------------ */

/**
 * Consume whatever whole lines are buffered.
 *
 * Named `drain_commands` until this file gained a feature that drains the
 * *board*. Two meanings of one verb in one file is how a reader ends up
 * believing the receive buffer frees flash.
 *
 * Bounded by the output buffer, because dispatching is how that buffer is
 * written: a command that answers cannot be handled while an answer is already
 * waiting to go out. A `put_file` chunk answers only when it is marked `done` -
 * including one that could not be served, which is counted and discarded in
 * silence (`net_import.h`) - so a transfer drains many frames per turn and is
 * not rate-limited to one.
 */
static void consume_commands(client_t *c)
{
    while (c->out_len == 0) {
        const size_t line = wire_line_length(c->rx, c->rx_len);
        if (line == 0) {
            break;
        }
        dispatch(c, c->rx, line - 1); /* without the newline */
        memmove(c->rx, c->rx + line, c->rx_len - line);
        c->rx_len -= line;
    }
}

/** @return false when the peer has gone. */
static bool read_link(client_t *c)
{
    char buf[128];
    const int n = c->link->ops->recv(c->link, buf, sizeof(buf));
    if (n == NET_LINK_GONE) {
        return false;
    }
    for (int i = 0; i < n; i++) {
        if (c->rx_len < sizeof(c->rx)) {
            c->rx[c->rx_len++] = buf[i];
            continue;
        }
        /* Over-long line: drop it whole rather than truncate it into something
         * that parses as a different, shorter command. */
        if (!c->rx_overrun) {
            ESP_LOGW(TAG, "client %d sent a line over %d bytes; dropping it", c->id,
                     CLIENT_RX_MAX);
            c->rx_overrun = true;
        }
        if (buf[i] == '\n') {
            c->rx_len = 0;
            c->rx_overrun = false;
        }
    }
    return true;
}

/** @return false when the connection should be torn down. */
static bool write_link(client_t *c)
{
    const int n =
        c->link->ops->send(c->link, out_buf(c) + c->out_sent, c->out_len - c->out_sent);
    if (n == NET_LINK_GONE) {
        return false;
    }
    c->out_sent += (size_t)n;
    if (n > 0) {
        c->out_since_us = now_us(); /* progress; the stall clock restarts */
    }
    if (c->out_sent >= c->out_len) {
        c->out_len = 0;
        c->out_sent = 0;
        out_release(c); /* the last byte is gone; the buffer is not ours to keep */
    }
    return true;
}

/** Choose the next line to send. Snapshots first: they are the smaller debt. */
static void fill_next(client_t *c)
{
    uint32_t state_version, control_version, clients_version, profile_version;
    uint32_t time_map_version;
    lock(s_shared.lock);
    state_version = s_shared.state_version;
    control_version = s_shared.control_version;
    clients_version = s_shared.clients_version;
    profile_version = s_shared.profile_version;
    time_map_version = s_shared.time_map_version;
    unlock(s_shared.lock);

    if (c->seen_control != control_version && fill_control(c)) {
        return;
    }
    if (c->seen_clients != clients_version && fill_clients(c)) {
        return;
    }
    /* Ahead of the state change it causes: a switch to low power emits both, and
     * a client that saw `warmup` before it saw the profile would have a second
     * in which the mode says continuous and the state says something continuous
     * mode never enters. */
    if (c->seen_profile != profile_version && fill_profile(c)) {
        return;
    }
    if (c->seen_state != state_version && fill_state(c)) {
        return;
    }
    /* Above the readings, because it is at most a line every six minutes and a
     * queue of readings can be a second deep for a long time; below the state,
     * because a fan clean shows up as a state change first and the figure that
     * explains it should not arrive before it. */
    if (clean_due_changed(c) && fill_power(c)) {
        return;
    }
    /* Readings wait while the session is still deciding what to record against,
     * because a line with neither a timestamp nor a relative clock is one the app
     * can do nothing with. Once the session has settled they go out either way:
     * with an instant if there was a clock, and with the board's own clock if
     * there was not. It is the *undecided* state that must not be streamed, not
     * the clockless one - that was the bug this closes. */
    if (net_time_valid() || (s_base.decided && s_base.relative)) {
        if (fill_reading(c)) {
            return;
        }
    }
    /* Above the readings a client is about to have to convert. */
    if (c->seen_time_map != time_map_version && fill_time_map(c)) {
        return;
    }
    /* Above the transfer, below the live stream: a phone that has just been told
     * the store is filling should hear it before it starts a long backfill, and
     * a message a second is not what "near full" needs saying with. */
    if (storage_changed(c) && fill_storage(c)) {
        return;
    }
    if (alerts_changed(c) && fill_alerts(c)) {
        return;
    }
    /* Above the backfill and below everything live. A backfill is two days of
     * readings the client can ask for again at any time; the preserved alert
     * data is the part that cannot be fetched by any other route once the ring
     * has moved past it, so it does not queue behind the long transfer. */
    if (fill_alert_data(c)) {
        return;
    }
    /* Above the ordinary backfill, below everything live, and for a sharper
     * reason than the preserved data above it: a block that is not sent is a
     * block that is not confirmed and therefore not freed, so a drain queued
     * behind a two-day transfer would leave the board full for the length of
     * it. A backfill can be asked for again at any time; this one is holding
     * space that acquisition is about to want. */
    if (fill_drain_data(c)) {
        return;
    }
    /* A transfer is the lowest priority deliberately. Live readings, state and
     * the client roster are what someone is looking at; a backfill of two days
     * is what they are waiting for, and it uses whatever the live stream leaves
     * spare rather than competing with it. */
    (void)fill_file_data(c);
}

static void close_client(client_t *c)
{
    const UBaseType_t headroom = uxTaskGetStackHighWaterMark(NULL);
    ESP_LOGI(TAG, "client %d (%s) gone; queue dropped %u readings, stack headroom %u B", c->id,
             c->link->transport, (unsigned)acq_queue_dropped(&c->queue), (unsigned)headroom);

    if (s_stats.min_stack_free == 0 || headroom < s_stats.min_stack_free) {
        s_stats.min_stack_free = (uint32_t)headroom;
    }

    /*
     * A line the client never accepted is a line nobody was ever told about.
     *
     * It matters most for the one message a whole transfer is waiting on: an
     * `import_result` armed into a socket the phone stops reading is a send
     * that, at the far end, simply never ends. Said here, with what was left,
     * because this is the last moment at which anything knows.
     */
    if (c->out_len > 0) {
        ESP_LOGW(TAG, "client %d went with %u of %u bytes of a line unsent", c->id,
                 (unsigned)(c->out_len - c->out_sent), (unsigned)c->out_len);
    }
    if (c->import.in_flight) {
        ESP_LOGW(TAG,
                 "client %d went mid-import of %s; %" PRIu32 " added, %" PRIu32
                 " already held so far, and it was never answered",
                 c->id, c->import.name, c->import.added, c->import.duplicate);
    }

    /* An import belonging to this client ends with it - sealing what it had
     * already accepted rather than discarding it, because those readings are
     * additive and were missing from this controller a moment ago. */
    store_import_abandon(c->id);
    /*
     * And a back-up-and-clear belonging to this client ends with it, freeing
     * nothing on the way out.
     *
     * This is the disconnect the whole design is arranged around: the link drops
     * mid-block, or the app is killed, or the phone walks out of range. Whatever
     * had been confirmed is on the device and gone from the board; whatever had
     * only been *sent* is still exactly where it was, and the next drain offers
     * it again. There is nothing to roll back because there is nothing in
     * flight - see `store_drain.h`.
     */
    c->drain_sending = false;
    c->drain_offered = false;
    store_drain_close(c->id);
    /* And everything this client had borrowed goes back: the 2.7 KiB
     * preserved-segment walk, the 3.0 KiB backfill cursor and the 2 KiB chunk
     * buffer. Nothing of a delivery is lost by stopping here - the watermark is
     * this client's own and the segments are still on flash for the next
     * connection - and this is the path a client that drops mid-transfer takes,
     * so it is the one that decides whether the heap comes back. */
    alerts_release_cursor(c);
    end_file_transfer(c);
    scratch_release(c);
    out_release(c);

    release_control(c);
    /* Last use of the link: after this the transport may reuse the storage, so
     * nothing below may touch it. */
    c->link->ops->close(c->link);

    lock(s_registry_lock);
    c->in_use = false;
    c->link = NULL;
    s_stats.clients_now--;
    /* Hand the slot back. Everything that walks the registry skips a NULL
     * slot, and the walkers all hold this lock, so nothing can be looking at
     * `c` once the pointer is cleared. */
    for (size_t i = 0; i < WIRE_MAX_CLIENTS; i++) {
        if (s_clients[i] == c) {
            s_clients[i] = NULL;
            break;
        }
    }
    unlock(s_registry_lock);

    vSemaphoreDelete(c->lock);
    free(c);

    lock(s_shared.lock);
    s_shared.clients_version++;
    unlock(s_shared.lock);
}

static void client_task(void *arg)
{
    client_t *c = (client_t *)arg;

    ESP_LOGI(TAG, "client %d connected over %s from %s (%u of %u)", c->id, c->link->transport,
             c->link->peer, (unsigned)s_stats.clients_now, (unsigned)WIRE_MAX_CLIENTS);
    /* Everyone learns about the new arrival, including the arrival itself. */
    lock(s_shared.lock);
    s_shared.clients_version++;
    unlock(s_shared.lock);

    if (!fill_hello(c)) {
        ESP_LOGE(TAG, "client %d: hello did not fit in %d bytes", c->id, WIRE_LINE_MAX);
        close_client(c);
        vTaskDelete(NULL);
        return;
    }

    /* When this task last actually gave up the core. See ::CLIENT_YIELD_EVERY_MS. */
    int64_t last_block_us = now_us();

    for (;;) {
        /*
         * Only ask for more when there is somewhere to put it.
         *
         * `consume_commands` can only dispatch while the output buffer is free,
         * because dispatching is how the output buffer gets written. A client
         * streaming commands faster than that - which is exactly what a bulk
         * `put_file` is - used to keep being read anyway, and the read dropped
         * whole lines once `rx` filled. Silently: the transfer looked like it
         * had been delivered and rows had simply gone.
         *
         * Not reading is the signal. On TCP it closes the receive window and
         * stops the sender at the far end, which is the honest mechanism and
         * the one the app's blocking send is written to expect. On Bluetooth
         * there is no window, so the link refuses the GATT write instead and
         * the central sees the failure - see `gatt_access` in components/ble.
         * Either way nothing is dropped without somebody being told; the
         * transfer just runs at the speed the controller can take it.
         */
        const bool room_to_read = wire_line_length(c->rx, c->rx_len) == 0;

        /*
         * A transfer with rows still to send is waiting on room in the socket,
         * not on the clock, so say so: a link with room comes back from `wait`
         * at once and a full one blocks there until it drains, which is the
         * backpressure this loop wants anyway.
         *
         * Asking only about `out_len` meant that between frames - the moment
         * one has been handed to the socket and the next has not been built -
         * a backfill waited on readability alone, and an app that has sent its
         * `get_file` and is now only listening never makes the link readable.
         * So every frame cost a full ::LINK_WAIT_MS. At the thirteen readings a
         * 2 KiB frame holds that is about 120 a second whatever the network can
         * do, which is twelve minutes for a day of 1 Hz recording and half an
         * hour for a full ring - the "it fills in slowly" the captain saw.
         *
         * The Bluetooth path already had its half of this: see
         * BLE_GAP_EVENT_NOTIFY_TX in components/ble, which wakes this task when
         * a notification leaves "so a 640-byte line is not paced by the 100 ms
         * poll". TCP never got the equivalent.
         */
        const bool want_write =
            c->out_len > 0 || (c->xfer_active && !c->import.in_flight);

        /* The only place this task sleeps. It wakes on arriving bytes, on room
         * to write when the link can tell the two apart, or on the timeout.
         *
         * Skipped entirely when neither flag is set, which means a complete
         * command is already in `rx` and nothing is waiting to go out: that
         * work needs no event from the link, and sleeping on one is 100 ms
         * spent not dispatching it. `consume_commands` below always consumes at
         * least one such line, so this cannot spin. */
        const int64_t before_wait_us = now_us();
        if (room_to_read || want_write) {
            c->link->ops->wait(c->link, room_to_read, want_write, LINK_WAIT_MS);
        }

        /* ::CLIENT_YIELD_EVERY_MS carries the argument. `wait` above is allowed
         * to return at once and usually does during a transfer, so "it sleeps
         * in `wait`" is not something this loop may assume - the clock is what
         * decides, and it is reset by any wait that really did block so an idle
         * client pays nothing for this. */
        const int64_t after_wait_us = now_us();
        /* A millisecond is the discriminator: a `select` with room returns in
         * tens of microseconds, and one that really waited cannot come back
         * inside a scheduler tick. Erring towards "it did not block" only costs
         * an extra yield. */
        if (after_wait_us - before_wait_us >= 1000) {
            last_block_us = after_wait_us;
        } else if (after_wait_us - last_block_us >= (int64_t)CLIENT_YIELD_EVERY_MS * 1000) {
            vTaskDelay(1);
            last_block_us = now_us();
        }

        if (room_to_read && !read_link(c)) {
            break;
        }

        if (c->out_len > 0 && !write_link(c)) {
            break;
        }

        /* A peer that has gone away while we were not reading from it shows up
         * as a failed write, not a failed read, so the stall check below is what
         * reaps it. Nothing to do here. */

        /* A backlogged client is given the length of its own queue to catch up.
         * Past that it is not slow, it is absent, and it is holding one of eight
         * slots plus a task while dropping every reading anyway. */
        if (c->out_len > 0 && now_us() - c->out_since_us > (int64_t)CLIENT_STALL_MS * 1000) {
            ESP_LOGW(TAG, "client %d has not accepted a line in %d s; disconnecting it",
                     c->id, CLIENT_STALL_MS / 1000);
            s_stats.stalled_out++;
            break;
        }

        consume_commands(c);

        /* A transfer that has gone quiet is closed out and answered, rather
         * than left holding the store's import claim until the connection
         * drops. Every other device is refused while it is held, so a phone
         * that stops half way would otherwise lock the controller against the
         * rest of the house without anybody being told. See
         * ::NET_IMPORT_IDLE_MS for why the bound sits where it does. */
        if (c->out_len == 0 &&
            net_import_stale(&c->import, now_us(), (int64_t)NET_IMPORT_IDLE_MS * 1000)) {
            ESP_LOGW(TAG, "client %d went quiet mid-import; closing it out", c->id);
            (void)finish_import(c, true);
        }

        /*
         * Nothing is volunteered to a client that is uploading.
         *
         * This is the other half of the wedge in `net_import.h`. The reply
         * buffer is one line deep and a client cannot be read while a line is
         * waiting in it, so *anything* put there during a transfer - a reading,
         * a roster, a storage warning - stops the controller reading the moment
         * the far end stops accepting it. A client in the middle of a blocking
         * upload is by definition not reading, so the two starve each other and
         * a transfer that was working simply stops.
         *
         * Skipping the live stream for the length of a transfer costs that
         * client the readings taken during it, which is what a bounded
         * drop-oldest queue already promises a consumer that is not keeping up
         * (`acq_queue.h`). Nothing is lost: the controller records them itself,
         * and the app asks for what it is missing as soon as the transfer ends.
         */
        if (c->out_len == 0 && !c->import.in_flight) {
            fill_next(c);
        }
        if (c->out_len > 0) {
            /* Try immediately rather than waiting for the next wait: at 1 Hz the
             * link is normally ready and this keeps latency at zero. */
            if (!write_link(c)) {
                break;
            }
        }
    }

    close_client(c);
    vTaskDelete(NULL);
}

/* --- accepting ------------------------------------------------------------ */

static void configure_socket(int sock)
{
    int one = 1;
    (void)setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    (void)setsockopt(sock, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));
    int value = KEEPALIVE_IDLE_S;
    (void)setsockopt(sock, IPPROTO_TCP, TCP_KEEPIDLE, &value, sizeof(value));
    value = KEEPALIVE_INTERVAL_S;
    (void)setsockopt(sock, IPPROTO_TCP, TCP_KEEPINTVL, &value, sizeof(value));
    value = KEEPALIVE_COUNT;
    (void)setsockopt(sock, IPPROTO_TCP, TCP_KEEPCNT, &value, sizeof(value));

    /* Non-blocking, because ::net_link_ops_t promises that recv and send never
     * block and that a client task sleeps only in `wait`. Under the old
     * select-then-act loop a blocking socket was safe; under a link that is
     * woken for either direction it is not, and a send that parked would defeat
     * the stall detector as well. */
    const int flags = fcntl(sock, F_GETFL, 0);
    (void)fcntl(sock, F_SETFL, (flags < 0 ? 0 : flags) | O_NONBLOCK);
}

static int open_listener(uint16_t port)
{
    const int sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock < 0) {
        ESP_LOGE(TAG, "socket() failed: errno %d", errno);
        return -1;
    }
    int one = 1;
    (void)setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

    struct sockaddr_in addr = {
        .sin_family = AF_INET,
        .sin_port = htons(port),
        .sin_addr.s_addr = htonl(INADDR_ANY),
    };
    if (bind(sock, (struct sockaddr *)&addr, sizeof(addr)) != 0 ||
        listen(sock, WIRE_MAX_CLIENTS) != 0) {
        ESP_LOGE(TAG, "bind/listen on %u failed: errno %d", (unsigned)port, errno);
        close(sock);
        return -1;
    }

    /* So accept() returns periodically and the task can log, rather than
     * sitting in the kernel with no way to notice anything else. */
    struct timeval timeout = {.tv_sec = 1, .tv_usec = 0};
    (void)setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    ESP_LOGI(TAG, "listening on port %u for up to %u clients", (unsigned)port,
             (unsigned)WIRE_MAX_CLIENTS);
    return sock;
}

/**
 * Take a free slot, allocating it, or NULL if there is none to be had.
 *
 * Two different failures, and the caller is told which: every slot taken, or
 * not enough memory for another one. They mean different things to whoever is
 * holding the phone, so ::net_server_attach reports them differently.
 */
static client_t *take_slot(net_link_t *link, bool *out_of_memory)
{
    client_t *chosen = NULL;
    size_t index = WIRE_MAX_CLIENTS;

    *out_of_memory = false;

    /*
     * Can the heap afford this before it is spent, not after.
     *
     * `heap_caps_calloc` succeeding is not the same question: it says this slot
     * fits, not that the radio underneath it will still be able to get a frame
     * buffer once the connection is carrying traffic. Six clients fitted, and
     * the Wi-Fi driver then failed an allocation and dropped the association,
     * taking all six down together. See `net_budget.h` for the measurement.
     */
    if (!net_budget_admits(heap_caps_get_free_size(MALLOC_CAP_8BIT), net_server_client_bytes(),
                           NET_HEAP_RESERVE)) {
        *out_of_memory = true;
        return NULL;
    }

    lock(s_registry_lock);
    for (size_t i = 0; i < WIRE_MAX_CLIENTS; i++) {
        if (s_clients[i] != NULL) {
            continue;
        }
        index = i;
        break;
    }
    if (index == WIRE_MAX_CLIENTS) {
        unlock(s_registry_lock);
        return NULL; /* genuinely at the cap */
    }

    /* Claimed while the registry is held, so two accepts cannot pick the same
     * index; the allocation itself happens after, because it is the slow part
     * and nothing else needs the lock to do it. */
    chosen = heap_caps_calloc(1, sizeof(client_t), MALLOC_CAP_8BIT);
    if (chosen != NULL) {
        chosen->lock = xSemaphoreCreateMutex();
        if (chosen->lock == NULL) {
            free(chosen);
            chosen = NULL;
        }
    }
    if (chosen == NULL) {
        unlock(s_registry_lock);
        *out_of_memory = true;
        return NULL;
    }
    s_clients[index] = chosen;

    {
        chosen->in_use = true;
        chosen->link = link;
        chosen->id = ++s_next_client_id;
        out_release(chosen); /* a reused slot must not inherit a borrowed buffer */
        chosen->out_len = 0;
        chosen->out_sent = 0;
        chosen->rx_len = 0;
        chosen->rx_overrun = false;
        chosen->seen_state = 0;
        chosen->seen_control = 0;
        chosen->seen_clients = 0;
        /* Not zero, unlike its neighbours: `hello` carries the profile, so a
         * `profile` line straight after it would say the same thing twice. What
         * this client needs is the *next* change, and it is up to date until
         * then. Read without `s_shared.lock`, which is held nowhere else in this
         * function and is not worth nesting inside the registry lock for a
         * word-sized load: losing the race costs one redundant line. */
        chosen->seen_profile = s_shared.profile_version;
        /*
         * Per-connection state has to be cleared here, not merely on close: a
         * client that drops mid-transfer never runs its own teardown, and the
         * next connection inherits the slot. For the import counters that would
         * mean a fresh transfer reporting a previous one's totals - numbers the
         * user is being asked to trust about their own data.
         *
         * `told_storage` is reset for the same reason in the other direction:
         * inheriting it would leave a new phone never told the store is filling,
         * which is the one message that triggers an automatic export.
         *
         * The outbound half is the same defect pointing the other way: a client
         * that vanishes mid-`get_file` leaves its cursor live, and the next
         * arrival is sent `file_data` for a session it never asked for, which
         * the app folds into its history as though it had. Both halves are
         * cleared here, in one place, because they are one bug.
         */
        net_import_reset(&chosen->import);
        chosen->told_storage = false;
        chosen->told_storage_warn = false;
        chosen->told_clean = false;
        chosen->told_clean_hours = 0.0;
        chosen->xfer_active = false;
        chosen->xfer_rows = 0;
        chosen->xfer_name[0] = '\0';
        /* Nothing borrowed yet. `calloc` has already done this; it is written
         * out because the three borrowed buffers are the difference between a
         * slot that costs 6.4 KB and one that costs 11.4, and a slot that
         * inherited a stale pointer would free memory it does not own. */
        chosen->xfer = NULL;
        chosen->import_scratch = NULL;
        /*
         * True, and that is the feature: preserved alert data goes to a device
         * on connection **by default**. A client that does not want it says so
         * with `set_alerts`; one that says nothing - including every client
         * built before any of this existed - is sent it.
         */
        chosen->alerts_auto = true;
        chosen->told_alerts = false;
        chosen->told_alerts_segments = 0;
        chosen->told_alerts_rate_ms = 0;
        chosen->alerts_since_seq = 0;
        chosen->alerts_seq = 0;
        chosen->alerts_header_sent = false;
        chosen->alerts_rows = 0;
        chosen->alerts_name[0] = '\0';
        chosen->alerts_cursor = NULL;
        acq_queue_init(&chosen->queue, chosen->slots, ACQ_CLIENT_QUEUE_MAX);
        /* Until it identifies itself, name it by its id - better than "unknown"
         * when working out who is holding the control lock. */
        snprintf(chosen->name, sizeof(chosen->name), "client %d", chosen->id);
        s_stats.clients_now++;
        s_stats.clients_total++;
    }
    unlock(s_registry_lock);
    return chosen;
}

/* --- the socket as a link ------------------------------------------------- */

/**
 * One accepted socket, wearing the interface the protocol talks to.
 *
 * Static and exactly as many as there are client slots, for the reason the
 * client array gives: a connection must not be able to fail on a fragmented
 * heap. The pool is guarded by ::s_registry_lock because the accept task takes
 * an entry and a client task gives it back.
 */
typedef struct {
    net_link_t base;
    bool in_use;
    int sock;
} tcp_link_t;

static tcp_link_t s_tcp_links[WIRE_MAX_CLIENTS];

static tcp_link_t *as_tcp(net_link_t *link)
{
    return (tcp_link_t *)link;
}

static int tcp_recv(net_link_t *link, void *buf, size_t cap)
{
    const int n = recv(as_tcp(link)->sock, buf, cap, 0);
    if (n == 0) {
        return NET_LINK_GONE; /* orderly close */
    }
    if (n < 0) {
        return (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) ? 0 : NET_LINK_GONE;
    }
    return n;
}

static int tcp_send(net_link_t *link, const void *buf, size_t len)
{
    const int n = send(as_tcp(link)->sock, buf, len, 0);
    if (n < 0) {
        return (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) ? 0 : NET_LINK_GONE;
    }
    return n;
}

static void tcp_wait(net_link_t *link, bool for_read, bool for_write, uint32_t timeout_ms)
{
    const int sock = as_tcp(link)->sock;
    fd_set readable, writable;
    FD_ZERO(&readable);
    FD_ZERO(&writable);
    if (for_read) {
        FD_SET(sock, &readable);
    }
    if (for_write) {
        FD_SET(sock, &writable);
    }
    struct timeval timeout = {
        .tv_sec = (time_t)(timeout_ms / 1000),
        .tv_usec = (suseconds_t)((timeout_ms % 1000) * 1000),
    };
    (void)select(sock + 1, for_read ? &readable : NULL, for_write ? &writable : NULL, NULL,
                 &timeout);
}

static void tcp_close(net_link_t *link)
{
    tcp_link_t *t = as_tcp(link);
    close(t->sock);
    lock(s_registry_lock);
    t->sock = -1;
    t->in_use = false;
    unlock(s_registry_lock);
}

static const net_link_ops_t TCP_LINK_OPS = {
    .recv = tcp_recv,
    .send = tcp_send,
    .wait = tcp_wait,
    .close = tcp_close,
};

/** Wrap an accepted socket. NULL when the pool is exhausted. */
static tcp_link_t *tcp_link_take(int sock, const struct sockaddr_in *peer)
{
    tcp_link_t *chosen = NULL;
    lock(s_registry_lock);
    for (size_t i = 0; i < WIRE_MAX_CLIENTS; i++) {
        if (s_tcp_links[i].in_use) {
            continue;
        }
        chosen = &s_tcp_links[i];
        chosen->in_use = true;
        chosen->sock = sock;
        chosen->base.ops = &TCP_LINK_OPS;
        chosen->base.transport = "tcp";
        break;
    }
    unlock(s_registry_lock);

    if (chosen != NULL) {
        char ip[16];
        inet_ntoa_r(peer->sin_addr, ip, sizeof(ip));
        snprintf(chosen->base.peer, sizeof(chosen->base.peer), "%s:%u", ip,
                 (unsigned)ntohs(peer->sin_port));
    }
    return chosen;
}

/** Hand a link back without ever having served it. */
static void tcp_link_release(tcp_link_t *t)
{
    lock(s_registry_lock);
    t->sock = -1;
    t->in_use = false;
    unlock(s_registry_lock);
}

/**
 * Turn away an over-cap connection the way the daemon does, with a reason.
 *
 * The close is not just `close()`, and the difference is not cosmetic. Closing
 * a socket that still has unread inbound data makes TCP send an RST, and an RST
 * discards whatever is still in the send buffer - so the client sees
 * "connection reset by peer" instead of the message explaining why. A client
 * that identifies itself the instant it connects, which the app does, hits that
 * race every time. `shutdown` then drain then close is what actually delivers
 * the refusal: the FIN carries the queued bytes, and the drain clears the
 * inbound data that would otherwise turn the close into a reset.
 *
 * Verified: without the drain, nine simultaneous connections gave the ninth an
 * ECONNRESET and no message at all.
 *
 * The drain is driven by `select` rather than by `SO_RCVTIMEO` because the
 * socket may already be non-blocking by the time we get here, and a
 * non-blocking `recv` ignores the receive timeout - it would return EAGAIN at
 * once and drain nothing, quietly reintroducing the reset this exists to avoid.
 */
static void refuse(int sock)
{
    char line[WIRE_LINE_MAX];
    const int n = net_server_refusal_line(line, sizeof(line));
    if (n > 0) {
        (void)send(sock, line, (size_t)n, 0);
    }

    (void)shutdown(sock, SHUT_WR);
    const int64_t until_us = now_us() + 250000;
    for (;;) {
        const int64_t left_us = until_us - now_us();
        if (left_us <= 0) {
            break;
        }
        fd_set readable;
        FD_ZERO(&readable);
        FD_SET(sock, &readable);
        struct timeval timeout = {
            .tv_sec = (time_t)(left_us / 1000000),
            .tv_usec = (suseconds_t)(left_us % 1000000),
        };
        if (select(sock + 1, &readable, NULL, NULL, &timeout) <= 0) {
            break;
        }
        char discard[64];
        if (recv(sock, discard, sizeof(discard), 0) <= 0) {
            break;
        }
    }
    close(sock);

    /* Which of the two, because they are not the same problem and the log is
     * where somebody looks first. Saying "already serving 8 clients" while
     * serving four sent an afternoon looking for a leak that was not there:
     * the board had the slots and not the heap, and only the error line sent to
     * the client - which nothing logs - said so. See
     * ::net_server_refusal_line, which has always drawn the distinction. */
    if (s_last_refusal == REFUSED_NO_MEMORY) {
        ESP_LOGW(TAG, "refused a connection: out of memory, not out of slots (%u B free)",
                 (unsigned)heap_caps_get_free_size(MALLOC_CAP_8BIT));
    } else {
        ESP_LOGW(TAG, "refused a connection: already serving %u clients",
                 (unsigned)WIRE_MAX_CLIENTS);
    }
}

static void accept_task(void *arg)
{
    (void)arg;
    int listener = -1;

    for (;;) {
        if (listener < 0) {
            listener = open_listener(s_config.port);
            if (listener < 0) {
                vTaskDelay(pdMS_TO_TICKS(2000));
                continue;
            }
        }

        struct sockaddr_in peer;
        socklen_t peer_len = sizeof(peer);
        const int sock = accept(listener, (struct sockaddr *)&peer, &peer_len);
        if (sock < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
                continue; /* the one-second timeout; nothing is wrong */
            }
            ESP_LOGW(TAG, "accept failed: errno %d; reopening the listener", errno);
            close(listener);
            listener = -1;
            continue;
        }

        configure_socket(sock);
        tcp_link_t *t = tcp_link_take(sock, &peer);
        if (t == NULL) {
            /* The pool is the same size as the client cap, so this and the
             * attach failure below are the same condition seen twice. */
            s_stats.refused++;
            s_last_refusal = REFUSED_AT_CAPACITY;
            refuse(sock);
            continue;
        }
        if (!net_server_attach(&t->base)) {
            tcp_link_release(t);
            refuse(sock);
        }
    }
}

/* --- attaching any transport ---------------------------------------------- */

bool net_server_attach(net_link_t *link)
{
    if (link == NULL || s_registry_lock == NULL) {
        return false;
    }

    bool out_of_memory = false;
    client_t *c = take_slot(link, &out_of_memory);
    if (c == NULL) {
        s_stats.refused++;
        s_last_refusal = out_of_memory ? REFUSED_NO_MEMORY : REFUSED_AT_CAPACITY;
        if (out_of_memory) {
            ESP_LOGE(TAG, "no memory for another client slot (%u B); %u B of heap left",
                     (unsigned)sizeof(client_t),
                     (unsigned)heap_caps_get_free_size(MALLOC_CAP_8BIT));
        }
        return false;
    }

    if (xTaskCreate(client_task, "sps30_client", s_config.client_stack_bytes, c,
                    s_config.client_priority, &c->task) != pdPASS) {
        /* The slot fitted but its task's stack did not. Same cause as above and
         * the same thing to tell the client, so say so rather than blaming a
         * capacity that is not the problem. */
        ESP_LOGE(TAG, "no memory for a task for client %d over %s; %u B of heap left", c->id,
                 link->transport, (unsigned)heap_caps_get_free_size(MALLOC_CAP_8BIT));
        s_stats.refused++;
        s_last_refusal = REFUSED_NO_MEMORY;
        lock(s_registry_lock);
        c->in_use = false;
        c->link = NULL;
        s_stats.clients_now--;
        for (size_t i = 0; i < WIRE_MAX_CLIENTS; i++) {
            if (s_clients[i] == c) {
                s_clients[i] = NULL;
                break;
            }
        }
        unlock(s_registry_lock);
        vSemaphoreDelete(c->lock);
        free(c);
        return false;
    }
    return true;
}

int net_server_refusal_line(char *out, size_t cap)
{
    char message[96];
    if (s_last_refusal == REFUSED_NO_MEMORY) {
        /* Not `at_capacity`: the app offers to wait for a slot when it sees
         * that flag, and there is no slot coming. This is the controller being
         * out of memory, and saying "already serving 8 clients" while serving
         * none sent whoever read the log looking in the wrong place entirely. */
        snprintf(message, sizeof(message),
                 "the sensor is out of memory and cannot take another connection");
        /* No `ref`: this answers the connection, not a command. */
        return wire_error_line(out, cap, message, false, false, NULL);
    }
    snprintf(message, sizeof(message), "sensor is already serving %u clients",
             (unsigned)WIRE_MAX_CLIENTS);
    return wire_error_line(out, cap, message, false, true, NULL);
}

/* --- lifecycle ------------------------------------------------------------ */

esp_err_t net_server_start(const net_server_config_t *config)
{
    s_config = config != NULL ? *config : NET_SERVER_DEFAULT_CONFIG();

    s_registry_lock = xSemaphoreCreateMutex();
    s_shared.lock = xSemaphoreCreateMutex();
    if (s_registry_lock == NULL || s_shared.lock == NULL) {
        return ESP_ERR_NO_MEM;
    }
    for (size_t i = 0; i < WIRE_MAX_CLIENTS; i++) {
        s_clients[i] = NULL;
        s_tcp_links[i].sock = -1;
    }
    /* Both halves of the cost. The stack is the larger one now and was never
     * printed, which is half of why `WIRE_MAX_CLIENTS` looked like the bound
     * that mattered. What the heap makes of it is said by `main` rather than
     * here, because this runs before the store and acquisition have taken
     * theirs - see ::net_server_client_bytes. */
    ESP_LOGI(TAG,
             "a client costs %u B (a %u B slot, a %u B stack and ~%u B of lwIP), claimed "
             "when one connects and freed when it goes",
             (unsigned)net_server_client_bytes(), (unsigned)sizeof(client_t),
             (unsigned)s_config.client_stack_bytes, (unsigned)NET_CLIENT_HEAP_OVERHEAD);
    ESP_LOGI(TAG, "%u B of heap is kept back for the radio and the store, whatever is asked",
             (unsigned)NET_HEAP_RESERVE);

    /* Version 1 against a client's 0, so the first thing every connection is
     * told after `hello` is the current state, control, roster and time map. */
    s_shared.state_version = 1;
    s_shared.control_version = 1;
    s_shared.clients_version = 1;
    s_shared.profile_version = 1;
    s_shared.time_map_version = 1;

    /* Every time the clock is learned - from SNTP or from a connected phone -
     * an equivalence is appended and everyone is told. `store_add_anchor`
     * declines when there is nothing recorded relative to anchor, which is the
     * ordinary case on a board that has always had a network. */
    net_time_observe(on_clock_learned);
    snprintf(s_shared.state, sizeof(s_shared.state), "%s", acq_state_str(acq_state()));
    snprintf(s_shared.detail, sizeof(s_shared.detail), "%s", "starting");

    const acq_observer_t observer = {
        .on_reading = on_reading,
        .on_state = on_state,
        .on_profile = on_profile,
        .ctx = NULL,
    };
    const esp_err_t err = acq_add_observer(&observer);
    if (err != ESP_OK) {
        return err;
    }

    if (xTaskCreate(accept_task, "sps30_accept", 3072, NULL, s_config.accept_priority, NULL) !=
        pdPASS) {
        return ESP_ERR_NO_MEM;
    }
    return ESP_OK;
}

size_t net_server_client_bytes(void)
{
    /* Including the lwIP the socket has not taken yet. Charging only what is
     * spent at accept time is what let the board promise five and deliver four;
     * see ::NET_CLIENT_HEAP_OVERHEAD. */
    return sizeof(client_t) + s_config.client_stack_bytes + NET_CLIENT_HEAP_OVERHEAD;
}

void net_server_get_stats(net_server_stats_t *out)
{
    if (out != NULL) {
        *out = s_stats;
    }
}
