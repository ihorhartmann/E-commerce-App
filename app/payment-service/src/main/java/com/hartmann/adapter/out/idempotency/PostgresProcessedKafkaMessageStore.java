package com.hartmann.adapter.out.idempotency;

import com.hartmann.adapter.out.idempotency.row.ProcessedKafkaMessageRow;
import com.hartmann.application.port.out.idempotency.ProcessedKafkaMessageStore;
import com.hartmann.application.port.out.idempotency.dto.KafkaMessageMetadata;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.SQLDataType.TIMESTAMPWITHTIMEZONE;
import static org.jooq.impl.SQLDataType.VARCHAR;

/**
 * markProcessed relies on the ambient transaction the injected DSLContext participates in (the
 * same Spring-managed transaction as the business mutation it protects) - it never opens its own,
 * unlike PostgresHttpRequestStore. deleteExpired is the exception, since a sweep has no ambient
 * business transaction to join.
 */
@Repository
public class PostgresProcessedKafkaMessageStore implements ProcessedKafkaMessageStore {
    private static final Table<Record> PROCESSED_KAFKA_MESSAGES = table(name("payment", "processed_kafka_messages"));

    private static final Field<String> ID = field(name("processed_kafka_messages", "id"), VARCHAR);
    private static final Field<String> CORRELATION_ID = field(name("processed_kafka_messages", "correlation_id"), VARCHAR);
    private static final Field<String> MESSAGE_TYPE = field(name("processed_kafka_messages", "message_type"), VARCHAR);
    private static final Field<OffsetDateTime> PROCESSED_AT = field(name("processed_kafka_messages", "processed_at"), TIMESTAMPWITHTIMEZONE);

    private final DSLContext dsl;

    public PostgresProcessedKafkaMessageStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * ON CONFLICT DO NOTHING rather than catching a constraint-violation error: a redelivered
     * messageId is a routine, expected outcome here, not an exceptional one, and this way Postgres
     * never raises an error (and never aborts the transaction) for it - the inserted row count
     * alone tells us which case we're in.
     */
    @Override
    public boolean markProcessed(KafkaMessageMetadata message) {
        var row = new ProcessedKafkaMessageRow(message.messageId(), message.correlationId(), message.messageType(), Instant.now());

        int inserted = dsl.insertInto(PROCESSED_KAFKA_MESSAGES)
                .set(ID, row.id())
                .set(CORRELATION_ID, row.correlationId())
                .set(MESSAGE_TYPE, row.messageType())
                .set(PROCESSED_AT, row.processedAt().atOffset(ZoneOffset.UTC))
                .onConflictDoNothing()
                .execute();

        return inserted == 0;
    }

    @Override
    public int deleteExpired(Instant cutoff, int limit) {
        return dsl.execute(
                "DELETE FROM payment.processed_kafka_messages WHERE ctid IN ("
                        + "SELECT ctid FROM payment.processed_kafka_messages WHERE processed_at < ? LIMIT ?)",
                cutoff.atOffset(ZoneOffset.UTC), limit
        );
    }
}
