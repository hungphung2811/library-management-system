# ADR-0008: Tìm kiếm xuyên tenant cho platform admin ở màn hình quản lý

- Trạng thái: đề xuất (chưa cài đặt)
- Nền: ADR-0006 (multi-tenant, `@TenantId` + RLS fail-closed), ADR-0003 (phân quyền động), ADR-0005 (BFF `lms-api`)

## Bối cảnh

Yêu cầu đã chốt với người dùng:

1. "Admin" chỉ là **platform admin**: token có `platformAdmin=true` và **không** có `tenantId`. Admin của một tenant (role `is_admin`, token có `tenantId`) vẫn bị khóa trong tenant của token. Platform admin đã `switch-tenant` (token có `tenantId`) cũng được coi như người dùng của tenant đó.
2. Phạm vi: các endpoint GET danh sách/tìm kiếm ở màn hình quản lý. Chỉ **đọc**; mọi thao tác ghi vẫn theo tenant trong token.
3. Người gọi không phải platform admin gửi tham số `tenantId`: **bỏ qua** (không lỗi), luôn dùng tenant trong token.
4. Platform admin: có `tenantId` thì lọc theo tenant đó; không có thì trả dữ liệu của **tất cả** tenant.

Hiện trạng (đã đối chiếu code, nhánh `main`):

- `TenantIdentifierResolver` trả `TenantContext.current().orElse(NIL_TENANT)`; Hibernate `@TenantId` luôn thêm `tenant_id = ?` vào HQL/Criteria/derived query. Không có giá trị "tất cả".
- Hibernate trong repo là **6.6.29.Final**; `CurrentTenantIdentifierResolver` có phương thức mặc định `boolean isRoot(T)` (đã xác nhận bằng `javap`). Code hiện tại chưa dùng.
- RLS: mỗi bảng tenant có đúng một policy `tenant_isolation` (`FOR ALL`, `USING` = `WITH CHECK` = `tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid`). `role_permissions` dùng `EXISTS (SELECT 1 FROM roles ...)`. 15 bảng có RLS (identity 5, catalog 2, circulation 5, audit 2, system 1).
- `TenantRlsConnectionInitializer` chạy `set_config('app.tenant_id', ?, false)` mỗi lần mượn connection và đặt lại `''` khi trả.
- `PermissionInterceptor` (security-common) là chỗ duy nhất trả 403 `TENANT_REQUIRED`; đã có `Mode.PLATFORM_ADMIN` (`@PlatformAdminOnly`). Token platform admin không tenant có `roles=[]`, `permissions=[]`, không `admin`, nên hiện bị 403 ở mọi endpoint thường.
- `tenant-common` phụ thuộc `security-common` (không có chiều ngược lại).
- `spring.jpa.open-in-view: false` ở identity, catalog, circulation, support, system: session Hibernate chỉ mở trong use case, sau interceptor.
- `lms-api` dựng lại query từng endpoint (không bê nguyên query string, trừ `/api/platform/**`) và chuyển nguyên JWT qua `TokenRelayInterceptor`.
- Bảng toàn cục không RLS, không `@TenantId`: `users`, `tenant_memberships`, `permissions`, `tenants`, `organizations`, `credentials`, `external_logins`, `refresh_tokens`, `known_tenants` (circulation). Danh bạ tenant chỉ có ở identity (`tenants`) và circulation (`known_tenants`); catalog và support không biết danh sách tenant.

## Quyết định

1. **Cơ chế đọc xuyên tenant = "root tenant" của Hibernate + policy RLS chỉ-SELECT theo biến phiên riêng** (phương án A2 dưới đây).
   - `TenantContext` có thêm phạm vi đọc `ALL` (ngoài một tenant cụ thể). Khi phạm vi là `ALL`: `TenantIdentifierResolver` trả một UUID canh gác `ROOT` và `isRoot(ROOT) = true`, nên Hibernate bỏ điều kiện `tenant_id = ?` cho truy vấn đọc, không cần native query.
   - `TenantRlsConnectionInitializer` đặt thêm biến phiên `app.tenant_scope` (`'all'` hoặc `''`) **trong cùng câu lệnh** với `app.tenant_id`, mỗi lần mượn connection, và đặt lại cả hai khi trả.
   - Thêm policy `PERMISSIVE` thứ hai **chỉ `FOR SELECT`**: `USING (current_setting('app.tenant_scope', true) = 'all')`, chỉ trên các bảng mà endpoint trong phạm vi đọc tới (8 bảng, 4 migration; không phải cả 15).
   - Phạm vi "một tenant cụ thể" do platform admin chọn **không cần gì mới ở DB**: chỉ `TenantContext.set(tenantId)` cho request đó, `@TenantId` và RLS chạy như hiện nay.
