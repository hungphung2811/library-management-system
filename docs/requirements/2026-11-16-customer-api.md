# Đề bài: `customer-api`, cổng công khai cho khách (trang giới thiệu SaaS và storefront mua sách)

Ngày: 2026-11-16. Người viết: BA. Người nhận: `sa`, `be-dev`, `tester`.
Đề bài liên quan: `docs/requirements/2026-11-16-hieu-sach-module-ban-hang.md`. ADR liên quan: 0003, 0005, 0006, 0007, 0009, 0010, 0011. Tài liệu liên quan: `docs/api/customer-api-contact-reader.md`.

## 0. Thay đổi so với bản trước (cập nhật 2026-11-17)

- ✅ Khách vãng lai chỉ được: xem danh sách/chi tiết đầu sách (tên, tác giả, giá bán, còn hàng/hết hàng) và đọc **preview** một đoạn ngắn. Không đặt hàng, không gửi liên hệ, không xem gì thêm. Mọi thao tác khác bắt buộc đăng nhập (mục 3, 4.7, 5).
- ✅ Thêm tính năng preview: nguồn là văn bản do nhân viên tenant nhập cho đầu sách ở catalog, kèm mô tả ngắn; có migration catalog và API nhập qua `lms-api` (mục 4.7, 6.1, 6.3, 6.5).
- ✅ Liên hệ đọc giả với cửa hàng: bắt buộc đăng nhập, không honeypot (chi tiết ở `docs/api/customer-api-contact-reader.md`). Form SaaS ở 4.4 (gửi tới platform admin) là việc khác, vẫn công khai như cũ.
- 🚫 Loại tải file PDF/EPUB và cắt trang (mục 3.2).
- ⏳ Danh sách điểm `sa` phải bổ sung vào ADR-0011: mục 7.1.
- ⚠️ Mâu thuẫn cần `sa` xử lý: bản trước và ADR-0011 mô tả DTO sách không có mô tả/preview và O8 chỉ nói về ảnh bìa; các mục đó nay lỗi thời (xem 7.1).

## 1. Mục tiêu

Mở một cổng công khai (không phải nhân viên) cho hai lớp người dùng, qua một BFF thứ hai tách khỏi `lms-api`:

- Lớp 1, trang giới thiệu SaaS công khai: bên có nhu cầu thuê (thư viện/hiệu sách) xem nội dung giới thiệu, gửi form liên hệ/đăng ký dùng thử.
- Lớp 2, storefront đọc giả theo từng tenant: khách vãng lai xem danh mục sách bán và đọc preview ngắn; khi đăng nhập thì đặt hàng, xem đơn của mình, liên hệ cửa hàng.
- Nguyên tắc: khách vãng lai chỉ được xem và đọc preview; mọi thao tác còn lại bắt buộc đăng nhập.

Giai đoạn 1 chỉ "mua sách", dùng lại `sales-service` (ADR-0010). `customer-api` không giữ nghiệp vụ tiền/tồn kho.

## 2. Bối cảnh (đã đối chiếu tài liệu)

- ℹ️ FE hiện chỉ vào qua nginx 8080 rồi `lms-api` 8085 (`/api/**` → `/internal/**`), ADR-0005. `lms-api` dành cho nhân viên/quản trị.
- ℹ️ `sales-service` (8087) có `/internal/**` cho đơn online của khách (ADR-0010 gọi là nhóm `/api/shop/**` ở BFF), kiểm sở hữu theo `userId` trong token; khách tự đăng ký qua `POST /internal/auth/signup` (cờ `signup_default`, mã chung `SIGNUP_NOT_AVAILABLE`).
- ℹ️ Module theo tenant: claim `modules`, `@RequiresModule` (ADR-0009). Endpoint `@PublicEndpoint` không có token nên interceptor không kiểm module; phải do use case/BFF kiểm.
- ℹ️ Gọi chéo service hiện chỉ có token người dùng chuyển tiếp và header `X-Internal-Api-Key` (chỉ dùng cho đăng ký khóa quyền).
- ℹ️ Cổng 8081 đến 8087 đã dùng; đề xuất `customer-api` = 8088 (⏳ sa kiểm lại trước khi chốt).
- ✅ Catalog (đã đối chiếu code): `titles` chỉ có `id, name, author, isbn, cover_price, created_at` (+ `tenant_id`, RLS từ `V2`); không có mô tả, preview, ảnh bìa. Migration catalog mới nhất là `V3__platform_read_all.sql`, số kế tiếp dự kiến `V4` (⚠️ kiểm lại trước khi tạo). `TitleController` đã có `POST /lookup` (tra theo lô).
- ⚠️ Chưa có cơ chế gọi nội bộ cho khách chưa đăng nhập (ADR-0011 chọn token dịch vụ `@StorefrontAccessible`); chưa có context `cms`.

## 3. Phạm vi

### 3.1 Làm

