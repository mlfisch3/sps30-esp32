/**
 * @file net_time_offset.c
 * @brief The zone's UTC offset, in both units the system uses. See `net_time.h`.
 *
 * Split out of `net_time.c` rather than left in it, and the split is the point:
 * everything else in that file is SNTP, NVS and `esp_timer`, so none of it can
 * be built on a host, while this is `<time.h>` over a `time_t` and builds
 * anywhere. The Makefile in `firmware/test` compiles *this* translation unit -
 * not a copy of it - and holds it to the instants `wire.py` resolves with
 * `zoneinfo`.
 *
 * That matters because the two units are the whole hazard here. `wire.h`'s
 * formatters take seconds; every field that records an offset holds minutes.
 * The SNTP path crossed them and put -14400 into a field read as -240, and
 * there was nowhere a host test could see it happen. See `test_net_time.c`.
 */

#include "net_time.h"

#include <time.h>

int net_time_utc_offset_s(int64_t epoch_s)
{
    const time_t t = (time_t)epoch_s;
    struct tm local, utc;
    localtime_r(&t, &local);
    gmtime_r(&t, &utc);

    int offset = (local.tm_hour - utc.tm_hour) * 3600 + (local.tm_min - utc.tm_min) * 60 +
                 (local.tm_sec - utc.tm_sec);
    /* The two calendars can land on different days, and at a year boundary
     * tm_yday jumps by ~365 rather than 1, which is why the test is on the sign
     * and not on the magnitude. */
    const int day_delta = local.tm_yday - utc.tm_yday;
    if (day_delta == 1 || day_delta < -1) {
        offset += 86400;
    } else if (day_delta == -1 || day_delta > 1) {
        offset -= 86400;
    }
    return offset;
}

int net_time_utc_offset_min(int64_t epoch_s)
{
    /* Truncation toward zero is exact for every real zone: they are all whole
     * minutes, and have been since the last of the local mean times went in
     * 1972. Nothing here rounds. */
    return net_time_utc_offset_s(epoch_s) / 60;
}
