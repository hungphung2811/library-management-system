# Chuyển nghiệp vụ từ dự án cũ (D:\workspace\library-manager, C# .NET) sang Java

Nguồn: `docs/requirements.md` (QĐ1–QĐ9, UC01–UC15) của dự án cũ. Code LM cũ chỉ là prototype in-memory (đầu sách, đặt trước,
quy định mượn), nên nguồn nghiệp vụ chính là tài liệu yêu cầu. Phần IAM/SSO của `Platform.*` đã có tương đương ở `identity-service`.

## Đã chuyển (lát cắt 1: đặt trước)

| Use case / quy định | Vị trí trong Java | Ghi chú |
|---|---|---|
| UC02 Tìm kiếm sách | `catalog-service` / `catalog` (`GET /api/books?keyword=&available=`) | Kèm số bản sao sẵn có lấy từ `inventory` |
| UC11 (một phần) Thêm đầu sách, nhập bản sao | `catalog` (`POST /api/books`), `inventory` (`POST /api/book-copies`) | Chưa có sửa/thanh lý |
| UC10 (một phần) Cấp thẻ, gia hạn thẻ, khóa/mở khóa | `identity-service` / `user` (`/api/users/{id}/reader-profile`) | Hồ sơ độc giả: loại SV/GV, hạn thẻ, trạng thái |
| QĐ2 (điều kiện thẻ) | `ReaderProfile.isCardValid`, cờ `cardValid` ở `GET /api/users/me/reader-profile` | Chưa gồm "không giữ sách quá hạn / không nợ phạt" (cần `borrowing` + `fine`) |
| UC03 Đặt trước, QĐ5, QĐ7 | `circulation-service` / `reservation` (`POST /api/reservations`) | FIFO theo `(reserved_at, id)`; unique index chặn trùng khi đồng thời |
| UC04 Hủy đặt trước | `DELETE /api/reservations/{id}` | Đặt trước của người khác coi như không tồn tại |
| UC05 (một phần) Xem đặt trước của tôi | `GET /api/reservations` | Kèm vị trí hàng đợi |

Quyền: `RESERVE_BOOK` (migration V6 của identity) cho `MEMBER`, `LIBRARY_ADMIN`, `SUPER_ADMIN`.
`circulation` gọi `catalog` và `identity` đồng bộ bằng chính Bearer token của độc giả; lỗi phụ thuộc trả 503 `DEPENDENCY_UNAVAILABLE`.

## Đã chuyển (lát cắt 2: giữ bản sao cho người đặt trước)

| Use case / quy định | Vị trí trong Java | Ghi chú |
|---|---|---|
| QĐ6, QĐ9 Giữ bản sao 3 ngày cho người đầu hàng đợi | `reservation`: trạng thái `READY` kèm `copyId`, `holdUntil` | Kích hoạt bởi `CopyAvailableEvent` (topic `inventory.events`) từ catalog; nhập bản sao mới là một nguồn |
| UC14 Xử lý đặt trước quá hạn | `ExpireOverdueReservationsUseCase`, job `0 5 0 * * *`, thủ công `POST /api/reservations/expirations` (cần `APPROVE_BORROWING`) | Hạn giữ là ngày cuối còn nhận; qua ngày đó mới EXPIRED, rồi giữ cho người kế tiếp hoặc trả về kho |
| Hủy đặt trước `READY` | `CancelReservationUseCase` | Bản sao đang giữ được chuyển cho người kế tiếp hoặc trả về kho |
| Đồng bộ trạng thái bản sao | `catalog` nghe `reservation.events`: `ReservationReadyEvent` thì RESERVED, `CopyReleasedEvent` thì AVAILABLE | Idempotent; nhả bản sao thì catalog phát lại `CopyAvailableEvent` để người mới vào hàng đợi được giữ ngay |

Đã chạy thật với Postgres và Kafka: nhập bản sao thì giữ cho r1 (`holdUntil` = +3 ngày) và catalog đặt RESERVED; r1 hủy thì chuyển sang r2;
r2 hủy thì catalog trả về AVAILABLE; hạn giữ quá hạn thì EXPIRED và bản sao về AVAILABLE.

Giới hạn đã biết: publish event là best-effort, chưa có Transactional Outbox (ADR-0001). Nếu bản sao bị mượn trực tiếp ngay lúc event đang đi, catalog chỉ ghi log
cảnh báo và đặt trước vẫn `READY` (cần luồng `borrowing` đánh dấu bản sao LOANED và xử lý ngoại lệ này).

## Đã chuyển (lát cắt 3: xác thực bổ sung, UC01)

| Hạng mục | Vị trí trong Java | Ghi chú |
|---|---|---|
| Đổi mật khẩu | `POST /api/auth/password` (chỉ cần token hợp lệ) | Cần đúng mật khẩu hiện tại; mật khẩu mới ≥ 8 ký tự và khác mật khẩu cũ; đổi xong thu hồi mọi refresh token |
| `must_change_password` | cột `credentials.must_change_password` (V7), claim `mustChangePassword`, trường `mustChangePassword` ở response đăng nhập | Token loại này **không mang permission nào** nên mọi endpoint kiểm quyền ở mọi service trả 403 cho tới khi đổi mật khẩu. Admin khởi tạo mặc định bị bắt đổi (tắt ở profile dev); `POST /api/auth/credentials` nhận `mustChangePassword` tùy chọn |
| Kiểm JWT ở biên | `lms-api` (`SecurityConfiguration`); nginx chặn `/.well-known/**` | Request không có token hợp lệ bị 401; JWKS không lộ ra ngoài. (Trước đây làm ở `api-gateway`, nay thay bằng nginx + `lms-api`, xem ADR-0004.) Từng service vẫn kiểm lại token và tự kiểm quyền |

