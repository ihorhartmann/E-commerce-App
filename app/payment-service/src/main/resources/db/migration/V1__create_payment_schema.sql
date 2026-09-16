CREATE TABLE IF NOT EXISTS payment.payments (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    external_id VARCHAR(255),
    amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_payments_external_id UNIQUE (external_id)
);

CREATE INDEX idx_payments_order_id ON payment.payments (order_id);

CREATE TABLE IF NOT EXISTS payment.refunds (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL REFERENCES payment.payments (id),
    external_id VARCHAR(255),
    amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ,
    CONSTRAINT uq_refunds_external_id UNIQUE (external_id)
);

CREATE INDEX idx_refunds_payment_id ON payment.refunds (payment_id);

CREATE TABLE IF NOT EXISTS payment.processed_kafka_messages (
    id             VARCHAR(255) PRIMARY KEY,
    correlation_id VARCHAR(255) NOT NULL,
    message_type   VARCHAR(255) NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_processed_kafka_messages_processed_at ON payment.processed_kafka_messages (processed_at);

CREATE TABLE IF NOT EXISTS payment.idempotent_http_requests (
    id           VARCHAR(255) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    status       VARCHAR(30) NOT NULL,
    http_code    INTEGER,
    response     JSONB,
    created_at   TIMESTAMPTZ NOT NULL,
    claimed_at   TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_idempotent_http_requests_created_at ON payment.idempotent_http_requests (created_at);

CREATE TABLE IF NOT EXISTS payment.outbox_messages (
                                                       id             VARCHAR(255) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    correlation_id VARCHAR(255) NOT NULL,
    message_type   VARCHAR(255) NOT NULL,
    key            VARCHAR(255) NOT NULL,
    payload        JSONB NOT NULL,
    sent_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
    );

CREATE INDEX idx_outbox_messages_sent_at ON payment.outbox_messages (sent_at);
