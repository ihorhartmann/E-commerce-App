package com.hartmann.app

import com.hartmann.core.auth.AuthFlowLauncher
import com.hartmann.core.auth.SessionApi
import com.hartmann.core.auth.model.CurrentUser
import com.hartmann.core.error.ErrorHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

sealed interface SessionState {
    data object Loading : SessionState

    data object Guest : SessionState

    data class SignedIn(val user: CurrentUser) : SessionState
}

class SessionViewModel(
    private val scope: CoroutineScope,
    private val sessionApi: SessionApi,
    private val authFlowLauncher: AuthFlowLauncher,
    private val errorHandler: ErrorHandler,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state

    fun load() {
        scope.launch {
            _state.value = try {
                sessionApi.getCurrentUser()?.let { SessionState.SignedIn(it) } ?: SessionState.Guest
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // the catalog still works without a session, so the app goes on as for a guest
                errorHandler.handle(error)
                SessionState.Guest
            }
        }
    }

    fun signIn() = authFlowLauncher.signIn()

    fun signOut() = authFlowLauncher.signOut()
}
