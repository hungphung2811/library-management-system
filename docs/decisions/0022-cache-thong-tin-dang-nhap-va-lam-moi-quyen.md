# ADR-0022: Redis cho luồng đăng nhập và nút "Làm mới quyền" ở menu người dùng

- Trạng thái: đề xuất (SA). Chưa cài đặt. Cần người dùng chốt các câu hỏi ở mục 8.
- Liên quan: ADR-0003 (khóa `Controller/method`, quyền nằm trong JWT), ADR-0006 (RLS, `TenantContext`), ADR-0007 (cache menu trên Redis của system-service), ADR-0009 (module theo tenant), ADR-0016 (platform admin `X-Tenant-Id`), ADR-0017 (họ phiên, `sid`, cửa sổ ân hạn refresh).
- Yêu cầu gốc: "check cho tôi xem luồng đăng nhập có thêm được redis vào giảm tải user info get data từ database không. nếu lúc phân quyền lại thì thêm API refresh cache ở toolbar user menu cho user tự chủ động".

## 1. Bối cảnh và mục tiêu

### 1.1. Câu hỏi cần trả lời

1. Thêm Redis vào luồng đăng nhập (login, refresh, switch-tenant, các API "của tôi") có giảm tải DB đáng kể không?
2. Khi quản trị viên đổi phân quyền, người dùng có cách nào tự bấm để quyền mới có hiệu lực ngay, không phải đăng xuất/đăng nhập lại?

### 1.2. Ràng buộc

- Quyền đã nằm trong JWT (ADR-0003): mọi service kiểm khóa theo claim `permissions`/`admin`/`modules`, không đọc DB mỗi request. Quyền mới chỉ có hiệu lực khi đăng nhập hoặc làm mới token kế tiếp. Access token sống 15 phút (`auth.jwt.access-token-ttl: PT15M`, `services/identity-service/src/main/resources/application.yml:45`), refresh token 7 ngày (dòng 49).
- Multi-tenant bằng RLS (ADR-0006): mọi dữ liệu role/gán role là của tenant; khóa cache (nếu có) phải gồm `tenantId`.
- Không có atomicity xuyên DB/Redis; Kafka best-effort (ADR-0001).
- RAM máy dev rất chật: Redis đang chạy với `maxmemory 64mb`, `mem_limit 128m` (`docker-compose.yml:58-62`) và chỉ được coi là cache phụ.
- Ưu tiên giải pháp đơn giản khớp khuôn sẵn có.

## 2. Hiện trạng (bằng chứng từ code)

### 2.1. Ai đang dùng Redis

| Thành phần | Dùng Redis? | Bằng chứng |
|---|---|---|
| system-service, context `menu` | ✅ Có: cache cây menu **chưa lọc** theo tenant, khóa `tenant:{id}:system:menu:tree`, TTL 10 phút, xóa sau commit khi sửa menu, lỗi Redis chỉ WARN | `services/system-service/src/main/java/lms/system/menu/infrastructure/cache/RedisMenuCacheAdapter.java`, `application.yml:20-29, 104-109`; health Redis tắt (`application.yml:41-43`) |
| identity-service | ❌ Không có dependency, cấu hình hay biến môi trường Redis nào | grep `redis` toàn repo chỉ ra system-service và `database-common` |
| lms-api, catalog, circulation, support, sales, customer-api | ❌ Không | như trên; customer-api dùng Caffeine in-process (`StorefrontAccessCache`) |
| `database-common` | ℹ️ Có provider `RedisConnection` nhưng không service nào khai báo kết nối Redis qua `database.connections.*` | `shared-libraries/database-common/.../provider/redis/` |

Ghi chú: `docker-compose.yml:60` ghi rõ "Redis chỉ làm cache menu".

### 2.2. Request thông thường KHÔNG chạm DB để lấy "user info"

