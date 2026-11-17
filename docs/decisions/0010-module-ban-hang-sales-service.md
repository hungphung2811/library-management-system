# ADR-0010: Module bán hàng (SALES): `sales-service` riêng, tiền/VAT, tồn kho đồng thời, audit không dựa Kafka

- Trạng thái: đề xuất, các câu mở S1 đến S6 đã được người dùng chốt ngày 2026-11-16 (chưa có code)
- Liên quan: ADR-0001 (Outbox), ADR-0002 (gộp service), ADR-0003 (phân quyền động), ADR-0004/0007 (migration tập trung, system-service), ADR-0005 (BFF), ADR-0006 (multi-tenant), ADR-0009 (module theo tenant)
- Đề bài: `docs/requirements/2026-11-16-hieu-sach-module-ban-hang.md` (rủi ro 3 đến 9 ở mục 9)
- Thiết kế chi tiết, hợp đồng API, kế hoạch pha và test: `docs/architecture/sales-module-design.md`

## 1. Bối cảnh và mục tiêu

Quyết định nghiệp vụ người dùng đã chốt (truyền qua điều phối viên, đề bài đang được cập nhật):

- Đơn online **giữ tồn ngay khi đặt**, hoàn khi hủy.
- Tài khoản khách: nhân viên tạo hộ **hoặc** khách tự đăng ký; chưa xác thực email/điện thoại.
- Giảm giá: chỉ cần quyền riêng (không luồng duyệt); nhập theo **số tiền hoặc phần trăm**.
- Trả hàng hỏng: nhân viên chọn **nhập lại tồn** hoặc **ghi hao hụt**.

Các câu mở đã chốt tiếp (2026-11-16, chi tiết ở mục 5): đơn chuyển khoản quá hạn không tự hủy; **có** ngoại lệ trả hàng quá N ngày bằng khóa quyền riêng, bắt buộc lý do và audit; phí ship không chịu VAT; dữ liệu cá nhân khách vãng lai ẩn danh sau thời hạn cấu hình theo tenant (mặc định 12 tháng); mặc định tenant mới VAT 5% giá đã gồm thuế, N = 7, ngưỡng tồn 5, phí ship 0; tự đăng ký bật bằng cờ `signup_default` trên role.

Hiện trạng đã đối chiếu trong code:

- catalog: `Title` có `coverPrice` kiểu `BigDecimal` (`NUMERIC(12,2)`), dùng tính phí phạt; `BookCopy` là bản sao cho mượn. Không có endpoint tra nhiều đầu sách một lần. `TitleController` ở `/internal/books`.
- circulation: `Fine.amount` kiểu `BigDecimal`. Chưa có chuẩn tiền chung.
- Gọi chéo service hiện dùng **token người dùng chuyển tiếp** (`CatalogCopyRestClientAdapter` của circulation dùng `BearerTokens.current()`), không có credential service-to-service.
- Audit tập trung ở support chỉ nhận qua Kafka (`AuditEventConsumer` nghe `auth.events`, `access-control.events`, `borrowing.events`, `identity-events`); support không có API ghi đồng bộ.
- `credentials.username` duy nhất toàn hệ thống; `CreateUserUseCase` báo trùng chung chung.
- Vai trò DB, init SQL, afterMigrate, target migration, compose, lms-api client: đã có mẫu đầy đủ từ system/catalog (ADR-0006, ADR-0007).
- Cổng 8081 đến 8086 đã dùng; 8087 chưa ai dùng (đã grep cấu hình và tài liệu).

## 2. Quyết định

### 2.1. Service mới `sales-service`, không mở rộng catalog/circulation

| Tiêu chí | **A. `sales-service` + `sales_db` (chọn)** | B. Thêm context vào catalog-service | C. Thêm vào circulation-service | D. Tồn kho + giá ở catalog, đơn ở service mới |
|---|---|---|---|---|
| Bất biến "trừ tồn + ghi đơn + ghi tiền + sổ cái trong một giao dịch" | Có: tất cả trong `sales_db` | Có | Có | **Không**: hai DB, cần saga; vi phạm rủi ro 5 |
| Ranh giới bounded context | Rõ: bán hàng là context riêng | catalog (danh mục, lõi dùng chung) trộn nghiệp vụ tiền | Mượn trả và bán hàng là hai nghiệp vụ khác | Tách đúng nhưng sai chỗ cắt |
| Bật/tắt theo module, mở rộng riêng | Chặn cả service bằng một annotation lớp; scale riêng giờ cao điểm bán | Lẫn với endpoint lõi | Lẫn với LENDING | |
| Chi phí hạ tầng | Một lần: init SQL, target migration, compose, Dockerfile, pom, lms-api client (đều có mẫu) | Thấp | Thấp | Cao nhất |
| Rủi ro tới lending | Không chạm DB/tiến trình lending | Lỗi sales có thể làm catalog (lending dùng) chậm/chết (ADR-0002 đã nêu) | Như B | |

