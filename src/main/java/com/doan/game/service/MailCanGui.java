package com.doan.game.service;

/**
 * Một lá mail cần gửi. Đây là SỰ KIỆN, không phải lệnh gửi: service chỉ phát ra, MailService
 * nghe và gửi sau khi transaction đã commit.
 *
 * Tách hai việc đó là bắt buộc. Bài học EXE201: gửi mail đồng bộ trong transaction làm
 * /register treo theo SMTP, và nếu transaction rollback thì người dùng vẫn nhận được mail
 * trỏ tới một token không tồn tại — họ bấm link rồi nhận "link không đúng".
 */
public record MailCanGui(String to, String subject, String body) {
}