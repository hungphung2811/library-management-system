# Báo cáo hiệu năng và kiểm thử đầu cuối: nhập master data từ Excel (ADR-0024)

## 1. Môi trường đo
- ℹ️ Máy dev Windows 11, i5-10210U (4 nhân/8 luồng), 15,8 GB RAM. Java 27, mọi service chạy `java -jar` từ jar build lúc 15:51 (có `import-common`; migration identity V31 đã áp lúc 15:52; các migration catalog V7-V9, circulation V10, sales V8-V11, audit V5 đã áp từ phiên trước lúc 03:30).
- ℹ️ `JAVA_TOOL_OPTIONS=-Xmx256m`, cờ `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC` của `dev-start.ps1`; bật `--app.security.enabled=true --lms.security.permission.enabled=true`. Gồm system, identity, catalog, circulation, support, sales, lms-api, customer-api (FE không chạy).
- ℹ️ Postgres/Kafka/Redis/Mongo trong Docker. Redis `maxmemory 64mb`, `allkeys-lru`. Cache `LMS_CACHE_ENABLED=false`. Service chạy trên host nên gọi Redis qua cổng Docker Desktop (relay chậm, xem 5.2).
- ℹ️ nginx: container `perf-nginx` (nginx:1.27-alpine, cấu hình mới copy từ repo vào scratchpad, upstream trỏ `host.docker.internal`) chiếm 8080. Dừng: `docker rm -f perf-nginx`.
- ℹ️ Không có k6/hey/wrk/vegeta, dùng script Python (`urllib`, thread) ở scratchpad. Đo heap bằng `jstat`, RSS bằng Win32 API. Không có `pg_stat_statements`, Hibernate statistics tắt: số câu SQL chưa đo (chỉ có số transaction).
- ℹ️ Tenant mặc định, admin `admin`, 1 người dùng; tenant `demo` để thử cô lập.

## 2. Kết quả chức năng đầu cuối (qua lms-api 8085 và nginx 8080)
- ✅ Tải mẫu 8 loại: 200, `.xlsx`, `no-store`, tên `mau-<type>.xlsx`.
- ✅ Upload, preview, rows, file lỗi, confirm, poll tới COMMITTED cho cả 8 loại; confirm hai lần không ghi hai lần (202 rồi 200/202 cùng phiên).
- ✅ DB khớp: tenant_id đúng (0...d1), danh mục/đầu sách/bản sao/NCC/người dùng đúng số dòng hợp lệ; dòng lỗi (trùng trong file, thiếu tham chiếu, ngày/năm sai, thể loại quá 2 cấp) không ghi; đầu sách không ISBN ghi kèm cảnh báo.
- ✅ Tồn đầu kỳ: biến động `ADJUSTMENT` lý do "Tồn đầu kỳ nhập từ Excel <importId>"; bộ đếm `on_hand` khớp tổng sổ cái (0 dòng lệch trong 5.010 mặt hàng). Audit tóm tắt `STOCK_ITEMS_IMPORTED`/`SUPPLIERS_IMPORTED` có.
- ✅ Người dùng: mật khẩu tạm tải được một lần (lần 2: 410 `IMPORT_CREDENTIALS_GONE`), đăng nhập bằng tài khoản mới 200, token `mustChangePassword=true`, không có quyền; sai mật khẩu 401. Độc giả có hồ sơ (5/10 như dữ liệu thử).
- ✅ Phân quyền: role không có khóa (demo.thuthu, demo.cuahang) upload bị 403 `Thiếu quyền: XImportController/upload`; tải mẫu vẫn 200. Phiên của tenant khác/người khác: 404 cho get/rows/confirm/delete.
- ✅ File xấu: không phải zip 415, OLE2/xls 415, xlsm 415, zip cắt cụt 422 CORRUPT, DOCTYPE 422 UNSAFE, zip bomb 150 MB giải nén 422 UNSAFE (file 154 KB), mẫu của loại khác 422 MISMATCH, rỗng 422, 5.001 dòng 422 TOO_MANY_ROWS, 5,5 MB 413 JSON `IMPORT_FILE_TOO_LARGE`, 6,5 MB 413 HTML từ nginx (đúng ADR).
- ✅ Công thức: ô `=HYPERLINK(...)`, `+cmd|calc` đi vào như chuỗi; file lỗi không có `<f>`, style `quotePrefix`.
- ✅ Giới hạn 3 phiên/người: 429 `IMPORT_SESSION_LIMIT`. Redis: khóa `lms:import:v1:...` TTL ~1.800 giây; bị xóa sau DELETE; creds bị xóa sau tải.
- ✅ Thứ tự tham chiếu -> đầu sách(+bản sao) -> mặt hàng chạy đúng.
- ⏭️ Chưa kiểm: module tắt (MODULE_NOT_ENABLED), platform admin với `X-Tenant-Id`, hết TTL 30 phút thật, dừng Redis hẳn (hệ thống chặn thao tác dừng container; thay bằng `CLIENT PAUSE 4000`, mục 5.2).

