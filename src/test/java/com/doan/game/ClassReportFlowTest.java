package com.doan.game;

import com.doan.game.DTO.response.GroupReportResponse;
import com.doan.game.entity.Account;
import com.doan.game.entity.AiJob;
import com.doan.game.entity.DailySummary;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.entity.Quiz;
import com.doan.game.entity.QuizResult;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.NotificationType;
import com.doan.game.enums.QuizKind;
import com.doan.game.enums.SlotStatus;
import com.doan.game.enums.SummarySource;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AiJobRepository;
import com.doan.game.repository.DailySummaryRepository;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.NotificationRepository;
import com.doan.game.repository.QuizRepository;
import com.doan.game.repository.QuizResultRepository;
import com.doan.game.service.AiJobWriter;
import com.doan.game.service.AiTextClient;
import com.doan.game.service.ReportService;
import com.doan.game.service.impl.AiJobQueue;
import com.doan.game.service.impl.ReportSummaryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Báo cáo lớp + gom lô (Hưng chốt 25/11, build/spec-bao-cao-lop-gom-lo-2026-11-25.md).
 *
 * KHÔNG @Transactional: ReportSummaryService / ClassReportService ghi trong transaction riêng (REQUIRES_NEW, như sau commit
 * nộp bài) nên dữ liệu dựng phải được commit trước. Mỗi test dùng nhóm / việc riêng (tên theo nanoTime).
 */
@SpringBootTest
class ClassReportFlowTest {

    private static final Instant START = Instant.parse("2026-11-25T02:00:00Z");

    @Autowired private ReportSummaryService summaries;
    @Autowired private ReportService reports;
    @Autowired private AiJobQueue queue;
    @Autowired private AiJobRepository jobRepo;
    @Autowired private DailySummaryRepository summaryRepo;
    @Autowired private NotificationRepository notificationRepo;
    @Autowired private AccountRepository accountRepo;
    @Autowired private LearnerGroupRepository groupRepo;
    @Autowired private LearnerSlotRepository slotRepo;
    @Autowired private QuizRepository quizRepo;
    @Autowired private QuizResultRepository quizResultRepo;
    @Autowired private ObjectMapper objectMapper;

    // ------------------------------------------------------------------ 1. mốc 35 / 70 / 100

    /**
     * Lớp 10 em: ngưỡng 5 (35 %) / 7 (70 %) / 10 (100 %). 4 em → chưa có; 5 em → bản mốc 35; 7 em khi bản 35 còn chờ → THAY
     * (cùng dòng, cùng việc, giữ giờ xếp); việc đang chạy thì 10 em → bản mới; gọi lại không sinh thêm. Em trong lớp không
     * có việc AI riêng (BR-52).
     */
    @Test
    void milestonesReplacePendingAndNeverRewriteBetween() {
        Account teacher = adult("gv-moc");
        LearnerGroup room = group(teacher);
        List<LearnerSlot> kids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            kids.add(slot(room, "Em " + i));
        }
        Quiz post = postTest(teacher, room, 1);

        submit(post, kids.subList(0, 4));
        assertThat(classRows(room)).isEmpty();

        submit(post, kids.subList(4, 5));
        List<DailySummary> rows = classRows(room);
        assertThat(rows).hasSize(1);
        DailySummary first = rows.get(0);
        assertThat(first.getMilestone()).isEqualTo(35);
        assertThat(first.getSubmittedCount()).isEqualTo(5);
        assertThat(first.getActiveCount()).isEqualTo(10);
        assertThat(first.getSlot()).isNull();
        assertThat(first.getSummaryDate()).isNull();
        assertThat(first.getSource()).isEqualTo(SummarySource.RULE);
        AiJob job = jobRepo.findById(first.getAiJob().getId()).orElseThrow();
        assertThat(job.getLane()).isEqualTo("CLASS_MILESTONE");
        assertThat(job.getStatus()).isEqualTo("QUEUED");
        Instant queuedAt = job.getCreatedAt();
        String firstInput = job.getInput();
        assertThat(firstInput).doesNotContain("Em 0").doesNotContain(kids.get(0).getId().toString());

