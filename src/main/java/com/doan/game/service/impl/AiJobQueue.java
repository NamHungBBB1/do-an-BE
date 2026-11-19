package com.doan.game.service.impl;

import com.doan.game.DTO.response.AiJobResponse;
import com.doan.game.DTO.response.PageResponse;
import com.doan.game.entity.AiJob;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.entity.DailySummary;
import com.doan.game.enums.SummarySource;
import com.doan.game.repository.AiJobRepository;
import com.doan.game.repository.DailySummaryRepository;
import com.doan.game.service.AiJobWriter;
import com.doan.game.service.AiTextClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hàng chờ việc AI trong Postgres (khung báo cáo AI 13.3, spec kho ảnh + giọng 4.7b — không thêm hạ tầng). Mỗi
 * {@code app.report.queue.interval-ms} (10 s): trả việc kẹt RUNNING quá 5 phút về QUEUED; khoá tối đa {@code batch-size} việc
 * QUEUED / WAITING_RETRY đã tới hạn (SKIP LOCKED), chuyển RUNNING; gọi {@link AiJobWriter} NGOÀI transaction; ghi kết quả.
 *
 * Luật đếm (Hưng 19/11, nhiều khoá Gemini miễn phí nên hết lượt là chuyện thường):
 * - hết lượt (quota): WAITING_RETRY, KHÔNG tính attempts, lùi 30 s nhân đôi tới trần 15 phút; dừng cả lô của lượt này (mọi
 *   khoá nhiều khả năng cùng bị chặn) — việc còn lại trong lô cũng hẹn cùng giờ.
 * - lỗi thường (5xx, timeout, đầu ra bị loại): attempts + 1, lùi 30 s / 2 phút / 10 phút; lỗi lần thứ 4 (quá 3 lần thử lại)
 *   thì FAILED. Đợt D: thay FAILED bằng leo tầng 3–4 (WAITING_AGENT, phiếu Discord).
 * - xong: DONE + chép bài sang mọi DailySummary trỏ tới việc (source LLM).
 * - {@code app.report.ai-enabled=false} (mặc định PR này) hoặc chưa có writer: FAILED ngay, mã AI_DISABLED — bài giữ bản theo
 *   luật, báo cáo hiện FALLBACK sau tối đa một chu kỳ.
 * Lỗi gần nhất ghi lastErrorCode / lastErrorText trên việc; sổ từng lượt gọi (AiCallLog) để đợt D.
 */
@Slf4j
@Component
public class AiJobQueue {

    static final String QUEUED = "QUEUED";
    static final String RUNNING = "RUNNING";
    static final String WAITING_RETRY = "WAITING_RETRY";
    static final String WAITING_AGENT = "WAITING_AGENT";
    static final String DONE = "DONE";
    static final String FAILED = "FAILED";
    static final String REPORT_SUMMARY = "REPORT_SUMMARY";

