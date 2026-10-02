# Kiến trúc BE — bản khung

Sinh lần đầu 25/09/2026, **sinh lại 02/10/2026** theo ERD bản 01/10 (role cơ bản). **Toàn bộ là khung:
chưa có một dòng nghiệp vụ nào.** Mọi hàm còn ném `UnsupportedOperationException("chua cai dat")` — cố ý,
để không ai gọi một lớp rỗng rồi tưởng nó đã chạy.

Khuôn lấy từ `build/swp-kien-truc.drawio`, tức đúng cách nhóm đã làm ở SWP.

---

## Ba tầng

| Tầng | Gói | Việc của nó | Cấm làm |
|---|---|---|---|
| Cửa vào | `controller` | Nhận HTTP, gọi service, trả về | Không một dòng nghiệp vụ |
| Nghiệp vụ | `service` + `service.impl` | Toàn bộ luật, giao dịch, kiểm quyền | Không đụng thẳng HTTP |
| Dữ liệu | `repository` | Spring Data JPA | Không chứa luật |

Nền là `entity` — ánh xạ 1–1 với **21 thực thể** của ERD bản 01/10 (`brainstorm-hub/public/erd-0110.json`).

## Vai và gói (02/10)

- **Parent / Teacher không lưu ở `Account`.** Vai suy từ `Entitlement` còn hạn: có dòng `PARENT` còn hạn
  là Parent, có dòng `TEACHER` còn hạn là Teacher; một tài khoản giữ được cả hai. Hai gói độc lập, mỗi gói
  **3 tháng, tính theo NGÀY** giờ Việt Nam (`startsOn`, `expiresOn` kiểu `DATE`, dùng hết ngày `expiresOn`).
- **Mọi thao tác cần gói đều gọi `EntitlementService.activePlans(accountId)`**, không tin scope trong JWT:
  gói có thể hết hạn giữa phiên.
- Admin cấp được gói **không cần thanh toán** (`Entitlement.source = ADMIN`, ghi `grantedById`).
- Vai nội bộ (`ADMIN`, sau này `GAME_EDITOR` / `GAME_REVIEWER` / `GAME_MANAGER`) lưu ở `AccountRole`,
  mỗi lần cấp / thu ghi `RoleGrantLog` kèm lý do.
- **Child / Student không có tài khoản**: slot là danh tính. Child hay Student suy từ `context` của nhóm
  (`FAMILY` / `CLASS`). Một trẻ ở cả nhà lẫn lớp có hai slot riêng, dữ liệu tự tách.
- Mỗi tài khoản **tối đa một nhóm đang mở cho mỗi context**: `LearnerGroup.openContext` = `context` khi
  đang mở, `NULL` khi đã đóng, `UNIQUE(ownerId, openContext)` — chạy được trên cả H2 lẫn PostgreSQL.

## Bốn nhóm cắt ngang

`DTO/request` · `DTO/response` — dùng `record` của Java 17. **Entity không bao giờ lọt thẳng ra API**;
lộ một cột nội bộ ra ngoài thì về sau không rút lại được.

`mapper` — viết tay, phương thức tĩnh. Không MapStruct, đúng như SWP.

`enums` — `Role`, `PlanKind`, `EntitlementSource`, `LearningContext`, `SlotStatus`, `AuthProvider`,
`TokenPurpose`, `TransactionPurpose`, `TransactionStatus`, `SummarySource`. Tên enum lấy theo **tập giá
trị** chứ không theo tên cột (`purpose` có ở hai bảng khác nghĩa; `context` và `openContext` dùng chung).

`exception` — `ErrorCode` → `AppException` → `GlobalExceptionHandler` → `ApiResponse`.

`configuration` — CORS, OpenAPI, Security, **PayOS**: `PayOsProperties` (record, đọc `app.payos.*`) và
`PayOsConfig` tạo bean `PayOS` từ SDK chính chủ `vn.payos:payos-java`; **không có khoá thì không có bean**,
nên máy dev không bao giờ gọi PayOS thật. Khoá đặt trong `/etc/finteen.env` (`PAYOS_CLIENT_ID`,
`PAYOS_API_KEY`, `PAYOS_CHECKSUM_KEY`), không nằm trong git.

---

## Bản đồ service → bảng

