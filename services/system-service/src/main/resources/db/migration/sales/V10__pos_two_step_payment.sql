-- ADR-0021: POS hai bước cho mọi phương thức thanh toán. Lệnh thanh toán MANUAL (thu tay) cần lưu tiền khách đưa, tiền
-- thối và mã giao dịch (thẻ/chuyển khoản) do thu ngân nhập; đơn đổi hàng chờ thanh toán có thể bị hủy nên phiếu trả
-- chỉ bị "dùng" bởi đơn đổi chưa hủy. Chỉ DDL.
ALTER TABLE payment_intents
    ADD COLUMN tendered_amount BIGINT,
    ADD COLUMN change_due      BIGINT,
    ADD COLUMN transaction_ref VARCHAR(64),
    ADD CONSTRAINT ck_payment_intents_tendered CHECK (tendered_amount IS NULL OR tendered_amount >= 0),
    ADD CONSTRAINT ck_payment_intents_change CHECK (change_due IS NULL OR change_due >= 0);

-- Mỗi phiếu trả chỉ được đổi một lần, nhưng đơn đổi bị hủy (hết hạn thanh toán, thu ngân hủy) trả lại phiếu để đổi lại
DROP INDEX IF EXISTS uq_sales_orders_exchange;
CREATE UNIQUE INDEX uq_sales_orders_exchange ON sales_orders (tenant_id, exchange_of_return_id)
    WHERE exchange_of_return_id IS NOT NULL AND status <> 'CANCELLED';
