# support-service

- Responsibility: Notification (email, nhắc hạn trả) và Audit (nhật ký hoạt động)
- Port: 8084
- Database: `support_db` (sở hữu riêng, không service nào khác được truy cập trực tiếp)
- Kiến trúc: DDD + Hexagonal (`domain` / `application` / `infrastructure`), xem `services/circulation-service` làm bản tham chiếu.

Trạng thái: khung dự án, chưa có business logic.
