# Đề bài: Danh sách origin được phép theo tenant cho `customer-api` (CORS động)

- Ngày: 2026-11-17
- Liên quan: ADR-0011 (customer-api, token dịch vụ storefront, `StorefrontAccessCache`), ADR-0006 (multi-tenant), ADR-0009 (module SALES), `docs/requirements/2026-11-17-hop-dong-thue-dich-vu.md` (hợp đồng, suspend tenant), `docs/requirements/2026-11-16-customer-api.md`
- Trạng thái: người dùng đã chọn theo khuyến nghị của BA cho cả 8 câu hỏi; chờ `sa` thiết kế.

## 1. Mục tiêu

Cho phép mỗi tenant có website riêng (domain riêng) gọi từ **trình duyệt** vào `customer-api` công khai (`/public/t/{code}/...`) mà vẫn được phép theo CORS, và ngược lại chặn trình duyệt của trang lạ gọi API dưới danh nghĩa một tenant. Cụ thể:

- ✅ Gắn domain (origin) với tenant: platform admin khai báo origin nào được gọi storefront của tenant nào.
- ✅ Thay danh sách origin cố định trong `nginx.conf` (`map $http_origin $cors_origin`, chỉ `localhost:3000`, `localhost:5173`) bằng CORS động theo `Origin` và `{code}` do `customer-api` quyết định.

### 1.1. Giới hạn cam kết (bắt buộc ghi rõ cho tenant và FE)

⚠️ Header `Origin` do trình duyệt đặt; client không phải trình duyệt (curl, bot, server) giả được tùy ý. Vì vậy danh sách origin **chỉ là kiểm soát CORS, không phải xác thực**, không phải bí mật và **không cam kết chống bot cào**. Dữ liệu công khai (danh mục, giá, preview ngắn) vốn dự định xem được; chống lạm dụng vẫn là `limit_req` ở nginx. Tài liệu API và màn hình admin phải ghi câu này để tenant không hiểu nhầm đó là bảo mật.

## 2. Bối cảnh (đã xác nhận trong code)

- ✅ Tenant xác định bằng `{code}` trong đường dẫn; không có API key; không có trường domain/origin nào ở tenant.
- ✅ CORS do nginx: `map $http_origin $cors_origin` hằng số, `snippets/common.conf` thêm `Access-Control-Allow-Origin $cors_origin` và trả lời preflight ở nginx.
- ✅ `StorefrontAccessCache` (Caffeine, trong bộ nhớ từng instance) gọi identity `POST /internal/storefront/access-tokens`; kết quả dương giữ đến khi token còn dưới 60 giây (token `svc` sống 5 phút), kết quả phủ định giữ 60 giây; mọi lý do không khả dụng trả chung `404 SHOP_NOT_AVAILABLE`.
- ✅ Giới hạn tốc độ nginx hiện theo `$binary_remote_addr` (`public_read`, `public_form`, `public_preview`, `login`).
- ✅ Tenant hết hạn hợp đồng bị suspend; tắt SALES làm storefront không khả dụng (độ trễ tối đa khoảng 5 phút, ADR-0011 Q2).
- ✅ customer-api không có Kafka/JPA (ArchUnit); không có `tenant-common`.

## 3. Phạm vi

### 3.1. Làm
1. Lưu danh sách origin được phép theo tenant (tối đa 10 mỗi tenant), có cờ bật/tắt từng origin.
2. API platform (`/api/platform/**` qua `lms-api`) để platform admin xem, thêm, sửa cờ, xóa origin của tenant; admin FE hiển thị (phần FE do bên FE; đề bài này nêu hợp đồng API). Tenant admin **chỉ xem**.
3. `customer-api` quyết định CORS động cho `/public/**` theo `Origin` và `{code}` (kể cả preflight `OPTIONS`).
4. nginx chuyển tiếp `Origin` nguyên văn cho `/public/**` và không tự đặt CORS cho `/public/**`; sửa `snippets/common.conf` để không ghi đè hai lần.
5. Giới hạn tốc độ nginx thêm theo `{code}` (trích mã từ URI bằng `map`/regex), giữ theo IP.
6. Cờ cấu hình chế độ nghiêm, bật dần theo môi trường.