Chọn **A**. ADR-0002 gộp service để tránh chi phí cho phạm vi nhỏ; ở đây sales là một module thuê riêng, có dữ liệu tiền, cần cách ly lỗi và tắt được nguyên khối, nên lợi ích vượt chi phí một lần.

Thông số:

| Mục | Giá trị |
|---|---|
| Module Maven | `services/sales-service`, artifactId `sales-service`, package gốc `lms.sales` |
| Cổng | **8087** (`expose`, không publish ra host, như các service khác) |
| DB | `sales_db`; vai trò `sales` (chủ DB, chỉ system-service dùng) và `sales_app` (NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE) trong `infrastructure/postgres/init-databases.sql` |
| Migration | target `sales` trong system-service: `db/migration/sales/V1__...sql` + `afterMigrate.sql` (mẫu `catalog/afterMigrate.sql`); `migration.targets.sales` khai báo cuối danh sách; compose thêm `SALES_DB_*` cho system-service |
| Context trong service | `stock` (mặt hàng bán, sổ cái, nhà cung cấp, phiếu nhập, điều chỉnh), `ordering` (đơn, thanh toán, hoàn tiền, phiếu trả), `settings` (cấu hình bán hàng theo tenant). `ordering.application` gọi `stock.application`/`settings.application` trong cùng transaction (cùng DB, cùng transaction manager); ArchUnit như các service khác |
| Kafka | Chỉ phát (best-effort) topic `sales.events`; **không** nghe topic nào |
| lms-api | `SalesClient` + `HttpSalesClient`, `ServicesProperties.salesUrl` (`SALES_SERVICE_URL`), controller `/api/sales/**` (nhân viên) và `/api/shop/**` (khách) |
| nginx | Không đổi cho `/api/**`; thêm `limit_req` cho `/api/auth/signup` (mục 2.3) |
| Security | `/internal/**` authenticated, còn lại denyAll; `require-tenant: true`; mọi controller `@RequiresModule(SALES)` (ADR-0009) |

### 2.2. Ranh giới với catalog, circulation, identity

| Dữ liệu | Chủ sở hữu | Ghi chú |
|---|---|---|
| Đầu sách (tên, tác giả, ISBN, `coverPrice`) | catalog | Không đổi; `coverPrice` vẫn chỉ catalog sửa, vẫn dùng tính phí phạt |
| `sellingPrice`, tồn kho bán, sổ cái, ngưỡng tồn | sales | `sellingPrice` NULL = dùng `coverPrice` lúc bán |
| Bản sao cho mượn | catalog (`inventory`) | Hai kho độc lập về cấu trúc (khác DB), không có bộ đếm chung |
| Khách có tài khoản | identity (user + membership + role của tenant) | sales chỉ lưu `userId` |

- **sales → catalog**: gọi đồng bộ, token người dùng chuyển tiếp (đúng mẫu circulation). catalog thêm **một** endpoint tra nhiều đầu sách theo danh sách id (ví dụ `TitleController/lookup`, lõi, không `@RequiresModule`; tối đa ~100 id). Gọi **trước** khi mở transaction để không giữ connection DB khi chờ HTTP. Đầu sách không tồn tại/không thấy → `409 TITLE_NOT_AVAILABLE`. catalog lỗi/chết → `503`, không bán (không có bản sao cục bộ của đầu sách để tránh lệch).
- **Snapshot trên dòng đơn**: tên đầu sách, ISBN, đơn giá tại thời điểm bán. Đơn lịch sử không phụ thuộc catalog nữa; đầu sách bị sửa/xóa sau đó không ảnh hưởng phiếu.
- **Danh mục bán**: một đầu sách chỉ bán được sau khi được "đưa vào bán" (`StockItemController/register`: kiểm đầu sách tồn tại qua catalog, tạo mặt hàng tồn 0). Tránh tạo mặt hàng ngầm khi nhập kho (tranh chấp tạo đồng thời) và cho danh sách hàng bán rõ ràng.
- **circulation**: không tương tác. Mượn không đổi tồn bán; bán không đổi bản sao.
- **identity**: xem 2.3.

Phương án đã loại cho giá/tên đầu sách: bản sao cục bộ trong sales cập nhật bằng event của catalog (Kafka best-effort, chưa có event `TitleUpdated`; lệch giá khi bán là lỗi tiền); BFF ghép dữ liệu catalog (giá phải tính ở BE, trong use case, không ở BFF).

