# Kịch bản demo hệ thống quản lý thư viện và hiệu sách (từ đầu đến cuối)

Thời lượng khoảng 25 phút. Dữ liệu: tenant `demo` (nạp bằng `scripts\seed-demo.ps1`). Mật khẩu chung `Demo@12345` (platform admin `Platform@12345`). Kịch bản này có bản chạy tự động: `scripts/demo-e2e.sh` (xem mục "Chạy tự động").

## 0. Chuẩn bị (5 phút trước buổi demo)

1. `docker compose up -d` (Postgres, Kafka, Redis, Mongo) và `docker compose up -d mailpit` (hộp thư giả `http://localhost:8025`).
2. `scripts\dev-start.ps1`, rồi chạy riêng sales (8087) và customer-api (8088) với `STOREFRONT_CLIENT_SECRET` giống nhau cho identity và customer-api (xem CLAUDE.md).
3. FE: `http://localhost:3000` (proxy tới lms-api 8085).
4. Mở sẵn 2 trình duyệt ẩn danh: một cho nhân viên, một cho khách.

## Danh sách tài khoản demo (chỉ dùng ở máy dev)

Đăng nhập nhân viên và độc giả ở `http://localhost:3000/login`. Khách mua online dùng cửa hàng qua customer-api (xem phần "Khách storefront").

### Nền tảng (không thuộc đơn vị nào)

| Tài khoản | Mật khẩu | Vai trò | Dùng cho bước |
|---|---|---|---|
| `platform.admin` | `Platform@12345` | Platform admin (`platformAdmin=true`) | Mục 1: dashboard nền tảng, chọn đơn vị để xem thay |

### Tenant `demo` (mật khẩu chung `Demo@12345`)

| Tài khoản | Vai trò | Quyền chính | Dùng cho bước |
|---|---|---|---|
| `demo.admin` | Admin đơn vị (`isAdmin`, bỏ qua kiểm khóa) | Toàn quyền trong đơn vị: dashboard, người dùng, vai trò, menu, chính sách chống lạm dụng, xuất CSV | Mục 2, xác nhận đơn online |
| `demo.thuthu` | Thủ thư (`LIBRARIAN`) | Sách, bản sao, mượn trả, đặt trước. Không xuất CSV | Mục 3 (xem lưu ý bên dưới) |
| `demo.cuahang` | Quản lý cửa hàng (`STORE_MANAGER`) | Bán hàng POS, tồn kho, đơn hàng, thu tiền | Mục 4 |
| `demo.docgia01` đến `demo.docgia24` | Độc giả (`MEMBER`) | Xem phiếu mượn, phạt, đặt trước của chính mình | Mục 3 (`demo.docgia01` có khoản phạt chưa thu 24.000đ) |

### Tenant mặc định (dev)

| Tài khoản | Mật khẩu | Vai trò |
|---|---|---|
| `admin` | `Admin@12345` | Admin của tenant mặc định `00000000-0000-0000-0000-0000000000d1` (không phải tenant demo; muốn vào demo dùng chuyển đơn vị) |

### Khách storefront (tự đăng ký)

Đăng nhập ở cửa hàng `demo` (customer-api): `POST http://localhost:8088/public/t/demo/auth/login`, hoặc trên trang storefront của FE. Mật khẩu của mọi tài khoản dưới đây là `Passw0rd!x1`. OTP xác minh email xem ở Mailpit `http://localhost:8025`.

| Tài khoản | Email | Trạng thái | Dùng để demo |
|---|---|---|---|
| `mailtest6790` | `mailtest6790@example.com` | Đã xác minh, chưa có đơn | **Nên dùng**: đăng nhập và đặt đơn ngay |
| `demo-e2e-20504-b`, `demo-e2e-30851-b`, `demo-e2e-4994-b`, `demo-e2e-6313-b` | `<tên>@example.com` | Đã xác minh, mỗi tài khoản có 1 đơn đã bị hủy do hết hạn giữ hàng | Đăng nhập và đặt đơn |
| `demo-e2e-153-a`, `demo-e2e-20504-a`, `demo-e2e-30851-a`, `demo-e2e-4994-a`, `demo-e2e-6313-a` | `<tên>@example.com` | Đã xác minh, đã đặt nhiều đơn dồn dập | ⚠️ Có thể đang chạm trần đơn chờ hoặc tốc độ, dùng để minh họa chống lạm dụng |
| `mailtest2756`, `mailtest20021` | `<tên>@example.com` | **Chưa xác minh email** | Minh họa `403 EMAIL_NOT_VERIFIED` và nhập OTP (xem thư ở Mailpit; cần bấm gửi lại nếu OTP đã hết hạn 10 phút) |

Muốn tài khoản mới: tự đăng ký ở bước 5 (tên và email tùy ý, mật khẩu đạt chính sách) hoặc chạy `bash scripts/demo-e2e.sh` (tạo thêm `demo-e2e-<số>-a` và `-b`, đã xác minh).

⚠️ **Lưu ý quyền của `demo.thuthu`:** hiện vai trò này chưa được gán đủ khóa nên khi demo có thể gặp `403/502`:
- Lập phiếu mượn: thiếu khóa tra bản sao của catalog (`BookCopyController/get`).
- Xem và thu phạt: thiếu `FineController/byReader`.
- Trả sách theo bản sao: bị `403`.

Cách xử lý khi demo: dùng `demo.admin` cho các thao tác này, hoặc gán thêm khóa cho role `LIBRARIAN` ở màn Vai trò trước buổi demo.

