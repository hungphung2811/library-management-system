-- ADR-0020: thanh toán QR bằng cổng giả lập cho POS. Thêm phương thức QR và trạng thái đơn AWAITING_PAYMENT vào các
-- CHECK hiện có, và bảng payment_intents (lệnh thanh toán). Tên ràng buộc là tên Postgres tự đặt cho CHECK cấp cột
-- trong V1/V2 (<bảng>_<cột>_check); DROP ... IF EXISTS để chạy lại an toàn trên DB đã chỉnh tay.

ALTER TABLE sales_orders DROP CONSTRAINT IF EXISTS sales_orders_payment_method_check;
ALTER TABLE sales_orders ADD CONSTRAINT sales_orders_payment_method_check
    CHECK (payment_method IN ('CASH','BANK_TRANSFER','CARD','COD','QR'));

ALTER TABLE sales_orders DROP CONSTRAINT IF EXISTS sales_orders_status_check;
ALTER TABLE sales_orders ADD CONSTRAINT sales_orders_status_check
    CHECK (status IN ('PENDING_CONFIRMATION','CONFIRMED','READY_FOR_PICKUP','SHIPPING','COMPLETED','CANCELLED',
                      'AWAITING_PAYMENT'));

ALTER TABLE payment_records DROP CONSTRAINT IF EXISTS payment_records_method_check;
ALTER TABLE payment_records ADD CONSTRAINT payment_records_method_check
    CHECK (method IN ('CASH','BANK_TRANSFER','CARD','COD','QR'));

-- Khoản hoàn của đơn đã thu bằng QR vẫn có thể ghi nhận qua phương thức bất kỳ (kể cả QR) khi nhân viên đánh dấu đã hoàn
ALTER TABLE refunds DROP CONSTRAINT IF EXISTS refunds_method_check;
ALTER TABLE refunds ADD CONSTRAINT refunds_method_check
    CHECK (method IN ('CASH','BANK_TRANSFER','CARD','COD','QR'));

-- ===== Lệnh thanh toán =====
-- reference: mã ngắn duy nhất TOÀN HỆ THỐNG (nội dung chuyển khoản), nên UNIQUE không kèm tenant; webhook tra tenant theo
-- mã này (chỉ sau khi xác thực chữ ký). provider_txn_id duy nhất trong tenant: một giao dịch của cổng không thanh toán hai lệnh.
CREATE TABLE payment_intents (
    id              UUID         PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    order_id        UUID         NOT NULL,
    amount          BIGINT       NOT NULL CHECK (amount > 0),
    reference       VARCHAR(40)  NOT NULL,
    status          VARCHAR(10)  NOT NULL CHECK (status IN ('PENDING','PAID','EXPIRED','CANCELLED')),
    provider        VARCHAR(30)  NOT NULL,
    provider_txn_id VARCHAR(100),
    qr_payload      VARCHAR(512),
    expires_at      TIMESTAMPTZ  NOT NULL,
    paid_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_payment_intents_reference UNIQUE (reference),
    CONSTRAINT fk_payment_intents_order FOREIGN KEY (order_id, tenant_id) REFERENCES sales_orders (id, tenant_id),
    CONSTRAINT ck_payment_intents_paid CHECK (status <> 'PAID' OR (provider_txn_id IS NOT NULL AND paid_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_payment_intents_txn ON payment_intents (tenant_id, provider_txn_id)
    WHERE provider_txn_id IS NOT NULL;
CREATE INDEX idx_payment_intents_status_expires ON payment_intents (status, expires_at);
CREATE INDEX idx_payment_intents_order ON payment_intents (tenant_id, order_id);

-- ===== Row-Level Security (fail-closed), cùng mẫu V1 =====
ALTER TABLE payment_intents ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_intents FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment_intents
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY platform_read_all ON payment_intents FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');