### 2.3. Khách hàng, đăng ký, vai trò mẫu, seed khóa quyền

**Mô hình quyền của khách: khóa động + kiểm sở hữu ở tầng application** (không dùng `@AuthenticatedOnly`).

| Tiêu chí | **Khóa động gán cho role khách (chọn)** | `@AuthenticatedOnly` + kiểm sở hữu |
|---|---|---|
| Mặc định từ chối (ADR-0003) | Giữ | Mọi user của tenant (kể cả nhân viên thư viện của tenant bật cả hai module) đặt được đơn |
| Tenant tắt bán online mà vẫn giữ POS | Thu hồi khóa của role khách | Không làm được nếu không thêm cờ |
| `TENANT_REQUIRED` | Áp dụng (nhánh PERMISSION) | Phải dựa vào nhánh mới của ADR-0009 |
| Chi phí | Cần seed khóa cho role khách, người tự đăng ký phải tự nhận role | Không cần seed |

Kiểm sở hữu: use case khách luôn lấy `userId` từ token (`CurrentUser`), không nhận `userId` từ body/query; xem đơn của người khác trả `404 ORDER_NOT_FOUND` (không lộ đơn tồn tại).

**Tài khoản khách**:
- Nhân viên tạo hộ: dùng luồng sẵn có (`UserController/create` + gán role), không thêm code.
- Tự đăng ký: endpoint công khai mới ở identity `POST /internal/auth/signup` (`@PublicEndpoint`, mở trong `SecurityConfiguration` của identity như `/internal/auth/login`), lms-api `POST /api/auth/signup`, nginx `limit_req` riêng (vùng như `login`). Body: `tenantCode`, `username`, `password`, `fullName`, `email`, tùy chọn `phone`. Use case (application, không ở controller):
  1. Tenant tồn tại, `ACTIVE`, **có bật SALES** (đọc `tenant_modules`; endpoint công khai không có token nên interceptor không kiểm được): nếu không → `403 SIGNUP_NOT_AVAILABLE` (một mã chung cho "không có tenant/không bật SALES/không có role đăng ký" để không dò được tenant).
  2. Tenant phải có **đúng một role đánh dấu `signup_default`** (cờ mới trên `roles`/`role_templates`, chỉ mục duy nhất từng phần theo tenant). Không có → `SIGNUP_NOT_AVAILABLE`. Role `is_admin` không được đánh dấu `signup_default` (luật domain, kiểm cả khi tenant admin sửa role). Không hardcode tên role.
  3. Trong một transaction identity: tạo user + credential (không `mustChangePassword`) + membership + gán role `signup_default`, chạy trong `TenantContext.callAs(tenant)`.
  4. Trả `201` (không tự đăng nhập; FE gọi login). Trùng tên đăng nhập/email → lỗi trùng chung như `CreateUserUseCase`.
- Chống lạm dụng (chưa xác thực là quyết định của người dùng): giới hạn tốc độ ở nginx, mật khẩu tối thiểu như hiện có, role đăng ký không bao giờ là admin, tenant admin gỡ cờ `signup_default` là tắt tự đăng ký. Chấp nhận rủi ro tài khoản rác (ghi ⚠️ ở mục 4).

**Vai trò mẫu và seed khóa chưa đăng ký** (rủi ro 7):
- Thêm `role_templates` có `module = 'SALES'`: Thu ngân, Thủ kho, Quản lý cửa hàng, Khách hàng (`signup_default = true`), kèm `role_template_permissions` là các khóa `Controller/method` của sales (và `TitleController/lookup` của catalog cho các role cần tra đầu sách khi bán; ⚠️ chỉ cần nếu sales gọi catalog bằng token người dùng như quyết định 2.2). Tên hiển thị là dữ liệu; tenant admin sửa được.
- Vì `role_permissions.permission_code` có FK tới `permissions(code)`, migration seed phải **chèn trước các khóa vào `permissions`** (`source='AUTO'`, `service='sales-service'`, `http_method`, `path_pattern`, `ON CONFLICT (code) DO NOTHING`), đúng mẫu V9. Khi endpoint được gọi lần đầu, đăng ký `ON CONFLICT` giữ nguyên dòng đã có.
- Bật SALES cho tenant (ADR-0009) chép các mẫu này thành role của tenant trong cùng transaction.
- Chống lệch giữa seed và code: **chốt tên controller/method trước** khi viết migration seed; test ở sales-service tính tập khóa thật bằng `PermissionKeyResolver` trên mọi handler và so với tập khóa trong file seed (đọc từ jar system-service ở test scope). Đổi tên controller mà quên migration thì test đỏ.
- Tenant mặc định (chỉ LENDING) không nhận role sales cho tới khi bật SALES.

