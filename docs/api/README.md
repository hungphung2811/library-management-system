# Base URL và định tuyến API (đọc trước khi tích hợp)

FE chỉ gọi **một địa chỉ duy nhất: cổng vào nginx**. Không bao giờ gọi thẳng cổng của từng service. Nguồn: `infrastructure/nginx/nginx.conf`, `docker-compose.yml`.

## 1. Base URL

| Môi trường | Origin (gọi là `<ORIGIN>`) |
|---|---|
| Local (docker compose `--profile apps`) | `http://localhost:8080` |
| Production | ⚠️ Chưa có trong repo. Lấy từ người triển khai; origin FE production phải được thêm vào `map $cors_origin` trong nginx.conf, nếu không trình duyệt bị chặn CORS |

Mọi URL trong các file hợp đồng là **đường dẫn tương đối** so với `<ORIGIN>`. Ví dụ `POST /public/site/contact-requests` nghĩa là `POST http://localhost:8080/public/site/contact-requests`.

## 2. Hai tiền tố, hai backend khác nhau

| Tiền tố | Đi tới | Dùng cho | Đăng nhập |
|---|---|---|---|
| `/public/**` | `customer-api` (8088, qua nginx) | Website giới thiệu SaaS, storefront đọc giả (xem sách, preview, mua), contact | Công khai (một số endpoint cần token khách, xem từng file) |
| `/api/**` | `lms-api` (8085, qua nginx) | FE quản trị và nhân viên: tenant, người dùng, sách, đơn, CMS admin, thông báo... | Bắt buộc Bearer token (trừ đăng nhập/làm mới) |

Quy tắc chọn nhanh:
- FE **website giới thiệu và storefront cho khách** dùng `/public/**`, **không** có tiền tố `/api`.
- FE **quản trị/nhân viên** dùng `/api/**`.
- Hai bên **không dùng chung token**: token khách (đăng nhập qua `/public/t/{code}/auth/login`) không dùng được ở `/api/**` và ngược lại. Token đăng nhập nhân viên lấy từ `/api/auth/login`.

## 3. Không được gọi

- `/internal/**` và `/.well-known/**`: nginx luôn trả `404`, không phải API cho FE.
- Cổng trực tiếp của service: identity 8081, catalog 8082, circulation 8083, support 8084, lms-api 8085, system 8086, sales 8087, customer-api 8088, integration-api 8089. Chỉ nginx (8080) được publish ra host, các cổng này chỉ có trong mạng compose.
- `/actuator/**` của `customer-api` (nginx không route).
- Các đường dẫn chỉ có trong tài liệu dạng "đề xuất" (đánh dấu ⚠️ ĐỀ XUẤT) chưa có code: đừng tích hợp thật.

## 4. Nhầm lẫn hay gặp

| Việc cần làm | Đúng | Sai |
|---|---|---|
| Website gửi form liên hệ/dùng thử SaaS | `POST /public/site/contact-requests` | `/api/cms/contact-requests` (đây là API admin xử lý, cần platform admin) |
| Admin xử lý form liên hệ | `/api/cms/contact-requests/**` (Bearer platform admin) | `/public/site/...` |
| Đọc trang giới thiệu | `GET /public/site/pages/{slug}` | `/api/cms/pages` (bản admin, gồm cả nháp) |
| Nhân viên nhập preview sách | `PUT /api/books/{id}/preview` | `/public/t/{code}/books/...` |
| Đăng nhập nhân viên | `POST /api/auth/login` | `/public/t/{code}/auth/login` |
| Đăng nhập khách storefront | `POST /public/t/{code}/auth/login` | `/api/auth/login` |

## 5. Header, CORS, giới hạn

- Gửi `Authorization: Bearer <accessToken>` khi endpoint yêu cầu. Gửi `Content-Type: application/json` cho POST/PUT (sai kiểu trả `415`).
- Tùy chọn `X-Correlation-Id`: nginx gán `$request_id` và trả lại header `X-Correlation-Id` (FE đọc được vì đã expose). Đưa giá trị này khi báo lỗi.
- CORS hiện cho phép `http://localhost:3000` và `http://localhost:5173`, cho phép credentials, method `GET, POST, PUT, PATCH, DELETE, OPTIONS`, header `Authorization, Content-Type, X-Correlation-Id`. Origin khác không có header CORS.
- Giới hạn tốc độ theo IP (trả `429` do nginx): `/public/**` 20 yêu cầu/giây, form liên hệ và đăng ký 5 yêu cầu/phút, đăng nhập 10 yêu cầu/phút. Thân yêu cầu `/public/**` tối đa 16 KB (quá thì `413`), `/api/**` tối đa 1 MB.
- `GET /healthz` trả `{"status":"UP"}` để kiểm tra nginx sống.