ℹ️ Các tài khoản khác trong `identity_db` (`admin.bachkhoa`, `xt-*`, `pgnew01`...) do các lần test cũ tạo ra, chưa rõ mật khẩu, không dùng cho demo.

## 1. Mở đầu: nền tảng SaaS cho thuê (platform admin, 3 phút)

- Đăng nhập `platform.admin`. Trang Tổng quan hiện dashboard nền tảng: số đơn vị, trạng thái, module đang dùng, hợp đồng sắp hết hạn.
- Ý chính: một nền tảng, nhiều đơn vị thuê (multi-tenant), dữ liệu cách ly bằng Row-Level Security.
- Chọn đơn vị `demo` ở thanh trên: platform admin xem và thao tác thay đơn vị (có ghi log kiểm toán).

## 2. Quản trị đơn vị (admin tenant, 4 phút)

- Đăng nhập `demo.admin`. Dashboard: thẻ KPI, biểu đồ mượn trả, doanh thu, tình trạng bản sao, top sách.
- Menu động và phân quyền động theo khóa `Controller/method`: mở Quản trị, Vai trò và quyền.
- Mở "Chính sách chống lạm dụng đặt hàng" (API `order-abuse-policy`): hạn giữ hàng 15 phút, trần đơn chờ, tốc độ đặt.
- Xuất báo cáo CSV (đơn hàng, tồn kho, phiếu quá hạn).

## 3. Thư viện: mượn trả (thủ thư, 5 phút)

- Đăng nhập `demo.thuthu`. Tìm đầu sách, chọn bản sao sẵn có.
- Lập phiếu mượn cho độc giả `demo.docgia01`.
- Độc giả xem phiếu của mình (`/api/borrowings/mine`), thủ thư nhận trả sách, kiểm tra trạng thái bản sao quay về sẵn có.

## 4. Hiệu sách: bán tại quầy (thu ngân, 3 phút)

- Đăng nhập `demo.cuahang`. Tạo đơn POS tiền mặt cho một mặt hàng.
- Kiểm tra tồn kho giảm đúng, và sổ cái tồn kho ghi biến động.

## 5. Khách mua online (storefront, 7 phút)

1. Khách mở cửa hàng `demo` (customer-api): xem danh mục sách đang bán.
2. Đăng ký tài khoản mới. Hệ thống gửi OTP 6 chữ số qua email (xem trong Mailpit).
3. Đăng nhập trước khi xác minh: bị `403 EMAIL_NOT_VERIFIED`. Nhập OTP sai: bị từ chối; nhập đúng: xác minh xong, đăng nhập được.
4. Đặt đơn: tồn kho được giữ ngay (reserved) và đơn có hạn giữ hàng.
5. Chống lạm dụng (điểm nhấn):
   - Gửi lại cùng mã yêu cầu cùng nội dung: trả đơn cũ, không giữ hàng hai lần.
   - Cùng mã yêu cầu nhưng đổi số lượng: `422 IDEMPOTENCY_KEY_REUSED`.
   - Đặt số lượng vượt trần: `400 INVALID_QUANTITY`.
   - Đặt dồn dập: `429 ORDER_RATE_LIMITED` hoặc `ORDER_LIMIT_EXCEEDED`.
6. Nhân viên xác nhận một đơn (hết hạn giữ hàng), một đơn khác để quá hạn: job tự hủy và nhả tồn.

## 6. Phiên đăng nhập an toàn (2 phút)

- Access token 15 phút, refresh token xoay vòng mỗi lần dùng. Dùng lại token cũ bị từ chối (chống đánh cắp phiên).

## 7. Kết (1 phút)

Tóm tắt: multi-tenant, phân quyền động, dashboard, chống lạm dụng đặt hàng, xác minh email.

## Hỏi đáp chuẩn bị sẵn

Mỗi câu có "trả lời ngắn" để nói ngay và "chi tiết" khi bị hỏi sâu. Chỗ nào chưa kiểm chứng trên bản demo thì ghi rõ ⚠️ để không hứa quá.

### 1. Sách nhiều tập, nhiều phần, nhiều bản dịch

**Trả lời ngắn:** Hệ thống thiết kế mô hình 3 tầng: *tác phẩm* (nội dung), *ấn bản* (một lần xuất bản cụ thể) và *bản sao* (cuốn sách vật lý), kèm *bộ sách* để gom các tập.

**Chi tiết:**
- Ví dụ Harry Potter: một bộ sách có 7 tác phẩm (tập 1 đến 7). Mỗi tác phẩm có nhiều ấn bản (bìa mềm, bìa cứng, ebook, bản dịch, tái bản) và mỗi ấn bản có nhiều bản sao có mã vạch riêng.
- Tập chia nhiều phần (phần 1/2) và bộ hộp: ấn bản có số phần (`partNumber`, `partLabel`); tác phẩm có loại `SINGLE` hoặc `COLLECTION`.
- Gộp tác phẩm trùng bằng thao tác merge (tối đa 20 nguồn mỗi lần).
- Đặt trước tính theo **tác phẩm**, giới hạn cùng ngôn ngữ: khi hết bản sao của mọi ấn bản cùng tác phẩm và ngôn ngữ thì mới đặt trước được; mỗi độc giả chỉ một đặt trước còn hiệu lực trên một tác phẩm.
- Mượn vẫn theo **bản sao**, nên biết chính xác cuốn nào đang ở tay ai.
- ⚠️ Tài liệu: `docs/decisions/0026-mo-hinh-tac-pham-an-ban-ban-sao-frbr.md`, `docs/api/catalog-works-series.md`, `docs/api/circulation-reservations-work.md`. ADR ghi trạng thái "đề xuất" và tính năng đang được một phiên khác hoàn thiện (test gom tác phẩm ở storefront còn lỗi), nên chưa nên demo trực tiếp phần này nếu chưa chạy thử.