Phương án đã loại cho seed: gán quyền bằng tên role hardcode (trái ADR-0003); quét endpoint lúc khởi động để đăng ký khóa (ADR-0003 đã nêu là chưa làm; khởi động sales phải chờ identity, và vẫn phải có bước gán khóa cho role mẫu); chỉ dựa vào tenant admin tự gán sau lần gọi đầu (tenant mới không dùng được ngay, trái yêu cầu seed).

### 2.4. Tiền và thuế

- **Kiểu số**: số nguyên đồng, `long` trong Java, `BIGINT` trong DB. VND có số mũ 0 (ISO 4217); không có phần lẻ cần lưu. Phép tính trung gian (VAT, phần trăm) làm bằng số nguyên với làm tròn tường minh, không dùng `double`.
- **Value object** `Money(long amount)` + `currency` cố định `VND` đặt trong **sales-service** (gói dùng chung nội bộ, ví dụ `lms.sales.shared.domain`, Java thuần). Không đưa vào `common-kernel` lúc này: chưa có nơi thứ hai dùng (`Fine`/`coverPrice` vẫn `BigDecimal`, chuyển đổi chúng ngoài phạm vi). Khi có nơi thứ hai thì chuyển lên `common-kernel`.
- **Chuyển `coverPrice` (BigDecimal) sang đồng**: `setScale(0, HALF_UP)` khi lấy làm giá bán mặc định.
- **Tỷ lệ**: thuế suất và giảm giá phần trăm lưu bằng **basis point** (`int`, 500 = 5%), tránh số thập phân.
- **Làm tròn**: `HALF_UP` về đồng, **theo từng dòng**; tổng đơn là tổng các dòng (không tính lại ở cấp đơn), nên tổng luôn khớp phiếu và phiếu trả hoàn đúng theo dòng.
- **Thứ tự tính một dòng**:
  1. `gross = unitPrice × qty`
  2. Giảm giá dòng: số tiền, hoặc `round(gross × bps / 10000)`; không vượt `gross`.
  3. Giảm giá đơn (số tiền hoặc %) **phân bổ** về các dòng theo tỷ lệ giá trị sau giảm dòng, phần dư do làm tròn dồn theo phương pháp phần dư lớn nhất; tổng phân bổ bằng đúng giảm giá đơn, không dòng nào âm.
  4. `amount` = sau giảm giá.
  5. Giá **đã gồm thuế**: `vat = round(amount × r / (10000 + r))`, `total = amount`. Giá **chưa gồm thuế**: `vat = round(amount × r / 10000)`, `total = amount + vat`.
- **Phí ship**: cộng vào tổng đơn, **không chịu VAT** (đã chốt S3); phiếu in ghi phí ship tách khỏi phần chịu thuế.
- **Snapshot trên đơn**: thuế suất, cờ giá-đã-gồm-thuế, currency, đơn giá, giảm giá, VAT từng dòng, phí ship. Đổi cấu hình chỉ ảnh hưởng đơn mới. Đơn đã thu tiền không tính lại.
- Bất biến kiểm ở domain: không số tiền nào âm; giảm giá không vượt giá trị; tổng đơn ≥ 0; `Σ total dòng + phí ship = tổng đơn`.

Phương án đã loại:

| Phương án | Lý do loại |
|---|---|
| `BigDecimal` + `NUMERIC(14,2)` | Lưu phần lẻ không tồn tại với VND; dễ lẫn `scale`, `equals` khác `compareTo`; nhiều chỗ phải nhớ làm tròn |
| VAT tính một lần ở cấp đơn rồi phân bổ về dòng | Thêm bước phân bổ VAT; trả hàng một phần phải tính ngược. Chênh lệch giữa hai cách ≤ 0,5 đồng × số dòng, không đáng kể với VND |
| Lưu số chưa làm tròn, làm tròn khi hiển thị | Phiếu in và số liệu lưu không khớp; trả hàng hoàn sai lẻ |
| `Money` trong `common-kernel` ngay | Chưa có nơi thứ hai dùng; thêm thay đổi vào thư viện mọi module phụ thuộc |

### 2.5. Đồng thời và nhất quán

**Tồn kho hai bộ đếm** trên mỗi mặt hàng: `on_hand` (hàng thực có trong cửa hàng) và `reserved` (đã giữ cho đơn online chưa giao); `available = on_hand − reserved`. Ràng buộc DB `CHECK (on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand)` là lớp chặn cuối.

