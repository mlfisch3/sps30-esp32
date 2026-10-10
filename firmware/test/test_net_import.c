/**
 * @file test_net_import.c
 * @brief Host tests for the rule that a `put_file` transfer answers once.
 *
 * The defect these exist for was found on the board on 2026-08-03 and is
 * written up in `net_import.h`: two devices importing at the same moment, and
 * *neither* of them ever got a result. The claim on the store was sound - the
 * one importer that finished filed its rows correctly and nothing crossed
 * sessions - so what failed was the refusal. It was issued once per chunk, the
 * far end never read any of them, and once its receive buffer closed the
 * controller stopped reading, the sender stopped sending, and both sides sat
 * there until they timed out.
 *
 * That is not a thing a hardware test can see the inside of, and `net_server.c`
 * around it is FreeRTOS and lwIP and does not compile here. So the rule itself
 * was lifted into a module that does, and this drives it directly:
 *
 *   * a refused transfer produces **no** message until its last chunk, and then
 *     exactly one, saying `busy`, with every row counted as not placed;
 *   * a refusal is latched, so it cannot flicker back to accepting half way
 *     through an archive;
 *   * one bad session in an archive costs that session and no other;
 *   * a transfer that stops half way is answered too, rather than holding the
 *     store's claim in silence;
 *   * a transfer that runs out of heap says `busy` and keeps what it had
 *     already stored, because the buffer it decodes into is borrowed and a
 *     borrow can fail;
 *   * an ordinary transfer still ends with one `ok`.
 *
 * The board test `tests/test_board_slow.py::test_two_simultaneous_imports_never
 * _cross_sessions` is the other half of this and stays where it is: it is what
 * proves the two ends actually agree.
 *
 * Builds and runs on the host with no ESP-IDF: see the Makefile.
 */

#include <stdio.h>
#include <string.h>

#include "net_import.h"

static int g_checks = 0;
static int g_failures = 0;

#define CHECK(cond, ...)                                                       \
    do {                                                                       \
        g_checks++;                                                            \
        if (!(cond)) {                                                         \
            g_failures++;                                                      \
            printf("  FAIL %s:%d: ", __FILE__, __LINE__);                      \
            printf(__VA_ARGS__);                                               \
            printf("\n");                                                      \
        }                                                                      \
    } while (0)

static const char *const SESSION_A = "SPS30-20260803_200110.csv";
static const char *const SESSION_B = "SPS30-20260802_083000.csv";

/** A clock the tests move by hand, in the microseconds the caller supplies. */
static int64_t g_now_us;

/** Feed one chunk of @p rows rows under @p name, and report what it asked for. */
static net_import_step_t chunk(net_import_t *t, const char *name)
{
    g_now_us += 1000; /* frames arrive a millisecond apart, as they do */
    return net_import_chunk(t, name, g_now_us);
}

/** Count @p n rows of a chunk the caller has already decided the fate of. */
static void rows(net_import_t *t, unsigned n, bool placed)
{
    for (unsigned i = 0; i < n; i++) {
        net_import_row(t, placed);
    }
}

/**
 * The defect, stated as a test.
 *
 * Six hundred chunks arrive while another device holds the claim. Not one of
 * them may produce a message: on the board every one of those messages was a
 * line in a one-line-deep buffer that the controller could not read past.
 */
static void test_a_refused_transfer_says_nothing_until_it_is_done(void)
{
    net_import_t t;
    net_import_reset(&t);

    net_import_answer_t answer;
    for (int i = 0; i < 600; i++) {
        const net_import_step_t step = chunk(&t, SESSION_A);
        if (i == 0) {
            CHECK(step == NET_IMPORT_OPEN, "the first chunk should ask to open, got %d", step);
            net_import_refused(&t, "another device was already importing", true);
        } else {
            CHECK(step == NET_IMPORT_DISCARD, "chunk %d should be discarded, got %d", i, step);
        }
        rows(&t, 8, false);

        /* The whole point: nothing may be sent here. `net_import_answer` is the
         * only thing that produces a message, and it is only ever called for a
         * chunk marked `done`. */
        CHECK(t.in_flight, "chunk %d left no transfer in flight", i);
    }

    CHECK(net_import_answer(&t, false, &answer), "the last chunk produced no answer at all");
    CHECK(strcmp(answer.state, "busy") == 0, "state is '%s', expected 'busy'", answer.state);
    CHECK(answer.added == 0, "%u rows were added by a transfer that was refused", answer.added);
    CHECK(answer.duplicate == 0, "%u duplicates from a transfer that never reached the store",
          answer.duplicate);
    CHECK(answer.rejected == 600 * 8, "%u rows accounted for, expected %u", answer.rejected,
          600u * 8u);
    CHECK(answer.sessions == 0, "%u sessions opened by a refused transfer", answer.sessions);
    CHECK(strcmp(answer.name, SESSION_A) == 0, "the answer names '%s'", answer.name);
    CHECK(strstr(answer.message, "another device") != NULL,
          "the message does not say who had the controller: '%s'", answer.message);
    CHECK(strstr(answer.message, "again") != NULL,
          "the message does not say the file can be sent again: '%s'", answer.message);

    /* And the transfer is over, so the next chunk starts a fresh one. */
    CHECK(!t.in_flight, "answering left the transfer in flight");
    CHECK(!net_import_answer(&t, false, &answer), "a second answer was produced for one transfer");
}

