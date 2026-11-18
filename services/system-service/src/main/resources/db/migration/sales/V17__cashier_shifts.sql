-- ADR-0030 gói B: chốt ca thu ngân. Ca gắn với người thu ngân; tiền vào ca được liên kết bằng cột shift_id (nullable) trên
-- payment_intents, payment_records, refunds. Mọi bảng mới có tenant_id NOT NULL + RLS (ENABLE + FORCE) + tenant_isolation +
-- platform_read_all như V1. Tiền là BIGINT đồng. Không sửa file migration cũ.

-- ===== Cấu hình ca theo tenant (tạo lười như order_abuse_policies; chưa có dòng = mặc định trong code) =====
CREATE TABLE cashier_shift_settings (
    tenant_id                  UUID        PRIMARY KEY,
    require_open_shift_for_pos BOOLEAN     NOT NULL DEFAULT FALSE,
    variance_threshold         BIGINT      NOT NULL DEFAULT 0 CHECK (variance_threshold >= 0),
    version                    BIGINT      NOT NULL DEFAULT 0,
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                 UUID
);

-- ===== Ca thu ngân =====
-- status OPEN/CLOSED; variance_status NONE/PENDING_APPROVAL/APPROVED (chờ quản lý duyệt chênh lệch). Mở lại ca là CLOSED sang
-- OPEN (reopen_count tăng) và xóa số liệu đóng cũ (lịch sử nằm ở cashier_shift_events).
CREATE TABLE cashier_shifts (
    id                UUID         PRIMARY KEY,
    tenant_id         UUID         NOT NULL,
    shift_no          VARCHAR(30)  NOT NULL,
    cashier_user_id   UUID         NOT NULL,
    register_label    VARCHAR(100),
    status            VARCHAR(10)  NOT NULL CHECK (status IN ('OPEN','CLOSED')),
    opened_at         TIMESTAMPTZ  NOT NULL,
    opening_float     BIGINT       NOT NULL,
    closed_at         TIMESTAMPTZ,
    closed_by         UUID,
    counted_cash      BIGINT,
    expected_cash     BIGINT,
    variance          BIGINT,
    variance_reason   VARCHAR(500),
    variance_status   VARCHAR(20)  NOT NULL DEFAULT 'NONE'
                      CHECK (variance_status IN ('NONE','PENDING_APPROVAL','APPROVED')),
    approved_by       UUID,
    approved_at       TIMESTAMPTZ,
    approval_note     VARCHAR(500),
    reopen_count      INT          NOT NULL DEFAULT 0 CHECK (reopen_count >= 0),
    client_request_id UUID         NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_cashier_shifts_float CHECK (opening_float >= 0),
    CONSTRAINT ck_cashier_shifts_counted CHECK (counted_cash IS NULL OR counted_cash >= 0),
    CONSTRAINT ck_cashier_shifts_closed CHECK (status = 'OPEN' OR (closed_at IS NOT NULL AND counted_cash IS NOT NULL
                                                                   AND expected_cash IS NOT NULL AND variance IS NOT NULL)),
    CONSTRAINT uq_cashier_shifts_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT uq_cashier_shifts_no UNIQUE (tenant_id, shift_no),
    CONSTRAINT uq_cashier_shifts_request UNIQUE (tenant_id, client_request_id)
);
-- Mỗi thu ngân tối đa một ca OPEN: hai yêu cầu mở ca đồng thời chỉ một bên thắng (bên thua nhận 409 SHIFT_ALREADY_OPEN)
CREATE UNIQUE INDEX uq_cashier_shifts_open ON cashier_shifts (tenant_id, cashier_user_id) WHERE status = 'OPEN';
CREATE INDEX idx_cashier_shifts_status_opened ON cashier_shifts (tenant_id, status, opened_at DESC);
CREATE INDEX idx_cashier_shifts_cashier_opened ON cashier_shifts (tenant_id, cashier_user_id, opened_at DESC);
CREATE INDEX idx_cashier_shifts_pending_approval ON cashier_shifts (tenant_id, variance_status)
    WHERE variance_status = 'PENDING_APPROVAL';

-- ===== Lịch sử ca: chỉ chèn thêm =====
CREATE TABLE cashier_shift_events (
    id          UUID         PRIMARY KEY,
    tenant_id   UUID         NOT NULL,
    shift_id    UUID         NOT NULL,
    type        VARCHAR(20)  NOT NULL CHECK (type IN ('OPENED','CLOSED','REOPENED','VARIANCE_APPROVED')),
    actor_id    UUID,
    occurred_at TIMESTAMPTZ  NOT NULL,
    snapshot    JSONB,
    CONSTRAINT fk_cashier_shift_events_shift FOREIGN KEY (shift_id, tenant_id) REFERENCES cashier_shifts (id, tenant_id)
);
CREATE INDEX idx_cashier_shift_events_shift ON cashier_shift_events (tenant_id, shift_id, occurred_at);

-- ===== Liên kết tiền vào ca (nullable: đơn cũ/online/tenant không dùng ca không có ca) =====
ALTER TABLE payment_intents ADD COLUMN shift_id UUID;
ALTER TABLE payment_records ADD COLUMN shift_id UUID;
ALTER TABLE refunds         ADD COLUMN shift_id UUID;
ALTER TABLE payment_intents ADD CONSTRAINT fk_payment_intents_shift
    FOREIGN KEY (shift_id, tenant_id) REFERENCES cashier_shifts (id, tenant_id);
ALTER TABLE payment_records ADD CONSTRAINT fk_payment_records_shift
    FOREIGN KEY (shift_id, tenant_id) REFERENCES cashier_shifts (id, tenant_id);
ALTER TABLE refunds ADD CONSTRAINT fk_refunds_shift
    FOREIGN KEY (shift_id, tenant_id) REFERENCES cashier_shifts (id, tenant_id);
-- Báo cáo ca gom nhóm theo (tenant_id, shift_id); lệnh PENDING của ca chặn đóng ca
CREATE INDEX idx_payment_records_shift ON payment_records (tenant_id, shift_id) WHERE shift_id IS NOT NULL;
CREATE INDEX idx_refunds_shift ON refunds (tenant_id, shift_id) WHERE shift_id IS NOT NULL;
CREATE INDEX idx_payment_intents_shift_pending ON payment_intents (tenant_id, shift_id)
    WHERE shift_id IS NOT NULL AND status = 'PENDING';

-- ===== Row-Level Security (fail-closed), cùng mẫu V1 =====
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['cashier_shift_settings', 'cashier_shifts', 'cashier_shift_events']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I
            USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
            WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
        EXECUTE format($p$CREATE POLICY platform_read_all ON %I FOR SELECT
            USING (current_setting('app.tenant_scope', true) = 'all')$p$, t);
    END LOOP;
END
$$;
