package com.hartmann.interfaceadapter.out.idempotency.row

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "processed_kafka_messages", schema = "orders")
class ProcessedKafkaMessageRow(
    @Id
    var id: UUID? = null,
    var correlationId: String = "",
    var messageType: String = "",
    var processedAt: Instant = Instant.now(),
)
