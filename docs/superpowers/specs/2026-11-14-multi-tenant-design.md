# Thiết kế multi-tenant cho hệ thống quản lý thư viện

Ngày: 2026-11-14. Trạng thái: bản nháp chờ duyệt. Nhánh làm việc: `multiple-tenant` (worktree `.worktrees/multiple-tenant`).

## 1. Mục đích và lý do

Chủ hệ thống muốn bán dịch vụ quản lý thư viện cho nhiều trường học trên cùng một hệ thống. **Một thư viện của một trường là một tenant.** Trường thuê hai thư viện riêng biệt thì có hai tenant. Mỗi tenant có dữ liệu riêng và không thấy dữ liệu của tenant khác, kể cả hai tenant cùng một trường.

Hiện tại hệ thống chỉ phục vụ một thư viện: không có khái niệm tenant ở bất kỳ đâu. `username` và email unique toàn hệ thống, role và quyền dùng chung, 4 database cố định (identity_db, catalog_db, circulation_db, audit_db) cùng `migration-service` chạy Flyway trên 4 database đó.

## 2. Quyết định của chủ hệ thống (đã chốt trong buổi trao đổi)

1. Tài khoản thuộc về **người dùng**, không thuộc về một tenant. Một người có thể có quyền ở nhiều tenant.
2. Đăng nhập xong người dùng vào **một tenant đang chọn**. Lần đầu là tenant đầu tiên họ có quyền. Khi người dùng chọn lại tenant, BE lưu lại và dùng cho lần đăng nhập sau.
3. Role và quyền gán **theo từng tenant** (thủ thư ở thư viện A có thể chỉ là độc giả ở thư viện B).
4. Token chỉ mang quyền của **một** tenant đang chọn; đổi tenant thì cấp token mới.
5. **Chỉ người vận hành** (chủ hệ thống) tạo tenant và tài khoản quản trị đầu tiên của tenant. Có một cấp quản trị nằm trên mọi tenant, tách khỏi quản trị viên của từng tenant. Trường không tự đăng ký.
6. Số tenant ban đầu nhỏ; tải tăng thì tính sau.
7. Mô hình lưu dữ liệu: **hướng A**, dùng chung database và schema, mỗi bảng thuộc tenant có cột `tenant_id`.
8. Mỗi tenant có dữ liệu nghiệp vụ riêng hoàn toàn: đầu sách, bản sao, mượn trả, đặt trước, phạt, độc giả, phòng ban, audit. Cấp "trường" chỉ là nhóm để quản lý và tính tiền, không có dữ liệu nghiệp vụ riêng.

## 3. Mục tiêu, ngoài phạm vi, điều không được xảy ra

**Mục tiêu**
- Chạy được nhiều tenant trên cùng một bộ service, dữ liệu cách ly.
- Một tài khoản dùng được ở nhiều tenant, đổi tenant không cần đăng nhập lại.
- Dữ liệu và triển khai hiện có tiếp tục chạy được sau khi nâng cấp (xem mục 6).

**Ngoài phạm vi (làm sau)**
- Tách tenant lớn sang schema hoặc database riêng (chỉ chừa đường mở, xem mục 5.5).
- Tính tiền, hạn mức, gói dịch vụ; tên miền hoặc subdomain riêng cho từng tenant; giao diện và cấu hình riêng theo tenant.
- Trường tự đăng ký; xuất hoặc xóa toàn bộ dữ liệu của một tenant; báo cáo hoặc tìm kiếm xuyên tenant.
- Đăng nhập ngoài (OIDC) riêng cho từng tenant: tạm giữ là toàn hệ thống như hiện nay.

**Điều không được xảy ra (kể cả khi tính năng "chạy đúng")**
- Một request của tenant A đọc hoặc ghi được dòng của tenant B, kể cả qua SQL native hoặc quên điều kiện lọc.
- Cấp token cho tenant mà người dùng không còn quyền.
- Cứng mã tên role hoặc id tenant trong code (giữ nguyên nguyên tắc đã làm ở phân quyền động: ADR-0003).
- Làm hỏng dữ liệu hiện có khi nâng cấp.

## 4. Các cách làm đã cân nhắc

