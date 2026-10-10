/**
 * @file net_mdns_record.c
 * @brief The advertisement as data. See net_mdns_record.h for what is in it and
 *        why so little.
 *
 * One of the three host-testable translation units in `components/net`, and for
 * the same reason as the other two: everything it decides can be checked without
 * a radio, and the thing it decides is a compatibility surface that outlives any
 * particular firmware.
 */

#include "net_mdns_record.h"

#include <stdio.h>

/* Static, so the pointers handed out stay valid for the life of the program and
 * a caller never has to think about ownership. */
static const net_mdns_txt_t TXT[NET_MDNS_TXT_COUNT] = {
    {NET_MDNS_TXT_VERSION, NET_MDNS_TXT_VERSION_VALUE},
    {NET_MDNS_TXT_PROTOCOL, NET_MDNS_TXT_PROTOCOL_VALUE},
};

size_t net_mdns_txt_records(net_mdns_txt_t *out, size_t cap)
{
    if (out == NULL || cap < NET_MDNS_TXT_COUNT) {
        return 0;
    }
    for (size_t i = 0; i < NET_MDNS_TXT_COUNT; i++) {
        out[i] = TXT[i];
    }
    return NET_MDNS_TXT_COUNT;
}

int net_mdns_describe(char *out, size_t cap, unsigned port)
{
    if (out == NULL || cap == 0) {
        return -1;
    }
    out[0] = '\0';

    int used = snprintf(out, cap, "%s.%s.%s on %s.local:%u", NET_MDNS_INSTANCE,
                        NET_MDNS_SERVICE, NET_MDNS_PROTO, NET_MDNS_HOSTNAME, port);
    if (used < 0 || (size_t)used >= cap) {
        out[0] = '\0';
        return -1;
    }

    for (size_t i = 0; i < NET_MDNS_TXT_COUNT; i++) {
        const int more = snprintf(out + used, cap - (size_t)used, " %s=%s", TXT[i].key,
                                  TXT[i].value);
        if (more < 0 || (size_t)more >= cap - (size_t)used) {
            out[0] = '\0';
            return -1;
        }
        used += more;
    }
    return used;
}
