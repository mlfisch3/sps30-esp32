/**
 * @file test_store_map.c
 * @brief The occupancy map's classifier, over real pages in a fake ring.
 *
 * `store_map.c` is what turns 4,480 page headers into the seat map the app
 * draws, and the one thing it must never do is say more than the headers say:
 * a cell is data because a real header peeked as data, empty because the flash
 * was genuinely erased, bad because it was neither - never because a fraction
 * said it probably was. So the suite writes real pages with the shipping
 * writer, seals a real anchor page, erases and corrupts real slots, and asserts
 * the map reports exactly what was put there, slot by slot.
 *
 * The numeric states and the 2-bit packing are protocol vocabulary; the
 * PY_PAGE_STATE_* values in wire_vectors_generated.h come from running the
 * real wire.py, and this suite holds the C constants and packing to them.
 * The base64 and JSON layers above the packing are test_wire.c's, held to the
 * same generated lines.
 */

#include <math.h> /* the generated vectors carry NAN and INFINITY cases */
#include <stdio.h>
#include <string.h>

#include "store_codec.h"
#include "store_map.h"
#include "wire_vectors_generated.h"

static int failures = 0;
static int checks = 0;

#define CHECK(cond, ...)                                                       \
    do {                                                                       \
        checks++;                                                              \
        if (!(cond)) {                                                         \
            failures++;                                                        \
            printf("  FAIL %s:%d: ", __FILE__, __LINE__);                      \
            printf(__VA_ARGS__);                                               \
            printf("\n");                                                      \
        }                                                                      \
    } while (0)

/* --- a small ring, in an array --------------------------------------------- */

#define SLOTS 64u

static uint8_t s_ring[SLOTS][STORE_PAGE_BYTES];

static void ring_reset(void)
{
    /* Erased flash is 0xFF. Zeroing would be kinder than a board ever gets. */
    memset(s_ring, 0xFF, sizeof(s_ring));
}

/** Seal one real data page of @p count readings into @p slot. */
static void put_data_page(uint32_t slot, uint32_t seq, uint32_t count)
{
    store_writer_t w;
    store_writer_init(&w, seq, 20260807u, 1754500000000LL, 1000, -240, false);
    for (uint32_t i = 1; i <= count; i++) {
        store_reading_t r;
        memset(&r, 0, sizeof(r));
        r.index = i;
        r.epoch_ms = 1754500000000LL + (int64_t)i * 1000LL;
        for (int f = 0; f < STORE_FIELDS; f++) {
            r.values[f] = (float)((i * 13u + (uint32_t)f * 7u) % 997u) / 100.0f;
        }
        CHECK(store_writer_add(&w, &r), "reading %u would not pack", i);
    }
    CHECK(store_writer_seal(&w, s_ring[slot], false), "page would not seal");
}

/** Seal one real anchor page into @p slot. */
static void put_anchor_page(uint32_t slot, uint32_t seq)
{
    store_anchor_t anchor = {
        .rel_ms = store_rel_pack(3u, 123456u),
        .epoch_ms = 1754500001234LL,
        .utc_offset_min = -240,
    };
    CHECK(store_seal_anchor_page(s_ring[slot], seq, 20260807u, 1754500000000LL,
                                 store_rel_pack(3u, 123456u), -240, &anchor, 1),
          "anchor page would not seal");
}

static uint32_t s_refuse_slot = UINT32_MAX;

static bool slot_read(void *ctx, uint32_t slot, uint8_t *out, size_t len)
{
    (void)ctx;
    if (slot >= SLOTS || len > STORE_PAGE_BYTES || slot == s_refuse_slot) {
        return false;
    }
    memcpy(out, s_ring[slot], len);
    return true;
}

static store_slot_reader_t reader_of(void)
{
    store_slot_reader_t src;
    src.read = slot_read;
    src.ctx = NULL;
    src.slots = SLOTS;
    return src;
}

