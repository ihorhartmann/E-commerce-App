package com.hartmann.adapter.in.http.refund;

import com.hartmann.adapter.in.http.refund.dto.SubmitRefundRequest;
import com.hartmann.adapter.in.http.refund.dto.RefundResponse;
import com.hartmann.application.port.in.RefundService;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/refunds")
public class RefundController {
    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    @GetMapping("/{orderId}")
    public List<RefundResponse> getByOrderId(@PathVariable("orderId") long orderId) {
        return refundService.getByOrderId(orderId)
                .stream()
                .map(RefundResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<RefundResponse> submitRefund(@RequestBody SubmitRefundRequest request) {
        var refund = refundService.submitRefund(null, request.paymentId(), SubmitRefundRequest.toMoney(request));

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(RefundResponse.from(refund));
    }
}
