# Rà soát N+1 và index: catalog-service, circulation-service, lms-api (phần gọi hai service)

Phương pháp: đọc code (repository, adapter, use case, consumer Kafka, job, adapter báo cáo, BFF) và đối chiếu với migration catalog V1..V8, circulation V1..V9. Không chạy EXPLAIN (không có DB đang chạy trong phiên này), nên mọi nhận định về index là suy luận từ truy vấn và định nghĩa index, chưa đo.

## 1. Phát hiện N+1

| # | Vị trí | Mô tả | Mức độ | Trạng thái |
|---|---|---|---|---|
| 1 | `circulation/reservation/application/ListMyReservationsUseCase.java:17` | `queuePosition` gọi từng đặt trước: mỗi dòng một `count(*)` (`SpringDataReservationRepository.countAheadOrEqual`) | Trung bình (số dòng = số đặt trước của độc giả, thường nhỏ nhưng tăng tuyến tính) | Đã sửa |
| 2 | `circulation/borrowing/application/BorrowBookUseCase.java:94-99` | Vòng lặp theo bản sao: `inventory.findById` (HTTP sang catalog) + `reservations.holderOf` (SQL) mỗi bản sao | Thấp (giới hạn bởi hạn mức mượn, vài bản sao mỗi phiếu) | Chưa sửa |
| 3 | `BorrowBookUseCase.java:108-112` | `reservations.fulfill` + `inventory.changeStatus` (HTTP) từng bản sao | Thấp, có chủ đích (bù trừ theo từng bản sao, catalog chưa có API đổi trạng thái theo lô) | Chưa sửa |
| 4 | `circulation/reservation/application/ExpireOverdueReservationsUseCase.java:34-40` | Job định kỳ: mỗi đặt trước hết hạn một lần lưu + `holdOrRelease` (đọc hàng đợi + gọi catalog) | Thấp-Trung bình (job nền, theo tenant; mỗi bản sao cần quyết định riêng) | Chưa sửa |
| 5 | `circulation/fine/application/CollectFinesUseCase.java:30-34`, `AssessFineUseCase.java:57-64` | `save` từng khoản phạt (merge entity mới dựng gây thêm một SELECT mỗi dòng), `exists` từng dòng | Thấp (tối đa vài khoản mỗi lần) | Chưa sửa |
| 6 | `catalog/catalog/infrastructure/persistence/TitlePersistenceAdapter.java:42-45` | Ghi `title_authors` từng tác giả (`save` trong vòng lặp) | Thấp (vài tác giả mỗi đầu sách, đường ghi) | Chưa sửa |

Đã kiểm tra và không có N+1:
- Danh sách đầu sách (`TitlePersistenceAdapter.toDomains`): tác giả nạp một truy vấn `IN`; `SearchTitlesUseCase` đếm bản sao khả dụng theo lô (`countByTitleIds`); `TitleClassificationService` tra NXB/thể loại/tác giả theo lô (`findByIds`).
- `ListCopiesUseCase` tra mã kệ theo lô (`findShelfLocationCodes`); `LookupCopiesUseCase`, `LookupTitlesUseCase` dùng `findByIds`/`findLookupByIds`; `GetReferenceUseCase` tra tên cha theo lô.
- `BorrowingJpaEntity.items` là `@ElementCollection(EAGER)` có `@BatchSize(100)`: danh sách/tìm kiếm phiếu nạp items theo lô. Các entity JPA khác không có `@ManyToOne`/`@OneToMany` lazy.
- Báo cáo (`LendingStatsQueryAdapter`, `CatalogStatsQueryAdapter`): SQL tổng hợp một truy vấn mỗi chỉ số. Adapter nhãn bản sao và tên người dùng (`Catalog...RestClientAdapter`, `IdentityUserNameRestClientAdapter`) gọi theo lô `MAX_BATCH`, `ExportOverdueBorrowingsCsvUseCase` tra nhãn theo trang.
- Consumer Kafka (`ReservationEventConsumer`, `InventoryEventConsumer`) xử lý mỗi message một bản sao, không lặp. `ExpireReservationsJob` lặp theo tenant (cần thiết, mỗi tenant một ngữ cảnh RLS).
- lms-api: `TitleNameEnricher` gom id (tối đa 100) rồi gọi `/internal/books/lookup` một lần; các controller catalog/circulation chuyển tiếp từng request, không ghép theo vòng lặp.

## 2. Đã sửa

- Mục 1: thêm `ReservationRepository.queuePositions(Collection<Reservation>)` (domain port), cài đặt ở `ReservationPersistenceAdapter` bằng một truy vấn `SpringDataReservationRepository.findQueueOrder` (hàng đợi WAITING của các đầu sách theo `(reserved_at, id)`, xếp hạng trong bộ nhớ). Vị trí giữ nguyên quy ước của `queuePosition` (0 nếu không WAITING); `queuePosition` đơn lẻ vẫn dùng cho `ReserveTitleUseCase`. `ListMyReservationsUseCase` gọi hàm theo lô. Hợp đồng API không đổi.
- Test: `ReservationUseCasesTest.danhSachCuaToiTinhViTriTheoLoMotLanKhongGoiTungDong` (5 đặt trước: 0 lần `queuePosition` đơn lẻ, 1 lần theo lô). Test đơn vị về thứ tự gọi port; chưa có IT đếm câu SQL bằng Hibernate Statistics (cần Docker, chưa chạy).

