/**
 * @file sps30.h
 * @brief SPS30 transport over ESP32 UART2, layered on the pure `shdlc` codec.
 *
 * This is the transport half of the split that `sps30.py` describes: the codec
 * knows nothing about hardware, and everything here is the serial line. The
 * command set now covers everything `sps30.py` issues - the identify commands,
 * measurement, sleep/wake and fan cleaning - because the exchange has run
 * against the board and each one could be checked there rather than guessed at.
 *
 * Wiring (see HARDWARE_SETUP.md; TX and RX cross exactly once):
 *
 *     SPS30 VDD (1) -> 5V      (5 V +/-10%, NOT 3V3)
 *     SPS30 RX  (2) -> GPIO17  (the ESP32's TX)
 *     SPS30 TX  (3) -> GPIO16  (the ESP32's RX)
 *     SPS30 SEL (4) -> unconnected, floating selects UART
 *     SPS30 GND (5) -> GND
 *
 * GPIO1/GPIO3 are deliberately not used: they are the USB serial console, and
 * keeping the sensor off them is what lets flashing and logging stay
 * independent of it.
 *
 * @note Not thread-safe by design. The Python notes that its transport "must be
 *       called by the single serial owner only"; the same holds here, and the
 *       frame buffers are file-static rather than on the caller's stack.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"
#include "sps30_codec.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Default pins and link settings. UART2, 115200 8N1, no flow control. */
#define SPS30_DEFAULT_UART_PORT 2
#define SPS30_DEFAULT_TX_GPIO 17
#define SPS30_DEFAULT_RX_GPIO 16
#define SPS30_BAUD_RATE 115200

/** Longest a device reply may take before the read is abandoned. */
#define SPS30_DEFAULT_TIMEOUT_MS 2000

/** How many characters a serial number or product type can need, with the NUL. */
#define SPS30_STRING_LEN 33

typedef struct {
    int uart_port;  /**< UART controller number; 2 keeps GPIO1/3 free */
    int tx_gpio;    /**< ESP32 TX, to the sensor's RX */
    int rx_gpio;    /**< ESP32 RX, from the sensor's TX */
    int timeout_ms; /**< per-byte read timeout */
} sps30_config_t;

/*
 * There is deliberately no baud-rate field. The SPS30 speaks 115200 8N1 and
 * nothing else, so a rate that could be set wrong would only ever be a way to
 * break a working rig.
 */

/** The wiring the project standardised on. */
#define SPS30_DEFAULT_CONFIG()                                                 \
    ((sps30_config_t){                                                         \
        .uart_port = SPS30_DEFAULT_UART_PORT,                                  \
        .tx_gpio = SPS30_DEFAULT_TX_GPIO,                                      \
        .rx_gpio = SPS30_DEFAULT_RX_GPIO,                                      \
        .timeout_ms = SPS30_DEFAULT_TIMEOUT_MS,                                \
    })

/**
 * @brief Install the UART driver and configure the pins.
 *
 * Safe to call once. Returns `ESP_ERR_INVALID_STATE` if already initialised.
 */
esp_err_t sps30_init(const sps30_config_t *config);

/** @brief Uninstall the UART driver. */
esp_err_t sps30_deinit(void);

/**
 * @brief Read the product type, e.g. "00080000".
 * @param out NUL-terminated on success. @p cap should be ::SPS30_STRING_LEN.
 */
esp_err_t sps30_product_type(char *out, size_t cap);

/**
 * @brief Read the serial number.
 *
 * The sensor on this bench is known to answer "E3164D2544338487"; it is the
 * same physical unit the Raspberry Pi has been reading.
 */
esp_err_t sps30_serial_number(char *out, size_t cap);

/**
 * @brief Read the firmware version. Expect 2.1 on this sensor.
 */
esp_err_t sps30_firmware_version(uint8_t *major, uint8_t *minor);

/**
 * @brief The device state byte from the most recent failed exchange.
 *
 * Meaningful only after a call returned `ESP_ERR_INVALID_RESPONSE`; pair it
 * with `shdlc_state_str()` for the datasheet's wording.
 */
uint8_t sps30_last_device_state(void);

/* --- measurement ---------------------------------------------------------- */

/**
 * @brief Start measuring, in big-endian IEEE-754 float output format.
 *
 * The argument bytes are `0x01, 0x03` - subcommand 1, format 3 - exactly as
 * `sps30.py` sends them. Format 3 is what makes the read return the 40-byte
 * float frame ::sps30_read_measurement decodes; the alternative integer format
 * would silently change the payload layout.
 *
 * Returns `ESP_ERR_INVALID_RESPONSE` with a device state of `0x43` if the
 * sensor is already measuring. Callers wanting a known state should stop first
 * and ignore that error - see ::sps30_stop_measurement.
 */
esp_err_t sps30_start_measurement(void);

/** @brief Stop measuring and return to idle. */
esp_err_t sps30_stop_measurement(void);

