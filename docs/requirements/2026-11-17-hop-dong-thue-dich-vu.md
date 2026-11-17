# Đề bài: Quản lý hợp đồng thuê dịch vụ theo tenant

- Ngày: 2026-11-17
- Trạng thái: ⏳ chờ `sa` thiết kế
- Liên quan: ADR-0003, ADR-0006 (multi-tenant, platform admin, tenant ACTIVE/SUSPENDED), ADR-0009 (module theo tenant, `MODULE_NOT_ENABLED`), ADR-0001 (Kafka best-effort), `docs/api/platform-tenant-modules.md`, `docs/requirements/2026-11-16-hieu-sach-module-ban-hang.md` (đã loại tính tiền gói; đề bài này không mâu thuẫn vì chỉ ghi nhận hợp đồng và tiền thủ công)

## 1. Mục tiêu

ℹ️ Hệ thống hiện bán dịch vụ cho các bên thuê (tenant) nhưng chưa có nơi ghi nhận "ai thuê gì, đến khi nào, giá bao nhiêu, đã trả chưa". Mục tiêu: platform admin quản lý hợp đồng thuê của từng tenant, hợp đồng là nguồn sự thật cho việc bật/tắt module, tự xử lý khi hết hạn, và có lịch sử đối soát được. Tenant admin xem được hợp đồng của mình (chỉ đọc).

## 2. Bối cảnh (đã có trong code/tài liệu)

- ✅ `identity_db.tenants` (toàn cục, không RLS), trạng thái ACTIVE/SUSPENDED, API `suspend`/`activate`.
- ✅ `tenant_modules` (LENDING/SALES), API platform `PUT/DELETE /api/platform/tenants/{id}/modules/{module}`, use case `Enable/DisableTenantModuleUseCase`, claim JWT `modules`, chặn 403 `MODULE_NOT_ENABLED`; tắt module cuối cùng trả 409 `TENANT_MODULE_REQUIRED`.
- ✅ `PlatformOperationEvent` + log `platform-audit` (Kafka best-effort).
- ❌ Chưa có: hợp đồng, thời hạn, giá, thanh toán, job lập lịch ở identity, trang admin FE cho tenant/module/hợp đồng.

## 3. Vai trò

| Vai trò | Quyền |
|---|---|
| Platform admin (`@PlatformAdminOnly`) | Tạo, sửa, kích hoạt, hủy, gia hạn hợp đồng; ghi nhận thanh toán; xem mọi hợp đồng, lịch sử, danh sách sắp hết hạn |
| Tenant admin | Chỉ đọc hợp đồng của chính tenant mình: module, gói/tên hợp đồng, ngày bắt đầu/kết thúc, trạng thái, cờ ân hạn và TỔNG TIỀN PHẢI TRẢ. KHÔNG thấy số đã thu, số còn nợ, các dòng thanh toán, ghi chú nội bộ, lịch sử thao tác nội bộ, thông tin ngoại lệ bật/tắt tay |
| Người dùng thường | Không truy cập |

⚠️ Không có vai trò kinh doanh/kế toán riêng (ngoài phạm vi).

## 4. Phạm vi

### Làm
1. Hợp đồng theo tenant: một tenant có nhiều hợp đồng theo thời gian; tại mỗi thời điểm tối đa một hợp đồng hiệu lực. Gia hạn = tạo hợp đồng mới nối tiếp (giữ lịch sử).
2. Nội dung hợp đồng: danh sách module (LENDING/SALES, ít nhất một), ngày bắt đầu, ngày kết thúc (có thể trống với hợp đồng legacy), tổng tiền VND (tự nhập), chu kỳ thanh toán tự do (số ngày hoặc tháng/năm, chỉ để ghi nhận), ghi chú nội bộ.
3. Vòng đời: NHÁP, HIỆU LỰC, HẾT HẠN, ĐÃ HỦY.
4. Ghi nhận thanh toán thủ công bất biến.
5. Hết hạn tự động qua job định kỳ, có thời gian ân hạn.
6. Nhắc sắp hết hạn bằng thông báo trong hệ thống.
7. Lịch sử thay đổi hợp đồng lưu trong DB, cộng event và log.
8. Backfill hợp đồng legacy cho tenant hiện có.