- Kiểm quyền: `PermissionInterceptor` đọc claim JWT; đăng ký khóa đi qua `CachingPermissionKeyRegistrar` (tập `ConcurrentHashMap` trong bộ nhớ, `shared-libraries/security-common/.../permission/CachingPermissionKeyRegistrar.java:25`), chỉ gọi identity lần đầu mỗi khóa. Không có tra DB theo người dùng mỗi request, không kiểm `sid` với DB.
- FE dựng phiên từ JWT: `sessionFromToken(token)` (`library-management-system-fe/src/auth/AuthProvider.tsx:44`), không gọi API "me" khi khởi động.
- `GET /api/menus/me`: system-service đọc cây menu của tenant từ Redis (miss thì DB), rồi lọc theo claim của JWT (`GetMyMenuUseCase.execute`). Không có dữ liệu theo người dùng trong cache. FE gọi với `staleTime: 60_000` (`src/ui/useNavItems.ts:76`).
- `GET /api/users/me`, `/api/users/me/reader-profile`: chỉ trang Hồ sơ gọi (`src/pages/Profile.tsx:57-58`). `GetUserUseCase.me` đọc `findInTenant` + trạng thái khóa (`services/identity-service/.../user/application/GetUserUseCase.java:36-44`). Tần suất thấp.
- `GET /api/me/tenants`: FE `staleTime: 5 phút` (`src/auth/AuthProvider.tsx:132`).

Kết luận 2.2: phần lớn tải "user info" đã được JWT gánh. DB chỉ bị chạm khi **cấp token** (login, refresh mỗi khoảng 15 phút mỗi phiên đang mở, switch-tenant).

### 2.3. Đếm truy vấn khi cấp token

Đường chung: `TokenService.issue` (`services/identity-service/src/main/java/lms/identity/auth/application/TokenService.java`). Ký hiệu: M = số tenant người dùng là thành viên, R = số role của người dùng trong tenant đã chọn.

| Bước | File:dòng | Câu lệnh SQL (ước lượng) | Cache được? |
|---|---|---|---|
| L1. Tìm credential theo username | `LoginUseCase.java:88` | 1 SELECT | ❌ Không (hash mật khẩu, `failedAttempts`, `lockedUntil` phải tươi) |
| L2. bcrypt | `LoginUseCase.java:92`, `BcryptPasswordHasher` (strength mặc định 10) | 0 (CPU, ⚠️ khoảng vài chục ms mỗi lần, chưa đo) | ❌ |
| T1. `users.disabled` | `TokenService.java:161` → `GetUserDirectoryInfoUseCase` | 1 SELECT | ❌ Không nên (trạng thái vô hiệu là kiểm soát an ninh) |
| T2. Chọn/kiểm tenant | `TokenService.java:166` → `ResolveLoginTenantUseCase` (login) / `VerifyTenantAccessUseCase` (refresh) / `SwitchTenantUseCase` | `findActiveByUser` 1 + **`tenants.findById` M lần (N+1)** (`AccessibleTenants.java:28-30`) + `enabledOf` 1 (`:45`) + `findLastTenant` 1 (`ResolveLoginTenantUseCase.java:46`, chỉ login) + `memberships.find` 1 (`AccessibleTenants.java:41`) + UPDATE last tenant nếu đổi (`ResolveLoginTenantUseCase.java:63`, `SwitchTenantUseCase`) | ⚠️ Một phần (danh sách tenant + module), nhưng membership/trạng thái tenant là điều kiện truy cập |
| T3. Role + permission | `TokenService.java:180` → `GetUserAccessUseCase.execute` (REQUIRES_NEW) | `user_roles` 1 + `roles` 1 (`AccessControlPersistenceAdapter.java:67-68`) + **`role_permissions` có thể R lần** (`RoleJpaEntity.java:43` `@ElementCollection(fetch = EAGER)`, identity không cấu hình `default_batch_fetch_size`) ⚠️ chưa đo số câu thật | ⚠️ Có thể, nhưng xem mục 3.2 |
| T4. Module của tenant đã chọn | `TokenService.java:186` → `ListTenantModulesUseCase.enabledCodes` | 1 SELECT, **trùng dữ liệu** đã có trong `choice.available()` (`TenantSummary.modules`, `TenantSummary.java:11`) | Không cần cache, bỏ được hẳn |
| T5. Lưu refresh token (xoay) | `TokenService.java:211` → `AuthPersistenceAdapter.rotate` | 1 UPDATE có điều kiện (refresh/switch) + 1 INSERT | ❌ Ghi |
| L3. Ghi đăng nhập thành công | `LoginUseCase.java:101-102` | `save` qua `merge` (entity id gán sẵn): SELECT + UPDATE | ❌ Ghi |
| R1. Refresh: tìm refresh token | `RefreshTokenUseCase.java:96` | 1 SELECT | ❌ Không (thu hồi, phát hiện dùng lại) |
| R2. Refresh: credential | `RefreshTokenUseCase.java:117` | 1 SELECT | ❌ Không (khóa tài khoản) |

