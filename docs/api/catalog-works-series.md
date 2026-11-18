# API tác phẩm (works) và bộ sách (series) (ADR-0026, pha 1)

Hợp đồng nội bộ của `catalog-service` (`/internal/**`); hợp đồng cho FE ở `lms-api` (`/api/**`, pha 2d) nằm ở mục "API lms-api" cuối tài liệu. Quy ước từ vựng: `Title`/`titleId`/"book" = ấn bản; `Work`/`workId` = tác phẩm; `Series`/`seriesId` = bộ sách.

## Tác phẩm `/internal/works`

| Phương thức | Khóa quyền | Mô tả |
|---|---|---|
| `GET /internal/works` (`@CrossTenantReadable`) | `WorkController/search` | query `{keyword, seriesId, categoryId, authorId, page (từ 0), size (1..100, mặc định 20)}` -> `{items: WorkSummary[], total, page, size}`; sắp theo tên rồi id |
| `GET /internal/works/{id}` | `WorkController/get` | `WorkDetail` kèm `editions[]` |
| `POST /internal/works` | `WorkController/create` | `201 WorkDetail` |
| `PUT /internal/works/{id}` | `WorkController/update` | thay toàn bộ; ghi gương thể loại/bộ sách/tác giả sang mọi ấn bản |
| `POST /internal/works/{id}/merge` | `WorkController/merge` | body `{sourceWorkIds[] (1..20)}`; gộp vào tác phẩm `{id}` (đích), trả `WorkDetail` đích |

Body tạo/sửa: `{title (bắt buộc, <=255), originalTitle, originalLanguage ([a-z]{2,8}), author, authorIds[<=20], categoryId, seriesId, seriesNumber (>=1, chỉ khi có seriesId), seriesLabel (<=50), description (<=500), kind (SINGLE|COLLECTION, mặc định SINGLE)}`. `author` trống mà có `authorIds` thì ghép tên tác giả chuẩn; cả hai trống: `400 INVALID_WORK`.

`WorkSummary`: `{id, title, originalTitle, author, seriesId, seriesName, seriesNumber, kind, editionCount, availableCopies (mọi ấn bản, mọi ngôn ngữ), coverImageUrl (ấn bản đầu có ảnh)}`.
`WorkDetail`: `{id, title, originalTitle, originalLanguage, author, authors[{id,name}], description, categoryId, categoryName, seriesId, seriesName, seriesNumber, seriesLabel, kind, tenantId, editions[{titleId, isbn, name, format, language, translator, edition, publicationYear, partNumber, partLabel, coverPrice, coverImageUrl, availableCopies}]}`.

Lỗi: `404 WORK_NOT_FOUND`; `400 INVALID_WORK` (vi phạm quy tắc, hoặc body `changeWork` sai); `409 WORK_MERGE_SELF` (nguồn chứa đích); `404 CATEGORY_NOT_FOUND|AUTHOR_NOT_FOUND|SERIES_NOT_FOUND`, `422 *_INACTIVE` của tham chiếu; `400 VALIDATION_FAILED`.
Không cache. Không `@StorefrontAccessible`.

## Bộ sách `/internal/series`

Cùng khuôn danh mục tác giả (`docs/api/catalog-reference.md`): `GET /internal/series` (`SeriesController/search`, query `keyword, active, page, size`), `GET /internal/series/options` (`SeriesController/options`), `GET /{id}` (`get`), `POST` (`create`), `PUT /{id}` (`update`), `PUT /{id}/status` (`setStatus`). Body tạo/sửa `{name (<=255), description (<=500)}`; response `{id, name, description, active, tenantId}`. Mã lỗi: `SERIES_NOT_FOUND`, `DUPLICATE_SERIES_NAME`, `SERIES_INACTIVE`, `INVALID_SERIES`. Cache 60 giây theo tenant (namespace `catalog.series`), ghi thì xóa.

## Khóa quyền mới (12) và migration

