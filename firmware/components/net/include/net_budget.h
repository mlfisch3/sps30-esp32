/**
 * @file net_budget.h
 * @brief Whether the heap can afford one more client, and how many in all.
 *
 * Pure arithmetic over three numbers, in a header of its own so the host pack
 * can drive it: `net_server.c` is FreeRTOS and lwIP and does not compile off the
 * board, and this decision is the one that has to be right.
 *
 * ## Why a reserve, and not simply "did the allocation succeed"
 *
 * It used to be exactly that: a client cost an 11,400 B slot, the fifth
 * `calloc` failed, and the board refused it in words. That worked by accident.
 * Shrinking the slot to 6,336 B raised the ceiling and took the accident away,
 * and what turned up underneath was measured on the board on 2026-08-04:
 *
 * ```
 * clients=5  heap 20008 B free   healthy
 * clients=6  heap   848 B free   W wifi:m f null
 * clients=6  heap   412 B free   W wifi:m f null  x3
 *            -> the AP association dropped and ALL SIX clients were reset
 * ```
 *
 * The slot and the task stack are not the whole cost of a client. lwIP takes
 * about 1.7 KB more per socket as data flows, and the Wi-Fi driver allocates
 * from the same heap on every frame it handles. So a sixth client fits, right
 * up until the radio underneath it cannot get a buffer - and then the failure is
 * not one refused connection but every connection dropped at once, which at a
 * public demonstration is the difference between "it is busy" and "it is
 * broken".
 *
 * A client that is refused costs one person a retry. A radio that starves costs
 * everybody their session. So the board keeps ::NET_HEAP_RESERVE back for the
 * things that cannot be refused, and turns away the connection that would eat
 * it - which is a decision that has to be made *before* the allocation, because
 * an allocation that succeeds has already spent the money.
 */
#ifndef NET_BUDGET_H
#define NET_BUDGET_H

#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief Heap held back for the radio, the store and everything unrefusable.
 *
 * 20 KiB, chosen against the measurement above rather than picked: five clients
 * sat at 20,008 B free and ran clean, and the run that starved the Wi-Fi driver
 * was at 412 B. It is deliberately more than the ~1.7 KB of lwIP a socket goes
 * on to take after it has been accepted, because that is not the only thing
 * still to come - a `get_file` borrows 3.0 KB, a `put_file` 2 KB, and a
 * preserved-segment delivery 2.7 KB, and all three happen on a connection that
 * was accepted long before.
 *
 * Raise it if the radio is ever seen to starve again; lower it only against a
 * board run, never against arithmetic.
 */
#define NET_HEAP_RESERVE 20480u

/**
 * @brief What a client goes on to cost after it has been accepted.
 *
 * The slot and the task stack are taken up front and are exact. lwIP is not: a
 * socket carrying traffic takes buffers of its own from the same heap, and they
 * are not charged until the data flows. Measured on the board 2026-08-04, four
 * connected clients against an idle board:
 *
 * ```
 * 0 clients   heap 91492 B free
 * 4 clients   heap 33672 B free    -> 14455 B each, against 13504 up front
 * ```
 *
 * 1 KiB, rounded down from the 951 B that leaves, because rounding this *up*
 * costs a connection the board could have carried and rounding it down is what
 * the reserve is there to absorb.
 *
 * It exists so one number answers both questions. Without it the boot log
 * divided the heap by the up-front cost and promised five clients on a board
 * that then refused the fifth, and a log that disagrees with the behaviour is
 * how somebody comes to trust the wrong one.
 */
#define NET_CLIENT_HEAP_OVERHEAD 1024u

/**
 * @brief True when @p free_bytes can take another client and keep the reserve.
 *
 * @param free_bytes what the heap says right now.
 * @param each what one client costs up front: its slot plus its task stack.
 * @param reserve what must survive the accept; ::NET_HEAP_RESERVE in the
 *        firmware, a parameter here so the tests can pin the edges.
 */
static inline bool net_budget_admits(size_t free_bytes, size_t each, size_t reserve)
{
    /* `each == 0` is refused rather than waved through, so this and
     * ::net_budget_capacity can never disagree about the same heap - capacity
     * returns 0 there, and a rule whose two halves tell different stories is
     * how the boot log came to promise six clients on a board carrying five.
     *
     * Written as a subtraction on the left so it cannot wrap: `free < each +
     * reserve` overflows for absurd inputs and would admit a client on a heap
     * that has nothing. */
    if (each == 0u) {
        return false;
    }
    return free_bytes >= reserve && (free_bytes - reserve) >= each;
}

/**
 * @brief How many clients @p free_bytes can hold while keeping @p reserve.
 *
 * What the boot log prints. It is a **forecast, not a promise**: it divides one
 * heap reading by a typical client, while ::net_budget_admits re-reads the heap
 * for every connection. A board that forecast four was observed to take five
 * probes that were only watching readings, because a client that never asks for
 * a backfill costs less than the measured average. Under-promising is the safe
 * direction and the intended one - the accept path is the truth, and it is the
 * one that keeps the reserve.
 */
static inline size_t net_budget_capacity(size_t free_bytes, size_t each, size_t reserve)
{
    if (each == 0u || free_bytes <= reserve) {
        return 0u;
    }
    return (free_bytes - reserve) / each;
}

/**
 * @brief What `hello` should say @p transport_max clients means, on this heap.
 *
 * `hello`'s `max_clients` used to be the size of the socket array, and on a board
 * whose heap admits three that is not a capacity, it is a promise of five
 * connections that will each be refused. The refusal itself was always honest -
 * "the sensor is out of memory and cannot take another connection" - so the only
 * thing lying was the number a client was shown before it tried.
 *
 * The honest number is the one the accept path will actually honour: whoever is
 * on the board already, plus what the heap has room for, and never more than the
 * link itself can carry. It moves with the heap, which is the point - a board
 * serving three and out of room says three, and says four again the moment one
 * leaves. Under-promising is the safe direction, exactly as it is for
 * ::net_budget_capacity: the accept path re-reads the heap and is the truth.
 *
 * @param transport_max what this link could carry if memory were free -
 *        `wire_max_clients_for`, so Bluetooth's one stays one.
 * @param connected clients on the board now, over every transport, since they
 *        all spend the same heap.
 */
static inline unsigned net_budget_advertised_clients(unsigned transport_max, unsigned connected,
                                                     size_t free_bytes, size_t each,
                                                     size_t reserve)
{
    size_t total = (size_t)connected + net_budget_capacity(free_bytes, each, reserve);
    if (total > (size_t)transport_max) {
        total = (size_t)transport_max;
    }
    /* Never zero. A client reading this frame is itself proof that one
     * connection is possible, and "0 of 0 connected" is not a state the app has
     * any sensible thing to say about. */
    if (total == 0u) {
        total = 1u;
    }
    return (unsigned)total;
}

#ifdef __cplusplus
}
#endif

#endif /* NET_BUDGET_H */
