# catalog-service

- Responsibility: Catalog (đầu sách, tác giả, nhà xuất bản, danh mục) và Inventory (bản sao sách, kho, vị trí kệ)
- Port: 8082 (nội bộ: API dưới `/internal/**`, FE gọi qua `lms-api`)
- Database: `catalog_db` (sở hữu riêng, không service nào khác được truy cập trực tiếp)
- Kiến trúc: DDD + Hexagonal (`domain` / `application` / `infrastructure`), xem `services/circulation-service` làm bản tham chiếu.

Trạng thái: đã có `catalog` (đầu sách, tìm kiếm UC02) và `inventory` (nhập bản sao, UC11 một phần). Còn thiếu: sửa/thanh lý bản sao, tác giả, nhà xuất bản, danh mục, đổi trạng thái bản sao qua event.