Tổng ước lượng (⚠️ chưa đo bằng thống kê Hibernate):
- Login: khoảng 11 + M + R câu lệnh, khoảng 7 lần mượn kết nối.
- Refresh: khoảng 9 + M + R câu lệnh.
- Switch-tenant: khoảng 10 + M + R câu lệnh.

Thêm: mỗi lần mượn kết nối, `TenantRlsConnectionInitializer` chạy 1 `set_config` khi mượn và 1 khi trả (`shared-libraries/tenant-common/src/main/java/lms/tenant/TenantRlsConnectionInitializer.java`), tức khoảng 14 round trip phụ cho một lần login. Trên mạng nội bộ đây là chi phí nhỏ, ⚠️ chưa đo.

### 2.4. Tần suất thực tế

- Login bị nginx giới hạn 10 request/phút/IP, burst 5 (`infrastructure/nginx/nginx.conf:38, 87`); chi phí chủ yếu là bcrypt (CPU), không phải DB.
- Refresh: khoảng 1 lần/15 phút cho mỗi tab có phiên đang hoạt động (FE gọi khi 401 hoặc lúc nạp trang, `src/api/client.ts:15-38`). Ví dụ 1.000 phiên đồng thời → khoảng 1,1 refresh/giây → khoảng 15 câu SQL/giây cho identity_db. Mức này rất nhỏ với PostgreSQL. ⚠️ Con số là ước lượng, chưa có đo hiệu năng cho identity (chỉ có `SalesPerfIT`).
- `/api/users/me`, `/api/me/tenants`: thấp (chỉ trang Hồ sơ, FE cache 5 phút).

### 2.5. Sự kiện đổi quyền đã có

| Thay đổi | Sự kiện hiện có | Hệ quả với phiên |
|---|---|---|
| Gán role cho người dùng | `RoleAssignedEvent` qua Kafka topic `access-events` (`UserRoleUseCase.assign`, dòng 36-43) | Không ai tiêu thụ; quyền mới có khi refresh kế tiếp (tối đa 15 phút) |
| Thu hồi role | `RoleRevokedEvent` (`UserRoleUseCase.revoke`, dòng 47-54) | `AccessEventConsumer` thu hồi **mọi refresh token** của người dùng (`auth/infrastructure/messaging/AccessEventConsumer.java`): lần refresh sau bị 401, phải đăng nhập lại. Best-effort (Kafka) |
| Sửa permission của role (grant/revoke/update/delete) | ❌ Không phát sự kiện (`RoleManagementUseCase.java:51-120`) | Quyền mới có khi refresh kế tiếp |
| Bật/tắt module tenant | `TenantModuleEvents.Enabled/Disabled` (`EnableTenantModuleUseCase.java:80`, `DisableTenantModuleUseCase.java:78`) | Claim `modules` cập nhật khi refresh kế tiếp |
| Vô hiệu hóa người dùng | Kiểm khi cấp token (`TokenService.java:161-164`) | Refresh kế tiếp bị chặn |
| Sửa menu | Xóa cache Redis sau commit (ADR-0007) | Có hiệu lực ngay với `/api/menus/me` lần gọi sau (FE còn giữ 60 giây) |

**Điểm mấu chốt**: thứ "cache" làm người dùng thấy quyền cũ không phải DB hay Redis, mà là **access token còn hạn** (tối đa 15 phút) cộng cache React Query của FE (menu 60 giây, danh sách tenant 5 phút). Muốn quyền mới có hiệu lực ngay chỉ cần cấp lại access token, việc mà `POST /api/auth/refresh` đã làm.

## 3. Quyết định

### 3.1. Khuyến nghị

