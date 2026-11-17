# Đề bài: Mở rộng nền tảng cho hiệu sách, thêm module Bán hàng (SALES)

Ngày: 2026-11-16. Người viết: BA. Người nhận: `sa`, `be-dev`, `tester`.

## 1. Mục tiêu

Biến hệ thống hiện tại (quản lý thư viện, multi-tenant) thành một nền tảng cho thuê (SaaS) phục vụ nhiều loại hình: thư viện (module LENDING, đã có) và hiệu sách (module SALES, mới). Mỗi tenant được platform admin bật module phù hợp; tenant có thể bật cả hai. Module SALES hỗ trợ:

- Bán tại quầy (POS) cho khách vãng lai hoặc khách có tài khoản.
- Khách tự đặt hàng online (nhận tại quầy hoặc tenant tự giao).
- Quản lý giá bán, tồn kho, nhập kho, nhà cung cấp, hóa đơn (phiếu bán hàng), trả/đổi hàng, có audit.

## 2. Bối cảnh (đã xác nhận qua code và tài liệu)

- ℹ️ Multi-tenant dùng chung DB, RLS theo `app.tenant_id`, token theo tenant (ADR-0006).
- ℹ️ Phân quyền động theo khóa `TênController/tênMethod`, role là dữ liệu, cờ `is_admin` quyết định quản trị (ADR-0003). Không hardcode tên role.
- ℹ️ Catalog có `Title`/`Copy` theo tenant; `coverPrice` hiện dùng tính phí phạt.
- ⚠️ Tenant hiện chưa có khái niệm "module bật/tắt"; chưa có cơ chế chặn endpoint theo module.
- ⚠️ Chưa có chuẩn xử lý tiền/VAT/làm tròn trong codebase.
- ⚠️ Chưa có Outbox (ADR-0001): `KafkaEventPublisher` best-effort, mất event khi Kafka lỗi.
- ⚠️ Tạo service/DB mới đòi hỏi nhiều chỗ: `system-service` (migration), `infrastructure/postgres/init-databases.sql`, `docker-compose`, nginx, `lms-api` (BFF), vai trò DB `<svc>_app`, RLS.
- ℹ️ Nợ có sẵn, ngoài phạm vi: `borrow_rules` chưa nối vào `BorrowBookUseCase`.

## 3. Phạm vi

### 3.1 Làm

- ⏳ Cơ chế module theo tenant: `LENDING`, `SALES`; platform admin bật/tắt qua `/api/platform/**`.
- ⏳ Chặn endpoint theo module (403 `MODULE_NOT_ENABLED`) và ẩn menu theo module.
- ⏳ Giá bán `sellingPrice` theo đầu sách, mặc định bằng `coverPrice`.
- ⏳ Tồn kho bán theo số lượng, sổ cái biến động, cảnh báo dưới ngưỡng.
- ⏳ Nhà cung cấp và phiếu nhập kho cơ bản.
- ⏳ Bán POS: đơn nhiều dòng, khách vãng lai hoặc gắn user.
- ⏳ Bán online: khách (user của tenant) đặt hàng, nhận tại quầy hoặc giao tự quản.
- ⏳ Thanh toán: chỉ ghi nhận phương thức, nhân viên đánh dấu đã thu.
- ⏳ Phiếu bán hàng in/xem được; VAT cấu hình theo tenant.
- ⏳ Trả/đổi hàng trong N ngày (cấu hình theo tenant), hoàn tiền, cộng lại tồn.
- ⏳ Giảm giá nhập tay theo dòng/đơn (quyền riêng).
- ⏳ Audit mọi bước ghi.
- ⏳ Vai trò/khóa quyền mới cho thu ngân, thủ kho, quản lý cửa hàng, khách.

### 3.2 Không làm (xem thêm mục 11)

Cổng thanh toán, hóa đơn điện tử, hãng vận chuyển, mã khuyến mãi, đa kho/chi nhánh, danh mục ISBN toàn cục, tính tiền gói thuê.

## 4. Người dùng và vai trò