2. **Chỗ chặn duy nhất là `PermissionInterceptor`**, dựa trên annotation mới `@CrossTenantReadable` (security-common) đặt trên từng endpoint GET trong phạm vi. Interceptor quyết định ai được xuyên tenant và gọi SPI `TenantScopeBinder` (interface ở security-common, cài đặt ở tenant-common) để gắn phạm vi. Controller không tự đọc tham số tenant.
3. **Tham số**: FE gửi query `tenantId` lên `/api/**`; `lms-api` chuyển nguyên thành query `tenantId` xuống `/internal/**`, **không diễn giải** (không kiểm claim). Service chỉ dùng tham số này khi token là platform admin không tenant.
4. **`tenantId` không tồn tại**: không kiểm tra tồn tại, trả danh sách rỗng (200). `tenantId` sai định dạng UUID từ platform admin: 400 `INVALID_TENANT_ID`. Từ người không phải platform admin: bỏ qua mọi giá trị, kể cả sai định dạng (đúng yêu cầu 3).
5. **DTO**: thêm `tenantId` vào phần tử của mọi danh sách trong phạm vi (luôn có, kể cả khi đọc một tenant). Không thêm `tenantName` ở BE; FE tự ánh xạ từ danh sách tenant mà nó đã tải cho bộ chọn tenant (`GET /api/platform/tenants`).
6. **Audit**: mỗi request xuyên tenant ghi log INFO có cấu trúc `platform-audit operation=cross-tenant-read` (theo tiền lệ ADR-0006 với thao tác tổ chức), không phát Kafka.

### So sánh phương án cơ chế

| Tiêu chí | A1: policy SELECT + native/JDBC vượt `@TenantId` | **A2 (chọn): policy SELECT + Hibernate `isRoot`** | B: lặp `TenantContext.callAs` từng tenant rồi gộp | C: vai trò DB chỉ-đọc riêng (BYPASSRLS hoặc policy `TO role`) + DataSource + JdbcTemplate riêng |
|---|---|---|---|---|
| An toàn cho user thường | Như A2 ở DB; nhưng thêm native query ngoài `@TenantId`, mất một lớp lọc, phải nới danh sách rà của `TenantArchitectureRules.noUnreviewedNativeQueries` | Hai lớp vẫn còn: `ALL` chỉ bật khi interceptor xác nhận platform admin; DB chỉ thấy hết khi biến phiên = `'all'` | Cao nhất: không đổi DB, không có trạng thái "tất cả" | Tách vật lý pool; nhưng BYPASSRLS đọc được mọi bảng, kể cả bảng ngoài phạm vi |
| Fail-closed | Giữ (biến rỗng thì policy mới sai) | Giữ: thiếu biến = `NULL` ≠ `'all'`; ghi vẫn bị `tenant_isolation` `WITH CHECK` và `TenantContext.require()` chặn | Giữ | Giữ cho pool chính; pool admin không có lớp RLS |
| Rò rỉ biến phiên trong pool | Có rủi ro, giảm bằng đặt lại mỗi lần mượn | Như A1, giảm bằng đặt cả hai biến trong một câu lệnh mỗi lần mượn và đặt lại khi trả | Không | Không (pool riêng) |
| Migration | 4 file, 8 bảng | 4 file, 8 bảng | 0 | Sửa `init-databases.sql` (vai trò mới, mức cluster), `afterMigrate.sql`, biến môi trường/secret mới cho 4 service |
| Phân trang, sắp xếp | Đúng, một câu SQL | Đúng, một câu SQL (`Page` của audit-logs và users dùng nguyên) | Sai hoặc đắt: trang k cần đọc `(k+1)*size` dòng ở mỗi tenant rồi trộn; `total` = tổng N lần đếm | Đúng |
| Hiệu năng khi nhiều tenant | 1 truy vấn | 1 truy vấn | N truy vấn, N transaction (vì `callAs` phải bao ngoài transaction), tăng tuyến tính | 1 truy vấn, thêm một pool mỗi service |
| Độ phức tạp code | Viết lại 9 truy vấn bằng SQL tay, ánh xạ tay | Nhỏ: resolver, initializer, interceptor, use case nhận phạm vi; truy vấn JPA giữ nguyên | Vừa; catalog/support còn không có danh sách tenant (phải gọi identity) | Lớn: cấu hình `database-common` thêm kết nối, repository trùng lặp |
| Kiểm thử bằng IT | Được | Được (cần IT xác nhận hành vi `isRoot` của Hibernate 6.6) | Được | Được nhưng cần thêm vai trò trong container |

