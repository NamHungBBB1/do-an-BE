# Chốt bổ sung cho spec slot v2 của Thanh (18/11/2026)

Áp lên `slot-quota (2).md`. Chỗ nào khác v2 thì **theo tệp này**. Nguồn quyết định: Hưng 17–18/11 (terminal).

## A. Không trần — nhưng lớp phải "giữ chỗ" tối thiểu (chống spam lớp)

- **Không có trần tổng** slot (Hưng): mua bao nhiêu pack cũng được — giữ Q5 của v2.
- **Mỗi lớp CLASS phải giữ chỗ tối thiểu `MIN_CLASS_SIZE = 5` slot** (hằng số, đổi được) và **chỗ giữ trừ thẳng vào ví**. Lớp rỗng không mở
  được nữa; số lớp mở được bị chặn tự nhiên bởi ví (40 slot → tối đa 8 lớp).
- Công thức (thay §2 v2):

```
limit      = base + Σ slot_addon.slots
used       = số slot đã tạo của tài khoản (mọi trạng thái, mọi nhóm)            -- như v2
reserved   = Σ trên các lớp CLASS ĐANG MỞ: max(0, plannedSize − createdInClass)  -- chỗ giữ chưa dùng
free       = max(0, limit − used − reserved)
```
  `createdInClass` đếm **mọi trạng thái** (slot đã xoá trong lớp vẫn ăn chỗ của lớp đó — xoá là mất).

| Thao tác | Điều kiện | Lỗi |
|---|---|---|
| Mở lớp CLASS | `MIN_CLASS_SIZE ≤ plannedSize ≤ free` (bỏ trống = không cho; FE gợi ý `min(40, free)`) | 1001 / 3006 |
| Mở slot trong lớp | `createdInClass < plannedSize` (lấy từ chỗ đã giữ, `free` không đổi) | 3026 |
| Mở slot gia đình | `free ≥ 1` | 3006 |
| Sửa `plannedSize` | `max(MIN_CLASS_SIZE, createdInClass) ≤ mới`, phần tăng thêm `≤ free` | 1001 / 3006 |
| Đóng lớp | chỗ giữ chưa dùng **trả về `free`**; slot đã tạo vẫn tính `used` | — |

- Khoá dòng tài khoản trước khi tính `free` (như v2 §7). Bỏ "bỏ trống `plannedSize` = remaining" của v2.

## B. Xoá slot — chốt (thay Q9, Q14, §4, §5, §9 dòng "giấu điểm/trùng tên", §13 dòng PII)

Một thao tác duy nhất: **Xoá** (`DELETE /api/slots/{id}`), không còn "trả slot" (giữ Q12), cho cả slot đã có bài (giữ Q7).

| Khi xoá | Làm gì | Vì sao |
|---|---|---|
| `status` | `WIPED`, `wiped_at = now`, `token_version += 1` | bé đăng xuất ngay (token cũ 401) |
| `display_name`, `pin_hash`, `personalization`, `avatar/badge` | **NULL — xoá thật** | Luật Bảo vệ dữ liệu cá nhân 2025: người dùng có quyền yêu cầu xoá; dữ liệu trẻ em |
| `code` | **giữ** trong DB, API không trả | mã không cấp lại cho bé khác; đăng nhập mã cũ → 3007 |
| Kết quả học (8 bảng FK `slot_id`) | **giữ, ẩn danh** (không còn tên) | số liệu báo cáo nhóm không đổi; dữ liệu ẩn danh không còn là dữ liệu cá nhân |
| Ví | `used` không đổi | xoá là mất slot (Q6) |

- **Báo cáo nhóm**: vẫn có dòng của slot đã xoá, nhãn **"Học sinh đã xoá"** (đánh số theo thứ tự tạo nếu nhiều dòng). Không cần cột `seq`.
- **Trùng tên**: chỉ cấm trùng với slot **đang dùng (ACTIVE)** trong cùng nhóm; slot đã xoá không còn tên nên không xét. Bỏ Q9 bản "kể cả slot đã xoá".
- Bỏ khỏi v2: giữ `display_name` khi xoá, cột `seq`, "purge thủ công ngoài hệ thống", dòng chống gian lận "xoá để giấu điểm / tạo lại tên". Lý do: xoá đã
  mất slot (muốn tạo lại phải mua), báo cáo vẫn giữ dòng ẩn danh nên không giấu được điểm.
- **Thêm `PATCH /api/slots/{id}`** đổi tên (+ đổi PIN như cũ): gõ nhầm thì đổi tên, không phải xoá mất slot.
- Xoá slot `WIPED` lần hai → 5004. Admin không có xoá cứng (giữ Q15). Xoá cả **tài khoản** người lớn: ngoài đợt này, ghi vào việc sau.