| | Cách | Đánh đổi |
|---|---|---|
| **A (chọn)** | Dùng chung database, cột `tenant_id` trên mọi bảng thuộc tenant; Hibernate `@TenantId` lọc và điền tự động; Row-Level Security của PostgreSQL làm lớp chặn thứ hai | Rẻ và nhanh nhất, `migration-service` giữ nguyên 4 database. Cách ly dựa vào code và RLS, không tách vật lý; một tenant rất lớn ảnh hưởng tenant khác |
| B | Mỗi tenant một schema trong cùng database | Cách ly mạnh hơn nhưng migration phải chạy cho từng schema và kết nối phải chuyển schema; số tenant lớn thì chậm dần |
| C | A, cộng sẵn lớp định tuyến kết nối theo tenant | Thêm công thiết kế cho thứ chưa cần, trong khi chủ hệ thống nói tính sau |

Mỗi tenant một database bị loại ngay vì vận hành nặng khi số tenant còn nhỏ.

Lý do chọn A: khớp "số lượng nhỏ trước". `@TenantId` kết hợp RLS chặn rủi ro sót điều kiện lọc ở hai lớp, và đường lên C vẫn mở vì mọi truy cập dữ liệu đều đi qua ngữ cảnh tenant.

## 5. Thiết kế (phần dưới là lựa chọn của người viết, chờ chủ hệ thống xem)

### 5.1 Mô hình tenant và người dùng (identity-service, thêm bounded context `tenant`)
- `organizations` (trường, chỉ để nhóm) và `tenants(id, code, name, organization_id nullable, status ACTIVE/SUSPENDED, created_at)`.
- `tenant_memberships(user_id, tenant_id, department_id nullable, status)`: người dùng thuộc tenant nào. **Phòng ban chuyển từ `users` sang bảng này** vì phòng ban là dữ liệu của tenant còn tài khoản là toàn cục.
- `users.last_tenant_id`: tenant được chọn gần nhất.
- `roles.tenant_id` (role thuộc tenant), `user_roles` giữ nguyên nhưng role đã gắn tenant. Cờ `is_admin` của role nghĩa là quản trị viên **của tenant đó**. Danh mục khóa permission `Controller/method` **dùng chung toàn hệ thống** (mô tả endpoint, không phải dữ liệu tenant).
- Quản trị cấp trên mọi tenant: cờ `platform_admin` trên tài khoản, tạo bằng bootstrap/cấu hình, không tạo qua API tenant.
- Dữ liệu toàn cục (không có `tenant_id`): `credentials`, `users` (username, họ tên, email), `external_logins`, `permissions`.

### 5.2 Đăng nhập, chọn tenant và token
- Đăng nhập: xác thực xong, chọn tenant = `last_tenant_id` nếu còn thành viên ACTIVE và tenant ACTIVE; không thì tenant đầu tiên (xếp theo thời điểm tham gia) mà người dùng còn quyền. Phản hồi gồm token và danh sách tenant người này truy cập được.
- `POST /api/auth/switch-tenant {tenantId}`: kiểm tra thành viên, lưu `last_tenant_id`, cấp access token và refresh token mới của tenant đó.
- Token thêm claim `tenantId`; `permissions` và `admin` chỉ của tenant đó. Refresh token gắn `tenant_id`; refresh giữ nguyên tenant.
- Tài khoản không còn tenant nào và không phải platform admin: không đăng nhập được (lỗi riêng, rõ nghĩa).
- Token của platform admin không có `tenantId` và chỉ dùng được cho endpoint nền tảng (`/api/platform/**`); muốn làm việc trong một tenant thì phải là thành viên của tenant đó.
- Tenant SUSPENDED: không cấp và không làm mới token; access token đang sống hết hạn trong tối đa 15 phút (chấp nhận).

