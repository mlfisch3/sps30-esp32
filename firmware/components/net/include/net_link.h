/**
 * @file net_link.h
 * @brief A byte pipe to one client, with the transport factored out.
 *
 * `net_server.c` implements the protocol - `hello`, readings, the control lock,
 * the command vocabulary, the eight-client cap - and until BLE arrived it also
 * owned the socket those bytes moved over. The two are separable, and separating
 * them is what lets Bluetooth carry the identical protocol without a second
 * implementation of it: `BLUETOOTH_DESIGN.md`'s central claim is that the
 * payload is the same newline-delimited JSONL either way and **only the
 * transport differs**, and this type is that sentence expressed in code.
 *
 * The Android app already reads it this way round: `Transport` there has
 * `TcpTransport` and `BleTransport` behind it and all the message handling above
 * is transport-agnostic. This is the same seam on the controller.
 *
 * ## What a link owes the protocol
 *
 * Very little, deliberately - four operations and no buffering policy:
 *
 * - ::net_link_ops_t::recv and ::net_link_ops_t::send **never block**. They move
 *   what they can right now and say how much that was. A link that has nothing
 *   to give returns 0, which is not an error and not end-of-stream.
 * - ::net_link_ops_t::wait is where the client task sleeps, so a link that is
 *   idle costs no CPU. It may return early and spuriously; the caller re-checks.
 * - ::net_link_ops_t::close releases the link. It is called exactly once, from
 *   the client task, after the protocol has finished with it.
 *
 * The peer going away is reported as ::NET_LINK_GONE from `recv` or `send`
 * rather than through a separate predicate, because that is the moment the
 * client task can act on it.
 *
 * ## Ownership
 *
 * The transport owns the storage. `net_server` borrows a ::net_link_t for the
 * life of one client and hands it back through `close`; it never allocates or
 * frees one. That keeps the "no allocation on the connection path" rule that
 * `net_server.c` argues for, and it keeps transport-shaped resources - a socket,
 * a GATT connection handle - in the file that understands them.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** The peer has gone. Returned by `recv` and `send`; not a transient condition. */
#define NET_LINK_GONE (-1)

typedef struct net_link net_link_t;

/** @brief The four things the protocol needs from a transport. */
typedef struct {
    /**
     * Take up to @p cap bytes without blocking.
     * @return bytes read, 0 if none are available now, ::NET_LINK_GONE if the
     *         peer has gone.
     */
    int (*recv)(net_link_t *link, void *buf, size_t cap);

    /**
     * Hand over as much of @p buf as the transport will take without blocking.
     * @return bytes accepted, which may be 0, or ::NET_LINK_GONE.
     */
    int (*send)(net_link_t *link, const void *buf, size_t len);

    /**
     * Sleep until something may have changed, or @p timeout_ms elapses.
     *
     * @param for_read false when the protocol has no room for more input. The
     *        link must then **not** solicit any: on TCP that means leaving the
     *        bytes in the receive buffer so the window closes and the sender
     *        stops, which is what keeps a bulk `put_file` from overrunning a
     *        384-byte line buffer and losing rows in silence.
     * @param for_write true when the caller has bytes waiting to go out, so a
     *        transport that can distinguish the two cases may wait for room
     *        rather than only for arrivals.
     */
    void (*wait)(net_link_t *link, bool for_read, bool for_write, uint32_t timeout_ms);

    /** Release the link. Called once, by the client task, and never re-entered. */
    void (*close)(net_link_t *link);
} net_link_ops_t;

/**
 * @brief One client's byte pipe.
 *
 * Embed this as the first member of a transport's own link structure and
 * `container_of` back to it in the operations - the usual C arrangement, and the
 * reason no allocation or void-pointer context is needed here.
 */
struct net_link {
    const net_link_ops_t *ops;
    /** "tcp" or "ble". Appears in the log so a connection's transport is
     *  visible without correlating it against anything. */
    const char *transport;
    /** Peer label for the log: an address and port, or a Bluetooth address. */
    char peer[24];
};

#ifdef __cplusplus
}
#endif
