package com.hartmann.core.auth

import com.hartmann.core.auth.dto.CurrentUserResponse
import com.hartmann.core.auth.dto.fromCurrentUserResponse
import com.hartmann.core.auth.model.CurrentUser
import com.hartmann.core.network.bodyOrThrow
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode

class KtorSessionApi private constructor(private val client: HttpClient) : SessionApi {
    override suspend fun getCurrentUser(): CurrentUser? {
        val response = client.get("api/v1/me")
        // the gateway answers a guest (no session) with 204, not with an error
        if (response.status == HttpStatusCode.NoContent) {
            return null
        }
        return fromCurrentUserResponse(response.bodyOrThrow<CurrentUserResponse>())
    }

    companion object {
        fun create(client: HttpClient): SessionApi = KtorSessionApi(client)
    }
}
