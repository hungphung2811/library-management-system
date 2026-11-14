# catalog-service

- Responsibility: Catalog (đầu sách, tác giả, nhà xuất bản, danh mục) và Inventory (bản sao sách, kho, vị trí kệ)
- Port: 8082
- Database: `catalog_db` (sở hữu riêng, không service nào khác được truy cập trực tiếp)
- Kiến trúc: DDD + Hexagonal (`domain` / `application` / `infrastructure`), xem `services/circulation-service` làm bản tham chiếu.

Trạng thái: khung dự án, chưa có business logic.
