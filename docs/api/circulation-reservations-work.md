# Đặt trước theo tác phẩm (ADR-0026 mục 3.5, pha 2a)

Hợp đồng `POST /internal/reservations` (lms-api `/api/reservations`) KHÔNG đổi: body vẫn `{titleId}` (ấn bản độc giả chọn), khóa quyền không đổi. Chỉ đổi ngữ nghĩa và thêm trường phản hồi.

## Ngữ nghĩa mới
- Đặt trước theo TÁC PHẨM, giới hạn cùng ngôn ngữ với ấn bản đã chọn. Circulation đọc `workId`, `language`, `workAvailableCopies` từ `GET /internal/books/{titleId}` (khóa `TitleController/get` không đổi).
- Chỉ đặt được khi hết bản sao của cả tác phẩm cùng ngôn ngữ (`workAvailableCopies`; catalog cũ không trả thì dùng `availableCopies` của ấn bản). Còn bản sao: `409 COPY_STILL_AVAILABLE` như cũ.
- Mỗi độc giả một đặt trước còn hiệu lực (WAITING/READY) trên một tác phẩm: đặt thêm ấn bản khác cùng tác phẩm trả `409 ALREADY_RESERVED` (mã như cũ).
- Bản sao sẵn sàng (`CopyAvailableEvent` có `workId`, `language`) giữ cho người đầu hàng đợi của tác phẩm đó, chỉ trong số độc giả nhận được ngôn ngữ của bản sao. Event không có `workId` hoặc `language` thì rơi về hàng đợi theo đúng ấn bản như trước.
- `EditionWorkChangedEvent {editionId, oldWorkId, newWorkId}`: đặt trước còn hiệu lực (WAITING, READY) của ấn bản chuyển sang `newWorkId`. Gộp làm một độc giả có hai đặt trước trên cùng tác phẩm: giữ cái đang giữ bản sao (READY), cùng trạng thái thì giữ cái đặt sớm hơn, hủy cái còn lại (phát `ReservationCancelledEvent`; bản sao đang giữ của cái bị hủy chuyển cho người kế tiếp hoặc trả về kho). Idempotent.
- Vị trí hàng đợi (`queuePosition`) tính trong cùng tác phẩm và cùng ngôn ngữ.

## Trường thêm (chỉ thêm, FE cũ bỏ qua được)
`ReservationResponse` (`POST/GET /internal/reservations...`): thêm `workId` (UUID), `language` (chuỗi hoặc null = mọi ngôn ngữ). lms-api hiện chưa chuyển hai trường này nếu DTO của nó không khai.

## Migration
circulation `V13__reservations_work_id.sql`: `reservations.work_id UUID NULL`, `language VARCHAR(8) NULL`; backfill `md5('lms-work:'||title_id)::uuid` theo từng tenant (RLS); index `idx_reservations_work_queue (tenant_id, work_id, reserved_at, id) WHERE WAITING`, `uq_reservations_active_work (tenant_id, reader_id, work_id) WHERE WAITING/READY`, `idx_reservations_title_active (tenant_id, title_id) WHERE WAITING/READY`. Index cũ theo `title_id` giữ lại tới pha contract.
`borrowing_items` không đổi (ADR: mượn theo bản sao, `title_id` vẫn là snapshot ấn bản).

## Phụ thuộc catalog
`CopyAvailableEvent` hiện có `workId` nhưng chưa có `language` của ấn bản: cho tới khi catalog thêm `language` vào payload, giữ bản sao vẫn rơi về hàng đợi theo ấn bản (an toàn, chưa gộp được giữa các ấn bản cùng tác phẩm).
