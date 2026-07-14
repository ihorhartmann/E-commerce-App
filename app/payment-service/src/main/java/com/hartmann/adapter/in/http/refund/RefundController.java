package com.hartmann.adapter.in.http.refund;

import com.hartmann.adapter.in.http.refund.dto.SubmitRefundRequest;
import com.hartmann.adapter.in.http.refund.dto.RefundResponse;
import com.hartmann.adapter.in.http.refund.mapper.RefundMapper;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;

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
    private final RefundMapper refundMapper;

    public RefundController(RefundService refundService, RefundMapper refundMapper) {
        this.refundService = refundService;
        this.refundMapper = refundMapper;
    }

    @GetMapping("/{orderId}")
    public List<RefundResponse> getByOrderId(@PathVariable("orderId") long orderId) {
        return refundService.getByOrderId(orderId)
                .stream()
                .map(refundMapper::fromDomain)
                .toList();
    }

    @PostMapping
    public ResponseEntity<RefundResponse> submitRefund(@RequestBody SubmitRefundRequest request) {
        var amount = new Money(request.amount(), Currency.fromCode(request.currency()));
        var refund = refundService.submitRefund(request.paymentId(), amount);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(refundMapper.fromDomain(refund));
    }
}
