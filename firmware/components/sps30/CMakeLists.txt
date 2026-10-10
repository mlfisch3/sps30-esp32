# sps30_codec.c is deliberately pure - no ESP-IDF, no UART - so test/ compiles
# that same source on the host, exactly as it does for the shdlc codec.
idf_component_register(
    SRCS "sps30.c" "sps30_codec.c"
    INCLUDE_DIRS "include"
    REQUIRES driver shdlc esp_timer
)