### 3.2. Không làm (ngoài phạm vi)
- ❌ Xác minh quyền sở hữu domain (DNS TXT, tệp).
- ❌ Tenant admin tự đăng ký/xóa origin.
- ❌ Captcha, WAF, chống bot.
- ❌ Khóa API (công khai hay bí mật), ký request server-to-server, gọi từ backend của tenant.
- ❌ Giới hạn tốc độ theo domain hoặc theo gói thuê.
- ❌ Wildcard origin (mặc định không hỗ trợ).
- ❌ Cơ chế riêng cho hết hạn hợp đồng/tắt SALES (xem 5.6).

## 4. Vai trò
| Vai trò | Quyền |
|---|---|
| Platform admin | Xem, thêm, bật/tắt, xóa origin của mọi tenant |
| Tenant admin | Chỉ xem origin của tenant mình |
| Khách/website (trình duyệt) | Không thao tác; chịu kiểm CORS |

## 5. Quy tắc nghiệp vụ

1. **Chuẩn hóa origin** khi lưu và khi so khớp: `scheme://host[:port]`, chữ thường, bỏ dấu `/` cuối, so khớp chính xác scheme + host + port (cổng mặc định 80/443 coi như không ghi). Từ chối khi lưu: có path/query/fragment, thiếu scheme, scheme khác `http`/`https`, ký tự `*`, giá trị `null`. Khi gặp `Origin: null` từ trình duyệt: không cho phép.
2. **Tối đa 10 origin mỗi tenant**; trùng (sau chuẩn hóa) trong cùng tenant bị từ chối. Ghi nhận ở đề: một origin có được gán cho nhiều tenant hay không (xem câu hỏi mở C1).
3. **Cờ bật/tắt** từng origin; origin tắt coi như không có. Thay đổi có hiệu lực trong khoảng 60 giây (TTL của cache danh sách origin).
4. **Luôn được phép** (không cần khai báo): origin của domain nền tảng (cấu hình) và `http://localhost:3000`, `http://localhost:5173` khi chạy dev (cờ cấu hình; xem 8).
5. **Quyết định CORS** cho request `/public/t/{code}/**`: phân giải storefront trước (như hiện tại); nếu `Origin` thuộc danh sách (bật) của tenant đó hoặc thuộc nhóm "luôn được phép" thì trả `Access-Control-Allow-Origin: <origin>` (kèm `Vary: Origin`, các header cho phép như cấu hình nginx hiện tại); ngược lại **bỏ header CORS** (trình duyệt tự chặn). Không trả mã lỗi riêng kiểu `ORIGIN_NOT_ALLOWED`.
6. **Hết hạn/tắt SALES/suspend**: không thêm cơ chế riêng. Origin chỉ có tác dụng khi tenant ACTIVE và SALES bật, dựa vào `404 SHOP_NOT_AVAILABLE` hiện có; độ trễ tối đa khoảng 5 phút.
7. **Request không có `Origin`** (server, curl, ứng dụng không phải trình duyệt) vẫn được xử lý như hiện nay; origin chưa đăng ký chỉ mất CORS, không bị 403.
8. **Không lộ tenant**: phản hồi (thân, mã, header) cho origin không hợp lệ không được khác theo việc tenant có tồn tại hay không; tenant không khả dụng vẫn là `404 SHOP_NOT_AVAILABLE` cùng thân cố định.
9. **Đường dẫn không có `{code}`** (`/public/auth/refresh`, `/public/auth/logout`, `/public/site/**`): xem câu hỏi mở C2; mặc định đề xuất chỉ cho domain nền tảng và localhost dev ở chế độ nghiêm.
10. **Chế độ nghiêm** (cờ cấu hình theo môi trường): tắt = hành vi tương thích (ví dụ cho phép như hiện nay cho origin chưa khai báo, chỉ log đếm); bật = áp quy tắc 5. Cho phép bật dần.

## 6. Phân quyền
- API platform: khóa `Controller/method` theo ADR-0003; ghi là `@PlatformAdminOnly`. Tên controller/method chốt ở thiết kế, không đổi sau đó (là khóa quyền).
- Tenant admin xem: endpoint đọc riêng theo tenant của token, kiểm khóa động như thường lệ (hoặc `@AuthenticatedOnly` nếu `sa` thấy hợp); không có thao tác ghi cho tenant admin.
- Đường dẫn công khai `/public/**` vẫn do `customer-api` quyết (chỉ xác thực/CORS), không thêm quyền.

## 7. API và dữ liệu (mức khái niệm, `sa` chốt chi tiết)

