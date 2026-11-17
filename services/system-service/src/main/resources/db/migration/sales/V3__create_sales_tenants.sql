-- sales_db pha 2, B2.5 (ADR-0010 mục 2.8): danh bạ tenant cho job ẩn danh dữ liệu khách vãng lai.
-- KHÔNG RLS, KHÔNG tenant_id theo kiểu bảng nghiệp vụ: chỉ job ẩn danh đọc, ghi "chèn nếu chưa có" trong transaction tạo
-- đơn đầu tiên của tenant. Chỉ chứa mã tenant và thời điểm thấy lần đầu, không dữ liệu cá nhân.
CREATE TABLE sales_tenants (
    tenant_id     UUID        PRIMARY KEY,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Tenant đã có đơn từ pha 1 (POS) nhưng chưa có dòng danh bạ: bổ sung. Đọc xuyên tenant bằng policy platform_read_all
-- (app.tenant_scope = 'all', chỉ trong transaction của migration).
SELECT set_config('app.tenant_scope', 'all', true);
INSERT INTO sales_tenants (tenant_id, first_seen_at)
SELECT tenant_id, min(created_at) FROM sales_orders GROUP BY tenant_id
ON CONFLICT (tenant_id) DO NOTHING;
