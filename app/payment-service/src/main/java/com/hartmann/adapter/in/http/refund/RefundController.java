package com.hartmann.adapter.in.http.refund;

import com.hartmann.application.port.in.RefundUseCase;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/refunds")
public class RefundController {
    private final RefundUseCase refundUseCase;

    public RefundController(RefundUseCase refundUseCase) {
        this.refundUseCase = refundUseCase;
    }

    @GetMapping("/{orderId}")
    public List<RefundResponse> getByOrderId(@PathVariable("orderId") long orderId) {
        return refundUseCase.getByOrderId(orderId)
                .stream()
                .map(RefundResponse::fromDomainModel)
                .toList();
    }
}