## 3. Chưa sửa và lý do

- Mục 2: gom `findById` thành tra lô cần dùng `/internal/book-copies/lookup`, endpoint này có khóa quyền `Controller/method` khác với `GET /internal/book-copies/{id}`; thủ thư đang mượn được qua khóa cũ có thể bị 403 sau khi đổi (đổi hành vi phân quyền). Cần quyết định riêng (gán thêm khóa cho role hoặc thêm API tra lô cùng khóa). Phần `holderOf` local có thể gom nhưng lợi ích nhỏ.
- Mục 3: cần API catalog đổi trạng thái theo lô và logic bù trừ từng phần, thay đổi lớn.
- Mục 4-6: số dòng nhỏ hoặc đường ghi nền, rủi ro đổi hành vi (event theo từng dòng, bù trừ) lớn hơn lợi ích. Có thể thêm `existsAll`/`saveAll` sau nếu đo thấy chậm.

## 4. Index

Index đã thêm (migration mới, không sửa file cũ):
- `catalog/V9__titles_sort_index.sql`: `idx_titles_tenant_id_name ON titles (tenant_id, name)`. Phục vụ `findAllByOrderByName`/`searchPage` khi không có từ khóa (`WHERE tenant_id AND ORDER BY name LIMIT`). `idx_titles_tenant_name` (V2) là `(tenant_id, lower(name))` nên không dùng được cho `ORDER BY t.name`.
- `circulation/V10__borrowings_reader_history_index.sql`: `idx_borrowings_tenant_user_borrow_date ON borrowings (tenant_id, user_id, borrow_date DESC, id)`. Phục vụ `findByUserIdOrdered` và `search` có `readerId` (sắp xếp `borrow_date DESC, id`).

Đã đối chiếu, đủ index (không thêm): `reservations` (queue, reader, active, ready copy, hold expiry đều đúng điều kiện và có `tenant_id` đứng đầu), `fines` (reader, unpaid, unique item/reason), `borrowing_items` (join theo `borrowing_id`, mở theo `(tenant_id, book_copy_id)` có điều kiện, `return_date` cho báo cáo), `borrowings` (`borrow_date`, `due_date` cho thống kê/quá hạn), `book_copies` (`title_id,status`, `tenant_id,status`, `tenant_id,shelf`, unique barcode), `authors/publishers/categories/shelf_locations` `(tenant_id, name_key|code_key)`, `title_authors` (khóa chính `(title_id, author_id)` và `(tenant_id, author_id)`), khóa ngoại `titles.publisher_id/category_id`.

Đề xuất bỏ (chưa bỏ, cần người duyệt và, nếu muốn chắc, EXPLAIN trên dữ liệu thật):
- `idx_titles_name_lower (lower(name))` và `idx_titles_author_lower (lower(author))` (catalog V1): không có `tenant_id`, và truy vấn tìm kiếm là `like '%từ khóa%'` (wildcard đầu) nên btree không dùng được; tốn chi phí ghi.
- `idx_titles_tenant_name (tenant_id, lower(name))` (catalog V2): không truy vấn nào sắp xếp/lọc theo `lower(name)` (đã có `idx_titles_tenant_id_name`).
- `idx_borrowings_tenant_user (tenant_id, user_id)` (circulation V6): là tiền tố của index mới, thừa sau V10. Giữ nếu muốn một lần chạy an toàn rồi bỏ ở đợt sau.

Đề xuất khác (không thêm vì chưa đo): tìm kiếm đầu sách theo từ khóa `%x%` trên `lower(name)`/`lower(author)` cần `pg_trgm` + GIN `(tenant_id, lower(name) gin_trgm_ops)` (cần extension, quyền tạo extension và cân nhắc dung lượng); chỉ nên làm khi một tenant có số đầu sách lớn.

Lưu ý: các index một phần `WHERE status = 'WAITING'|'READY'|'UNPAID'` chỉ được dùng nếu planner chứng minh được điều kiện; khi Hibernate gửi `status` dưới dạng tham số, generic plan có thể không dùng index một phần. Cần EXPLAIN (ANALYZE) với `plan_cache_mode` mặc định trên dữ liệu thật để xác nhận, chưa kiểm chứng.

## 5. Chưa kiểm chứng

- Không chạy EXPLAIN/đo thực tế; nhận định index là suy luận.
- Migration V9 (catalog) và V10 (circulation) chưa được chạy trên PostgreSQL trong phiên này (chạy khi system-service khởi động lại hoặc qua `POST /internal/migrations/run`); cú pháp chỉ có `CREATE INDEX IF NOT EXISTS`.
- Chưa có IT đếm câu SQL (cần Docker); cải thiện mục 1 được chứng minh bằng test đơn vị ở tầng port.
