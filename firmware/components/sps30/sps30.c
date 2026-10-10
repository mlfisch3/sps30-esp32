/**
 * @file sps30.c
 * @brief SPS30 UART2 transport - see sps30.h.
 *
 * Mirrors the transport in `sps30.py` step for step, because that is the
 * version known to work against this sensor:
 *
 *   - flush the input buffer, then write the framed command;
 *   - skip bytes until a START delimiter, then collect through the next STOP;
 *   - un-stuff, parse, check the command echo, check the device state.
 *
 * The framing itself is in the `shdlc` component and is not repeated here.
 */

#include "sps30.h"

#include <string.h>

#include "driver/uart.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "shdlc.h"

static const char *TAG = "sps30";

/* SPS30 command bytes, from sps30.py. */
#define CMD_START 0x00
#define CMD_STOP 0x01
#define CMD_READ 0x03
#define CMD_SLEEP 0x10
#define CMD_WAKE 0x11
#define CMD_CLEAN 0x56
#define CMD_AUTO_CLEAN 0x80
#define CMD_INFO 0xD0
#define CMD_VERSION 0xD1
#define CMD_RESET 0xD3

/* Sub-command selectors for CMD_INFO. */
#define INFO_PRODUCT_TYPE 0x00
#define INFO_SERIAL_NUMBER 0x03

/* Start-Measurement arguments: subcommand 1, output format 3 = big-endian
 * IEEE-754 floats. Both bytes matter; format 3 is what makes the read return
 * the 40-byte frame sps30_decode_measurement expects. */
#define START_SUBCOMMAND 0x01
#define START_FORMAT_FLOAT 0x03

/* Sub-command selector for CMD_AUTO_CLEAN; the same byte reads or writes,
 * distinguished by whether four data bytes follow. */
#define AUTO_CLEAN_SELECTOR 0x00

/* The datasheet's wake sequence: a raw 0xFF pulse to wake the receiver, then
 * the Wake-up command inside 100 ms. Missing that window looks exactly like a
 * timeout, so the sequence is retried rather than trusted once. */
#define WAKE_PULSE_BYTE 0xFF
#define WAKE_RETRY_DELAY_MS 50

/* The UART driver's own ring buffer. Two pages is ample for a protocol whose
 * longest reply is 47 bytes on the wire. */
#define RX_BUFFER_SIZE 512
#define TX_BUFFER_SIZE 512

static bool s_initialised = false;
static sps30_config_t s_config;
static uint8_t s_last_state = 0;

/* Link diagnosis for the most recent exchange. `s_rx_count` counts every byte
 * that arrived while trying to read a reply, including the ones discarded
 * before a START delimiter - that count is precisely what separates "nothing is
 * talking" from "something is talking and we cannot understand it". */
static sps30_link_fault_t s_link_fault = SPS30_LINK_OK;
static size_t s_rx_count = 0;

/* Single-owner buffers: see the thread-safety note in the header. Keeping them
 * static rather than on the stack matters because SHDLC_MAX_FRAME is 522 bytes
 * and the main task's stack is measured in single-digit kilobytes. */
static uint8_t s_tx_frame[SHDLC_MAX_FRAME];
static uint8_t s_rx_frame[SHDLC_MAX_FRAME];
static uint8_t s_inner[SHDLC_MAX_INNER];

uint8_t sps30_last_device_state(void)
{
    return s_last_state;
}

sps30_link_fault_t sps30_last_link_fault(void)
{
    return s_link_fault;
}

size_t sps30_last_rx_count(void)
{
    return s_rx_count;
}

const char *sps30_link_fault_str(sps30_link_fault_t fault)
{
    switch (fault) {
    case SPS30_LINK_OK:
        return "link ok";
    case SPS30_LINK_SILENT:
        return "silence: check the interface (SEL floating), the wiring, then power";
    case SPS30_LINK_GARBLED:
        return "garbled bytes: check the baud rate and the logic levels";
    case SPS30_LINK_REFUSED:
        return "the device answered and refused the command";
    default:
        return "unknown link fault";
    }
}

