package com.hartmann.adapter.out.tracing;

import com.hartmann.application.port.out.tracing.TraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Reads and restores the current OpenTelemetry context, which is also what Spring's observations (HTTP, Kafka) use as
 * their parent - so a Kafka send inside withParent becomes a child span of the stored one.
 */
@Component
public class OpenTelemetryTraceContextPropagator implements TraceContextPropagator {
    private static final String TRACEPARENT = "traceparent";

    private static final TextMapSetter<Map<String, String>> SETTER = (carrier, key, value) -> {
        if (carrier != null) {
            carrier.put(key, value);
        }
    };

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private final TextMapPropagator propagator;

    public OpenTelemetryTraceContextPropagator(TextMapPropagator propagator) {
        this.propagator = propagator;
    }

    @Override
    public String currentTraceparent() {
        var carrier = new HashMap<String, String>();
        propagator.inject(Context.current(), carrier, SETTER);
        return carrier.get(TRACEPARENT);
    }

    @Override
    public <T> T withParent(String traceparent, Supplier<T> block) {
        if (traceparent == null) {
            return block.get();
        }

        var context = propagator.extract(Context.current(), Map.of(TRACEPARENT, traceparent), GETTER);
        try (var ignored = context.makeCurrent()) {
            return block.get();
        }
    }
}
