# Kênh token và `aud` (storefront tách khỏi nhân viên)

Căn cứ: customer-api-data-authorization A1, A18, mục 5.3 phương án C. Đổi hợp đồng đăng nhập/làm mới của identity.

## Hợp đồng identity (`/internal/auth/*`)

- `POST /internal/auth/login`: thêm trường tùy chọn `channel` = `STAFF` | `STOREFRONT` (mặc định `STAFF`; giá trị khác -> `400 VALIDATION_FAILED`). `STOREFRONT` bắt buộc có `tenantCode` (thiếu -> `401 UNAUTHORIZED`, trước khi tra tài khoản). Thứ tự kiểm tra không đổi cho mọi kênh: tài khoản -> khóa/vô hiệu (`423 ACCOUNT_LOCKED`, `403 ACCOUNT_DISABLED`) -> mật khẩu -> tenant (`403 NO_TENANT_ACCESS`). Việc gộp mọi lỗi thành `401 LOGIN_FAILED` do customer-api làm (HttpAuthClient), identity giữ mã riêng.
- `POST /internal/auth/refresh`: thêm `channel` tùy chọn như trên. Refresh token chỉ làm mới được ĐÚNG kênh đã phát hành; lệch kênh -> `401 UNAUTHORIZED`, token không bị thu hồi. Phiên mới giữ kênh.
- `POST /internal/auth/switch-tenant`: refresh token kênh STOREFRONT bị `401` (phiên storefront gắn một tenant).
- Không cần bí mật cho `channel`: kênh STOREFRONT chỉ THU HẸP token.
- `refresh_tokens.channel` (`STAFF`|`STOREFRONT`, mặc định `STAFF`, migration V18). Mọi refresh token cũ là STAFF.

## Token kênh STOREFRONT

- `aud = ["lms-storefront"]`.
- KHÔNG có `admin`, KHÔNG có `platformAdmin`, kể cả khi tài khoản có role admin hoặc là platform admin (nhân viên đăng nhập ở storefront nhận token hạn chế). Vẫn có `tenantId`, `modules`, `roles`. Phiên storefront bắt buộc có tenant (platform admin không tenant -> 401).
- `permissions` chỉ gồm khóa thuộc ALLOWLIST cấu hình `lms.identity.token.storefront-permissions` (danh sách phân tách bằng dấu phẩy; mặc định `ShopController/*,TitleController/lookup,ReservationController/mine,ReservationController/cancel`, ADR-0023 mục 4): mục kết thúc `/*` khớp mọi khóa cùng controller, mục khác khớp chính xác. Mọi khóa khác của role (nhân viên, `StorefrontController/*`, quản trị role...) bị loại khỏi claim của token STOREFRONT, cả khi đăng nhập lẫn làm mới. Token kênh STAFF không bị lọc. Admin tenant muốn đặt hàng phải có role mang khóa `ShopController/*`. Cấu hình rỗng (`=`) nghĩa là không khóa nào.
- Ký và JWKS không đổi.
- ADR-0029 (thanh toán SePay cho website) KHÔNG đổi allowlist `storefront-permissions`: đặt/hủy đơn dùng lại `ShopController/placeOrder` và `ShopController/cancelMyOrder` (đã thuộc `ShopController/*`); hai endpoint đọc mới (`GET .../orders/{id}/payment`, `GET .../payment-options`) là `@AuthenticatedOnly`, không có khóa mới và không cần migration identity.

## Token kênh STAFF (lms-api)

- Giai đoạn chuyển tiếp: KHÔNG có `aud` (mọi token cũ vẫn dùng được).
- Cờ identity `lms.identity.token.staff-audience=true`: ghi `aud = ["lms-staff"]`. Mặc định tắt.

## lms-api

`ChannelAudienceFilter`: token có `aud` chứa `lms-storefront` -> `401` (như token không hợp lệ). Cờ `lms.api.security.require-staff-audience` (mặc định `false`): khi `true` chỉ nhận token có `aud` chứa `lms-staff` (bật cùng lúc với cờ identity khi hết giai đoạn chuyển tiếp). Refresh token storefront gửi tới `POST /api/auth/refresh` -> identity trả 401 (lms-api không gửi `channel`).

## Token kênh INTEGRATION (`aud = lms-integration`)

