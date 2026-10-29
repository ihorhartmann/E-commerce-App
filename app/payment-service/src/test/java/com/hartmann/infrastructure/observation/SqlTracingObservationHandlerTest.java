package com.hartmann.infrastructure.observation;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import net.ttddyy.observation.tracing.QueryContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class SqlTracingObservationHandlerTest {

    @Test
    void createSenderSpan_startsAClientSpanWithoutTheDataSourceAsItsRemoteService() {
        var tracer = mock(Tracer.class);
        var builder = mock(Span.Builder.class, RETURNS_SELF);
        var span = mock(Span.class);
        when(tracer.spanBuilder()).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        var context = new QueryContext();
        context.setRemoteServiceName("ecommerce");

        var actual = new SqlTracingObservationHandler(tracer).createSenderSpan(context);

        assertSame(span, actual);
        verify(builder, times(1)).kind(Span.Kind.CLIENT);
        verify(builder, never()).remoteServiceName(any());
        verify(builder, times(1)).start();
    }
}