### Ngoài phạm vi
- ❌ Danh mục gói (plan) và giá chuẩn; giới hạn dùng (số người dùng, đầu sách, cửa hàng, dung lượng).
- ❌ Dùng thử (trial), tạm dừng hợp đồng, tự gia hạn.
- ❌ Cổng thanh toán; hóa đơn, VAT, hóa đơn điện tử; xuất PDF.
- ❌ Gửi email nhắc hạn.
- ❌ Vai trò kinh doanh/kế toán riêng, tách quyền xem tiền.
- ❌ Chế độ chỉ đọc khi hết hạn.
- ℹ️ Phía FE (repo `../library-management-system-fe`, apps/admin): trang tenant/module/hợp đồng cho platform admin và trang xem hợp đồng cho tenant admin là việc FE, ngoài repo BE; BE chỉ cung cấp API và ghi tài liệu `docs/api/`.

## 5. Quy tắc nghiệp vụ

### 5.1. Hợp đồng
- R1. Một tenant có tối đa một hợp đồng HIỆU LỰC tại mỗi thời điểm. Kích hoạt hợp đồng khi khoảng thời gian chồng lấn hợp đồng HIỆU LỰC khác của cùng tenant: 409.
- R2. Hợp đồng gồm ít nhất một module hợp lệ (LENDING/SALES); mã lạ 400, rỗng 400.
- R3. Ngày kết thúc phải sau hoặc bằng ngày bắt đầu. Hợp đồng không thời hạn chỉ dành cho hợp đồng legacy do migration backfill tạo; API tạo mới BẮT BUỘC có ngày kết thúc, thiếu thì 400 (✅ đã chốt).
- R4. Số tiền là số nguyên VND không âm, hợp đồng legacy giá 0.
- R5. Chỉ sửa được hợp đồng NHÁP (module, ngày, giá, chu kỳ). Hợp đồng HIỆU LỰC chỉ được sửa ghi chú, kéo dài ngày kết thúc (✅ đã chốt giữ thao tác này; chỉ được dời ra sau, ghi lịch sử, không được gây chồng lấn hợp đồng khác theo R1; nếu đang trong ân hạn thì kéo dài vượt ngày hôm nay đưa hợp đồng ra khỏi ân hạn) hoặc hủy. HẾT HẠN và ĐÃ HỦY không sửa được.
- R6. Hủy hợp đồng HIỆU LỰC: chuyển ĐÃ HỦY, xử lý tắt module như hết hạn nhưng không có ân hạn (xem 7), bắt buộc có lý do.

### 5.2. Vòng đời

| Từ | Sang | Khi |
|---|---|---|
| NHÁP | HIỆU LỰC | Platform admin kích hoạt, được phép kích hoạt TRƯỚC ngày bắt đầu (✅ đã chốt). HIỆU LỰC nghĩa là "đã chốt"; module chỉ được bật khi tới ngày bắt đầu (xem R11) |
| NHÁP | ĐÃ HỦY | Platform admin hủy |
| HIỆU LỰC | HẾT HẠN | Qua hết ngày kết thúc cộng ân hạn (job) |
| HIỆU LỰC | ĐÃ HỦY | Platform admin hủy |

- Ngày kết thúc tính theo giờ Việt Nam (`Asia/Ho_Chi_Minh`): hợp đồng hết hạn vào cuối ngày kết thúc (24:00 giờ VN); lưu timestamp UTC.
- Ân hạn cấu hình được, mặc định 7 ngày, kể từ cuối ngày kết thúc. Trong ân hạn module vẫn bật; trạng thái hợp đồng vẫn HIỆU LỰC nhưng hiển thị cờ "đang ân hạn" (cờ suy ra, không phải trạng thái mới).

### 5.3. Thanh toán thủ công
- R7. Mỗi dòng thanh toán: hợp đồng, ngày thanh toán, số tiền VND, ghi chú, mã tham chiếu, người ghi nhận, thời điểm ghi.
- R8. Bất biến: chỉ thêm dòng, không sửa, không xóa. Sửa sai bằng dòng đảo (✅ đã chốt: đảo TOÀN PHẦN, số tiền bằng đúng âm của dòng gốc, tham chiếu trỏ dòng gốc). Mỗi dòng gốc chỉ được đảo một lần; không đảo dòng đảo; không đảo một phần. Muốn ghi lại số đúng thì thêm dòng thanh toán mới sau khi đảo.
- R9. Số đã thu = tổng các dòng (kể cả đảo); số còn nợ = tổng tiền hợp đồng trừ số đã thu. Chỉ để hiển thị; không có chặn tự động theo nợ (ghi nhận thủ công, không có hóa đơn).
- R10. Chỉ ghi nhận thanh toán cho hợp đồng không phải NHÁP (giả định).

