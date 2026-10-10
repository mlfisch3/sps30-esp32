/**
 * @file test_net_budget.c
 * @brief Host tests for the rule that decides whether a connection is accepted.
 *
 * The defect these exist for was found on the board on 2026-08-04, and it is
 * the second one in the same afternoon. The first was that a client slot cost
 * 11,400 B and the board refused a fifth connection; shrinking the slot to
 * 6,336 B fixed that and uncovered this, which is worse:
 *
 * ```
 * clients=5  heap 20008 B free   healthy
 * clients=6  heap   848 B free   W wifi:m f null
 * clients=6  heap   412 B free   W wifi:m f null  x3
 *            -> the AP association dropped and ALL SIX clients were reset
 * ```
 *
 * The old ceiling was an accident that worked: the fifth `calloc` failed, and a
 * failed allocation is a refusal. With the slot small enough to fit six, the
 * allocation stopped failing and nothing else said no - so the board accepted a
 * client it could not carry, starved the Wi-Fi driver, and dropped everybody.
 *
 * One refused client costs one person a retry. A starved radio costs every
 * connected device its session at once, which at a public demonstration is the
 * difference between "it is busy" and "it is broken". So the decision moved in
 * front of the allocation and got a reserve to defend, and this drives it:
 *
 *   * a heap that cannot pay for the client AND keep the reserve says no;
 *   * the boundary is exact in both directions, because an off-by-one here is
 *     either a refused client that would have been fine or the crash above;
 *   * the arithmetic cannot wrap, whatever it is asked;
 *   * the capacity the boot log forecasts is consistent with the rule that
 *     admits, for one fixed idea of what a client costs. On the board the two
 *     can still differ - a client only watching readings costs less than the
 *     measured average, and a board forecasting four took five such probes -
 *     which is under-promising, and the safe direction.
 *
 * `net_server.c` is FreeRTOS and lwIP and does not compile here, which is why
 * the rule lives in a header of its own. Builds and runs on the host with no
 * ESP-IDF: see the Makefile.
 */

#include <stdio.h>

#include "net_budget.h"

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

/** What the board actually measured on 2026-08-04, so the numbers are real. */
#define BOARD_FREE_AT_BOOT 91492u
/** Slot + stack + ::NET_CLIENT_HEAP_OVERHEAD, which is what a client really is. */
#define BOARD_CLIENT_BYTES (6336u + 7168u + NET_CLIENT_HEAP_OVERHEAD)

/**
 * The run that dropped everybody, replayed as a decision.
 *
 * Five clients left 20,008 B and ran clean; a sixth left 412 B and the radio
 * starved. The rule has to say yes to the fifth and no to the sixth, and it is
 * the second half that is the fix.
 */
static void test_the_run_that_starved_the_radio_is_refused(void)
{
    /* Free heap as each client is being considered, measured on the board. */
    const size_t free_at_4th = 48127u; /* three connected */
    const size_t free_at_5th = 33672u; /* four connected - the observed refusal */
    const size_t free_at_6th = 19300u; /* five connected, on the run that fell over */

    CHECK(net_budget_admits(free_at_4th, BOARD_CLIENT_BYTES, NET_HEAP_RESERVE),
          "the fourth client was refused; three or four at once is the case the "
          "controller exists to serve");
    CHECK(!net_budget_admits(free_at_5th, BOARD_CLIENT_BYTES, NET_HEAP_RESERVE),
          "a fifth client was admitted at 33672 B free");
    CHECK(!net_budget_admits(free_at_6th, BOARD_CLIENT_BYTES, NET_HEAP_RESERVE),
          "the sixth client was admitted; that is the run where the Wi-Fi driver "
          "failed an allocation and all six connections were reset");
}

/**
 * The boundary, from both sides.
 *
 * Exactly enough is enough - refusing there would cost a connection that would
 * have been fine - and one byte short is short.
 */
static void test_the_boundary_is_exact(void)
{
    const size_t each = 14528u;
    const size_t reserve = 20480u;
    const size_t exactly = each + reserve;

    CHECK(net_budget_admits(exactly, each, reserve), "exactly enough was refused");
    CHECK(!net_budget_admits(exactly - 1u, each, reserve), "one byte short was admitted");
    CHECK(net_budget_admits(exactly + 1u, each, reserve), "one byte spare was refused");

    /* And the reserve is a floor, not a suggestion: a heap holding only the
     * reserve buys nothing, however small the client. */
    CHECK(!net_budget_admits(reserve, 1u, reserve), "the reserve itself was spent");
    CHECK(!net_budget_admits(reserve - 1u, 1u, reserve), "below the reserve, and still admitting");
}

/** A heap with nothing in it admits nothing, and does not wrap trying. */
static void test_nothing_is_admitted_on_an_empty_heap(void)
{
    CHECK(!net_budget_admits(0u, BOARD_CLIENT_BYTES, NET_HEAP_RESERVE),
          "an empty heap admitted a client");
    CHECK(!net_budget_admits(0u, 0u, 0u), "a zero-cost client on an empty heap");
    CHECK(!net_budget_admits(100u, BOARD_CLIENT_BYTES, NET_HEAP_RESERVE),
          "100 bytes bought a 13 KB client");
    /* The subtraction is on the left precisely so this cannot wrap into a yes. */
    CHECK(!net_budget_admits(1u, (size_t)-1, NET_HEAP_RESERVE), "an absurd cost wrapped to admit");
    CHECK(!net_budget_admits(1u, BOARD_CLIENT_BYTES, (size_t)-1), "an absurd reserve wrapped");
}

