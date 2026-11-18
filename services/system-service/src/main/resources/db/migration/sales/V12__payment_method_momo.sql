-- Thanh toán MoMo sandbox cho POS: thêm phương thức MOMO vào các CHECK có cột phương thức (xem V9).
ALTER TABLE sales_orders DROP CONSTRAINT IF EXISTS sales_orders_payment_method_check;
ALTER TABLE sales_orders ADD CONSTRAINT sales_orders_payment_method_check
    CHECK (payment_method IN ('CASH','BANK_TRANSFER','CARD','COD','QR','MOMO'));

ALTER TABLE payment_records DROP CONSTRAINT IF EXISTS payment_records_method_check;
ALTER TABLE payment_records ADD CONSTRAINT payment_records_method_check
    CHECK (method IN ('CASH','BANK_TRANSFER','CARD','COD','QR','MOMO'));

ALTER TABLE refunds DROP CONSTRAINT IF EXISTS refunds_method_check;
ALTER TABLE refunds ADD CONSTRAINT refunds_method_check
    CHECK (method IN ('CASH','BANK_TRANSFER','CARD','COD','QR','MOMO'));
