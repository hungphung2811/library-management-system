-- ADR-0013: danh sách origin (website) được gọi customer-api /public/t/{code}/** từ trình duyệt, theo tenant.
-- Bảng TOÀN CỤC (không RLS, không @TenantId, nằm trong TENANT_EXEMPT_TABLES): platform admin ghi, cấp token storefront
-- đọc khi chưa có ngữ cảnh tenant; use case lọc tenant_id tường minh. origin duy nhất toàn hệ thống (kể cả dòng đang tắt).
-- Không seed dữ liệu (kể cả tenant mặc định).
CREATE TABLE tenant_storefront_origins (
    id         UUID                     PRIMARY KEY,
    tenant_id  UUID                     NOT NULL REFERENCES tenants (id),
    origin     VARCHAR(300)             NOT NULL,
    enabled    BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by UUID,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_by UUID,
    CONSTRAINT tenant_storefront_origins_origin_key UNIQUE (origin),
    -- lưới an toàn khi ai đó chèn tay: đã chuẩn hóa (chữ thường, scheme://host[:port], không path/query)
    CONSTRAINT ck_tenant_storefront_origins_format
        CHECK (origin = lower(origin) AND origin ~ '^https?://[a-z0-9.-]+(:[0-9]{1,5})?$')
);
CREATE INDEX idx_tenant_storefront_origins_tenant ON tenant_storefront_origins (tenant_id);

-- Lịch sử chỉ thêm. origin_id không có FK: dòng gốc có thể đã bị xóa (REMOVED vẫn giữ chuỗi origin).
CREATE TABLE tenant_storefront_origin_history (
    id          UUID                     PRIMARY KEY,
    tenant_id   UUID                     NOT NULL,
    origin_id   UUID                     NOT NULL,
    origin      VARCHAR(300)             NOT NULL,
    action      VARCHAR(10)              NOT NULL CHECK (action IN ('ADDED', 'ENABLED', 'DISABLED', 'REMOVED')),
    actor_id    UUID                     NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_tenant_storefront_origin_history_tenant ON tenant_storefront_origin_history (tenant_id, occurred_at DESC);

-- Chặn UPDATE/DELETE lịch sử, kể cả với chủ bảng (lớp 2; lớp 3 là REVOKE trong afterMigrate.sql).
CREATE FUNCTION tenant_storefront_origin_history_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% là bảng chỉ thêm: không được % dòng', TG_TABLE_NAME, TG_OP USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenant_storefront_origin_history_append_only
    BEFORE UPDATE OR DELETE ON tenant_storefront_origin_history
    FOR EACH ROW EXECUTE FUNCTION tenant_storefront_origin_history_append_only();
