# Slot và hạn mức — thiết kế v3, chốt 18/11/2026

Tài liệu này **thay thế bản v2** (và bản v1). Nguồn quyết định: Hưng 17–18/11/2026, bản chốt bổ
sung `chot-bo-sung-spec-slot-v2-2026-11-18.md` (tệp 18:45, bản 7,37 KB — bản 18:43 đã bị thay).
Kênh `#chung` là nơi công bố; tệp trong Discord là bản gốc.

**4 thay đổi so với v2:**

| Nhóm | Nội dung | Ảnh hưởng |
|---|---|---|
| **A** | Lớp CLASS phải **giữ chỗ tối thiểu `MIN_CLASS_SIZE = 5`**, chỗ giữ **trừ vào ví** | Công thức ví đổi: thêm `reserved` / `free` |
| **B** | Xoá slot = **xoá tên thật** (tên, PIN, cá nhân hoá → NULL), chỉ giữ `code` | Bỏ `seq`, bỏ "giữ display_name", thêm `PATCH /api/slots/{id}` |
| **C** | Luồng thanh toán viết lại rõ; **bỏ Q16** (không giới hạn pack/đơn) | Chỉ kiểm `packs ≥ 1` + trần `amount` |
| **D** | Dữ liệu cũ: **mạnh tay** — `UPDATE entitlement SET expires_on = '9999-12-31'` | Bỏ nhánh "để gói cũ tự hết hạn" |

> **Không xoá database, không rebuild.** Thay đổi schema vẫn chỉ gồm **2 bảng mới + 2 cột mới +
> 1 câu ALTER CHECK** (§3.1). Khác v2 duy nhất ở chỗ **không còn cột `seq`**.

## 1. Quyết định đã chốt

| Mã | Quyết định |
|---|---|
| Q1 | Hạn mức gắn theo **tài khoản** (ví slot), không theo nhóm |
| Q2 | Parent: **4 slot**, mua thêm **+2/pack** |
| Q3 | Teacher: **40 slot**, mua thêm **+20/pack** |
| Q4 | Giáo viên **mở nhiều lớp**, tự chia ví thành lớp; **sĩ số mỗi lớp do giáo viên đặt** |
| Q5 | Gói **vĩnh viễn**: không có ngày hết hạn, **không reset**, **không trần** add-on |
| Q6 | Slot đã mở là **tiêu hao vĩnh viễn**: xoá, đóng lớp đều **không hoàn lượt** |
| Q7 | **Được xoá slot kể cả đã có bài làm**; xoá là **không khôi phục** |
| Q8 | **1 phiên hoạt động/slot**: login mới vô hiệu token cũ |
| Q9 | ~~Cấm trùng tên kể cả slot đã xoá~~ → **sửa 18/11**: chỉ cấm trùng với slot **`ACTIVE`** cùng nhóm; slot đã xoá **không còn tên** nên không xét |
| Q10 | Add-on bán qua **PayOS**, giá do admin đặt, **vĩnh viễn** |
| Q11 | **Bỏ hoàn tiền**: xử lý ngoài hệ thống, hệ thống chỉ đối soát `needs_review` |
| Q12 | **Bỏ "trả slot"** (`POST /api/slots/{id}/return`); chỉ còn xoá |
| Q13 | Mỗi tài khoản **1 gói gốc mỗi loại** (PARENT, TEACHER); add-on chỉ mua khi đã có gói gốc |
| Q14 | ~~Xoá = bia mộ giữ tên~~ → **sửa 18/11**: xoá = **ẩn danh hoá thật**; giữ `code` + kết quả học ẩn danh (status `WIPED` sẵn có) |
| Q15 | Admin **không được xoá cứng** slot |
| Q16 | ~~1–20 pack/đơn~~ → **BỎ 18/11**: **không giới hạn số pack mỗi đơn**, chỉ `packs ≥ 1` |
| Q17 | **`MIN_CLASS_SIZE = 5`**: lớp CLASS phải giữ chỗ tối thiểu 5 slot, chỗ giữ trừ vào ví |
| Q18 | **`PATCH /api/slots/{id}`** đổi tên (và PIN): gõ nhầm thì sửa, không phải xoá mất slot |

## 2. Định nghĩa "vĩnh viễn" và công thức ví

> **Gói vĩnh viễn** = quyền dùng **không có ngày hết hạn**. Số slot là **tổng hạn mức trọn đời của
> tài khoản**, không reset theo tháng / quý / năm / kỳ thanh toán.

