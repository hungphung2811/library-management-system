# API menu (lms-api -> system-service)

Nguồn: ADR-0007. FE chỉ gọi `/api/**` qua nginx (8080); `lms-api` chuyển tiếp 1-1 sang `/internal/menus/**` của system-service (cổng 8086, cấu hình `SYSTEM_SERVICE_URL`), kèm Bearer, `X-Correlation-Id`. Tenant lấy từ claim `tenantId` trong JWT được chuyển tiếp.

## Endpoint

| Method | Đường dẫn FE | Chuyển tiếp tới | Quyền |
|---|---|---|---|
| GET | `/api/menus/me` | `/internal/menus/me` | Chỉ cần đăng nhập |
| GET | `/api/admin/menus` | `/internal/menus` | Khóa động |
| POST | `/api/admin/menus` | `/internal/menus` | Khóa động |
| PUT | `/api/admin/menus/{id}` | `/internal/menus/{id}` | Khóa động |
| DELETE | `/api/admin/menus/{id}` | `/internal/menus/{id}` | Khóa động |
| POST | `/api/admin/menus/defaults` | `/internal/menus/defaults` | Khóa động |

### Khóa quyền cho FE/quản trị

`lms-api` không kiểm quyền (ADR-0005) và không dùng `PermissionInterceptor`; system-service kiểm trên token được chuyển tiếp. Vì vậy khóa là của controller trong system-service (đã đối chiếu code thật), không phải của controller lms-api (`MenuController`/`MenuAdminController` ở lms-api không sinh khóa):

| Hành động | Khóa |
|---|---|
| Xem cây menu quản trị | `MenuAdminController/listMenus` |
| Tạo | `MenuAdminController/createMenu` |
| Sửa/di chuyển | `MenuAdminController/updateMenu` |
| Xóa | `MenuAdminController/deleteMenu` |
| Chép menu mặc định | `MenuAdminController/initializeDefaults` (mới) |
| Menu của tôi | `MenuController/myMenu`, `@AuthenticatedOnly` (không cần gán) |

Khóa chỉ xuất hiện trong danh mục permissions sau khi endpoint được gọi lần đầu. Quản trị viên (`admin=true`) dùng được ngay; role khác cần được gán các khóa trên (khóa `initializeDefaults` chỉ đăng ký sau lần gọi đầu) và đăng nhập/refresh lại để có hiệu lực. Không đổi tên controller/method của system-service (đổi là mất quyền đã gán).

## GET /api/menus/me

`200` mảng cây `MenuNode` (cha có `children`, nút lá có `children: []`), đã lọc theo quyền của người gọi, đã bỏ mục tắt, sắp theo `sortOrder`:

```json
[{"id":"uuid","code":"circulation","label":"Lưu thông","path":null,"icon":"swap",
  "children":[{"id":"uuid","code":"loans","label":"Mượn trả","path":"/loans","icon":null,"children":[]}]}]
```

Cây còn được lọc theo module tenant đang thuê (claim JWT `modules`, ADR-0009): mục gắn module (`LENDING`/`SALES`) mà tenant chưa bật thì ẩn cả nhánh, kể cả với admin; mục lõi (không module) luôn hiện. Token cũ thiếu hẳn claim `modules` được coi là `[LENDING]` (cờ `lms.security.permission.legacy-modules-fallback`, mặc định bật). Bật/tắt module có hiệu lực ở lần gọi sau khi token được cấp lại (đăng nhập/refresh/switch-tenant). Response không đổi hình dạng.

Tenant chưa có dòng menu nào nhận bộ menu mặc định trong code (Trang chủ, Sách, Mượn trả [LENDING], Quản trị [cần khóa `AccessControlController/listRoles`], nhánh Bán hàng [SALES]) cũng lọc theo module và quyền; tenant đã có dữ liệu riêng thì chỉ dùng dữ liệu đó (không trộn). Id của mục mặc định ổn định giữa các lần gọi nhưng sẽ đổi sau khi chép thành dữ liệu tenant.

Không trả `permissionKey`, `enabled`. `path` là route nội bộ của FE (bắt đầu bằng `/`), có thể `null` ở nút nhóm.

## GET /api/admin/menus

⚠️ THAY ĐỔI HỢP ĐỒNG (phá vỡ): trước đây trả mảng, nay trả đối tượng `{usingDefaults, items}`. `items` là danh sách PHẲNG (cha đứng trước con, gồm cả mục đang tắt), KHÔNG có `children`; FE dựng cây theo `parentId` (gốc có `parentId = null`). Mỗi phần tử:
`{id, code, label, path, icon, parentId, sortOrder, enabled, permissionKey, version, updatedAt, module}` (`module` mới: `null` = mục lõi, hoặc `LENDING`/`SALES`).

`usingDefaults = true` khi tenant chưa có dòng menu nào (khi đó `items` rỗng và `GET /api/menus/me` đang dùng bộ mặc định). FE nên hiện nút "Khởi tạo menu từ mặc định" gọi `POST /api/admin/menus/defaults`.

## POST /api/admin/menus/defaults

