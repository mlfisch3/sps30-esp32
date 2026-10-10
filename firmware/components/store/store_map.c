/**
 * @file store_map.c
 * @brief Classifying every page of a region for the occupancy map. Pure code.
 *
 * See store_map.h for what a cell can honestly say and why the peek standard
 * is the boot scan's. Nothing here allocates, blocks, writes or touches
 * hardware, which is what lets `test_store_map.c` drive it over an array.
 */
#include "store_map.h"

#include <string.h>

uint8_t store_map_classify(const uint8_t header[STORE_HEADER_BYTES])
{
    store_header_t peek;
    if (store_peek_page(header, &peek)) {
        return peek.anchor ? STORE_MAP_PAGE_ANCHOR : STORE_MAP_PAGE_DATA;
    }
    return store_map_classify_blank(header, STORE_HEADER_BYTES) == STORE_MAP_PAGE_EMPTY
               ? STORE_MAP_PAGE_EMPTY
               : STORE_MAP_PAGE_BAD;
}

uint8_t store_map_classify_blank(const uint8_t *bytes, size_t len)
{
    for (size_t i = 0; i < len; i++) {
        if (bytes[i] != 0xFF) {
            return STORE_MAP_PAGE_DATA;
        }
    }
    return STORE_MAP_PAGE_EMPTY;
}

void store_map_cell_set(uint8_t *cells, uint32_t page, uint8_t state)
{
    const uint32_t shift = (page & 3u) * 2u;
    uint8_t *cell = &cells[page >> 2];
    *cell = (uint8_t)((*cell & ~(0x3u << shift)) | ((state & 0x3u) << shift));
}

uint8_t store_map_cell_get(const uint8_t *cells, uint32_t page)
{
    return (uint8_t)((cells[page >> 2] >> ((page & 3u) * 2u)) & 0x3u);
}

bool store_map_scan(const store_slot_reader_t *src, uint8_t *cells, size_t cells_cap)
{
    if (src == NULL || src->read == NULL || cells == NULL ||
        cells_cap < STORE_MAP_CELLS_BYTES(src->slots)) {
        return false;
    }
    memset(cells, 0, STORE_MAP_CELLS_BYTES(src->slots));
    for (uint32_t slot = 0; slot < src->slots; slot++) {
        uint8_t header[STORE_HEADER_BYTES];
        uint8_t state = STORE_MAP_PAGE_BAD;
        if (src->read(src->ctx, slot, header, sizeof(header))) {
            state = store_map_classify(header);
        }
        store_map_cell_set(cells, slot, state);
    }
    return true;
}
