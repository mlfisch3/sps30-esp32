# Host tests for the pure parts of the firmware.
#
# No ESP-IDF, no hardware, no toolchain activation. Eighteen suites, all
# compiling the same translation units the firmware does rather than copies of
# them:
#
#   test_shdlc      the SHDLC codec and the measurement decoding, cross-checked
#                   against the repository's sps30.py
#   test_acq_queue  the bounded drop-oldest reading queue
#   test_wire       the JSONL protocol encoder and its command reader,
#                   cross-checked against the repository's wire.py
#   test_store      the on-device page format, cross-checked against the
#                   repository's store.py
#   test_store_merge  taking a session back in without losing one, over a
#                   simulated ring that refuses every overwrite
#   test_store_preserve  what survives the ring wrapping: the alert policy, the
#                   segment summary, and the two rings of the preservation area
#                   driven right through a wrap
#   test_store_sessions  the rule that a session's summary goes when its pages
#                   go and at no other time, which is the difference between a
#                   session being readable and being readings on flash that
#                   nothing can name
#   test_store_walk the read half of `get_file`: the rows it returns held against
#                   the whole-ring cursor that shipped before it, over a real
#                   2.19 MB ring, and then what the reads cost
#   test_store_drain  the only code here that deletes the captain's data on
#                   purpose, driven through every way it can be interrupted -
#                   a confirmation that does not match, a cancel, a reboot part
#                   way through, the wrap boundary, preserved alert segments,
#                   and the final block that is kept rather than freed
#   test_store_map  the occupancy map's classifier: what a cell may say about a
#                   page comes from that page's own header and from nowhere
#                   else, over real pages, a real anchor, a torn write and
#                   flash that refuses to read
#
#   test_ble_bytes  the inbound buffer behind the BLE RX characteristic, and
#                   what it discards when a command stream overruns it
#   test_acq_clean  the fan-clean account: a week of run time in microseconds,
#                   including the restart it has to survive
#   test_acq_profile  the bounds a client-supplied acquisition profile is held
#                   to, cross-checked against the repository's wire.py
#   test_net_import the rule that one `put_file` transfer produces exactly one
#                   message, which is the difference between refusing a second
#                   importer and wedging both of them
#   test_net_mdns   what the board announces to every device on the LAN: that it
#                   is the record the app browses for, and - the half that needs
#                   a test rather than a review - that it is only that, with no
#                   serial number, address or live state in it
#   test_net_budget whether the heap can afford one more connection, which is
#                   the difference between refusing a client and starving the
#                   Wi-Fi driver and dropping every client at once
#   test_net_time   the zone's UTC offset in both units the system uses, cross-
#                   checked against the repository's wire.py, which is where a
#                   value sixty times too large shows up
#   test_power_model  what the board draws and how long a battery would last it,
#                   cross-checked against the repository's power.py. Nothing in
#                   that model has been measured, so the two mirrors agreeing is
#                   the only check either of them has - and the property that
#                   matters, that the reported time is short by design, is
#                   asserted independently of the vectors
#
# The standard pack and the slow one
# ----------------------------------
#   make                build and run the standard pack - all eighteen above
#   make slow           build and run the long-running suites, if any
#
# The standard pack runs in about a tenth of a second once built, and that is
# the point of it: it is quick enough that there is never a reason not to run
# it. A suite that would spoil that goes in the slow pack instead, and joins it
# by being named `slow_*.c` and added to SLOW_BINS below. There is currently
# nothing in it - every suite here is fast by construction, because each one is
# pure C over its own buffers with the clock and the flash passed in, which is
# exactly what lets `test_acq_clean` measure a week and `test_store_preserve`
# wrap a two-day ring in microseconds. `make slow` says so rather than
# succeeding silently.
#
#   make replay         build replay_store, which puts a real board's partition
#                       image through the export/import round trip. Selective
#                       rather than slow: it needs an image no checkout has
#   make vectors        regenerate vectors_generated.h from sps30.py
#   make wire-vectors   regenerate wire_vectors_generated.h from wire.py
#   make store-vectors  regenerate store_vectors_generated.h from store.py
#   make power-vectors  regenerate power_vectors_generated.h from power.py
#   make clean
#
# Refusing to look successful when it cannot run
# ----------------------------------------------
# A pack that cannot run must say so and exit non-zero, never exit quietly and
# let the absence of failures read as a pass. Three guards here:
#
#   1. No compiler -> a named error before anything is built, not `cc: not
#      found` buried in the first recipe.
#   2. Every suite must exit 0 *and* report a positive check count on its
#      `N checks, M failures` line, which is the one convention all of them
#      share (indented or not - `test_net_import` indents its). A binary that
#      returns 0 having asserted nothing is exactly the failure mode this is
#      about, so "it counted nothing" is treated as "it did not run".
#   3. The last line is a verdict naming how many of the expected suites ran
#      and how many checks they made, so `17/17` versus `16/17` is visible at a
#      glance and an emptied-out suite shows up as a collapsed check count.
#   4. The verdict also names the pack this target *excluded* and calls its
#      state unknown, so a green line cannot be read as covering suites it
#      never ran. A pack that is only ever excluded can sit red indefinitely
#      while every routine run prints green - that happened in the apk pack,
#      and this line is what makes it impossible to miss here.
#
# The repository's other two packs have the same split and the same guards:
#   ./run_tests.sh software  /  ./run_tests.sh slow                (Python)
#   apk: ./gradlew :app:testDebugUnitTest  /  ... -Pslow           (Kotlin)

