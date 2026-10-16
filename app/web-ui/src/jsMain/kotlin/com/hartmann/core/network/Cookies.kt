package com.hartmann.core.network

import kotlinx.browser.document

/** The CSRF token cookie the gateway sets; readable on purpose (not HttpOnly), so it can be echoed back. */
const val CSRF_COOKIE = "XSRF-TOKEN"

fun readCookie(name: String): String? =
    document.cookie.split(";")
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter("=")
        ?.let { decodeURIComponent(it) }

private external fun decodeURIComponent(encoded: String): String