    /** Lùi sau lỗi thường thứ 1, 2, 3; lỗi thứ 4 thì FAILED. */
    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10));
    static final Duration QUOTA_BASE = Duration.ofSeconds(30);
    static final Duration QUOTA_CAP = Duration.ofMinutes(15);
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private final AiJobRepository jobRepo;
    private final DailySummaryRepository summaryRepo;
    private final ObjectProvider<AiJobWriter> writers;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean queueEnabled;
    private final boolean aiEnabled;
    private final int batchSize;
    /** Số lần hết lượt liên tiếp của tiến trình này (các khoá dùng chung quota) — xong một việc thì về 0. */
    private int quotaStreak;

    public AiJobQueue(AiJobRepository jobRepo, DailySummaryRepository summaryRepo, ObjectProvider<AiJobWriter> writers,
                      PlatformTransactionManager txManager, Clock clock,
                      @Value("${app.report.queue.enabled:true}") boolean queueEnabled,
                      @Value("${app.report.ai-enabled:false}") boolean aiEnabled,
                      @Value("${app.report.queue.batch-size:5}") int batchSize) {
        this.jobRepo = jobRepo;
        this.summaryRepo = summaryRepo;
        this.writers = writers;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.queueEnabled = queueEnabled;
        this.aiEnabled = aiEnabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.report.queue.interval-ms:10000}",
            initialDelayString = "${app.report.queue.interval-ms:10000}")
    public void tick() {
        if (!queueEnabled) {
            return;
        }
        try {
            runOnce(aiEnabled ? writers.getIfAvailable() : null);
        } catch (RuntimeException e) {
            log.warn("hang cho AI loi: {}", e.toString());
        }
    }

    /** Một lượt. writer null = AI tắt. Trả số việc đã lấy. Test gọi thẳng (lịch tắt trong test). */
    public synchronized int runOnce(AiJobWriter writer) {
        List<UUID> ids = tx.execute(s -> claim());
        for (int i = 0; i < ids.size(); i++) {
            UUID id = ids.get(i);
            if (writer == null) {
                tx.executeWithoutResult(s -> fail(id, "AI_DISABLED", "AI chưa bật (app.report.ai-enabled=false)"));
                continue;
            }
            AiJob job = jobRepo.findById(id).orElse(null);
            if (job == null) {
                continue;
            }
            try {
                AiJobWriter.Output out = writer.write(job, alias -> tx.executeWithoutResult(s ->
                        jobRepo.findById(id).ifPresent(j -> j.setCurrentKeyAlias(alias))));
                tx.executeWithoutResult(s -> done(id, out));
                quotaStreak = 0;
            } catch (AiTextClient.AiCallException e) {
                if (e.quota()) {
                    List<UUID> rest = List.copyOf(ids.subList(i, ids.size()));
                    tx.executeWithoutResult(s -> quota(rest, e.getMessage()));
                    break;
                }
                tx.executeWithoutResult(s -> retryOrFail(id, e.getMessage()));
            } catch (RuntimeException e) {
                tx.executeWithoutResult(s -> retryOrFail(id, e.toString()));
            }
        }
        return ids.size();
    }

    private List<UUID> claim() {
        Instant now = Instant.now(clock);
        jobRepo.requeueStale(now.minus(STALE_AFTER));
        List<UUID> ids = new ArrayList<>();
        for (AiJob job : jobRepo.lockDue(now, PageRequest.of(0, batchSize))) {
            job.setStatus(RUNNING);
            job.setLockedAt(now);
            job.setStartedAt(now);
            ids.add(job.getId());
        }
        return ids;
    }

    private void done(UUID id, AiJobWriter.Output out) {
        AiJob job = jobRepo.findById(id).orElseThrow();
        job.setStatus(DONE);
        job.setOutput(out.text());
        job.setTier(out.tier());
        job.setProvider(out.provider());
        job.setKeyAlias(out.keyAlias());
        job.setDoneAt(Instant.now(clock));
        job.setLockedAt(null);
        job.setNextAttemptAt(null);
        for (DailySummary s : summaryRepo.findByAiJob_Id(id)) {
            s.setSummary(out.text());
            s.setSource(SummarySource.LLM);
            s.setTier(out.tier());
            s.setProvider(out.provider());
            s.setModel(out.model());
            s.setPromptVersion(out.promptVersion());
        }
    }

    /** Hết lượt: hẹn lại phần còn lại của lô, không tính lần thử. */
    private void quota(List<UUID> ids, String message) {
        Duration wait = QUOTA_BASE.multipliedBy(1L << Math.min(quotaStreak, 10));
        if (wait.compareTo(QUOTA_CAP) > 0) {
            wait = QUOTA_CAP;
        }
        quotaStreak++;
        Instant next = Instant.now(clock).plus(wait);
        for (UUID id : ids) {
            jobRepo.findById(id).ifPresent(job -> {
                job.setStatus(WAITING_RETRY);
                job.setLockedAt(null);
                job.setNextAttemptAt(next);
                job.setLastErrorCode("QUOTA");
                job.setLastErrorText(clip(message));
            });
        }
    }

    private void retryOrFail(UUID id, String message) {
        AiJob job = jobRepo.findById(id).orElseThrow();
        int attempts = (job.getAttempts() == null ? 0 : job.getAttempts()) + 1;
        job.setAttempts(attempts);
        job.setLastErrorCode("AI_ERROR");
        job.setLastErrorText(clip(message));
        job.setLockedAt(null);
        if (attempts > BACKOFF.size()) {
            job.setStatus(FAILED);   // đợt D: leo tầng 3–4 (WAITING_AGENT) thay vì dừng
            job.setDoneAt(Instant.now(clock));
            job.setNextAttemptAt(null);
        } else {
            job.setStatus(WAITING_RETRY);
            job.setNextAttemptAt(Instant.now(clock).plus(BACKOFF.get(attempts - 1)));
        }
    }

    private void fail(UUID id, String code, String message) {
        jobRepo.findById(id).ifPresent(job -> {
            job.setStatus(FAILED);
            job.setLastErrorCode(code);
            job.setLastErrorText(clip(message));
            job.setLockedAt(null);
            job.setDoneAt(Instant.now(clock));
        });
    }

    // ------------------------------------------------------------------ trang admin (chỉ đọc)

    @Transactional(readOnly = true)
    public PageResponse<AiJobResponse> list(String status, String kind, int page, int size) {
        Page<AiJob> p = jobRepo.search(blankToNull(status), blankToNull(kind),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        return PageResponse.of(p, AiJobResponse::of);
    }

    @Transactional(readOnly = true)
    public AiJobResponse get(UUID id) {
        return jobRepo.findById(id).map(AiJobResponse::of)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có việc AI " + id));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String clip(String s) {
        return s == null ? null : s.length() <= 500 ? s : s.substring(0, 500);
    }
}
