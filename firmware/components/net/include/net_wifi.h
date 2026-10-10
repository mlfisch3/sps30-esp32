/**
 * @file net_wifi.h
 * @brief Station mode: join the configured network and stay on it.
 *
 * Deliberately the whole of the Wi-Fi story. Everything above this - the TCP
 * server, SNTP - only ever asks "is there a link, and what address am I on",
 * which is why those questions are the only ones this header answers.
 *
 * ## Credentials never appear here
 *
 * The SSID and password arrive as arguments, from a header generated at build
 * time into the build directory out of files that live outside the repository
 * (see `../../cmake/wifi_credentials.cmake`). Nothing in this component stores
 * them beyond the Wi-Fi driver's own copy, and **nothing logs them**: the
 * connection lines say "the configured network", never its name. An SSID in a
 * log is an SSID in a pasted bug report.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Bytes for a dotted-quad plus its NUL. */
#define NET_WIFI_IP_LEN 16

/** @brief Credentials and the hostname to ask the DHCP server for. */
typedef struct {
    const char *ssid;
    const char *password;
    /** Shows up in the router's client list, so the board is findable by name. */
    const char *hostname;
} net_wifi_config_t;

/**
 * @brief Bring up NVS, lwIP and the default event loop. Idempotent.
 *
 * Split out of ::net_wifi_start because it is a precondition for **sockets**,
 * not for Wi-Fi: until lwIP's TCP/IP task exists, `socket()` asserts on an
 * invalid mailbox rather than returning an error, so it cannot be retried
 * around. The server binds the wildcard address and is meant to survive having
 * no association at all - no credentials, or an access point that is down - so
 * it needs this without needing the station.
 *
 * Call it before ::net_server_start. ::net_wifi_start calls it too, so the
 * order of the two does not matter.
 */
esp_err_t net_stack_init(void);

/**
 * @brief Bring up the station and start connecting.
 *
 * Returns as soon as the attempt is under way rather than when it succeeds:
 * acquisition must not wait for an access point, and the retry loop below
 * handles an AP that is not there yet exactly as it handles one that goes away
 * later.
 */
esp_err_t net_wifi_start(const net_wifi_config_t *config);

/** @brief True while the station holds an IP address. */
bool net_wifi_connected(void);

/**
 * @brief Block until connected, or until @p timeout_ms elapses.
 * @return true if connected. Waiting is a convenience for start-up logging;
 *         nothing depends on it.
 */
bool net_wifi_wait_connected(uint32_t timeout_ms);

/** @brief The assigned address as a dotted quad, or "" when there is none. */
void net_wifi_ip(char *out, size_t cap);

/** @brief How many times the link has dropped since boot. */
uint32_t net_wifi_disconnects(void);

/** @brief How many times an address has been assigned since boot. */
uint32_t net_wifi_connects(void);

#ifdef __cplusplus
}
#endif
