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
- Token có thể mang claim `platformAdmin=true` (tài khoản có cờ `platform_admin`) và/hoặc `tenantId`. Endpoint nền tảng (`/api/platform/**`, đặt ở identity-service, `lms-api` và nginx định tuyến tới) yêu cầu `platformAdmin`; endpoint thuộc tenant yêu cầu `tenantId`. Platform admin không phải thành viên tenant nào thì token chỉ có `platformAdmin`; nếu họ cũng là thành viên thì token có cả hai. Cờ `platform_admin` chỉ đặt bằng cấu hình bootstrap, tài khoản `admin` dev hiện có **không** tự thành platform admin (người vận hành có tài khoản riêng). *(Lựa chọn của người viết.)*
- "Có quyền ở tenant" nghĩa là có membership ACTIVE ở tenant ACTIVE; role chỉ quyết định permission trong tenant. Độc giả không có role vẫn là thành viên. Khi migrate, mọi người dùng hiện có được tạo membership vào tenant mặc định. *(Lựa chọn của người viết.)*
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

### 5.6 Quản lý người dùng trong tenant
Chủ hệ thống chốt: quản trị viên của từng tenant **tự quản lý** người dùng của tenant mình. Các quy tắc cụ thể (phần quy tắc chi tiết là lựa chọn của người viết):
- Quản trị viên tenant tự tạo người dùng cho tenant mình, gán role, phòng ban, bật/tắt membership, và liệt kê/tìm kiếm người dùng. Mọi thao tác chỉ thấy và chỉ tác động tới người có membership trong tenant của họ (`/api/users` lọc theo `tenant_memberships`).
- Tạo người dùng mà username hoặc email đã tồn tại ở bất kỳ nơi nào: báo "đã tồn tại" chung chung, không tiết lộ tài khoản đó thuộc tenant nào hay có tồn tại ở tenant khác. Muốn thêm một tài khoản có sẵn vào tenant thì người vận hành gắn vào; luồng "mời, người dùng tự đồng ý" để làm sau.
- Sửa thông tin tài khoản (họ tên, email) và đặt lại mật khẩu: quản trị viên tenant chỉ làm được với tài khoản mà **chỉ là thành viên của đúng tenant đó** và không phải platform admin. Khi tài khoản trở thành thành viên của từ hai tenant trở lên, quyền này chuyển về chính người dùng (và người vận hành). Lý do: nếu không, quản trị viên của tenant A có thể đặt lại mật khẩu để chiếm tài khoản đang dùng ở tenant B. Đặt lại mật khẩu dùng cơ chế mật khẩu tạm có sẵn (buộc đổi ở lần đăng nhập đầu).
- Quản trị viên tenant không tạo, gán hoặc thu hồi được role nằm ngoài tenant của mình, và vẫn chịu quy tắc hiện có: chỉ quản trị viên mới gán role `isAdmin` (ở phạm vi tenant).
- Test cách ly cho bảng `users`: quản trị viên A không liệt kê, đọc, sửa hay đặt lại mật khẩu người dùng chỉ thuộc tenant B; không sửa được tài khoản thuộc nhiều tenant.

## 6. Nâng cấp dữ liệu hiện có
Thêm migration mới vào `services/migration-service` (không sửa file cũ để không đổi checksum): tạo một tenant mặc định, thêm `tenant_id` dạng cho phép null, gán toàn bộ dữ liệu hiện có vào tenant mặc định, đặt NOT NULL, đổi các ràng buộc unique, bật RLS. Người dùng hiện có được tạo membership vào tenant mặc định, role hiện có thành role của tenant đó, `SUPER_ADMIN` seed hiện tại thành quản trị tenant; tài khoản `admin` dev vẫn đăng nhập được. Tạo tenant mới thì sao chép bộ role mẫu cho tenant đó. Bộ mẫu là **dữ liệu** trong bảng riêng `role_templates` (và quyền của từng mẫu theo khóa permission), khởi tạo từ 5 role seed hiện có; không sao chép từ tenant mặc định (có thể đã bị sửa) và không tự đồng bộ khi danh mục permission đổi. *(Lựa chọn của người viết.)*

Ngoài Postgres: bản ghi audit cũ trong Mongo được gán `tenantId` của tenant mặc định bằng một script một lần; khóa Redis cũ không có tiền tố tenant được bỏ (hết hạn theo TTL hoặc xóa); access và refresh token cũ không có `tenantId` bị từ chối, người dùng đăng nhập lại một lần khi triển khai. *(Lựa chọn của người viết.)*

