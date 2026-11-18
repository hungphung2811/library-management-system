-- Sửa V13 (đã áp lên DB dev nên không sửa file, tránh đổi checksum): khối backfill của V13 chọn DISTINCT tenant_id từ
-- reservations với app.tenant_scope='all', nhưng reservations chỉ có policy tenant_isolation (V7 chỉ thêm platform_read_all cho
-- borrowings, borrowing_items, fines) nên vai trò chủ bảng chịu FORCE RLS thấy 0 dòng và work_id không được điền.
-- V14: thêm policy platform_read_all cho reservations (khuôn V7, chỉ SELECT; ghi vẫn chỉ qua tenant_isolation) rồi backfill
-- lại từng tenant (idempotent: chỉ điền dòng work_id IS NULL).
CREATE POLICY platform_read_all ON reservations
    FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');

DO $$
DECLARE
    tenants UUID[];
    t       UUID;
BEGIN
    PERFORM set_config('app.tenant_scope', 'all', true);
    SELECT COALESCE(array_agg(DISTINCT tenant_id), ARRAY[]::uuid[]) INTO tenants FROM reservations WHERE work_id IS NULL;
    PERFORM set_config('app.tenant_scope', '', true);

    FOREACH t IN ARRAY tenants LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        UPDATE reservations SET work_id = md5('lms-work:' || title_id::text)::uuid
        WHERE tenant_id = t AND work_id IS NULL;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);
END
$$;