## 6. Liên kết với module hiện có (hợp đồng là nguồn sự thật)

- R11. Module của hợp đồng được bật qua `EnableTenantModuleUseCase` hiện có (idempotent, chép role mẫu như cũ) khi hợp đồng HIỆU LỰC và đã tới ngày bắt đầu (giờ VN, đầu ngày bắt đầu): nếu kích hoạt vào/sau ngày bắt đầu thì bật ngay trong lúc kích hoạt; nếu kích hoạt trước ngày bắt đầu thì job (cùng job mục 7, chạy mỗi giờ) bật khi tới ngày. Job bật module cũng phải tự đặt `TenantContext` và idempotent.
- R12. Khi hợp đồng hết hạn (hết ân hạn) hoặc bị hủy: tắt module của hợp đồng qua `DisableTenantModuleUseCase`, NGOẠI TRỪ module vẫn được hợp đồng HIỆU LỰC khác của cùng tenant bao phủ (hợp đồng nối tiếp).
- R13. Thứ tự khi gia hạn nối tiếp: hợp đồng mới (có ngày bắt đầu liền sau ngày kết thúc hợp đồng cũ) được kích hoạt trước khi hợp đồng cũ hết hạn; các hợp đồng HIỆU LỰC không chồng khoảng [bắt đầu, kết thúc] nên R1 vẫn đúng. Khi hợp đồng cũ qua hạn trong lúc hợp đồng mới đã tới ngày bắt đầu thì module không bị tắt (R12). Hợp đồng nối tiếp kích hoạt trong ân hạn của hợp đồng cũ: khoảng ngày vẫn không chồng, module vẫn bật, không bị tắt rồi bật lại. `sa` nêu cách định nghĩa "chồng lấn" theo khoảng ngày (không tính ân hạn).
- R14. Platform admin vẫn có thể bật/tắt module tay bằng API hiện có. Thao tác tay không đổi hợp đồng và không bị hợp đồng ghi đè ngay lập tức:
  - Được ghi vào log `platform-audit` và lịch sử DB với loại "ngoại lệ ngoài hợp đồng" (kèm tenant, module, người thao tác).
  - Tắt tay một module đang thuộc hợp đồng HIỆU LỰC: module tắt cho tới khi platform admin bật lại tay hoặc có hợp đồng kích hoạt mới bao phủ module. Job hết hạn KHÔNG tự bật lại module.
  - Bật tay một module không thuộc hợp đồng nào: giữ nguyên, nhưng khi hợp đồng gần nhất hết hạn, job chỉ tắt module thuộc hợp đồng đó; module bật tay ngoài hợp đồng không bị job tắt.
  - Cần cho `sa` chốt cách phát hiện "module bật tay ngoài hợp đồng" (xem mục 12).
- R15. Quan hệ với ràng buộc 409 `TENANT_MODULE_REQUIRED`: xem mục 7.

## 7. Hành vi khi hết hạn

Job định kỳ (✅ đã chốt chạy MỖI GIỜ; độ trễ xử lý tối đa khoảng 1 giờ, cộng 15 phút của claim JWT) quét hợp đồng HIỆU LỰC đã quá ngày kết thúc cộng ân hạn:

