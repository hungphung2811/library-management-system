-- Cách ly dữ liệu circulation theo tenant: lượt mượn, bản sao trong lượt mượn, đặt trước, phí phạt, quy tắc mượn.
-- Dữ liệu hiện có về tenant mặc định (id do identity V11 tạo; circulation_db không có bảng tenants nên không có FK).
-- Thứ tự: thêm cột + backfill + ràng buộc + dữ liệu khởi tạo TRƯỚC, bật RLS SAU CÙNG (FORCE RLS áp cả chủ bảng, tức
-- chính vai trò chạy migration này).

-- ===== Danh bạ tenant mà circulation biết (toàn cục, không RLS) =====
-- Nuôi bởi event TenantCreated/TenantStatusChanged của identity; việc nền (UC14) đọc danh sách ACTIVE trước khi có
-- tenant nào trong ngữ cảnh, nên bảng này không thuộc tenant.
CREATE TABLE known_tenants (
    tenant_id  UUID        PRIMARY KEY,
    status     VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO known_tenants (tenant_id, status) VALUES ('00000000-0000-0000-0000-0000000000d1', 'ACTIVE');

-- ===== borrowings =====
ALTER TABLE borrowings ADD COLUMN tenant_id UUID;
UPDATE borrowings SET tenant_id = '00000000-0000-0000-0000-0000000000d1';
ALTER TABLE borrowings ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE borrowings ADD CONSTRAINT uq_borrowings_id_tenant UNIQUE (id, tenant_id);
DROP INDEX IF EXISTS idx_borrowings_user_id;
CREATE INDEX idx_borrowings_tenant_user ON borrowings (tenant_id, user_id);

-- ===== borrowing_items =====
-- Bảng phụ của @ElementCollection: Hibernate không ghi tenant_id cho bảng này, nên cột lấy mặc định từ biến phiên
-- app.tenant_id (do TenantRlsConnectionInitializer đặt). Khóa ngoại kép bảo đảm luôn trùng tenant của lượt mượn.
ALTER TABLE borrowing_items ADD COLUMN tenant_id UUID;
UPDATE borrowing_items bi SET tenant_id = b.tenant_id FROM borrowings b WHERE b.id = bi.borrowing_id;
ALTER TABLE borrowing_items ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE borrowing_items ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::uuid;
ALTER TABLE borrowing_items DROP CONSTRAINT borrowing_items_borrowing_id_fkey;
ALTER TABLE borrowing_items ADD CONSTRAINT fk_borrowing_items_borrowing_tenant
    FOREIGN KEY (borrowing_id, tenant_id) REFERENCES borrowings (id, tenant_id) ON DELETE CASCADE;
-- Một bản sao không thể thuộc hai lượt mượn đang mở trong cùng tenant
DROP INDEX IF EXISTS uq_borrowing_items_open_copy;
CREATE UNIQUE INDEX uq_borrowing_items_open_copy ON borrowing_items (tenant_id, book_copy_id) WHERE return_date IS NULL;

-- ===== reservations =====
ALTER TABLE reservations ADD COLUMN tenant_id UUID;
UPDATE reservations SET tenant_id = '00000000-0000-0000-0000-0000000000d1';
ALTER TABLE reservations ALTER COLUMN tenant_id SET NOT NULL;
DROP INDEX IF EXISTS uq_reservations_active;
CREATE UNIQUE INDEX uq_reservations_active ON reservations (tenant_id, reader_id, title_id)
    WHERE status IN ('WAITING', 'READY');
DROP INDEX IF EXISTS idx_reservations_queue;
CREATE INDEX idx_reservations_queue ON reservations (tenant_id, title_id, reserved_at, id) WHERE status = 'WAITING';
DROP INDEX IF EXISTS idx_reservations_reader;
CREATE INDEX idx_reservations_reader ON reservations (tenant_id, reader_id, reserved_at DESC);
DROP INDEX IF EXISTS uq_reservations_ready_copy;
CREATE UNIQUE INDEX uq_reservations_ready_copy ON reservations (tenant_id, copy_id) WHERE status = 'READY';
DROP INDEX IF EXISTS idx_reservations_hold_expiry;
CREATE INDEX idx_reservations_hold_expiry ON reservations (tenant_id, hold_until) WHERE status = 'READY';

-- ===== fines =====
ALTER TABLE fines ADD COLUMN tenant_id UUID;
UPDATE fines SET tenant_id = '00000000-0000-0000-0000-0000000000d1';
ALTER TABLE fines ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE fines DROP CONSTRAINT uq_fines_item_reason;
ALTER TABLE fines ADD CONSTRAINT uq_fines_item_reason UNIQUE (tenant_id, borrowing_id, book_copy_id, reason);
DROP INDEX IF EXISTS idx_fines_reader;
CREATE INDEX idx_fines_reader ON fines (tenant_id, reader_id, created_at DESC);
DROP INDEX IF EXISTS idx_fines_reader_unpaid;
CREATE INDEX idx_fines_reader_unpaid ON fines (tenant_id, reader_id) WHERE status = 'UNPAID';

-- ===== borrow_rules: khóa chính (tenant_id, reader_type) =====
ALTER TABLE borrow_rules ADD COLUMN tenant_id UUID;
UPDATE borrow_rules SET tenant_id = '00000000-0000-0000-0000-0000000000d1';
ALTER TABLE borrow_rules ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE borrow_rules DROP CONSTRAINT borrow_rules_pkey;
ALTER TABLE borrow_rules ADD PRIMARY KEY (tenant_id, reader_type);
-- Quy tắc mặc định của tenant mặc định (V4 đã seed; giữ nếu admin đã sửa). Tenant mới nhận quy tắc mặc định qua
-- event TenantCreated (circulation-service, ProvisionDefaultBorrowRulesUseCase).
INSERT INTO borrow_rules (tenant_id, reader_type, max_items, loan_days) VALUES
    ('00000000-0000-0000-0000-0000000000d1', 'STUDENT', 5, 14),
    ('00000000-0000-0000-0000-0000000000d1', 'LECTURER', 10, 30)
ON CONFLICT (tenant_id, reader_type) DO NOTHING;

-- ===== Row-Level Security (fail-closed: không có app.tenant_id thì không thấy và không ghi được dòng nào) =====
-- Mẫu chung của tenant-common (TenantRlsConnectionInitializer). FORCE để cả chủ bảng cũng chịu RLS.
ALTER TABLE borrowings ENABLE ROW LEVEL SECURITY;
ALTER TABLE borrowings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON borrowings
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE borrowing_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE borrowing_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON borrowing_items
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE reservations ENABLE ROW LEVEL SECURITY;
ALTER TABLE reservations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reservations
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE fines ENABLE ROW LEVEL SECURITY;
ALTER TABLE fines FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fines
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE borrow_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE borrow_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON borrow_rules
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
