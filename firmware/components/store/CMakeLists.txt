# On-device storage. store_codec.c is pure C - no ESP-IDF, no flash, no clock -
# so test/ compiles that same source on the host and diffs what it emits against
# the repository's store.py. store_merge.c is pure in the same way, and for a
# sharper reason: the ring it writes into is a callback, so the host tests can
# supply one that refuses every overwrite and *check* that an import never costs
# a reading rather than take the claim on trust.
#
# store_alert.c and store_preserve.c are pure for that same second reason. The
# preservation area is a pair of rings that only misbehave once they have turned
# over, and turning one over on the board means two days of continuous 1 Hz. The
# flash underneath it is three function pointers, so the host tests fill it,
# wrap it, and check what survived - in milliseconds.
#
# store_sessions.c is pure for a third reason, and the sharpest of the three:
# what it decides is which sessions the board can still *name*, and a summary
# dropped while its pages are live turns readings that are on flash into
# readings nothing can ask for. That rule had nowhere to be checked while it
# lived inside store.c; the host pack now drives it directly.
#
# store_walk.c is pure for a fourth reason, and it is the read half of the same
# argument: what it decides is which pages a `get_file` has to read to answer,
# and getting that wrong is either a session that comes back short or most of a
# second of flash spent per request. Its ring is a callback, so the host pack
# walks a real ring image and counts the reads.
#
# store_drain.c is pure for a fifth reason, and it is the one that matters most:
# it is the only code in this firmware that deletes the captain's data on
# purpose, block by block, and every one of its failure paths - a confirmation
# that does not match, a cancel, a reboot part way through, the wrap boundary,
# the final block that must be kept - is a thing that has to be *checked* rather
# than argued for. Its flash is two function pointers, so the host pack drives
# every one of them in milliseconds against a ring the board would take two days
# to fill.
#
# store_map.c is pure for a sixth reason, the map's one safety property: what
# the occupancy map says about a page comes from that page's own header and
# from nowhere else. Its ring is the same callback store_walk.c reads, so the
# host pack writes real pages, tears one, corrupts another, and checks the map
# answers slot by slot rather than from a fraction.
#
# store.c is the flash ring and is not host-testable: it is esp_partition,
# FreeRTOS and erase timing, and there is nothing left in it that a fake would
# prove.
idf_component_register(
    SRCS "store_codec.c" "store_merge.c" "store_alert.c" "store_preserve.c"
         "store_sessions.c" "store_walk.c" "store_drain.c" "store_map.c" "store.c"
    INCLUDE_DIRS "include"
    REQUIRES acq wire spi_flash esp_partition
)
