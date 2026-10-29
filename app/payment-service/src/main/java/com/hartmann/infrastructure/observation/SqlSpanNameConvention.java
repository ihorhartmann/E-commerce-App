package com.hartmann.infrastructure.observation;

import net.ttddyy.observation.tracing.QueryContext;
import net.ttddyy.observation.tracing.QueryObservationConvention;

import java.util.Locale;
import java.util.regex.Pattern;

// Names a SQL statement's span `SELECT outbox_messages` instead of datasource-micrometer's bare `query`.
public class SqlSpanNameConvention implements QueryObservationConvention {
    // The table a statement works on: after `from` (select, delete), `into` (insert) or `update`.
    private static final Pattern TABLE_REGEX = Pattern.compile("\\b(?:from|into|update)\\s+([\\w.\"]+)", Pattern.CASE_INSENSITIVE);

    // The name of a statement whose operation can't be told - datasource-micrometer's own.
    static final String UNKNOWN_STATEMENT = "query";

    // `{OPERATION} {table}`, OpenTelemetry's database span name - `SELECT outbox_messages`, `UPDATE payments` - a schema
    // the statement names left out - taken from the statement as prepared, so no bound value gets into it; the operation
    // alone when no table is found.
    static String sqlSpanName(String sql) {
        if (sql == null || sql.isBlank()) {
            return UNKNOWN_STATEMENT;
        }

        var operation = sql.trim().split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        var table = TABLE_REGEX.matcher(sql);
        if (!table.find()) {
            return operation;
        }
        var qualifiedTable = table.group(1).replace("\"", "");
        return operation + " " + qualifiedTable.substring(qualifiedTable.lastIndexOf('.') + 1);
    }

    // A batch is named after its first statement - the statements of one batch are the same one, prepared once.
    @Override
    public String getContextualName(QueryContext context) {
        return sqlSpanName(context.getQueries().isEmpty() ? null : context.getQueries().getFirst());
    }
}
