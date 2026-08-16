package dev.arnv.bluke.bluetooth

/**
 * Allows a HID host connection only after the user has explicitly requested that host.
 * Authorization lasts for the current connection and is cleared as soon as it disconnects.
 */
internal class ManualHidConnectionGate {
    private val lock = Any()
    private var authorizedAddress: String? = null

    fun authorize(address: String) = synchronized(lock) {
        authorizedAddress = address
    }

    fun revoke(address: String? = null) = synchronized(lock) {
        if (address == null || authorizedAddress.equals(address, ignoreCase = true)) {
            authorizedAddress = null
        }
    }

    fun isAuthorized(address: String): Boolean = synchronized(lock) {
        authorizedAddress.equals(address, ignoreCase = true)
    }
}
