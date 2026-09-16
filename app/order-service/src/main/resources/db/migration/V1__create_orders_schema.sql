CREATE TABLE orders.orders (
    id                        BIGSERIAL PRIMARY KEY,
    user_id                   BIGINT NOT NULL,
    address_city              VARCHAR(255) NOT NULL,
    address_postal_code       VARCHAR(255) NOT NULL,
    address_line1             VARCHAR(255) NOT NULL,
    address_line2             VARCHAR(255) NOT NULL,
    status                    VARCHAR(30) NOT NULL,
    payment_confirmed_at      TIMESTAMPTZ,
    cancellation_reason       VARCHAR(30),
    awaiting_return           BOOLEAN NOT NULL,
    shipment_tracking_number  VARCHAR(255),
    shipment_picked_up_at     TIMESTAMPTZ,
    shipment_delivered_at     TIMESTAMPTZ,
    shipment_returned_at      TIMESTAMPTZ,
    refund_requested_at       TIMESTAMPTZ,
    refund_amount             NUMERIC(19, 2),
    refund_currency           VARCHAR(3),
    refund_confirmed_at       TIMESTAMPTZ,
    refund_failed_at          TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL,
    updated_at                TIMESTAMPTZ
);

CREATE TABLE orders.order_items (
    id             BIGSERIAL PRIMARY KEY,
    order_id       BIGINT NOT NULL REFERENCES orders.orders (id),
    product_id     BIGINT NOT NULL,
    quantity       INTEGER NOT NULL,
    price_amount   NUMERIC(19, 2) NOT NULL,
    price_currency VARCHAR(3) NOT NULL
);

CREATE INDEX idx_order_items_order_id ON orders.order_items (order_id);

CREATE TABLE orders.processed_kafka_messages (
    id             VARCHAR(255) PRIMARY KEY,
    correlation_id VARCHAR(255) NOT NULL,
    message_type   VARCHAR(255) NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_processed_kafka_messages_processed_at ON orders.processed_kafka_messages (processed_at);

CREATE TABLE orders.idempotent_http_requests (
    id           VARCHAR(255) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    status       VARCHAR(30) NOT NULL,
    http_code    INTEGER,
    response     JSONB,
    created_at   TIMESTAMPTZ NOT NULL,
    claimed_at   TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_idempotent_http_requests_created_at ON orders.idempotent_http_requests (created_at);

CREATE TABLE orders.outbox_messages (
    id             VARCHAR(255) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    correlation_id VARCHAR(255) NOT NULL,
    message_type   VARCHAR(255) NOT NULL,
    topic          VARCHAR(255) NOT NULL,
    key            VARCHAR(255) NOT NULL,
    payload        JSONB NOT NULL,
    sent_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_messages_sent_at ON orders.outbox_messages (sent_at);