### 7.1. Dữ liệu
- Bảng mới ở `identity_db` (ví dụ `tenant_storefront_origins`): `id`, `tenant_id`, `origin` (đã chuẩn hóa), `enabled`, `created_at`, `created_by`, `updated_at`; duy nhất `(tenant_id, origin)`; kiểm tối đa 10 ở tầng ứng dụng.
- Quyết định RLS: có `tenant_id` + `@TenantId` + RLS/FORCE, hoặc nằm trong `TENANT_EXEMPT_TABLES` như `tenants` (platform admin không có tenant trong token; xem câu hỏi mở C3).
- ⚠️ Số migration identity kế tiếp phải kiểm tra ngay trước khi tạo: `V17` đã bị giữ chỗ bởi sales B1.6/ADR-0011 và hợp đồng thuê (ADR-0012). File nằm ở `services/system-service/src/main/resources/db/migration/identity/`.

### 7.2. API platform (qua `lms-api`, `/api/platform/**`)
- `GET /api/platform/tenants/{tenantId}/storefront-origins`
- `POST /api/platform/tenants/{tenantId}/storefront-origins` body `{origin}`
- `PUT /api/platform/tenants/{tenantId}/storefront-origins/{id}` body `{enabled}`
- `DELETE /api/platform/tenants/{tenantId}/storefront-origins/{id}`
- Lỗi dự kiến: `400 ORIGIN_INVALID`, `409 ORIGIN_DUPLICATED`, `409 ORIGIN_LIMIT_EXCEEDED`, `404` không tìm thấy. Tên cuối do `sa`/`be-dev` chốt.
- Tenant admin xem: một `GET` theo tenant của token (đường dẫn do `sa` chốt, không dùng `/api/platform/**`).

### 7.3. Lấy danh sách origin ở `customer-api`
- Hướng đề xuất: identity trả `allowedOrigins` cùng lời gọi cấp token storefront (`StorefrontAccess`), để không thêm một vòng gọi. `sa` quyết (xem C4).

## 8. Hành vi

| Tình huống | Kết quả |
|---|---|
| Preflight `OPTIONS /public/t/{code}/books` với origin đã khai báo (bật) | Có `Access-Control-Allow-Origin: <origin>`, `Vary: Origin`, các header/phương thức cho phép |
| Origin chưa khai báo hoặc đã tắt | Không có header CORS (trình duyệt chặn); request không `Origin` vẫn 200 |
| Origin của tenant A gọi `{code}` của tenant B | Không có header CORS |
| `{code}` không khả dụng | `404 SHOP_NOT_AVAILABLE` cùng thân cố định, không phân biệt lý do |
| `Origin: null` | Không cho phép |
| Dev (profile dev) | `localhost:3000`, `localhost:5173` luôn được phép |
| Thêm/xóa/tắt origin | Hiệu lực trong khoảng 60 giây |
| Chế độ nghiêm tắt | Tương thích ngược (xem 5.10) |

Giới hạn tốc độ nginx:
- Giữ vùng theo IP hiện có.
- Thêm vùng theo `{code}` (mã trích từ `/public/t/{code}/...` bằng `map`/regex; đường dẫn không có mã thì khóa rỗng, không bị giới hạn theo vùng này).
- ⚠️ Rủi ro sau CDN/LB: nếu không cấu hình `set_real_ip_from` + `real_ip_header` thì mọi khách chung một IP; vùng theo `{code}` vẫn hoạt động nhưng vùng theo IP sẽ chặn hàng loạt.

## 9. Ràng buộc cho `sa`
1. Chuyển CORS của `/public/**` từ nginx sang `customer-api`: nginx chuyển tiếp `Origin` nguyên văn, preflight `OPTIONS` đi qua `customer-api` (không trả lời ở nginx); sửa `snippets/common.conf` để `/public/**` không ghi đè hai lần; CORS cho `/api/**` (lms-api) giữ như cũ.
2. Cache danh sách origin riêng (hoặc tách khỏi token `svc`) với TTL khoảng 60 giây, ngắn hơn vòng đời token 5 phút; không nhúng origin vào claim token.
3. Cache Caffeine trong bộ nhớ từng instance, **không vô hiệu hóa chéo instance**; `customer-api` không có Kafka (ArchUnit cấm `org.springframework.kafka`). Hiệu lực "khoảng 60 giây" dựa hoàn toàn vào TTL.
4. Không phá hợp đồng ArchUnit của `customer-api` (không JPA, không `security-common`).
5. `IdentityGatewayController` của `lms-api` chuyển nguyên tiền tố `/api/platform/**` sang identity; API mới đi theo tiền lệ đó.
6. Tenant mặc định `00000000-0000-0000-0000-0000000000d1` và profile dev: không để dev bị chặn.

