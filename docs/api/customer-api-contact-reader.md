# Contact của ĐỌC GIẢ gửi cửa hàng (người mua bán sách bình thường)

> **Base URL:** `<ORIGIN>` = `http://localhost:8080` (nginx). Phần công khai dùng `<ORIGIN>/public/t/{code}/...` (không có `/api`); phần nhân viên dùng `<ORIGIN>/api/...`. ✅ Phần đã chốt theo ADR-0015 **đã có code** (customer-api `POST /public/t/{code}/contact-requests`, sales-service, lms-api `/api/sales/contact-requests/**`): hợp đồng chính thức ở `customer-api.md` và `sales.md`. Các mục bên dưới là bản nháp cũ, chỉ để tham khảo. Xem [README.md](README.md).

ℹ️ (2026-11-17) Đã chốt thiết kế ở `docs/decisions/0015-lien-he-doc-gia-voi-cua-hang.md`: đường dẫn nhân viên là `/api/sales/contact-requests/**` (không phải `/api/shop-contacts`), trạng thái `NEW`/`IN_PROGRESS`/`CLOSED`, chưa có `orderId`, thêm mã `429 SHOP_CONTACT_LIMIT_EXCEEDED`. Khi lệch, `customer-api.md`, `sales.md` và ADR-0015 thắng (bản nháp này KHÔNG dùng `/api/shop-contacts`, trạng thái `HANDLED`/`SPAM`).

⚠️ **ĐỀ XUẤT, CHƯA CÓ TRONG ADR-0011 HAY ĐỀ BÀI, CHƯA CÓ CODE.** Tài liệu này là bản nháp hợp đồng để FE storefront chuẩn bị và để `ba`/`sa` chốt. FE chưa nên làm thật cho tới khi mục 7 (câu hỏi mở) được chốt và `docs/api/customer-api.md` ghi các endpoint này. Phần contact của bên mua dịch vụ SaaS là việc khác: `docs/api/customer-api-contact-saas.md`.

## 1. Khác gì so với contact của bên mua dịch vụ SaaS

| | Bên mua dịch vụ SaaS | Đọc giả với cửa hàng |
|---|---|---|
| Gửi tới | Platform admin của SaaS | Nhân viên cửa hàng (thuộc một tenant) |
| Phạm vi dữ liệu | Toàn nền tảng, không tenant | Thuộc đúng một tenant, tách theo RLS |
| Đường dẫn | `/public/site/contact-requests` | `/public/t/{code}/contact-requests` (đề xuất) |
| Trạng thái | Đã làm (C0) | Chưa làm |

## 2. Quy tắc nghiệp vụ đề xuất

- Chỉ phục vụ tenant ACTIVE có bật module bán sách; mọi trường hợp khác trả `404 SHOP_NOT_AVAILABLE` (một thân cố định, chống dò tenant, cùng quy tắc ADR-0011 O5).
- ✅ Đã chốt: **bắt buộc đăng nhập**. Khách vãng lai chỉ được xem đầu sách và đọc preview (xem `docs/api/customer-api.md`), không gửi được liên hệ. Dùng token người dùng của tenant, không dùng token dịch vụ `svc`.
- Có thể gắn với một đơn hàng bằng `orderId` (tùy chọn, đơn phải thuộc về người đang đăng nhập, để nhân viên biết đang hỏi về đơn nào). Phụ thuộc sales-service (ADR-0010) nên chỉ mở ở giai đoạn C2.
- Chống lạm dụng: giới hạn tốc độ nginx cùng vùng `public_form` (5 yêu cầu/phút/IP), thân tối đa 16 KB. Vì đã đăng nhập nên không cần honeypot.
- `customer-api` kiểm token khớp tenant của `{code}` (`ShopSessionGuard`, `403 SHOP_SESSION_MISMATCH`); sales-service/nơi lưu mới là nơi kiểm khóa quyền.
- Không có phản hồi qua email ở giai đoạn này (hệ thống chưa có kênh gửi email). Nhân viên xử lý trong màn hình quản trị, khách được liên hệ lại bằng thông tin họ cung cấp.
- Dữ liệu cá nhân giữ 12 tháng rồi ẩn danh, theo cùng chính sách ADR-0011 O10 (cần xác nhận, Q4).

## 3. API công khai (đề xuất)

`POST /public/t/{code}/contact-requests`

- `code`: mã tenant, khớp `^[a-z0-9-]{2,50}$`.
- Header: `Content-Type: application/json`, `Authorization: Bearer <accessToken>` (bắt buộc, token đăng nhập từ `POST /public/t/{code}/auth/login`).
- Thành công: `201 Created`, không thân. `Cache-Control: no-store`.

### Body

| Trường | Kiểu | Bắt buộc | Quy tắc |
|---|---|---|---|
| `fullName` | string | có | Tối đa 100 ký tự |
| `email` | string | có | Đúng định dạng, tối đa 254 |
| `phone` | string | không | Tối đa 20 |
| `topic` | string | có | `ORDER`, `PRODUCT`, `PAYMENT`, `OTHER` |
| `orderId` | string (UUID) | không | Đơn phải thuộc người đang đăng nhập; sai thì `400 VALIDATION_FAILED` |
| `message` | string | có | Tối đa 2000 |
| `consent` | boolean | có | Phải là `true`, không thì `CONSENT_REQUIRED` |