1. ✅ **Chưa thêm Redis vào identity-service.** Lý do: đường nóng (mọi request nghiệp vụ) đã không chạm DB nhờ JWT; DB chỉ bị chạm khi cấp token, tần suất khoảng 1/15 phút mỗi phiên; phần lớn câu lệnh khi cấp token là loại không được phép cache (credential, refresh token, trạng thái khóa/vô hiệu, ghi xoay token). Phần cache được (role/permission/tenant) chỉ tiết kiệm vài câu SELECT có chỉ mục, đổi lại thêm một dependency vào đường đăng nhập và một bài toán vô hiệu hóa khó (mục 3.2).
2. ✅ **Tối ưu truy vấn ngay trong identity (phương án A, không cache)**: bỏ N+1 tenant, nạp `role_permissions` theo lô, bỏ câu module trùng. Giảm khoảng 1 + (M-1) + (R-1) câu mỗi lần cấp token mà không đổi hành vi hay nhất quán.
3. ✅ **Nút "Làm mới quyền" ở menu người dùng dùng lại `POST /api/auth/refresh` hiện có**, không thêm endpoint BE mới. Bấm nút = xoay refresh token, nhận access token mới mang role/permission/module mới nhất từ DB, rồi FE bỏ cache React Query. Hiệu lực ngay ở tab đó.
4. ⏳ Thêm giới hạn tốc độ cho `/api/auth/refresh` ở nginx (mức cụ thể chờ chốt, mục 8 câu Q2) và cooldown ở FE.

### 3.2. Vì sao cache quyền theo người dùng không đáng làm lúc này

- Cache quyền chỉ được đọc lúc cấp token. Token đã là bản "cache" 15 phút của quyền; cache thêm một tầng trước token chỉ làm quyền cũ sống lâu hơn (TTL cache + 15 phút) nếu vô hiệu hóa lỡ nhịp.
- Vô hiệu hóa đúng là khó: sửa permission của **một role** ảnh hưởng mọi người có role đó trong tenant, nhưng hiện không có sự kiện (mục 2.5). Phải xóa theo tenant (khóa phiên bản `tenant:{id}:identity:authz:ver`) hoặc quét người dùng của role. Thêm các nguồn đổi khác: module tenant, membership, trạng thái tenant, cờ `is_admin`.
- Sai sót ở đây là lỗi bảo mật (giữ quyền đã bị thu hồi), không phải chỉ là dữ liệu cũ. Nút "Làm mới quyền" cũng mất tác dụng nếu chính cache trả quyền cũ, khiến cần thêm API xóa cache, tức thêm bề mặt.
- Redis trong đường cấp token: Redis chết phải fail-open về DB (như menu), nghĩa là hệ thống phải chịu được tải DB đầy đủ trong mọi trường hợp, nên cache không giảm được năng lực DB cần dự phòng.

### 3.3. Điều kiện để xem lại (khi nào mới cần Redis)

Xem lại ADR này khi có **ít nhất một** điều kiện sau, đo bằng số liệu thật:

| Điều kiện | Ngưỡng gợi ý | Hướng xử lý khi đó |
|---|---|---|
| Thời gian DB chiếm phần lớn p95 của `/internal/auth/refresh` sau khi đã làm phương án A | p95 refresh > 200 ms do DB, hoặc CPU identity_db > 60% kéo dài | Cache `TenantChoice.available` + `UserAccess` theo `(tenantId, userId)` bằng Caffeine trước, Redis khi có nhiều instance identity |
| Chạy nhiều instance identity và cần cache dùng chung | ≥ 2 instance | Redis, khóa `tenant:{tenantId}:identity:access:{userId}` theo mẫu khóa của ADR-0007 |
| Cần thu hồi quyền **tức thì** (dưới 15 phút) cho mọi service | Yêu cầu nghiệp vụ/bảo mật rõ ràng | Không phải cache người dùng mà là "phiên bản quyền" hoặc denylist `sid` trên Redis, kiểm ở `PermissionInterceptor` của mọi service (phương án C). Thay đổi lớn, cần ADR riêng |

## 4. Các phương án đã cân nhắc

