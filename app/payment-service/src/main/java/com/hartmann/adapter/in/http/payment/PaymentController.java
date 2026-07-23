package com.hartmann.adapter.in.http.payment;

import com.hartmann.adapter.in.http.payment.dto.PaymentResponse;
import com.hartmann.adapter.in.http.payment.dto.SubmitPaymentRequest;
import com.hartmann.adapter.in.http.payment.dto.SubmitPaymentResponse;
import com.hartmann.adapter.in.http.payment.mapper.PaymentMapper;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentService paymentService;
    private final PaymentMapper paymentMapper;

    public PaymentController(PaymentService paymentService, PaymentMapper paymentMapper) {
        this.paymentService = paymentService;
        this.paymentMapper = paymentMapper;
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<PaymentResponse> getByOrderId(@PathVariable("orderId") long orderId) {
        return ResponseEntity.ok(
                paymentMapper.toPaymentResponse(paymentService.getByOrderId(orderId))
        );
    }

    @PostMapping
    public ResponseEntity<SubmitPaymentResponse> submitPayment(@RequestBody SubmitPaymentRequest request) {
        var amount = new Money(request.amount(), Currency.fromCode(request.currency()));
        var result = paymentService.submitPayment(request.orderId(), amount);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(paymentMapper.toSubmitPaymentResponse(result));
    }
}
