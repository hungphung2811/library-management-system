-- sales_db (ADR-0010, docs/architecture/sales-module-design.md mục 3), pha 1: cấu hình, mặt hàng bán + sổ cái tồn kho,
-- nhà cung cấp, phiếu nhập, đơn hàng (POS và online), thanh toán, bộ đếm số chứng từ, nhật ký audit.
-- V2 (pha 2): phiếu trả hàng và hoàn tiền (sales_returns, refunds). sales_tenants (danh bạ tenant cho job ẩn danh,
-- không RLS) thêm ở B2.5, kèm TENANT_EXEMPT_TABLES.
-- Mọi bảng có tenant_id NOT NULL + RLS (ENABLE + FORCE) ở cuối file. Khóa ngoại nội bộ dùng khóa kép (id, tenant_id).
-- Không FK sang DB khác: title_id, user_id, actor_id chỉ là UUID. Số tiền BIGINT (đồng VND), tỷ lệ INT (basis point).

-- ===== Cấu hình bán hàng theo tenant (tạo lười: chưa có dòng thì dùng SalesSettings.defaults()) =====
CREATE TABLE sales_settings (
    tenant_id                    UUID        PRIMARY KEY,
    vat_rate_bps                 INT         NOT NULL DEFAULT 500 CHECK (vat_rate_bps BETWEEN 0 AND 10000),
    prices_include_vat           BOOLEAN     NOT NULL DEFAULT TRUE,
    return_window_days           INT         NOT NULL DEFAULT 7 CHECK (return_window_days >= 0),
    fixed_shipping_fee           BIGINT      NOT NULL DEFAULT 0 CHECK (fixed_shipping_fee >= 0),
    low_stock_threshold          INT         NOT NULL DEFAULT 5 CHECK (low_stock_threshold >= 0),
    walk_in_pii_retention_months INT         NOT NULL DEFAULT 12 CHECK (walk_in_pii_retention_months BETWEEN 1 AND 120),
    currency                     VARCHAR(3)  NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    version                      BIGINT      NOT NULL DEFAULT 0,
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                   UUID
);

-- ===== Bộ đếm số chứng từ theo tenant (order_no, receipt_no, ...): tăng trong cùng transaction tạo chứng từ =====
CREATE TABLE sales_counters (
    tenant_id  UUID        NOT NULL,
    name       VARCHAR(30) NOT NULL,
    last_value BIGINT      NOT NULL DEFAULT 0 CHECK (last_value >= 0),
    PRIMARY KEY (tenant_id, name)
);

-- ===== Mặt hàng bán (đầu sách đã "đưa vào bán") =====
CREATE TABLE stock_items (
    id                  UUID        PRIMARY KEY,
    tenant_id           UUID        NOT NULL,
    title_id            UUID        NOT NULL,
    on_hand             INT         NOT NULL DEFAULT 0,
    reserved            INT         NOT NULL DEFAULT 0,
    selling_price       BIGINT      CHECK (selling_price >= 0),
    low_stock_threshold INT         CHECK (low_stock_threshold >= 0),
    active              BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_stock_items_tenant_title UNIQUE (tenant_id, title_id),
    CONSTRAINT uq_stock_items_id_tenant UNIQUE (id, tenant_id),
    -- Lớp chặn cuối cho tồn kho (ADR-0010 2.5): không âm, không giữ quá hàng thực có
    CONSTRAINT ck_stock_items_quantities CHECK (on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand)
);