| Vai trò (khái niệm) | Việc chính |
|---|---|
| Thu ngân | Lập đơn POS, đánh dấu đã thu tiền, in phiếu, xử lý trả hàng nếu được cấp quyền |
| Thủ kho | Nhập kho, nhà cung cấp, điều chỉnh/kiểm kho, xem cảnh báo tồn thấp |
| Quản lý cửa hàng | Cấu hình giá bán, VAT, N ngày trả hàng, phí ship; duyệt giảm giá, trả hàng, xem báo cáo đơn |
| Khách | Đăng ký tài khoản (user của tenant), xem sách bán, đặt đơn online, xem đơn của mình |
| Platform admin | Bật/tắt module cho tenant (không phải vai trò của tenant) |

Các "vai trò" trên chỉ là gợi ý nhóm khóa quyền khi seed; bản chất là role dữ liệu gán tập khóa `Controller/method`. Không hardcode tên role trong code.

## 5. Quy tắc nghiệp vụ và ngoại lệ

### 5.1 Module theo tenant

- Tenant hiện có: backfill `LENDING`. Tenant mới: mặc định `LENDING`. Tenant có thể bật cả hai.
- Chỉ platform admin bật/tắt (`/api/platform/**`). Chưa tính tiền gói.
- ✅ Đã chốt: tenant phải có ít nhất 1 module bật; tắt module cuối cùng bị từ chối.
- ✅ Đã chốt: tắt module chấp nhận có độ trễ hiệu lực tối đa 15 phút (cho phép cache/token; sa chọn cơ chế trong giới hạn này, bật module thì nên có hiệu lực sớm nhất có thể).
- Tắt module: dữ liệu giữ nguyên; mọi endpoint thuộc module trả 403 mã `MODULE_NOT_ENABLED`; menu ẩn.
- Chặn theo module áp hai chiều: endpoint lending khi tenant không bật LENDING, endpoint sales khi không bật SALES.
- Ngoại lệ: platform admin (token không có `tenantId`) không bị chặn bởi module; endpoint `@AuthenticatedOnly` chung (đăng nhập, đổi mật khẩu, menu) không bị chặn.
- Đơn đang dở khi tắt SALES: giữ nguyên dữ liệu, không thao tác thêm được cho đến khi bật lại.

### 5.2 Giá và tồn kho

- `sellingPrice` riêng; nếu chưa đặt thì dùng `coverPrice`. `coverPrice` vẫn dùng cho tính phí phạt, không đổi.
- Tồn kho bán là số lượng (không theo từng bản sao), độc lập hoàn toàn với `Copy` cho mượn. Một đầu sách có thể vừa có bản sao cho mượn vừa có tồn kho bán, hai kho không ảnh hưởng nhau.
- Một kho mỗi tenant.
- Mọi biến động ghi sổ cái: nhập, bán, trả, điều chỉnh, kiểm kho; số tồn hiện tại là kết quả nhất quán với sổ cái.
- Không bán vượt tồn: đơn bị từ chối. Không bao giờ tồn âm, kể cả khi hai người bán đồng thời (yêu cầu kiểm soát đồng thời, xem mục 9).
- Cảnh báo khi tồn dưới ngưỡng (ngưỡng cấu hình, mức mặc định do sa đề xuất).
- Điều chỉnh/kiểm kho bắt buộc có lý do; cần quyền riêng.

### 5.3 Nhà cung cấp và nhập kho

- Nhà cung cấp: tên, liên hệ cơ bản.
- Phiếu nhập kho: nhà cung cấp, danh sách dòng (đầu sách, số lượng, giá nhập). Xác nhận phiếu mới cộng tồn và ghi sổ cái. Phiếu đã xác nhận không sửa; sai thì điều chỉnh.
- Chưa tính giá vốn bình quân hay báo cáo lãi lỗ (ngoài phạm vi).

### 5.4 Đơn hàng và trạng thái

Tiền tệ: chỉ VND; đơn ghi sẵn trường `currency` (giá trị VND). Số tiền là số nguyên đồng; quy tắc làm tròn VAT do sa chốt.

Trạng thái đơn (đề xuất, sa chốt chi tiết):

