package com.doan.game.service.impl;

import com.doan.game.DTO.request.BuyPlanRequest;
import com.doan.game.DTO.response.PaymentResponse;
import com.doan.game.DTO.response.PaymentStatusResponse;
import com.doan.game.DTO.response.TransactionAdminResponse;
import com.doan.game.configuration.PayOsProperties;
import com.doan.game.entity.Account;
import com.doan.game.entity.Entitlement;
import com.doan.game.entity.Plan;
import com.doan.game.entity.Transaction;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.PlanKind;
import com.doan.game.enums.TransactionPurpose;
import com.doan.game.enums.TransactionStatus;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.PlanRepository;
import com.doan.game.repository.TransactionRepository;
import com.doan.game.service.EntitlementFactory;
import com.doan.game.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.payos.PayOS;
import vn.payos.exception.PayOSException;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkRequest;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkResponse;
import vn.payos.model.v2.paymentRequests.PaymentLink;
import vn.payos.model.webhooks.WebhookData;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ruột thật đầu tiên của BE (02/10). Ba quy tắc giữ cho đúng:
 *  1. Trạng thái giao dịch chỉ đi tới: PENDING -> PAID hoặc PENDING -> FAILED, không bao giờ lùi.
 *  2. Ghi nhận PAID là idempotent: webhook về hai lần, hay webhook và returnUrl cùng lúc, chỉ cấp gói
 *     một lần (khoá hàng + Entitlement.transactionId UNIQUE).
 *  3. Ngày gói tính theo giờ Việt Nam qua Clock; gia hạn nối tiếp từ ngày sau hạn cũ.
 */
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);
    private static final DateTimeFormatter PAYOS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TransactionRepository transactionRepo;
    private final EntitlementRepository entitlementRepo;
    /** Quy tắc ngày của gói sống ở đó, dùng chung cho admin cấp tay. */
    private final EntitlementFactory entitlementFactory;
    private final AccountRepository accountRepo;
    private final PlanRepository planRepo;
    /** Không có khoá thì không có bean (PayOsConfig); getIfAvailable() trả null thay vì chết lúc khởi động. */
    private final ObjectProvider<PayOS> payOS;
    private final PayOsProperties payOsProps;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ tạo link
    @Override
    @Transactional
    public PaymentResponse createPayment(UUID accountId, BuyPlanRequest req) {
        PlanKind kind = PlanServiceImpl.parseKind(req.kind());
        long price = planRepo.findByKind(kind).map(Plan::getPrice)
                .orElseThrow(() -> new AppException(ErrorCode.PLAN_PRICE_NOT_SET, kind.name()));
        Account account = accountRepo.findById(accountId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "account " + accountId));
        PayOS client = client();

        Transaction tx = new Transaction();
        tx.setAccount(account);
        tx.setOrderCode(generateOrderCode());
        tx.setKind(kind);
        tx.setPurpose(hasActivePlan(accountId, kind) ? TransactionPurpose.RENEW : TransactionPurpose.NEW);
        tx.setAmount(price);
        tx.setStatus(TransactionStatus.PENDING);
        tx.setCreatedAt(Instant.now(clock));
        // Lưu trước khi gọi PayOS: webhook chỉ tin orderCode đã có trong bảng.
        tx = transactionRepo.saveAndFlush(tx);

        Instant expiresAt = Instant.now(clock).plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        CreatePaymentLinkRequest data = CreatePaymentLinkRequest.builder()
                .orderCode(tx.getOrderCode())
                .amount(price)
                // Nội dung chuyển khoản: PayOS giới hạn ngắn, không dấu.
                .description("FinTeen " + (kind == PlanKind.PARENT ? "goi phu huynh" : "goi giao vien"))
                .returnUrl(payOsProps.returnUrl())
                .cancelUrl(payOsProps.cancelUrl())
                // Link tự hết hạn sau 24h để giao dịch treo không sống mãi; cron thấy EXPIRED thì FAILED.
                .expiredAt(expiresAt.getEpochSecond())
                .build();
        try {
            CreatePaymentLinkResponse res = client.paymentRequests().create(data);
            tx.setGatewayRef(res.getPaymentLinkId());
            return new PaymentResponse(tx.getOrderCode(), res.getQrCode(), res.getBin(), res.getAccountNumber(),
                    res.getAccountName(), price, res.getDescription(), expiresAt, res.getCheckoutUrl());
        } catch (PayOSException e) {
            // Ném ra là rollback cả dòng PENDING: không có link thì không có giao dịch.
            throw new AppException(ErrorCode.PAYOS_ERROR, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ xem / huỷ
    @Override
    @Transactional
    public PaymentStatusResponse getPayment(UUID accountId, long orderCode) {
        Transaction tx = findOwnedTransaction(accountId, orderCode);
        if (tx.getStatus() == TransactionStatus.PENDING) {
            reconcileWithPayOS(tx);
        }
        return toStatus(tx);
    }

    @Override
    @Transactional
    public void cancelPayment(UUID accountId, long orderCode, String reason) {
        Transaction tx = findOwnedTransaction(accountId, orderCode);
        if (tx.getStatus() != TransactionStatus.PENDING) {
            throw new AppException(ErrorCode.TRANSACTION_NOT_PENDING, String.valueOf(orderCode));
        }
        try {
            client().paymentRequests().cancel(orderCode, reason == null || reason.isBlank() ? "Nguoi dung huy" : reason);
        } catch (PayOSException e) {
            throw new AppException(ErrorCode.PAYOS_ERROR, e.getMessage());
        }
        tx.setStatus(TransactionStatus.FAILED);
    }

    // ------------------------------------------------------------------ webhook
    @Override
    @Transactional
    public void handleWebhook(String body) {
        PayOS client = payOS.getIfAvailable();
        if (client == null) {
            log.warn("Webhook PayOS tới nhưng máy chủ chưa cấu hình khoá — bỏ qua");
            return;
        }
        WebhookData data;
        try {
            // Demo chính chủ truyền ObjectNode vào verify(); đọc body thô thành cây JSON rồi đưa y như vậy.
            data = client.webhooks().verify(objectMapper.readTree(body));
        } catch (Exception e) {
            log.warn("Webhook PayOS sai chữ ký hoặc body lạ — bỏ qua: {}", e.getMessage());
            return;
        }
        Optional<Transaction> opt = transactionRepo.lockByOrderCode(data.getOrderCode());
        if (opt.isEmpty()) {
            // PayOS gửi webhook thử khi đăng ký URL, orderCode không có thật. Phải nhận 200, không phải lỗi.
            log.info("Webhook PayOS orderCode {} không có trong hệ thống — bỏ qua", data.getOrderCode());
            return;
        }
        Transaction tx = opt.get();
        if (!"00".equals(data.getCode())) {
            log.info("Webhook PayOS orderCode {} báo code {} ({}) — chưa phải thanh toán thành công",
                    data.getOrderCode(), data.getCode(), data.getDesc());
            return;
        }
        if (!Objects.equals(data.getAmount(), tx.getAmount())) {
            log.warn("Webhook PayOS orderCode {}: số tiền {} khác giao dịch {} — KHÔNG cấp gói, cần đối soát tay",
                    data.getOrderCode(), data.getAmount(), tx.getAmount());
            return;
        }
        markPaid(tx, parsePayosTime(data.getTransactionDateTime()));
    }

    @Override
    public String confirmWebhook(String url) {
        String target = url == null || url.isBlank() ? payOsProps.webhookUrl() : url;
        try {
            client().webhooks().confirm(target);
        } catch (PayOSException e) {
            throw new AppException(ErrorCode.PAYOS_ERROR, e.getMessage());
        }
        log.info("Đã đăng ký webhook PayOS: {}", target);
        return target;
    }

    // ------------------------------------------------------------------ cron
    /** Quét giao dịch PENDING từ 2 phút tới 25 giờ tuổi: đủ cũ để webhook đã kịp về, đủ mới để link còn ý nghĩa. */
    @Override
    @Scheduled(fixedDelayString = "${app.payos.sweep-ms:120000}", initialDelayString = "${app.payos.sweep-ms:120000}")
    @Transactional
    public void sweepPendingTransactions() {
        if (payOS.getIfAvailable() == null) {
            return;
        }
        Instant now = Instant.now(clock);
        List<Transaction> pending = transactionRepo.findByStatusAndCreatedAtBetween(
                TransactionStatus.PENDING, now.minus(25, ChronoUnit.HOURS), now.minus(2, ChronoUnit.MINUTES));
        for (Transaction tx : pending) {
            reconcileWithPayOS(tx);
        }
        if (!pending.isEmpty()) {
            log.info("Đối soát {} giao dịch PENDING với PayOS", pending.size());
        }
    }

    // ------------------------------------------------------------------ admin
    @Override
    @Transactional(readOnly = true)
    public List<TransactionAdminResponse> listTransactions(TransactionStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        var result = status == null
                ? transactionRepo.findAllByOrderByCreatedAtDesc(pageable)
                : transactionRepo.findByStatusOrderByCreatedAtDesc(status, pageable);
        return result.map(PaymentServiceImpl::toAdmin).getContent();
    }

    @Override
    @Transactional
    public PaymentStatusResponse reconcile(long orderCode) {
        Transaction tx = transactionRepo.lockByOrderCode(orderCode)
                .orElseThrow(() -> new AppException(ErrorCode.TRANSACTION_NOT_FOUND, String.valueOf(orderCode)));
        if (tx.getStatus() == TransactionStatus.PENDING) {
            reconcileWithPayOS(tx);
        }
        return toStatus(tx);
    }

    // ------------------------------------------------------------------ lõi
    /** Hỏi PayOS trạng thái thật của một giao dịch PENDING và áp vào. Lỗi mạng thì để nguyên, lần sau quét lại. */
    private void reconcileWithPayOS(Transaction tx) {
        PayOS client = payOS.getIfAvailable();
        if (client == null) {
            return;
        }
        try {
            PaymentLink link = client.paymentRequests().get(tx.getOrderCode());
            String linkStatus = link.getStatus() == null ? "" : link.getStatus().name();
            switch (linkStatus) {
                case "PAID" -> markPaid(tx, Instant.now(clock));
                case "CANCELLED", "EXPIRED", "FAILED" -> tx.setStatus(TransactionStatus.FAILED);
                default -> { /* PENDING, PROCESSING, UNDERPAID…: chờ */ }
            }
        } catch (PayOSException e) {
            log.warn("Hỏi PayOS orderCode {} lỗi: {}", tx.getOrderCode(), e.getMessage());
        }
    }

    /** Idempotent. Gọi trong transaction đang giữ khoá hàng (lockByOrderCode) hoặc trong cron. */
    void markPaid(Transaction tx, Instant paidAt) {
        if (tx.getStatus() == TransactionStatus.PAID) {
            return;
        }
        if (tx.getStatus() == TransactionStatus.FAILED) {
            log.warn("Giao dịch {} đã FAILED nhưng PayOS báo PAID — không tự cấp gói, cần đối soát tay", tx.getOrderCode());
            return;
        }
        tx.setStatus(TransactionStatus.PAID);
        tx.setPaidAt(paidAt);
        entitlementRepo.save(buildEntitlementFrom(tx));
        log.info("Giao dịch {} PAID, cấp gói {} cho tài khoản {}", tx.getOrderCode(), tx.getKind(), tx.getAccount().getId());
    }

    /**
     * N đọc từ Plan lúc cấp (dòng Plan không có đường xoá; orElse chỉ để webhook không bao giờ ném).
     * Ngày startsOn / expiresOn do EntitlementFactory tính, dùng chung với admin cấp tay.
     */
    private Entitlement buildEntitlementFrom(Transaction tx) {
        int months = planRepo.findByKind(tx.getKind()).map(Plan::getMonths).orElse(PlanServiceImpl.DEFAULT_MONTHS);
        return entitlementFactory.create(tx.getAccount(), tx.getKind(), months,
                EntitlementSource.PAYMENT, tx, null, null);
    }

    private boolean hasActivePlan(UUID accountId, PlanKind kind) {
        LocalDate today = LocalDate.now(clock);
        return entitlementRepo.findTopByAccount_IdAndKindOrderByExpiresOnDesc(accountId, kind)
                .filter(e -> !e.getExpiresOn().isBefore(today))
                .isPresent();
    }

    private Transaction findOwnedTransaction(UUID accountId, long orderCode) {
        Transaction tx = transactionRepo.lockByOrderCode(orderCode)
                .orElseThrow(() -> new AppException(ErrorCode.TRANSACTION_NOT_FOUND, String.valueOf(orderCode)));
        if (!tx.getAccount().getId().equals(accountId)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return tx;
    }

    private PayOS client() {
        PayOS c = payOS.getIfAvailable();
        if (c == null) {
            throw new AppException(ErrorCode.PAYOS_NOT_CONFIGURED);
        }
        return c;
    }

    /**
     * PayOS cần orderCode là số dương, duy nhất trong kênh, tối đa 9007199254740991.
     * Cách sinh: mili-giây hiện tại × 1000 + 3 chữ số ngẫu nhiên (≈1,8e15 < 9e15); trùng thì UNIQUE ở DB chặn.
     */
    private long generateOrderCode() {
        return Instant.now(clock).toEpochMilli() * 1000 + ThreadLocalRandom.current().nextInt(1000);
    }

    /** PayOS gửi "yyyy-MM-dd HH:mm:ss" theo giờ Việt Nam; đọc không ra thì lấy giờ hiện tại. */
    private Instant parsePayosTime(String s) {
        try {
            return LocalDateTime.parse(s, PAYOS_TIME).atZone(clock.getZone()).toInstant();
        } catch (Exception e) {
            return Instant.now(clock);
        }
    }

    private static PaymentStatusResponse toStatus(Transaction tx) {
        return new PaymentStatusResponse(tx.getOrderCode(), tx.getStatus().name(), tx.getAmount(), tx.getPaidAt());
    }

    private static TransactionAdminResponse toAdmin(Transaction tx) {
        return new TransactionAdminResponse(tx.getOrderCode(), tx.getAccount().getId(), tx.getAccount().getEmail(),
                tx.getKind().name(), tx.getPurpose().name(), tx.getAmount(), tx.getStatus().name(),
                tx.getCreatedAt(), tx.getPaidAt());
    }
}