- Pha P0 đặt nền (`@IntegrationScope`, nhánh interceptor, `CallerIdentity`, `ChannelAudienceFilter`); **pha I2 đã có endpoint cấp token**: identity `POST /internal/oauth2/token` (OAuth2 client_credentials, RFC 6749 mục 4.4), ra ngoài qua `integration-api` `POST /api/integration/v1/oauth/token` (hợp đồng và mã lỗi ở `docs/api/integration-api.md`). Token ký RS256 bằng khóa của identity (cùng JWKS), TTL mặc định 5 phút (`lms.identity.integration.token-ttl`).
- Claim: `iss`, `sub` = `client_id` = `application_id` = id Application, `aud = "lms-integration"` (chuỗi: identity gọi `.audience(List.of(...))` nhưng Nimbus/Spring Security ghi `aud` một phần tử dạng chuỗi theo RFC 7519; nhiều phần tử mới là mảng; decoder của integration-api chấp nhận cả hai dạng), `client_id`, `application_id`, `tenantId`, `scope` (chuỗi cách nhau dấu cách; chỉ scope thuộc module tenant đang bật), `modules`, `jti`, `iat`, `exp`. KHÔNG có `admin`, `platformAdmin`, `roles`, `permissions`, `svc`, `sid`.
- `integration-api` (cổng 8089) bắt buộc `aud` chứa `lms-integration` và `iss` khớp cấu hình; token nhân viên (không aud, `lms-staff`), storefront và `svc` bị `401` ngay ở decoder, đồng thời từ chối token mang `svc`/`admin`/`platformAdmin`. Pha I3 đã có endpoint đọc `/api/integration/v1/{books,inventory,orders}` (scope `books:read`, `inventory:read`, `orders:read`; xem `docs/api/integration-api.md` mục 8), giới hạn tốc độ theo application và log truy cập.
- `lms-api` (`ChannelAudienceFilter`) trả `401` cho token có `aud` chứa `lms-integration` (như `lms-storefront`). customer-api đã từ chối vì bắt buộc `lms-storefront`.
- Ở các service, `PermissionInterceptor` chỉ cho token này vào endpoint có `@IntegrationScope("<scope>")` và scope nằm trong claim `scope` (chuỗi cách nhau bằng dấu cách); còn lại `403 FORBIDDEN`/`TENANT_REQUIRED`/`MODULE_NOT_ENABLED`/`INSUFFICIENT_SCOPE`. Danh sách đóng đang gắn (pha I3): catalog `TitleController/get|lookup|lookupByIsbn` (`books:read`), sales `StockItemController/search|get` (`inventory:read`), `SalesOrderController/search|get` (`orders:read`); contact/notes/receipt và mọi endpoint ghi KHÔNG gắn.
- Mọi access token do identity phát (nhân viên, storefront, svc) thêm claim `jti` (UUID); FE không cần đổi.
- **JWKS hai khóa (xoay khóa ký, pha I5)**: `GET /.well-known/jwks.json` mặc định có MỘT khóa (khóa đang ký). Trong cửa sổ xoay khóa, đặt `AUTH_JWT_PREVIOUS_PUBLIC_KEY` (PEM X.509 của khóa CŨ) thì JWKS trả hai khóa: khóa đang ký trước, khóa cũ sau, mỗi khóa có `kid` riêng (thumbprint). Chỉ có thành phần công khai. Token luôn được ký bằng khóa hiện tại; khóa cũ chỉ để xác minh token đã phát. Mọi resource server (các service, lms-api, customer-api, integration-api) dùng `jwk-set-uri` nên chọn khóa theo `kid` của token, không phải đổi gì; identity tự dùng cùng tập khóa này cho decoder của nó. PEM cũ sai hoặc trùng khóa đang ký thì identity không khởi động (thông điệp nêu tên biến). Resource server cache JWKS (Nimbus mặc định 5 phút) nên khóa mới có hiệu lực xác minh sau tối đa chừng đó. Quy trình đầy đủ: `docs/architecture/JWT_KEY_ROTATION.md`.
- JWKS vẫn **không** mở ra internet: nginx giữ chặn `/.well-known/` (Q11); các service lấy trực tiếp từ identity trong mạng nội bộ.

## Việc của customer-api (agent khác)

