package com.sensirion.sps30.data

/**
 * Progress of copying every recorded session from the sensor to this device.
 *
 * Named for what it actually does. "Export" had come to mean two different
 * things - writing the plotted data out as a CSV on this device, and pulling the
 * sensor's whole archive across the network - and the two are worth keeping
 * verbally distinct, because confusing them risks someone believing they have a
 * backup when they have a screenshot's worth of numbers.
 */
sealed interface SessionBackup {
    data object Idle : SessionBackup

    /** Asked, but the sensor has not yet said how much is coming. */
    data object Requested : SessionBackup

    /**
     * A copy is running. Every figure here climbs, and they measure four things.
     *
     * [received] and [total] drive the bar and are whatever unit
     * [receivedAreSessions] says they are - sessions when the controller streams
     * its sessions one at a time, bytes when it sends a sized archive. The row
     * that renders this used to print `"session ${received + 1} of $total"` for
     * both, which on the second path reads "session 1 of 4,182,993": a count of
     * bytes with the word "session" in front of it.
     *
     * [rows] and [newHere] are the pair that explains the fault this whole
     * account was rebuilt over. [rows] is what has gone into the archive file
     * and it climbs at the speed of the link; [newHere] is how much of that this
     * device did not already hold, and on a copy of sessions the plot already
     * has it stays at nought from beginning to end. Reporting only the first
     * gave the captain a number racing past 75,000 under a panel correctly
     * saying nothing was missing.
     */
    data class Receiving(
        val received: Long,
        val total: Long,
        val sessions: Int,
        val rows: Long,
        /** Readings this copy has added to this device's own record. */
        val newHere: Long = 0L,
        /** False when [received] and [total] are bytes rather than sessions. */
        val receivedAreSessions: Boolean = true,
    ) : SessionBackup {
        val fraction: Float
            get() = if (total > 0) (received.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f

        /** Sessions finished, or 0 on a path that counts bytes instead. */
        val sessionsDone: Int get() = if (receivedAreSessions) received.toInt() else 0

        /** Sessions expected, or 0 on a path that counts bytes instead. */
        val sessionsTotal: Int get() = if (receivedAreSessions) total.toInt() else 0
    }

    /** Saved. [location] is a human-readable path to show the user. */
    data class Saved(
        val location: String,
        val bytes: Long,
        val sessions: Int,
        val rows: Long,
        /**
         * Sessions the controller says it holds but did not put in this archive.
         *
         * Normally zero, and the controller is built so that it should be: its
         * session list names everything the store knows about. It is carried
         * anyway because the alternative is a user believing a partial archive
         * is a complete backup - which is the one wrong belief this whole
         * feature exists to prevent, and it is exactly what happened when the
         * list was capped below what the store held.
         */
        val missing: Int = 0,
    ) : SessionBackup

    data class Failed(val reason: String) : SessionBackup
}
