# Redis và multi-tenant

Từ khi có multi-tenant (ADR-0006), mọi khóa Redis phụ thuộc tenant dùng tiền tố `tenant:{tenantId}:` (ví dụ `tenant:00000000-0000-0000-0000-0000000000d1:<khóa>`), để hai tenant không thấy hoặc ghi đè dữ liệu của nhau.

Khóa cũ (không tiền tố) sẽ không còn được đọc:

- Khóa có TTL tự hết hạn, không cần làm gì.
- Khóa không TTL: xóa thủ công khi muốn dọn (liệt kê bằng `redis-cli --scan`, bỏ các khóa bắt đầu bằng `tenant:`, kiểm tra danh sách trước khi `DEL`).
- Cache chỉ là bản sao nên mất khóa cũ chỉ gây cache miss, không mất dữ liệu gốc.