- ⏳ Service mới `customer-api` (BFF thứ hai, **không có DB riêng**), gọi `/internal/**` của catalog, sales, identity, system.
- ⏳ DTO công khai riêng, tách khỏi DTO nội bộ; không lộ trường nội bộ.
- ⏳ nginx route `/public/**` tới `customer-api`, có `limit_req`; không route `/internal/**` ra ngoài.
- ⏳ Công khai không cần đăng nhập (chỉ đọc, có giới hạn tốc độ): nội dung giới thiệu SaaS, thông tin tenant, danh mục và chi tiết sách bán của tenant, **preview** của đầu sách.
- ⏳ Công khai ghi: chỉ form liên hệ/đăng ký dùng thử **của bên mua dịch vụ SaaS** (ghi đi qua system-service, context `cms`). Không có ghi công khai nào ở storefront tenant.
- ⏳ Cần đăng nhập (tài khoản tenant): đặt hàng online, xem đơn của tôi, hủy đơn khi còn chờ xác nhận, liên hệ cửa hàng (đề xuất ở `customer-api-contact-reader.md`, chưa thuộc phạm vi code của đề bài này).
- ⏳ Preview và mô tả ngắn cho đầu sách: thêm trường ở catalog (`titles`), migration catalog, API nhân viên nhập qua `lms-api`, endpoint đọc công khai.
- ⏳ Tự đăng ký tài khoản khách theo tenant: dùng lại signup của ADR-0010, không viết lại.
- ⏳ Context `cms` mới trong system-service (system_db, RLS ADR-0007): nội dung giới thiệu quản trị được, hộp form liên hệ; API admin `@PlatformAdminOnly` đi qua `lms-api`.

### 3.2 Ngoài phạm vi (🚫)

- 🚫 **Thuê sách** (giai đoạn 2). Hiện chưa có nghiệp vụ cho: giá thuê, tiền cọc, phí theo ngày; chưa có luồng "mượn có thu phí" trong circulation. Cần BA/người dùng chốt riêng trước khi làm.
- 🚫 Thanh toán online, cổng thanh toán (như đề bài bán hàng).
- 🚫 Giỏ hàng nâng cao, wishlist, đánh giá, gợi ý, tìm kiếm nâng cao ngoài lọc cơ bản.
- 🚫 Xác thực email/điện thoại, quên mật khẩu qua email (chưa có ở hệ thống).
- 🚫 Tự động tạo tenant từ form dùng thử: form chỉ ghi nhận yêu cầu, platform admin xử lý tay.
- 🚫 Gửi email/thông báo cho người điền form (chưa có hạ tầng; xem câu hỏi mở).
- 🚫 CMS theo tenant do tenant admin tự sửa (giai đoạn này chỉ platform admin quản trị).
- 🚫 **Tải file PDF/EPUB, lưu trữ file sách, cắt trang/trích tự động từ file**: preview chỉ là đoạn văn bản nhân viên nhập tay.
- 🚫 Khách vãng lai đặt hàng, gửi liên hệ cửa hàng, hoặc xem bất cứ thứ gì ngoài danh sách/chi tiết/preview đầu sách.
- 🚫 Soạn thảo rich text/HTML cho preview, ảnh/video trong preview, preview theo chương hoặc nhiều đoạn, phiên bản/nháp preview (giữ một giá trị duy nhất; xem A12).
- 🚫 Giao diện FE (đề bài này chỉ BE/hợp đồng API).
- 🚫 Mọi nghiệp vụ tiền, VAT, tồn kho trong `customer-api` (chỉ chuyển tiếp sang sales-service).

## 4. Quy tắc nghiệp vụ và ngoại lệ

### 4.1 Xác định tenant

- Tenant xác định bằng `tenantCode` (slug) trong đường dẫn `/public/t/{code}/...`.
- Chỉ phục vụ tenant `ACTIVE` **và** đang bật module `SALES`. Mọi trường hợp còn lại (không tồn tại, bị tạm ngưng, chưa bật SALES, sai code) trả **cùng một mã lỗi chung** (đề xuất `404 SHOP_NOT_AVAILABLE`, cùng nội dung, cùng thời gian phản hồi xấp xỉ) để không dò được tenant.
- Tenant mới chỉ LENDING: storefront không khả dụng (khớp ADR-0010 về signup).

### 4.2 Dữ liệu sách lộ ra công khai (tối thiểu)

- Danh sách chỉ: id công khai/mã tham chiếu, tên sách, tác giả, giá bán (`sellingPrice`, nếu chưa đặt thì dùng `coverPrice` theo ADR-0010), cờ **còn hàng/hết hàng**, và cờ `hasPreview` (bool, để FE biết có nút "đọc thử"). Danh sách **không** trả mô tả và nội dung preview.
- Chi tiết thêm: mô tả ngắn (`description`, nếu có). Nội dung preview chỉ lấy qua endpoint preview (4.7).
- **Không** lộ: số tồn chính xác, `on_hand`/`reserved`, giá vốn/giá nhập, ngưỡng tồn, ISBN (A5), `tenant_id`, `titleId` nội bộ nếu có thể thay bằng mã công khai (sa quyết định; ADR-0011 O8 chọn `bookRef = titleId`).
- Chỉ sách đã "đưa vào bán" mới hiện. Còn hàng = `available > 0` do sales tính; customer-api chỉ chuyển bool.
- Phân trang có giới hạn kích thước trang tối đa (đề xuất 50).