| Trạng thái | Ý nghĩa | Ai chuyển |
|---|---|---|
| ⏳ Chờ xác nhận | Đơn online vừa tạo; tồn đã bị trừ/giữ ngay lúc đặt (✅ đã chốt) | Khách tạo |
| 🔄 Đã xác nhận, chờ thanh toán | Nhân viên xác nhận đơn, chờ thu tiền (chuyển khoản/COD) | Nhân viên có quyền xác nhận đơn |
| 🔄 Đã thu tiền | Nhân viên đánh dấu đã nhận tiền (tiền mặt/chuyển khoản/thẻ; với COD là khi giao xong) | Thu ngân hoặc nhân viên có quyền xác nhận thu |
| 🔄 Đang giao / Sẵn sàng nhận tại quầy | Hoàn tất chuẩn bị hàng | Nhân viên |
| ✅ Hoàn tất | Khách đã nhận hàng và đã thu đủ | Nhân viên |
| ❌ Đã hủy | Hủy trước khi hoàn tất; hoàn tồn đã trừ/giữ | Nhân viên có quyền, hoặc khách (chỉ khi chưa xác nhận) |
| ⚠️ Trả một phần/toàn bộ | Có phiếu trả liên kết đơn | Qua luồng trả hàng |

Đơn POS: tạo và thu tiền ngay, đi thẳng đến Hoàn tất (một bước hoặc hai bước tùy sa).

Quy trình thu tiền thủ công cho đơn online:
- Chuyển khoản: khách chuyển ngoài hệ thống; nhân viên đối soát sao kê rồi đánh dấu "đã thu", ghi người xác nhận, thời điểm, tham chiếu giao dịch (nhập tay, tùy chọn).
- COD: shipper/tenant thu khi giao; nhân viên đánh dấu "đã thu" sau khi nhận tiền.
- Thẻ (POS): nhân viên đánh dấu đã thu sau khi quẹt máy ngoài hệ thống.
- Hệ thống không tự kiểm chứng tiền về. Mọi lần đánh dấu đã thu đều ghi audit (ai, khi nào, số tiền, phương thức).
- ✅ Đã chốt: đơn online được trừ/giữ tồn NGAY khi khách đặt (ghi sổ cái loại bán/giữ), hết tồn thì từ chối đặt. Khi đơn bị hủy (khách hoặc nhân viên) tồn được hoàn lại và ghi sổ cái. Trừ tồn và tạo đơn cùng một giao dịch DB.
- ✅ Đã chốt: đơn online chuyển khoản quá hạn chưa thu thì nhân viên có quyền hủy tay; không có hủy tự động ở giai đoạn 1.

### 5.5 Online, nhận hàng và phí ship

- Khách phải có tài khoản (user của tenant).
- ✅ Đã chốt: tài khoản khách có hai đường: nhân viên tạo hộ, hoặc khách tự đăng ký. Giai đoạn 1 chưa xác thực email/điện thoại. Sa đánh giá chống lạm dụng tối thiểu (giới hạn tần suất đăng ký) nhưng không thêm bước xác thực.
- Hai hình thức nhận: nhận tại quầy (phí ship 0) hoặc giao tự quản.
- Phí ship: nhân viên nhập tay khi xác nhận đơn, hoặc phí cố định theo tenant (cấu hình). Không tính theo khoảng cách/cân nặng; không tích hợp hãng vận chuyển.
- Giao tự quản: ghi địa chỉ, số điện thoại người nhận trong đơn; trạng thái do nhân viên cập nhật.

### 5.6 Hóa đơn (phiếu bán hàng) và VAT

- Phiếu bán hàng xem và in được (dữ liệu để FE in; hệ thống không phát hành hóa đơn điện tử).
- ✅ Đã chốt: phí ship không chịu VAT (VAT chỉ tính trên tiền hàng sau giảm giá; phí ship cộng vào tổng sau thuế).
- VAT cấu hình theo tenant: thuế suất (mặc định 5%) và "giá đã gồm thuế" (mặc định có) hay "chưa gồm thuế". Phiếu hiển thị tiền hàng, giảm giá, VAT, phí ship, tổng.
- Thay đổi cấu hình VAT chỉ áp dụng cho đơn mới; đơn cũ giữ nguyên số liệu đã chốt (snapshot thuế suất và cờ giá-đã-gồm-thuế trên đơn).
- Phiếu của đơn đã xuất không sửa số liệu.

