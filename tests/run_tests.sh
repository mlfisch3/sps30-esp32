#!/usr/bin/env bash
# Standard SPS30 test protocol.
#
# The standard pack is the one that gets run, and it stays quick because that is
# what makes it get run. Everything long-running is marked and excluded from it,
# and has its own command below.
#
#   ./run_tests.sh software   # the standard pack: no sensor, no board, ~4 s
#   ./run_tests.sh slow       # only the long-running ones (see below)
#   ./run_tests.sh hardware   # + hardware-in-the-loop tests (run on the Pi)
#   ./run_tests.sh all        # alias for hardware
#
# The slow pack needs a controller to talk to, and the tests that send anything
# back to it need to be told that is allowed:
#
#   ./run_tests.sh slow --board 192.168.1.226
#   ./run_tests.sh slow --board 192.168.1.226 --board-writable
#
# A test joins the slow pack by carrying `@pytest.mark.slow`; see tests/conftest.py.
#
# Extra args are forwarded to pytest, e.g.:
#   ./run_tests.sh hardware --port /dev/ttyUSB0 -v
#
# Refusing to look successful when it cannot run
# ----------------------------------------------
# Two guards, because this script once exited in 0.15 s with a bare
# `No module named pytest`, which reads as a pass when it is timed casually:
#
#   1. A preflight (tools/check_test_deps.py) names every missing package and
#      how to install it, then exits non-zero without pretending to have run.
#   2. The pack ends with an explicit verdict line (tests/conftest.py) that says
#      whether it ran, not merely whether what ran was green. A test skipped
#      because this machine could not run it turns the whole run red; pass
#      `--allow-degraded` to accept that knowingly.
#
# And one more, on every pack: the verdict names the tests the command
# *excluded* and calls their state unknown, so a green line cannot be read as
# covering a pack it never ran. An excluded pack can otherwise sit red
# indefinitely while every routine run prints green - the apk pack did exactly
# that, and its verdicts now go further: each pack reports the other's last
# known result and how stale it is (see apk/app/build.gradle.kts).
#
# The other two packs in this repository have the same split, and the same
# guards:
#   firmware/test:  make                                  /  make slow
#   apk:            ./gradlew :app:testDebugUnitTest      /  ... -Pslow
set -euo pipefail
cd "$(dirname "$0")"

mode="${1:-software}"
shift || true

# One interpreter for both the preflight and the run. Resolving `pytest` from
# PATH separately is exactly how a preflight ends up checking a different
# environment than the tests run in, which would defeat the point of having one.
py="${PYTHON:-python3}"

case "$mode" in
  software|slow|hardware|all) ;;
  *) echo "usage: $0 [software|slow|hardware|all] [pytest args...]" >&2; exit 2 ;;
esac

"$py" tools/check_test_deps.py --quiet || exit 1

case "$mode" in
  software) exec "$py" -m pytest -m "not hardware and not slow" "$@" ;;
  slow) exec "$py" -m pytest --run-slow -m "slow" "$@" ;;
  hardware|all) exec "$py" -m pytest --run-hardware -m "not slow" "$@" ;;
esac
