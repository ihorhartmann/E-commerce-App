package com.hartmann.interfaceadapter.out.outbox

import com.hartmann.interfaceadapter.out.outbox.row.OutboxMessageRow
import com.hartmann.interfaceadapter.out.outbox.row.toOutboxMessage
import com.hartmann.usecase.port.out.outbox.OutboxMessageRepository
import com.hartmann.usecase.port.out.outbox.dto.OutboxMessage
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Repository
class PostgresOutboxMessageRepository(
    private val jpaRepository: OutboxMessageJpaRepository,
    transactionManager: PlatformTransactionManager,
) : OutboxMessageRepository {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    override fun insert(message: OutboxMessage) {
        jpaRepository.save(
            OutboxMessageRow(
                correlationId = message.correlationId,
                messageType = message.messageType,
                topic = message.topic,
                key = message.key,
                payload = message.payload,
                traceparent = message.traceparent,
            )
        )
    }

    override fun fetchUnsent(limit: Int): List<OutboxMessage> =
        jpaRepository.findUnsent(limit).map { toOutboxMessage(it) }

    override fun markSentBatch(messageIds: List<UUID>, sentAt: Instant) {
        if (messageIds.isEmpty()) {
            return
        }

        transactionTemplate.execute { jpaRepository.markSentBatch(messageIds, sentAt) }
    }
}
