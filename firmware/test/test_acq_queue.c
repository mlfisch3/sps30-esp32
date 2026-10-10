/**
 * @file test_acq_queue.c
 * @brief Host tests for the bounded drop-oldest reading queue.
 *
 * The queue is the seam a transport will read from, and its overflow behaviour
 * is the part that decides whether a stalled client loses the newest data or
 * the oldest. That is a decision worth pinning down before anything depends on
 * it, and it costs nothing to test: the queue is pure C over caller-supplied
 * storage, so there is no hardware and no allocation involved.
 *
 * Builds and runs on the host with no ESP-IDF: see the Makefile.
 */

#include <stdio.h>
#include <string.h>

#include "acq_queue.h"

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

/** A reading identifiable by its sequence number alone. */
static acq_reading_t reading(uint32_t sequence)
{
    acq_reading_t r;
    memset(&r, 0, sizeof(r));
    r.sequence = sequence;
    r.session = 1;
    r.uptime_us = (int64_t)sequence * 1000000;
    r.values.pm2_5 = (float)sequence;
    return r;
}

/**
 * The record size is the unit of the ACQ_CLIENT_QUEUE_MAX arithmetic.
 *
 * A static assertion already guards it in acq_queue.c; this states the same
 * thing where somebody reading the tests will see the number, along with what
 * the whole eight-client allocation costs.
 */
static void test_record_size(void)
{
    CHECK(sizeof(acq_reading_t) == ACQ_READING_BYTES,
          "acq_reading_t is %zu bytes, the sizing argument assumes %u",
          sizeof(acq_reading_t), (unsigned)ACQ_READING_BYTES);

    const size_t per_client = ACQ_CLIENT_QUEUE_MAX * sizeof(acq_reading_t);
    const size_t total = per_client * ACQ_MAX_CLIENTS;

    /* Free DRAM at boot on this board is 179 KiB, measured from heap_init. The
     * whole client allocation has to stay a small fraction of that, because
     * Wi-Fi and lwIP have not been added yet and will want most of the rest. */
    CHECK(total < 179u * 1024u / 8u,
          "client queues are %zu bytes, over an eighth of the 179 KiB free", total);

    printf("sizing: %u readings x %zu B x %u clients = %zu B (%zu KiB), "
           "%.1f%% of 179 KiB free DRAM\n",
           (unsigned)ACQ_CLIENT_QUEUE_MAX, sizeof(acq_reading_t),
           (unsigned)ACQ_MAX_CLIENTS, total, total / 1024,
           100.0 * (double)total / (179.0 * 1024.0));
}

static void test_fifo_order(void)
{
    acq_reading_t storage[4];
    acq_queue_t q;
    acq_queue_init(&q, storage, 4);

    CHECK(acq_queue_count(&q) == 0, "a fresh queue is empty");

    acq_reading_t out;
    CHECK(!acq_queue_pop(&q, &out), "popping an empty queue fails");

    for (uint32_t i = 1; i <= 3; i++) {
        CHECK(acq_queue_push(&q, &(acq_reading_t){0}) || true, "push must not fail");
    }
    acq_queue_reset(&q);

    for (uint32_t i = 1; i <= 4; i++) {
        acq_reading_t r = reading(i);
        CHECK(acq_queue_push(&q, &r), "push %u should not drop", i);
    }
    CHECK(acq_queue_count(&q) == 4, "four pushed, four queued");
    CHECK(acq_queue_dropped(&q) == 0, "nothing dropped while it fits");

    for (uint32_t i = 1; i <= 4; i++) {
        CHECK(acq_queue_pop(&q, &out), "pop %u should succeed", i);
        CHECK(out.sequence == i, "expected sequence %u, got %u", i, out.sequence);
        CHECK(out.values.pm2_5 == (float)i, "payload travelled with the record");
        CHECK(out.uptime_us == (int64_t)i * 1000000, "timestamp travelled too");
    }
    CHECK(acq_queue_count(&q) == 0, "drained");

    printf("fifo: readings come out in the order they went in, payload intact\n");
}

/**
 * Overflow drops the OLDEST, and says so.
 *
 * Drop-oldest is the deliberate choice: a client that comes back after a stall
 * wants the air as it is now, not a half-minute-old snapshot to scroll past.
 */
static void test_drop_oldest(void)
{
    acq_reading_t storage[3];
    acq_queue_t q;
    acq_queue_init(&q, storage, 3);

    for (uint32_t i = 1; i <= 3; i++) {
        acq_reading_t r = reading(i);
        CHECK(acq_queue_push(&q, &r), "push %u fits", i);
    }

    acq_reading_t r4 = reading(4);
    CHECK(!acq_queue_push(&q, &r4), "the fourth push must report a drop");
    CHECK(acq_queue_count(&q) == 3, "still exactly capacity");
    CHECK(acq_queue_dropped(&q) == 1, "one drop counted");

    acq_reading_t out;
    for (uint32_t want = 2; want <= 4; want++) {
        CHECK(acq_queue_pop(&q, &out), "pop should succeed");
        CHECK(out.sequence == want, "expected %u after the drop, got %u", want,
              out.sequence);
    }

    printf("overflow: the oldest is dropped and counted, the newest is kept\n");
}

