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
import org.springframework.context.ApplicationEventPublisher;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock ApplicationEventPublisher events;
    @Mock com.doan.game.repository.AccountRoleRepository accountRoleRepo;

    /** 10:00 sáng 02/10/2026 giờ Việt Nam. */
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ClockConfig.VN);
    private PaymentServiceImpl svc;
    private Account account;
    private Transaction tx;

    @BeforeEach
    void setUp() {
        // txTemplate = null: cron sweep không chạy trong unit test (xem SlotLifecycle/flow test cho đường thật).
        svc = new PaymentServiceImpl(transactionRepo, entitlementRepo,
                new EntitlementFactory(entitlementRepo, accountRepo, clock), accountRepo, planRepo, payOSProvider,
                new PayOsProperties("id", "key", "sum", "https://x/webhook", "https://fe/ok", "https://fe/cancel"),
                clock, new ObjectMapper(), events, accountRoleRepo, null);
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
        // Biên nhận: một mail, có mã đơn và hạn gói
        ArgumentCaptor<OutgoingMail> mail = ArgumentCaptor.forClass(OutgoingMail.class);
        verify(events).publishEvent(mail.capture());
        assertTrue(mail.getValue().body().contains("123") && mail.getValue().body().contains("01/01/2027"));
    }

    @Test
    void buyingAgainReturnsTheOpenOrderWithoutCallingPayOS() {
        threeMonthPlan();
        when(accountRepo.lockById(account.getId())).thenReturn(Optional.of(account));
        tx.setQrCode("000201...");
        tx.setMonths(3);   // đơn giữ số tháng lúc mua (P-05): tái dùng chỉ khi cùng giá VÀ cùng số tháng
        tx.setExpiresAt(Instant.now(clock).plusSeconds(3600));
        when(transactionRepo.findFirstByAccount_IdAndKindAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(account.getId()), eq(PlanKind.PARENT), eq(TransactionStatus.PENDING), any()))
                .thenReturn(Optional.of(tx));

        var res = svc.createPayment(account.getId(), new com.doan.game.DTO.request.BuyPlanRequest("PARENT"));

        assertEquals(123L, res.orderCode());
        assertEquals("000201...", res.qrCode());
        verifyNoInteractions(payOSProvider);
        verify(transactionRepo, never()).saveAndFlush(any());
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

    /** P-01a: lệch tiền → không cấp gói, nhưng đơn phải mang dấu needsReview + note cho admin, không chỉ log. */
    @Test
    void amountMismatchGrantsNothingButFlagsForReview() {
        payOSReports(123L, 1000L, "00");
        when(transactionRepo.lockByOrderCode(123L)).thenReturn(Optional.of(tx));

        svc.handleWebhook("{}");

        assertEquals(TransactionStatus.PENDING, tx.getStatus());
        verify(entitlementRepo, never()).save(any());
        assertTrue(tx.isNeedsReview());
        assertTrue(tx.getNote().contains("1000") && tx.getNote().contains("2000"));
    }

    /** P-01b (Hưng chốt 09/10 "tự cấp gói"): tiền về sau khi đơn đã FAILED → vẫn PAID + cấp gói, ghi note. */
    @Test
    void paidAfterFailedStillGrantsWithNote() {
        payOSReports(123L, 2000L, "00");
        threeMonthPlan();
        tx.setStatus(TransactionStatus.FAILED);
        when(transactionRepo.lockByOrderCode(123L)).thenReturn(Optional.of(tx));
        when(entitlementRepo.findTopByAccount_IdAndKindOrderByExpiresOnDesc(account.getId(), PlanKind.PARENT))
                .thenReturn(Optional.empty());

        svc.handleWebhook("{}");

        assertEquals(TransactionStatus.PAID, tx.getStatus());
        verify(entitlementRepo).save(any());
        assertTrue(tx.getNote().contains("FAILED"));
    }

    /** P-05: số tháng lấy từ ĐƠN, không phải từ Plan hiện tại (admin đổi 3 → 1 tháng sau khi khách bấm mua). */
    @Test
    void monthsComeFromTheOrderNotTheCurrentPlan() {
        payOSReports(123L, 2000L, "00");
        tx.setMonths(3);
        when(transactionRepo.lockByOrderCode(123L)).thenReturn(Optional.of(tx));
        when(entitlementRepo.findTopByAccount_IdAndKindOrderByExpiresOnDesc(account.getId(), PlanKind.PARENT))
                .thenReturn(Optional.empty());

        svc.handleWebhook("{}");

        ArgumentCaptor<Entitlement> captor = ArgumentCaptor.forClass(Entitlement.class);
        verify(entitlementRepo).save(captor.capture());
        assertEquals(LocalDate.of(2027, 1, 1), captor.getValue().getExpiresOn());
        verify(planRepo, never()).findByKind(any());
    }

    /** P-08: admin là admin, không mua gói → 3004. */
    @Test
    void adminCannotBuyAPlan() {
        when(accountRepo.lockById(account.getId())).thenReturn(Optional.of(account));
        when(accountRoleRepo.existsByAccount_IdAndRole(account.getId(), com.doan.game.enums.Role.ADMIN)).thenReturn(true);

        var ex = org.junit.jupiter.api.Assertions.assertThrows(com.doan.game.exception.AppException.class,
                () -> svc.createPayment(account.getId(), new com.doan.game.DTO.request.BuyPlanRequest("PARENT")));

        assertEquals(3004, ex.getErrorCode().getCode());
        verifyNoInteractions(payOSProvider);
    }

    @Test
    void unknownOrderCodeIsIgnored() {
        payOSReports(999L, 2000L, "00");
        when(transactionRepo.lockByOrderCode(999L)).thenReturn(Optional.empty());

        svc.handleWebhook("{}");   // webhook thử của PayOS: không ném, không cấp gì

        verify(entitlementRepo, never()).save(any());
    }
}
