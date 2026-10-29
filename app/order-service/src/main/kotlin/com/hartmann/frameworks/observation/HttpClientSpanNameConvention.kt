package com.hartmann.frameworks.observation

import org.springframework.http.client.observation.ClientRequestObservationContext
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention

/** Names an outgoing call's span `POST /api/v1/payments` instead of Spring's `http post`. */
class HttpClientSpanNameConvention : DefaultClientRequestObservationConvention() {
    override fun getContextualName(context: ClientRequestObservationContext): String =
        httpSpanName(context.carrier?.method?.name() ?: UNKNOWN_METHOD, context.uriTemplate)
}