```
limit     = base(gói) + Σ slot_addon.slots
used      = số slot ĐÃ TẠO của tài khoản (mọi trạng thái, mọi nhóm)
reserved  = Σ trên các lớp CLASS ĐANG MỞ: max(0, plannedSize − createdInClass)
free      = max(0, limit − used − reserved)
```

- `base`: `PARENT → 4`, `TEACHER → 40`; không có gói gốc đúng loại → 0.
- `extra`: tổng `slot_addon.slots` của tài khoản theo `kind` — **không có trần**.
- `used` đếm **mọi dòng `learner_slot`** của tài khoản, **bất kể status** (ACTIVE, ARCHIVED,
  WIPED), **bất kể nhóm còn mở hay đã đóng**, **không có mốc ngày**. Xoá slot không giảm `used`.
- `createdInClass` đếm **mọi trạng thái** của lớp đó — slot đã xoá trong lớp **vẫn ăn chỗ** của lớp.
- Slot chỉ bị tính khi **thực sự được tạo**. Giáo viên khai `plannedSize = 40` mà mới tạo 5 slot
  thì `used = 5`, `reserved = 35`.
- Ví Parent và ví Teacher **tách riêng**; một tài khoản có thể có cả hai gói.

Ví dụ: giáo viên gói 40 + 3 pack (+60) → `limit = 100`. Đã tạo 37 slot (5 đã xoá) → `used = 37`.
Hai lớp đang mở: lớp A `plannedSize = 30` (đã tạo 30), lớp B `plannedSize = 10` (đã tạo 7) →
`reserved = 0 + 3 = 3` → `free = 100 − 37 − 3 = 60`.

### 2.1 Điều kiện từng thao tác

| Thao tác | Điều kiện | Lỗi |
|---|---|---|
| Mở lớp CLASS | `MIN_CLASS_SIZE ≤ plannedSize ≤ free`; **không cho bỏ trống** (FE gợi ý `min(40, free)`) | 1001 / 3006 |
| Mở slot trong lớp | `createdInClass < plannedSize` (lấy từ chỗ đã giữ, `free` **không đổi**) | 3026 |
| Mở slot gia đình (FAMILY) | `free ≥ 1` | 3006 |
| Sửa `plannedSize` | `max(MIN_CLASS_SIZE, createdInClass) ≤ mới`; phần tăng thêm `≤ free` | 1001 / 3006 |
| Đóng lớp | chỗ giữ **chưa dùng trả về `free`**; slot đã tạo vẫn tính `used` | — |
| Xoá slot | không đổi `used`; nếu là slot của lớp đang mở thì `createdInClass` giảm, **chỗ đó không trả về** (đã tiêu hao) | — |

Khoá dòng **tài khoản** trước khi tính `free` (§7). Bỏ luật v2 "bỏ trống `plannedSize` = `remaining`".

## 3. Mô hình dữ liệu

| Bảng | Thay đổi |
|---|---|
| `plan` | giữ cột; `months` **bỏ khỏi API** nhưng **không xoá cột** |
| `slot_addon_price` | **bảng mới**: `kind` (PK), `slots_per_pack` (2 / 20), `price`, `updated_at`, `updated_by_id` |
| `slot_addon` | **bảng mới**: `id`, `account_id`, `kind`, `slots`, `transaction_id` (unique), `created_at`; không có `expires_on` |
| `entitlement` | **không đổi cột**; gói mới ghi `expires_on = 9999-12-31` |
| `transaction` | thêm `packs` (nullable); `purpose` thêm giá trị **`ADDON`** |
| `learner_slot` | thêm `token_version` (NOT NULL default 0); **không thêm `seq`**; **giữ tên cột `wiped_at`**, **giữ status `WIPED`** |
| `learner_group` | không đổi cột; `slot_limit` = **sĩ số lớp** (`plannedSize`, CLASS), NULL với FAMILY |

### 3.1 Thay đổi schema tối thiểu — không rebuild

| # | Thay đổi | Cách làm | Phá dữ liệu? |
|---|---|---|---|
| 1 | `slot_addon` | Hibernate `ddl-auto=update` tự tạo | Không |
| 2 | `slot_addon_price` | Hibernate tự tạo | Không |
| 3 | `transaction.packs` | Hibernate thêm cột nullable | Không |
| 4 | `transaction.purpose` nhận `ADDON` | **ALTER CHECK** (PostgreSQL chặn giá trị mới) | Không |
| 5 | `learner_slot.token_version` | Hibernate thêm cột; default 0 | Không |
| 6 | `learner_slot.status` | **Không đổi enum**: vẫn `ACTIVE / ARCHIVED / WIPED` | Không |
| 7 | `learner_slot.wiped_at` | **Không đổi tên cột** | Không |