### 5.7 Giảm giá

- ✅ Đã chốt: nhập tay theo dòng hoặc theo đơn, hỗ trợ cả số tiền và phần trăm.
- ✅ Đã chốt: chỉ cần khóa quyền riêng, không có ngưỡng và không có luồng duyệt; mọi giảm giá ghi audit.
- Chưa có mã khuyến mãi/voucher.
- Giảm giá không làm tổng âm; không giảm sâu hơn giá trị dòng.

### 5.8 Trả/đổi hàng

- Cho phép trong N ngày kể từ ngày bán (hoàn tất/giao), N cấu hình theo tenant. Quá hạn: từ chối, ✅ đã chốt: chỉ người có quyền riêng (quản lý) được cho phép ngoại lệ, phải ghi lý do và audit.
- Phiếu trả liên kết đơn gốc; trả theo dòng và số lượng, không vượt số đã mua trừ số đã trả.
- Cộng lại tồn kho khi trả (ghi sổ cái). ✅ Đã chốt: với mỗi dòng trả, nhân viên chọn "nhập lại tồn" (bán lại được) hoặc "ghi giảm/hao hụt" (hàng hỏng, không cộng tồn bán nhưng vẫn ghi sổ cái loại hao hụt); hoàn tiền không phụ thuộc lựa chọn này.
- Hoàn tiền: theo phương thức gốc hoặc tiền mặt; nhân viên đánh dấu đã hoàn (ghi nhận thủ công như thu tiền).
- Đổi hàng: mô hình là phiếu trả cộng một đơn mới, liên kết với nhau; chênh lệch thu/hoàn theo đơn mới.
- Quyền riêng cho trả hàng và hoàn tiền; mọi bước ghi audit.
- Đơn chưa thu tiền thì không có hoàn tiền; hủy đơn thay cho trả hàng.

### 5.9 Dữ liệu cá nhân

- Khách có tài khoản: đơn tham chiếu `userId`, không sao chép dư thừa.
- Khách POS vãng lai: không bắt buộc tên/điện thoại. Nếu nhân viên nhập tên/điện thoại để phục vụ trả hàng hoặc liên hệ, đó là dữ liệu cá nhân lưu trong đơn: chỉ lưu khi cần, hiển thị che một phần ở danh sách, người có quyền mới xem đầy đủ, không đưa vào log. Địa chỉ giao hàng cũng là dữ liệu cá nhân, cùng quy tắc.
- ✅ Đã chốt: khách vãng lai chỉ lưu tối thiểu (tên, điện thoại, đều tùy chọn), che một phần khi hiển thị, thời gian giữ cấu hình theo tenant, mặc định 12 tháng. Hết hạn thì ẩn danh: chỉ xóa tên và điện thoại, đơn giữ lại để đối soát (số liệu tiền, dòng hàng không đổi). Cần tác vụ định kỳ ẩn danh, có audit. Yêu cầu pháp lý chi tiết (quyền xóa theo yêu cầu) ghi ở câu hỏi mở cho sa/pháp lý.

### 5.10 Giá trị mặc định cấu hình theo tenant (✅ đã chốt)

Mọi giá trị đều cấu hình được theo tenant; mặc định cho tenant mới bật SALES:

| Cấu hình | Mặc định |
|---|---|
| Số ngày trả/đổi hàng (N) | 7 |
| Ngưỡng tồn thấp | 5 |
| Thuế suất VAT | 5% (tenant có thể nhập giá trị khác) |
| Giá đã gồm thuế (mặc định) | Có (đã chốt; tenant đổi được) |
| Thời gian giữ dữ liệu cá nhân khách vãng lai | 12 tháng, sau đó ẩn danh (cấu hình theo tenant) |
| Phí ship cố định | tenant nhập, mặc định 0 |

## 6. Phân quyền

