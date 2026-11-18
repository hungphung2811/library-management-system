-- ADR-0019 mục 2.5: banner trang chủ cửa hàng theo tenant (context sales.banner).
-- Nội dung công khai, không PII: RLS ENABLE + FORCE, policy tenant_isolation và platform_read_all (FOR SELECT, ADR-0008)
-- như các bảng sales khác (khác shop_contact_requests). Quyền cho sales_app do afterMigrate.sql + default privileges cấp.
-- Chỉ DDL, không ghi dữ liệu. Luật đầy đủ (NFC, ký tự cho phép, '..', '%hh') ở domain; CHECK link_path là lớp chặn cuối.
CREATE TABLE storefront_banners (
    id         UUID          PRIMARY KEY,
    tenant_id  UUID          NOT NULL,
    title      VARCHAR(100)  NOT NULL,
    subtitle   VARCHAR(200),
    link_path  VARCHAR(200),
    sort_order INT           NOT NULL DEFAULT 0,
    active     BOOLEAN       NOT NULL DEFAULT TRUE,
    starts_at  TIMESTAMPTZ,
    ends_at    TIMESTAMPTZ,
    version    BIGINT        NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ   NOT NULL,
    updated_at TIMESTAMPTZ   NOT NULL,
    created_by UUID,
    updated_by UUID,
    CONSTRAINT ck_storefront_banners_title CHECK (btrim(title) <> ''),
    CONSTRAINT ck_storefront_banners_subtitle CHECK (subtitle IS NULL OR btrim(subtitle) <> ''),
    -- Đường dẫn tương đối gốc storefront: bắt đầu bằng đúng một '/', không '\', không khoảng trắng/ký tự điều khiển.
    CONSTRAINT ck_storefront_banners_link_path CHECK (
        link_path IS NULL
        OR (link_path ~ '^/([^/\\]|$)' AND link_path !~ '[[:space:][:cntrl:]\\]')),
    CONSTRAINT ck_storefront_banners_sort_order CHECK (sort_order BETWEEN 0 AND 999),
    CONSTRAINT ck_storefront_banners_window CHECK (starts_at IS NULL OR ends_at IS NULL OR starts_at < ends_at)
);

CREATE INDEX ix_storefront_banners_tenant_order ON storefront_banners (tenant_id, sort_order, created_at, id);

ALTER TABLE storefront_banners ENABLE ROW LEVEL SECURITY;
ALTER TABLE storefront_banners FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON storefront_banners
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY platform_read_all ON storefront_banners FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');
