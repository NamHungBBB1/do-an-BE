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
│   └── config/    OpenApiConfig · CorsConfig
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

| | |
|---|---|
| `GET /api/health` | sống chưa + `buildVersion` |
| `POST /api/telemetry/estimates` | nhận **một mảng** dòng cam kết, trần 500 dòng/lô |
| `GET /api/telemetry/estimates?seed=` | lấy lại cả lượt thí điểm theo seed |

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