### 4.3 Đặt hàng và đơn của tôi

- Cần đăng nhập bằng tài khoản thuộc đúng tenant của `{code}`; token có `tenantId` khác tenant trong đường dẫn thì từ chối (mã chung, không lộ).
- Đặt hàng đi theo luồng đơn online của ADR-0010 (giữ tồn ngay, `clientRequestId` chống gửi trùng, hết hàng `409 INSUFFICIENT_STOCK`). `customer-api` không tính giá/VAT/tồn, chỉ chuyển tiếp và trả kết quả đã lọc DTO.
- "Đơn của tôi": `userId` luôn lấy từ token; xem đơn người khác trả `404 ORDER_NOT_FOUND`.
- Khách hủy được khi đơn còn `PENDING_CONFIRMATION` (quy tắc của sales).

### 4.4 Form liên hệ/dùng thử (của bên mua dịch vụ SaaS)

- ℹ️ Mục này chỉ áp cho form gửi tới platform admin. Liên hệ của đọc giả với cửa hàng là việc khác: bắt buộc đăng nhập, không honeypot (xem `docs/api/customer-api-contact-reader.md`).
- Trường: họ tên, email, điện thoại (tùy chọn), tên tổ chức, loại hình (thư viện/hiệu sách/khác), lời nhắn (giới hạn độ dài), đồng ý xử lý dữ liệu (bắt buộc tick).
- Chống lạm dụng: `limit_req` ở nginx, giới hạn độ dài/định dạng ở validate, một trường honeypot (sa quyết định có làm không). Không captcha ở giai đoạn 1 (giả định A6).
- Dữ liệu cá nhân (email, điện thoại): không đưa vào log; chỉ platform admin đọc; xóa/ẩn danh theo thời hạn cấu hình (đề xuất mặc định 12 tháng, sa chốt cơ chế).
- Trạng thái yêu cầu: mới, đang xử lý, đã xử lý, spam (platform admin đổi).

### 4.5 Nội dung CMS

- Nội dung có trạng thái bản nháp/đã xuất bản; `customer-api` chỉ đọc bản **đã xuất bản**, không bao giờ trả bản nháp.
- Nội dung là văn bản có cấu trúc đơn giản (tiêu đề, nội dung, thứ tự). Nội dung HTML/Markdown do người quản trị nhập phải được làm sạch/escape trước khi trả (chống XSS; sa chốt cách) (giả định A7).

### 4.7 Preview đầu sách và quyền của khách vãng lai

Quyền của khách vãng lai (chưa đăng nhập), liệt kê đủ và đóng:
- ✅ Được: danh sách đầu sách, chi tiết đầu sách (4.2), preview (mục này), thông tin tenant `{code, name}` (cần để dựng storefront), nội dung giới thiệu SaaS đã xuất bản.
- ❌ Không được: đặt hàng, xem/hủy đơn, gửi liên hệ cửa hàng, bất kỳ ghi dữ liệu tenant nào. Gặp các endpoint này không token thì `401`.
- ℹ️ Đăng ký/đăng nhập vẫn công khai (là cổng để thành khách đăng nhập, không phải xem nội dung).

Quy tắc preview:
- Nguồn: đoạn văn bản do nhân viên tenant nhập cho đầu sách trong catalog (trường `preview_text`), cộng mô tả ngắn `description` (tùy chọn). Không có file, không cắt trang.
- Độ dài tối đa: `preview_text` 2000 ký tự, `description` 500 ký tự (đề xuất, A11), kiểm ở domain catalog và validate; vượt thì `400`. Chuẩn hóa: cắt khoảng trắng đầu/cuối; chuỗi rỗng coi là "chưa có".
- Định dạng: văn bản thuần theo mặc định đề xuất (xuống dòng giữ, mọi thẻ HTML hiển thị như chữ thường). Nếu `sa` chọn Markdown thì phải render như CMS (`escapeHtml(true)` + `sanitizeUrls(true)`), đầu ra không thực thi được script (A12).
- Chỉ trả cho sách **đang hiển thị ở storefront** (đã "đưa vào bán" và đang hoạt động, đúng điều kiện danh sách). Sách chưa bán, không tồn tại, hoặc chưa có preview đều trả cùng `404 BOOK_NOT_FOUND` (không phân biệt).
- Preview không kèm ISBN, số tồn, giá vốn, `tenant_id`; DTO preview chỉ gồm `{bookRef, title, previewText}` (tên trường cuối do `sa`).
- Chống cào hàng loạt: không có endpoint trả preview theo lô, danh sách không chứa preview, mỗi lần gọi một sách; có vùng `limit_req` riêng cho preview (mức do `sa` chọn, nghiêm hơn `public_read`). Giới hạn chặt trên đây chỉ làm chậm cào, không ngăn tuyệt đối; nội dung preview coi như đã chấp nhận công khai (nhân viên tự chịu trách nhiệm nội dung nhập).
- Nhân viên nhập/sửa/xóa preview bằng quyền riêng theo khóa động `Controller/method` (ADR-0003), cùng cách như sửa đầu sách; tenant A không sửa/đọc preview của tenant B (RLS sẵn có).

