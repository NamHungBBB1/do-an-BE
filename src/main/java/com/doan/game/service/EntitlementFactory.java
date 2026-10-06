package com.doan.game.service;

import com.doan.game.entity.Account;
import com.doan.game.entity.Entitlement;
import com.doan.game.entity.Transaction;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.PlanKind;
import com.doan.game.repository.EntitlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Nơi DUY NHẤT dựng một dòng Entitlement, để mua gói (payment) và admin cấp tay (grant) không thể
 * lệch nhau về quy tắc ngày.
 *
 * Hai luật:
 * - Gia hạn nối tiếp: gói mới bắt đầu từ ngày sau hạn cũ nếu hạn cũ còn ở tương lai (kéo dài sớm
 *   không mất ngày), nếu đã hết hạn hoặc chưa từng có thì bắt đầu từ hôm nay (giờ Việt Nam).
 * - Dùng hết ngày expiresOn, nên expiresOn = startsOn + N tháng − 1 ngày.
 */
@Component
@RequiredArgsConstructor
public class EntitlementFactory {

    private final EntitlementRepository entitlementRepo;
    private final Clock clock;

    public Entitlement create(Account account, PlanKind kind, int months, EntitlementSource source,
                              Transaction transaction, Account grantedBy, String reason) {
        LocalDate today = LocalDate.now(clock);
        LocalDate startDate = entitlementRepo
                .findTopByAccount_IdAndKindOrderByExpiresOnDesc(account.getId(), kind)
                .map(Entitlement::getExpiresOn)
                .map(previousExpiry -> previousExpiry.plusDays(1))
                .filter(d -> d.isAfter(today))
                .orElse(today);

        Entitlement entitlement = new Entitlement();
        entitlement.setAccount(account);
        entitlement.setKind(kind);
        entitlement.setSource(source);
        entitlement.setTransaction(transaction);
        entitlement.setGrantedBy(grantedBy);
        entitlement.setReason(reason);
        entitlement.setStartsOn(startDate);
        entitlement.setExpiresOn(startDate.plusMonths(months).minusDays(1));
        entitlement.setCreatedAt(Instant.now(clock));
        return entitlement;
    }
}