1. Với mỗi module của hợp đồng không được hợp đồng HIỆU LỰC khác bao phủ (R12): tắt module bằng `DisableTenantModuleUseCase`.
2. Nếu việc tắt sẽ khiến tenant không còn module nào (tức mọi module cùng hết hạn): KHÔNG tắt module cuối (tránh 409 `TENANT_MODULE_REQUIRED`); thay vào đó `suspend` tenant bằng use case suspend hiện có. Module cuối vẫn ở trạng thái bật nhưng tenant bị SUSPENDED nên không dùng được.
3. Chuyển hợp đồng sang HẾT HẠN, ghi lịch sử (kèm kết quả: module nào đã tắt, tenant có bị suspend không).
4. Giữ nguyên toàn bộ dữ liệu nghiệp vụ, role và menu.
5. Job chạy ngoài `PermissionInterceptor` nên PHẢI tự đặt `TenantContext` (`callAs(tenantId)`) khi gọi use case bật/tắt module, như `CreateTenantUseCase`.
6. Idempotent và chịu được chạy lặp/chạy song song nhiều bản (nhiều instance identity): `sa` thiết kế khóa (ví dụ khóa DB hoặc `SELECT ... FOR UPDATE SKIP LOCKED`).
7. Lỗi một hợp đồng không được làm dừng cả lượt quét; ghi log và thử lại lượt sau.
8. Kích hoạt lại (✅ đã chốt): khi job suspend tenant vì hết hạn, ghi nhận nguyên nhân "do hết hạn hợp đồng" (cột hoặc bản ghi lịch sử, `sa` chốt cách lưu, kèm hợp đồng gây ra). Khi một hợp đồng mới của tenant đó được kích hoạt và tới hiệu lực (bật được module), hệ thống TỰ `activate` tenant, NHƯNG chỉ khi lần suspend gần nhất của tenant do job hết hạn gây ra. Tenant bị platform admin suspend tay (hoặc lý do khác) thì KHÔNG tự activate. Nếu sau suspend do hết hạn, platform admin lại suspend tay thì nguyên nhân đổi thành "tay", và không tự activate nữa. Việc tự activate ghi lịch sử, log `platform-audit` và dùng use case activate hiện có.

Độ trễ: module bị tắt vẫn dùng được tối đa 15 phút theo TTL access token (đã chấp nhận ở ADR-0009 M2). Suspend tenant có hiệu lực theo cơ chế hiện có của `suspend`.

## 8. Nhắc sắp hết hạn

- Mốc nhắc cấu hình được, mặc định 30, 7, 1 ngày trước ngày kết thúc.
- Nhắc bằng thông báo trong hệ thống (bounded context `notification` của support-service). Người nhận (✅ đã chốt): MỌI người dùng đang hoạt động có role `is_admin` của tenant tại thời điểm nhắc (không có người liên hệ chỉ định); đồng thời platform admin xem được danh sách hợp đồng sắp hết hạn và đang ân hạn qua API.
- Mỗi mốc chỉ nhắc một lần cho mỗi hợp đồng (chống gửi trùng khi job chạy lặp).
- Không gửi email.
- ⚠️ Kafka best-effort: mất event nhắc là mất thông báo; việc nhắc không được là nguồn sự thật. Danh sách sắp hết hạn phải đọc từ DB.

## 9. Dữ liệu và API (mức khái niệm, `sa` chốt chi tiết)

### 9.1. Bảng khái niệm (identity_db, toàn cục, không RLS, thêm vào `TENANT_EXEMPT_TABLES`)

| Bảng | Nội dung chính |
|---|---|
| `tenant_contracts` | id, tenant_id (FK), tên/mã hợp đồng, trạng thái, ngày bắt đầu, ngày kết thúc (null cho legacy), tổng tiền VND, chu kỳ (loại + giá trị), cờ legacy, ghi chú nội bộ, thời điểm tạo/sửa, người tạo/sửa |
| `tenant_contract_modules` | contract_id, module (CHECK LENDING/SALES) |
| `tenant_contract_payments` | id, contract_id, ngày thanh toán, số tiền (có thể âm cho dòng đảo), ghi chú, mã tham chiếu, reverses_payment_id (null), người ghi, thời điểm ghi; không UPDATE/DELETE |
| `tenant_contract_history` | id, contract_id (null được cho ngoại lệ ngoài hợp đồng), tenant_id, loại sự kiện, dữ liệu trước/sau, người thao tác (hoặc `system` cho job), thời điểm |
| nhắc hạn | bản ghi ghi nhận mốc đã nhắc (hoặc cột tương đương) |

Ràng buộc DB: không cho hai hợp đồng HIỆU LỰC chồng thời gian của cùng tenant (ràng buộc loại trừ hoặc kiểm trong transaction); bảng thanh toán không có đường UPDATE/DELETE từ ứng dụng.

### 9.2. API platform (chỉ `@PlatformAdminOnly`, đường dẫn `/api/platform/**`, `lms-api` chuyển nguyên)