### 4.6 Lỗi và giới hạn

- Không bao giờ trả stack trace/thông điệp nội bộ; lỗi theo `ErrorResponse` (`{code, message}`).
- Lỗi từ service phía sau (catalog/sales/identity chết) trả `503` chung, không lộ tên service/host.
- Giới hạn tốc độ vượt ngưỡng: `429` từ nginx.

## 5. Phân quyền

| Nhóm | Điều kiện | Ghi chú |
|---|---|---|
| Xem nội dung giới thiệu SaaS, gửi form SaaS | Không cần đăng nhập; `@PublicEndpoint` | Có `limit_req`; form SaaS ghi vào bảng nền tảng, không ghi dữ liệu tenant |
| Thông tin tenant, danh sách/chi tiết đầu sách, preview | Không cần đăng nhập; chỉ đọc qua token dịch vụ `svc` ở endpoint `@StorefrontAccessible` (ADR-0011 2.1), tenant lấy từ `{code}` | Token `svc` không có `permissions`, không dùng được ở endpoint khác; catalog/sales vẫn kiểm module SALES và RLS theo claim `tenantId` |
| Đặt hàng, xem/hủy đơn của tôi, liên hệ cửa hàng | Đăng nhập tài khoản tenant (token người dùng, **không** token `svc`) | Khóa động `Controller/method` (ADR-0003) do dịch vụ nhận kiểm (ADR-0011 2.4: sales kiểm), không tin BFF. Khách vãng lai: `401` |
| Nhân viên nhập/sửa/xóa preview và mô tả đầu sách | Đăng nhập nhân viên tenant, khóa động `Controller/method` ở catalog | Qua `lms-api`; role phải được gán khóa này; chỉ admin hoặc role được gán mới dùng được; không dùng token `svc` |
| Quản trị CMS và hộp form liên hệ | `@PlatformAdminOnly` ở system-service, qua `lms-api` | Tenant admin nhận 403 |

- Khóa của phần cần đăng nhập phải nằm trong tập quyền của role khách (mẫu "Khách hàng" của ADR-0010 bật SALES) để khách tự đăng ký dùng được ngay. Seed khóa theo mẫu ADR-0010 (chèn khóa vào `permissions` trước, test đối chiếu tập khóa thật với seed).
- Mặc định từ chối (ADR-0003): khóa chưa gán thì 403.
- Chặn module: sales vẫn `@RequiresModule(SALES)`; customer-api kiểm bổ sung ở bước xác định tenant (4.1) vì endpoint công khai không có token.

## 6. API và dữ liệu dự kiến (mức ranh giới; tên cụ thể do sa)

Tiền tố nginx: `/public/**` → customer-api; customer-api tự gọi `/internal/**` của service phía sau.

### 6.1 Công khai, không đăng nhập

| Method | Đường dẫn | Nội dung |
|---|---|---|
| GET | `/public/site/pages` và `/public/site/pages/{slug}` | Nội dung giới thiệu SaaS đã xuất bản |
| POST | `/public/site/contact-requests` | Gửi form liên hệ/dùng thử; `201`, không trả lại dữ liệu cá nhân |
| GET | `/public/t/{code}` | Thông tin công khai tối thiểu của tenant (tên hiển thị); lỗi chung nếu không khả dụng |
| GET | `/public/t/{code}/books` | Danh mục sách bán (lọc theo từ khóa/tác giả cơ bản, phân trang); mỗi mục có `hasPreview`, không có preview |
| GET | `/public/t/{code}/books/{bookRef}` | Chi tiết một sách (DTO công khai, có `description` nếu có) |
| GET | `/public/t/{code}/books/{bookRef}/preview` | Đoạn preview của sách; `404 BOOK_NOT_FOUND` chung (4.7). Đề xuất tách endpoint riêng thay vì gộp vào chi tiết để danh sách/chi tiết nhẹ, giới hạn tốc độ riêng, đệm riêng; `sa` có thể gộp nếu thấy hợp lý (O11) |
| POST | `/public/t/{code}/auth/signup` | Tự đăng ký (chuyển sang signup ADR-0010; mã lỗi chung) |
| POST | `/public/t/{code}/auth/login` | Đăng nhập khách (⚠️ có đi qua customer-api hay dùng `lms-api` `/api/auth/login`: câu hỏi O2) |

### 6.2 Cần đăng nhập