## 6. Các file hợp đồng

| File | Backend | Tiền tố |
|---|---|---|
| `customer-api.md` | customer-api | `/public/**` |
| `customer-api-contact-saas.md` | customer-api + lms-api | `/public/site/**` và `/api/cms/contact-requests` |
| `customer-api-contact-reader.md` | ⚠️ đề xuất, chưa có code | `/public/t/{code}/contact-requests` |
| `cms.md` | lms-api | `/api/cms/**` |
| `catalog-books.md`, `catalog-preview.md`, `catalog-lookup.md` | lms-api / catalog | `/api/books/**` |
| `catalog-works-series.md` | catalog, lms-api (pha 2d) | `/api/works/**`, `/api/series/**`, `PUT /api/books/{id}/work`, trường mới của `/api/books`, `/api/book-copies/{id}` (kèm `/internal/works/**`, `/internal/series/**`) |
| `sales.md` | lms-api | `/api/sales/**` |
| `sales-shifts.md` (ADR-0030 gói B) | lms-api → sales-service: chốt ca thu ngân | `/api/sales/shifts/**`, `/api/sales/shift-settings` |
| `sales-invoices.md` (ADR-0030 gói A) | lms-api → sales-service: hóa đơn VAT nội bộ; khách gửi yêu cầu qua customer-api | `/api/sales/invoices/**`, `/api/sales/orders/{id}/invoice(-request)`, `/api/sales/invoice-settings`, `PUT /public/t/{code}/orders/{orderId}/invoice-request` |
| `platform-contracts.md` | identity (qua lms-api, platform admin) | `/api/platform/tenants/{id}/contracts`, `/api/platform/contracts/**` |
| `tenant-contracts.md` | lms-api | `/api/contracts` |
| `platform-storefront-origins.md` | identity (qua lms-api, platform admin) + hợp đồng identity cho customer-api (ADR-0013) | `/api/platform/tenants/{tenantId}/storefront-origins` |
| `tenant-storefront-origins.md` | lms-api (tenant admin, chỉ xem) | `GET /api/storefront-origins` |
| `applications.md` | lms-api (tenant admin) + identity (platform admin): Application, scope, domain, credential (ADR-0027, pha P1) | `/api/applications/**`, `/api/platform/tenants/{tenantId}/applications/**` |
| `public-api-v1.md` | customer-api (qua nginx): Public API v1 cho website tenant, nhận diện bằng header `X-Site-Key` (ADR-0027, pha P2) | `/api/public/v1/{books,banners,sessions,orders}/**` |
| `integration-api.md` | integration-api (qua nginx, chuyển tới identity): token OAuth2 client_credentials cho backend tenant (ADR-0027, pha I2) | `POST /api/integration/v1/oauth/token` |
| `dashboard-reports.md` | lms-api (gộp catalog, circulation, sales; tổng quan nền tảng ở identity) | `/api/dashboard/**`, `/api/reports/**`, `GET /api/platform/dashboard/overview` |
| `storefront-kham-pha.md` | ⚠️ đề xuất, chưa có code (ADR-0019): customer-api + lms-api | `/public/t/{code}/books?sort&inStock`, `/public/t/{code}/books/suggestions`, `/public/t/{code}/banners`, `/api/sales/banners/**` |
| `notifications.md` (mục "Thông báo theo sự kiện", ADR-0030 gói C) | lms-api → support-service | `/api/notifications/me/{preferences,deliveries}`, `/api/notification-deliveries/**`, `/api/notification-settings/channels`, `/api/notification-templates/**` |
| Các file còn lại (`identity-*`, `circulation.md`, `notifications.md`, `menu.md`...) | lms-api | `/api/**` |

## 7. Chưa kiểm chứng

- ⚠️ Origin production, domain và HTTPS chưa có trong repo.
- ⚠️ Bảng định tuyến lấy từ `nginx.conf` hiện tại (đã `nginx -t` ok và `tester` kiểm `/internal/**` 404, giới hạn tốc độ); chưa kiểm lại toàn bộ đường dẫn qua hệ thống chạy thật.
