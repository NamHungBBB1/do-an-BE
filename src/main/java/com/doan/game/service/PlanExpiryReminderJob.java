package com.doan.game.service;

import com.doan.game.entity.Account;
import com.doan.game.entity.Entitlement;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.service.impl.AuthServiceImpl;
import com.doan.game.service.impl.PaymentServiceImpl;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Nhắc gia hạn: VietQR không tự trừ tiền định kỳ, nên mail nhắc là cách duy nhất để người dùng
 * không bị cắt gói bất ngờ (Hưng 07/10: "làm cho chuẩn nghiệp vụ"). Gửi 7 ngày trước hạn, 8h sáng
 * giờ VN. Đã gia hạn (còn dòng hạn xa hơn cùng loại) thì không nhắc.
 *
 * ponytail: chỉ nhắc đúng ngày expiresOn = hôm nay + 7; máy chủ tắt đúng hôm đó thì bỏ lỡ một lần.
 * Cần chắc chắn thì thêm cột remindedAt vào Entitlement.
 */
@Component
@RequiredArgsConstructor
public class PlanExpiryReminderJob {

    private static final Logger log = LoggerFactory.getLogger(PlanExpiryReminderJob.class);
    static final int DAYS_BEFORE = 7;

    private final EntitlementRepository entitlementRepo;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Scheduled(cron = "${app.plan-reminder.cron:0 0 8 * * *}", zone = "Asia/Ho_Chi_Minh")
    @Transactional(readOnly = true)
    public void remindExpiringPlans() {
        LocalDate expiresOn = LocalDate.now(clock).plusDays(DAYS_BEFORE);
        int sent = 0;
        for (Entitlement e : entitlementRepo.findByExpiresOn(expiresOn)) {
            Account a = e.getAccount();
            boolean renewed = entitlementRepo.findTopByAccount_IdAndKindOrderByExpiresOnDesc(a.getId(), e.getKind())
                    .filter(latest -> latest.getExpiresOn().isAfter(expiresOn))
                    .isPresent();
            if (renewed || a.getEmail() == null) {
                continue;
            }
            events.publishEvent(new OutgoingMail(a.getEmail(), "Gói FinTeen của bạn sắp hết hạn",
                    """
                            <p>Chào %s,</p>
                            <p>Gói <b>%s</b> của bạn hết hạn ngày <b>%s</b> (còn %d ngày).</p>
                            <p>Hết hạn thì bạn không mở được nhóm / slot mới, nhưng slot và dữ liệu đang có vẫn giữ
                            nguyên. Gia hạn trong ứng dụng thì thời hạn mới nối tiếp, không mất ngày nào.</p>
                            """.formatted(AuthServiceImpl.escape(a.getDisplayName()), PaymentServiceImpl.planLabel(e.getKind()),
                            PaymentServiceImpl.DMY.format(expiresOn), DAYS_BEFORE)));
            sent++;
        }
        if (sent > 0) {
            log.info("Nhắc gia hạn: {} mail cho gói hết hạn {}", sent, expiresOn);
        }
    }
}
