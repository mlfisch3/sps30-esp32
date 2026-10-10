/**
 * @file replay_store.c
 * @brief Replay a real store partition through the export/import round trip.
 *
 * `test_store_merge.c` proves the merge over data this project invented.
 * This proves it over data the sensor actually measured: hand it an image of a
 * board's `store` partition - `esptool read_flash 0x190000 0x270000 image.bin` -
 * and it does, on the host, exactly what a phone and a controller do to each
 * other:
 *
 * 1. reads every session out of the ring, through the real page decoder;
 * 2. **formats each reading as the CSV row the export writes**, with the real
 *    `%.2f`/`%.3f` and the real ISO timestamp;
 * 3. **parses those rows back** with the real ::wire_parse_row;
 * 4. feeds them to the real ::store_merge, through a sink that refuses to
 *    overwrite anything - the same rule `store.c` enforces against flash;
 * 5. compares the ring **byte for byte** with what it was before, twice.
 *
 * The only things not exercised are the socket and the screen. Everything that
 * decides whether a reading survives is the shipping code, running over readings
 * that cannot be recaptured.
 *
 * It also prints each session's backup digest, which is what the controller's
 * erase checks a phone's claim against - so the same image can be used to
 * confirm that the Kotlin and the C agree about a real session.
 *
 *     make -C firmware/test replay
 *     ./firmware/test/build/replay_store image.bin
 *
 * Read-only with respect to the image: it is loaded into memory and the file is
 * never written.
 */

#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "store_codec.h"
#include "store_merge.h"
#include "wire.h"

/* --- the ring, in memory --------------------------------------------------- */

#define SECTOR_BYTES 4096
#define SLOTS_PER_SECTOR (SECTOR_BYTES / STORE_PAGE_BYTES)

typedef struct {
    uint8_t *bytes;
    uint32_t slots;
    uint32_t head;
    uint32_t next_seq;
    uint32_t writes;
    uint32_t refusals;
} ring_t;

static bool slot_has_page(const ring_t *ring, uint32_t slot)
{
    store_header_t peek;
    return store_peek_page(ring->bytes + (size_t)slot * STORE_PAGE_BYTES, &peek);
}

/** The same rule `store.c` applies: never write where a page already is. */
static bool head_is_free(const ring_t *ring)
{
    if (ring->head >= ring->slots) {
        return false;
    }
    const uint32_t span = (ring->head % SLOTS_PER_SECTOR == 0) ? SLOTS_PER_SECTOR : 1;
    for (uint32_t i = 0; i < span; i++) {
        if (slot_has_page(ring, (ring->head + i) % ring->slots)) {
            return false;
        }
    }
    return true;
}

static uint32_t ring_next_seq(void *ctx)
{
    return ((ring_t *)ctx)->next_seq;
}

static bool ring_commit(void *ctx, const uint8_t page[STORE_PAGE_BYTES])
{
    ring_t *ring = ctx;
    if (!head_is_free(ring)) {
        ring->refusals++;
        return false;
    }
    memcpy(ring->bytes + (size_t)ring->head * STORE_PAGE_BYTES, page, STORE_PAGE_BYTES);
    ring->head = (ring->head + 1) % ring->slots;
    ring->next_seq++;
    ring->writes++;
    return true;
}

/* --- sessions -------------------------------------------------------------- */

typedef struct {
    uint32_t session;
    int64_t start_ms;
    int16_t utc_offset_min;
    uint32_t cadence_ms;
    uint32_t readings;
    uint32_t pages;
    uint32_t digest;
    char name[WIRE_CSV_NAME_LEN];
} session_t;

#define MAX_SESSIONS 32

static size_t collect_sessions(const ring_t *ring, session_t *out, size_t cap)
{
    size_t count = 0;
    for (uint32_t slot = 0; slot < ring->slots; slot++) {
        store_header_t page;
        if (!store_peek_page(ring->bytes + (size_t)slot * STORE_PAGE_BYTES, &page)) {
            continue;
        }
        session_t *found = NULL;
        for (size_t i = 0; i < count; i++) {
            if (out[i].session == page.session && out[i].start_ms == page.session_start_ms) {
                found = &out[i];
                break;
            }
        }
        if (found == NULL) {
            if (count == cap) {
                continue;
            }
            found = &out[count++];
            memset(found, 0, sizeof(*found));
            found->session = page.session;
            found->start_ms = page.session_start_ms;
            found->utc_offset_min = page.utc_offset_min;
            found->cadence_ms = page.cadence_ms;
            wire_csv_name(page.session_start_ms / 1000, page.utc_offset_min * 60, found->name,
                          sizeof(found->name));
        }
        found->pages++;
        found->readings += page.count;
    }
    return count;
}

