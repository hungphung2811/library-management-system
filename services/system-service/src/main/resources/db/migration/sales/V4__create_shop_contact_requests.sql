-- sales_db (ADR-0015 mục 2.3): yêu cầu liên hệ của đọc giả gửi cửa hàng.
-- Chứa văn bản tự do và dữ liệu cá nhân của khách: RLS ENABLE + FORCE, CHỈ policy tenant_isolation (cố ý KHÔNG có
-- platform_read_all: nền tảng không có nhu cầu đọc nội dung liên hệ). Ẩn danh sau hạn lưu giữ giữ lại dòng, chủ đề,
-- trạng thái và các mốc thời gian.
CREATE TABLE shop_contact_requests (
    id                UUID          PRIMARY KEY,
    tenant_id         UUID          NOT NULL,
    customer_user_id  UUID,
    topic             VARCHAR(20)   NOT NULL,
    message           VARCHAR(2000),
    contact_name      VARCHAR(100),
    contact_email     VARCHAR(254),
    contact_phone     VARCHAR(20),
    consent_at        TIMESTAMPTZ   NOT NULL,
    status            VARCHAR(20)   NOT NULL DEFAULT 'NEW',
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL,
    closed_at         TIMESTAMPTZ,
    pii_anonymized_at TIMESTAMPTZ,
    CONSTRAINT ck_shop_contact_topic CHECK (topic IN ('ORDER', 'PRODUCT', 'PAYMENT', 'OTHER')),
    CONSTRAINT ck_shop_contact_status CHECK (status IN ('NEW', 'IN_PROGRESS', 'CLOSED')),
    CONSTRAINT ck_shop_contact_closed_at CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CONSTRAINT ck_shop_contact_message_not_blank CHECK (message IS NULL OR btrim(message) <> ''),
    CONSTRAINT ck_shop_contact_name_not_blank CHECK (contact_name IS NULL OR btrim(contact_name) <> ''),
    -- Nhất quán ẩn danh: chưa ẩn danh thì bốn cột bắt buộc có giá trị; đã ẩn danh thì cả năm cột PII là NULL.
    CONSTRAINT ck_shop_contact_pii_consistency CHECK (
        (pii_anonymized_at IS NULL
            AND customer_user_id IS NOT NULL AND message IS NOT NULL
            AND contact_name IS NOT NULL AND contact_email IS NOT NULL)
        OR
        (pii_anonymized_at IS NOT NULL
            AND customer_user_id IS NULL AND message IS NULL
            AND contact_name IS NULL AND contact_email IS NULL AND contact_phone IS NULL))
);

-- Danh sách nhân viên (lọc trạng thái, mới nhất trước)
CREATE INDEX ix_shop_contact_tenant_status_created ON shop_contact_requests (tenant_id, status, created_at DESC);
-- Chống spam/trùng theo người gửi (R4, R5)
CREATE INDEX ix_shop_contact_tenant_user_created ON shop_contact_requests (tenant_id, customer_user_id, created_at DESC)
    WHERE customer_user_id IS NOT NULL;
-- Job ẩn danh: dòng chưa ẩn danh theo mốc tạo
CREATE INDEX ix_shop_contact_tenant_pending_anonymize ON shop_contact_requests (tenant_id, created_at)
    WHERE pii_anonymized_at IS NULL;

ALTER TABLE shop_contact_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE shop_contact_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shop_contact_requests
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
