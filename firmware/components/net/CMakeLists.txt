# Wi-Fi, SNTP and the JSONL TCP server. Almost none of this is host-testable -
# it is ESP-IDF and lwIP - which is exactly why the protocol encoding it uses
# lives in the pure `wire` component next door and is tested there.
#
# `net_import.c`, `net_time_offset.c` and `net_mdns_record.c` are the exceptions
# and deliberately so: all three are pure C with no ESP-IDF in them, and the host
# suites compile these same translation units rather than copies. `net_import.c`
# holds the one rule that a `put_file` transfer answers exactly once - see
# net_import.h for what happened when that rule was not held. `net_time_offset.c`
# holds the zone's offset in both units the system uses, which is where seconds
# were once handed to a field that records minutes. `net_mdns_record.c` holds
# what the board announces to the whole LAN, which is worth being able to read
# and assert on without a radio.
#
# `mdns` is not an IDF component in v5.3 - it comes from the registry through
# idf_component.yml next door, which says why.
idf_component_register(
    SRCS "net_wifi.c" "net_time.c" "net_time_offset.c" "net_server.c" "net_import.c"
         "net_mdns.c" "net_mdns_record.c"
    INCLUDE_DIRS "include"
    REQUIRES wire acq store power esp_wifi esp_netif esp_event nvs_flash esp_timer lwip mdns
)
