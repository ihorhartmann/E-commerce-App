package com.hartmann.adapter.common.http.stripe;

import com.hartmann.adapter.common.http.stripe.exception.UnknownStripeRefundStatusException;
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
        var stripeStatus = "unknown_status";

        assertThatThrownBy(() -> StripeRefundStatusMapper.fromStripeStatus(stripeStatus))
                .isInstanceOfSatisfying(UnknownStripeRefundStatusException.class,
                        exception -> assertThat(exception.attributes()).containsEntry("stripeStatus", stripeStatus));
    }

    @Test
    void fromStripeStatus_throwsOnAMissingStatus() {
        assertThatThrownBy(() -> StripeRefundStatusMapper.fromStripeStatus(null))
                .isInstanceOf(UnknownStripeRefundStatusException.class);
    }
}