### 2. Hiện trạng mượn trả sách

**Trả lời ngắn:** Đã có đủ vòng đời: lập phiếu mượn, gia hạn, trả, báo mất, đặt trước, tính phạt trễ, nhắc hạn, đền bù sách mất/hỏng.

**Chi tiết (mặc định, mỗi đơn vị tự đổi được, xem bảng cấu hình bên dưới):**
- Thời hạn mượn 14 ngày, gia hạn tối đa 2 lần, mỗi lần 14 ngày, tối đa 5 cuốn đang mượn mỗi độc giả.
- Phạt trễ 5.000đ/ngày, có thể đặt ngày ân hạn. Độc giả còn nợ phạt thì **bị chặn mượn tiếp** (`UNPAID_FINES`); thu phạt xong mượn được ngay. Demo: `demo.docgia01` đang nợ 24.000đ.
- Đặt trước: chỉ khi hết bản sao; có hàng đợi theo thứ tự; bản sao trả về được giữ cho người đầu hàng đợi (mặc định 3 ngày).
- Sách mất hoặc hỏng: có bậc hư hỏng cấu hình được và chính sách đền bù (mặc định 1,5 lần giá bìa, cửa sổ tìm lại sách 30 ngày); miễn hoặc giảm phạt cần duyệt.
- Nhắc hạn tự động: nhắc trước hạn 2 ngày, nhắc quá hạn mỗi 3 ngày, tối đa 3 lần.
- Báo cáo CSV phiếu quá hạn cho thủ thư.
- ⚠️ Khi chạy thử kịch bản, role thủ thư `demo.thuthu` chưa đủ khóa quyền để lập phiếu, thu phạt và trả sách (xem mục tài khoản). Cần gán thêm khóa trước khi demo bằng tài khoản này.

### 3. Cách thức chống lạm dụng (abuse) khi đặt hàng online

**Trả lời ngắn:** Nhiều lớp chồng nhau: giới hạn theo IP ở nginx, giới hạn tốc độ theo khách và theo cửa hàng, trần số đơn chờ, hạn giữ hàng tự hết, chấm điểm rủi ro, và xác minh email.

**Chi tiết:**
- **Hạn giữ hàng:** đơn online giữ tồn kho tối đa 15 phút nếu nhân viên chưa xác nhận, sau đó job tự hủy và nhả hàng. Chống "giữ hàng giả" khiến khách thật không mua được.
- **Trần theo khách:** tối đa 5 đơn đang chờ, 20 đơn mỗi 24 giờ, 50 cuốn mỗi dòng, 100 cuốn mỗi đơn. Hủy rồi đặt lại vẫn bị tính.
- **Chặn spam:** quá 5 đơn/phút/khách hoặc 300 đơn/phút/cửa hàng thì bị `429`; nginx giới hạn thêm theo IP (đặt đơn 20 lần/phút, đăng ký và xác minh email 5 lần/phút).
- **Chấm rủi ro (LOW, MEDIUM, HIGH, CRITICAL):** dựa vào tỷ lệ hủy trong 30 ngày, số đơn trong ngày và số lượng bất thường. HIGH thì siết trần đơn chờ còn một nửa; CRITICAL thì chặn đặt đơn.
- **Gửi lại cùng mã yêu cầu:** cùng nội dung thì trả đơn cũ, không giữ hàng hai lần; khác nội dung thì `422`.
- **Tồn kho không bán vượt:** mỗi lần giữ hàng là một câu cập nhật có điều kiện trong DB, đã kiểm thử 20 luồng đặt đồng thời trên Postgres thật.
- **Cách ly tenant:** hạn mức theo cửa hàng nên một cửa hàng bị spam không ảnh hưởng cửa hàng khác.
- ⚠️ Chưa có: xác minh số điện thoại, CAPTCHA, tín hiệu thiết bị ("nhiều tài khoản cùng một thiết bị"), hàng chờ duyệt tay cho mức CRITICAL (hiện chỉ chặn), hoàn tiền tự động khi thanh toán đến muộn. Ngưỡng chấm rủi ro hiện là cấu hình nền tảng, chưa theo gói dịch vụ.
- Báo cáo đầy đủ: `docs/security/ORDER_ABUSE_AUDIT.md`, `docs/security/ORDER_ABUSE_IMPLEMENTATION_REPORT.md`.

### 4. Đăng ký tài khoản khách có an toàn không?

**Trả lời ngắn:** Khách phải xác minh email bằng mã OTP 6 chữ số mới đăng nhập được.

**Chi tiết:** OTP hết hạn sau 10 phút, sai quá 5 lần thì bị vô hiệu, gửi lại cách nhau tối thiểu 60 giây và tối đa 5 lần trong 24 giờ. OTP không lưu thô trong DB. Hệ thống không tiết lộ tài khoản có tồn tại hay không (gửi lại luôn trả `202`, mọi lỗi xác minh cùng một mã lỗi). Tài khoản nhân viên và tài khoản cũ được coi là đã xác minh. ⚠️ Xác minh số điện thoại chưa làm (cần chọn nhà cung cấp SMS).

### 5. Thanh toán hỗ trợ những hình thức nào?

