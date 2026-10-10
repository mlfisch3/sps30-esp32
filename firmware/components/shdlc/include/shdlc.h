/**
 * @file shdlc.h
 * @brief Pure SHDLC codec for Sensirion sensors - framing, byte-stuffing, checksum.
 *
 * This is a faithful C port of the codec half of `sps30.py`, the field-proven
 * Python driver at the repository root. That module deliberately separates the
 * pure protocol codec from the serial transport, and this header keeps the same
 * split: **nothing here touches a UART**, so all of it is testable on the host.
 * The transport lives in the `sps30` component.
 *
 * SHDLC is Sensirion's framing over a 115200 8N1 line:
 *   - `0x7E` START and STOP delimiters (they are the same byte),
 *   - four bytes are escaped inside a frame as `0x7D, (byte ^ 0x20)`,
 *   - the checksum is the LSB of the byte sum, bitwise inverted.
 *
 * Where this differs from the Python, it is because C has no exceptions and no
 * dynamic buffers: the raisers become `shdlc_err_t` returns and every writer
 * takes an explicit capacity. The byte-for-byte output is identical, and
 * `test/gen_vectors.py` proves that by generating the expectations from
 * `sps30.py` itself.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Frame start delimiter. */
#define SHDLC_START 0x7Eu
/** Frame stop delimiter - deliberately the same byte as START. */
#define SHDLC_STOP 0x7Eu
/** Escape byte introducing a stuffed byte. */
#define SHDLC_ESC 0x7Du
/** The SPS30's SHDLC address is fixed at 0. */
#define SHDLC_ADDR 0x00u

/** The length field is one byte, so a payload can never exceed this. */
#define SHDLC_MAX_DATA 255u

/**
 * Largest un-stuffed frame body: the MISO header is the longer of the two at
 * four bytes (addr, cmd, state, length), plus the data, plus the checksum.
 */
#define SHDLC_MAX_INNER (4u + SHDLC_MAX_DATA + 1u) /* 260 */

/**
 * Largest wire frame: every body byte could need escaping, which doubles it,
 * plus the two delimiters.
 */
#define SHDLC_MAX_FRAME (2u * SHDLC_MAX_INNER + 2u) /* 522 */

/** Result of a codec call. `SHDLC_OK` is zero; everything else is a failure. */
typedef enum {
    SHDLC_OK = 0,
    SHDLC_ERR_BUFFER_TOO_SMALL, /**< the destination could not hold the result */
    SHDLC_ERR_DATA_TOO_LONG,    /**< data longer than the one-byte length field */
    SHDLC_ERR_BAD_STUFFING,     /**< a trailing ESC, or ESC + a byte that is not escapable */
    SHDLC_ERR_TOO_SHORT,        /**< fewer bytes than the smallest legal frame */
    SHDLC_ERR_BAD_DELIMITER,    /**< missing START or STOP */
    SHDLC_ERR_BAD_LENGTH,       /**< declared length overruns the frame */
    SHDLC_ERR_BAD_CHECKSUM,     /**< checksum did not match the recomputed one */
} shdlc_err_t;

/** A short, human-readable description of @p err. Never NULL. */
const char *shdlc_strerror(shdlc_err_t err);

/**
 * @brief SHDLC checksum: the LSB of the byte sum, bitwise inverted.
 *
 * Mirrors `sps30.py`'s `checksum()`, which computes `0xFF - (sum(...) & 0xFF)`.
 */
uint8_t shdlc_checksum(const uint8_t *data, size_t len);

/**
 * @brief Apply SHDLC byte-stuffing to the bytes between the frame delimiters.
 *
 * `0x7E`, `0x7D`, `0x11` and `0x13` each become `ESC, (byte ^ 0x20)`.
 * Worst case the output is twice @p src_len.
 *
 * @param[out] out_len bytes written to @p dst. Set only on success.
 */
shdlc_err_t shdlc_stuff(const uint8_t *src, size_t src_len, uint8_t *dst,
                        size_t dst_cap, size_t *out_len);

/**
 * @brief Reverse SHDLC byte-stuffing.
 *
 * Fails with `SHDLC_ERR_BAD_STUFFING` on a trailing ESC or on an ESC followed
 * by a byte that is not one of the four escape codes - matching the Python,
 * which raises `SPS30Error("malformed byte-stuffing in response")` for both.
 */
shdlc_err_t shdlc_unstuff(const uint8_t *src, size_t src_len, uint8_t *dst,
                          size_t dst_cap, size_t *out_len);

/**
 * @brief Build a complete MOSI frame, ready to write to the wire.
 *
 * Equivalent to `sps30.py`'s `build_frame()`: the body is
 * `[ADDR, cmd, data_len] + data + [checksum]`, stuffed and wrapped in
 * delimiters.
 *
 * @param data may be NULL when @p data_len is 0.
 */
shdlc_err_t shdlc_frame(uint8_t cmd, const uint8_t *data, size_t data_len,
                        uint8_t *dst, size_t dst_cap, size_t *out_len);

/**
 * @brief Strip the delimiters from a wire frame and un-stuff the body.
 *
 * The inverse of ::shdlc_frame's outer layer, and the C equivalent of the
 * Python's `unstuff(frame[1:-1])`. Feed the result to ::shdlc_parse_response.
 */
shdlc_err_t shdlc_unframe(const uint8_t *frame, size_t frame_len, uint8_t *dst,
                          size_t dst_cap, size_t *out_len);

/** A decoded MISO response. @c data points into the caller's inner buffer. */
typedef struct {
    uint8_t cmd;         /**< the command the device is echoing back */
    uint8_t state;       /**< device state; 0 is success, see ::shdlc_state_str */
    const uint8_t *data; /**< borrowed pointer into the caller's buffer */
    size_t data_len;
} shdlc_response_t;

/**
 * @brief Parse the un-stuffed bytes between the delimiters of a MISO frame.
 *
 * Verifies framing and checksum. Like the Python's `parse_response()`, it does
 * **not** itself fail on a non-zero device state - the caller decides, so that
 * a caller can report the device's own error text.
 *
 * @note Also like the Python, trailing bytes after the checksum are tolerated
 *       rather than rejected. That leniency is deliberate: it is the behaviour
 *       that has been running against this sensor, and tightening it here would
 *       be an unverifiable change to a proven exchange.
 */
shdlc_err_t shdlc_parse_response(const uint8_t *inner, size_t inner_len,
                                 shdlc_response_t *out);

/**
 * @brief Description of a non-zero device state byte, from the SPS30 datasheet.
 *
 * Mirrors `sps30.py`'s `STATE_ERRORS`; returns "unknown error" for codes that
 * table does not list. Never NULL.
 */
const char *shdlc_state_str(uint8_t state);

#ifdef __cplusplus
}
#endif
