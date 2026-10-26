package com.hartmann.interfaceadapter.common.tracing

import com.hartmann.usecase.port.common.tracing.TraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.context.propagation.TextMapSetter
import org.springframework.stereotype.Component

/**
 * Reads and restores the current OpenTelemetry context, which is also what Spring's observations (HTTP, Kafka) use as
 * their parent - so a Kafka send inside [withParent] becomes a child span of the stored one.
 */
@Component
class OpenTelemetryTraceContextPropagator(
    private val propagator: TextMapPropagator,
) : TraceContextPropagator {
    private companion object {
        const val TRACEPARENT = "traceparent"

        val SETTER = TextMapSetter<MutableMap<String, String>> { carrier, key, value -> carrier?.put(key, value) }

        val GETTER = object : TextMapGetter<Map<String, String>> {
            override fun keys(carrier: Map<String, String>) = carrier.keys

            override fun get(carrier: Map<String, String>?, key: String) = carrier?.get(key)
        }
    }

    override fun currentTraceparent(): String? {
        val carrier = mutableMapOf<String, String>()
        propagator.inject(Context.current(), carrier, SETTER)
        return carrier[TRACEPARENT]
    }

    override fun <T> withParent(traceparent: String?, block: () -> T): T {
        if (traceparent == null) {
            return block()
        }

        val context = propagator.extract(Context.current(), mapOf(TRACEPARENT to traceparent), GETTER)
        return context.makeCurrent().use { block() }
    }
}
