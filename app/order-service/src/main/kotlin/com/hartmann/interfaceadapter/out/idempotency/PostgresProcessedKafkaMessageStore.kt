package com.hartmann.interfaceadapter.out.idempotency

import com.hartmann.usecase.port.out.idempotency.ProcessedKafkaMessageStore
import com.hartmann.usecase.port.out.idempotency.dto.KafkaMessageMetadata
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * markProcessed relies on the ambient transaction the caller's own @Transactional method already
 * opened - it never starts its own, unlike deleteExpired, which has no ambient business
 * transaction to join and therefore needs its own via TransactionTemplate (a @Modifying @Query
 * repository method has no transaction of its own unless one is already open).
 */
@Repository
class PostgresProcessedKafkaMessageStore(
    private val jpaRepository: ProcessedKafkaMessageJpaRepository,
    transactionManager: PlatformTransactionManager,
) : ProcessedKafkaMessageStore {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    override fun markProcessed(message: KafkaMessageMetadata): Boolean {
        val inserted = jpaRepository.insertIfAbsent(message.messageId, message.correlationId, message.messageType, Instant.now())
        return inserted == 0
    }

    override fun deleteExpired(cutoff: Instant, limit: Int): Int =
        transactionTemplate.execute { jpaRepository.deleteBatchOlderThan(cutoff, limit) }
}
