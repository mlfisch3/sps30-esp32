#!/usr/bin/env python3
"""What the Python test pack needs installed, and a check that it is there.

This file is the single source of truth for that list. `run_tests.sh` runs it
before pytest, `requirements-dev.txt` mirrors it for `pip install -r`, and
README section 7.1 points at both.

Why it exists
-------------
`./run_tests.sh software` once exited in 0.15 s with a bare `No module named
pytest`. Timed casually that reads as a pass, and it had been reporting nothing
for an unknown length of time. A runner that cannot run must say so in words and
exit non-zero, because a check that quietly does nothing manufactures confidence
that a failing check never would.

The list is deliberately wider than "what pytest needs to start". A missing
`textual` does not stop the pack; it turns six GUI tests into skips, and a run
with skips is not a clean run. Everything needed for the *whole* standard pack
to actually execute belongs here.

Run it directly for a report:

    python3 tools/check_test_deps.py          # exit 0 if the pack can run
"""
from __future__ import annotations

import importlib.util
import sys
from dataclasses import dataclass

INSTALL_HINT = "python3 -m pip install -r requirements-dev.txt"


@dataclass(frozen=True)
class Dep:
    module: str  # what `import` names it
    package: str  # what pip names it
    why: str  # what stops working without it


# Keep in step with requirements-dev.txt.
REQUIRED = (
    Dep("pytest", "pytest", "the test runner itself - without it nothing runs at all"),
    Dep("serial", "pyserial", "sps30.py's serial port; every driver test imports it"),
    Dep("textual", "textual", "the GUI app class the headless smoke tests mount"),
    Dep(
        "textual_plotext",
        "textual-plotext",
        "the chart widget that app is built from (pulls in plotext)",
    ),
)


def missing(deps=REQUIRED):
    """Return the deps that cannot be imported, without importing them.

    `find_spec` locates the module without executing it, which keeps this check
    at a few milliseconds - the packs are fast, and a preflight that cost a
    second would be a reason to skip the preflight.
    """
    absent = []
    for dep in deps:
        try:
            found = importlib.util.find_spec(dep.module) is not None
        except (ImportError, ValueError):
            found = False
        if not found:
            absent.append(dep)
    return absent


def report(absent, stream=sys.stderr):
    """Print the plain-words explanation of why nothing can be run."""
    n = len(absent)
    print(
        f"\nCANNOT RUN THE TEST PACK: {n} required "
        f"{'package is' if n == 1 else 'packages are'} missing.\n",
        file=stream,
    )
    width = max(len(d.package) for d in absent)
    for dep in absent:
        print(f"  {dep.package:<{width}}  {dep.why}", file=stream)
    print(
        f"\nInstall with:\n\n    {INSTALL_HINT}\n"
        f"\nNothing was run. This is a setup failure, not a test result -"
        f" do not read it as a pass.\n",
        file=stream,
    )


def main(argv):
    quiet = "--quiet" in argv[1:]
    absent = missing()
    if absent:
        report(absent)
        return 1
    if not quiet:
        names = ", ".join(d.package for d in REQUIRED)
        print(f"test dependencies present: {names}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