-- Sổ cái tồn kho: chỉ chèn thêm, ghi cùng transaction với mọi thay đổi bộ đếm. on_hand = SUM(delta_on_hand).
CREATE TABLE stock_ledger (
    id             UUID         PRIMARY KEY,
    tenant_id      UUID         NOT NULL,
    stock_item_id  UUID         NOT NULL,
    type           VARCHAR(20)  NOT NULL CHECK (type IN ('RECEIPT','SALE','RESERVE','RELEASE','FULFIL','RETURN',
                                                          'WRITE_OFF','ADJUSTMENT','STOCKTAKE','CANCEL_RESTOCK')),
    delta_on_hand  INT          NOT NULL DEFAULT 0,
    delta_reserved INT          NOT NULL DEFAULT 0,
    ref_type       VARCHAR(30),
    ref_id         UUID,
    reason         VARCHAR(500),
    actor_id       UUID,
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_stock_ledger_item FOREIGN KEY (stock_item_id, tenant_id) REFERENCES stock_items (id, tenant_id),
    -- Điều chỉnh, kiểm kho, hao hụt bắt buộc có lý do
    CONSTRAINT ck_stock_ledger_reason CHECK (type NOT IN ('ADJUSTMENT','STOCKTAKE','WRITE_OFF')
                                             OR (reason IS NOT NULL AND length(btrim(reason)) > 0))
);
CREATE INDEX idx_stock_ledger_item_time ON stock_ledger (tenant_id, stock_item_id, occurred_at DESC);
CREATE INDEX idx_stock_ledger_ref ON stock_ledger (tenant_id, ref_type, ref_id);

-- ===== Nhà cung cấp và phiếu nhập =====
CREATE TABLE suppliers (
    id         UUID         PRIMARY KEY,
    tenant_id  UUID         NOT NULL,
    name       VARCHAR(255) NOT NULL,
    phone      VARCHAR(30),
    email      VARCHAR(255),
    address    VARCHAR(500),
    note       VARCHAR(1000),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_suppliers_id_tenant UNIQUE (id, tenant_id)
);
CREATE UNIQUE INDEX uq_suppliers_tenant_name ON suppliers (tenant_id, lower(name));

CREATE TABLE goods_receipts (
    id                UUID         PRIMARY KEY,
    tenant_id         UUID         NOT NULL,
    receipt_no        VARCHAR(30)  NOT NULL,
    supplier_id       UUID         NOT NULL,
    status            VARCHAR(20)  NOT NULL CHECK (status IN ('DRAFT','CONFIRMED','CANCELLED')),
    client_request_id UUID         NOT NULL,
    created_by        UUID,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    confirmed_at      TIMESTAMPTZ,
    confirmed_by      UUID,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_goods_receipts_tenant_no UNIQUE (tenant_id, receipt_no),
    CONSTRAINT uq_goods_receipts_tenant_request UNIQUE (tenant_id, client_request_id),
    CONSTRAINT uq_goods_receipts_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT fk_goods_receipts_supplier FOREIGN KEY (supplier_id, tenant_id) REFERENCES suppliers (id, tenant_id),
    CONSTRAINT ck_goods_receipts_confirmed CHECK (status <> 'CONFIRMED' OR confirmed_at IS NOT NULL)
);
CREATE INDEX idx_goods_receipts_tenant_created ON goods_receipts (tenant_id, created_at DESC);

CREATE TABLE goods_receipt_lines (
    id         UUID    PRIMARY KEY,
    tenant_id  UUID    NOT NULL,
    receipt_id UUID    NOT NULL,
    line_no    INT     NOT NULL,
    title_id   UUID    NOT NULL,
    quantity   INT     NOT NULL CHECK (quantity > 0),
    unit_cost  BIGINT  NOT NULL CHECK (unit_cost >= 0),
    CONSTRAINT uq_goods_receipt_lines_no UNIQUE (receipt_id, line_no),
    CONSTRAINT fk_goods_receipt_lines_receipt FOREIGN KEY (receipt_id, tenant_id)
        REFERENCES goods_receipts (id, tenant_id)
);

