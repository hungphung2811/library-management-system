# API quản trị CMS (lms-api -> system-service)

> **Base URL:** `<ORIGIN>` = `http://localhost:8080` (nginx). Mọi đường dẫn bắt đầu bằng `/api/cms/` và cần Bearer của platform admin. Đây là API **quản trị**, không dùng cho website công khai (website dùng `/public/site/**`). Xem [README.md](README.md).

Nguồn: ADR-0011 mục 2.3, 2.9, 2.10, 2.11, 3.4 (B-C5). FE chỉ gọi `/api/cms/**` qua nginx (8080); `lms-api` chuyển tiếp 1-1 sang `/internal/cms/**` của system-service (8086), kèm Bearer và `X-Correlation-Id`. Dùng tiền tố `/api/cms/**` (không phải `/api/platform/**`, vì tiền tố đó được `IdentityGatewayController` chuyển nguyên sang identity).

ℹ️ Đã đối chiếu và khớp hợp đồng thật của system-service (B-C3: `CmsPageAdminController`, `ContactRequestAdminController`, `CmsRequests`, `CmsResponses`). Thay đổi so với bản đầu cho FE: trường `publicationStatus` đổi thành `status`; danh sách trang trả `CmsPageSummary` (không có nội dung); xuất bản bắt buộc body `{version}`; danh sách yêu cầu liên hệ trả `ContactRequestSummary`.

## Phân quyền

`lms-api` không kiểm quyền (ADR-0005). system-service tự kiểm trên token được chuyển tiếp: hai controller admin là `@PlatformAdminOnly` (chỉ platform admin, tức token không có `tenantId` và `platformAdmin=true`). Không có khóa quyền động để gán cho role; tenant admin và người dùng thường nhận `403`. Phần công khai (`/internal/cms/public/**`) không đi qua `lms-api`, chỉ `customer-api`.

## Endpoint

| Method | Đường dẫn FE | Chuyển tiếp tới | Thành công |
|---|---|---|---|
| GET | `/api/cms/pages` | `GET /internal/cms/pages` | 200 mảng `CmsPageSummary` (không phân trang) |
| GET | `/api/cms/pages/{id}` | `GET /internal/cms/pages/{id}` | 200 `CmsPage` |
| POST | `/api/cms/pages` | `POST /internal/cms/pages` | 201 `CmsPage` + `Location: /api/cms/pages/{id}` |
| PUT | `/api/cms/pages/{id}` | `PUT /internal/cms/pages/{id}` | 200 `CmsPage` |
| POST | `/api/cms/pages/{id}/publication` | `POST /internal/cms/pages/{id}/publication` | 200 `CmsPage`; body bắt buộc `{"version":n}` (xuất bản: chép bản làm việc sang bản công khai) |
| DELETE | `/api/cms/pages/{id}/publication` | `DELETE /internal/cms/pages/{id}/publication` | 200 `CmsPage` (gỡ: xóa bản công khai, giữ bản làm việc; không body) |
| DELETE | `/api/cms/pages/{id}` | `DELETE /internal/cms/pages/{id}` | 204 |
| GET | `/api/cms/contact-requests` | `GET /internal/cms/contact-requests` | 200 `ContactRequestPage` (items là `ContactRequestSummary`) |
| GET | `/api/cms/contact-requests/{id}` | `GET /internal/cms/contact-requests/{id}` | 200 `ContactRequest` |
| PUT | `/api/cms/contact-requests/{id}/status` | `PUT /internal/cms/contact-requests/{id}/status` | 200 `ContactRequest` |
| POST | `/api/cms/contact-requests/{id}/anonymization` | `POST /internal/cms/contact-requests/{id}/anonymization` | 200 `ContactRequest` (đã ẩn danh) |

Không có token: `401`. Không phải platform admin: `403 FORBIDDEN` do system-service.

## Trang CMS

`CmsPage` (response):

```json
{"id":"uuid","slug":"gioi-thieu","title":"Giới thiệu","bodyMarkdown":"# Xin chào",
 "publishedTitle":null,"publishedBodyMarkdown":null,"publishedAt":null,
 "status":"DRAFT","sortOrder":10,"version":0,
 "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","updatedBy":null}
```

`CmsPageSummary` (phần tử của `GET /api/cms/pages`, không có nội dung):

```json
{"id":"uuid","slug":"gioi-thieu","title":"Giới thiệu","status":"DRAFT","sortOrder":10,
 "publishedAt":null,"version":0,"updatedAt":"2026-01-01T00:00:00Z"}
```

- Nội dung là Markdown thô. Mỗi trang có hai bản: bản làm việc (`title`, `bodyMarkdown`) và bản đã xuất bản (`published*`). Xuất bản chép bản làm việc sang bản công khai; sửa sau khi xuất bản không lộ ra công khai cho tới khi xuất bản lại.
- `status` (không phải `publicationStatus`): `DRAFT`, `PUBLISHED`, `PUBLISHED_WITH_CHANGES` (đã xuất bản nhưng bản làm việc khác bản công khai).
- Khách chỉ thấy HTML do system-service render từ bản đã xuất bản (HTML thô trong Markdown bị escape, link `javascript:` bị bỏ). Nội dung lồng quá sâu (khối `>`/danh sách sâu hơn 32 cấp) hoặc hơn 500 ký tự `<` thì HTML công khai là văn bản thô đã escape trong `<pre>` (không lỗi, không đổi mã trạng thái); FE admin xem trước thì tự render `bodyMarkdown` và không được tin HTML thô.

Tạo `POST /api/cms/pages`:

