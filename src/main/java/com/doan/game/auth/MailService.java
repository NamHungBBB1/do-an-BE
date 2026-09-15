package com.doan.game.auth;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Gửi mail xác thực.
 *
 * Ba quyết định ở đây đều rút từ vết đau của EXE201:
 *
 * 1. **@Async** — gửi mail KHÔNG được nằm trong luồng của `/register`. BE của EXE201
 *    gửi đồng bộ ngay trong transaction đăng ký, SMTP chậm là người dùng ngồi chờ,
 *    SMTP chết là đăng ký chết theo.
 * 2. **Nuốt lỗi, chỉ log** — mail hỏng thì tài khoản vẫn phải tạo xong. Người dùng
 *    còn nút "gửi lại"; mất luôn tài khoản thì không cứu được.
 * 3. **Chưa cấu hình SMTP thì IN LINK RA LOG** thay vì nổ. Team FE chạy ở máy không
 *    có tài khoản Brevo, không có đường này thì không ai thử được luồng xác thực.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    // ObjectProvider chứ không phải JavaMailSender thẳng: Spring Boot CHỈ tạo bean đó khi
    // spring.mail.host có giá trị. Ở máy dev và trong test thì host rỗng -> không có bean,
    // tiêm thẳng là cả context chết lúc khởi động.
    private final ObjectProvider<JavaMailSender> sender;
    private final String host;
    private final String from;
    private final String fromName;

    public MailService(ObjectProvider<JavaMailSender> sender,
                       @Value("${spring.mail.host:}") String host,
                       @Value("${app.mail.from}") String from,
                       @Value("${app.mail.from-name}") String fromName) {
        this.sender = sender;
        this.host = host;
        this.from = from;
        this.fromName = fromName;
    }

    @Async
    public void sendVerification(String to, String displayName, String link) {
        JavaMailSender s = sender.getIfAvailable();
        if (s == null || host == null || host.isBlank()) {
            log.warn("""
                    CHƯA CẤU HÌNH SMTP — không gửi mail. Link xác thực cho {}:
                    {}""", to, link);
            return;
        }
        try {
            MimeMessage msg = s.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, false, StandardCharsets.UTF_8.name());
            h.setFrom(from, fromName);
            h.setTo(to);
            h.setSubject("Xác thực email cho tài khoản FinTeen");
            h.setText(body(displayName, link), true);
            s.send(msg);
            log.info("Đã gửi mail xác thực tới {}", to);
        } catch (Exception e) {
            // Không ném lên: tài khoản đã tạo xong rồi, người dùng còn nút gửi lại.
            log.error("Gửi mail xác thực tới {} hỏng: {}", to, e.toString());
        }
    }

    private static String body(String name, String link) {
        return """
                <div style="font-family:'Segoe UI',Roboto,Arial,sans-serif;font-size:15px;color:#1f2937;line-height:1.6">
                  <p>Chào %s,</p>
                  <p>Bấm nút dưới đây để xác thực email cho tài khoản <b>FinTeen</b>:</p>
                  <p><a href="%s" style="display:inline-block;padding:11px 22px;background:#2563eb;
                     color:#fff;text-decoration:none;border-radius:6px">Xác thực email</a></p>
                  <p style="color:#6b7280;font-size:13px">Link có hiệu lực trong 24 giờ.
                     Nút không bấm được thì mở link này:<br>%s</p>
                  <p style="color:#6b7280;font-size:13px">Không phải bạn đăng ký? Bỏ qua mail này,
                     tài khoản sẽ không dùng được gì nếu không xác thực.</p>
                </div>"""
                .formatted(escape(name), link, escape(link));
    }

    /** Tên do người dùng tự nhập, đi thẳng vào HTML là mở đường chèn thẻ. */
    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
