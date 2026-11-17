-- ADR-0008: policy PERMISSIVE thứ hai, chỉ FOR SELECT, cộng với tenant_isolation (không đổi). Khi biến phiên app.tenant_scope = 'all'
-- (do TenantRlsConnectionInitializer đặt cho platform admin) bảng đọc được mọi tenant; thiếu biến thì NULL ≠ 'all' nên fail-closed.
-- INSERT/UPDATE/DELETE vẫn chỉ qua tenant_isolation (USING/WITH CHECK theo app.tenant_id). Chỉ DDL, không ghi dữ liệu.

CREATE POLICY platform_read_all ON borrowings
    FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');

CREATE POLICY platform_read_all ON borrowing_items
    FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');

CREATE POLICY platform_read_all ON fines
    FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');
