package com.hartmann.interfaceadapter.out.idempotency.row

import com.hartmann.usecase.port.out.idempotency.dto.HttpRequestRecord
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "idempotent_http_requests", schema = "orders")
class HttpRequestRow(
    @Id
    var id: String = "",
    var requestHash: String = "",
    var status: String = "",
    var httpCode: Int? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var response: String? = null,
    var createdAt: Instant = Instant.now(),
    var claimedAt: Instant = Instant.now(),
    var completedAt: Instant? = null,
)

fun HttpRequestRow.toRecord() = HttpRequestRecord(
    id = id,
    requestHash = requestHash,
    httpCode = httpCode,
    response = response,
    createdAt = createdAt,
    claimedAt = claimedAt,
    completedAt = completedAt,
)
