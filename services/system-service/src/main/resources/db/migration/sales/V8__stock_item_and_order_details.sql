-- Làm giàu schema bán hàng: thông tin quản lý mặt hàng (SKU, nhà cung cấp, giá nhập, vị trí kho...) và ghi chú/ngày giao
-- dự kiến của đơn. Mọi cột tùy chọn (NULL), không backfill. Chỉ DDL: không ghi dữ liệu nên không cần app.tenant_id;
-- RLS/policy của stock_items và sales_orders giữ nguyên (cột mới nằm trong cùng dòng, cùng chính sách).
-- Dữ liệu nhạy cảm: cost_price, note (mặt hàng) và staff_note (đơn) CHỈ trả qua endpoint khóa riêng của nhân viên,
-- không bao giờ ra storefront/API khách; ghi chú đơn không ghi vào log, audit hay event.

ALTER TABLE stock_items
    ADD COLUMN sku                VARCHAR(40),
    ADD COLUMN supplier           VARCHAR(255),
    ADD COLUMN cost_price         BIGINT,
    ADD COLUMN bin_location       VARCHAR(64),
    ADD COLUMN reorder_quantity   INT,
    ADD COLUMN last_restocked_at  TIMESTAMPTZ,
    ADD COLUMN note               VARCHAR(500),
    ADD CONSTRAINT ck_stock_items_cost_price CHECK (cost_price IS NULL OR cost_price >= 0),
    ADD CONSTRAINT ck_stock_items_reorder_quantity CHECK (reorder_quantity IS NULL OR reorder_quantity >= 0),
    ADD CONSTRAINT ck_stock_items_sku_not_blank CHECK (sku IS NULL OR btrim(sku) <> '');

-- SKU duy nhất theo tenant khi có (ứng dụng chuẩn hóa: cắt khoảng trắng, chữ hoa).
CREATE UNIQUE INDEX uq_stock_items_tenant_sku ON stock_items (tenant_id, sku) WHERE sku IS NOT NULL;

ALTER TABLE sales_orders
    ADD COLUMN customer_note           VARCHAR(500),
    ADD COLUMN staff_note              VARCHAR(500),
    ADD COLUMN expected_delivery_date  DATE,
    ADD CONSTRAINT ck_sales_orders_expected_delivery CHECK (expected_delivery_date IS NULL
        OR fulfilment IN ('PICKUP', 'DELIVERY'));
