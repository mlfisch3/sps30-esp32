/**
 * @file net_mdns.h
 * @brief Announce the protocol server on the LAN, so the app can listen for it
 *        instead of knocking on every door.
 *
 * What this replaces on the app's side: a sweep of all 254 addresses of every
 * local /24, 64 sockets at a time, keeping whichever one answers `hello`. It
 * works, it found this board on a real LAN for months, and it is the wrong
 * model - it is slow, it is loud, it cannot tell this controller from anything
 * else that happens to accept a connection, and both phone platforms are in the
 * process of gating exactly that pattern behind a permission built around
 * declared service types. See `docs/PRD.md`.
 *
 * What goes in the advertisement, and what deliberately does not, is
 * `net_mdns_record.h` - separately, because it is pure and can therefore be
 * tested. This file is the ESP-IDF half and adds nothing to it.
 *
 * ## Failing is not fatal, on purpose
 *
 * ::net_mdns_start returning an error means the board is not announcing itself.
 * It does not mean the board is unreachable: the TCP server is listening either
 * way, and the app falls back to the sweep it has always used. So the caller
 * logs and carries on, exactly as it does when Wi-Fi or Bluetooth fails to
 * start. A controller that refused to serve because it could not advertise
 * would be a controller that broke discovery in order to improve it.
 */
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"
#include "net_mdns_record.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief Bring up the mDNS responder and advertise the protocol server.
 *
 * Idempotent: a second call with the responder already up returns `ESP_OK` and
 * changes nothing.
 *
 * Call after ::net_stack_init - it needs lwIP and the default event loop - and
 * after ::net_wifi_start, though not after the station has associated: the
 * responder attaches itself to the interface through netif events, so a board
 * whose access point is not switched on yet starts announcing when it arrives.
 *
 * @param port the TCP port the SRV record points at, i.e. what
 *        ::net_server_start is listening on.
 */
esp_err_t net_mdns_start(uint16_t port);

/** @brief True once the responder is up and the service has been registered. */
bool net_mdns_advertising(void);

#ifdef __cplusplus
}
#endif