-- ===== Đơn hàng (POS và online) =====
CREATE TABLE sales_orders (
    id                       UUID         PRIMARY KEY,
    tenant_id                UUID         NOT NULL,
    order_no                 VARCHAR(30)  NOT NULL,
    channel                  VARCHAR(10)  NOT NULL CHECK (channel IN ('POS','ONLINE')),
    status                   VARCHAR(30)  NOT NULL CHECK (status IN ('PENDING_CONFIRMATION','CONFIRMED',
                                                 'READY_FOR_PICKUP','SHIPPING','COMPLETED','CANCELLED')),
    payment_status           VARCHAR(30)  NOT NULL DEFAULT 'UNPAID' CHECK (payment_status IN ('UNPAID','PAID',
                                                 'PARTIALLY_REFUNDED','REFUNDED')),
    payment_method           VARCHAR(20)  NOT NULL CHECK (payment_method IN ('CASH','BANK_TRANSFER','CARD','COD')),
    customer_user_id         UUID,
    -- Dữ liệu cá nhân (không ghi vào log/audit/event); walk_in_* bị ẩn danh sau thời hạn lưu giữ
    walk_in_name             VARCHAR(255),
    walk_in_phone            VARCHAR(30),
    fulfilment               VARCHAR(10)  NOT NULL DEFAULT 'COUNTER' CHECK (fulfilment IN ('COUNTER','PICKUP','DELIVERY')),
    recipient_name           VARCHAR(255),
    recipient_phone          VARCHAR(30),
    recipient_address        VARCHAR(500),
    -- Snapshot cấu hình tại thời điểm bán
    vat_rate_bps             INT          NOT NULL CHECK (vat_rate_bps >= 0),
    prices_include_vat       BOOLEAN      NOT NULL,
    currency                 VARCHAR(3)   NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    subtotal                 BIGINT       NOT NULL CHECK (subtotal >= 0),
    discount_total           BIGINT       NOT NULL DEFAULT 0 CHECK (discount_total >= 0),
    vat_total                BIGINT       NOT NULL CHECK (vat_total >= 0),
    shipping_fee             BIGINT       NOT NULL DEFAULT 0 CHECK (shipping_fee >= 0),
    grand_total              BIGINT       NOT NULL CHECK (grand_total >= 0),
    order_discount_type      VARCHAR(10)  CHECK (order_discount_type IN ('AMOUNT','PERCENT')),
    order_discount_value     BIGINT       CHECK (order_discount_value >= 0),
    client_request_id        UUID         NOT NULL,
    exchange_of_return_id    UUID,        -- tham chiếu sales_returns (V2, pha 2); chưa có FK
    created_by               UUID,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    confirmed_at             TIMESTAMPTZ,
    confirmed_by             UUID,
    paid_at                  TIMESTAMPTZ,
    completed_at             TIMESTAMPTZ,
    cancelled_at             TIMESTAMPTZ,
    cancelled_by             UUID,
    cancel_reason            VARCHAR(500),
    pii_anonymized_at        TIMESTAMPTZ,
    version                  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_sales_orders_tenant_no UNIQUE (tenant_id, order_no),
    CONSTRAINT uq_sales_orders_tenant_request UNIQUE (tenant_id, client_request_id),
    CONSTRAINT uq_sales_orders_id_tenant UNIQUE (id, tenant_id)
);
CREATE INDEX idx_sales_orders_tenant_status_created ON sales_orders (tenant_id, status, created_at DESC);
CREATE INDEX idx_sales_orders_tenant_payment_created ON sales_orders (tenant_id, payment_status, created_at);
CREATE INDEX idx_sales_orders_tenant_customer ON sales_orders (tenant_id, customer_user_id, created_at DESC)
    WHERE customer_user_id IS NOT NULL;
-- Phục vụ job ẩn danh dữ liệu khách vãng lai (ADR-0010 2.8)
CREATE INDEX idx_sales_orders_pii_pending ON sales_orders (tenant_id, completed_at)
    WHERE pii_anonymized_at IS NULL AND completed_at IS NOT NULL;