CC      ?= cc

# Checked at parse time so the message arrives before any recipe runs. Costs
# one `command -v`, which keeps the pack's tenth of a second intact.
ifeq ($(shell command -v $(CC) 2>/dev/null),)
$(error no C compiler: `$(CC)` is not on PATH. These host tests need one - \
install with `sudo apt install build-essential` on Debian/Ubuntu, or pass \
CC=<compiler>. Nothing was built and nothing was run: this is a setup failure, \
not a test result)
endif

# If a platform lacks ASan/UBSan the link fails loudly, which is the right
# outcome; build with `make CFLAGS='-std=c11 -Wall -Wextra -Werror -O1 -g'` to
# run without them, knowing that is a weaker pack than the one CI runs.
CFLAGS  ?= -std=c11 -Wall -Wextra -Werror -Wpedantic -O1 -g \
           -fsanitize=address,undefined -fno-omit-frame-pointer
LDLIBS  += -lm
CODEC   := ../components/shdlc
SPS30   := ../components/sps30
ACQ     := ../components/acq
WIRE    := ../components/wire
STORE   := ../components/store
BLE     := ../components/ble
NET     := ../components/net
POWER   := ../components/power
BUILD   := build
SHDLC_BIN := $(BUILD)/test_shdlc
QUEUE_BIN := $(BUILD)/test_acq_queue
WIRE_BIN  := $(BUILD)/test_wire
STORE_BIN := $(BUILD)/test_store
MERGE_BIN := $(BUILD)/test_store_merge
BLE_BIN   := $(BUILD)/test_ble_bytes
CLEAN_BIN := $(BUILD)/test_acq_clean
PRESERVE_BIN := $(BUILD)/test_store_preserve
SESSIONS_BIN := $(BUILD)/test_store_sessions
WALK_BIN  := $(BUILD)/test_store_walk
DRAIN_BIN := $(BUILD)/test_store_drain
MAP_BIN   := $(BUILD)/test_store_map
PROFILE_BIN := $(BUILD)/test_acq_profile
IMPORT_BIN := $(BUILD)/test_net_import
BUDGET_BIN := $(BUILD)/test_net_budget
MDNS_BIN  := $(BUILD)/test_net_mdns
NETTIME_BIN := $(BUILD)/test_net_time
POWER_BIN := $(BUILD)/test_power_model

# The slow pack. A long-running suite is named `slow_*.c` and its binary is
# listed here; `make slow` builds and runs exactly these, and `make` never does.
SLOW_BINS := $(BUILD)/slow_wire_format

# Cores the slow pack may use. Every suite in it takes a shard count and a shard
# index, so a sweep over an input space splits without any threading in the C.
SLOW_SHARDS ?= $(shell nproc 2>/dev/null || echo 1)

