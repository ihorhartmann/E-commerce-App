package com.hartmann.adapter.out.repository;

import com.hartmann.adapter.out.repository.entity.PaymentEntity;
import com.hartmann.adapter.out.repository.exception.DatabaseFailureException;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.application.port.out.repository.exception.ConcurrentUpdateException;
import com.hartmann.application.port.out.repository.exception.PaymentNotFoundException;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import static org.jooq.impl.DSL.*;
import static org.jooq.impl.SQLDataType.TIMESTAMPWITHTIMEZONE;

/**
 * Every method reports a database error as DatabaseFailureException, so it carries a code of its own rather than
 * reaching the error handlers as an anonymous jOOQ exception. A failure at commit time, outside these methods,
 * still does.
 */
@Repository
public class PostgresPaymentRepository implements PaymentRepository {
    private static final Table<Record> PAYMENTS = table(name("payment", "payments"));

    private static final Field<Long> ID = field(name("payments", "id"), Long.class);
    private static final Field<Long> ORDER_ID = field(name("payments", "order_id"), Long.class);
    private static final Field<String> EXTERNAL_ID = field(name("payments", "external_id"), String.class);
    private static final Field<BigDecimal> AMOUNT = field(name("payments", "amount"), BigDecimal.class);
    private static final Field<String> CURRENCY = field(name("payments", "currency"), String.class);
    private static final Field<String> STATUS = field(name("payments", "status"), String.class);
    private static final Field<BigDecimal> REFUNDED_AMOUNT = field(name("payments", "refunded_amount"), BigDecimal.class);
    private static final Field<Long> VERSION = field(name("payments", "version"), Long.class);
    private static final Field<OffsetDateTime> CREATED_AT = field(name("payments", "created_at"), TIMESTAMPWITHTIMEZONE);
    private static final Field<OffsetDateTime> UPDATED_AT = field(name("payments", "updated_at"), TIMESTAMPWITHTIMEZONE);
    private static final Field<OffsetDateTime> EXPIRES_AT = field(name("payments", "expires_at"), TIMESTAMPWITHTIMEZONE);

    private static final List<Field<?>> PAYMENT_FIELDS = List.of(
            ID, ORDER_ID, EXTERNAL_ID, AMOUNT, CURRENCY, STATUS, REFUNDED_AMOUNT, VERSION, CREATED_AT, UPDATED_AT, EXPIRES_AT);

    private static final List<String> NON_TERMINAL_STATUSES =
            PaymentStatus.NON_TERMINAL_STATUSES.stream().map(PaymentStatus::name).toList();

    private final DSLContext dsl;

    public PostgresPaymentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Payment create(Payment payment) {
        var entity = PaymentEntity.from(payment);
        var record = translatingFailures(() -> dsl.insertInto(PAYMENTS)
                .set(ORDER_ID, entity.orderId())
                .set(EXTERNAL_ID, entity.externalId())
                .set(AMOUNT, entity.amount())
                .set(CURRENCY, entity.currency())
                .set(STATUS, entity.status())
                .set(REFUNDED_AMOUNT, entity.refundedAmount())
                .set(VERSION, entity.version())
                .set(CREATED_AT, toOffsetDateTime(entity.createdAt()))
                .set(UPDATED_AT, toOffsetDateTime(entity.updatedAt()))
                .set(EXPIRES_AT, toOffsetDateTime(entity.expiresAt()))
                .returning(PAYMENT_FIELDS)
                .fetchOne());
        //noinspection DataFlowIssue
        return toPayment(record);
    }

    @Override
    public Payment getById(long paymentId) {
        var record = translatingFailures(() -> dsl.select(PAYMENT_FIELDS).from(PAYMENTS).where(ID.eq(paymentId)).fetchOne());
        if (record == null) {
            throw PaymentNotFoundException.withId(paymentId);
        }
        return toPayment(record);
    }

    @Override
    public Payment getByOrderId(long orderId) {
        var record = translatingFailures(() -> dsl.select(PAYMENT_FIELDS).from(PAYMENTS).where(ORDER_ID.eq(orderId)).fetchOne());
        if (record == null) {
            throw PaymentNotFoundException.withOrderId(orderId);
        }
        return toPayment(record);
    }

