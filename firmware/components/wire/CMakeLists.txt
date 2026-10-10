# wire.c is pure C - no ESP-IDF, no sockets, no clock - so test/ compiles this
# same source on the host and diffs what it emits against the repository's
# wire.py. The one dependency is sps30's codec header, which is pure too: the
# reading encoder names its fields from SPS30_FIELD_NAMES rather than keeping a
# second copy of the ten-value order.
idf_component_register(
    SRCS "wire.c"
    INCLUDE_DIRS "include"
    REQUIRES sps30
)
