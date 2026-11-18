# Application, scope, domain, credential (SaaS API Authentication, pha P1)

Nền: `docs/architecture/SAAS_API_AUTHENTICATION_DESIGN.md` (D5, D7, D8, mục 4.3, 4.4), ADR-0027. Tenant admin tự đăng ký **Application** (website công khai hoặc backend tích hợp). Pha này CHƯA có token endpoint (pha I2) và CHƯA có site key ở customer-api (pha P2): site key và credential chỉ được tạo và quản lý, chưa dùng để xác thực ở đâu.

Mọi đường dẫn tương đối so với `<ORIGIN>` (nginx 8080 -> lms-api). Đường `/api/applications/**` chuyển sang identity `/internal/applications/**`; phản hồi **luôn** có `Cache-Control: no-store`. Tenant lấy từ token (claim `tenantId`), **không** nhận `tenantId` từ body/query/đường dẫn (field thừa bị bỏ qua). `id` của Application/credential/domain thuộc tenant khác trả `404` cùng thân với "không tồn tại".

## Phân quyền

| Nhóm | Cơ chế | Khóa |
|---|---|---|
| Tenant admin (`/api/applications/**`) | Khóa động ADR-0003 `ApplicationController/<method>`; role `is_admin` đi qua sẵn, role thường cần được gán khóa (tự đăng ký lần đầu endpoint được gọi) | `list`, `get`, `create`, `update`, `delete`, `replaceScopes`, `createCredential`, `revokeCredential`, `rotateSiteKey`, `listDomains`, `addDomain`, `removeDomain`, `history` |
| Platform admin (`/api/platform/tenants/{tenantId}/applications/**`) | `@PlatformAdminOnly` (không dùng khóa), tenant lấy từ đường dẫn | n/a |

Tên controller/method là khóa quyền: không đổi. Không token `401`; token không `tenantId` ở API tenant `403 TENANT_REQUIRED`; thiếu khóa `403`.

## Mô hình

- `kind`: `PUBLIC_SITE` (có `siteKey` công khai `lms_pk_...`, có domain allowlist, không scope/credential) hoặc `INTEGRATION` (có scope + credential, không site key). `INTEGRATION` chỉ tạo được khi tenant có hợp đồng thuê ACTIVE đang hiệu lực (IN_EFFECT hoặc IN_GRACE, ADR-0012), nếu không `409 CONTRACT_REQUIRED`.
- `status`: `ACTIVE`, `SUSPENDED` (platform admin), xóa mềm (không còn thấy qua API). `id` của Application đồng thời là `clientId`.
- Tên duy nhất trong tenant (không phân biệt hoa thường, `409 APPLICATION_NAME_DUPLICATED`); tối đa 20 Application chưa xóa mỗi tenant (`lms.identity.applications.max-per-tenant`, `409 APPLICATION_LIMIT_EXCEEDED`).
- Scope hợp lệ: `books:read|write`, `inventory:read|write`, `orders:read|write`, `customers:read|write`. **Chỉ cấp được** `books:read`, `inventory:read`, `orders:read`; scope ghi và `customers:*` bị `422 SCOPE_NOT_GRANTABLE` (chờ quyết định Q5/Q6); scope ngoài danh mục `400 INVALID_SCOPE`.
- Credential: secret `lms_sk_` + base64url(32 byte CSPRNG). Server chỉ lưu SHA-256 và 4 ký tự cuối (`secretHint`). **Secret đầy đủ chỉ có trong phản hồi tạo credential, một lần duy nhất**; không có API nào đọc lại. Tối đa 2 credential dùng được (ACTIVE chưa hết hạn) cùng lúc (`409 CREDENTIAL_LIMIT_EXCEEDED`); xoay vòng = tạo credential mới, đổi phía client, rồi thu hồi credential cũ. Hạn dùng mặc định 365 ngày (`lms.identity.applications.credential-ttl`), tối đa 730 ngày.

## Endpoint tenant admin

