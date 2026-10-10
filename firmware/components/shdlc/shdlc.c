/**
 * @file shdlc.c
 * @brief Pure SHDLC codec - see shdlc.h. No hardware, no ESP-IDF, no libc I/O.
 *
 * Deliberately depends on nothing but <string.h> and the fixed-width integer
 * types, so the host tests compile this exact translation unit rather than a
 * copy of it.
 */

#include "shdlc.h"

#include <string.h>

/* The four bytes that must not appear raw inside a frame, and their escape
 * codes. Each code is the byte XOR 0x20, exactly as in `sps30.py`'s _STUFF. */
static bool escape_code_for(uint8_t b, uint8_t *code)
{
    switch (b) {
    case 0x7E: *code = 0x5E; return true;
    case 0x7D: *code = 0x5D; return true;
    case 0x11: *code = 0x31; return true;
    case 0x13: *code = 0x33; return true;
    default: return false;
    }
}

/* The inverse table (_UNSTUFF in the Python). An ESC followed by anything not
 * listed here is malformed, not a literal. */
static bool byte_for_escape_code(uint8_t code, uint8_t *b)
{
    switch (code) {
    case 0x5E: *b = 0x7E; return true;
    case 0x5D: *b = 0x7D; return true;
    case 0x31: *b = 0x11; return true;
    case 0x33: *b = 0x13; return true;
    default: return false;
    }
}

const char *shdlc_strerror(shdlc_err_t err)
{
    switch (err) {
    case SHDLC_OK: return "ok";
    case SHDLC_ERR_BUFFER_TOO_SMALL: return "destination buffer too small";
    case SHDLC_ERR_DATA_TOO_LONG: return "data longer than 255 bytes";
    case SHDLC_ERR_BAD_STUFFING: return "malformed byte-stuffing in response";
    case SHDLC_ERR_TOO_SHORT: return "response too short";
    case SHDLC_ERR_BAD_DELIMITER: return "missing frame delimiter";
    case SHDLC_ERR_BAD_LENGTH: return "declared length exceeds frame";
    case SHDLC_ERR_BAD_CHECKSUM: return "checksum mismatch";
    default: return "unknown codec error";
    }
}

const char *shdlc_state_str(uint8_t state)
{
    switch (state) {
    case 0x00: return "ok";
    case 0x01: return "wrong data length for this command";
    case 0x02: return "unknown command";
    case 0x03: return "no access right for command";
    case 0x04: return "illegal command parameter or out of range";
    case 0x28: return "internal function argument out of range";
    case 0x43: return "command not allowed in current state";
    default: return "unknown error";
    }
}

uint8_t shdlc_checksum(const uint8_t *data, size_t len)
{
    uint32_t sum = 0;
    for (size_t i = 0; i < len; i++) {
        sum += data[i];
    }
    /* 0xFF - (sum & 0xFF), i.e. the LSB of the sum, bitwise inverted. */
    return (uint8_t)(0xFFu - (sum & 0xFFu));
}

shdlc_err_t shdlc_stuff(const uint8_t *src, size_t src_len, uint8_t *dst,
                        size_t dst_cap, size_t *out_len)
{
    size_t n = 0;

    for (size_t i = 0; i < src_len; i++) {
        uint8_t code;
        if (escape_code_for(src[i], &code)) {
            if (n + 2 > dst_cap) {
                return SHDLC_ERR_BUFFER_TOO_SMALL;
            }
            dst[n++] = (uint8_t)SHDLC_ESC;
            dst[n++] = code;
        } else {
            if (n + 1 > dst_cap) {
                return SHDLC_ERR_BUFFER_TOO_SMALL;
            }
            dst[n++] = src[i];
        }
    }

    *out_len = n;
    return SHDLC_OK;
}

shdlc_err_t shdlc_unstuff(const uint8_t *src, size_t src_len, uint8_t *dst,
                          size_t dst_cap, size_t *out_len)
{
    size_t n = 0;

    for (size_t i = 0; i < src_len; i++) {
        uint8_t b = src[i];

        if (b == SHDLC_ESC) {
            /* A trailing ESC has nothing to escape, and only the four codes
             * are legal after one. Both are malformed. */
            if (i + 1 >= src_len || !byte_for_escape_code(src[i + 1], &b)) {
                return SHDLC_ERR_BAD_STUFFING;
            }
            i++;
        }

        if (n + 1 > dst_cap) {
            return SHDLC_ERR_BUFFER_TOO_SMALL;
        }
        dst[n++] = b;
    }

    *out_len = n;
    return SHDLC_OK;
}

shdlc_err_t shdlc_frame(uint8_t cmd, const uint8_t *data, size_t data_len,
                        uint8_t *dst, size_t dst_cap, size_t *out_len)
{
    uint8_t body[SHDLC_MAX_INNER];
    size_t body_len = 0;
    size_t stuffed_len = 0;
    shdlc_err_t err;

    if (data_len > SHDLC_MAX_DATA) {
        return SHDLC_ERR_DATA_TOO_LONG;
    }

    /* payload = [ADDR, cmd, len(data)] + data */
    body[body_len++] = (uint8_t)SHDLC_ADDR;
    body[body_len++] = cmd;
    body[body_len++] = (uint8_t)data_len;
    if (data_len > 0) {
        memcpy(&body[body_len], data, data_len);
        body_len += data_len;
    }

    /* body = payload + checksum(payload) */
    body[body_len] = shdlc_checksum(body, body_len);
    body_len++;

    /* frame = START + stuff(body) + STOP */
    if (dst_cap < 1) {
        return SHDLC_ERR_BUFFER_TOO_SMALL;
    }
    dst[0] = (uint8_t)SHDLC_START;

    err = shdlc_stuff(body, body_len, &dst[1], dst_cap - 1, &stuffed_len);
    if (err != SHDLC_OK) {
        return err;
    }

    if (1 + stuffed_len + 1 > dst_cap) {
        return SHDLC_ERR_BUFFER_TOO_SMALL;
    }
    dst[1 + stuffed_len] = (uint8_t)SHDLC_STOP;

    *out_len = stuffed_len + 2;
    return SHDLC_OK;
}

shdlc_err_t shdlc_unframe(const uint8_t *frame, size_t frame_len, uint8_t *dst,
                          size_t dst_cap, size_t *out_len)
{
    /* At minimum both delimiters must be present. */
    if (frame_len < 2) {
        return SHDLC_ERR_TOO_SHORT;
    }
    if (frame[0] != SHDLC_START || frame[frame_len - 1] != SHDLC_STOP) {
        return SHDLC_ERR_BAD_DELIMITER;
    }

    return shdlc_unstuff(&frame[1], frame_len - 2, dst, dst_cap, out_len);
}

shdlc_err_t shdlc_parse_response(const uint8_t *inner, size_t inner_len,
                                 shdlc_response_t *out)
{
    /* addr, cmd, state, length, checksum - five bytes even with no data. */
    if (inner_len < 5) {
        return SHDLC_ERR_TOO_SHORT;
    }

    size_t length = inner[3];

    /* The declared length must leave room for itself and the checksum byte.
     * This single test is exactly the Python's two: a short slice and a
     * missing checksum byte are both this. */
    if (inner_len < 5 + length) {
        return SHDLC_ERR_BAD_LENGTH;
    }

    uint8_t expected = shdlc_checksum(inner, 4 + length);
    if (inner[4 + length] != expected) {
        return SHDLC_ERR_BAD_CHECKSUM;
    }

    out->cmd = inner[1];
    out->state = inner[2];
    out->data = &inner[4];
    out->data_len = length;
    return SHDLC_OK;
}
