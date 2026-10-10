/**
 * @file power_report.h
 * @brief The whole `power_estimate` reply, composed from the model.
 *
 * The mirror of `power.estimate_message`: what the board draws in the state it
 * is in, what every other acquisition mode would cost instead, and a runtime
 * slot that says `no_capacity` until a battery exists to say otherwise. The
 * generated vectors hold this composition to the Python byte for byte, which is
 * what lets the app render the reply without owning a power model of its own.
 *
 * Pure in the same way `power_model.h` is: no ESP-IDF, no clock, no sockets.
 * Everything about the board arrives in the ::power_state_t, and the answer
 * leaves as one encoded line for the caller to send.
 */
#pragma once

#include "power_model.h"
#include "wire.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief Encode the `power_estimate` reply for @p state and @p battery.
 *
 * The composition rules are `power.estimate_message`'s, and they are the
 * contract:
 *
 * * An alternative is **this board doing something else**: every input except
 *   the acquisition profile is carried over unchanged, except a stated write
 *   rate, which describes what the board is doing right now rather than what
 *   recording under another profile would write - each alternative derives its
 *   rate from its own profile.
 * * `sleeps` is this board's reality under every alternative. A board whose
 *   sensor cannot sleep is priced without the sleeping, because showing it the
 *   capable board's saving would overstate a switch it cannot make.
 *
 * @param state what the board is doing. NULL is the default state.
 * @param battery what is known about remaining capacity. NULL - every board
 *        today - reports the runtime slot as `no_capacity`.
 * @return bytes written, or -1 if @p cap cannot hold the line.
 */
int power_report_line(char *out, size_t cap, const power_state_t *state,
                      const power_battery_t *battery);

#ifdef __cplusplus
}
#endif
