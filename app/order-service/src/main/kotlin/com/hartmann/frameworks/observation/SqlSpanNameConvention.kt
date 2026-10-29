package com.hartmann.frameworks.observation

import net.ttddyy.observation.tracing.QueryContext
import net.ttddyy.observation.tracing.QueryObservationConvention

/** The table a statement works on: after `from` (select, delete), `into` (insert) or `update`. */
private val TABLE_REGEX = Regex("""\b(?:from|into|update)\s+([\w."]+)""", RegexOption.IGNORE_CASE)

/** The name of a statement whose operation can't be told - datasource-micrometer's own. */
private const val UNKNOWN_STATEMENT = "query"

/**
 * `{OPERATION} {table}`, OpenTelemetry's database span name - `SELECT outbox_messages`, `UPDATE orders` - a
 * schema the statement names left out - taken from the statement as prepared, so no bound value gets into it; the
 * operation alone when no table is found.
 */
fun sqlSpanName(sql: String?): String {
    val operation = sql?.trim()?.substringBefore(' ')?.uppercase()
    if (operation.isNullOrEmpty()) {
        return UNKNOWN_STATEMENT
    }

    val table = TABLE_REGEX.find(sql)?.groupValues?.get(1)?.replace("\"", "")?.substringAfterLast('.')
    return if (table.isNullOrEmpty()) operation else "$operation $table"
}

/** Names a SQL statement's span `SELECT outbox_messages` instead of datasource-micrometer's bare `query`. */
class SqlSpanNameConvention : QueryObservationConvention {
    // a batch is named after its first statement - the statements of one batch are the same one, prepared once
    override fun getContextualName(context: QueryContext): String = sqlSpanName(context.queries.firstOrNull())
}