## 7. Chức năng quản lý tenant cho super admin
"Super admin" trong tài liệu này là tài khoản có cờ `platform_admin` (mục 5.1), tức người vận hành đứng trên mọi tenant. Chủ hệ thống yêu cầu có chức năng này. Phạm vi chức năng dưới đây là đề xuất của người viết, chờ duyệt.

API nằm ở identity-service dưới `/api/platform/**`, yêu cầu claim `platformAdmin` (không dùng khóa `Controller/method` của tenant), qua `lms-api` và nginx như các API khác.

| Chức năng | Mô tả |
|---|---|
| Tạo tenant | Nhập mã (`code`, unique), tên, trường (organization, tùy chọn). Trong **một thao tác**: tạo tenant, sao chép role mẫu từ `role_templates`, tạo dữ liệu khởi tạo (ví dụ quy tắc mượn mặc định), tạo tài khoản quản trị đầu tiên (tài khoản mới với mật khẩu tạm buộc đổi, hoặc gắn tài khoản có sẵn) và gán role quản trị của tenant |
| Danh sách và tìm kiếm | Tìm theo mã, tên, trường, trạng thái; phân trang; mỗi dòng có số thành viên |
| Xem chi tiết | Thông tin tenant, trường, trạng thái, số thành viên, danh sách quản trị viên của tenant |
| Sửa | Đổi tên, đổi trường; đổi `code` chỉ khi chưa có dữ liệu gắn với nó (đề xuất: không cho đổi `code` sau khi tạo để tránh vỡ tham chiếu) |
| Tạm ngưng / kích hoạt lại | `SUSPENDED` chặn cấp và làm mới token cho tenant (token đang sống hết hạn trong tối đa 15 phút); kích hoạt lại thì dùng được ngay. Dữ liệu giữ nguyên |
| Quản lý trường (organization) | Tạo, sửa, liệt kê; gán hoặc đổi trường cho tenant |
| Quản trị viên tenant | Thêm một quản trị viên cho tenant (gắn tài khoản có sẵn hoặc tạo mới), gỡ quản trị viên, đặt lại mật khẩu quản trị viên khi tài khoản chỉ thuộc tenant đó (cùng quy tắc ở mục 5.6) |
| Gắn tài khoản có sẵn vào tenant | Đây là đường duy nhất để một tài khoản thuộc nhiều tenant (mục 5.6) |
| Nhật ký thao tác nền tảng | Mọi thao tác trên ghi audit (ai, làm gì, tenant nào, khi nào) |

**Giới hạn:** super admin quản lý tenant, không xem dữ liệu nghiệp vụ trong tenant (sách, mượn trả, độc giả). Muốn làm việc bên trong một tenant thì phải là thành viên của tenant đó. Không có thao tác xóa cứng tenant (đã nằm ngoài phạm vi ở mục 3); chỉ tạm ngưng. Chưa có giao diện quản trị (dự án hiện chưa có CMS); chức năng này chỉ gồm API, FE dùng sau.

**Kiểm thử:** chỉ có `platformAdmin` mới gọi được; quản trị viên tenant và người dùng thường nhận 403; tạo tenant tạo đủ role, dữ liệu khởi tạo và quản trị viên; tạo trùng `code` bị từ chối; tạm ngưng chặn đăng nhập và làm mới token, kích hoạt lại thì dùng được; mọi thao tác có audit.

## 8. Kiểm thử cần có
- Integration test (Testcontainers) với hai tenant: đọc, ghi, tìm kiếm không thấy dữ liệu tenant kia, kể cả qua SQL native; không có tenant thì không thấy gì.
- Đăng nhập chọn đúng tenant (lần đầu và theo `last_tenant_id`), đổi tenant lưu lại, bị gỡ quyền thì rơi về tenant khác, không còn tenant nào thì từ chối, tenant SUSPENDED thì từ chối.
- Migration nâng cấp trên cơ sở dữ liệu có sẵn dữ liệu cũ, kiểm tra dữ liệu về đúng tenant mặc định.
- ArchUnit: truy cập dữ liệu không đi vòng qua ngữ cảnh tenant.

## 9. Để người xây dựng quyết định
Tên lớp và package, cơ chế đặt `app.tenant_id` cho RLS và cách tách tài khoản DB, ngữ cảnh "hệ thống" cho việc nền (miễn là fail-closed), hình dạng chi tiết của các API, cách refresh token gắn tenant, thứ tự thực hiện (gợi ý: identity và token trước, rồi thư viện cách ly dùng chung, rồi từng service, rồi messaging/audit, rồi API nền tảng).