```json
{"slug":"gioi-thieu","title":"Giới thiệu","bodyMarkdown":"# Xin chào","sortOrder":10}
```

| Trường | Ràng buộc |
|---|---|
| `slug` | bắt buộc, `^[a-z0-9-]+$`, tối đa 80 |
| `title` | bắt buộc, tối đa 200 |
| `bodyMarkdown` | bắt buộc (được rỗng), tối đa 50000 ký tự |
| `sortOrder` | tùy chọn |

Sửa `PUT /api/cms/pages/{id}`: `{title, bodyMarkdown, sortOrder, version}`, cả bốn bắt buộc. `slug` không đổi được. `version` là khóa lạc quan; lệch thì `409 CMS_VERSION_CONFLICT`.

Xuất bản `POST /api/cms/pages/{id}/publication`: body bắt buộc `{"version":n}` (phiên bản trang mà người dùng đã xem); thiếu thì `400 VALIDATION_FAILED`, lệch thì `409 CMS_VERSION_CONFLICT`. Gỡ xuất bản (`DELETE .../publication`) và xóa trang không có thân request.

## Yêu cầu liên hệ

Chứa dữ liệu cá nhân (họ tên, email, điện thoại, lời nhắn); chỉ platform admin xem. FE không lưu/log các trường này ngoài nhu cầu hiển thị.

`ContactRequest` (response):

```json
{"id":"uuid","fullName":"An","email":"an@example.com","phone":null,"organization":"Thư viện A",
 "businessType":"LIBRARY","message":"Cần demo","consentAt":"2026-01-01T00:00:00Z",
 "status":"NEW","version":0,"createdAt":"2026-01-01T00:00:00Z",
 "handledBy":null,"handledAt":null,"anonymizedAt":null}
```

`ContactRequestSummary` (phần tử của danh sách, không có `phone`, `message`, `consentAt`, `version`, `handled*`; mở chi tiết để lấy):

```json
{"id":"uuid","fullName":"An","email":"an@example.com","organization":"Thư viện A",
 "businessType":"LIBRARY","status":"NEW","createdAt":"2026-01-01T00:00:00Z","anonymizedAt":null}
```

- `businessType`: `LIBRARY`, `BOOKSTORE`, `OTHER`. `status`: `NEW`, `IN_PROGRESS`, `HANDLED`, `SPAM`.
- Sau khi ẩn danh: `fullName`, `email`, `phone`, `message` là `null`, `anonymizedAt` có giá trị; `organization`, `businessType`, `status`, mốc thời gian được giữ. Job hằng ngày cũng tự ẩn danh yêu cầu quá 12 tháng (mọi trạng thái), nên các trường này có thể `null` mà FE chưa gọi gì.

Tìm kiếm `GET /api/cms/contact-requests`, query (đều tùy chọn, chuyển nguyên xuống service, service kiểm giá trị):

| Tham số | Ý nghĩa |
|---|---|
| `status` | một trong 4 trạng thái |
| `from`, `to` | khoảng thời gian tạo, ISO-8601 instant (ví dụ `2026-01-01T00:00:00Z`); `from` gồm, `to` không gồm |
| `page` | từ 0 (âm hoặc thiếu thì 0) |
| `size` | mặc định 20, service ép vào khoảng 1 đến 100 |

Response: `{"items":[ContactRequestSummary],"total":1,"page":0,"size":20}`.

Đổi trạng thái `PUT /api/cms/contact-requests/{id}/status`: `{"status":"HANDLED","version":0}`, cả hai bắt buộc (`status` tối đa 20). Lệch version: `409 CONTACT_REQUEST_VERSION_CONFLICT`; chuyển trạng thái không hợp lệ: `409 CONTACT_STATUS_TRANSITION_INVALID`.

Ẩn danh ngay `POST /api/cms/contact-requests/{id}/anonymization`: không thân; dùng khi người gửi đòi xóa dữ liệu. Không hoàn tác được.

## Mã lỗi

`lms-api` chuyển nguyên thân lỗi 4xx của system-service (`{code,message,details}`); 5xx thành `502`. Lỗi validate ở `lms-api` (thiếu/sai trường body): `400 VALIDATION_FAILED`; lỗi validate do system-service là `400 VALIDATION_ERROR` (hai mã khác nhau, FE xử lý cả hai).

| Mã | HTTP | Khi |
|---|---|---|
| `CMS_PAGE_NOT_FOUND` | 404 | không có trang |
| `CMS_SLUG_DUPLICATED` | 409 | slug đã có |
| `CMS_VERSION_CONFLICT` | 409 | `version` trang lệch (sửa, xuất bản) |
| `CMS_SLUG_INVALID` | 400 | slug sai định dạng (service kiểm) |
| `CMS_CONTENT_TOO_LONG` | 400 | nội dung quá giới hạn của domain |
| `CMS_PAGE_INVALID` | 400 | trang không hợp lệ theo domain (gồm: tiêu đề/nội dung chứa ký tự điều khiển như NUL; nội dung cho phép xuống dòng, tab) |
| `CONTACT_REQUEST_NOT_FOUND` | 404 | không có yêu cầu liên hệ |
| `CONTACT_REQUEST_ANONYMIZED` | 409 | sửa yêu cầu đã ẩn danh |
| `CONTACT_REQUEST_VERSION_CONFLICT` | 409 | `version` yêu cầu liên hệ lệch |
| `CONTACT_STATUS_TRANSITION_INVALID` | 409 | chuyển trạng thái không hợp lệ |
| `VALIDATION_ERROR` | 400 | body/query sai theo system-service |
| `FORBIDDEN` | 403 | không phải platform admin |
