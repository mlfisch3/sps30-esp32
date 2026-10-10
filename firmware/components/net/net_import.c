/**
 * @file net_import.c
 * @brief The transfer bookkeeping described in net_import.h.
 *
 * Nothing here reaches the store, the socket or the clock: every one of those
 * is the caller's, which is what keeps this compilable on the host.
 */
#include "net_import.h"

#include <stdio.h>
#include <string.h>

static void copy(char *dst, size_t cap, const char *src)
{
    if (src == NULL) {
        dst[0] = '\0';
        return;
    }
    snprintf(dst, cap, "%s", src);
}

void net_import_reset(net_import_t *t)
{
    memset(t, 0, sizeof(*t));
}

net_import_step_t net_import_chunk(net_import_t *t, const char *name, int64_t now_us)
{
    t->in_flight = true;
    t->last_chunk_us = now_us;

    if (name == NULL || name[0] == '\0') {
        return NET_IMPORT_DISCARD;
    }
    /* Refused for the transfer: nothing more is stored under any name. */
    if (t->refused) {
        return NET_IMPORT_DISCARD;
    }
    if (t->name[0] != '\0' && strcmp(t->name, name) == 0) {
        /* The same session as the last chunk. Whether its rows are stored or
         * dropped was settled when it was first seen: a session that could not
         * be placed is skipped for as long as its chunks keep arriving. */
        return t->open ? NET_IMPORT_CONTINUE : NET_IMPORT_DISCARD;
    }
    /* A different session. If one was open it has to be closed first, and
     * either way this one is offered to the store - which is what keeps one
     * unplaceable file in an archive from costing the rest. */
    const bool was_open = t->open;
    copy(t->name, sizeof(t->name), name);
    t->open = false;
    return was_open ? NET_IMPORT_SWITCH : NET_IMPORT_OPEN;
}

void net_import_opened(net_import_t *t)
{
    t->open = true;
    t->sessions++;
}

void net_import_refused(net_import_t *t, const char *reason, bool try_again)
{
    if (!t->refused) {
        t->refused = true;
        t->try_again = try_again;
        t->reason = reason;
    }
    t->open = false;
}

void net_import_skipped(net_import_t *t, const char *reason)
{
    t->open = false;
    if (t->reason == NULL) {
        t->reason = reason;
    }
}

void net_import_row(net_import_t *t, bool placed)
{
    t->rows++;
    if (!placed) {
        t->rejected++;
    }
}

void net_import_fold(net_import_t *t, uint32_t added, uint32_t duplicate, uint32_t rejected,
                     bool no_room, bool fragmented)
{
    t->added += added;
    t->duplicate += duplicate;
    t->rejected += rejected;
    t->no_room = t->no_room || no_room;
    t->fragmented = t->fragmented || fragmented;
}

bool net_import_stale(const net_import_t *t, int64_t now_us, int64_t idle_us)
{
    return t->in_flight && now_us - t->last_chunk_us > idle_us;
}

bool net_import_answer(net_import_t *t, bool cut_short, net_import_answer_t *out)
{
    if (!t->in_flight) {
        return false;
    }

    memset(out, 0, sizeof(*out));
    /* The last session named by a chunk, so the answer says which transfer it
     * belongs to even when nothing was ever opened. */
    copy(out->name, sizeof(out->name), t->name);
    out->added = t->added;
    out->duplicate = t->duplicate;
    out->rejected = t->rejected;
    out->sessions = t->sessions;
    out->resumed = t->resumed;

    /* Said in every branch rather than in one of them, because the reader of
     * any of these numbers needs to know they are not the whole file. See
     * ::net_import_t::resumed. */
    const char *const part = t->resumed
                                 ? "; an earlier part of this file was answered separately"
                                 : "";

    if (t->refused) {
        /* The one this whole file exists for. `busy` rather than `error` when
         * it can be retried: nothing is wrong with what was sent, it arrived at
         * a controller that was already taking somebody else's, and sending it
         * again is the cure. */
        if (t->try_again) {
            out->state = "busy";
            snprintf(out->message, sizeof(out->message),
                     "%s, so %u readings were not taken - send the file again in a moment%s",
                     t->reason, (unsigned)out->rejected, part);
        } else {
            out->state = "error";
            snprintf(out->message, sizeof(out->message), "%s, so %u readings were not taken%s",
                     t->reason, (unsigned)out->rejected, part);
        }
    } else if (t->no_room) {
        /* Two causes, one honest sentence. The ring may genuinely have no free
         * slot, or it may be past the line above which its remaining slots are
         * reserved for recording (see `import_may_take_a_slot` in store.c).
         * Either way what is true, and what the user needs, is the same: the
         * readings it already held are still there, nothing was removed, and
         * the way to make room is to copy off and erase rather than to resend. */
        out->state = "full";
        snprintf(out->message, sizeof(out->message),
                 "the controller kept %u readings and had no room for %u more; nothing was "
                 "removed to make space, and what room is left is held for recording%s",
                 (unsigned)(out->added + out->duplicate), (unsigned)out->rejected, part);
    } else if (cut_short) {
        /* Closed out by the controller, not by the sender. Whatever had been
         * accepted is sealed and counted; what never arrived is simply absent,
         * and saying so is the difference between this and silence. */
        out->state = "error";
        snprintf(out->message, sizeof(out->message),
                 "sending stopped part way and was closed after %u seconds; "
                 "%u added, %u already here, %u not placed%s",
                 (unsigned)(NET_IMPORT_IDLE_MS / 1000), (unsigned)out->added,
                 (unsigned)out->duplicate, (unsigned)out->rejected, part);
    } else if (t->fragmented || t->reason != NULL) {
        out->state = "error";
        snprintf(out->message, sizeof(out->message),
                 "%s; %u added, %u already here, %u not placed%s",
                 t->reason != NULL ? t->reason
                                   : "a session was too fragmented to finish merging",
                 (unsigned)out->added, (unsigned)out->duplicate, (unsigned)out->rejected, part);
    } else if (out->rejected > 0) {
        /* Said out loud rather than left to the `rejected` field. A short
         * `added` count with a cheerful message is how rows go missing without
         * anyone noticing, which is the failure this whole path exists to
         * prevent. */
        out->state = "ok";
        snprintf(out->message, sizeof(out->message), "%u added, %u already here, %u not placed%s",
                 (unsigned)out->added, (unsigned)out->duplicate, (unsigned)out->rejected, part);
    } else {
        out->state = "ok";
        snprintf(out->message, sizeof(out->message), "%u added, %u already here%s",
                 (unsigned)out->added, (unsigned)out->duplicate, part);
    }

    /* The one thing that outlives the answer. A transfer the controller ended
     * itself is one the sender is still sending, so whatever arrives next is
     * the remainder of the same file and its answer has to say so - see
     * ::net_import_t::resumed. Set after the reset because the reset is what
     * clears it, which is how "answered as a whole file" is the resting state
     * and being a remainder is the exception that has to be earned. */
    net_import_reset(t);
    t->resumed = cut_short;
    return true;
}
