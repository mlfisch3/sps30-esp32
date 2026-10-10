/**
 * @file acq_queue.c
 * @brief Bounded drop-oldest reading queue - see acq_queue.h.
 *
 * Pure C. Depends on nothing but <string.h>, <assert.h> and the fixed-width
 * integer types, so the host tests compile this exact translation unit.
 */

#include "acq_queue.h"

#include <assert.h>
#include <string.h>

/* The queue-sizing argument in the header is written in bytes per reading. If
 * the record ever grows, that argument is wrong and the build should say so
 * here rather than the board quietly running out of DRAM months later. */
_Static_assert(sizeof(acq_reading_t) == ACQ_READING_BYTES,
               "acq_reading_t changed size; revisit the ACQ_CLIENT_QUEUE_MAX arithmetic");

void acq_queue_init(acq_queue_t *q, acq_reading_t *storage, size_t capacity)
{
    if (q == NULL) {
        return;
    }
    q->slots = storage;
    q->capacity = (storage != NULL) ? capacity : 0;
    q->head = 0;
    q->count = 0;
    q->dropped = 0;
}

bool acq_queue_push(acq_queue_t *q, const acq_reading_t *reading)
{
    if (q == NULL || reading == NULL || q->capacity == 0) {
        return false;
    }

    bool clean = true;
    if (q->count == q->capacity) {
        /* Full: evict the oldest by advancing head. Its slot is about to be
         * overwritten by the write below, which lands on exactly that index. */
        q->head = (q->head + 1) % q->capacity;
        q->count--;
        q->dropped++;
        clean = false;
    }

    const size_t tail = (q->head + q->count) % q->capacity;
    memcpy(&q->slots[tail], reading, sizeof(*reading));
    q->count++;
    return clean;
}

bool acq_queue_pop(acq_queue_t *q, acq_reading_t *out)
{
    if (q == NULL || out == NULL || q->count == 0) {
        return false;
    }
    memcpy(out, &q->slots[q->head], sizeof(*out));
    q->head = (q->head + 1) % q->capacity;
    q->count--;
    return true;
}

size_t acq_queue_count(const acq_queue_t *q)
{
    return (q != NULL) ? q->count : 0;
}

uint32_t acq_queue_dropped(const acq_queue_t *q)
{
    return (q != NULL) ? q->dropped : 0;
}

void acq_queue_reset(acq_queue_t *q)
{
    if (q == NULL) {
        return;
    }
    q->head = 0;
    q->count = 0;
}
