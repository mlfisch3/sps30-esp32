package com.sensirion.sps30.net

import java.net.Socket

/**
 * Where every socket to the controller comes from.
 *
 * The portable connection code - [Discovery.probe] and [DaemonClient] - must
 * not name a platform class, but on Android every socket to the controller has
 * to come from the Wi-Fi network binder so it is tied to that network from
 * creation (a full-tunnel VPN or a Wi-Fi with no internet would otherwise
 * misroute it - see `WifiNetworkBinder` in :app for the whole story). This
 * object is the seam between those two facts: the platform layer installs its
 * factory once at startup, and the portable code asks here.
 *
 * The default is a plain [Socket], which is correct wherever the platform has
 * no say in routing: the JVM tests, and any host where the default route is
 * the right one.
 *
 * Installed once per process, before the first connection (`MonitorSession.
 * ensureStarted` on Android, in the same breath as `WifiNetworkBinder.start`).
 * Not per-call configuration: how a socket reaches the controller is a
 * property of the platform, not of any one caller, which is why this is an
 * installed fact rather than a parameter threaded through every signature.
 */
object ControllerSockets {

    @Volatile
    var factory: () -> Socket = { Socket() }

    /** An unconnected socket, from whatever factory the platform installed. */
    fun create(): Socket = factory()
}
