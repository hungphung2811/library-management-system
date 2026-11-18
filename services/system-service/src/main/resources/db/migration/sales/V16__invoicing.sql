-- ADR-0030 goi A: hoa don VAT noi bo (context invoicing). Hoa don noi bo CHUA co gia tri phap ly cua hoa don dien tu
-- (Nghi dinh 123/2020, Thong tu 78/2021) cho toi khi tich hop nha cung cap HDDT.
-- * invoice_settings: cau hinh theo tenant (nguoi ban, mau so, ky hieu), PK = tenant_id nen luu bang JDBC nhu sales_settings.
-- * invoice_requests: yeu cau xuat hoa don cua khach/nhan vien, moi don mot yeu cau.
-- * invoices, invoice_lines: BAT BIEN sau ISSUED (trigger o cuoi file): chi cho doi sang CANCELLED (kem cot huy) va cot
--   tich hop external_*; khong xoa o bat ky trang thai nao.
-- * So hoa don lay tu sales_counters (name = 'INVOICE:<mau so>:<ky hieu>:<nam>', toi da 30 ky tu, kiem o ung dung).
-- Moi bang co tenant_id: ENABLE + FORCE ROW LEVEL SECURITY cung mau V1/V9.

-- ===== Cau hinh hoa don theo tenant =====
CREATE TABLE invoice_settings (
    tenant_id                UUID         PRIMARY KEY,
    seller_legal_name        VARCHAR(255) NOT NULL,
    seller_tax_code          VARCHAR(14)  NOT NULL,
    seller_address           VARCHAR(500) NOT NULL,
    seller_phone             VARCHAR(30),
    seller_email             VARCHAR(255),
    template_code            VARCHAR(6)   NOT NULL,
    series                   VARCHAR(8)   NOT NULL,
    auto_issue_on_completion BOOLEAN      NOT NULL DEFAULT FALSE,
    version                  BIGINT       NOT NULL DEFAULT 1,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by               UUID,
    CONSTRAINT ck_invoice_settings_tax_code CHECK (seller_tax_code ~ '^[0-9]{10}(-[0-9]{3})?$'),
    CONSTRAINT ck_invoice_settings_template CHECK (template_code ~ '^[0-9A-Za-z/]{1,6}$'),
    CONSTRAINT ck_invoice_settings_series CHECK (series ~ '^[0-9A-Za-z/]{1,8}$')
);

-- ===== Yeu cau xuat hoa don =====
CREATE TABLE invoice_requests (
    id                UUID         PRIMARY KEY,
    tenant_id         UUID         NOT NULL,
    order_id          UUID         NOT NULL,
    buyer_name        VARCHAR(255),
    buyer_company     VARCHAR(255),
    buyer_tax_code    VARCHAR(14),
    buyer_address     VARCHAR(500),
    buyer_email       VARCHAR(255),
    source            VARCHAR(10)  NOT NULL CHECK (source IN ('POS','ONLINE')),
    status            VARCHAR(10)  NOT NULL CHECK (status IN ('OPEN','FULFILLED','WITHDRAWN')),
    requested_by      UUID,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    pii_anonymized_at TIMESTAMPTZ,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_invoice_requests_order UNIQUE (tenant_id, order_id),
    CONSTRAINT uq_invoice_requests_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT fk_invoice_requests_order FOREIGN KEY (order_id, tenant_id) REFERENCES sales_orders (id, tenant_id),
    CONSTRAINT ck_invoice_requests_tax_code CHECK (buyer_tax_code IS NULL
        OR buyer_tax_code ~ '^([0-9]{10}(-[0-9]{3})?|[0-9]{12})$'),
    -- Co MST thi bat buoc ten don vi va dia chi (tru khi da an danh)
    CONSTRAINT ck_invoice_requests_company CHECK (buyer_tax_code IS NULL OR pii_anonymized_at IS NOT NULL
        OR (buyer_company IS NOT NULL AND buyer_address IS NOT NULL))
);