/**
 * @brief Read the latest measured values.
 *
 * @param[out] out written only when @p ready comes back true.
 * @param[out] ready false when the sensor has no new value this tick, which is
 *             normal and not a fault: the SPS30 publishes exactly one new value
 *             per second, so polling any faster returns an empty payload most
 *             of the time. `sps30.py` returns `None` for this.
 */
esp_err_t sps30_read_measurement(sps30_measurement_t *out, bool *ready);

/** @brief Start the fan self-clean; it runs for about ten seconds. */
esp_err_t sps30_fan_clean(void);

/** @brief Soft-reset the device. */
esp_err_t sps30_reset(void);

/* --- raw pass-through ----------------------------------------------------- */

/**
 * @brief Send @p request verbatim and return one SHDLC response frame raw.
 *
 * The port of `sps30.py`'s `transceive_raw`, and deliberately as dumb as it is:
 * the input buffer is flushed, the bytes go out exactly as given, and the next
 * delimiter-framed reply comes back **including both `0x7e` delimiters and with
 * byte-stuffing intact**. Nothing is un-stuffed, no checksum is verified and no
 * command echo is checked, because the point of the raw console is to see what
 * the device actually said - including when what it said is wrong.
 *
 * Consequently this can leave the device in a state the state machine did not
 * ask for. That is the console's nature rather than a defect; `restart` is the
 * documented way back, exactly as on the daemon.
 *
 * Subject to the same single-owner rule as every other call here: the
 * acquisition task owns the UART, so reach this through ::acq_raw_transaction
 * rather than calling it from a transport task.
 *
 * @param[out] resp_len bytes written to @p resp.
 * @return `ESP_ERR_INVALID_SIZE` if the reply did not end within @p resp_cap.
 */
esp_err_t sps30_transceive_raw(const uint8_t *request, size_t request_len,
                               uint8_t *resp, size_t resp_cap, size_t *resp_len);

/* --- sleep / wake (firmware >= 2.0) --------------------------------------- */

/**
 * @brief Enter Sleep-Mode: fan, laser and most electronics off (< 50 uA).
 *
 * Must be issued from idle, i.e. after ::sps30_stop_measurement. This also
 * **switches the serial receiver off**, so nothing will answer until
 * ::sps30_wake has run.
 */
esp_err_t sps30_sleep(void);

/**
 * @brief Leave Sleep-Mode and return to idle.
 *
 * In Sleep-Mode the UART receiver is off, so the datasheet's sequence is: send
 * a single `0xFF` byte as a low pulse on RX to wake the interface, then send
 * the Wake-up command **within 100 ms**. Miss that window and the module drops
 * straight back to sleep, so a lost race just looks like a timeout - hence the
 * retries, which `sps30.py` has for the same reason.
 *
 * The `0xFF` pulse is not a frame and draws no reply; it is written raw.
 *
 * @param attempts how many times to try the pulse-then-command sequence.
 */
esp_err_t sps30_wake(int attempts);

/** @brief Read the automatic fan-clean interval, in seconds (0 = disabled). */
esp_err_t sps30_auto_clean_interval(uint32_t *seconds);

/**
 * @brief Set the automatic fan-clean interval; stored in non-volatile memory.
 *
 * The sensor's own counter only advances while it is *powered and measuring*,
 * and resets whenever it is switched off, so duty-cycled operation has to
 * schedule cleaning itself rather than rely on this.
 */
esp_err_t sps30_set_auto_clean_interval(uint32_t seconds);

/* --- link diagnosis ------------------------------------------------------- */

/**
 * @brief How the link failed, which points at a different fault each way.
 *
 * `HARDWARE_SETUP.md` is explicit about this and it has already cost the
 * project hours, so the driver draws the distinction itself rather than leaving
 * it to whoever reads the log.
 */
typedef enum {
    SPS30_LINK_OK = 0,   /**< the last exchange completed */
    SPS30_LINK_SILENT,   /**< not one byte came back: interface, wiring or power */
    SPS30_LINK_GARBLED,  /**< bytes arrived but would not decode: baud or logic levels */
    SPS30_LINK_REFUSED,  /**< the device answered and rejected the command */
} sps30_link_fault_t;

/** @brief How the most recent exchange failed. ::SPS30_LINK_OK if it did not. */
sps30_link_fault_t sps30_last_link_fault(void);

/** @brief One line naming the likely cause of @p fault. Never NULL. */
const char *sps30_link_fault_str(sps30_link_fault_t fault);

/**
 * @brief Bytes received during the most recent exchange.
 *
 * Zero alongside ::SPS30_LINK_SILENT is the evidence for that verdict, and it
 * is worth logging: nothing arriving cannot be a framing problem, because a
 * framing problem still produces bytes.
 */
size_t sps30_last_rx_count(void);

#ifdef __cplusplus
}
#endif
