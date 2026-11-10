package com.hartmann.adapter.in.http.payment;

import com.hartmann.adapter.common.tracing.CorrelationConst;
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
import org.springframework.web.bind.annotation.RequestHeader;
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

    /**
     * Cancels the order's payment for a customer who cancels the order before paying it; order-service learns the
     * outcome from Kafka (PaymentCancelled), this answer only tells the customer. 409 if it's already paid.
     */
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<PaymentResponse> cancelPayment(
            @RequestHeader(CorrelationConst.HTTP_HEADER) String correlationId,
            @PathVariable("orderId") long orderId
    ) {
        return ResponseEntity.ok(PaymentResponse.from(paymentService.cancelPayment(correlationId, orderId)));
    }

    @PostMapping
    public ResponseEntity<SubmitPaymentResponse> submitPayment(@RequestBody SubmitPaymentRequest request) {
        var result = paymentService.submitPayment(request.orderId(), SubmitPaymentRequest.toMoney(request));

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(SubmitPaymentResponse.from(result));
    }
}
