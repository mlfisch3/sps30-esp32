/**
 * @file ble_nus.c
 * @brief The Nordic UART Service as a ::net_link_t. See `ble_nus.h`.
 *
 * ## Shape
 *
 * NimBLE runs one host task. Everything Bluetooth-shaped happens there: the GAP
 * events, the GATT write callback, the notification completions. Everything
 * protocol-shaped happens on the client task `net_server.c` starts, which knows
 * only recv/send/wait/close. The two meet at ::ble_link_t, which is guarded by a
 * per-link mutex and holds exactly three things worth sharing: the connection
 * handle, whether the peer is still there, and the inbound command bytes.
 *
 * ## Why a phone is served on subscribe, not on connect
 *
 * The first thing the protocol sends is `hello`, and a notification sent before
 * the central has written the CCCD is not delivered - it is dropped by the
 * stack, silently. Android's `BleTransport` connects, discovers services, raises
 * the MTU and only then enables notifications, so attaching at
 * ::BLE_GAP_EVENT_CONNECT would reliably lose the one message the app waits for.
 * Attaching at ::BLE_GAP_EVENT_SUBSCRIBE is the point at which the pipe is
 * genuinely open in both directions.
 *
 * Unsubscribing is therefore also a disconnection as far as the protocol is
 * concerned, even though the GAP connection survives it.
 *
 * ## Backpressure
 *
 * A notification is queued, not sent, and the queue is the host's mbuf pool. On
 * a full pool ::ble_send returns the bytes it managed rather than an error, and
 * the client task tries the rest after its next wait - the same shape as a TCP
 * send that accepts a partial write. ::BLE_GAP_EVENT_NOTIFY_TX wakes the task
 * as room appears, so throughput is not tied to the 100 ms poll.
 */

#include "ble_nus.h"

#include <string.h>

#include "ble_bytes.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "host/ble_gap.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "net_link.h"
#include "net_server.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "wire.h"

/* The protocol reports Bluetooth's capacity to the app, and the app tells the
 * user why a second device cannot connect. If these two ever disagree the
 * message is a lie in one direction or the other, so they are pinned together
 * here - the one place both are visible. */
_Static_assert(WIRE_MAX_CLIENTS_BLE == BLE_NUS_MAX_LINKS,
               "what the app is told Bluetooth serves must be what it serves");

static const char *TAG = "ble";

/**
 * Inbound command bytes held between the GATT callback and the client task.
 *
 * `net_server.c` refuses a command line longer than 384 bytes, so anything this
 * buffer cannot hold is already past what the protocol will act on. 512 leaves
 * room for a long line plus the beginning of the next one at the 100 ms poll,
 * and costs 512 bytes per connection.
 */
#define BLE_RX_BYTES 512

/** Fallback payload per notification before the MTU is negotiated: the BLE 4.0
 *  default of 23, less the three bytes of ATT header. */
#define BLE_MIN_PAYLOAD 20

/* --- the Nordic UART Service --------------------------------------------- */
/*
 * 6E400001-B5A3-F393-E0A9-E50E24DCCA9E and its two characteristics, byte-
 * reversed as BLE_UUID128_INIT wants them. These are not ours to choose: the
 * app filters its scan on the service UUID and looks the characteristics up by
 * theirs, and `sps30_ble.py` advertises the same three on the Pi.
 */
static const ble_uuid128_t NUS_SVC_UUID =
    BLE_UUID128_INIT(0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0, 0x93, 0xf3, 0xa3, 0xb5,
                     0x01, 0x00, 0x40, 0x6e);
static const ble_uuid128_t NUS_RX_UUID =
    BLE_UUID128_INIT(0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0, 0x93, 0xf3, 0xa3, 0xb5,
                     0x02, 0x00, 0x40, 0x6e);
static const ble_uuid128_t NUS_TX_UUID =
    BLE_UUID128_INIT(0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0, 0x93, 0xf3, 0xa3, 0xb5,
                     0x03, 0x00, 0x40, 0x6e);

static uint16_t s_tx_handle;
static uint8_t s_own_addr_type;
static char s_device_name[24];
static ble_nus_stats_t s_stats;

/* --- one connected phone -------------------------------------------------- */

