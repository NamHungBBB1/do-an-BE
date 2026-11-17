# Slot và hạn mức — thiết kế chốt 17/11/2026

Tài liệu này là nguồn sự thật cho đợt sửa slot. Mọi số liệu dưới đây đã được chốt; chỗ nào ghi
**(mở)** là còn chờ Hưng/giảng viên xác nhận.

## 1. Quyết định đã chốt

| Mã | Quyết định |
|---|---|
| Q1 | Hạn mức gắn theo **tài khoản** (ví slot), không theo nhóm |
| Q2 | Parent: **4 slot** mặc định, mua thêm **+2/pack**, trần **10** |
| Q3 | Teacher: **40 slot** mặc định, mua thêm **+20/pack**, trần **200/kỳ** |
| Q4 | Giáo viên **mở nhiều lớp**, tự chia ví slot thành lớp; sĩ số mỗi lớp do giáo viên đặt |
| Q5 | Add-on **hết hạn cùng gói chính**; hạn mức **reset mỗi kỳ gói** |
| Q6 | Slot đã mở là **tiêu hao**: trả hay xoá đều **không hoàn lượt** |
| Q7 | Cấm xoá slot đã có bài làm; chỉ được trả slot, xoá hẳn do admin |
| Q8 | **1 phiên hoạt động/slot**: login mới làm token cũ mất hiệu lực |
| Q9 | Cấm **trùng tên** slot trong cùng nhóm, kể cả slot đã trả/đã xoá |
| Q10 | Bán add-on qua **PayOS** ngay |
| Q11 | **Bỏ hoàn tiền**: không merge PR #59; hoàn tiền xử lý ngoài hệ thống |

## 2. Mô hình ví slot

```
quota(kỳ) = base(plan) + extra(add-on đã mua, còn hạn)
đã dùng   = số slot được tạo trong kỳ hiện tại (mọi trạng thái)
còn lại   = quota − đã dùng
```

- `base`: `PARENT → 4`, `TEACHER → 40`, không có gói còn hạn → 0.
- `extra`: Parent tối đa 6 (3 pack × 2); Teacher tối đa 160 (8 pack × 20).
- **Kỳ** = khoảng hiệu lực của entitlement hiện tại (`startedOn..expiresOn`). Sang kỳ mới, `đã dùng`
  tự đếm lại từ 0 vì đếm theo `learner_slot.created_at >= startedOn`; không cần job reset.
- Xoá/trả slot **không** làm giảm `đã dùng` (Q6). Đóng lớp cũng vậy.
- Add-on không cộng dồn sang kỳ sau (Q5).

Hệ quả: `capacity` không còn là `slotLimit − count(ACTIVE)`.

## 3. Thay đổi mô hình dữ liệu

| Bảng | Thay đổi |
|---|---|
| `account` | không thêm cột; quota suy từ `entitlement` + `slot_addon` + `count(learner_slot)` |
| `learner_group` | **không đổi cột nào**; `slot_limit` đổi nghĩa thành **sĩ số giáo viên đặt** (CLASS), NULL với FAMILY |
| `learner_slot` | P1 chưa đổi; P2 thêm `token_version`, `seq`, giữ `display_name` khi WIPED |
| `slot_addon` | **mới**: `id`, `account_id`, `kind` (PARENT/TEACHER), `slots`, `expires_on`, `transaction_id` (unique), `created_at` |
| `transaction` | P3 thêm `purpose = ADDON`, `addon_packs` |

**Không phải DROP unique constraint.** Cách cho giáo viên mở nhiều lớp mà không đụng CSDL: giữ nguyên
`UNIQUE(owner_id, open_context)`, nhưng nhóm CLASS đang mở ghi `open_context = NULL`, chỉ FAMILY mới
ghi `open_context = 'FAMILY'`. Cả H2 lẫn PostgreSQL đều cho nhiều dòng NULL trong unique index nên
CLASS mở bao nhiêu lớp cũng được, còn FAMILY vẫn độc quyền một nhóm. "Nhóm đang mở" suy từ
`closed_at IS NULL`, không suy từ `open_context` nữa.

Lý do quan trọng: production đang chạy `ddl-auto=update`, bỏ một unique constraint khỏi entity **không**
tự xoá index dưới DB — phải ALTER TABLE tay giữa lúc đang chạy. Cách trên không cần migration phá huỷ,
chỉ thêm bảng `slot_addon` (Hibernate tự tạo).

## 4. Thay đổi API

| Endpoint | Thay đổi |
|---|---|
| `POST /api/groups` | CLASS: bỏ chặn 5001, nhận `plannedSize` (1..ví còn lại); FAMILY: như cũ |
| `GET /api/groups` | `slotLimit` = trần ví (FAMILY) hoặc sĩ số lớp (CLASS) |
| `GET /api/groups/{id}/capacity` | `min(plannedSize − ACTIVE, ví còn lại)`; FAMILY: ví còn lại |
| `PATCH /api/groups/{id}` | **mới**: sửa `plannedSize`, không nhỏ hơn số ACTIVE, không vượt ví |
| `POST /api/slots`, `/bulk` | kiểm ví (3006) và sĩ số lớp (3026); 3006 đổi message |
| `DELETE /api/slots/{id}` | P2: chặn khi có bài làm → 3027; admin dùng endpoint riêng |
| `POST /api/slots/login` | P2: tăng `token_version`, token cũ trả 401 |
| `GET /api/me/quota` | **mới (P1)**: base, extra, limit, used, remaining, kỳ gói |
| `POST /api/payments/addon` | **mới (P3)**: tạo link PayOS cho N pack |
| `POST /api/payments/webhook` | P3: nhánh `purpose = ADDON` → cấp `slot_addon`, idempotent |
| `GET /api/admin/addon-price`, `PUT` | **mới (P3)**: admin đặt giá 1 pack theo từng role |