- Lý do hai bộ đếm (thay vì trừ thẳng một số khi đặt online): kiểm kho đếm hàng thực trên kệ, gồm cả sách đang để riêng cho đơn chưa giao; so với `on_hand` mới đúng. Một bộ đếm sẽ làm kiểm kho cộng nhầm.
- **Giữ/trừ bằng cập nhật có điều kiện**, một câu lệnh mỗi dòng:
  - Giữ (đặt online): `reserved += q` với điều kiện `on_hand − reserved >= q`.
  - Bán POS: `on_hand −= q` với điều kiện `on_hand − reserved >= q`.
  - Số dòng bị ảnh hưởng = 0 → `409 INSUFFICIENT_STOCK` (kèm `titleId`), **rollback cả đơn** (không tạo đơn một phần).
  - Gộp dòng trùng `titleId` trước; cập nhật theo thứ tự `titleId` tăng dần để hai đơn nhiều dòng không khóa chéo (deadlock).
  - Dùng JPQL bulk update (`@Modifying`), có điều kiện `tenant_id` tường minh bên cạnh RLS; **không** native query (giữ nguyên danh sách native đã rà). ⚠️ Hành vi `@TenantId` với JPQL bulk update chưa kiểm chứng; RLS vẫn chặn ở DB.
- **Sổ cái** `stock_ledger` chỉ chèn thêm (append-only), ghi trong **cùng transaction** với mọi thay đổi bộ đếm: loại `RECEIPT`, `SALE`, `RESERVE`, `RELEASE`, `FULFIL` (giữ → đã giao), `RETURN`, `WRITE_OFF`, `ADJUSTMENT`, `STOCKTAKE`, `CANCEL_RESTOCK`; mỗi dòng có `delta_on_hand`, `delta_reserved`, tham chiếu chứng từ, người làm, lý do. Bất biến: `on_hand = Σ delta_on_hand`, `reserved = Σ delta_reserved` theo mặt hàng; có truy vấn đối soát dùng trong IT.
- **Đơn và tiền**: aggregate `SalesOrder` có `@Version` (khóa lạc quan) + máy trạng thái ở domain. Hai người đánh dấu đã thu cùng lúc: một thành công, người kia `409 ORDER_STATE_CONFLICT` (xung đột phiên bản hoặc trạng thái không cho phép). Hủy và đánh dấu thu cùng lúc: tương tự.
- **Trả hàng đồng thời trên cùng đơn**: tạo phiếu trả khóa dòng đơn bằng `PESSIMISTIC_WRITE` (khóa JPA, không native) rồi mới tính "đã mua − đã trả"; hai phiếu trả song song được tuần tự hóa, không trả vượt.
- **Chống gửi trùng** (bấm hai lần, client thử lại khi hết thời gian chờ): tạo đơn POS/online, phiếu nhập, phiếu trả nhận `clientRequestId` (UUID do FE sinh), `UNIQUE (tenant_id, client_request_id)`; gửi lại trả về chứng từ đã tạo, không trừ tồn lần hai.
- **Không Kafka trong đường ghi**: trừ tồn, sổ cái, đơn, thanh toán, hoàn tiền, nhật ký audit đều nằm trong **một transaction của `sales_db`**. Kafka chỉ phát sau commit để thông báo/audit tập trung.

Phương án đã loại:

| Phương án | Lý do loại |
|---|---|
| Khóa lạc quan `@Version` trên mặt hàng tồn | Đầu sách bán chạy giờ cao điểm: xung đột liên tục, phải thử lại ở application hoặc báo lỗi giả cho người dùng dù còn hàng |
| `SELECT ... FOR UPDATE` rồi kiểm ở Java | Đúng nhưng hai lượt gọi DB và giữ khóa lâu hơn cho cùng kết quả; giữ làm phương án dự phòng nếu bulk update vướng `@TenantId` |
| Trừ tồn qua event Kafka/saga | Mất event là sai tồn hoặc sai tiền; chưa có Outbox (ADR-0001) |
| Một bộ đếm `available` | Kiểm kho sai khi có đơn online đang giữ hàng (đã giải thích ở trên) |

### 2.6. Audit không mất