```json
{
  "fullName": "Trần Bình", "email": "binh@example.vn", "phone": "0911111111",
  "topic": "ORDER", "message": "Đơn của tôi chưa được xác nhận thanh toán.",
  "consent": true
}
```

### Mã lỗi (cùng bảng chung ở `docs/api/customer-api.md`)

| Status | `code` | Khi nào |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Sai/thiếu trường, quá dài, `topic` lạ, `orderId` không hợp lệ |
| 400 | `CONSENT_REQUIRED` | Chưa đồng ý xử lý dữ liệu |
| 401 | `UNAUTHORIZED` | Không có token hoặc token hết hạn: FE điều hướng sang đăng nhập (khách vãng lai gặp mã này) |
| 403 | `SHOP_SESSION_MISMATCH` | Token thuộc tenant khác với `{code}` |
| 404 | `SHOP_NOT_AVAILABLE` | Tenant không khả dụng, không bật bán sách, hoặc không tồn tại |
| 429 | (nginx) | Vượt giới hạn tốc độ |
| 503 | `SERVICE_UNAVAILABLE` | Service phía sau lỗi |

## 4. API xử lý của nhân viên cửa hàng (đề xuất, cần chốt nơi đặt)

Đi qua `lms-api` `/api/**`, cần Bearer token của người dùng tenant, quyền theo khóa động `Controller/method` (ADR-0003), nên role nhân viên phải được gán khóa này. Tên controller/method chưa chốt nên khóa chưa chốt.

| Method | Đường dẫn (đề xuất) | Mô tả |
|---|---|---|
| GET | `/api/shop-contacts` | Danh sách, query `status?`, `topic?`, `from?`, `to?`, `page?`, `size?` |
| GET | `/api/shop-contacts/{id}` | Chi tiết |
| PUT | `/api/shop-contacts/{id}/status` | Đổi trạng thái `{status, version}` |
| POST | `/api/shop-contacts/{id}/anonymization` | Ẩn danh ngay |

Trạng thái và quy tắc chuyển giống phía SaaS: `NEW`, `IN_PROGRESS`, `HANDLED`, `SPAM`. Nhân viên chỉ thấy yêu cầu của tenant mình (RLS), platform admin không thấy nội dung.

## 5. Gợi ý trải nghiệm FE

- Đặt form trong trang storefront của tenant (`/t/{code}/...`), lấy `code` từ đường dẫn, không để người dùng tự nhập.
- Khách chưa đăng nhập: ẩn form, hiện lời mời "Đăng nhập để liên hệ cửa hàng" dẫn tới đăng nhập/đăng ký; không gọi API khi chưa có token.
- Điền sẵn tên/email từ tài khoản và cho chọn đơn để hỏi (khi C2 xong).
- Khóa nút gửi khi đang gửi; xử lý 429/503 như file SaaS (mục 2 và 3).
- Không ghi dữ liệu cá nhân vào log hay công cụ phân tích ở FE.

## 6. Ảnh hưởng tới thiết kế (để sa/ba cân nhắc)

- `customer-api` không có DB, nên cần nơi lưu có `tenant_id` + RLS (ADR-0006). ✅ Đã chọn `sales-service` (cùng DB bán hàng, gắn được với đơn). Đã loại `support-service`/`audit-service` (luồng sinh từ event, không có dữ liệu người dùng nhập). Không dùng `system-service` `cms` vì bảng đó cố ý không có tenant (ADR-0011 O3).
- Dùng token người dùng của tenant (đã chốt bắt buộc đăng nhập), không dùng token dịch vụ `svc`; endpoint nhận không gắn `@StorefrontAccessible`.
- Cần thêm khóa/role cho nhân viên và có thể thêm mục vào ADR-0010 hoặc ADR mới.
- Thêm luật nginx cho đường dẫn mới và mã lỗi mới vào danh sách chuyển tiếp của `PublicExceptionHandler`.

## 7. Câu hỏi mở (cần người dùng quyết)

- ✅ **Q1** đã chốt (2026-11-17): lưu ở `sales-service`. Cần `sa` ghi vào ADR-0010/ADR-0011 (bảng có `tenant_id` + RLS, API nhân viên trong `/api/sales/**` thay vì `/api/shop-contacts`, khóa quyền do `SalesController/method` sinh ra) và `ba` bổ sung đề bài trước khi `be-dev` làm. Đường dẫn ở mục 4 là tạm, sẽ đổi theo ADR.
- ✅ **Q2** đã chốt: bắt buộc đăng nhập (vãng lai chỉ xem đầu sách và preview).
- **Q3.** Có cần trả lời lại trong hệ thống (luồng hội thoại) hay chỉ ghi nhận rồi nhân viên liên hệ ngoài hệ thống? (khuyến nghị giai đoạn này chỉ ghi nhận.)
- **Q4.** Thời hạn giữ và ẩn danh: dùng 12 tháng như form SaaS?
- **Q5.** Có thông báo cho nhân viên khi có liên hệ mới (qua `support-service`/notification) không?

## 8. Chưa kiểm chứng

- ⚠️ Toàn bộ nội dung là đề xuất, không có code, không có test, chưa qua `sa`/`ba`.
- ⚠️ Đường dẫn, tên trường, mã lỗi và khóa quyền có thể đổi sau khi chốt.
