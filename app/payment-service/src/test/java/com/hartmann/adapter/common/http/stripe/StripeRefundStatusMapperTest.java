package com.hartmann.adapter.common.http.stripe;

import com.hartmann.domain.model.RefundStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class StripeRefundStatusMapperTest {

    @Test
    void fromStripeStatus_mapsSucceeded() {
        assertThat(StripeRefundStatusMapper.fromStripeStatus("succeeded")).isEqualTo(RefundStatus.SUCCEEDED);
    }

    @Test
    void fromStripeStatus_mapsPending() {
        assertThat(StripeRefundStatusMapper.fromStripeStatus("pending")).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void fromStripeStatus_mapsRequiresActionToPending() {
        assertThat(StripeRefundStatusMapper.fromStripeStatus("requires_action")).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void fromStripeStatus_mapsFailedAndCanceledToFailed() {
        assertThat(StripeRefundStatusMapper.fromStripeStatus("failed")).isEqualTo(RefundStatus.FAILED);
        assertThat(StripeRefundStatusMapper.fromStripeStatus("canceled")).isEqualTo(RefundStatus.FAILED);
    }

    @Test
    void fromStripeStatus_throwsOnAnUnknownStatus() {
        assertThatThrownBy(() -> StripeRefundStatusMapper.fromStripeStatus("unknown_status"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown_status");
    }
}
