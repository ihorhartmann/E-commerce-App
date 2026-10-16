package com.hartmann.core.auth

/**
 * Starts signing in or out - and leaves the app doing so: both go through the gateway and Keycloak as full page
 * loads, so nothing comes back here; the outcome shows on the next page load (`/api/v1/me`).
 */
interface AuthFlowLauncher {
    fun signIn()

    fun signOut()
}
