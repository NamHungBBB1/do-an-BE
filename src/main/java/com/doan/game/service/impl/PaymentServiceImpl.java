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
import com.doan.game.service.OutgoingMail;
import com.doan.game.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
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
import java.time.Duration;
import com.doan.game.enums.Role;
import com.doan.game.repository.AccountRoleRepository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ruột thật đầu tiên của BE (02/10). Ba quy tắc giữ cho đúng:
 *  1. Trạng thái giao dịch chỉ đi tới: PENDING -> PAID hoặc PENDING -> FAILED, không bao giờ lùi.
 *  2. Ghi nhận PAID là idempotent: webhook về hai lần, hay webhook và GET trạng thái (FE poll) cùng lúc,
 *     chỉ cấp gói một lần (khoá hàng + Entitlement.transactionId UNIQUE).
 *  3. Ngày gói tính theo giờ Việt Nam qua Clock; gia hạn nối tiếp từ ngày sau hạn cũ.
 *  4. Tiền đã về thì không bao giờ chỉ nằm trong log (P-01, 10/10): lệch tiền → đơn đánh dấu needsReview + note
 *     cho admin đối soát; trả sau khi đơn đã FAILED (huỷ / hết hạn) → vẫn cấp gói, ghi note (Hưng chốt 09/10 "tự cấp gói").
 */
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);
    private static final DateTimeFormatter PAYOS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DMY_HM = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    /** Link / QR PayOS sống 24 h; cron đối soát mọi đơn PENDING đã quá SWEEP_MIN_AGE (đủ để webhook kịp về). */
    static final Duration LINK_TTL = Duration.ofHours(24);
    static final Duration REUSE_MARGIN = Duration.ofMinutes(1);
    static final Duration SWEEP_MIN_AGE = Duration.ofMinutes(2);

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
    private final ApplicationEventPublisher events;
    private final AccountRoleRepository accountRoleRepo;
    /** Cron đối soát mỗi đơn trong giao dịch riêng (P-06): một đơn hỏng không kéo cả lô rollback. */
    private final TransactionTemplate txTemplate;

    // ------------------------------------------------------------------ tạo link
    @Override
    @Transactional
    public PaymentResponse createPayment(UUID accountId, BuyPlanRequest req) {
        PlanKind kind = PlanServiceImpl.parseKind(req.kind());
        // Khoá tài khoản (P-02): hai request Mua cùng lúc xếp hàng, request sau thấy đơn request trước vừa tạo.
        Account account = accountRepo.lockById(accountId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "account " + accountId));
        // "Admin là admin, không mua gói" (Hưng chốt 08/10, P-08).
        if (accountRoleRepo.existsByAccount_IdAndRole(accountId, Role.ADMIN)) {
            throw new AppException(ErrorCode.FORBIDDEN, "tài khoản admin không mua gói");
        }
        Plan plan = planRepo.findByKind(kind)
                .orElseThrow(() -> new AppException(ErrorCode.PLAN_PRICE_NOT_SET, kind.name()));
        long price = plan.getPrice();
        int months = plan.getMonths() == null ? PlanServiceImpl.DEFAULT_MONTHS : plan.getMonths();
        // Idempotent: còn đơn chờ cùng gói, cùng giá, cùng số tháng, QR còn hạn ít nhất 1 phút thì trả lại
        // đúng đơn đó. Bấm Mua hai lần hay tải lại trang không sinh đơn rác, không có hai QR để lỡ trả hai lần.
        Optional<Transaction> open = transactionRepo
                .findFirstByAccount_IdAndKindAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                        accountId, kind, TransactionStatus.PENDING, Instant.now(clock).plus(REUSE_MARGIN))
                .filter(t -> t.getQrCode() != null && t.getAmount() == price
                        && t.getMonths() != null && t.getMonths() == months);
        if (open.isPresent()) {
            return toPaymentResponse(open.get());
        }
        PayOS client = client();

        Transaction tx = new Transaction();
        tx.setAccount(account);
        tx.setOrderCode(generateOrderCode());
        tx.setKind(kind);
        tx.setPurpose(hasActivePlan(accountId, kind) ? TransactionPurpose.RENEW : TransactionPurpose.NEW);
        tx.setAmount(price);
        tx.setMonths(months);   // đơn giữ cả giá lẫn số tháng lúc mua (P-05): admin đổi gói sau đó không ảnh hưởng
        tx.setStatus(TransactionStatus.PENDING);
        tx.setCreatedAt(Instant.now(clock));
        // Lưu trước khi gọi PayOS: webhook chỉ tin orderCode đã có trong bảng.
        tx = transactionRepo.saveAndFlush(tx);

        Instant expiresAt = Instant.now(clock).plus(LINK_TTL).truncatedTo(ChronoUnit.SECONDS);
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
            tx.setQrCode(res.getQrCode());
            tx.setBankBin(res.getBin());
            tx.setBankAccountNumber(res.getAccountNumber());
            tx.setBankAccountName(res.getAccountName());
            tx.setTransferNote(res.getDescription());
            tx.setExpiresAt(expiresAt);
            return toPaymentResponse(tx);
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
    @Transactional(readOnly = true)
    public List<TransactionAdminResponse> listMyTransactions(UUID accountId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return transactionRepo.findByAccount_IdOrderByCreatedAtDesc(accountId, pageable)
                .map(PaymentServiceImpl::toAdmin).getContent();
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
            // Tiền đã về nhưng lệch: không cấp gói, nhưng cũng không để chuyện này chỉ nằm trong log (P-01).
            // Đơn vẫn PENDING, đánh dấu cho admin lọc needsReview rồi đối soát tay.
            log.warn("Webhook PayOS orderCode {}: số tiền {} khác giao dịch {} — KHÔNG cấp gói, cần đối soát tay",
                    data.getOrderCode(), data.getAmount(), tx.getAmount());
            tx.setNeedsReview(true);
            tx.setNote("PayOS báo nhận " + data.getAmount() + " đ, khác đơn " + tx.getAmount() + " đ ("
                    + data.getTransactionDateTime() + ") — chưa cấp gói, cần đối soát");
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
    /**
     * Đối soát mọi đơn PENDING đã quá 2 phút tuổi (đủ để webhook kịp về), KHÔNG có cận trên (P-04: máy chủ
     * tắt đúng giờ 24→25 h làm đơn bị bỏ quên mãi). Mỗi đơn một giao dịch riêng có khoá hàng (P-06): GET / webhook
     * vừa cấp xong thì cron thấy PAID và bỏ qua; một đơn lỗi không kéo cả lô rollback.
     */
    @Override
    @Scheduled(fixedDelayString = "${app.payos.sweep-ms:120000}", initialDelayString = "${app.payos.sweep-ms:120000}")
    public void sweepPendingTransactions() {
        if (payOS.getIfAvailable() == null) {
            return;
        }
        List<Long> orderCodes = txTemplate.execute(status -> transactionRepo
                .findByStatusAndCreatedAtBefore(TransactionStatus.PENDING, Instant.now(clock).minus(SWEEP_MIN_AGE))
                .stream().map(Transaction::getOrderCode).toList());
        int done = 0;
        for (Long orderCode : orderCodes == null ? List.<Long>of() : orderCodes) {
            try {
                txTemplate.executeWithoutResult(status -> transactionRepo.lockByOrderCode(orderCode)
                        .filter(t -> t.getStatus() == TransactionStatus.PENDING)
                        .ifPresent(this::reconcileWithPayOS));
                done++;
            } catch (RuntimeException e) {
                log.error("Đối soát đơn {} lỗi — bỏ qua, lần sau quét lại", orderCode, e);
            }
        }
        if (done > 0) {
            log.info("Đối soát {} giao dịch PENDING với PayOS", done);
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
            // Tiền về sau khi đơn đã huỷ / hết hạn: khách đã trả thì cấp gói (Hưng chốt 09/10 "tự cấp gói"),
            // ghi note để admin thấy đây là đơn trả muộn. Ngoại lệ duy nhất của luật "trạng thái không lùi".
            log.warn("Giao dịch {} đã FAILED nhưng PayOS báo PAID — cấp gói theo luật trả muộn", tx.getOrderCode());
            tx.setNote("Tiền về sau khi đơn đã FAILED (huỷ / hết hạn) — tự cấp gói theo luật 09/10");
        }
        tx.setStatus(TransactionStatus.PAID);
        tx.setPaidAt(paidAt);
        Entitlement e = buildEntitlementFrom(tx);
        entitlementRepo.save(e);
        sendReceiptMail(tx, e);
        log.info("Giao dịch {} PAID, cấp gói {} cho tài khoản {}", tx.getOrderCode(), tx.getKind(), tx.getAccount().getId());
    }

    /**
     * Số tháng lấy từ ĐƠN (ghi lúc mua, P-05); đơn cũ chưa có cột thì đọc Plan (orElse chỉ để webhook không bao giờ ném).
     * Ngày startsOn / expiresOn do EntitlementFactory tính, dùng chung với admin cấp tay.
     */
    private Entitlement buildEntitlementFrom(Transaction tx) {
        int months = tx.getMonths() != null ? tx.getMonths()
                : planRepo.findByKind(tx.getKind()).map(Plan::getMonths).orElse(PlanServiceImpl.DEFAULT_MONTHS);
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

    /** Biên nhận: phát sự kiện, MailService gửi SAU KHI COMMIT — rollback thì không có mail. */
    private void sendReceiptMail(Transaction tx, Entitlement e) {
        Account a = tx.getAccount();
        events.publishEvent(new OutgoingMail(a.getEmail(), "Biên nhận thanh toán FinTeen #" + tx.getOrderCode(),
                """
                        <p>Chào %s,</p>
                        <p>FinTeen đã nhận thanh toán của bạn.</p>
                        <ul>
                        <li>Mã đơn: <b>%d</b></li>
                        <li>Gói: <b>%s</b> (%s)</li>
                        <li>Số tiền: <b>%s đ</b></li>
                        <li>Thanh toán lúc: %s</li>
                        <li>Thời hạn gói: %s – %s</li>
                        </ul>
                        <p>Thắc mắc về giao dịch, trả lời mail này kèm mã đơn.</p>
                        """.formatted(AuthServiceImpl.escape(a.getDisplayName()), tx.getOrderCode(),
                        planLabel(tx.getKind()), tx.getPurpose() == TransactionPurpose.RENEW ? "gia hạn" : "mua mới",
                        String.format(Locale.ROOT, "%,d", tx.getAmount()).replace(',', '.'),
                        DMY_HM.format(tx.getPaidAt().atZone(clock.getZone())),
                        DMY.format(e.getStartsOn()), DMY.format(e.getExpiresOn()))));
    }

    public static String planLabel(PlanKind kind) {
        return kind == PlanKind.PARENT ? "Phụ huynh" : "Giáo viên";
    }

    private PaymentResponse toPaymentResponse(Transaction tx) {
        return new PaymentResponse(tx.getOrderCode(), tx.getQrCode(), tx.getBankBin(), tx.getBankAccountNumber(),
                tx.getBankAccountName(), tx.getAmount(), tx.getTransferNote(), tx.getExpiresAt());
    }

    private static PaymentStatusResponse toStatus(Transaction tx) {
        return new PaymentStatusResponse(tx.getOrderCode(), tx.getStatus().name(), tx.getAmount(), tx.getPaidAt());
    }

    private static TransactionAdminResponse toAdmin(Transaction tx) {
        return new TransactionAdminResponse(tx.getOrderCode(), tx.getAccount().getId(), tx.getAccount().getEmail(),
                tx.getKind().name(), tx.getPurpose().name(), tx.getAmount(), tx.getMonths(), tx.getStatus().name(),
                tx.getCreatedAt(), tx.getPaidAt(), tx.isNeedsReview(), tx.getNote());
    }
}
