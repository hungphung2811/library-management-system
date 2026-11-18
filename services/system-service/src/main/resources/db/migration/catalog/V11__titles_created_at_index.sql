-- Phục vụ GET /internal/books/new-authors (tác giả có sách mới cho storefront): WHERE tenant_id = ? AND created_at >= ?
-- ORDER BY created_at DESC. Chỉ DDL, không ghi dữ liệu; titles giữ RLS+FORCE từ V2. Bảng nhỏ đến vừa nên không cần
-- CONCURRENTLY (tenant có hàng triệu đầu sách thì tạo tay bằng CREATE INDEX CONCURRENTLY trước, IF NOT EXISTS bỏ qua).
CREATE INDEX IF NOT EXISTS idx_titles_tenant_created_at ON titles (tenant_id, created_at DESC);
