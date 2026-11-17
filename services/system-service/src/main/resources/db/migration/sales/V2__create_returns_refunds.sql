-- sales_db pha 2 (ADR-0010 mục 2.5, 2.7; docs/architecture/sales-module-design.md mục 3): phiếu trả hàng + dòng, hoàn tiền.
-- Cột online/giao nhận (recipient_*, confirmed_*, cancelled_*, exchange_of_return_id) đã có ở V1 nên không thêm.
-- Mọi bảng mới có tenant_id NOT NULL + RLS (ENABLE + FORCE) + tenant_isolation + platform_read_all như V1.

-- Một đơn chỉ có một bản ghi thu (thu đủ grand_total, không thu từng phần): chặn ghi trùng khi hai người đánh dấu đã thu.
CREATE UNIQUE INDEX uq_payment_records_order ON payment_records (tenant_id, order_id);

CREATE TABLE sales_returns (
    id                UUID         PRIMARY KEY,
    tenant_id         UUID         NOT NULL,
    return_no         VARCHAR(30)  NOT NULL,
    order_id          UUID         NOT NULL,
    reason            VARCHAR(500) NOT NULL CHECK (length(btrim(reason)) > 0),
    late              BOOLEAN      NOT NULL DEFAULT FALSE,
    late_reason       VARCHAR(500),
    days_overdue      INT          NOT NULL DEFAULT 0 CHECK (days_overdue >= 0),
    refund_total      BIGINT       NOT NULL CHECK (refund_total >= 0),
    client_request_id UUID         NOT NULL,
    created_by        UUID,        -- phiếu quá hạn: người cho phép
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_sales_returns_tenant_no UNIQUE (tenant_id, return_no),
    CONSTRAINT uq_sales_returns_tenant_request UNIQUE (tenant_id, client_request_id),
    CONSTRAINT uq_sales_returns_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT fk_sales_returns_order FOREIGN KEY (order_id, tenant_id) REFERENCES sales_orders (id, tenant_id),
    CONSTRAINT ck_sales_returns_late_reason CHECK (late = FALSE OR (late_reason IS NOT NULL AND length(btrim(late_reason)) > 0))
);
CREATE INDEX idx_sales_returns_order ON sales_returns (tenant_id, order_id);

CREATE TABLE sales_return_lines (
    id            UUID        PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    return_id     UUID        NOT NULL,
    order_line_id UUID        NOT NULL,
    quantity      INT         NOT NULL CHECK (quantity > 0),
    disposition   VARCHAR(10) NOT NULL CHECK (disposition IN ('RESTOCK','WRITE_OFF')),
    refund_amount BIGINT      NOT NULL CHECK (refund_amount >= 0),
    CONSTRAINT fk_sales_return_lines_return FOREIGN KEY (return_id, tenant_id) REFERENCES sales_returns (id, tenant_id),
    CONSTRAINT fk_sales_return_lines_order_line FOREIGN KEY (order_line_id, tenant_id)
        REFERENCES sales_order_lines (id, tenant_id)
);
CREATE INDEX idx_sales_return_lines_return ON sales_return_lines (tenant_id, return_id);
CREATE INDEX idx_sales_return_lines_order_line ON sales_return_lines (tenant_id, order_line_id);

-- Đổi hàng: đơn POS mới tham chiếu phiếu trả; mỗi phiếu trả chỉ được đổi một lần
ALTER TABLE sales_orders ADD CONSTRAINT fk_sales_orders_exchange_return
    FOREIGN KEY (exchange_of_return_id, tenant_id) REFERENCES sales_returns (id, tenant_id);
CREATE UNIQUE INDEX uq_sales_orders_exchange ON sales_orders (tenant_id, exchange_of_return_id)
    WHERE exchange_of_return_id IS NOT NULL;

CREATE TABLE refunds (
    id          UUID         PRIMARY KEY,
    tenant_id   UUID         NOT NULL,
    order_id    UUID         NOT NULL,
    source      VARCHAR(15)  NOT NULL CHECK (source IN ('CANCELLATION','RETURN')),
    return_id   UUID,
    amount      BIGINT       NOT NULL CHECK (amount > 0),
    status      VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','REFUNDED')),
    method      VARCHAR(20)  CHECK (method IN ('CASH','BANK_TRANSFER','CARD','COD')),
    reference   VARCHAR(100),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    refunded_by UUID,
    refunded_at TIMESTAMPTZ,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_refunds_order FOREIGN KEY (order_id, tenant_id) REFERENCES sales_orders (id, tenant_id),
    CONSTRAINT fk_refunds_return FOREIGN KEY (return_id, tenant_id) REFERENCES sales_returns (id, tenant_id),
    CONSTRAINT ck_refunds_source_return CHECK ((source = 'RETURN') = (return_id IS NOT NULL)),
    CONSTRAINT ck_refunds_done CHECK (status = 'PENDING' OR (method IS NOT NULL AND refunded_at IS NOT NULL))
);
CREATE INDEX idx_refunds_tenant_status ON refunds (tenant_id, status, created_at DESC);
CREATE INDEX idx_refunds_order ON refunds (tenant_id, order_id);
-- Mỗi đơn chỉ một khoản hoàn do hủy; mỗi phiếu trả chỉ một khoản hoàn
CREATE UNIQUE INDEX uq_refunds_cancellation ON refunds (tenant_id, order_id) WHERE source = 'CANCELLATION';
CREATE UNIQUE INDEX uq_refunds_return ON refunds (tenant_id, return_id) WHERE return_id IS NOT NULL;

-- ===== Row-Level Security (fail-closed), cùng mẫu V1 =====
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['sales_returns', 'sales_return_lines', 'refunds']
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