Lý do chọn A2: giữ nguyên truy vấn JPA và hai lớp cách ly (ứng dụng + DB), phân trang đúng bằng một câu SQL, chỉ 4 migration chỉ thêm policy (DDL, không ghi dữ liệu nên không vướng FORCE RLS), không đụng danh sách native query đã rà, không thêm vai trò DB hay secret. Đánh đổi chấp nhận: thêm trạng thái "tất cả" vào biến phiên; giảm thiểu bằng đặt lại bắt buộc ở mỗi lần mượn connection và IT pool 1 connection (mục Kiểm chứng còn nợ).

Đã loại:
- A1: native query làm mất lớp `@TenantId`, phải nới luật ArchUnit, nhân đôi truy vấn mà A2 làm được không cần.
- B: phân trang audit-logs/users sai hoặc đắt; catalog/support không có danh bạ tenant. Giữ B làm phương án dự phòng **chỉ** khi IT chứng minh `isRoot` không bỏ lọc như mong đợi.
- C: chi phí vận hành (vai trò cluster, secret, pool) quá lớn cho nhu cầu chỉ đọc 9 endpoint; BYPASSRLS rộng hơn cần thiết.

### So sánh điểm chèn (chỗ chặn duy nhất)

| Điểm chèn | Đánh giá |
|---|---|
| **`PermissionInterceptor` + SPI `TenantScopeBinder` (chọn)** | Biết `HandlerMethod` nên đọc được annotation opt-in; đã là chỗ quyết định `TENANT_REQUIRED` và `PLATFORM_ADMIN`; chạy trước controller và trước khi use case mở session (open-in-view tắt). Dùng SPI vì security-common không phụ thuộc tenant-common (tránh vòng phụ thuộc). |
| `TenantContextFilter` | Sớm nhất nhưng không biết endpoint nào được opt-in; phải tự so method/đường dẫn; interceptor vẫn trả `TENANT_REQUIRED` nên phải sửa hai nơi. |
| Interceptor riêng ở tenant-common | Không chạm code phân quyền, nhưng `PermissionInterceptor` chạy trước và vẫn chặn (403 `TENANT_REQUIRED`, rồi 403 thiếu quyền vì `permissions=[]`), nên vẫn phải sửa nó; hai nơi quyết định một chuyện. |
| `TokenRelayInterceptor` ở `lms-api` | Không thể là chỗ chặn: service phải tự thực thi theo JWT (không tin BFF). Chỉ dùng để chuyển tham số nếu muốn ngầm; không chọn vì DTO đằng nào cũng phải sửa từng client. |

## Thiết kế

### Ngữ cảnh mới

- `TenantContext` (tenant-common) thêm cờ phạm vi đọc: `NONE` (không tenant), `SINGLE(uuid)` (như hiện nay), `ALL`. API gợi ý: `TenantContext.bindAllTenants()`, `boolean isAllTenants()`, `Optional<UUID> readFilter()` (rỗng nghĩa là `ALL`; ném `TenantRequiredException` khi `NONE`). `be-dev` chốt tên.
- Bất biến: `ALL` và `SINGLE` loại trừ nhau; `TenantContext.require()` **vẫn ném lỗi khi `ALL`** (mọi đường ghi và mọi constructor entity fail-closed). `clear()` xóa cả cờ; `runAs`/`callAs` lưu và khôi phục cả cờ (trong `callAs(t, ...)` phạm vi là `SINGLE(t)`).
- `TenantIdentifierResolver`: `ALL` → trả `ROOT` (UUID canh gác cố định, khác `NIL_TENANT`), `isRoot(id)` = `ROOT.equals(id)`; còn lại như cũ.
- `TenantRlsConnectionInitializer`: `SELECT set_config('app.tenant_id', ?, false), set_config('app.tenant_scope', ?, false)`; `ALL` → (`''`, `'all'`); `SINGLE(t)` → (`t`, `''`); `NONE` → (`''`, `''`). `reset()` đặt cả hai về `''`.

