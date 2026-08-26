package com.hartmann.adapter.out.repository;

import com.hartmann.adapter.out.repository.entity.PaymentEntity;
import com.hartmann.adapter.out.repository.entity.RefundEntity;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.application.port.out.repository.exception.ObjectNotFoundException;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.model.Refund;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;

import static org.jooq.impl.DSL.*;
import static org.jooq.impl.SQLDataType.TIMESTAMPWITHTIMEZONE;

@Repository
public class PostgresPaymentRepository implements PaymentRepository {
    private static final Table<Record> PAYMENTS = table(name("payment", "payments"));
    private static final Table<Record> REFUNDS = table(name("payment", "refunds"));

    private static final Field<Long> ID = field(name("payments", "id"), Long.class);
    private static final Field<Long> ORDER_ID = field(name("payments", "order_id"), Long.class);
    private static final Field<String> EXTERNAL_ID = field(name("payments", "external_id"), String.class);
    private static final Field<BigDecimal> AMOUNT = field(name("payments", "amount"), BigDecimal.class);
    private static final Field<String> CURRENCY = field(name("payments", "currency"), String.class);
    private static final Field<String> STATUS = field(name("payments", "status"), String.class);
    private static final Field<OffsetDateTime> CREATED_AT = field(name("payments", "created_at"), TIMESTAMPWITHTIMEZONE);
    private static final Field<OffsetDateTime> UPDATED_AT = field(name("payments", "updated_at"), TIMESTAMPWITHTIMEZONE);

    private static final Field<Long> REFUND_ID = field(name("refunds", "id"), Long.class);
    private static final Field<Long> REFUND_PAYMENT_ID = field(name("refunds", "payment_id"), Long.class);
    private static final Field<String> REFUND_EXTERNAL_ID = field(name("refunds", "external_id"), String.class);
    private static final Field<BigDecimal> REFUND_AMOUNT = field(name("refunds", "amount"), BigDecimal.class);
    private static final Field<String> REFUND_CURRENCY = field(name("refunds", "currency"), String.class);
    private static final Field<String> REFUND_STATUS = field(name("refunds", "status"), String.class);
    private static final Field<OffsetDateTime> REFUND_CREATED_AT = field(name("refunds", "created_at"), TIMESTAMPWITHTIMEZONE);
    private static final Field<OffsetDateTime> REFUND_UPDATED_AT = field(name("refunds", "updated_at"), TIMESTAMPWITHTIMEZONE);

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
                .set(CREATED_AT, toOffsetDateTime(entity.createdAt()))
                .set(UPDATED_AT, toOffsetDateTime(entity.updatedAt()))
                .returning(ID)
                .fetchOne(ID);
        return getById(id);
    }

    @Override
    public Payment getById(long paymentId) {
        var record = dsl.select(ID, ORDER_ID, EXTERNAL_ID, AMOUNT, CURRENCY, STATUS, CREATED_AT, UPDATED_AT)
                .from(PAYMENTS).where(ID.eq(paymentId)).fetchOne();
        if (record == null) {
            throw new ObjectNotFoundException("Payment with id " + paymentId + " not found");
        }
        return toPayment(record);
    }

    @Override
    public Payment getByOrderId(long orderId) {
        var record = dsl.select(ID, ORDER_ID, EXTERNAL_ID, AMOUNT, CURRENCY, STATUS, CREATED_AT, UPDATED_AT)
                .from(PAYMENTS).where(ORDER_ID.eq(orderId)).fetchOne();
        if (record == null) {
            throw new ObjectNotFoundException("Payment for order " + orderId + " not found");
        }
        return toPayment(record);
    }

    @Override
    public Payment updateStatus(long paymentId, PaymentStatus status) {
        var updated = dsl.update(PAYMENTS)
                .set(STATUS, status.name())
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
        var record = dsl.select(ID, ORDER_ID, EXTERNAL_ID, AMOUNT, CURRENCY, STATUS, CREATED_AT, UPDATED_AT)
                .from(PAYMENTS).where(EXTERNAL_ID.eq(externalId)).fetchOne();
        if (record == null) {
            throw new ObjectNotFoundException("Payment with external id " + externalId + " not found");
        }
        return toPayment(record);
    }

    @Override
    public Payment getByIdWithRefunds(long paymentId) {
        var rows = dsl.select(ID, ORDER_ID, EXTERNAL_ID, AMOUNT, CURRENCY, STATUS, CREATED_AT, UPDATED_AT,
                        REFUND_ID, REFUND_PAYMENT_ID, REFUND_EXTERNAL_ID, REFUND_AMOUNT, REFUND_CURRENCY,
                        REFUND_STATUS, REFUND_CREATED_AT, REFUND_UPDATED_AT)
                .from(PAYMENTS)
                .leftJoin(REFUNDS).on(REFUND_PAYMENT_ID.eq(ID))
                .where(ID.eq(paymentId))
                .fetch();

        if (rows.isEmpty()) {
            throw new ObjectNotFoundException("Payment with id " + paymentId + " not found");
        }

        var refunds = new ArrayList<Refund>();
        for (var row : rows) {
            var refundId = row.get(REFUND_ID);
            if (refundId == null) {
                continue;
            }

            refunds.add(RefundEntity.toRefund(new RefundEntity(
                    refundId,
                    row.get(REFUND_PAYMENT_ID),
                    row.get(REFUND_EXTERNAL_ID),
                    row.get(REFUND_AMOUNT),
                    row.get(REFUND_CURRENCY),
                    row.get(REFUND_STATUS),
                    toInstant(row.get(REFUND_CREATED_AT)),
                    toInstant(row.get(REFUND_UPDATED_AT))
            )));
        }

        var first = rows.get(0);
        var paymentEntity = new PaymentEntity(
                first.get(ID), first.get(ORDER_ID), first.get(EXTERNAL_ID), first.get(AMOUNT),
                first.get(CURRENCY), first.get(STATUS), toInstant(first.get(CREATED_AT)), toInstant(first.get(UPDATED_AT))
        );
        return PaymentEntity.toPayment(paymentEntity, refunds);
    }

    private Payment toPayment(Record record) {
        var entity = new PaymentEntity(
                record.get(ID), record.get(ORDER_ID), record.get(EXTERNAL_ID), record.get(AMOUNT),
                record.get(CURRENCY), record.get(STATUS), toInstant(record.get(CREATED_AT)), toInstant(record.get(UPDATED_AT))
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
