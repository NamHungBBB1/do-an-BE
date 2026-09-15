# Backend — Game giáo dục tài chính

Backend **mỏng**. Mô phỏng chạy hoàn toàn ở client; đây chỉ là **sổ ghi sự kiện**.

> **Chốt D3:** *Mô phỏng chạy hoàn toàn ở client. Backend mỏng, chỉ là sổ ghi sự kiện.
> Game phải chơi trọn vẹn khi server chết.*

Nghĩa là: **đừng đưa logic tính toán tài chính vào đây.** Lãi kép, nợ, giá tài sản —
tất cả tính ở client. Server nhận log, cất, và trả lại khi cần phân tích. Server chết
thì người chơi không được biết.

## Chạy

```bash
mvn spring-boot:run
```

Mặc định dùng **H2 trong bộ nhớ** — clone về là chạy được ngay, không cần cài database.

- API: <http://localhost:8080/api/health>
- Swagger: <http://localhost:8080/swagger>

Dùng Postgres thật thì đặt biến môi trường, **không sửa `application.properties`**:

```bash
DB_URL=jdbc:postgresql://host:5432/ten_db
DB_USER=...
DB_PASSWORD=...
CORS_ORIGINS=https://ten-mien-fe
BUILD_VERSION=0.1.3
```

## Kiểm

```bash
mvn test
```

## Cấu trúc — xếp theo TÍNH NĂNG

```
com.doan.game/
├── GameApplication.java
├── shared/                  dùng chung, không thuộc tính năng nào
│   ├── web/       ApiResponse · HealthController
│   ├── error/     ErrorCode · AppException · GlobalExceptionHandler
│   └── config/    OpenApiConfig · CorsConfig · SecurityConfig
├── auth/                    xác thực — người lớn và trẻ, hai đường tách hẳn
│   ├── web/       AuthController · SlotController · AuthDtos
│   ├── domain/    Account · ChildSlot · Role · LearningContext
│   ├── repository/AccountRepository · ChildSlotRepository
│   └──            AuthService · JwtService
└── telemetry/               một tính năng = một thư mục
    ├── web/       TelemetryController · EstimateRequest
    ├── domain/    EstimateEvent
    └── repository/EstimateEventRepository
```

Thêm tính năng mới → **thêm một thư mục**, không rải file vào 5 thư mục có sẵn.

## Bốn quy ước — đọc trước khi viết dòng đầu tiên

**1. Xếp theo tính năng, không theo tầng.**
Không có `controller/`, `service/`, `repository/` ở gốc. Sửa một tính năng thì mở
một thư mục, không phải năm. Dự án EXE201 của nhóm đã chuyển từ xếp-theo-tầng sang
kiểu này hồi 07/2026 — đừng đi lại đường cũ.

**2. Không `interface` + `Impl` mặc định.**
Viết `class` trước. Chỉ tách `interface` khi **thật sự có hiện thực thứ hai**: lưu tệp,
thanh toán, gửi mail, nguồn ngoài có thể đổi. Nghiệp vụ trong nhà thì một lớp là đủ —
19 interface mà mỗi cái một hiện thực chỉ thêm tệp phải mở, không đổi được gì.

**3. Mọi lỗi đi một cửa.**
Ném `AppException(ErrorCode.X)`. **Không** try/catch rồi tự dựng response trong
controller — làm vậy là có hai hình dạng lỗi và FE phải học cả hai.
Lỗi mới → thêm vào `ErrorCode`, cấp dải số mới, **đừng chen số vào giữa dải cũ**
(FE có thể đã bắt theo mã).

**4. Mọi response cùng một vỏ.**
```json
{ "code": 0, "message": "OK", "result": { } }
```
`code = 0` là thành công. Khác 0 là mã trong `ErrorCode`.

## API hiện có

