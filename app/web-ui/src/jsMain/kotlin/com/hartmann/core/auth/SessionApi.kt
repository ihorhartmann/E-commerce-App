package com.hartmann.core.auth

import com.hartmann.core.auth.model.CurrentUser

interface SessionApi {
    /** The signed-in user, or null for a guest (no session). */
    suspend fun getCurrentUser(): CurrentUser?
}