-- ===== Hoa don =====
CREATE TABLE invoices (
    id                   UUID         PRIMARY KEY,
    tenant_id            UUID         NOT NULL,
    kind                 VARCHAR(20)  NOT NULL CHECK (kind IN ('ORIGINAL','REPLACEMENT','ADJUSTMENT_DECREASE')),
    status               VARCHAR(10)  NOT NULL CHECK (status IN ('DRAFT','ISSUED','CANCELLED')),
    order_id             UUID         NOT NULL,
    order_no             VARCHAR(30)  NOT NULL,
    return_id            UUID,
    original_invoice_id  UUID,
    template_code        VARCHAR(6)   NOT NULL,
    series               VARCHAR(8)   NOT NULL,
    fiscal_year          INT,
    invoice_no           BIGINT,
    issued_at            TIMESTAMPTZ,
    issued_by            UUID,
    cancelled_at         TIMESTAMPTZ,
    cancelled_by         UUID,
    cancel_reason        VARCHAR(500),
    seller_legal_name    VARCHAR(255) NOT NULL,
    seller_tax_code      VARCHAR(14)  NOT NULL,
    seller_address       VARCHAR(500) NOT NULL,
    seller_phone         VARCHAR(30),
    seller_email         VARCHAR(255),
    buyer_name           VARCHAR(255),
    buyer_company        VARCHAR(255),
    buyer_tax_code       VARCHAR(14),
    buyer_address        VARCHAR(500),
    buyer_email          VARCHAR(255),
    currency             VARCHAR(3)   NOT NULL DEFAULT 'VND',
    prices_include_vat   BOOLEAN      NOT NULL,
    payment_method       VARCHAR(20),
    total_before_vat     BIGINT       NOT NULL CHECK (total_before_vat >= 0),
    vat_total            BIGINT       NOT NULL CHECK (vat_total >= 0),
    grand_total          BIGINT       NOT NULL CHECK (grand_total >= 0),
    amount_in_words      VARCHAR(600),
    issuer               VARCHAR(30)  NOT NULL DEFAULT 'INTERNAL',
    external_id          VARCHAR(100),
    external_status      VARCHAR(30),
    external_lookup_code VARCHAR(100),
    client_request_id    UUID,
    created_by           UUID,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_invoices_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT fk_invoices_order FOREIGN KEY (order_id, tenant_id) REFERENCES sales_orders (id, tenant_id),
    CONSTRAINT fk_invoices_return FOREIGN KEY (return_id, tenant_id) REFERENCES sales_returns (id, tenant_id),
    CONSTRAINT fk_invoices_original FOREIGN KEY (original_invoice_id, tenant_id) REFERENCES invoices (id, tenant_id),
    CONSTRAINT ck_invoices_totals CHECK (grand_total = total_before_vat + vat_total),
    CONSTRAINT ck_invoices_issued CHECK (status <> 'ISSUED' OR (invoice_no IS NOT NULL AND invoice_no > 0
        AND fiscal_year IS NOT NULL AND issued_at IS NOT NULL AND amount_in_words IS NOT NULL)),
    CONSTRAINT ck_invoices_cancelled CHECK (status <> 'CANCELLED' OR (cancelled_at IS NOT NULL
        AND cancel_reason IS NOT NULL)),
    CONSTRAINT ck_invoices_original_ref CHECK ((kind = 'ORIGINAL' AND original_invoice_id IS NULL)
        OR (kind <> 'ORIGINAL' AND original_invoice_id IS NOT NULL)),
    CONSTRAINT ck_invoices_return_ref CHECK (return_id IS NULL OR kind = 'ADJUSTMENT_DECREASE')
);
-- So hoa don tuan tu theo (tenant, mau so, ky hieu, nam), khong trung
CREATE UNIQUE INDEX uq_invoices_number ON invoices (tenant_id, template_code, series, fiscal_year, invoice_no)
    WHERE invoice_no IS NOT NULL;
-- Moi don toi da mot hoa don goc/thay the con hieu luc
CREATE UNIQUE INDEX uq_invoices_order_effective ON invoices (tenant_id, order_id)
    WHERE kind IN ('ORIGINAL','REPLACEMENT') AND status <> 'CANCELLED';
-- Moi phieu tra toi da mot hoa don dieu chinh con hieu luc
CREATE UNIQUE INDEX uq_invoices_return ON invoices (tenant_id, return_id)
    WHERE return_id IS NOT NULL AND status <> 'CANCELLED';
CREATE UNIQUE INDEX uq_invoices_client_request ON invoices (tenant_id, client_request_id)
    WHERE client_request_id IS NOT NULL;
-- Danh sach (loc trang thai, moi nhat truoc), tra theo don, tra dieu chinh theo hoa don goc
CREATE INDEX idx_invoices_status_created ON invoices (tenant_id, status, created_at DESC);
CREATE INDEX idx_invoices_created ON invoices (tenant_id, created_at DESC);
CREATE INDEX idx_invoices_order ON invoices (tenant_id, order_id);
CREATE INDEX idx_invoices_original ON invoices (tenant_id, original_invoice_id)
    WHERE original_invoice_id IS NOT NULL;