**Trả lời ngắn:** Tại quầy: tiền mặt, chuyển khoản, thẻ, QR; có cổng sandbox MoMo và SePay. Online: tiền mặt khi nhận hàng, COD, chuyển khoản và SePay khi bật.

**Chi tiết:** Đơn POS hai bước: tạo đơn giữ hàng, thu ngân xác nhận đã thu rồi mới trừ kho. SePay, MoMo và QR giả lập mặc định tắt, bật bằng biến môi trường (bảng bên dưới); chưa bật mà chọn thì báo `PAYMENT_NOT_ENABLED`. Kết quả cổng được lấy bằng cách FE thăm dò trạng thái lệnh thanh toán (không dùng webhook với SePay sandbox).

### 6. Nhiều khách thuê dùng chung thì dữ liệu có bị lẫn không?

**Trả lời ngắn:** Không. Mỗi đơn vị thuê là một tenant, dữ liệu cách ly bằng Row-Level Security của PostgreSQL ở mức cơ sở dữ liệu.

**Chi tiết:** Dịch vụ chạy bằng vai trò DB không có quyền bỏ qua RLS, nên lỗi code cũng không đọc được dữ liệu tenant khác. Platform admin chỉ thao tác thay đơn vị khi gửi `X-Tenant-Id`, và mọi lần như vậy được ghi log kiểm toán. Có test tự động kiểm cách ly trên DB thật.

### 7. Phân quyền hoạt động thế nào?

**Trả lời ngắn:** Phân quyền động theo từng API. Mỗi API tự đăng ký một khóa dạng `Controller/method`, admin gán khóa cho vai trò.

**Chi tiết:** Quyền mới của vai trò chỉ có hiệu lực khi đăng nhập hoặc làm mới token lần sau. Admin đơn vị bỏ qua kiểm khóa. Menu bên trái cũng thay đổi theo quyền và module đơn vị đã thuê (thư viện, bán hàng).

### 8. Phiên đăng nhập có an toàn không?

**Trả lời ngắn:** Access token sống 15 phút, refresh token sống 7 ngày và xoay vòng mỗi lần dùng; dùng lại token cũ bị từ chối.

**Chi tiết:** Dùng lại token đã thu hồi ngoài cửa sổ ân hạn thì chỉ thu hồi đúng phiên đó, không ảnh hưởng thiết bị khác. ⚠️ Refresh token nằm ở localStorage của trình duyệt nên một lỗ hổng XSS đọc được nó; đây là đánh đổi đã ghi nhận.

### 9. Hệ thống còn thiếu gì? (trả lời thẳng khi bị hỏi)

- Xác minh số điện thoại, CAPTCHA, hàng chờ duyệt tay đơn rủi ro cao, hoàn tiền tự động khi thanh toán đến muộn.
- Xem trước Markdown khi sửa trang website (hiện hiện Markdown thô).
- Màn hình xem log tích hợp đang được làm; hiện log chỉ ra console.
- Chưa có tải trọng thực tế: chưa đo hiệu năng dưới nhiều người dùng cùng lúc.
- Một số IT cũ còn lỗi (`SalesFlowIT` một test), và thủ thư demo chưa đủ khóa quyền.

## Thông số cấu hình

Đổi bằng giao diện (đã ghi chú) hoặc biến môi trường trước khi khởi động dịch vụ. Mặc định lấy từ `application.yml` của từng service.

### Chính sách mượn trả (mỗi đơn vị, màn hình `Chính sách mượn trả`, API `/api/circulation/policy`)

| Thông số | Mặc định | Khoảng | Ý nghĩa |
|---|---|---|---|
| `loanDays` | 14 | 1 đến 365 | Số ngày được mượn |
| `maxRenewals` | 2 | 0 đến 20 | Số lần gia hạn tối đa |
| `renewalDays` | 14 | 1 đến 365 | Số ngày mỗi lần gia hạn |
| `maxActiveLoansPerReader` | 5 | 1 đến 100 | Số cuốn đang mượn tối đa mỗi độc giả |
| `finePerDay` | 5.000đ | 0 đến 10.000.000 | Phạt trễ mỗi ngày |
| `graceDays` | 0 | 0 đến 90 | Số ngày ân hạn trước khi tính phạt |
| `reservationHoldDays` | 3 | 1 đến 30 | Số ngày giữ sách cho người đặt trước |
| `dueReminderDaysBefore` | 2 | 0 đến 30 | Nhắc trước hạn (0 = tắt) |
| `overdueReminderIntervalDays` | 3 | 1 đến 30 | Chu kỳ nhắc quá hạn |
| `overdueReminderMax` | 3 | 0 đến 20 | Số lần nhắc quá hạn tối đa (0 = tắt) |

### Chính sách đền bù sách mất (mỗi đơn vị, API `/api/circulation/compensation-policy`)

| Thông số | Mặc định | Ý nghĩa |
|---|---|---|
| `lostRate` | 1,5 | Hệ số đền bù nhân với giá |
| `priceSource` | `COVER_PRICE` | Lấy giá bìa hoặc giá mua |
| `minAmount`, `maxAmount` | không đặt | Sàn và trần số tiền đền |
| `foundWindowDays` | 30 | Số ngày được hoàn tiền nếu tìm lại sách |
| `refundProcessingFee` | 0 | Phí xử lý hoàn tiền |

Bậc hư hỏng (`/api/circulation/damage-levels`): mặc định `MINOR` 20% và `MAJOR` 50%, tối đa 10 bậc.

