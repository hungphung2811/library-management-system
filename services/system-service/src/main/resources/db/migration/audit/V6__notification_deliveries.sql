-- ADR-0030 gói C: thông báo theo sự kiện qua provider (EMAIL trước). Chỉ DDL, không ghi dữ liệu.
-- Bảng nghiệp vụ có tenant_id: ENABLE + FORCE ROW LEVEL SECURITY, policy tenant_isolation đúng mẫu V3.

-- Danh sách tenant đã từng có thông báo: dispatcher lặp qua đây (không RLS vì không chứa dữ liệu tenant,
-- giống sales_tenants/known_tenants). Được chèn "nếu chưa có" khi enqueue.
CREATE TABLE notification_tenants (
    tenant_id     UUID                     PRIMARY KEY,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Hàng chờ + nhật ký gửi. UNIQUE (tenant_id, event_id, channel, recipient_key) là khóa khử trùng (Kafka giao lại).
-- recipient_address chỉ có với người nhận trực tiếp (khách lẻ) và được xóa về NULL khi delivery đến trạng thái kết thúc.
CREATE TABLE notification_deliveries (
    id                       UUID                     PRIMARY KEY,
    tenant_id                UUID                     NOT NULL,
    event_id                 UUID                     NOT NULL,
    event_type               VARCHAR(100)             NOT NULL,
    notification_type        VARCHAR(60)              NOT NULL,
    category                 VARCHAR(40)              NOT NULL,
    channel                  VARCHAR(20)              NOT NULL,
    recipient_user_id        UUID,
    recipient_key            VARCHAR(64)              NOT NULL,
    recipient_address        VARCHAR(320),
    recipient_address_masked VARCHAR(320),
    params                   JSONB                    NOT NULL DEFAULT '{}'::jsonb,
    template_source          VARCHAR(10),
    template_version         INT,
    status                   VARCHAR(10)              NOT NULL
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'RETRY', 'DEAD', 'SKIPPED')),
    skip_reason              VARCHAR(30),
    attempts                 INT                      NOT NULL DEFAULT 0,
    attempts_base            INT                      NOT NULL DEFAULT 0,
    next_attempt_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error_code          VARCHAR(60),
    last_error_at            TIMESTAMP WITH TIME ZONE,
    sent_at                  TIMESTAMP WITH TIME ZONE,
    provider_message_id      VARCHAR(200),
    last_retry_by            UUID,
    last_retry_at            TIMESTAMP WITH TIME ZONE,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_notification_deliveries_dedup UNIQUE (tenant_id, event_id, channel, recipient_key)
);

-- Dispatcher: where tenant_id = :t and status in (PENDING, RETRY) and next_attempt_at <= :now order by next_attempt_at
CREATE INDEX idx_notification_deliveries_due ON notification_deliveries (tenant_id, status, next_attempt_at);
-- Giới hạn tốc độ: đếm SENT trong 60 giây gần nhất (chỉ mục một phần nên nhỏ)
CREATE INDEX idx_notification_deliveries_sent ON notification_deliveries (tenant_id, sent_at) WHERE status = 'SENT';
-- Lịch sử gửi của chính người dùng
CREATE INDEX idx_notification_deliveries_user ON notification_deliveries (tenant_id, recipient_user_id, created_at DESC)
    WHERE recipient_user_id IS NOT NULL;
-- Danh sách quản trị (lọc status rồi mới nhất trước) và dọn dữ liệu cũ
CREATE INDEX idx_notification_deliveries_list ON notification_deliveries (tenant_id, created_at DESC);

ALTER TABLE notification_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_deliveries FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_deliveries
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
-- Đếm tổng số thư toàn hệ thống cho giới hạn toàn cục (phạm vi đọc ALL, ADR-0008); chỉ SELECT
CREATE POLICY platform_read_all ON notification_deliveries
    FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');

-- Mẫu nội dung do tenant ghi đè: mỗi lần lưu là một phiên bản mới, đúng một phiên bản active
CREATE TABLE notification_templates (
    id                UUID                     PRIMARY KEY,
    tenant_id         UUID                     NOT NULL,
    notification_type VARCHAR(60)              NOT NULL,
    channel           VARCHAR(20)              NOT NULL,
    version           INT                      NOT NULL,
    subject           VARCHAR(300)             NOT NULL,
    body              TEXT                     NOT NULL,
    active            BOOLEAN                  NOT NULL,
    created_by        UUID,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_notification_templates_version UNIQUE (tenant_id, notification_type, channel, version)
);
CREATE UNIQUE INDEX uq_notification_templates_active ON notification_templates (tenant_id, notification_type, channel)
    WHERE active;

ALTER TABLE notification_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_templates FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_templates
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- Bật/tắt kênh theo loại thông báo của tenant; không có dòng = mặc định trong code (bật)
CREATE TABLE notification_channel_settings (
    tenant_id         UUID                     NOT NULL,
    notification_type VARCHAR(60)              NOT NULL,
    channel           VARCHAR(20)              NOT NULL,
    enabled           BOOLEAN                  NOT NULL,
    updated_by        UUID,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, notification_type, channel)
);

ALTER TABLE notification_channel_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_channel_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_channel_settings
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- Người dùng tắt nhận theo nhóm và kênh; không có dòng = nhận
CREATE TABLE notification_preferences (
    tenant_id  UUID                     NOT NULL,
    user_id    UUID                     NOT NULL,
    category   VARCHAR(40)              NOT NULL,
    channel    VARCHAR(20)              NOT NULL,
    enabled    BOOLEAN                  NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, user_id, category, channel)
);

ALTER TABLE notification_preferences ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_preferences FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_preferences
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
