package com.sensirion.sps30

/**
 * The one way code in :core says something to a log.
 *
 * This module must not name `android.util.Log`, and on the plain JVM there is
 * no logcat to name; the default writes to stderr, which the JVM tests can see
 * and Android redirects to logcat anyway. The app installs the real thing at
 * startup (`MonitorSession.ensureStarted`), in the same breath as
 * [net.ControllerSockets] - the same seam, for words instead of sockets.
 *
 * Deliberately one level. Core code logs almost nothing by design - the state
 * flows and result types are how it reports - and the single caller today is
 * the reassembler dropping an over-long line. If you find yourself wanting
 * debug logging here, the question to ask first is why the behaviour is not
 * visible in a returned value.
 */
object CoreLog {

    @Volatile
    var warn: (tag: String, message: String) -> Unit = { tag, message ->
        System.err.println("$tag: $message")
    }
}
