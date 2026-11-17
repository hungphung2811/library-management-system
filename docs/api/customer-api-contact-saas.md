# Contact của bên MUA DỊCH VỤ SaaS (thư viện/hiệu sách muốn dùng hệ thống)

> **Base URL:** `<ORIGIN>` = `http://localhost:8080` (nginx, cổng vào duy nhất). Phần A (website gửi form) dùng `<ORIGIN>/public/site/contact-requests`, **không có `/api`**. Phần B (admin xử lý) dùng `<ORIGIN>/api/cms/contact-requests/**`, cần Bearer của platform admin. Hai phần thuộc hai backend khác nhau, đừng nhầm. Xem [README.md](README.md).

Dành cho hai FE: (A) website giới thiệu SaaS, nơi bên mua dịch vụ gửi form liên hệ/đăng ký dùng thử; (B) màn hình quản trị nền tảng, nơi platform admin xử lý các yêu cầu đó. Contact của đọc giả với cửa hàng là việc khác, xem `docs/api/customer-api-contact-reader.md`. Nguồn: ADR-0011 và `docs/api/customer-api.md` (hợp đồng chung `/public/**`, là bản chuẩn nếu lệch), `docs/api/cms.md` (phía admin).

## 1. Tổng quan

- Mục đích: khách truy cập website gửi yêu cầu liên hệ hoặc đăng ký dùng thử dịch vụ SaaS quản lý thư viện/hiệu sách. Platform admin xem và xử lý ở phần B (mục 7).
- Không cần đăng nhập, không cần token, không có tenant. Gọi từ trình duyệt qua nginx (cổng 8080), không gọi thẳng cổng 8088.
- Chưa gửi email xác nhận cho người dùng; yêu cầu chỉ được lưu để quản trị xem.

## 2. Endpoint

`POST /public/site/contact-requests`

- Header: `Content-Type: application/json`. Không cần `Authorization` (nếu gửi cũng bị bỏ qua).
- Thành công: `201 Created`, không có thân phản hồi.
- Phản hồi luôn có `Cache-Control: no-store`.

### Body

| Trường | Kiểu | Bắt buộc | Quy tắc |
|---|---|---|---|
| `fullName` | string | có | Không rỗng, tối đa 100 ký tự |
| `email` | string | có | Đúng định dạng email, tối đa 254 ký tự |
| `phone` | string | không | Tối đa 20 ký tự. Bỏ trống hoặc `null` đều được |
| `organization` | string | có | Tên thư viện/hiệu sách, không rỗng, tối đa 200 ký tự |
| `businessType` | string | có | Một trong `LIBRARY`, `BOOKSTORE`, `OTHER` |
| `message` | string | có | Không rỗng, tối đa 2000 ký tự |
| `consent` | boolean | có | Phải là `true` (đồng ý cho xử lý dữ liệu liên hệ). Thiếu hoặc `false` trả `CONSENT_REQUIRED` |
| `website` | string | không | Trường bẫy chống bot (honeypot). **FE luôn để trống** và ẩn khỏi người dùng (xem mục 4) |

Ví dụ:

```json
{
  "fullName": "Nguyễn An",
  "email": "an@example.vn",
  "phone": "0900000000",
  "organization": "Thư viện Quận 1",
  "businessType": "LIBRARY",
  "message": "Tôi muốn dùng thử gói thư viện cho 3 chi nhánh.",
  "consent": true,
  "website": ""
}
```

### Phản hồi lỗi

Mọi lỗi có dạng `{ "code": "...", "message": "...", "details": [...], "timestamp": "..." }`. FE dựa vào `code`, không dựa vào `message`.

| Status | `code` | Khi nào | FE xử lý |
|---|---|---|---|
| 400 | `VALIDATION_FAILED` | Thiếu hoặc sai trường, quá dài, `businessType` lạ, email sai định dạng, JSON hỏng. `details` là mảng chuỗi dạng `"tên-trường: quy tắc"` (không kèm giá trị đã nhập). Có thể không có `details` khi lỗi đến từ service phía sau | Hiện lỗi ở trường tương ứng nếu có `details`, không thì báo chung "Vui lòng kiểm tra lại thông tin" |
| 400 | `CONSENT_REQUIRED` | `consent` thiếu hoặc `false` | Hiện lỗi ở ô đồng ý |
| 429 | (do nginx, thân có thể không phải JSON) | Gửi quá nhiều (xem mục 3) | Báo "Bạn gửi quá nhanh, vui lòng thử lại sau ít phút" và khóa nút gửi tạm thời |
| 503 | `SERVICE_UNAVAILABLE` | Hệ thống phía sau tạm lỗi | Báo "Hệ thống đang bận, thử lại sau", giữ nguyên dữ liệu đã nhập |
| 500 | `INTERNAL_ERROR` | Lỗi không lường trước | Như 503 |

