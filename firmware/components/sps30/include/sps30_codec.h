/**
 * @file sps30_codec.h
 * @brief Pure decoding of the SPS30's Read-Measured-Values payload.
 *
 * This is the same split the `shdlc` component makes and for the same reason:
 * **nothing here touches a UART or ESP-IDF**, so the host tests compile this
 * exact translation unit and cross-check it against `sps30.py`, the driver that
 * has actually been reading this sensor.
 *
 * It sits in the `sps30` component rather than in `shdlc` because the 40-byte
 * float layout is an SPS30 thing, not an SHDLC thing - SHDLC carries an opaque
 * payload and does not care what is in it.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Bytes in a full Read-Measured-Values payload: ten big-endian float32. */
#define SPS30_MEASUREMENT_BYTES 40u

/** How many values a measurement carries. */
#define SPS30_MEASUREMENT_FIELDS 10u

/**
 * @brief One set of SPS30 outputs.
 *
 * The field order is a **hard contract** shared with `sps30.py`'s `Measurement`
 * dataclass, the daemon's CSV, the wire protocol and the Kotlin port. Do not
 * reorder it: `values[]` overlays the named fields precisely so that the wire
 * order and the named access are the same thing by construction.
 *
 * Mass concentrations are ug/m3, number concentrations #/cm3, typical size um.
 */
typedef union {
    struct {
        float pm1_0;        /**< mass conc. PM1.0  ug/m3 */
        float pm2_5;        /**< mass conc. PM2.5  ug/m3 */
        float pm4_0;        /**< mass conc. PM4.0  ug/m3 */
        float pm10;         /**< mass conc. PM10   ug/m3 */
        float nc0_5;        /**< number conc. PM0.5 #/cm3 */
        float nc1_0;        /**< number conc. PM1.0 #/cm3 */
        float nc2_5;        /**< number conc. PM2.5 #/cm3 */
        float nc4_0;        /**< number conc. PM4.0 #/cm3 */
        float nc10;         /**< number conc. PM10  #/cm3 */
        float typical_size; /**< typical particle size, um */
    };
    float values[SPS30_MEASUREMENT_FIELDS];
} sps30_measurement_t;

/** The CSV/wire column names, in the contract order above. */
extern const char *const SPS30_FIELD_NAMES[SPS30_MEASUREMENT_FIELDS];

/** Result of decoding a measurement payload. */
typedef enum {
    SPS30_DECODE_OK = 0,      /**< a full measurement was decoded */
    SPS30_DECODE_NOT_READY,   /**< empty payload: no new value this tick */
    SPS30_DECODE_BAD_LENGTH,  /**< neither empty nor exactly 40 bytes */
} sps30_decode_t;

/**
 * @brief Decode a Read-Measured-Values payload into @p out.
 *
 * Mirrors `sps30.py`'s `decode_measurement()` exactly, including the case that
 * is easy to miss: **an empty payload is not an error.** The SPS30 answers the
 * read command with zero data bytes when no new value is ready yet, which
 * happens on most polls faster than 1 Hz, so the Python returns `None` and the
 * caller simply tries again. Treating it as a fault would tear down a healthy
 * session several times a second.
 *
 * Any other length is a genuine protocol fault and returns
 * ::SPS30_DECODE_BAD_LENGTH, matching the Python's `SPS30Error`.
 *
 * @param out written only when ::SPS30_DECODE_OK is returned.
 */
sps30_decode_t sps30_decode_measurement(const uint8_t *data, size_t len,
                                        sps30_measurement_t *out);

/**
 * @brief Decode one big-endian IEEE-754 float32 from four bytes.
 *
 * The SPS30 sends most-significant byte first and the ESP32 is little-endian,
 * so the bytes are assembled explicitly rather than cast. The bit pattern then
 * reaches `float` through `memcpy`, which is the portable spelling - type
 * punning through a pointer cast is undefined behaviour and the host tests run
 * under UndefinedBehaviorSanitizer, which would say so.
 */
float sps30_decode_float_be(const uint8_t *b);

/**
 * @brief True if every field is finite and within the sensor's specified range.
 *
 * The datasheet gives a 0-1000 ug/m3 mass range and a 0-3000 #/cm3 number
 * range; typical particle size cannot exceed PM10's 10 um. A frame that passes
 * framing and checksum but decodes to a NaN or a wildly out-of-range value has
 * usually arrived over a marginal line, and is worth catching before it is
 * recorded as data.
 */
bool sps30_measurement_plausible(const sps30_measurement_t *m);

#ifdef __cplusplus
}
#endif
