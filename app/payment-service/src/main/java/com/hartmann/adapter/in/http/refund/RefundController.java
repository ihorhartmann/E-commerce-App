package com.hartmann.adapter.in.http.refund;

import com.hartmann.application.port.in.RefundService;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
                .map(refundMapper::fromDomainModel)
                .toList();
    }
}
