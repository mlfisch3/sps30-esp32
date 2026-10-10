/**
 * @file slow_wire_format.c
 * @brief Every float32 there is, formatted both ways, compared byte for byte.
 *
 * ::wire_format_fixed replaced the ten `snprintf("%.2f", (double))` calls that a
 * backfilled row used to cost with integer arithmetic. That is a **formatting**
 * change for exactly as long as the bytes are identical; the moment they are
 * not, it is a change to the wire format, and the wire format is what
 * `wire_vectors_generated.h` holds the C, the Python and the Kotlin
 * implementations to.
 *
 * "Identical on the values we tried" is not the claim worth making about a
 * function with 2^32 inputs, so this makes the other one: **all 4,294,967,296
 * float32 bit patterns, at two decimals and at three**, against the `snprintf`
 * they replaced. Nothing is sampled and nothing is skipped, including the
 * subnormals, the negative zeros and both infinities.
 *
 * It is in the slow pack because it takes minutes rather than the tenth of a
 * second the standard pack is protected by. `test_wire.c` carries the fast part
 * - the store's whole quantised domain, the named rounding cases, and the
 * handover to `snprintf` - and runs every time.
 *
 * Run with `make -C firmware/test slow`. Add `SLOW_SHARDS=<n>` and
 * `SLOW_SHARD=<i>` to split it across cores.
 */

#include <inttypes.h>
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "wire.h"

int main(int argc, char **argv)
{
    uint64_t shards = 1;
    uint64_t shard = 0;
    if (argc >= 2) {
        shards = strtoull(argv[1], NULL, 10);
    }
    if (argc >= 3) {
        shard = strtoull(argv[2], NULL, 10);
    }
    if (shards == 0 || shard >= shards) {
        printf("usage: %s [shards] [shard]\n", argv[0]);
        return 2;
    }

    const uint64_t total = UINT64_C(1) << 32;
    const uint64_t lo = total / shards * shard;
    const uint64_t hi = shard + 1 == shards ? total : total / shards * (shard + 1);

    printf("every float32 in [%" PRIu64 ", %" PRIu64 "), at 2 and 3 decimals\n", lo, hi);

    uint64_t checks = 0;
    uint64_t failures = 0;
    uint64_t exact = 0;
    uint64_t handed_back = 0;

    for (uint64_t i = lo; i < hi; i++) {
        const uint32_t bits = (uint32_t)i;
        float value;
        memcpy(&value, &bits, sizeof(value));
        for (unsigned decimals = 2; decimals <= 3; decimals++) {
            char mine[32];
            char theirs[32];
            checks++;
            if (!wire_format_fixed(value, decimals, mine, sizeof(mine))) {
                /* Handed back to `snprintf`, which is the same bytes by
                 * construction. What is worth checking is that it only ever
                 * happens for values this sensor cannot produce. */
                handed_back++;
                if (isfinite(value) && fabsf(value) < 1.0e12f) {
                    failures++;
                    if (failures <= 20) {
                        printf("  FAIL bits=%08" PRIx32 " d=%u: refused a plausible value %g\n",
                               bits, decimals, (double)value);
                    }
                }
                continue;
            }
            exact++;
            snprintf(theirs, sizeof(theirs), decimals == 3 ? "%.3f" : "%.2f", (double)value);
            if (strcmp(mine, theirs) != 0) {
                failures++;
                if (failures <= 20) {
                    printf("  FAIL bits=%08" PRIx32 " d=%u: want %s, got %s\n", bits, decimals,
                           theirs, mine);
                }
            }
        }
    }

    printf("  %" PRIu64 " done exactly, %" PRIu64 " handed back to snprintf\n", exact,
           handed_back);
    printf("\n%" PRIu64 " checks, %" PRIu64 " failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
