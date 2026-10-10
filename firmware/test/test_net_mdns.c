/**
 * @file test_net_mdns.c
 * @brief Host tests for what the board announces to every device on the LAN.
 *
 * Two questions, and the second one is the reason this file exists.
 *
 * **Is the advertisement what the app is looking for?** The service type, the
 * proto, the host name, the instance name and both TXT records are held against
 * `wire.py`, which is where they are defined and which the app mirrors
 * separately. Nobody can test the C against the Kotlin here, but both are
 * testable against the Python, and a name that differs by one character is a
 * controller no client can find - the discovery equivalent of a message type
 * silently ignored.
 *
 * **Is the advertisement *only* that?** A DNS-SD record is multicast to the
 * whole link, unencrypted, unprompted and repeatedly, so every field in it is
 * public to anyone listening. `docs/SECURITY_PLAN.md` takes the sensor's serial
 * number *out* of `hello` on exactly that reasoning - it is a permanent hardware
 * identifier handed to a stranger who connects - and an advertisement is handed
 * to a stranger who does not even do that. So this suite pins the record shut:
 * the TXT count is asserted, the keys are asserted by name and position, and the
 * rendered advertisement is searched for the identifiers that were available and
 * deliberately left out. A third TXT key cannot be added without this failing,
 * which is the point: it makes adding one a decision rather than a diff.
 *
 * `net_mdns.c` is ESP-IDF and does not compile here; `net_mdns_record.c` is pure
 * C and does, and it is compiled rather than copied. See the Makefile.
 */

#include <math.h> /* wire_vectors_generated.h spells NaN and infinity */
#include <stdio.h>
#include <string.h>

#include "net_mdns_record.h"
#include "wire.h"
#include "wire_vectors_generated.h"

static int g_checks = 0;
static int g_failures = 0;

#define CHECK(cond, ...)                                                       \
    do {                                                                       \
        g_checks++;                                                            \
        if (!(cond)) {                                                         \
            g_failures++;                                                      \
            printf("  FAIL %s:%d: ", __FILE__, __LINE__);                      \
            printf(__VA_ARGS__);                                               \
            printf("\n");                                                      \
        }                                                                      \
    } while (0)

/**
 * The names, against `wire.py`.
 *
 * The app has never seen this board and has no channel to negotiate over: it
 * browses for a service type it was compiled with. Either the two strings match
 * or discovery does not happen at all, and there is no partial credit and no
 * error message.
 */
static void test_the_names_are_the_ones_wire_py_defines(void)
{
    CHECK(strcmp(NET_MDNS_SERVICE, PY_MDNS_SERVICE) == 0,
          "service type is %s, wire.py says %s", NET_MDNS_SERVICE, PY_MDNS_SERVICE);
    CHECK(strcmp(NET_MDNS_PROTO, PY_MDNS_PROTO) == 0, "proto is %s, wire.py says %s",
          NET_MDNS_PROTO, PY_MDNS_PROTO);
    CHECK(strcmp(NET_MDNS_HOSTNAME, PY_MDNS_HOSTNAME) == 0,
          "host name is %s, wire.py says %s", NET_MDNS_HOSTNAME, PY_MDNS_HOSTNAME);
    CHECK(strcmp(NET_MDNS_INSTANCE, PY_MDNS_INSTANCE) == 0,
          "instance is %s, wire.py says %s", NET_MDNS_INSTANCE, PY_MDNS_INSTANCE);
}

/**
 * The names, against DNS-SD itself.
 *
 * RFC 6763 section 7: a service name is a leading underscore and at most 15
 * characters in total, letters, digits and hyphens only, with at least one
 * letter. A name that breaks the rule is not rejected loudly by a responder -
 * it is advertised, and then some clients see it and some do not, which is the
 * worst way for a compatibility surface to be wrong.
 */
static void test_the_service_name_is_legal_dns_sd(void)
{
    const size_t len = strlen(NET_MDNS_SERVICE);
    CHECK(NET_MDNS_SERVICE[0] == '_', "a DNS-SD service name starts with an underscore");
    CHECK(len >= 2 && len <= 15, "a DNS-SD service name is 2 to 15 bytes; this is %u",
          (unsigned)len);

    bool has_letter = false;
    bool legal = true;
    for (size_t i = 1; i < len; i++) {
        const char c = NET_MDNS_SERVICE[i];
        const bool alpha = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        const bool digit = c >= '0' && c <= '9';
        has_letter = has_letter || alpha;
        legal = legal && (alpha || digit || c == '-');
    }
    CHECK(legal, "%s has a character DNS-SD does not allow", NET_MDNS_SERVICE);
    CHECK(has_letter, "%s has no letter in it", NET_MDNS_SERVICE);
    CHECK(strcmp(NET_MDNS_PROTO, "_tcp") == 0 || strcmp(NET_MDNS_PROTO, "_udp") == 0,
          "DNS-SD has two protocols and %s is not one of them", NET_MDNS_PROTO);
}

