# API catalog: thông tin bổ sung của bản sao, tác giả, nhà xuất bản, vị trí kệ

Cập nhật 2026-11-18. Hợp đồng cho FE (nginx 8080 -> `lms-api` 8085 -> `catalog-service` `/internal/**`). Mọi trường mới đều TÙY CHỌN (null/vắng = chưa nhập), không backfill dữ liệu cũ. Migration: `services/system-service/.../db/migration/catalog/V8__catalog_extra_fields.sql` (chỉ DDL, có CHECK, RLS giữ nguyên). Cùng nguyên tắc với đầu sách (`docs/api/catalog-title-extra-fields.md`).

## Quy tắc PUT (quan trọng cho FE)

PUT các thực thể này là THAY TOÀN BỘ phần thông tin bổ sung, nhất quán với đầu sách: thiếu hoặc `null` hoặc chuỗi rỗng nghĩa là XÓA giá trị đó. Form sửa phải gửi lại đủ các trường đang hiển thị. Riêng `PUT /api/{authors|publishers|categories|shelf-locations}/{id}/status` và `PUT /api/book-copies/{id}/status|shelf-location` không đụng thông tin bổ sung.

Lỗi: sai ràng buộc cú pháp (độ dài, khoảng giá trị, enum, URL, ngày ở tương lai) trả `400 VALIDATION_FAILED` ở lms-api; sai quy tắc nghiệp vụ trả 422 với `code` của catalog (`INVALID_COPY`, `INVALID_AUTHOR`, `INVALID_PUBLISHER`, `INVALID_SHELF_LOCATION`). Khóa quyền hiện có không đổi.

## Bản sao (`/api/book-copies`)

Trường (JSON): `acquiredDate` (ISO `yyyy-MM-dd`, không ở tương lai), `acquisitionSource` (`PURCHASE`|`DONATION`|`EXCHANGE`|`OTHER`), `purchasePrice` (số nguyên VND >= 0, CHỈ hợp lệ khi `acquisitionSource` = `PURCHASE`, nếu không thì 422), `conditionGrade` (`NEW`|`GOOD`|`FAIR`|`WORN`), `conditionNote` (tối đa 300), `lastInventoryDate` (không ở tương lai, không trước `acquiredDate` nếu có cả hai).

- `POST /api/book-copies`: thêm sáu trường trên vào body hiện có (`titleId`, `barcode`, `shelfLocationId`). Body cũ vẫn hợp lệ.
- `PUT /api/book-copies/{id}/acquisition` (MỚI): thay toàn bộ sáu trường (body có thể là `{}` để xóa hết). Trả `CopyView`. Khóa quyền mới `BookCopyController/updateAcquisition` (tự đăng ký lần đầu được gọi; role không phải admin phải được gán, hiệu lực khi đăng nhập/refresh kế tiếp). Không đổi trạng thái mượn trả, không phát event Kafka.
- Response `CopyView` (POST, `GET /api/book-copies?titleId=`, `PUT .../status`, `PUT .../shelf-location`, `PUT .../acquisition`): thêm cuối `acquiredDate, acquisitionSource, purchasePrice, conditionGrade, conditionNote, lastInventoryDate` (null nếu chưa nhập). Tên đầy đủ: `id, titleId, barcode, status, tenantId, shelfLocationId, shelfLocationCode, acquiredDate, acquisitionSource, purchasePrice, conditionGrade, conditionNote, lastInventoryDate`.
- `GET /api/book-copies/{id}` và `/by-barcode/{barcode}` (`CopyDetailsView`, dùng cho mượn trả) KHÔNG đổi.

## Tác giả (`/api/authors`)

Trường: `birthYear` (1..năm hiện tại), `deathYear` (>= `birthYear` nếu có, <= năm hiện tại), `nationality` (<= 100), `photoUrl` (<= 500, http/https), `website` (<= 500, http/https). Có trong body POST/PUT và trong `ReferenceView` (GET danh sách, chi tiết, POST, PUT, status).

## Nhà xuất bản (`/api/publishers`)

Trường: `country` (<= 100), `foundedYear` (1400..năm hiện tại), `website` (<= 500, http/https), `address` (<= 500). Có trong body POST/PUT và trong `ReferenceView`.

Lưu ý `ReferenceView` dùng chung cho tác giả, nhà xuất bản, thể loại: các trường bổ sung CHỈ xuất hiện khi có giá trị (vắng = chưa nhập hoặc không thuộc loại này), khác với đầu sách trả `null`. Các khóa cũ (`id, name, description, parentId, parentName, active, tenantId`) giữ nguyên. Thể loại không có trường bổ sung. `SaveReferenceRequest` dùng chung: trường không thuộc loại của đường dẫn bị catalog bỏ qua. Ở catalog-service, tác giả và nhà xuất bản không còn trả `parentId`/`parentName` (luôn null trước đây); lms-api vẫn trả hai khóa này (null).

## Vị trí kệ (`/api/shelf-locations`)

Trường: `floor` (<= 20, ví dụ `Tầng 2`), `zone` (<= 50, ví dụ `Khu Văn học`), `capacity` (1..100000, số bản sao tối đa). Có trong body POST/PUT và `ShelfLocationView` (`id, code, description, active, tenantId, floor, zone, capacity`). `capacity` chỉ để hiển thị: chưa chặn gán quá sức chứa.

## Dữ liệu demo (ADR-0018)

`scripts\seed-demo.ps1 -Only catalog` nạp: 160 bản sao có ngày nhập, nguồn nhập, giá mua (55-70% giá bìa), tình trạng, ngày kiểm kê và gán vào 1 trong 16 kệ (Tầng 1/2, khu theo chủ đề, sức chứa 40-60); tác giả thật (năm sinh/mất, quốc tịch), nhà xuất bản thật (quốc gia, năm thành lập, website, địa chỉ). Dữ liệu minh họa gần đúng. Chạy lại trên tenant demo đã seed chỉ điền các trường còn null của bản ghi đã có (không đè giá trị đã sửa, không đổi trạng thái bản sao), nên không nhân đôi.
