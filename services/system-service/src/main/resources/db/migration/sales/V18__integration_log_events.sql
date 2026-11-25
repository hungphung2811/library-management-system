-- ADR-0029 (giai doan luu log): luu dong log kenh INTEGRATION_OUTBOUND / INTEGRATION_INBOUND va su kien chon loc cua kenh
-- SYSTEM de man hinh van hanh xem, loc, phan trang. CHI luu cac truong da che san cua IntegrationLog (khong than request,
-- khong token, khong so dien thoai). Giu 30 ngay (job don trong sales-service). Khong sua file migration cu.
--
-- tenant_id NULL: su kien chua biet tenant (vi du webhook chua tra duoc tenant); chi platform (app.tenant_scope = 'all') doc duoc.
CREATE TABLE integration_log_events (
    id             UUID         PRIMARY KEY,
    tenant_id      UUID,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    channel        VARCHAR(24)  NOT NULL CHECK (channel IN ('INTEGRATION_OUTBOUND', 'INTEGRATION_INBOUND', 'SYSTEM')),
    provider       VARCHAR(128),
    event          VARCHAR(32)  NOT NULL,
    level          VARCHAR(8)   NOT NULL,
    op             VARCHAR(128),
    outcome        VARCHAR(128),
    http_status    INT,
    duration_ms    BIGINT,
    error_kind     VARCHAR(128),
    error_class    VARCHAR(128),
    anomaly_type   VARCHAR(128),
    correlation_id VARCHAR(128),
    intent_id      UUID,
    reference      VARCHAR(128),
    extra          JSONB
);

-- Man hinh: moi nhat truoc, loc theo kenh trong khoang thoi gian (tenant_id dung dau vi RLS loc theo cot nay)
CREATE INDEX idx_integration_log_tenant_channel_time ON integration_log_events (tenant_id, channel, occurred_at DESC);
-- Job don cu (xoa theo occurred_at, khong theo tenant) va platform doc moi tenant
CREATE INDEX idx_integration_log_time ON integration_log_events (occurred_at);

ALTER TABLE integration_log_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration_log_events FORCE ROW LEVEL SECURITY;
-- Tenant chi doc va chen dong cua minh (hang tenant_id NULL khong khop nen tenant khong thay). Co y KHONG cap UPDATE/DELETE
-- cho tenant: log la du lieu chi them, chi job don (phat platform_purge ben duoi) duoc xoa.
CREATE POLICY tenant_read ON integration_log_events FOR SELECT
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_insert ON integration_log_events FOR INSERT
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
-- Platform doc moi dong (ke ca tenant_id NULL).
CREATE POLICY platform_read_all ON integration_log_events FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');
-- Ghi dong khong co tenant: luong nen ghi log chay trong pham vi 'all' (khong co app.tenant_id), chi duoc chen dong NULL.
CREATE POLICY platform_insert_untenanted ON integration_log_events FOR INSERT
    WITH CHECK (tenant_id IS NULL AND current_setting('app.tenant_scope', true) = 'all');
-- Don dep theo han giu: job nen chay trong pham vi 'all', chi duoc xoa (khong sua) dong log.
CREATE POLICY platform_purge ON integration_log_events FOR DELETE
    USING (current_setting('app.tenant_scope', true) = 'all');