`WorkController/{search,get,create,update,merge}`, `TitleController/changeWork`, `SeriesController/{search,options,get,create,update,setStatus}`. Seed ở identity `V34__work_series_permissions.sql` (V32 đã bị `applications` chiếm, V33 dành cho đền bù): mẫu LIBRARY_ADMIN, LIBRARIAN, STORE_MANAGER nhận đủ; mẫu và role tenant đang có `TitleController/search|get` nhận khóa ĐỌC (`WorkController/search|get`, `SeriesController/search|options|get`); khóa ghi không backfill. Role đang chạy chỉ có hiệu lực từ lần đăng nhập/refresh kế tiếp.

## Dữ liệu và migration catalog

`V10__works_series_expand.sql`: bảng `series`, `works`, `work_authors` (RLS), cột `titles.work_id` (NULL ở pha expand), `part_number`, `part_label`; backfill mỗi đầu sách thành một tác phẩm 1:1 với id `md5('lms-work:'||title_id)::uuid`. Chưa có bước contract (`work_id NOT NULL`); tác phẩm chưa gắn (`workId = null`) vẫn hợp lệ ở mọi phản hồi cho tới lúc đó.

## API lms-api (`/api/**`, pha 2d)

BFF chuyển nguyên sang `/internal/**` của catalog-service (cùng thân, cùng mã lỗi, chuyển `Authorization`, `X-Tenant-Id`, `X-Correlation-Id`); quyền do catalog kiểm theo khóa ở trên, `lms-api` không kiểm khóa. Cần đăng nhập. Mọi trường mới chỉ THÊM, FE cũ không bị vỡ.

| FE gọi | Chuyển tới | Khóa (catalog) | Ghi chú |
|---|---|---|---|
| `GET /api/works` | `GET /internal/works` | `WorkController/search` | query `{keyword, seriesId, categoryId, authorId, page, size}` |
| `GET /api/works/{id}` | `GET /internal/works/{id}` | `WorkController/get` | kèm `editions[]` |
| `POST /api/works` | `POST /internal/works` | `WorkController/create` | `201` + `Location: /api/works/{id}` |
| `PUT /api/works/{id}` | `PUT /internal/works/{id}` | `WorkController/update` | thay toàn bộ, ghi gương sang mọi ấn bản |
| `POST /api/works/{id}/merge` | `POST /internal/works/{id}/merge` | `WorkController/merge` | body `{sourceWorkIds[] (1..20)}`, trả tác phẩm đích |
| `PUT /api/books/{id}/work` | `PUT /internal/books/{id}/work` | `TitleController/changeWork` | body đúng một trong `{workId}` (gắn vào tác phẩm có sẵn) hoặc `{newWork: {...SaveWork}}` (tách ra tác phẩm mới); trả `TitleView` |
| `GET /api/series`, `/options`, `/{id}` | `/internal/series...` | `SeriesController/search`, `options`, `get` | cùng khuôn `/api/authors` |
| `POST /api/series`, `PUT /api/series/{id}`, `PUT /api/series/{id}/status` | tương ứng | `SeriesController/create`, `update`, `setStatus` | body `{name, description}`, `{active}`; response `ReferenceView` (`parentId`, `parentName` luôn null với bộ sách) |
| `/api/catalog/imports/series/**` | `/internal/imports/series/**` | `SeriesImportController/upload`, `confirm` | xem `catalog-import.md` |

Body/response của `/api/works` giống mục "Tác phẩm" ở trên (`kind` là chuỗi `SINGLE|COLLECTION`). Lỗi `400 VALIDATION_FAILED` do `lms-api` bắt trước khi chuyển (tên trống, `kind` ngoài danh sách, `sourceWorkIds` rỗng hoặc quá 20, `newWork.title` trống).