| | A. Không Redis, tối ưu truy vấn (+ Caffeine khi cần) | B. Redis cache `UserAccess`/tenant theo `(tenantId, userId)`, TTL ngắn + xóa khi đổi quyền | C. B + pub/sub (hoặc "phiên bản quyền" kiểm mỗi request) |
|---|---|---|---|
| Giảm tải DB | Khoảng 2-5 câu mỗi lần cấp token (tùy M, R) | Thêm khoảng 4-6 câu SELECT mỗi lần cấp token khi trúng cache | Như B; nếu kiểm phiên bản mỗi request thì thêm 1 lệnh Redis cho **mọi** request |
| Độ nhất quán quyền | Như hiện tại (≤ 15 phút, hoặc ngay khi bấm làm mới) | Kém hơn nếu lỡ xóa cache: ≤ TTL cache + 15 phút | Tốt nhất: có thể thu hồi gần tức thì |
| Độ phức tạp | Thấp, chỉ sửa adapter/query | Trung bình-cao: bảng vô hiệu hóa nhiều nguồn, sự kiện mới cho sửa role | Cao: mọi service phụ thuộc Redis, thêm đường nóng |
| Vận hành | Không thêm gì | identity thêm Redis (cấu hình, timeout 200 ms, health tắt như system-service) | Redis thành thành phần bắt buộc thực tế của mọi service |
| RAM máy dev | 0 | Redis đã chạy sẵn, thêm ít bộ nhớ | Như B, cộng client Lettuce ở mọi service |
| Rủi ro bảo mật | Không thêm | Rò quyền cũ hoặc chéo tenant nếu khóa sai | Như B; Redis chết thì phải chọn fail-open (mất thu hồi) hay fail-closed (sập hệ thống) |
| **Kết luận** | ✅ **Chọn** | ❌ Loại lúc này (lợi ít, rủi ro bảo mật) | ❌ Loại lúc này (vượt nhu cầu); để dành cho điều kiện ở mục 3.3 |

Phương án phụ cho nút làm mới quyền:

| | D1. Dùng lại `POST /api/auth/refresh` | D2. Endpoint mới `POST /api/auth/me/refresh` (`@AuthenticatedOnly`) | D3. Endpoint xóa cache server (`POST /api/me/cache/eviction`) |
|---|---|---|---|
| Hiệu lực quyền mới | Ngay (token mới) | Ngay (bên trong vẫn gọi đúng `RefreshTokenUseCase`) | ❌ Không đủ: JWT cũ vẫn mang quyền cũ, vẫn phải refresh |
| Việc BE | Không (chỉ nginx nếu chọn rate limit) | Thêm controller ở identity + lms-api, khóa mới, test | Có cache thì mới có gì để xóa (cần phương án B) |
| Bảo mật | Đã được ADR-0017 bảo vệ (xoay, ân hạn, phát hiện dùng lại, kiểm kênh/tenant) | Như D1, thêm kiểm `sub` của access token khớp `userId` của refresh token | Thêm bề mặt tấn công |
| **Kết luận** | ✅ **Chọn** | ❌ Loại: trùng chức năng, không thêm giá trị | ❌ Loại |

## 5. Thiết kế

### 5.1. Phương án A: tối ưu truy vấn trong identity (không đổi hợp đồng)

| # | Chỗ sửa | Nội dung | Tầng |
|---|---|---|---|
| A1 | `tenant/application/AccessibleTenants.of` + `TenantRepository` | Thay `tenants.findById` từng membership (`AccessibleTenants.java:30`) bằng một lần `findAllByIds(Collection<UUID>)` (port mới trên `TenantRepository`, adapter dùng `findAllById`). Giữ nguyên thứ tự sắp xếp (joinedAt, code) và lọc `isActive` | domain port + infrastructure adapter |
| A2 | `accesscontrol` nạp role | Nạp `role_permissions` theo lô: hoặc `spring.jpa.properties.hibernate.default_batch_fetch_size: 32` trong `services/identity-service/src/main/resources/application.yml` (đọc lại file, thêm vào khối `spring.jpa` sẵn có, không tạo khối trùng), hoặc truy vấn `JOIN FETCH` riêng cho `findAllByIds`. Khuyến nghị: `@BatchSize`/`default_batch_fetch_size` (một dòng, ảnh hưởng toàn service nhưng chỉ làm giảm số câu). be-dev chốt sau khi đo | infrastructure |
| A3 | `TokenService.issue` (`TokenService.java:186`) | Lấy module của tenant đã chọn từ `choice.available()` (phần tử có `id == tenantId`); chỉ gọi `tenantAccess.modules(tenantId)` khi không tìm thấy (phòng hờ). Lưu ý: `available` chỉ chứa tenant đang hoạt động mà người dùng truy cập được, đúng điều kiện cấp token | application |

Không đổi: thứ tự kiểm tra (credential, khóa, mật khẩu, vô hiệu, tenant), bất biến R20 (mỗi bước transaction ngắn, không gọi `TokenService` trong transaction mở), chính sách RLS.

### 5.2. Nút "Làm mới quyền" (FE, dùng lại API hiện có)