- Mọi endpoint mới dùng khóa động `TênController/tênMethod` (ADR-0003), tự đăng ký lần gọi đầu; role nhận khóa qua gán role. Không dùng `@RequirePermission("KEY")`, không `hasAuthority`, không hardcode tên role.
- Endpoint khách (xem sách bán, đặt đơn, xem đơn của mình): có thể `@AuthenticatedOnly` kèm kiểm sở hữu theo `userId` ở tầng application, hoặc khóa gán cho role khách; sa chọn và ghi ADR (câu hỏi cho sa số 3).
- Nhóm hành động cần khóa riêng (mỗi hành động một method/khóa để gán độc lập): tạo đơn POS, xác nhận đơn online, đánh dấu đã thu, hủy đơn, giảm giá, tạo phiếu trả, hoàn tiền, nhập kho, điều chỉnh/kiểm kho, quản lý nhà cung cấp, cấu hình giá bán, cấu hình VAT/N ngày/phí ship, xem sổ cái tồn.
- Seed vai trò mẫu (thu ngân, thủ kho, quản lý cửa hàng, khách) bằng migration/seed dữ liệu cho tenant bật SALES, gán sẵn khóa tương ứng; chỉ là dữ liệu, tenant admin sửa được. Do khóa chỉ được đăng ký khi endpoint được gọi lần đầu, sa cần giải pháp để seed gán khóa chưa tồn tại (câu hỏi cho sa số 5).
- Bật/tắt module: chỉ platform admin, qua `/api/platform/**`; khóa riêng.
- Khóa thay đổi khi đổi tên controller/method làm role mất quyền; quyền mới có hiệu lực khi đăng nhập hoặc refresh token kế tiếp (hành vi hiện có).
- Chặn module và chặn quyền độc lập nhau: có quyền nhưng module tắt vẫn 403 `MODULE_NOT_ENABLED`.

## 7. API và dữ liệu dự kiến (mức khái niệm)

Không thiết kế bảng chi tiết; sa quyết định.

Khái niệm dữ liệu:
- Cấu hình module theo tenant (tenant, mã module, bật/tắt, thời điểm, người đổi).
- Cấu hình bán hàng theo tenant: VAT (thuế suất, giá đã gồm thuế), N ngày trả hàng, phí ship cố định, ngưỡng tồn thấp, currency (VND).
- Giá bán theo đầu sách (`sellingPrice`, tham chiếu `titleId` của tenant).
- Tồn kho bán theo đầu sách và sổ cái biến động (loại: nhập, bán, trả, điều chỉnh, kiểm kho; số lượng; tham chiếu chứng từ; người thực hiện; lý do).
- Nhà cung cấp; phiếu nhập kho và dòng.
- Đơn bán và dòng (kênh POS/online, khách `userId` hoặc vãng lai, phương thức thanh toán, snapshot giá/giảm giá/VAT/phí ship, currency, trạng thái, người thu).
- Phiếu trả và dòng, liên kết đơn gốc; bản ghi hoàn tiền.

API khái niệm (đường dẫn qua BFF `/api/**`, service `/internal/**`; tên cụ thể do sa):
- Platform: xem/bật/tắt module của tenant.
- Tenant hiện tại: lấy danh sách module bật (để FE ẩn menu).
- Giá bán, tồn kho, sổ cái, nhập kho, nhà cung cấp, điều chỉnh/kiểm kho.
- Đơn: tạo POS, tạo online, xác nhận, đánh dấu đã thu, cập nhật giao/nhận, hủy, xem, xem phiếu in.
- Trả hàng: tạo phiếu trả, đánh dấu hoàn tiền, xem.
- Cấu hình bán hàng theo tenant.

Dữ liệu và migration:
- Mọi bảng mới có `tenant_id`, RLS + FORCE, entity `@TenantId`; bảng ngoại lệ phải nằm trong `TENANT_EXEMPT_TABLES` (ArchUnit bắt thiếu).
- Migration đặt ở `services/system-service/.../db/migration/<target>/`, kiểm tra số `n` kế tiếp.
- Backfill module `LENDING` cho mọi tenant hiện có; migration ghi bảng RLS phải đặt `app.tenant_id` hoặc chạy bằng superuser.

## 8. Module theo tenant và menu

