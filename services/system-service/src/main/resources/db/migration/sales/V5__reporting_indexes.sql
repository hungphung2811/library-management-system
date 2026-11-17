-- ADR-0018 mục 2.2: chỉ mục cho doanh thu (theo dòng tiền: paid_at, refunded_at), CSV đơn theo ngày tạo, top bán trừ trả hàng.
-- Chỉ DDL (bảng giữ RLS+FORCE).
CREATE INDEX idx_sales_orders_tenant_paid_at ON sales_orders (tenant_id, paid_at) WHERE paid_at IS NOT NULL;
CREATE INDEX idx_sales_orders_tenant_created_at ON sales_orders (tenant_id, created_at);
CREATE INDEX idx_refunds_tenant_refunded_at ON refunds (tenant_id, refunded_at) WHERE status = 'REFUNDED';
CREATE INDEX idx_sales_returns_tenant_created_at ON sales_returns (tenant_id, created_at);