esp_err_t sps30_init(const sps30_config_t *config)
{
    if (s_initialised) {
        return ESP_ERR_INVALID_STATE;
    }

    s_config = config ? *config : (sps30_config_t)SPS30_DEFAULT_CONFIG();

    const uart_config_t uart_config = {
        .baud_rate = SPS30_BAUD_RATE,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };

    esp_err_t err = uart_driver_install(s_config.uart_port, RX_BUFFER_SIZE,
                                        TX_BUFFER_SIZE, 0, NULL, 0);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "uart_driver_install failed: %s", esp_err_to_name(err));
        return err;
    }

    err = uart_param_config(s_config.uart_port, &uart_config);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "uart_param_config failed: %s", esp_err_to_name(err));
        uart_driver_delete(s_config.uart_port);
        return err;
    }

    err = uart_set_pin(s_config.uart_port, s_config.tx_gpio, s_config.rx_gpio,
                       UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "uart_set_pin failed: %s", esp_err_to_name(err));
        uart_driver_delete(s_config.uart_port);
        return err;
    }

    s_initialised = true;
    ESP_LOGI(TAG, "UART%d up at %d 8N1: TX=GPIO%d -> sensor RX, RX=GPIO%d <- sensor TX",
             s_config.uart_port, SPS30_BAUD_RATE, s_config.tx_gpio, s_config.rx_gpio);
    return ESP_OK;
}

esp_err_t sps30_deinit(void)
{
    if (!s_initialised) {
        return ESP_ERR_INVALID_STATE;
    }
    s_initialised = false;
    return uart_driver_delete(s_config.uart_port);
}

/** One byte, or ESP_ERR_TIMEOUT. Every byte that arrives is counted. */
static esp_err_t read_byte(uint8_t *out)
{
    int n = uart_read_bytes(s_config.uart_port, out, 1,
                            pdMS_TO_TICKS(s_config.timeout_ms));
    if (n == 1) {
        s_rx_count++;
        return ESP_OK;
    }
    return n < 0 ? ESP_FAIL : ESP_ERR_TIMEOUT;
}

/**
 * Decide what a failed read means, from whether anything came back at all.
 *
 * This is the rule `HARDWARE_SETUP.md` states and the one that has already cost
 * this project hours: **dead silence means interface, wiring or power; garbled
 * bytes mean baud rate or logic levels.** Nothing arriving cannot be a framing
 * problem - a framing problem still produces bytes.
 */
static void classify_read_failure(void)
{
    s_link_fault = (s_rx_count == 0) ? SPS30_LINK_SILENT : SPS30_LINK_GARBLED;
}

/**
 * Read one SHDLC frame: skip to START, collect through STOP.
 *
 * The returned bytes include both delimiters and are still stuffed, exactly as
 * the Python's `_read_frame` returns them, so the framing logic lives in one
 * place.
 *
 * It differs from the Python in one way, and deliberately. `_read_frame`'s
 * skip-to-START loop is bounded only by the per-byte timeout, so a line that
 * never goes quiet and never carries a `0x7E` holds it forever. That is not
 * theoretical: pointing the receive pin at a source of continuous noise wedged
 * this function for as long as the noise lasted - 85,787 bytes consumed in one
 * measured run - and it only returned when the noise stopped. Blocking the
 * acquisition task indefinitely is worse than any diagnosis it could eventually
 * print, so both loops are bounded here, by bytes and by a deadline.
 */