### 5.3 Cách ly dữ liệu (cả 4 service)
- Mọi bảng thuộc tenant có `tenant_id UUID NOT NULL`: `titles`, `book_copies`, `borrowings` và `borrowing_items`, `reservations`, `fines`, `borrow_rules`, `reader_profiles`, `departments`, `roles`, `tenant_memberships`, `audit_logs`, và `refresh_tokens`.
- Ràng buộc unique trở thành unique **theo tenant**: ISBN, mã vạch, `card_no`, mã phòng ban, tên role, các chỉ mục unique một phần của đặt trước/mượn. `borrow_rules` đổi khóa chính thành (`tenant_id`, `reader_type`); `reader_profiles` thành (`tenant_id`, `user_id`).
- Lớp 1: entity JPA gắn `@TenantId`, một `CurrentTenantIdentifierResolver` đọc `TenantContext`. Lớp 2: RLS trên mọi bảng thuộc tenant với điều kiện `tenant_id = current_setting('app.tenant_id')`, đặt bằng `SET LOCAL` ở đầu mỗi transaction. Hai lớp phải **fail-closed**: không có tenant trong ngữ cảnh thì không thấy dòng nào.
- RLS chỉ có tác dụng với người dùng DB không phải chủ bảng và không có BYPASSRLS (hoặc bảng bật FORCE). Hiện service và `migration-service` cùng dùng chủ sở hữu DB, nên cần tách: tài khoản chạy migration (chủ) và tài khoản chạy ứng dụng (không bypass RLS).
- Các SQL native hiện có (upsert permission, truy vấn audit, join chi tiết bản sao) phải được rà để chạy đúng dưới RLS.
- Audit trên Mongo (`LOG_DB`): thêm trường `tenantId` và lọc theo đó. Khóa Redis có tiền tố tenant.

### 5.4 Lan truyền tenant
- Một filter trong `security-common` (sau bước xác thực JWT) đọc claim `tenantId` đặt vào `TenantContext` và xóa khi xong request. Không có `tenantId` mà endpoint không phải endpoint nền tảng thì từ chối.
- `lms-api` (BFF) và nginx không cần đoán tenant: tenant chỉ lấy từ token; các lời gọi service-to-service tiếp tục chuyển token người dùng như hiện nay.
- `EventEnvelope` thêm `tenantId`; consumer đặt `TenantContext` từ envelope. Việc nền (không có request) phải chỉ rõ tenant khi chạy.

### 5.5 Đường lên về sau
Mọi truy cập dữ liệu đi qua `TenantContext`, nên sau này có thể thêm lớp định tuyến kết nối theo tenant để tách một tenant lớn sang database riêng mà không đổi code nghiệp vụ. Phần này không làm bây giờ.

## 6. Nâng cấp dữ liệu hiện có
Thêm migration mới vào `services/migration-service` (không sửa file cũ để không đổi checksum): tạo một tenant mặc định, thêm `tenant_id` dạng cho phép null, gán toàn bộ dữ liệu hiện có vào tenant mặc định, đặt NOT NULL, đổi các ràng buộc unique, bật RLS. Người dùng hiện có được tạo membership vào tenant mặc định, role hiện có thành role của tenant đó, `SUPER_ADMIN` seed hiện tại thành quản trị tenant; tài khoản `admin` dev vẫn đăng nhập được. Tạo tenant mới thì sao chép bộ role mẫu hiện có (5 role seed) cho tenant đó.

## 7. API nền tảng cho người vận hành
`/api/platform/**` (chỉ platform admin): tạo/sửa/tạm ngưng tenant, tạo organization, tạo tài khoản quản trị đầu tiên của tenant (hoặc gắn tài khoản có sẵn), liệt kê tenant. Tạo tenant sinh bộ role mẫu và dữ liệu khởi tạo trong cùng một thao tác.

## 8. Kiểm thử cần có
- Integration test (Testcontainers) với hai tenant: đọc, ghi, tìm kiếm không thấy dữ liệu tenant kia, kể cả qua SQL native; không có tenant thì không thấy gì.
- Đăng nhập chọn đúng tenant (lần đầu và theo `last_tenant_id`), đổi tenant lưu lại, bị gỡ quyền thì rơi về tenant khác, không còn tenant nào thì từ chối, tenant SUSPENDED thì từ chối.
- Migration nâng cấp trên cơ sở dữ liệu có sẵn dữ liệu cũ, kiểm tra dữ liệu về đúng tenant mặc định.
- ArchUnit: truy cập dữ liệu không đi vòng qua ngữ cảnh tenant.

## 9. Để người xây dựng quyết định
Tên lớp và package, cơ chế đặt `app.tenant_id` cho RLS và cách tách tài khoản DB, ngữ cảnh "hệ thống" cho việc nền (miễn là fail-closed), hình dạng chi tiết của các API, cách refresh token gắn tenant, thứ tự thực hiện (gợi ý: identity và token trước, rồi thư viện cách ly dùng chung, rồi từng service, rồi messaging/audit, rồi API nền tảng).
