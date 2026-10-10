package com.sensirion.sps30.service

import com.sensirion.sps30.ui.DashboardUiState

/**
 * Decides when the ongoing notification is worth posting again.
 *
 * The session's state changes for a great many reasons a notification cannot
 * show: a row landing in the plot, a backfill's progress, a transfer's byte
 * count, a window being panned. The service used to post on every one of them -
 * measured on the tablet at 2-3.5 `notify(42)` calls a second at idle and up to
 * 53 a second during a transfer, 23,157 in 39 minutes - which floods the log and
 * keeps a background service busy redrawing two lines that did not move.
 *
 * So the rule is: **post it when it changes.** The comparison is on
 * [Notifications.Content], which is exactly what the notification draws, so
 * nothing a user can see is ever skipped and nothing they cannot see is ever
 * posted.
 *
 * Deliberately a small object with its own state rather than a `distinctUntilChanged`
 * in the collector: this is the rule that was got wrong, and a rule worth a test
 * has to be reachable by one.
 */
class NotificationGate {

    private var last: Notifications.Content? = null

    /**
     * The content to post for [state], or null when nothing visible has moved
     * since the last post.
     */
    fun postFor(state: DashboardUiState): Notifications.Content? {
        val next = Notifications.contentOf(state)
        if (next == last) return null
        last = next
        return next
    }

    /**
     * Forget what was posted, so the next state is posted whatever it says.
     *
     * For when the notification is no longer on screen - the service stopped and
     * started again - and the gate's memory would otherwise suppress the first
     * post of the new one.
     */
    fun reset() {
        last = null
    }
}