    @Override
    public Payment markPending(long paymentId, long version, String externalId) {
        return translatingFailures(() -> dsl.update(PAYMENTS)
                .set(EXTERNAL_ID, externalId)
                .set(STATUS, PaymentStatus.PENDING.name())
                .set(VERSION, VERSION.plus(1))
                .set(UPDATED_AT, toOffsetDateTime(Instant.now()))
                .where(ID.eq(paymentId))
                .and(VERSION.eq(version))
                .returning(PAYMENT_FIELDS)
                .fetchOptional())
                .map(this::toPayment)
                .orElseThrow(() -> notUpdated(paymentId, version));
    }

    @Override
    public Payment updateStatus(long paymentId, long version, PaymentStatus status) {
        return translatingFailures(() -> dsl.update(PAYMENTS)
                .set(STATUS, status.name())
                .set(VERSION, VERSION.plus(1))
                .set(UPDATED_AT, toOffsetDateTime(Instant.now()))
                .where(ID.eq(paymentId))
                .and(VERSION.eq(version))
                .returning(PAYMENT_FIELDS)
                .fetchOptional())
                .map(this::toPayment)
                .orElseThrow(() -> notUpdated(paymentId, version));
    }

    @Override
    public Payment getByExternalId(String externalId) {
        var record = translatingFailures(() -> dsl.select(PAYMENT_FIELDS).from(PAYMENTS).where(EXTERNAL_ID.eq(externalId)).fetchOne());
        if (record == null) {
            throw PaymentNotFoundException.withExternalId(externalId);
        }
        return toPayment(record);
    }

    @Override
    public Payment updateRefundedAmount(Payment payment) {
        return translatingFailures(() -> dsl.update(PAYMENTS)
                .set(REFUNDED_AMOUNT, payment.refundedAmount().amount())
                .set(VERSION, VERSION.plus(1))
                .set(UPDATED_AT, toOffsetDateTime(payment.updatedAt()))
                .where(ID.eq(payment.id()))
                .and(VERSION.eq(payment.version()))
                .returning(PAYMENT_FIELDS)
                .fetchOptional())
                .map(this::toPayment)
                .orElseThrow(() -> notUpdated(payment.id(), payment.version()));
    }

    // Nothing matched: either the payment is gone (getById throws), or someone changed it after it was loaded.
    private ConcurrentUpdateException notUpdated(long paymentId, long version) {
        getById(paymentId);
        return ConcurrentUpdateException.ofPayment(paymentId, version);
    }

    @Override
    public List<Payment> expireOverdue() {
        var now = toOffsetDateTime(Instant.now());
        return translatingFailures(() -> dsl.update(PAYMENTS)
                .set(STATUS, PaymentStatus.EXPIRED.name())
                .set(VERSION, VERSION.plus(1))
                .set(UPDATED_AT, now)
                .where(STATUS.in(NON_TERMINAL_STATUSES))
                .and(EXPIRES_AT.lt(now))
                .returning(PAYMENT_FIELDS)
                .fetch())
                .map(this::toPayment);
    }

    private Payment toPayment(Record record) {
        var entity = new PaymentEntity(
                record.get(ID), record.get(ORDER_ID), record.get(EXTERNAL_ID), record.get(AMOUNT),
                record.get(CURRENCY), record.get(STATUS), record.get(REFUNDED_AMOUNT), record.get(VERSION),
                toInstant(record.get(CREATED_AT)), toInstant(record.get(UPDATED_AT)), toInstant(record.get(EXPIRES_AT))
        );
        return PaymentEntity.toPayment(entity);
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant toInstant(OffsetDateTime offsetDateTime) {
        return offsetDateTime == null ? null : offsetDateTime.toInstant();
    }

    // Spring Boot's jOOQ integration translates an SQLException into Spring's DataAccessException; what jOOQ
    // itself rejects stays its own DataAccessException.
    private static <T> T translatingFailures(Supplier<T> block) {
        try {
            return block.get();
        } catch (DataAccessException | org.jooq.exception.DataAccessException e) {
            throw new DatabaseFailureException(e);
        }
    }
}