/**
 * The refusal is latched, not re-decided.
 *
 * A multi-session archive names a new session part way through. If the claim
 * were retried there, the archive would land half in and half out depending on
 * when the other device happened to finish - and the user asked one question.
 */
static void test_a_refusal_covers_the_whole_transfer(void)
{
    net_import_t t;
    net_import_reset(&t);

    CHECK(chunk(&t, SESSION_A) == NET_IMPORT_OPEN, "the first chunk should ask to open");
    net_import_refused(&t, "another device was already importing", true);
    rows(&t, 8, false);

    CHECK(chunk(&t, SESSION_B) == NET_IMPORT_DISCARD,
          "a new session name re-opened the claim inside a refused transfer");
    rows(&t, 8, false);
    CHECK(chunk(&t, SESSION_A) == NET_IMPORT_DISCARD, "and back again");
    rows(&t, 8, false);

    net_import_answer_t answer;
    CHECK(net_import_answer(&t, false, &answer), "no answer");
    CHECK(strcmp(answer.state, "busy") == 0, "state is '%s', expected 'busy'", answer.state);
    CHECK(answer.rejected == 24, "%u rows not placed, expected 24", answer.rejected);
    CHECK(answer.sessions == 0, "%u sessions opened", answer.sessions);
}

/**
 * One bad file in an archive costs that file and nothing else.
 *
 * A clockless session cannot be placed by name and never will be, so it is
 * skipped rather than latched - and the sessions around it still merge.
 */
static void test_a_session_that_cannot_be_placed_does_not_stop_the_rest(void)
{
    net_import_t t;
    net_import_reset(&t);

    CHECK(chunk(&t, "SPS30-run1196-rel.csv") == NET_IMPORT_OPEN, "should ask to open");
    net_import_skipped(&t,
                       "this controller cannot yet take back a session recorded without a clock");
    rows(&t, 8, false);
    CHECK(chunk(&t, "SPS30-run1196-rel.csv") == NET_IMPORT_DISCARD,
          "the skipped session asked to open a second time");
    rows(&t, 8, false);

    /* A different name is a different question. */
    CHECK(chunk(&t, SESSION_A) == NET_IMPORT_OPEN,
          "a session after a skipped one was not offered to the store");
    net_import_opened(&t);
    CHECK(chunk(&t, SESSION_A) == NET_IMPORT_CONTINUE, "the open session should continue");
    rows(&t, 16, true);
    net_import_fold(&t, 4, 12, 0, false, false);

    net_import_answer_t answer;
    CHECK(net_import_answer(&t, false, &answer), "no answer");
    CHECK(strcmp(answer.state, "error") == 0, "state is '%s', expected 'error'", answer.state);
    CHECK(answer.added == 4, "%u added, expected the 4 that the good session merged",
          answer.added);
    CHECK(answer.duplicate == 12, "%u duplicates, expected 12", answer.duplicate);
    CHECK(answer.rejected == 16, "%u rejected, expected the 16 rows of the skipped session",
          answer.rejected);
    CHECK(answer.sessions == 1, "%u sessions opened, expected 1", answer.sessions);
    CHECK(strstr(answer.message, "without a clock") != NULL,
          "the message does not say why the session was skipped: '%s'", answer.message);
}

/**
 * A transfer that stops half way is answered, and lets go.
 *
 * Silence here is expensive twice over: the sender waits for a result that is
 * never coming, and every other device on the network is refused for as long as
 * the claim is held.
 */