.PHONY: all test slow replay vectors wire-vectors store-vectors power-vectors clean

all: test

TEST_BINS := $(SHDLC_BIN) $(QUEUE_BIN) $(WIRE_BIN) $(STORE_BIN) $(MERGE_BIN) \
             $(BLE_BIN) $(CLEAN_BIN) $(PRESERVE_BIN) $(PROFILE_BIN) \
             $(IMPORT_BIN) $(BUDGET_BIN) $(MDNS_BIN) $(NETTIME_BIN) $(SESSIONS_BIN) \
             $(POWER_BIN) $(WALK_BIN) $(DRAIN_BIN) $(MAP_BIN)

test: $(TEST_BINS)
ifeq ($(strip $(TEST_BINS)),)
	@echo "no host suites are listed in TEST_BINS: this target would report" >&2
	@echo "success having run nothing. Refusing to." >&2
	@exit 1
else
	@set -u; ran=0; checks=0; want=$(words $(TEST_BINS)); \
	for bin in $(TEST_BINS); do \
	    echo; \
	    if ! out=$$(./$$bin 2>&1); then \
	        printf '%s\n' "$$out"; \
	        echo; echo "VERDICT: it ran, and it was not fine - $$bin failed" >&2; \
	        exit 1; \
	    fi; \
	    printf '%s\n' "$$out"; \
	    n=$$(printf '%s\n' "$$out" \
	         | sed -n 's/^[[:space:]]*\([0-9][0-9]*\) checks, .*/\1/p' | tail -1); \
	    if [ -z "$$n" ] || [ "$$n" -le 0 ]; then \
	        echo; \
	        echo "VERDICT: $$bin exited 0 having reported no checks." >&2; \
	        echo "A suite that asserted nothing did not pass, it did not run." >&2; \
	        exit 1; \
	    fi; \
	    ran=$$((ran + 1)); checks=$$((checks + n)); \
	done; \
	if [ "$$ran" -ne "$$want" ]; then \
	    echo; echo "VERDICT: INCOMPLETE - $$ran of $$want suites ran" >&2; exit 1; \
	fi; \
	echo; \
	echo "VERDICT: it ran, and it was fine - $$ran/$$want suites, $$checks checks"; \
	echo "  (this target excluded the slow pack, whose state is unknown until \`make slow\` says otherwise)"
endif

# The other half of the split. Kept a separate target rather than a flag on this
# one, so that "the standard pack" is what `make` with no arguments means.
slow: $(SLOW_BINS)
ifeq ($(strip $(SLOW_BINS)),)
	@echo "no long-running host suites: every suite in this directory is fast"
	@echo "by construction. Add one as slow_*.c and list it in SLOW_BINS."
else
	@set -u; for bin in $(SLOW_BINS); do \
	    echo; echo "$$bin over $(SLOW_SHARDS) shards"; \
	    i=0; pids=''; \
	    while [ $$i -lt $(SLOW_SHARDS) ]; do \
	        ./$$bin $(SLOW_SHARDS) $$i > $(BUILD)/shard.$$i.log 2>&1 & \
	        pids="$$pids $$!"; i=$$((i + 1)); \
	    done; \
	    bad=0; for p in $$pids; do wait $$p || bad=1; done; \
	    cat $(BUILD)/shard.*.log; \
	    checks=$$(sed -n 's/^\([0-9][0-9]*\) checks, .*/\1/p' $(BUILD)/shard.*.log \
	              | paste -sd+ | bc); \
	    fails=$$(sed -n 's/^[0-9][0-9]* checks, \([0-9][0-9]*\) failures/\1/p' \
	             $(BUILD)/shard.*.log | paste -sd+ | bc); \
	    rm -f $(BUILD)/shard.*.log; \
	    echo; \
	    if [ "$$bad" != 0 ] || [ "$$fails" != 0 ] || [ -z "$$checks" ] || [ "$$checks" -le 0 ]; then \
	        echo "VERDICT: it ran, and it was not fine - $$bin, $$fails failures" >&2; \
	        exit 1; \
	    fi; \
	    echo "VERDICT: it ran, and it was fine - $$bin, $$checks checks"; \
	done; \
	echo "  (this target excluded the standard pack, whose state is unknown until \`make\` says otherwise)"