Câu ALTER duy nhất (chạy **trước** khi deploy jar):

```sql
ALTER TABLE transaction DROP CONSTRAINT transaction_purpose_check;
ALTER TABLE transaction ADD CONSTRAINT transaction_purpose_check
    CHECK (purpose IN ('NEW', 'RENEW', 'ADDON'));
```

Vì sao **không** thêm `DELETED` vào `SlotStatus`: production có
`learner_slot_status_check CHECK (status IN ('ACTIVE','ARCHIVED','WIPED'))`, mà `ddl-auto=update`
**không** sửa CHECK. Thêm giá trị mới sẽ làm mọi lần xoá slot ném lỗi 500. Giữ `WIPED` và đổi
**hành vi** (xoá thật tên/PIN) là đủ, không đụng CSDL.

Vì sao **không** thêm unique `(group_id, display_name)` ở DB: kiểm trùng tên phải so với slot
`ACTIVE`; unique ở DB sẽ áp cả dòng `WIPED` (tên đã NULL nên nhiều NULL vẫn thoả ở PostgreSQL, nhưng
dữ liệu cũ có thể đã trùng tên) → thêm unique có thể làm app **không khởi động được** (cơ chế N-03,
deploy tự rollback). Kiểm trùng tên ở **service**.

`expires_on = 9999-12-31` thay vì `NULL`: mọi câu `expiresOn >= today` hiện có chạy nguyên, không
NPE. Nhưng **xoá** `PlanExpiryReminderJob` và **bỏ** `expiringIn7Days` khỏi dashboard.

**Đổi entity phải qua `erd_0110.py` → `sinh-khung-be.py`**, không sửa tay (yêu cầu của nhóm).

### 3.2 Dữ liệu cũ — xử lý mạnh tay (toàn bộ là dữ liệu test, Hưng 18/11)

Thay toàn bộ §3.2 của v2:

| Bước | Việc |
|---|---|
| 1 | `pg_dump` trước (CI đã tự làm trong `ci-deploy.sh`, vẫn kiểm bản mới nhất) |
| 2 | Chạy ALTER CHECK ở §3.1 |
| 3 | `UPDATE entitlement SET expires_on = '9999-12-31';` — mọi gói thử thành vĩnh viễn. **Không** còn nhánh "để gói cũ tự hết hạn", **không** cần `used_baseline` |
| 4 | Slot cũ giữ nguyên. Tài khoản thử đã tạo quá ví thì `free = 0` — **chấp nhận**, mua pack để thử luồng |
| 5 | Lớp CLASS đang mở: `UPDATE learner_group SET slot_limit = GREATEST(5, <số slot đã tạo trong lớp>) WHERE ...` để `plannedSize` hợp lệ dưới luật Q17 |
| 6 | Slot `WIPED` cũ (đã null tên/mã) → giữ nguyên, không backfill |

`plan.months` còn cột nhưng không dùng; gói mới không ghi số tháng.

## 4. Vòng đời slot

| Trạng thái | Ai đặt | Ý nghĩa | API trả về |
|---|---|---|---|
| `ACTIVE` | mở slot | đang dùng, mã + PIN sống | đủ thông tin |
| `ARCHIVED` | **chỉ `closeGroup`** | nhóm/lớp đã kết thúc | đủ thông tin, mã chết (3009) |
| `WIPED` | chủ nhóm bấm xoá | đã xoá, ẩn danh, mất lượt vĩnh viễn | `displayName = null`, `code = null` |

**Khi xoá slot** (một transaction):

| Trường | Làm gì | Vì sao |
|---|---|---|
| `status` | `WIPED`, `wiped_at = now` | dấu vết duy nhất còn lại |
| `token_version` | `+= 1` | token trẻ cũ chết ngay (401) |
| `display_name`, `pin_hash`, `personalization`, `badge` | **NULL — xoá thật** | Luật Bảo vệ dữ liệu cá nhân 2025: người dùng có quyền yêu cầu xoá, dữ liệu trẻ em |
| `code` | **giữ** trong DB, API không trả | mã không cấp lại cho bé khác; đăng nhập mã cũ → 3007 |
| Kết quả học (8 bảng FK `slot_id`) | **giữ, ẩn danh** | số liệu báo cáo nhóm không đổi; dữ liệu ẩn danh không còn là dữ liệu cá nhân |
| Ví | `used` **không đổi** | xoá là mất slot (Q6) |