Không body. Chép bộ menu mặc định (10 mục, gồm nhánh Bán hàng gắn `module = SALES`) thành dữ liệu của tenant, một lần: `201 {usingDefaults: false, items: [...]}` (cùng dạng `GET /api/admin/menus`). Tenant đã có bất kỳ dòng menu nào: `409 MENU_ALREADY_INITIALIZED`. Gọi đồng thời hai lần: lần sau có thể nhận `409 MENU_CONFLICT`. Sau bước này tenant admin sửa menu bằng các API bên dưới.

## POST /api/admin/menus

Body: `{code (bắt buộc), label (bắt buộc), parentId?, path?, icon?, sortOrder? (mặc định 0), enabled? (mặc định true), permissionKey?, module? (LENDING|SALES, bỏ trống = mục lõi)}` -> `201 AdminNode` kèm `Location: /api/admin/menus/{id}`. Độ dài tối đa: code 100, label 200, path 500, icon 100, permissionKey 255.

## PUT /api/admin/menus/{id}

Body: `{label (bắt buộc), sortOrder (bắt buộc), enabled (bắt buộc), version (bắt buộc), parentId?, path?, icon?, permissionKey?, module?}` (PUT thay thế toàn bộ: bỏ `module` là đặt về mục lõi) -> `200 AdminNode` (cùng giới hạn độ dài). `code` không đổi được; đổi `parentId` là di chuyển nút. `version` là giá trị đọc được lần trước; lệch thì `409 MENU_VERSION_CONFLICT` (FE tải lại rồi sửa tiếp).

## DELETE /api/admin/menus/{id}

`204` không nội dung. Nút còn con -> `409 MENU_HAS_CHILDREN`.

## Lỗi

Định dạng `ErrorResponse` `{code, message, details}`; lms-api giữ nguyên mã HTTP và nội dung 4xx của system-service:

| HTTP | code |
|---|---|
| 400 | `VALIDATION_FAILED` (lms-api kiểm trước: thiếu trường bắt buộc/quá độ dài), `VALIDATION_ERROR` (system-service, `details` là danh sách `"field: message"`; FE xử lý cả hai mã như lỗi form), `MENU_INVALID`, `MENU_CYCLE`, `MENU_TOO_DEEP`, `MENU_PATH_INVALID`, `MENU_PERMISSION_KEY_INVALID`, `MENU_LIMIT_EXCEEDED`, `MENU_MODULE_INVALID` (module không thuộc `LENDING`/`SALES`) |
| 401 | Không có/hết hạn token |
| 403 | Thiếu khóa quyền (API quản trị); `TENANT_REQUIRED` khi token không có tenant (API quản trị) |
| 404 | `MENU_NOT_FOUND`, `MENU_PARENT_NOT_FOUND` |
| 409 | `MENU_CODE_DUPLICATED`, `MENU_HAS_CHILDREN`, `MENU_VERSION_CONFLICT`, `MENU_ALREADY_INITIALIZED`, `MENU_CONFLICT` (xung đột ghi đồng thời, tải lại rồi thử lại) |
| 502 | `DEPENDENCY_ERROR` (system-service lỗi 5xx) |
| 503 | `DEPENDENCY_UNAVAILABLE` (không gọi được system-service) |

## FE thay `MFE_NAV_LINKS`

Hiện `Nav.tsx` đọc env `MFE_NAV_LINKS` (`[{href, label, permission?}]`) rồi lọc theo `session.admin || permissions.includes(permission)`. Thay bằng:

1. Sau đăng nhập/refresh/switch-tenant gọi `GET /api/menus/me`, render thẳng cây trả về (`href = path`, `label`, `icon`, `children`). Không cần lọc quyền ở FE: BE đã lọc theo token.
2. Nút có `path = null` chỉ là nhóm (không điều hướng); ẩn nhóm nếu không còn con (BE đã cắt nhóm rỗng, FE chỉ cần xử lý `children: []` ở lá).
3. Gọi lại menu khi đổi tenant hoặc làm mới token (quyền đổi theo token). Ẩn menu chỉ là trải nghiệm, quyền thật vẫn do API kiểm.
4. Màn quản trị menu dùng `/api/admin/menus` (danh sách phẳng, tự dựng cây theo `parentId`) (chỉ hiển thị với admin hoặc người có khóa `MenuAdminController/*`).

## Hành vi đặc biệt

- Menu rỗng (người gọi không thấy mục nào; tenant chưa có mục nào thì nhận menu mặc định, không rỗng): `GET /api/menus/me` -> `200 []`. FE nên có fallback (ví dụ chỉ hiện trang chủ) và không coi là lỗi.
- Token không có `tenantId` (platform admin): `GET /api/menus/me` -> `200 []`; API quản trị -> `403 TENANT_REQUIRED`.
- Redis lỗi/chết: cache menu chỉ là tối ưu; system-service đọc thẳng DB nên API vẫn trả `200` (chậm hơn). Sửa menu phá cache; nếu phá cache thất bại, dữ liệu cũ tối đa 10 phút (TTL).
- system-service chết: `/api/menus/me` -> `503 DEPENDENCY_UNAVAILABLE`; FE nên giữ menu đã tải gần nhất hoặc fallback.