static esp_err_t read_frame(uint8_t *out, size_t cap, size_t *out_len)
{
    uint8_t b;
    esp_err_t err;

    /* One deadline for the whole reply. The per-byte timeout catches a dead
     * line; this catches a live one that never says anything meaningful, which
     * a slow trickle of noise would otherwise sustain indefinitely. */
    const int64_t deadline_us =
        esp_timer_get_time() + (int64_t)s_config.timeout_ms * 1000;

    for (;;) {
        /* A reply begins within a frame's worth of bytes or it is not a reply.
         * uart_flush_input() ran before the command went out, so anything ahead
         * of the START delimiter is noise, not backlog. */
        if (s_rx_count > SHDLC_MAX_FRAME || esp_timer_get_time() > deadline_us) {
            s_link_fault = SPS30_LINK_GARBLED;
            ESP_LOGE(TAG, "%zu bytes with no start delimiter, giving up: %s",
                     s_rx_count, sps30_link_fault_str(s_link_fault));
            return ESP_ERR_TIMEOUT;
        }
        err = read_byte(&b);
        if (err != ESP_OK) {
            classify_read_failure();
            /* Distinguish the two, because they send you to different places.
             * Bytes arriving that never form a frame is a live line being
             * misread; nothing arriving at all is a dead one. */
            if (s_rx_count == 0) {
                ESP_LOGE(TAG, "no reply at all (start byte): %s",
                         sps30_link_fault_str(s_link_fault));
            } else {
                ESP_LOGE(TAG, "%zu bytes but no start delimiter: %s", s_rx_count,
                         sps30_link_fault_str(s_link_fault));
            }
            return err;
        }
        if (b == SHDLC_START) {
            break;
        }
        /* Anything before the first delimiter is line noise or a partial frame
         * from a previous exchange; the Python discards it the same way. */
    }

    size_t n = 0;
    out[n++] = (uint8_t)SHDLC_START;

    for (;;) {
        if (esp_timer_get_time() > deadline_us) {
            s_link_fault = SPS30_LINK_GARBLED;
            ESP_LOGE(TAG, "frame did not end within %d ms: %s", s_config.timeout_ms,
                     sps30_link_fault_str(s_link_fault));
            return ESP_ERR_TIMEOUT;
        }
        err = read_byte(&b);
        if (err != ESP_OK) {
            /* A frame that started and never finished: the line is live, so
             * this is a decoding problem rather than an absent sensor. */
            s_link_fault = SPS30_LINK_GARBLED;
            ESP_LOGE(TAG, "frame started but never ended after %zu bytes: %s", n,
                     sps30_link_fault_str(s_link_fault));
            return err;
        }
        if (n >= cap) {
            s_link_fault = SPS30_LINK_GARBLED;
            ESP_LOGE(TAG, "response exceeded %zu bytes without a stop delimiter", cap);
            return ESP_ERR_INVALID_SIZE;
        }
        out[n++] = b;
        if (b == SHDLC_STOP) {
            break;
        }
    }

    *out_len = n;
    return ESP_OK;
}

/**
 * Send a command and return its response payload.
 *
 * Fails with ESP_ERR_INVALID_RESPONSE for a device-reported error state or a
 * command echo mismatch; ::sps30_last_device_state carries the state byte.
 */
