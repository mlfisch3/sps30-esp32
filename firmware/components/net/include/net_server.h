/**
 * @file net_server.h
 * @brief The JSONL-over-TCP server: the seam `acq` was built for, filled in.
 *
 * `acq.h` describes the shape this had to take before either existed - "register
 * an observer that copies into an ::acq_queue_t and drains it from its own task"
 * - and this is that, with nothing added to `acq` to make it fit.
 *
 * ## The one rule
 *
 * **A client can never stall acquisition.** Everything else here follows from
 * it. Readings are handed over by a copy into a bounded drop-oldest queue, so
 * the acquisition task's whole involvement in the network is a `memcpy` under an
 * uncontended mutex. Each client then serialises and sends from its own task,
 * where a full TCP window, a phone that has walked out of range, or a reader
 * that has simply stopped reading costs that client its backlog and nobody else
 * anything at all.
 *
 * ## The protocol is not new
 *
 * `wire.py` on the Raspberry Pi defines it and the Android app already speaks
 * it, so this implements it rather than negotiating it: same port, same message
 * types, same field names, same `(csv, index)` de-duplication key, same
 * eight-client cap, same control-claim arbitration. The app needs no change to
 * talk to this board, which is the entire point.
 *
 * ## Storage is served, not apologised for
 *
 * This controller used to answer `list_files` with an empty list and `get_file`
 * with "this controller has no stored sessions; the phone is the record". That
 * was true of a firmware that stored nothing, and it is the reason a gap in the
 * plot could never be filled: a phone that had been away had no way to ask for
 * what it missed. `store.h` now keeps the readings on flash and this serves
 * them - `list_files` names what is held, `get_file` streams a session's rows
 * past whatever index the client already has.
 *
 * `list_files` is **paged**, because a frame is finite and what the board holds
 * is not: it names what fits and says where the rest begin (`wire.file_list_page`
 * and ::FILE_LIST_MAX). A board holding 84 sessions used to name 64 of them and
 * the other twenty could not be fetched, plotted or erased, because a name is
 * the only handle any of those has. Carrying more per frame is the fix that
 * already failed twice on the session table; a client that walks the pages is
 * the one with no number in it.
 *
 * `backup` still answers with an error, because it asks the *controller* to
 * build a zip of CSVs, and that needs a filesystem and a compressor this part
 * has not got. The error names the route that does work rather than refusing the
 * errand - and the app now takes that route: it collects the sessions with
 * `list_files` and `get_file` and writes the zip itself, on the device where
 * there is somewhere to put it.
 *
 * ## The transfer half
 *
 * Three commands above `wire.py`, because a controller whose entire archive is a
 * flash partition needs them and a Raspberry Pi with a filesystem never did.
 *
 * - **`put_file`** takes a session back. Rows in, a chunk at a time, in the
 *   daemon's own column order. It **adds only**: the controller keeps every
 *   reading it already has, counts the incoming copy as a duplicate, and writes
 *   nothing over anything. `store_merge.h` carries the argument for why that is
 *   a property of the code rather than a promise about it. It does not take the
 *   control claim - it cannot change what the sensor is doing, and a second
 *   phone holding the lock should not be able to stop the first restoring an
 *   archive.
 * - **`storage`** says how full the ring is. Sent unasked when it crosses
 *   ::WIRE_STORE_WARN_FRACTION, which is the trigger for the phone to copy
 *   everything off - so that data leaves before there is ever pressure to
 *   delete it.
 * - **`erase`** is the only command here that removes a reading, so it is the
 *   only one of the three behind the control claim, and it refuses unless the
 *   asking device can prove it already holds the data. Proof is a per-session
 *   row count and digest that the controller recomputes from its own pages;
 *   anything that does not match keeps every page it has.
 *
 * ## What is still open on the wire
 *
 * Nothing here is authenticated. The listener is on `0.0.0.0:8730` and any
 * device on the LAN can connect, which was already true of `pause`, `clean` and
 * `restart` - and `erase` is a good deal worse to hand out. It is gated on the
 * control claim and on proof of a backup, which is protection against accident
 * rather than against anybody, and the override skips even that. The per-device
 * token is Phase 4 work (`HANDOFF.md` item 14); until it lands this belongs on a
 * network the owner trusts.
 *
 * ## TCP is one transport, not the only one
 *
 * Everything above is about the protocol; none of it is about sockets. The
 * socket lives behind ::net_link_t, and ::net_server_attach takes any link and
 * runs the full protocol over it. `components/ble` uses that to serve the
 * identical JSONL over a Nordic UART Service, which is why Bluetooth needed no
 * second implementation of `hello`, the control lock or the command vocabulary.
 *
 * The sections above meet here, and the meeting is the point: because `dispatch`
 * is written once, **everything storage added is answered over Bluetooth too** -
 * `list_files`, `get_file`, `put_file`, `storage`, `erase` - with nothing
 * transport-specific to add. That is what makes collecting a board that has
 * never had a network possible at all; `BLUETOOTH_DESIGN.md` explains why no
 * per-transport command filter exists.
 *
 * The one place the two genuinely differ is inbound flow control, because TCP
 * has a receive window and Bluetooth has not. Both refuse rather than drop -
 * see the client loop and `gatt_access` in `components/ble`.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"
#include "net_link.h"
#include "wire.h"

#ifdef __cplusplus
extern "C" {
#endif

/** @brief Tuning. ::NET_SERVER_DEFAULT_CONFIG is what the firmware ships. */
typedef struct {
    uint16_t port;
    /**
     * Below the acquisition task's priority, deliberately. If the two ever
     * contend, reading the sensor on time is what matters; a client waiting an
     * extra scheduling slice for its line is invisible at 1 Hz.
     */
    int client_priority;
    int accept_priority;
    /**
     * Per-client stack. Measured, not guessed: serialising a reading calls
     * `snprintf` with ten doubles, and newlib's float formatting is the hungriest
     * thing on this path. The firmware logs the high-water mark when a client
     * disconnects, so this number stays checkable.
     *
     * Raised from 5120 when `file_list` grew to name every session the store
     * holds rather than the first twelve. That list is built in three arrays on
     * this stack - about 134 bytes a session, so roughly 2.1 KB at sixteen - and
     * naming them all is what stops a backup silently missing the oldest one.
     */
    uint32_t client_stack_bytes;
} net_server_config_t;