static void test_a_transfer_that_goes_quiet_is_answered(void)
{
    net_import_t t;
    net_import_reset(&t);

    CHECK(!net_import_stale(&t, g_now_us, (int64_t)NET_IMPORT_IDLE_MS * 1000),
          "an idle client with no transfer was called stale");

    CHECK(chunk(&t, SESSION_A) == NET_IMPORT_OPEN, "should ask to open");
    net_import_opened(&t);
    rows(&t, 8, true);
    net_import_fold(&t, 8, 0, 0, false, false);

    const int64_t idle_us = (int64_t)NET_IMPORT_IDLE_MS * 1000;
    CHECK(!net_import_stale(&t, g_now_us + idle_us - 1, idle_us),
          "a transfer was called stale a microsecond early");
    CHECK(net_import_stale(&t, g_now_us + idle_us + 1, idle_us),
          "a transfer silent for the whole window was not called stale");

    net_import_answer_t answer;
    CHECK(net_import_answer(&t, true, &answer), "a stale transfer produced no answer");
    CHECK(strcmp(answer.state, "error") == 0, "state is '%s', expected 'error'", answer.state);
    CHECK(answer.added == 8, "%u added, expected the 8 that were sealed", answer.added);
    CHECK(strstr(answer.message, "stopped part way") != NULL,
          "the message does not say the transfer was cut short: '%s'", answer.message);

    CHECK(!net_import_stale(&t, g_now_us + idle_us * 100, idle_us),
          "an answered transfer is still being called stale, so it would be answered again");
}

/** The ordinary case, unchanged: one transfer, two sessions, one `ok`. */
static void test_an_ordinary_transfer_still_ends_with_one_ok(void)
{
    net_import_t t;
    net_import_reset(&t);

    CHECK(chunk(&t, SESSION_A) == NET_IMPORT_OPEN, "should ask to open");
    net_import_opened(&t);
    rows(&t, 24, true);
    CHECK(chunk(&t, SESSION_A) == NET_IMPORT_CONTINUE, "same session, same claim");
    rows(&t, 24, true);

    CHECK(chunk(&t, SESSION_B) == NET_IMPORT_SWITCH, "a new session should ask for a switch");
    net_import_fold(&t, 0, 48, 0, false, false); /* what SESSION_A's merge reported */
    net_import_opened(&t);
    rows(&t, 10, true);
    net_import_fold(&t, 10, 0, 0, false, false);

    net_import_answer_t answer;
    CHECK(net_import_answer(&t, false, &answer), "no answer");
    CHECK(strcmp(answer.state, "ok") == 0, "state is '%s', expected 'ok'", answer.state);
    CHECK(answer.added == 10, "%u added, expected 10", answer.added);
    CHECK(answer.duplicate == 48, "%u duplicates, expected 48", answer.duplicate);
    CHECK(answer.rejected == 0, "%u rejected, expected none", answer.rejected);
    CHECK(answer.sessions == 2, "%u sessions, expected 2", answer.sessions);
    CHECK(strcmp(answer.name, SESSION_B) == 0, "the answer names '%s'", answer.name);
    CHECK(strstr(answer.message, "already here") != NULL, "the message reads '%s'",
          answer.message);
}

/** A full ring is `full`, and says how much got in before it filled. */
static void test_a_ring_that_fills_says_so(void)
{
    net_import_t t;
    net_import_reset(&t);

    CHECK(chunk(&t, SESSION_A) == NET_IMPORT_OPEN, "should ask to open");
    net_import_opened(&t);
    rows(&t, 32, true);
    net_import_fold(&t, 20, 0, 0, true, false);

    net_import_answer_t answer;
    CHECK(net_import_answer(&t, false, &answer), "no answer");
    CHECK(strcmp(answer.state, "full") == 0, "state is '%s', expected 'full'", answer.state);
    CHECK(strstr(answer.message, "nothing was removed") != NULL,
          "the message does not say the ring kept what it had: '%s'", answer.message);
}

/**
 * A controller that cannot store at all says `error`, not `busy`.
 *
 * Same silence discipline, different advice: sending the file again would meet
 * the same controller with the same missing partition.
 */