- Bảng `sales_audit_log` trong `sales_db`, chỉ chèn thêm, ghi **trong cùng transaction** với thao tác nghiệp vụ qua port `SalesAuditPort` (application gọi, adapter JPA). Mỗi bước ghi đều có bản ghi: tạo/xác nhận/hủy đơn, giảm giá, đánh dấu thu (người, thời điểm, số tiền, phương thức, tham chiếu), chuyển trạng thái giao/nhận, nhập kho, điều chỉnh, kiểm kho, đưa vào bán, đổi giá, đổi cấu hình, phiếu trả, trả hàng quá hạn (kèm lý do), hoàn tiền, ẩn danh dữ liệu khách vãng lai (mục 2.8). Rollback thì cả nghiệp vụ lẫn audit cùng mất (đúng).
- Nội dung audit chỉ chứa id, số tiền, số lượng, mã trạng thái; **không** chứa tên/điện thoại/địa chỉ khách (dữ liệu cá nhân, đề bài 5.9).
- Sau commit: phát bản tóm tắt lên `sales.events` (best-effort) để support đưa vào audit tập trung (support thêm topic vào `AuditEventConsumer`). Bảng có cột `published_at` (NULL = chưa phát được) để relay Outbox sau này (ADR-0001) đọc lại, không phải đổi schema.
- Đọc audit bán hàng đầy đủ: nguồn chuẩn là `sales_audit_log` (endpoint đọc riêng, khóa riêng, pha 2); màn hình audit chung ở support có thể thiếu bản ghi khi Kafka lỗi.

Phương án đã loại: chỉ audit qua Kafka (mất khi Kafka lỗi, trái rủi ro 5); gọi REST đồng bộ sang support (không atomic xuyên DB: ghi xong đơn mà gọi lỗi thì vẫn mất, và sales phụ thuộc support); làm Outbox đầy đủ ngay (relay, idempotent consumer, DLQ là việc nền tảng của ADR-0001, vượt phạm vi; bảng có `published_at` để nối sau).

### 2.7. Luồng đơn

Chi tiết trạng thái, hợp đồng, mã lỗi ở `docs/architecture/sales-module-design.md`. Tóm tắt quyết định:

- **POS một bước**: `PosOrderController/create` (không giảm giá) và `PosOrderController/createWithDiscount` (có giảm giá; khóa riêng) nhận dòng, phương thức thanh toán (tiền mặt/chuyển khoản/thẻ), khách vãng lai hoặc `userId`; trong một transaction: trừ `on_hand`, ghi sổ cái, đơn `COMPLETED`, thanh toán `PAID` (người thu = người tạo), audit. Thu ngân chỉ gửi khi đã nhận tiền/quẹt thẻ xong ngoài hệ thống. Hai endpoint thay vì một cờ vì ADR-0003 chỉ cho kiểm quyền theo endpoint, không được kiểm khóa trong code.
- **Online**: khách đặt → `PENDING_CONFIRMATION` (giữ tồn: `reserved += q`) → nhân viên xác nhận (đặt phí ship nhập tay hoặc phí cố định của tenant) → `CONFIRMED` → `READY_FOR_PICKUP` (nhận tại quầy) hoặc `SHIPPING` (giao tự quản; lúc này hàng rời cửa hàng: `FULFIL` = `on_hand −= q, reserved −= q`) → `COMPLETED` (nhận tại quầy: `FULFIL` lúc này). Thanh toán là trục riêng: `UNPAID → PAID` do nhân viên có khóa đánh dấu (chuyển khoản: sau đối soát; COD: sau khi nhận tiền); `COMPLETED` yêu cầu `PAID`.
- **Hủy**: khách hủy được khi còn `PENDING_CONFIRMATION`; nhân viên (khóa riêng) hủy được trước `COMPLETED`. Đơn chuyển khoản quá hạn chưa thu **không tự hủy** (đã chốt S1): không có job hủy tự động; nhân viên lọc đơn `UNPAID` theo tuổi đơn (`GET /orders` có bộ lọc thời gian) và hủy tay. Hủy khi còn giữ → `RELEASE`; hủy khi đang `SHIPPING` (giao thất bại) → `CANCEL_RESTOCK` (`on_hand += q`). Đơn đã `PAID` bị hủy sinh **khoản hoàn** trạng thái chờ.
- **Hoàn tiền** là bản ghi riêng (nguồn: hủy đơn hoặc phiếu trả), `PENDING → REFUNDED` do nhân viên có khóa hoàn tiền đánh dấu (phương thức gốc hoặc tiền mặt). Đơn chưa thu tiền thì không có hoàn tiền.
- **Trả hàng quá hạn (đã chốt S2: có ngoại lệ)**: endpoint riêng `SalesReturnController/createLate` (khóa riêng, mặc định chỉ gán cho mẫu Quản lý cửa hàng), cùng quy tắc với trả thường **trừ** kiểm N ngày; bắt buộc `lateReason` không rỗng (thiếu → `400 LATE_REASON_REQUIRED`). Phiếu trả lưu `late = true`, `late_reason`, `approved_by` (= người gọi); audit `RETURN_LATE_APPROVED` gồm người, thời điểm, số ngày quá hạn, lý do. Endpoint thường `SalesReturnController/create` vẫn trả `409 RETURN_WINDOW_EXPIRED` khi quá N ngày (FE gợi ý người có quyền dùng luồng quá hạn). Hai endpoint thay vì một cờ trong body vì ADR-0003 chỉ cho kiểm quyền theo endpoint. Đã loại: luồng duyệt hai bước (người tạo + người duyệt), vượt nhu cầu; quản lý tự trả hàng bằng khóa riêng là đủ.
- **Trả hàng**: chỉ đơn `COMPLETED` và `PAID`, trong N ngày tính từ `completedAt` (trừ luồng quá hạn ở trên); theo dòng, số lượng không vượt "đã mua − đã trả"; mỗi dòng nhân viên chọn `RESTOCK` (`RETURN +q`) hoặc `WRITE_OFF` (`RETURN +q` rồi `WRITE_OFF −q`, tồn không đổi nhưng sổ cái thấy rõ hao hụt); số tiền hoàn = tỷ lệ của `total` dòng, phần dư làm tròn dồn vào lần trả cuối của dòng để tổng hoàn không vượt `total`. **Đổi hàng** = phiếu trả + đơn POS mới có `exchangeOfReturnId`.
- **Tắt SALES khi đơn dở**: dữ liệu và tồn đang giữ giữ nguyên; không thao tác được tới khi bật lại (ADR-0009). Tồn đang giữ vẫn bị "khóa" trong thời gian đó (ℹ️).