| Method | Đường dẫn | Nội dung |
|---|---|---|
| POST | `/public/t/{code}/contact-requests` | Liên hệ cửa hàng (đề xuất, hợp đồng ở `customer-api-contact-reader.md`; ngoài code của đề bài này tới khi chốt Q1 của file đó) |
| POST | `/public/t/{code}/orders` | Đặt hàng online (`clientRequestId`, dòng hàng, hình thức nhận, thông tin nhận hàng) |
| GET | `/public/t/{code}/orders` | Đơn của tôi (phân trang) |
| GET | `/public/t/{code}/orders/{orderId}` | Chi tiết đơn của tôi |
| POST | `/public/t/{code}/orders/{orderId}/cancel` | Hủy đơn khi còn chờ xác nhận |

### 6.3 Admin (qua `lms-api`, `@PlatformAdminOnly`, không thuộc `/public/**`)

- CRUD trang nội dung CMS, xuất bản/gỡ xuất bản.
- Danh sách/xem/đổi trạng thái yêu cầu liên hệ.
- Nhân viên tenant nhập preview/mô tả: qua `lms-api`, ví dụ `PUT /api/books/{id}` đã có (thêm hai trường tùy chọn `description`, `previewText` vào body cập nhật đầu sách) hoặc `PUT /api/books/{id}/preview` riêng; tên do `sa` chốt (O11). Xóa preview = gửi rỗng/null. Đây là API nhân viên, không thuộc `/public/**`, không `@PlatformAdminOnly`.

### 6.5 Dữ liệu catalog cho preview (ranh giới, sa chốt chi tiết)

- ⏳ `titles` thêm hai cột tùy chọn: `description` (VARCHAR(500)) và `preview_text` (VARCHAR(2000) hoặc TEXT kèm CHECK độ dài), cho phép NULL. Migration `V4` ở `services/system-service/.../db/migration/catalog/` (⚠️ kiểm số ngay trước khi tạo). Bảng `titles` đã bật RLS + `@TenantId` từ `V2`: chỉ thêm cột, không cần chính sách mới; entity JPA thêm trường tương ứng, domain `Title` kiểm độ dài.
- ⏳ DTO nội bộ tra đầu sách theo lô (`lookup`) không trả `preview_text` mặc định (tránh kéo nội dung dài cho danh mục); chỉ endpoint chi tiết/preview mới đọc.
- ⏳ Sales storefront chỉ cho khách xem preview của sách đang bán: kiểm điều kiện "đang bán" ở sales rồi mới lấy preview từ catalog (O11).

### 6.4 Dữ liệu `cms` (system_db, ADR-0007; sa chốt chi tiết)

- ⏳ `cms_pages`: `id`, `slug` (duy nhất), `title`, `body`, `sort_order`, `status` (DRAFT/PUBLISHED), `published_at`, `updated_at`, `updated_by`. Nội dung cấp **nền tảng**, không phụ thuộc tenant (xem A3).
- ⏳ `cms_contact_requests`: `id`, `full_name`, `email`, `phone`, `organization`, `business_type`, `message`, `consent`, `status`, `created_at`, `handled_by`, `handled_at`, `anonymized_at`.
- Cả hai là dữ liệu nền tảng, không có `tenant_id`: sa quyết định (a) đưa vào `TENANT_EXEMPT_TABLES` (như bảng toàn cục) hay (b) dùng `tenant_id` của tenant nền tảng với RLS; kèm cách ghi từ endpoint công khai không có tenant (câu hỏi O3).
- Migration: thêm vào `services/system-service/.../db/migration/system/`, kiểm tra số `n` kế tiếp ngay trước khi tạo; migration ghi dữ liệu vào bảng RLS phải đặt `app.tenant_id`.
- Ghi form đi qua system-service (`/internal/cms/contact-requests`, khóa/cơ chế công khai do sa chọn): customer-api **không** truy cập system_db trực tiếp.

## 7. Điểm mở giao cho `sa` (không tự quyết ở đề bài)