### Quy tắc trong `PermissionInterceptor` (endpoint có `@CrossTenantReadable`)

```
eligible = token.platformAdmin == true AND token không có tenantId AND HTTP method = GET
nếu eligible:
    p = request.getParameter("tenantId")
    p rỗng/không có     -> binder.bindAll()           ; log platform-audit scope=ALL
    p là UUID hợp lệ    -> binder.bindSingle(p)       ; log platform-audit scope=<p>
    p sai định dạng     -> 400 INVALID_TENANT_ID
    register(khóa) như cũ (để khóa vẫn hiện cho quản trị), rồi cho qua, KHÔNG kiểm khóa permission
ngược lại:
    luồng hiện tại, KHÔNG đọc tham số tenantId:
    requireTenant && !hasTenant -> 403 TENANT_REQUIRED ; kiểm admin/khóa như cũ
```

- Endpoint không có annotation: không đổi hành vi (platform admin không tenant vẫn 403 `TENANT_REQUIRED`). Đây là fail-closed: thiếu cả tenant lẫn điều kiện platform admin thì vẫn 403.
- Annotation đặt trên method không phải GET: bỏ qua ở runtime và nên có test kiến trúc (ArchUnit) bắt `@CrossTenantReadable` chỉ đi với `@GetMapping`.
- `TenantScopeBinder` mặc định là no-op ở security-common; tenant-common đăng ký bean cài đặt qua `TenantAutoConfiguration`. Service thiếu tenant-common (chỉ `lms-api`) không bị ảnh hưởng. Dọn dẹp: `TenantContextFilter` đã `clear()` trong `finally`, bao ngoài interceptor.
- Use case trong phạm vi đổi từ `TenantContext.require()` sang `readFilter()` (ví dụ `SearchUsersUseCase`). Quy tắc nghiệp vụ nằm ở application/persistence adapter, không ở controller.

### Luồng dữ liệu

```
FE --GET /api/users?tenantId=T--> nginx:8080 --> lms-api:8085
   lms-api: không kiểm claim, thêm ?tenantId=T vào URI /internal/users (nếu FE có gửi)
   TokenRelayInterceptor: chuyển nguyên Bearer JWT
--> identity:8081 /internal/users?tenantId=T
   Spring Security: xác thực chữ ký JWT
   TenantContextFilter: đặt SINGLE(claim tenantId) nếu có
   PermissionInterceptor + @CrossTenantReadable: platform admin không tenant -> bindSingle(T) / bindAll()
   Use case (@Transactional mở sau) -> mượn connection -> set app.tenant_id / app.tenant_scope
   Hibernate: SINGLE -> tenant_id = T ; ALL -> root, không thêm điều kiện
   PostgreSQL RLS: tenant_isolation (SINGLE) hoặc platform_read_all FOR SELECT (ALL)
```

### Bảng toàn cục và bảng RLS mở xuyên tenant khác nhau

- Bảng toàn cục (`users`, `tenant_memberships`, `permissions`): không có RLS, cách ly do ứng dụng giữ. `UserPersistenceAdapter.search` đang lọc bằng subquery `tenant_memberships.tenant_id = :t`. Ở `ALL`: bỏ điều kiện tenant nhưng **vẫn đi qua `tenant_memberships`** (chỉ người có ít nhất một membership), mỗi dòng kết quả là một cặp (user, tenant) để `tenantId`/`departmentId` có nghĩa. Không cần migration.
- Bảng RLS: cần policy mới (A2) và resolver root. Danh sách migration (số kế tiếp xác nhận ngày 2026-11-16, phải kiểm tra lại khi viết):

| Target | File | Bảng thêm policy `platform_read_all` FOR SELECT |
|---|---|---|
| identity | `V15__platform_read_all.sql` | `roles`, `departments` (`role_permissions` kế thừa qua `EXISTS` trên `roles`, ⚠️ cần IT) |
| catalog | `V3__platform_read_all.sql` | `titles`, `book_copies` |
| circulation | `V7__platform_read_all.sql` | `borrowings`, `borrowing_items`, `fines` |
| audit | `V4__platform_read_all.sql` | `audit_logs` |

