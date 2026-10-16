package com.doan.game;

import com.doan.game.configuration.ClockConfig;
import com.doan.game.entity.Account;
import com.doan.game.entity.Entitlement;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.Quiz;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.PlanKind;
import com.doan.game.enums.QuizKind;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.QuestionRepository;
import com.doan.game.repository.QuizRepository;
import com.doan.game.service.OutgoingMail;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ruột quiz hai kiểu đi qua HTTP thật (MockMvc + H2), token THẬT theo khuôn AuthFlowTest — 8 nhóm
 * trong contract quiz.md v2 (Hưng chốt 16/10), gộp thành 7 ca (nhóm 8 gộp vào nhóm 6 vì cùng dữ
 * liệu A vừa nộp).
 *
 * Bản "1.0.4" riêng (idempotent như PlayFlowTest / ReportFlowTest): 2 chương tối giản — chỉ để
 * gửi lô chơi "xong chương" kích hoạt hậu trắc. Lô gửi rỗng (choices / miniGames []) — hợp lệ
 * như emptyBatch của PlayFlowTest, không cần dựng cảnh.
 *
 * Số liệu / bản quyền không đếm tổng toàn bảng: import dùng externalId có nanoTime (độc lập JVM),
 * mọi ca tự dựng nhóm / slot / đề riêng. Đồng hồ đóng băng 10:00 02/10/2026 giờ VN — hạn nộp
 * "hôm sau" luôn tương lai, ca quá hạn ghi đề thẳng bằng repo với dueAt trước giờ đóng băng.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class QuizFlowTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ClockConfig.VN);
    private static final Pattern OTP_IN_MAIL = Pattern.compile(">(\\d{6})</p>");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Bản của test này — ContentFlow 1.0.0/1.0.1, Play 1.0.2, Report 1.0.3 nên 1.0.4 luôn trống. */
    private static final String VERSION = "1.0.4";

    /** Hạn nộp mặc định của đề test — 1 ngày sau đồng hồ đóng băng (tương lai, chưa overdue). */
    private static final String DUE_FUTURE = "2026-10-03T03:00:00Z";

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired EntitlementRepository entitlementRepo;
    @Autowired LearnerGroupRepository groupRepo;
    @Autowired QuestionRepository questionRepo;
    @Autowired QuizRepository quizRepo;
    @Autowired AuthFlowTest.MailCatcher mailCatcher;

    @BeforeEach
    void resetMailbox() {
        mailCatcher.sent.clear();
    }

    // ------------------------------------------------------------------ 1. nhập kho
    /** Admin import: upsert theo externalId (import lại updated, không nhân đôi), câu không có lựa chọn → skipped, bộ chương không lộ ở GET /api/questions. */
    @Test
    void importQuestionsUpsertsAndHidesChapterSet() throws Exception {
        String teacher = adult("qzimport", PlanKind.TEACHER);
        String p1 = ext("P1");
        String p2 = ext("P2");
        String noOptions = ext("NO");
        String c1 = ext("C1");
        String c2 = ext("C2");
        String body = "[" + questionJson(p1, null, true) + "," + questionJson(p2, null, true)
                + "," + questionJson(noOptions, null, false) + ","
                + questionJson(c1, 1, true) + "," + questionJson(c2, 1, true) + "]";

        mvc.perform(post("/api/admin/questions/import").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.inserted").value(4))
                .andExpect(jsonPath("$.result.updated").value(0))
                .andExpect(jsonPath("$.result.skipped").value(1));

        // Import lại: cùng externalId → updated, không nhân đôi (findByExternalId mà trùng thì nổ).
        mvc.perform(post("/api/admin/questions/import").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.inserted").value(0))
                .andExpect(jsonPath("$.result.updated").value(4))
                .andExpect(jsonPath("$.result.skipped").value(1));
        assertThat(questionRepo.findByExternalId(p1)).isPresent();

        // Giáo viên chọn câu: có 2 câu kho chung, KHÔNG có 2 câu chương 1 và câu không có lựa chọn.
        String list = mvc.perform(get("/api/questions").header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> externalIds = new ArrayList<>();
        for (JsonNode n : MAPPER.readTree(list).path("result")) {
            externalIds.add(n.path("externalId").asText());
        }
        assertThat(externalIds).contains(p1, p2).doesNotContain(c1, c2, noOptions);
    }

    // ------------------------------------------------------------------ 2. giáo viên ra đề, trẻ nộp
    /** Tạo + publish → A nộp 1 đúng 1 sai (score 1, review không correctIndex) → A completed 1; B vào lớp sau publish → pending 1. Đáp án không bao giờ xuống trẻ. */
    @Test
    void teacherPublishesAndLearnersSubmit() throws Exception {
        Room room = newRoom("qzday");
        List<String> ids = importPool(room.teacher(), 2);
        String quizId = createQuiz(room.teacher(), room.groupId(), ids, "De 1");
        publishQuiz(room.teacher(), quizId);

        Kid a = newKid(room, "HS A");
        String submitBody = mvc.perform(submit(a.token(), quizId,
                        answerJson(ids.get(0), 0, ids.get(1), 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.quizId").value(quizId))
                .andExpect(jsonPath("$.result.score").value(1))
                .andExpect(jsonPath("$.result.totalQuestions").value(2))
                .andExpect(jsonPath("$.result.review.length()").value(2))
                .andExpect(jsonPath("$.result.submittedAt").value(notNullValue()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // Review chỉ nói đúng / sai — không có đáp án.
        assertThat(submitBody).doesNotContain("correctIndex");

        // Screenshot đề để làm: không có correctIndex, đã nộp thì kèm submission.
        String detail = mvc.perform(get("/api/learner/quizzes/" + quizId)
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.questions.length()").value(2))
                .andExpect(jsonPath("$.result.submission.score").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(detail).doesNotContain("correctIndex");

        // A: đã nộp → completed 1, pending rỗng.
        mvc.perform(get("/api/learner/quizzes").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.pending").isEmpty())
                .andExpect(jsonPath("$.result.completed.length()").value(1))
                .andExpect(jsonPath("$.result.completed[0].title").value("De 1"))
                .andExpect(jsonPath("$.result.completed[0].score").value(1));

        // B vào lớp SAU khi publish: đề đã thấy, pending 1 (chưa nộp).
        Kid b = newKid(room, "HS B");
        mvc.perform(get("/api/learner/quizzes").header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.pending.length()").value(1))
                .andExpect(jsonPath("$.result.pending[0].id").value(quizId))
                .andExpect(jsonPath("$.result.pending[0].kind").value("CLASS_TEST"))
                .andExpect(jsonPath("$.result.pending[0].questionCount").value(2))
                .andExpect(jsonPath("$.result.pending[0].overdue").value(false))
                .andExpect(jsonPath("$.result.completed").isEmpty());
    }

    // ------------------------------------------------------------------ 3. lỗi đúng đường
    /** 8006 câu lạ, 8005 nộp hai, 8004 quá hạn (đồng hồ test), 8002 sửa / xoá đề đã publish, 8007 câu chương + câu không trong kho, 1001 dueAt quá khứ. */
    @Test
    void validationAndErrorCodes() throws Exception {
        Room room = newRoom("qzloi");
        List<String> ids = importPool(room.teacher(), 2);
        String chapterId = importChapterQuestion(ext("CHX"), 1);
        Kid a = newKid(room, "HS A");

        // 8007: câu hậu trắc không được cho vào đề giáo viên; 8007: câu lạ (không trong kho).
        mvc.perform(create(room.teacher(), room.groupId(), List.of(chapterId), "De cua chuong"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(8007));
        mvc.perform(create(room.teacher(), room.groupId(),
                        List.of(UUID.randomUUID().toString()), "De co ca la"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(8007));

        // 8007: câu trùng trong questionIds là lỗi người ra đề, không phải 8006 (lỗi nộp bài).
        mvc.perform(create(room.teacher(), room.groupId(),
                        List.of(ids.get(0), ids.get(0)), "De trung cau"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(8007));

        // 1001: dueAt phải sau thời điểm hiện tại (đồng hồ test 03:00 02/10).
        mvc.perform(post("/api/quizzes").header("Authorization", "Bearer " + room.teacher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + room.groupId() + "\",\"title\":\"De het han\","
                                + "\"questionIds\":[\"" + ids.get(0) + "\"],"
                                + "\"dueAt\":\"2026-10-01T03:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        String quizId = createQuiz(room.teacher(), room.groupId(), ids, "De 1");
        publishQuiz(room.teacher(), quizId);

        // 8002: publish rồi là bất biến — không sửa, không xoá.
        mvc.perform(put("/api/quizzes/" + quizId).header("Authorization", "Bearer " + room.teacher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"De sua\",\"questionIds\":[\"" + ids.get(0)
                                + "\"],\"dueAt\":\"" + DUE_FUTURE + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(8002));
        mvc.perform(delete("/api/quizzes/" + quizId).header("Authorization", "Bearer " + room.teacher()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(8002));

        // 8006: questionId lạ (không thuộc đề).
        mvc.perform(submit(a.token(), quizId,
                        "[{\"questionId\":\"" + UUID.randomUUID() + "\",\"chosenIndex\":0}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(8006));

        // Nộp hợp lệ → 200, rồi nộp lần hai → 8005.
        mvc.perform(submit(a.token(), quizId, answerJson(ids.get(0), 0, ids.get(1), 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.score").value(1));
        mvc.perform(submit(a.token(), quizId, answerJson(ids.get(0), 0, ids.get(1), 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(8005));

        // 8004: quá hạn — đề có dueAt trước đồng hồ test, ghi thẳng bằng repo (API không nhận dueAt quá khứ).
        String overdueId = seedOverdueQuiz(room, ids);
        mvc.perform(submit(a.token(), overdueId, answerJson(ids.get(0), 0)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(8004));
    }

    // ------------------------------------------------------------------ 4. cửa quyền
    /** Phụ huynh FAMILY tạo đề → 8008; token SLOT gọi /api/quizzes → 3004; giáo viên khác đọc kết quả → 3004; thiếu gói TEACHER đọc kho câu → 3005. */
    @Test
    void permissionGates() throws Exception {
        String parent = adult("qzphuhuynh", PlanKind.PARENT);
        String familyId = openGroup(parent, "Nha qzphuhuynh", "FAMILY");
        mvc.perform(post("/api/quizzes").header("Authorization", "Bearer " + parent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + familyId + "\",\"title\":\"De gia dinh\","
                                + "\"questionIds\":[],\"dueAt\":null}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(8008));

        Room room = newRoom("qzquyen");
        Kid a = newKid(room, "HS A");
        List<String> ids = importPool(room.teacher(), 2);
        String quizId = createQuiz(room.teacher(), room.groupId(), ids, "De 1");
        publishQuiz(room.teacher(), quizId);

        // Token trẻ không qua được cửa TYP_ACCOUNT.
        mvc.perform(get("/api/quizzes?groupId=" + room.groupId())
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));

        // Giáo viên khác không đọc được kết quả / chi tiết đề của lớp này.
        String teacher2 = adult("qzkhac", PlanKind.TEACHER);
        mvc.perform(get("/api/quizzes/" + quizId + "/results")
                        .header("Authorization", "Bearer " + teacher2))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));

        // Không có gói TEACHER → không lấy được kho câu (ADMIN thì qua, không test tới).
        String noPlan = adult("qzgoicong", PlanKind.PARENT);
        mvc.perform(get("/api/questions").header("Authorization", "Bearer " + noPlan))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value(3005));
    }

    // ------------------------------------------------------------------ 5. kết quả lớp + báo cáo
    /** summary {assigned 2 (hai dòng ACTIVE), submitted 1, averageScore 1.0}; B attempted false; results/{A} có correctIndex, /{B} → 8003; khối quizzes báo cáo slot + dòng / summary nhóm. */
    @Test
    void classResultsAndReports() throws Exception {
        releaseVersion();
        Room room = newRoom("qzketqua");
        List<String> ids = importPool(room.teacher(), 2);
        Kid a = newKid(room, "HS A");
        Kid b = newKid(room, "HS B");
        String quiz1 = createQuiz(room.teacher(), room.groupId(), ids, "De 1");
        publishQuiz(room.teacher(), quiz1);
        String quiz2 = createQuiz(room.teacher(), room.groupId(), ids, "De 2");
        publishQuiz(room.teacher(), quiz2);

        mvc.perform(submit(a.token(), quiz1, answerJson(ids.get(0), 0, ids.get(1), 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.score").value(1));

        // Kết quả cả lớp: hai dòng ACTIVE (A trước vì mở sớm hơn), A đã nộp, B chưa.
        mvc.perform(get("/api/quizzes/" + quiz1 + "/results")
                        .header("Authorization", "Bearer " + room.teacher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.quiz.questionCount").value(2))
                .andExpect(jsonPath("$.result.quiz.submittedCount").value(1))
                .andExpect(jsonPath("$.result.summary.assigned").value(2))
                .andExpect(jsonPath("$.result.summary.submitted").value(1))
                .andExpect(jsonPath("$.result.summary.averageScore").value(1.0))
                .andExpect(jsonPath("$.result.rows.length()").value(2))
                .andExpect(jsonPath("$.result.rows[0].slotId").value(a.slotId()))
                .andExpect(jsonPath("$.result.rows[0].attempted").value(true))
                .andExpect(jsonPath("$.result.rows[0].score").value(1))
                .andExpect(jsonPath("$.result.rows[1].slotId").value(b.slotId()))
                .andExpect(jsonPath("$.result.rows[1].attempted").value(false))
                .andExpect(jsonPath("$.result.rows[1].score").value(nullValue()));

        // Từng câu của A có đáp án; B chưa nộp → 8003.
        mvc.perform(get("/api/quizzes/" + quiz1 + "/results/" + a.slotId())
                        .header("Authorization", "Bearer " + room.teacher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.score").value(1))
                .andExpect(jsonPath("$.result.answers.length()").value(2))
                .andExpect(jsonPath("$.result.answers[0].correctIndex").value(0))
                .andExpect(jsonPath("$.result.answers[0].correct").value(notNullValue()));
        mvc.perform(get("/api/quizzes/" + quiz1 + "/results/" + b.slotId())
                        .header("Authorization", "Bearer " + room.teacher()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(8003));

        // Báo cáo slot: hai đề publish, A nộp 1 bài, B chưa — cùng dữ liệu.
        mvc.perform(get("/api/slots/" + a.slotId() + "/report")
                        .header("Authorization", "Bearer " + room.teacher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.quizzes.assigned").value(2))
                .andExpect(jsonPath("$.result.quizzes.submitted").value(1))
                .andExpect(jsonPath("$.result.quizzes.results[0].quizId").value(quiz1))
                .andExpect(jsonPath("$.result.quizzes.results[0].title").value("De 1"))
                .andExpect(jsonPath("$.result.quizzes.results[0].score").value(1))
                .andExpect(jsonPath("$.result.quizzes.results[0].totalQuestions").value(2))
                .andExpect(jsonPath("$.result.quizzes.results[0].submittedAt").value(notNullValue()));
        mvc.perform(get("/api/slots/" + b.slotId() + "/report")
                        .header("Authorization", "Bearer " + room.teacher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.quizzes.assigned").value(2))
                .andExpect(jsonPath("$.result.quizzes.submitted").value(0))
                .andExpect(jsonPath("$.result.quizzes.results").isEmpty());

        // Báo cáo nhóm: assigned = số đề publish của lớp; submitted / averageScore theo từng dòng và tổng.
        mvc.perform(get("/api/groups/" + room.groupId() + "/report")
                        .header("Authorization", "Bearer " + room.teacher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.summary.quizAssigned").value(2))
                .andExpect(jsonPath("$.result.summary.quizSubmitted").value(1))
                .andExpect(jsonPath("$.result.summary.averageScore").value(1.0))
                .andExpect(jsonPath("$.result.rows[0].slotId").value(a.slotId()))
                .andExpect(jsonPath("$.result.rows[0].quizAssigned").value(2))
                .andExpect(jsonPath("$.result.rows[0].quizSubmitted").value(1))
                .andExpect(jsonPath("$.result.rows[0].averageScore").value(1.0))
                .andExpect(jsonPath("$.result.rows[1].slotId").value(b.slotId()))
                .andExpect(jsonPath("$.result.rows[1].quizSubmitted").value(0))
                .andExpect(jsonPath("$.result.rows[1].averageScore").value(nullValue()));
    }

    // ------------------------------------------------------------------ 6. hậu trắc + dashboard đợt 3
    /** Bật cờ → A xong chương 1 tự có đề POST_TEST (B không thấy), chương không có câu không tạo, nộp → completed; tắt cờ thì chương đã xong + có câu vẫn không sinh đề; dashboard completed[0].score + QUIZ_SUBMITTED (nhóm 8). */
    @Test
    void postTestAutoCreatesAndDashboardReflectsIt() throws Exception {
        releaseVersion();
        Room room = newRoom("qzhautrac");
        Kid a = newKid(room, "HS A");
        Kid b = newKid(room, "HS B");

        // Bật cờ → GET /api/groups thấy postTestEnabled true (200 không result — ApiResponse.ok() Void).
        mvc.perform(post("/api/groups/" + room.groupId() + "/post-test")
                        .header("Authorization", "Bearer " + room.teacher())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        assertThat(postTestEnabled(room.teacher(), room.groupId())).isTrue();

        // Bộ hậu trắc chương 1: 2 câu (kho chung không import lần này — chỉ bộ chương).
        String ch1a = ext("H1A");
        String ch1b = ext("H1B");
        importAsAdmin("[" + questionJson(ch1a, 1, true) + "," + questionJson(ch1b, 1, true) + "]");
        // Đề hậu trắc chụp MỌI câu chương 1 còn sống — các ca khác trong cùng DB cũng nhập chương 1
        // (đều theo hợp đồng), nên đếm từ repo thay vì đoán đúng 2.
        int chapterOneCount = questionRepo.findByChapterAndRetiredAtIsNullOrderByExternalIdAsc(1).size();
        assertThat(chapterOneCount).isGreaterThanOrEqualTo(2);

        // A xong chương 1 → đề hậu trắc tự sinh trong danh sách; B chưa xong → không thấy.
        play(a.token(), 1);
        String listA = mvc.perform(get("/api/learner/quizzes").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.pending.length()").value(1))
                .andExpect(jsonPath("$.result.pending[0].kind").value("POST_TEST"))
                .andExpect(jsonPath("$.result.pending[0].chapter").value(1))
                .andExpect(jsonPath("$.result.pending[0].title").value("Hậu trắc chương 1"))
                .andExpect(jsonPath("$.result.pending[0].questionCount").value(chapterOneCount))
                .andExpect(jsonPath("$.result.pending[0].dueAt").value(nullValue()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String postQuizId = MAPPER.readTree(listA).path("result").path("pending").get(0).path("id").asText();
        mvc.perform(get("/api/learner/quizzes").header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.pending").isEmpty());

        // A xong chương 2 — chương CHƯA có câu → không tạo gì, không lỗi.
        play(a.token(), 2);
        mvc.perform(get("/api/learner/quizzes").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.pending.length()").value(1))
                .andExpect(jsonPath("$.result.pending[0].chapter").value(1));

        // Đề làm không có đáp án — và đúng bộ chương (chứa 2 câu vừa nhập của ca này).
        String detail = mvc.perform(get("/api/learner/quizzes/" + postQuizId)
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.questions.length()").value(chapterOneCount))
                .andExpect(jsonPath("$.result.submission").value(nullValue()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(detail).doesNotContain("correctIndex");
        List<String> detailIds = new ArrayList<>();
        for (JsonNode n : MAPPER.readTree(detail).path("result").path("questions")) {
            detailIds.add(n.path("id").asText());
        }
        assertThat(detailIds).contains(questionIdByExternal(ch1a), questionIdByExternal(ch1b));

        // A nộp (1 đúng 1 sai) → completed; pending trống.
        mvc.perform(submit(a.token(), postQuizId, answerJson(questionIdByExternal(ch1a), 0,
                        questionIdByExternal(ch1b), 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.score").value(1));
        mvc.perform(get("/api/learner/quizzes").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.pending").isEmpty())
                .andExpect(jsonPath("$.result.completed.length()").value(1))
                .andExpect(jsonPath("$.result.completed[0].kind").value("POST_TEST"))
                .andExpect(jsonPath("$.result.completed[0].chapter").value(1))
                .andExpect(jsonPath("$.result.completed[0].score").value(1));

        // Giáo viên thấy đề POST_TEST trong danh sách và kết quả: A đã nộp, B chưa.
        String teacherList = mvc.perform(get("/api/quizzes?groupId=" + room.groupId())
                        .header("Authorization", "Bearer " + room.teacher()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<JsonNode> postTests = new ArrayList<>();
        for (JsonNode n : MAPPER.readTree(teacherList).path("result")) {
            if ("POST_TEST".equals(n.path("kind").asText())) {
                postTests.add(n);
            }
        }
        assertThat(postTests).hasSize(1);
        assertThat(postTests.get(0).path("chapter").asInt()).isEqualTo(1);
        assertThat(postTests.get(0).path("submittedCount").asInt()).isEqualTo(1);
        mvc.perform(get("/api/quizzes/" + postQuizId + "/results")
                        .header("Authorization", "Bearer " + room.teacher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.rows.length()").value(2))
                .andExpect(jsonPath("$.result.rows[0].attempted").value(true))
                .andExpect(jsonPath("$.result.rows[1].attempted").value(false));

        // Sửa đề hậu trắc → 8002 (đề hậu trắc không bao giờ sửa được).
        mvc.perform(put("/api/quizzes/" + postQuizId).header("Authorization", "Bearer " + room.teacher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Sua hau trac\",\"questionIds\":[\""
                                + questionIdByExternal(ch1a) + "\"],\"dueAt\":null}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(8002));

        // Dashboard của A sau nộp (nhóm 8): completed[0].score + recentActivities[0] là QUIZ_SUBMITTED.
        mvc.perform(get("/api/learner/dashboard").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.quizzes.completed[0].score").value(1))
                .andExpect(jsonPath("$.result.quizzes.completed[0].title").value("Hậu trắc chương 1"))
                .andExpect(jsonPath("$.result.recentActivities[0].type").value("QUIZ_SUBMITTED"))
                .andExpect(jsonPath("$.result.recentActivities[0].label")
                        .value("Nộp quiz Hậu trắc chương 1"))
                .andExpect(jsonPath("$.result.recentActivities[0].at").value(notNullValue()));

        // Tắt cờ → pending biến mất, completed giữ nguyên.
        mvc.perform(post("/api/groups/" + room.groupId() + "/post-test")
                        .header("Authorization", "Bearer " + room.teacher())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        assertThat(postTestEnabled(room.teacher(), room.groupId())).isFalse();

        // Nhập câu chương 2 SAU khi tắt cờ: chương đã xong + đã có câu nhưng lớp tắt → vẫn không sinh đề.
        String ch2a = ext("H2A");
        importAsAdmin("[" + questionJson(ch2a, 2, true) + "]");
        mvc.perform(get("/api/learner/quizzes").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.pending").isEmpty())
                .andExpect(jsonPath("$.result.completed.length()").value(1))
                .andExpect(jsonPath("$.result.completed[0].chapter").value(1));
    }

    // ------------------------------------------------------------------ 7. quyền bật / tắt cờ
    /** FAMILY → 8008; không phải chủ → 3004; bật khi hết gói TEACHER → 3005; tắt khi hết gói → vẫn 200. */
    @Test
    void postTestFlagPermissions() throws Exception {
        // Phụ huynh bật trên nhóm FAMILY → 8008.
        String parent = adult("qzcod", PlanKind.PARENT);
        String familyId = openGroup(parent, "Nha qzcod", "FAMILY");
        mvc.perform(post("/api/groups/" + familyId + "/post-test")
                        .header("Authorization", "Bearer " + parent)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(8008));

        // Người không phải chủ → 3004 (trước cả kiểm gói).
        Room room = newRoom("qztong");
        String teacher2 = adult("qznhatong", PlanKind.TEACHER);
        mvc.perform(post("/api/groups/" + room.groupId() + "/post-test")
                        .header("Authorization", "Bearer " + teacher2)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));

        // Bật khi còn gói → 200; hết gói: bật → 3005, tắt → vẫn 200 (chỉ cần chủ nhóm).
        mvc.perform(post("/api/groups/" + room.groupId() + "/post-test")
                        .header("Authorization", "Bearer " + room.teacher())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        assertThat(postTestEnabled(room.teacher(), room.groupId())).isTrue();
        revoke(room.teacher(), PlanKind.TEACHER);
        mvc.perform(post("/api/groups/" + room.groupId() + "/post-test")
                        .header("Authorization", "Bearer " + room.teacher())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value(3005));
        mvc.perform(post("/api/groups/" + room.groupId() + "/post-test")
                        .header("Authorization", "Bearer " + room.teacher())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        assertThat(postTestEnabled(room.teacher(), room.groupId())).isFalse();
    }

    // ------------------------------------------------------------------ dựng dữ liệu
    /** Bản "1.0.4" của test này, idempotent: 2 chương tối giản (CH01, CH02) — chỉ để lô chơi "xong chương" chạy được. */
    private String releaseVersion() throws Exception {
        var cur = mvc.perform(get("/api/content/current")).andReturn().getResponse();
        if (cur.getStatus() == 200
                && cur.getContentAsString(StandardCharsets.UTF_8).contains("\"version\":\"" + VERSION + "\"")) {
            return VERSION;
        }
        var asAdmin = admin();
        mvc.perform(put("/api/studio/chapters/CH01/draft").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(minimalChapter(1)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/studio/chapters/CH02/draft").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(minimalChapter(2))
                )
                .andExpect(status().isOk());
        var pub = mvc.perform(post("/api/studio/releases").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"" + VERSION + "\",\"note\":\"quiz flow test\"}"))
                .andReturn().getResponse();
        if (pub.getStatus() == 409) {
            mvc.perform(post("/api/studio/releases/" + VERSION + "/current").with(asAdmin))
                    .andExpect(status().isOk());
        } else {
            assertThat(pub.getStatus()).as(pub.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        }
        return VERSION;
    }

    /** Chương N tối giản — không cảnh choice / minigame, lô rỗng vẫn ghi chaptersDone được. */
    private static String minimalChapter(int n) {
        return """
                {"id":"chapter-%d","title":"Chuong %d cho test quiz","version":"1.0.0",
                 "initialStats":{"SAVING":50,"HAPPINESS":50},
                 "sprites":{"MOM":{"neutral":"{{ASSET_BASE}}/a/abc.webp"}},
                 "scenes":[
                   {"id":"intro%d","type":"dialogue","background":"{{ASSET_BASE}}/a/bg1.webp","text":"Chao","nextSceneId":"end%d"},
                   {"id":"end%d","type":"ending"}
                 ],
                 "endingRules":{"priorityOrder":["x"],"endings":[]}}
                """.formatted(n, n, n, n, n);
    }

    /** externalId có nanoTime — import lại lần nữa trong cùng JVM vẫn là inserted, không lệch assertion. */
    private static String ext(String tag) {
        return "QZ" + System.nanoTime() + "-" + tag;
    }

    /** Nhập 2 câu kho chung (correctIndex 0, 2 lựa chọn) rồi đọc id theo đúng thứ tự externalId. */
    private List<String> importPool(String teacher, int count) throws Exception {
        List<String> extIds = new ArrayList<>();
        StringBuilder body = new StringBuilder("[");
        for (int i = 1; i <= count; i++) {
            String e = ext("POOL" + i);
            extIds.add(e);
            if (i > 1) {
                body.append(",");
            }
            body.append(questionJson(e, null, true));
        }
        body.append("]");
        importAsAdmin(body.toString());
        return poolIds(teacher, extIds);
    }

    private void importAsAdmin(String body) throws Exception {
        mvc.perform(post("/api/admin/questions/import").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private List<String> poolIds(String teacher, List<String> extIds) throws Exception {
        String body = mvc.perform(get("/api/questions").header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode arr = MAPPER.readTree(body).path("result");
        List<String> out = new ArrayList<>();
        for (String ext : extIds) {
            for (JsonNode n : arr) {
                if (ext.equals(n.path("externalId").asText())) {
                    out.add(n.path("id").asText());
                }
            }
        }
        assertThat(out).as("phai tim du %s trong kho", extIds).hasSize(extIds.size());
        return out;
    }

    /** Nhập một câu chương (lấy id bằng repo — câu chương không lộ ở GET /api/questions). */
    private String importChapterQuestion(String ext, int chapter) throws Exception {
        importAsAdmin("[" + questionJson(ext, chapter, true) + "]");
        return questionIdByExternal(ext);
    }

    private String questionIdByExternal(String ext) {
        return questionRepo.findByExternalId(ext).orElseThrow().getId().toString();
    }

    private static String questionJson(String ext, Integer chapter, boolean withOptions) {
        return "{\"externalId\":\"" + ext + "\",\"pool\":\"QZPOOL\",\"stem\":\"Cau " + ext + "\","
                + "\"options\":" + (withOptions ? "[\"Dung\",\"Sai\"]" : "[]") + ","
                + "\"correctIndex\":0,\"standard\":\"CEE-I\",\"concept\":null,\"source\":\"test\","
                + "\"license\":\"test\",\"chapter\":" + (chapter == null ? "null" : chapter) + "}";
    }

    /** Đề quá hạn: ghi thẳng Quiz (dueAt trước đồng hồ test) — API không bao giờ nhận dueAt quá khứ. */
    private String seedOverdueQuiz(Room room, List<String> ids) {
        LearnerGroup group = groupRepo.findById(UUID.fromString(room.groupId())).orElseThrow();
        Quiz quiz = new Quiz();
        quiz.setOwner(group.getOwner());
        quiz.setGroup(group);
        quiz.setTitle("De qua han");
        quiz.setQuestionIds("[" + ids.stream().map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",")) + "]");
        quiz.setKind(QuizKind.CLASS_TEST);
        quiz.setDueAt(Instant.parse("2026-10-02T02:59:00Z"));
        quiz.setPublishedAt(Instant.parse("2026-10-02T02:50:00Z"));
        return quizRepo.save(quiz).getId().toString();
    }

    // ------------------------------------------------------------------ người dùng / nhóm
    private record Room(String teacher, String groupId) {}
    private record Kid(String slotId, String token) {}

    private Room newRoom(String prefix) throws Exception {
        String teacher = adult(prefix, PlanKind.TEACHER);
        String groupId = openGroup(teacher, "Lop " + prefix, "CLASS");
        mvc.perform(post("/api/groups/" + groupId + "/consent")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk());
        return new Room(teacher, groupId);
    }

    private Kid newKid(Room room, String name) throws Exception {
        String slotBody = mvc.perform(slotRequest(room.teacher(), room.groupId(), name, "123456"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new Kid(extractId(slotBody), childLogin(slotBody, "123456"));
    }

    private void play(String child, int chapter) throws Exception {
        mvc.perform(post("/api/play/chapter").header("Authorization", "Bearer " + child)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chapter\":" + chapter + ",\"attemptIndex\":1,\"releaseVersion\":\""
                                + VERSION + "\",\"choices\":[],\"miniGames\":[]}"))
                .andExpect(status().isOk());
    }

    private boolean postTestEnabled(String jwt, String groupId) throws Exception {
        String body = mvc.perform(get("/api/groups").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (JsonNode n : MAPPER.readTree(body).path("result")) {
            if (groupId.equals(n.path("id").asText())) {
                return n.path("postTestEnabled").asBoolean();
            }
        }
        throw new AssertionError("khong thay nhom " + groupId + " trong GET /api/groups: " + body);
    }

    private String adult(String prefix, PlanKind kind) throws Exception {
        String jwt = registerAndVerify(prefix + "-" + System.nanoTime() + "@test.local");
        grant(jwt, kind);
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

    /** Thu hồi gói — kiểm "hết gói giữa chừng" mà không cần đợi đồng hồ nhảy (ca 7: bật → 3005, tắt → 200). */
    private void revoke(String jwt, PlanKind kind) {
        UUID id = UUID.fromString(subject(jwt));
        var toDelete = entitlementRepo.findAll().stream()
                .filter(e -> e.getAccount().getId().equals(id) && e.getKind() == kind)
                .toList();
        entitlementRepo.deleteAll(toDelete);
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

    private RequestPostProcessor admin() {
        Account a = new Account();
        a.setEmail("quiz-admin-" + System.nanoTime() + "@test.local");
        a.setDisplayName("Admin Quiz");
        a.setCreatedAt(Instant.now());
        a = accountRepo.save(a);
        String id = a.getId().toString();
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("SCOPE_ADMIN"));
    }

    // ------------------------------------------------------------------ gọi API
    private MockHttpServletRequestBuilder create(String teacher, String groupId,
                                                 List<String> questionIds, String title) {
        return post("/api/quizzes").header("Authorization", "Bearer " + teacher)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":\"" + groupId + "\",\"title\":\"" + title + "\","
                        + "\"questionIds\":[" + questionIds.stream().map(id -> "\"" + id + "\"")
                        .collect(Collectors.joining(",")) + "],\"dueAt\":\"" + DUE_FUTURE + "\"}");
    }

    private String createQuiz(String teacher, String groupId, List<String> ids, String title)
            throws Exception {
        String body = mvc.perform(create(teacher, groupId, ids, title))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.questionCount").value(ids.size()))
                .andExpect(jsonPath("$.result.publishedAt").value(nullValue()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return extractId(body);
    }

    private void publishQuiz(String teacher, String quizId) throws Exception {
        mvc.perform(post("/api/quizzes/" + quizId + "/publish")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.publishedAt").value(notNullValue()));
    }

    private static MockHttpServletRequestBuilder submit(String child, String quizId, String answers) {
        return post("/api/learner/quizzes/" + quizId + "/submit")
                .header("Authorization", "Bearer " + child)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answers\":" + answers + "}");
    }

    /** Một câu đúng (chọn 0 = correctIndex) + một câu sai (chọn 1). */
    private static String answerJson(String q1, int i1, String q2, int i2) {
        return "[{\"questionId\":\"" + q1 + "\",\"chosenIndex\":" + i1 + "},"
                + "{\"questionId\":\"" + q2 + "\",\"chosenIndex\":" + i2 + "}]";
    }

    private static String answerJson(String q1, int i1) {
        return "[{\"questionId\":\"" + q1 + "\",\"chosenIndex\":" + i1 + "}]";
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
        assertThat(m.find()).as("response phai co id: " + body).isTrue();
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