| # | Câu hỏi |
|---|---|
| ⚠️ O1 | Cách gọi nội bộ cho khách chưa đăng nhập (đọc danh mục, tra tenant, ghi form): hiện chỉ có token người dùng chuyển tiếp và `X-Internal-Api-Key`. Chọn: khóa dịch vụ riêng cho customer-api, token dịch vụ ngắn hạn, hay endpoint nội bộ `@PublicEndpoint` chặn bằng mạng/khóa nội bộ. Phải không cho `X-Internal-Api-Key` thành đặc quyền rộng. |
| ⚠️ O2 | Cách đặt `TenantContext` khi không có JWT (danh mục theo `{code}`): resolve `code → tenantId` ở đâu (identity có API nội bộ tra tenant, có cache không) và truyền sang catalog/sales thế nào (header nội bộ tin cậy, token dịch vụ có `tenantId`). Đăng nhập khách đi qua customer-api hay dùng `lms-api` `/api/auth/login`. |
| ⚠️ O3 | Bảng `cms` không có tenant: ngoại lệ RLS/`TENANT_EXEMPT_TABLES` hay tenant nền tảng; ArchUnit `TenantArchitectureRulesTest` phải xanh. |
| ⚠️ O4 | Customer-api có tự kiểm khóa động hay chỉ chuyển token cho sales kiểm (hai nơi cùng kiểm có lệch khóa không); khóa đăng ký của service không DB. |
| ⚠️ O5 | Chống dò tenant: mã lỗi chung và thời gian phản hồi; cách chống dò qua signup/login. |
| ⚠️ O6 | Danh mục công khai ghép dữ liệu catalog + sales: ai ghép (customer-api hay sales trả DTO công khai); cần endpoint tra hàng loạt (ADR-0010 đã có `lookup`) hay endpoint danh mục công khai mới; tránh N+1 và giới hạn tải khi công khai. |
| ⚠️ O7 | Tải/DoS: `limit_req` theo IP, vùng riêng cho `/public/**` và form liên hệ/signup, cache ngắn cho danh mục; header chuyển tiếp IP thật. |
| ⚠️ O8 | Ảnh bìa: catalog hiện có trường/đường dẫn ảnh chưa; nếu chưa thì có lộ ra không. Lộ `bookRef` công khai thay `titleId`. **Bổ sung:** preview/mô tả là trường mới của `titles` (mục 6.5); DTO công khai nay thêm `hasPreview`, `description`; chọn định dạng văn bản thuần hay Markdown render sạch. |
| ⚠️ O9 | Hạ tầng: module Maven, Dockerfile, compose (expose 8088, không publish), nginx, ArchUnit cho service không DB, CORS cho FE công khai, tắt actuator công khai. |
| ⚠️ O10 | Thời hạn giữ/ẩn danh dữ liệu form liên hệ và job thực hiện (cùng mẫu job ẩn danh của ADR-0010). |
| ⚠️ O11 | Preview: tách endpoint riêng hay gộp vào chi tiết; ai ghép (sales kiểm "đang bán" rồi gọi catalog, hay catalog trả thẳng); tên API nhập preview cho nhân viên; văn bản thuần hay Markdown render sạch; mức `limit_req` riêng cho preview. |

### 7.1 Điểm `sa` phải bổ sung vào ADR-0011 (do bản đề bài này thay đổi)

- ⏳ catalog-service: thêm mục thiết kế cho `description`, `preview_text`: migration catalog `V4` (kiểm số), giữ RLS/`@TenantId` sẵn có của `titles`, entity + domain (kiểm độ dài, chuẩn hóa), `TenantArchitectureRulesTest` vẫn xanh; DTO `lookup` không trả preview mặc định.
- ⏳ API nhân viên nhập preview: controller/method và khóa động (ADR-0003), seed khóa cho role thủ thư/nhân viên bán nếu cần (người dùng cần nhận biết quyền mới chỉ có hiệu lực sau đăng nhập/refresh kế tiếp); `lms-api` DTO và `CatalogClient`.
- ⏳ sales-service endpoint storefront: ghép `description`/`hasPreview` vào `items` và chi tiết; thêm `GET /internal/sales/storefront/items/{titleId}/preview` (hoặc gộp), kiểm sách "đang bán" trước khi trả preview. Ảnh hưởng **O6**: ADR-0011 2.6 hiện cố định DTO `{titleId, name, author, price, inStock}`; phải đổi thành có `hasPreview`, `description` và nói rõ preview không đi qua danh sách.
- ⏳ Token dịch vụ đọc catalog: `@StorefrontAccessible` hiện chỉ dự kiến cho `TitleController/lookup`; nếu sales lấy preview từ catalog thì endpoint đọc preview của catalog cũng phải `@StorefrontAccessible` và chỉ trả dữ liệu preview, không trả ISBN.
- ⏳ Giới hạn độ dài và chống cào: chốt 2000/500 ký tự, vùng nginx `public_preview` (mức `r/s`, burst), `Cache-Control` riêng cho preview, `client_max_body_size` cho API nhập; ghi rõ không có API theo lô.
- ⏳ Chống XSS: chốt văn bản thuần hay Markdown (dùng lại mẫu `commonmark` của ADR-0011 2.11 nếu chọn Markdown; render ở đâu: catalog hay sales).
- ⏳ **O8**: ADR-0011 2.8 nói DTO công khai "không có trường ảnh" và danh sách trường cố định; cập nhật để thêm `hasPreview`, `description`, giữ nguyên "không ISBN".
- ⏳ `PublicExceptionHandler` (2.9): thêm `BOOK_NOT_FOUND` (đã có trong 3.4) áp cho preview; API nhập preview của nhân viên đi qua `lms-api`, không qua customer-api, nên không cần thêm mã vào handler này.
- ⏳ Phân quyền mục 2.1/2.4: ghi rõ khách vãng lai không có đường nào ghi; đặt hàng/liên hệ không dùng token `svc`; endpoint `contact-requests` của đọc giả không gắn `@StorefrontAccessible`.
- ⏳ Phân việc (mục 4 ADR): thêm việc B-C6 mở rộng (catalog: cột + API preview), B-C7 (sales ghép), T-C4/T-C6 thêm kịch bản preview; cập nhật README/CLAUDE.md nếu thêm khóa/cổng.


## 8. Tiêu chí nghiệm thu (kiểm được bằng test)