Trường mới trên các DTO sẵn có (đều tùy chọn, null nếu ấn bản chưa gắn tác phẩm):
- `TitleView` (`GET/POST/PUT /api/books[/{id}]`, `PUT /api/books/{id}/work`): `workId`, `workTitle`, `originalTitle`, `originalLanguage`, `seriesId`, `seriesName`, `partNumber`, `partLabel`, `workAvailableCopies` (bản sao sẵn có của mọi ấn bản cùng tác phẩm CÙNG ngôn ngữ). `series`, `seriesNumber`, `categoryId`, `authors` vẫn trả, giá trị lấy từ tác phẩm.
- `CreateTitleRequest`, `UpdateTitleRequest`: thêm `workId` (chỉ tạo; không gửi = tạo tác phẩm mới 1:1), `partNumber`, `partLabel`. Lưu ý: sửa thể loại, tác giả chuẩn, bộ sách trên một ấn bản đổi cho MỌI ấn bản cùng tác phẩm.
- `GET /api/books`: query record `SearchBooksQuery` thêm `workId`, `seriesId` (lọc ấn bản).
- `CopyDetailsView` (`GET /api/book-copies/{id}`, `/by-barcode/{barcode}`): thêm `workId` (tác phẩm của ấn bản chứa bản sao).
- `CopyView` (`/api/book-copies`) không đổi.

## Sự kiện inventory.events

- `CopyAvailableEvent`: `eventId, copyId, titleId, occurredAt, workId, language`. `workId` null nếu ấn bản chưa gắn tác phẩm; `language` là ngôn ngữ ấn bản, null nếu chưa khai. Phát khi nhập bản sao (kể cả đường lô), nhả giữ, trả về, và khi `LOST -> AVAILABLE` (hoàn khoản tìm lại sách). Consumer cũ bỏ qua trường mới.

## Dữ liệu demo theo mô hình tác phẩm (pha 2f, ADR-0018 và ADR-0026)

Không đổi API, không đổi khóa quyền, không có migration mới. `scripts\seed-demo.ps1` (tenant `demo`) nay nạp:

- 64 ấn bản gốc GIỮ NGUYÊN id (`DemoIds.title(n)`); mỗi cái là tác phẩm 1:1 có id `md5('lms-work:'||title_id)::uuid` (khớp backfill của catalog V10, circulation V13, sales V14).
- 7 ấn bản bổ sung (id `DemoIds.title(64..70)`, mỗi cái một bản sao AVAILABLE và một mặt hàng tồn riêng): Harry Potter tập 1 bìa cứng (vi) và bản gốc tiếng Anh, "Đắc nhân tâm" ebook, "Thói quen nguyên tử" (bản dịch của "Atomic Habits"), "Những người khốn khổ" phần 2 (ấn bản gốc là "Phần 1/2", `partNumber` 1 và 2), "Sapiens" tiếng Anh, và một ấn bản bộ hộp `Harry Potter – Bộ hộp tập 1-2` thuộc tác phẩm `kind = COLLECTION` (id `DemoIds.work(0)`, cùng bộ "Harry Potter", không có số tập).
- 65 tác phẩm, 6 bộ sách có số tập (Head First, Lịch sử Việt Nam bằng tranh, Harry Potter, Doraemon, Thám tử lừng danh Conan, Kính vạn hoa; mỗi tập một tác phẩm, `seriesLabel` "Tập N"). Một số tác phẩm có `originalTitle`/`originalLanguage` (Harry Potter, Les Misérables, Đắc nhân tâm, Sapiens...).
- Sales: `stock_items.work_id` ghi theo kế hoạch cho mọi mặt hàng (39 mặt hàng; ấn bản cùng tác phẩm cùng `work_id`, nên storefront gộp thành một nhóm có biến thể).
- Circulation: 6 đặt trước READY có `work_id` và `language` (ngôn ngữ ấn bản); thêm 5 đặt trước theo tác phẩm ở trạng thái WAITING (Harry Potter tập 1 chọn `vi` và `en`, "Những người khốn khổ" và "Đắc nhân tâm" chọn mọi ngôn ngữ, "Atomic Habits" chọn `vi`), mỗi (độc giả, tác phẩm) một dòng.

Chạy lại không nhân đôi, theo quy tắc điền-chỗ-trống của ADR-0018: tác phẩm, ấn bản, bộ sách đã có chỉ được điền các trường còn null (bộ sách backfill cùng tên được dùng lại, không tạo trùng); `titles.work_id`/nhãn phần chỉ điền khi còn trống; đặt trước READY cũ chỉ được điền `language` nếu còn null. Ngoại lệ: `stock_items.work_id` được ghi lại theo kế hoạch mỗi lần chạy (snapshot dẫn xuất, storefront tự sửa nếu catalog trả `workId` khác).