| Method | Đường dẫn | Body | Ra |
|---|---|---|---|
| GET | `/api/applications` | | `200 [ApplicationSummary]` |
| POST | `/api/applications` | `{ name (1-100), kind, rateLimitPerMinute? (1..1000000) }` | `201 ApplicationDetail` + `Location` |
| GET | `/api/applications/{id}` | | `200 ApplicationDetail` |
| PUT | `/api/applications/{id}` | `{ name, rateLimitPerMinute? }` (thay toàn bộ; chỉ khi ACTIVE) | `200 ApplicationDetail` |
| DELETE | `/api/applications/{id}` | | `204`; thu hồi mọi credential (`APPLICATION_DELETED`) và xóa domain |
| PUT | `/api/applications/{id}/scopes` | `{ scopes: ["books:read", ...] }` (thay toàn bộ, rỗng = thu hồi hết; chỉ INTEGRATION ACTIVE) | `200 ApplicationDetail` |
| POST | `/api/applications/{id}/credentials` | tùy chọn `{ expiresInDays? }` | `201 { credentialId, clientId, clientSecret, secretHint, createdAt, expiresAt }`, `Cache-Control: no-store` |
| POST | `/api/applications/{id}/credentials/{credentialId}/revocation` | tùy chọn `{ reason? }` (`MANUAL` mặc định, `ROTATED`, `COMPROMISED`) | `200 CredentialView` |
| POST | `/api/applications/{id}/site-key/rotation` | | `200 ApplicationDetail` (site key cũ hết hiệu lực ngay, không chồng lấp) |
| GET | `/api/applications/{id}/domains` | | `200 { maxOriginsPerTenant, domains: [{ id, origin, enabled, createdAt, createdBy, updatedAt, updatedBy }] }` |
| POST | `/api/applications/{id}/domains` | `{ origin }` | `201` origin; chuẩn hóa/kiểm như ADR-0013 |
| DELETE | `/api/applications/{id}/domains/{domainId}` | | `204` |
| GET | `/api/applications/{id}/history` | | `200 [{ id, credentialId, action, detail, actorId, occurredAt }]` mới nhất trước (tối đa 200) |

`ApplicationSummary`: `id, name, kind, status, siteKey, rateLimitPerMinute, scopes[], usableCredentials, createdAt, updatedAt`. `ApplicationDetail`: `id, clientId, name, kind, status, siteKey, rateLimitPerMinute, scopes[], credentials[], createdAt, createdBy, updatedAt, updatedBy`. `CredentialView`: `id, secretHint, status (ACTIVE|REVOKED), usable, createdAt, createdBy, expiresAt, lastUsedAt, revokedAt, revokeReason`. Không có trường nào chứa secret hay hash ngoài phản hồi tạo credential.

Domain: dùng lại bảng và luật origin của ADR-0013 (chuẩn hóa chữ thường/punycode, bỏ cổng mặc định, không path/wildcard, `http` chỉ localhost, UNIQUE toàn hệ thống, tối đa 10 origin mỗi tenant gồm cả origin cấp tenant). Chỉ cho `PUBLIC_SITE` ACTIVE. Domain allowlist chỉ là CORS, không phải xác thực. Origin trùng với tenant khác trả `409 ORIGIN_DUPLICATED` **không** lộ tenant chủ.

## Endpoint platform admin

`/api/platform/tenants/{tenantId}/applications` (không cần `X-Tenant-Id`, như `storefront-origins`).

| Method | Đường dẫn | Ra |
|---|---|---|
| GET | `.../applications`, `.../applications/{id}`, `.../applications/{id}/history` | như trên, của tenant `{tenantId}` |
| POST | `.../applications/{id}/suspension` | `200 ApplicationDetail` (`409 APPLICATION_NOT_ACTIVE` nếu không ACTIVE) |
| POST | `.../applications/{id}/reactivation` | `200 ApplicationDetail` (`409 APPLICATION_NOT_SUSPENDED`) |
| DELETE | `.../applications/{id}` | `204` (xóa mềm như trên) |

Platform admin không tạo Application và không cấp credential hộ tenant.

## Mã lỗi `{ code, message, details? }`

| HTTP | `code` |
|---|---|
| 400 | `VALIDATION_FAILED`, `INVALID_APPLICATION`, `INVALID_SCOPE`, `ORIGIN_INVALID` (`details[0]` = lý do) |
| 404 | `APPLICATION_NOT_FOUND`, `CREDENTIAL_NOT_FOUND`, `APPLICATION_DOMAIN_NOT_FOUND` |
| 409 | `CONTRACT_REQUIRED`, `KIND_MISMATCH`, `APPLICATION_NAME_DUPLICATED`, `APPLICATION_LIMIT_EXCEEDED`, `CREDENTIAL_LIMIT_EXCEEDED`, `CREDENTIAL_NOT_ACTIVE`, `APPLICATION_NOT_ACTIVE`, `APPLICATION_NOT_SUSPENDED`, `ORIGIN_DUPLICATED`, `ORIGIN_LIMIT_EXCEEDED` |
| 422 | `SCOPE_NOT_GRANTABLE` |

## Ghi chú cho FE

- Hiển thị `clientSecret` một lần với nút sao chép và cảnh báo "không xem lại được"; không lưu vào localStorage hay log.
- Hộp thoại xoay vòng: tạo credential mới, đợi client đổi, rồi thu hồi credential cũ (tối đa 2 cùng lúc).
- Danh sách chọn scope chỉ nên bật `books:read`, `inventory:read`, `orders:read` ở giai đoạn này.
- Tenant chưa có hợp đồng hiệu lực: ẩn lựa chọn loại `INTEGRATION` hoặc hiển thị `CONTRACT_REQUIRED`.