### 2.8. Lưu giữ dữ liệu cá nhân khách vãng lai (đã chốt S4)

- Cấu hình theo tenant `walkInPiiRetentionMonths` trong `SalesSettings`, mặc định **12**, giới hạn 1 đến 120 tháng.
- Hết hạn thì **ẩn danh**: đặt `walk_in_name`, `walk_in_phone` về NULL và ghi `pii_anonymized_at`. Đơn, dòng, tiền, sổ cái, audit giữ nguyên để đối soát. Mốc tính: `completed_at` (hoặc `cancelled_at`); đơn chưa kết thúc không bị ẩn danh. Phạm vi chỉ là tên và điện thoại khách vãng lai (đúng quyết định); địa chỉ/người nhận của đơn online thuộc khách có tài khoản, không nằm trong chính sách này (⚠️ mục 4).
- **Job ẩn danh** trong sales-service (`infrastructure`, `@Scheduled` hằng ngày, tắt được bằng cấu hình), gọi use case `AnonymizeWalkInContactsUseCase` theo **từng tenant** trong `TenantContext.callAs(tenant)`:
  - Danh sách tenant: bảng `sales_tenants` (`tenant_id`, `first_seen_at`) trong `sales_db`, **không RLS**, thêm vào `TENANT_EXEMPT_TABLES`; ghi kiểu "chèn nếu chưa có" trong cùng transaction tạo đơn đầu tiên của tenant (chỉ tenant từng có đơn mới có dữ liệu cá nhân). Nếu dùng `INSERT ... ON CONFLICT DO NOTHING` native thì lớp adapter đó vào danh sách native đã rà (bảng không có dữ liệu tenant nên an toàn).
  - Mỗi tenant: đọc `walkInPiiRetentionMonths`, cập nhật theo lô (ví dụ 500 dòng/transaction) các đơn có `walk_in_name` hoặc `walk_in_phone` khác NULL, `pii_anonymized_at` NULL, đã kết thúc trước mốc. Idempotent: chạy lại hoặc hai instance chạy chồng chỉ cập nhật 0 dòng, không cần khóa phân tán.
  - Audit: một bản ghi `sales_audit_log` mỗi lô (`WALK_IN_PII_ANONYMIZED`, số đơn, mốc thời gian; không có PII), actor là hệ thống. Lỗi một tenant chỉ log và chuyển tenant kế tiếp.
- Phương án đã loại: danh bạ tenant qua Kafka như `known_tenants` của circulation (mất event là tenant không bao giờ được ẩn danh); gọi identity lấy danh sách tenant (thêm API nội bộ, job phụ thuộc identity); xóa hẳn đơn (mất đối soát, trái quyết định).
- Làm ở **cuối pha 2** (sau khi có đơn với dữ liệu khách vãng lai ổn định), xem kế hoạch ở tài liệu thiết kế.

## 3. Thiết kế

Xem `docs/architecture/sales-module-design.md`: thành phần, mô hình dữ liệu khái niệm, máy trạng thái, hợp đồng API, mã lỗi, cấu hình triển khai.

```
FE ──/api/sales/**, /api/shop/**──> nginx(8080) ──> lms-api(8085) ──/internal/sales/**──> sales-service(8087) ──> sales_db (sales_app, RLS)
                                                                              └──/internal/books/lookup (token chuyển tiếp)──> catalog-service(8082)
sales-service ──sau commit, best-effort──> Kafka sales.events ──> support-service (audit tập trung)
system-service(8086) ──Flyway target `sales`──> sales_db
```

