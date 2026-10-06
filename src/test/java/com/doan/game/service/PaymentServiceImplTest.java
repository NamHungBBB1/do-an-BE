package com.doan.game.service;

import com.doan.game.configuration.ClockConfig;
import com.doan.game.configuration.PayOsProperties;
import com.doan.game.entity.Account;
import com.doan.game.entity.Entitlement;
import com.doan.game.entity.Transaction;
import com.doan.game.enums.PlanKind;
import com.doan.game.enums.TransactionStatus;
import com.doan.game.entity.Plan;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.PlanRepository;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.TransactionRepository;
import com.doan.game.service.impl.PaymentServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import vn.payos.PayOS;
import vn.payos.model.webhooks.WebhookData;
import vn.payos.service.blocking.webhooks.WebhooksService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kiểm cái dễ sai nhất của thanh toán: ghi nhận PAID và quy tắc ngày của gói. Không Spring, không H2,
 * PayOS giả bằng Mockito — chỉ còn lại logic của mình.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock TransactionRepository transactionRepo;
    @Mock EntitlementRepository entitlementRepo;
    @Mock AccountRepository accountRepo;
    @Mock PlanRepository planRepo;
    @Mock ObjectProvider<PayOS> payOSProvider;
    @Mock PayOS payOS;
    @Mock WebhooksService webhooks;

    /** 10:00 sáng 02/10/2026 giờ Việt Nam. */
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ClockConfig.VN);
    private PaymentServiceImpl svc;
    private Account account;
    private Transaction tx;

    @BeforeEach
    void setUp() {
        svc = new PaymentServiceImpl(transactionRepo, entitlementRepo,
                new EntitlementFactory(entitlementRepo, clock), accountRepo, planRepo, payOSProvider,
                new PayOsProperties("id", "key", "sum", "https://x/webhook", "https://fe/ok", "https://fe/cancel"),
                clock, new ObjectMapper());
        account = new Account();
        account.setId(UUID.randomUUID());
        tx = new Transaction();
        tx.setAccount(account);
        tx.setOrderCode(123L);
        tx.setKind(PlanKind.PARENT);
        tx.setAmount(2000L);
        tx.setStatus(TransactionStatus.PENDING);
    }

    private void payOSReports(long orderCode, long amount, String code) {
        // Dựng xong rồi mới stub: builder của SDK có @NonNull, NPE giữa when()...thenReturn() là UnfinishedStubbing.
        WebhookData data = WebhookData.builder()
                .orderCode(orderCode).amount(amount).code(code).desc("ok")
                .description("FinTeen goi phu huynh").accountNumber("0000").reference("REF")
                .transactionDateTime("2026-10-02 10:05:00").currency("VND").paymentLinkId("plink")
                .build();
        when(payOSProvider.getIfAvailable()).thenReturn(payOS);
        when(payOS.webhooks()).thenReturn(webhooks);
        when(webhooks.verify(any())).thenReturn(data);
    }

    private void threeMonthPlan() {
        Plan p = new Plan();
        p.setKind(PlanKind.PARENT);
        p.setPrice(2000L);
        p.setMonths(3);
        when(planRepo.findByKind(PlanKind.PARENT)).thenReturn(Optional.of(p));
    }

    @Test
    void paidWebhookGrantsPlanFromToday() {
        payOSReports(123L, 2000L, "00");
        threeMonthPlan();
        when(transactionRepo.lockByOrderCode(123L)).thenReturn(Optional.of(tx));
        when(entitlementRepo.findTopByAccount_IdAndKindOrderByExpiresOnDesc(account.getId(), PlanKind.PARENT))
                .thenReturn(Optional.empty());

        svc.handleWebhook("{}");

        assertEquals(TransactionStatus.PAID, tx.getStatus());
        ArgumentCaptor<Entitlement> captor = ArgumentCaptor.forClass(Entitlement.class);
        verify(entitlementRepo).save(captor.capture());
        assertEquals(LocalDate.of(2026, 10, 2), captor.getValue().getStartsOn());
        // 02/10 + 3 tháng = 02/01/2027, dùng hết ngày 01/01/2027
        assertEquals(LocalDate.of(2027, 1, 1), captor.getValue().getExpiresOn());
        assertEquals(tx, captor.getValue().getTransaction());
    }

    @Test
    void earlyRenewalStartsAfterCurrentExpiry() {
        payOSReports(123L, 2000L, "00");
        threeMonthPlan();
        when(transactionRepo.lockByOrderCode(123L)).thenReturn(Optional.of(tx));
        Entitlement old = new Entitlement();
        old.setExpiresOn(LocalDate.of(2026, 11, 15));
        when(entitlementRepo.findTopByAccount_IdAndKindOrderByExpiresOnDesc(account.getId(), PlanKind.PARENT))
                .thenReturn(Optional.of(old));

        svc.handleWebhook("{}");

        ArgumentCaptor<Entitlement> captor = ArgumentCaptor.forClass(Entitlement.class);
        verify(entitlementRepo).save(captor.capture());
        assertEquals(LocalDate.of(2026, 11, 16), captor.getValue().getStartsOn());
        assertEquals(LocalDate.of(2027, 2, 15), captor.getValue().getExpiresOn());
    }

    @Test
    void duplicateWebhookGrantsOnce() {
        payOSReports(123L, 2000L, "00");
        tx.setStatus(TransactionStatus.PAID);
        when(transactionRepo.lockByOrderCode(123L)).thenReturn(Optional.of(tx));

        svc.handleWebhook("{}");

        verify(entitlementRepo, never()).save(any());
    }

    @Test
    void amountMismatchGrantsNothing() {
        payOSReports(123L, 1000L, "00");
        when(transactionRepo.lockByOrderCode(123L)).thenReturn(Optional.of(tx));

        svc.handleWebhook("{}");

        assertEquals(TransactionStatus.PENDING, tx.getStatus());
        verify(entitlementRepo, never()).save(any());
    }

    @Test
    void unknownOrderCodeIsIgnored() {
        payOSReports(999L, 2000L, "00");
        when(transactionRepo.lockByOrderCode(999L)).thenReturn(Optional.empty());

        svc.handleWebhook("{}");   // webhook thử của PayOS: không ném, không cấp gì

        verify(entitlementRepo, never()).save(any());
    }
}