```mermaid
sequenceDiagram
    participant U as Người dùng (UserMenu)
    participant FE as FE (refreshSession)
    participant N as nginx
    participant B as lms-api
    participant I as identity
    U->>FE: Bấm "Làm mới quyền"
    FE->>FE: Ghi lại claim cũ (permissions, modules, admin, roles)
    FE->>N: POST /api/auth/refresh {refreshToken}
    N->>B: (limit_req zone refresh, nếu chốt)
    B->>I: POST /internal/auth/refresh
    I->>I: RefreshTokenUseCase: kiểm token/kênh/tenant, credential, membership, đọc role+permission+module mới, xoay refresh token (giữ sid)
    I-->>FE: 200 {accessToken mới, refreshToken mới}
    FE->>FE: setTokens; so claim cũ/mới; bỏ cache React Query ("api", "tenants")
    FE-->>U: "Đã cập nhật quyền" hoặc "Quyền không thay đổi"
```

Hợp đồng (đã có, không đổi):

- `POST /api/auth/refresh` (lms-api) → `POST /internal/auth/refresh` (identity, `@PublicEndpoint`, `AuthController.java:95-102`).
- Request: `{ "refreshToken": "<raw>" }` (kênh mặc định STAFF).
- Response 200: `TokenResponse` như đăng nhập (`accessToken`, `refreshToken`, `tokenType`, `expiresIn`, `mustChangePassword`, `tenantId`, `availableTenants`...).
- Lỗi:
  - `401 UNAUTHORIZED`: refresh token không hợp lệ/hết hạn/đã bị thu hồi. Trường hợp điển hình sau khi bị **thu hồi role** (`AccessEventConsumer` đã thu hồi mọi refresh token): FE đưa về trang đăng nhập với thông báo "Phiên đã hết hiệu lực do thay đổi phân quyền, vui lòng đăng nhập lại".
  - `401 REFRESH_TOKEN_SUPERSEDED`: tab khác vừa xoay (ADR-0017 mục 2.2). FE đọc lại refresh token mới nhất trong `localStorage` rồi thử lại một lần; **không** xóa phiên.
  - `403 NO_TENANT_ACCESS`: không còn là thành viên tenant đang dùng. FE mở hộp chọn đơn vị (`TenantSwitchModal`) hoặc về đăng nhập.
  - `403 ACCOUNT_DISABLED`, `423 ACCOUNT_LOCKED` (`AuthExceptionHandler.java:78-86`): về trang đăng nhập kèm thông báo.
- Khóa quyền: không có (endpoint công khai bằng refresh token). Không cần `@AuthenticatedOnly` vì refresh token chính là bằng chứng; người gọi chỉ tác động tới đúng phiên của mình (cùng `sid`, ADR-0017).

Hành vi chi tiết:

- Chỉ phiên ở tab hiện tại nhận quyền mới ngay. Thiết bị/phiên khác của cùng người dùng tự nhận ở lần refresh kế tiếp (≤ 15 phút). Đúng với mô hình họ phiên ADR-0017: mỗi `sid` xoay độc lập. Các tab cùng trình duyệt dùng chung refresh token trong `localStorage` nên sẽ thấy token mới khi đọc lại (theo cơ chế ADR-0017 H4).
- FE **phải** gọi qua `refreshSession()` sẵn có (`src/api/client.ts:15`), không tự `fetch` riêng, để dùng chung single-flight (và Web Locks nếu FE đã có) và tránh tự gây `REFRESH_TOKEN_SUPERSEDED`.
- Hiện `refreshSession()` xóa phiên với mọi lỗi `< 500` (`client.ts:31-32`), kể cả `REFRESH_TOKEN_SUPERSEDED`. ⚠️ Cần FE kiểm lại xử lý mã này theo ADR-0017 trước khi thêm nút, nếu không bấm nút đúng lúc tab khác vừa refresh sẽ bị đăng xuất.
- Sau khi có token mới: `qc.removeQueries({ predicate: q => q.queryKey[0] === "api" })` (giống `switchTenant`, `AuthProvider.tsx:91`) và vô hiệu `["tenants", userId]`. Menu (`/api/menus/me`) tự gọi lại và được system-service lọc theo claim mới; **không** cần xóa cache Redis menu (cache theo tenant, không theo người dùng, đã được xóa khi sửa menu).
- Platform admin đang làm thay tenant (`X-Tenant-Id`, ADR-0016): quyền đã bỏ qua kiểm khóa; nút vẫn hoạt động (làm mới phiên nền tảng), không ảnh hưởng tenant đang làm thay. Giữ nguyên lựa chọn đơn vị đang làm thay.
- Tài khoản `mustChangePassword`: token mới vẫn không có quyền (đúng thiết kế); FE nên ẩn nút ở trạng thái này.
- Nhãn gợi ý: "Làm mới quyền truy cập" kèm mô tả ngắn "Dùng khi quản trị viên vừa thay đổi quyền của bạn".