CREATE TABLE invoice_lines (
    id                UUID         PRIMARY KEY,
    tenant_id         UUID         NOT NULL,
    invoice_id        UUID         NOT NULL,
    line_no           INT          NOT NULL CHECK (line_no > 0),
    order_line_id     UUID,
    item_name         VARCHAR(500) NOT NULL,
    isbn              VARCHAR(30),
    unit              VARCHAR(20)  NOT NULL,
    quantity          INT          NOT NULL CHECK (quantity > 0),
    unit_price        BIGINT       NOT NULL CHECK (unit_price >= 0),
    discount          BIGINT       NOT NULL DEFAULT 0 CHECK (discount >= 0),
    amount_before_vat BIGINT       NOT NULL CHECK (amount_before_vat >= 0),
    vat_rate_bps      INT          CHECK (vat_rate_bps IS NULL OR (vat_rate_bps >= 0 AND vat_rate_bps <= 10000)),
    vat_amount        BIGINT       NOT NULL CHECK (vat_amount >= 0),
    line_total        BIGINT       NOT NULL CHECK (line_total >= 0),
    CONSTRAINT uq_invoice_lines_no UNIQUE (tenant_id, invoice_id, line_no),
    CONSTRAINT fk_invoice_lines_invoice FOREIGN KEY (invoice_id, tenant_id) REFERENCES invoices (id, tenant_id),
    CONSTRAINT fk_invoice_lines_order_line FOREIGN KEY (order_line_id, tenant_id)
        REFERENCES sales_order_lines (id, tenant_id),
    CONSTRAINT ck_invoice_lines_total CHECK (line_total = amount_before_vat + vat_amount),
    CONSTRAINT ck_invoice_lines_no_rate_no_vat CHECK (vat_rate_bps IS NOT NULL OR vat_amount = 0)
);
-- Tra dong theo dong don goc (tong dieu chinh cua tung dong)
CREATE INDEX idx_invoice_lines_order_line ON invoice_lines (tenant_id, order_line_id)
    WHERE order_line_id IS NOT NULL;

-- ===== Row-Level Security (fail-closed), cung mau V9 =====
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['invoice_settings', 'invoice_requests', 'invoices', 'invoice_lines']
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

-- ===== Bat bien sau ISSUED (cung voi rang buoc o domain) =====
-- DRAFT sua tu do. ISSUED chi doi sang CANCELLED (kem cot huy) va cot tich hop external_*. CANCELLED chi doi cot
-- external_*. Khong xoa hoa don o bat ky trang thai nao.
CREATE FUNCTION invoices_guard_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    mutable_cols text[] := ARRAY['status', 'cancelled_at', 'cancelled_by', 'cancel_reason', 'external_id',
                                 'external_status', 'external_lookup_code', 'version'];
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Khong duoc xoa hoa don (%)', OLD.id USING ERRCODE = '23514';
    END IF;
    IF OLD.status = 'DRAFT' THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'ISSUED' AND NEW.status NOT IN ('ISSUED', 'CANCELLED') THEN
        RAISE EXCEPTION 'Hoa don da phat hanh chi duoc huy (%)', OLD.id USING ERRCODE = '23514';
    END IF;
    IF OLD.status = 'CANCELLED' THEN
        IF NEW.status <> 'CANCELLED' THEN
            RAISE EXCEPTION 'Hoa don da huy khong doi trang thai (%)', OLD.id USING ERRCODE = '23514';
        END IF;
        mutable_cols := ARRAY['external_id', 'external_status', 'external_lookup_code', 'version'];
    END IF;
    IF (to_jsonb(NEW) - mutable_cols) IS DISTINCT FROM (to_jsonb(OLD) - mutable_cols) THEN
        RAISE EXCEPTION 'Hoa don da phat hanh la bat bien (%)', OLD.id USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_invoices_immutable BEFORE UPDATE OR DELETE ON invoices
    FOR EACH ROW EXECUTE FUNCTION invoices_guard_immutable();

-- Dong hoa don: khong sua/xoa khi hoa don khong con la DRAFT (dong chen cung luc voi hoa don trong mot transaction)
CREATE FUNCTION invoice_lines_guard_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    parent_status text;
BEGIN
    SELECT status INTO parent_status FROM invoices WHERE id = OLD.invoice_id AND tenant_id = OLD.tenant_id;
    IF parent_status IS NOT NULL AND parent_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'Dong hoa don da phat hanh la bat bien (%)', OLD.invoice_id USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_invoice_lines_immutable BEFORE UPDATE OR DELETE ON invoice_lines
    FOR EACH ROW EXECUTE FUNCTION invoice_lines_guard_immutable();
