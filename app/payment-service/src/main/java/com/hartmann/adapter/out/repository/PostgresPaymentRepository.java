package com.hartmann.adapter.out.repository;

import com.hartmann.adapter.out.repository.entity.PaymentEntity;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.application.port.out.repository.exception.ConcurrentUpdateException;
import com.hartmann.application.port.out.repository.exception.ObjectNotFoundException;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
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

import static org.jooq.impl.DSL.*;
import static org.jooq.impl.SQLDataType.TIMESTAMPWITHTIMEZONE;

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
        var id = dsl.insertInto(PAYMENTS)
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
                .returning(ID)
                .fetchOne(ID);
        //noinspection DataFlowIssue
        return getById(id);
    }

    @Override
    public Payment getById(long paymentId) {
        var record = dsl.select(PAYMENT_FIELDS).from(PAYMENTS).where(ID.eq(paymentId)).fetchOne();
        if (record == null) {
            throw new ObjectNotFoundException("Payment with id " + paymentId + " not found");
        }
        return toPayment(record);
    }

    @Override
    public Payment getByOrderId(long orderId) {
        var record = dsl.select(PAYMENT_FIELDS).from(PAYMENTS).where(ORDER_ID.eq(orderId)).fetchOne();
        if (record == null) {
            throw new ObjectNotFoundException("Payment for order " + orderId + " not found");
        }
        return toPayment(record);
    }

    @Override
    public Payment markPending(long paymentId, String externalId) {
        var updated = dsl.update(PAYMENTS)
                .set(EXTERNAL_ID, externalId)
                .set(STATUS, PaymentStatus.PENDING.name())
                .set(VERSION, VERSION.plus(1))
                .set(UPDATED_AT, toOffsetDateTime(Instant.now()))
                .where(ID.eq(paymentId))
                .execute();
        if (updated == 0) {
            throw new ObjectNotFoundException("Payment with id " + paymentId + " not found");
        }
        return getById(paymentId);
    }

    @Override
    public Payment updateStatus(long paymentId, PaymentStatus status) {
        var updated = dsl.update(PAYMENTS)
                .set(STATUS, status.name())
                .set(VERSION, VERSION.plus(1))
                .set(UPDATED_AT, toOffsetDateTime(Instant.now()))
                .where(ID.eq(paymentId))
                .execute();
        if (updated == 0) {
            throw new ObjectNotFoundException("Payment with id " + paymentId + " not found");
        }
        return getById(paymentId);
    }

    @Override
    public Payment getByExternalId(String externalId) {
        var record = dsl.select(PAYMENT_FIELDS).from(PAYMENTS).where(EXTERNAL_ID.eq(externalId)).fetchOne();
        if (record == null) {
            throw new ObjectNotFoundException("Payment with external id " + externalId + " not found");
        }
        return toPayment(record);
    }

    @Override
    public Payment updateRefundedAmount(Payment payment) {
        var record = dsl.update(PAYMENTS)
                .set(REFUNDED_AMOUNT, payment.refundedAmount().amount())
                .set(VERSION, VERSION.plus(1))
                .set(UPDATED_AT, toOffsetDateTime(payment.updatedAt()))
                .where(ID.eq(payment.id()))
                .and(VERSION.eq(payment.version()))
                .returning(PAYMENT_FIELDS)
                .fetchOne();
        if (record != null) {
            return toPayment(record);
        }

        // Nothing matched: either the payment is gone, or someone changed it after it was loaded.
        getById(payment.id());
        throw new ConcurrentUpdateException(
                "Payment " + payment.id() + " was changed concurrently since version " + payment.version());
    }

    @Override
    public List<Payment> expireOverdue() {
        var now = toOffsetDateTime(Instant.now());
        return dsl.update(PAYMENTS)
                .set(STATUS, PaymentStatus.EXPIRED.name())
                .set(VERSION, VERSION.plus(1))
                .set(UPDATED_AT, now)
                .where(STATUS.in(NON_TERMINAL_STATUSES))
                .and(EXPIRES_AT.lt(now))
                .returning(PAYMENT_FIELDS)
                .fetch(this::toPayment);
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
}
