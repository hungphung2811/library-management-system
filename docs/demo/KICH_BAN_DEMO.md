# Kịch bản demo hệ thống quản lý thư viện và hiệu sách (từ đầu đến cuối)

Thời lượng khoảng 25 phút. Dữ liệu: tenant `demo` (nạp bằng `scripts\seed-demo.ps1`). Mật khẩu chung `Demo@12345` (platform admin `Platform@12345`). Kịch bản này có bản chạy tự động: `scripts/demo-e2e.sh` (xem mục "Chạy tự động").

## 0. Chuẩn bị (5 phút trước buổi demo)

1. `docker compose up -d` (Postgres, Kafka, Redis, Mongo) và `docker compose up -d mailpit` (hộp thư giả `http://localhost:8025`).
2. `scripts\dev-start.ps1`, rồi chạy riêng sales (8087) và customer-api (8088) với `STOREFRONT_CLIENT_SECRET` giống nhau cho identity và customer-api (xem CLAUDE.md).
3. FE: `http://localhost:3000` (proxy tới lms-api 8085).
4. Mở sẵn 2 trình duyệt ẩn danh: một cho nhân viên, một cho khách.

## 1. Mở đầu: nền tảng SaaS cho thuê (platform admin, 3 phút)

- Đăng nhập `platform.admin`. Trang Tổng quan hiện dashboard nền tảng: số đơn vị, trạng thái, module đang dùng, hợp đồng sắp hết hạn.
- Ý chính: một nền tảng, nhiều đơn vị thuê (multi-tenant), dữ liệu cách ly bằng Row-Level Security.
- Chọn đơn vị `demo` ở thanh trên: platform admin xem và thao tác thay đơn vị (có ghi log kiểm toán).

## 2. Quản trị đơn vị (admin tenant, 4 phút)

- Đăng nhập `demo.admin`. Dashboard: thẻ KPI, biểu đồ mượn trả, doanh thu, tình trạng bản sao, top sách.
- Menu động và phân quyền động theo khóa `Controller/method`: mở Quản trị, Vai trò và quyền.
- Mở "Chính sách chống lạm dụng đặt hàng" (API `order-abuse-policy`): hạn giữ hàng 15 phút, trần đơn chờ, tốc độ đặt.
- Xuất báo cáo CSV (đơn hàng, tồn kho, phiếu quá hạn).

## 3. Thư viện: mượn trả (thủ thư, 5 phút)

- Đăng nhập `demo.thuthu`. Tìm đầu sách, chọn bản sao sẵn có.
- Lập phiếu mượn cho độc giả `demo.docgia01`.
- Độc giả xem phiếu của mình (`/api/borrowings/mine`), thủ thư nhận trả sách, kiểm tra trạng thái bản sao quay về sẵn có.

## 4. Hiệu sách: bán tại quầy (thu ngân, 3 phút)

- Đăng nhập `demo.cuahang`. Tạo đơn POS tiền mặt cho một mặt hàng.
- Kiểm tra tồn kho giảm đúng, và sổ cái tồn kho ghi biến động.

## 5. Khách mua online (storefront, 7 phút)

1. Khách mở cửa hàng `demo` (customer-api): xem danh mục sách đang bán.
2. Đăng ký tài khoản mới. Hệ thống gửi OTP 6 chữ số qua email (xem trong Mailpit).
3. Đăng nhập trước khi xác minh: bị `403 EMAIL_NOT_VERIFIED`. Nhập OTP sai: bị từ chối; nhập đúng: xác minh xong, đăng nhập được.
4. Đặt đơn: tồn kho được giữ ngay (reserved) và đơn có hạn giữ hàng.
5. Chống lạm dụng (điểm nhấn):
   - Gửi lại cùng mã yêu cầu cùng nội dung: trả đơn cũ, không giữ hàng hai lần.
   - Cùng mã yêu cầu nhưng đổi số lượng: `422 IDEMPOTENCY_KEY_REUSED`.
   - Đặt số lượng vượt trần: `400 INVALID_QUANTITY`.
   - Đặt dồn dập: `429 ORDER_RATE_LIMITED` hoặc `ORDER_LIMIT_EXCEEDED`.
6. Nhân viên xác nhận một đơn (hết hạn giữ hàng), một đơn khác để quá hạn: job tự hủy và nhả tồn.

## 6. Phiên đăng nhập an toàn (2 phút)

- Access token 15 phút, refresh token xoay vòng mỗi lần dùng. Dùng lại token cũ bị từ chối (chống đánh cắp phiên).

## 7. Kết (1 phút)

Tóm tắt: multi-tenant, phân quyền động, dashboard, chống lạm dụng đặt hàng, xác minh email.

## Chạy tự động

```bash
bash scripts/demo-e2e.sh            # chạy đủ kịch bản, in ✅/❌ từng bước
SKIP_EXPIRY=1 bash scripts/demo-e2e.sh   # bỏ bước chờ hết hạn giữ hàng (~2 phút)
```

Yêu cầu: các dịch vụ và Mailpit đang chạy (mục 0), tenant `demo` đã seed. Script tạo vài tài khoản khách `demo-e2e-*` và đơn thử trong tenant `demo`.
