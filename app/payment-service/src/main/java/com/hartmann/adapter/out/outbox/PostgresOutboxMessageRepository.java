package com.hartmann.adapter.out.outbox;

import com.hartmann.adapter.out.outbox.row.OutboxMessageRow;
import com.hartmann.application.port.out.outbox.OutboxMessageRepository;
import com.hartmann.application.port.out.outbox.dto.OutboxMessage;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.SQLDataType.TIMESTAMPWITHTIMEZONE;
import static org.jooq.impl.SQLDataType.VARCHAR;

@Repository
public class PostgresOutboxMessageRepository implements OutboxMessageRepository {
    private static final Table<Record> OUTBOX_MESSAGES = table(name("payment", "outbox_messages"));

    private static final Field<UUID> ID = field(name("outbox_messages", "id"), SQLDataType.UUID);
    private static final Field<String> CORRELATION_ID = field(name("outbox_messages", "correlation_id"), VARCHAR);
    private static final Field<String> MESSAGE_TYPE = field(name("outbox_messages", "message_type"), VARCHAR);
    private static final Field<String> KEY = field(name("outbox_messages", "key"), VARCHAR);
    private static final Field<JSONB> PAYLOAD = field(name("outbox_messages", "payload"), SQLDataType.JSONB);
    private static final Field<OffsetDateTime> SENT_AT = field(name("outbox_messages", "sent_at"), TIMESTAMPWITHTIMEZONE);
    private static final Field<OffsetDateTime> CREATED_AT = field(name("outbox_messages", "created_at"), TIMESTAMPWITHTIMEZONE);

    private final DSLContext dsl;

    public PostgresOutboxMessageRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(OutboxMessage message) {
        var row = OutboxMessageRow.from(message);

        dsl.insertInto(OUTBOX_MESSAGES)
                .set(CORRELATION_ID, row.correlationId())
                .set(MESSAGE_TYPE, row.messageType())
                .set(KEY, row.key())
                .set(PAYLOAD, JSONB.valueOf(row.payload()))
                .execute();
    }

    @Override
    public List<OutboxMessage> fetchUnsent(int limit) {
        return dsl.select(ID, CORRELATION_ID, MESSAGE_TYPE, KEY, PAYLOAD, SENT_AT, CREATED_AT)
                .from(OUTBOX_MESSAGES)
                .where(SENT_AT.isNull())
                .orderBy(CREATED_AT)
                .limit(limit)
                .fetch(record -> OutboxMessageRow.toOutboxMessage(new OutboxMessageRow(
                        record.get(ID),
                        record.get(CORRELATION_ID),
                        record.get(MESSAGE_TYPE),
                        record.get(KEY),
                        record.get(PAYLOAD).data(),
                        toInstant(record.get(SENT_AT)),
                        toInstant(record.get(CREATED_AT))
                )));
    }

    private static Instant toInstant(OffsetDateTime offsetDateTime) {
        return offsetDateTime == null ? null : offsetDateTime.toInstant();
    }

    @Override
    public void markSentBatch(List<UUID> messageIds, Instant sentAt) {
        if (messageIds.isEmpty()) {
            return;
        }

        dsl.update(OUTBOX_MESSAGES)
                .set(SENT_AT, sentAt.atOffset(ZoneOffset.UTC))
                .where(ID.in(messageIds))
                .execute();
    }
}
