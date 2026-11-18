# Rà soát N+1 và index: sales, identity (ngoài luồng cấp token), support, system (menu), lms-api, customer-api

Phạm vi: `sales-service`, `support-service` (audit, notification), `identity-service` (danh sách người dùng/độc giả/phòng ban,
tìm kiếm, phiên, hợp đồng; KHÔNG đụng AccessibleTenants/TokenService/RoleJpaEntity.role_permissions), `system-service` (menu),
phần gọi các service đó ở `lms-api` và `customer-api`. Không đụng catalog-service, circulation-service.
Quy ước áp dụng: mục "Quy ước code" trong `CLAUDE.md` (không gọi repository/port/client trong vòng lặp; index composite, tenant_id đứng đầu).

Phương pháp: đọc mã (repository/adapter/use case, mapper, job, consumer, client BFF) và đối chiếu với index trong migration
sales V1..V10, identity V1..V29, audit V1..V4, system V1..V5. KHÔNG chạy EXPLAIN trên dữ liệu thật: mọi nhận định về kế hoạch
truy vấn là suy luận từ mã và định nghĩa index.

## 1. Phát hiện N+1

| # | Mức | Vị trí | Mô tả | Trạng thái |
|---|---|---|---|---|
| 1 | Trung bình | `sales-service` `StockPersistenceAdapter.refreshCoverPrices` | Vòng `for` chạy một `UPDATE` cho từng mặt hàng. `StorefrontQueryUseCases.syncMissingCoverPrices` gọi với tối đa 500 mặt hàng ở lần đầu một tenant sắp theo giá (`SYNC_BATCH`), `healSnapshots` tối đa 50. | ĐÃ SỬA |
| 2 | Trung bình | `identity-service` `ContractQueryUseCase.expiring` (`GET` hợp đồng sắp hết hạn của platform admin) | `ops.brief(tenantId)` (một `findById`) cho từng tenant khác nhau trong danh sách (có nhớ đệm theo tenant nhưng vẫn N truy vấn). | ĐÃ SỬA |
| 3 | Trung bình | `identity-service` `InProcessReaderLookupAdapter.findUserIdsByEmail` (dòng 30-33), gọi từ `ExternalSignInUseCase` dòng 68 | Mỗi lần đăng nhập ngoài lặp qua MỌI tenant ACTIVE, mỗi tenant một transaction `REQUIRES_NEW` + một truy vấn email. Tỷ lệ theo số tenant. | CHƯA SỬA (xem 2.2) |
| 4 | Thấp | `identity-service` `ImportUsersUseCase.process` (dòng ~112-117) | Mỗi dòng (tối đa 500) chạy 3 truy vấn kiểm trùng (`existsByUsername`, `findUserIdByUsername`, `existsByEmail`), cộng việc ghi từng dòng. | CHƯA SỬA (xem 2.3) |
| 5 | Thấp | `identity-service` `ListActiveTenantAdminsQuery.execute` | `memberships.find(userId, tenantId)` cho từng quản trị viên của tenant (job nhắc hạn, vài người mỗi tenant). | CHƯA SỬA |
| 6 | Thấp | `sales-service` `StockService.applyOne` + `StockPersistenceAdapter.append` | Mỗi dòng hàng: `UPDATE` có điều kiện (`tryApply`) + `SELECT id` + `INSERT` sổ cái. Số dòng đơn bị chặn (`MAX_LINES`). | CHƯA SỬA (xem 2.4) |
| 7 | Thấp | `sales-service` `ExpirePaymentIntentsUseCase.execute` dòng 34 | Một transaction cho từng lệnh thanh toán hết hạn (tối đa `expire-batch-size` = 100 mỗi tenant mỗi chu kỳ). Cố ý: lỗi một lệnh không làm hỏng cả lô; file thuộc tính năng QR đang sửa dở. | CHƯA SỬA, giữ nguyên |
| 8 | Thấp | `support-service` `ContractExpiringConsumer` dòng 70 | Một `INSERT ... ON CONFLICT` (một transaction) cho từng người nhận (quản trị viên tenant, vài người). Idempotent theo từng người nên tách riêng là có chủ đích. | CHƯA SỬA, giữ nguyên |
| 9 | Thấp | `system-service` `InitializeDefaultMenusUseCase.execute` dòng 47 + `MenuPersistenceAdapter.save` | `findById` + `INSERT` cho từng menu mặc định (khoảng vài chục) khi chép menu cho tenant mới; chạy một lần mỗi tenant. | CHƯA SỬA |

