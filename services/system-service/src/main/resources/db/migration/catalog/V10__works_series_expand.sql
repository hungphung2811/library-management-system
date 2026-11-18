-- ADR-0026 pha E1 (expand): mô hình 3 tầng tác phẩm (works) - ấn bản (titles, giữ nguyên id) - bản sao (book_copies), kèm bộ sách (series).
-- Chỉ THÊM bảng và cột NULL nên jar catalog cũ vẫn chạy trên schema mới (Hibernate validate của jar cũ không thấy cột mới).
-- KHÔNG làm bước contract (titles.work_id NOT NULL): để migration riêng ở pha sau, sau khi R1 chạy ổn.
-- Mọi bảng mới có tenant_id + ENABLE/FORCE RLS (tenant_isolation) + platform_read_all (chỉ SELECT) như V5.
-- Quyền cho catalog_app do afterMigrate.sql cấp (default privileges).
--
-- Backfill (mục 4.2 ADR): mỗi đầu sách hiện có thành MỘT tác phẩm 1:1, id TẤT ĐỊNH tính được bằng SQL thuần ở mọi DB:
--   work_id   = md5('lms-work:'   || titles.id::text)::uuid
--   series_id = md5('lms-series:' || tenant_id::text || ':' || name_key)::uuid
-- (circulation, sales backfill cùng công thức từ title_id của chính DB mình; Java: lms.catalog.catalog.domain.LegacyIds).
-- name_key của series PHẢI khớp Reference.keyOf() (Java): NFC, bỏ khoảng trắng Unicode ở hai đầu (Character.isWhitespace),
-- gộp khoảng trắng ASCII liên tiếp thành một dấu cách, chữ thường. Test: CatalogWorksMigrationIT so với Java.
-- FORCE RLS áp cả cho chủ bảng: khối DO lặp từng tenant và đặt app.tenant_id; danh sách tenant đọc một lần bằng
-- app.tenant_scope = 'all' (platform_read_all) rồi tắt scope, sau đó mọi câu ghi chỉ dựa vào tenant_isolation.
-- Chạy lại khối backfill an toàn (ON CONFLICT DO NOTHING, UPDATE chỉ dòng work_id IS NULL).

-- ===== series (bộ sách; khuôn danh mục Reference của V5) =====
CREATE TABLE series (
    id          UUID          PRIMARY KEY,
    tenant_id   UUID          NOT NULL,
    name        VARCHAR(255)  NOT NULL CHECK (btrim(name) <> ''),
    name_key    VARCHAR(255)  NOT NULL,
    description VARCHAR(500),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_series_tenant_name UNIQUE (tenant_id, name_key),
    CONSTRAINT uq_series_id_tenant UNIQUE (id, tenant_id)
);

-- ===== works (tác phẩm) =====
CREATE TABLE works (
    id                UUID          PRIMARY KEY,
    tenant_id         UUID          NOT NULL,
    title             VARCHAR(255)  NOT NULL CHECK (btrim(title) <> ''),
    original_title    VARCHAR(255)  CHECK (original_title IS NULL OR btrim(original_title) <> ''),
    original_language VARCHAR(8)    CHECK (original_language IS NULL OR original_language ~ '^[a-z]{2,8}$'),
    author            VARCHAR(255)  NOT NULL,
    description       VARCHAR(500)  CHECK (description IS NULL OR btrim(description) <> ''),
    category_id       UUID,
    series_id         UUID,
    series_number     INTEGER       CHECK (series_number IS NULL OR series_number > 0),
    series_label      VARCHAR(50)   CHECK (series_label IS NULL OR btrim(series_label) <> ''),
    kind              VARCHAR(20)   NOT NULL DEFAULT 'SINGLE' CHECK (kind IN ('SINGLE', 'COLLECTION')),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_works_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_works_series_number_needs_series CHECK (series_number IS NULL OR series_id IS NOT NULL),
    CONSTRAINT fk_works_category_tenant FOREIGN KEY (category_id, tenant_id) REFERENCES categories (id, tenant_id),
    CONSTRAINT fk_works_series_tenant FOREIGN KEY (series_id, tenant_id) REFERENCES series (id, tenant_id)
);
-- Không UNIQUE (tenant_id, series_id, series_number): backfill sinh trùng hợp lệ (HP 1 bìa mềm và bìa cứng là hai work cho
-- tới khi gộp); trùng số tập là gợi ý gộp, không phải lỗi.
CREATE INDEX idx_works_tenant_title ON works (tenant_id, title, id);
CREATE INDEX idx_works_tenant_series ON works (tenant_id, series_id, series_number, id) WHERE series_id IS NOT NULL;
CREATE INDEX idx_works_tenant_category ON works (tenant_id, category_id) WHERE category_id IS NOT NULL;

