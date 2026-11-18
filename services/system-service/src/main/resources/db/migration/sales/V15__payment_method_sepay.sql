-- Thanh toán SePay Test mode cho POS (ADR-0028): thêm phương thức SEPAY vào các CHECK có cột phương thức (xem V12).
ALTER TABLE sales_orders DROP CONSTRAINT IF EXISTS sales_orders_payment_method_check;
ALTER TABLE sales_orders ADD CONSTRAINT sales_orders_payment_method_check
    CHECK (payment_method IN ('CASH','BANK_TRANSFER','CARD','COD','QR','MOMO','SEPAY'));

ALTER TABLE payment_records DROP CONSTRAINT IF EXISTS payment_records_method_check;
ALTER TABLE payment_records ADD CONSTRAINT payment_records_method_check
    CHECK (method IN ('CASH','BANK_TRANSFER','CARD','COD','QR','MOMO','SEPAY'));

ALTER TABLE refunds DROP CONSTRAINT IF EXISTS refunds_method_check;
ALTER TABLE refunds ADD CONSTRAINT refunds_method_check
    CHECK (method IN ('CASH','BANK_TRANSFER','CARD','COD','QR','MOMO','SEPAY'));