/**
 * Exactly the TXT records intended, in order, and no others.
 *
 * The count is checked first and on its own, because it is what makes the rest
 * of this suite a specification rather than a sample: asserting that key 0 is
 * `txtvers` and key 1 is `proto` says nothing at all about a key 2.
 */
static void test_the_txt_records_are_exactly_two_and_these(void)
{
    CHECK(NET_MDNS_TXT_COUNT == PY_MDNS_TXT_COUNT,
          "the firmware advertises %d TXT records, wire.py names %d", NET_MDNS_TXT_COUNT,
          PY_MDNS_TXT_COUNT);

    net_mdns_txt_t txt[NET_MDNS_TXT_COUNT + 2];
    memset(txt, 0, sizeof(txt));
    const size_t count = net_mdns_txt_records(txt, NET_MDNS_TXT_COUNT + 2);
    CHECK(count == (size_t)PY_MDNS_TXT_COUNT, "wrote %u TXT records, wire.py names %d",
          (unsigned)count, PY_MDNS_TXT_COUNT);

    for (size_t i = 0; i < count && i < (size_t)PY_MDNS_TXT_COUNT; i++) {
        CHECK(txt[i].key != NULL && strcmp(txt[i].key, PY_MDNS_TXT[i].key) == 0,
              "TXT %u key is %s, wire.py says %s", (unsigned)i,
              txt[i].key != NULL ? txt[i].key : "(null)", PY_MDNS_TXT[i].key);
        CHECK(txt[i].value != NULL && strcmp(txt[i].value, PY_MDNS_TXT[i].value) == 0,
              "TXT %u value is %s, wire.py says %s", (unsigned)i,
              txt[i].value != NULL ? txt[i].value : "(null)", PY_MDNS_TXT[i].value);
    }

    /* Nothing was written past the records that exist, so a caller that sizes
     * its array from the constant and then iterates the return value cannot
     * read a key that was never set. */
    CHECK(txt[NET_MDNS_TXT_COUNT].key == NULL && txt[NET_MDNS_TXT_COUNT].value == NULL,
          "an entry past the last TXT record was written to");
}

/**
 * A caller that cannot hold the whole record gets none of it.
 *
 * Half an advertisement is not a smaller advertisement, it is a different one -
 * a client reading a TXT set with `proto` missing would have to guess what
 * version it is talking to.
 */
static void test_a_short_array_gets_nothing(void)
{
    net_mdns_txt_t one[1] = {{(const char *)0x1, (const char *)0x1}};
    CHECK(net_mdns_txt_records(one, 1) == 0, "a one-entry array was given a partial record set");
    CHECK(one[0].key == (const char *)0x1, "a refused call wrote to the caller's array anyway");
    CHECK(net_mdns_txt_records(NULL, NET_MDNS_TXT_COUNT) == 0, "a NULL array was written to");
}

/** The rendered line, which is both what the console prints and what the rest
 *  of this suite reads. */
static void test_the_rendered_advertisement_says_what_it_should(void)
{
    char line[NET_MDNS_DESCRIBE_LEN];
    const int used = net_mdns_describe(line, sizeof(line), WIRE_TCP_PORT);
    CHECK(used > 0, "the advertisement did not render");
    CHECK(used > 0 && (size_t)used == strlen(line), "the length returned is not the length written");

    CHECK(strstr(line, NET_MDNS_INSTANCE) != NULL, "no instance name in %s", line);
    CHECK(strstr(line, NET_MDNS_SERVICE) != NULL, "no service type in %s", line);
    CHECK(strstr(line, NET_MDNS_PROTO) != NULL, "no proto in %s", line);
    CHECK(strstr(line, NET_MDNS_HOSTNAME ".local") != NULL, "no .local host name in %s", line);

    /* The port is the one the server listens on, from the same constant, so an
     * SRV record cannot point at a port nothing is bound to. */
    char port[16];
    snprintf(port, sizeof(port), "%u", (unsigned)WIRE_TCP_PORT);
    CHECK(strstr(line, port) != NULL, "no port %s in %s", port, line);
    CHECK(WIRE_TCP_PORT == PY_DEFAULT_PORT, "the advertised port is not wire.py's");

    CHECK(strstr(line, NET_MDNS_TXT_VERSION "=" NET_MDNS_TXT_VERSION_VALUE) != NULL,
          "no txtvers in %s", line);
    CHECK(strstr(line, NET_MDNS_TXT_PROTOCOL "=" NET_MDNS_TXT_PROTOCOL_VALUE) != NULL,
          "no proto record in %s", line);
}

