# Phiên đa thiết bị (ADR-0017)

Hợp đồng `/api/**` (lms-api) cho FE. Thiết kế: `docs/decisions/0017-phien-da-thiet-bi.md`. Mọi phản hồi chứa IP đều `Cache-Control: no-store`.

## 1. Thiết bị đăng nhập của tôi

Cần đăng nhập (không có khóa quyền ADR-0003). Chủ thể là `sub` của token, không nhận `userId`. Phạm vi: mọi tenant, mọi kênh (STAFF và STOREFRONT) của tài khoản. Token `svc` bị 403.

| Method | Đường dẫn | Vào | Ra |
|---|---|---|---|
| GET | `/api/auth/sessions` | không | `200 SessionView[]` (sắp `lastUsedAt` giảm dần) |
| DELETE | `/api/auth/sessions/{sessionId}` | path UUID | `204` |
| POST | `/api/auth/sessions/revocation` | `{ "keepCurrent": boolean }` (bắt buộc, thiếu thì 400 `VALIDATION_FAILED`) | `200 { "revokedSessions": number }` |

```ts
type SessionView = {
  sessionId: string;            // UUID
  channel: "STAFF" | "STOREFRONT";
  tenantId: string | null;      // null = phiên nền tảng
  tenantName: string | null;
  deviceSummary: string | null; // "Chrome trên Windows", "Không rõ"
  ipAddress: string | null;     // IP đầy đủ, chỉ chủ phiên thấy ở API này
  startedAt: string;            // ISO-8601
  lastUsedAt: string;
  expiresAt: string;
  current: boolean;             // đúng phiên của token đang gọi
};
```

- `keepCurrent=true`: thu hồi mọi phiên trừ phiên hiện tại. `false`: thu hồi tất cả kể cả hiện tại (FE tự xóa token cục bộ sau đó). Xóa chính phiên hiện tại bằng DELETE tương đương đăng xuất.
- Lỗi: `404 SESSION_NOT_FOUND` (không có, không phải của mình, hoặc đã kết thúc); `409 SESSION_NOT_IDENTIFIED` (`keepCurrent=true` nhưng access token không có claim `sid`, tức token phát trước khi triển khai: hết tối đa sau 15 phút, hoặc refresh để lấy token mới rồi gọi lại).

## 2. Quản trị viên xem và thu hồi phiên của người dùng (trong tenant)

| Method | Đường dẫn | Khóa quyền (cho `packages/api/src/permissions.ts`) | Ra |
|---|---|---|---|
| GET | `/api/users/{id}/sessions` | `UserController/listSessions` | `200 UserSessionView[]` |
| DELETE | `/api/users/{id}/sessions/{sessionId}` | `UserController/revokeSession` | `204` |
| POST | `/api/users/{id}/sessions/revocation` (đã có) | khóa hiện có | `200 { "revokedSessions": number }` |

```ts
type UserSessionView = {
  sessionId: string; channel: "STAFF" | "STOREFRONT";
  deviceSummary: string | null; ipAddress: string | null; // IP đầy đủ
  startedAt: string; lastUsedAt: string; expiresAt: string;
};
```

- Lỗi: `404 SESSION_NOT_FOUND`, `404 USER_NOT_FOUND`, `403 USER_OPERATION_NOT_ALLOWED` (không đủ thẩm quyền với người đó, ví dụ admin), `403 TENANT_REQUIRED`, `403` thiếu khóa quyền.
- Mỗi lần xem danh sách phiên của người khác đều bị ghi audit. FE KHÔNG tự làm mới định kỳ (không polling, không refetch khi focus); chỉ tải khi người dùng mở màn hình hoặc bấm "Tải lại".
- Hai khóa mới chưa gán cho role nào thì người không phải admin nhận 403; gán trong màn hình role, có hiệu lực ở lần đăng nhập/refresh kế tiếp.

## 3. Mã lỗi refresh mới

`POST /api/auth/refresh` (và `/public/auth/refresh` của storefront) có thể trả `401 REFRESH_TOKEN_SUPERSEDED`: refresh token vừa được xoay vòng trong ân hạn 30 giây (thường do tab khác vừa làm mới). Không phải dấu hiệu bị tấn công và KHÔNG được đăng xuất. Ở storefront thông điệp là "Phiên vừa được làm mới ở nơi khác, vui lòng thử lại".

## 4. Lưu ý cho FE

1. **Single-flight giữa các tab**: bọc `refreshSession()` bằng `navigator.locks.request("lms-refresh", ...)`. Sau khi giữ khóa, đọc lại `lms_rt` từ `localStorage` ngay trước khi gọi refresh (tab khác có thể vừa ghi token mới); nếu token đã đổi so với lúc vào hàng đợi thì dùng token mới, không gọi refresh nữa. Trình duyệt không có Web Locks thì giữ hành vi cũ, dựa vào ân hạn.
2. **`401 REFRESH_TOKEN_SUPERSEDED`**: đọc lại `lms_rt`; nếu khác token vừa gửi thì thử lại đúng một lần với token mới; nếu giống thì coi là hết phiên (xóa phiên). Không đăng xuất ngay.
3. **Phiên bị thu hồi từ xa**: refresh trả `401 UNAUTHORIZED` thì xóa phiên và về trang đăng nhập với thông điệp chung "Phiên đăng nhập đã kết thúc" (BE không phân biệt bị đăng xuất từ xa với hết hạn).
4. **Access token đã phát còn dùng được tối đa 15 phút** sau khi phiên bị thu hồi; thu hồi chỉ chặn làm mới. Ghi chú này trên giao diện ("thiết bị kia có thể còn truy cập tối đa 15 phút").
5. Màn hình "Thiết bị đăng nhập": hiển thị `deviceSummary`, `ipAddress`, tenant/kênh, `startedAt`, `lastUsedAt`, nhãn "Thiết bị này" theo `current`; nút đăng xuất từng phiên, "Đăng xuất các thiết bị khác" (`keepCurrent=true`), "Đăng xuất tất cả" (`keepCurrent=false`, rồi xóa token cục bộ).
6. Không cần đổi gì ở đăng nhập/refresh/logout (hình dạng không đổi). Header `X-Client-Ip`/`X-Client-User-Agent` do lms-api tự gắn, FE không gửi.

## 5. Ghi chú vận hành (lms-api)

- IP client: lms-api chỉ tin `X-Real-IP` (nginx đặt bằng `$remote_addr` trong `snippets/proxy.conf`) khi kết nối tới từ proxy tin cậy `lms.api.client-ip.trusted-proxies` (biến môi trường `LMS_API_CLIENT_IP_TRUSTED_PROXIES`, mặc định chỉ loopback); không đọc `X-Forwarded-For`. Chạy nginx trong Docker cần đặt biến này thành dải mạng Docker (ví dụ `172.16.0.0/12`), nếu không IP phiên là IP của container nginx.
- customer-api chưa gắn IP/UA cho phiên storefront (phiên storefront hiện không có IP/thiết bị trừ khi phía sau tự thêm).
