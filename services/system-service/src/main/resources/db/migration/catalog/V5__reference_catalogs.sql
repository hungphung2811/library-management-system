-- Danh mục chuẩn theo tenant cho catalog: tác giả, nhà xuất bản, thể loại (cây tối đa 2 cấp), vị trí kệ.
-- Chỉ DDL (không ghi dữ liệu nên không cần app.tenant_id). Dữ liệu cũ KHÔNG backfill: các cột chuỗi hiện có
-- (titles.author ...) giữ nguyên, tham chiếu mới đều tùy chọn (nullable).
-- Mỗi bảng: tenant_id + ENABLE/FORCE RLS (policy tenant_isolation như V2) + platform_read_all (ADR-0008, chỉ SELECT).
-- *_key là tên đã chuẩn hóa chữ thường do ứng dụng điền; UNIQUE (tenant_id, *_key) chặn trùng tên theo tenant.
-- active=false là "ngừng dùng" thay cho xóa cứng khi mục đã được tham chiếu.
-- Quyền cho vai trò catalog_app do callback afterMigrate.sql cấp (ALL TABLES + default privileges).

-- ===== authors =====
CREATE TABLE authors (
    id          UUID          PRIMARY KEY,
    tenant_id   UUID          NOT NULL,
    name        VARCHAR(255)  NOT NULL CHECK (btrim(name) <> ''),
    name_key    VARCHAR(255)  NOT NULL,
    description VARCHAR(500),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_authors_tenant_name UNIQUE (tenant_id, name_key),
    CONSTRAINT uq_authors_id_tenant UNIQUE (id, tenant_id)
);
CREATE INDEX idx_authors_tenant_name ON authors (tenant_id, name_key);

-- ===== publishers =====
CREATE TABLE publishers (
    id          UUID          PRIMARY KEY,
    tenant_id   UUID          NOT NULL,
    name        VARCHAR(255)  NOT NULL CHECK (btrim(name) <> ''),
    name_key    VARCHAR(255)  NOT NULL,
    description VARCHAR(500),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_publishers_tenant_name UNIQUE (tenant_id, name_key),
    CONSTRAINT uq_publishers_id_tenant UNIQUE (id, tenant_id)
);
CREATE INDEX idx_publishers_tenant_name ON publishers (tenant_id, name_key);

-- ===== categories (cây tối đa 2 cấp: ứng dụng kiểm; parent cùng tenant nhờ khóa ngoại kép) =====
CREATE TABLE categories (
    id          UUID          PRIMARY KEY,
    tenant_id   UUID          NOT NULL,
    parent_id   UUID,
    name        VARCHAR(255)  NOT NULL CHECK (btrim(name) <> ''),
    name_key    VARCHAR(255)  NOT NULL,
    description VARCHAR(500),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_categories_tenant_name UNIQUE (tenant_id, name_key),
    CONSTRAINT uq_categories_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_categories_not_self_parent CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT fk_categories_parent_tenant FOREIGN KEY (parent_id, tenant_id) REFERENCES categories (id, tenant_id)
);
CREATE INDEX idx_categories_tenant_parent ON categories (tenant_id, parent_id);

-- ===== shelf_locations (mã vị trí + mô tả) =====
CREATE TABLE shelf_locations (
    id          UUID          PRIMARY KEY,
    tenant_id   UUID          NOT NULL,
    code        VARCHAR(64)   NOT NULL CHECK (btrim(code) <> ''),
    code_key    VARCHAR(64)   NOT NULL,
    description VARCHAR(500),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_shelf_locations_tenant_code UNIQUE (tenant_id, code_key),
    CONSTRAINT uq_shelf_locations_id_tenant UNIQUE (id, tenant_id)
);
CREATE INDEX idx_shelf_locations_tenant_code ON shelf_locations (tenant_id, code_key);

-- ===== Tham chiếu tùy chọn từ đầu sách và bản sao (nullable; khóa ngoại kép giữ cùng tenant) =====
ALTER TABLE titles ADD COLUMN publisher_id UUID;
ALTER TABLE titles ADD COLUMN category_id UUID;
ALTER TABLE titles ADD CONSTRAINT fk_titles_publisher_tenant
    FOREIGN KEY (publisher_id, tenant_id) REFERENCES publishers (id, tenant_id);
ALTER TABLE titles ADD CONSTRAINT fk_titles_category_tenant
    FOREIGN KEY (category_id, tenant_id) REFERENCES categories (id, tenant_id);
CREATE INDEX idx_titles_tenant_category ON titles (tenant_id, category_id) WHERE category_id IS NOT NULL;
CREATE INDEX idx_titles_tenant_publisher ON titles (tenant_id, publisher_id) WHERE publisher_id IS NOT NULL;

ALTER TABLE book_copies ADD COLUMN shelf_location_id UUID;
ALTER TABLE book_copies ADD CONSTRAINT fk_book_copies_shelf_tenant
    FOREIGN KEY (shelf_location_id, tenant_id) REFERENCES shelf_locations (id, tenant_id);
CREATE INDEX idx_book_copies_tenant_shelf ON book_copies (tenant_id, shelf_location_id) WHERE shelf_location_id IS NOT NULL;

-- ===== title_authors: nhiều tác giả chuẩn cho một đầu sách (sort_order giữ thứ tự) =====
CREATE TABLE title_authors (
    title_id   UUID    NOT NULL,
    author_id  UUID    NOT NULL,
    tenant_id  UUID    NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (title_id, author_id),
    CONSTRAINT fk_title_authors_title_tenant FOREIGN KEY (title_id, tenant_id) REFERENCES titles (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_title_authors_author_tenant FOREIGN KEY (author_id, tenant_id) REFERENCES authors (id, tenant_id)
);
CREATE INDEX idx_title_authors_author ON title_authors (tenant_id, author_id);

-- ===== Row-Level Security (fail-closed, mẫu như V2/V3) =====
ALTER TABLE authors ENABLE ROW LEVEL SECURITY;
ALTER TABLE authors FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON authors
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY platform_read_all ON authors
    FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');

ALTER TABLE publishers ENABLE ROW LEVEL SECURITY;
ALTER TABLE publishers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON publishers
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY platform_read_all ON publishers
    FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');

ALTER TABLE categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE categories FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON categories
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY platform_read_all ON categories
    FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');

ALTER TABLE shelf_locations ENABLE ROW LEVEL SECURITY;
ALTER TABLE shelf_locations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shelf_locations
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY platform_read_all ON shelf_locations
    FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');

ALTER TABLE title_authors ENABLE ROW LEVEL SECURITY;
ALTER TABLE title_authors FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON title_authors
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY platform_read_all ON title_authors
    FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');
