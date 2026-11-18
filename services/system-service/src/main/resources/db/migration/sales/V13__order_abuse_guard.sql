-- Chống lạm dụng đặt hàng online (docs/security/ORDER_ABUSE_AUDIT.md, pha 1 đến 3). Chỉ thêm cột nullable và bảng mới,
-- không đổi dữ liệu hiện có; đơn cũ có hai cột mới NULL nên không bao giờ bị worker hết hạn động tới.

-- reservation_expires_at: hạn giữ hàng của đơn online đang chờ xác nhận. NULL = không hết hạn (đơn cũ, đơn POS, đơn đã xác nhận).
-- request_hash: SHA-256 của nội dung đơn khi đặt, để từ chối cùng clientRequestId mà khác nội dung.
ALTER TABLE sales_orders ADD COLUMN IF NOT EXISTS reservation_expires_at TIMESTAMPTZ;
ALTER TABLE sales_orders ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64);

-- Worker hết hạn chỉ quét đơn còn giữ hàng: index một phần giữ nhỏ và đúng điều kiện truy vấn.
CREATE INDEX IF NOT EXISTS idx_sales_orders_reservation_expiry ON sales_orders (tenant_id, reservation_expires_at)
    WHERE status = 'PENDING_CONFIRMATION' AND reservation_expires_at IS NOT NULL;

-- Chính sách chống lạm dụng theo tenant (tạo lười: chưa có dòng thì dùng giá trị mặc định trong code).
CREATE TABLE IF NOT EXISTS order_abuse_policies (
    tenant_id                  UUID        PRIMARY KEY,
    reservation_minutes        INT         NOT NULL CHECK (reservation_minutes BETWEEN 0 AND 10080),
    max_pending_orders         INT         NOT NULL CHECK (max_pending_orders BETWEEN 1 AND 1000),
    max_orders_per_day         INT         NOT NULL CHECK (max_orders_per_day BETWEEN 1 AND 10000),
    max_quantity_per_line      INT         NOT NULL CHECK (max_quantity_per_line BETWEEN 1 AND 10000),
    max_total_quantity         INT         NOT NULL CHECK (max_total_quantity BETWEEN 1 AND 100000),
    orders_per_minute_per_user INT         NOT NULL CHECK (orders_per_minute_per_user BETWEEN 1 AND 600),
    orders_per_minute_tenant   INT         NOT NULL CHECK (orders_per_minute_tenant BETWEEN 1 AND 100000),
    version                    BIGINT      NOT NULL DEFAULT 0,
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                 UUID
);

ALTER TABLE order_abuse_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_abuse_policies FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON order_abuse_policies
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY platform_read_all ON order_abuse_policies FOR SELECT
    USING (current_setting('app.tenant_scope', true) = 'all');
