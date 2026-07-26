package com.hartmann.adapter.in.http.payment;

import com.hartmann.adapter.in.http.payment.dto.PaymentResponse;
import com.hartmann.adapter.in.http.payment.dto.SubmitPaymentRequest;
import com.hartmann.adapter.in.http.payment.dto.SubmitPaymentResponse;
import com.hartmann.application.port.in.PaymentService;
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

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<PaymentResponse> getByOrderId(@PathVariable("orderId") long orderId) {
        return ResponseEntity.ok(
                PaymentResponse.from(paymentService.getByOrderId(orderId))
        );
    }

    @PostMapping
    public ResponseEntity<SubmitPaymentResponse> submitPayment(@RequestBody SubmitPaymentRequest request) {
        var result = paymentService.submitPayment(request.orderId(), SubmitPaymentRequest.toMoney(request));

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(SubmitPaymentResponse.from(result));
    }
}