8 bảng có FK `slot_id`: `daily_summary`, `choice_event`, `run_state`, `achievement`,
`concept_mastery`, `quiz_result`, `group_report_row`, `mini_game_result`.

Xoá slot đã `WIPED` → 5004. Xoá slot `ARCHIVED` (nhóm đã đóng) → **được** (chỉ ẩn danh hoá, số báo
cáo đã đóng băng nên không đổi). Xoá **tài khoản** người lớn: **ngoài đợt này**, ghi vào việc sau.

## 5. Báo cáo sau khi xoá

- Báo cáo nhóm **không được lọc status**: liệt kê mọi slot của nhóm, gồm cả `WIPED`.
- Dòng của slot đã xoá hiện số liệu bình thường, nhãn **"Học sinh đã xoá"**; nếu nhiều dòng thì
  đánh số theo **thứ tự tạo** (`created_at`) — **không cần cột `seq`**.
- Nhóm đã đóng: số liệu lấy bản đóng băng (`group_report_row`); chỉ `display_name` / `status` đọc
  sống từ `learner_slot`, nên xoá sau khi đóng chỉ đổi nhãn, không đổi số.
- `GET /api/reports/slots/{id}` của slot đã xoá: trả số liệu, `learner.displayName = null`,
  `learner.code = null`, `status = WIPED`.
- Báo cáo **không được** lộ tên qua bảng phụ (`group_report_row` chỉ lưu số, không lưu tên).

## 6. API

| Endpoint | Thay đổi |
|---|---|
| `GET /api/plans` | công khai: mỗi gói trả `kind`, `price`, `addonSlots`, `addonPrice`, `updatedAt` |
| `PUT /api/admin/plans/{kind}` | body `{price}`; **bỏ `months`** |
| `PUT /api/admin/addon-price/{kind}` | **mới**: body `{price}`, `slots_per_pack` cố định 2 / 20 |
| `POST /api/payments` | mua gói vĩnh viễn; đã có gói cùng loại → **4008** |
| `POST /api/payments/addon` | **mới**: body `{kind, packs}` (`packs ≥ 1`, **không trần**; trần là `amount ≤ 50.000.000`) |
| `POST /api/payments/webhook` | nhánh `purpose = ADDON` → cấp `slot_addon`, idempotent theo `transaction_id` |
| `POST /api/groups` | CLASS: nhiều lớp, `plannedSize` **bắt buộc** `≥ 5` và `≤ free`; FAMILY: 1 nhóm |
| `GET /api/groups` | `slotLimit` = `limit` của ví (FAMILY) hoặc `plannedSize` (CLASS) |
| `GET /api/groups/{id}/capacity` | CLASS: `plannedSize − createdInClass` (chỗ này đã nằm trong `reserved`, **không** trừ `free` lần nữa); FAMILY: `free` |
| `PATCH /api/groups/{id}` | sửa `plannedSize`: `max(5, createdInClass) ≤ mới`, phần tăng `≤ free` |
| `POST /api/slots`, `/bulk` | FAMILY: `free ≥ 1` (3006); CLASS: `createdInClass < plannedSize` (3026) |
| `PATCH /api/slots/{id}` | **mới (Q18)**: đổi tên (+ PIN); tên không trùng slot `ACTIVE` khác trong nhóm |
| `POST /api/slots/{id}/return` | **XOÁ BỎ** (Q12) |
| `DELETE /api/slots/{id}` | cho phép cả khi có bài làm, cả slot ARCHIVED → `WIPED` + ẩn danh hoá |
| `POST /api/slots/login` | tăng `token_version` của slot; token cũ → 401 |
| `GET /api/me/quota` | `context`, `plan`, `base`, `extra`, `limit`, `used`, `reserved`, `free`; **bỏ `termStart`/`termEnd`**; thêm `wiped` (số slot đã xoá, chỉ để hiển thị) |

Mã lỗi:

