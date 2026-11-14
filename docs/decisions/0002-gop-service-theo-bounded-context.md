# ADR-0002: Gộp 11 service thành 4 service theo bounded context

- Trạng thái: chấp nhận
- Bối cảnh: spec ban đầu chia 11 microservice. Với phạm vi một hệ thống thư viện, chi phí vận hành
  (11 database, 11 Dockerfile, gọi nhau qua mạng) lớn hơn lợi ích; cây thư mục cũng sâu
  (`.../infrastructure/adapter/in/web/...`).
- Quyết định:
  - Gộp thành `identity-service` (auth, accesscontrol, user, department), `catalog-service` (catalog,
    inventory), `circulation-service` (borrowing, reservation, fine), `support-service` (notification, audit).
  - Mỗi bounded context vẫn là package gốc riêng gồm `domain` / `application` / `infrastructure`; bỏ cấp
    `adapter/in|out`, dùng `infrastructure/{web,persistence,messaging,client,security,config}`.
  - Context chỉ gọi nhau qua tầng `application` (ArchUnit ép), nên tách lại thành service riêng vẫn dễ.
  - Trong `identity-service`, `auth` lấy role/permission bằng lời gọi trong tiến trình tới
    `GetUserAccessUseCase`, bỏ REST nội bộ và khóa `X-Internal-Api-Key`.
- Đánh đổi: các context trong cùng service cùng triển khai và cùng mở rộng; một database chung
  (`identity_db` chứa bảng của `auth` và `accesscontrol`). Một lỗi nặng ở context này có thể ảnh hưởng
  context kia trong cùng tiến trình.
- Hệ quả: lớp cấu hình trùng tên phải đặt tên theo context (`AuthBeanConfiguration`,
  `AccessControlBeanConfiguration`), `@RestControllerAdvice` giới hạn bằng `basePackages`, mỗi service có
  đúng một `SecurityFilterChain`.
