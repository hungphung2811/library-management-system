-- Context cms (ADR-0011): nội dung trang giới thiệu SaaS và yêu cầu liên hệ/dùng thử. Dữ liệu CẤP NỀN TẢNG:
-- KHÔNG có tenant_id, KHÔNG bật RLS (ghi trong TENANT_EXEMPT_TABLES của tenant-common). Bảo vệ bằng tầng ứng dụng:
-- API quản trị @PlatformAdminOnly; API công khai chỉ đọc cột đã xuất bản và chỉ tạo yêu cầu liên hệ.
-- Quyền cho vai trò system_app: afterMigrate.sql cấp SELECT/INSERT/UPDATE/DELETE trên mọi bảng + default privileges
-- (không cần GRANT ở đây).

-- Bản làm việc (title, body_markdown) và bản đã xuất bản (published_*) nằm chung một dòng. Truy vấn công khai chỉ
-- đọc published_* với published_at IS NOT NULL nên bản nháp không lộ kể cả khi đang sửa trang đã xuất bản.
CREATE TABLE cms_pages (
    id                      UUID         PRIMARY KEY,
    slug                    VARCHAR(80)  NOT NULL,
    title                   VARCHAR(200) NOT NULL,
    body_markdown           TEXT         NOT NULL DEFAULT '',
    published_title         VARCHAR(200) NULL,
    published_body_markdown TEXT         NULL,
    published_at            TIMESTAMPTZ  NULL,
    sort_order              INT          NOT NULL DEFAULT 0,
    version                 BIGINT       NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by              UUID         NULL,
    CONSTRAINT uq_cms_pages_slug UNIQUE (slug),
    CONSTRAINT ck_cms_pages_slug CHECK (slug ~ '^[a-z0-9-]+$'),
    CONSTRAINT ck_cms_pages_body_len CHECK (char_length(body_markdown) <= 50000),
    -- Ba cột đã xuất bản cùng có hoặc cùng không
    CONSTRAINT ck_cms_pages_published CHECK (
        (published_at IS NULL AND published_title IS NULL AND published_body_markdown IS NULL)
        OR (published_at IS NOT NULL AND published_title IS NOT NULL AND published_body_markdown IS NOT NULL))
);
CREATE INDEX idx_cms_pages_published ON cms_pages (sort_order, slug) WHERE published_at IS NOT NULL;

-- Yêu cầu liên hệ/dùng thử. full_name, email, phone, message là dữ liệu cá nhân: job hằng ngày (và thao tác của
-- platform admin) đặt NULL + ghi anonymized_at sau 12 tháng; giữ organization, business_type, status và mốc thời gian
-- để thống kê.
CREATE TABLE cms_contact_requests (
    id            UUID          PRIMARY KEY,
    full_name     VARCHAR(100)  NULL,
    email         VARCHAR(254)  NULL,
    phone         VARCHAR(20)   NULL,
    organization  VARCHAR(200)  NOT NULL,
    business_type VARCHAR(20)   NOT NULL,
    message       VARCHAR(2000) NULL,
    consent_at    TIMESTAMPTZ   NOT NULL,
    status        VARCHAR(20)   NOT NULL DEFAULT 'NEW',
    version       BIGINT        NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    handled_by    UUID          NULL,
    handled_at    TIMESTAMPTZ   NULL,
    anonymized_at TIMESTAMPTZ   NULL,
    CONSTRAINT ck_cms_contact_business_type CHECK (business_type IN ('LIBRARY', 'BOOKSTORE', 'OTHER')),
    CONSTRAINT ck_cms_contact_status CHECK (status IN ('NEW', 'IN_PROGRESS', 'HANDLED', 'SPAM')),
    -- Chưa ẩn danh thì phải đủ thông tin liên lạc
    CONSTRAINT ck_cms_contact_pii CHECK (
        anonymized_at IS NOT NULL OR (full_name IS NOT NULL AND email IS NOT NULL AND message IS NOT NULL))
);
CREATE INDEX idx_cms_contact_status_created ON cms_contact_requests (status, created_at);
-- Job ẩn danh quét các dòng chưa ẩn danh theo thời điểm tạo
CREATE INDEX idx_cms_contact_not_anonymized ON cms_contact_requests (created_at) WHERE anonymized_at IS NULL;