static esp_err_t transceive(uint8_t cmd, const uint8_t *data, size_t data_len,
                            uint8_t *resp, size_t resp_cap, size_t *resp_len)
{
    if (!s_initialised) {
        return ESP_ERR_INVALID_STATE;
    }

    /* Both are per-exchange, so reset them here rather than in read_frame -
     * the byte count has to span the whole reply, delimiters included. */
    s_link_fault = SPS30_LINK_OK;
    s_rx_count = 0;

    size_t tx_len = 0;
    shdlc_err_t cerr = shdlc_frame(cmd, data, data_len, s_tx_frame,
                                   sizeof(s_tx_frame), &tx_len);
    if (cerr != SHDLC_OK) {
        ESP_LOGE(TAG, "building command 0x%02x: %s", cmd, shdlc_strerror(cerr));
        return ESP_ERR_INVALID_ARG;
    }

    /* Discard anything stale before writing, as the Python's
     * reset_input_buffer() does, so a previous timeout cannot desynchronise
     * this exchange. */
    esp_err_t err = uart_flush_input(s_config.uart_port);
    if (err != ESP_OK) {
        return err;
    }

    int written = uart_write_bytes(s_config.uart_port, s_tx_frame, tx_len);
    if (written < 0 || (size_t)written != tx_len) {
        ESP_LOGE(TAG, "short write for command 0x%02x", cmd);
        return ESP_FAIL;
    }

    size_t rx_len = 0;
    err = read_frame(s_rx_frame, sizeof(s_rx_frame), &rx_len);
    if (err != ESP_OK) {
        return err;
    }

    /* Everything from here on is a decode failure over a line that is
     * demonstrably alive - a complete frame arrived. That is the garbled
     * branch of the troubleshooting tree, never the silent one. */
    size_t inner_len = 0;
    cerr = shdlc_unframe(s_rx_frame, rx_len, s_inner, sizeof(s_inner), &inner_len);
    if (cerr != SHDLC_OK) {
        s_link_fault = SPS30_LINK_GARBLED;
        ESP_LOGE(TAG, "un-framing response to 0x%02x: %s", cmd, shdlc_strerror(cerr));
        return ESP_ERR_INVALID_RESPONSE;
    }

    shdlc_response_t parsed;
    cerr = shdlc_parse_response(s_inner, inner_len, &parsed);
    if (cerr != SHDLC_OK) {
        s_link_fault = SPS30_LINK_GARBLED;
        ESP_LOGE(TAG, "parsing response to 0x%02x: %s", cmd, shdlc_strerror(cerr));
        return ESP_ERR_INVALID_RESPONSE;
    }

    if (parsed.cmd != cmd) {
        s_link_fault = SPS30_LINK_GARBLED;
        ESP_LOGE(TAG, "command echo mismatch: sent 0x%02x got 0x%02x", cmd, parsed.cmd);
        return ESP_ERR_INVALID_RESPONSE;
    }

    s_last_state = parsed.state;
    if (parsed.state != 0) {
        /* The device is talking perfectly well and saying no. That is neither a
         * wiring fault nor a baud fault, and telling the caller to go and check
         * cables would be actively misleading. */
        s_link_fault = SPS30_LINK_REFUSED;
        /* One refusal is routine and the rest are not. Start and Stop are how a
         * caller drives the sensor to a known mode, so both are issued without
         * knowing which mode it is in, and the one that was already true comes
         * back 0x43 "not allowed in current state". Both callers document
         * discarding it. Logging that at error level put eight ERROR lines into
         * a nine-minute duty-cycle run and would eventually hide a real one, so
         * it is a debug line - and only for these two commands, because 0x43
         * anywhere else is a genuine surprise. The return value is unchanged:
         * the caller still gets the refusal, this only stops shouting about it. */
        const bool routine = parsed.state == 0x43 &&
                             (cmd == CMD_START || cmd == CMD_STOP);
        if (routine) {
            ESP_LOGD(TAG, "0x%02x refused: %s (expected; the sensor was already "
                          "in that mode)",
                     cmd, shdlc_state_str(parsed.state));
        } else {
            ESP_LOGE(TAG, "device error state 0x%02x: %s", parsed.state,
                     shdlc_state_str(parsed.state));
        }
        return ESP_ERR_INVALID_RESPONSE;
    }

    if (parsed.data_len > resp_cap) {
        ESP_LOGE(TAG, "response payload %zu exceeds buffer %zu", parsed.data_len, resp_cap);
        return ESP_ERR_INVALID_SIZE;
    }

    if (parsed.data_len > 0) {
        memcpy(resp, parsed.data, parsed.data_len);
    }
    *resp_len = parsed.data_len;
    return ESP_OK;
}

/**
 * Copy an SPS30 string payload out as C text.
 *
 * The device NUL-terminates inside the payload and pads the rest, so the string
 * ends at the first NUL - the same rule as the Python's `_ascii()`.
 */
static void payload_to_string(const uint8_t *data, size_t len, char *out, size_t cap)
{
    size_t n = 0;
    while (n < len && n < cap - 1 && data[n] != 0x00) {
        out[n] = (char)data[n];
        n++;
    }
    out[n] = '\0';
}