typedef struct {
    net_link_t base;

    /** A GAP connection holds this slot. Set on connect, cleared once the
     *  protocol has finished with it, so a slot is never handed out twice. */
    bool in_use;
    /** ::net_server_attach succeeded and `close` is still owed to us. */
    bool attached;
    /** The peer has gone, or has stopped listening. Terminal. */
    bool gone;

    uint16_t conn_handle;
    uint16_t mtu;

    SemaphoreHandle_t lock;
    /** Given whenever something the client task might care about happened. */
    SemaphoreHandle_t signal;

    ble_bytes_t in;
    uint8_t in_storage[BLE_RX_BYTES];
} ble_link_t;

static ble_link_t s_links[BLE_NUS_MAX_LINKS];
static SemaphoreHandle_t s_slots_lock;

static void lock(SemaphoreHandle_t m)
{
    xSemaphoreTake(m, portMAX_DELAY);
}

static void unlock(SemaphoreHandle_t m)
{
    xSemaphoreGive(m);
}

static ble_link_t *as_ble(net_link_t *link)
{
    return (ble_link_t *)link;
}

/** The slot holding @p conn_handle, or NULL. */
static ble_link_t *link_for(uint16_t conn_handle)
{
    ble_link_t *found = NULL;
    lock(s_slots_lock);
    for (size_t i = 0; i < BLE_NUS_MAX_LINKS; i++) {
        if (s_links[i].in_use && s_links[i].conn_handle == conn_handle) {
            found = &s_links[i];
            break;
        }
    }
    unlock(s_slots_lock);
    return found;
}

/** Mark a link finished with and let the slot be reused. */
static void free_slot(ble_link_t *l)
{
    lock(s_slots_lock);
    l->in_use = false;
    l->attached = false;
    l->conn_handle = BLE_HS_CONN_HANDLE_NONE;
    unlock(s_slots_lock);
}

static bool a_slot_is_free(void)
{
    bool free_one = false;
    lock(s_slots_lock);
    for (size_t i = 0; i < BLE_NUS_MAX_LINKS; i++) {
        if (!s_links[i].in_use) {
            free_one = true;
            break;
        }
    }
    unlock(s_slots_lock);
    return free_one;
}

/* --- advertising ---------------------------------------------------------- */

static int gap_event(struct ble_gap_event *event, void *arg);

/**
 * Advertise, if there is a slot for whoever answers.
 *
 * Called from the host task and from a client task on its way out, and safe
 * either way: NimBLE answers `BLE_HS_EALREADY` when advertising is already
 * running, which is the ordinary case rather than a fault. Not advertising while
 * full is deliberate - a phone that cannot see the board retries quietly,
 * whereas one that connects and is refused has to be told why over a link it
 * only just established.
 */
static void advertise_if_free(void)
{
    if (!a_slot_is_free()) {
        return;
    }

    struct ble_hs_adv_fields fields;
    memset(&fields, 0, sizeof(fields));
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    /* The service UUID is the whole of discovery: the app scans with a filter on
     * it, so this is what makes the board findable without an address. 16 bytes
     * of UUID plus its header and the flags is 21 of the 31 available. */
    fields.uuids128 = (ble_uuid128_t *)&NUS_SVC_UUID;
    fields.num_uuids128 = 1;
    fields.uuids128_is_complete = 1;

    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "could not set the advertisement: rc %d", rc);
        return;
    }

    /* The name goes in the scan response, where there is room for it; it names
     * the board in the app's banner and is not used to find it. */
    struct ble_hs_adv_fields rsp;
    memset(&rsp, 0, sizeof(rsp));
    rsp.name = (uint8_t *)s_device_name;
    rsp.name_len = (uint8_t)strlen(s_device_name);
    rsp.name_is_complete = 1;
    rc = ble_gap_adv_rsp_set_fields(&rsp);
    if (rc != 0) {
        ESP_LOGE(TAG, "could not set the scan response: rc %d", rc);
        return;
    }

    struct ble_gap_adv_params params;
    memset(&params, 0, sizeof(params));
    params.conn_mode = BLE_GAP_CONN_MODE_UND;
    params.disc_mode = BLE_GAP_DISC_MODE_GEN;

    rc = ble_gap_adv_start(s_own_addr_type, NULL, BLE_HS_FOREVER, &params, gap_event, NULL);
    if (rc != 0 && rc != BLE_HS_EALREADY) {
        ESP_LOGE(TAG, "could not start advertising: rc %d", rc);
    }
}