### Chống lạm dụng đặt hàng online (mỗi đơn vị, API `/api/sales/settings/order-abuse-policy`)

| Thông số | Mặc định | Khoảng | Biến môi trường cho mặc định nền tảng |
|---|---|---|---|
| `reservationMinutes` | 15 | 0 đến 10080 (0 = không hết hạn) | `LMS_ABUSE_RESERVATION_MINUTES` |
| `maxPendingOrders` | 5 | 1 đến 1000 | `LMS_ABUSE_MAX_PENDING_ORDERS` |
| `maxOrdersPerDay` | 20 | 1 đến 10000 | `LMS_ABUSE_MAX_ORDERS_PER_DAY` |
| `maxQuantityPerLine` | 50 | 1 đến 10000 | `LMS_ABUSE_MAX_QUANTITY_PER_LINE` |
| `maxTotalQuantity` | 100 | 1 đến 100000 | `LMS_ABUSE_MAX_TOTAL_QUANTITY` |
| `ordersPerMinutePerUser` | 5 | 1 đến 600 | `LMS_ABUSE_ORDERS_PER_MINUTE_PER_USER` |
| `ordersPerMinuteTenant` | 300 | 1 đến 100000 | `LMS_ABUSE_ORDERS_PER_MINUTE_TENANT` |

Chấm rủi ro (cấu hình nền tảng, `application.yml` của sales): `lms.sales.abuse.risk.min-orders` = 5, `cancel-percent-medium` = 30, `cancel-percent-high` = 50, `cancel-percent-critical` = 80. Job hết hạn giữ hàng: `lms.sales.abuse.reservation-expiry-delay-ms` = 30000, `reservation-expiry-job-enabled` = true.

### Xác minh email (identity, biến môi trường `EMAIL_VERIFICATION_*`)

| Thông số | Mặc định |
|---|---|
| `lms.identity.email-verification.enabled` | true |
| `otp-ttl` | 10 phút |
| `max-attempts` | 5 |
| `resend-min-interval` | 60 giây |
| `max-sends-per-day` | 5 |
| `otp-secret` (`EMAIL_VERIFICATION_OTP_SECRET`) | rỗng, **production phải đặt** |
| `from` | `no-reply@lms.local` |
| SMTP (`MAIL_HOST`, `MAIL_PORT`) | `localhost:1025` (Mailpit) |

### Phiên đăng nhập và token (identity)

| Thông số | Mặc định |
|---|---|
| `access-token-ttl` | 15 phút |
| `refresh-token-ttl` | 7 ngày |
| `STOREFRONT_CLIENT_SECRET` | rỗng; identity và customer-api phải đặt **cùng giá trị** |

### Cache và giới hạn tốc độ (Redis)

| Thông số | Mặc định | Ý nghĩa |
|---|---|---|
| `LMS_CACHE_ENABLED` | `false` ở dev-start, `true` ở Docker | Bật cache dữ liệu đọc nhiều |
| `lms.ratelimit.enabled` | true | Giới hạn tốc độ theo khách và cửa hàng (không tắt theo cache) |
| `REDIS_HOST` | `localhost` | Địa chỉ Redis |

### Thanh toán (sales)

| Thông số | Mặc định | Ý nghĩa |
|---|---|---|
| `PAYMENTS_SEPAY_ENABLED` | false | Bật cổng SePay sandbox |
| `PAYMENTS_SEPAY_TOKEN`, `_BANK_BIN`, `_ACCOUNT_NO`, `_ACCOUNT_NAME`, `_BANK_ACCOUNT_ID` | rỗng | Thông tin tài khoản sandbox, **không commit giá trị thật** |
| `PAYMENTS_SEPAY_MIN_QUERY_INTERVAL_MS` | 5000 | Giãn cách thăm dò mỗi lệnh |
| `app.payments.sandbox.enabled` | false (dev bật) | Cổng QR giả lập |
| `app.payments.expire-delay-ms` | 60000 | Chu kỳ job hết hạn lệnh thanh toán |
| `LOGGING_LEVEL_LMS_LOG_OUTBOUND` | INFO | Đặt `DEBUG` để thấy log thăm dò SePay |

### Dữ liệu cá nhân và lưu giữ (sales, API `/api/sales/settings`)

| Thông số | Mặc định | Khoảng |
|---|---|---|
| `vatRateBps` | 500 (5%) | 0 đến 10000 |
| `returnWindowDays` | 7 | 0 đến 365 |
| `lowStockThreshold` | 5 | 0 đến 100000 |
| `walkInPiiRetentionMonths` | 12 | 1 đến 120 |

### Cache dữ liệu (Redis, theo tenant)

Chỉ cache dữ liệu đọc nhiều và ít đổi; ghi tương ứng sẽ xóa cache. Khóa Redis có dạng `lms:v1:t:{tenantId}:{namespace}:{hash}`. Redis lỗi thì hệ thống tự đọc thẳng DB (không làm hỏng yêu cầu).

| Dữ liệu | Dịch vụ | TTL |
|---|---|---|
| Tác giả, thể loại, nhà xuất bản, vị trí kệ | catalog | 60 giây |
| Chính sách mượn trả | circulation | 60 giây |
| Cấu hình bán hàng, nhà cung cấp | sales | 30 giây |
| Phòng ban (danh sách, cây), vai trò (danh sách, chi tiết) | identity | 30 giây |
| Trang website công khai (CMS) | system | 60 giây, dùng chung mọi tenant |