Đã kiểm và KHÔNG có N+1 (để lần sau khỏi rà lại):
- sales: `SalesOrderPersistenceAdapter.assemble` (dòng + thanh toán theo lô `IN`), `GoodsReceiptPersistenceAdapter.linesOf`, `SalesReturnPersistenceAdapter.assemble`,
  `StorefrontQueryUseCases` (một lần `catalog.lookup` cho cả trang, một lần `findStorefrontRows`), `ListStockItemsUseCase`, `StockCsvReportUseCase` (tra tên theo trang),
  `SalesStatsUseCases.lookupNames`, `PlaceOnline/PlacePos/CommitPos*` (`findByTitleIds` + `catalog.lookup` một lần), `HttpCatalogLookupAdapter.lookup` (chia lô theo `MAX_IDS_PER_CALL`, không gọi từng id).
- identity: `SearchUsersUseCase`/`UserPersistenceAdapter.search` (fetch join `user`, trạng thái khóa tra theo lô `activeLocks`), `ExportUsersUseCase` (mã phòng ban và role theo lô),
  `ListUserRoleNamesUseCase`, `GetCredentialInfoUseCase`, `SearchTenantsUseCase` (đếm thành viên và module theo lô), `ListMySessionsUseCase` (một lần `available`), `TenantPersistenceAdapter.findAllByIds`, `ContractQueryUseCase.listByTenant` (`paidByContracts` theo lô).
- lms-api: `TitleNameEnricher` gom `titleId` thành một lần `HttpCatalogTitleClient.names` (tối đa 100 id); `DashboardAggregator` gọi song song 3 nguồn cố định; các client còn lại chỉ chuyển tiếp. customer-api chỉ chuyển tiếp từng yêu cầu, không ghép dữ liệu.
- support: `NotificationPersistenceAdapter`, `AuditLogPersistenceAdapter.search` (một truy vấn trang + một `count`).

## 2. Đã sửa và chưa sửa

### 2.1 Đã sửa

1. `StockPersistenceAdapter.refreshCoverPrices` ghi theo lô: một câu `UPDATE ... SET cover_price_snapshot = CASE WHEN title_id = ? THEN ? ... ELSE cover_price_snapshot END WHERE tenant_id = ? AND title_id IN (...)` cho mỗi 100 mặt hàng (500 mặt hàng: 5 câu thay cho 500). Hợp đồng và hành vi không đổi (bỏ qua giá âm/rỗng). Test: `CoverPriceRefreshIT` (đếm câu SQL qua Hibernate statistics và kiểm giá trị từng dòng).
2. `ContractQueryUseCase.expiring` tra tên/mã tenant bằng một truy vấn (`TenantModuleOperations.briefs` dùng `TenantRepository.findAllByIds`, adapter đã override bằng `findAllById`). Hành vi tenant không còn tồn tại (tên rỗng) giữ nguyên. Test: `ExpiringContractsBatchLookupTest`.

### 2.2 Chưa sửa: đăng nhập ngoài lặp theo tenant (#3)

`reader_profiles` chịu RLS theo tenant và KHÔNG có policy `platform_read_all` (V15 chỉ cho `roles`, `departments`), nên không có cách truy vấn một lần qua mọi tenant mà không đổi RLS.
Phương án đề xuất (cần quyết định, thuộc luồng đăng nhập nên không tự sửa): (a) thêm policy `platform_read_all` cho `reader_profiles` rồi truy vấn một lần ở phạm vi `all`;
hoặc (b) giữ vòng lặp nhưng chỉ lặp các tenant mà email đã có `tenant_memberships` (một truy vấn lấy tenant theo user có email đó). Trong lúc chưa đổi, index mới ở mục 3 làm mỗi lần lặp rẻ (không quét tuần tự).