/* --- the link operations -------------------------------------------------- */

static int ble_recv(net_link_t *link, void *buf, size_t cap)
{
    ble_link_t *l = as_ble(link);
    size_t n;
    bool gone;

    lock(l->lock);
    n = ble_bytes_read(&l->in, buf, cap);
    gone = l->gone;
    unlock(l->lock);

    if (n > 0) {
        return (int)n; /* deliver what the peer sent before reporting it gone */
    }
    return gone ? NET_LINK_GONE : 0;
}

static int ble_send(net_link_t *link, const void *buf, size_t len)
{
    ble_link_t *l = as_ble(link);
    uint16_t conn_handle;
    size_t payload;

    lock(l->lock);
    const bool gone = l->gone;
    conn_handle = l->conn_handle;
    payload = l->mtu > 3 ? (size_t)(l->mtu - 3) : BLE_MIN_PAYLOAD;
    unlock(l->lock);

    if (gone) {
        return NET_LINK_GONE;
    }

    size_t sent = 0;
    while (sent < len) {
        const size_t n = (len - sent) < payload ? (len - sent) : payload;
        struct os_mbuf *om = ble_hs_mbuf_from_flat((const uint8_t *)buf + sent, n);
        if (om == NULL) {
            /* The mbuf pool is empty; try again after the next wait. Same
             * "not yet" as a deferred notify below, and counted with it. */
            s_stats.notify_deferred++;
            break;
        }
        /* Takes ownership of `om` whether it succeeds or fails, so there is
         * nothing to free on either path. */
        const int rc = ble_gatts_notify_custom(conn_handle, s_tx_handle, om);
        if (rc != 0) {
            if (rc == BLE_HS_ENOTCONN) {
                lock(l->lock);
                l->gone = true;
                unlock(l->lock);
                return sent > 0 ? (int)sent : NET_LINK_GONE;
            }
            /*
             * Out of buffers is not a failure, it is the link saying "not yet".
             * The caller retries from where this stopped and nothing is lost -
             * a bulk transfer spends most of its life here by design.
             *
             * Counting it as a failure was actively misleading: a session copy
             * that arrived byte-perfect reported 28,202 "notify_failed", which
             * reads as 28,202 lost notifications and sends whoever is
             * debugging the next problem to the wrong place entirely.
             */
            if (rc == BLE_HS_ENOMEM || rc == BLE_HS_EAGAIN || rc == BLE_HS_EBUSY) {
                s_stats.notify_deferred++;
            } else {
                s_stats.notify_failed++;
            }
            break;
        }
        sent += n;
    }
    return (int)sent;
}

static void ble_wait(net_link_t *link, bool for_read, bool for_write, uint32_t timeout_ms)
{
    /* One signal covers arrivals, room to write and departure, so neither flag
     * changes what is waited on. `for_read` still matters, but on this
     * transport it is honoured in ::gatt_access rather than here: there is no
     * receive window to leave closed, so the refusal has to happen at the
     * moment the central offers the bytes. */
    (void)for_read;
    (void)for_write;
    ble_link_t *l = as_ble(link);
    (void)xSemaphoreTake(l->signal, pdMS_TO_TICKS(timeout_ms));
}

static void ble_close(net_link_t *link)
{
    ble_link_t *l = as_ble(link);

    lock(l->lock);
    const bool still_there = !l->gone;
    const uint16_t conn_handle = l->conn_handle;
    l->gone = true;
    unlock(l->lock);

    if (still_there) {
        /* The protocol finished with a phone that has not left - at capacity, or
         * stalled. Terminating is what the TCP path's close() does. */
        (void)ble_gap_terminate(conn_handle, BLE_ERR_REM_USER_CONN_TERM);
    }
    free_slot(l);
    advertise_if_free();
}

static const net_link_ops_t BLE_LINK_OPS = {
    .recv = ble_recv,
    .send = ble_send,
    .wait = ble_wait,
    .close = ble_close,
};

/* --- GATT ----------------------------------------------------------------- */

/**
 * The phone wrote a command. Runs on the host task, so it copies and returns.
 *
 * A write that does not fit the inbound buffer is not silently truncated:
 * `ble_bytes` discards back to a line boundary so a half command can never be
 * spliced onto the next one, and counts what it lost.
 */