Không thêm cho `user_roles`, `reader_profiles`, `reservations`, `borrow_rules`, `notifications`, `menus` (không có endpoint trong phạm vi). Mở rộng sau thì thêm migration mới. Migration chỉ chứa `CREATE POLICY`, không ghi dữ liệu nên không cần `set_config`.

### Phạm vi endpoint

Áp dụng (`@CrossTenantReadable`):

| Service | Endpoint nội bộ (khóa) | Ghi chú |
|---|---|---|
| identity | `GET /internal/users` (`UserController/search`) | Bảng toàn cục, đổi điều kiện ở adapter; dòng = (user, tenant); `UserPageResponse` giữ phân trang |
| identity | `GET /internal/departments` (`DepartmentController/list`), `GET /internal/departments/tree` (`DepartmentController/tree`) | Cây ở `ALL` là rừng, mỗi nút có `tenantId`; `parentId` không bao giờ trỏ khác tenant |
| identity | `GET /internal/roles` (`AccessControlController/listRoles`) | `permissionCodes` EAGER qua `role_permissions` |
| identity | `GET /internal/permissions` (`AccessControlController/listPermissions`) | Dữ liệu toàn cục, không đổi truy vấn; gắn annotation để màn hình role của platform admin không bị 403 |
| catalog | `GET /internal/books` (`TitleController/search`) | `availableCopies`: truy vấn title và `countByTitleIds` cùng chạy trong `ALL` nên nhất quán; `titleId` là khóa chính toàn cục nên đếm theo `titleId` đúng |
| catalog | `GET /internal/book-copies?titleId=` (`BookCopyController/list`) | `titleId` toàn cục duy nhất, `ALL` và `SINGLE` cho cùng kết quả nếu đúng tenant |
| circulation | `GET /internal/borrowings?readerId=` (`BorrowingController/byReader`) | Không gọi service khác |
| circulation | `GET /internal/fines?readerId=` (`FineController/byReader`) | Ở `ALL`, `totalUnpaid` là tổng qua mọi tenant (xem rủi ro) |
| support | `GET /internal/audit-logs` (`AuditLogController/search`) | `Page`, sắp `occurredAt DESC, id ASC`; một câu SQL nên phân trang đúng |

Không áp dụng:
- Nhóm "của tôi" lọc theo userId của token: `borrowings/mine`, `fines/mine`, `GET /internal/reservations` (thực chất là "của tôi"), `notifications/me`, `notifications/me/unread-count`, `me/tenants`, `me/external`. Token platform admin không tenant không có nghĩa nghiệp vụ ở đây.
- `/api/platform/**`: đã xuyên tenant bằng `@PlatformAdminOnly`.
- GET theo id (`users/{id}`, `books/{id}`, `book-copies/{id}`, `by-barcode`, `borrowings/{id}`, `roles/{id}`, `departments/{id}`): ngoài phạm vi đợt này (xem câu hỏi mở). Lưu ý nếu mở sau: `book-copies/{id}` và `by-barcode` dùng native query (chỉ RLS lọc) và barcode chỉ duy nhất theo tenant, nên chỉ cho `SINGLE`, không cho `ALL`.
- Mọi endpoint ghi.

RestClient: các use case danh sách trên không gọi service khác (đã kiểm kê). Nếu `lms-api` làm giàu dữ liệu một danh sách bằng lời gọi GET thứ hai (⚠️ chưa rà hết `lms-api`), lời gọi đó cũng mang JWT platform admin và phải truyền `tenantId` của từng dòng (`SINGLE`) hoặc gọi endpoint cũng có annotation; nếu không sẽ nhận 403 `TENANT_REQUIRED`.

## Hợp đồng API

