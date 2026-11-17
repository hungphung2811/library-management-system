-- ADR-0011 (2.12e): mô tả ngắn và đoạn đọc thử của đầu sách, tùy chọn, văn bản thuần.
-- Chỉ thêm cột (không ghi dữ liệu nên không cần app.tenant_id); RLS, policy và @TenantId của titles giữ nguyên.
-- Giới hạn độ dài ở DB bảo đảm không đường nào trả dài hơn kể cả khi chèn thẳng; CHECK giữ "hasPreview" nhất quán
-- (chuỗi rỗng/chỉ khoảng trắng không hợp lệ, phải là NULL).
ALTER TABLE titles
    ADD COLUMN description  VARCHAR(500),
    ADD COLUMN preview_text VARCHAR(2000);

ALTER TABLE titles
    ADD CONSTRAINT titles_description_not_blank CHECK (description IS NULL OR btrim(description) <> ''),
    ADD CONSTRAINT titles_preview_text_not_blank CHECK (preview_text IS NULL OR btrim(preview_text) <> '');