### 2.3 Chưa sửa: nhập người dùng hàng loạt (#4)

Có thể gom ba kiểm tra trùng thành ba truy vấn `IN` cho cả file (thêm phương thức theo lô ở `UserRepository` và `AccountCredentialPort`). Lợi ích chính ở chế độ `dryRun` (từ ~1500 truy vấn xuống 3);
khi ghi thật, mỗi dòng còn nhiều thao tác ghi và kiểm tra riêng trong `ImportUserRowExecutor`/`CreateUserUseCase` nên lợi ích nhỏ hơn. Thao tác quản trị hiếm, giới hạn 500 dòng: để lại.

### 2.4 Chưa sửa: ghi tồn kho theo từng dòng (#6)

`tryApply` là `UPDATE` có điều kiện để giữ bất biến tồn (chống bán âm khi đồng thời), nên cố ý tách theo dòng và theo thứ tự UUID tăng dần (tránh deadlock). Có thể bỏ `SELECT id` của `append`
bằng cách chèn sổ cái qua truy vấn con, nhưng đổi cổng `StockPort` và các lớp giả trong test (`Harness`, `ReportingUseCaseTest`); lợi ích nhỏ so với rủi ro. Để lại.

### 2.5 Đề xuất cho catalog (KHÔNG tự sửa)

Không phát hiện chỗ nào buộc phải thêm endpoint theo lô ở catalog: sales và lms-api đã dùng `POST /internal/books/lookup` (theo danh sách id, tối đa 200 mỗi lần).

## 3. Index

Migration mới (chỉ DDL, tên `idx_<bảng>_<cột...>`, lý do trong comment SQL của từng file):

| File | Index | Truy vấn dùng |
|---|---|---|
| `sales/V11__list_and_job_indexes.sql` | `idx_goods_receipts_tenant_status_created (tenant_id, status, created_at DESC)` | `GoodsReceiptPersistenceAdapter.search` lọc theo trạng thái |
| | `idx_goods_receipts_tenant_supplier_created (tenant_id, supplier_id, created_at DESC)` | cùng truy vấn, lọc theo nhà cung cấp; khóa ngoại `supplier_id` chưa có index dẫn đầu |
| | `idx_sales_orders_pii_pending_cancelled (tenant_id, cancelled_at) WHERE pii_anonymized_at IS NULL AND cancelled_at IS NOT NULL` | `WalkInAnonymizationAdapter.findDue`, nhánh đơn đã huỷ (index cũ chỉ phủ `completed_at`) |
| `identity/V30__reader_profiles_tenant_email_index.sql` | `idx_reader_profiles_tenant_lower_email (tenant_id, lower(email))` | `SpringDataReaderProfileRepository.findUserIdsByEmail` (đăng nhập ngoài, chạy cho từng tenant) |
| `audit/V5__audit_list_and_unread_indexes.sql` | `idx_audit_logs_tenant_occurred (tenant_id, occurred_at DESC, id)` | `AuditLogPersistenceAdapter.search` khi không lọc (màn Nhật ký mặc định); V2 chỉ còn index có `aggregate_id`/`event_type` |
| | `idx_notifications_tenant_user_unread (tenant_id, user_id) WHERE read_at IS NULL` | `countByUserIdAndReadAtIsNull` (huy hiệu chưa đọc, gọi lặp) và `markAllRead` |

Đã đối chiếu và KHÔNG thêm (đủ index): `sales_orders` (tìm theo trạng thái, thanh toán, khách, ngày tạo, `paid_at`), `stock_ledger`, `stock_items` (storefront mới/giá, SKU), `sales_audit_log`, `shop_contact_requests`, `storefront_banners`, `refunds` theo trạng thái, `sales_returns`, `payment_records`, `menus` (`tenant_id, parent_id, sort_order`), `refresh_tokens` (phiên theo user/tenant còn hiệu lực, dọn theo `expires_at`), `user_roles`, `tenant_memberships`, `tenant_contracts`.

