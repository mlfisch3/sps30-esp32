# acq_queue.c, acq_clean.c and acq_profile.c are pure C over caller-supplied
# storage, so test/ compiles them on the host; acq.c is the FreeRTOS state
# machine, and the NVS the fan-clean count survives in, and is not host-testable.
idf_component_register(
    SRCS "acq.c" "acq_queue.c" "acq_clean.c" "acq_profile.c"
    INCLUDE_DIRS "include"
    REQUIRES sps30 esp_timer nvs_flash
)
