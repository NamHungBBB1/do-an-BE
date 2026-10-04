package com.doan.game.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Địa chỉ gửi mail, đọc từ app.mail.* (tức biến môi trường MAIL_FROM / MAIL_FROM_NAME trong
 * /etc/finteen.env trên máy chủ). Tách khỏi spring.mail.* vì phần đó là hạ tầng SMTP, còn
 * đây là "ai đứng ra gửi" — thứ người dùng nhìn thấy trong hộp thư.
 *
 * Record + constructor binding: không setter, nạp xong là không ai sửa được.
 *
 * Đăng ký bằng @EnableConfigurationProperties trong MailConfig chứ KHÔNG để @Component ở đây:
 * để @Component thì Spring tự chọn constructor để autowire hai tham số String TRƯỚC, và
 * không tìm thấy bean String nào để nạp → chết lúc khởi động, trước cả khi binding kịp chạy.
 */
@ConfigurationProperties(prefix = "app.mail")
public record AppMailProperties(String from, String fromName) {
}