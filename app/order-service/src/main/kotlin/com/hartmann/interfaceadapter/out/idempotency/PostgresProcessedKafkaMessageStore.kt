package com.hartmann.interfaceadapter.out.idempotency

import com.hartmann.usecase.port.out.idempotency.ProcessedKafkaMessageStore
import com.hartmann.usecase.port.out.idempotency.dto.KafkaMessageMetadata
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class PostgresProcessedKafkaMessageStore(
    private val jpaRepository: ProcessedKafkaMessageJpaRepository,
) : ProcessedKafkaMessageStore {
    override fun markProcessed(message: KafkaMessageMetadata): Boolean {
        val inserted = jpaRepository.insertIfAbsent(message.messageId, message.correlationId, message.messageType, Instant.now())
        return inserted == 0
    }

    override fun deleteExpired(cutoff: Instant, limit: Int): Int =
        jpaRepository.deleteBatchOlderThan(cutoff, limit)
}