| Service | Giữ bảng nào | Làm gì |
|---|---|---|
| `AuthService` | Account, Credential, VerificationToken | Đăng ký, xác minh email, đăng nhập, gộp cách đăng nhập, quên / đặt lại mật khẩu |
| `EntitlementService` | Entitlement | Gói đang giữ (`activePlans`), admin cấp gói, nhắc sắp hết hạn |
| `GroupService` | LearnerGroup | Mở nhóm gia đình / lớp, xác nhận đồng ý phụ huynh, kết thúc nhóm, đếm chỗ trống |
| `SlotService` | LearnerSlot | Mở slot (một hoặc nhiều), trả, xoá sạch, đổi PIN, trẻ đăng nhập |
| `PlayService` | ChoiceEvent, MiniGameResult, RunState | Nhận lô một chương, chống ghi trùng |
| `QuizService` | Question, Quiz, QuizResult, QuizAnswer | Soạn quiz từ kho, phát cho lớp, chấm, kết quả từng em |
| `ReportService` | GroupReport, GroupReportRow | Đông cứng báo cáo lúc kết thúc nhóm |
| `AchievementService` | Achievement | Thành tựu, chứng chỉ (đổi thưởng đã bỏ) |
| `PaymentService` | Transaction | PayOS theo demo chính chủ: tạo link (`orderCode` do mình sinh), xem / huỷ theo `orderCode`, webhook `verify(body)` → PAID tạo Entitlement và **luôn trả 200**, admin đăng ký URL webhook, cron quét PENDING |
| `AdminService` | AccountRole, RoleGrantLog | Cấp và thu vai nội bộ, kèm sổ ghi |
| `LearnerModelService` | ConceptMastery, DailySummary | Knowledge tracing, phân cụm lớp, tổng kết mỗi đêm qua AI provider, bản theo luật khi AI lỗi |

---

## Những điều phải giữ khi điền ruột

**1. Truy vấn dữ liệu trẻ lọc theo `groupId`, không bao giờ theo `ownerId`.** Một tài khoản sở hữu
nhiều nhóm theo thời gian; lọc theo tài khoản là gộp các đợt vào một danh sách — và **không có gì báo
lỗi**, danh sách chỉ dài hơn bình thường.

**2. `Credential` khoá trên `(provider, subject)`, không khoá trên email.** OpenID Connect Core mục 5.7
nói rõ email KHÔNG được dùng làm định danh duy nhất. Gộp tài khoản cần **hai vế** — email đã xác minh *và*
người dùng chứng minh được quyền sở hữu tài khoản cũ.

**3. AVAILABLE không có dòng; bia mộ không ăn chỗ.** Chỗ trống = `slotLimit` trừ số slot `ACTIVE`. Mở slot
mới tạo dòng và sinh mã. Xoá sạch một em (`WIPED`) thì hàng vẫn còn nhưng thôi chiếm chỗ.

**4. LOCKED không lưu.** "Đang khoá" = `lockedUntil > now`. Không có job mở khoá, nên không có cách nào
để `status` và đồng hồ lệch nhau.

**5. Số thì đóng băng, danh tính thì đọc sống.** `GroupReportRow` chỉ giữ con số và một tham chiếu tới
slot — **không chép tên vào**.

**6. Chống ghi trùng bằng UNIQUE thật.** `ChoiceEvent(slot, chapter, attempt, scene)` và
`MiniGameResult(slot, chapter, attempt, game, item)` là `@UniqueConstraint`; lô gửi lại thì ghi đè vô
hại, không cộng hai lần. **Luôn nhận lô kể cả khi gói đã hết hạn**, chỉ không mở khoá chương mới.

**7. Ra ngoài chỉ có con số.** Job tổng kết gửi mô hình ngôn ngữ con số đã tính kèm mã slot. Không bao
giờ gửi tên. AI lỗi thì ghi bản theo luật với `DailySummary.source = RULE`, người lớn vẫn có báo cáo.

**8. Ngày theo giờ Việt Nam.** `Entitlement.startsOn / expiresOn` và `DailySummary.summaryDate` tính
bằng `ZoneId.of("Asia/Ho_Chi_Minh")`, không dùng giờ máy chủ.

---

## Sinh lại

Lược đồ đổi thì **chạy lại script, đừng sửa tay**:

```
python ops/sinh-khung-be.py      # entity · enums · repository · service · controller
python ops/sinh-dto-mapper.py    # DTO · mapper
```

Script hiện đọc `brainstorm-hub/public/erd-0110.json` (bản nháp 01/10, chờ ghi vào Register). Khi sheet
`6.Entities` đã sửa theo bản này thì trỏ lại `erd.json`.

**Cẩn thận:** script **xoá rồi sinh lại** các gói `entity`, `enums`, `repository`, `service`,
`controller` (trừ `HealthController`). Khi đã có nghiệp vụ thật trong `service/impl`, phải sửa script cho
nó bỏ qua những tệp đã có — hoặc đừng chạy nữa.

## Chưa vào khung, cố ý

- **Question** chưa sửa: cần `concept` (để QuizAnswer cập nhật ConceptMastery) và `retiredAt` (câu đã dùng
  không xoá cứng). Hưng bảo để sau.
- **Studio** (Chapter, ChapterDraft, ContentReview, GameRelease, ReleaseChapter, VoiceClip) và ba vai
  Game Editor / Reviewer / Manager: làm sau khi chốt xong role cơ bản. `buildVersion` lúc đó thành khoá
  ngoại tới `GameRelease`.
