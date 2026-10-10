/**
 * @file net_import.h
 * @brief One `put_file` transfer, and the single answer it is owed.
 *
 * A transfer is many frames and one question: *what happened to my readings?*
 * This holds the running answer to that question from the first chunk to the
 * last, decides what each chunk's rows are for, and builds the one
 * `import_result` that ends it.
 *
 * ### Why this is a module and not four fields in `client_t`
 *
 * Because getting it wrong wedges the board, and the wedge cannot be seen from
 * the outside. The controller's reply buffer is **one line deep**, and a client
 * cannot be read while a line is waiting to go out - that coupling is
 * deliberate (it is the backpressure that stops a bulk transfer overrunning the
 * inbound buffer, see `net_server.c`). So a `put_file` chunk that answers is a
 * chunk that stops the controller reading until the answer has been accepted.
 *
 * A refusal repeated per chunk therefore does not refuse anything. It fills the
 * far end's receive buffer with thousands of identical lines, and when that
 * buffer closes, the controller stops reading, the client stops sending, and
 * both sit there until something times out. Measured on the board on
 * 2026-08-03: two simultaneous imports, three runs, neither client ever got a
 * result and both blocked in `sendall`. The claim itself was sound - nothing
 * crossed sessions, nothing was corrupted - the *refusal* was the defect.
 *
 * So the rule this module exists to hold:
 *
 * > **A transfer produces exactly one message: its result. A chunk that cannot
 * > be served is counted and discarded in silence, and the reason is carried to
 * > the end and said once.**
 *
 * Pure C over its own struct: no ESP-IDF, no allocation, no locking, no clock
 * of its own. That is what lets the host tests compile this exact translation
 * unit (`firmware/test/test_net_import.c`) even though `net_server.c` around it
 * is FreeRTOS and lwIP and cannot be built off the board.
 *
 * @note Not thread-safe, by the same convention as `acq_queue.h`. Each client
 *       task owns its own transfer and nothing else touches it.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "wire.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief How long a transfer may go quiet before it is closed out and answered.
 *
 * Bounded from both sides rather than chosen freely.
 *
 * Below it: frames of a transfer arrive back to back. The app sends eight rows
 * a frame over a link that carries kilobytes a second, so the gap between two
 * frames of a live transfer is milliseconds. Fifteen seconds is three orders of
 * magnitude of headroom, which covers a phone that stalls on its own storage
 * without ever covering a phone that is still sending.
 *
 * Above it: `CLIENT_STALL_MS` is 30 s, and a transfer that outlived it would be
 * answered by having its connection dropped - silence again, which is the whole
 * failure this file is about. Half of it leaves the answer time to be built and
 * sent while the client is still there to hear it.
 *
 * The other thing this bound protects is the *store claim*: an importer that
 * stops half way holds the claim, and every other device is refused until it
 * lets go. Closing the transfer out releases it.
 */
#define NET_IMPORT_IDLE_MS 15000

/**
 * Longest `import_result` message this composes.
 *
 * The longest a branch of ::net_import_answer can produce is a borrowed reason
 * of about seventy characters, three 32-bit counts spelled out, and - when the
 * controller had already answered part of the same file - the clause that says
 * so. Measured against the real strings rather than estimated: 202 bytes at the
 * worst, and this is that with room.
 */
#define NET_IMPORT_MESSAGE_MAX 224

/**
 * @brief What to do with the chunk just read.
 *
 * ::NET_IMPORT_OPEN and ::NET_IMPORT_SWITCH are requests to the caller, which
 * owns the store; both may fail, and the caller says so with
 * ::net_import_refused or ::net_import_skipped rather than by returning.
 */
typedef enum {
    NET_IMPORT_OPEN = 0, /**< nothing is open: claim the store for this name */
    NET_IMPORT_SWITCH,   /**< a different session: close the open one, then claim this */
    NET_IMPORT_CONTINUE, /**< the open session is this chunk's: merge its rows */
    NET_IMPORT_DISCARD,  /**< nothing will be stored: count the rows and drop them */
} net_import_step_t;

/**
 * @brief The state of one client's transfer.
 *
 * Zeroed means "no transfer". ::net_import_answer returns it to that state, so
 * a client never has to remember to reset it.
 */
