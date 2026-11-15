-- Cách ly audit log theo tenant: audit_logs thuộc một tenant. Dữ liệu hiện có về tenant mặc định (cùng id với
-- V11 của identity; audit_db là CSDL riêng nên không có khóa ngoại tới bảng tenants).
-- Bảng notifications (V3) thuộc task khác.

ALTER TABLE audit_logs ADD COLUMN tenant_id UUID;
UPDATE audit_logs SET tenant_id = '00000000-0000-0000-0000-0000000000d1';
ALTER TABLE audit_logs ALTER COLUMN tenant_id SET NOT NULL;

-- Truy vấn luôn đi kèm tenant: chỉ mục theo tenant thay cho chỉ mục không có tenant
DROP INDEX idx_audit_logs_aggregate;
DROP INDEX idx_audit_logs_event_type;
CREATE INDEX idx_audit_logs_tenant_aggregate ON audit_logs (tenant_id, aggregate_id, occurred_at DESC);
CREATE INDEX idx_audit_logs_tenant_event_type ON audit_logs (tenant_id, event_type, occurred_at DESC);

-- Row-Level Security (fail-closed: không có app.tenant_id thì không thấy và không ghi được dòng nào).
-- Mẫu chung của tenant-common (TenantRlsConnectionInitializer). FORCE để cả chủ bảng cũng chịu RLS.
ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_logs
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