/** Every reading of one session, in the ring's own page order. */
static size_t read_session(const ring_t *ring, const session_t *session,
                           store_reading_t *out, size_t cap)
{
    static store_page_t page; /* 2.6 KB: off the stack, as on the board */
    size_t n = 0;
    for (uint32_t slot = 0; slot < ring->slots; slot++) {
        const uint8_t *raw = ring->bytes + (size_t)slot * STORE_PAGE_BYTES;
        store_header_t peek;
        if (!store_peek_page(raw, &peek) || peek.session != session->session ||
            peek.session_start_ms != session->start_ms) {
            continue;
        }
        if (!store_decode_page(raw, &page)) {
            continue;
        }
        for (uint8_t i = 0; i < page.count && n < cap; i++) {
            out[n++] = page.readings[i];
        }
    }
    return n;
}

static uint32_t digest_of(const store_reading_t *readings, size_t count)
{
    uint32_t digest = 0;
    for (size_t i = 0; i < count; i++) {
        digest = store_digest_fold(digest, store_digest_reading(&readings[i]));
    }
    return digest;
}

/* --- the export, exactly as the wire writes it ----------------------------- */

/**
 * One reading as its exported CSV row.
 *
 * The same decimals `wire_file_data_row` uses - two, and three for particle
 * size - because that is what makes the number in the file and the integer in
 * flash the same number. Formatting it any other way here would be testing a
 * round trip nobody performs.
 */
static void export_row(const store_reading_t *reading, int utc_offset_s, char *out, size_t cap)
{
    char index[WIRE_INDEX_LEN];
    char stamp[WIRE_TIMESTAMP_LEN];
    wire_format_index(reading->index, index, sizeof(index));
    wire_iso_timestamp(reading->epoch_ms / 1000, utc_offset_s, stamp, sizeof(stamp));

    size_t len = (size_t)snprintf(out, cap, "%s,%s", index, stamp);
    for (int f = 0; f < STORE_FIELDS && len < cap; f++) {
        len += (size_t)snprintf(out + len, cap - len,
                                f == STORE_FIELDS - 1 ? ",%.3f" : ",%.2f",
                                (double)reading->values[f]);
    }
    snprintf(out + len, cap - len, ",%s", reading->settling ? WIRE_EVENT_SETTLING : "");
}

/* --- the round trip -------------------------------------------------------- */

static int failures = 0;

#define CHECK(cond, ...)                                                       \
    do {                                                                       \
        if (!(cond)) {                                                         \
            failures++;                                                        \
            printf("    FAIL: ");                                              \
            printf(__VA_ARGS__);                                               \
            printf("\n");                                                      \
        }                                                                      \
    } while (0)

#define MAX_READINGS 200000
static store_reading_t g_before[MAX_READINGS];
static store_reading_t g_after[MAX_READINGS];

/** Import @p count exported rows of @p session back into @p ring. */
static store_merge_stats_t round_trip(ring_t *ring, const session_t *session,
                                      const store_reading_t *readings, size_t count)
{
    store_merge_t merge;
    store_merge_begin(&merge, session->session, session->start_ms,
                      session->cadence_ms ? session->cadence_ms : 1000u,
                      session->utc_offset_min);

    /* What the session already holds, from page headers - the same walk
     * `store_import_open` does over flash. */
    for (uint32_t slot = 0; slot < ring->slots; slot++) {
        store_header_t peek;
        if (!store_peek_page(ring->bytes + (size_t)slot * STORE_PAGE_BYTES, &peek) ||
            peek.session != session->session ||
            peek.session_start_ms != session->start_ms) {
            continue;
        }
        if (!store_merge_note_held(&merge, peek.first_index, peek.count)) {
            printf("    the held set overflowed; the merge would be refused\n");
            break;
        }
    }

    const store_sink_t sink = {
        .next_seq = ring_next_seq, .commit = ring_commit, .ctx = ring};

    for (size_t i = 0; i < count; i++) {
        /* Out through the export formatter, back in through the row reader.
         * This is the whole point: not the readings, the *file*. */
        char row[256];
        export_row(&readings[i], session->utc_offset_min * 60, row, sizeof(row));

        wire_row_t parsed;
        if (!wire_parse_row(row, strlen(row), &parsed)) {
            printf("    a row this project wrote could not be read back: %s\n", row);
            failures++;
            continue;
        }
        store_reading_t reading = {
            .index = parsed.index,
            .epoch_ms = parsed.epoch_s * 1000,
            .settling = parsed.settling,
        };
        for (int f = 0; f < STORE_FIELDS; f++) {
            reading.values[f] = parsed.values.values[f];
        }
        store_merge_add(&merge, &reading, &sink);
    }
    store_merge_finish(&merge, &sink);
    return merge.stats;
}

