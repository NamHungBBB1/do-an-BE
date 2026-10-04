package com.doan.game.service;

import com.doan.game.configuration.AppMailProperties;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;

/**
 * Gửi mail. Nghe sự kiện OutgoingMail và gửi SAU KHI COMMIT, bất đồng bộ.
 *
 * Không bắt lỗi kiểu "cứ thử": nếu gửi hỏng mà không ai biết, người dùng bấm "gửi lại" là
 * xong. Ở đây lỗi chỉ ghi log, không ném — ném ở đây sẽ làm hỏng luôn luồng đăng ký vốn
 * đã xong.
 *
 * Máy dev không đặt MAIL_HOST thì KHÔNG gửi, chỉ in nội dung ra log để team bấm được link.
 * Cùng cách PaymentServiceImpl xử lý PayOS: không có cấu hình thì không gọi ra ngoài.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    /** Không có khoá thì không có bean; getIfAvailable() trả null thay vì chết lúc khởi động. */
    private final ObjectProvider<JavaMailSender> sender;
    /** spring.mail.* — đọc host để biết có cấu hình SMTP thật hay không. */
    private final MailProperties springMail;
    private final AppMailProperties appMail;

    public MailService(ObjectProvider<JavaMailSender> sender, MailProperties springMail, AppMailProperties appMail) {
        this.sender = sender;
        this.springMail = springMail;
        this.appMail = appMail;
    }

    /**
     * @Async để request trả về ngay, không đợi SMTP.
     * AFTER_COMMIT để không bao giờ gửi mail cho một transaction đã rollback.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void send(OutgoingMail m) {
        if (!isSmtpConfigured()) {
            log.info("""

                    === MAIL (không có SMTP, chỉ in ra log) ===
                    tới:     {}
                    tiêu đề: {}
                    {}
                    === hết ===""", m.to(), m.subject(), m.body());
            return;
        }
        try {
            MimeMessage msg = sender.getIfAvailable().createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, false, StandardCharsets.UTF_8.name());
            h.setFrom(appMail.from(), appMail.fromName() == null || appMail.fromName().isBlank()
                    ? appMail.from() : appMail.fromName());
            h.setTo(m.to());
            h.setSubject(m.subject());
            h.setText(m.body(), true);
            sender.getIfAvailable().send(msg);
        } catch (Exception e) {
            // Cố tình KHÔNG log token vào log: ở máy chủ thật, log là nơi kẻ xấm vào đọc được.
            log.warn("Gửi mail tới {} lỗi, người dùng phải bấm gửi lại: {}", m.to(), e.getMessage());
        }
    }

    /**
     * Host rỗng thì coi như chưa cấu hình. Phải kiểm tra chứ không dựa vào việc Spring có tạo
     * JavaMailSender hay không: spring.mail.host mặc định là chuỗi rỗng trong
     * application.properties, chuỗi rỗng vẫn "có" nên bean vẫn được tạo, và gửi sẽ chết vì
     * không có địa chỉ máy chủ.
     */
    private boolean isSmtpConfigured() {
        return sender.getIfAvailable() != null
                && springMail.getHost() != null && !springMail.getHost().isBlank()
                && appMail.from() != null && !appMail.from().isBlank();
    }
}