package com.doan.game;

import com.doan.game.configuration.ClockConfig;
import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.entity.Entitlement;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.entity.Question;
import com.doan.game.entity.Quiz;
import com.doan.game.entity.QuizAnswer;
import com.doan.game.entity.QuizAttempt;
import com.doan.game.entity.QuizResult;
import com.doan.game.entity.RunState;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.PlanKind;
import com.doan.game.enums.Role;
import com.doan.game.enums.SlotStatus;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.ChapterDraftRepository;
import com.doan.game.repository.ChapterRepository;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.GameReleaseRepository;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.QuestionRepository;
import com.doan.game.repository.QuizAnswerRepository;
import com.doan.game.repository.QuizAttemptRepository;
import com.doan.game.repository.QuizRepository;
import com.doan.game.repository.QuizResultRepository;
import com.doan.game.repository.ReleaseChapterRepository;
import com.doan.game.repository.RunStateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quiz v3 qua HTTP thật (MockMvc + H2), đúng 10 nhóm ca của build/thiet-ke-be-quiz-v3-2026-11-16.md mục 6.
 *
 * Người dùng dựng thẳng bằng repo + JWT giả của spring-security-test (TYP_ACCOUNT / TYP_SLOT như AdminPlanFlowTest):
 * service vẫn tự kiểm gói trong DB nên không cần đi đường đăng ký / OTP. Câu kho chung tạo bằng repo với externalId có
 * nanoTime (H2 dùng chung giữa các lớp test, không đếm tổng bảng). Đồng hồ chỉnh được (mặc định 10:00 02/10/2026 giờ VN),
 * mỗi ca đặt lại; ngẫu nhiên là Random(42) — tính tất định của bản xáo kiểm riêng ở QuizPaperTest.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class QuizV3FlowTest {

    private static final Instant START = Instant.parse("2026-10-02T03:00:00Z");
    private static final MutableClock CLOCK = new MutableClock();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DUE_FUTURE = "2026-10-03T03:00:00Z";

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountRoleRepository accountRoleRepo;
    @Autowired EntitlementRepository entitlementRepo;
    @Autowired LearnerGroupRepository groupRepo;
    @Autowired LearnerSlotRepository slotRepo;
    @Autowired QuestionRepository questionRepo;
    @Autowired QuizRepository quizRepo;
    @Autowired QuizAttemptRepository attemptRepo;
    @Autowired QuizResultRepository quizResultRepo;
    @Autowired QuizAnswerRepository quizAnswerRepo;
    @Autowired RunStateRepository runStateRepo;
    @Autowired ReleaseChapterRepository releaseChapterRepo;
    @Autowired GameReleaseRepository releaseRepo;
    @Autowired ChapterDraftRepository draftRepo;
    @Autowired ChapterRepository chapterRepo;

    @BeforeEach
    void resetClock() {
        CLOCK.set(START);
    }

    // ------------------------------------------------------------------ 1. câu của tôi
    /** Lô 3 câu (1 trùng) → 2 mới + 1 dùng lại; lỗi theo index; người khác không thấy; sửa câu đã phát 8009; xoá đã dùng → ẩn, chưa dùng → mất; copy kho → copiedFromId; copy hậu trắc → 8007. */
    @Test
    void myQuestionsBatchEditDeleteCopy() throws Exception {
        Account teacher = adult("v3mine", PlanKind.TEACHER);
        Account other = adult("v3khac", PlanKind.TEACHER);

        JsonNode saved = result(mvc.perform(post("/api/questions/mine").with(as(teacher))
                .contentType(MediaType.APPLICATION_JSON)
                .content("[" + newQ("Tiet kiem la gi?", 0, "Giu tien", "Tieu het") + ","
                        + newQ("Lai suat la gi?", 1, "Phi", "Tien loi") + ","
                        + newQ("  tiet   KIEM la gi? ", 0, "giu tien", " TIEU het") + "]"))
                .andExpect(status().isOk()));
        assertThat(saved).hasSize(3);
        assertThat(saved.get(0).path("reused").asBoolean()).isFalse();
        assertThat(saved.get(1).path("reused").asBoolean()).isFalse();
        assertThat(saved.get(2).path("reused").asBoolean()).isTrue();
        assertThat(saved.get(2).path("id").asText()).isEqualTo(saved.get(0).path("id").asText());
        String q1 = saved.get(0).path("id").asText();
        String q2 = saved.get(1).path("id").asText();

        // Lỗi trả theo index, không lưu câu nào của lô.
        mvc.perform(post("/api/questions/mine").with(as(teacher)).contentType(MediaType.APPLICATION_JSON)
                        .content("[" + newQ("Cau dung", 0, "A", "B") + "," + newQ("Mot lua chon", 0, "A") + ","
                                + newQ(" ", 5, "A", "B") + "]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.result[0].index").value(1))
                .andExpect(jsonPath("$.result[0].field").value("options"))
                .andExpect(jsonPath("$.result[1].index").value(2))
                .andExpect(jsonPath("$.result[1].field").value("stem"))
                .andExpect(jsonPath("$.result[2].field").value("correctIndex"));
        assertThat(questionRepo.findByOwner_IdAndRetiredAtIsNullOrderByCreatedAtDesc(teacher.getId())).hasSize(2);

        // Người khác không thấy; câu của giáo viên không lọt vào kho chung.
        assertThat(result(mvc.perform(get("/api/questions/mine").with(as(other))))).isEmpty();
        assertThat(result(mvc.perform(get("/api/questions/mine").with(as(teacher))))).hasSize(2);
        assertThat(ids(result(mvc.perform(get("/api/questions").with(as(teacher)))))).doesNotContain(q1, q2);
        mvc.perform(put("/api/questions/mine/" + q1).with(as(other)).contentType(MediaType.APPLICATION_JSON)
                        .content(newQ("Sua trom", 0, "A", "B")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(3004));

        // q1 vào đề đã phát → sửa 8009, xoá chỉ ẩn; q2 chưa vào đề nào → sửa được, xoá mất hẳn.
        LearnerGroup room = classGroup(teacher);
        String quizId = createQuiz(teacher, room, List.of(q1), "PRACTICE", null, null);
        publish(teacher, quizId);
        JsonNode mine = result(mvc.perform(get("/api/questions/mine").with(as(teacher))));
        for (JsonNode n : mine) {
            assertThat(n.path("usedInPublished").asInt()).isEqualTo(n.path("id").asText().equals(q1) ? 1 : 0);
        }
        mvc.perform(put("/api/questions/mine/" + q1).with(as(teacher)).contentType(MediaType.APPLICATION_JSON)
                        .content(newQ("Doi cau da phat", 0, "A", "B")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(8009));
        mvc.perform(put("/api/questions/mine/" + q2).with(as(teacher)).contentType(MediaType.APPLICATION_JSON)
                        .content(newQ("Lai suat la gi (sua)?", 1, "Phi", "Tien loi", "Thue")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.options.length()").value(3));
        mvc.perform(delete("/api/questions/mine/" + q1).with(as(teacher))).andExpect(status().isOk());
        mvc.perform(delete("/api/questions/mine/" + q2).with(as(teacher))).andExpect(status().isOk());
        assertThat(questionRepo.findById(UUID.fromString(q1)).orElseThrow().getRetiredAt()).isNotNull();
        assertThat(questionRepo.findById(UUID.fromString(q2))).isEmpty();
        assertThat(result(mvc.perform(get("/api/questions/mine").with(as(teacher))))).isEmpty();

        // Copy câu kho chung → bản sao của tôi, câu kho không đổi; câu hậu trắc / câu người khác → 8007.
        Question pool = poolQuestion(4, 2, true);
        pool.setReference("{\"title\":\"FDIC Money Smart\",\"url\":\"https://example.org\",\"stemOriginal\":\"Orig\"}");
        questionRepo.save(pool);
        JsonNode copy = result(mvc.perform(post("/api/questions/" + pool.getId() + "/copy").with(as(teacher)))
                .andExpect(status().isOk()));
        assertThat(copy.path("copiedFromId").asText()).isEqualTo(pool.getId().toString());
        assertThat(copy.path("id").asText()).isNotEqualTo(pool.getId().toString());
        assertThat(copy.path("reference").path("title").asText()).isEqualTo("FDIC Money Smart");
        Question poolAfter = questionRepo.findById(pool.getId()).orElseThrow();
        assertThat(poolAfter.getOwner()).isNull();
        assertThat(poolAfter.getStem()).isEqualTo(pool.getStem());
        Question chapterQ = poolQuestion(2, 0, false);
        chapterQ.setChapter(1);
        questionRepo.save(chapterQ);
        mvc.perform(post("/api/questions/" + chapterQ.getId() + "/copy").with(as(teacher)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(8007));
        mvc.perform(post("/api/questions/" + copy.path("id").asText() + "/copy").with(as(other)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(8007));

        // Thiếu gói TEACHER → 3005.
        Account noPlan = adult("v3khonggoi", PlanKind.PARENT);
        mvc.perform(get("/api/questions/mine").with(as(noPlan)))
                .andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.code").value(3005));
    }

    /** Nhập kho mang keepOptionOrder + reference; GET /api/questions trả lại hai trường mới (không đổi hình cũ). */
    @Test
    void importCarriesReferenceAndKeepOptionOrder() throws Exception {
        String ext = "V3IMP" + System.nanoTime();
        mvc.perform(post("/api/admin/questions/import").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"externalId\":\"" + ext + "\",\"pool\":\"FDIC\",\"stem\":\"Cau nhap\","
                                + "\"options\":[\"A\",\"B\",\"Ca hai\"],\"correctIndex\":2,\"standard\":\"CEE-I\","
                                + "\"keepOptionOrder\":true,\"reference\":{\"title\":\"Money Smart\",\"page\":\"p.5\","
                                + "\"stemOriginal\":\"Import\",\"optionsOriginal\":[\"A\",\"B\",\"Both\"],"
                                + "\"standardFromSource\":true}}]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.inserted").value(1));
        Account teacher = adult("v3nhap", PlanKind.TEACHER);
        JsonNode row = null;
        for (JsonNode n : result(mvc.perform(get("/api/questions").with(as(teacher))))) {
            if (ext.equals(n.path("externalId").asText())) {
                row = n;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("keepOptionOrder").asBoolean()).isTrue();
        assertThat(row.path("reference").path("page").asText()).isEqualTo("p.5");
        assertThat(row.path("reference").path("optionsOriginal").get(2).asText()).isEqualTo("Both");
        assertThat(row.path("correctIndex").asInt()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 2. tạo đề có newQuestions
    /** Tạo đề với newQuestions + id kho: một giao dịch; một câu mới sai → không lưu câu nào, không có đề; thiếu mode → 1001. */
    @Test
    void createQuizWithNewQuestionsIsAllOrNothing() throws Exception {
        Account teacher = adult("v3import", PlanKind.TEACHER);
        LearnerGroup room = classGroup(teacher);
        Question pool = poolQuestion(4, 1, false);

        JsonNode quiz = result(mvc.perform(post("/api/quizzes").with(as(teacher))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":\"" + room.getId() + "\",\"title\":\"De import\",\"mode\":\"TEST\","
                        + "\"questionIds\":[\"" + pool.getId() + "\"],\"dueAt\":\"" + DUE_FUTURE + "\","
                        + "\"newQuestions\":[" + newQ("Cau moi 1", 0, "A", "B") + ","
                        + newQ("Cau moi 2", 1, "A", "B", "C") + "]}"))
                .andExpect(status().isOk()));
        assertThat(quiz.path("questionCount").asInt()).isEqualTo(3);
        assertThat(quiz.path("mode").asText()).isEqualTo("TEST");
        assertThat(quiz.path("durationSeconds").asInt()).isEqualTo(180);   // 1 phút × 3 câu mỗi em
        assertThat(quiz.path("drawCount").isNull()).isTrue();
        assertThat(questionRepo.findByOwner_IdAndRetiredAtIsNullOrderByCreatedAtDesc(teacher.getId())).hasSize(2);
        int quizzesBefore = quizRepo.findByGroup_Id(room.getId()).size();

        mvc.perform(post("/api/quizzes").with(as(teacher)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + room.getId() + "\",\"title\":\"De loi\",\"mode\":\"PRACTICE\","
                                + "\"questionIds\":[],\"newQuestions\":[" + newQ("Cau moi 3", 0, "A", "B") + ","
                                + newQ("Cau hong", 9, "A", "B") + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.result[0].index").value(1));
        assertThat(questionRepo.findByOwner_IdAndRetiredAtIsNullOrderByCreatedAtDesc(teacher.getId())).hasSize(2);
        assertThat(quizRepo.findByGroup_Id(room.getId())).hasSize(quizzesBefore);

        // Chế độ bắt buộc; drawCount quá số câu → 1001.
        mvc.perform(post("/api/quizzes").with(as(teacher)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + room.getId() + "\",\"title\":\"Thieu che do\","
                                + "\"questionIds\":[\"" + pool.getId() + "\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
        mvc.perform(post("/api/quizzes").with(as(teacher)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + room.getId() + "\",\"title\":\"Rut qua\",\"mode\":\"TEST\","
                                + "\"drawCount\":2,\"questionIds\":[\"" + pool.getId() + "\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
        // Câu của giáo viên khác không vào đề được (8007).
        Account other = adult("v3import2", PlanKind.TEACHER);
        UUID foreign = questionRepo.findByOwner_IdAndRetiredAtIsNullOrderByCreatedAtDesc(teacher.getId()).get(0).getId();
        mvc.perform(post("/api/quizzes").with(as(other)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + classGroup(other).getId() + "\",\"title\":\"Cau nguoi khac\","
                                + "\"mode\":\"PRACTICE\",\"questionIds\":[\"" + foreign + "\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(8007));
    }

    // ------------------------------------------------------------------ 3 + 4. xáo, rút, chấm theo chỉ số gốc
    /** TEST rút 3/4: paper hoán vị hợp lệ, rút đúng N, chấm qua vị trí đã xáo đúng chỉ số gốc, QuizAnswer lưu chỉ số gốc, không response nào có correctIndex; kết quả giáo viên có cột lượt làm + paper. */
    @Test
    void testModeShufflesDrawsAndGradesByOriginalIndex() throws Exception {
        Account teacher = adult("v3xao", PlanKind.TEACHER);
        LearnerGroup room = classGroup(teacher);
        List<Question> pool = List.of(poolQuestion(4, 0, false), poolQuestion(4, 1, false),
                poolQuestion(4, 2, false), poolQuestion(4, 3, true));
        List<UUID> poolIds = pool.stream().map(Question::getId).toList();
        String quizId = createQuiz(teacher, room, poolIds.stream().map(UUID::toString).toList(), "TEST", 3, null);
        publish(teacher, quizId);
        LearnerSlot a = slot(room, "HS A");
        LearnerSlot b = slot(room, "HS B");
        slot(room, "HS C");   // không mở đề → NOT_OPENED

        StringBuilder learnerBodies = new StringBuilder();
        for (LearnerSlot kid : List.of(a, b)) {
            String body = mvc.perform(post("/api/learner/quizzes/" + quizId + "/attempts").with(as(kid)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.attemptNo").value(1))
                    .andExpect(jsonPath("$.result.mode").value("TEST"))
                    .andExpect(jsonPath("$.result.questions.length()").value(3))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            learnerBodies.append(body);
            QuizAttempt attempt = onlyAttempt(quizId, kid);
            JsonNode paper = MAPPER.readTree(attempt.getPaper());
            assertThat(paper).hasSize(3);
            List<String> drawn = new ArrayList<>();
            for (JsonNode entry : paper) {
                drawn.add(entry.path("q").asText());
                List<Integer> o = new ArrayList<>();
                entry.path("o").forEach(x -> o.add(x.asInt()));
                assertThat(o).containsExactlyInAnyOrder(0, 1, 2, 3);
                if (entry.path("q").asText().equals(pool.get(3).getId().toString())) {
                    assertThat(o).containsExactly(0, 1, 2, 3);   // keepOptionOrder: không xáo đáp án
                }
            }
            assertThat(new HashSet<>(drawn)).hasSize(3);
            assertThat(poolIds.stream().map(UUID::toString).toList()).containsAll(drawn);
            assertThat(attempt.getDeadlineAt()).isEqualTo(START.plusSeconds(180));   // 1 phút × 3 câu
            // Đáp án em thấy đúng thứ tự paper.
            JsonNode shown = MAPPER.readTree(body).path("result").path("questions").get(0).path("options");
            Question first = questionRepo.findById(UUID.fromString(paper.get(0).path("q").asText())).orElseThrow();
            List<String> original = MAPPER.readValue(first.getOptions(), MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
            for (int i = 0; i < 4; i++) {
                assertThat(shown.get(i).asText()).isEqualTo(original.get(paper.get(0).path("o").get(i).asInt()));
            }
        }

        // A: vị trí 0 đúng, vị trí 1 sai, vị trí 2 bỏ trống → 1 điểm; rời trang 2 lần.
        QuizAttempt attemptA = onlyAttempt(quizId, a);
        JsonNode paperA = MAPPER.readTree(attemptA.getPaper());
        int correct0 = correctPos(paperA.get(0));
        int wrong1 = (correctPos(paperA.get(1)) + 1) % 4;
        saveAnswer(a, attemptA.getId(), 0, correct0, 1).andExpect(status().isOk());
        saveAnswer(a, attemptA.getId(), 1, wrong1, 2).andExpect(status().isOk());
        saveAnswer(a, attemptA.getId(), 1, 9, 2)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(8006));
        CLOCK.set(START.plusSeconds(75));
        String submitA = mvc.perform(post("/api/learner/attempts/" + attemptA.getId() + "/submit").with(as(a))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"leaveCount\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.answered").value(2))
                .andExpect(jsonPath("$.result.total").value(3))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        learnerBodies.append(submitA);
        assertThat(learnerBodies.toString()).doesNotContain("correctIndex").doesNotContain("\"q\"");

        QuizResult resultA = quizResultRepo.findByQuiz_IdAndSlot_Id(UUID.fromString(quizId), a.getId()).orElseThrow();
        assertThat(resultA.getScore()).isEqualTo(1);
        Map<UUID, Integer> chosenByQuestion = new java.util.HashMap<>();
        for (QuizAnswer answer : quizAnswerRepo.findByQuizResult_Id(resultA.getId())) {
            chosenByQuestion.put(answer.getQuestion().getId(), answer.getChosenIndex());
        }
        assertThat(chosenByQuestion).hasSize(3);
        UUID q0 = UUID.fromString(paperA.get(0).path("q").asText());
        UUID q1 = UUID.fromString(paperA.get(1).path("q").asText());
        UUID q2 = UUID.fromString(paperA.get(2).path("q").asText());
        assertThat(chosenByQuestion.get(q0)).isEqualTo(questionRepo.findById(q0).orElseThrow().getCorrectIndex());
        assertThat(chosenByQuestion.get(q1)).isEqualTo(paperA.get(1).path("o").get(wrong1).asInt());
        assertThat(chosenByQuestion.get(q2)).isNull();

        // Giáo viên: A SUBMITTED (làm trong 75 giây, rời trang 2), B IN_PROGRESS, C NOT_OPENED; paper theo thứ tự em thấy.
        JsonNode results = result(mvc.perform(get("/api/quizzes/" + quizId + "/results").with(as(teacher)))
                .andExpect(status().isOk()));
        assertThat(results.path("quiz").path("drawCount").asInt()).isEqualTo(3);
        Map<String, JsonNode> rows = new java.util.HashMap<>();
        results.path("rows").forEach(r -> rows.put(r.path("slotId").asText(), r));
        JsonNode rowA = rows.get(a.getId().toString());
        assertThat(rowA.path("state").asText()).isEqualTo("SUBMITTED");
        assertThat(rowA.path("totalQuestions").asInt()).isEqualTo(3);
        assertThat(rowA.path("attemptCode").asText()).isEqualTo(attemptA.getId().toString().substring(0, 6));
        assertThat(rowA.path("durationSeconds").asInt()).isEqualTo(75);
        assertThat(rowA.path("leaveCount").asInt()).isEqualTo(2);
        assertThat(rowA.path("autoSubmitted").asBoolean()).isFalse();
        assertThat(rows.get(b.getId().toString()).path("state").asText()).isEqualTo("IN_PROGRESS");
        assertThat(rows.values().stream().filter(r -> "NOT_OPENED".equals(r.path("state").asText())).count())
                .isEqualTo(1);
        JsonNode slotResult = result(mvc.perform(get("/api/quizzes/" + quizId + "/results/" + a.getId())
                .with(as(teacher))).andExpect(status().isOk()));
        JsonNode paperRows = slotResult.path("paper");
        assertThat(paperRows).hasSize(3);
        assertThat(paperRows.get(0).path("questionId").asText()).isEqualTo(q0.toString());
        assertThat(paperRows.get(0).path("chosen").asInt()).isEqualTo(correct0);
        assertThat(paperRows.get(0).path("correctPos").asInt()).isEqualTo(correct0);
        assertThat(paperRows.get(0).path("correct").asBoolean()).isTrue();
        assertThat(paperRows.get(1).path("correct").asBoolean()).isFalse();
        assertThat(paperRows.get(2).path("chosen").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ 5. đồng hồ, chốt lười
    /** Quá deadline + 30 giây: lưu bị 8011 và lượt đã tự nộp lúc deadline; giáo viên mở kết quả chốt lượt quá hạn của em khác. */
    @Test
    void expiredAttemptIsAutoSubmitted() throws Exception {
        Account teacher = adult("v3gio", PlanKind.TEACHER);
        LearnerGroup room = classGroup(teacher);
        Question q = poolQuestion(2, 0, true);
        String quizId = createQuiz(teacher, room, List.of(q.getId().toString()), "TEST", null, 1);
        publish(teacher, quizId);
        LearnerSlot a = slot(room, "HS A");
        LearnerSlot b = slot(room, "HS B");
        UUID attemptA = UUID.fromString(result(mvc.perform(post("/api/learner/quizzes/" + quizId + "/attempts")
                .with(as(a)))).path("attemptId").asText());
        UUID attemptB = UUID.fromString(result(mvc.perform(post("/api/learner/quizzes/" + quizId + "/attempts")
                .with(as(b)))).path("attemptId").asText());
        saveAnswer(a, attemptA, 0, 0, 0).andExpect(status().isOk());   // keepOptionOrder: vị trí 0 = đáp án đúng
        saveAnswer(b, attemptB, 0, 0, 0).andExpect(status().isOk());

        // Trong thời gian trễ (deadline + 20 giây) vẫn lưu được.
        CLOCK.set(START.plusSeconds(80));
        saveAnswer(a, attemptA, 0, 0, 3).andExpect(status().isOk());
        // Quá deadline + 30 giây: chốt rồi 8011.
        CLOCK.set(START.plusSeconds(91));
        saveAnswer(a, attemptA, 0, 1, 3)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(8011));
        QuizAttempt closedA = attemptRepo.findById(attemptA).orElseThrow();
        assertThat(closedA.isAutoSubmitted()).isTrue();
        assertThat(closedA.getSubmittedAt()).isEqualTo(START.plusSeconds(60));
        assertThat(closedA.getScore()).isEqualTo(1);
        assertThat(closedA.getLeaveCount()).isEqualTo(3);

        // B chưa ai đụng tới: giáo viên mở kết quả là chốt.
        assertThat(attemptRepo.findById(attemptB).orElseThrow().getSubmittedAt()).isNull();
        JsonNode results = result(mvc.perform(get("/api/quizzes/" + quizId + "/results").with(as(teacher))));
        for (JsonNode row : results.path("rows")) {
            assertThat(row.path("state").asText()).isEqualTo("SUBMITTED");
            assertThat(row.path("autoSubmitted").asBoolean()).isTrue();
            assertThat(row.path("score").asInt()).isEqualTo(1);
        }
        assertThat(results.path("summary").path("submitted").asInt()).isEqualTo(2);
        // Đề Kiểm tra mở lại sau khi đã tự nộp → 8012.
        mvc.perform(post("/api/learner/quizzes/" + quizId + "/attempts").with(as(b)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(8012));
    }

    // ------------------------------------------------------------------ 6 + 7. một lần / làm lại / giấu tới hạn
    /** TEST lần hai → 8012; nộp hai lần → cùng kết quả; trước dueAt không có điểm / đúng sai, sau dueAt có; PRACTICE làm lại tạo lượt 2, QuizResult vẫn là lần đầu. */
    @Test
    void singleTestAttemptHiddenUntilDueAndPracticeRepeats() throws Exception {
        Account teacher = adult("v3lan", PlanKind.TEACHER);
        LearnerGroup room = classGroup(teacher);
        Question q1 = poolQuestion(2, 0, true);
        Question q2 = poolQuestion(2, 1, true);
        List<String> ids = List.of(q1.getId().toString(), q2.getId().toString());
        LearnerSlot a = slot(room, "HS A");

        String testId = createQuiz(teacher, room, ids, "TEST", null, null);
        publish(teacher, testId);
        UUID attempt = UUID.fromString(result(mvc.perform(post("/api/learner/quizzes/" + testId + "/attempts")
                .with(as(a)))).path("attemptId").asText());
        // Mở lại khi còn lượt mở → cùng lượt.
        assertThat(result(mvc.perform(post("/api/learner/quizzes/" + testId + "/attempts").with(as(a))))
                .path("attemptId").asText()).isEqualTo(attempt.toString());
        // Vị trí 0 đúng, vị trí 1 bỏ trống (thứ tự câu đã xáo — tìm vị trí đáp án đúng từ paper).
        saveAnswer(a, attempt, 0, correctPos(MAPPER.readTree(attemptRepo.findById(attempt).orElseThrow().getPaper())
                .get(0)), null).andExpect(status().isOk());
        JsonNode first = result(mvc.perform(post("/api/learner/attempts/" + attempt + "/submit").with(as(a)))
                .andExpect(status().isOk()));
        JsonNode again = result(mvc.perform(post("/api/learner/attempts/" + attempt + "/submit").with(as(a)))
                .andExpect(status().isOk()));
        assertThat(again.path("submittedAt").asText()).isEqualTo(first.path("submittedAt").asText());
        assertThat(first.path("score").isNull()).isTrue();     // Kiểm tra, trước hạn nộp: chỉ "đã nộp"
        assertThat(first.path("review").isNull()).isTrue();
        assertThat(first.path("answered").asInt()).isEqualTo(1);
        mvc.perform(post("/api/learner/quizzes/" + testId + "/attempts").with(as(a)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(8012));
        JsonNode list = result(mvc.perform(get("/api/learner/quizzes").with(as(a))));
        JsonNode completed = list.path("completed").get(0);
        assertThat(completed.path("id").asText()).isEqualTo(testId);
        assertThat(completed.path("score").isNull()).isTrue();

        // Sau hạn nộp: có điểm và đúng / sai từng vị trí (vẫn không có đáp án đúng).
        CLOCK.set(Instant.parse(DUE_FUTURE).plusSeconds(1));
        String review = mvc.perform(get("/api/learner/attempts/" + attempt).with(as(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.score").value(1))
                .andExpect(jsonPath("$.result.review.length()").value(2))
                .andExpect(jsonPath("$.result.review[0].correct").value(true))
                .andExpect(jsonPath("$.result.review[1].chosen").isEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(review).doesNotContain("correctIndex");
        CLOCK.set(START);

        // Luyện tập: lần 1 được 0, lần 2 đúng hết — kết quả chính thức vẫn là lần đầu.
        String practiceId = createQuiz(teacher, room, ids, "PRACTICE", null, null);
        publish(teacher, practiceId);
        JsonNode p1 = result(mvc.perform(post("/api/learner/quizzes/" + practiceId + "/attempts").with(as(a))));
        assertThat(p1.path("deadlineAt").asText()).isEqualTo(DUE_FUTURE);
        UUID p1Id = UUID.fromString(p1.path("attemptId").asText());
        JsonNode p1Result = result(mvc.perform(post("/api/learner/attempts/" + p1Id + "/submit").with(as(a))));
        assertThat(p1Result.path("score").asInt()).isEqualTo(0);              // Luyện tập: thấy ngay
        assertThat(p1Result.path("review")).hasSize(2);
        JsonNode p2 = result(mvc.perform(post("/api/learner/quizzes/" + practiceId + "/attempts").with(as(a))));
        assertThat(p2.path("attemptNo").asInt()).isEqualTo(2);
        UUID p2Id = UUID.fromString(p2.path("attemptId").asText());
        QuizAttempt second = attemptRepo.findById(p2Id).orElseThrow();
        JsonNode paper2 = MAPPER.readTree(second.getPaper());
        for (int pos = 0; pos < 2; pos++) {
            saveAnswer(a, p2Id, pos, correctPos(paper2.get(pos)), null).andExpect(status().isOk());
        }
        assertThat(result(mvc.perform(post("/api/learner/attempts/" + p2Id + "/submit").with(as(a))))
                .path("score").asInt()).isEqualTo(2);
        assertThat(quizResultRepo.findByQuiz_IdAndSlot_Id(UUID.fromString(practiceId), a.getId()).orElseThrow()
                .getScore()).isEqualTo(0);
        mvc.perform(get("/api/quizzes/" + practiceId + "/results").with(as(teacher)))
                .andExpect(jsonPath("$.result.rows[0].score").value(0))
                .andExpect(jsonPath("$.result.rows[0].attemptCode").value(p1Id.toString().substring(0, 6)));
        // Lượt của em khác → 8013.
        LearnerSlot b = slot(room, "HS B");
        mvc.perform(get("/api/learner/attempts/" + p2Id).with(as(b)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(8013));
    }

    // ------------------------------------------------------------------ 8. đường nộp cũ
    /** Đề có mode → đường nộp cũ 8010; đề cũ mode NULL vẫn nộp đường cũ (và không mở lượt làm được). */
    @Test
    void oldSubmitPathBlockedForModeQuizzes() throws Exception {
        Account teacher = adult("v3cu", PlanKind.TEACHER);
        LearnerGroup room = classGroup(teacher);
        Question q = poolQuestion(2, 0, false);
        LearnerSlot a = slot(room, "HS A");
        String body = "{\"answers\":[{\"questionId\":\"" + q.getId() + "\",\"chosenIndex\":0}]}";

        String modeQuiz = createQuiz(teacher, room, List.of(q.getId().toString()), "PRACTICE", null, null);
        publish(teacher, modeQuiz);
        mvc.perform(post("/api/learner/quizzes/" + modeQuiz + "/submit").with(as(a))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(8010));
        mvc.perform(get("/api/learner/quizzes").with(as(a)))
                .andExpect(jsonPath("$.result.pending[0].mode").value("PRACTICE"));

        String legacy = createQuiz(teacher, room, List.of(q.getId().toString()), "PRACTICE", null, null);
        publish(teacher, legacy);
        Quiz old = quizRepo.findById(UUID.fromString(legacy)).orElseThrow();
        old.setMode(null);   // như 5 đề production trước v3
        quizRepo.save(old);
        mvc.perform(post("/api/learner/quizzes/" + legacy + "/submit").with(as(a))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.score").value(1));
        mvc.perform(post("/api/learner/quizzes/" + legacy + "/attempts").with(as(a)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
    }

    // ------------------------------------------------------------------ 9. hậu trắc gia đình
    /** Phụ huynh gói PARENT bật → bé xong chương → thấy hậu trắc, làm qua lượt làm → báo cáo có khối quizzes; thiếu gói → 3005; giáo viên bật FAMILY người khác → 3004; nhóm đóng → 8008. */
    @Test
    void familyPostTest() throws Exception {
        ensureRelease();
        Account parent = adult("v3phuhuynh", PlanKind.PARENT);
        LearnerGroup family = group(parent, LearningContext.FAMILY);
        LearnerSlot kid = slot(family, "Be An");
        Account teacher = adult("v3gvkhac", PlanKind.TEACHER);

        mvc.perform(postTest(teacher, family, true))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(3004));
        mvc.perform(postTest(parent, family, true)).andExpect(status().isOk());

        Question c8a = poolQuestion(3, 0, false);
        c8a.setChapter(8);
        questionRepo.save(c8a);
        int chapterEight = questionRepo.findByChapterAndRetiredAtIsNullOrderByExternalIdAsc(8).size();
        assertThat(result(mvc.perform(get("/api/learner/quizzes").with(as(kid)))).path("pending")).isEmpty();
        finishChapter(kid, 8);

        JsonNode pending = result(mvc.perform(get("/api/learner/quizzes").with(as(kid)))).path("pending");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).path("kind").asText()).isEqualTo("POST_TEST");
        assertThat(pending.get(0).path("chapter").asInt()).isEqualTo(8);
        String postTestId = pending.get(0).path("id").asText();
        JsonNode opened = result(mvc.perform(post("/api/learner/quizzes/" + postTestId + "/attempts").with(as(kid)))
                .andExpect(status().isOk()));
        assertThat(opened.path("kind").asText()).isEqualTo("POST_TEST");
        assertThat(opened.path("deadlineAt").isNull()).isTrue();
        assertThat(opened.path("questions")).hasSize(chapterEight);
        UUID attemptId = UUID.fromString(opened.path("attemptId").asText());
        JsonNode submitted = result(mvc.perform(post("/api/learner/attempts/" + attemptId + "/submit").with(as(kid))));
        assertThat(submitted.path("review")).hasSize(chapterEight);   // hậu trắc: đúng / sai ngay khi nộp
        mvc.perform(post("/api/learner/quizzes/" + postTestId + "/attempts").with(as(kid)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(8005));

        mvc.perform(get("/api/learner/dashboard").with(as(kid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.quizzes.completed.length()").value(1));
        mvc.perform(get("/api/slots/" + kid.getId() + "/report").with(as(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.quizzes.assigned").value(1))
                .andExpect(jsonPath("$.result.quizzes.submitted").value(1))
                .andExpect(jsonPath("$.result.quizzes.results[0].quizId").value(postTestId));
        // Phụ huynh xem từng câu của bé như giáo viên.
        mvc.perform(get("/api/quizzes/" + postTestId + "/results/" + kid.getId()).with(as(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.paper.length()").value(chapterEight));

        // Hết gói PARENT: bật → 3005, tắt → 200. Nhóm đã đóng → 8008.
        entitlementRepo.deleteAll(entitlementRepo.findAll().stream()
                .filter(e -> e.getAccount().getId().equals(parent.getId())).toList());
        mvc.perform(postTest(parent, family, false)).andExpect(status().isOk());
        mvc.perform(postTest(parent, family, true))
                .andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.code").value(3005));
        LearnerGroup closed = groupRepo.findById(family.getId()).orElseThrow();
        closed.setClosedAt(START);
        closed.setOpenContext(null);
        groupRepo.save(closed);
        mvc.perform(postTest(parent, family, false))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(8008));
    }

    // ------------------------------------------------------------------ 10. mở cùng lúc
    /** Hai lần mở cùng lúc (khoá dòng slot) chỉ ra một lượt. */
    @Test
    void concurrentOpenCreatesOneAttempt() throws Exception {
        Account teacher = adult("v3dongthoi", PlanKind.TEACHER);
        LearnerGroup room = classGroup(teacher);
        Question q = poolQuestion(2, 0, false);
        String quizId = createQuiz(teacher, room, List.of(q.getId().toString()), "TEST", null, null);
        publish(teacher, quizId);
        LearnerSlot a = slot(room, "HS A");

        Callable<String> open = () -> mvc.perform(post("/api/learner/quizzes/" + quizId + "/attempts").with(as(a)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> calls = pool.invokeAll(List.of(open, open));
            List<String> attemptIds = new ArrayList<>();
            for (Future<String> call : calls) {
                JsonNode body = MAPPER.readTree(call.get());
                assertThat(body.path("code").asInt()).as(body.toString()).isZero();
                attemptIds.add(body.path("result").path("attemptId").asText());
            }
            assertThat(attemptIds.get(0)).isEqualTo(attemptIds.get(1));
        } finally {
            pool.shutdown();
        }
        assertThat(attemptRepo.findByQuiz_IdAndSlot_IdOrderByAttemptNoAsc(UUID.fromString(quizId), a.getId()))
                .hasSize(1);
    }

    // ------------------------------------------------------------------ dựng dữ liệu
    private Account adult(String tag, PlanKind plan) {
        Account account = new Account();
        account.setEmail(tag + "-" + System.nanoTime() + "@test.local");
        account.setDisplayName("Test " + tag);
        account.setCreatedAt(START);
        account = accountRepo.save(account);
        Entitlement e = new Entitlement();
        e.setAccount(account);
        e.setKind(plan);
        e.setStartsOn(LocalDate.ofInstant(START, ClockConfig.VN).minusDays(1));
        e.setExpiresOn(LocalDate.ofInstant(START, ClockConfig.VN).plusMonths(3));
        e.setSource(EntitlementSource.ADMIN);
        e.setGrantedBy(account);
        e.setReason("test");
        e.setCreatedAt(START);
        entitlementRepo.save(e);
        return account;
    }

    private LearnerGroup classGroup(Account owner) {
        return group(owner, LearningContext.CLASS);
    }

    private LearnerGroup group(Account owner, LearningContext context) {
        LearnerGroup group = new LearnerGroup();
        group.setOwner(owner);
        group.setContext(context);
        group.setOpenContext(context);
        group.setName("Nhom " + System.nanoTime());
        group.setSlotLimit(context == LearningContext.CLASS ? 40 : 4);
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

    /** Câu kho chung n lựa chọn ("L0".."Ln-1"), đáp án đúng ở `correct`. */
    private Question poolQuestion(int options, int correct, boolean keepOrder) {
        Question question = new Question();
        question.setExternalId("V3P" + System.nanoTime());
        question.setPool("V3");
        question.setStem("Cau " + question.getExternalId());
        question.setOptions(MAPPER.valueToTree(java.util.stream.IntStream.range(0, options)
                .mapToObj(i -> "L" + i).toList()).toString());
        question.setCorrectIndex(correct);
        question.setKeepOptionOrder(keepOrder);
        question.setCreatedAt(START);
        return questionRepo.save(question);
    }

    private void finishChapter(LearnerSlot slot, int chapter) {
        RunState run = runStateRepo.findBySlot_Id(slot.getId()).orElseGet(RunState::new);
        run.setSlot(slot);
        run.setChaptersDone("[" + chapter + "]");
        run.setAttemptIndex(1);
        run.setUpdatedAt(START);
        runStateRepo.save(run);
    }

    /** Báo cáo slot cần một bản phát hành hiện hành — chỉ phát khi DB chung chưa có (không đè bản của lớp test khác). */
    private void ensureRelease() throws Exception {
        if (releaseRepo.findFirstByIsCurrentTrue().isEmpty()) {
            ContentTestSupport.publishFresh(mvc, admin(), new ContentTestSupport.Repos(
                    releaseChapterRepo, releaseRepo, draftRepo, chapterRepo), Map.of("CH01", """
                    {"id":"chapter-1","title":"Chuong 1 cho quiz v3","version":"1.0.0",
                     "initialStats":{"SAVING":50,"HAPPINESS":50},
                     "sprites":{"MOM":{"neutral":"{{ASSET_BASE}}/a/abc.webp"}},
                     "scenes":[{"id":"intro1","type":"dialogue","background":"{{ASSET_BASE}}/a/bg1.webp",
                                "text":"Chao","nextSceneId":"end1"},{"id":"end1","type":"ending"}],
                     "endingRules":{"priorityOrder":["x"],"endings":[]}}
                    """), "quiz v3 test");
        }
    }

    private RequestPostProcessor admin() {
        Account a = new Account();
        a.setEmail("v3-admin-" + System.nanoTime() + "@test.local");
        a.setDisplayName("Admin v3");
        a.setCreatedAt(START);
        a = accountRepo.save(a);
        AccountRole role = new AccountRole();
        role.setAccount(a);
        role.setRole(Role.ADMIN);
        role.setGrantedAt(START);
        accountRoleRepo.save(role);
        String id = a.getId().toString();
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("SCOPE_ADMIN"),
                new SimpleGrantedAuthority("TYP_ACCOUNT"));
    }

    // ------------------------------------------------------------------ gọi API
    private static RequestPostProcessor as(Account account) {
        String id = account.getId().toString();
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("TYP_ACCOUNT"));
    }

    private static RequestPostProcessor as(LearnerSlot slot) {
        String id = slot.getId().toString();
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("TYP_SLOT"));
    }

    private String createQuiz(Account teacher, LearnerGroup group, List<String> questionIds, String mode,
                              Integer drawCount, Integer durationMinutes) throws Exception {
        String body = "{\"groupId\":\"" + group.getId() + "\",\"title\":\"De v3\",\"mode\":\"" + mode + "\","
                + "\"dueAt\":\"" + DUE_FUTURE + "\",\"drawCount\":" + drawCount + ",\"durationMinutes\":"
                + durationMinutes + ",\"questionIds\":[" + questionIds.stream().map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",")) + "]}";
        return result(mvc.perform(post("/api/quizzes").with(as(teacher))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()))
                .path("id").asText();
    }

    private void publish(Account teacher, String quizId) throws Exception {
        mvc.perform(post("/api/quizzes/" + quizId + "/publish").with(as(teacher))).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postTest(
            Account caller, LearnerGroup group, boolean enabled) {
        return post("/api/groups/" + group.getId() + "/post-test").with(as(caller))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + enabled + "}");
    }

    private ResultActions saveAnswer(LearnerSlot slot, UUID attemptId, int pos, int choice, Integer leaveCount)
            throws Exception {
        return mvc.perform(put("/api/learner/attempts/" + attemptId + "/answers/" + pos).with(as(slot))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":" + choice + ",\"leaveCount\":" + leaveCount + "}"));
    }

    private QuizAttempt onlyAttempt(String quizId, LearnerSlot slot) {
        List<QuizAttempt> attempts = attemptRepo.findByQuiz_IdAndSlot_IdOrderByAttemptNoAsc(UUID.fromString(quizId),
                slot.getId());
        assertThat(attempts).hasSize(1);
        return attempts.get(0);
    }

    /** Vị trí (trong thứ tự em thấy) của đáp án đúng của một mục paper. */
    private int correctPos(JsonNode entry) {
        int correct = questionRepo.findById(UUID.fromString(entry.path("q").asText())).orElseThrow().getCorrectIndex();
        for (int i = 0; i < entry.path("o").size(); i++) {
            if (entry.path("o").get(i).asInt() == correct) {
                return i;
            }
        }
        throw new AssertionError("paper thiếu đáp án đúng: " + entry);
    }

    private static String newQ(String stem, int correctIndex, String... options) {
        return "{\"stem\":\"" + stem + "\",\"correctIndex\":" + correctIndex + ",\"options\":["
                + java.util.Arrays.stream(options).map(o -> "\"" + o + "\"").collect(Collectors.joining(","))
                + "]}";
    }

    private static JsonNode result(ResultActions actions) throws Exception {
        return MAPPER.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("result");
    }

    private static List<String> ids(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path("id").asText()));
        return out;
    }

    /** Đồng hồ chỉnh được: ca hết giờ / sau hạn nộp nhảy giờ, mỗi ca đặt lại START. */
    static final class MutableClock extends Clock {
        private volatile Instant now = START;

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ClockConfig.VN;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }

        /** Xáo tất định trong test (thiết kế mục 0); bản thật là SecureRandom. */
        @Bean
        @Primary
        Random testRandom() {
            return new Random(42);
        }
    }
}