Đề xuất, CHƯA làm (cần số liệu hoặc không rõ lợi ích):
- `payment_intents`: index `idx_payment_intents_status_expires (status, expires_at)` không có `tenant_id` trong khi `findExpiredPendingIds` lọc `tenant_id = :t AND status = 'PENDING' AND expires_at <= :now`. Nên thay bằng `(tenant_id, expires_at) WHERE status = 'PENDING'` (nhỏ hơn); không đổi vì bảng thuộc tính năng QR/thanh toán của phiên khác đang sửa.
- Tìm kiếm chứa chuỗi (`lower(x) LIKE '%kw%'`) cho người dùng (username/họ tên/email), đơn hàng (`order_no`), nhà cung cấp (`name`): B-tree không phục vụ được dấu `%` ở đầu; cần `pg_trgm` (extension, quyết định riêng). Hiện bị giới hạn bởi `tenant_id` và phân trang.
- `refunds` không lọc: `ORDER BY created_at DESC` chưa có `(tenant_id, created_at DESC)`; số hoàn tiền ít nên chưa cần.
- `stock_items` danh sách quản trị (gồm cả mặt hàng ngừng bán) sắp `created_at DESC, id` chưa có index không-partial; index storefront chỉ phủ `WHERE active`. Chỉ đáng thêm nếu một tenant có hàng chục nghìn mặt hàng.
- `ListMyNotificationsUseCase` (`findByUserIdOrderByCreatedAtDescIdAsc`) trả TOÀN BỘ thông báo của người dùng, không phân trang. Không phải N+1 nhưng tăng vô hạn; đổi sang phân trang là đổi hợp đồng API, cần phối hợp phía FE.
- `system-service` (`cms_pages`, `cms_contact_requests`) ngoài phạm vi "menu": chưa rà.

## 4. Chưa kiểm chứng

- Không chạy EXPLAIN/ANALYZE trên dữ liệu thật; lợi ích của index là suy luận từ mã và định nghĩa. Đã kiểm trên một database tạm (đã xoá) của Postgres 16 đang chạy ở dev, KHÔNG đụng DB dev: `sales/V1..V11` và `audit/V1..V5` áp được liên tiếp không lỗi; `identity/V30` áp được lên bảng `reader_profiles` rút gọn và `EXPLAIN` chọn `idx_reader_profiles_tenant_lower_email`; với `enable_seqscan=off` trên bảng rỗng, `EXPLAIN` chọn `idx_audit_logs_tenant_occurred` và `idx_notifications_tenant_user_unread` (chỉ chứng minh planner dùng được index, không đo lợi ích). Nên chạy `EXPLAIN (ANALYZE, BUFFERS)` trên dữ liệu demo (`scripts\seed-demo.ps1`) cho 3 truy vấn: nhật ký audit không lọc, đếm chưa đọc, tìm email độc giả.
- Migration mới chưa áp lên DB dev đang chạy (cần khởi động lại `system-service`). Chuỗi `identity/V1..V30` không chạy liền được trong DB tạm vì V9 báo thiếu bảng `permission_key_map` khi nạp bằng psql (chưa tìm nguyên nhân, không liên quan thay đổi này).
- `ExternalSignInUseCase` (đăng nhập ngoài) không được thay đổi, không có test mới ở luồng đó.

## 5. Kết quả test

`mvn -o -pl services/sales-service,services/identity-service,services/support-service -am test` (không chạy `*IT`): BUILD SUCCESS, 0 lỗi. Số test theo module (thứ tự reactor): system-service 296, identity-service 674, support-service 76, sales-service 477 (gồm ArchUnit và WebMvc).
`CoverPriceRefreshIT` (Testcontainers Postgres 16, cô lập Kafka, `-Dapi.version=1.44`): 2/2 đạt, xác nhận 120 mặt hàng ghi trong tối đa 4 câu SQL, giá trị từng dòng đúng, giá âm bị bỏ qua, và 3 index của `sales/V11` đã tạo. Lần chạy đầu thất bại do container Postgres quá hạn khởi động (máy tải cao) và lần hai do chính IT gọi sai hàm quá tải (không có transaction), đã sửa IT.
Không chạy các `*IT` khác (identity/support/audit) vì không đổi mã ở đó ngoài `ContractQueryUseCase` và `TenantModuleOperations` (đã có test đơn vị).