| Mục | Hợp đồng |
|---|---|
| Tham số | Query `tenantId` (UUID, tùy chọn) trên các endpoint `/api/**` tương ứng bảng phạm vi. `lms-api` chuyển nguyên thành query `tenantId` sang `/internal/**`. Đường dẫn `/api/**` chính xác do `be-dev` đối chiếu `lms-api` (⚠️ chưa liệt kê từng đường dẫn BFF). |
| Platform admin (token không tenant) | Không có `tenantId`: dữ liệu mọi tenant. Có `tenantId`: chỉ tenant đó; tenant không tồn tại → 200, danh sách rỗng. Sai định dạng → 400 `INVALID_TENANT_ID`. Không cần khóa permission cho các endpoint này. |
| Người dùng khác (kể cả admin tenant, platform admin đã switch-tenant) | `tenantId` bị bỏ qua hoàn toàn; hành vi và mã lỗi như hiện nay (403 `TENANT_REQUIRED` khi token thiếu tenant, 403 `FORBIDDEN` khi thiếu khóa). |
| Response | Mỗi phần tử danh sách có thêm `tenantId` (UUID) ở cả DTO nội bộ và DTO `lms-api`: `UserResponse`, `DepartmentResponse`, `DepartmentNode`, `RoleResponse`, `TitleResponse`, `CopyResponse`, `BorrowingResponse`, `FineResponse`, `AuditLogResponse`. `PermissionResponse` không đổi (toàn cục). Không có `tenantName`. Thay đổi chỉ thêm trường, không phá vỡ FE hiện có. |
| Phân trang | `audit-logs` và `users` giữ `page/size/total`; các danh sách khác vẫn trả toàn bộ (kể cả ở `ALL`). |

## Hệ quả và rủi ro

- ⚠️ **Admin tenant tự gửi `tenantId`**: bị bỏ qua vì điều kiện `eligible` đòi token không có `tenantId` và `platformAdmin=true`. Phải có test WebMvc ma trận (user thường, admin tenant, platform admin có tenant, platform admin không tenant) × (có/không/sai `tenantId`).
- ⚠️ **Token giả claim**: mọi service xác thực chữ ký JWT (resource server); `platformAdmin` chỉ phát khi `credentials.platform_admin` (đặt bằng cấu hình) và không có trong token `mustChangePassword`. Không tin header hay tham số nào thay cho claim. `lms-api` không ra quyết định.
- ⚠️ **Rò rỉ `app.tenant_scope` trong pool**: đặt cả hai biến trong một câu lệnh ở mỗi lần mượn (không phụ thuộc `reset()`); connection không qua `TenantRlsDataSource` thì biến chưa từng đặt → `NULL` → policy mới sai (fail-closed). Giới hạn có sẵn từ ADR-0006 vẫn còn: đổi phạm vi giữa một transaction đang giữ connection không cập nhật biến; phạm vi phải được gắn trước khi use case mở transaction (interceptor bảo đảm).
- ⚠️ **Một vai trò `<svc>_app` tự đặt `app.tenant_scope='all'` bằng SQL**: cùng mức rủi ro như tự đặt `app.tenant_id` hiện nay (biến phiên do client đặt được); phòng bằng việc không có SQL động/native ngoài danh sách đã rà. Không làm yếu hơn hiện trạng về bản chất, nhưng phạm vi lộ khi bị SQL injection lớn hơn (mọi tenant thay vì một tenant đoán đúng uuid).
- ⚠️ **Hành vi `isRoot` của Hibernate 6.6** với derived query, JPQL `@Query`, Specification, `@ElementCollection` EAGER và `findById`: chưa chạy. Nếu Hibernate vẫn thêm điều kiện, chuyển sang B cho endpoint bị ảnh hưởng.
- ⚠️ **Ghi trong phạm vi `ALL`**: chặn ba lớp (annotation chỉ GET; `TenantContext.require()` ném; `tenant_isolation` `WITH CHECK`/`USING` cho UPDATE/DELETE). Cần IT chứng minh UPDATE/DELETE dưới `app.tenant_scope='all'` không chạm dòng nào.
- ⚠️ **Hiệu năng `ALL`**: các danh sách không phân trang (books, departments, roles, book-copies, borrowings/fines theo reader) trả mọi tenant; `books?available=true` lọc trong bộ nhớ sau khi tải hết; `countByTitleIds` dùng `IN` không chia lô. Chỉ mục hiện có thường dẫn đầu bằng `tenant_id` (⚠️ chưa rà) nên truy vấn `ALL` có thể quét tuần tự. Cần đo với nhiều tenant.
- ⚠️ **`totalUnpaid` ở `ALL`**: là tổng tiền phạt của một độc giả qua mọi thư viện; chấp nhận vì từng `FineResponse` có `tenantId`, FE nên hiển thị kèm ghi chú hoặc khuyến khích chọn tenant.
- ℹ️ **Audit**: xem xuyên tenant là truy cập đặc quyền nên ghi log INFO `platform-audit operation=cross-tenant-read actorId=<sub> endpoint=<khóa> scope=ALL|<tenantId>` ở interceptor. Không vào kho audit của support (kho đó cần `tenantId`), giống quyết định với thao tác tổ chức ở ADR-0006.
- ℹ️ **Cache theo tenant**: Redis dùng tiền tố `tenant:{id}:`; không có khóa cho `ALL`. Quy tắc: không đọc/ghi cache khi phạm vi `ALL`. ⚠️ Chưa rà các endpoint trong phạm vi có dùng cache không (kiểm kê không thấy).
- ℹ️ **Đổi tên controller/method** không ảnh hưởng quyền xuyên tenant (dựa annotation, không dựa khóa), nhưng khóa thường của user vẫn theo ADR-0003.