/* --- the tests -------------------------------------------------------------- */

static void test_states_are_the_protocol_vocabulary(void)
{
    printf("the states and the packing are wire.py's\n");
    CHECK(STORE_MAP_PAGE_EMPTY == PY_PAGE_STATE_EMPTY, "EMPTY drifted");
    CHECK(STORE_MAP_PAGE_DATA == PY_PAGE_STATE_DATA, "DATA drifted");
    CHECK(STORE_MAP_PAGE_ANCHOR == PY_PAGE_STATE_ANCHOR, "ANCHOR drifted");
    CHECK(STORE_MAP_PAGE_BAD == PY_PAGE_STATE_BAD, "BAD drifted");

    /* The packed bytes under every cells vector, before base64: page i in
     * bits (i % 4) * 2. The base64 layer is asserted in test_wire.c, where
     * the encoder that emits it lives. */
    for (size_t v = 0; v < sizeof(WIRE_CELLS_VECTORS) / sizeof(WIRE_CELLS_VECTORS[0]); v++) {
        const wire_cells_vector_t *vec = &WIRE_CELLS_VECTORS[v];
        uint8_t packed[8];
        memset(packed, 0, sizeof(packed));
        for (size_t i = 0; i < vec->pages; i++) {
            store_map_cell_set(packed, (uint32_t)i, vec->states[i]);
        }
        for (size_t i = 0; i < vec->pages; i++) {
            CHECK(store_map_cell_get(packed, (uint32_t)i) == vec->states[i],
                  "vector %zu page %zu did not round-trip", v, i);
        }
    }

    /* The worked example from wire.py's docstring: [DATA, EMPTY, ANCHOR, BAD]
     * is 0b11_10_00_01. */
    uint8_t cell = 0;
    store_map_cell_set(&cell, 0, STORE_MAP_PAGE_DATA);
    store_map_cell_set(&cell, 1, STORE_MAP_PAGE_EMPTY);
    store_map_cell_set(&cell, 2, STORE_MAP_PAGE_ANCHOR);
    store_map_cell_set(&cell, 3, STORE_MAP_PAGE_BAD);
    CHECK(cell == 0xE1, "packed byte is 0x%02X, want 0xE1", cell);

    /* Setting a cell twice must replace, not accumulate: the scan reuses one
     * buffer per refresh. */
    store_map_cell_set(&cell, 2, STORE_MAP_PAGE_EMPTY);
    CHECK(store_map_cell_get(&cell, 2) == STORE_MAP_PAGE_EMPTY, "a cell did not clear");
    CHECK(store_map_cell_get(&cell, 3) == STORE_MAP_PAGE_BAD, "a neighbour was disturbed");

    CHECK(STORE_MAP_CELLS_BYTES(1) == 1u, "1 page is 1 byte");
    CHECK(STORE_MAP_CELLS_BYTES(4) == 1u, "4 pages are 1 byte");
    CHECK(STORE_MAP_CELLS_BYTES(5) == 2u, "5 pages are 2 bytes");
    CHECK(STORE_MAP_CELLS_BYTES(4480) == 1120u, "the ring is 1,120 bytes");
    CHECK(STORE_MAP_CELLS_BYTES(4992) == 1248u, "the partition is 1,248 bytes");
}