## 10. Tiêu chí nghiệm thu (kiểm được bằng test)
- ✅ AC1. Preflight với origin đã khai báo và bật: có đúng `Access-Control-Allow-Origin` bằng origin gửi lên, có `Vary: Origin`; origin lạ: không có header CORS.
- ✅ AC2. Origin của tenant A gọi `/public/t/{codeB}/...`: không có header CORS; gọi `/public/t/{codeA}/...`: có.
- ✅ AC3. Origin tắt hoặc bị xóa: sau tối đa khoảng 60 giây không còn header CORS (đồng hồ giả trong test cache).
- ✅ AC4. Chuẩn hóa: `HTTPS://Shop.Example.com/` và `https://shop.example.com` coi là một origin; khác cổng/scheme là khác origin; có path, `*`, `null`, thiếu scheme bị `400 ORIGIN_INVALID`.
- ✅ AC5. Thêm origin thứ 11 của một tenant: `409`; thêm trùng: `409`.
- ✅ AC6. `Origin: null` không được cho phép.
- ✅ AC7. Request không có `Origin` vẫn 200 như hiện tại (không hồi quy).
- ✅ AC8. Tenant không tồn tại/bị suspend/tắt SALES: `404 SHOP_NOT_AVAILABLE` cùng thân, dù origin hợp lệ hay không; không có mã riêng `ORIGIN_NOT_ALLOWED`.
- ✅ AC9. Platform admin gọi được API quản lý; tenant admin chỉ gọi được phần xem; thiếu khóa 403; không token 401.
- ✅ AC10. `localhost:3000` và `localhost:5173` được phép ở profile dev; domain nền tảng luôn được phép.
- ✅ AC11. nginx: vùng theo `{code}` trả 429 khi vượt ngưỡng cho một mã mà không ảnh hưởng mã khác; vùng theo IP giữ nguyên; `nginx -t` hợp lệ.
- ✅ AC12. Preflight không còn bị nginx trả lời cho `/public/**`; `/api/**` vẫn CORS như cũ; không có hai header `Access-Control-Allow-Origin` trong một phản hồi.
- ✅ AC13. Test cách ly: platform admin chỉ sửa được origin của tenant được chỉ định (không lẫn tenant).
- ✅ AC14. Chế độ nghiêm tắt cho kết quả tương thích như quy tắc 5.10; bật theo cờ cấu hình.
- ✅ AC15. Tài liệu API (`docs/api/customer-api*.md`) và màn hình admin ghi rõ giới hạn ở mục 1.1.

## 11. Giả định
- A1. Chỉ gọi từ trình duyệt (frontend thuần) ở giai đoạn này.
- A2. Platform admin nhập domain thủ công, không có xác minh sở hữu.
- A3. Origin gồm `scheme://host[:port]`, so khớp chính xác, không wildcard.
- A4. Độ trễ hiệu lực 60 giây (origin) và tối đa khoảng 5 phút (hết hạn/tắt SALES) là chấp nhận được.
- A5. Dữ liệu công khai nên không cần che; chống lạm dụng chỉ là làm chậm (nginx).

## 12. Câu hỏi mở
- C1. Một origin có được gán cho nhiều tenant không? Đề xuất: không (duy nhất toàn hệ thống) để domain xác định rõ một tenant; nhưng bàn với `sa` nếu có trường hợp chia sẻ domain.
- C2. Quy tắc CORS cho `/public/auth/refresh|logout` và `/public/site/**` (không có `{code}`): đề xuất chỉ domain nền tảng và localhost dev; refresh/logout của storefront tenant có cần cho origin của tenant không (nếu cần, dựa vào đâu nếu thiếu `{code}`)?
- C3. Bảng origin: RLS + `@TenantId` hay `TENANT_EXEMPT_TABLES`?
- C4. Nơi lấy `allowedOrigins`: nhúng vào lời gọi cấp token storefront (đề xuất) hay endpoint riêng; cache cùng hay tách.
- C5. Nên chốt giá trị TTL cache origin và cache phủ định (đề xuất 60 giây) và cờ nghiêm theo từng môi trường (dev tắt, staging cảnh báo, prod bật).
- C6. Có cho phép `http://` (không TLS) cho domain thật ở production không, hay chỉ `https` (ngoại lệ localhost)? Đề xuất: chỉ `https` ở prod.
- C7. Tên endpoint và khóa quyền cuối cùng cho phía tenant admin xem.