| Mã | Dùng cho |
|---|---|
| `3005 PLAN_REQUIRED` | chưa có gói gốc đúng loại (mở nhóm / mua add-on) |
| `3006 SLOT_LIMIT_REACHED` | `free = 0` hoặc `plannedSize > free` |
| `3026 CLASS_FULL` | lớp đã đủ sĩ số (`createdInClass ≥ plannedSize`) |
| `3027` | **để trống** — không cần `SLOT_HAS_ACTIVITY` nữa vì Q7 cho xoá |
| `4002 PLAN_PRICE_NOT_SET` | admin chưa đặt giá gói **hoặc** giá pack |
| `4008 PLAN_ALREADY_OWNED` | **mới**: đã có gói gốc loại này |
| `5004 SLOT_NOT_ACTIVE` | xoá slot đã `WIPED` |
| `1001 VALIDATION_FAILED` | `packs < 1`, `amount` vượt `MAX_PRICE_VND`, `plannedSize < 5`, tên trùng slot ACTIVE |

## 7. Thứ tự kiểm và khoá

**Mở lớp CLASS:**

1. Có gói gốc `TEACHER` → 3005
2. `plannedSize` bắt buộc, `≥ MIN_CLASS_SIZE` → 1001
3. Khoá dòng tài khoản, tính `free` → `plannedSize ≤ free` → 3006

**Mở slot:**

1. `groupId` bắt buộc, nhóm tồn tại → 1003
2. Nhóm thuộc người gọi → 3004
3. Nhóm chưa đóng → 5002
4. Có gói gốc đúng loại → 3005
5. Lớp CLASS đã xác nhận đồng ý phụ huynh → 5003
6. Dữ liệu hợp lệ: tên ≤ 40 **và không trùng slot `ACTIVE`** cùng nhóm, PIN 6 số, badge ≤ 40 → 1001
7. FAMILY: `free ≥ 1` → 3006; CLASS: `createdInClass < plannedSize` → 3026

Bulk: kiểm toàn bộ dữ liệu trước, rồi kiểm chỗ cho **cả lô**; thiếu một điều kiện là từ chối cả lô
(CLASS: `createdInClass + số slot trong lô ≤ plannedSize`; FAMILY: `số slot trong lô ≤ free`).

Khoá dòng **tài khoản** trước khi tính `free` (không chỉ khoá nhóm): hai request mở lớp/slot ở hai
nhóm khác nhau cùng đọc `free = 5` sẽ cùng chèn. Thứ tự khoá: nhóm → tài khoản.

## 8. Thanh toán và giá

**Hai loại hàng**, cùng một luồng PayOS:

| Hàng | Ai mua | Điều kiện | Nhận được | `purpose` |
|---|---|---|---|---|
| Gói gốc PARENT / TEACHER | tài khoản đã xác thực email | chưa có gói gốc loại đó (có rồi → 4008) | `base` 4 / 40 slot, **vĩnh viễn** | `NEW` |
| Gói thêm (pack) | chủ gói gốc cùng loại (chưa có → 3005) | `packs ≥ 1`, **không giới hạn số pack mỗi đơn** | +2 / +20 slot mỗi pack, **vĩnh viễn** | `ADDON` |

`RENEW` không còn sinh ra (giữ trong enum cho dòng cũ).

**Luồng một đơn**

1. Người dùng chọn hàng (+ số pack) → `POST /api/payments` hoặc `/payments/addon`. Chỉ kiểm
   `packs ≥ 1` và `amount` không vượt mức PayOS / `MAX_PRICE_VND` (chống tràn số — **không phải**
   hạn mức).
2. BE lấy **giá hiện hành** (admin đặt; chưa đặt → 4002), tính `amount = giá gói` hoặc
   `packs × giá pack`, **chép vào đơn** (đổi giá sau không ảnh hưởng đơn cũ). Tạo đơn `PENDING` +
   link QR PayOS, link sống **24 giờ** (`LINK_TTL`). Đã có đơn `PENDING` cùng hàng + cùng số pack +
   cùng giá còn hạn → **trả lại đúng đơn đó**, không tạo QR thứ hai.
3. Người dùng trả tiền → PayOS gọi webhook (đã ký) → BE kiểm chữ ký + số tiền:
   - khớp → `PAID` + **cấp hàng trong cùng transaction DB**: gói gốc → dòng `entitlement`
     (`expires_on = 9999-12-31`); pack → dòng `slot_addon` (`transaction_id` UNIQUE ⇒ webhook gọi
     lại không cấp hai lần);
   - lệch tiền → đơn giữ `PENDING`, `needs_review = true` + ghi chú, admin đối soát tay (luật P-01).
