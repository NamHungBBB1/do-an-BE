package com.doan.game;

import java.util.Map;
import com.doan.game.repository.ChapterRepository;
import com.doan.game.repository.ChapterDraftRepository;
import com.doan.game.repository.GameReleaseRepository;
import com.doan.game.repository.ReleaseChapterRepository;
import com.doan.game.configuration.ClockConfig;
import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.entity.Entitlement;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.PlanKind;
import com.doan.game.enums.Role;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.ChoiceEventRepository;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.MiniGameResultRepository;
import com.doan.game.repository.RunStateRepository;
import com.doan.game.service.OutgoingMail;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ruột POST /api/play/chapter + GET /api/play/state đi qua HTTP thật (MockMvc + H2), token SLOT
 * THẬT theo khuôn AuthFlowTest — 7 ca trong contract play.md (Hưng duyệt 12/10).
 *
 * Dựng: admin lưu nháp CH01 (JSON có cảnh choice 2 option với deltaStats, cảnh minigame
 * DRAG_AND_DROP 2 item) + CH02, phát hành bản riêng "1.0.2". Bản CÓ 2 chương để tách được hai lỗi:
 * chương 2 tồn tại mà chưa tới lượt → 7003, chương không có trong bản → 7001 (bản 1 chương thì
 * chương 2 rơi 7001, không test được 7003); ca chơi lại vì thế chơi hết 2 chương rồi mới
 * attemptIndex 2 — cùng luật "xong hết mọi chương của bản hiện tại".
 *
 * Thứ tự class: ContentFlowTest (C) chạy trước PlayFlowTest (P) theo thứ tự filesystem của
 * surefire; ContentFlowTest để current = 1.0.0 nên mình phát hành 1.0.2 (không đụng 1.0.0/1.0.1).
 * Mỗi ca tự mở nhóm / slot riêng → độc lập, không phụ thuộc thứ tự ca trong class.
 * Đồng hồ đóng băng 10:00 02/10/2026 giờ VN — gói cấp trong test luôn còn hạn.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class PlayFlowTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ClockConfig.VN);
    private static final Pattern OTP_IN_MAIL = Pattern.compile(">(\\d{6})</p>");

    /** Bản của test này — ContentFlowTest chỉ dùng 1.0.0 / 1.0.1 nên 1.0.2 luôn còn trống. */
    /** Số hiệu BE tính khi phát hành (PR C 23/10) — một lần mỗi class, xem ContentTestSupport. */
    private static String version;

    @Autowired MockMvc mvc;
    @Autowired ReleaseChapterRepository releaseChapterRepo;
    @Autowired GameReleaseRepository releaseRepo;
    @Autowired ChapterDraftRepository draftRepo;
    @Autowired ChapterRepository chapterRepo;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountRoleRepository accountRoleRepo;
    @Autowired EntitlementRepository entitlementRepo;
    @Autowired ChoiceEventRepository choiceEventRepo;
    @Autowired MiniGameResultRepository miniGameResultRepo;
    @Autowired RunStateRepository runStateRepo;
    @Autowired AuthFlowTest.MailCatcher mailCatcher;

    @BeforeEach
    void resetMailbox() {
        mailCatcher.sent.clear();
    }

    // ------------------------------------------------------------------ 1. lô hợp lệ
    /** Lô hợp lệ → 200, đúng response; DB: 1 ChoiceEvent effects đúng JSON, 2 MiniGameResult một đúng một sai, RunState = initial + delta. */
    @Test
    void validBatchStampsChapterAndScores() throws Exception {
        releaseVersion();
        String child = newChild("playhop");

        var res = mvc.perform(batchRequest(child, validBatch(1, 1, version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result.attemptIndex").value(1))
                .andExpect(jsonPath("$.result.totalChapters").value(2))
                .andExpect(jsonPath("$.result.chaptersDone[0]").value(1))
                .andExpect(jsonPath("$.result.chaptersDone.length()").value(1))
                .andExpect(jsonPath("$.result.continueChapter").value(2))
                .andExpect(jsonPath("$.result.newAchievements").isEmpty())
                .andReturn().getResponse();
        UUID slotId = UUID.fromString(subject(child));

        // ChoiceEvent: đúng 1 dòng, effects BE tra từ JSON chương (client không gửi).
        var choices = choiceEventRepo.findBySlot_IdAndChapterAndAttemptIndex(slotId, 1, 1);
        assertThat(choices).hasSize(1);
        assertThat(choices.get(0).getEffects()).contains("\"SAVING\":10").contains("\"FIQ\":99");
        assertThat(choices.get(0).getBuildVersion()).isEqualTo(version);

        // MiniGameResult: 2 dòng cùng game — ITEM_MEAL đúng (NEEDS == category), ITEM_UPGRADE sai.
        var minis = miniGameResultRepo.findBySlot_IdAndChapterAndAttemptIndex(slotId, 1, 1);
        assertThat(minis).hasSize(2);
        assertThat(minis)
                .anySatisfy(m -> {
                    assertThat(m.getItemId()).isEqualTo("ITEM_MEAL");
                    assertThat(m.isCorrect()).isTrue();
                })
                .anySatisfy(m -> {
                    assertThat(m.getItemId()).isEqualTo("ITEM_UPGRADE");
                    assertThat(m.isCorrect()).isFalse();
                });

        // RunState: initialStats chương 1 (SAVING 50) + delta (+10) = 60; khoá FIQ trong JSON bỏ qua.
        var run = runStateRepo.findBySlot_Id(slotId).orElseThrow();
        assertThat(run.getAttemptIndex()).isEqualTo(1);
        assertThat(run.getSaving()).isEqualTo(60);
        assertThat(run.getHappiness()).isEqualTo(55);
        assertThat(run.getRisk()).isEqualTo(30);
        assertThat(run.getGoal()).isEqualTo(50);
        assertThat(run.getWealth()).isEqualTo(0);
        assertThat(run.getChaptersDone()).isEqualTo("[1]");
        assertThat(res.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("wealth");
    }

    // ------------------------------------------------------------------ 1a. khuôn v3 — câu `grade`, hàm chấm chung
    /**
     * Mini-game có câu {@code grade}: FE gửi theo Ô NHẬP (giá trị JSON bất kỳ), BE chấm MỌI câu của cảnh — ô thiếu → câu sai,
     * ô lạ → bỏ qua; chosen dài (mảng / object) lưu được (cột TEXT); khái niệm theo câu, mặc định theo cảnh.
     */
    @Test
    void gradedMiniGameScoresEveryQuestionFromInputs() throws Exception {
        releaseVersion();
        String child = newChild("playv3");
        String batch = "{\"chapter\":1,\"attemptIndex\":1,\"releaseVersion\":\"" + version + "\",\"choices\":[],\"miniGames\":["
                + "{\"gameId\":\"SHOP_V3\",\"itemId\":\"phone\",\"chosen\":\"A\"},"
                + "{\"gameId\":\"SHOP_V3\",\"itemId\":\"inspected\",\"chosen\":[\"A:app-store-reviews\","
                + "\"A:battery-health-report\",\"A:terms-and-conditions\"]},"
                + "{\"gameId\":\"SHOP_V3\",\"itemId\":\"cancelDay\",\"chosen\":30},"
                + "{\"gameId\":\"SHOP_V3\",\"itemId\":\"phone\",\"chosen\":\"B\"},"
                + "{\"gameId\":\"SHOP_V3\",\"itemId\":\"junk\",\"chosen\":{\"x\":1}}]}";
        mvc.perform(batchRequest(child, batch)).andExpect(status().isOk());
        var minis = miniGameResultRepo.findBySlot_IdAndChapterAndAttemptIndex(UUID.fromString(subject(child)), 1, 1);
        assertThat(minis).extracting(m -> m.getItemId()).containsExactlyInAnyOrder("budget", "evidence", "Q_RATE");
        assertThat(minis).allSatisfy(m -> assertThat(m.getGameId()).isEqualTo("SHOP_V3"))
                .anySatisfy(m -> {   // phone lần đầu = A (dòng B gửi sau bị bỏ), huỷ ngày 30 > 29 → vượt ngân sách
                    assertThat(m.getItemId()).isEqualTo("budget");
                    assertThat(m.isCorrect()).isFalse();
                    assertThat(m.getChosen()).isEqualTo("{\"phone\":\"A\",\"cancelDay\":30}");
                    assertThat(m.getConcept()).isEqualTo("D03.04");
                })
                .anySatisfy(m -> {
                    assertThat(m.getItemId()).isEqualTo("evidence");
                    assertThat(m.isCorrect()).isTrue();
                    assertThat(m.getChosen()).hasSizeGreaterThan(64).contains("A:terms-and-conditions");
                })
                .anySatisfy(m -> {   // không gửi ô Q_RATE → sai, chosen null; khái niệm khai riêng ở câu
                    assertThat(m.getItemId()).isEqualTo("Q_RATE");
                    assertThat(m.isCorrect()).isFalse();
                    assertThat(m.getChosen()).isNull();
                    assertThat(m.getConcept()).isEqualTo("D03.09");
                });
    }

    // ------------------------------------------------------------------ 1b. CAREER_MATCH
    /** Game có CẢ jobs lẫn questions (Kidz review 12/10): itemId là việc hay câu hỏi đều chấm được — một job fit, một job over, một câu đúng, một câu sai. */
    @Test
    void careerMatchScoresJobsAndQuestions() throws Exception {
        releaseVersion();
        String child = newChild("career");
        String body = "{\"chapter\":1,\"attemptIndex\":1,\"releaseVersion\":\"" + version + "\","
                + "\"choices\":[],\"miniGames\":["
                + "{\"gameId\":\"CAREER_MATCH_CH03\",\"itemId\":\"JOB_DAY\",\"chosen\":\"fit\"},"
                + "{\"gameId\":\"CAREER_MATCH_CH03\",\"itemId\":\"JOB_NIGHT\",\"chosen\":\"fit\"},"
                + "{\"gameId\":\"CAREER_MATCH_CH03\",\"itemId\":\"Q_NET\",\"chosen\":\"OPT_HIGH\"},"
                + "{\"gameId\":\"CAREER_MATCH_CH03\",\"itemId\":\"Q_PROFIT\",\"chosen\":\"OPT_DOWN\"}]}";

        mvc.perform(batchRequest(child, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        UUID slotId = UUID.fromString(subject(child));
        var minis = miniGameResultRepo.findBySlot_IdAndChapterAndAttemptIndex(slotId, 1, 1);
        assertThat(minis).hasSize(4);
        assertThat(minis)
                .anySatisfy(m -> {
                    assertThat(m.getItemId()).isEqualTo("JOB_DAY");
                    assertThat(m.isCorrect()).isTrue();
                })
                .anySatisfy(m -> {
                    assertThat(m.getItemId()).isEqualTo("JOB_NIGHT");
                    assertThat(m.isCorrect()).isFalse();
                })
                .anySatisfy(m -> {
                    assertThat(m.getItemId()).isEqualTo("Q_NET");
                    assertThat(m.isCorrect()).isTrue();
                })
                .anySatisfy(m -> {
                    assertThat(m.getItemId()).isEqualTo("Q_PROFIT");
                    assertThat(m.isCorrect()).isFalse();
                });
    }

    // ------------------------------------------------------------------ 2. gửi lại
    /** Gửi lại y hệt → 200 cùng result, số dòng DB không đổi (UNIQUE + kiểm chaptersDone). */
    @Test
    void resendSameBatchIsNoop() throws Exception {
        releaseVersion();
        String child = newChild("playgui");
        String body = validBatch(1, 1, version);

        mvc.perform(batchRequest(child, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.chaptersDone.length()").value(1));

        UUID slotId = UUID.fromString(subject(child));
        long choices = choiceEventRepo.countBySlot_IdAndChapterAndAttemptIndex(slotId, 1, 1);
        long minis = miniGameResultRepo.countBySlot_IdAndChapterAndAttemptIndex(slotId, 1, 1);

        mvc.perform(batchRequest(child, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result.attemptIndex").value(1))
                .andExpect(jsonPath("$.result.chaptersDone[0]").value(1))
                .andExpect(jsonPath("$.result.continueChapter").value(2));

        assertThat(choiceEventRepo.countBySlot_IdAndChapterAndAttemptIndex(slotId, 1, 1)).isEqualTo(choices);
        assertThat(miniGameResultRepo.countBySlot_IdAndChapterAndAttemptIndex(slotId, 1, 1)).isEqualTo(minis);
    }

    // ------------------------------------------------------------------ 3. lỗi 7003 / 7001 / 7002
    /** Chương 2 chưa tới lượt → 409/7003; bản lạ → 404/7001; optionId lạ → 400/7002. */
    @Test
    void wrongBatchGetsRightErrors() throws Exception {
        String v = releaseVersion();
        String child = newChild("loibatch");

        mvc.perform(batchRequest(child, "{\"chapter\":2,\"attemptIndex\":1,\"releaseVersion\":\"" + v
                        + "\",\"choices\":[],\"miniGames\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(7003));

        mvc.perform(batchRequest(child, "{\"chapter\":1,\"attemptIndex\":1,\"releaseVersion\":\"9.9.9\","
                        + "\"choices\":[],\"miniGames\":[]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(7001));

        mvc.perform(batchRequest(child, "{\"chapter\":1,\"attemptIndex\":1,\"releaseVersion\":\"" + v
                        + "\",\"choices\":[{\"sceneId\":\"choice-1\",\"optionId\":\"khong-ton-tai\"}],"
                        + "\"miniGames\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(7002));
    }

    // ------------------------------------------------------------------ 4. cửa quyền
    /** Token người lớn → 403/3004; slot đã trả → 410/3009 — cùng luật dashboard. */
    @Test
    void adultTokenAndReturnedSlotRejected() throws Exception {
        String v = releaseVersion();
        String parent = parentToken("playnguolon");

        mvc.perform(post("/api/play/chapter").header("Authorization", "Bearer " + parent)
                        .contentType(MediaType.APPLICATION_JSON).content(validBatch(1, 1, v)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));

        String groupId = openGroup(parent, "Nha tra", "FAMILY");
        String slotBody = mvc.perform(slotRequest(parent, groupId, "Be Tra", "123456"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String child = childLogin(slotBody, "123456");
        mvc.perform(post("/api/slots/" + subject(child) + "/return")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk());

        mvc.perform(batchRequest(child, validBatch(1, 1, v)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(3009));
    }

    // ------------------------------------------------------------------ 5. state + dashboard đợt 2
    /** GET /api/play/state trước và sau lô; dashboard sau lô có progress từ RunState + recentActivities CHAPTER_DONE. */
    @Test
    void stateAndDashboardReflectRun() throws Exception {
        String v = releaseVersion();
        String child = newChild("playstate");

        // TRƯỚC khi chơi: chưa có RunState → mặc định.
        mvc.perform(get("/api/play/state").header("Authorization", "Bearer " + child))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.releaseVersion").value(v))
                .andExpect(jsonPath("$.result.attemptIndex").value(1))
                .andExpect(jsonPath("$.result.totalChapters").value(2))
                .andExpect(jsonPath("$.result.completedChapters").value(0))
                .andExpect(jsonPath("$.result.chaptersDone").isEmpty())
                .andExpect(jsonPath("$.result.continueChapter").value(1))
                .andExpect(jsonPath("$.result.lastPlayedAt").value(nullValue()));

        mvc.perform(batchRequest(child, validBatch(1, 1, v)))
                .andExpect(status().isOk());

        // SAU lô: cùng nguồn RunState với dashboard.
        mvc.perform(get("/api/play/state").header("Authorization", "Bearer " + child))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.attemptIndex").value(1))
                .andExpect(jsonPath("$.result.completedChapters").value(1))
                .andExpect(jsonPath("$.result.chaptersDone[0]").value(1))
                .andExpect(jsonPath("$.result.continueChapter").value(2))
                .andExpect(jsonPath("$.result.lastPlayedAt").value(org.hamcrest.Matchers.notNullValue()));

        mvc.perform(get("/api/learner/dashboard").header("Authorization", "Bearer " + child))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.progress.completedChapters").value(1))
                .andExpect(jsonPath("$.result.progress.chaptersDone[0]").value(1))
                .andExpect(jsonPath("$.result.progress.continueChapter").value(2))
                .andExpect(jsonPath("$.result.progress.lastPlayedAt").value(org.hamcrest.Matchers.notNullValue()))
                .andExpect(jsonPath("$.result.recentActivities.length()").value(1))
                .andExpect(jsonPath("$.result.recentActivities[0].type").value("CHAPTER_DONE"))
                .andExpect(jsonPath("$.result.recentActivities[0].label").value("Xong chương 1"))
                .andExpect(jsonPath("$.result.recentActivities[0].at").value(org.hamcrest.Matchers.notNullValue()));
    }

    // ------------------------------------------------------------------ 6. 5 chỉ số không lọt ra
    /** Response cho token SLOT không bao giờ chứa chuỗi "wealth" / "saving" (Hưng chốt 10/10). */
    @Test
    void responsesHideFiveStats() throws Exception {
        String v = releaseVersion();
        String child = newChild("anatchi");

        mvc.perform(batchRequest(child, validBatch(1, 1, v)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("wealth"))))
                .andExpect(content().string(not(containsString("saving"))));

        mvc.perform(get("/api/play/state").header("Authorization", "Bearer " + child))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("wealth"))))
                .andExpect(content().string(not(containsString("saving"))));

        mvc.perform(get("/api/learner/dashboard").header("Authorization", "Bearer " + child))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("wealth"))));
    }

    // ------------------------------------------------------------------ 7. chơi lại
    /** Xong hết 2 chương của bản → attemptIndex 2, chaptersDone về [1], lần 1 vẫn còn trong DB. */
    @Test
    void replayAfterAllChaptersStartsNewAttempt() throws Exception {
        String v = releaseVersion();
        String child = newChild("choilai");

        mvc.perform(batchRequest(child, validBatch(1, 1, v)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.chaptersDone.length()").value(1));

        mvc.perform(batchRequest(child, emptyBatch(2, 1, v)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.chaptersDone.length()").value(2))
                .andExpect(jsonPath("$.result.chaptersDone[1]").value(2))
                .andExpect(jsonPath("$.result.continueChapter").value(nullValue()));

        // Chơi lại: attemptIndex 2, chương 1 của lần chơi mới.
        mvc.perform(batchRequest(child, validBatch(1, 2, v)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.attemptIndex").value(2))
                .andExpect(jsonPath("$.result.chaptersDone.length()").value(1))
                .andExpect(jsonPath("$.result.chaptersDone[0]").value(1))
                .andExpect(jsonPath("$.result.continueChapter").value(2));

        UUID slotId = UUID.fromString(subject(child));
        // Lần 1 vẫn còn nguyên trong DB (không xoá, không ghi đè).
        assertThat(choiceEventRepo.countBySlot_IdAndChapterAndAttemptIndex(slotId, 1, 1)).isEqualTo(1);
        assertThat(miniGameResultRepo.countBySlot_IdAndChapterAndAttemptIndex(slotId, 1, 1)).isEqualTo(2);
        assertThat(choiceEventRepo.countBySlot_IdAndChapterAndAttemptIndex(slotId, 1, 2)).isEqualTo(1);
        assertThat(miniGameResultRepo.countBySlot_IdAndChapterAndAttemptIndex(slotId, 1, 2)).isEqualTo(2);
        var run = runStateRepo.findBySlot_Id(slotId).orElseThrow();
        assertThat(run.getAttemptIndex()).isEqualTo(2);
        assertThat(run.getChaptersDone()).isEqualTo("[1]");
        // Chỉ số reset về initialStats rồi cộng lại delta của lần mới.
        assertThat(run.getSaving()).isEqualTo(60);
    }

    // ------------------------------------------------------------------ dựng dữ liệu
    /**
     * Bản "1.0.2" của test này, idempotent: đã là current thì qua; nếu chạy lại trong cùng JVM
     * (6001) thì chỉ đưa con trỏ về. JSON chương giàu deltaStats + minigame để BE chấm được.
     */
    private String releaseVersion() throws Exception {
        if (version == null) {
            version = ContentTestSupport.publishFresh(mvc, admin(), new ContentTestSupport.Repos(
                    releaseChapterRepo, releaseRepo, draftRepo, chapterRepo), Map.of("CH01", chapterOne(), "CH02", chapterTwo()), "play flow test");
        }
        return version;
    }

    /** CH01: 1 cảnh choice 2 option (deltaStats, có khoá FIQ để kiểm bỏ qua) + 2 mini-game —
     *  DRAG_AND_DROP 2 item (Cần/Muốn) và CAREER_MATCH có CẢ jobs lẫn questions (Kidz review 12/10).
     *  Hình dạng đúng JSON chương 1.0.0 thật: cảnh "minigame", game có "mechanic". */
    private static String chapterOne() {
        return """
                {"id":"chapter-1","title":"Chuong 1 cho test play","version":"1.0.0",
                 "initialStats":{"SAVING":50,"HAPPINESS":50,"RISK":30,"GOAL":50,"WEALTH":0,"FIQ":10},
                 "sprites":{"MOM":{"neutral":"{{ASSET_BASE}}/a/abc.webp"}},
                 "scenes":[
                   {"id":"intro","type":"dialogue","background":"{{ASSET_BASE}}/a/bg1.webp","text":"Chao","nextSceneId":"choice-1"},
                   {"id":"choice-1","type":"choice","knowledgeUnits":["D01.01"],"options":[
                     {"id":"opt-save","text":"Tiet kiem","deltaStats":{"SAVING":10,"HAPPINESS":5,"FIQ":99},"nextSceneId":"game-1"},
                     {"id":"opt-spend","text":"Tieu tien","deltaStats":{"SAVING":-5,"HAPPINESS":10},"nextSceneId":"game-1"}]},
                   {"id":"game-1","type":"minigame","knowledgeUnits":["D01.01"],"nextSceneId":"game-2",
                    "game":{"id":"NEEDS_OR_WANTS_CH01","mechanic":"DRAG_AND_DROP","items":[
                      {"id":"ITEM_MEAL","category":"NEEDS","text":"Bua an"},
                      {"id":"ITEM_UPGRADE","category":"WANTS","text":"Nang cap tai nghe"}]}},
                   {"id":"game-2","type":"minigame","nextSceneId":"game-3",
                    "game":{"id":"CAREER_MATCH_CH03","mechanic":"MATCH_AND_CALCULATE","weeklyLimit":20,
                     "jobs":[
                       {"id":"JOB_DAY","hours":10,"text":"Viec ngay"},
                       {"id":"JOB_NIGHT","hours":30,"text":"Viec dem"}],
                     "questions":[
                       {"id":"Q_NET","options":[{"id":"OPT_HIGH"},{"id":"OPT_LOW"}],"correctId":"OPT_HIGH"},
                       {"id":"Q_PROFIT","options":[{"id":"OPT_UP"},{"id":"OPT_DOWN"}],"correctId":"OPT_UP"}]}},
                   {"id":"game-3","type":"minigame","knowledgeUnits":["D03.04"],"nextSceneId":"end",
                    "game":{"id":"SHOP_V3","config":{"criteria":{
                      "budget":{"title":"Ngan sach","grade":{"id":"budget","rule":{"any":[{"input":"phone","eq":"B"},
                        {"all":[{"input":"phone","eq":"A"},{"input":"cancelDay","min":1,"max":29}]}]}}},
                      "evidence":{"title":"Bang chung","grade":{"id":"evidence","rule":{"all":[{"input":"phone","eq":"A"},
                        {"input":"inspected","has":["A:app-store-reviews","A:battery-health-report","A:terms-and-conditions"]}]}}}},
                     "questions":[{"id":"Q_RATE","grade":{"concept":"D03.09","rule":{"eq":5}}}]}}},
                   {"id":"end","type":"ending"}
                 ],
                 "endingRules":{"priorityOrder":["x"],"endings":[]}}
                """;
    }

    /** CH02 tối giản — chỉ để bản có 2 chương (tách 7003 / 7001 và cho "xong hết" ở ca chơi lại). */
    private static String chapterTwo() {
        return """
                {"id":"chapter-2","title":"Chuong 2 cho test play","version":"1.0.0",
                 "initialStats":{"SAVING":50,"HAPPINESS":50},
                 "sprites":{"MOM":{"neutral":"{{ASSET_BASE}}/a/abc.webp"}},
                 "scenes":[
                   {"id":"intro2","type":"dialogue","background":"{{ASSET_BASE}}/a/bg1.webp","text":"Chao","nextSceneId":"end2"},
                   {"id":"end2","type":"ending"}
                 ],
                 "endingRules":{"priorityOrder":["x"],"endings":[]}}
                """;
    }

    /** Lô chương 1 đầy đủ: 1 lựa chọn + 2 dòng mini-game (một đúng một sai). */
    private static String validBatch(int chapter, int attempt, String version) {
        return "{\"chapter\":" + chapter + ",\"attemptIndex\":" + attempt
                + ",\"releaseVersion\":\"" + version + "\","
                + "\"choices\":[{\"sceneId\":\"choice-1\",\"optionId\":\"opt-save\","
                + "\"chosenAt\":\"2026-10-02T03:00:00Z\"}],"
                + "\"miniGames\":["
                + "{\"gameId\":\"NEEDS_OR_WANTS_CH01\",\"itemId\":\"ITEM_MEAL\",\"chosen\":\"NEEDS\","
                + "\"playedAt\":\"2026-10-02T03:00:00Z\"},"
                + "{\"gameId\":\"NEEDS_OR_WANTS_CH01\",\"itemId\":\"ITEM_UPGRADE\",\"chosen\":\"NEEDS\"}],"
                + "\"finishedAt\":\"2026-10-02T03:00:00Z\"}";
    }

    /** Lô không có lựa chọn / mini-game — hợp lệ (cả hai mảng có thể rỗng). */
    private static String emptyBatch(int chapter, int attempt, String version) {
        return "{\"chapter\":" + chapter + ",\"attemptIndex\":" + attempt
                + ",\"releaseVersion\":\"" + version + "\",\"choices\":[],\"miniGames\":[]}";
    }

    /** Một trẻ mới: chủ nhóm FAMILY + slot + login → token SLOT THẬT, độc lập với các ca khác. */
    private String newChild(String prefix) throws Exception {
        String parent = parentToken(prefix);
        String groupId = openGroup(parent, "Nha " + prefix, "FAMILY");
        String slotBody = mvc.perform(slotRequest(parent, groupId, "Be " + prefix, "123456"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return childLogin(slotBody, "123456");
    }

    private RequestPostProcessor admin() {
        Account a = new Account();
        a.setEmail("play-admin-" + System.nanoTime() + "@test.local");
        a.setDisplayName("Admin Play");
        a.setCreatedAt(Instant.now());
        a = accountRepo.save(a);
        AccountRole role = new AccountRole();  // PR B: service tra vai trong DB, không tin JWT
        role.setAccount(a);
        role.setRole(Role.ADMIN);
        role.setGrantedAt(Instant.now());
        accountRoleRepo.save(role);
        String id = a.getId().toString();
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("SCOPE_ADMIN"));
    }

    private String parentToken(String prefix) throws Exception {
        String jwt = registerAndVerify(prefix + "-" + System.nanoTime() + "@test.local");
        grant(jwt, PlanKind.PARENT);
        return jwt;
    }

    private String openGroup(String jwt, String name, String context) throws Exception {
        String body = mvc.perform(openGroupRequest(jwt, name, context))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return extractId(body);
    }

    private String childLogin(String slotBody, String pin) throws Exception {
        String code = extractSlotCode(slotBody);
        String body = mvc.perform(post("/api/slots/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"pin\":\"" + pin + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return extractJwt(body);
    }

    private void grant(String jwt, PlanKind kind) {
        Account a = accountRepo.findById(UUID.fromString(subject(jwt))).orElseThrow();
        Entitlement e = new Entitlement();
        e.setAccount(a);
        e.setKind(kind);
        e.setStartsOn(LocalDate.now(FIXED_CLOCK).minusDays(1));
        e.setExpiresOn(LocalDate.now(FIXED_CLOCK).plusMonths(3));
        e.setSource(EntitlementSource.ADMIN);
        e.setGrantedBy(a);
        e.setCreatedAt(Instant.now(FIXED_CLOCK));
        entitlementRepo.save(e);
    }

    private String registerAndVerify(String email) throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        String otp = tokenFromMail();
        return extractJwt(mvc.perform(post("/api/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"otp\":\"" + otp + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ gọi API
    private static MockHttpServletRequestBuilder batchRequest(String childToken, String body) {
        return post("/api/play/chapter")
                .header("Authorization", "Bearer " + childToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static MockHttpServletRequestBuilder openGroupRequest(String jwt, String name, String context) {
        return post("/api/groups")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"context\":\"" + context + "\"}");
    }

    private static MockHttpServletRequestBuilder slotRequest(String jwt, String groupId,
                                                             String displayName, String pin) {
        return post("/api/slots")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":\"" + groupId + "\",\"displayName\":\"" + displayName
                        + "\",\"pin\":\"" + pin + "\",\"badge\":\"nhi dong\"}");
    }

    // ------------------------------------------------------------------ đọc response
    private static String extractId(String body) {
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]+)\"").matcher(body);
        assertThat(m.find()).as("response nhom phai co id: " + body).isTrue();
        return m.group(1);
    }

    private static String extractSlotCode(String body) {
        Matcher m = Pattern.compile("\"code\":\"([A-Z0-9]+)\"").matcher(body);
        assertThat(m.find()).as("response slot phai co code chu: " + body).isTrue();
        return m.group(1);
    }

    private static String subject(String jwt) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[1]),
                StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"sub\":\"([^\"]+)\"").matcher(payload);
        assertThat(m.find()).as("token phai co sub: " + payload).isTrue();
        return m.group(1);
    }

    private static String extractJwt(String body) {
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).as("phai co accessToken").isTrue();
        return m.group(1);
    }

    private static String registerJson(String email) {
        return "{\"email\":\"" + email + "\",\"phone\":\"0900000000\","
                + "\"password\":\"matkhau123\",\"displayName\":\"Nguyen Test\"}";
    }

    private String tokenFromMail() {
        assertThat(mailCatcher.sent).isNotEmpty();
        OutgoingMail last = mailCatcher.sent.get(mailCatcher.sent.size() - 1);
        Matcher m = OTP_IN_MAIL.matcher(last.body());
        assertThat(m.find()).as("mail phai co ma OTP 6 so").isTrue();
        return m.group(1);
    }

    @TestConfiguration
    static class TestConfig {

        /** Đồng hồ đóng băng để gói trong test luôn còn hạn — y hệt AuthFlowTest. */
        @Bean
        @Primary
        Clock testClock() {
            return FIXED_CLOCK;
        }

        /** Bắt lá mail đăng ký bằng listener thật của AuthFlowTest, không mock MailService. */
        @Bean
        AuthFlowTest.MailCatcher thu() {
            return new AuthFlowTest.MailCatcher();
        }
    }

}
