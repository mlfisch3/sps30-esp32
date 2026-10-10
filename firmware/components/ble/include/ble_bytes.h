/**
 * @file ble_bytes.h
 * @brief The inbound byte buffer between a GATT write callback and a client task.
 *
 * Commands arrive on the Nordic UART RX characteristic, on the NimBLE host task,
 * in whatever sized pieces the phone's stack chose. They are consumed later, by
 * the client task in `net_server.c`, which reassembles them into `\n`-terminated
 * JSONL lines. Something has to hold the bytes in between, and that something
 * has to be bounded: the writer is an interrupt-adjacent callback that must not
 * block, and the reader can be a moment behind.
 *
 * Pure C by the rule the rest of this firmware follows - **no ESP-IDF, no
 * NimBLE, no allocation, no locking** - so `test/` compiles this exact
 * translation unit and exercises the overflow behaviour that is otherwise only
 * reachable by mistreating a phone. The caller supplies the storage and the
 * mutual exclusion; `ble_nus.c` holds its per-link mutex across every call.
 *
 * ## Why overflow is not just "drop the excess"
 *
 * This carries a **line-delimited command stream**, and the two obvious
 * discard policies both corrupt it:
 *
 * - Dropping the *oldest* bytes splices the tail of one command onto the head of
 *   the next, and the result can parse. `{"cmd":"pause"}` and `{"cmd":"clean"}`
 *   can leave something that reads as a valid third command nobody sent.
 * - Dropping the *newest* bytes truncates a command mid-line, and because the
 *   protocol's commands lead with their `cmd` field, a truncated line can still
 *   parse as a shorter one.
 *
 * So an overflow instead **discards back to the last line boundary and stays in
 * resync until the next one**: everything from the last complete line up to and
 * including the next newline is thrown away. What the reader gets is always a
 * prefix of some sequence of whole lines the peer actually sent, with an
 * announced gap. That is the same rule `net_server.c` applies to a line longer
 * than its receive buffer, for the same reason, and ::ble_bytes_dropped makes
 * the loss visible rather than silent.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** @brief A bounded FIFO of bytes with line-aware overflow. Fields are private. */
typedef struct {
    uint8_t *storage;
    size_t cap;
    size_t head; /**< index of the oldest byte held */
    size_t len;  /**< bytes held */
    /** True while discarding to the next newline after an overflow. */
    bool resyncing;
    uint32_t dropped; /**< bytes discarded since init, over all overflows */
} ble_bytes_t;

/**
 * @brief Point @p b at caller-owned storage and empty it.
 * @param cap bytes of @p storage; must be at least 1.
 */
void ble_bytes_init(ble_bytes_t *b, uint8_t *storage, size_t cap);

/**
 * @brief Append what the peer wrote.
 *
 * Never partially accepts: either all @p len bytes are taken, or none are and
 * the buffer enters resync (see the file comment). Bytes offered while
 * resyncing are discarded up to and including the next newline.
 *
 * @return true if the bytes were taken whole, false if they were discarded.
 */
bool ble_bytes_write(ble_bytes_t *b, const void *src, size_t len);

/**
 * @brief Take up to @p cap of the oldest bytes.
 * @return how many were copied; 0 when empty.
 */
size_t ble_bytes_read(ble_bytes_t *b, void *dst, size_t cap);

/** @brief How many bytes are waiting to be read. */
size_t ble_bytes_len(const ble_bytes_t *b);

/** @brief Bytes lost to overflow since init. Nonzero means a command vanished. */
uint32_t ble_bytes_dropped(const ble_bytes_t *b);

/** @brief Forget everything held, keeping the drop count. For a new connection. */
void ble_bytes_reset(ble_bytes_t *b);

#ifdef __cplusplus
}
#endif