- FE nhận danh sách module bật của tenant hiện tại; menu của module tắt ẩn. Menu hiện do `menu` context của system-service quản lý (system_db); sa quyết định ẩn theo module ở đâu (lọc ở BE hay FE).
- Chặn endpoint theo module ở tầng chung (ví dụ filter/interceptor trong `security-common` hoặc BFF), không rải kiểm tra vào từng use case. Cách thực hiện do sa quyết định.

## 9. Ràng buộc và rủi ro chuyển cho sa

| # | Điều sa phải giải quyết |
|---|---|
| ⚠️ 1 | Module chưa có trong mô hình tenant: nơi lưu (identity vì tenant ở đó, hay system-service), nơi đọc nhanh, và cách đưa vào token hay tra cứu mỗi request (ảnh hưởng thời điểm hiệu lực khi bật/tắt). |
| ⚠️ 2 | Chưa có cơ chế chặn theo module: thiết kế một điểm chặn chung, mã lỗi `MODULE_NOT_ENABLED` theo `ErrorResponse`, hai chiều lending/sales, ngoại lệ platform admin và `@AuthenticatedOnly`. |
| ⚠️ 3 | Tiền và VAT chưa có chuẩn: kiểu số (số nguyên đồng), quy tắc làm tròn, giá đã gồm/chưa gồm thuế, tổng dòng so với tổng đơn. Cần ADR ngắn. |
| ⚠️ 4 | Đồng thời: không bán âm khi hai người bán cùng lúc (khóa hàng/cập nhật có điều kiện/khóa lạc quan); trả hàng đồng thời trên cùng đơn; hai người đánh dấu thu cùng một đơn. Cần test đồng thời. |
| ⚠️ 5 | Chưa có Outbox (ADR-0001): không dùng event Kafka để trừ tồn hoặc ghi tiền. Trừ tồn, ghi sổ cái và ghi tiền phải cùng giao dịch DB trong một service. Event/audit/thông báo chỉ là phụ, best-effort. Audit mọi bước: cần quyết định cách bảo đảm (ghi cùng giao dịch hay qua event), vì audit qua Kafka có thể mất. |
| ⚠️ 6 | Service/DB: đặt SALES trong service mới hay trong catalog-service/circulation-service. Nếu tạo service/DB mới cần: migration ở `system-service` (target mới), `init-databases.sql` (DB + vai trò `<svc>_app`), docker-compose, nginx, route/client/controller ở `lms-api`, ArchUnit, tenant RLS. Sa so sánh chi phí và quyết định bằng ADR. |
| ⚠️ 7 | Seed vai trò/khóa quyền chưa tồn tại (khóa tự đăng ký khi gọi lần đầu), seed theo tenant bật module. |
| ⚠️ 8 | Cách ly dữ liệu giữa lending và sales trên cùng đầu sách: sales tham chiếu `titleId` qua ID/API của catalog (không truy cập DB chéo), xử lý đầu sách bị xóa/ẩn khi còn đơn lịch sử (snapshot tên, giá trên dòng đơn). |
| ⚠️ 9 | Khách đăng ký tài khoản: tận dụng luồng user của identity (tenant, role khách), không tạo hệ thống tài khoản riêng. |

## 10. Tiêu chí nghiệm thu (kiểm được bằng test)

Module và menu
- ⏳ Tenant chỉ bật LENDING: gọi endpoint sales trả 403 `MODULE_NOT_ENABLED`; menu không có mục sales.
- ⏳ Tenant chỉ bật SALES: gọi endpoint lending trả 403 `MODULE_NOT_ENABLED`; menu không có mục lending.
- ⏳ Tenant bật cả hai: cả hai nhóm endpoint dùng được, menu có cả hai.
- ⏳ Tắt module rồi bật lại: dữ liệu còn nguyên.
- ⏳ Chỉ platform admin gọi được API bật/tắt; tenant admin nhận 403.
- ⏳ Tenant hiện có sau migration có LENDING; tenant mới mặc định LENDING.
- ⏳ Test lending hiện có vẫn xanh (mvn test, các IT liên quan).