Cố ý **không cache**: số bản sao sẵn có, tồn kho, đơn hàng, thống kê dashboard, đăng nhập và phân quyền (cần dữ liệu luôn mới). Ghi dữ liệu từ đường khác (Kafka, nhập CSV, seed) không xóa cache nên dữ liệu có thể cũ tối đa 30 đến 60 giây. Công tắc: `LMS_CACHE_ENABLED` (dev-start đặt `false`, Docker mặc định `true`). Cấu hình Redis có thời gian chờ 200ms.

### Các job nền chạy định kỳ

Giờ cron theo giờ máy chủ, trừ khi ghi chú khác. Mỗi job tắt hoặc đổi lịch được bằng thuộc tính ở cột cuối. Các job theo tenant chạy lần lượt từng tenant, một tenant lỗi không dừng tenant khác; chạy trùng nhiều instance đều an toàn.

| Job | Dịch vụ | Chạy khi nào | Việc làm | Thuộc tính |
|---|---|---|---|---|
| Hết hạn giữ hàng đơn online | sales | Mỗi 30 giây (trễ khởi động 45 giây) | Hủy đơn quá hạn giữ hàng, nhả tồn | `lms.sales.abuse.reservation-expiry-delay-ms`, `reservation-expiry-job-enabled` |
| Hết hạn lệnh thanh toán | sales | Mỗi 60 giây (trễ khởi động 30 giây) | Đánh dấu lệnh QR hết hạn, nhả tồn, hủy đơn POS chờ thu | `app.payments.expire-delay-ms`, `expire-job-enabled` |
| Ẩn danh khách vãng lai, liên hệ khách | sales | Hằng ngày 02:30 | Ẩn dữ liệu cá nhân quá hạn lưu giữ (mặc định 12 tháng) | `lms.sales.anonymize.cron`, `enabled` |
| Dọn log vận hành | sales | Hằng ngày 03:15 | Xóa log tích hợp quá 30 ngày | `lms.sales.logs.purge.cron`, `retention-days` |
| Nhắc hạn trả sách | circulation | Hằng ngày 07:00 giờ Việt Nam | Ghi sổ nhắc và phát sự kiện nhắc sắp hạn, quá hạn | `app.borrowing.reminder-cron` |
| Hết hạn đặt trước | circulation | Hằng ngày 00:05 | Hủy đặt trước quá hạn giữ chỗ | `app.reservation.expire-cron` |
| Xả hàng chờ thông báo | support | Mỗi 5 giây | Gửi các thông báo đang chờ | `lms.notification.dispatch.interval-ms`, `dispatch.enabled` |
| Dọn thông báo cũ | support | Hằng ngày 03:30 giờ Việt Nam | Xóa lượt gửi quá 180 ngày | `lms.notification.retention.cron`, `retention-days` |
| Dọn refresh token | identity | Hằng ngày 03:30 | Xóa token hết hạn quá 1 ngày | `lms.auth.refresh-cleanup.cron` |
| Vòng đời hợp đồng thuê | identity | Mỗi giờ, phút thứ 7, giờ Việt Nam | Xử lý hợp đồng sắp hết hạn, ân hạn, hết hạn | `lms.contract.job.cron`, `job.enabled`, `lms.contract.zone` |
| Xuất dữ liệu tenant | identity | Mỗi 10 giây | Nhận yêu cầu xuất mới nhất, dọn gói hết hạn, đánh dấu tiến trình kẹt | `lms.tenant-data.export.poll-interval` |
| Ẩn danh yêu cầu liên hệ SaaS | system | Hằng ngày 03:30 | Ẩn danh yêu cầu liên hệ quá 12 tháng | `lms.system.cms.anonymization.cron` |

ℹ️ Muốn thấy job chạy khi demo: đặt hạn giữ hàng 1 phút rồi đợi tối đa khoảng 2 phút (xem bước 7 của script `demo-e2e.sh`), hoặc rút `app.payments.expire-delay-ms`. Các job hằng ngày chạy ban đêm nên không thể thấy trực tiếp trong buổi demo.

### Cấu hình hóa đơn và cửa hàng

Màn hình `Cấu hình hóa đơn` (`/api/sales/invoice-settings`) lưu thông tin người bán in trên hóa đơn: tên pháp lý, mã số thuế, địa chỉ... Màn hình `Cấu hình bán hàng` lưu thuế suất, thời hạn trả hàng, ngưỡng tồn thấp, số tháng lưu dữ liệu khách vãng lai (xem bảng ở trên).

## Chức năng nổi bật (nêu trong 2 phút)

