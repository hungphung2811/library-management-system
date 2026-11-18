-- Rà soát index (docs/architecture/ra-soat-n-plus-1-va-index-sales-identity-support.md). Chỉ DDL, bảng giữ RLS+FORCE;
-- tenant_id luôn đứng đầu, rồi cột lọc "=", rồi cột ORDER BY.

-- GoodsReceiptPersistenceAdapter.search: where tenant_id = :t [and status = :status] [and supplier_id = :supplier]
-- order by created_at desc, id desc. Trước đây chỉ có (tenant_id, created_at DESC): lọc DRAFT (ít) hay lọc theo nhà cung cấp
-- phải duyệt ngược toàn bộ phiếu của tenant. Khóa ngoại (supplier_id, tenant_id) cũng chưa có index dẫn đầu bằng cột đó.
CREATE INDEX idx_goods_receipts_tenant_status_created ON goods_receipts (tenant_id, status, created_at DESC);
CREATE INDEX idx_goods_receipts_tenant_supplier_created ON goods_receipts (tenant_id, supplier_id, created_at DESC);

-- WalkInAnonymizationAdapter.findDue (job ẩn danh khách vãng lai, chạy định kỳ cho từng tenant): nhánh
-- status = 'CANCELLED' and cancelled_at < :cutoff chưa có index (idx_sales_orders_pii_pending chỉ phủ completed_at).
-- Index riêng, một phần (partial) nên chỉ chứa đơn huỷ chưa ẩn danh, nhỏ dần khi job chạy.
CREATE INDEX idx_sales_orders_pii_pending_cancelled ON sales_orders (tenant_id, cancelled_at)
    WHERE pii_anonymized_at IS NULL AND cancelled_at IS NOT NULL;
