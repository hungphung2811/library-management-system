-- ADR-0026 mục 3.5 (pha expand): đặt trước theo TÁC PHẨM, giới hạn cùng ngôn ngữ với ấn bản độc giả chọn.
-- work_id: id tác phẩm của ấn bản lúc đặt. Backfill bằng công thức tất định md5('lms-work:'||title_id)::uuid (thuần SQL trong
-- circulation_db, không đọc catalog_db; khớp catalog V10 vì mọi ấn bản cũ là tác phẩm 1:1).
-- language: ngôn ngữ ấn bản độc giả chọn; NULL = mọi ngôn ngữ (dòng cũ: work 1:1 nên tương đương).
-- Cột để NULL được ở pha expand (jar circulation cũ có thể còn chèn dòng không work_id trong cửa sổ triển khai; jar mới
-- coi NULL = công thức tất định theo title_id). Chưa bỏ index cũ theo title_id (pha contract).
-- reservations có FORCE RLS: khối backfill đặt app.tenant_id từng tenant (cùng mẫu catalog V10).
ALTER TABLE reservations ADD COLUMN work_id UUID;
ALTER TABLE reservations ADD COLUMN language VARCHAR(8);

DO $$
DECLARE
    tenants UUID[];
    t       UUID;
BEGIN
    PERFORM set_config('app.tenant_scope', 'all', true);
    SELECT COALESCE(array_agg(DISTINCT tenant_id), ARRAY[]::uuid[]) INTO tenants FROM reservations;
    PERFORM set_config('app.tenant_scope', '', true);

    FOREACH t IN ARRAY tenants LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        UPDATE reservations SET work_id = md5('lms-work:' || title_id::text)::uuid
        WHERE tenant_id = t AND work_id IS NULL;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);
END
$$;

-- Hàng đợi FIFO theo tác phẩm (tenant_id đứng đầu: RLS lọc theo cột này)
CREATE INDEX idx_reservations_work_queue ON reservations (tenant_id, work_id, reserved_at, id) WHERE status = 'WAITING';
-- Một độc giả một đặt trước còn hiệu lực trên một tác phẩm
CREATE UNIQUE INDEX uq_reservations_active_work ON reservations (tenant_id, reader_id, work_id)
    WHERE status IN ('WAITING', 'READY');
-- EditionWorkChangedEvent: tìm đặt trước còn hiệu lực của một ấn bản (index hàng đợi cũ chỉ phủ WAITING)
CREATE INDEX idx_reservations_title_active ON reservations (tenant_id, title_id) WHERE status IN ('WAITING', 'READY');
