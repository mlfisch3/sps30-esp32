/**
 * @file ble_bytes.c
 * @brief See `ble_bytes.h`. A ring buffer whose only subtlety is what it throws
 *        away when it is full.
 */

#include "ble_bytes.h"

#include <string.h>

#define NEWLINE ((uint8_t)'\n')

static uint8_t at(const ble_bytes_t *b, size_t i)
{
    return b->storage[(b->head + i) % b->cap];
}

void ble_bytes_init(ble_bytes_t *b, uint8_t *storage, size_t cap)
{
    b->storage = storage;
    b->cap = cap;
    b->head = 0;
    b->len = 0;
    b->resyncing = false;
    b->dropped = 0;
}

void ble_bytes_reset(ble_bytes_t *b)
{
    b->head = 0;
    b->len = 0;
    b->resyncing = false;
}

size_t ble_bytes_len(const ble_bytes_t *b)
{
    return b->len;
}

uint32_t ble_bytes_dropped(const ble_bytes_t *b)
{
    return b->dropped;
}

/** Drop the partial line at the end, leaving only whole lines behind. */
static void discard_partial_tail(ble_bytes_t *b)
{
    size_t whole = 0; /* bytes up to and including the last newline held */
    for (size_t i = 0; i < b->len; i++) {
        if (at(b, i) == NEWLINE) {
            whole = i + 1;
        }
    }
    b->dropped += (uint32_t)(b->len - whole);
    b->len = whole;
}

bool ble_bytes_write(ble_bytes_t *b, const void *src, size_t len)
{
    const uint8_t *bytes = (const uint8_t *)src;

    if (len == 0) {
        return !b->resyncing;
    }

    if (b->resyncing) {
        /* Everything up to the newline belongs to the command that was cut in
         * half, so it goes; the remainder is the start of a line the peer sent
         * whole, and is worth keeping. */
        for (size_t i = 0; i < len; i++) {
            if (bytes[i] != NEWLINE) {
                continue;
            }
            b->dropped += (uint32_t)(i + 1);
            b->resyncing = false;
            (void)ble_bytes_write(b, bytes + i + 1, len - i - 1);
            return false; /* this call still lost bytes, whatever the tail did */
        }
        b->dropped += (uint32_t)len;
        return false;
    }

    if (len > b->cap - b->len) {
        /* Refused whole. Anything already buffered past the last line boundary
         * would be the head of a command whose tail we are about to lose. */
        b->dropped += (uint32_t)len;
        discard_partial_tail(b);
        b->resyncing = true;
        return false;
    }

    for (size_t i = 0; i < len; i++) {
        b->storage[(b->head + b->len + i) % b->cap] = bytes[i];
    }
    b->len += len;
    return true;
}

size_t ble_bytes_read(ble_bytes_t *b, void *dst, size_t cap)
{
    uint8_t *out = (uint8_t *)dst;
    const size_t n = cap < b->len ? cap : b->len;
    for (size_t i = 0; i < n; i++) {
        out[i] = at(b, i);
    }
    b->head = (b->head + n) % b->cap;
    b->len -= n;
    if (b->len == 0) {
        b->head = 0; /* keeps the common case contiguous, and eases debugging */
    }
    return n;
}