CREATE TABLE sales_order_lines (
    id                       UUID         PRIMARY KEY,
    tenant_id                UUID         NOT NULL,
    order_id                 UUID         NOT NULL,
    line_no                  INT          NOT NULL,
    title_id                 UUID         NOT NULL,
    title_name               VARCHAR(255) NOT NULL,   -- snapshot
    isbn                     VARCHAR(20),             -- snapshot
    quantity                 INT          NOT NULL CHECK (quantity > 0),
    unit_price               BIGINT       NOT NULL CHECK (unit_price >= 0),
    line_discount            BIGINT       NOT NULL DEFAULT 0 CHECK (line_discount >= 0),
    allocated_order_discount BIGINT       NOT NULL DEFAULT 0 CHECK (allocated_order_discount >= 0),
    amount                   BIGINT       NOT NULL CHECK (amount >= 0),
    vat_amount               BIGINT       NOT NULL CHECK (vat_amount >= 0),
    line_total               BIGINT       NOT NULL CHECK (line_total >= 0),
    CONSTRAINT uq_sales_order_lines_no UNIQUE (order_id, line_no),
    CONSTRAINT uq_sales_order_lines_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT fk_sales_order_lines_order FOREIGN KEY (order_id, tenant_id) REFERENCES sales_orders (id, tenant_id)
);
CREATE INDEX idx_sales_order_lines_title ON sales_order_lines (tenant_id, title_id);

CREATE TABLE payment_records (
    id          UUID         PRIMARY KEY,
    tenant_id   UUID         NOT NULL,
    order_id    UUID         NOT NULL,
    amount      BIGINT       NOT NULL CHECK (amount >= 0),
    method      VARCHAR(20)  NOT NULL CHECK (method IN ('CASH','BANK_TRANSFER','CARD','COD')),
    reference   VARCHAR(100),
    recorded_by UUID,
    recorded_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_payment_records_order FOREIGN KEY (order_id, tenant_id) REFERENCES sales_orders (id, tenant_id)
);
CREATE INDEX idx_payment_records_order ON payment_records (tenant_id, order_id);

-- ===== Nhật ký audit: chỉ chèn thêm, cùng transaction với nghiệp vụ (ADR-0010 2.6); không chứa PII =====
CREATE TABLE sales_audit_log (
    id             UUID         PRIMARY KEY,   -- = eventId
    tenant_id      UUID         NOT NULL,
    action         VARCHAR(50)  NOT NULL,
    aggregate_type VARCHAR(30)  NOT NULL,
    aggregate_id   UUID,
    actor_id       UUID,
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    data           JSONB,
    published_at   TIMESTAMPTZ                 -- NULL = chưa phát lên sales.events (chờ relay Outbox sau này)
);
CREATE INDEX idx_sales_audit_log_tenant_time ON sales_audit_log (tenant_id, occurred_at DESC);
CREATE INDEX idx_sales_audit_log_aggregate ON sales_audit_log (tenant_id, aggregate_type, aggregate_id);
CREATE INDEX idx_sales_audit_log_unpublished ON sales_audit_log (published_at) WHERE published_at IS NULL;

-- ===== Row-Level Security (fail-closed) =====
-- Mẫu chung của tenant-common: tenant_isolation theo app.tenant_id (ENABLE + FORCE), cộng policy platform_read_all
-- chỉ FOR SELECT khi app.tenant_scope = 'all' (ADR-0008). Bật RLS sau cùng.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['sales_settings', 'sales_counters', 'stock_items', 'stock_ledger', 'suppliers',
                             'goods_receipts', 'goods_receipt_lines', 'sales_orders', 'sales_order_lines',
                             'payment_records', 'sales_audit_log']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I
            USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
            WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
        EXECUTE format($p$CREATE POLICY platform_read_all ON %I FOR SELECT
            USING (current_setting('app.tenant_scope', true) = 'all')$p$, t);
    END LOOP;
END
$$;
