package com.doan.game.service.impl;

import com.doan.game.DTO.response.SlotReportResponse;
import com.doan.game.entity.AiJob;
import com.doan.game.entity.DailySummary;
import com.doan.game.enums.SummarySource;
import com.doan.game.repository.AiJobRepository;
import com.doan.game.repository.DailySummaryRepository;
import com.doan.game.repository.LearnerSlotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sinh bài viết cho người lớn (khung báo cáo AI mục 5, 13.3 — Hưng chốt 19/11, phương án B kèm hàng chờ).
 *
 * Mốc: em xong một chương mà chương đã COMPLETE (hậu trắc đã nộp, hoặc chương không có hậu trắc), hoặc em vừa nộp hậu trắc
 * → bài CHAPTER; xong CH08 và mọi chương COMPLETE → thêm bài FINAL. Mỗi lần gọi rà MỌI chương COMPLETE của lần chơi hiện tại
 * còn thiếu bài (tự vá chương xong trước khi có PR này). Khoá (slot, kind, chương, lần chơi): gọi lại / gửi lại không sinh
 * dòng thứ hai; chơi lại chương là dòng mới. Bài đã ghi không dựng lại (BR-48).
 *
 * Mỗi bài: dựng gói whitelist (ReportPackage), packageHash, ghi bản theo luật (source RULE, tier 0) NGAY — người lớn luôn có
 * chữ. Rồi theo hash: việc REPORT_SUMMARY cùng hash đã DONE → chép bài AI (source LLM); đang chờ / đang chạy → trỏ vào việc đó;
 * không có → tạo AiJob mới (QUEUED, input = gói). AiJob không có cột nào trỏ về slot: nối ngược qua DailySummary.aiJob.
 *
 * Chạy SAU khi transaction nộp bài / gửi lô đã commit, trong transaction riêng; lỗi ở đây chỉ ghi log — bé nộp bài không bao
 * giờ hỏng vì báo cáo. Lỗi giữa chừng (máy chết sau commit) thì lần gửi lô / nộp bài sau của em sẽ vá.
 * TODO: bản lớp (kind CLASS, mốc đề quiz hết hạn) — ngoài phạm vi PR này.
 */
@Slf4j
@Service
public class ReportSummaryService {

    /** Em có tiến độ mới (xong chương hoặc nộp hậu trắc). Chỉ mang id để BE tự tra — không mang số liệu. */
    public record ProgressEvent(UUID slotId) {
    }

    private final ReportServiceImpl reports;
    private final DailySummaryRepository summaryRepo;
    private final AiJobRepository jobRepo;
    private final LearnerSlotRepository slotRepo;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReportSummaryService(ReportServiceImpl reports, DailySummaryRepository summaryRepo, AiJobRepository jobRepo,
                                LearnerSlotRepository slotRepo, PlatformTransactionManager txManager, Clock clock) {
        this.reports = reports;
        this.summaryRepo = summaryRepo;
        this.jobRepo = jobRepo;
        this.slotRepo = slotRepo;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProgress(ProgressEvent event) {
        try {
            tx.executeWithoutResult(s -> enqueue(event.slotId()));
        } catch (RuntimeException e) {
            log.warn("khong sinh duoc bai bao cao cho slot {}: {}", event.slotId(), e.toString());
        }
    }

    /** Ghi mọi bài còn thiếu của em (gọi trong transaction). */
    void enqueue(UUID slotId) {
        ReportServiceImpl.ChapterBlocks blocks = reports.chapterBlocksOf(slotId);
        for (SlotReportResponse.ChapterRow row : blocks.chapters()) {
            if (ReportServiceImpl.COMPLETE.equals(row.reportStatus())) {
                ensure(slotId, ReportPackage.CHAPTER, row.chapter(), blocks,
                        () -> ReportPackage.chapter(blocks, row));
            }
        }
        if (ReportServiceImpl.COMPLETE.equals(blocks.finalReport().status())) {
            ensure(slotId, ReportPackage.FINAL, ReportServiceImpl.FINAL_CHAPTER, blocks,
                    () -> ReportPackage.finalPackage(blocks));
        }
    }

    private void ensure(UUID slotId, String kind, int chapter, ReportServiceImpl.ChapterBlocks blocks,
                        java.util.function.Supplier<ReportPackage.Pkg> build) {
        if (summaryRepo.findBySlot_IdAndKindAndChapterAndAttemptIndex(slotId, kind, chapter, blocks.attemptIndex())
                .isPresent()) {
            return;
        }
        ReportPackage.Pkg pkg = build.get();
        String json = ReportPackage.canonicalJson(pkg);
        String hash = ReportPackage.sha256(json);
        Instant now = Instant.now(clock);

        DailySummary s = new DailySummary();
        s.setSlot(slotRepo.getReferenceById(slotId));
        s.setKind(kind);
        s.setChapter(chapter);
        s.setAttemptIndex(blocks.attemptIndex());
        s.setBuildVersion(blocks.chapterVersion().get(chapter));
        s.setPackageHash(hash);
        s.setSummary(ReportPackage.ruleText(pkg));
        s.setSource(SummarySource.RULE);
        s.setTier(0);
        s.setCreatedAt(now);

        List<AiJob> done = jobRepo.findByKindAndInputHashAndStatusInOrderByCreatedAtDesc(AiJobQueue.REPORT_SUMMARY,
                hash, List.of(AiJobQueue.DONE));
        if (!done.isEmpty()) {
            AiJob job = done.get(0);
            s.setAiJob(job);
            // Bài đã ghép sẵn ở dòng khác cùng việc (kèm model / promptVersion); không còn dòng nào thì ghép lại từ đầu ra thô.
            DailySummary prev = summaryRepo.findByAiJob_Id(job.getId()).stream()
                    .filter(d -> d.getSource() == SummarySource.LLM).findFirst().orElse(null);
            s.setSummary(prev != null ? prev.getSummary() : ReportAiOutput.compose(job.getInput(), job.getOutput()));
            s.setModel(prev == null ? null : prev.getModel());
            s.setPromptVersion(prev == null ? null : prev.getPromptVersion());
            s.setSource(SummarySource.LLM);
            s.setTier(job.getTier());
            s.setProvider(job.getProvider());
        } else {
            List<AiJob> pending = jobRepo.findByKindAndInputHashAndStatusInOrderByCreatedAtDesc(
                    AiJobQueue.REPORT_SUMMARY, hash, List.of(AiJobQueue.QUEUED, AiJobQueue.RUNNING,
                            AiJobQueue.WAITING_RETRY, AiJobQueue.WAITING_AGENT));
            s.setAiJob(pending.isEmpty() ? newJob(json, hash, now) : pending.get(0));
        }
        summaryRepo.saveAndFlush(s);
    }

    private AiJob newJob(String json, String hash, Instant now) {
        AiJob job = new AiJob();
        job.setKind(AiJobQueue.REPORT_SUMMARY);
        job.setInput(json);
        job.setInputHash(hash);
        job.setStatus(AiJobQueue.QUEUED);
        job.setTier(1);
        job.setAttempts(0);
        job.setCreatedAt(now);
        return jobRepo.save(job);
    }
}