## Phân việc đề xuất

- ⏳ Bước 1 (tuần tự, chặn mọi bước sau): shared-libraries. `security-common`: `@CrossTenantReadable`, SPI `TenantScopeBinder`, nhánh mới trong `PermissionInterceptor`, log audit. `tenant-common`: phạm vi `ALL` trong `TenantContext`, resolver `isRoot`, initializer hai biến, bean binder, luật ArchUnit annotation chỉ đi với GET. Xong khi: unit test ma trận interceptor và test `TenantContext` qua; `mvn test` toàn repo xanh.
- ⏳ Bước 2 (song song với bước 1): 4 migration ở `system-service` (identity V15, catalog V3, circulation V7, audit V4; kiểm lại số kế tiếp). Xong khi: `MigrationApiIT`/`TenantRlsIT` mở rộng chứng minh SELECT thấy mọi tenant với `'all'`, UPDATE/DELETE không chạm dòng, connection tái dùng sau `'all'` không còn thấy tenant khác.
- ⏳ Bước 3 (sau 1 và 2, song song theo service, mỗi agent một service): gắn annotation, đổi use case sang `readFilter()`, thêm `tenantId` vào DTO. identity (users, departments, roles, permissions), catalog (books, book-copies), circulation (borrowings, fines), support (audit-logs). Xong khi: IT từng service với token platform admin cho 3 trường hợp (không `tenantId`, `tenantId` đúng, `tenantId` của tenant khác) và token admin tenant gửi `tenantId` lạ vẫn chỉ thấy tenant của mình.
- ⏳ Bước 4 (sau DTO nội bộ của bước 3): `lms-api` thêm query `tenantId` vào các controller/`Http*Client` tương ứng và trường `tenantId` vào DTO BFF. Xong khi: test WebMvc của `lms-api` xác nhận tham số được chuyển xuống và trường được ánh xạ.
- ⏳ Tester: kịch bản đầu-cuối qua nginx với 2 tenant có dữ liệu. Perf-tester: đo `GET /api/books` và `audit-logs` ở `ALL` với nhiều tenant (sau bước 3).

## Kiểm chứng còn nợ

Chỉ IT/Docker kiểm chứng được (Testcontainers, `-DargLine=-Dapi.version=1.44` với Docker 29):

- ⚠️ Hibernate 6.6.29 bỏ điều kiện `@TenantId` khi `isRoot` trả true, cho mọi kiểu truy vấn đang dùng ở 9 endpoint, và không đổi hành vi khi `SINGLE`.
- ⚠️ Policy `platform_read_all` FOR SELECT cộng `tenant_isolation`: SELECT thấy mọi tenant; INSERT/UPDATE/DELETE bị chặn; `role_permissions` hiện đủ qua `EXISTS` trên `roles`; `borrowing_items` (không có field tenant trong JPA) đọc đủ.
- ⚠️ Không rò biến phiên: IT pool Hikari 1 connection, request `ALL` của platform admin rồi request của user thường trên cùng connection chỉ thấy tenant của user.
- ⚠️ Chạy bằng vai trò `<svc>_app` (NOBYPASSRLS), không phải superuser, theo mẫu `AppRoleDatabase`.
- ⚠️ `lms-api` → service đầu-cuối qua nginx (đường dẫn `/api/**` chính xác chưa rà).

Lệnh chạy bù tổng (như ADR-0006):

```bash
mvn -B test '-Dtest=*Test,*IT' -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
```