Kho và bán
- ⏳ Tenant bật cả hai: một đầu sách có bản sao cho mượn và tồn kho bán; mượn không đổi tồn bán, bán không đổi số bản sao khả dụng.
- ⏳ Đơn nhiều dòng: tồn giảm đúng từng dòng, sổ cái ghi tương ứng.
- ⏳ Bán vượt tồn bị từ chối, đơn không được tạo một phần.
- ⏳ Hai giao dịch bán đồng thời cùng một đầu sách còn tồn 1: đúng một thành công, tồn không âm (test đồng thời).
- ⏳ Nhập kho xác nhận làm tồn tăng đúng; tồn thấp hơn ngưỡng sinh cảnh báo.
- ⏳ `sellingPrice` chưa đặt thì dùng `coverPrice`; đổi `sellingPrice` không đổi `coverPrice` và không đổi phí phạt.

Đơn, thanh toán, hóa đơn
- ⏳ Đơn có phương thức thanh toán (tiền mặt/chuyển khoản/thẻ), currency VND.
- ⏳ Đơn online chuyển khoản/COD: chưa có "đã thu" cho đến khi nhân viên có quyền đánh dấu; audit ghi người, thời điểm, số tiền.
- ⏳ VAT giá-đã-gồm-thuế và chưa-gồm-thuế cho ra tổng đúng theo quy tắc làm tròn; đổi cấu hình sau đó không đổi đơn cũ.
- ⏳ Phiếu bán hàng xem/in được với đủ tiền hàng, giảm giá, VAT, phí ship, tổng.
- ⏳ Giảm giá cần quyền riêng; thiếu quyền bị 403; có quyền thì không cần duyệt thêm; hỗ trợ số tiền và phần trăm; giảm giá không làm tổng âm.
- ⏳ Đơn online: tồn bị trừ ngay khi đặt; đặt vượt tồn bị từ chối; hủy đơn hoàn đúng tồn và ghi sổ cái.
- ⏳ Đơn online chuyển khoản quá hạn không tự hủy; nhân viên có quyền hủy tay được.
- ⏳ Khách tự đăng ký được và nhân viên tạo hộ được; không yêu cầu xác thực email/điện thoại.
- ⏳ Tenant mới bật SALES có cấu hình mặc định: trả hàng 7 ngày, ngưỡng tồn 5, VAT 5% giá đã gồm thuế, giữ dữ liệu cá nhân khách vãng lai 12 tháng; sửa được.
- ⏳ Phí ship không chịu VAT: VAT không tính trên phí ship; phiếu hiển thị phí ship tách riêng.
- ⏳ Hết thời gian giữ (mặc định 12 tháng): tên và điện thoại khách vãng lai bị xóa, đơn và số liệu tiền còn nguyên; có audit.
- ⏳ Tắt module cuối cùng của tenant bị từ chối.
- ⏳ Sau khi tắt module, chậm nhất 15 phút endpoint trả 403 `MODULE_NOT_ENABLED`.
- ⏳ Khách chỉ xem được đơn của chính mình; xem đơn người khác bị từ chối.
- ⏳ Khách POS vãng lai không cần tài khoản; thông tin cá nhân nhập thêm bị che ở danh sách và không xuất hiện trong log.

Trả hàng
- ⏳ Trả trong N ngày: thành công, tồn cộng lại, có bản ghi hoàn tiền; quá N ngày bị từ chối.
- ⏳ Trả quá N ngày chỉ thành công khi người có quyền ngoại lệ riêng thực hiện (kèm lý do, audit); người không có quyền bị từ chối.
- ⏳ Trả hàng chọn "nhập lại tồn" thì tồn tăng; chọn "ghi giảm/hao hụt" thì tồn bán không tăng và sổ cái có bút toán hao hụt.
- ⏳ Trả vượt số lượng đã mua (tính cả lần trả trước) bị từ chối.
- ⏳ Phiếu trả liên kết đơn gốc; thiếu quyền trả/hoàn tiền bị 403.