| | | cần token |
|---|---|---|
| `GET /api/health` | sống chưa + `buildVersion` | không |
| `POST /api/auth/register` | người lớn: Gmail + SĐT + mật khẩu → token | không |
| `POST /api/auth/login` | người lớn đăng nhập → token | không |
| `POST /api/auth/child/login` | trẻ: `code` (QR) + `pin` → token gắn 1 slot | không |
| `POST /api/auth/plan` | bật gói phụ huynh/giáo viên → **token mới** | người lớn |
| `GET /api/auth/me` | hồ sơ; trả hai hình dạng khác nhau cho người lớn và trẻ | có |
| `GET /api/auth/verify?token=` | link trong mail, trả **HTML** chứ không phải JSON | không |
| `POST /api/auth/verify/resend` | gửi lại mail xác thực, tối đa 1 lần/phút | người lớn |
| `POST /api/auth/child/link` | gắn slot vào tài khoản trẻ (kèm QR + PIN) | có |
| `POST /api/auth/context` | trẻ đã liên kết CHỌN bối cảnh → token của bối cảnh đó | có |
| `POST /api/slots` | tạo slot → **PIN chỉ hiện một lần** | đã mua gói |
| `GET /api/slots` | danh sách slot mình sở hữu | đã mua gói |
| `POST /api/slots/{id}/reset-pin` | cấp PIN mới | đã mua gói |
| `POST /api/slots/end-class` | kết thúc lớp: lưu trữ data, trả lại 40 slot | đã mua gói |
| `POST /api/telemetry/estimates` | nhận **một mảng** dòng cam kết, trần 500 dòng/lô | không |
| `GET /api/telemetry/estimates?seed=` | lấy lại cả lượt thí điểm theo seed | không |

Token gửi ở header `Authorization: Bearer <token>`. Bấm **Authorize** trên `/swagger` là thử được hết.

Telemetry **cố ý không cần token**: chốt D3 nói game phải chơi trọn vẹn khi server chết,
bắt đăng nhập ở cổng ghi log là đi ngược chốt đó.

## Luồng auth — năm điều FE phải biết trước khi gọi

Toàn bộ thiết kế lấy từ biên bản `docs/brainstorming/brainstorm-luong-authen-2026-09-14`.

**0. SĐT chỉ được LƯU, KHÔNG được xác thực.** Không có hạ tầng SMS và sẽ không có —
SMS OTP ở Việt Nam phải đăng ký brandname với nhà mạng, mất tiền cả lúc đăng ký lẫn mỗi tin.
Đừng viết trong tài liệu rằng SĐT đã xác thực. Thứ chứng minh người thật là **email**.

**1. Trẻ không tự đăng ký.** Người lớn tạo slot, hệ thống sinh `code` (in ra QR) + `pin` 6 số.
PIN gốc trả về **đúng một lần** ở response tạo slot; sau đó chỉ còn bản băm. Mất thì `reset-pin`.

**2. Nhà và lớp riêng hoàn toàn.** `FAMILY` và `CLASS` là hai phạm vi tách hẳn: phụ huynh không
xem được phần ở lớp, giáo viên không xem được phần ở nhà. Bối cảnh nằm **trong token** (claim `ctx`),
không phải tham số client gửi lên — để chặn được ngay ở cổng.

**3. Thứ duy nhất đi qua ranh giới là `level` và `badge`.** Lịch sử từng lượt thì không.
Cô giáo cần biết bé đã từng chơi trước đó, nếu không thì so sánh vô nghĩa — nhưng chỉ cần con số.

**4. Trẻ có cả nhà lẫn lớp thì phải CHỌN.** Không có phiên nào gộp hai bối cảnh:
`login` → `GET /me` (xem `linkedContexts`) → `POST /context` với `slotId` → nhận token của bối cảnh đó.

**5. Xác thực email là cánh cửa chặn spam.** Đăng ký thì ai cũng đăng ký được và CÓ token ngay
— để FE hiện được màn "vào hộp thư bấm link". Nhưng chưa xác thực thì **không mua được gói,
không tạo được slot nào**: cả hai trả `403 · 3011`. Đọc cờ `emailVerified` trong `GET /me`.

