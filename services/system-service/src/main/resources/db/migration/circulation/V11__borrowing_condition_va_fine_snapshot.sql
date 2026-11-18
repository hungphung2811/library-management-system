-- Tình trạng sách và chính sách đền bù (docs/requirements/2026-11-18-tinh-trang-sach-va-den-bu.md), phần 1: cột mới.
-- Chỉ DDL, chỉ thêm cột nullable (hoặc có DEFAULT hằng) nên không ghi dữ liệu bảng RLS và không cần set_config('app.tenant_id').
-- Dòng cũ KHÔNG backfill: lượt mượn mở trước migration không có dữ liệu lúc mượn; khoản phạt cũ có payable_amount NULL
-- nghĩa là bằng amount (code đọc COALESCE(payable_amount, amount)), snapshot NULL.

-- ===== borrowing_items: tình trạng bản sao lúc mượn (bất biến sau khi lập phiếu, không có API sửa) =====
-- condition_recorded = FALSE: lượt mượn lập trước migration ("không có dữ liệu lúc mượn").
-- condition_recorded = TRUE và condition_grade NULL: bản sao chưa có tình trạng ở catalog ("chưa ghi nhận").
ALTER TABLE borrowing_items ADD COLUMN condition_grade    VARCHAR(10);
ALTER TABLE borrowing_items ADD COLUMN condition_note     VARCHAR(500);
ALTER TABLE borrowing_items ADD COLUMN condition_recorded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE borrowing_items ADD CONSTRAINT ck_borrowing_items_condition_grade
    CHECK (condition_grade IS NULL OR condition_grade IN ('NEW', 'GOOD', 'FAIR', 'WORN')) NOT VALID;
ALTER TABLE borrowing_items VALIDATE CONSTRAINT ck_borrowing_items_condition_grade;

-- ===== fines: số tiền phải thu, snapshot cách tính, ghi chú/ảnh bằng chứng, trạng thái WAIVED/REDUCED =====
-- amount (số tiền gốc) không bao giờ sửa; payable_amount là số phải thu sau miễn/giảm (NULL = amount).
ALTER TABLE fines ADD COLUMN payable_amount      NUMERIC(14,2);
ALTER TABLE fines ADD COLUMN base_price          NUMERIC(14,2);
ALTER TABLE fines ADD COLUMN price_source        VARCHAR(20);
ALTER TABLE fines ADD COLUMN price_fallback      BOOLEAN;
ALTER TABLE fines ADD COLUMN rate                NUMERIC(6,4);
ALTER TABLE fines ADD COLUMN damage_level_code   VARCHAR(30);
ALTER TABLE fines ADD COLUMN damage_level_label  VARCHAR(100);
ALTER TABLE fines ADD COLUMN amount_before_clamp NUMERIC(14,2);
ALTER TABLE fines ADD COLUMN assessment_note     VARCHAR(1000);
-- Tối đa 3 URL http/https, mỗi URL <= 500 ký tự, nối bằng xuống dòng (URL không chứa khoảng trắng). Chỉ lưu, không tải.
ALTER TABLE fines ADD COLUMN evidence_urls       VARCHAR(1600);

ALTER TABLE fines ADD CONSTRAINT ck_fines_payable
    CHECK (payable_amount IS NULL OR (payable_amount >= 0 AND payable_amount <= amount)) NOT VALID;
ALTER TABLE fines VALIDATE CONSTRAINT ck_fines_payable;
ALTER TABLE fines ADD CONSTRAINT ck_fines_price_source
    CHECK (price_source IS NULL OR price_source IN ('COVER_PRICE', 'PURCHASE_PRICE')) NOT VALID;
ALTER TABLE fines VALIDATE CONSTRAINT ck_fines_price_source;

-- Trạng thái thêm WAIVED (miễn toàn phần) và REDUCED (giảm một phần, còn phải thu). ck_fines_paid (V5) giữ nguyên:
-- chỉ PAID mới có paid_at.
ALTER TABLE fines DROP CONSTRAINT IF EXISTS fines_status_check;
ALTER TABLE fines ADD CONSTRAINT fines_status_check
    CHECK (status IN ('UNPAID', 'PAID', 'WAIVED', 'REDUCED')) NOT VALID;
ALTER TABLE fines VALIDATE CONSTRAINT fines_status_check;

-- Khoản còn phải thu (UNPAID hoặc REDUCED) của một độc giả: tổng nợ, khóa dòng khi thu, kiểm điều kiện mượn.
-- Thay idx_fines_reader_unpaid (V6, chỉ UNPAID) vì khoản REDUCED cũng còn nợ.
CREATE INDEX idx_fines_reader_collectible ON fines (tenant_id, reader_id) WHERE status IN ('UNPAID', 'REDUCED');
DROP INDEX IF EXISTS idx_fines_reader_unpaid;
-- Kiểm "bậc hỏng đã được dùng" khi xóa bậc.
CREATE INDEX idx_fines_tenant_damage_level ON fines (tenant_id, damage_level_code) WHERE damage_level_code IS NOT NULL;