Ví dụ lỗi 400:

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Dữ liệu không hợp lệ",
  "details": ["email: must be a well-formed email address", "organization: must not be blank"],
  "timestamp": "2026-11-17T09:00:00Z"
}
```

Lưu ý: chuỗi trong `details` do máy sinh, có thể bằng tiếng Anh. FE nên tự viết thông điệp tiếng Việt theo tên trường, chỉ dùng tên trường trước dấu `:`.

## 3. Giới hạn tốc độ (nginx, theo IP)

- Form liên hệ: 5 yêu cầu/phút, burst 3. Vượt quá trả `429`.
- Mọi `/public/**` nói chung: 20 yêu cầu/giây, burst 40.
- Thân yêu cầu tối đa 16 KB. Quá lớn nginx trả `413`.
- Vì vậy FE phải chặn gửi nhiều lần: khóa nút ngay khi bấm, chỉ mở lại sau khi có phản hồi.

## 4. Trường honeypot `website`

- Thêm một ô nhập tên `website` vào form nhưng ẩn bằng CSS (`position: absolute; left: -9999px`, `tabindex="-1"`, `autocomplete="off"`, `aria-hidden="true"`). Không dùng `display: none` hoặc `type="hidden"` vì một số bot bỏ qua các ô đó.
- Người thật không nhìn thấy nên luôn để trống. Bot thường điền nên bị loại.
- Nếu `website` có giá trị, server vẫn trả `201` để bot không biết bị chặn, nhưng **không lưu** yêu cầu.
- FE không báo lỗi nào liên quan đến trường này.

## 5. Gợi ý trải nghiệm

- Kiểm tra phía FE trước khi gửi theo cùng quy tắc ở mục 2 (độ dài, email, `consent`) để giảm lượt gọi lỗi. Server vẫn là nơi quyết định.
- Ô `consent` là checkbox có nội dung đồng ý xử lý dữ liệu cá nhân, kèm đường dẫn tới chính sách quyền riêng tư (nội dung do bên kinh doanh cung cấp).
- Sau `201`: hiện thông báo thành công, xóa form. Không điều hướng dựa vào dữ liệu trả về vì không có thân.
- Dữ liệu cá nhân (email, điện thoại, lời nhắn) không ghi vào log hay công cụ phân tích ở FE.
- Dữ liệu lưu 12 tháng rồi được ẩn danh (ADR-0011 O10). Nên nêu điều này trong chính sách quyền riêng tư.

## 6. Thử nhanh bằng curl

```bash
curl -i -X POST http://localhost:8080/public/site/contact-requests \
  -H 'Content-Type: application/json' \
  -d '{"fullName":"Nguyễn An","email":"an@example.vn","organization":"Thư viện X","businessType":"LIBRARY","message":"Muốn dùng thử","consent":true,"website":""}'
```

Kỳ vọng: `HTTP/1.1 201`. Cần chạy hệ thống bằng `docker compose --profile apps up -d --build` (xem README).

## 7. Phần B: platform admin xử lý yêu cầu (lms-api `/api/cms/contact-requests`)

Cho FE quản trị. Cần Bearer token của **platform admin** (token không có `tenantId`, claim `platformAdmin=true`). Tenant admin hoặc người chỉ có khóa quyền đều bị `403 FORBIDDEN`; không token là `401`. Chi tiết đầy đủ và mã lỗi ở `docs/api/cms.md`; dưới đây là phần FE cần.

| Method | Đường dẫn | Mô tả |
|---|---|---|
| GET | `/api/cms/contact-requests` | Danh sách, mới nhất trước. Query `status?` (`NEW`, `IN_PROGRESS`, `HANDLED`, `SPAM`), `from?`, `to?` (ISO-8601 instant, `from` gồm, `to` không gồm, theo ngày gửi), `page?` (từ 0), `size?` (mặc định 20, tối đa 100) |
| GET | `/api/cms/contact-requests/{id}` | Chi tiết |
| PUT | `/api/cms/contact-requests/{id}/status` | Đổi trạng thái, body `{status, version}` |
| POST | `/api/cms/contact-requests/{id}/anonymization` | Ẩn danh ngay (xóa họ tên, email, điện thoại, lời nhắn), idempotent |

- Danh sách trả `{items, page, size, total}`; mỗi phần tử: `{id, fullName, email, organization, businessType, status, createdAt, anonymizedAt}`.
- Chi tiết thêm: `phone`, `message`, `consentAt`, `version`, `handledBy`, `handledAt`.
- Chuyển trạng thái hợp lệ: `NEW` sang `IN_PROGRESS`/`HANDLED`/`SPAM`; `IN_PROGRESS` sang `NEW`/`HANDLED`/`SPAM`; `HANDLED` sang `IN_PROGRESS`; `SPAM` sang `NEW`. Chuyển sai trả `409 CONTACT_STATUS_TRANSITION_INVALID`.
- `version` lấy từ lần đọc chi tiết gần nhất; lệch thì `409 CONTACT_REQUEST_VERSION_CONFLICT`: FE tải lại rồi cho người dùng thử lại.
- Yêu cầu đã ẩn danh: các trường cá nhân là `null`, sửa trạng thái trả `409 CONTACT_REQUEST_ANONYMIZED`. Hệ thống tự ẩn danh sau 12 tháng.
- Lỗi chưa tìm thấy: `404 CONTACT_REQUEST_NOT_FOUND`.
- Màn hình nên che bớt email/điện thoại khi không cần, không ghi dữ liệu này vào công cụ phân tích.

## 8. Chưa kiểm chứng

- ⚠️ Hợp đồng viết theo code của `customer-api` và các test đơn vị/WebMvc. Chưa gọi thử end-to-end qua nginx với hệ thống chạy thật; `tester` đang kiểm.
- ⚠️ Thông điệp trong `details` là chuỗi mặc định của Bean Validation, có thể đổi (không ổn định). FE chỉ nên dựa vào tên trường.
- ⚠️ Thân của phản hồi `429` và `413` do nginx sinh, chưa xác nhận định dạng.