endif

$(SHDLC_BIN): test_shdlc.c $(CODEC)/shdlc.c $(CODEC)/include/shdlc.h \
              $(SPS30)/sps30_codec.c $(SPS30)/include/sps30_codec.h \
              vectors_generated.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(CODEC)/include -I$(SPS30)/include -I. -o $@ \
	    test_shdlc.c $(CODEC)/shdlc.c $(SPS30)/sps30_codec.c $(LDLIBS)

# acq_queue.c needs sps30_codec.h for the measurement type but none of its code;
# sps30_codec.c is linked in anyway so the whole record is exercised.
$(QUEUE_BIN): test_acq_queue.c $(ACQ)/acq_queue.c $(ACQ)/include/acq_queue.h \
              $(SPS30)/sps30_codec.c $(SPS30)/include/sps30_codec.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(ACQ)/include -I$(SPS30)/include -I. -o $@ \
	    test_acq_queue.c $(ACQ)/acq_queue.c $(SPS30)/sps30_codec.c $(LDLIBS)

# wire.c needs sps30_codec.c for SPS30_FIELD_NAMES: the reading encoder names
# its keys from that list rather than keeping a second copy of the order.
# store_alert.h comes in too, for one assertion that is worth the include: the
# line a board with no phone preserves against is 20.0 in three places - here,
# wire.py and the app's AlertRule defaults - and that is exactly the kind of
# constant that drifts silently. store_map.c and store_codec.c come in for the
# storage_map vectors: the frame's cells are packed by the shipping packer, so
# asserting the emitted line against wire.py exercises packer, base64 and JSON
# as the one pipeline the board actually runs.
$(WIRE_BIN): test_wire.c $(WIRE)/wire.c $(WIRE)/include/wire.h \
             $(SPS30)/sps30_codec.c $(SPS30)/include/sps30_codec.h \
             $(STORE)/include/store_alert.h $(STORE)/store_map.c \
             $(STORE)/include/store_map.h $(STORE)/store_codec.c \
             $(STORE)/include/store_codec.h wire_vectors_generated.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(WIRE)/include -I$(SPS30)/include -I$(STORE)/include -I. -o $@ \
	    test_wire.c $(WIRE)/wire.c $(STORE)/store_map.c $(STORE)/store_codec.c \
	    $(SPS30)/sps30_codec.c $(LDLIBS)

# net_import.c is one of the two host-testable translation units in
# components/net, and it is one on purpose: everything else there is FreeRTOS and
# lwIP, so the rule that a `put_file` transfer answers exactly once had nowhere
# to be checked until it was lifted out. net_time_offset.c below was lifted out
# for the same reason and after the same kind of defect. This one needs wire.c
# for WIRE_CSV_NAME_LEN's neighbours and sps30_codec.c for what wire.c names its
# keys from.
$(IMPORT_BIN): test_net_import.c $(NET)/net_import.c $(NET)/include/net_import.h \
               $(WIRE)/include/wire.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(NET)/include -I$(WIRE)/include -I$(SPS30)/include -I. -o $@ \
	    test_net_import.c $(NET)/net_import.c $(LDLIBS)

# net_budget.h is header-only and pure arithmetic, which is the whole point of
# it: the decision to accept or refuse a connection is the one thing in the
# accept path that can be checked without a board, and it is the one that got
# six clients onto a heap that could carry five.
$(BUDGET_BIN): test_net_budget.c $(NET)/include/net_budget.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(NET)/include -I. -o $@ test_net_budget.c $(LDLIBS)

