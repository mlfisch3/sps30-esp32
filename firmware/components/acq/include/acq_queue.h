/**
 * @file acq_queue.h
 * @brief A bounded, drop-oldest queue of readings - the seam a transport reads.
 *
 * This is the shape of the boundary between acquisition and whatever ships the
 * data off the board. Acquisition pushes; a transport pops. Neither knows
 * anything about the other, so the network layer is an addition here rather
 * than a change: it creates a queue, registers it as a sink (see `acq.h`), and
 * drains it. **No transport is implemented yet** and none is implied by this
 * file.
 *
 * Pure C: no ESP-IDF, no allocation, no locking, so the host tests compile this
 * exact translation unit. Storage is supplied by the caller, which keeps the
 * memory cost of a client visible at its declaration instead of buried in a
 * malloc - the whole point of the sizing argument below.
 *
 * @note Not thread-safe, by the same convention as `sps30.h`. A queue shared
 *       between the acquisition task and a transport task must be serialised by
 *       its owner. Keeping the lock outside is what lets this be pure C and
 *       therefore testable on the host.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "sps30_codec.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Set on readings taken during the post-start settling spike. */
#define ACQ_FLAG_SETTLING 0x01u

/**
 * @brief One recorded reading, as it sits in a queue.
 *
 * Laid out largest-first so it packs to exactly 56 bytes with no interior
 * padding; ::ACQ_READING_BYTES asserts that, because the queue-sizing
 * arithmetic below is written in terms of it and must not drift silently.
 *
 * The timestamp is monotonic uptime, not wall-clock time. Nothing on this board
 * knows the date: there is no RTC and SNTP needs a network, which is out of
 * scope. A transport that does know the time can convert on the way out, and
 * uptime is the more useful of the two for spotting a gap anyway.
 */
typedef struct {
    int64_t uptime_us;           /**< esp_timer_get_time() when the value was read */
    sps30_measurement_t values;  /**< the ten outputs, in contract order */
    uint32_t sequence;           /**< 1-based position within the session */
    uint16_t session;            /**< which acquisition session; see acq.h */
    uint8_t flags;               /**< ::ACQ_FLAG_SETTLING */
    uint8_t reserved;            /**< explicit pad; keeps sizeof stable */
} acq_reading_t;

/** The size the sizing arithmetic assumes. Asserted in acq_queue.c. */
#define ACQ_READING_BYTES 56u

/**
 * @brief Readings buffered per connected client before the oldest is dropped.
 *
 * The Raspberry Pi daemon uses `CLIENT_QUEUE_MAX = 1000` and queues serialised
 * JSON, which is roughly 200 bytes a message and so about **200 KB per
 * client**. This part cannot afford one such client, let alone eight. The
 * arithmetic, from the figures this board actually reports at boot:
 *
 *     Measured free DRAM at boot (heap_init):     179 KiB
 *
 *     Committed once the network layer lands, all of it out of that 179 KiB:
 *       Wi-Fi driver, static + dynamic buffers     ~50 KiB
 *       lwIP: pbuf pool, PCBs, per-socket buffers  ~30 KiB
 *       core task stacks (main, acq, tcpip, srv)   ~15 KiB
 *       8 client tasks at 3 KiB                    ~24 KiB
 *       OTA / TLS / logging / headroom             ~20 KiB
 *                                                  -------
 *                                                  ~139 KiB
 *     Left for client reading queues:               ~40 KiB
 *     Divided by MAX_CLIENTS = 8:                     5 KiB each
 *     At 56 bytes a reading:                       ~91 readings
 *
 * So the memory ceiling is about 90, and **32 is chosen instead**, for a reason
 * that is not arithmetic: at the 1 Hz the SPS30 publishes, 32 readings is 32
 * seconds of backlog. A client that has not drained for half a minute is not
 * briefly busy, it is gone, and the cure for a gone client is to notice and let
 * it re-sync on reconnect - not to hold its data longer. A deeper queue buys no
 * correctness, only a later admission. Duty-cycled profiles record one reading
 * per cycle, where 32 is hours rather than seconds.
 *
 * What it costs: 8 x 32 x 56 = 14,336 bytes, **14 KiB**, under 8% of free DRAM,
 * against 1.6 MB for eight Pi-sized queues - which does not fit in the part's
 * entire 512 KB of SRAM, never mind its DRAM.
 *
 * Most of that saving is not the smaller count, it is queueing the 56-byte
 * binary record rather than ~200 bytes of serialised JSON. Serialising at send
 * time instead of at enqueue time is what makes 32 deep cost 1.8 KiB.
 *
 * ## Measured once the network layer landed
 *
 * The estimates above were made before there was anything to measure. The board
 * now reports, from `heap_caps_get_free_size(MALLOC_CAP_8BIT)` at the points
 * `main.c` logs it:
 *
 *     free at boot, before any networking            245 KiB
 *     free with Wi-Fi, SNTP and the server up        187 KiB   (networking: 58 KiB)
 *
 * **The number is unchanged and the argument holds** - 32 is a behavioural
 * choice, made above on how long a backlog is worth keeping, and no measurement
 * moves it.
 *
 * **The headroom is not what an earlier version of this block said.** It read
 * "5.6 KiB per streaming client, 23.5 KiB of static DRAM for the eight slots,
 * so the real headroom is larger than the budget assumed". That was measured
 * before the transfer buffers and Bluetooth existed and every part of it is now
 * false. `net_server.c` carries what replaced it, measured on hardware rather
 * than predicted:
 *
 *     a client now costs                              11 KiB
 *       (2 KiB out, 2 KiB in, 2 KiB import scratch, ~3 KiB page cursor)
 *     eight of them                                   87 KiB
 *     enabling Bluetooth, out of dram0_0_seg          56 KiB
 *     free heap when the slots were still static       7 KiB - every
 *                                                     connection refused
 *
 * The slots are heap-allocated for that reason and are not static DRAM any more;
 * `net_server.c`'s comment on `s_clients` is the authority on that, not this.
 *
 * So: read this block for why the queue is 32 deep. Do **not** read it as
 * headroom to spend. Anything that would raise ::ACQ_CLIENT_QUEUE_MAX or
 * ::ACQ_MAX_CLIENTS is spending 11 KiB a client out of a budget that has already
 * had a board refusing connections at the bottom of it, and the real ceiling on
 * simultaneous clients has not been measured - `heap_caps_calloc` in `take_slot`
 * has no floor, so the eighth client simply fails to allocate. That measurement
 * needs the board.
 */
