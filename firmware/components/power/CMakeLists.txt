# power_model.c is pure C over a caller-owned struct - no ESP-IDF, no clock, no
# flash - so test/ compiles it on the host and holds it against vectors the real
# `power.py` produced. It depends on `acq` only for `acq_profile_normalize`,
# which is `wire.normalize_profile` and is pure in the same way.
#
# power_report.c is the model's one caller: it composes the `power_estimate`
# reply - the current state priced, every alternative mode priced, the runtime
# slot that says `no_capacity` until a battery exists - and hands it to `wire`
# to encode. That is why this component depends on `wire`; the model itself
# still does not.
idf_component_register(
    SRCS "power_model.c" "power_report.c"
    INCLUDE_DIRS "include"
    REQUIRES acq wire
)