typedef struct {
    /** Between the first chunk and the answer. Nothing is volunteered to a
     *  client while this is set; see `net_server.c`'s client loop. */
    bool in_flight;
    /** Nothing more will be stored under any name. Latched for the whole
     *  transfer: see ::net_import_refused for why it is not retried. */
    bool refused;
    /** The refusal is somebody else's turn rather than a fault, so the answer
     *  says `busy` and asks for the file again. */
    bool try_again;
    /** The store is open on ::name. False both before it has been claimed and
     *  after it has been skipped, which is why one name and this flag replace
     *  the three names an earlier draft of this kept. */
    bool open;
    bool no_room;    /**< the ring filled during the merge */
    bool fragmented; /**< a session could not be merged safely */

    /**
     * This transfer is the remainder of one the controller closed out itself.
     *
     * Set by ::net_import_answer on a `cut_short` answer and **survives the
     * reset that answer performs**, which is the one field that does. It is how
     * the two ends stop disagreeing about when a transfer finished: the
     * controller ends a quiet transfer after ::NET_IMPORT_IDLE_MS and answers
     * it, but the sender does not know that and keeps sending, so the frames
     * that follow open a *second* transfer with its own counters. Its answer
     * used to read as the whole account of the file - "0 added, 900 already
     * here" for an archive of a hundred thousand readings - and there was
     * nothing anywhere to say the rest had been answered separately.
     *
     * Cleared by the next answer, so a transfer that is closed out twice says
     * so once per answer and never latches for the life of the connection.
     */
    bool resumed;

    /**
     * Why nothing (or nothing more) was taken, in the words the user gets.
     *
     * **Borrowed, not copied**: every reason is a string literal in
     * `net_server.c`, the same convention ::wire_import_result_t uses for its
     * own `state`. A caller that passes anything with a shorter life than the
     * transfer will be read after it is gone. The alternative was 96 bytes of
     * buffer in a struct that is allocated per connection on a part with 512 KB
     * of SRAM, on a board that has been seen refusing a fourth client for want
     * of heap.
     */
    const char *reason;
    /** The session the last chunk named. */
    char name[WIRE_CSV_NAME_LEN];

    uint32_t added;
    uint32_t duplicate;
    uint32_t rejected;
    uint32_t rows;     /**< every row seen, placed or not */
    uint32_t sessions; /**< sessions actually opened in the store */

    /** When the last chunk arrived, in the caller's clock. */
    int64_t last_chunk_us;
} net_import_t;

/** @brief The one message a transfer ends with, owning all of its own storage. */
typedef struct {
    char name[WIRE_CSV_NAME_LEN];
    char message[NET_IMPORT_MESSAGE_MAX];
    /** `ok`, `full`, `busy` or `error`. A string literal, never freed. */
    const char *state;
    uint32_t added;
    uint32_t duplicate;
    uint32_t rejected;
    uint32_t sessions;
    /**
     * These counts cover a remainder, not a whole file.
     *
     * Said in ::message too, which is what the user reads; this is what the
     * controller's own log reads, so a board log shows a file answered in two
     * parts as two parts rather than as two unrelated transfers.
     */
    bool resumed;
} net_import_answer_t;

/** @brief Forget any transfer in progress, without answering it. */
void net_import_reset(net_import_t *t);

/**
 * @brief Read one chunk's header and say what it is.
 *
 * Marks the transfer in flight and restarts its idle clock, so this must be
 * called for **every** chunk - including one that will only be discarded.
 *
 * @param name the session the chunk names.
 * @param now_us the caller's monotonic clock.
 */
net_import_step_t net_import_chunk(net_import_t *t, const char *name, int64_t now_us);

/** @brief The store accepted the claim for the session this chunk named. */
void net_import_opened(net_import_t *t);

/**
 * @brief Nothing in this transfer can be stored, and that is settled.
 *
 * The case this file was written for is @p try_again true: another device holds
 * the store's import claim. It is latched for the **whole** transfer rather
 * than retried at the next session, because retrying would let a multi-session
 * archive land half in and half out depending on when the other device happened
 * to finish, and the user asked one question about one transfer. Told once, at
 * the end, they can send the same file again - every row already there comes
 * back a duplicate, so a repeat costs nothing.
 *
 * @p try_again false is for a controller that cannot serve the transfer at all
 * (no storage; a frame with no session name): the answer says `error`, because
 * sending it again would do the same thing.
 *
 * @p reason is borrowed and must outlive the transfer; see ::net_import_t.
 */
void net_import_refused(net_import_t *t, const char *reason, bool try_again);

/**
 * @brief This one session cannot be taken, and the rest of the transfer can.
 *
 * For a name the controller cannot place, or a session too fragmented to merge
 * into safely. Its rows are counted as rejected and dropped; a chunk naming a
 * *different* session still opens normally, so one bad file in an archive does
 * not cost the others.
 *
 * @p reason is borrowed and must outlive the transfer; see ::net_import_t.
 */
void net_import_skipped(net_import_t *t, const char *reason);

/** @brief Count one row. @p placed is false when it went nowhere. */
void net_import_row(net_import_t *t, bool placed);

/** @brief Fold a closed session's merge stats into the transfer's totals. */
void net_import_fold(net_import_t *t, uint32_t added, uint32_t duplicate, uint32_t rejected,
                     bool no_room, bool fragmented);

/**
 * @brief True when a transfer has been silent for @p idle_us.
 *
 * False when nothing is in flight, so this is safe to ask every turn of the
 * client loop.
 */
bool net_import_stale(const net_import_t *t, int64_t now_us, int64_t idle_us);

/**
 * @brief Build the transfer's one answer and return @p t to its resting state.
 *
 * @param cut_short true when this is being ended by the controller rather than
 *                  by a chunk marked `done` - a stale transfer, say. It changes
 *                  what the message says, never what the counts are, and it
 *                  leaves ::net_import_t::resumed set so that whatever the
 *                  sender goes on to send is answered as a remainder rather
 *                  than as a whole file.
 * @return false when nothing was in flight, in which case @p out is untouched
 *         and no message should be sent.
 */
bool net_import_answer(net_import_t *t, bool cut_short, net_import_answer_t *out);

#ifdef __cplusplus
}
#endif
