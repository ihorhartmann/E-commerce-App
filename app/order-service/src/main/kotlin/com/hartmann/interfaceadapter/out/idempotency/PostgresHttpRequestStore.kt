package com.hartmann.interfaceadapter.out.idempotency

import com.hartmann.usecase.port.out.idempotency.HttpRequestStore
import com.hartmann.usecase.port.out.idempotency.dto.HttpRequestRecord
import com.hartmann.interfaceadapter.out.idempotency.row.toRecord
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

private const val STATUS_IN_PROGRESS = "IN_PROGRESS"
private const val STATUS_COMPLETED = "COMPLETED"

@Repository
class PostgresHttpRequestStore(
    private val jpaRepository: HttpRequestJpaRepository,
    transactionManager: PlatformTransactionManager,
) : HttpRequestStore {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    override fun insert(idempotencyKey: String, requestHash: String): Boolean {
        val now = Instant.now()
        val inserted = transactionTemplate.execute {
            jpaRepository.insertIfAbsent(idempotencyKey, requestHash, STATUS_IN_PROGRESS, now, now)
        }
        return inserted == 1
    }

    override fun find(idempotencyKey: String): HttpRequestRecord? =
        jpaRepository.findById(idempotencyKey).orElse(null)?.toRecord()

    override fun reclaim(idempotencyKey: String, ttl: Duration): Boolean {
        val cutoff = Instant.now().minus(ttl)
        val reclaimed = transactionTemplate.execute {
            jpaRepository.reclaim(idempotencyKey, Instant.now(), cutoff)
        }
        return reclaimed == 1
    }

    override fun complete(idempotencyKey: String, httpCode: Int, response: String) {
        transactionTemplate.execute {
            val existing = jpaRepository.findById(idempotencyKey).orElseThrow()
            existing.status = STATUS_COMPLETED
            existing.httpCode = httpCode
            // NULL, not "": the column is jsonb, and an empty string isn't valid JSON
            existing.response = response.ifEmpty { null }
            existing.completedAt = Instant.now()
            jpaRepository.save(existing)
        }
    }

    override fun release(idempotencyKey: String) {
        transactionTemplate.execute { jpaRepository.deleteInProgress(idempotencyKey) }
    }

    override fun deleteExpired(cutoff: Instant, limit: Int): Int =
        transactionTemplate.execute { jpaRepository.deleteBatchOlderThan(cutoff, limit) }
}
