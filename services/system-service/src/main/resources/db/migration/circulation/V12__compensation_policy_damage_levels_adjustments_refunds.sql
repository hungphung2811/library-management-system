-- Tình trạng sách và chính sách đền bù, phần 2: bảng mới. Mọi bảng có tenant_id nên bật RLS + FORCE (mẫu V6/V8); quyền cho
-- circulation_app do afterMigrate.sql cấp. Bảng mới rỗng: không ghi dữ liệu, không cần set_config('app.tenant_id').
-- Tenant chưa có dòng damage_levels / compensation_policies dùng mặc định trong code (hạt giống MINOR 20% và MAJOR 50%,
-- mất 150% giá bìa, không sàn/trần, hạn tìm lại 30 ngày, phí xử lý hoàn 0) nên hành vi cũ không đổi.

-- ===== Bậc hỏng do tenant định nghĩa =====
CREATE TABLE damage_levels (
    id         UUID         PRIMARY KEY,
    tenant_id  UUID         NOT NULL,
    code       VARCHAR(30)  NOT NULL CHECK (code ~ '^[A-Z0-9_]{2,30}$'),
    label      VARCHAR(100) NOT NULL CHECK (length(btrim(label)) > 0),
    rate       NUMERIC(6,4) NOT NULL CHECK (rate >= 0 AND rate <= 5),
    sort_order INTEGER      NOT NULL DEFAULT 0 CHECK (sort_order BETWEEN 0 AND 1000),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    version    BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    updated_by UUID,
    CONSTRAINT uq_damage_levels_tenant_code UNIQUE (tenant_id, code)
);
-- Danh sách theo thứ tự hiển thị của tenant: WHERE tenant_id = ? ORDER BY sort_order, code
CREATE INDEX idx_damage_levels_tenant_sort ON damage_levels (tenant_id, sort_order, code);

-- ===== Chính sách đền bù, một dòng mỗi tenant (không sửa circulation_policies của V8) =====
CREATE TABLE compensation_policies (
    id                    UUID          PRIMARY KEY,
    tenant_id             UUID          NOT NULL,
    version               BIGINT        NOT NULL DEFAULT 0,
    lost_rate             NUMERIC(6,4)  NOT NULL CHECK (lost_rate >= 0 AND lost_rate <= 5),
    price_source          VARCHAR(20)   NOT NULL CHECK (price_source IN ('COVER_PRICE', 'PURCHASE_PRICE')),
    min_amount            NUMERIC(14,0) CHECK (min_amount IS NULL OR min_amount >= 0),
    max_amount            NUMERIC(14,0) CHECK (max_amount IS NULL OR max_amount >= 0),
    found_window_days     INTEGER       NOT NULL CHECK (found_window_days BETWEEN 1 AND 365),
    refund_processing_fee NUMERIC(14,0) NOT NULL DEFAULT 0 CHECK (refund_processing_fee >= 0),
    updated_at            TIMESTAMPTZ   NOT NULL,
    updated_by            UUID,
    CONSTRAINT uq_compensation_policies_tenant UNIQUE (tenant_id),
    CONSTRAINT ck_compensation_policies_bounds CHECK (min_amount IS NULL OR max_amount IS NULL OR min_amount <= max_amount)
);

-- ===== Đề nghị miễn/giảm khoản phạt chưa thu =====
-- Dòng chỉ thêm và chuyển PENDING -> APPROVED/REJECTED một lần, không xóa/sửa lịch sử. fine_amount/fine_reason/reader_id
-- là bản chụp lúc đề nghị để danh sách không phải nối bảng fines.
CREATE TABLE fine_adjustment_requests (
    id               UUID          PRIMARY KEY,
    tenant_id        UUID          NOT NULL,
    fine_id          UUID          NOT NULL,
    reader_id        UUID          NOT NULL,
    fine_reason      VARCHAR(20)   NOT NULL,
    fine_amount      NUMERIC(14,2) NOT NULL,
    kind             VARCHAR(10)   NOT NULL CHECK (kind IN ('WAIVE', 'REDUCE')),
    payable_amount   NUMERIC(14,2) NOT NULL CHECK (payable_amount >= 0),
    reason           VARCHAR(500)  NOT NULL CHECK (length(btrim(reason)) > 0),
    status           VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    requested_by     UUID          NOT NULL,
    requested_at     TIMESTAMPTZ   NOT NULL,
    decided_by       UUID,
    decided_at       TIMESTAMPTZ,
    decision_note    VARCHAR(500),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_fine_adjustment_decided CHECK ((status = 'PENDING') = (decided_at IS NULL AND decided_by IS NULL)),
    CONSTRAINT ck_fine_adjustment_kind_amount CHECK ((kind = 'WAIVE') = (payable_amount = 0)),
    CONSTRAINT ck_fine_adjustment_approver_differs CHECK (decided_by IS NULL OR decided_by <> requested_by)
);
-- Một khoản có tối đa một đề nghị đang chờ (chặn đề nghị trùng khi hai thủ thư gửi đồng thời)
CREATE UNIQUE INDEX uq_fine_adjustment_pending ON fine_adjustment_requests (tenant_id, fine_id) WHERE status = 'PENDING';
-- Danh sách theo trạng thái, mới nhất trước; lịch sử một khoản
CREATE INDEX idx_fine_adjustment_status ON fine_adjustment_requests (tenant_id, status, requested_at DESC);
CREATE INDEX idx_fine_adjustment_fine ON fine_adjustment_requests (tenant_id, fine_id, requested_at DESC);
CREATE INDEX idx_fine_adjustment_reader ON fine_adjustment_requests (tenant_id, reader_id, requested_at DESC);

