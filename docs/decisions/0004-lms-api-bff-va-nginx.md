# ADR-0004: Một service API duy nhất cho FE (`lms-api`) sau nginx; các service khác chỉ là nội bộ

- Trạng thái: chấp nhận (đang chuyển dần từng context)
- Bối cảnh: ban đầu `api-gateway` (Spring Cloud Gateway) chỉ chuyển tiếp thẳng vào controller của từng service, nên hợp đồng API cho
  frontend bị chia nhỏ trong nhiều service và mọi thay đổi nội bộ đều lộ ra FE.
- Quyết định:
  - `lms-api` (Spring MVC, cổng 8085, không publish ra host) là nơi duy nhất giữ controller và DTO cho FE (`/api/**`).
  - Các service nghiệp vụ chỉ còn là service nội bộ: API của chúng đặt dưới `/internal/**`, nginx không route tới đó (trả 404) và
    chúng không publish cổng ra host (chỉ `expose` trong mạng compose).
  - `lms-api` gọi service nội bộ bằng client có kiểu (interface `...Client` + cài đặt HTTP riêng), chuyển tiếp token của người dùng
    và correlation id. DTO cho FE tách khỏi DTO nội bộ.
  - Xác thực: `lms-api` kiểm JWT bằng JWKS của identity. Phân quyền (ADR-0003) vẫn do từng service nội bộ kiểm trên token được chuyển
    tiếp, nên ma trận quyền chỉ có một nguồn. Lỗi 4xx của service được chuyển nguyên mã và nội dung; 5xx thành 502; mất kết nối thành 503.
  - Nginx thay `api-gateway`: CORS (danh sách origin), header bảo mật, giới hạn tốc độ `/api/auth/login`, chặn `/internal/**` và
    `/.well-known/**` (JWKS chỉ cho service nội bộ), gán `X-Correlation-Id` ở biên, chuyển tiếp `/api/**` về `lms-api`.
- Đánh đổi:
  - Thêm một hop và thêm lớp DTO/ánh xạ; `lms-api` thành điểm phụ thuộc chung (cần scale và theo dõi riêng).
  - Nginx bản miễn phí không kiểm JWT, nên việc chặn request không token xảy ra ở `lms-api` (một hop sau nginx) chứ không phải ở biên.
  - Luồng đăng nhập ngoài (redirect, cookie `lms_ext`) sẽ phải đi xuyên `lms-api` khi chuyển identity: cần giữ nguyên Set-Cookie và Location.
- Chuyển đổi: catalog đã chuyển. identity, circulation, support còn được nginx route tạm thẳng vào service (khối "TẠM THỜI" trong
  `infrastructure/nginx/nginx.conf`); chuyển xong context nào thì xóa route tạm tương ứng và đổi API của service sang `/internal/**`.
