package com.doan.game;

import com.doan.game.configuration.ClockConfig;
import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.entity.AiJob;
import com.doan.game.entity.DailySummary;
import com.doan.game.entity.Entitlement;
import com.doan.game.entity.Question;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.PlanKind;
import com.doan.game.enums.Role;
import com.doan.game.enums.SummarySource;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.AiJobRepository;
import com.doan.game.repository.ChapterDraftRepository;
import com.doan.game.repository.ChapterRepository;
import com.doan.game.repository.DailySummaryRepository;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.GameReleaseRepository;
import com.doan.game.repository.QuestionRepository;
import com.doan.game.repository.ReleaseChapterRepository;
import com.doan.game.service.AiJobWriter;
import com.doan.game.service.AiTextClient;
import com.doan.game.service.OutgoingMail;
import com.doan.game.service.impl.AiJobQueue;
import com.doan.game.service.impl.ReportSummaryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bài viết cho người lớn + hàng chờ việc AI (khung báo cáo AI 13.3, Hưng chốt 19/11) — đi qua HTTP thật (MockMvc + H2).
 *
 * Lịch của hàng chờ tắt trong test (src/test/resources/config/application.properties); test gọi AiJobQueue.runOnce thẳng.
 * Hàng chờ dùng chung H2 với các lớp test khác (thứ tự lớp đổi theo hệ điều hành): việc tự tạo để kiểm luật đếm có
 * createdAt = 1970 nên luôn đứng đầu lô; việc sinh từ bài nộp thì "xả" tới khi việc của mình xong — không bao giờ đếm toàn
 * bảng hay tra theo vị trí.
 *
 * Nội dung: 8 chương (CH01 có lựa chọn + mini-game + mã lõi, CH02–08 tối giản) — đủ cho bài tổng kết 8 chương. Kho hậu trắc
 * chương 1 luôn có ít nhất câu của lớp này; chương 2–7 không có câu nào (không lớp test nào gắn), chương 8 có thể có.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReportSummaryFlowTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ClockConfig.VN);
    private static final Pattern OTP_IN_MAIL = Pattern.compile(">(\\d{6})</p>");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KID_NAME = "Tran Thi Rieng";

    private static String version;

    @Autowired MockMvc mvc;
    @Autowired ReleaseChapterRepository releaseChapterRepo;
    @Autowired GameReleaseRepository releaseRepo;
    @Autowired ChapterDraftRepository draftRepo;
    @Autowired ChapterRepository chapterRepo;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountRoleRepository accountRoleRepo;
    @Autowired EntitlementRepository entitlementRepo;
    @Autowired QuestionRepository questionRepo;
    @Autowired DailySummaryRepository summaryRepo;
    @Autowired AiJobRepository jobRepo;
    @Autowired AiJobQueue queue;
    @Autowired ReportSummaryService summaryService;
    @Autowired TransactionTemplate tx;
    @Autowired AuthFlowTest.MailCatcher mailCatcher;

    @BeforeEach
    void resetMailbox() {
        mailCatcher.sent.clear();
    }

    // ------------------------------------------------------------------ 1. luồng chính
    /**
     * Xong CH01 (có hậu trắc) → WAITING_POST_TEST, chưa có dòng. Nộp hậu trắc → dòng CHAPTER bản theo luật + hash, việc AI
     * QUEUED, báo cáo GENERATING có chữ. Chạy job với AI tắt → FAILED (AI_DISABLED), báo cáo FALLBACK, chữ giữ nguyên.
     * Gói gửi đi không chứa tên / mã / id của bé, nhóm, email. Gọi sinh bài lại không thêm dòng.
     */
    @Test
    @Order(1)
    void postTestSubmissionQueuesSummaryThenFallsBack() throws Exception {
        releaseVersion();
        postTestQuestion();
        Kid kid = newKid("sumqmain");
        play(kid, 1, chapterOneBatch(version));

        JsonNode ch1 = chapterRow(report(kid), 1);
        assertThat(ch1.path("reportStatus").asText()).isEqualTo("PARTIAL");
        assertThat(ch1.path("summary").path("status").asText()).isEqualTo("WAITING_POST_TEST");
        assertThat(ch1.path("summary").path("text").isNull()).isTrue();
        assertThat(summaryRepo.findBySlot_IdAndKindAndChapterAndAttemptIndex(kid.slotId(), "CHAPTER", 1, 1)).isEmpty();

        passPostTest(kid);
        DailySummary row = summaryRepo.findBySlot_IdAndKindAndChapterAndAttemptIndex(kid.slotId(), "CHAPTER", 1, 1)
                .orElseThrow();
        assertThat(row.getSource()).isEqualTo(SummarySource.RULE);
        assertThat(row.getTier()).isZero();
        assertThat(row.getPackageHash()).matches("[0-9a-f]{64}");
        assertThat(row.getBuildVersion()).isEqualTo(version);
        assertThat(row.getSummary()).startsWith("Chương 1 — Chuong mot tong ket")
                .contains("Quyết định: bé chọn phương án có bước cân nhắc ở 1/1 lựa chọn có nhãn.")
                .contains("Mini-game: bé làm đúng 1/2 câu.")
                .contains("Bài sau chương: bé làm đúng 0/")
                .contains("Câu chưa đúng: NEEDS_WANTS_SUM / ITEM_UPGRADE.");
        AiJob job = jobOf(row);
        assertThat(job.getStatus()).isEqualTo("QUEUED");
        assertThat(job.getKind()).isEqualTo("REPORT_SUMMARY");
        assertThat(job.getInputHash()).isEqualTo(row.getPackageHash()).isEqualTo(sha256(job.getInput()));

        // Whitelist (khung 13.3): gói không có định danh hay dữ liệu cá nhân, có số liệu và chữ phương án bản gốc.
        String pkg = job.getInput();
        for (String forbidden : List.of(KID_NAME, kid.code(), kid.slotId().toString(), kid.groupId(), kid.groupName(),
                kid.parentEmail(), "displayName", "email", "slotId", "groupId", "accountId", "\"age\"", "gender",
                "interest", "receivedAt", "playedAt", "chosenAt", "\"chosen\":\"NEEDS\"")) {
            assertThat(pkg).as("goi khong duoc chua " + forbidden).doesNotContain(forbidden);
        }
        JsonNode p = JSON.readTree(pkg);
        assertThat(p.path("kind").asText()).isEqualTo("CHAPTER");
        assertThat(p.path("frame").path("chapters").get(0).path("goal").asText()).isEqualTo("Muc tieu chuong mot");
        assertThat(p.path("frame").path("decisions").get(0).path("options").get(0).path("label").asText())
                .isEqualTo("Tiet kiem");
        assertThat(p.path("result").path("decisions").get(0).path("deltaStats").path("SAVING").asInt()).isEqualTo(10);
        assertThat(p.path("result").path("noData").toString()).contains("D01.04");
        assertThat(pkg).contains("\"D01.02\"");

        JsonNode generating = chapterRow(report(kid), 1).path("summary");
        assertThat(generating.path("status").asText()).isEqualTo("GENERATING");
        assertThat(generating.path("text").asText()).isEqualTo(row.getSummary());
        assertThat(generating.path("source").asText()).isEqualTo("RULE");

        drain(job.getId(), null);
        AiJob failed = jobRepo.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getLastErrorCode()).isEqualTo("AI_DISABLED");
        JsonNode result = report(kid);
        JsonNode fallback = chapterRow(result, 1).path("summary");
        assertThat(fallback.path("status").asText()).isEqualTo("FALLBACK");
        assertThat(fallback.path("text").asText()).isEqualTo(row.getSummary());
        assertThat(fallback.path("tier").asInt()).isZero();
        assertThat(result.path("dailySummary").path("text").asText()).isEqualTo(row.getSummary());
        assertThat(result.path("finalReport").path("status").asText()).isEqualTo("NONE");
        assertThat(result.path("finalReport").path("summary").isNull()).isTrue();

        // Gọi lại (gửi lại sự kiện) → vẫn một dòng, cùng việc.
        summaryService.onProgress(new ReportSummaryService.ProgressEvent(kid.slotId()));
        assertThat(summaryRepo.findBySlotWithJob(kid.slotId())).hasSize(1);
        assertThat(summaryRepo.findBySlotWithJob(kid.slotId()).get(0).getId()).isEqualTo(row.getId());
    }

    // ------------------------------------------------------------------ 2. dùng lại theo hash
    /**
     * Chương không có hậu trắc (CH02) → bài sinh ngay khi xong chương. Hai bé cùng kết quả → cùng hash, bé thứ hai trỏ vào
     * CÙNG việc đang chờ. Việc xong (writer giả) → bài AI chép sang cả hai (READY). Bé thứ ba cùng kết quả → chép bài AI ngay.
     */
    @Test
    @Order(2)
    void identicalPackagesShareOneJobAndReuseReadyText() throws Exception {
        // Kho hậu trắc chương 2–8 dùng chung với lớp khác và báo cáo tính sống: rút suốt ca để CH02 luôn "không có hậu trắc".
        List<Question> retired = retireChapters();
        try {
            releaseVersion();
            Kid a = kidAtChapterTwo("sumqhasha");
            Kid b = kidAtChapterTwo("sumqhashb");
            DailySummary rowA = chapterTwoRow(a);
            DailySummary rowB = chapterTwoRow(b);
            assertThat(rowB.getPackageHash()).isEqualTo(rowA.getPackageHash());
            UUID jobId = jobOf(rowA).getId();
            assertThat(jobOf(rowB).getId()).isEqualTo(jobId);
            assertThat(chapterRow(report(a), 2).path("summary").path("status").asText()).isEqualTo("GENERATING");

            AiJobWriter ok = (job, progress) -> {
                progress.usingKey("khoa-thu");
                return new AiJobWriter.Output("Bai AI cho chuong 2", 1, "GEMINI", "khoa-thu", "gemini-test", "report-v1");
            };
            drain(jobId, ok);
            AiJob done = jobRepo.findById(jobId).orElseThrow();
            assertThat(done.getStatus()).isEqualTo("DONE");
            assertThat(done.getKeyAlias()).isEqualTo("khoa-thu");
            assertThat(done.getCurrentKeyAlias()).isEqualTo("khoa-thu");
            for (Kid k : List.of(a, b)) {
                JsonNode s = chapterRow(report(k), 2).path("summary");
                assertThat(s.path("status").asText()).isEqualTo("READY");
                assertThat(s.path("text").asText()).isEqualTo("Bai AI cho chuong 2");
                assertThat(s.path("source").asText()).isEqualTo("LLM");
                assertThat(s.path("tier").asInt()).isEqualTo(1);
                assertThat(s.path("provider").asText()).isEqualTo("GEMINI");
            }

            Kid c = kidAtChapterTwo("sumqhashc");
            DailySummary rowC = chapterTwoRow(c);
            assertThat(rowC.getSource()).isEqualTo(SummarySource.LLM);
            assertThat(rowC.getSummary()).isEqualTo("Bai AI cho chuong 2");
            assertThat(jobOf(rowC).getId()).isEqualTo(jobId);
            assertThat(chapterRow(report(c), 2).path("summary").path("status").asText()).isEqualTo("READY");
        } finally {
            restore(retired);
        }
    }

    // ------------------------------------------------------------------ 3. tổng kết 8 chương
    /** Xong CH08, mọi chương COMPLETE → bài FINAL (chapter 8) bản theo luật; finalReport.summary có chữ. */
    @Test
    @Order(3)
    void finalSummaryAfterEightChapters() throws Exception {
        releaseVersion();
        postTestQuestion();
        Kid kid = newKid("sumqfinal");
        // Xong chương cuối thì /api/play/state không còn báo hậu trắc; kho chương 2–8 (lớp khác có thể gắn) tạm rút để các
        // chương đó không có hậu trắc — COMPLETE ngay. Trả lại trong finally.
        List<Question> retired = retireChapters();
        try {
            play(kid, 1, chapterOneBatch(version));
            passPostTest(kid);
            for (int ch = 2; ch <= 8; ch++) {
                play(kid, ch, emptyBatch(ch, version));
                passPostTest(kid);
            }
            assertFinal(kid);
        } finally {
            restore(retired);
        }
    }

    private void assertFinal(Kid kid) throws Exception {
        assertThat(report(kid).path("finalReport").path("status").asText()).as(report(kid).path("chapters").toString())
                .isEqualTo("COMPLETE");
        DailySummary fin = summaryRepo.findBySlot_IdAndKindAndChapterAndAttemptIndex(kid.slotId(), "FINAL", 8, 1)
                .orElseThrow();
        assertThat(fin.getSummary()).startsWith("Tổng kết 8 chương.").contains("Mini-game: bé làm đúng 1/2 câu.");
        assertThat(JSON.readTree(jobOf(fin).getInput()).path("kind").asText()).isEqualTo("FINAL");
        JsonNode fr = report(kid).path("finalReport");
        assertThat(fr.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(fr.path("summary").path("status").asText()).isIn("GENERATING", "READY", "FALLBACK");
        assertThat(fr.path("summary").path("text").asText()).isNotBlank();
    }

    // ------------------------------------------------------------------ 4. luật đếm của hàng chờ
    /** Hết lượt: WAITING_RETRY, không tính lần thử, dừng cả lô (writer gọi đúng một lần), việc sau cũng hẹn lại. */
    @Test
    @Order(4)
    void quotaReschedulesWithoutCountingAndStopsTheBatch() {
        AiJob first = oldJob(1, "QUEUED", null);
        AiJob second = oldJob(2, "QUEUED", null);
        AtomicInteger calls = new AtomicInteger();
        queue.runOnce((job, progress) -> {
            calls.incrementAndGet();
            throw new AiTextClient.AiCallException("429 het luot", true);
        });
        assertThat(calls.get()).isEqualTo(1);
        Instant now = Instant.now(FIXED_CLOCK);
        for (AiJob j : List.of(first, second)) {
            AiJob after = jobRepo.findById(j.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo("WAITING_RETRY");
            assertThat(after.getAttempts()).isZero();
            assertThat(after.getLastErrorCode()).isEqualTo("QUOTA");
            assertThat(after.getNextAttemptAt()).isAfter(now).isBeforeOrEqualTo(now.plus(Duration.ofMinutes(15)));
            assertThat(after.getLockedAt()).isNull();
        }
    }

    /** Lỗi thường: lùi 30 s / 2 phút / 10 phút, lỗi lần thứ 4 → FAILED. */
    @Test
    @Order(5)
    void ordinaryFailuresBackOffThenFail() {
        AiJob job = oldJob(3, "QUEUED", null);
        AiJobWriter broken = (j, progress) -> {
            throw new AiTextClient.AiCallException("503", false);
        };
        Instant now = Instant.now(FIXED_CLOCK);
        List<Duration> waits = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10));
        for (int i = 0; i < 3; i++) {
            queue.runOnce(broken);
            AiJob after = jobRepo.findById(job.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo("WAITING_RETRY");
            assertThat(after.getAttempts()).isEqualTo(i + 1);
            assertThat(after.getLastErrorCode()).isEqualTo("AI_ERROR");
            assertThat(after.getNextAttemptAt()).isEqualTo(now.plus(waits.get(i)));
            makeDue(job.getId());
        }
        queue.runOnce(broken);
        AiJob failed = jobRepo.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getAttempts()).isEqualTo(4);
    }

    /** RUNNING kẹt quá 5 phút (máy khởi động lại) → về hàng chờ và được chạy lại. */
    @Test
    @Order(6)
    void staleRunningJobIsRequeued() {
        AiJob stale = oldJob(4, "RUNNING", Instant.now(FIXED_CLOCK).minus(Duration.ofMinutes(6)));
        AiJob fresh = oldJob(5, "RUNNING", Instant.now(FIXED_CLOCK).minus(Duration.ofMinutes(1)));
        queue.runOnce((job, progress) -> new AiJobWriter.Output("ok", 1, "GEMINI", "k", "m", "p"));
        assertThat(jobRepo.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo("DONE");
        assertThat(jobRepo.findById(fresh.getId()).orElseThrow().getStatus()).isEqualTo("RUNNING");
    }

    // ------------------------------------------------------------------ 5. trang admin
    /** Admin xem việc AI: lọc status, chi tiết; không bao giờ có gói input. Người lớn thường → 403. */
    @Test
    @Order(7)
    void adminSeesJobsWithoutInput() throws Exception {
        AiJob job = oldJob(6, "FAILED", null);
        RequestPostProcessor admin = admin();
        String list = mvc.perform(get("/api/admin/ai-jobs").param("status", "FAILED").param("size", "100").with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(list).contains(job.getId().toString()).doesNotContain("\"input\"").doesNotContain("goi-mat");
        String one = mvc.perform(get("/api/admin/ai-jobs/" + job.getId()).with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode r = JSON.readTree(one).path("result");
        assertThat(r.path("status").asText()).isEqualTo("FAILED");
        assertThat(r.path("inputHash").asText()).isEqualTo(job.getInputHash());
        assertThat(r.has("input")).isFalse();
        String parent = parentToken("sumqadmin");
        mvc.perform(get("/api/admin/ai-jobs").header("Authorization", "Bearer " + parent))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ hàng chờ

    /** Việc tự dựng, createdAt 1970 + n giây → luôn đứng đầu lô, không bị việc của lớp khác chen. */
    private AiJob oldJob(int n, String status, Instant lockedAt) {
        AiJob job = new AiJob();
        job.setKind("REPORT_SUMMARY");
        job.setInput("{\"goi-mat\":" + n + "}");
        job.setInputHash("test-" + UUID.randomUUID());
        job.setStatus(status);
        job.setAttempts(0);
        job.setTier(1);
        job.setLockedAt(lockedAt);
        job.setCreatedAt(Instant.EPOCH.plusSeconds(n * 1000L + System.nanoTime() % 1000));
        return jobRepo.save(job);
    }

    private void makeDue(UUID id) {
        AiJob job = jobRepo.findById(id).orElseThrow();
        job.setNextAttemptAt(Instant.now(FIXED_CLOCK));
        jobRepo.save(job);
    }

    /** Chạy job tới khi việc của mình xong (hàng chờ có việc của lớp khác). */
    private void drain(UUID jobId, AiJobWriter writer) {
        for (int i = 0; i < 200; i++) {
            String s = jobRepo.findById(jobId).orElseThrow().getStatus();
            if ("DONE".equals(s) || "FAILED".equals(s)) {
                return;
            }
            if ("WAITING_RETRY".equals(s)) {
                makeDue(jobId);   // đồng hồ đứng yên: việc bị hẹn lại (ca hàng chờ khác trong lớp) không bao giờ tới hạn
            }
            if (queue.runOnce(writer) == 0) {
                break;
            }
        }
        assertThat(jobRepo.findById(jobId).orElseThrow().getStatus()).isIn("DONE", "FAILED");
    }

    private AiJob jobOf(DailySummary row) {
        UUID id = tx.execute(s -> summaryRepo.findById(row.getId()).orElseThrow().getAiJob().getId());
        return jobRepo.findById(id).orElseThrow();
    }

    private DailySummary chapterTwoRow(Kid kid) {
        return summaryRepo.findBySlot_IdAndKindAndChapterAndAttemptIndex(kid.slotId(), "CHAPTER", 2, 1).orElseThrow();
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ chơi

    private record Kid(String token, UUID slotId, String code, String groupId, String groupName, String parent,
                       String parentEmail) {}

    private Kid kidAtChapterTwo(String prefix) throws Exception {
        postTestQuestion();
        Kid kid = newKid(prefix);
        play(kid, 1, chapterOneBatch(version));
        passPostTest(kid);
        play(kid, 2, emptyBatch(2, version));   // CH02 không có hậu trắc (gọi trong retireChapters) → bài sinh ngay
        return kid;
    }

    /** Kho hậu trắc dùng chung H2 với lớp khác: tạm rút câu chương 2–8 (trả lại bằng restore). */
    private List<Question> retireChapters() {
        List<Question> out = new java.util.ArrayList<>();
        for (int ch = 2; ch <= 8; ch++) {
            out.addAll(questionRepo.findByChapterAndRetiredAtIsNullOrderByExternalIdAsc(ch));
        }
        out.forEach(q -> q.setRetiredAt(Instant.parse("2026-10-01T00:00:00Z")));
        return questionRepo.saveAll(out);
    }

    private void restore(List<Question> retired) {
        retired.forEach(q -> q.setRetiredAt(null));
        questionRepo.saveAll(retired);
    }

    private void play(Kid kid, int chapter, String body) throws Exception {
        var res = mvc.perform(post("/api/play/chapter").header("Authorization", "Bearer " + kid.token())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertThat(res.getStatus()).as("chuong " + chapter + ": " + res.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
    }

    /** Chương vừa xong có đề hậu trắc thì nộp bài trắng (như PlayFlowTest.passPostTest); không có thì thôi. */
    private void passPostTest(Kid kid) throws Exception {
        String state = mvc.perform(get("/api/play/state").header("Authorization", "Bearer " + kid.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode required = JSON.readTree(state).path("result").path("postTestRequired");
        if (required.isObject()) {
            mvc.perform(post("/api/learner/quizzes/" + required.path("quizId").asText() + "/submit")
                            .header("Authorization", "Bearer " + kid.token())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"answers\":[]}"))
                    .andExpect(status().isOk());
        }
    }

    private JsonNode report(Kid kid) throws Exception {
        String body = mvc.perform(get("/api/slots/" + kid.slotId() + "/report")
                        .header("Authorization", "Bearer " + kid.parent()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).path("result");
    }

    private static JsonNode chapterRow(JsonNode report, int chapter) {
        for (JsonNode c : report.path("chapters")) {
            if (c.path("chapter").asInt() == chapter) {
                return c;
            }
        }
        throw new AssertionError("khong co chuong " + chapter + ": " + report.path("chapters"));
    }

    /** Một câu hậu trắc chương 1 có mã — kho chương 1 luôn có ít nhất một câu, CH01 luôn chờ hậu trắc. */
    private void postTestQuestion() {
        Question q = new Question();
        q.setExternalId("SUMQ" + System.nanoTime());
        q.setPool("FINTEEN_POST");
        q.setStem("Cau " + q.getExternalId());
        q.setOptions("[\"Dung\",\"Sai\"]");
        q.setCorrectIndex(0);
        q.setConcept("D01.03");
        q.setChapter(1);
        q.setCreatedAt(Instant.now(FIXED_CLOCK));
        questionRepo.save(q);
    }

    // ------------------------------------------------------------------ nội dung

    private String releaseVersion() throws Exception {
        if (version == null) {
            Map<String, String> chapters = new LinkedHashMap<>();
            chapters.put("CH01", chapterOne());
            for (int i = 2; i <= 8; i++) {
                chapters.put("CH0" + i, minimalChapter(i));
            }
            version = ContentTestSupport.publishFresh(mvc, admin(), new ContentTestSupport.Repos(
                    releaseChapterRepo, releaseRepo, draftRepo, chapterRepo), chapters, "report summary test");
        }
        return version;
    }

    private static String chapterOne() {
        return """
                {"id":"chapter-1","title":"Chuong mot tong ket","version":"1.0.0",
                 "initialStats":{"SAVING":50,"HAPPINESS":50,"RISK":30,"GOAL":50,"WEALTH":0},
                 "metadata":{"chapterId":"CH01","targetAge":14,"goalDescription":"Muc tieu chuong mot",
                   "coreKnowledgeUnits":["D01.02","D01.03","D01.04"]},
                 "sprites":{"MOM":{"neutral":"{{ASSET_BASE}}/a/abc.webp"}},
                 "scenes":[
                   {"id":"intro","type":"dialogue","background":"{{ASSET_BASE}}/a/bg1.webp","text":"Chao","nextSceneId":"choice-1"},
                   {"id":"choice-1","type":"choice","options":[
                     {"id":"opt-save","text":"Tiet kiem","deltaStats":{"SAVING":10,"HAPPINESS":5},
                      "evidenceScores":{"I":1,"C":0,"P":0},"nextSceneId":"game-1"},
                     {"id":"opt-spend","text":"Tieu tien","deltaStats":{"SAVING":-5,"HAPPINESS":10},
                      "evidenceScores":{"I":0,"C":0,"P":0},"nextSceneId":"game-1"}]},
                   {"id":"game-1","type":"minigame","knowledgeUnits":["D01.02"],"nextSceneId":"end",
                    "game":{"id":"NEEDS_WANTS_SUM","mechanic":"DRAG_AND_DROP","items":[
                      {"id":"ITEM_MEAL","category":"NEEDS","text":"Bua an"},
                      {"id":"ITEM_UPGRADE","category":"WANTS","text":"Nang cap tai nghe"}]}},
                   {"id":"end","type":"ending"}
                 ],
                 "endingRules":{"priorityOrder":["x"],"endings":[]}}
                """;
    }

    private static String minimalChapter(int n) {
        return """
                {"id":"chapter-%d","title":"Chuong %d tong ket","version":"1.0.0",
                 "initialStats":{"SAVING":50,"HAPPINESS":50},
                 "metadata":{"chapterId":"CH0%d","goalDescription":"Muc tieu %d","coreKnowledgeUnits":["D0%d.01"]},
                 "sprites":{"MOM":{"neutral":"{{ASSET_BASE}}/a/abc.webp"}},
                 "scenes":[
                   {"id":"intro%d","type":"dialogue","background":"{{ASSET_BASE}}/a/bg1.webp","text":"Chao","nextSceneId":"end%d"},
                   {"id":"end%d","type":"ending"}
                 ],
                 "endingRules":{"priorityOrder":["x"],"endings":[]}}
                """.formatted(n, n, n, n, n, n, n, n);
    }

    /** Lô CH01: chọn phương án có bước cân nhắc + mini-game một đúng một sai. */
    private static String chapterOneBatch(String version) {
        return "{\"chapter\":1,\"attemptIndex\":1,\"releaseVersion\":\"" + version + "\","
                + "\"choices\":[{\"sceneId\":\"choice-1\",\"optionId\":\"opt-save\",\"chosenAt\":\"2026-10-02T03:00:00Z\"}],"
                + "\"miniGames\":["
                + "{\"gameId\":\"NEEDS_WANTS_SUM\",\"itemId\":\"ITEM_MEAL\",\"chosen\":\"NEEDS\",\"playedAt\":\"2026-10-02T03:00:00Z\"},"
                + "{\"gameId\":\"NEEDS_WANTS_SUM\",\"itemId\":\"ITEM_UPGRADE\",\"chosen\":\"NEEDS\"}],"
                + "\"finishedAt\":\"2026-10-02T03:00:00Z\"}";
    }

    private static String emptyBatch(int chapter, String version) {
        return "{\"chapter\":" + chapter + ",\"attemptIndex\":1,\"releaseVersion\":\"" + version
                + "\",\"choices\":[],\"miniGames\":[]}";
    }

    // ------------------------------------------------------------------ tài khoản (khuôn PlayFlowTest)

    private Kid newKid(String prefix) throws Exception {
        String email = prefix + "-" + System.nanoTime() + "@test.local";
        String parent = registerAndVerify(email);
        grant(parent);
        String groupName = "Nha " + prefix + System.nanoTime();
        String groupBody = mvc.perform(post("/api/groups").header("Authorization", "Bearer " + parent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + groupName + "\",\"context\":\"FAMILY\",\"plannedSize\":40}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String groupId = match("\"id\":\"([0-9a-f-]+)\"", groupBody);
        String slotBody = mvc.perform(post("/api/slots").header("Authorization", "Bearer " + parent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + groupId + "\",\"displayName\":\"" + KID_NAME
                                + "\",\"pin\":\"123456\",\"badge\":\"nhi dong\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String code = match("\"code\":\"([A-Z0-9]+)\"", slotBody);
        String login = mvc.perform(post("/api/slots/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"pin\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = match("\"accessToken\":\"([^\"]+)\"", login);
        return new Kid(token, UUID.fromString(subject(token)), code, groupId, groupName, parent, email);
    }

    private String parentToken(String prefix) throws Exception {
        String jwt = registerAndVerify(prefix + "-" + System.nanoTime() + "@test.local");
        grant(jwt);
        return jwt;
    }

    private RequestPostProcessor admin() {
        Account a = new Account();
        a.setEmail("sumq-admin-" + System.nanoTime() + "@test.local");
        a.setDisplayName("Admin Sumq");
        a.setCreatedAt(Instant.now());
        a = accountRepo.save(a);
        AccountRole role = new AccountRole();
        role.setAccount(a);
        role.setRole(Role.ADMIN);
        role.setGrantedAt(Instant.now());
        accountRoleRepo.save(role);
        String id = a.getId().toString();
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("SCOPE_ADMIN"));
    }

    private void grant(String jwt) {
        Account a = accountRepo.findById(UUID.fromString(subject(jwt))).orElseThrow();
        Entitlement e = new Entitlement();
        e.setAccount(a);
        e.setKind(PlanKind.PARENT);
        e.setStartsOn(LocalDate.now(FIXED_CLOCK).minusDays(1));
        e.setExpiresOn(LocalDate.now(FIXED_CLOCK).plusMonths(3));
        e.setSource(EntitlementSource.ADMIN);
        e.setGrantedBy(a);
        e.setCreatedAt(Instant.now(FIXED_CLOCK));
        entitlementRepo.save(e);
    }

    private String registerAndVerify(String email) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"phone\":\"0900000000\","
                                + "\"password\":\"matkhau123\",\"displayName\":\"Nguyen Test\"}"))
                .andExpect(status().isOk());
        assertThat(mailCatcher.sent).isNotEmpty();
        OutgoingMail last = mailCatcher.sent.get(mailCatcher.sent.size() - 1);
        Matcher m = OTP_IN_MAIL.matcher(last.body());
        assertThat(m.find()).isTrue();
        String body = mvc.perform(post("/api/auth/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"otp\":\"" + m.group(1) + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return match("\"accessToken\":\"([^\"]+)\"", body);
    }

    private static String match(String regex, String body) {
        Matcher m = Pattern.compile(regex).matcher(body);
        assertThat(m.find()).as(regex + " trong " + body).isTrue();
        return m.group(1);
    }

    private static String subject(String jwt) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[1]),
                StandardCharsets.UTF_8);
        return match("\"sub\":\"([^\"]+)\"", payload);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        Clock testClock() {
            return FIXED_CLOCK;
        }

        @Bean
        AuthFlowTest.MailCatcher thu() {
            return new AuthFlowTest.MailCatcher();
        }
    }
}
