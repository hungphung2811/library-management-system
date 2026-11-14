# ADR-0001: Publish event trực tiếp ở giai đoạn đầu, chuyển sang Transactional Outbox

- Trạng thái: chấp nhận, có nợ kỹ thuật
- Bối cảnh: context `borrowing` (circulation-service) lưu phiếu mượn vào PostgreSQL rồi publish event lên Kafka. Hai bước này
  không nằm trong cùng một transaction nên có thể lệch (đã lưu nhưng event mất, hoặc ngược lại).
- Quyết định: bản tham chiếu publish trực tiếp (bất đồng bộ, log lỗi) để giữ đơn giản.
- Cập nhật: `KafkaEventPublisher` là best-effort, lỗi gửi (kể cả hết thời gian chờ metadata khi broker sự cố) chỉ
  được log và không làm hỏng request. Phát hiện khi test: trước đó broker sự cố làm đăng nhập, gán role, mượn
  sách đều lỗi. Đánh đổi: event có thể bị mất cho tới khi có Outbox. Producer đặt `max.block.ms=2000` để không
  chặn request quá lâu.
- Hệ quả: trước khi lên production phải thêm bảng `outbox` ghi cùng transaction với aggregate, cùng một
  relay đẩy sang Kafka, cộng consumer idempotent theo `eventId`, retry và Dead Letter Queue.