4. Hết 24 giờ không trả / người dùng huỷ → `FAILED`. Tiền về **sau** khi đã `FAILED` → vẫn cấp hàng
   + ghi chú (luật "trả muộn" 09/10). Riêng gói gốc trả muộn mà tài khoản **đã có** gói gốc từ đơn
   khác → **không cấp trùng**, đánh `needs_review` để hoàn tay.
5. Gửi mail biên nhận: hàng, số slot cộng thêm, **tổng ví sau khi cộng**, số tiền, mã đơn.

**Trạng thái đơn** chỉ đi tới: `PENDING → PAID` hoặc `PENDING → FAILED` (+ `FAILED → PAID` khi trả
muộn). Không lùi.

**Không có**: hoàn tiền trong hệ thống (ngoài hệ thống, đối soát bằng `needs_review` — Q11), gia
hạn, nhắc hết hạn (xoá `PlanExpiryReminderJob`).

**Giá đề xuất** (admin đổi được bất cứ lúc nào, không hardcode):

| Hạng mục | Giá |
|---|---|
| Gói Parent vĩnh viễn — 4 slot | 399.000 đ |
| Gói Teacher vĩnh viễn — 40 slot | 1.990.000 đ |
| Pack Parent — +2 slot | 99.000 đ |
| Pack Teacher — +20 slot | 399.000 đ |

- **Ai đặt giá:** chỉ tài khoản `SCOPE_ADMIN` (admin đầu tiên từ biến `ADMIN_EMAILS`, có ghi
  `role_grant_log`; admin sau do admin khác cấp). Không seed giá → chưa PUT thì mọi lượt mua trả
  **4002**.
- **Pack là đơn vị bán, không phải hạn mức.** 1 pack = 2 slot (Parent) hoặc 20 slot (Teacher);
  `amount = packs × giá pack` (3 pack Parent = +6 slot).
- Kiểm giá: `> 0` và `≤ 50.000.000` (`MAX_PRICE_VND`). Kiểm `amount` **trước** khi gọi PayOS.
- Dashboard: tách **doanh thu gói gốc / pack**, lọc đơn `needs_review`.
- Người dùng thấy trang **"Ví slot"**: đã có / đã dùng / **đang giữ cho lớp** / còn trống; nút
  "Mua thêm slot" (chọn số pack, thấy tổng tiền trước khi ra QR).

## 9. Chống gian lận

| Lỗ hổng | Cách chặn |
|---|---|
| Xoá slot để lấy lại lượt | `used` đếm mọi status, không mốc ngày; xoá không hoàn (Q6) |
| Xoá slot để giấu điểm | Báo cáo lọc theo **nhóm**, không lọc status; nhãn "Học sinh đã xoá" |
| Xoá để thi lại quiz | Xoá không hoàn slot → muốn thi lại phải mua pack; dữ liệu bài vẫn nằm trong báo cáo |
| Tái dùng mã slot | **Giữ `code`** khi xoá; login tra mã ra slot `WIPED` → 3007, mã không cấp lại |
| Nhiều em dùng chung 1 slot | `token_version`: login mới tăng số, token cũ 401; TTL token trẻ 8 giờ |
| Token sống sau khi xoá slot | Decoder xử lý cả `typ = SLOT`: sai `token_version` hoặc status ≠ ACTIVE → 401 ngay |
| **Spam lớp rỗng để ôm chỗ** | **`MIN_CLASS_SIZE = 5` + `reserved` trừ thẳng ví**: 40 slot → tối đa 8 lớp |
| **Mở lớp rồi đóng để lấy lại chỗ** | Đóng lớp chỉ trả **chỗ giữ chưa dùng**; slot đã tạo vẫn tính `used` |
| Mở nhiều lớp để lách hạn mức | Ví là tổng của tài khoản, không phải của từng lớp |
| Admin xoá cứng để hoàn ví | Không có endpoint hard delete; ẩn danh hoá như chủ nhóm |
| Sửa giá rồi bán lại | `transaction.amount` chụp lúc mua; log giá ở admin (đề xuất thêm bảng log) |

## 10. Việc phải sửa trong code