1. **Một nền tảng cho nhiều đơn vị thuê (SaaS):** thư viện lẫn hiệu sách dùng chung hạ tầng, cách ly dữ liệu từng tenant ở mức cơ sở dữ liệu (Row-Level Security); hợp đồng thuê có vòng đời tự động (sắp hết hạn, ân hạn, hết hạn); đơn vị tự xuất dữ liệu của mình.
2. **Phân quyền động và menu động:** gán quyền cho vai trò theo từng API, không cần sửa code; menu đổi theo quyền và module đã thuê.
3. **Dashboard có biểu đồ cho cả hai cấp:** platform admin xem tổng quan nền tảng (số đơn vị, hợp đồng sắp hết hạn); admin đơn vị xem mượn trả, doanh thu, tình trạng bản sao, top sách; xuất báo cáo CSV.
4. **Hai nghiệp vụ trong một sản phẩm:** mượn trả (phiếu mượn, gia hạn, đặt trước có hàng đợi, phạt, đền bù, nhắc hạn tự động) và bán sách (POS, tồn kho, nhập hàng, trả hàng hoàn tiền, đơn online, hóa đơn).
5. **Chống lạm dụng khi bán online:** hạn giữ hàng tự hết, trần theo khách, giới hạn tốc độ, chấm rủi ro, xác minh email bằng OTP, tồn kho không bán vượt dù nhiều người đặt cùng lúc.
6. **Thanh toán QR đa cổng:** thu ngân thu hai bước, có QR giả lập, MoMo và SePay sandbox; kết quả cổng tự đồng bộ.
7. **Mô hình sách tác phẩm, ấn bản, bản sao, bộ sách:** xử lý sách nhiều tập, nhiều bản dịch, đặt trước theo tác phẩm (⚠️ xem lưu ý ở câu hỏi 1).
8. **Cửa hàng công khai cho khách:** storefront riêng từng đơn vị qua cổng công khai chỉ đọc và đặt đơn, có trang website quản lý bằng CMS và API công khai cho tích hợp ngoài.
9. **Nhập dữ liệu hàng loạt từ Excel** (đầu sách, bản sao, mặt hàng, nhà cung cấp, người dùng) và dữ liệu demo nạp một lệnh.
10. **Bảo mật phiên và kiểm toán:** refresh token xoay vòng, phát hiện dùng lại token, nhật ký hoạt động nghiệp vụ, log kiểm toán khi platform admin thao tác thay đơn vị, log tích hợp tách kênh với dữ liệu nhạy cảm được che.

## Từ viết tắt và thuật ngữ kỹ thuật

### Từ viết tắt (tên đầy đủ và nghĩa)

| Viết tắt | Tên đầy đủ | Nghĩa trong hệ thống |
|---|---|---|
| SaaS | Software as a Service | Phần mềm cho thuê theo dịch vụ: nhiều đơn vị thuê dùng chung một nền tảng |
| RLS | Row-Level Security | Bảo mật mức dòng của PostgreSQL: mỗi truy vấn chỉ thấy dòng thuộc tenant của mình |
| RBAC | Role-Based Access Control | Phân quyền theo vai trò: gán quyền cho vai trò, gán vai trò cho người dùng |
| JWT | JSON Web Token | Chuỗi mã hóa chứa danh tính và quyền của người dùng sau khi đăng nhập (access token) |
| OTP | One-Time Password | Mật khẩu dùng một lần, ở đây là mã 6 chữ số gửi qua email để xác minh |
| SMTP | Simple Mail Transfer Protocol | Giao thức gửi email; Mailpit nhận thư giả qua giao thức này |
| CAPTCHA | Completely Automated Public Turing test to tell Computers and Humans Apart | Kiểm tra "bạn không phải máy" (hệ thống chưa có) |
| POS | Point of Sale | Bán tại quầy, màn hình của thu ngân |
| COD | Cash On Delivery | Thu tiền mặt khi giao hàng |
| QR | Quick Response (code) | Mã vạch hai chiều để quét chuyển khoản |
| VAT | Value Added Tax | Thuế giá trị gia tăng (thuế GTGT) |
| VND | Việt Nam đồng | Đơn vị tiền tệ; hệ thống lưu số nguyên đồng, không có số lẻ |
| CSV | Comma-Separated Values | Tệp bảng tính văn bản phân tách bằng dấu phẩy, dùng để xuất báo cáo |
| API | Application Programming Interface | Giao diện lập trình: các địa chỉ mà FE và hệ thống ngoài gọi |
| BFF | Backend For Frontend | Lớp trung gian riêng cho giao diện: `lms-api` (nhân viên) và `customer-api` (khách) |
| FE / BE | Frontend / Backend | Giao diện người dùng / phần máy chủ |
| DB | Database | Cơ sở dữ liệu (PostgreSQL, MongoDB) |
| CMS | Content Management System | Hệ thống quản trị nội dung trang website |
| TTL | Time To Live | Thời gian sống: bao lâu dữ liệu cache hoặc token còn hiệu lực |
| HMAC | Hash-based Message Authentication Code | Băm có khóa bí mật, dùng để lưu OTP không ở dạng thô |
| SHA-256 | Secure Hash Algorithm, 256-bit | Hàm băm một chiều, dùng để so khớp nội dung đơn khi gửi lại |
| UUID | Universally Unique Identifier | Mã định danh duy nhất toàn cục, ví dụ mã đơn, mã tenant |
| ISBN | International Standard Book Number | Mã số tiêu chuẩn quốc tế của một ấn bản sách |
| FRBR | Functional Requirements for Bibliographic Records | Mô hình thư mục 3 tầng: tác phẩm, ấn bản, bản sao |
| PII | Personally Identifiable Information | Dữ liệu cá nhân có thể nhận diện một người (tên, điện thoại, email) |
| XSS | Cross-Site Scripting | Lỗi cho phép chèn mã độc vào trang web, có thể đọc dữ liệu trong trình duyệt |
| NAT | Network Address Translation | Nhiều máy dùng chung một địa chỉ IP công cộng, nên nginx không thể giới hạn quá chặt theo IP |
| IP | Internet Protocol (address) | Địa chỉ mạng của máy gọi |
| ADR | Architecture Decision Record | Tài liệu ghi lại một quyết định kiến trúc và lý do (thư mục `docs/decisions/`) |
| IT | Integration Test | Kiểm thử tích hợp, chạy trên cơ sở dữ liệu thật |
| E2E | End-to-End | Kiểm thử từ đầu đến cuối như người dùng thật |
| CRUD | Create, Read, Update, Delete | Bốn thao tác cơ bản: tạo, đọc, sửa, xóa |
| HTTP | HyperText Transfer Protocol | Giao thức web; các mã `200`, `400`, `403`, `409`, `422`, `429`, `503` là mã kết quả |
| cron | (lịch của Unix) | Cú pháp hẹn giờ chạy job, ví dụ `0 30 3 * * *` là 03:30 mỗi ngày |