static void test_a_controller_with_no_storage_does_not_ask_for_a_retry(void)
{
    net_import_t t;
    net_import_reset(&t);

    for (int i = 0; i < 3; i++) {
        CHECK(chunk(&t, SESSION_A) != NET_IMPORT_CONTINUE, "chunk %d should not be stored", i);
        net_import_refused(&t, "storage is not available on this controller", false);
        rows(&t, 8, false);
    }

    net_import_answer_t answer;
    CHECK(net_import_answer(&t, false, &answer), "no answer");
    CHECK(strcmp(answer.state, "error") == 0, "state is '%s', expected 'error'", answer.state);
    CHECK(answer.rejected == 24, "%u rejected, expected 24", answer.rejected);
    CHECK(strstr(answer.message, "again") == NULL,
          "a transfer that cannot ever succeed was told to try again: '%s'", answer.message);
}

/**
 * A transfer that runs out of memory asks to be sent again, and says so once.
 *
 * The chunk buffer a `put_file` decodes into is 2 KiB borrowed from the heap
 * for the length of the transfer, not carried in the client slot - that is what
 * took a connected client from 11.4 KB to 6.3 KB and stopped the board refusing
 * a fifth connection. The cost of borrowing is that the borrow can fail, and
 * this is the shape of that failure: the rows of that chunk cannot be decoded,
 * so they must be counted as not placed and the transfer must ask for the file
 * again. Answering per chunk here is the original defect, which is why this
 * goes through ::net_import_refused with `try_again` like any other refusal
 * rather than replying on the spot.
 *
 * `busy` and not `error` is the load-bearing part: the heap comes back when a
 * client disconnects, so sending the archive again really does work, and every
 * row already stored returns a duplicate rather than a second copy.
 */
static void test_a_transfer_that_runs_out_of_memory_asks_to_be_sent_again(void)
{
    net_import_t t;
    net_import_reset(&t);

    /* Two chunks land normally, then the heap has nothing left to lend. */
    CHECK(chunk(&t, SESSION_A) == NET_IMPORT_OPEN, "the first chunk should ask to open");
    net_import_opened(&t);
    rows(&t, 16, true);
    net_import_fold(&t, 16, 0, 0, false, false);

    for (int i = 0; i < 40; i++) {
        const net_import_step_t step = chunk(&t, SESSION_A);
        if (i == 0) {
            CHECK(step == NET_IMPORT_CONTINUE, "the open session should continue, got %d", step);
            net_import_refused(&t,
                               "the sensor ran out of memory part way through; nothing was "
                               "lost, send it again when fewer devices are connected",
                               true);
        } else {
            CHECK(step == NET_IMPORT_DISCARD, "chunk %d should be discarded, got %d", i, step);
        }
        rows(&t, 8, false);
        CHECK(t.in_flight, "chunk %d left no transfer in flight", i);
    }

    net_import_answer_t answer;
    CHECK(net_import_answer(&t, false, &answer), "no answer for a transfer that ran out of heap");
    CHECK(strcmp(answer.state, "busy") == 0, "state is '%s', expected 'busy'", answer.state);
    /* What did land is still reported: those readings are on flash and the user
     * is owed the truth about them, not a round zero. */
    CHECK(answer.added == 16, "%u added, expected the 16 that were stored before the wall",
          answer.added);
    CHECK(answer.rejected == 40 * 8, "%u rows accounted for, expected %u", answer.rejected,
          40u * 8u);
    CHECK(strstr(answer.message, "memory") != NULL,
          "the message does not name the wall that was hit: '%s'", answer.message);
    CHECK(strstr(answer.message, "again") != NULL,
          "the message does not say the file can be sent again: '%s'", answer.message);
    CHECK(!t.in_flight, "answering left the transfer in flight");
}

/** A message that overran its buffer would be truncated mid-sentence. */
static void test_every_message_fits(void)
{
    net_import_t t;
    net_import_reset(&t);
    (void)chunk(&t, SESSION_A);
    net_import_refused(&t,
                       "this controller cannot yet take back a session recorded without a clock",
                       true);
    rows(&t, 4000000, false); /* a wide count, to make the digits count too */

    net_import_answer_t answer;
    CHECK(net_import_answer(&t, true, &answer), "no answer");
    CHECK(strlen(answer.message) < NET_IMPORT_MESSAGE_MAX - 1,
          "the longest message fills %zu of %u bytes, so it is being truncated",
          strlen(answer.message), (unsigned)NET_IMPORT_MESSAGE_MAX);

    /* The same message again for the transfer that follows a close-out, which
     * is the longest this can produce: every branch gains the clause saying so.
     * `t` is already marked resumed by the answer above. */
    (void)chunk(&t, SESSION_A);
    net_import_refused(&t,
                       "this controller cannot yet take back a session recorded without a clock",
                       true);
    rows(&t, 4000000, false);
    CHECK(net_import_answer(&t, true, &answer), "no answer for the remainder");
    CHECK(strlen(answer.message) < NET_IMPORT_MESSAGE_MAX - 1,
          "the longest message of a resumed transfer fills %zu of %u bytes, so it is being "
          "truncated",
          strlen(answer.message), (unsigned)NET_IMPORT_MESSAGE_MAX);
}

