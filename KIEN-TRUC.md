# Kiến trúc BE — bản khung

Sinh ngày 25/09/2026 từ lược đồ ERD đã chốt. **Toàn bộ là khung: chưa có một dòng nghiệp vụ nào.**
Mọi hàm còn ném `UnsupportedOperationException("chua cai dat")` — cố ý, để không ai gọi một lớp
rỗng rồi tưởng nó đã chạy.

Khuôn lấy từ `build/swp-kien-truc.drawio`, tức đúng cách nhóm đã làm ở SWP.

---

## Ba tầng

| Tầng | Gói | Việc của nó | Cấm làm |
|---|---|---|---|
| Cửa vào | `controller` | Nhận HTTP, gọi service, trả về | Không một dòng nghiệp vụ |
| Nghiệp vụ | `service` + `service.impl` | Toàn bộ luật, giao dịch, kiểm quyền | Không đụng thẳng HTTP |
| Dữ liệu | `repository` | Spring Data JPA | Không chứa luật |

Nền là `entity` — ánh xạ 1–1 với 19 thực thể trong Capstone Register.

## Bốn nhóm cắt ngang

`DTO/request` · `DTO/response` — dùng `record` của Java 17. **Entity không bao giờ lọt thẳng ra API**;
lộ một cột nội bộ ra ngoài thì về sau không rút lại được.

`mapper` — viết tay, phương thức tĩnh. Không MapStruct, đúng như SWP.

`enums` — `Role`, `LearningContext`, `AuthProvider`, `PackageKind`, `TransactionStatus`.

`exception` — `ErrorCode` → `AppException` → `GlobalExceptionHandler` → `ApiResponse`.

`configuration` — CORS, OpenAPI, Security.

---

## Bản đồ service → bảng

| Service | Giữ bảng nào | Làm gì |
|---|---|---|
| `AuthService` | Account, Credential | Đăng ký, xác minh email, đăng nhập, gộp cách đăng nhập |
| `GroupService` | LearnerGroup | Mở lớp, đóng lớp, đếm chỗ trống |
| `SlotService` | ChildSlot | Phát chỗ, trả chỗ, xoá sạch, đường trẻ đăng nhập |
| `PlayService` | ChoiceEvent, MiniGameResult, RunState | Nhận lô một chương |
| `QuizService` | Question, Quiz, QuizResult | Kho câu hỏi, soạn quiz, chấm |
| `ReportService` | GroupReport, GroupReportRow | Đông cứng báo cáo lúc đóng nhóm |
| `RewardService` | Achievement, RewardItem, Redemption | Thành tựu và đổi thưởng |
| `PaymentService` | Transaction | Cổng thanh toán và webhook |
| `AdminService` | RoleGrantLog | Phát và thu vai, kèm sổ ghi |
| `LearnerModelService` | ConceptMastery, DailySummary | Knowledge tracing, phân cụm lớp, bản tổng kết mỗi đêm (FR-25 đến FR-27) |

---

## Bốn điều phải giữ khi điền ruột

**1. Truy vấn dữ liệu trẻ lọc theo `groupId`, không bao giờ theo `ownerId`.** Một người lớn có thể
giữ cả gói phụ huynh lẫn gói giáo viên; lọc theo tài khoản là gộp con mình với học sinh mình dạy
vào một danh sách — và **không có gì báo lỗi**, danh sách chỉ dài hơn bình thường. Luật `BR-116`.

**2. `Credential` khoá trên `(provider, subject)`, không khoá trên email.** OpenID Connect Core
mục 5.7 nói rõ email KHÔNG được dùng làm định danh duy nhất: nó đổi được và có thể cấp lại cho
người khác. Gộp tài khoản cần **hai vế** — email đã xác minh *và* người dùng chứng minh được quyền
sở hữu tài khoản cũ.

**3. Bia mộ không ăn chỗ.** Chỗ trống = `slotLimit` trừ số slot **chưa `archived` và chưa
`deletedAt`**. Xoá sạch một em thì hàng vẫn còn (để báo cáo cũ không thủng dòng) nhưng thôi chiếm
chỗ ngay. Luật `BR-103`.

**4. Số thì đóng băng, danh tính thì đọc sống.** `GroupReportRow` chỉ giữ con số và một tham chiếu
tới slot — **không chép tên vào**. Nhờ vậy xoá một em là mọi báo cáo cũ tự hiện tên mặc định, không
phải đi sửa một bản đã đông cứng.

---

## Sinh lại

Lược đồ đổi thì **chạy lại script, đừng sửa tay**:

```
python ops/sinh-khung-be.py      # entity · enums · repository · service · controller
python ops/sinh-dto-mapper.py    # DTO · mapper
```

Script đọc thẳng `brainstorm-hub/public/erd.json`, thứ được sinh từ sheet `6.Entities` của
Capstone Register. Lược đồ vừa nhảy từ 10 lên 17 thực thể trong một ngày — sửa tay 100 tệp một
lần nữa là không xong.

**Cẩn thận:** script **xoá rồi sinh lại** các gói `entity`, `enums`, `repository`, `service`,
`controller`. Khi đã có nghiệp vụ thật trong `service/impl`, phải sửa script cho nó bỏ qua những
tệp đã có — hoặc đừng chạy nữa.

---

## Luật riêng của phần AI

**5. Ra ngoài chỉ có con số.** Job tổng kết mỗi đêm gọi mô hình ngôn ngữ của bên thứ ba. Thứ gửi
đi chỉ là con số đã tính (mức nắm khái niệm, nhóm hành vi) kèm mã slot. Không bao giờ gửi tên, tên
lớp hay lịch sử lựa chọn thô. Luật `BR-404`, yêu cầu `NFR-08`. Gọi lỗi thì để đêm sau thử lại,
không được làm chậm việc chơi.

## Còn thiếu, cần chốt trước khi code

- **Bảng số dư tiền trong game.** Nhóm đã chốt 26/09 là CÓ bảng này (`FR-19` cần nó), nhưng chưa
  rõ nó giữ những gì — đang chờ làm rõ trước khi thêm vào sổ đăng ký.
- **Nhà cung cấp mô hình ngôn ngữ** cho `FR-27` chưa chọn, và nó thường tính tiền theo lượt gọi,
  trái với ràng buộc `C-04` (chỉ dùng công cụ miễn phí). Cần chốt trước khi viết job tổng kết.
