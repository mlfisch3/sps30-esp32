/**
 * @file sps30_codec.c
 * @brief Pure measurement decoding - see sps30_codec.h.
 *
 * Depends on nothing but <string.h>, <math.h> and the fixed-width integer
 * types, so the host tests compile this exact translation unit rather than a
 * copy of it.
 */

#include "sps30_codec.h"

#include <math.h>
#include <string.h>

const char *const SPS30_FIELD_NAMES[SPS30_MEASUREMENT_FIELDS] = {
    "pm1_0", "pm2_5", "pm4_0", "pm10",  "nc0_5",
    "nc1_0", "nc2_5", "nc4_0", "nc10",  "typical_size",
};

/* Datasheet ranges, with headroom. These bound what the *sensor* can report,
 * not what the air can contain, and they are deliberately loose: the job here
 * is to catch a frame that decoded to nonsense, not to second-guess a genuine
 * high reading. Mass concentration is specified 0-1000 ug/m3 and number
 * concentration 0-3000 #/cm3; typical particle size cannot exceed PM10's own
 * 10 um. Doubling each leaves any real reading untouched while a corrupt float
 * - which lands in the 1e30 range or on NaN far more often than just outside a
 * limit - is still rejected. */
#define MASS_MAX_UG_M3 2000.0f
#define NUMBER_MAX_PER_CM3 6000.0f
#define TYPICAL_SIZE_MAX_UM 20.0f

float sps30_decode_float_be(const uint8_t *b)
{
    uint32_t bits = ((uint32_t)b[0] << 24) | ((uint32_t)b[1] << 16) |
                    ((uint32_t)b[2] << 8) | (uint32_t)b[3];
    float out;
    memcpy(&out, &bits, sizeof(out));
    return out;
}

sps30_decode_t sps30_decode_measurement(const uint8_t *data, size_t len,
                                        sps30_measurement_t *out)
{
    /* An empty payload means "no new value ready yet" and is entirely normal;
     * see the header. It is checked before the NULL guard on `data` because the
     * caller legitimately passes NULL with a zero length for that case. */
    if (len == 0) {
        return SPS30_DECODE_NOT_READY;
    }
    if (data == NULL || out == NULL || len != SPS30_MEASUREMENT_BYTES) {
        return SPS30_DECODE_BAD_LENGTH;
    }

    for (size_t i = 0; i < SPS30_MEASUREMENT_FIELDS; i++) {
        out->values[i] = sps30_decode_float_be(data + i * 4u);
    }
    return SPS30_DECODE_OK;
}

bool sps30_measurement_plausible(const sps30_measurement_t *m)
{
    if (m == NULL) {
        return false;
    }
    for (size_t i = 0; i < SPS30_MEASUREMENT_FIELDS; i++) {
        const float v = m->values[i];
        if (!isfinite(v) || v < 0.0f) {
            return false;
        }
    }
    /* Fields 0-3 are mass, 4-8 number, 9 typical size - the contract order. */
    for (size_t i = 0; i < 4; i++) {
        if (m->values[i] > MASS_MAX_UG_M3) {
            return false;
        }
    }
    for (size_t i = 4; i < 9; i++) {
        if (m->values[i] > NUMBER_MAX_PER_CM3) {
            return false;
        }
    }
    return m->typical_size <= TYPICAL_SIZE_MAX_UM;
}