# net_mdns_record.c is the third host-testable translation unit in
# components/net, and it is there for a reason the other two do not have: what a
# device broadcasts to a whole network, unencrypted and unprompted, is a
# security surface as well as a compatibility one, and "we did not put the serial
# number in it" is a claim worth having a build check rather than a reviewer's
# memory. It needs wire.h for the port the SRV record points at - the same
# constant the listener binds - and sps30_codec.h because wire.h names its keys
# from that list.
$(MDNS_BIN): test_net_mdns.c $(NET)/net_mdns_record.c $(NET)/include/net_mdns_record.h \
             $(WIRE)/include/wire.h wire_vectors_generated.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(NET)/include -I$(WIRE)/include -I$(SPS30)/include -I. -o $@ \
	    test_net_mdns.c $(NET)/net_mdns_record.c $(LDLIBS)

# net_time_offset.c is the second host-testable translation unit in
# components/net, and it was split out of net_time.c to become one: the rest of
# that file is SNTP and NVS, while the zone arithmetic is `<time.h>` and builds
# anywhere. It is compiled here rather than copied, and held to the same
# instants wire.py resolves - which is where an offset in the wrong unit shows.
#
# The one suite that needs a feature-test macro: `localtime_r`, `gmtime_r`,
# `setenv` and `tzset` are POSIX rather than C11, and `-std=c11 -Wpedantic`
# hides them on glibc. ESP-IDF's newlib exposes them without asking, so this is
# a host-build concern only and does not belong in the source.
$(NETTIME_BIN): test_net_time.c $(NET)/net_time_offset.c $(NET)/include/net_time.h \
                wire_vectors_generated.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -D_POSIX_C_SOURCE=200809L -I$(NET)/include -I. -o $@ \
	    test_net_time.c $(NET)/net_time_offset.c $(LDLIBS)

# store_codec.c is pure C over its own buffers: no ESP-IDF, no flash, no clock.
# store.c is deliberately absent - it is esp_partition and erase timing, and a
# fake for those would prove nothing the format tests do not already prove.
$(STORE_BIN): test_store.c $(STORE)/store_codec.c $(STORE)/include/store_codec.h \
              store_vectors_generated.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(STORE)/include -I. -o $@ \
	    test_store.c $(STORE)/store_codec.c $(LDLIBS)
# ble_bytes.c is pure C - no ESP-IDF and no NimBLE - which is the only reason
# the overflow rule can be tested at all: reaching it on hardware means writing
# to a GATT characteristic faster than a task drains it.
$(BLE_BIN): test_ble_bytes.c $(BLE)/ble_bytes.c $(BLE)/include/ble_bytes.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(BLE)/include -I. -o $@ \
	    test_ble_bytes.c $(BLE)/ble_bytes.c $(LDLIBS)

# acq_clean.c is pure C over a caller-owned struct - no clock, no flash - which
# is what lets a week of measurement run here in microseconds instead of a week.
$(CLEAN_BIN): test_acq_clean.c $(ACQ)/acq_clean.c $(ACQ)/include/acq_clean.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(ACQ)/include -I. -o $@ \
	    test_acq_clean.c $(ACQ)/acq_clean.c $(LDLIBS)

# acq_profile.c is the same kind of pure - arithmetic over a caller-owned struct
# - which is what lets the clamping be held against wire.py's own answers rather
# than against expectations somebody wrote down twice.
$(PROFILE_BIN): test_acq_profile.c $(ACQ)/acq_profile.c $(ACQ)/include/acq_profile.h \
                wire_vectors_generated.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(ACQ)/include -I. -o $@ \
	    test_acq_profile.c $(ACQ)/acq_profile.c $(LDLIBS)

# power_model.c is pure in the same way, and depends on acq_profile.c for the
# one thing it must not reimplement: the bounds a profile off a socket is held
# to. A cycle nobody clamped would average the sensor's draw away to nothing and
# turn into a predicted runtime of months.
#
# power_report.c and wire.c join the link because the suite also holds the whole
# `power_estimate` reply - composition and encoding - against lines the real
# `power.estimate_message` produced; sps30_codec.c only because wire.c's reading
# encoder references it.
$(POWER_BIN): test_power_model.c $(POWER)/power_model.c $(POWER)/power_report.c \
              $(POWER)/include/power_model.h $(POWER)/include/power_report.h \
              $(ACQ)/acq_profile.c $(ACQ)/include/acq_profile.h \
              $(WIRE)/wire.c $(WIRE)/include/wire.h \
              $(SPS30)/sps30_codec.c $(SPS30)/include/sps30_codec.h \
              power_vectors_generated.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(POWER)/include -I$(ACQ)/include -I$(WIRE)/include \
	    -I$(SPS30)/include -I$(STORE)/include -I. -o $@ \
	    test_power_model.c $(POWER)/power_model.c $(POWER)/power_report.c \
	    $(WIRE)/wire.c $(SPS30)/sps30_codec.c $(ACQ)/acq_profile.c $(LDLIBS)