static int gatt_access(uint16_t conn_handle, uint16_t attr_handle,
                       struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)attr_handle;
    (void)arg;

    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    ble_link_t *l = link_for(conn_handle);
    if (l == NULL) {
        return 0; /* a write from a connection we are not serving; ignore it */
    }

    uint8_t buf[BLE_RX_BYTES];
    uint16_t len = 0;
    const int rc = ble_hs_mbuf_to_flat(ctxt->om, buf, sizeof(buf), &len);
    if (rc != 0) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    lock(l->lock);
    const bool took = ble_bytes_write(&l->in, buf, len);
    const uint32_t dropped = ble_bytes_dropped(&l->in);
    unlock(l->lock);

    xSemaphoreGive(l->signal);

    if (!took) {
        /*
         * Refuse the write rather than swallow it.
         *
         * This is Bluetooth's version of TCP closing its receive window, and it
         * exists for the same reason: the client loop stops draining while it
         * has a command it cannot yet dispatch, and a bulk `put_file` will
         * outrun it. On TCP the sender simply stalls. Here the bytes are
         * already in our hands, so the only honest answer is an ATT error - the
         * central is told the write failed instead of believing rows were
         * delivered that were not.
         *
         * `ble_bytes` has still discarded back to a line boundary, so what
         * survives is a prefix of whole commands either way; this is about the
         * peer knowing.
         */
        ESP_LOGW(TAG, "refused a write: the inbound buffer is full (%u B lost so far)",
                 (unsigned)dropped);
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    return 0;
}

static const struct ble_gatt_svc_def GATT_SERVICES[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &NUS_SVC_UUID.u,
        .characteristics =
            (struct ble_gatt_chr_def[]){
                {
                    /* Write-with-response and without: the app uses the former,
                     * and offering both is what every NUS implementation does. */
                    .uuid = &NUS_RX_UUID.u,
                    .access_cb = gatt_access,
                    .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
                },
                {
                    .uuid = &NUS_TX_UUID.u,
                    .access_cb = gatt_access,
                    .val_handle = &s_tx_handle,
                    .flags = BLE_GATT_CHR_F_NOTIFY,
                },
                {0},
            },
    },
    {0},
};

/* --- GAP ------------------------------------------------------------------ */

/** Tell a phone we have no room, over the link it just opened, then drop it. */
static void refuse_subscriber(ble_link_t *l)
{
    char line[WIRE_LINE_MAX];
    const int n = net_server_refusal_line(line, sizeof(line));
    if (n > 0) {
        struct os_mbuf *om = ble_hs_mbuf_from_flat(line, (uint16_t)n);
        if (om != NULL) {
            (void)ble_gatts_notify_custom(l->conn_handle, s_tx_handle, om);
        }
    }
    s_stats.refused++;
    ESP_LOGW(TAG, "refused a Bluetooth client: the controller has no free slot");
    /*
     * Let the notification reach the air before terminating, or the phone gets
     * a disconnection and no explanation - the Bluetooth form of the RST that
     * `refuse()` in net_server.c goes to such lengths to avoid.
     *
     * This does stall the NimBLE host task, which is normally worth avoiding.
     * It is accepted here because the path needs every one of the controller's
     * eight client slots to be taken before it can run at all, and because the
     * cost when it does is up to 200 ms of latency on the other connection -
     * invisible against a 1 Hz stream.
     */
    vTaskDelay(pdMS_TO_TICKS(200));
    (void)ble_gap_terminate(l->conn_handle, BLE_ERR_REM_USER_CONN_TERM);
    free_slot(l);
}

/** Hand a freshly subscribed phone to the protocol. */
static void serve(ble_link_t *l)
{
    lock(l->lock);
    const bool gone = l->gone;
    unlock(l->lock);

    lock(s_slots_lock);
    if (l->attached || gone) {
        unlock(s_slots_lock);
        return; /* a repeat subscribe, or it left mid-handshake */
    }
    l->attached = true;
    unlock(s_slots_lock);

    s_stats.subscribes++;
    if (!net_server_attach(&l->base)) {
        lock(s_slots_lock);
        l->attached = false;
        unlock(s_slots_lock);
        refuse_subscriber(l);
        advertise_if_free();
    }
}

