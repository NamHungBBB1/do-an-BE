# Dữ liệu mẫu cho demo — `seed_demo.py`

Nạp dữ liệu demo **chỉ qua API công khai** (không ghi thẳng DB), để buổi demo lặp lại được y hệt.
Python 3, chỉ thư viện chuẩn.

## Chạy (máy dev, H2 bộ nhớ)

```bash
# 1. Bật BE trên DB MỚI. MAIL_HOST rỗng → OTP in ra log; admin@demo.test tự có vai ADMIN khi xác minh.
mvn -q -DskipTests package
MAIL_HOST= ADMIN_EMAILS=admin@demo.test java -jar target/*.jar > be.log 2>&1 &

# 2. Seed (đọc OTP từ be.log)
python scripts/seed/seed_demo.py --base http://localhost:8080 --log be.log
```

Mật khẩu chung cho mọi tài khoản người lớn: `--password` > biến môi trường `SEED_PASSWORD` > sinh ngẫu nhiên.
Script **in mật khẩu và PIN đúng một lần ra terminal**, không ghi vào tệp nào. PIN trẻ luôn sinh ngẫu nhiên.

## Lặp lại được thế nào

Script **chỉ chạy trên DB mới**. Trước khi ghi gì, nó kiểm `GET /api/content/current`: đã có bản phát hành → dừng,
không ghi gì. Muốn seed lại: tắt BE rồi bật lại (H2 bộ nhớ là DB mới), chạy lại lệnh trên. Mỗi lần ra cùng dữ liệu
(cùng tài khoản, nhóm, bản 1.0.0, cùng chỉ số), chỉ mã slot / PIN / mật khẩu khác.

## Tạo ra gì

| Phần | Dữ liệu |
|---|---|
| Nội bộ | `admin@demo.test` (ADMIN), `editor@`, `reviewer@`, `manager@demo.test` (GAME_EDITOR / REVIEWER / MANAGER, admin cấp qua `/api/admin/roles/grant`) |
| Nội dung | CH01–CH03 từ `src/test/resources/content/` (cùng tệp `ContentSchemaTest` kiểm): editor lưu nháp + gửi duyệt, reviewer duyệt, manager phát hành → bản `1.0.0` |
| Phụ huynh | `parent@demo.test`, gói PARENT 3 tháng (admin cấp, không qua thanh toán), nhóm FAMILY "Nhà demo", 2 slot (Bé An, Bé Bình) |
| Giáo viên | `teacher@demo.test`, gói TEACHER 3 tháng, lớp CLASS "Lớp 8A demo", **xác nhận đồng ý phụ huynh trước** rồi mới mở 3 slot (Minh, Lan, Huy) |
| Chơi | Bé An xong CH01 + CH02 qua `/api/play/chapter` (chọn phương án đầu của mỗi cảnh chọn; mini-game đúng, cố ý sai 1 thẻ Cần / Muốn) |
| Quiz | 3 câu `DEMO-Q1..3` (pool `DEMO`, nhập qua `/api/admin/questions/import`, upsert theo `externalId`), một đề CLASS_TEST đã phát, Minh nộp bài |

Cuối cùng script tự đọc lại bằng API (bản hiện tại, báo cáo Bé An, báo cáo lớp); lệch là thoát với mã lỗi.

## Chặn nhầm môi trường

- URL chứa `kidzeconomy` (production) → luôn từ chối.
- Host khác `localhost` / `127.0.0.1` / `::1` → từ chối, trừ khi ghi rõ `--allow-host <host>` (staging).
- Tài khoản dùng miền `.test` (RFC 2606): mail không bao giờ tới người thật.

## Staging có mail thật

Bỏ `--log`: script hỏi OTP trên terminal cho từng tài khoản. Khi đó miền `demo.test` không nhận được mail, nên phải
sửa `DOMAIN` thành hộp thư mình đọc được (hoặc cấu hình BE gửi về một hộp thư bắt mail). BE phải chạy với
`ADMIN_EMAILS=admin@<miền đó>`.

## Chưa có

- Khối `chapters` / `concepts` trong `GET /api/slots/{id}/report` vẫn rỗng: BE chưa làm đợt 2 của báo cáo. Dữ liệu chơi
  (lựa chọn, kết quả mini-game) đã ghi, `progress` và `indicators` có số.
- Chỉ CH01–CH03 (bản trong repo). CH04–CH08 khuôn v3 chưa có trong repo BE.
