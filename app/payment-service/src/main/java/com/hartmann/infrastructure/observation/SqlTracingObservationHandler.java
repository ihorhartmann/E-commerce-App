package com.hartmann.infrastructure.observation;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import net.ttddyy.observation.tracing.DataSourceBaseContext;
import net.ttddyy.observation.tracing.QueryTracingObservationHandler;

// Creates a SQL statement's span without peer.service: datasource-micrometer gives it the data source as the remote
// service - `ecommerce`, which Grafana shows before every statement's name - and the database is already in the db.*
// attributes. Ordered before datasource-micrometer's own handler, which never gets a statement then: of the tracing
// handlers, only the first that supports an observation handles it.
public class SqlTracingObservationHandler extends QueryTracingObservationHandler {
    public SqlTracingObservationHandler(Tracer tracer) {
        super(tracer);
    }

    @Override
    public Span createSenderSpan(DataSourceBaseContext context) {
        context.setRemoteServiceName(null);
        return super.createSenderSpan(context);
    }
}
