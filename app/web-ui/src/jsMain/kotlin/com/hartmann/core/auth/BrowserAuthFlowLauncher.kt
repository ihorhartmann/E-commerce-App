package com.hartmann.core.auth

import com.hartmann.core.network.CSRF_COOKIE
import com.hartmann.core.network.readCookie
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement

class BrowserAuthFlowLauncher private constructor() : AuthFlowLauncher {
    // Spring Security's OAuth2 login endpoint: redirects to Keycloak and, once signed in, back to the app
    override fun signIn() {
        window.location.assign(LOGIN_PATH)
    }

    /**
     * Signing out has to be a real form POST, not a fetch: the gateway answers with a redirect to Keycloak's
     * end-session page, which only a page navigation follows. The CSRF token goes along as the `_csrf` form field.
     */
    override fun signOut() {
        val form = document.createElement("form") as HTMLFormElement
        form.method = "post"
        form.action = LOGOUT_PATH
        readCookie(CSRF_COOKIE)?.let { token ->
            val input = document.createElement("input") as HTMLInputElement
            input.type = "hidden"
            input.name = CSRF_FORM_FIELD
            input.value = token
            form.appendChild(input)
        }
        document.body?.appendChild(form)
        form.submit()
    }

    companion object {
        private const val LOGIN_PATH = "/oauth2/authorization/keycloak"
        private const val LOGOUT_PATH = "/logout"
        private const val CSRF_FORM_FIELD = "_csrf"

        fun create(): AuthFlowLauncher = BrowserAuthFlowLauncher()
    }
}