/**
 * What the boot log says and what the accept path does are one number.
 *
 * They were two for a while, and the log's was the cheerier: it divided the
 * whole heap by the up-front cost, ignoring both the reserve and lwIP's share.
 * This pins them together for a fixed cost per client; what the board then
 * makes of a real client is measured, not asserted here.
 */
static void test_the_capacity_matches_what_will_be_admitted(void)
{
    const size_t each = BOARD_CLIENT_BYTES;
    const size_t reserve = NET_HEAP_RESERVE;

    /* Walk the heap down one client at a time and check the two agree at every
     * step: capacity counts a client exactly when admits() would take one. */
    size_t free_bytes = BOARD_FREE_AT_BOOT;
    size_t taken = 0;
    const size_t predicted = net_budget_capacity(BOARD_FREE_AT_BOOT, each, reserve);
    while (net_budget_admits(free_bytes, each, reserve)) {
        free_bytes -= each;
        taken++;
        if (taken > 64u) {
            break; /* a runaway would hang the suite rather than fail it */
        }
    }
    CHECK(taken == predicted, "the log promises %zu clients and the accept path takes %zu",
          predicted, taken);

    /* And on this board that number is four - the number the accept path was
     * observed to honour - not the six the whole heap divided by the cost
     * would suggest, nor the five that ignoring lwIP's share once promised. */
    CHECK(predicted == 4u, "capacity on the measured board is %zu, expected 4", predicted);
    CHECK(BOARD_FREE_AT_BOOT / each == 6u,
          "the arithmetic that ignores the reserve no longer says 6, so this test's "
          "premise has moved and the numbers above want rechecking");
    CHECK(net_budget_capacity(BOARD_FREE_AT_BOOT, 13504u, reserve) == 5u,
          "charging only the up-front cost no longer over-promises, so the reason "
          "NET_CLIENT_HEAP_OVERHEAD exists has gone and it wants re-measuring");

    CHECK(net_budget_capacity(0u, each, reserve) == 0u, "an empty heap promised a client");
    CHECK(net_budget_capacity(BOARD_FREE_AT_BOOT, 0u, reserve) == 0u,
          "a zero-cost client promised an unbounded number");
}

/**
 * What `hello` advertises is what the accept path will honour.
 *
 * Measured on the board on 2026-08-04: it admitted three clients and refused the
 * fourth - correctly, and in words that said why - while `hello` went on saying
 * `"max_clients": 8` to every one of them. Nothing was broken by it except the
 * user's expectations, which is enough: a number a client is shown before it
 * tries is a promise, and five of those eight could never be kept.
 *
 * These are the two figures side by side. Whatever heap the board is on, what it
 * says must be what it would do.
 */
static void test_what_is_advertised_is_what_will_be_admitted(void)
{
    const size_t each = BOARD_CLIENT_BYTES;
    const size_t reserve = NET_HEAP_RESERVE;

    /* The demo board, three connected and out of room. It said eight. */
    CHECK(net_budget_advertised_clients(8u, 3u, 19300u, each, reserve) == 3u,
          "a board serving three and refusing the fourth advertised %u",
          net_budget_advertised_clients(8u, 3u, 19300u, each, reserve));
    CHECK(!net_budget_admits(19300u, each, reserve),
          "the premise moved: that heap now admits a fourth client");

    /* Walk it down from boot: the figure advertised is always the clients on the
     * board plus the ones the accept path would still take, never more. */
    size_t free_bytes = BOARD_FREE_AT_BOOT;
    unsigned connected = 0;
    for (;;) {
        const unsigned said =
            net_budget_advertised_clients(8u, connected, free_bytes, each, reserve);
        unsigned room = 0;
        size_t heap = free_bytes;
        while (net_budget_admits(heap, each, reserve) && room < 64u) {
            heap -= each;
            room++;
        }
        const unsigned honest = connected + room > 8u ? 8u : connected + room;
        CHECK(said == (honest == 0u ? 1u : honest),
              "with %u connected and %zu B free it advertises %u and would serve %u",
              connected, free_bytes, said, honest);
        if (!net_budget_admits(free_bytes, each, reserve) || connected >= 8u) {
            break;
        }
        free_bytes -= each;
        connected++;
    }
    CHECK(connected == 4u, "the measured board took %u clients before it stopped", connected);

    /* Bluetooth serves one however much heap there is, because the bound there is
     * NimBLE's link count and not memory. Two TCP clients on the board do not
     * make the phone on the BLE link believe a second one may join it. */
    CHECK(net_budget_advertised_clients(1u, 2u, BOARD_FREE_AT_BOOT, each, reserve) == 1u,
          "a Bluetooth client was promised room for a second device");

    /* Never zero. The client reading the frame is itself proof of one. */
    CHECK(net_budget_advertised_clients(8u, 0u, 0u, each, reserve) == 1u,
          "a board with no heap left told its one client it could serve none");
    CHECK(net_budget_advertised_clients(8u, 0u, BOARD_FREE_AT_BOOT, 0u, reserve) == 1u,
          "a zero-cost client promised an unbounded number");
}

int main(void)
{
    printf("net_budget: what the heap can afford\n");
    test_the_run_that_starved_the_radio_is_refused();
    test_the_boundary_is_exact();
    test_nothing_is_admitted_on_an_empty_heap();
    test_the_capacity_matches_what_will_be_admitted();
    test_what_is_advertised_is_what_will_be_admitted();

    printf("  %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