Công khai và tenant
- ⏳ `GET /public/t/{code}/books` với tenant ACTIVE có SALES: trả danh mục; không đăng nhập vẫn gọi được.
- ⏳ Tenant không tồn tại, bị ngưng, hoặc chưa bật SALES: cùng một mã lỗi và cùng nội dung; không phân biệt được các trường hợp.
- ⏳ DTO sách chỉ có các trường ở 4.2; không có số tồn chính xác, giá vốn, `tenant_id`, ISBN (test so khớp tập trường, không chỉ giá trị).
- ⏳ Danh sách sách không chứa `previewText`; chỉ có `hasPreview` đúng với dữ liệu (true khi đã nhập, false khi rỗng/NULL).

Khách vãng lai và preview
- ⏳ Không token: danh sách, chi tiết, preview trả `200`; đặt hàng, xem/hủy đơn, `contact-requests` của đọc giả trả `401` (kiểm từng endpoint).
- ⏳ Token dịch vụ `svc` không dùng được ở endpoint ghi hoặc cần đăng nhập (403/401, theo ADR-0011); khách vãng lai không lấy được gì ngoài danh sách, chi tiết, preview, thông tin tenant (test so khớp tập endpoint công khai).
- ⏳ Preview trả đúng nội dung nhân viên đã nhập, tối đa 2000 ký tự; nhập 2001 ký tự: `400` và không lưu; `description` 501 ký tự: `400`. Không có đường nào trả nội dung dài hơn giới hạn (kiểm cả ở lưu và ở đọc, chèn trực tiếp dữ liệu quá dài vào DB không làm đầu ra vượt giới hạn hoặc phải bị chặn bằng CHECK).
- ⏳ Phản hồi preview chỉ có tập trường đã chốt (4.7): không ISBN, không số tồn/`onHand`/`reserved`, không giá vốn, không `tenant_id` (test so khớp tập trường).
- ⏳ Sách không tồn tại, chưa "đưa vào bán", hoặc chưa có preview: cùng `404 BOOK_NOT_FOUND`, cùng thân.
- ⏳ Preview chứa `<script>`, `<img onerror>`, `javascript:` (và Markdown độc nếu chọn Markdown): đầu ra không thực thi được (thẻ thành văn bản, URL độc bị bỏ).
- ⏳ Cách ly tenant: preview của tenant B không đọc được qua `{code}` của tenant A hoặc bằng `bookRef` của B; không có `{code}` hợp lệ thì `404 SHOP_NOT_AVAILABLE`. Nhân viên tenant A không sửa được preview đầu sách của tenant B (`404`).
- ⏳ API nhập preview: nhân viên có khóa thành công; thiếu khóa: `403`; khách (token khách hàng) và token `svc`: `403`; người dùng không token: `401`.
- ⏳ Chống cào: vượt giới hạn `public_preview` trả `429` (kiểm cấu hình/IT nginx); không tồn tại endpoint trả nhiều preview một lần (test tập đường dẫn công khai).
- ⏳ Migration catalog `V4` chạy được trên DB hiện có (cột NULL, sách cũ không bị ảnh hưởng, `hasPreview=false`); RLS của `titles` vẫn bật (đối chiếu bằng IT cách ly như `CatalogTenantIsolationIT`).
- ⏳ Sách có tồn 0 hiện "hết hàng"; có tồn hiện "còn hàng"; sách chưa "đưa vào bán" không xuất hiện.
- ⏳ Tenant A không thấy sách/đơn của tenant B qua `{code}` hay qua token tenant khác.

Đăng nhập, đặt hàng
- ⏳ Đặt hàng không có token: 401; token tenant khác tenant trong đường dẫn: bị từ chối, mã chung.
- ⏳ Thiếu khóa quyền (role không có khóa): 403; có khóa: đặt thành công, tồn bị giữ đúng (sales).
- ⏳ Đặt vượt tồn: `409 INSUFFICIENT_STOCK`; gửi lại cùng `clientRequestId`: trả đơn cũ, không giữ tồn hai lần.
- ⏳ "Đơn của tôi" chỉ có đơn của người gọi; xem đơn người khác: `404 ORDER_NOT_FOUND`.
- ⏳ Khách tự đăng ký xong đăng nhập và đặt hàng được (role khách `signup_default`); signup ở tenant không hợp lệ trả mã chung.
- ⏳ customer-api không có logic tiền/VAT/tồn: không import domain của sales (ArchUnit).

CMS và form
- ⏳ Chỉ trang PUBLISHED được trả công khai; DRAFT không bao giờ lộ.
- ⏳ API admin CMS và danh sách yêu cầu liên hệ: platform admin gọi được; tenant admin nhận 403.
- ⏳ Gửi form hợp lệ: `201`, bản ghi xuất hiện ở hộp yêu cầu của platform admin; thiếu `consent`/sai định dạng/quá dài: 400.
- ⏳ Email/điện thoại trong form không xuất hiện trong log (kiểm bằng test log).
- ⏳ Nội dung CMS chứa script không thực thi được ở đầu ra công khai (đã làm sạch/escape).
- ⏳ Bảng `cms` có RLS hoặc nằm trong `TENANT_EXEMPT_TABLES` đúng quyết định của sa; `TenantArchitectureRulesTest` xanh.