static void on_connect(struct ble_gap_event *event)
{
    if (event->connect.status != 0) {
        advertise_if_free(); /* the attempt failed; go back to being findable */
        return;
    }
    s_stats.connects++;

    ble_link_t *chosen = NULL;
    lock(s_slots_lock);
    for (size_t i = 0; i < BLE_NUS_MAX_LINKS; i++) {
        if (s_links[i].in_use) {
            continue;
        }
        chosen = &s_links[i];
        chosen->in_use = true;
        chosen->attached = false;
        chosen->gone = false;
        chosen->conn_handle = event->connect.conn_handle;
        chosen->mtu = 0;
        break;
    }
    unlock(s_slots_lock);

    if (chosen == NULL) {
        /* NimBLE is built for exactly as many connections as there are slots, so
         * this is unreachable rather than a policy. Say so if it ever happens. */
        ESP_LOGE(TAG, "connection %u arrived with every slot taken",
                 (unsigned)event->connect.conn_handle);
        (void)ble_gap_terminate(event->connect.conn_handle, BLE_ERR_REM_USER_CONN_TERM);
        return;
    }

    lock(chosen->lock);
    ble_bytes_reset(&chosen->in);
    chosen->mtu = ble_att_mtu(event->connect.conn_handle);
    unlock(chosen->lock);

    struct ble_gap_conn_desc desc;
    if (ble_gap_conn_find(event->connect.conn_handle, &desc) == 0) {
        const uint8_t *a = desc.peer_id_addr.val;
        snprintf(chosen->base.peer, sizeof(chosen->base.peer),
                 "%02x:%02x:%02x:%02x:%02x:%02x", a[5], a[4], a[3], a[2], a[1], a[0]);
    } else {
        snprintf(chosen->base.peer, sizeof(chosen->base.peer), "unknown");
    }

    ESP_LOGI(TAG, "connected to %s; waiting for it to subscribe", chosen->base.peer);
    /* A second phone can still arrive while this one is being set up. */
    advertise_if_free();
}

static void on_disconnect(struct ble_gap_event *event)
{
    s_stats.disconnects++;
    ble_link_t *l = link_for(event->disconnect.conn.conn_handle);
    if (l == NULL) {
        advertise_if_free();
        return;
    }

    lock(l->lock);
    l->gone = true;
    unlock(l->lock);
    xSemaphoreGive(l->signal);

    ESP_LOGI(TAG, "%s disconnected (reason %d)", l->base.peer, event->disconnect.reason);

    /* If the protocol never took it, nobody will call close, so the slot is
     * ours to release. If it did, close is still owed and releases it there -
     * releasing here would let a new phone take a slot still being served. */
    bool attached;
    lock(s_slots_lock);
    attached = l->attached;
    unlock(s_slots_lock);
    if (!attached) {
        free_slot(l);
    }
    advertise_if_free();
}

static int gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;

    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        on_connect(event);
        return 0;

    case BLE_GAP_EVENT_DISCONNECT:
        on_disconnect(event);
        return 0;

    case BLE_GAP_EVENT_SUBSCRIBE: {
        ble_link_t *l = link_for(event->subscribe.conn_handle);
        if (l == NULL || event->subscribe.attr_handle != s_tx_handle) {
            return 0;
        }
        if (event->subscribe.cur_notify) {
            serve(l);
        } else {
            /* It stopped listening. The GAP connection is still up, but as far
             * as the protocol goes the phone has gone: nothing more can be
             * delivered to it. */
            lock(l->lock);
            l->gone = true;
            unlock(l->lock);
            xSemaphoreGive(l->signal);
        }
        return 0;
    }

    case BLE_GAP_EVENT_MTU: {
        ble_link_t *l = link_for(event->mtu.conn_handle);
        if (l != NULL) {
            lock(l->lock);
            l->mtu = (uint16_t)event->mtu.value;
            unlock(l->lock);
            s_stats.mtu = (uint16_t)event->mtu.value;
            ESP_LOGI(TAG, "%s negotiated an ATT MTU of %u B (%u B a notification)",
                     l->base.peer, (unsigned)event->mtu.value,
                     (unsigned)(event->mtu.value - 3));
        }
        return 0;
    }

    case BLE_GAP_EVENT_NOTIFY_TX: {
        /* A notification left the queue, so there may be room for the next
         * chunk. Waking the client task here is what keeps a 640-byte line from
         * being paced by the 100 ms poll. */
        ble_link_t *l = link_for(event->notify_tx.conn_handle);
        if (l != NULL) {
            xSemaphoreGive(l->signal);
        }
        return 0;
    }

    case BLE_GAP_EVENT_ADV_COMPLETE:
        advertise_if_free();
        return 0;

    default:
        return 0;
    }
}