-- ===== Đề nghị hoàn khoản khi tìm lại sách đã báo mất =====
-- mode: REFUND (khoản mất đã thu, ghi bút toán hoàn), WAIVE (khoản mất chưa thu: miễn toàn phần thay vì hoàn),
-- RESTORE_ONLY (khoản mất đã được miễn: chỉ đưa bản sao về kho). Chỉ ghi nhận bút toán, không chuyển tiền thật.
CREATE TABLE fine_refunds (
    id                    UUID          PRIMARY KEY,
    tenant_id             UUID          NOT NULL,
    fine_id               UUID          NOT NULL,
    borrowing_id          UUID          NOT NULL,
    book_copy_id          UUID          NOT NULL,
    reader_id             UUID          NOT NULL,
    lost_on               DATE          NOT NULL,
    found_on              DATE          NOT NULL,
    restore_status        VARCHAR(10)   NOT NULL CHECK (restore_status IN ('AVAILABLE', 'DAMAGED')),
    check_note            VARCHAR(500),
    refund_method         VARCHAR(20)   NOT NULL CHECK (refund_method IN ('CASH', 'BANK_TRANSFER')),
    reference_code        VARCHAR(100),
    mode                  VARCHAR(12)   NOT NULL CHECK (mode IN ('REFUND', 'WAIVE', 'RESTORE_ONLY')),
    fine_status_at_request VARCHAR(10)  NOT NULL,
    collected_amount      NUMERIC(14,2) NOT NULL CHECK (collected_amount >= 0),
    processing_fee        NUMERIC(14,2) NOT NULL CHECK (processing_fee >= 0),
    refund_amount         NUMERIC(14,2) NOT NULL CHECK (refund_amount >= 0),
    status                VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    requested_by          UUID          NOT NULL,
    requested_at          TIMESTAMPTZ   NOT NULL,
    decided_by            UUID,
    decided_at            TIMESTAMPTZ,
    decision_note         VARCHAR(500),
    version               BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_fine_refunds_decided CHECK ((status = 'PENDING') = (decided_at IS NULL AND decided_by IS NULL)),
    CONSTRAINT ck_fine_refunds_approver_differs CHECK (decided_by IS NULL OR decided_by <> requested_by)
);
-- Mỗi khoản mất chỉ hoàn một lần (đề nghị đang chờ hoặc đã duyệt); đề nghị bị từ chối được lập lại
CREATE UNIQUE INDEX uq_fine_refunds_active ON fine_refunds (tenant_id, fine_id) WHERE status IN ('PENDING', 'APPROVED');
CREATE INDEX idx_fine_refunds_status ON fine_refunds (tenant_id, status, requested_at DESC);
CREATE INDEX idx_fine_refunds_reader ON fine_refunds (tenant_id, reader_id, requested_at DESC);

-- ===== Row-Level Security (fail-closed, mẫu V6/V8) =====
ALTER TABLE damage_levels ENABLE ROW LEVEL SECURITY;
ALTER TABLE damage_levels FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON damage_levels
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE compensation_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE compensation_policies FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON compensation_policies
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE fine_adjustment_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE fine_adjustment_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fine_adjustment_requests
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE fine_refunds ENABLE ROW LEVEL SECURITY;
ALTER TABLE fine_refunds FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fine_refunds
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