1. Login: gửi `channel: "STOREFRONT"` + `tenantCode` từ đường dẫn. Refresh: `POST /internal/auth/refresh` với `channel: "STOREFRONT"` (logout không cần).
2. Bắt buộc `aud` chứa `lms-storefront` ở route cần đăng nhập. Cách rẻ nhất: `spring.security.oauth2.resourceserver.jwt.audiences: lms-storefront` (Spring Boot tự kiểm `aud`); route công khai không bị ảnh hưởng vì không có token. Token không `aud` (token `lms-api`) và token `svc` -> 401. Giữ `ShopSessionGuard`.
3. Không bao giờ chuyển `channel` từ client; BFF tự đặt.

## Việc của FE

Token storefront không dùng được ở ứng dụng nhân viên (`/api/**` trả 401) và ngược lại. Lưu riêng, không dùng chung `localStorage` với app nhân viên.

## Token dịch vụ `svc` cấp theo site key (Public Site Key, pha P2)

`POST /internal/storefront/access-tokens` với `{siteKey}` (thay cho `{tenantCode}`) cấp token `svc` như cũ (TTL 5 phút, `permissions` rỗng, không `aud`) và **thêm claim `application_id`** (id Application kiểu `PUBLIC_SITE`). Token cấp theo `{tenantCode}` không có claim này. Claim chỉ để ghi nhận/truy vết; quyền vẫn do `@StorefrontAccessible` và module quyết. Chi tiết ở `docs/api/public-api-v1.md` mục 7.

## Claim `tenantAccess` (chế độ chỉ đọc, ADR-0031 D4, gói F0)

- Claim tùy chọn `tenantAccess` trong access token gắn tenant: `READ_ONLY` hoặc `FULL`. Vắng claim = `FULL` (hành vi cũ). identity chỉ ghi `READ_ONLY` (không ghi `FULL`) và không ghi cho token phiên nền tảng (không `tenantId`) hay token platform admin. Hiện identity luôn dùng `TenantAccessModePort` mặc định `FULL` nên chưa token nào mang claim; gói G nối với pha hợp đồng.
- `PermissionInterceptor` (mọi service dùng security-common): token mang `tenantAccess` khác `FULL` (giá trị lạ cũng bị coi là chỉ đọc) thì mọi request không phải `GET`/`HEAD`/`OPTIONS` bị `403 TENANT_READ_ONLY` (thân `{"code":"TENANT_READ_ONLY","message":...}`), kể cả admin của tenant. Không bị chặn: endpoint `@PublicEndpoint` (đăng nhập, làm mới token), endpoint đánh dấu `@AllowedWhenReadOnly` (vẫn kiểm khóa quyền/module như thường), và platform admin không có `tenantId` hành động thay bằng `X-Tenant-Id`.
- FE: gặp `403 TENANT_READ_ONLY` thì hiển thị băng cảnh báo chỉ đọc và không cho thao tác ghi. Chưa có endpoint nào đổi hợp đồng; chưa đánh dấu `@AllowedWhenReadOnly` ở service nào (việc của gói G1).

## Token dịch vụ `svc = tenant-data` (ADR-0031 D1, gói F0)

- Do identity ký (cùng khóa/JWKS), dành cho điều phối xuất/xóa dữ liệu tenant (gói F2/G2), chưa có endpoint cấp ra ngoài; chỉ có phương thức phát `TenantDataTokenIssuerPort.issueTenantData` ở identity.
- Claim: `iss`, `sub` = UUID định danh yêu cầu xuất/xóa, `aud = ["lms-tenant-data"]`, `svc = "tenant-data"`, `tenantId`, `permissions = []`, `jti`, `iat`, `exp` (TTL mặc định và tối đa 10 phút). KHÔNG có `admin`, `platformAdmin`, `roles`, `username`, `modules`, `sid`, `tenantAccess`.
- Ở các service, `PermissionInterceptor` chỉ cho token này vào endpoint `@TenantDataAccess` (cần `aud` đúng và `tenantId`, nếu không `403 FORBIDDEN`/`TENANT_REQUIRED`); không kiểm khóa, không đăng ký khóa, không kiểm module. Ngược lại mọi token khác (người dùng kể cả admin/platform admin, storefront `svc`, integration) ở endpoint `@TenantDataAccess` bị `403 FORBIDDEN`, và token tenant-data ở endpoint không mang annotation cũng `403 FORBIDDEN`. `@TenantDataAccess` thắng `@PublicEndpoint`.
- `lms-api` và `customer-api` không phục vụ token này (endpoint `/internal/tenant-data/**` chỉ gọi nội bộ giữa identity và service).