        submit(post, kids.subList(5, 7));   // 7 em = 70 %, bản 35 còn chờ → thay
        rows = classRows(room);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getId()).isEqualTo(first.getId());
        assertThat(rows.get(0).getMilestone()).isEqualTo(70);
        assertThat(rows.get(0).getSubmittedCount()).isEqualTo(7);
        AiJob replaced = jobRepo.findById(job.getId()).orElseThrow();
        assertThat(replaced.getCreatedAt()).isEqualTo(queuedAt);
        assertThat(replaced.getInput()).isNotEqualTo(firstInput);

        // Việc đã được lấy đi chạy → không thay nữa; 10 em = 100 % thành bản mới.
        replaced.setStatus("RUNNING");
        jobRepo.save(replaced);
        submit(post, kids.subList(7, 10));
        rows = classRows(room);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getMilestone()).isEqualTo(100);
        assertThat(rows.get(1).getMilestone()).isEqualTo(70);

        // Gọi lại (em nộp lại / gửi lại sự kiện): không viết lại giữa hai mốc.
        summaries.onProgress(new ReportSummaryService.ProgressEvent(kids.get(0).getId()));
        assertThat(classRows(room)).hasSize(2);

        // BR-52: không việc AI nào ngoài 2 việc của lớp được tạo cho các em.
        for (LearnerSlot k : kids) {
            assertThat(summaryRepo.findBySlotWithJob(k.getId())).allSatisfy(d -> assertThat(d.getAiJob()).isNull());
        }
    }

    // ------------------------------------------------------------------ 2. báo cáo GV + thông báo

    /**
     * Báo cáo lớp: chưa đủ mốc → WAITING; có việc chờ → PREPARING; AI viết xong → READY + thông báo REPORT_READY cho
     * giáo viên (D2) trỏ báo cáo nhóm. JSON trả FE không có trường nội bộ (mốc, ngăn, lô, nhà cung cấp…).
     */
    @Test
    void teacherReportShowsStatesAndHidesInternals() throws Exception {
        Account teacher = adult("gv-xem");
        LearnerGroup room = group(teacher);
        List<LearnerSlot> kids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            kids.add(slot(room, "Ban " + i));
        }
        Quiz post = postTest(teacher, room, 2);
        submit(post, kids.subList(0, 4));
        GroupReportResponse r = reports.groupReport(teacher.getId(), room.getId());
        assertThat(r.chapters()).isEmpty();   // chưa ai xong chương, chưa có nhận xét

        submit(post, kids.subList(4, 5));   // lớp 5 em: một mốc duy nhất (5 em)
        r = reports.groupReport(teacher.getId(), room.getId());
        assertThat(r.chapters()).hasSize(1);
        GroupReportResponse.ClassChapter ch = r.chapters().get(0);
        assertThat(ch.chapter()).isEqualTo(2);
        assertThat(ch.active()).isEqualTo(5);
        assertThat(ch.comment().state()).isEqualTo("PREPARING");
        assertThat(ch.comment().text()).isNull();
        assertThat(ch.notFinished()).hasSize(5);

        UUID jobId = classRows(room).get(0).getAiJob().getId();
        drainUntilDone(jobId, (job, p) -> new AiJobWriter.Output("Lớp làm tốt.", 1, "GEMINI", "k-lop", "m", "report-v3"));
        r = reports.groupReport(teacher.getId(), room.getId());
        ch = r.chapters().get(0);
        assertThat(ch.comment().state()).isEqualTo("READY");
        assertThat(ch.comment().text()).isEqualTo("Lớp làm tốt.");
        assertThat(ch.comment().basedOn()).isEqualTo(5);

        var bell = notificationRepo.findByRecipient_Id(teacher.getId(), PageRequest.of(0, 10)).getContent();
        assertThat(bell).anySatisfy(n -> {
            assertThat(n.getType()).isEqualTo(NotificationType.REPORT_READY);
            assertThat(n.getActionType()).isEqualTo("OPEN_GROUP_REPORT");
            assertThat(n.getActionResourceId()).isEqualTo(room.getId());
        });

        String json = objectMapper.writeValueAsString(r.chapters());
        for (String hidden : List.of("milestone", "lane", "batch", "provider", "tier", "GEMINI", "k-lop", "prompt")) {
            assertThat(json).doesNotContainIgnoringCase(hidden);
        }
    }

    // ------------------------------------------------------------------ 3. gom lô

    /**
     * Ba việc cùng ngăn → một lần writeBatch; một bài bị loại → chỉ việc đó lỗi (attempts 1), lượt sau gửi LẺ (write).
     */
    @Test
    void batchOneCallThenRejectedItemGoesSingle() {
        String lane = "PARENT_FINAL";
        List<AiJob> mine = List.of(laneJob(lane), laneJob(lane), laneJob(lane));
        Set<UUID> ids = Set.of(mine.get(0).getId(), mine.get(1).getId(), mine.get(2).getId());
        UUID bad = mine.get(1).getId();
        List<List<UUID>> batches = new CopyOnWriteArrayList<>();
        List<UUID> singles = new CopyOnWriteArrayList<>();
        AiJobWriter writer = new AiJobWriter() {
            @Override
            public Output write(AiJob job, Progress progress) {
                singles.add(job.getId());
                return new Output("bài lẻ", 1, "GEMINI", "k", "m", "p");
            }

            @Override
            public BatchResult writeBatch(List<AiJob> jobs, Progress progress) {
                batches.add(jobs.stream().map(AiJob::getId).toList());
                Map<UUID, Output> ok = new java.util.LinkedHashMap<>();
                Map<UUID, AiTextClient.AiCallException> errors = new java.util.LinkedHashMap<>();
                for (AiJob j : jobs) {
                    if (j.getId().equals(bad)) {
                        errors.put(j.getId(), new AiTextClient.AiCallException("TOO_LONG: 200 > 180", false,
                                "REJECTED_OUTPUT", false));
                    } else {
                        ok.put(j.getId(), new Output("bài lô", 1, "GEMINI", "k", "m", "p"));
                    }
                }
                return new BatchResult(ok, errors, List.of());
            }
        };
        for (int i = 0; i < 20 && !ids.stream().allMatch(id -> status(id).equals("DONE")
                || (id.equals(bad) && !status(id).equals("QUEUED"))); i++) {
            queue.runOnce(writer);
        }
        assertThat(batches).anySatisfy(b -> assertThat(b).containsAll(ids));
        assertThat(status(mine.get(0).getId())).isEqualTo("DONE");
        assertThat(status(mine.get(2).getId())).isEqualTo("DONE");
        AiJob rejected = jobRepo.findById(bad).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo("WAITING_RETRY");
        assertThat(rejected.getAttempts()).isEqualTo(1);
        assertThat(rejected.getLastErrorCode()).isEqualTo("REJECTED_OUTPUT");
        assertThat(rejected.getBatchId()).isNotNull();
        // Admin theo dõi lô: 2 đạt, 1 bị loại; việc bị loại sẽ gửi lẻ; không lộ gói / bài.
        var view = queue.batch(rejected.getBatchId());
        assertThat(view.items()).extracting("aiJobId").containsAll(ids);
        assertThat(view.passed()).isGreaterThanOrEqualTo(2);
        assertThat(view.rejected()).isGreaterThanOrEqualTo(1);
        assertThat(view.items()).filteredOn(it -> it.aiJobId().equals(bad))
                .singleElement().satisfies(it -> {
                    assertThat(it.next()).isEqualTo("SINGLE_RETRY");
                    assertThat(it.rejectedRule()).startsWith("TOO_LONG");
                });

        rejected.setNextAttemptAt(Instant.now().minusSeconds(1));
        jobRepo.save(rejected);
        for (int i = 0; i < 20 && !status(bad).equals("DONE"); i++) {
            queue.runOnce(writer);
        }
        assertThat(status(bad)).isEqualTo("DONE");
        assertThat(singles).contains(bad);
    }

    /**
     * Soát PR #77 (lỗi 1): lô ném lỗi không phải AiCallException (vd giải mã khoá hỏng) → mọi việc tính một lần thử và
     * chờ gửi lẻ, KHÔNG đứng RUNNING rồi quay vòng tới 6 giờ.
     */
    @Test
    void batchUnexpectedErrorDoesNotStrandJobs() {
        List<AiJob> mine = List.of(laneJob("CLASS_MILESTONE"), laneJob("CLASS_MILESTONE"));
        AiJobWriter boom = new AiJobWriter() {
            @Override
            public Output write(AiJob job, Progress progress) {
                throw new IllegalStateException("khoa hong");
            }

            @Override
            public BatchResult writeBatch(List<AiJob> jobs, Progress progress) {
                throw new IllegalStateException("khoa hong");
            }
        };
        for (int i = 0; i < 20 && mine.stream().anyMatch(j -> status(j.getId()).equals("QUEUED")); i++) {
            queue.runOnce(boom);
        }
        for (AiJob j : mine) {
            AiJob after = jobRepo.findById(j.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo("WAITING_RETRY");
            assertThat(after.getAttempts()).isEqualTo(1);
            assertThat(after.getLockedAt()).isNull();
        }
    }

    /** Việc chờ quá 6 giờ → FAILED mã EXPIRED (bản theo luật), không kẹt mãi (rà rủi ro nhóm 1 #6). */
    @Test
    void jobsWaitingOverSixHoursExpire() {
        AiJob old = laneJob("PARENT_CHAPTER");
        old.setCreatedAt(Instant.now().minus(Duration.ofHours(7)));
        old.setStatus("WAITING_RETRY");
        old.setNextAttemptAt(Instant.now().plus(Duration.ofMinutes(10)));
        jobRepo.save(old);
        queue.runOnce((job, p) -> new AiJobWriter.Output("x", 1, "GEMINI", "k", "m", "p"));
        AiJob after = jobRepo.findById(old.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("FAILED");
        assertThat(after.getLastErrorCode()).isEqualTo("EXPIRED");
    }

    // ------------------------------------------------------------------ dựng dữ liệu

    private void submit(Quiz post, List<LearnerSlot> who) {
        for (LearnerSlot s : who) {
            QuizResult r = new QuizResult();
            r.setQuiz(post);
            r.setSlot(s);
            r.setSubmittedAt(START);
            r.setScore(1);
            quizResultRepo.save(r);
            summaries.onProgress(new ReportSummaryService.ProgressEvent(s.getId()));
        }
    }

    private List<DailySummary> classRows(LearnerGroup room) {
        return summaryRepo.findByGroupWithJob(room.getId(), "CLASS");
    }

    private String status(UUID id) {
        return jobRepo.findById(id).orElseThrow().getStatus();
    }

    private void drainUntilDone(UUID jobId, AiJobWriter writer) {
        for (int i = 0; i < 30 && !status(jobId).equals("DONE"); i++) {
            queue.runOnce(writer);
        }
        assertThat(status(jobId)).isEqualTo("DONE");
    }

    private AiJob laneJob(String lane) {
        AiJob job = new AiJob();
        job.setKind("REPORT_SUMMARY");
        job.setLane(lane);
        job.setInput("{\"kind\":\"FINAL\",\"n\":" + System.nanoTime() + "}");
        job.setInputHash("test-" + UUID.randomUUID());
        job.setStatus("QUEUED");
        job.setAttempts(0);
        job.setTier(1);
        // Cũ nhất hàng để lượt kế lấy đúng các việc này trước việc của test khác.
        job.setCreatedAt(Instant.now().minus(Duration.ofHours(5)).plusNanos(System.nanoTime() % 1000));
        return jobRepo.save(job);
    }

    private Account adult(String tag) {
        Account account = new Account();
        account.setEmail(tag + "-" + System.nanoTime() + "@test.local");
        account.setDisplayName("Test " + tag);
        account.setCreatedAt(START);
        return accountRepo.save(account);
    }

    private LearnerGroup group(Account owner) {
        LearnerGroup group = new LearnerGroup();
        group.setOwner(owner);
        group.setContext(LearningContext.CLASS);
        group.setOpenContext(LearningContext.CLASS);
        group.setName("Lop " + System.nanoTime());
        group.setSlotLimit(40);
        group.setConsentConfirmedAt(START);
        group.setOpenedAt(START);
        return groupRepo.save(group);
    }

    private LearnerSlot slot(LearnerGroup group, String name) {
        LearnerSlot slot = new LearnerSlot();
        slot.setGroup(group);
        slot.setCode(Long.toString(System.nanoTime(), 36).toUpperCase());
        slot.setPinHash("x");
        slot.setDisplayName(name);
        slot.setStatus(SlotStatus.ACTIVE);
        slot.setCreatedAt(START);
        return slotRepo.save(slot);
    }

    private Quiz postTest(Account owner, LearnerGroup group, int chapter) {
        Quiz quiz = new Quiz();
        quiz.setOwner(owner);
        quiz.setGroup(group);
        quiz.setTitle("Hậu trắc chương " + chapter);
        quiz.setKind(QuizKind.POST_TEST);
        quiz.setChapter(chapter);
        quiz.setPublishedAt(START);
        return quizRepo.save(quiz);
    }
}