## C. Nghiệp vụ thanh toán — bản rõ ràng

**Hai loại hàng**, cùng một luồng PayOS:

| Hàng | Ai mua | Điều kiện | Nhận được | `purpose` |
|---|---|---|---|---|
| Gói gốc PARENT / TEACHER | tài khoản đã xác thực email | chưa có gói gốc loại đó (có rồi → 4008) | `base` 4 / 40 slot, **vĩnh viễn** | `NEW` |
| Gói thêm (pack) | chủ gói gốc cùng loại (chưa có → 3005) | `packs ≥ 1`, **không giới hạn số pack mỗi đơn** (Hưng 18/11 bỏ Q16) | +2 / +20 slot mỗi pack, **vĩnh viễn** | `ADDON` |

`RENEW` không còn sinh ra (giữ trong enum cho dòng cũ).

**Luồng một đơn**

1. Người dùng chọn hàng (+ số pack) → `POST /api/payments` hoặc `/payments/addon`. **Bỏ Q16 (1–20 pack/đơn)**: chỉ kiểm `packs ≥ 1` và
   `amount` không vượt mức PayOS / `MAX_PRICE_VND` (kiểm số tiền có sẵn, chống tràn số — không phải hạn mức).
2. BE lấy **giá hiện hành** (admin đặt; chưa đặt → 4002), tính `amount = giá gói` hoặc `packs × giá pack`, **chép vào đơn** (đổi giá sau không ảnh
   hưởng đơn cũ). Tạo đơn `PENDING` + link QR PayOS, link sống **24 giờ** (giữ `LINK_TTL`).
   Đã có đơn `PENDING` cùng hàng + cùng số pack + cùng giá còn hạn → trả lại đúng đơn đó (không tạo QR thứ hai).
3. Người dùng trả tiền → PayOS gọi webhook (đã ký) → BE kiểm chữ ký + số tiền:
   - khớp → `PAID` + **cấp hàng trong cùng transaction DB**: gói gốc → dòng `entitlement` (`expires_on = 9999-12-31`); pack → dòng `slot_addon`
     (`transaction_id` UNIQUE ⇒ webhook gọi lại không cấp hai lần);
   - lệch tiền → đơn giữ `PENDING`, `needs_review = true` + ghi chú, admin đối soát tay (giữ luật P-01 10/10).
4. Hết 24 giờ không trả / người dùng huỷ → `FAILED`. Tiền về **sau** khi đã `FAILED` → vẫn cấp hàng + ghi chú (giữ luật "trả muộn" 09/10).
   Riêng gói gốc trả muộn mà tài khoản đã có gói gốc từ đơn khác → **không cấp trùng**, đánh `needs_review` để hoàn tay.
5. Gửi mail biên nhận: hàng, số slot cộng thêm, tổng ví sau khi cộng, số tiền, mã đơn.

**Trạng thái đơn** chỉ đi tới: `PENDING → PAID` hoặc `PENDING → FAILED` (+ `FAILED → PAID` khi trả muộn). Không lùi.

**Không có**: hoàn tiền trong hệ thống (ngoài hệ thống, đối soát bằng `needs_review` — giữ Q11), gia hạn, nhắc hết hạn (xoá `PlanExpiryReminderJob`).

**Admin**: đặt 4 giá (gói gốc PARENT/TEACHER, pack PARENT/TEACHER), `> 0` và `≤ 50.000.000`; dashboard tách doanh thu gói gốc / pack; lọc đơn `needs_review`.

**Người dùng thấy**: trang "Ví slot" — đã có / đã dùng / đang giữ cho lớp / còn trống; nút "Mua thêm slot" (chọn số pack, thấy tổng tiền trước khi ra QR).

## D. Dữ liệu cũ — mạnh tay (toàn bộ là dữ liệu test, Hưng 18/11)

Thay §3.2 v2 bằng:
1. `pg_dump` trước (CI tự làm, vẫn kiểm).
2. Chạy SQL sửa CHECK `transaction_purpose_check` thêm `ADDON` (như v2 §3.1).
3. `UPDATE entitlement SET expires_on = '9999-12-31';` — mọi gói thử thành vĩnh viễn, không cần nhánh "để tự hết hạn".
4. Slot cũ giữ nguyên; tài khoản thử đã tạo quá ví thì `free = 0` — chấp nhận (mua pack để thử luồng). Không cần cột `used_baseline`.
5. `learner_group.slot_limit` cũ của lớp: đặt `plannedSize = max(5, số slot đã tạo trong lớp)` cho lớp đang mở.
