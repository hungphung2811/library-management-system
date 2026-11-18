-- ADR-0026 mục 3.6 (pha 2b): gộp biến thể theo tác phẩm ở storefront.
-- work_id là SNAPSHOT id tác phẩm (catalog_db) của ấn bản, cùng khuôn cover_price_snapshot (ADR-0019): chỉ để GROUP BY
-- ở DB, không bao giờ hiển thị hay dùng để bán. Ứng dụng ghi lúc đưa vào bán và tự sửa khi xem nếu catalog trả workId khác.
-- NULL = chưa biết: danh sách coi mặt hàng là một nhóm riêng (COALESCE(work_id, title_id)).
-- Backfill bằng đúng công thức catalog V10: mỗi ấn bản cũ là một tác phẩm 1:1 có id md5('lms-work:' || title_id)::uuid.
-- stock_items FORCE RLS và UPDATE bị tenant_isolation chặn khi không có app.tenant_id, nên lặp theo tenant.
ALTER TABLE stock_items ADD COLUMN work_id UUID;

SELECT set_config('app.tenant_scope', 'all', true);
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT DISTINCT tenant_id FROM stock_items LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        UPDATE stock_items
           SET work_id = md5('lms-work:' || title_id::text)::uuid
         WHERE tenant_id = t AND work_id IS NULL;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;

-- Gom nhóm và nạp biến thể theo tác phẩm: tenant_id đứng đầu (RLS), chỉ mặt hàng đang bán.
CREATE INDEX idx_stock_items_work ON stock_items (tenant_id, work_id) WHERE active AND work_id IS NOT NULL;