### 5.3. Chống lạm dụng

- FE: vô hiệu nút 30 giây sau mỗi lần bấm thành công; đang chạy thì hiện trạng thái chờ.
- Server: mỗi lần bấm là 1 ghi (xoay refresh token) + khoảng 9 câu đọc; bị giới hạn tự nhiên vì cần refresh token hợp lệ. Đề xuất thêm vùng nginx riêng cho `location = /api/auth/refresh` theo mẫu `location = /api/auth/login` (`nginx.conf:86-92`): ví dụ `limit_req_zone $binary_remote_addr zone=refresh:10m rate=60r/m;` với `burst=30 nodelay`. ⚠️ Mức giới hạn chưa kiểm chứng: thư viện/cửa hàng có nhiều nhân viên sau cùng một IP NAT, và FE refresh hàng loạt khi nạp lại trang; đặt quá thấp sẽ làm đăng xuất oan (`refreshSession` xóa phiên với 429 vì `< 500`). Xem câu hỏi Q2.
- Nếu thêm vùng nginx: FE phải coi `429` là lỗi tạm (giữ phiên, báo thử lại sau), không xóa token.

## 6. Phân việc

| ID | Ai | Việc | Phụ thuộc | Song song |
|---|---|---|---|---|
| ⏳ P0 | perf-tester | Đo nền (baseline) `POST /api/auth/login` và `/api/auth/refresh` trên stack dev: p50/p95, số câu SQL mỗi request (bật `hibernate.generate_statistics` hoặc log SQL ở profile riêng), CPU identity/Postgres. Ghi kết quả để đối chiếu điều kiện mục 3.3 | Không | Với F1 |
| ⏳ B1 | be-dev | A1: `TenantRepository.findAllByIds` + sửa `AccessibleTenants.of` (giữ sắp xếp/lọc). Test đơn vị: thứ tự tenant, tenant không hoạt động bị loại, membership không hoạt động bị loại | Không | Với B2, B3, F1 |
| ⏳ B2 | be-dev | A2: nạp `role_permissions` theo lô (`default_batch_fetch_size` hoặc `JOIN FETCH`). Đọc lại `application.yml` trước khi sửa | Không | Với B1, B3 |
| ⏳ B3 | be-dev | A3: lấy module từ `choice.available()` trong `TokenService.issue`; giữ gọi `tenantAccess.modules` làm dự phòng. Sửa `TokenServiceTest` tương ứng (không còn gọi `modules` khi có `available`) | Không | Với B1, B2 |
| ⏳ B4 | be-dev (hoặc người giữ hạ tầng) | Vùng `limit_req` cho `/api/auth/refresh` ở `infrastructure/nginx/nginx.conf`, **sau khi** chốt Q2 | Q2 | Với B1-B3 |
| ⏳ F1 | FE | Sửa `refreshSession()`: phân biệt `REFRESH_TOKEN_SUPERSEDED` (đọc lại token, thử lại một lần) và `429` (giữ phiên) với `401 UNAUTHORIZED` (xóa phiên). Kiểm lại đã đúng ADR-0017 chưa | Không | Với B* |
| ⏳ F2 | FE | Mục "Làm mới quyền truy cập" trong `src/ui/UserMenu.tsx`: gọi `refreshSession()`, so claim cũ/mới, bỏ cache React Query, thông báo kết quả, cooldown 30 giây, ẩn khi `mustChangePassword`, xử lý lỗi theo mục 5.2 | F1 | |
| ⏳ T1 | tester | Test đơn vị/IT cho B1-B3: kết quả `TokenResponse`/claim **không đổi** so với trước (roles, permissions, admin, modules, available, departmentId); IT identity (vai trò `identity_app`, RLS) đếm số câu SQL giảm | B1, B2, B3 | |
| ⏳ T2 | tester | E2E (Playwright): admin gán role mới cho người dùng → người dùng bấm "Làm mới quyền" → menu có mục mới, gọi API mới được, không phải đăng nhập lại. Thu hồi role → bấm → về trang đăng nhập kèm thông báo. Sửa permission của role → bấm → có hiệu lực. Hai tab: bấm ở tab A, tab B không bị đăng xuất | F2 | |
| ⏳ P1 | perf-tester | Đo lại sau B1-B3, so với P0; kết luận có chạm ngưỡng mục 3.3 không | P0, B1-B3 | |