| Method | Đường dẫn | Mục đích |
|---|---|---|
| POST | `/api/platform/tenants/{tenantId}/contracts` | Tạo hợp đồng NHÁP |
| GET | `/api/platform/tenants/{tenantId}/contracts` | Danh sách hợp đồng của tenant |
| GET | `/api/platform/contracts/{id}` | Chi tiết (kèm thanh toán, số còn nợ) |
| PUT | `/api/platform/contracts/{id}` | Sửa theo R5 |
| POST | `/api/platform/contracts/{id}/activate` | Kích hoạt |
| POST | `/api/platform/contracts/{id}/cancel` | Hủy (body có `reason`) |
| POST | `/api/platform/contracts/{id}/renew` | Tạo hợp đồng NHÁP nối tiếp (sao chép module, ngày bắt đầu = hôm sau ngày kết thúc) |
| POST | `/api/platform/contracts/{id}/payments` | Ghi thanh toán |
| POST | `/api/platform/contracts/{id}/payments/{paymentId}/reverse` | Ghi dòng đảo |
| GET | `/api/platform/contracts/{id}/history` | Lịch sử |
| GET | `/api/platform/contracts/expiring` | Sắp hết hạn và đang ân hạn (tham số `withinDays`, gom thành record theo quy ước) |

### 9.3. API cho tenant admin (chỉ đọc)

- Một endpoint dạng `GET /api/contracts/current` (và danh sách `GET /api/contracts`) trong ngữ cảnh tenant, theo ADR-0003 (khóa `Controller/method`, gán cho role tenant admin) hoặc `@AuthenticatedOnly` kèm kiểm admin: để `sa` chốt. DTO riêng, KHÔNG chứa ghi chú nội bộ, lịch sử, thông tin ngoại lệ.
- Trả: module, ngày bắt đầu/kết thúc, trạng thái, cờ ân hạn, tổng tiền phải trả. KHÔNG trả số đã thu, số còn nợ, dòng thanh toán (✅ đã chốt).
- Tenant admin truy cập hợp đồng (theo id) của tenant khác: 404 (✅ đã chốt, không lộ sự tồn tại).

### 9.4. Event và audit
- Mọi thay đổi hợp đồng (tạo, sửa, kích hoạt, hủy, hết hạn, thanh toán, đảo, kéo dài, thao tác tay ngoài hợp đồng) phát `PlatformOperationEvent` (best-effort), ghi log `platform-audit`, VÀ ghi `tenant_contract_history` cùng transaction với thay đổi (không chỉ dựa vào Kafka).
- Có thể thêm event miền `TenantContractActivated/Expired` nếu `sa` cần; không service nào bắt buộc nghe để hoạt động đúng.

## 10. Phân quyền

| Nhóm endpoint | Cơ chế |
|---|---|
| `/api/platform/**` hợp đồng | `@PlatformAdminOnly`; tenant admin 403 `FORBIDDEN`; không token 401 |
| Xem hợp đồng của tenant | Chỉ tenant admin (xem 9.3); phải có `tenantId` trong token, thiếu thì 403 `TENANT_REQUIRED`; tenant A không đọc được hợp đồng tenant B |
| Job | Chạy nội bộ, không qua interceptor, tự đặt `TenantContext` |

⚠️ `@PlatformAdminOnly` là tất cả hoặc không gì: mọi platform admin xem được tiền; chấp nhận (không có vai trò kế toán, ngoài phạm vi).
⚠️ Không đổi tên controller/method có sẵn (đổi sẽ làm role mất quyền, ADR-0003). Khóa quyền mới đăng ký ở lần gọi đầu và chưa gán role nào thì tenant admin không phải `is_admin` bị 403: `sa` nêu cách cấp khóa xem hợp đồng cho role tenant admin.

## 11. Dữ liệu hiện có và migration

- Migration mới ở `services/system-service/src/main/resources/db/migration/identity/` (⚠️ kiểm số `n` kế tiếp ngay trước khi tạo; tài liệu cũ ghi identity đã tới V16, có thể đã có thêm).
- Backfill trong cùng migration: mỗi tenant hiện có (kể cả tenant mặc định `00000000-0000-0000-0000-0000000000d1`) nhận một hợp đồng legacy trạng thái HIỆU LỰC, không thời hạn (ngày kết thúc null), giá 0, gắn đúng các module đang bật trong `tenant_modules` tại thời điểm migrate. Mục đích: job hết hạn không tắt module tenant cũ.
- Bảng toàn cục nên không cần `set_config('app.tenant_id', ...)`; vẫn thêm vào `TENANT_EXEMPT_TABLES`.
- Tạo tenant (`POST /api/platform/tenants`) KHÔNG bắt buộc kèm hợp đồng và không tự tạo hợp đồng (✅ đã chốt). Tenant không có hợp đồng nào không bị job động tới (module bật tay như hiện nay); platform admin tạo hợp đồng sau.

## 12. Ràng buộc và việc cho `sa` chốt

