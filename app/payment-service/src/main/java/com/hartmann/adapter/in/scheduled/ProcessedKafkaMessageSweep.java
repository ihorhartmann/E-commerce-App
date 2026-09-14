package com.hartmann.adapter.in.scheduled;

import com.hartmann.application.port.out.idempotency.ProcessedKafkaMessageStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class ProcessedKafkaMessageSweep {
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final int SWEEP_BATCH_SIZE = 1000;

    private final ProcessedKafkaMessageStore store;

    public ProcessedKafkaMessageSweep(ProcessedKafkaMessageStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelay = 300_000)
    public void sweep() {
        var cutoff = Instant.now().minus(RETENTION);
        int deleted;
        do {
            deleted = store.deleteExpired(cutoff, SWEEP_BATCH_SIZE);
        } while (deleted > 0);
    }
}
