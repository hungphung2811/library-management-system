# ADR-0004: Migration tập trung trong `migration-service`

- Trạng thái: chấp nhận
- Bối cảnh: mỗi service (identity, catalog, circulation, support) tự giữ file SQL Flyway và tự chạy Flyway lúc khởi động lên DB của mình
  (support-service còn phải chạy Flyway thủ công vì có nhiều nguồn dữ liệu). Việc này khiến lịch sử migration nằm rải rác, khó xem
  "DB nào đã chạy file nào", và khởi động nhiều bản sao của cùng một service cùng lúc đều tranh nhau migrate.
- Quyết định:
  - Tạo `services/migration-service` (cổng 8086, package `lms.migration`) làm runner migration tập trung: giữ mọi file SQL ở
    `src/main/resources/db/migration/{identity,catalog,circulation,audit}/` và chạy Flyway lên `identity_db`, `catalog_db`,
    `circulation_db`, `audit_db` lúc khởi động.
  - Sổ cái là bảng `flyway_schema_history` sẵn có trong từng DB, không tạo DB riêng. API tra cứu/chạy lại:
    `GET /api/migrations`, `GET /api/migrations/{target}`, `POST /api/migrations/run`, `POST /api/migrations/{target}/run`.
  - Toàn bộ file SQL được chuyển vào migration-service; các service không còn chạy Flyway, chỉ dùng schema đã có (validate).
    IT của từng service bật Flyway ở test scope, lấy script từ jar thường của migration-service (location `classpath:db/migration/<target>`).
  - Health `/actuator/health` của migration-service chỉ UP sau khi migrate xong; trong docker compose các service chờ `service_healthy` của nó.
  - Thêm migration mới = thêm file `V<n>` vào thư mục target tương ứng của migration-service.
- Đánh đổi:
  - migration-service giữ credential owner của cả 4 DB, bề mặt rủi ro lớn hơn so với mỗi service chỉ giữ credential DB của mình.
  - Phải khởi động trước các service khác (local và compose), nếu không service sẽ gặp DB chưa có schema.
  - Ranh giới sở hữu schema lỏng hơn: file SQL của một service nằm ngoài service đó, dễ sửa nhầm hoặc đụng số phiên bản khi nhiều người cùng thêm.
  - Lịch sử Flyway giữ nguyên nên DB đang chạy không bị migrate lại, miễn là script không đổi checksum (di chuyển file mà không đổi nội dung là an toàn).
  - Không atomic xuyên DB: migrate 4 DB là 4 lần chạy độc lập, có thể thành công một phần.
  - Không có rollback (Flyway Community không hỗ trợ undo); sửa lỗi bằng migration mới.
