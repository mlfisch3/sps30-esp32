/**
 * @file store_map.h
 * @brief Classifying every page of a region for the occupancy map. Pure code.
 *
 * The `storage_map` message draws the store the way a seat map draws a cabin:
 * one cell per 512-byte page, coloured by what the page's own header says.
 * This file is the part of that which can be checked on a workstation - the
 * classification and the 2-bit packing - lifted out of `store.c` for the same
 * reason `store_walk.c` was: give it a ::store_slot_reader_t and it maps any
 * ring, and the host pack gives it an array.
 *
 * ## What a cell can honestly say
 *
 * A state comes from the same 48-byte header peek the boot scan trusts, and
 * inherits its standard exactly: ::store_peek_page does not check the CRC, so
 * ::STORE_MAP_PAGE_DATA means "holds a plausible page", not "decoded end to
 * end". Checking 4,992 CRCs per refresh would read the whole partition to
 * upgrade a display, which is the trade `store_codec.h` already refused for
 * the boot scan, for the same reason, at the same call site.
 *
 * The map never writes. It is a read of the ring that changes nothing, which
 * is the whole of its safety story and the reason it can run while the writer
 * runs: a page half-written under the peek classifies as ::STORE_MAP_PAGE_BAD
 * or ::STORE_MAP_PAGE_EMPTY for one refresh and as data on the next, which is
 * the honest answer to "what is on this page right now".
 *
 * The numeric states are protocol vocabulary - `wire.PAGE_STATE_*` in
 * `wire.py`, mirrored in Kotlin - and the wire vectors hold all three
 * implementations to the same values and the same packing.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "store_codec.h"
#include "store_walk.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Erased flash: no header, nothing recorded. `wire.PAGE_STATE_EMPTY`. */
#define STORE_MAP_PAGE_EMPTY 0u
/** A plausible reading page (header peek; CRC unchecked). `wire.PAGE_STATE_DATA`. */
#define STORE_MAP_PAGE_DATA 1u
/** A time-equivalence page: infrastructure, not readings. `wire.PAGE_STATE_ANCHOR`. */
#define STORE_MAP_PAGE_ANCHOR 2u
/** Not erased and not a page: a torn write or corruption. `wire.PAGE_STATE_BAD`. */
#define STORE_MAP_PAGE_BAD 3u

/** Bytes the packed cells of @p pages pages occupy: four pages per byte. */
#define STORE_MAP_CELLS_BYTES(pages) (((pages) + 3u) / 4u)

/**
 * @brief What one page's header says it is.
 *
 * @param header the first ::STORE_HEADER_BYTES of the page.
 * @return a `STORE_MAP_PAGE_*` state.
 */
uint8_t store_map_classify(const uint8_t header[STORE_HEADER_BYTES]);

/**
 * @brief Blank-or-not, for regions whose records are not pages.
 *
 * The preserved-segment header region holds 128-byte records, so a page there
 * has no header to peek. All-0xFF is erased flash and anything else is data;
 * the map does not pretend to know more about a region whose format it does
 * not read.
 */
uint8_t store_map_classify_blank(const uint8_t *bytes, size_t len);

/** @brief Write @p state into the 2-bit cell of page @p page. */
void store_map_cell_set(uint8_t *cells, uint32_t page, uint8_t state);

/** @brief The 2-bit cell of page @p page. */
uint8_t store_map_cell_get(const uint8_t *cells, uint32_t page);

/**
 * @brief Classify every slot of a ring into packed cells.
 *
 * Reads ::STORE_HEADER_BYTES per slot - the map is a header walk, never a body
 * walk. A slot whose read fails classifies as ::STORE_MAP_PAGE_BAD: the honest
 * state for flash that cannot be read is not "empty".
 *
 * @param cells_cap bytes available at @p cells; must cover
 *        ::STORE_MAP_CELLS_BYTES(src->slots).
 * @return false when @p cells cannot hold the map, which is a caller bug, not
 *         a flash condition.
 */
bool store_map_scan(const store_slot_reader_t *src, uint8_t *cells, size_t cells_cap);

#ifdef __cplusplus
}
#endif
