-- ADR-0019 mục 2.1: sắp theo giá ở DB cho danh sách storefront.
-- cover_price_snapshot là bản sao giá bìa của catalog, CHỈ để làm khóa sắp (không bao giờ hiển thị hay tính tiền; giá thật
-- là sellingPrice ?? coverPrice sống từ catalog). Đã làm tròn đồng (HALF_UP), NULL = chưa biết. Ứng dụng ghi lúc đưa vào
-- bán, đồng bộ lười trước truy vấn sắp theo giá và tự sửa lệch khi xem.
-- effective_price là cột sinh: đổi selling_price qua API thì tự cập nhật, entity ánh xạ chỉ đọc.
-- Chỉ DDL, không ghi dữ liệu nên không cần app.tenant_id; RLS/policy của stock_items giữ nguyên.
-- ⚠️ Cột sinh STORED viết lại bảng và giữ khóa ACCESS EXCLUSIVE trong lúc chạy: chấp nhận với cỡ bảng hiện tại.
ALTER TABLE stock_items
    ADD COLUMN cover_price_snapshot BIGINT,
    ADD COLUMN effective_price BIGINT GENERATED ALWAYS AS (COALESCE(selling_price, cover_price_snapshot)) STORED,
    ADD CONSTRAINT ck_stock_items_cover_price_snapshot CHECK (cover_price_snapshot IS NULL OR cover_price_snapshot >= 0);

-- Sách mới về: theo ngày đưa vào bán.
CREATE INDEX idx_stock_items_storefront_newest ON stock_items (tenant_id, created_at DESC, id) WHERE active;
-- Sắp theo giá (giảm dần dùng quét ngược cùng chỉ mục); mặt hàng chưa có giá hiệu lực bị loại ngay ở DB.
CREATE INDEX idx_stock_items_storefront_price ON stock_items (tenant_id, effective_price, id)
    WHERE active AND effective_price IS NOT NULL;