#define ACQ_CLIENT_QUEUE_MAX 32u

/**
 * @brief Sockets the lwIP defaults allow, minus the listener and OTA.
 *
 * `LWIP_MAX_SOCKETS` defaults to 10 and can be raised to 16; reserving two
 * leaves eight. Stated here because it is the divisor in the arithmetic above.
 */
#define ACQ_MAX_CLIENTS 8u

/**
 * @brief A ring of readings over caller-supplied storage.
 *
 * Only readings belong here. The archive and file-transfer paths of the Pi
 * protocol must **not** share this queue: it drops, and dropping a chunk of an
 * archive silently corrupts it. The Pi kept a lossy/lossless flag on every
 * queued message for exactly that reason; separating the channels enforces the
 * same invariant structurally instead, so there is no flag left to forget to
 * set. A lossless path needs backpressure, which is a different mechanism, not
 * a different bit.
 */
typedef struct {
    acq_reading_t *slots; /**< caller's storage, @c capacity entries */
    size_t capacity;
    size_t head;    /**< index of the oldest queued reading */
    size_t count;   /**< how many are queued */
    uint32_t dropped; /**< cumulative readings lost to overflow */
} acq_queue_t;

/**
 * @brief Point @p q at @p storage and empty it.
 * @param storage at least @p capacity entries, owned by the caller.
 */
void acq_queue_init(acq_queue_t *q, acq_reading_t *storage, size_t capacity);

/**
 * @brief Append @p reading, dropping the oldest if the ring is full.
 *
 * Drop-oldest rather than reject-newest: a stalled client that comes back
 * should see the most recent air, not a half-minute-old snapshot it has to
 * scroll past.
 *
 * @return true if nothing was dropped, false if the oldest was evicted to make
 *         room. Either way the reading is queued - the return value is a signal
 *         worth logging, not a failure to handle.
 */
bool acq_queue_push(acq_queue_t *q, const acq_reading_t *reading);

/**
 * @brief Remove the oldest reading into @p out.
 * @return false if the queue is empty, leaving @p out untouched.
 */
bool acq_queue_pop(acq_queue_t *q, acq_reading_t *out);

/** @brief How many readings are queued. */
size_t acq_queue_count(const acq_queue_t *q);

/** @brief How many readings this queue has ever dropped. */
uint32_t acq_queue_dropped(const acq_queue_t *q);

/** @brief Discard everything queued, keeping the drop counter. */
void acq_queue_reset(acq_queue_t *q);

#ifdef __cplusplus
}
#endif