| File | Sửa |
|---|---|
| `service/SlotQuotaService.java`, `SlotQuota.java` | còn `base/extra/limit/used/reserved/free`; bỏ trần + mốc kỳ |
| `service/impl/SlotQuotaServiceImpl.java` | đếm mọi slot của tài khoản; tính `reserved` trên lớp CLASS đang mở; bỏ mốc kỳ |
| `repository/EntitlementRepository.java` | xoá `findActiveTermStart/End`, `countExpiringBetween`, `findTopBy...ExpiresOnDesc` |
| `service/EntitlementFactory.java` | thêm `createPermanent` (sentinel `9999-12-31`); **giữ** `create(months)` cho admin cấp tay |
| `service/PlanExpiryReminderJob.java` | **xoá file** |
| `service/PlanServiceImpl.java` | bỏ `months` khỏi API; thêm CRUD giá add-on |
| `DTO/response/PlanResponse.java` | bỏ `months`, thêm `addonSlots`, `addonPrice` |
| `entity/SlotAddon.java`, `repository/SlotAddonRepository.java` | bỏ `expiresOn`; `sumSlots(account, kind)` |
| `entity/SlotAddonPrice.java`, repository | **mới** |
| `entity/LearnerSlot.java` | thêm `tokenVersion`; **không** thêm `seq`; `wipeSlot` null `displayName`/`pinHash`/`personalization`/`badge`, **giữ** `code`, tăng `tokenVersion` |
| `service/impl/SlotServiceImpl.java` | bỏ `returnSlot`; thêm `renameSlot` (PATCH); trùng tên chỉ so slot `ACTIVE`; login tăng `tokenVersion` |
| `controller/SlotController.java` | thêm `PATCH /api/slots/{id}`; bỏ `POST /{id}/return` |
| `security/RevocationAwareJwtDecoder.java` | nhánh `typ = SLOT`: tra slot, kiểm `token_version` + status |
| `service/TokenService.java` | phát claim `tv` cho token SLOT |
| `service/impl/GroupServiceImpl.java` | `MIN_CLASS_SIZE`, `reserved`, `free`; bỏ mọi khái niệm kỳ; bỏ "bỏ trống = remaining" |
| `service/impl/PaymentServiceImpl.java` | `purpose = ADDON`, cấp add-on, 4008, chặn mua trùng gói gốc, trả muộn, mail biên nhận |
| `service/impl/AdminServiceImpl.java`, `AdminDashboardResponse` | bỏ `expiringIn7Days`; tách doanh thu gói gốc / pack; lọc `needs_review` |
| `service/impl/ReportServiceImpl.java` | nhãn "Học sinh đã xoá", không lọc status |
| `exception/ErrorCode.java` | thêm 4008; 3006 giữ message mới; 3027 để trống |

Test phải **viết lại**: `SlotQuotaFlowTest` (bỏ kỳ gói, thêm `reserved`/`free`), `SlotLifecycleFlowTest`
(bỏ `/return`, xoá có bài vẫn giữ báo cáo, PATCH tên), `GroupSlotFlowTest` (`MIN_CLASS_SIZE`),
`GroupCloseBulkFlowTest`, `SlotLoginFlowTest` (assert token chết). Test mới: `SlotAddonPaymentTest`,
`SlotDeleteFraudTest`.

Ghi chú: 2 commit P1 cũ (`fa3a373`, `1bb0cfd`, nhánh `feat/slot-quota`) làm theo mô hình **theo
kỳ**; phần nhiều lớp + `plannedSize` dùng lại được, phần quota/`termStart-termEnd` phải sửa lại.
Nhánh này **chưa đẩy GitHub** — cần push để nhóm soát.

## 11. Test chấp nhận