# store_merge.c is the same kind of pure: the ring it writes into is a callback,
# so the test supplies one that counts writes and refuses overwrites, and the
# "an import never evicts" claim is checked rather than asserted.
$(MERGE_BIN): test_store_merge.c $(STORE)/store_merge.c $(STORE)/include/store_merge.h \
              $(STORE)/store_codec.c $(STORE)/include/store_codec.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(STORE)/include -I. -o $@ \
	    test_store_merge.c $(STORE)/store_merge.c $(STORE)/store_codec.c $(LDLIBS)

# store_alert.c and store_preserve.c are pure in the same way store_merge.c is,
# and for the same payoff: the flash under the preservation area is three
# function pointers, so this drives both of its rings right through a wrap and
# checks what survived. On the board that would take two days of recording.
$(PRESERVE_BIN): test_store_preserve.c $(STORE)/store_alert.c $(STORE)/include/store_alert.h \
                 $(STORE)/store_preserve.c $(STORE)/include/store_preserve.h \
                 $(STORE)/store_codec.c $(STORE)/include/store_codec.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(STORE)/include -I. -o $@ \
	    test_store_preserve.c $(STORE)/store_alert.c $(STORE)/store_preserve.c \
	    $(STORE)/store_codec.c $(LDLIBS)

# store_sessions.c is pure in the same way, and it is the one that decides what
# the board can still *name*: it needs store_codec.c for the page header and
# wire.c for the two name forms a session can have, which is why sps30_codec.c
# comes with it - wire.c names its keys from that list.
$(SESSIONS_BIN): test_store_sessions.c $(STORE)/store_sessions.c \
                 $(STORE)/include/store_sessions.h $(STORE)/store_codec.c \
                 $(STORE)/include/store_codec.h $(WIRE)/wire.c $(WIRE)/include/wire.h \
                 $(SPS30)/sps30_codec.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(STORE)/include -I$(WIRE)/include -I$(SPS30)/include -I. -o $@ \
	    test_store_sessions.c $(STORE)/store_sessions.c $(STORE)/store_codec.c \
	    $(WIRE)/wire.c $(SPS30)/sps30_codec.c $(LDLIBS)

# store_walk.c is pure for the same reason store_sessions.c is, and it is the
# other half of the same question: that one decides which sessions the board can
# name, this one decides which pages a `get_file` has to read to answer for one.
# Its ring is a callback, so the suite builds a real 2.19 MB ring with the
# shipping page writer, walks it both ways - the old whole-ring cursor is kept
# in the test in full - and checks the rows are identical before it looks at what
# the reads cost. It needs store_sessions.c because the span the walk is planned
# against is the summary's, and wire.c because that is what names a session.
$(WALK_BIN): test_store_walk.c $(STORE)/store_walk.c $(STORE)/include/store_walk.h \
             $(STORE)/store_sessions.c $(STORE)/include/store_sessions.h \
             $(STORE)/store_codec.c $(STORE)/include/store_codec.h \
             $(WIRE)/wire.c $(WIRE)/include/wire.h $(SPS30)/sps30_codec.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(STORE)/include -I$(WIRE)/include -I$(SPS30)/include -I. -o $@ \
	    test_store_walk.c $(STORE)/store_walk.c $(STORE)/store_sessions.c \
	    $(STORE)/store_codec.c $(WIRE)/wire.c $(SPS30)/sps30_codec.c $(LDLIBS)

