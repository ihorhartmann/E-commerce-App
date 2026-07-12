package com.hartmann.adapter.in.http.payment;

import com.hartmann.application.port.in.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
                paymentMapper.fromDomainModel(paymentService.getByOrderId(orderId))
        );
    }
}
