package com.hartmann.app

import com.hartmann.core.auth.AuthFlowLauncher
import com.hartmann.core.auth.SessionApi
import com.hartmann.core.auth.model.CurrentUser
import com.hartmann.core.auth.model.Role
import com.hartmann.core.error.ErrorHandler
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verify.VerifyMode.Companion.not
import dev.mokkery.verifySuspend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private val sessionApi = mock<SessionApi>()
    private val authFlowLauncher = mock<AuthFlowLauncher>(MockMode.autoUnit)
    private val errorHandler = mock<ErrorHandler>(MockMode.autoUnit)

    private fun TestScope.viewModel() = SessionViewModel(backgroundScope, sessionApi, authFlowLauncher, errorHandler)

    @Test
    fun `load - signs the user in when there is a session`() = runTest {
        val user = CurrentUser("0f8a", "Client One", setOf(Role.CLIENT))
        everySuspend { sessionApi.getCurrentUser() } returns user
        val viewModel = viewModel()

        viewModel.load()
        runCurrent()

        assertEquals(SessionState.SignedIn(user), viewModel.state.value)
        verifySuspend(exactly(1)) { sessionApi.getCurrentUser() }
    }

    @Test
    fun `load - treats the user as a guest when there is no session`() = runTest {
        everySuspend { sessionApi.getCurrentUser() } returns null
        val viewModel = viewModel()

        viewModel.load()
        runCurrent()

        assertEquals(SessionState.Guest, viewModel.state.value)
        verify(not) { errorHandler.handle(any()) }
    }

    @Test
    fun `load - goes on as a guest when the session can't be checked`() = runTest {
        val error = IllegalStateException("gateway down")
        everySuspend { sessionApi.getCurrentUser() } throws error
        val viewModel = viewModel()

        viewModel.load()
        runCurrent()

        assertEquals(SessionState.Guest, viewModel.state.value)
        verify(exactly(1)) { errorHandler.handle(error) }
    }

    @Test
    fun `signIn - starts signing the user in`() = runTest {
        val viewModel = viewModel()

        viewModel.signIn()

        verify(exactly(1)) { authFlowLauncher.signIn() }
    }

    @Test
    fun `signOut - starts signing the user out`() = runTest {
        val viewModel = viewModel()

        viewModel.signOut()

        verify(exactly(1)) { authFlowLauncher.signOut() }
    }
}
