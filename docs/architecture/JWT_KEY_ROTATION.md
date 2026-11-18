# Xoay khóa ký JWT (vận hành)

Áp dụng cho khóa RS256 của identity-service (`AUTH_JWT_PRIVATE_KEY`, `AUTH_JWT_PUBLIC_KEY`). Mục tiêu: đổi khóa ký mà không làm token đang dùng bị 401. Nền: `SAAS_API_AUTHENTICATION_DESIGN.md` mục 4.5, `docs/api/token-audience.md` (JWKS hai khóa).

## Cơ chế

- identity ký bằng khóa hiện tại, `kid` là thumbprint của khóa công khai.
- `AUTH_JWT_PREVIOUS_PUBLIC_KEY` (cấu hình `auth.jwt.previous-public-key-pem`, tùy chọn, mặc định trống) là khóa công khai CŨ (PEM X.509, chỉ phần công khai, KHÔNG đặt khóa riêng cũ ở đâu cả). Khi có giá trị, `GET /.well-known/jwks.json` trả hai khóa (khóa ký trước), và decoder của chính identity cũng chấp nhận token ký bằng khóa cũ.
- Mọi service, lms-api, customer-api, integration-api đọc JWKS qua `jwk-set-uri` và chọn khóa theo `kid`: không cần cấu hình lại, không cần khởi động lại. Chúng cache JWKS (mặc định Nimbus 5 phút; khóa lạ `kid` mới kích hoạt nạp lại).
- Sai định dạng PEM hoặc trùng khóa đang ký: identity không khởi động (thông điệp nêu `AUTH_JWT_PREVIOUS_PUBLIC_KEY`).
- JWKS không mở ra internet (nginx chặn `/.well-known/`, quyết định Q11): chỉ mạng nội bộ.

## Quy trình

Thời gian chờ ở bước 4 phải lớn hơn tuổi thọ lớn nhất của token ký bằng khóa cũ: access token nhân viên 15 phút (`auth.jwt.access-token-ttl`), token integration 5 phút; refresh token (7 ngày) **không** là JWT ký bằng khóa này (opaque, lưu DB) nên không cần chờ 7 ngày cho việc xác minh chữ ký. Cửa sổ khuyến nghị: 1 giờ.

1. Sinh cặp khóa mới (RSA 2048, PKCS#8 cho riêng, X.509 cho công khai), lưu khóa riêng vào kho bí mật. Không ghi khóa vào repo.
2. Lấy khóa công khai HIỆN TẠI (đang ở `AUTH_JWT_PUBLIC_KEY`) làm khóa cũ.
3. Cập nhật identity (mọi instance cùng lúc, hoặc rolling vì khóa công khai mới và cũ cùng nằm trong JWKS của instance đã đổi; xem lưu ý bên dưới):
   - `AUTH_JWT_PRIVATE_KEY` và `AUTH_JWT_PUBLIC_KEY` = cặp khóa MỚI;
   - `AUTH_JWT_PREVIOUS_PUBLIC_KEY` = khóa công khai CŨ.
   Khởi động lại identity. Kiểm tra `GET /.well-known/jwks.json` (từ trong mạng nội bộ) có 2 khóa, `kid` khác nhau, khóa đầu là khóa mới. Đăng nhập thử và gọi một API bằng token mới.
4. Chờ hết cửa sổ (>= 1 giờ, tối thiểu TTL token lớn nhất + thời gian cache JWKS của resource server).
5. Gỡ `AUTH_JWT_PREVIOUS_PUBLIC_KEY` (để trống), khởi động lại identity. JWKS còn một khóa. Hủy khóa riêng cũ.

Lưu ý rolling nhiều instance identity: trong lúc đang đổi, một số instance ký bằng khóa mới, số còn lại bằng khóa cũ; resource server chỉ thấy JWKS của instance nó gọi. Nếu instance chưa đổi trả JWKS chỉ có khóa cũ thì token ký bằng khóa mới bị 401 cho đến khi resource server nạp lại JWKS từ instance đã đổi. Vì vậy ưu tiên đổi đồng loạt (một instance hoặc tắt-bật nhanh), hoặc đặt JWKS sau một địa chỉ cân bằng tải ổn định và đổi toàn bộ trong vài phút.

## Khẩn cấp (khóa riêng bị lộ)

Không dùng cửa sổ xoay: đổi sang khóa mới, **để trống** `AUTH_JWT_PREVIOUS_PUBLIC_KEY`, khởi động lại identity; mọi token cũ (kể cả của kẻ tấn công) bị 401 ngay khi resource server nạp lại JWKS (tối đa vài phút do cache; có thể khởi động lại resource server để nạp ngay). Người dùng đăng nhập lại; token integration xin lại bằng client credentials.

## Điều chưa tự động

- identity chưa tự sinh/cuộn khóa theo lịch; việc xoay là thao tác vận hành.
- Chưa có kiểm thử với Redis/nginx/docker thật cho quy trình này (xem phần kiểm chứng trong báo cáo pha I5); có test đơn vị cho JWKS hai khóa và decoder hai khóa.