static void test_classify_reads_the_header_the_way_the_boot_scan_does(void)
{
    printf("classification is the boot scan's standard, no more\n");
    ring_reset();

    uint8_t erased[STORE_HEADER_BYTES];
    memset(erased, 0xFF, sizeof(erased));
    CHECK(store_map_classify(erased) == STORE_MAP_PAGE_EMPTY, "erased is empty");

    put_data_page(0, 1, 5);
    CHECK(store_map_classify(s_ring[0]) == STORE_MAP_PAGE_DATA, "a data page is data");

    put_anchor_page(1, 2);
    CHECK(store_map_classify(s_ring[1]) == STORE_MAP_PAGE_ANCHOR,
          "an anchor page is anchor, not data");

    /* A torn write: the first bytes went, the rest is still erased. Neither
     * blank nor a page, and the map must say so rather than guess. */
    uint8_t torn[STORE_HEADER_BYTES];
    memset(torn, 0xFF, sizeof(torn));
    torn[0] = 0x53;
    CHECK(store_map_classify(torn) == STORE_MAP_PAGE_BAD, "a torn header is bad");

    /* A plausible magic with an impossible count: store_peek_page's own
     * rejection, inherited rather than reimplemented. */
    uint8_t liar[STORE_HEADER_BYTES];
    memset(liar, 0, sizeof(liar));
    liar[0] = 0x53;
    liar[1] = 0x33;
    liar[2] = 1;    /* version */
    liar[38] = 200; /* count: past STORE_MAX_READINGS */
    CHECK(store_map_classify(liar) == STORE_MAP_PAGE_BAD, "an impossible count is bad");

    uint8_t blank[STORE_PAGE_BYTES];
    memset(blank, 0xFF, sizeof(blank));
    CHECK(store_map_classify_blank(blank, sizeof(blank)) == STORE_MAP_PAGE_EMPTY,
          "all-FF flash is empty");
    blank[511] = 0xFE;
    CHECK(store_map_classify_blank(blank, sizeof(blank)) == STORE_MAP_PAGE_DATA,
          "one written bit anywhere is data");
}

static void test_scan_reports_the_ring_slot_by_slot(void)
{
    printf("a scan answers for every slot, and only from its header\n");
    ring_reset();

    /* A young board: pages 0..9 recorded, an anchor at 4, slot 7 corrupted by
     * a torn write, everything past 9 still erased. */
    for (uint32_t slot = 0; slot < 10; slot++) {
        if (slot == 4) {
            put_anchor_page(slot, slot + 1);
        } else {
            put_data_page(slot, slot + 1, 3 + slot);
        }
    }
    memset(s_ring[7], 0xFF, STORE_PAGE_BYTES);
    s_ring[7][0] = 0x53; /* the torn first byte of a write that never finished */

    const store_slot_reader_t src = reader_of();
    uint8_t cells[STORE_MAP_CELLS_BYTES(SLOTS)];
    CHECK(store_map_scan(&src, cells, sizeof(cells)), "the scan refused a fitting buffer");

    for (uint32_t slot = 0; slot < SLOTS; slot++) {
        uint8_t want = STORE_MAP_PAGE_EMPTY;
        if (slot < 10) {
            want = STORE_MAP_PAGE_DATA;
        }
        if (slot == 4) {
            want = STORE_MAP_PAGE_ANCHOR;
        }
        if (slot == 7) {
            want = STORE_MAP_PAGE_BAD;
        }
        CHECK(store_map_cell_get(cells, slot) == want,
              "slot %u is %u, want %u", slot, store_map_cell_get(cells, slot), want);
    }

    /* Flash that cannot be read is not "empty": a reader failure is reported
     * as bad, because an empty cell claims the flash was seen and was erased. */
    s_refuse_slot = 2;
    CHECK(store_map_scan(&src, cells, sizeof(cells)), "the scan died on a bad read");
    CHECK(store_map_cell_get(cells, 2) == STORE_MAP_PAGE_BAD,
          "an unreadable slot must be bad, not empty");
    s_refuse_slot = UINT32_MAX;

    /* A buffer that cannot hold the map is a caller bug and is refused whole:
     * a map cut short would be a partition that looks smaller than it is. */
    CHECK(!store_map_scan(&src, cells, STORE_MAP_CELLS_BYTES(SLOTS) - 1),
          "a short buffer must be refused");
}

int main(void)
{
    test_states_are_the_protocol_vocabulary();
    test_classify_reads_the_header_the_way_the_boot_scan_does();
    test_scan_reports_the_ring_slot_by_slot();
    printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