/**
 * A buffer that will not hold the whole line gets an empty string, not a piece
 * of one - the same promise every encoder in `wire.c` makes, for the same
 * reason: a truncated record read as a whole one is worse than no record.
 */
static void test_a_short_buffer_renders_nothing(void)
{
    char full[NET_MDNS_DESCRIBE_LEN];
    const int used = net_mdns_describe(full, sizeof(full), WIRE_TCP_PORT);
    CHECK(used > 0, "the advertisement did not render");

    /* Every length short of the whole thing, including the ones that cut inside
     * a TXT record rather than before it. */
    for (size_t cap = 1; cap <= (size_t)used; cap++) {
        char small[NET_MDNS_DESCRIBE_LEN];
        memset(small, 'x', sizeof(small));
        CHECK(net_mdns_describe(small, cap, WIRE_TCP_PORT) == -1,
              "a %u-byte buffer claimed to hold a %d-byte line", (unsigned)cap, used);
        CHECK(small[0] == '\0', "a refused render left %u bytes of something behind",
              (unsigned)cap);
    }

    CHECK(net_mdns_describe(NULL, NET_MDNS_DESCRIBE_LEN, WIRE_TCP_PORT) == -1,
          "a NULL buffer was written to");
    CHECK(NET_MDNS_DESCRIBE_LEN > used,
          "NET_MDNS_DESCRIBE_LEN (%d) does not leave room for the line it is for (%d)",
          NET_MDNS_DESCRIBE_LEN, used);
}

/**
 * What is *not* in the advertisement.
 *
 * Each of these was available at the point the record is built and each one is
 * left out on purpose; see net_mdns_record.h. They are checked by value rather
 * than by review because a reviewer reads the record once and this reads it on
 * every build.
 */
static void test_nothing_identifies_the_unit(void)
{
    char line[NET_MDNS_DESCRIBE_LEN];
    CHECK(net_mdns_describe(line, sizeof(line), WIRE_TCP_PORT) > 0,
          "the advertisement did not render");

    /* The SPS30's serial number. `main.c` knows it at boot and `hello` carries
     * it today; the security plan removes it from `hello` precisely because it
     * is a permanent hardware identifier. */
    CHECK(strstr(line, "E3164D2544338487") == NULL, "the sensor's serial number is on the LAN");

    /* The radio's address, in the shape components/ble renders it - the
     * Bluetooth name is `SPS30-c24aea`, and repeating that here would put the
     * same permanent identifier on the other transport. */
    CHECK(strstr(line, "SPS30-") == NULL, "a MAC-derived name is on the LAN");
    CHECK(strstr(line, ":") == NULL || strstr(line, ".local:") != NULL,
          "something colon-separated that is not the port is in %s", line);

    /* Live state. None of it helps a client choose a device, and the client
     * count in particular would be an occupancy beacon updated as people come
     * and go. */
    CHECK(strstr(line, ".csv") == NULL, "the session name is on the LAN");
    CHECK(strstr(line, "clients") == NULL, "the client count is on the LAN");

    /*
     * And the general form of it: every byte of the advertisement comes from a
     * fixed string in `net_mdns_record.c` or from the port. Nothing is derived
     * from the chip, the sensor, the clock or the state, so two boards flashed
     * from the same image advertise identical records - which is the property a
     * passive observer defeats an identifier with.
     */
    char again[NET_MDNS_DESCRIBE_LEN];
    CHECK(net_mdns_describe(again, sizeof(again), WIRE_TCP_PORT) > 0,
          "the advertisement did not render");
    CHECK(strcmp(line, again) == 0, "the advertisement is not the same twice");
}

int main(void)
{
    printf("net_mdns: what the board announces to the LAN\n");
    test_the_names_are_the_ones_wire_py_defines();
    test_the_service_name_is_legal_dns_sd();
    test_the_txt_records_are_exactly_two_and_these();
    test_a_short_array_gets_nothing();
    test_the_rendered_advertisement_says_what_it_should();
    test_a_short_buffer_renders_nothing();
    test_nothing_identifies_the_unit();

    printf("  %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
