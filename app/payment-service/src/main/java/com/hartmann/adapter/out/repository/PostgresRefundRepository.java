package com.hartmann.adapter.out.repository;

import com.hartmann.adapter.out.repository.entity.RefundEntity;
import com.hartmann.application.port.out.repository.RefundRepository;
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

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.SQLDataType.TIMESTAMPWITHTIMEZONE;

@Repository
public class PostgresRefundRepository implements RefundRepository {
    private static final Table<Record> REFUNDS = table(name("payment", "refunds"));

    private static final Field<Long> ID = field(name("refunds", "id"), Long.class);
    private static final Field<Long> PAYMENT_ID = field(name("refunds", "payment_id"), Long.class);
    private static final Field<String> EXTERNAL_ID = field(name("refunds", "external_id"), String.class);
    private static final Field<BigDecimal> AMOUNT = field(name("refunds", "amount"), BigDecimal.class);
    private static final Field<String> CURRENCY = field(name("refunds", "currency"), String.class);
    private static final Field<String> STATUS = field(name("refunds", "status"), String.class);
    private static final Field<OffsetDateTime> CREATED_AT = field(name("refunds", "created_at"), TIMESTAMPWITHTIMEZONE);
    private static final Field<OffsetDateTime> UPDATED_AT = field(name("refunds", "updated_at"), TIMESTAMPWITHTIMEZONE);

    private final DSLContext dsl;

    public PostgresRefundRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Refund create(Refund refund) {
        var entity = RefundEntity.from(refund);
        var id = dsl.insertInto(REFUNDS)
                .set(PAYMENT_ID, entity.paymentId())
                .set(EXTERNAL_ID, entity.externalId())
                .set(AMOUNT, entity.amount())
                .set(CURRENCY, entity.currency())
                .set(STATUS, entity.status())
                .set(CREATED_AT, toOffsetDateTime(entity.createdAt()))
                .set(UPDATED_AT, toOffsetDateTime(entity.updatedAt()))
                .returning(ID)
                .fetchOne(ID);
        return getById(id);
    }

    @Override
    public Refund markPending(long refundId, String externalId, RefundStatus status) {
        var updated = dsl.update(REFUNDS)
                .set(EXTERNAL_ID, externalId)
                .set(STATUS, status.name())
                .set(UPDATED_AT, toOffsetDateTime(Instant.now()))
                .where(ID.eq(refundId))
                .execute();
        if (updated == 0) {
            throw new ObjectNotFoundException("Refund with id " + refundId + " not found");
        }
        return getById(refundId);
    }

    @Override
    public List<Refund> findByPaymentId(long paymentId) {
        return dsl.select(ID, PAYMENT_ID, EXTERNAL_ID, AMOUNT, CURRENCY, STATUS, CREATED_AT, UPDATED_AT)
                .from(REFUNDS).where(PAYMENT_ID.eq(paymentId)).fetch(this::toRefund);
    }

    @Override
    public Refund updateStatus(long refundId, RefundStatus status) {
        var updated = dsl.update(REFUNDS)
                .set(STATUS, status.name())
                .set(UPDATED_AT, toOffsetDateTime(Instant.now()))
                .where(ID.eq(refundId))
                .execute();
        if (updated == 0) {
            throw new ObjectNotFoundException("Refund with id " + refundId + " not found");
        }
        return getById(refundId);
    }

    @Override
    public Refund getByExternalId(String externalId) {
        var record = dsl.select(ID, PAYMENT_ID, EXTERNAL_ID, AMOUNT, CURRENCY, STATUS, CREATED_AT, UPDATED_AT)
                .from(REFUNDS).where(EXTERNAL_ID.eq(externalId)).fetchOne();
        if (record == null) {
            throw new ObjectNotFoundException("Refund with external id " + externalId + " not found");
        }
        return toRefund(record);
    }

    private Refund getById(long id) {
        var record = dsl.select(ID, PAYMENT_ID, EXTERNAL_ID, AMOUNT, CURRENCY, STATUS, CREATED_AT, UPDATED_AT)
                .from(REFUNDS).where(ID.eq(id)).fetchOne();
        if (record == null) {
            throw new ObjectNotFoundException("Refund with id " + id + " not found");
        }
        return toRefund(record);
    }

    private Refund toRefund(Record record) {
        var entity = new RefundEntity(
                record.get(ID), record.get(PAYMENT_ID), record.get(EXTERNAL_ID), record.get(AMOUNT),
                record.get(CURRENCY), record.get(STATUS), toInstant(record.get(CREATED_AT)), toInstant(record.get(UPDATED_AT))
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