static esp_err_t read_info_string(uint8_t selector, char *out, size_t cap)
{
    if (out == NULL || cap == 0) {
        return ESP_ERR_INVALID_ARG;
    }

    uint8_t payload[SHDLC_MAX_DATA];
    size_t payload_len = 0;

    esp_err_t err = transceive(CMD_INFO, &selector, 1, payload, sizeof(payload), &payload_len);
    if (err != ESP_OK) {
        return err;
    }

    payload_to_string(payload, payload_len, out, cap);
    return ESP_OK;
}

esp_err_t sps30_product_type(char *out, size_t cap)
{
    return read_info_string(INFO_PRODUCT_TYPE, out, cap);
}

esp_err_t sps30_serial_number(char *out, size_t cap)
{
    return read_info_string(INFO_SERIAL_NUMBER, out, cap);
}

esp_err_t sps30_firmware_version(uint8_t *major, uint8_t *minor)
{
    if (major == NULL || minor == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    uint8_t payload[SHDLC_MAX_DATA];
    size_t payload_len = 0;

    esp_err_t err = transceive(CMD_VERSION, NULL, 0, payload, sizeof(payload), &payload_len);
    if (err != ESP_OK) {
        return err;
    }

    if (payload_len < 2) {
        ESP_LOGE(TAG, "short version response: %zu bytes", payload_len);
        return ESP_ERR_INVALID_RESPONSE;
    }

    *major = payload[0];
    *minor = payload[1];
    return ESP_OK;
}

/* --- measurement ---------------------------------------------------------- */

/** A command that carries no arguments and expects no payload back. */
static esp_err_t simple_command(uint8_t cmd)
{
    uint8_t payload[SHDLC_MAX_DATA];
    size_t payload_len = 0;
    return transceive(cmd, NULL, 0, payload, sizeof(payload), &payload_len);
}

esp_err_t sps30_start_measurement(void)
{
    const uint8_t args[2] = {START_SUBCOMMAND, START_FORMAT_FLOAT};
    uint8_t payload[SHDLC_MAX_DATA];
    size_t payload_len = 0;
    return transceive(CMD_START, args, sizeof(args), payload, sizeof(payload),
                      &payload_len);
}

esp_err_t sps30_stop_measurement(void)
{
    return simple_command(CMD_STOP);
}

esp_err_t sps30_read_measurement(sps30_measurement_t *out, bool *ready)
{
    if (out == NULL || ready == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    *ready = false;

    uint8_t payload[SHDLC_MAX_DATA];
    size_t payload_len = 0;

    esp_err_t err = transceive(CMD_READ, NULL, 0, payload, sizeof(payload), &payload_len);
    if (err != ESP_OK) {
        return err;
    }

    switch (sps30_decode_measurement(payload, payload_len, out)) {
    case SPS30_DECODE_OK:
        *ready = true;
        return ESP_OK;
    case SPS30_DECODE_NOT_READY:
        /* Normal: no new value this tick. Not a fault, and deliberately not
         * logged - at a poll interval below one second this is the common case
         * and would drown the log. */
        return ESP_OK;
    case SPS30_DECODE_BAD_LENGTH:
    default:
        s_link_fault = SPS30_LINK_GARBLED;
        ESP_LOGE(TAG, "measurement payload was %zu bytes, expected %u",
                 payload_len, (unsigned)SPS30_MEASUREMENT_BYTES);
        return ESP_ERR_INVALID_RESPONSE;
    }
}

esp_err_t sps30_fan_clean(void)
{
    return simple_command(CMD_CLEAN);
}

esp_err_t sps30_reset(void)
{
    return simple_command(CMD_RESET);
}

/* --- raw pass-through ------------------------------------------------------ */

esp_err_t sps30_transceive_raw(const uint8_t *request, size_t request_len,
                               uint8_t *resp, size_t resp_cap, size_t *resp_len)
{
    if (!s_initialised) {
        return ESP_ERR_INVALID_STATE;
    }
    if (request == NULL || request_len == 0 || resp == NULL || resp_cap == 0 ||
        resp_len == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    /* Per-exchange, as in transceive(): the byte count has to span this reply
     * alone or the silent/garbled diagnosis describes the previous one. */
    s_link_fault = SPS30_LINK_OK;
    s_rx_count = 0;
    *resp_len = 0;

    esp_err_t err = uart_flush_input(s_config.uart_port);
    if (err != ESP_OK) {
        return err;
    }

    const int written = uart_write_bytes(s_config.uart_port, request, request_len);
    if (written < 0 || (size_t)written != request_len) {
        ESP_LOGE(TAG, "short write for a %zu byte raw frame", request_len);
        return ESP_FAIL;
    }

    /* Straight into the caller's buffer. read_frame() bounds itself by that
     * capacity and by the configured timeout, so a frame the console asked for
     * that the sensor never finishes cannot hold the acquisition task. */
    return read_frame(resp, resp_cap, resp_len);
}

/* --- sleep / wake --------------------------------------------------------- */

esp_err_t sps30_sleep(void)
{
    return simple_command(CMD_SLEEP);
}

esp_err_t sps30_wake(int attempts)
{
    if (!s_initialised) {
        return ESP_ERR_INVALID_STATE;
    }
    if (attempts < 1) {
        attempts = 1;
    }

    esp_err_t last = ESP_FAIL;
    for (int i = 0; i < attempts; i++) {
        /* The pulse is a raw byte, not a frame, and draws no reply. Flush
         * first so a stale byte cannot be mistaken for the command's answer,
         * and wait for the pulse to leave the shift register before starting
         * the 100 ms clock - queueing it is not the same as sending it. */
        const uint8_t pulse = WAKE_PULSE_BYTE;
        uart_flush_input(s_config.uart_port);
        uart_write_bytes(s_config.uart_port, &pulse, 1);
        uart_wait_tx_done(s_config.uart_port, pdMS_TO_TICKS(100));

        last = simple_command(CMD_WAKE);
        if (last == ESP_OK) {
            return ESP_OK;
        }
        vTaskDelay(pdMS_TO_TICKS(WAKE_RETRY_DELAY_MS));
    }

    ESP_LOGW(TAG, "wake failed after %d attempts: %s", attempts, esp_err_to_name(last));
    return last;
}

/* --- automatic fan cleaning ----------------------------------------------- */

esp_err_t sps30_auto_clean_interval(uint32_t *seconds)
{
    if (seconds == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    const uint8_t selector = AUTO_CLEAN_SELECTOR;
    uint8_t payload[SHDLC_MAX_DATA];
    size_t payload_len = 0;

    esp_err_t err = transceive(CMD_AUTO_CLEAN, &selector, 1, payload,
                               sizeof(payload), &payload_len);
    if (err != ESP_OK) {
        return err;
    }
    if (payload_len < 4) {
        ESP_LOGE(TAG, "short auto-clean response: %zu bytes", payload_len);
        return ESP_ERR_INVALID_RESPONSE;
    }

    *seconds = ((uint32_t)payload[0] << 24) | ((uint32_t)payload[1] << 16) |
               ((uint32_t)payload[2] << 8) | (uint32_t)payload[3];
    return ESP_OK;
}

esp_err_t sps30_set_auto_clean_interval(uint32_t seconds)
{
    const uint8_t args[5] = {
        AUTO_CLEAN_SELECTOR,
        (uint8_t)(seconds >> 24),
        (uint8_t)(seconds >> 16),
        (uint8_t)(seconds >> 8),
        (uint8_t)seconds,
    };
    uint8_t payload[SHDLC_MAX_DATA];
    size_t payload_len = 0;
    return transceive(CMD_AUTO_CLEAN, args, sizeof(args), payload, sizeof(payload),
                      &payload_len);
}
