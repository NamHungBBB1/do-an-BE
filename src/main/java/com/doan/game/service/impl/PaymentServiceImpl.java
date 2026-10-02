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
    public PaymentResponse taoGiaoDich(UUID accountId, BuyPlanRequest req) {
        PlanKind kind = PlanServiceImpl.docKind(req.kind());
        long gia = planRepo.findByKind(kind).map(Plan::getPrice)
                .orElseThrow(() -> new AppException(ErrorCode.PLAN_PRICE_NOT_SET, kind.name()));
        Account account = accountRepo.findById(accountId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "account " + accountId));
        PayOS client = client();

        Transaction tx = new Transaction();
        tx.setAccount(account);
        tx.setOrderCode(sinhOrderCode());
        tx.setKind(kind);
        tx.setPurpose(dangConHan(accountId, kind) ? TransactionPurpose.RENEW : TransactionPurpose.NEW);
        tx.setAmount(gia);
        tx.setStatus(TransactionStatus.PENDING);
        tx.setCreatedAt(Instant.now(clock));
        // Lưu trước khi gọi PayOS: webhook chỉ tin orderCode đã có trong bảng.
        tx = transactionRepo.saveAndFlush(tx);

        CreatePaymentLinkRequest data = CreatePaymentLinkRequest.builder()
                .orderCode(tx.getOrderCode())
                .amount(gia)
                // Nội dung chuyển khoản: PayOS giới hạn ngắn, không dấu.
                .description("FinTeen " + (kind == PlanKind.PARENT ? "goi phu huynh" : "goi giao vien"))
                .returnUrl(payOsProps.returnUrl())
                .cancelUrl(payOsProps.cancelUrl())
                // Link tự hết hạn sau 24h để giao dịch treo không sống mãi; cron thấy EXPIRED thì FAILED.
                .expiredAt(Instant.now(clock).plus(1, ChronoUnit.DAYS).getEpochSecond())
                .build();
        try {
            CreatePaymentLinkResponse res = client.paymentRequests().create(data);
            tx.setGatewayRef(res.getPaymentLinkId());
            return new PaymentResponse(res.getCheckoutUrl(), tx.getOrderCode());
        } catch (PayOSException e) {
            // Ném ra là rollback cả dòng PENDING: không có link thì không có giao dịch.
            throw new AppException(ErrorCode.PAYOS_ERROR, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ xem / huỷ
    @Override
    @Transactional
    public PaymentStatusResponse xemGiaoDich(UUID accountId, long orderCode) {
        Transaction tx = cuaToi(accountId, orderCode);
        if (tx.getStatus() == TransactionStatus.PENDING) {
            doiSoatVoiPayOS(tx);
        }
        return toStatus(tx);
    }

    @Override
    @Transactional
    public void huyGiaoDich(UUID accountId, long orderCode, String lyDo) {
        Transaction tx = cuaToi(accountId, orderCode);
        if (tx.getStatus() != TransactionStatus.PENDING) {
            throw new AppException(ErrorCode.TRANSACTION_NOT_PENDING, String.valueOf(orderCode));
        }
        try {
            client().paymentRequests().cancel(orderCode, lyDo == null || lyDo.isBlank() ? "Nguoi dung huy" : lyDo);
        } catch (PayOSException e) {
            throw new AppException(ErrorCode.PAYOS_ERROR, e.getMessage());
        }
        tx.setStatus(TransactionStatus.FAILED);
    }

    // ------------------------------------------------------------------ webhook
    @Override
    @Transactional
    public void nhanWebhook(String body) {
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
        Optional<Transaction> opt = transactionRepo.khoaTheoOrderCode(data.getOrderCode());
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
        ghiNhanDaTra(tx, docThoiDiem(data.getTransactionDateTime()));
    }

    @Override
    public String xacNhanWebhook(String url) {
        String dich = url == null || url.isBlank() ? payOsProps.webhookUrl() : url;
        try {
            client().webhooks().confirm(dich);
        } catch (PayOSException e) {
            throw new AppException(ErrorCode.PAYOS_ERROR, e.getMessage());
        }
        log.info("Đã đăng ký webhook PayOS: {}", dich);
        return dich;
    }

    // ------------------------------------------------------------------ cron
    /** Quét giao dịch PENDING từ 2 phút tới 25 giờ tuổi: đủ cũ để webhook đã kịp về, đủ mới để link còn ý nghĩa. */
    @Override
    @Scheduled(fixedDelayString = "${app.payos.sweep-ms:120000}", initialDelayString = "${app.payos.sweep-ms:120000}")
    @Transactional
    public void quetGiaoDichTreo() {
        if (payOS.getIfAvailable() == null) {
            return;
        }
        Instant now = Instant.now(clock);
        List<Transaction> treo = transactionRepo.findByStatusAndCreatedAtBetween(
                TransactionStatus.PENDING, now.minus(25, ChronoUnit.HOURS), now.minus(2, ChronoUnit.MINUTES));
        for (Transaction tx : treo) {
            doiSoatVoiPayOS(tx);
        }
        if (!treo.isEmpty()) {
            log.info("Đối soát {} giao dịch PENDING với PayOS", treo.size());
        }
    }

    // ------------------------------------------------------------------ admin
    @Override
    @Transactional(readOnly = true)
    public List<TransactionAdminResponse> danhSachGiaoDich(TransactionStatus status, int page, int size) {
        Pageable trang = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        var ds = status == null
                ? transactionRepo.findAllByOrderByCreatedAtDesc(trang)
                : transactionRepo.findByStatusOrderByCreatedAtDesc(status, trang);
        return ds.map(PaymentServiceImpl::toAdmin).getContent();
    }

    @Override
    @Transactional
    public PaymentStatusResponse doiSoat(long orderCode) {
        Transaction tx = transactionRepo.khoaTheoOrderCode(orderCode)
                .orElseThrow(() -> new AppException(ErrorCode.TRANSACTION_NOT_FOUND, String.valueOf(orderCode)));
        if (tx.getStatus() == TransactionStatus.PENDING) {
            doiSoatVoiPayOS(tx);
        }
        return toStatus(tx);
    }

    // ------------------------------------------------------------------ lõi
    /** Hỏi PayOS trạng thái thật của một giao dịch PENDING và áp vào. Lỗi mạng thì để nguyên, lần sau quét lại. */
    private void doiSoatVoiPayOS(Transaction tx) {
        PayOS client = payOS.getIfAvailable();
        if (client == null) {
            return;
        }
        try {
            PaymentLink link = client.paymentRequests().get(tx.getOrderCode());
            String trangThai = link.getStatus() == null ? "" : link.getStatus().name();
            switch (trangThai) {
                case "PAID" -> ghiNhanDaTra(tx, Instant.now(clock));
                case "CANCELLED", "EXPIRED", "FAILED" -> tx.setStatus(TransactionStatus.FAILED);
                default -> { /* PENDING, PROCESSING, UNDERPAID…: chờ */ }
            }
        } catch (PayOSException e) {
            log.warn("Hỏi PayOS orderCode {} lỗi: {}", tx.getOrderCode(), e.getMessage());
        }
    }

    /** Idempotent. Gọi trong transaction đang giữ khoá hàng (khoaTheoOrderCode) hoặc trong cron. */
    void ghiNhanDaTra(Transaction tx, Instant luc) {
        if (tx.getStatus() == TransactionStatus.PAID) {
            return;
        }
        if (tx.getStatus() == TransactionStatus.FAILED) {
            log.warn("Giao dịch {} đã FAILED nhưng PayOS báo PAID — không tự cấp gói, cần đối soát tay", tx.getOrderCode());
            return;
        }
        tx.setStatus(TransactionStatus.PAID);
        tx.setPaidAt(luc);
        entitlementRepo.save(capGoiTu(tx));
        log.info("Giao dịch {} PAID, cấp gói {} cho tài khoản {}", tx.getOrderCode(), tx.getKind(), tx.getAccount().getId());
    }

    /**
     * Gói mới bắt đầu từ hôm nay, hoặc từ ngày sau hạn của gói còn hiệu lực (gia hạn sớm không mất ngày);
     * dùng hết ngày expiresOn, nên expiresOn = startsOn + N tháng − 1 ngày. N đọc từ Plan lúc cấp
     * (dòng Plan không có đường xoá; orElse chỉ để webhook không bao giờ ném).
     */
    private Entitlement capGoiTu(Transaction tx) {
        LocalDate homNay = LocalDate.now(clock);
        LocalDate batDau = entitlementRepo
                .findTopByAccount_IdAndKindOrderByExpiresOnDesc(tx.getAccount().getId(), tx.getKind())
                .map(e -> e.getExpiresOn().plusDays(1))
                .filter(d -> d.isAfter(homNay))
                .orElse(homNay);
        int thang = planRepo.findByKind(tx.getKind()).map(Plan::getMonths).orElse(PlanServiceImpl.THANG_MAC_DINH);
        Entitlement e = new Entitlement();
        e.setAccount(tx.getAccount());
        e.setKind(tx.getKind());
        e.setSource(EntitlementSource.PAYMENT);
        e.setTransaction(tx);
        e.setStartsOn(batDau);
        e.setExpiresOn(batDau.plusMonths(thang).minusDays(1));
        e.setCreatedAt(Instant.now(clock));
        return e;
    }

    private boolean dangConHan(UUID accountId, PlanKind kind) {
        LocalDate homNay = LocalDate.now(clock);
        return entitlementRepo.findTopByAccount_IdAndKindOrderByExpiresOnDesc(accountId, kind)
                .filter(e -> !e.getExpiresOn().isBefore(homNay))
                .isPresent();
    }

    private Transaction cuaToi(UUID accountId, long orderCode) {
        Transaction tx = transactionRepo.khoaTheoOrderCode(orderCode)
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
    private long sinhOrderCode() {
        return Instant.now(clock).toEpochMilli() * 1000 + ThreadLocalRandom.current().nextInt(1000);
    }

    /** PayOS gửi "yyyy-MM-dd HH:mm:ss" theo giờ Việt Nam; đọc không ra thì lấy giờ hiện tại. */
    private Instant docThoiDiem(String s) {
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
