package com.hartmann.interfaceadapter.`in`.scheduled

import com.hartmann.usecase.port.out.idempotency.ProcessedKafkaMessageStore
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

private val RETENTION: Duration = Duration.ofDays(30)
private const val SWEEP_BATCH_SIZE = 1000

@Component
class ProcessedKafkaMessageSweep(
    private val store: ProcessedKafkaMessageStore,
) {
    @Scheduled(fixedDelay = 300_000)
    fun sweep() {
        val cutoff = Instant.now().minus(RETENTION)
        while (store.deleteExpired(cutoff, SWEEP_BATCH_SIZE) > 0) {
            // keep deleting in batches
        }
    }
}
