-- Rà soát index (docs/architecture/ra-soat-n-plus-1-va-index-catalog-circulation.md). Chỉ DDL, không ghi dữ liệu.
-- Bảng titles giữ RLS+FORCE từ V2. Bảng nhỏ đến vừa nên không cần CONCURRENTLY; nếu một tenant có hàng triệu đầu sách
-- thì tạo tay bằng CREATE INDEX CONCURRENTLY trước khi chạy migration (IF NOT EXISTS bỏ qua phần đã có).

-- Dùng bởi SpringDataTitleRepository.findAllByOrderByName / searchPage khi không có từ khóa (danh sách đầu sách, giới hạn
-- bằng LIMIT): WHERE tenant_id = ? ORDER BY name LIMIT n. idx_titles_tenant_name (V2) là (tenant_id, lower(name)) nên
-- KHÔNG phục vụ được ORDER BY t.name (không có lower), truy vấn phải sắp xếp toàn bộ đầu sách của tenant.
CREATE INDEX IF NOT EXISTS idx_titles_tenant_id_name ON titles (tenant_id, name);
