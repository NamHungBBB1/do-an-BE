# FinTeen — Backend (SEP490, FA26SE220)

Spring Boot 3.5 / Java 17. API cho web FinTeen: tài khoản người lớn (phụ huynh, giáo viên, admin),
gói & thanh toán PayOS, nhóm gia đình / lớp và slot trẻ (mã + PIN), dashboard người học, nội dung game
(phát hành theo phiên bản, đọc công khai). Viết lại 10/10/2026 cho đúng mã hiện tại.

Thiết kế chi tiết và các luật phải giữ: **`KIEN-TRUC.md`** (đọc trước khi code).

## Chạy tại chỗ

```bash
mvn spring-boot:run
```

Mặc định **H2 trong bộ nhớ** — clone về là chạy, không cần cài database. Mail không có SMTP thì in OTP ra log.

- Health: <http://localhost:8080/api/health> · Swagger: <http://localhost:8080/swagger-ui/index.html>

Biến môi trường (tất cả có mặc định cho máy dev; production đặt trong `/etc/finteen.env`, không bao giờ commit):

| Nhóm | Biến |
|---|---|
| Web | `PORT`, `CONTEXT_PATH`, `CORS_ORIGINS`, `PUBLIC_BASE_URL`, `BUILD_VERSION` |
| DB | `DB_URL`, `DB_USER`, `DB_PASSWORD` (production: PostgreSQL 16) |
| Token | `JWT_SECRET` (≥ 32 ký tự — **bắt buộc** khi DB không phải H2 bộ nhớ, app từ chối khởi động nếu còn khoá mặc định), `JWT_ADULT_TTL_HOURS`, `JWT_CHILD_TTL_HOURS` |
| Mail | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, `MAIL_FROM_NAME` |
| Thanh toán | `PAYOS_CLIENT_ID`, `PAYOS_API_KEY`, `PAYOS_CHECKSUM_KEY`, `PAYOS_WEBHOOK_URL`, `PAYOS_SWEEP_MS` |
| Khác | `ADMIN_EMAILS` (email được vai ADMIN), `FIREBASE_PROJECT_ID` (đăng nhập Google), `ASSETS_BASE_URL` (kho ảnh CDN), `RATE_LIMIT_MAIL`, `RATE_LIMIT_WINDOW_SECONDS` |

## Kiểm thử

```bash
mvn test
```

Test chạy trên H2 với `hbm2ddl.halt_on_error=true`; phần lớn là test luồng qua HTTP thật (MockMvc):
đăng ký / OTP / đăng nhập, gói & thanh toán, nhóm / slot, dashboard người học, nội dung game, cửa quyền.

## Cấu trúc

```
com.doan.game/
├── controller/      mỏng: nhận, gọi service, trả ApiResponse
├── service/ + impl/ nghiệp vụ (interface + Impl)
├── repository/      Spring Data JPA
├── entity/, enums/  SINH TỰ ĐỘNG từ ERD — không sửa tay (xem dưới)
├── DTO/, mapper/    request / response
├── security/        JWT HS256 (typ ACCOUNT | SLOT), thu hồi token, giới hạn IP
├── configuration/   SecurityConfig, Clock, OpenAPI, CORS
└── exception/       ErrorCode (mã 1xxx chung, 3xxx auth, 4xxx thanh toán, 5xxx nhóm / slot, 6xxx nội dung)
```

Mọi response cùng một vỏ `{"code":0,"message":"OK","result":…}`; lỗi `{"code":<mã>,"message":…}` kèm HTTP status
tương ứng (một cửa ra duy nhất: `GlobalExceptionHandler`).

**Đổi schema:** không sửa `entity/*.java` tay. Sửa ERD (`brainstorm-hub/scripts/erd_0110.py` trong repo tài liệu)
→ chạy bộ sinh `ops/sinh-khung-be.py` → tệp có ruột nằm trong `GIU_LAI` của bộ sinh. Cột mới luôn cho phép NULL;
`ddl-auto=update` không đổi kiểu cột đã có — việc đó làm bằng SQL tay (xem `ops/finteen-deploy/*.sql`).

## Deploy

Merge vào `main` (branch protection: PR + job `test` xanh) → GitHub Actions build jar → SSH lên VPS bằng khoá
forced-command → `pg_dump` trước → thay jar → chờ `/api/health` 120 s → hỏng thì tự lùi bản trước.
Không deploy tay.

## Không có ở đây

Mô phỏng tài chính trong game chạy ở client; BE chỉ phát nội dung đã phát hành và (sắp tới) ghi sự kiện
lựa chọn rồi tự tính chỉ số — không tin số do client gửi. Flyway chưa dùng (ddl-auto + SQL tay).
