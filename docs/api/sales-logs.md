# Log vận hành sales (lưu DB): `/api/sales/logs/**` (lms-api)

Nền: ADR-0029 (`docs/decisions/0029-log-tich-hop-va-dau-hieu-bat-thuong.md`), `docs/architecture/log-tich-hop-va-bat-thuong.md`. Dành cho màn hình FE có 2 tab: Tích hợp (gọi ra + gọi vào) và Hệ thống.

## Cách hoạt động

- Dòng log kênh `INTEGRATION_OUTBOUND` (`lms.log.outbound.<provider>`), `INTEGRATION_INBOUND` (`lms.log.inbound.<nguồn>`) và sự kiện chọn lọc kênh `SYSTEM` (`lms.log.system`) của sales-service vẫn ra stdout như cũ, đồng thời được một Logback appender phân tích và ghi theo lô vào bảng `integration_log_events` của `sales_db` (migration sales `V18`). Chỉ lưu các trường đã che sẵn của `IntegrationLog` (không thân request/phản hồi, không token, không số điện thoại; mã tham chiếu đã che dạng `ORD*****1234`). Khóa lạ hoặc giá trị ngoài danh sách ký tự an toàn bị bỏ.
- Kênh `SYSTEM` chỉ lưu dòng từ mức `WARN` trở lên hoặc có `anomalyType` (`lms.sales.logs.persist.system-min-level`).
- Ghi không chặn nghiệp vụ: hàng đợi có giới hạn (5000), đầy thì bỏ dòng (có đếm, log cảnh báo tối đa 1 lần/phút), lỗi DB chỉ log. Log phục vụ giám sát, không phải sổ cái: có thể mất dòng khi quá tải hoặc khi tiến trình tắt đột ngột.
- Giữ 30 ngày (`lms.sales.logs.retention-days`); job `OperationalLogPurgeJob` xóa theo lô lúc 03:15 mỗi ngày (`lms.sales.logs.purge.enabled`, `.cron`, `.batch-size`). Tắt thu thập: `lms.sales.logs.persist.enabled=false` (đã tắt ở profile `demo-seed` và trong test).
- `lms-api` chuyển 1-1 `/api/sales/logs/**` sang `/internal/sales/logs/**`; khóa quyền, module `SALES` và tenant kiểm ở sales-service.

## Endpoint và khóa quyền

| Method, đường dẫn (BFF) | Khóa quyền | Kết quả |
|---|---|---|
| GET `/api/sales/logs/integration` | `OperationalLogController/integration` | Trang log kênh tích hợp (`INTEGRATION_OUTBOUND` và `INTEGRATION_INBOUND`) |
| GET `/api/sales/logs/system` | `OperationalLogController/system` | Trang log kênh `SYSTEM` (tham số `channel` bị bỏ qua) |

Khóa chỉ được đăng ký (migration identity `V42`), KHÔNG gán sẵn cho role mẫu nào: quản trị tenant (`is_admin`) có sẵn, role khác phải được gán tay trong màn phân quyền. Đổi tên controller/method là đổi khóa.

## Query (cả hai endpoint)

| Tham số | Ý nghĩa |
|---|---|
| `channel` | Chỉ tab tích hợp: `OUTBOUND` hoặc `INBOUND`; vắng = cả hai; giá trị khác `400 INVALID_REQUEST` |
| `provider` | Khớp đúng nhà cung cấp/nguồn (`momo`, `sepay`...) |
| `outcome` | Khớp đúng kết quả (`SUCCESS`, `FAILURE`...) |
| `level` | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` (không phân biệt hoa thường); khác `400 INVALID_REQUEST` |
| `anomalyOnly` | `true` = chỉ dòng có `anomalyType` |
| `from`, `to` | ISO-8601 (`2026-11-01T00:00:00Z`); `from` bao gồm, `to` loại trừ. Vắng `to` = bây giờ; vắng `from` = `to` trừ 7 ngày. Khoảng tối đa 31 ngày, `from` phải trước `to`, vi phạm `400 INVALID_RANGE` |
| `tenantId` | Chỉ platform admin (xem Phạm vi tenant); người khác bị bỏ qua |
| `page`, `size` | `page` từ 0; `size` mặc định 20, tối đa 100 |

## Phản hồi

`{ items: [...], total, page, size }`, mới nhất trước (`occurredAt` giảm dần). Mỗi item:

`{ id, occurredAt, channel, provider, event, level, op, outcome, httpStatus, durationMs, errorKind, errorClass, anomalyType, correlationId, intentId, reference, tenantId }`

- `channel`: `INTEGRATION_OUTBOUND` | `INTEGRATION_INBOUND` | `SYSTEM`; `event`: `integration-call` | `integration-receive` | `system-event`; `level`: mức log.
- `provider`, `op`, `outcome`, `httpStatus`, `durationMs`, `errorKind`, `errorClass`, `anomalyType`, `correlationId`, `intentId`, `reference` có thể `null` (dòng log không có trường đó). `correlationId` khớp header `X-Correlation-Id` để lần theo request.
- `tenantId` là `null` với dòng chưa biết tenant (ví dụ webhook chưa tra được tenant); chỉ platform admin thấy các dòng này.
- Các trường phụ (`errorCode`, `partnerCode`, `retryAfter`, `suppressed`, `source`, `failures`, `signaturePresent`, `bodyLength`, `clientIp` đã che) lưu ở cột `extra` nhưng CHƯA trả qua API.

## Phạm vi tenant

- Người dùng tenant: chỉ log của tenant trong token (RLS `FORCE` theo `app.tenant_id` cộng lọc tường minh); `tenantId` trong query và `X-Tenant-Id` không có tác dụng.
- Platform admin (ADR-0008/0016): hai endpoint gắn `@CrossTenantReadable`. Không có `tenantId`/`X-Tenant-Id` thì thấy log mọi tenant kể cả dòng `tenantId = null`; có query `tenantId` (hoặc header `X-Tenant-Id`) thì chỉ tenant đó. Có ghi log `platform-audit`.

## Giới hạn

- Chỉ sales-service (các service khác chưa lưu log vào DB).
- Không tìm theo văn bản tự do; không xuất file; không có đếm thống kê riêng.
- Lưu trên hạ tầng DB của sales: tải ghi lớn có thể bị bỏ bớt dòng như mô tả ở trên.
