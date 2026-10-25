-- Keep historical orders on their existing manual settlement flow.
ALTER TABLE merchant_orders
    ADD COLUMN payment_method VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN payment_state VARCHAR(16),
    ADD COLUMN alipay_trade_no VARCHAR(64),
    ADD COLUMN paid_at TIMESTAMPTZ,
    ADD COLUMN payment_expires_at TIMESTAMPTZ,
    ADD COLUMN payment_queried_at TIMESTAMPTZ;

ALTER TABLE merchant_orders DROP CONSTRAINT merchant_orders_status_check;
ALTER TABLE merchant_orders ADD CONSTRAINT merchant_orders_status_check
    CHECK (status IN ('PENDING_PAYMENT', 'PENDING_CONFIRMATION', 'CONFIRMED', 'SHIPPED', 'COMPLETED', 'CANCELLED'));
ALTER TABLE merchant_orders ADD CONSTRAINT ck_merchant_orders_payment
    CHECK (payment_method IN ('MANUAL', 'ALIPAY') AND
        ((payment_method = 'MANUAL' AND payment_state IS NULL)
         OR (payment_method = 'ALIPAY' AND payment_state IS NOT NULL
             AND payment_state IN ('PENDING', 'PAID', 'CLOSED') AND payment_expires_at IS NOT NULL)));
ALTER TABLE merchant_orders ADD CONSTRAINT ck_merchant_orders_paid
    CHECK ((payment_state IS NOT DISTINCT FROM 'PAID' AND alipay_trade_no IS NOT NULL AND paid_at IS NOT NULL)
        OR (payment_state IS DISTINCT FROM 'PAID' AND alipay_trade_no IS NULL AND paid_at IS NULL));
CREATE UNIQUE INDEX idx_merchant_orders_alipay_trade ON merchant_orders(alipay_trade_no)
    WHERE alipay_trade_no IS NOT NULL;
