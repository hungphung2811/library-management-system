-- Thông báo cho người dùng (hiện tại: đặt trước sẵn sàng nhận). Bảng mới nên không cần backfill: bật RLS ngay.
-- Idempotent theo event: UNIQUE (tenant_id, event_id), eventId do tenant sinh ra nên khóa kèm tenant (ruling T11).
CREATE TABLE notifications (
    id         UUID                     PRIMARY KEY,
    tenant_id  UUID                     NOT NULL,
    event_id   UUID                     NOT NULL,
    user_id    UUID                     NOT NULL,
    type       VARCHAR(100)             NOT NULL,
    message    TEXT                     NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    read_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_notifications_tenant_event UNIQUE (tenant_id, event_id)
);

CREATE INDEX idx_notifications_tenant_user ON notifications (tenant_id, user_id, created_at DESC);

-- Row-Level Security (fail-closed), đúng mẫu audit_logs ở V2.
ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notifications
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
