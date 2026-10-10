/**
 * @file net_mdns_record.h
 * @brief What the controller announces to the LAN, and nothing else.
 *
 * The advertisement itself - the service type, the instance name, the host name
 * and the TXT records - stated once, as data, so that the thing on the wire can
 * be read, tested and reasoned about without a board and without a network.
 *
 * Pure C by the same rule `wire` follows: **no ESP-IDF, no sockets, no
 * allocation**. ::net_mdns_txt_records fills an array the caller owns and
 * ::net_mdns_describe writes into a buffer the caller owns. That is what lets
 * the host test pack compile this exact translation unit and check the
 * advertisement against `wire.py`, which is where these names are defined.
 * `net_mdns.c` next door is the ESP-IDF half; it announces what this file says
 * and adds nothing of its own.
 *
 * ## Why the advertisement is this short
 *
 * A DNS-SD record is multicast to every device on the link, unencrypted,
 * unprompted, and re-sent on every browse. Whatever is in it is public, so the
 * question for each field is not "might a client find this useful" but "does a
 * client need this to decide whether to connect at all".
 *
 * Three things do: **which protocol** (the service type), **where** (the SRV
 * record's host and port, which mDNS builds itself) and **which version of
 * it** (::NET_MDNS_TXT_PROTOCOL). Everything else a client wants arrives in
 * `hello` over the connection it just opened.
 *
 * Three things emphatically do not, and each one was available and left out:
 *
 * - **The sensor's serial number.** `hello` carries it today; the security plan
 *   (`docs/SECURITY_PLAN.md`, "No stable identifier on the wire") takes it out
 *   of `hello` precisely because it is a permanent hardware identifier handed to
 *   any stranger who connects. Putting it in a record handed to any stranger who
 *   *listens* would be strictly worse than the leak being closed.
 * - **The board's MAC address**, or anything derived from it - the Bluetooth
 *   name `SPS30-c24aea` included. Same reason: it is a serial number by another
 *   route, and it survives the board changing address, network and owner.
 * - **Live state**: the session name, the acquisition profile, how many clients
 *   are connected. None of it helps a client choose a device, all of it is an
 *   occupancy signal, and the connected-client count in particular would put a
 *   "someone is home" beacon on the LAN and rewrite it as people come and go.
 *
 * The instance name is a constant for the same reason; see ::NET_MDNS_INSTANCE.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/** `wire.MDNS_SERVICE`. DNS-SD caps a service name at 15 bytes with its
 *  leading underscore, which is checked in the host pack rather than assumed. */
#define NET_MDNS_SERVICE "_sensorq"

/** `wire.MDNS_PROTO`. */
#define NET_MDNS_PROTO "_tcp"

/**
 * `wire.MDNS_HOSTNAME`: the mDNS host name, so the A record is `sps30.local`.
 *
 * The same string `main.c` asks DHCP for, from this one definition, so the two
 * cannot drift and so the security work that anonymises it has one place to
 * change rather than two.
 */
#define NET_MDNS_HOSTNAME "sps30"

/**
 * `wire.MDNS_INSTANCE`: the service instance name.
 *
 * A constant, with nothing unit-specific in it. Two boards on one LAN are
 * handled by DNS-SD's own conflict resolution - RFC 6762 renames the loser
 * during probing, which ESP-IDF's responder implements - so uniqueness costs no
 * identifier. Which board is *yours* is a question for the connection, and the
 * security plan answers it there with a per-connection keyed hint.
 */
#define NET_MDNS_INSTANCE "SENSORQ"

/** `wire.MDNS_TXT_VERSION`: the DNS-SD convention for the TXT layout version. */
#define NET_MDNS_TXT_VERSION "txtvers"
/** `wire.MDNS_TXT_PROTOCOL`: the version of the JSONL protocol `wire.py` defines.
 *  A client that cannot speak it can say so without opening a socket. */
#define NET_MDNS_TXT_PROTOCOL "proto"

/** `wire.MDNS_TXT_VERSION_VALUE`. */
#define NET_MDNS_TXT_VERSION_VALUE "1"
/** `wire.MDNS_TXT_PROTOCOL_VALUE`. */
#define NET_MDNS_TXT_PROTOCOL_VALUE "1"

/** How many TXT records the advertisement carries. Two, and adding a third is a
 *  protocol decision - it fails the host pack until `wire.py` names it too. */
#define NET_MDNS_TXT_COUNT 2

/** Bytes ::net_mdns_describe needs, including the NUL. */
#define NET_MDNS_DESCRIBE_LEN 128

/** @brief One TXT record. Both halves point at string literals owned by this
 *  translation unit, so a caller never has to free or copy them. */
typedef struct {
    const char *key;
    const char *value;
} net_mdns_txt_t;

/**
 * @brief Fill @p out with every TXT record the advertisement carries.
 *
 * @param out array of at least @p cap entries, owned by the caller.
 * @param cap how many it has room for.
 * @return the number written, or 0 if @p out is NULL or @p cap is smaller than
 *         ::NET_MDNS_TXT_COUNT. Never a partial record set: half an
 *         advertisement is a different advertisement.
 */
size_t net_mdns_txt_records(net_mdns_txt_t *out, size_t cap);

/**
 * @brief Write the whole advertisement as one line, for the log and the tests.
 *
 * `SENSORQ._sensorq._tcp on sps30.local:8730 txtvers=1 proto=1`
 *
 * The point of it is that the console says exactly what went on the wire, in one
 * line, and the host pack asserts against the same function - so "what is
 * advertised" is one artefact rather than a claim in a comment and a separate
 * claim in a log.
 *
 * @return bytes written, not counting the NUL, or -1 if it would not fit (in
 *         which case @p out is left empty rather than truncated).
 */
int net_mdns_describe(char *out, size_t cap, unsigned port);

#ifdef __cplusplus
}
#endif