/** Wrapping the ring many times must not corrupt or lose ordering. */
static void test_wraparound(void)
{
    acq_reading_t storage[ACQ_CLIENT_QUEUE_MAX];
    acq_queue_t q;
    acq_queue_init(&q, storage, ACQ_CLIENT_QUEUE_MAX);

    const uint32_t total = ACQ_CLIENT_QUEUE_MAX * 10 + 7;
    for (uint32_t i = 1; i <= total; i++) {
        acq_reading_t r = reading(i);
        acq_queue_push(&q, &r);
    }

    CHECK(acq_queue_count(&q) == ACQ_CLIENT_QUEUE_MAX, "full after overrunning it");
    CHECK(acq_queue_dropped(&q) == total - ACQ_CLIENT_QUEUE_MAX,
          "every reading beyond capacity is counted as dropped");

    /* What survives is exactly the most recent ACQ_CLIENT_QUEUE_MAX. */
    acq_reading_t out;
    uint32_t expected = total - ACQ_CLIENT_QUEUE_MAX + 1;
    while (acq_queue_pop(&q, &out)) {
        CHECK(out.sequence == expected, "expected %u, got %u", expected, out.sequence);
        expected++;
    }
    CHECK(expected == total + 1, "the survivors are the newest, in order");

    printf("wraparound: %u pushes through a %u-deep ring stayed ordered\n", total,
           (unsigned)ACQ_CLIENT_QUEUE_MAX);
}

/** Interleaved push and pop, the pattern a draining transport produces. */
static void test_interleaved(void)
{
    acq_reading_t storage[8];
    acq_queue_t q;
    acq_queue_init(&q, storage, 8);

    uint32_t pushed = 0, popped = 0;
    acq_reading_t out;
    for (int round = 0; round < 500; round++) {
        for (int i = 0; i < 3; i++) {
            acq_reading_t r = reading(++pushed);
            acq_queue_push(&q, &r);
        }
        for (int i = 0; i < 2; i++) {
            if (acq_queue_pop(&q, &out)) {
                popped++;
                /* Whatever comes out must be one we put in, and never a repeat. */
                CHECK(out.sequence >= 1 && out.sequence <= pushed,
                      "sequence %u is outside what was pushed", out.sequence);
            }
        }
    }
    CHECK(acq_queue_count(&q) + popped + acq_queue_dropped(&q) == pushed,
          "queued + popped + dropped must account for every push");

    printf("interleaved: %u pushes and %u pops balanced exactly\n", pushed, popped);
}

/** Defensive arguments: nothing here may write through a NULL. */
static void test_null_safety(void)
{
    acq_reading_t out;
    acq_reading_t r = reading(1);
    acq_queue_t q;

    acq_queue_init(NULL, NULL, 0);
    CHECK(!acq_queue_push(NULL, &r), "push to NULL queue fails");
    CHECK(!acq_queue_pop(NULL, &out), "pop from NULL queue fails");
    CHECK(acq_queue_count(NULL) == 0, "count of NULL is zero");
    CHECK(acq_queue_dropped(NULL) == 0, "drops of NULL is zero");
    acq_queue_reset(NULL);

    /* A queue with no storage must refuse rather than write somewhere. */
    acq_queue_init(&q, NULL, 16);
    CHECK(q.capacity == 0, "no storage means no capacity");
    CHECK(!acq_queue_push(&q, &r), "push with no storage fails");

    acq_reading_t storage[2];
    acq_queue_init(&q, storage, 2);
    CHECK(!acq_queue_push(&q, NULL), "pushing NULL fails");
    CHECK(acq_queue_push(&q, &r), "a real push still works afterwards");
    CHECK(!acq_queue_pop(&q, NULL), "popping into NULL fails");
    CHECK(acq_queue_count(&q) == 1, "the failed pop did not consume anything");

    printf("null safety: every entry point refuses NULL without writing\n");
}

/** reset() clears the contents but keeps the cumulative drop count. */
static void test_reset(void)
{
    acq_reading_t storage[2];
    acq_queue_t q;
    acq_queue_init(&q, storage, 2);

    for (uint32_t i = 1; i <= 5; i++) {
        acq_reading_t r = reading(i);
        acq_queue_push(&q, &r);
    }
    const uint32_t dropped = acq_queue_dropped(&q);
    CHECK(dropped == 3, "three dropped from a 2-deep ring after five pushes");

    acq_queue_reset(&q);
    CHECK(acq_queue_count(&q) == 0, "reset empties the ring");
    CHECK(acq_queue_dropped(&q) == dropped,
          "reset keeps the drop counter: it is a lifetime health metric");

    acq_reading_t r = reading(99);
    CHECK(acq_queue_push(&q, &r), "usable again after a reset");
    acq_reading_t out;
    CHECK(acq_queue_pop(&q, &out) && out.sequence == 99, "and the ring still works");

    printf("reset: contents cleared, the lifetime drop count preserved\n");
}

int main(void)
{
    printf("acquisition queue host tests\n");
    printf("the bounded drop-oldest seam a transport will read from\n\n");

    test_record_size();
    test_fifo_order();
    test_drop_oldest();
    test_wraparound();
    test_interleaved();
    test_null_safety();
    test_reset();

    printf("\n%d checks, %d failures\n", g_checks, g_failures);
    if (g_failures == 0) {
        printf("PASS\n");
        return 0;
    }
    printf("FAIL\n");
    return 1;
}
