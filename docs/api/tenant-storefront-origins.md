# Origin storefront của tenant (tenant admin, chỉ xem): `GET /api/storefront-origins`

Nền: ADR-0013 2.8. Tenant admin xem danh sách website (origin) mà platform admin đã khai báo cho tenant mình; không sửa được (đổi qua platform admin, xem `platform-storefront-origins.md`).

| Method | Đường dẫn (FE) | Đích (identity) | Ra |
|---|---|---|---|
| GET | `/api/storefront-origins` | `GET /internal/storefront-origins` | `200 { "origins": [ { "origin": "https://shop.example.com", "enabled": true } ] }` |

- Không nhận tham số: tenant lấy từ claim `tenantId` của token. Chỉ origin của tenant đó.
- Mảng theo thứ tự tạo, gồm cả dòng đang tắt (`enabled=false`). Chưa khai báo: `{ "origins": [] }`.
- DTO riêng, chỉ `origin` và `enabled`: không có `id`, `createdBy`, `updatedBy`, thời gian hay số tối đa (id người thao tác của platform admin không lộ cho tenant).
- Không có route ghi nào cho tenant (`POST/PUT/DELETE` trên đường dẫn này là `4xx`).
- Không yêu cầu module `SALES`: tenant đã tắt SALES vẫn xem được cấu hình.

## Phân quyền

Khóa động `TenantStorefrontOriginController/list` (ADR-0003; không `@RequirePermission`). Role có cờ `is_admin` mang `admin=true` nên xem được ngay, không cần gán. Người thường: gán khóa này cho role (khóa tự đăng ký khi endpoint được gọi lần đầu), có hiệu lực từ lần đăng nhập/refresh kế tiếp. Đổi tên controller/method đổi khóa.

| Tình huống | Kết quả |
|---|---|
| Không token | `401` |
| Token không `tenantId` (platform admin) | `403 TENANT_REQUIRED` |
| Người thường thiếu khóa | `403` |
| Tenant admin hoặc có khóa | `200` |

## Lời nhắc hiển thị cho FE

Hiển thị câu giới hạn cam kết: danh sách này chỉ kiểm soát CORS của trình duyệt, không phải xác thực và không chống bot hay `curl`; thay đổi có hiệu lực trong khoảng 1 phút. Ẩn trang khi nhận `403`.
