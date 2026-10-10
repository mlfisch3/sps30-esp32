# Deliberately no REQUIRES: the codec is pure and depends on nothing, which is
# what lets test/ compile this same source on the host with plain gcc.
idf_component_register(
    SRCS "shdlc.c"
    INCLUDE_DIRS "include"
)