### Thuật ngữ nghiệp vụ và kỹ thuật

| Thuật ngữ | Giải thích |
|---|---|
| Tenant (đơn vị thuê) | Một khách hàng thuê hệ thống, ví dụ một thư viện hoặc một hiệu sách. Dữ liệu các tenant tách biệt hoàn toàn |
| Platform admin | Quản trị viên của cả nền tảng, không thuộc tenant nào; thao tác thay tenant bằng header `X-Tenant-Id` |
| Module (LENDING, SALES) | Gói chức năng tenant đã thuê: `LENDING` là thư viện mượn trả, `SALES` là bán hàng |
| Khóa quyền `Controller/method` | Mỗi API có một khóa quyền riêng, ví dụ `BorrowingController/borrow`; vai trò được gán các khóa này |
| Access token / refresh token | Token ngắn hạn dùng gọi API (15 phút) / token dài hạn (7 ngày) dùng để lấy access token mới |
| Rotation (xoay vòng token) | Mỗi lần làm mới sẽ cấp refresh token mới và thu hồi token cũ; dùng lại token cũ bị từ chối |
| Idempotency (tính lũy đẳng) | Gửi cùng một yêu cầu nhiều lần chỉ có tác dụng một lần; đặt đơn dùng `clientRequestId` làm khóa |
| Rate limit (giới hạn tốc độ) | Chặn khi một khách hoặc địa chỉ gửi quá nhiều yêu cầu trong một khoảng thời gian, trả `429` |
| Reserved / onHand / available | Tồn kho: `onHand` là số cuốn thực có, `reserved` là số đã giữ cho đơn chưa giao, `available` = `onHand` - `reserved` |
| Giữ hàng (reservation) | Tạm trừ tồn khả dụng cho một đơn chờ; có hạn, hết hạn thì tự nhả |
| Đặt trước (reservation, thư viện) | Độc giả xếp hàng chờ khi sách hết bản sao; bản sao trả về được giữ cho người đầu hàng đợi |
| Tác phẩm / ấn bản / bản sao | Nội dung trừu tượng / một lần xuất bản cụ thể (ISBN, định dạng, bản dịch) / cuốn sách vật lý có mã vạch |
| Bộ sách (series) | Nhóm các tập cùng một bộ, ví dụ Harry Potter tập 1 đến 7 |
| Phiếu mượn | Chứng từ ghi độc giả mượn những bản sao nào, hạn trả, số lần gia hạn |
| Webhook | Cổng thanh toán chủ động gọi vào hệ thống để báo đã nhận tiền |
| Polling (thăm dò) | FE hoặc BE định kỳ hỏi cổng thanh toán xem đã nhận tiền chưa (SePay sandbox dùng cách này) |
| Sandbox | Môi trường thử của nhà cung cấp, tiền và giao dịch không thật |
| Mailpit | Hộp thư giả chạy trong Docker để xem email OTP khi demo, thư không ra ngoài |
| Job nền (scheduled job) | Tác vụ tự chạy theo lịch, ví dụ hủy đơn quá hạn giữ hàng |
| Cache | Lưu tạm kết quả đọc trong Redis để trả lời nhanh hơn |
| Redis | Kho dữ liệu trong bộ nhớ, dùng cho cache và đếm giới hạn tốc độ |
| Kafka | Nền tảng truyền sự kiện giữa các dịch vụ (ví dụ báo sách trả về) |
| Migration | Tệp cập nhật cấu trúc cơ sở dữ liệu, đánh số `V<n>`, chạy một lần theo thứ tự |
| Seed (nạp dữ liệu mẫu) | Chạy một lệnh để tạo dữ liệu demo cho tenant `demo` |
| Mock | Dữ liệu và máy chủ giả dùng khi chạy kiểm thử giao diện không cần backend thật |
| Slug | Phần đường dẫn thân thiện của trang website, ví dụ `gioi-thieu` |
| Markdown | Cú pháp văn bản có định dạng nhẹ, dùng để soạn nội dung trang CMS |
| Ẩn danh (anonymize) | Xóa hoặc thay dữ liệu cá nhân bằng giá trị rỗng sau thời hạn lưu giữ, giữ lại số liệu thống kê |
| Khóa lạc quan (`version`) | Mỗi bản ghi có số phiên bản; hai người sửa cùng lúc thì người sau nhận `409` và phải tải lại |
| Audit log (nhật ký kiểm toán) | Ghi ai làm gì, khi nào, để truy vết |
| Dashboard | Màn hình tổng quan có thẻ chỉ số và biểu đồ |

## Chạy tự động

```bash
bash scripts/demo-e2e.sh            # chạy đủ kịch bản, in ✅/❌ từng bước
SKIP_EXPIRY=1 bash scripts/demo-e2e.sh   # bỏ bước chờ hết hạn giữ hàng (~2 phút)
```

Yêu cầu: các dịch vụ và Mailpit đang chạy (mục 0), tenant `demo` đã seed. Script tạo vài tài khoản khách `demo-e2e-*` và đơn thử trong tenant `demo`.
