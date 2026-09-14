package com.hartmann.adapter.out.idempotency;

import com.hartmann.adapter.out.idempotency.row.HttpRequestRow;
import com.hartmann.application.port.out.idempotency.HttpRequestStore;
import com.hartmann.application.port.out.idempotency.dto.HttpRequestRecord;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.SQLDataType.INTEGER;
import static org.jooq.impl.SQLDataType.TIMESTAMPWITHTIMEZONE;
import static org.jooq.impl.SQLDataType.VARCHAR;

@Repository
public class PostgresHttpRequestStore implements HttpRequestStore {
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private static final Table<Record> IDEMPOTENT_HTTP_REQUESTS = table(name("payment", "idempotent_http_requests"));

    private static final Field<String> ID = field(name("idempotent_http_requests", "id"), VARCHAR);
    private static final Field<String> REQUEST_HASH = field(name("idempotent_http_requests", "request_hash"), VARCHAR);
    private static final Field<String> STATUS = field(name("idempotent_http_requests", "status"), VARCHAR);
    private static final Field<Integer> HTTP_CODE = field(name("idempotent_http_requests", "http_code"), INTEGER);
    private static final Field<JSONB> RESPONSE = field(name("idempotent_http_requests", "response"), org.jooq.impl.SQLDataType.JSONB);
    private static final Field<OffsetDateTime> CREATED_AT = field(name("idempotent_http_requests", "created_at"), TIMESTAMPWITHTIMEZONE);
    private static final Field<OffsetDateTime> CLAIMED_AT = field(name("idempotent_http_requests", "claimed_at"), TIMESTAMPWITHTIMEZONE);
    private static final Field<OffsetDateTime> COMPLETED_AT = field(name("idempotent_http_requests", "completed_at"), TIMESTAMPWITHTIMEZONE);

    private final DSLContext dsl;

    public PostgresHttpRequestStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * ON CONFLICT DO NOTHING rather than catching a constraint-violation error: a repeated
     * idempotencyKey is a routine, expected outcome here, not an exceptional one.
     */
    @Override
    public boolean insert(String idempotencyKey, String requestHash) {
        var now = Instant.now();
        var row = new HttpRequestRow(idempotencyKey, requestHash, STATUS_IN_PROGRESS, null, null, now, now, null);

        int inserted = dsl.transactionResult(configuration -> configuration.dsl().insertInto(IDEMPOTENT_HTTP_REQUESTS)
                .set(ID, row.id())
                .set(REQUEST_HASH, row.requestHash())
                .set(STATUS, row.status())
                .set(CREATED_AT, row.createdAt().atOffset(ZoneOffset.UTC))
                .set(CLAIMED_AT, row.claimedAt().atOffset(ZoneOffset.UTC))
                .onConflictDoNothing()
                .execute());

        return inserted == 1;
    }

    @Override
    public Optional<HttpRequestRecord> find(String idempotencyKey) {
        return dsl.selectFrom(IDEMPOTENT_HTTP_REQUESTS)
                .where(ID.eq(idempotencyKey))
                .fetchOptional(this::toRecord);
    }

    @Override
    public boolean reclaim(String idempotencyKey, Duration ttl) {
        var cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(ttl);

        int reclaimed = dsl.transactionResult(configuration -> configuration.dsl().update(IDEMPOTENT_HTTP_REQUESTS)
                .set(CLAIMED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .where(ID.eq(idempotencyKey))
                .and(STATUS.eq(STATUS_IN_PROGRESS))
                .and(CLAIMED_AT.lt(cutoff))
                .execute());

        return reclaimed == 1;
    }

    @Override
    public void complete(String idempotencyKey, int httpCode, String response) {
        dsl.transaction(configuration -> configuration.dsl().update(IDEMPOTENT_HTTP_REQUESTS)
                .set(STATUS, STATUS_COMPLETED)
                .set(HTTP_CODE, httpCode)
                .set(RESPONSE, response == null ? null : JSONB.valueOf(response))
                .set(COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .where(ID.eq(idempotencyKey))
                .execute());
    }

    @Override
    public int deleteExpired(Instant cutoff, int limit) {
        return dsl.transactionResult(configuration -> configuration.dsl().execute(
                "DELETE FROM payment.idempotent_http_requests WHERE ctid IN ("
                        + "SELECT ctid FROM payment.idempotent_http_requests WHERE created_at < ? LIMIT ?)",
                cutoff.atOffset(ZoneOffset.UTC), limit
        ));
    }

    private HttpRequestRecord toRecord(Record record) {
        var completedAt = record.get(COMPLETED_AT);
        var response = record.get(RESPONSE);
        return new HttpRequestRecord(
                record.get(ID),
                record.get(REQUEST_HASH),
                record.get(HTTP_CODE),
                response == null ? null : response.data(),
                record.get(CREATED_AT).toInstant(),
                record.get(CLAIMED_AT).toInstant(),
                completedAt == null ? null : completedAt.toInstant()
        );
    }
}
