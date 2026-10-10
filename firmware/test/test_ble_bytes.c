/**
 * @file test_ble_bytes.c
 * @brief Host tests for the inbound buffer between a GATT write and a client task.
 *
 * The interesting behaviour is the one that only shows up under abuse: what a
 * bounded buffer carrying a **line-delimited command stream** throws away when
 * it fills. Get that wrong and the failure is not a lost command, it is an
 * invented one - two half commands spliced into a third that parses and that
 * nobody sent. `ble_bytes.h` argues the rule; these tests hold it to it.
 *
 * Reaching this on hardware means writing to the RX characteristic faster than
 * the client task drains it, which is awkward to arrange and impossible to
 * arrange precisely. Here it is three lines of C.
 *
 * Builds and runs on the host with no ESP-IDF and no NimBLE: see the Makefile.
 */

#include <stdio.h>
#include <string.h>

#include "ble_bytes.h"

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

/** Read everything buffered into @p out as a NUL-terminated string. */
static size_t drain(ble_bytes_t *b, char *out, size_t cap)
{
    size_t total = 0;
    for (;;) {
        const size_t n = ble_bytes_read(b, out + total, cap - 1 - total);
        if (n == 0) {
            break;
        }
        total += n;
    }
    out[total] = '\0';
    return total;
}

static bool write_str(ble_bytes_t *b, const char *s)
{
    return ble_bytes_write(b, s, strlen(s));
}

/** Bytes go in and come out unchanged, in order, across arbitrary splits. */
static void test_fifo_order(void)
{
    uint8_t storage[64];
    ble_bytes_t b;
    ble_bytes_init(&b, storage, sizeof(storage));

    CHECK(ble_bytes_len(&b) == 0, "a fresh buffer holds %zu bytes", ble_bytes_len(&b));

    CHECK(write_str(&b, "{\"cmd\":"), "a short write should be taken");
    CHECK(write_str(&b, "\"pause\"}\n"), "the rest of the line should be taken");
    CHECK(ble_bytes_len(&b) == 16, "expected 16 bytes buffered, got %zu", ble_bytes_len(&b));

    char out[128];
    drain(&b, out, sizeof(out));
    CHECK(strcmp(out, "{\"cmd\":\"pause\"}\n") == 0, "reassembled to '%s'", out);
    CHECK(ble_bytes_len(&b) == 0, "the buffer should be empty after draining");
    CHECK(ble_bytes_dropped(&b) == 0, "nothing should have been dropped");
}

/** A reader taking a few bytes at a time gets the same stream. */
static void test_partial_reads(void)
{
    uint8_t storage[32];
    ble_bytes_t b;
    ble_bytes_init(&b, storage, sizeof(storage));

    CHECK(write_str(&b, "abcdefghij"), "should be taken");

    char out[16];
    size_t n = ble_bytes_read(&b, out, 3);
    CHECK(n == 3 && memcmp(out, "abc", 3) == 0, "first three bytes came back wrong");
    n = ble_bytes_read(&b, out, 3);
    CHECK(n == 3 && memcmp(out, "def", 3) == 0, "second three bytes came back wrong");
    CHECK(ble_bytes_len(&b) == 4, "expected 4 left, got %zu", ble_bytes_len(&b));

    char rest[16];
    drain(&b, rest, sizeof(rest));
    CHECK(strcmp(rest, "ghij") == 0, "the tail came back as '%s'", rest);
}

/** Writes and reads that straddle the end of the storage still line up. */
static void test_wraparound(void)
{
    uint8_t storage[8];
    ble_bytes_t b;
    ble_bytes_init(&b, storage, sizeof(storage));

    char out[16];
    for (int round = 0; round < 5; round++) {
        CHECK(write_str(&b, "12345"), "round %d: the write should fit", round);
        drain(&b, out, sizeof(out));
        CHECK(strcmp(out, "12345") == 0, "round %d: got '%s'", round, out);
    }
    CHECK(ble_bytes_dropped(&b) == 0, "wrapping should not drop anything");
}

/**
 * The point of the whole file: an overflow must not be able to manufacture a
 * command out of the halves of two others.
 *
 * The buffer is filled with one complete line plus the head of a second, then
 * offered more than it can hold. The complete line survives; the half line goes,
 * along with everything up to the next boundary.
 */