Audit và cách ly
- ⏳ Mọi bước ghi (bật/tắt module, nhập kho, điều chỉnh, tạo/hủy đơn, đánh dấu thu, giảm giá, trả hàng, hoàn tiền) có bản ghi audit.
- ⏳ IT cách ly tenant theo mẫu `CatalogTenantIsolationIT` cho mọi bảng mới: tenant A không thấy/ghi dữ liệu tenant B; không có tenant thì 403 `TENANT_REQUIRED`.
- ⏳ `TenantArchitectureRulesTest` và các ArchUnit khác xanh với bảng/entity mới.

## 11. Ngoài phạm vi

- 🚫 `borrow_rules` chưa nối `BorrowBookUseCase` (nợ có sẵn, không xử lý ở đây).
- 🚫 Cổng thanh toán (VNPay, MoMo, thẻ trực tuyến...).
- 🚫 Hóa đơn điện tử, kết nối cơ quan thuế.
- 🚫 Tích hợp hãng vận chuyển, tính phí ship tự động.
- 🚫 Mã khuyến mãi, voucher, chương trình giảm giá tự động.
- 🚫 Đa kho, chuỗi chi nhánh.
- 🚫 Danh mục ISBN toàn cục dùng chung giữa các tenant.
- 🚫 Tính tiền gói thuê, giới hạn theo gói.
- 🚫 Tiền tệ khác VND (chỉ ghi sẵn `currency`).
- 🚫 Giá vốn, báo cáo lãi lỗ, kế toán.
- 🚫 Giỏ hàng nâng cao, wishlist, đánh giá sản phẩm, gợi ý.

## 12. Giả định

- ℹ️ Tồn kho bán theo số lượng, không theo mã từng cuốn.
- ℹ️ Đầu sách (`Title`) theo tenant như hiện tại; sales tham chiếu `titleId` của tenant.
- ℹ️ Khách mua online là user của tenant; khách POS vãng lai không có tài khoản.
- ℹ️ Đơn POS tại quầy thu tiền ngay, không có giao hàng.
- ℹ️ Platform admin không bị chặn bởi module khi thao tác quản trị.
- ℹ️ Số tiền dùng số nguyên đồng.

## 13. Câu hỏi mở

Đã chốt (người dùng trả lời, đã chuyển vào mục 5 và 10):
1. ✅ Đơn online trừ/giữ tồn ngay khi đặt, hoàn khi hủy.
2. ✅ Chuyển khoản quá hạn: nhân viên hủy tay.
3. ✅ Khách tự đăng ký hoặc nhân viên tạo hộ; chưa xác thực email/điện thoại.
4. ✅ Giảm giá chỉ cần quyền riêng, không luồng duyệt.
5. ✅ Trả quá N ngày: chỉ người có quyền riêng (quản lý) cho ngoại lệ.
6. ✅ Trả hàng hỏng: nhân viên chọn nhập lại tồn hoặc ghi giảm/hao hụt.
7. ✅ Dữ liệu khách vãng lai: lưu tối thiểu, che khi hiển thị, giữ theo cấu hình tenant, mặc định 12 tháng rồi ẩn danh (xóa tên, điện thoại; giữ đơn).
8. ✅ Mặc định: trả hàng 7 ngày, ngưỡng tồn 5, VAT 5% giá đã gồm thuế, cấu hình được.
10. ✅ Tenant có ít nhất 1 module; tắt module có hiệu lực trễ tối đa 15 phút.
11. ✅ Phí ship không chịu VAT.
9. ✅ Giảm giá hỗ trợ cả số tiền và phần trăm.

Còn mở:
- ⚠️ Quyền xóa dữ liệu cá nhân theo yêu cầu (trước hạn 12 tháng) theo luật, nếu tenant cần tuân thủ (cho sa/pháp lý). Thời hạn mặc định và "giá đã gồm thuế" đã đóng.
- ⚠️ Chống lạm dụng đăng ký tự do khi chưa xác thực (cho sa: giới hạn tần suất).

Cho sa (xem mục 9): vị trí lưu và cách tra module, điểm chặn chung `MODULE_NOT_ENABLED`, chuẩn tiền/VAT/làm tròn, kiểm soát đồng thời, bảo đảm audit không dựa Kafka best-effort, service/DB mới hay mở rộng service sẵn có, seed role/khóa quyền, mô hình quyền cho khách.