## 3. Hiệu năng (5.000 dòng, lms-api, -Xmx256m)

| Loại | File | Upload + preview | Confirm (nền) | Redis `rows` | Heap max service | RSS max | Transaction |
|---|---|---|---|---|---|---|---|
| authors | 136 KB | 0,40 s | 3,1 s | 49 KB | 55 MB | 352 MB | 91 |
| titles (5.000 đầu sách) | 376 KB | 0,79 s | 5,1 s | 164 KB | 87 MB | 376 MB | 146 |
| titles 2.500 + bản sao 2.500 | 281 KB | 0,69 s | 6,1 s | 115 KB | 73 MB | 377 MB | 107 |
| stock-items (tồn đầu kỳ) | 228 KB | 1,65 s | 34,9 s | 230 KB | 54 MB | 340 MB | 108 |
| users (bcrypt) | 302 KB | 1,09 s | 859,9 s (5,8 người/giây) | 99 KB (+148 KB creds) | 66 MB | 379 MB | 37.640 |

- ✅ GET phiên 13-22 ms (trung vị), rows trang 200 dòng 58-194 ms, file lỗi 78-177 ms. Mọi thao tác nhỏ hơn timeout 30 s (lms-api) và 60 s (nginx) nhiều lần; confirm trả 202 sau 76-155 ms.
- ✅ Chi phí BFF: upload authors 5.000 dòng trực tiếp catalog 307-343 ms, qua lms-api 306-377 ms (khác biệt nằm trong nhiễu).
- ⚠️ stock-items confirm 34,9 s vượt mục tiêu 30 s của ADR: `StockItemImportExecutor` gọi `stockService.adjust` từng mặt hàng trong vòng lặp (khoảng 7 ms/dòng).
- ⚠️ users confirm 14,3 phút cho 5.000 người: khoảng 172 ms/người, xấp xỉ một lần bcrypt (đăng nhập cơ sở 153 ms) và mỗi người một transaction (~7,5 transaction/người). Một luồng executor, C1 only làm bcrypt chậm. Trong lúc đó đăng nhập admin (thăm dò 0,5 s/lần, 1.162 lần, 0 lỗi): p50 217 ms, p95 360 ms, max 1.443 ms so với cơ sở p50 153, p95 168, max 172 ms.
- ✅ Heap: đỉnh 55-87 MB (một upload), 128-163 MB khi 2 upload titles 5.000 dòng đồng thời; không OOM, FGC 0-2 lần trong mỗi lần đo. RSS catalog đỉnh 475 MB. lms-api heap ≤ 39 MB.
- ✅ Đồng thời: 3 upload authors cùng lúc: 2 thành công (~650 ms), 1 bị 429 `IMPORT_BUSY` (`retryAfterSeconds=5`, 88 ms); 4 và 6 upload titles: đúng 2 qua, còn lại 429 BUSY. 3 confirm cùng lúc: hoàn tất lần lượt sau 2,8 / 5,7 / 7,6 s (executor 1 luồng, hàng đợi hoạt động).
- ✅ Redis timeout 200 ms không gây 503 với file mẫu thường (rows tối đa 230 KB, creds 148 KB).
- ⏭️ Chưa đo: số câu SQL, pool Hikari, độ trễ Kafka (5.000 `CopyAvailableEvent`), p95 đăng nhập khi confirm catalog.

## 4. Lỗi tái hiện được (xếp theo mức ảnh hưởng)