/**
 * A transfer the controller ended itself does not make the rest a new file.
 *
 * This is the two ends disagreeing about when a transfer finished, which is
 * what the pass of 2026-08-04 saw as a send that reached a hundred per cent and
 * never ended. The controller closes a quiet transfer out after
 * ::NET_IMPORT_IDLE_MS and answers it; the sender knows nothing of that and
 * keeps sending, so the frames that follow open a second transfer with its own
 * counters. Its answer used to read as the whole account of the file - a
 * hundred thousand readings reported as the few hundred that arrived after the
 * close-out - with nothing anywhere to say the rest had been answered already.
 */
static void test_the_remainder_of_a_closed_out_transfer_says_that_is_what_it_is(void)
{
    net_import_t t;
    net_import_reset(&t);

    /* Half the file, then a silence long enough for the controller to give up. */
    (void)chunk(&t, SESSION_A);
    net_import_opened(&t);
    rows(&t, 800, true);
    net_import_fold(&t, 800, 0, 0, false, false);

    g_now_us += (int64_t)NET_IMPORT_IDLE_MS * 1000 + 1;
    CHECK(net_import_stale(&t, g_now_us, (int64_t)NET_IMPORT_IDLE_MS * 1000),
          "a transfer silent for longer than the bound is not being called stale");

    net_import_answer_t first;
    CHECK(net_import_answer(&t, true, &first), "the close-out produced no answer");
    CHECK(!first.resumed, "the first answer of a file claims to be a remainder");
    CHECK(first.added == 800, "%u added, expected the 800 that arrived", first.added);

    /* The sender never stopped. What it sends next is the rest of the same file. */
    CHECK(t.resumed, "the close-out did not leave the mark that the file is unfinished");
    (void)chunk(&t, SESSION_B);
    net_import_opened(&t);
    rows(&t, 200, true);
    net_import_fold(&t, 0, 200, 0, false, false);

    net_import_answer_t second;
    CHECK(net_import_answer(&t, false, &second), "the remainder produced no answer");
    CHECK(second.resumed, "the remainder does not know it is a remainder");
    CHECK(strstr(second.message, "earlier part") != NULL,
          "the remainder's message does not say part of the file was answered already: '%s'",
          second.message);
    /* And its counts are still its own: they are not invented, only explained. */
    CHECK(second.added == 0, "%u added by the remainder, expected 0", second.added);
    CHECK(second.duplicate == 200, "%u already here, expected 200", second.duplicate);

    /* A transfer that ends the way it should leaves no mark, so the next file
     * sent on the same connection is answered as a whole file. */
    CHECK(!t.resumed, "an orderly answer left the connection thinking a file is unfinished");
    (void)chunk(&t, SESSION_A);
    net_import_opened(&t);
    rows(&t, 8, true);
    net_import_fold(&t, 8, 0, 0, false, false);
    net_import_answer_t third;
    CHECK(net_import_answer(&t, false, &third), "the next file produced no answer");
    CHECK(!third.resumed, "a whole file sent after a resumed one is called a remainder");
    CHECK(strstr(third.message, "earlier part") == NULL,
          "a whole file is being told it is a remainder: '%s'", third.message);
}

int main(void)
{
    printf("net_import: one transfer, one answer\n");
    test_a_refused_transfer_says_nothing_until_it_is_done();
    test_a_refusal_covers_the_whole_transfer();
    test_a_session_that_cannot_be_placed_does_not_stop_the_rest();
    test_a_transfer_that_goes_quiet_is_answered();
    test_an_ordinary_transfer_still_ends_with_one_ok();
    test_a_ring_that_fills_says_so();
    test_a_controller_with_no_storage_does_not_ask_for_a_retry();
    test_a_transfer_that_runs_out_of_memory_asks_to_be_sent_again();
    test_every_message_fits();
    test_the_remainder_of_a_closed_out_transfer_says_that_is_what_it_is();

    printf("  %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