1. ⚠️ Nơi đặt: dự kiến identity-service, context `tenant` (bảng toàn cục không RLS). Tuân ArchUnit (domain thuần, application POJO). `sa` xác nhận hoặc đề xuất khác, kèm lý do.
2. ⚠️ Job lập lịch: identity chưa có (tiền lệ `ExpireReservationsJob` ở circulation). `sa` nêu cơ chế lịch, chu kỳ, khóa chống chạy song song.
3. ⚠️ Mâu thuẫn tắt module cuối (409) với hết hạn: đã chốt ở mục 7, `sa` xác nhận luồng suspend không phá đường đi của `SetTenantStatusUseCase`.
4. ⚠️ Độ trễ 15 phút claim `modules` chấp nhận được (ADR-0009); không đổi TTL.
5. ⚠️ Audit nền tảng chỉ log INFO + Kafka best-effort (ADR-0006/0001): bảng lịch sử DB trong cùng transaction là bắt buộc.
6. ⚠️ Thanh toán bất biến: quyền DB của vai trò `identity_app` nên chỉ cấp INSERT/SELECT trên bảng thanh toán nếu khả thi; ít nhất không có đường UPDATE/DELETE trong code, và có test.
7. ⚠️ Múi giờ: tính ngày theo `Asia/Ho_Chi_Minh`, lưu UTC; cần test biên (23:59 và 00:00 ngày kế).
8. ⚠️ Thao tác bật/tắt tay (R14): cách nhận biết module bật ngoài hợp đồng và cách hai nguồn không đè nhau.
9. ⚠️ Quyền platform admin tất cả-hoặc-không (mục 10).
10. ⚠️ Số migration và Flyway: không sửa file đã chạy.
11. ⚠️ Đăng ký khóa quyền và claim JWT: quyền mới của role chỉ hiệu lực ở token kế tiếp.
12. ⚠️ Profile dev tắt kiểm quyền/module; test WebMvc phải bật lại và `@MockitoBean PermissionKeyRegistrar`.

## 13. Tiêu chí nghiệm thu (kiểm được bằng test)

Hợp đồng và vòng đời:
- ✅ AC1. Platform admin tạo hợp đồng NHÁP với module, ngày, giá, chu kỳ; module rỗng hoặc lạ trả 400; ngày kết thúc trước ngày bắt đầu trả 400; thiếu ngày kết thúc trả 400 (không có hợp đồng không thời hạn qua API).
- ✅ AC1b. Kích hoạt trước ngày bắt đầu: hợp đồng thành HIỆU LỰC nhưng module CHƯA bật; job chạy sau khi tới ngày bắt đầu (giờ VN) thì bật module và chép role mẫu; chạy lại job không nhân bản.
- ✅ AC1c. Kéo dài ngày kết thúc hợp đồng HIỆU LỰC: chỉ dời ra sau, ghi lịch sử; dời về trước hoặc gây chồng lấn hợp đồng khác trả lỗi; hợp đồng đang ân hạn được kéo dài qua hôm nay thì không còn cờ ân hạn và không bị job hết hạn.
- ✅ AC2. Kích hoạt hợp đồng chồng thời gian với hợp đồng HIỆU LỰC khác của cùng tenant trả 409; hợp đồng của tenant khác không ảnh hưởng.
- ✅ AC3. Kích hoạt hợp đồng bật đúng các module của hợp đồng (gọi `EnableTenantModuleUseCase`), chép role mẫu, không nhân bản role đã có.
- ✅ AC4. Sửa hợp đồng HIỆU LỰC ngoài các trường cho phép (R5) bị từ chối; HẾT HẠN/ĐÃ HỦY không sửa được.
- ✅ AC5. Hủy hợp đồng yêu cầu lý do; sau hủy module của hợp đồng bị tắt theo R12.
- ✅ AC6. `renew` tạo hợp đồng NHÁP nối tiếp với module sao chép; kích hoạt hợp đồng nối tiếp trước khi hợp đồng cũ hết hạn không làm module bị tắt.

