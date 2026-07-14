package com.hartmann.application.service;

import com.hartmann.application.port.in.RefundService;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.application.port.out.repository.RefundRepository;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import com.hartmann.domain.value.Money;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RefundServiceImpl implements RefundService {
    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;

    public RefundServiceImpl(
            RefundRepository refundRepository,
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway
    ) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
    }

    @Override
    public List<Refund> getByOrderId(long orderId) {
        var payment = paymentRepository.getByOrderId(orderId);
        return refundRepository.findByPaymentId(payment.id());
    }

    @Override
    public Refund submitRefund(long paymentId, Money amount) {
        var payment = getPaymentWithRefunds(paymentId);
        var draftRefund = payment.createRefund(amount);

        var result = paymentGateway.submitRefund(payment.externalId(), amount);

        var refund = new Refund(
                draftRefund.id(), draftRefund.paymentId(), result.externalId(), draftRefund.amount(), result.status(),
                draftRefund.createdAt(), Instant.now()
        );
        return refundRepository.create(refund);
    }

    @Override
    public Refund updateStatusByExternalId(String externalId, RefundStatus status) {
        var refund = refundRepository.getByExternalId(externalId);
        return refundRepository.updateStatus(refund.id(), status);
    }

    //TODO replace it with join in real postgresql repo
    private Payment getPaymentWithRefunds(long paymentId) {
        var payment = paymentRepository.getById(paymentId);
        var refunds = refundRepository.findByPaymentId(paymentId);
        return new Payment(
                payment.id(),
                payment.orderId(),
                payment.externalId(), payment.amount(),
                payment.status(),
                refunds,
                payment.createdAt(),
                payment.updatedAt()
        );
    }
}