Link sống 24 giờ, **dùng một lần** (bấm xong là token bị xoá khỏi DB). Trong DB chỉ lưu
SHA-256 của token, không lưu token gốc. Gửi lại tối đa 1 lần/phút, quá thì `429 · 3015`.

Chạy ở máy mà không đặt `MAIL_HOST` thì **không gửi mail thật** — link xác thực được in
thẳng ra log, copy dán vào trình duyệt là xong. Team FE không cần tài khoản SMTP nào.

**6. Vai nằm trong token.** Mua gói xong mà FE giữ token cũ thì vẫn bị chặn ở `/api/slots`.
Vì thế `POST /api/auth/plan` trả về **token mới** — thay ngay, đừng chỉ đọc rồi bỏ.

Hạn mức: phụ huynh **4** slot, giáo viên **40**. Slot đã lưu trữ không tính — đó là ý nghĩa
của nút kết thúc lớp học. Sai PIN **5 lần** thì khoá slot 15 phút.

## Bản đang chạy

🔗 API: <https://kidzeconomy.com.vn/finteen/api/health>
· Swagger: <https://kidzeconomy.com.vn/finteen/swagger>

Chạy trên VPS riêng của nhóm, không phải nền tảng free — free nào cũng ngủ
(Render 15 phút, Koyeb 1 giờ và đã chặn đăng ký mới). Đổi lại phải tự vận hành.

| | |
|---|---|
| Tiến trình | systemd, user hệ thống riêng, `-Xmx512m` |
| DB | H2 **ghi ra file** — restart không mất data |
| Ra ngoài | nginx reverse proxy, context-path `/finteen` |
| `JWT_SECRET` | sinh bằng `openssl rand -hex 32` **ngay trên máy chủ**, chưa từng đi qua repo |

**Thông tin truy cập máy chủ (IP, khoá SSH, đường dẫn) KHÔNG nằm trong repo này —
repo public.** Script deploy để ngoài ở `ops/finteen-deploy/` trên máy, theo đúng cách
EXE201 đã làm với script đụng prod. Đẩy bản mới:

```bash
bash ../ops/finteen-deploy/redeploy.sh   # test -> build -> upload -> restart -> kiểm health
```

**Đổi CORS khi FE có tên miền:** sửa `CORS_ORIGINS` trong file env trên máy chủ rồi restart
service. Đừng sửa `application.properties`.

H2 file đủ cho giai đoạn này. Muốn Postgres thật thì đặt `DB_URL`/`DB_USER`/`DB_PASSWORD`,
driver đã có sẵn trong `pom.xml`.

## Vì sao `EstimateEvent` trông như vậy

Nó là **đối tượng đo chính của cả nghiên cứu** (xem `knowledge/05-do-luong.md`):

- **`seed` và `buildVersion` bắt buộc trên mọi dòng.** Thiếu thì dòng log vô dụng lúc
  phân tích, nên chặn ngay ở cổng vào chứ không phát hiện lúc xuất CSV.
- **`guess`/`truth` là số nguyên đồng** (chốt D1). Đừng đổi sang `double`.
- **Ghi thô, không tính điểm.** Sai số nghiên cứu là `log(guess) − log(truth)` **có dấu**,
  tính lúc phân tích. Điểm thưởng trong game là hàm khác có sàn có trần — lấy điểm thưởng
  làm biến nghiên cứu là méo toàn bộ phân tích.
- **`committedAt − shownAt`** cho thời gian cân nhắc. Dùng để **lọc** (bỏ lượt < 3 giây
  hoặc > 5 phút), không làm biến kết quả.
- **`playerKey` ẩn danh.** Không lưu tên, email, hay bất cứ gì nhận dạng được.

## Chưa có, và cố ý chưa có

Xác thực, phân quyền, thanh toán, WebSocket, gửi mail. Thêm khi có việc thật cần,
không thêm cho đủ bộ.