Hết hạn:
- ✅ AC7. Hợp đồng quá ngày kết thúc nhưng còn trong ân hạn (mặc định 7 ngày): chưa tắt module, API trả cờ ân hạn.
- ✅ AC8. Qua ân hạn: job tắt module của hợp đồng, chuyển HẾT HẠN, ghi lịch sử; dữ liệu nghiệp vụ, role, menu giữ nguyên.
- ✅ AC9. Tenant còn module khác bật từ hợp đồng khác: chỉ module của hợp đồng hết hạn bị tắt, module kia giữ.
- ✅ AC10. Mọi module của tenant cùng hết hạn: tenant bị suspend (status SUSPENDED), không phát sinh 409 `TENANT_MODULE_REQUIRED`, job không lỗi.
- ✅ AC11. Chạy job hai lần liên tiếp và chạy song song: kết quả như một lần (không tắt/suspend/ghi lịch sử lặp).
- ✅ AC12. Lỗi ở một hợp đồng không làm lượt quét bỏ qua các hợp đồng khác.
- ✅ AC12b. Tenant bị job suspend vì hết hạn: kích hoạt hợp đồng mới (tới hiệu lực) tự `activate` tenant, ghi lịch sử và `platform-audit`.
- ✅ AC12c. Tenant bị platform admin suspend tay (không do job hết hạn): kích hoạt hợp đồng mới KHÔNG tự activate. Tenant bị job suspend rồi bị suspend tay: không tự activate.
- ✅ AC12d. Job chạy mỗi giờ (cấu hình chu kỳ có thể kiểm bằng test cấu hình lịch).
- ✅ AC13. Job đặt `TenantContext` khi gọi use case (test chạy bằng vai trò app, không thủng RLS: IT).
- ✅ AC14. Test biên múi giờ: hợp đồng kết thúc ngày D hết hạn sau 24:00 giờ VN của D, không phải 24:00 UTC.
- ✅ AC15. Ân hạn đọc từ cấu hình; đổi cấu hình đổi hành vi.

Thanh toán:
- ✅ AC16. Ghi thanh toán lưu đủ ngày, số tiền, ghi chú, mã tham chiếu, người ghi; số còn nợ tính đúng.
- ✅ AC17. Không có API/đường mã nào UPDATE hoặc DELETE dòng thanh toán; sửa sai chỉ bằng dòng đảo toàn phần (số tiền đúng bằng âm của dòng gốc, không nhận số tiền tùy ý); đảo lần hai cùng dòng bị từ chối (409); đảo một dòng đảo bị từ chối; nhiều dòng thanh toán khác nhau mỗi dòng đảo được một lần.
- ✅ AC18. Không cho ghi thanh toán vào hợp đồng NHÁP (R10).

Thao tác tay và liên kết module:
- ✅ AC19. Bật/tắt module tay bằng API hiện có vẫn hoạt động; mỗi thao tác sinh bản ghi lịch sử loại "ngoại lệ ngoài hợp đồng" và log `platform-audit`.
- ✅ AC20. Module tắt tay khi hợp đồng đang HIỆU LỰC không bị job tự bật lại; module bật tay ngoài hợp đồng không bị job tắt khi hợp đồng khác hết hạn.

Phân quyền và cô lập:
- ✅ AC21. Tenant admin gọi API `/api/platform/**` hợp đồng nhận 403 `FORBIDDEN`; không token 401.
- ✅ AC22. Tenant admin chỉ đọc được hợp đồng của tenant mình; truy cập hợp đồng của tenant khác theo id trả 404; không token tenant thì 403 `TENANT_REQUIRED`.
- ✅ AC23. DTO tenant admin chỉ chứa module, tên, ngày, trạng thái, cờ ân hạn, TỔNG TIỀN; không có số đã thu, số còn nợ, dòng thanh toán, ghi chú nội bộ, lịch sử, ngoại lệ ngoài hợp đồng.
- ✅ AC24. Người dùng thường không xem được hợp đồng.

Nhắc hạn:
- ✅ AC25. Hợp đồng còn đúng 30, 7, 1 ngày tới ngày kết thúc phát đúng một thông báo cho MỖI người dùng có role `is_admin` của tenant ở mỗi mốc (người không có role `is_admin` và người của tenant khác không nhận); chạy job lặp không gửi trùng.
- ✅ AC25b. Tenant không có người dùng `is_admin` nào: không lỗi, ghi log cảnh báo, hợp đồng vẫn hiện trong danh sách sắp hết hạn của platform admin.
- ✅ AC26. `GET /api/platform/contracts/expiring` đọc từ DB, trả hợp đồng sắp hết hạn trong `withinDays` và đang ân hạn.
- ✅ AC27. Đổi mốc nhắc qua cấu hình đổi hành vi.

