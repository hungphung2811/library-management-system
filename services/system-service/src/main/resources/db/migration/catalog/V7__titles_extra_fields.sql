-- Thông tin bổ sung của đầu sách (tất cả tùy chọn, không backfill): phụ đề, năm xuất bản, lần tái bản, ngôn ngữ,
-- số trang, định dạng, dịch giả, bộ sách, ảnh bìa, nhãn. Chỉ thêm cột (không ghi dữ liệu nên không cần app.tenant_id);
-- RLS, policy và @TenantId của titles giữ nguyên. CHECK giữ ràng buộc ở DB kể cả khi chèn thẳng.
ALTER TABLE titles
    ADD COLUMN subtitle         VARCHAR(255),
    ADD COLUMN publication_year INTEGER,
    ADD COLUMN edition          VARCHAR(50),
    ADD COLUMN language         VARCHAR(8),
    ADD COLUMN page_count       INTEGER,
    ADD COLUMN format           VARCHAR(20),
    ADD COLUMN translator       VARCHAR(255),
    ADD COLUMN series           VARCHAR(255),
    ADD COLUMN series_number    INTEGER,
    ADD COLUMN cover_image_url  VARCHAR(500),
    ADD COLUMN tags             TEXT[];

ALTER TABLE titles
    ADD CONSTRAINT titles_publication_year_range CHECK (publication_year IS NULL OR publication_year BETWEEN 1000 AND 3000),
    ADD CONSTRAINT titles_language_format CHECK (language IS NULL OR language ~ '^[a-z]{2,8}$'),
    ADD CONSTRAINT titles_page_count_range CHECK (page_count IS NULL OR (page_count > 0 AND page_count <= 20000)),
    ADD CONSTRAINT titles_format_values CHECK (format IS NULL OR format IN ('PAPERBACK', 'HARDCOVER', 'EBOOK')),
    ADD CONSTRAINT titles_series_number_positive CHECK (series_number IS NULL OR series_number > 0),
    ADD CONSTRAINT titles_series_number_needs_series CHECK (series_number IS NULL OR series IS NOT NULL),
    ADD CONSTRAINT titles_cover_image_url_scheme CHECK (cover_image_url IS NULL OR cover_image_url ~* '^https?://'),
    ADD CONSTRAINT titles_tags_max CHECK (tags IS NULL OR cardinality(tags) <= 10),
    ADD CONSTRAINT titles_text_extras_not_blank CHECK (
        (subtitle IS NULL OR btrim(subtitle) <> '') AND (edition IS NULL OR btrim(edition) <> '')
        AND (translator IS NULL OR btrim(translator) <> '') AND (series IS NULL OR btrim(series) <> ''));