1. ❌ (Trung bình-Cao) Redis ghi chậm làm upload thất bại nhưng vẫn tạo phiên mồ côi. Bước: upload authors 5.000 dòng có mô tả 200-500 ký tự ngẫu nhiên (rows gzip ≥ ~0,9 MB). Kết quả: `QueryTimeoutException` (timeout 200 ms) -> service 503, nhưng script Lua vẫn chạy xong ở Redis nên phiên và khóa `active` còn đó, người dùng không có `importId`. Thử lại 3 lần: 429 `IMPORT_SESSION_LIMIT` tới 30 phút. Log catalog: `Redis không dùng được cho phiên nhập: QueryTimeoutException`. Đề xuất: timeout riêng dài hơn (1-2 s) cho lệnh ghi phiên import; khi lỗi ghi thì dọn phiên theo `importId` đã sinh; ZSET `active` nên bỏ mục không còn khóa `meta`.
2. ❌ (Trung bình) Phiên đã COMMITTED vẫn chiếm 1 trong 3 chỗ tới khi DELETE hoặc 30 phút: sau 3 lần nhập thành công liên tiếp (authors, publishers, categories) upload thứ 4 bị 429 (tái hiện ở bước 4). Quy trình đủ 8 loại sẽ bị chặn nếu FE không tự DELETE. Đề xuất: COMMITTED/COMMIT_FAILED không tính vào giới hạn, hoặc FE hủy phiên sau khi xong.
3. ❌ (Trung bình) `lms-api` `ApiExceptionHandler` đổi mọi 5xx của service thành 502 `DEPENDENCY_ERROR`, nên `503 IMPORT_STORAGE_UNAVAILABLE` (docs identity/catalog/sales-import.md mục 5) không tới FE. Tái hiện: `CLIENT PAUSE 4000 ALL` trên Redis rồi upload/GET: service trực tiếp trả 503 `IMPORT_STORAGE_UNAVAILABLE` (catalog, identity, sales), qua lms-api 502 `DEPENDENCY_ERROR` (log lms-api ERROR kèm stack). Đề xuất: chuyển nguyên 503 có `code` bắt đầu `IMPORT_` ở proxy import.
4. ⚠️ (Trung bình) Mất khóa `meta` giữa lúc COMMITTING (mô phỏng LRU/`DEL`): luồng nền dừng, titles 5.000 dòng chỉ ghi 1.600; người dùng nhận 404 `IMPORT_NOT_FOUND`, không biết ghi dở (đúng rủi ro ADR mục 8, nay đã tái hiện). Redis đầy (`allkeys-lru`, 90 MB khóa rác): phiên VALIDATED mất `meta` (404) còn `rows`/`result` mồ côi, khóa `active` mồ côi chiếm chỗ. Khuyến nghị vận hành: Redis riêng hoặc `noeviction`/maxmemory lớn hơn cho import.
5. ⚠️ (Thấp-Trung bình) Hiệu năng ghi: stock-items 35 s và users 14 phút (mục 3). Đề xuất: gom `adjust` và insert sổ cái theo lô; băm bcrypt song song vài luồng ngoài transaction và ghi người dùng theo chunk; hiển thị ước lượng thời gian cho người dùng.
6. ⚠️ (Thấp) `GET /api/*/imports/{type}/not-a-uuid` trả 400 mặc định của Spring (không theo `ErrorResponse`).
7. ⚠️ (Thấp) Mật khẩu tạm nằm dạng rõ trong Redis (`creds` 148 KB cho 5.000 người) tới khi tải hoặc 30 phút (đã biết trong ADR; Redis mở cổng 6379).

## 5. Ghi chú đo
### 5.1. Redis
- ℹ️ Cấu hình thật: `maxmemory 67108864`, `allkeys-lru`. Một phiên 5.000 dòng chiếm 50-230 KB (nén gzip), kết quả ~17 KB; rất nhỏ so với 64 MB (khoảng 250+ phiên).
- ℹ️ Script Lua chạy atomic nên đẩy Redis lên 99 MB (vượt maxmemory) tới khi script xong, rồi mới đẩy 339 khóa.
### 5.2. Redis qua Docker Desktop (môi trường dev)
- ℹ️ Đo trực tiếp bằng socket: SET 100 KB 72-80 ms, 900 KB 85-138 ms, 1,5 MB 136-177 ms, 3 MB 310-400 ms; EVAL 900 KB ~150-170 ms, 1,5 MB 260-360 ms, 3 MB 550-870 ms; PING 2 ms. Với relay này, timeout 200 ms chỉ đủ cho phiên dưới ~0,9 MB; trong mạng Docker nội bộ (compose) số này có thể tốt hơn, chưa đo. Dữ liệu thật nén được nên ngưỡng thực tế cao hơn dữ liệu ngẫu nhiên đã dùng.

## 6. Trạng thái bàn giao
- ✅ Stack đang chạy: 8081-8088 UP (heap 21-136 MB, RSS 147-478 MB), nginx thử nghiệm 8080. Không chạy FE. Tắt: `scripts\dev-stop.ps1`, `docker rm -f perf-nginx`; sales (8087) và customer-api (8088) tắt tay theo cổng. Không docker down, không commit.
- ⚠️ Dữ liệu đo còn trong DB dev (tenant mặc định): người dùng `pu%` (5.000) và `perfu160025%`, tác giả `PA %` (20.000) và `PerfAuthor 160025 %`, đầu sách `Perf Title%`/`Perf Sach 160025%`, mặt hàng sku `SK%`/`SKU160025%`, NCC `PerfSup%`, bản sao `BC%`/`PB160025%`. Xóa khi không cần (chưa làm để khỏi mất dữ liệu ngoài ý muốn).
- ⚠️ Người dùng `pu%` đã đặt mật khẩu tạm ngẫu nhiên (file đã tải, không lưu).
