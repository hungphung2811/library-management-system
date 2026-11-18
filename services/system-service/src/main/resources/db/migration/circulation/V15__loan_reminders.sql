-- Gói D (ADR-0030 D11): nhắc hạn trả. (1) ba cột cấu hình nhắc hạn trong chính sách mượn trả của tenant;
-- (2) sổ ghi lần nhắc loan_reminders (khử trùng, đếm số lần, dừng khi đủ trần). Bảng mới có tenant_id nên bật RLS + FORCE
-- (mẫu V8); quyền cho circulation_app do afterMigrate.sql cấp. Bảng mới rỗng nên không cần set_config('app.tenant_id').
-- Dòng circulation_policies đã có nhận mặc định 2 ngày / 3 ngày / 3 lần (DEFAULT của cột); tenant chưa có dòng dùng mặc định trong code.

ALTER TABLE circulation_policies
    ADD COLUMN due_reminder_days_before       INTEGER NOT NULL DEFAULT 2 CHECK (due_reminder_days_before BETWEEN 0 AND 30),
    ADD COLUMN overdue_reminder_interval_days INTEGER NOT NULL DEFAULT 3 CHECK (overdue_reminder_interval_days BETWEEN 1 AND 30),
    ADD COLUMN overdue_reminder_max           INTEGER NOT NULL DEFAULT 3 CHECK (overdue_reminder_max BETWEEN 0 AND 20);

CREATE TABLE loan_reminders (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    borrowing_id UUID        NOT NULL,
    kind         VARCHAR(10) NOT NULL CHECK (kind IN ('DUE_SOON', 'OVERDUE')),
    -- Hạn trả tại lúc nhắc: gia hạn đổi due_date thì (phiếu, hạn mới) là bộ khóa mới và được nhắc lại
    due_date     DATE        NOT NULL,
    seq          INTEGER     NOT NULL CHECK (seq >= 1),
    sent_on      DATE        NOT NULL,
    event_id     UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Khử trùng giữa các lần chạy/instance và phục vụ tra "đã nhắc chưa", "đã nhắc mấy lần" theo phiếu
    CONSTRAINT uq_loan_reminders_key UNIQUE (tenant_id, borrowing_id, kind, due_date, seq),
    CONSTRAINT fk_loan_reminders_borrowing_tenant FOREIGN KEY (borrowing_id, tenant_id)
        REFERENCES borrowings (id, tenant_id) ON DELETE CASCADE
);

ALTER TABLE loan_reminders ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_reminders FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON loan_reminders
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- Chọn phiếu theo hạn trả dùng idx_borrowings_tenant_due_date (tenant_id, due_date) của V9; borrowings không có cột status
-- (trạng thái suy ra từ borrowing_items.return_date) nên không thể làm chỉ mục một phần theo ACTIVE: không thêm chỉ mục.