## Đã chuyển (lát cắt 4: đăng nhập ngoài, mục P5)

OIDC Authorization Code + PKCE (S256) tự cài trong `identity-service` (không dùng thư viện OAuth client), nhà cung cấp là cấu hình
`auth.external.providers.<tên>` (mặc định có `google`, chỉ bật khi có `GOOGLE_CLIENT_ID`).

| Endpoint (qua nginx, công khai trừ `/me/...`) | Ghi chú |
|---|---|
| `GET /api/auth/external/providers` | `[{name, displayName}]` để vẽ nút |
| `GET /api/auth/external/{provider}/start?returnUrl=` | 302 sang IdP + cookie `lms_ext` (HttpOnly, SameSite=Lax, ký HMAC, sống 10 phút); 404 provider chưa cấu hình; 400 `returnUrl` ngoài allowlist |
| `GET /api/auth/external/{provider}/callback` | Kiểm state/PKCE/nonce/audience/issuer, rồi 302 về `returnUrl#externalCode=<mã>` hoặc `returnUrl?authError=<mã lỗi>` |
| `POST /api/auth/external/exchange {code}` | Đổi mã dùng một lần (60 giây, chỉ lưu băm) lấy cặp token. Token không bao giờ nằm trên URL |
| `GET /api/auth/me/external/{provider}/link?returnUrl=` | Cần Bearer; trả `{authorizationUrl}` (trình duyệt không gắn được header khi điều hướng) |
| `GET /api/auth/me/external`, `DELETE /api/auth/me/external/{provider}` | Danh sách và gỡ liên kết |

Quy tắc (giữ nguyên thiết kế cũ): khóa liên kết là `(provider, subject)`, không dùng email làm khóa; lần đầu chỉ tự liên kết khi email đã xác minh,
tên miền thuộc `allowed-email-domains` (rỗng = không giới hạn) và có **đúng một** hồ sơ độc giả cùng email; không tự tạo tài khoản; tài khoản khóa/vô hiệu hóa bị
chặn trước khi tạo liên kết; không đụng bộ đếm sai mật khẩu. Đã liên kết thì vào đúng người kể cả khi email ở IdP đổi.
Khác dự án cũ: tài khoản đăng nhập ngoài không bị ràng buộc `must_change_password`; vì credential luôn có mật khẩu nên không có lỗi `LAST_LOGIN_METHOD`.

Cấu hình triển khai thật (Google): tạo OAuth client (Web), đăng ký redirect URI `<AUTH_EXTERNAL_PUBLIC_BASE_URL>/external/google/callback`
(dev `http://localhost:8080/api/auth/external/google/callback`), đặt `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_ALLOWED_EMAIL_DOMAINS`,
`AUTH_EXTERNAL_ALLOWED_RETURN_ORIGINS` (origin frontend), `AUTH_EXTERNAL_STATE_SECRET` (bắt buộc đặt ở prod, dùng chung giữa các instance) và `AUTH_EXTERNAL_COOKIE_SECURE=true`.
Gợi ý frontend: nút là `window.location = /api/auth/external/<provider>/start?returnUrl=<origin>`; khi quay về, nếu có `#externalCode` thì `POST /external/exchange`,
nếu có `?authError=` thì hiện lỗi.

## Chưa chuyển (lát cắt tiếp theo)

| Hạng mục | Cần làm |
|---|---|
| Admin đặt lại mật khẩu, quên mật khẩu | Chưa có |

| Hạng mục | Cần làm |
|---|---|
| UC06 Lập phiếu mượn theo QĐ1/QĐ2 | Hạn mức và số ngày theo loại độc giả (SV 5 cuốn/14 ngày, GV 10 cuốn/30 ngày), kiểm tra QĐ2 đầy đủ, chấp nhận bản sao RESERVED của chính độc giả (QĐ4c). Hiện `borrowing` dùng hằng số cố định 14 ngày/10 cuốn. |
| UC07, UC08 Trả/báo mất sách, QĐ3, QĐ4, QĐ8 | Context `fine`: phí trễ 5.000đ/ngày, mất 150% giá bìa, hỏng 50%, quy tắc không cộng phí trễ khi mất; cần `coverPrice` từ catalog. |
| UC09 Thu phí phạt | `fine` |
| UC12 Cấu hình quy định mượn | Lưu hạn mức trong DB, mức phạt trong cấu hình (yêu cầu phi chức năng "Bảo trì") |
| UC13 Thống kê | Chưa có |
| UC15 Trợ lý giọng nói (Go + LLM) | Chưa chuyển; thiết kế ở `docs/design/2026-10-04-assistant-ai-llm.md` của dự án cũ. Chỉ nên gọi API công khai của `lms-api`. |
| Audit cho event đặt trước | Thêm topic `reservation.events` vào `AuditEventConsumer` và parser của support-service |