## 4. Rủi ro và việc chưa kiểm chứng

- ⚠️ JPQL bulk update với `@TenantId` (Hibernate 6.6) chưa kiểm chứng có tự thêm điều kiện tenant; thiết kế thêm `tenant_id` tường minh và RLS vẫn chặn, nhưng cần IT.
- ⚠️ Test đồng thời (hai giao dịch bán cùng đầu sách còn 1) phải chạy trên PostgreSQL thật (Testcontainers), bằng vai trò `sales_app`; H2/mock không chứng minh được.
- ⚠️ sales phụ thuộc đồng bộ vào catalog khi tạo đơn: catalog chết thì không bán được. Chấp nhận để giá luôn đúng; nếu thành vấn đề vận hành thì cân nhắc snapshot giá vào mặt hàng (ADR sau).
- ⚠️ Tự đăng ký chưa xác thực: có thể tạo tài khoản rác; tên đăng nhập duy nhất toàn hệ thống nên người đã có tài khoản ở tenant khác không tự đăng ký được ở hiệu sách (phải nhờ platform admin gắn, luồng `attachMember` sẵn có).
- ⚠️ Tập khóa seed phải khớp tên controller/method; có test đối chiếu nhưng chỉ chạy được sau khi chốt tên.
- ⚠️ Số migration (identity, system, sales) phải kiểm lại ngay trước khi tạo file.
- ⚠️ Chưa có số đo hiệu năng nền cho POS; mục tiêu ở tài liệu thiết kế là tham khảo.
- ℹ️ Phí ship không chịu VAT là quyết định đã chốt (S3); nếu sau này cần tính VAT phí vận chuyển thì đổi `PriceCalculator` và snapshot thêm trên đơn, đơn cũ không đổi.
- ⚠️ Thuế suất mặc định 5% (S5) chỉ là mặc định; thuế suất thật tùy loại sách, tenant tự chỉnh. Hệ thống chỉ có **một** thuế suất mỗi tenant, chưa có thuế suất theo đầu sách.
- ⚠️ Chính sách ẩn danh chỉ áp tên và điện thoại khách vãng lai; địa chỉ người nhận của đơn online không bị ẩn danh tự động. Cần người dùng xác nhận nếu muốn áp cả cho đơn online.
- ⚠️ Job ẩn danh chưa kiểm chứng trên dữ liệu lớn; cần IT với hai tenant có thời hạn khác nhau và kiểm RLS khi chạy trong `callAs`.
- ⚠️ Luồng trả quá hạn dựa hoàn toàn vào việc gán khóa `createLate` đúng người; tenant admin gán nhầm thì ai cũng trả quá hạn được (vẫn có audit).
- ℹ️ Nợ có sẵn, ngoài phạm vi: `borrow_rules` chưa nối `BorrowBookUseCase`; kiểm chứng còn nợ của ADR-0006 (IT tenant, giả định Hibernate) vẫn phải chạy bù; Outbox (ADR-0001) chưa có.

## 5. Câu hỏi đã chốt (người dùng quyết, 2026-11-16)

- ✅ S1 (đề bài Q2). Đơn chuyển khoản quá hạn chưa thu **không tự hủy**; nhân viên hủy tay (mục 2.7). Không có job hủy tự động.
- ✅ S2 (đề bài Q5). **Có** ngoại lệ trả hàng quá N ngày: endpoint/khóa riêng `SalesReturnController/createLate`, bắt buộc lý do, có audit (mục 2.7).
- ✅ S3. Phí ship **không chịu VAT** (mục 2.4).
- ✅ S4 (đề bài Q7). Dữ liệu cá nhân khách vãng lai lưu theo chính sách cấu hình theo tenant, mặc định 12 tháng rồi ẩn danh (chỉ xóa tên và điện thoại, đơn giữ để đối soát); job ẩn danh làm ở cuối pha 2 (mục 2.8).
- ✅ S5 (đề bài Q8). Mặc định tenant mới: VAT 5%, giá đã gồm thuế, N = 7 ngày, ngưỡng tồn thấp 5, phí ship cố định 0, lưu giữ dữ liệu khách vãng lai 12 tháng; **tất cả cấu hình được** qua `SalesSettingsController/update`. Thuế suất thật tùy loại sách, tenant tự chỉnh.
- ✅ S6. Tự đăng ký bật bằng cờ `signup_default` trên role (mục 2.3); mẫu "Khách hàng" mang cờ khi bật SALES; tenant admin gỡ cờ để tắt tự đăng ký.
