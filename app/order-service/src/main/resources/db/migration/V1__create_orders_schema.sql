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
