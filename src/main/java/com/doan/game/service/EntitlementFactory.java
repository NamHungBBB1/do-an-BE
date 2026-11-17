package com.doan.game.service;

import com.doan.game.repository.AccountRepository;
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
 * Hai đường:
 * - Mua gói ({@link #createPermanent}): gói VĨNH VIỄN (18/11/2026) — startsOn = hôm nay, expiresOn =
 *   {@link #PERMANENT_UNTIL}. Không nối tiếp, không có kỳ, không reset.
 * - Admin cấp tay ({@link #create}): vẫn theo số tháng để giữ công cụ cấp thử / xử lý khiếu nại; nối
 *   tiếp từ hạn cũ nếu còn ở tương lai.
 */
@Component
@RequiredArgsConstructor
public class EntitlementFactory {

    /** Sentinel "vĩnh viễn" — mọi câu expiresOn >= today hiện có chạy nguyên, không NPE như NULL. */
    public static final LocalDate PERMANENT_UNTIL = LocalDate.of(9999, 12, 31);

    private final EntitlementRepository entitlementRepo;
    private final AccountRepository accountRepo;
    private final Clock clock;

    /** Gói mua qua PayOS: vĩnh viễn, không phụ thuộc giá / số tháng của đơn cũ. */
    public Entitlement createPermanent(Account account, PlanKind kind, EntitlementSource source,
                                       Transaction transaction, Account grantedBy, String reason) {
        accountRepo.lockById(account.getId());
        Entitlement entitlement = new Entitlement();
        entitlement.setAccount(account);
        entitlement.setKind(kind);
        entitlement.setSource(source);
        entitlement.setTransaction(transaction);
        entitlement.setGrantedBy(grantedBy);
        entitlement.setReason(reason);
        entitlement.setStartsOn(LocalDate.now(clock));
        entitlement.setExpiresOn(PERMANENT_UNTIL);
        entitlement.setCreatedAt(Instant.now(clock));
        return entitlement;
    }

    public Entitlement create(Account account, PlanKind kind, int months, EntitlementSource source,
                              Transaction transaction, Account grantedBy, String reason) {
        // Khoá tài khoản trước khi đọc hạn cũ (P-03): hai lần cấp song song không còn cùng thấy một
        // expiresOn rồi cùng nối tiếp → hai gói chồng kỳ hạn.
        accountRepo.lockById(account.getId());
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
