# The Nordic UART Service, carrying the same JSONL the TCP server sends.
#
# `ble_bytes.c` is pure C - no ESP-IDF, no NimBLE - so test/ compiles that exact
# source on the host and exercises the overflow rule that is otherwise only
# reachable by mistreating a phone. Everything that touches the radio is in
# ble_nus.c, which has no tests and whose evidence is the hardware run recorded
# in firmware/README.md. Same split as `wire` against `net`.
idf_component_register(
    SRCS "ble_nus.c" "ble_bytes.c"
    INCLUDE_DIRS "include"
    REQUIRES net wire bt
)
