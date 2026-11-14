# lms-api

Service duy nhất đưa API ra cho frontend (BFF). Các service còn lại (identity, catalog, circulation, support) chỉ là
service nội bộ: API của chúng nằm dưới `/internal/**`, nginx không route tới đó và chúng không publish cổng ra ngoài.

```
FE --> nginx (:8080: TLS, CORS, rate limit, chặn /internal) --> lms-api (:8085) --> service nội bộ (/internal/**)
```

- Hợp đồng với FE nằm ở đây: controller và DTO trong `lms.api.<context>.web|dto`.
- `lms.api.<context>.client` là máy khách gọi service nội bộ; controller chỉ phụ thuộc interface (ArchUnit kiểm tra).
- Xác thực: lms-api kiểm JWT (JWKS của identity). Phân quyền theo permission do service nội bộ đảm nhiệm: lms-api chuyển tiếp
  token của người dùng nên một nguồn sự thật duy nhất cho ma trận quyền.
- Lỗi của service nội bộ được chuyển nguyên mã và nội dung (4xx); 5xx thành 502 `DEPENDENCY_ERROR`, không gọi được thành 503.

Trạng thái chuyển đổi: catalog, circulation (borrowings, reservations) và support (audit-logs) đã chuyển. identity còn được
nginx route tạm thẳng vào service (xem `infrastructure/nginx/nginx.conf`, mục TẠM THỜI, nếu còn) cho tới khi chuyển xong.
Quyết định kiến trúc: `docs/decisions/0005-lms-api-bff-va-nginx.md`.
