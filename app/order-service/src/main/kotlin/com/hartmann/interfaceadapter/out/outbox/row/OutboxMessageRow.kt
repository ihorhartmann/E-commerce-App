package com.hartmann.interfaceadapter.out.outbox.row

import com.hartmann.usecase.port.out.outbox.dto.OutboxMessage
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Generated
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.generator.EventType
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "outbox_messages", schema = "orders")
class OutboxMessageRow(
    @Id
    @Generated(event = [EventType.INSERT])
    @Column(insertable = false, updatable = false)
    var id: String = "",
    var correlationId: String = "",
    var messageType: String = "",
    var topic: String = "",
    var key: String = "",
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var payload: String = "",
    var sentAt: Instant? = null,
    var createdAt: Instant = Instant.now(),
)

fun toOutboxMessage(row: OutboxMessageRow) = OutboxMessage(
    messageId = row.id,
    correlationId = row.correlationId,
    messageType = row.messageType,
    topic = row.topic,
    key = row.key,
    payload = row.payload,
)