/* --- lifecycle ------------------------------------------------------------ */

static void on_sync(void)
{
    int rc = ble_hs_util_ensure_addr(0);
    if (rc != 0) {
        ESP_LOGE(TAG, "no usable Bluetooth address: rc %d", rc);
        return;
    }
    rc = ble_hs_id_infer_auto(0, &s_own_addr_type);
    if (rc != 0) {
        ESP_LOGE(TAG, "could not choose an address type: rc %d", rc);
        return;
    }

    uint8_t addr[6] = {0};
    (void)ble_hs_id_copy_addr(s_own_addr_type, addr, NULL);
    /* Named after the radio's own address, so two boards on a bench are
     * distinguishable without either being configured. The app finds the board
     * by service UUID regardless of what this says. */
    snprintf(s_device_name, sizeof(s_device_name), "SPS30-%02x%02x%02x", addr[2], addr[1],
             addr[0]);
    (void)ble_svc_gap_device_name_set(s_device_name);

    ESP_LOGI(TAG, "----------------------------------------");
    ESP_LOGI(TAG, "Bluetooth is up, advertising as %s", s_device_name);
    ESP_LOGI(TAG, "the app finds it by service UUID; there is nothing to configure");
    ESP_LOGI(TAG, "----------------------------------------");
    advertise_if_free();
}

static void on_reset(int reason)
{
    ESP_LOGE(TAG, "the Bluetooth host reset: reason %d; it will resynchronise", reason);
}

static void host_task(void *param)
{
    (void)param;
    nimble_port_run(); /* returns only when the host is stopped */
    nimble_port_freertos_deinit();
}

esp_err_t ble_nus_start(void)
{
    s_slots_lock = xSemaphoreCreateMutex();
    if (s_slots_lock == NULL) {
        return ESP_ERR_NO_MEM;
    }
    for (size_t i = 0; i < BLE_NUS_MAX_LINKS; i++) {
        ble_link_t *l = &s_links[i];
        l->base.ops = &BLE_LINK_OPS;
        l->base.transport = "ble";
        l->conn_handle = BLE_HS_CONN_HANDLE_NONE;
        l->lock = xSemaphoreCreateMutex();
        l->signal = xSemaphoreCreateBinary();
        if (l->lock == NULL || l->signal == NULL) {
            return ESP_ERR_NO_MEM;
        }
        ble_bytes_init(&l->in, l->in_storage, sizeof(l->in_storage));
    }

    esp_err_t err = nimble_port_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "the Bluetooth controller did not start: %s", esp_err_to_name(err));
        return err;
    }

    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.reset_cb = on_reset;

    ble_svc_gap_init();
    ble_svc_gatt_init();

    int rc = ble_gatts_count_cfg(GATT_SERVICES);
    if (rc == 0) {
        rc = ble_gatts_add_svcs(GATT_SERVICES);
    }
    if (rc != 0) {
        ESP_LOGE(TAG, "could not register the Nordic UART Service: rc %d", rc);
        return ESP_FAIL;
    }

    nimble_port_freertos_init(host_task);
    return ESP_OK;
}

void ble_nus_get_stats(ble_nus_stats_t *out)
{
    if (out == NULL) {
        return;
    }
    *out = s_stats;

    /* Summed here rather than accumulated in the write callback, because each
     * link counts its own losses cumulatively and adding a delta on every
     * write would be a second place to get the arithmetic wrong. */
    out->rx_dropped = 0;
    for (size_t i = 0; i < BLE_NUS_MAX_LINKS; i++) {
        lock(s_links[i].lock);
        out->rx_dropped += ble_bytes_dropped(&s_links[i].in);
        unlock(s_links[i].lock);
    }
}

const char *ble_nus_device_name(void)
{
    return s_device_name;
}
