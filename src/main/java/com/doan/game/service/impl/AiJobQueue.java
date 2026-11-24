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
 * - lỗi "fatal" (SAFETY, đầu ra bị loại lần hai, không còn khoá nào bật): FAILED ngay với mã của lỗi (đợt D).
 * Lỗi gần nhất ghi lastErrorCode / lastErrorText trên việc; sổ từng lần gọi HTTP ở AiCallLog (ReportAiWriter, đợt D).
 *
 * Gom lô (Hưng chốt 25/11, spec báo cáo lớp mục 4): việc có ngăn (lane) và chưa lỗi lần nào đi theo LÔ — mỗi ngăn gửi khi đủ
 * {@code max-size} (10) việc HOẶC việc cũ nhất đã chờ đủ T (bài của em 15 phút, nhận xét lớp 30 phút, chế độ demo 1 phút);
 * một lô = một lần gọi (AiJobWriter.writeBatch), kiểm từng bài. Việc đã lỗi (attempts > 0) hoặc không có ngăn (việc cũ) gửi
 * LẺ như trước. Việc chờ quá 6 giờ (kể cả do hết lượt) → FAILED mã EXPIRED (bản theo luật), không kẹt "đang chuẩn bị" mãi.
 * Nhận xét cả lớp xong / hỏng → báo giáo viên (thông báo REPORT_READY, D2).
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

    static final String LANE_PARENT_CHAPTER = "PARENT_CHAPTER";
    static final String LANE_PARENT_FINAL = "PARENT_FINAL";
    static final String LANE_CLASS = ClassReportService.LANE;
    static final List<String> LANES = List.of(LANE_PARENT_CHAPTER, LANE_PARENT_FINAL, LANE_CLASS);
    /** Lý do gửi (AiCallLog.sendReason): đủ lô / hết giờ chờ / gửi lẻ sau lỗi. */
    static final String SEND_FULL = "FULL";
    static final String SEND_TIMEOUT = "TIMEOUT";
    static final String SEND_SINGLE = "SINGLE";
    static final Duration EXPIRE_AFTER = Duration.ofHours(6);

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
    private final int maxBatch;
    private final Duration waitParent;
    private final Duration waitClass;
    private final ObjectProvider<com.doan.game.service.NotificationService> notifications;
    private final com.doan.game.repository.AiCallLogRepository callLogs;
    /** Ngăn xét đầu tiên ở lượt kế (xoay vòng). */
    private int laneStart;
    /** Số lần hết lượt liên tiếp của tiến trình này (các khoá dùng chung quota) — xong một việc thì về 0. */
    private int quotaStreak;

    public AiJobQueue(AiJobRepository jobRepo, DailySummaryRepository summaryRepo, ObjectProvider<AiJobWriter> writers,
                      PlatformTransactionManager txManager, Clock clock,
                      @Value("${app.report.queue.enabled:true}") boolean queueEnabled,
                      @Value("${app.report.ai-enabled:false}") boolean aiEnabled,
                      @Value("${app.report.queue.batch-size:5}") int batchSize,
                      @Value("${app.report.batch.max-size:10}") int maxBatch,
                      @Value("${app.report.batch.wait-parent-seconds:900}") long waitParentSeconds,
                      @Value("${app.report.batch.wait-class-seconds:1800}") long waitClassSeconds,
                      @Value("${app.ai.demo-mode:false}") boolean demoMode,
                      ObjectProvider<com.doan.game.service.NotificationService> notifications,
                      com.doan.game.repository.AiCallLogRepository callLogs) {
        this.jobRepo = jobRepo;
        this.summaryRepo = summaryRepo;
        this.writers = writers;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.queueEnabled = queueEnabled;
        this.aiEnabled = aiEnabled;
        this.batchSize = batchSize;
        this.maxBatch = Math.max(1, maxBatch);
        // Chế độ demo: chờ tối đa 1 phút để buổi demo thấy bài ngay.
        this.waitParent = demoMode ? Duration.ofMinutes(1) : Duration.ofSeconds(Math.max(0, waitParentSeconds));
        this.waitClass = demoMode ? Duration.ofMinutes(1) : Duration.ofSeconds(Math.max(0, waitClassSeconds));
        this.notifications = notifications;
        this.callLogs = callLogs;
    }

    /** Việc đã khoá ở một lượt: batch = một lô ≥ 2 việc cùng ngăn (gọi writeBatch); reason = lý do gửi. */
    private record Claim(List<UUID> ids, boolean batch, String reason) {
        static final Claim EMPTY = new Claim(List.of(), false, null);
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
        Claim c = tx.execute(s -> claim(writer == null));
        List<UUID> ids = c.ids();
        if (writer != null && c.batch()) {
            runBatch(writer, c);
            return ids.size();
        }
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
                if (e.fatal()) {
                    tx.executeWithoutResult(s -> fail(id, e.code() == null ? "AI_ERROR" : e.code(), e.getMessage()));
                    quotaStreak = 0;
                    continue;
                }
                if (e.quota()) {
                    List<UUID> rest = List.copyOf(ids.subList(i, ids.size()));
                    tx.executeWithoutResult(s -> quota(rest, e.getMessage()));
                    break;
                }
                tx.executeWithoutResult(s -> retryOrFail(id, e.code() == null ? "AI_ERROR" : e.code(), e.getMessage()));
            } catch (RuntimeException e) {
                tx.executeWithoutResult(s -> retryOrFail(id, "AI_ERROR", e.toString()));
            }
        }
        return ids.size();
    }

    /** Một lô: một lần gọi, rồi ghi từng việc (xong / lỗi riêng / hết lượt). */
    private void runBatch(AiJobWriter writer, Claim c) {
        List<AiJob> jobs = new ArrayList<>();
        for (UUID id : c.ids()) {
            jobRepo.findById(id).ifPresent(jobs::add);
        }
        AiJobWriter.BatchResult r;
        try {
            r = writer.writeBatch(jobs, new AiJobWriter.Progress() {
            @Override
            public void usingKey(String keyAlias) {
                tx.executeWithoutResult(s -> c.ids().forEach(id ->
                        jobRepo.findById(id).ifPresent(j -> j.setCurrentKeyAlias(keyAlias))));
            }

            @Override
            public String sendReason() {
                return c.reason();
            }
        });
        } catch (RuntimeException e) {
            // Như đường lẻ: tính lần thử, lần sau gửi lẻ — không để cả lô đứng RUNNING rồi quay vòng tới 6 giờ.
            log.warn("lo AI loi: {}", e.toString());
            c.ids().forEach(id -> safely(id, () -> retryOrFail(id, "AI_ERROR", e.toString())));
            return;
        }
        r.ok().forEach((id, out) -> safely(id, () -> done(id, out)));
        r.errors().forEach((id, e) -> safely(id, () -> {
            if (e.fatal()) {
                fail(id, e.code() == null ? "AI_ERROR" : e.code(), e.getMessage());
            } else {
                retryOrFail(id, e.code() == null ? "AI_ERROR" : e.code(), e.getMessage());
            }
        }));
        if (!r.quota().isEmpty()) {
            tx.executeWithoutResult(s -> quota(r.quota(), "mọi khoá AI hết lượt"));
        } else if (!r.ok().isEmpty()) {
            quotaStreak = 0;
        }
    }

    /** Ghi kết quả một việc trong giao dịch riêng; hỏng thì việc đó lỗi thường, các việc khác vẫn được ghi. */
    private void safely(UUID id, Runnable write) {
        try {
            tx.executeWithoutResult(s -> write.run());
        } catch (RuntimeException e) {
            log.warn("khong ghi duoc ket qua viec AI {}: {}", id, e.toString());
            try {
                tx.executeWithoutResult(s -> retryOrFail(id, "AI_ERROR", e.toString()));
            } catch (RuntimeException ignored) {
                // requeueStale sẽ trả việc về hàng chờ sau 5 phút
            }
        }
    }

    private Duration waitOf(String lane) {
        return LANE_CLASS.equals(lane) ? waitClass : waitParent;
    }

    /** noWait: AI tắt → lấy ngay, không chờ đủ lô / T (bài về bản theo luật trong một lượt như trước). */
    private Claim claim(boolean noWait) {
        Instant now = Instant.now(clock);
        jobRepo.requeueStale(now.minus(STALE_AFTER));
        expire(now);
        // Việc gửi lẻ (đã lỗi / việc cũ không ngăn) trước: chúng đã chờ qua ít nhất một lượt.
        List<UUID> singles = new ArrayList<>();
        for (AiJob job : jobRepo.lockDueSingles(now, PageRequest.of(0, batchSize))) {
            // Gửi lẻ sau lỗi giữ mã lô gốc để trang admin vẫn thấy bài bị loại nằm trong lô của nó.
            take(job, now, job.getBatchId() != null ? job.getBatchId()
                    : job.getLane() == null ? null : UUID.randomUUID());
            singles.add(job.getId());
        }
        if (!singles.isEmpty()) {
            return new Claim(singles, false, SEND_SINGLE);
        }
        int start = laneStart;
        laneStart = (laneStart + 1) % LANES.size();   // xoay ngăn đầu tiên mỗi lượt: một ngăn đông không bỏ đói ngăn khác
        for (int k = 0; k < LANES.size(); k++) {
            String lane = LANES.get((start + k) % LANES.size());
            List<AiJob> due = jobRepo.lockDueLane(now, lane, PageRequest.of(0, maxBatch));
            if (due.isEmpty()) {
                continue;
            }
            boolean full = due.size() >= maxBatch;
            boolean waited = noWait || !due.get(0).getCreatedAt().isAfter(now.minus(waitOf(lane)));
            if (full || waited) {
                UUID batchId = UUID.randomUUID();
                List<UUID> ids = new ArrayList<>();
                for (AiJob job : due) {
                    take(job, now, batchId);
                    ids.add(job.getId());
                }
                return new Claim(ids, ids.size() > 1, full ? SEND_FULL : SEND_TIMEOUT);
            }
        }
        return Claim.EMPTY;
    }

    private static void take(AiJob job, Instant now, UUID batchId) {
        job.setStatus(RUNNING);
        job.setLockedAt(now);
        job.setStartedAt(now);
        job.setBatchId(batchId);
    }

    /** Việc chờ quá 6 giờ → FAILED (EXPIRED): bài giữ bản theo luật. */
    private void expire(Instant now) {
        for (AiJob job : jobRepo.findExpired(now.minus(EXPIRE_AFTER))) {
            job.setStatus(FAILED);
            job.setLastErrorCode("EXPIRED");
            job.setLastErrorText("chờ quá " + EXPIRE_AFTER.toHours() + " giờ — dùng bản theo luật");
            job.setLockedAt(null);
            job.setDoneAt(now);
            job.setNextAttemptAt(null);
            notifyClass(job.getId());
        }
    }

    /** D2: nhận xét cả lớp vừa xong / hỏng → báo giáo viên (dòng gộp REPORT_READY của lớp + chương). */
    private void notifyClass(UUID jobId) {
        com.doan.game.service.NotificationService n = notifications.getIfAvailable();
        if (n == null) {
            return;
        }
        for (DailySummary s : summaryRepo.findByAiJob_Id(jobId)) {
            if (ReportPackage.CLASS.equals(s.getKind()) && s.getGroup() != null) {
                n.notifyClassCommentReady(s.getGroup(), s.getChapter());
            }
        }
    }

    private void done(UUID id, AiJobWriter.Output out) {
        AiJob job = jobRepo.findById(id).orElseThrow();
        job.setStatus(DONE);
        job.setOutput(out.raw());   // đầu ra thô (JSON theo mục); bài hiện cho người lớn nằm ở DailySummary.summary
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
        notifyClass(id);
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

    private void retryOrFail(UUID id, String code, String message) {
        AiJob job = jobRepo.findById(id).orElseThrow();
        int attempts = (job.getAttempts() == null ? 0 : job.getAttempts()) + 1;
        job.setAttempts(attempts);
        job.setLastErrorCode(code);
        job.setLastErrorText(clip(message));
        job.setLockedAt(null);
        if (attempts > BACKOFF.size()) {
            job.setStatus(FAILED);   // đợt D: leo tầng 3–4 (WAITING_AGENT) thay vì dừng
            job.setDoneAt(Instant.now(clock));
            job.setNextAttemptAt(null);
            notifyClass(id);
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
        notifyClass(id);
    }

    // ------------------------------------------------------------------ trang admin (chỉ đọc)

    @Transactional(readOnly = true)
    public PageResponse<AiJobResponse> list(String status, String kind, int page, int size) {
        return list(status, kind, null, page, size);
    }

    /** Lọc thêm theo lô (25/11, admin theo dõi lô). */
    @Transactional(readOnly = true)
    public PageResponse<AiJobResponse> list(String status, String kind, UUID batchId, int page, int size) {
        Page<AiJob> p = jobRepo.search(blankToNull(status), blankToNull(kind), batchId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        return PageResponse.of(p, AiJobResponse::of);
    }

    @Transactional(readOnly = true)
    public AiJobResponse get(UUID id) {
        return jobRepo.findById(id).map(AiJobResponse::of)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có việc AI " + id));
    }

    // ------------------------------------------------------------------ admin theo dõi lô (25/11)

    @Transactional(readOnly = true)
    public PageResponse<com.doan.game.DTO.response.AiBatchResponse> batches(String keyAlias, String outcome, Instant from,
                                                                            Instant to, int page, int size) {
        Page<com.doan.game.entity.AiCallLog> p = callLogs.searchBatches(blankToNull(keyAlias), blankToNull(outcome),
                from == null ? Instant.EPOCH : from, to == null ? Instant.parse("9999-12-31T00:00:00Z") : to,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        return PageResponse.of(p, l -> batchOf(l, jobRepo.findByBatchIdOrderByCreatedAtAsc(l.getBatchId()), false));
    }

    /** Một lô: lần gọi gần nhất của lô + từng việc. Không có → 1003. */
    @Transactional(readOnly = true)
    public com.doan.game.DTO.response.AiBatchResponse batch(UUID batchId) {
        List<com.doan.game.entity.AiCallLog> logs = callLogs.findByBatchIdOrderByCreatedAtAsc(batchId);
        List<AiJob> jobs = jobRepo.findByBatchIdOrderByCreatedAtAsc(batchId);
        if (logs.isEmpty() && jobs.isEmpty()) {
            throw new AppException(ErrorCode.NOT_FOUND, "không có lô " + batchId);
        }
        // Lần gọi của cả lô (cỡ lớn nhất); các lần gửi lẻ sau lỗi cũng mang mã lô này.
        com.doan.game.entity.AiCallLog last = logs.stream().max(java.util.Comparator.comparing(
                (com.doan.game.entity.AiCallLog l) -> l.getBatchSize() == null ? 0 : l.getBatchSize())
                .thenComparing(com.doan.game.entity.AiCallLog::getCreatedAt,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))).orElse(null);
        if (last == null) {
            last = new com.doan.game.entity.AiCallLog();
            last.setBatchId(batchId);
        }
        return batchOf(last, jobs, true);
    }

    private static com.doan.game.DTO.response.AiBatchResponse batchOf(com.doan.game.entity.AiCallLog l, List<AiJob> jobs,
                                                                      boolean withItems) {
        int passed = (int) jobs.stream().filter(j -> DONE.equals(j.getStatus())).count();
        int rejected = (int) jobs.stream().filter(j -> "REJECTED_OUTPUT".equals(j.getLastErrorCode())
                && !DONE.equals(j.getStatus())).count();
        List<com.doan.game.DTO.response.AiBatchResponse.Item> items = !withItems ? null : jobs.stream()
                .map(j -> new com.doan.game.DTO.response.AiBatchResponse.Item(j.getId(), j.getKind(), j.getLane(),
                        j.getStatus(), "REJECTED_OUTPUT".equals(j.getLastErrorCode()) ? j.getLastErrorText() : null,
                        WAITING_RETRY.equals(j.getStatus()) ? "SINGLE_RETRY" : FAILED.equals(j.getStatus()) ? "FALLBACK"
                                : null))
                .toList();
        return new com.doan.game.DTO.response.AiBatchResponse(l.getBatchId(), jobs.isEmpty() ? null : jobs.get(0).getLane(),
                l.getCreatedAt(), l.getSendReason(), l.getBatchSize() != null ? l.getBatchSize() : jobs.size(),
                l.getKeyAlias(), l.getModel(), l.getLatencyMs(), l.getTokensIn(), l.getTokensOut(), l.getOutcome(), passed,
                rejected, items);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String clip(String s) {
        return s == null ? null : s.length() <= 500 ? s : s.substring(0, 500);
    }
}