An toàn và hạ tầng
- ⏳ nginx có `limit_req` cho `/public/**`; vượt ngưỡng trả `429` (kiểm bằng test cấu hình hoặc IT nginx).
- ⏳ Không truy cập được `/internal/**` qua nginx 8080 hoặc qua customer-api (404/403); customer-api `SecurityConfiguration` không mở `/internal/**` ra ngoài.
- ⏳ Mọi lỗi 4xx/5xx công khai là `ErrorResponse` gọn; không có stack trace, tên class, tên host nội bộ (test với catalog/sales giả trả lỗi).
- ⏳ Khi service phía sau chết: `503` chung.
- ⏳ IT cách ly tenant theo mẫu `CatalogTenantIsolationIT` cho phần chạm dữ liệu tenant (danh mục, đơn); không có tenant mà gọi endpoint cần tenant thì lỗi chung/403 `TENANT_REQUIRED` theo thiết kế của sa.
- ⏳ ArchUnit của các service và customer-api xanh; `mvn test` toàn bộ (kể cả test hiện có của lending/sales/identity/system) vẫn xanh.

## 9. Giả định (BA tự đặt, ⚠️ cần xác nhận)

- ℹ️ A1: Cổng 8088 chưa ai dùng (đã đối chiếu ADR: 8081 đến 8087 đã dùng).
- ℹ️ A2: Storefront phục vụ đúng một tenant theo `{code}`; chưa có tên miền riêng (custom domain) cho tenant.
- ℹ️ A3: CMS giai đoạn 1 chỉ nội dung cấp nền tảng (trang giới thiệu SaaS); nội dung riêng cho từng storefront (banner, giới thiệu cửa hàng) chưa có, storefront chỉ hiện tên tenant và danh mục. Đưa vào câu hỏi mở nếu cần.
- ℹ️ A4: Ảnh bìa chưa có ở catalog (đã xác nhận bằng code); DTO không có trường ảnh, không bổ sung upload ảnh ở giai đoạn này.
- ℹ️ A11: Giới hạn đề xuất `preview_text` 2000 ký tự, `description` 500 ký tự; cả hai tùy chọn. `sa`/người dùng đổi được.
- ℹ️ A12: Preview là một giá trị duy nhất theo đầu sách, văn bản thuần mặc định (không Markdown); nếu chọn Markdown thì render sạch như CMS. Không có nháp/phiên bản/lịch sử cho preview. Nhập trong catalog, không tách bảng riêng.
- ℹ️ A13: Khách vãng lai xem được preview của mọi sách đang hiển thị ở storefront, không giới hạn số lần theo tài khoản (chỉ giới hạn theo IP bằng nginx); không có khái niệm "chỉ khách đã đăng nhập mới đọc preview".
- ℹ️ A14: Thông tin tenant `{code, name}` và nội dung giới thiệu SaaS đã xuất bản vẫn công khai (cần để dựng trang); người dùng nói "không xem thêm gì khác" được hiểu là không có dữ liệu tenant nào khác.
- ℹ️ A5: ISBN không lộ công khai ở giai đoạn 1 (sa có thể đổi).
- ℹ️ A6: Không dùng captcha giai đoạn 1; chống lạm dụng form SaaS bằng `limit_req`, validate, honeypot (tùy sa). Liên hệ đọc giả đã bắt buộc đăng nhập nên không honeypot.
- ℹ️ A7: Nội dung CMS là văn bản thuần/Markdown đã làm sạch; không cho nhúng script.
- ℹ️ A8: Form liên hệ chỉ lưu và hiển thị cho platform admin; không gửi email tự động.
- ℹ️ A9: Khách dùng lại luồng token chuẩn (access 15 phút, refresh 7 ngày); không thêm phiên riêng cho storefront.
- ℹ️ A10: Tên hiển thị tenant công khai lấy từ `tenants.name`; không có cờ "ẩn khỏi công khai" ngoài trạng thái ACTIVE và module SALES.

## 10. Câu hỏi còn mở (ngoài các điểm O1 đến O10 giao cho sa)

- ⚠️ Q1 (cho người dùng): form dùng thử cần gửi email xác nhận/thông báo cho platform admin không (cần hạ tầng email/thông báo, hiện chưa có)?
- ⚠️ Q2 (cho người dùng): cần nội dung riêng theo từng tenant trên storefront (banner, giới thiệu) và tenant admin tự sửa không, hay giữ platform admin quản trị như đã chốt?
- ⚠️ Q3 (cho người dùng/pháp lý): thời hạn giữ và quyền xóa theo yêu cầu cho dữ liệu form liên hệ.
- ⏳ Giai đoạn 2 "thuê sách": cần chốt riêng giá thuê, tiền cọc, phí theo ngày, xử lý trễ hạn, luồng thanh toán trước khi viết đề bài.