Mã lỗi: `3026 CLASS_FULL` (P1, đã có), `3027 SLOT_HAS_ACTIVITY` (P2), `3028 ADDON_QUOTA_EXCEEDED` (P3).
`3006` đổi message thành "Đã hết slot — mua thêm slot hoặc dùng slot còn trống" (bỏ số 4/40 hardcode).

## 5. Thứ tự kiểm khi mở slot (giữ nguyên tinh thần cũ)

1. `groupId` bắt buộc, nhóm tồn tại → 1003
2. Nhóm thuộc người gọi → 3004
3. Nhóm chưa đóng → 5002
4. Gói còn hạn đúng loại → 3005
5. Lớp CLASS đã xác nhận đồng ý → 5003
6. Dữ liệu hợp lệ: tên ≤ 40 **và không trùng** (kể cả slot đã trả/xoá), PIN 6 số, badge ≤ 40 → 1001
7. Còn lượt trong ví → 3006
8. Chưa vượt sĩ số lớp → 3026 `CLASS_FULL`

Bulk: kiểm toàn bộ dữ liệu trước, rồi kiểm `quota còn lại` và `sĩ số` cho **cả lô**; thiếu một
điều kiện là từ chối cả lô, không mở nửa lô.

## 6. Chống gian lận

| Lỗ hổng | Cách chặn |
|---|---|
| Xoá slot để thi lại quiz | Cấm xoá khi đã có bài (3027); admin xoá phải ghi lý do |
| Xoá slot để giấu điểm | `display_name` giữ trong DB, API trả `null` + `seq`; báo cáo hiện "Học sinh #3 (đã xoá)" |
| Tạo lại cùng tên sau khi trả/xoá | Unique mềm `(group_id, display_name)` áp cả ARCHIVED/WIPED |
| Nhiều em dùng chung 1 slot | `token_version` trong JWT; login mới vô hiệu token cũ |
| Tái dùng mã slot | Giữ nguyên: WIPED đặt `code = NULL`, mã không cấp lại |
| Mở hàng loạt lớp để lách quota | Ví slot chặn theo tổng, trần 200/kỳ |

## 7. Việc phải sửa trong code hiện tại

| File | Sửa |
|---|---|
| `entity/LearnerGroup.java` | giữ nguyên cột; đổi nghĩa `slotLimit` thành sĩ số lớp, CLASS đang mở ghi `open_context = NULL` |
| `entity/LearnerSlot.java` | thêm `tokenVersion`, `seq`; không null `displayName` khi wipe |
| `service/impl/GroupServiceImpl.java` | bỏ `FAMILY_SLOT_LIMIT`/`CLASS_SLOT_LIMIT`; `openGroup` nhiều lớp; `closeGroup` không trả lượt |
| `service/impl/SlotServiceImpl.java` | kiểm quota ví + sĩ số; guard xoá; trùng tên; login tăng `tokenVersion` |
| `service/impl/PaymentServiceImpl.java` | nhánh ADDON, idempotent, cấp add-on; `stats` tách doanh thu add-on |
| `exception/ErrorCode.java` | sửa message 3006, thêm 3026 (P1), 3027/3028 (P2/P3) |
| `docs/schema.sql`, `docs/erd.md`, `docs/state-machines.md` | đồng bộ |

Test phải sửa: `GroupSlotFlowTest`, `GroupCloseBulkFlowTest`, `SlotLifecycleFlowTest`,
`SlotLoginFlowTest` (đang assert 4/40 và 3006). Test mới: `SlotQuotaFlowTest`,
`SlotMultiClassFlowTest`, `SlotAntiFraudFlowTest`, `SlotAddonPaymentTest`.

## 8. Chia đợt

| Đợt | Nội dung | Ước lượng |
|---|---|---|
| P1 | Schema + ví quota + nhiều lớp + sĩ số lớp + sửa 3006 + test | 2–3 ngày |
| P2 | Chống gian lận: guard xoá, giữ tên, trùng tên, `token_version`, `seq` | 1,5–2 ngày |
| P3 | PayOS add-on: giá, tạo link, webhook idempotent, cấp quota, báo cáo | 4–6 ngày |
| P4 | Đồng bộ tài liệu (schema/ERD/RP3), đóng #59, chạy full test | 1–2 ngày |

## 9. Điểm còn mở

- **(mở)** PayOS add-on đụng `Transaction` + CHECK constraint trên PostgreSQL → cần Hưng duyệt
  trước khi merge P3.
- **(mở)** Giá 1 pack do admin đặt (mặc định đề xuất: Parent 2 slot, Teacher 20 slot — giá nhập
  sau, không hardcode).
- **(mở)** Giáo viên hạ `plannedSize` xuống dưới số ACTIVE: chặn hay cho phép kèm cảnh báo.