Lịch sử/audit:
- ✅ AC28. Mỗi thay đổi (tạo, sửa, kích hoạt, hủy, hết hạn, thanh toán, đảo, kéo dài, thao tác tay) có dòng trong `tenant_contract_history` cùng transaction, kể cả khi Kafka lỗi; có `PlatformOperationEvent` và log `platform-audit` khi Kafka khả dụng.
- ✅ AC29. Job ghi lịch sử với người thao tác là `system`.

Dữ liệu và kiến trúc:
- ✅ AC30. Migration backfill: mỗi tenant hiện có (kể cả tenant mặc định `00000000-0000-0000-0000-0000000000d1`) có đúng một hợp đồng legacy HIỆU LỰC, không thời hạn, giá 0, module khớp `tenant_modules` đang bật; chạy job hết hạn ngay sau migration không tắt module nào (IT khởi động trên DB có dữ liệu cũ, kiểm số dòng).
- ✅ AC31. Bảng mới nằm trong `TENANT_EXEMPT_TABLES`; ArchUnit (`TenantArchitectureRulesTest` và luật tầng) xanh; `mvn test` toàn bộ xanh; IT identity hiện có vẫn xanh.
- ✅ AC32. Tài liệu API ghi vào `docs/api/` (hợp đồng platform và hợp đồng tenant admin) và một ADR mới do `sa` viết.

## 14. Giả định

- ℹ️ Ngày hết hạn theo giờ Việt Nam (`Asia/Ho_Chi_Minh`), lưu timestamp UTC, hết hạn vào cuối ngày kết thúc.
- ℹ️ Ân hạn mặc định 7 ngày; mốc nhắc mặc định 30/7/1 ngày; cả hai cấu hình được.
- ℹ️ Tiền là số nguyên VND, không có tiền tệ khác, không có thuế.
- ℹ️ Hợp đồng tạo mới bắt buộc có ngày kết thúc; chỉ hợp đồng legacy (migration) không thời hạn (✅ đã chốt).
- ℹ️ Gói/tên hợp đồng là chuỗi tự do, không có danh mục.
- ℹ️ Hợp đồng không tự suy ra giới hạn dùng nào; module là phạm vi duy nhất.
- ℹ️ FE là việc ngoài repo BE.
- ℹ️ Nơi đặt dự kiến identity-service, `sa` chốt.

## 15. Câu hỏi còn mở

### Đã chốt (người dùng, 2026-11-17)

1. ✅ Cho kích hoạt hợp đồng trước ngày bắt đầu; module bật khi tới ngày bắt đầu (R11, mục 5.2, AC1b).
2. ✅ Tenant bị job suspend vì hết hạn: kích hoạt hợp đồng mới tự `activate` lại, chỉ khi lần suspend đó do job hết hạn gây ra (mục 7 điểm 8, AC12b, AC12c).
3. ✅ Tạo tenant không bắt buộc kèm hợp đồng (mục 11).
4. ✅ Không có hợp đồng không thời hạn tạo mới qua API, chỉ hợp đồng legacy backfill (R3, AC1).
5. ✅ Giữ thao tác kéo dài ngày kết thúc hợp đồng HIỆU LỰC (R5, AC1c).
6. ✅ Tenant admin chỉ thấy tổng tiền phải trả; hợp đồng tenant khác trả 404 (mục 3, 9.3, AC22, AC23).
7. ✅ Dòng đảo thanh toán chỉ đảo toàn phần, mỗi dòng đảo một lần (R8, AC17).
8. ✅ Người nhận nhắc hạn là mọi người dùng có role `is_admin` của tenant; job chạy mỗi giờ (mục 7, 8, AC25, AC12d).

### Còn mở cho `sa`

1. ⏳ Cách lưu nguyên nhân suspend ("do hết hạn hợp đồng" hay "tay") và liên kết tới hợp đồng gây ra (cột trên `tenants`, hay suy từ `tenant_contract_history`). Cần chống đua: suspend tay xen vào giữa job và lúc kích hoạt hợp đồng mới.
2. ⏳ Cách identity báo cho support-service tạo thông báo nhắc hạn (Kafka best-effort không phải nguồn sự thật; job phải đọc DB và đánh dấu mốc đã nhắc), và cách lấy danh sách người dùng `is_admin` của tenant trong ngữ cảnh job.
3. ⏳ Định nghĩa "chồng lấn" theo khoảng ngày (không tính ân hạn) và ràng buộc DB tương ứng (R1, R13).
