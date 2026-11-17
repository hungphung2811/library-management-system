-- ADR-0012: hợp đồng thuê dịch vụ theo tenant. Năm bảng TOÀN CỤC (không RLS, không @TenantId) như tenants/tenant_modules:
-- platform admin không có tenant vẫn đọc/ghi được, job quét xuyên tenant; lọc theo tenant cho tenant admin làm tường
-- minh trong use case. Cũng thêm nguyên nhân tạm ngưng vào tenants (2.3.5) và backfill hợp đồng legacy.

-- Ràng buộc chồng lấn (R1) cần btree_gist (extension "trusted" từ PG13: chủ DB tạo được). Không tạo được thì bỏ EXCLUDE,
-- chỉ còn kiểm trong ứng dụng dưới khóa dòng tenant (xem DO block bên dưới).
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS btree_gist;
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'Không tạo được extension btree_gist (%): bỏ ràng buộc EXCLUDE chống chồng lấn hợp đồng', SQLERRM;
END
$$;

CREATE TABLE tenant_contracts (
    id                  UUID                     PRIMARY KEY,
    tenant_id           UUID                     NOT NULL REFERENCES tenants (id),
    name                VARCHAR(255)             NOT NULL,
    status              VARCHAR(20)              NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'EXPIRED', 'CANCELLED')),
    legacy              BOOLEAN                  NOT NULL DEFAULT FALSE,
    starts_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    -- NULL chỉ với hợp đồng legacy chưa bị cắt; hợp đồng tạo qua API luôn có ngày kết thúc
    ends_at             TIMESTAMP WITH TIME ZONE,
    total_amount        BIGINT                   NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    billing_cycle_unit  VARCHAR(10)              CHECK (billing_cycle_unit IN ('DAY', 'MONTH', 'YEAR')),
    billing_cycle_value INTEGER                  CHECK (billing_cycle_value > 0),
    internal_note       VARCHAR(2000),
    modules_applied_at  TIMESTAMP WITH TIME ZONE,
    retained_module     VARCHAR(20)              CHECK (retained_module IN ('LENDING', 'SALES')),
    cancel_reason       VARCHAR(1000),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_by          UUID,
    version             BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT ck_contract_ends_when_not_legacy CHECK (legacy OR ends_at IS NOT NULL),
    CONSTRAINT ck_contract_period CHECK (ends_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_contract_cancel_reason CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL),
    CONSTRAINT ck_contract_cycle_pair CHECK ((billing_cycle_unit IS NULL) = (billing_cycle_value IS NULL))
);
CREATE INDEX idx_tenant_contracts_tenant ON tenant_contracts (tenant_id, starts_at DESC);
CREATE INDEX idx_tenant_contracts_status ON tenant_contracts (status, ends_at);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'btree_gist') THEN
        ALTER TABLE tenant_contracts ADD CONSTRAINT ex_tenant_contracts_active_overlap
            EXCLUDE USING gist (tenant_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
            WHERE (status = 'ACTIVE');
    END IF;
END
$$;

CREATE TABLE tenant_contract_modules (
    contract_id UUID        NOT NULL REFERENCES tenant_contracts (id),
    module      VARCHAR(20) NOT NULL CHECK (module IN ('LENDING', 'SALES')),
    PRIMARY KEY (contract_id, module)
);

-- Thanh toán: chỉ thêm. Sửa sai = dòng đảo toàn phần (amount < 0, reverses_payment_id trỏ dòng gốc dương) + dòng đúng.
CREATE TABLE tenant_contract_payments (
    id                  UUID                     PRIMARY KEY,
    contract_id         UUID                     NOT NULL REFERENCES tenant_contracts (id),
    tenant_id           UUID                     NOT NULL REFERENCES tenants (id),
    paid_on             DATE                     NOT NULL,
    amount              BIGINT                   NOT NULL CHECK (amount <> 0),
    note                VARCHAR(1000),
    reference           VARCHAR(255),
    reverses_payment_id UUID                     REFERENCES tenant_contract_payments (id),
    recorded_by         UUID,
    recorded_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_payment_reversal_sign CHECK ((reverses_payment_id IS NULL) = (amount > 0)),
    CONSTRAINT uq_payment_reversed_once UNIQUE (reverses_payment_id)
);
CREATE INDEX idx_tenant_contract_payments_contract ON tenant_contract_payments (contract_id, recorded_at);

-- Lịch sử: chỉ thêm. contract_id NULL cho ngoại lệ ngoài hợp đồng. actor_id NULL khi actor_type = SYSTEM (job).
CREATE TABLE tenant_contract_history (
    id          UUID                     PRIMARY KEY,
    tenant_id   UUID                     NOT NULL REFERENCES tenants (id),
    contract_id UUID                     REFERENCES tenant_contracts (id),
    type        VARCHAR(50)              NOT NULL,
    actor_id    UUID,
    actor_type  VARCHAR(10)              NOT NULL CHECK (actor_type IN ('USER', 'SYSTEM')),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    data        JSONB                    NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX idx_tenant_contract_history_contract ON tenant_contract_history (contract_id, occurred_at DESC);
CREATE INDEX idx_tenant_contract_history_tenant ON tenant_contract_history (tenant_id, occurred_at DESC);

-- Mốc nhắc hạn đã gửi. PK gồm ends_at: kéo dài ngày kết thúc thì mốc tự "mở lại".
CREATE TABLE tenant_contract_reminders (
    contract_id     UUID                     NOT NULL REFERENCES tenant_contracts (id),
    days_before     INTEGER                  NOT NULL CHECK (days_before > 0),
    ends_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    recipient_count INTEGER                  NOT NULL DEFAULT 0,
    skipped         BOOLEAN                  NOT NULL DEFAULT FALSE,
    PRIMARY KEY (contract_id, days_before, ends_at)
);

-- Chặn UPDATE/DELETE bảng bất biến, kể cả với chủ bảng (lớp 2; lớp 3 là REVOKE trong afterMigrate.sql).
CREATE FUNCTION tenant_contract_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% là bảng chỉ thêm: không được % dòng', TG_TABLE_NAME, TG_OP USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenant_contract_payments_append_only
    BEFORE UPDATE OR DELETE ON tenant_contract_payments
    FOR EACH ROW EXECUTE FUNCTION tenant_contract_append_only();
CREATE TRIGGER trg_tenant_contract_history_append_only
    BEFORE UPDATE OR DELETE ON tenant_contract_history
    FOR EACH ROW EXECUTE FUNCTION tenant_contract_append_only();

-- Nguyên nhân tạm ngưng (2.3.5). Tenant đang SUSPENDED trước migrate không biết nguyên nhân: coi là MANUAL (không bao
-- giờ tự mở).
ALTER TABLE tenants
    ADD COLUMN suspension_cause VARCHAR(30)
        CHECK (suspension_cause IN ('MANUAL', 'CONTRACT_EXPIRED', 'CONTRACT_CANCELLED')),
    ADD COLUMN suspended_by_contract_id UUID REFERENCES tenant_contracts (id);

UPDATE tenants SET suspension_cause = 'MANUAL' WHERE status = 'SUSPENDED';

ALTER TABLE tenants
    ADD CONSTRAINT ck_tenants_suspension_consistent CHECK ((status = 'ACTIVE') = (suspension_cause IS NULL)),
    ADD CONSTRAINT ck_tenants_suspended_by_contract CHECK (suspended_by_contract_id IS NULL
        OR suspension_cause IN ('CONTRACT_EXPIRED', 'CONTRACT_CANCELLED'));

-- Giữ nhất quán cho mọi đường ghi (kể cả SQL tay/script cũ đặt status mà không biết cột mới): SUSPENDED không nguyên
-- nhân thành MANUAL; ACTIVE xóa nguyên nhân.
CREATE FUNCTION tenants_normalize_suspension() RETURNS trigger AS $$
BEGIN
    IF NEW.status = 'ACTIVE' THEN
        NEW.suspension_cause := NULL;
        NEW.suspended_by_contract_id := NULL;
    ELSIF NEW.suspension_cause IS NULL THEN
        NEW.suspension_cause := 'MANUAL';
        NEW.suspended_by_contract_id := NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenants_normalize_suspension
    BEFORE INSERT OR UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION tenants_normalize_suspension();

-- Backfill legacy: mỗi tenant có ít nhất một module bật được một hợp đồng legacy ACTIVE, không hết hạn, module = các
-- module đang bật, nên job ngay sau migrate không tắt module nào. Bảng không RLS nên không cần set_config.
DO $$
DECLARE
    t RECORD;
    cid UUID;
BEGIN
    FOR t IN SELECT id, created_at FROM tenants LOOP
        IF NOT EXISTS (SELECT 1 FROM tenant_modules WHERE tenant_id = t.id AND enabled) THEN
            RAISE NOTICE 'Tenant % không có module bật: không tạo hợp đồng legacy', t.id;
            CONTINUE;
        END IF;
        cid := gen_random_uuid();
        INSERT INTO tenant_contracts (id, tenant_id, name, status, legacy, starts_at, ends_at, total_amount,
                                      modules_applied_at)
        VALUES (cid, t.id, 'Hợp đồng chuyển tiếp', 'ACTIVE', TRUE, t.created_at, NULL, 0, now());
        INSERT INTO tenant_contract_modules (contract_id, module)
        SELECT cid, module FROM tenant_modules WHERE tenant_id = t.id AND enabled;
        INSERT INTO tenant_contract_history (id, tenant_id, contract_id, type, actor_id, actor_type, data)
        VALUES (gen_random_uuid(), t.id, cid, 'LEGACY_BACKFILLED', NULL, 'SYSTEM',
                jsonb_build_object('modules', (SELECT jsonb_agg(module ORDER BY module) FROM tenant_modules
                                               WHERE tenant_id = t.id AND enabled)));
    END LOOP;
END
$$;