# store_map.c is pure for the map's one safety property: what it says about a
# page comes from that page's own header and from nowhere else. The suite writes
# real pages with the shipping writer, seals a real anchor page, tears one write
# and corrupts another, and asserts the map answers slot by slot - including
# that flash which cannot be read reports bad rather than empty. The states and
# the 2-bit packing are protocol vocabulary, held to wire.py through the same
# generated vectors test_wire.c uses.
$(MAP_BIN): test_store_map.c $(STORE)/store_map.c $(STORE)/include/store_map.h \
            $(STORE)/store_codec.c $(STORE)/include/store_codec.h \
            $(STORE)/include/store_walk.h wire_vectors_generated.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(STORE)/include -I. -o $@ \
	    test_store_map.c $(STORE)/store_map.c $(STORE)/store_codec.c $(LDLIBS)

# The only suite that cannot be fast: 2^32 float32 values, formatted twice each,
# against the `snprintf` wire_format_fixed replaced. It is the difference between
# "the bytes are the same on what we tried" and "the bytes are the same", which
# is the difference between a formatting change and a protocol change.
$(BUILD)/slow_wire_format: slow_wire_format.c $(WIRE)/wire.c $(WIRE)/include/wire.h \
                           $(SPS30)/sps30_codec.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(WIRE)/include -I$(SPS30)/include -I. -o $@ \
	    slow_wire_format.c $(WIRE)/wire.c $(SPS30)/sps30_codec.c $(LDLIBS)

# A host tool rather than a test: it needs an image of a real board's `store`
# partition, which no checkout has. Given one it exercises the shipping decoder,
# the shipping CSV formatter, the shipping row reader and the shipping merge over
# readings that were actually measured. See replay_store.c for how to get one.
REPLAY_BIN := $(BUILD)/replay_store
replay: $(REPLAY_BIN)

$(REPLAY_BIN): replay_store.c $(STORE)/store_merge.c $(STORE)/store_codec.c \
               $(WIRE)/wire.c $(SPS30)/sps30_codec.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(STORE)/include -I$(WIRE)/include -I$(SPS30)/include -I. -o $@ \
	    replay_store.c $(STORE)/store_merge.c $(STORE)/store_codec.c $(WIRE)/wire.c \
	    $(SPS30)/sps30_codec.c $(LDLIBS)

# Regenerates the expectations by running the real sps30.py. Deliberately not a
# dependency of the build: the generated header is committed so the tests run
# without Python, and regenerating is an explicit act.
vectors:
	python3 gen_vectors.py

# The same, for the protocol half, from the repository's wire.py.
wire-vectors:
	python3 gen_wire_vectors.py

# The same, for the storage format, from the repository's store.py.
store-vectors:
	python3 gen_store_vectors.py

# The same, for the power model, from the repository's power.py. It prints a
# note on every run saying that nothing in that model has been measured, which
# is the fact most likely to stop being obvious once the numbers look tidy.
power-vectors:
	python3 gen_power_vectors.py

clean:
	rm -rf $(BUILD)

# store_drain.c is pure in the same way store_preserve.c is, and for the sharpest
# reason of the lot: it is the only code in this firmware that deletes readings
# on purpose. Its flash is two function pointers, so this suite fills a ring,
# wraps it, fills the preservation area, and then interrupts the drain at every
# point at which an interruption could lose something - which is not a thing that
# can be done on a board whose owner is recording an event he cares about.
$(DRAIN_BIN): test_store_drain.c $(STORE)/store_drain.c $(STORE)/include/store_drain.h \
              $(STORE)/store_codec.c $(STORE)/include/store_codec.h \
              $(STORE)/store_alert.c $(STORE)/include/store_alert.h \
              $(STORE)/store_sessions.c $(STORE)/include/store_sessions.h \
              $(WIRE)/wire.c $(WIRE)/include/wire.h $(SPS30)/sps30_codec.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -I$(STORE)/include -I$(WIRE)/include -I$(SPS30)/include -I. -o $@ \
	    test_store_drain.c $(STORE)/store_drain.c $(STORE)/store_codec.c \
	    $(STORE)/store_alert.c $(STORE)/store_sessions.c $(WIRE)/wire.c \
	    $(SPS30)/sps30_codec.c $(LDLIBS)
