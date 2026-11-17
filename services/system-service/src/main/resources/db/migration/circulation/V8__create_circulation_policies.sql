-- Chính sách mượn trả theo từng tenant (số ngày mượn, gia hạn, hạn mức, phạt, giữ chỗ đặt trước). Một dòng mỗi tenant;
-- tenant chưa có dòng dùng giá trị mặc định trong code (trùng hành vi cũ). Bảng mới có tenant_id nên bật RLS + FORCE.
-- Quyền cho circulation_app do afterMigrate.sql cấp (ALL TABLES + default privileges).
CREATE TABLE circulation_policies (
    id                          UUID        PRIMARY KEY,
    tenant_id                   UUID        NOT NULL,
    version                     BIGINT      NOT NULL DEFAULT 0,
    loan_days                   INTEGER     NOT NULL CHECK (loan_days BETWEEN 1 AND 365),
    max_renewals                INTEGER     NOT NULL CHECK (max_renewals BETWEEN 0 AND 20),
    renewal_days                INTEGER     NOT NULL CHECK (renewal_days BETWEEN 1 AND 365),
    max_active_loans_per_reader INTEGER     NOT NULL CHECK (max_active_loans_per_reader BETWEEN 1 AND 100),
    fine_per_day                BIGINT      NOT NULL CHECK (fine_per_day BETWEEN 0 AND 10000000),
    grace_days                  INTEGER     NOT NULL CHECK (grace_days BETWEEN 0 AND 90),
    reservation_hold_days       INTEGER     NOT NULL CHECK (reservation_hold_days BETWEEN 1 AND 30),
    updated_at                  TIMESTAMPTZ NOT NULL,
    updated_by                  UUID,
    CONSTRAINT uq_circulation_policies_tenant UNIQUE (tenant_id)
);

ALTER TABLE circulation_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE circulation_policies FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON circulation_policies
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