#define NET_SERVER_DEFAULT_CONFIG()                                            \
    ((net_server_config_t){                                                    \
        .port = WIRE_TCP_PORT,                                                 \
        .client_priority = 4,                                                  \
        .accept_priority = 4,                                                  \
        .client_stack_bytes = 7168,                                            \
    })

/** @brief Counters for judging whether a run was healthy. */
typedef struct {
    uint32_t clients_now;       /**< connected right now, over every transport */
    uint32_t clients_total;     /**< accepted since boot */
    uint32_t refused;           /**< turned away at ::WIRE_MAX_CLIENTS */
    uint32_t readings_sent;     /**< reading lines written to a socket */
    uint32_t readings_dropped;  /**< readings evicted from a full client queue */
    uint32_t stalled_out;       /**< clients closed for not draining */
    uint32_t commands;          /**< client commands dispatched */
    uint32_t min_stack_free;    /**< smallest client-task headroom seen, bytes */
} net_server_stats_t;

/**
 * @brief Register the acquisition observer and start the accept task.
 *
 * Must be called **before** `acq_start`, because `acq_add_observer` is
 * deliberately not safe to call once acquisition is running. It does not need
 * Wi-Fi to be up: the listener binds to the wildcard address, which survives the
 * interface coming and going underneath it.
 *
 * @param config NULL for ::NET_SERVER_DEFAULT_CONFIG.
 */
esp_err_t net_server_start(const net_server_config_t *config);

/**
 * @brief What one connected client costs the heap: its slot plus its stack.
 *
 * Both halves, because the stack is the larger of the two and printing only the
 * slot is part of why ::WIRE_MAX_CLIENTS looked like the bound that mattered.
 * It is not: that constant is sized against lwIP's socket limit, and the heap
 * runs out first. Divide the free heap by this to get the number of connections
 * the board can actually hold, which is what `main` logs at boot and what the
 * refused client is told about.
 *
 * Valid only after ::net_server_start, which is where the stack size is fixed.
 */
size_t net_server_client_bytes(void);

/** @brief Copy out the counters. @p out must not be NULL. */
void net_server_get_stats(net_server_stats_t *out);

/**
 * @brief Run the protocol over @p link, on a task of its own.
 *
 * The transport-neutral half of accepting a connection: take a client slot,
 * start its task, and from then on the link is served exactly as a socket is -
 * same `hello`, same snapshots, same commands, same control arbitration. Used by
 * the TCP accept task here and by `components/ble` when a phone subscribes to
 * the Nordic UART TX characteristic.
 *
 * @p link must stay valid until its `close` operation is called, which the
 * client task does exactly once, on its way out. Ownership of the storage never
 * moves.
 *
 * @return false at ::WIRE_MAX_CLIENTS, or if the task could not be started. The
 *         caller still owns the link and should refuse the peer with
 *         ::net_server_refusal_line and then close it.
 */
bool net_server_attach(net_link_t *link);

/**
 * @brief Write the `at_capacity` refusal the daemon sends an over-cap client.
 *
 * Shared rather than duplicated, so the two transports refuse in the same words.
 * How the refusal is delivered is not shared: TCP has to shut down and drain
 * before closing or the message is lost to an RST (see `refuse`), and Bluetooth
 * does not.
 *
 * @return bytes written, or -1 if @p cap is too small.
 */
int net_server_refusal_line(char *out, size_t cap);

#ifdef __cplusplus
}
#endif