static void test_overflow_never_splices(void)
{
    uint8_t storage[32];
    ble_bytes_t b;
    ble_bytes_init(&b, storage, sizeof(storage));

    CHECK(write_str(&b, "{\"cmd\":\"pause\"}\n"), "the first line should fit");
    CHECK(write_str(&b, "{\"cmd\":\"cl"), "the head of the second should fit");
    CHECK(ble_bytes_len(&b) == 26, "expected 26 buffered, got %zu", ble_bytes_len(&b));

    /* Only 6 bytes of room left, so this cannot be taken. */
    CHECK(!write_str(&b, "ean\"}\n{\"cmd\":\"resume\"}\n"), "the overflow should be refused");

    char out[64];
    drain(&b, out, sizeof(out));
    CHECK(strcmp(out, "{\"cmd\":\"pause\"}\n") == 0,
          "only the whole line should have survived; got '%s'", out);
    CHECK(ble_bytes_dropped(&b) > 0, "the loss should be counted");

    /* Still resyncing: the rest of the cut command is discarded, and only what
     * follows the next newline is kept. */
    CHECK(!write_str(&b, "garbage-tail-of-a-command\n"), "the tail should be discarded");
    CHECK(write_str(&b, "{\"cmd\":\"resume\"}\n"), "the next whole line should be taken");

    drain(&b, out, sizeof(out));
    CHECK(strcmp(out, "{\"cmd\":\"resume\"}\n") == 0, "expected a clean resume, got '%s'", out);
}

/** A newline inside the refused write resynchronises within that same write. */
static void test_resync_inside_one_write(void)
{
    uint8_t storage[16];
    ble_bytes_t b;
    ble_bytes_init(&b, storage, sizeof(storage));

    CHECK(write_str(&b, "aaaaaaaaaa"), "ten bytes should fit in sixteen");
    CHECK(!write_str(&b, "bbbbbbbbbb"), "ten more should not");

    /* One write carrying the end of the lost line and the whole of the next. */
    CHECK(!ble_bytes_write(&b, "tail\nkeep\n", 10), "the call still lost bytes");

    char out[32];
    drain(&b, out, sizeof(out));
    CHECK(strcmp(out, "keep\n") == 0, "expected only the line after the boundary, got '%s'",
          out);
}

/** An exactly-full buffer is fine; one byte more is not. */
static void test_exact_fit(void)
{
    uint8_t storage[8];
    ble_bytes_t b;
    ble_bytes_init(&b, storage, sizeof(storage));

    CHECK(ble_bytes_write(&b, "12345678", 8), "an exact fit should be taken");
    CHECK(ble_bytes_len(&b) == 8, "expected 8 buffered, got %zu", ble_bytes_len(&b));
    CHECK(!ble_bytes_write(&b, "9", 1), "one byte past full should be refused");

    ble_bytes_init(&b, storage, sizeof(storage));
    CHECK(!ble_bytes_write(&b, "123456789", 9), "a write larger than the buffer is refused");
}

/** A new connection starts clean, but the loss count is history and survives. */
static void test_reset(void)
{
    uint8_t storage[8];
    ble_bytes_t b;
    ble_bytes_init(&b, storage, sizeof(storage));

    CHECK(!ble_bytes_write(&b, "123456789", 9), "should overflow");
    CHECK(ble_bytes_dropped(&b) == 9, "expected 9 dropped, got %u",
          (unsigned)ble_bytes_dropped(&b));

    /* Still resyncing, so a fragment with no newline in it is discarded too. */
    CHECK(!write_str(&b, "abc"), "a fragment offered mid-resync should be discarded");
    const uint32_t dropped = ble_bytes_dropped(&b);
    CHECK(dropped == 12, "expected 12 dropped, got %u", (unsigned)dropped);

    ble_bytes_reset(&b);

    CHECK(ble_bytes_len(&b) == 0, "reset should empty the buffer");
    CHECK(ble_bytes_dropped(&b) == dropped, "reset should keep the loss count");
    CHECK(write_str(&b, "xyz\n"), "reset should clear the resync state");

    char out[16];
    drain(&b, out, sizeof(out));
    CHECK(strcmp(out, "xyz\n") == 0, "expected a clean buffer, got '%s'", out);
}

/** Zero-length writes are a no-op, not an overflow. */
static void test_empty_write(void)
{
    uint8_t storage[8];
    ble_bytes_t b;
    ble_bytes_init(&b, storage, sizeof(storage));

    CHECK(ble_bytes_write(&b, "", 0), "an empty write should succeed");
    CHECK(ble_bytes_len(&b) == 0, "an empty write should buffer nothing");
    CHECK(ble_bytes_dropped(&b) == 0, "an empty write should drop nothing");
}

int main(void)
{
    printf("BLE inbound buffer host tests\n");
    printf("what a bounded command stream throws away when it fills\n\n");

    test_fifo_order();
    test_partial_reads();
    test_wraparound();
    test_overflow_never_splices();
    test_resync_inside_one_write();
    test_exact_fit();
    test_reset();
    test_empty_write();

    printf("\n%d checks, %d failures\n", g_checks, g_failures);
    if (g_failures == 0) {
        printf("PASS\n");
        return 0;
    }
    printf("FAIL\n");
    return 1;
}