| # | Kịch bản | Kỳ vọng |
|---|---|---|
| 1 | Parent mở nhóm FAMILY, mở 4 slot | 200; slot 5 → 3006 |
| 2 | Parent mua 1 pack rồi mở slot 5, 6 | 200; slot 7 → 3006 |
| 3 | Xoá slot 1 rồi mở slot mới | **3006** (không hoàn lượt) |
| 4 | Xoá slot đã có bài làm | 200; báo cáo nhóm vẫn có dòng đó, nhãn "Học sinh đã xoá" |
| 5 | Login bằng mã của slot đã xoá | 3007 |
| 6 | Mã slot đã xoá không được cấp lại | mở 500 slot, không mã nào trùng |
| 7 | Trẻ đang có token, chủ nhóm xoá slot | request kế tiếp của trẻ → 401 |
| 8 | Login lần hai cùng slot | token cũ → 401 |
| 9 | Teacher 40 slot, mở lớp `plannedSize = 30` rồi lớp 10 | 200; `free = 0`; mở lớp thứ ba → 3006 |
| 10 | Mở lớp với `plannedSize = 4` | 1001 |
| 11 | Mở slot thứ 31 trong lớp `plannedSize = 30` | 3026 |
| 12 | `PATCH plannedSize` xuống dưới `createdInClass` | 1001 |
| 13 | `PATCH plannedSize` tăng vượt `free` | 3006 |
| 14 | Đóng lớp còn 3 chỗ giữ chưa dùng | `free` tăng 3; `used` không đổi |
| 15 | Mua gói PARENT lần hai | 4008 |
| 16 | Mua add-on khi chưa có gói gốc | 3005 |
| 17 | Webhook gọi lại cùng `orderCode` | cấp add-on **một** lần |
| 18 | `packs = 0` | 1001 |
| 19 | `packs` lớn đến mức `amount > 50.000.000` | 1001, **không** gọi PayOS |
| 20 | Admin chưa đặt giá pack | 4002 |
| 21 | Slot đã xoá vẫn tính vào `used` của `/api/me/quota` | `used` không đổi |
| 22 | `PATCH /api/slots/{id}` đổi tên trùng slot `ACTIVE` khác | 1001 |
| 23 | Đơn `FAILED` rồi tiền về (trả muộn) | `FAILED → PAID`, cấp hàng, ghi chú |
| 24 | Trả muộn gói gốc khi đã có gói gốc từ đơn khác | **không** cấp trùng, `needs_review` |

## 12. Chia đợt và triển khai

| Đợt | Nội dung | Ước lượng |
|---|---|---|
| P0 | Chốt v3; chạy ALTER CHECK ở staging; chạy §3.2 trên DB test | 0,5 ngày |
| P1 | Ví vĩnh viễn: entitlement sentinel, bỏ job/trần/kỳ, `reserved`/`free`, `/api/me/quota`, test | 2 ngày |
| P2 | Vòng đời slot: bỏ `/return`, `PATCH` đổi tên, xoá ẩn danh, token chết, báo cáo, test | 1,5–2 ngày |
| P3 | PayOS add-on: bảng giá, mua, webhook idempotent, trả muộn, mail, admin API, dashboard | 4–6 ngày |
| P4 | Đồng bộ `docs/diagrams/schema.sql` / `erd.md` / `state-machines.md` / RP3, full test | 1–2 ngày |

**Checklist deploy:**

1. `pg_dump` (CI đã tự làm trong `ci-deploy.sh`, vẫn kiểm bản mới nhất).
2. Chạy ALTER CHECK ở §3.1 **trước** khi thay jar.
3. Deploy jar; Hibernate tự thêm `slot_addon`, `slot_addon_price`, `transaction.packs`,
   `learner_slot.token_version`.
4. `UPDATE entitlement SET expires_on = '9999-12-31';` (§3.2 bước 3).
5. `UPDATE learner_group SET slot_limit = GREATEST(5, <slot đã tạo>)` cho lớp CLASS đang mở.
6. Đặt giá: `PUT /api/admin/plans/PARENT`, `/TEACHER`, `PUT /api/admin/addon-price/PARENT`,
   `/TEACHER`.
7. `GET /api/plans` xác nhận 4 con số.
8. Kiểm health + một lượt mua thử trên PayOS sandbox (gói + pack), kiểm webhook gọi lại.

## 13. Điểm còn mở

- **(mở)** Giá 4 hạng mục ở §8 là đề xuất; nhóm chốt số cuối.
- **(mở)** Có thêm bảng **log giá** (ai đổi, từ bao nhiêu → bao nhiêu, lúc nào) hay chỉ giữ
  `updated_by_id` như hiện tại.
- **(mở)** Xoá **tài khoản người lớn** (quyền xoá dữ liệu theo luật) — **ngoài đợt này**, làm sau.
- **(đã đóng)** PayOS add-on + ALTER CHECK: Hưng duyệt 17/11 qua tệp bổ sung.
- **(đã đóng)** P1 (18/11): ví vĩnh viễn + `reserved`/`free` + sentinel `9999-12-31` đã code, 238 test xanh.
  Còn lại: `months` của **admin cấp tay** vẫn giữ (công cụ cấp thử / xử lý khiếu nại) — khác với gói
  mua qua PayOS là vĩnh viễn.
- **(đã đóng)** `used_baseline`: không cần, vì §3.2 UPDATE toàn bộ entitlement thành vĩnh viễn và
  chấp nhận `free = 0` với tài khoản thử đã dùng quá ví.
- **(đã đóng)** PII sau khi xoá: xoá thật (`display_name`, PIN, cá nhân hoá, badge → NULL), không
  cần purge thủ công.
