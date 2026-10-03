package com.hartmann.adapter.out.repository;

import com.hartmann.adapter.out.repository.entity.RefundEntity;
import com.hartmann.application.exception.ApplicationException;
import com.hartmann.application.port.out.repository.RefundRepository;
import com.hartmann.application.port.out.repository.exception.ConcurrentUpdateException;
import com.hartmann.application.port.out.repository.exception.DuplicateIdempotencyKeyException;
import com.hartmann.application.port.out.repository.exception.ObjectNotFoundException;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.SQLDataType.TIMESTAMPWITHTIMEZONE;

@Repository
public class PostgresRefundRepository implements RefundRepository {
    private static final Table<Record> REFUNDS = table(name("payment", "refunds"));

    private static final Field<Long> ID = field(name("refunds", "id"), Long.class);
    private static final Field<Long> PAYMENT_ID = field(name("refunds", "payment_id"), Long.class);
    private static final Field<UUID> IDEMPOTENCY_KEY = field(name("refunds", "idempotency_key"), UUID.class);
    private static final Field<String> EXTERNAL_ID = field(name("refunds", "external_id"), String.class);
    private static final Field<BigDecimal> AMOUNT = field(name("refunds", "amount"), BigDecimal.class);
    private static final Field<String> CURRENCY = field(name("refunds", "currency"), String.class);
    private static final Field<String> STATUS = field(name("refunds", "status"), String.class);
    private static final Field<Long> VERSION = field(name("refunds", "version"), Long.class);
    private static final Field<OffsetDateTime> CREATED_AT = field(name("refunds", "created_at"), TIMESTAMPWITHTIMEZONE);
    private static final Field<OffsetDateTime> UPDATED_AT = field(name("refunds", "updated_at"), TIMESTAMPWITHTIMEZONE);

    private static final List<Field<?>> REFUND_FIELDS = List.of(
            ID, PAYMENT_ID, IDEMPOTENCY_KEY, EXTERNAL_ID, AMOUNT, CURRENCY, STATUS, VERSION, CREATED_AT, UPDATED_AT);

    private final DSLContext dsl;

    public PostgresRefundRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * ON CONFLICT DO NOTHING rather than catching a constraint violation: Postgres never raises an
     * error (or aborts the surrounding transaction) for a duplicate key, and there's no need to dig
     * the violated constraint's name out of the driver exception to tell this conflict from others.
     */
    @Override
    public Refund create(Refund refund) {
        var entity = RefundEntity.from(refund);
        return dsl.insertInto(REFUNDS)
                .set(PAYMENT_ID, entity.paymentId())
                .set(IDEMPOTENCY_KEY, entity.idempotencyKey())
                .set(EXTERNAL_ID, entity.externalId())
                .set(AMOUNT, entity.amount())
                .set(CURRENCY, entity.currency())
                .set(STATUS, entity.status())
                .set(VERSION, entity.version())
                .set(CREATED_AT, toOffsetDateTime(entity.createdAt()))
                .set(UPDATED_AT, toOffsetDateTime(entity.updatedAt()))
                .onConflict(IDEMPOTENCY_KEY)
                .doNothing()
                .returning(REFUND_FIELDS)
                .fetchOptional()
                .map(this::toRefund)
                .orElseThrow(() -> new DuplicateIdempotencyKeyException(
                        "Refund with idempotency key " + entity.idempotencyKey() + " already exists"));
    }

    @Override
    public Optional<Refund> findByIdempotencyKey(UUID idempotencyKey) {
        return dsl.select(REFUND_FIELDS).from(REFUNDS).where(IDEMPOTENCY_KEY.eq(idempotencyKey)).fetchOptional(this::toRefund);
    }

    @Override
    public Refund markPending(long refundId, long version, String externalId, RefundStatus status) {
        return dsl.update(REFUNDS)
                .set(EXTERNAL_ID, externalId)
                .set(STATUS, status.name())
                .set(VERSION, VERSION.plus(1))
                .set(UPDATED_AT, toOffsetDateTime(Instant.now()))
                .where(ID.eq(refundId))
                .and(VERSION.eq(version))
                .returning(REFUND_FIELDS)
                .fetchOptional()
                .map(this::toRefund)
                .orElseThrow(() -> notUpdated(refundId, version));
    }

    @Override
    public List<Refund> findByPaymentId(long paymentId) {
        return dsl.select(REFUND_FIELDS).from(REFUNDS).where(PAYMENT_ID.eq(paymentId)).fetch(this::toRefund);
    }

    @Override
    public Refund updateStatus(long refundId, long version, RefundStatus status) {
        return dsl.update(REFUNDS)
                .set(STATUS, status.name())
                .set(VERSION, VERSION.plus(1))
                .set(UPDATED_AT, toOffsetDateTime(Instant.now()))
                .where(ID.eq(refundId))
                .and(VERSION.eq(version))
                .returning(REFUND_FIELDS)
                .fetchOptional()
                .map(this::toRefund)
                .orElseThrow(() -> notUpdated(refundId, version));
    }

    @Override
    public Refund getByExternalId(String externalId) {
        var record = dsl.select(REFUND_FIELDS).from(REFUNDS).where(EXTERNAL_ID.eq(externalId)).fetchOne();
        if (record == null) {
            throw new ObjectNotFoundException("Refund with external id " + externalId + " not found");
        }
        return toRefund(record);
    }

    // Nothing matched: either the refund is gone, or someone changed it after it was loaded.
    private ApplicationException notUpdated(long refundId, long version) {
        if (!dsl.fetchExists(REFUNDS, ID.eq(refundId))) {
            return new ObjectNotFoundException("Refund with id " + refundId + " not found");
        }
        return new ConcurrentUpdateException(
                "Refund " + refundId + " was changed concurrently since version " + version);
    }

    private Refund toRefund(Record record) {
        var entity = new RefundEntity(
                record.get(ID), record.get(PAYMENT_ID), record.get(IDEMPOTENCY_KEY), record.get(EXTERNAL_ID),
                record.get(AMOUNT), record.get(CURRENCY), record.get(STATUS), record.get(VERSION),
                toInstant(record.get(CREATED_AT)), toInstant(record.get(UPDATED_AT))
        );
        return RefundEntity.toRefund(entity);
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant toInstant(OffsetDateTime offsetDateTime) {
        return offsetDateTime == null ? null : offsetDateTime.toInstant();
    }
}
