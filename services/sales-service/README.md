# sales-service

- Responsibility: bán hàng của hiệu sách (module SALES, ADR-0009/0010): mặt hàng bán và tồn kho, nhập kho, đơn POS/online, thanh toán, trả hàng/hoàn tiền, cấu hình bán hàng theo tenant, nhật ký audit trong DB
- Port: 8087 (nội bộ: API dưới `/internal/sales/**`, FE gọi qua `lms-api`)
- Database: `sales_db` (vai trò `sales_app`, RLS theo tenant). Migration do `system-service` chạy (target `sales`, `db/migration/sales/`)
- Kiến trúc: `domain` / `application` / `infrastructure` (xem `docs/architecture/sales-module-design.md`)

Trạng thái: khung dịch vụ (B1.1) cùng schema `V1`. Chưa có controller nghiệp vụ.