int main(int argc, char **argv)
{
    /*
     * `--sample N` prints N real exported rows and the digest over exactly
     * those rows. That is what lets the Kotlin side be pinned to the C: the app
     * computes the same digest from the same rows, and a test that carries real
     * rows and a real expected value catches a drift between the two that no
     * amount of testing either side alone would.
     */
    long sample = 0;
    if (argc == 4 && strcmp(argv[2], "--sample") == 0) {
        sample = strtol(argv[3], NULL, 10);
    } else if (argc != 2) {
        fprintf(stderr, "usage: %s <store-partition.bin> [--sample N]\n", argv[0]);
        return 2;
    }
    FILE *f = fopen(argv[1], "rb");
    if (f == NULL) {
        perror(argv[1]);
        return 2;
    }
    fseek(f, 0, SEEK_END);
    const long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (size <= 0 || size % STORE_PAGE_BYTES != 0) {
        fprintf(stderr, "%s is not a whole number of %d-byte pages\n", argv[1],
                STORE_PAGE_BYTES);
        fclose(f);
        return 2;
    }

    ring_t ring = {.bytes = malloc((size_t)size), .slots = (uint32_t)(size / STORE_PAGE_BYTES)};
    uint8_t *pristine = malloc((size_t)size);
    if (ring.bytes == NULL || pristine == NULL ||
        fread(ring.bytes, 1, (size_t)size, f) != (size_t)size) {
        fprintf(stderr, "could not read %s\n", argv[1]);
        fclose(f);
        return 2;
    }
    fclose(f);
    memcpy(pristine, ring.bytes, (size_t)size);

    /* Find the write head the way the boot scan does: after the highest
     * page_seq. Anything past it in this image is free space. */
    uint32_t best_seq = 0, best_slot = 0, used = 0;
    bool any = false;
    for (uint32_t slot = 0; slot < ring.slots; slot++) {
        store_header_t page;
        if (!store_peek_page(ring.bytes + (size_t)slot * STORE_PAGE_BYTES, &page)) {
            continue;
        }
        used++;
        if (!any || page.page_seq > best_seq) {
            any = true;
            best_seq = page.page_seq;
            best_slot = slot;
        }
    }
    ring.head = any ? (best_slot + 1) % ring.slots : 0;
    ring.next_seq = best_seq + 1;

    printf("%s: %" PRIu32 " slots, %" PRIu32 " hold pages (%.1f%% full), head at %" PRIu32
           "\n\n",
           argv[1], ring.slots, used, 100.0 * used / ring.slots, ring.head);

    session_t sessions[MAX_SESSIONS];
    const size_t count = collect_sessions(&ring, sessions, MAX_SESSIONS);

    if (sample > 0) {
        /* The largest session: the most representative rows available. */
        size_t biggest = 0;
        for (size_t i = 1; i < count; i++) {
            if (sessions[i].readings > sessions[biggest].readings) {
                biggest = i;
            }
        }
        const session_t *session = &sessions[biggest];
        const size_t held = read_session(&ring, session, g_before, MAX_READINGS);
        const size_t take = (size_t)sample < held ? (size_t)sample : held;
        printf("session %s, first %zu of %zu readings\n", session->name, take, held);
        uint32_t digest = 0;
        for (size_t i = 0; i < take; i++) {
            char row[256];
            export_row(&g_before[i], session->utc_offset_min * 60, row, sizeof(row));
            printf("%s\n", row);
            digest = store_digest_fold(digest, store_digest_reading(&g_before[i]));
        }
        printf("digest over those %zu rows: %08" PRIx32 "\n", take, digest);
        free(ring.bytes);
        free(pristine);
        return 0;
    }

    uint32_t grand_before = 0, grand_after = 0;
    for (size_t i = 0; i < count; i++) {
        session_t *session = &sessions[i];
        const size_t before = read_session(&ring, session, g_before, MAX_READINGS);
        session->digest = digest_of(g_before, before);
        grand_before += (uint32_t)before;

        printf("%s\n", session->name);
        printf("  before      %zu readings, %" PRIu32 " pages, digest %08" PRIx32 "\n", before,
               session->pages, session->digest);

        /* --- first import: it should change nothing at all --- */
        const uint32_t writes_before = ring.writes;
        const store_merge_stats_t first = round_trip(&ring, session, g_before, before);
        printf("  import #1   %" PRIu32 " added, %" PRIu32 " already held, %" PRIu32
               " rejected, %" PRIu32 " pages written\n",
               first.added, first.duplicate, first.rejected, first.pages);
        CHECK(first.added == 0, "a re-import added %" PRIu32 " readings", first.added);
        CHECK(first.duplicate == before, "%" PRIu32 " duplicates for %zu readings",
              first.duplicate, before);
        CHECK(first.rejected == 0, "%" PRIu32 " rows were rejected", first.rejected);
        CHECK(ring.writes == writes_before, "the ring took %" PRIu32 " new pages",
              ring.writes - writes_before);
        CHECK(memcmp(ring.bytes, pristine, (size_t)size) == 0,
              "the partition changed after the first import");

        /* --- second import: idempotent means the second time too --- */
        const store_merge_stats_t second = round_trip(&ring, session, g_before, before);
        printf("  import #2   %" PRIu32 " added, %" PRIu32 " already held, %" PRIu32
               " rejected, %" PRIu32 " pages written\n",
               second.added, second.duplicate, second.rejected, second.pages);
        CHECK(second.added == 0, "a second re-import added %" PRIu32 " readings", second.added);
        CHECK(second.duplicate == before, "%" PRIu32 " duplicates on the second pass",
              second.duplicate);
        CHECK(memcmp(ring.bytes, pristine, (size_t)size) == 0,
              "the partition changed after the second import");

        const size_t after = read_session(&ring, session, g_after, MAX_READINGS);
        const uint32_t after_digest = digest_of(g_after, after);
        grand_after += (uint32_t)after;
        printf("  after       %zu readings, digest %08" PRIx32 "\n", after, after_digest);
        CHECK(after == before, "%zu readings became %zu", before, after);
        CHECK(after_digest == session->digest, "the digest changed: %08" PRIx32 " -> %08" PRIx32,
              session->digest, after_digest);

        /* And every reading, value for value, in whatever order it comes back. */
        uint32_t mismatched = 0;
        for (size_t a = 0; a < after; a++) {
            bool matched = false;
            for (size_t b = 0; b < before; b++) {
                if (g_after[a].index != g_before[b].index) {
                    continue;
                }
                matched = g_after[a].epoch_ms == g_before[b].epoch_ms &&
                          g_after[a].settling == g_before[b].settling;
                for (int fld = 0; fld < STORE_FIELDS && matched; fld++) {
                    matched = store_quantise(g_after[a].values[fld], fld) ==
                              store_quantise(g_before[b].values[fld], fld);
                }
                break;
            }
            if (!matched) {
                mismatched++;
            }
        }
        CHECK(mismatched == 0, "%" PRIu32 " readings came back different", mismatched);
        printf("  claim       %s,%zu,%08" PRIx32 "\n\n", session->name, before,
               session->digest);
    }

    printf("%zu sessions, %" PRIu32 " readings before, %" PRIu32 " after; "
           "%" PRIu32 " pages written, %" PRIu32 " overwrites refused\n",
           count, grand_before, grand_after, ring.writes, ring.refusals);
    printf("the partition is %s after two full round trips\n",
           memcmp(ring.bytes, pristine, (size_t)size) == 0 ? "byte-for-byte unchanged"
                                                           : "CHANGED");
    printf("\n%s\n", failures == 0 ? "PASS" : "FAIL");

    free(ring.bytes);
    free(pristine);
    return failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