Thứ tự: P0, B1, B2, B3, F1 làm song song; F2 sau F1; T1 sau B1-B3; T2 sau F2; P1 cuối. B4 chờ Q2.

Không có migration, không đổi khóa `Controller/method`, không đổi cổng/biến môi trường (trừ B4 ở nginx).

## 7. Rủi ro và việc chưa kiểm chứng

- ⚠️ Số câu SQL ở mục 2.3 là ước lượng từ đọc code, chưa đo. Đặc biệt `role_permissions` với `@ElementCollection(EAGER)` + `findAllById`: Hibernate 6 có thể nạp từng role một hoặc gộp; P0 phải xác nhận.
- ⚠️ Thời gian bcrypt và chi phí `set_config` RLS mỗi lần mượn kết nối chưa đo.
- ⚠️ Mức giới hạn nginx cho refresh (mục 5.3) chưa kiểm chứng với người dùng sau NAT.
- ⚠️ FE `refreshSession()` hiện xóa phiên với mọi lỗi `< 500`, kể cả `REFRESH_TOKEN_SUPERSEDED` và `429`: nút mới có thể làm đăng xuất oan nếu không làm F1 trước.
- ⚠️ Chưa kiểm lms-api có chuyển nguyên mã lỗi `ACCOUNT_DISABLED`/`ACCOUNT_LOCKED`/`NO_TENANT_ACCESS`/`REFRESH_TOKEN_SUPERSEDED` của identity ra FE ở `/api/auth/refresh` hay không; FE nên phân nhánh theo HTTP status trước, mã sau.
- ⚠️ `RoleRevokedEvent` đi qua Kafka best-effort (ADR-0001): Kafka lỗi thì refresh token không bị thu hồi, người dùng bấm "Làm mới quyền" vẫn nhận token mới **không có** role đã thu hồi (vì đọc DB), nên quyền vẫn đúng; chỉ khác là không bị buộc đăng nhập lại. Không phải lỗ hổng.
- ℹ️ Sửa permission của role không phát sự kiện: người dùng không nhận thông báo đổi quyền. Có thể bổ sung sau (gửi thông báo "Quyền của bạn đã thay đổi, bấm Làm mới quyền") nhưng ngoài phạm vi ADR này.
- ℹ️ Access token cũ (đã cấp trước khi đổi quyền) vẫn hợp lệ tới hết hạn ở mọi tab/thiết bị chưa làm mới. Đây là giới hạn đã biết của ADR-0003/ADR-0017 (R3), không đổi.
- ℹ️ B3 giả định `TenantSummary.modules` trong `choice.available()` là danh sách module **đang bật** giống `ListTenantModulesUseCase.enabledCodes` (cả hai dựa trên `TenantModuleRepository`: `enabledOf` và `findByTenant(...).enabled()`). be-dev đối chiếu hai đường trước khi bỏ câu gọi.

## 8. Câu hỏi mở cho người dùng

- Q1. Có đồng ý **chưa thêm Redis** cho identity, làm tối ưu truy vấn (A) và nút "Làm mới quyền" dùng lại API refresh? Khuyến nghị: đồng ý. Nếu người dùng vẫn muốn Redis vì mục tiêu khác (ví dụ chuẩn bị chạy nhiều instance identity, hoặc thu hồi quyền tức thì), cần nói rõ mục tiêu đó vì nó dẫn tới phương án C chứ không phải B.
- Q2. Có thêm giới hạn tốc độ nginx cho `/api/auth/refresh` không, và nhân viên một đơn vị có thường dùng chung một IP NAT không? Khuyến nghị: có, mức rộng `60r/m burst 30` theo IP, chỉ bật sau khi FE xử lý `429` không xóa phiên (F1).
- Q3. Bấm "Làm mới quyền" có nên làm mới luôn **mọi phiên khác** của người dùng (thiết bị khác) không? Khuyến nghị: không. Mỗi phiên tự nhận quyền mới ở lần refresh kế tiếp (≤ 15 phút); làm mới thiết bị khác cần cơ chế đẩy (push) không có sẵn.
