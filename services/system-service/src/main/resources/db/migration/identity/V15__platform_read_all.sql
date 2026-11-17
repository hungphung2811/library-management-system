-- ADR-0008: policy PERMISSIVE thứ hai, chỉ FOR SELECT, cộng với tenant_isolation (không đổi). Khi biến phiên app.tenant_scope = 'all'
-- (do TenantRlsConnectionInitializer đặt cho platform admin) bảng đọc được mọi tenant; thiếu biến thì NULL ≠ 'all' nên fail-closed.
-- INSERT/UPDATE/DELETE vẫn chỉ qua tenant_isolation (USING/WITH CHECK theo app.tenant_id). Chỉ DDL, không ghi dữ liệu.
-- role_permissions không cần policy riêng: tenant_isolation của nó là EXISTS trên roles, và subquery đó chịu RLS của roles
-- (kể cả platform_read_all) nên kế thừa việc đọc xuyên tenant.

CREATE POLICY platform_read_all ON roles
    FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');

CREATE POLICY platform_read_all ON departments
    FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');

-- Lỗ hổng do kế thừa: tenant_isolation của role_permissions là EXISTS trên roles, nên ở scope 'all' subquery thấy mọi role
-- và INSERT/UPDATE/DELETE trên role_permissions sẽ qua được (PlatformReadAllIT đã bắt). Chặn ghi bằng policy RESTRICTIVE
-- (AND với tenant_isolation) khi scope = 'all'; SELECT không bị ảnh hưởng. Thiếu biến thì coalesce về '' nên ghi như cũ.
CREATE POLICY platform_scope_no_insert ON role_permissions AS RESTRICTIVE FOR INSERT
    WITH CHECK (coalesce(current_setting('app.tenant_scope', true), '') <> 'all');

CREATE POLICY platform_scope_no_update ON role_permissions AS RESTRICTIVE FOR UPDATE
    USING (coalesce(current_setting('app.tenant_scope', true), '') <> 'all')
    WITH CHECK (coalesce(current_setting('app.tenant_scope', true), '') <> 'all');

CREATE POLICY platform_scope_no_delete ON role_permissions AS RESTRICTIVE FOR DELETE
    USING (coalesce(current_setting('app.tenant_scope', true), '') <> 'all');