-- ===== work_authors (thay dần title_authors) =====
CREATE TABLE work_authors (
    work_id    UUID    NOT NULL,
    author_id  UUID    NOT NULL,
    tenant_id  UUID    NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (work_id, author_id),
    CONSTRAINT fk_work_authors_work_tenant FOREIGN KEY (work_id, tenant_id) REFERENCES works (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_work_authors_author_tenant FOREIGN KEY (author_id, tenant_id) REFERENCES authors (id, tenant_id)
);
CREATE INDEX idx_work_authors_tenant_author ON work_authors (tenant_id, author_id);

-- ===== titles (ấn bản): work_id NULL ở pha expand, nhãn phần của tập =====
ALTER TABLE titles
    ADD COLUMN work_id     UUID,
    ADD COLUMN part_number INTEGER CHECK (part_number IS NULL OR part_number > 0),
    ADD COLUMN part_label  VARCHAR(50) CHECK (part_label IS NULL OR btrim(part_label) <> '');
ALTER TABLE titles ADD CONSTRAINT fk_titles_work_tenant
    FOREIGN KEY (work_id, tenant_id) REFERENCES works (id, tenant_id);
CREATE INDEX idx_titles_tenant_work ON titles (tenant_id, work_id) WHERE work_id IS NOT NULL;

-- ===== Row-Level Security (fail-closed, mẫu như V5) =====
ALTER TABLE series ENABLE ROW LEVEL SECURITY;
ALTER TABLE series FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON series
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY platform_read_all ON series
    FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');

ALTER TABLE works ENABLE ROW LEVEL SECURITY;
ALTER TABLE works FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON works
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY platform_read_all ON works
    FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');

ALTER TABLE work_authors ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_authors FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON work_authors
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY platform_read_all ON work_authors
    FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');

-- ===== Backfill: mỗi đầu sách thành 1 tác phẩm 1:1 (ADR-0026 mục 4.2). Khối này bắt đầu bằng "DO $$" (test chạy lại riêng). =====
DO $$
DECLARE
    tenants UUID[];
    t       UUID;
BEGIN
    PERFORM set_config('app.tenant_scope', 'all', true);
    SELECT COALESCE(array_agg(DISTINCT tenant_id), ARRAY[]::uuid[]) INTO tenants FROM titles;
    -- Chỉ tenant_isolation từ đây: mọi câu dưới lọc tenant_id tường minh và WITH CHECK chặn ghi lệch tenant.
    PERFORM set_config('app.tenant_scope', '', true);

    FOREACH t IN ARRAY tenants LOOP
        PERFORM set_config('app.tenant_id', t::text, true);

        -- 1. series từ chuỗi titles.series (tên chuẩn hóa; name_key giống Reference.keyOf)
        INSERT INTO series (id, tenant_id, name, name_key, active, created_at)
        SELECT DISTINCT ON (n.name_key) md5('lms-series:' || t::text || ':' || n.name_key)::uuid, t, n.name, n.name_key,
               TRUE, now()
        FROM (
            SELECT c.name, lower(c.name) AS name_key
            FROM (
                SELECT regexp_replace(
                           regexp_replace(normalize(ti.series, NFC),
                                          '^[ \t\n\r\f\v\u001c-\u001f  -  -    　]+'
                                          '|[ \t\n\r\f\v\u001c-\u001f  -  -    　]+$',
                                          '', 'g'),
                           '[ \t\n\r\f\v]+', ' ', 'g') AS name
                FROM titles ti
                WHERE ti.tenant_id = t AND ti.series IS NOT NULL
            ) c
            WHERE c.name <> ''
        ) n
        ORDER BY n.name_key, n.name
        ON CONFLICT DO NOTHING;

        -- 2. works: một dòng cho mỗi đầu sách (id tất định)
        INSERT INTO works (id, tenant_id, title, author, description, category_id, series_id, series_number, kind, created_at)
        SELECT md5('lms-work:' || ti.id::text)::uuid, t,
               COALESCE(NULLIF(btrim(ti.name), ''), '(không tên)'), ti.author,
               NULLIF(left(btrim(ti.description), 500), ''), ti.category_id, s.id,
               CASE WHEN s.id IS NULL THEN NULL ELSE ti.series_number END, 'SINGLE', ti.created_at
        FROM (
            SELECT ti0.*,
                   lower(regexp_replace(
                       regexp_replace(normalize(ti0.series, NFC),
                                      '^[ \t\n\r\f\v\u001c-\u001f  -  -    　]+'
                                      '|[ \t\n\r\f\v\u001c-\u001f  -  -    　]+$',
                                      '', 'g'),
                       '[ \t\n\r\f\v]+', ' ', 'g')) AS series_key
            FROM titles ti0
            WHERE ti0.tenant_id = t
        ) ti
        LEFT JOIN series s ON s.tenant_id = t AND ti.series_key <> '' AND s.name_key = ti.series_key
        ON CONFLICT DO NOTHING;

        -- 3. work_authors chép từ title_authors
        INSERT INTO work_authors (work_id, author_id, tenant_id, sort_order)
        SELECT md5('lms-work:' || ta.title_id::text)::uuid, ta.author_id, t, ta.sort_order
        FROM title_authors ta
        WHERE ta.tenant_id = t
        ON CONFLICT DO NOTHING;

        -- 4. gắn đầu sách vào tác phẩm tất định của nó
        UPDATE titles SET work_id = md5('lms-work:' || id::text)::uuid
        WHERE tenant_id = t AND work_id IS NULL;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);
END
$$;
