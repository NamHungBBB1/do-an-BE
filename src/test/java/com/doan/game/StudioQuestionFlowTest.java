package com.doan.game;

import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.Quiz;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.QuizKind;
import com.doan.game.enums.Role;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.QuestionRepository;
import com.doan.game.repository.QuizRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kho câu hỏi Studio (17/10, cách 1) qua HTTP thật (MockMvc + H2): editor thêm (externalId tự sinh), reviewer xem
 * nhưng không ghi, sửa, rút / đưa lại, thay câu bằng replacesId; câu đã nằm trong đề đã phát thì khoá nội dung (8009)
 * nhưng vẫn sửa được nhãn; người thường bị chặn ở cổng. JWT giả bằng spring-security-test (authorities đặt thẳng),
 * vai thật ghi vào account_role vì service tra DB.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class StudioQuestionFlowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountRoleRepository accountRoleRepo;
    @Autowired QuestionRepository questionRepo;
    @Autowired QuizRepository quizRepo;
    @Autowired LearnerGroupRepository groupRepo;

    @Test
    void editorManagesBankAndReviewerOnlyReads() throws Exception {
        Account editor = withRole(account("editor"), Role.GAME_EDITOR);
        Account reviewer = withRole(account("reviewer"), Role.GAME_REVIEWER);
        Account admin = withRole(account("admin"), Role.ADMIN);
        RequestPostProcessor asEditor = as(editor, "SCOPE_GAME_EDITOR", "TYP_ACCOUNT");
        RequestPostProcessor asReviewer = as(reviewer, "SCOPE_GAME_REVIEWER", "TYP_ACCOUNT");
        String tag = "sq" + System.nanoTime();

        // Editor thêm câu không có externalId → BE tự sinh "ST-…".
        String created = mvc.perform(post("/api/studio/questions").with(asEditor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "Cau hoi " + tag, 4, 2, 1, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.chapter").value(1))
                .andExpect(jsonPath("$.result.correctIndex").value(2))
                .andExpect(jsonPath("$.result.retiredAt").isEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode q1 = MAPPER.readTree(created).path("result");
        String id1 = q1.path("id").asText();
        assertThat(q1.path("externalId").asText()).startsWith("ST-");

        // Reviewer xem được (lọc theo chương, theo chữ), nhưng ghi thì 403 / 3004 ở service.
        mvc.perform(get("/api/studio/questions").with(asReviewer).param("chapter", "1").param("q", tag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(1))
                .andExpect(jsonPath("$.result[0].id").value(id1));
        mvc.perform(post("/api/studio/questions").with(asReviewer).contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "Reviewer thu them " + tag, 4, 0, 1, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));

        // Người thường (gói PARENT) không qua được cổng /api/studio/questions.
        Account parent = account("parent");
        mvc.perform(get("/api/studio/questions").with(as(parent, "SCOPE_PARENT", "TYP_ACCOUNT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));

        // Sửa câu chưa vào đề nào: đổi chữ thoải mái.
        mvc.perform(put("/api/studio/questions/" + id1).with(asEditor).contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "Cau hoi sua " + tag, 4, 3, 1, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.stem").value("Cau hoi sua " + tag))
                .andExpect(jsonPath("$.result.correctIndex").value(3));

        // Rút → biến khỏi kho mặc định và khỏi kho giáo viên; includeRetired vẫn thấy; đưa lại → về.
        mvc.perform(post("/api/studio/questions/" + id1 + "/retire").with(asEditor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.retiredAt").isNotEmpty());
        mvc.perform(get("/api/studio/questions").with(asReviewer).param("q", tag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").isEmpty());
        mvc.perform(get("/api/studio/questions").with(asReviewer).param("q", tag).param("includeRetired", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(1));
        String teacherBank = mvc.perform(get("/api/questions").with(as(admin, "SCOPE_ADMIN", "TYP_ACCOUNT")).param("q", tag))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(teacherBank).doesNotContain(id1);
        mvc.perform(post("/api/studio/questions/" + id1 + "/restore").with(asEditor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.retiredAt").isEmpty());

        // Thay câu: câu mới trỏ về câu cũ, câu cũ tự rút.
        String replaced = mvc.perform(post("/api/studio/questions").with(asEditor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-" + tag, "Cau hoi moi thay " + tag, 4, 1, 1, id1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.replacesId").value(id1))
                .andExpect(jsonPath("$.result.externalId").value("EXT-" + tag))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String id2 = MAPPER.readTree(replaced).path("result").path("id").asText();
        assertThat(questionRepo.findById(UUID.fromString(id1)).orElseThrow().getRetiredAt()).isNotNull();
        mvc.perform(get("/api/studio/questions").with(asReviewer).param("q", tag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(1))
                .andExpect(jsonPath("$.result[0].id").value(id2));

        // Dữ liệu sai: trùng externalId, thiếu lựa chọn, đáp án ngoài phạm vi, chương lạ → 1001; id lạ → 1003.
        mvc.perform(post("/api/studio/questions").with(asEditor).contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-" + tag, "Trung ma " + tag, 4, 0, 1, null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
        mvc.perform(post("/api/studio/questions").with(asEditor).contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "Mot lua chon " + tag, 1, 0, 1, null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
        mvc.perform(post("/api/studio/questions").with(asEditor).contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "Dap an sai " + tag, 3, 3, 1, null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
        mvc.perform(post("/api/studio/questions").with(asEditor).contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "Chuong la " + tag, 3, 0, 9, null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
        mvc.perform(put("/api/studio/questions/" + UUID.randomUUID()).with(asEditor)
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, "Khong co " + tag, 3, 0, 1, null)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void questionInPublishedQuizKeepsContentButLabelsChange() throws Exception {
        Account manager = withRole(account("manager"), Role.GAME_MANAGER);
        RequestPostProcessor asManager = as(manager, "SCOPE_GAME_MANAGER", "TYP_ACCOUNT");
        String tag = "lock" + System.nanoTime();
        String created = mvc.perform(post("/api/studio/questions").with(asManager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "Cau da dung " + tag, 4, 1, 2, null)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String id = MAPPER.readTree(created).path("result").path("id").asText();

        // Một đề ĐÃ PHÁT chứa câu này (ghi thẳng bằng repo — luồng phát đề đã có test riêng ở QuizFlowTest).
        Account teacher = account("teacher");
        LearnerGroup group = new LearnerGroup();
        group.setOwner(teacher);
        group.setContext(LearningContext.CLASS);
        group.setName("Lop " + tag);
        group.setSlotLimit(40);
        group.setOpenedAt(Instant.now());
        group = groupRepo.save(group);
        Quiz quiz = new Quiz();
        quiz.setOwner(teacher);
        quiz.setGroup(group);
        quiz.setTitle("De " + tag);
        quiz.setKind(QuizKind.CLASS_TEST);
        quiz.setQuestionIds("[\"" + id + "\"]");
        quiz.setPublishedAt(Instant.now());
        quizRepo.save(quiz);

        // Đổi chữ / đáp án → 8009; giữ nguyên nội dung mà đổi nhãn → 200; rút vẫn được.
        mvc.perform(put("/api/studio/questions/" + id).with(asManager).contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "Cau da dung SUA " + tag, 4, 1, 2, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(8009));
        mvc.perform(put("/api/studio/questions/" + id).with(asManager).contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "Cau da dung " + tag, 4, 0, 2, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(8009));
        mvc.perform(put("/api/studio/questions/" + id).with(asManager).contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithLabels(null, "Cau da dung " + tag, 4, 1, 2, "CEE-III", "D04.03")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.standard").value("CEE-III"))
                .andExpect(jsonPath("$.result.concept").value("D04.03"));
        mvc.perform(post("/api/studio/questions/" + id + "/retire").with(asManager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.retiredAt").isNotEmpty());
        assertThat(quizRepo.findById(quiz.getId()).orElseThrow().getQuestionIds()).contains(id);
    }

    // ---- phụ trợ ----

    private Account account(String prefix) {
        Account a = new Account();
        a.setEmail(prefix + "-" + System.nanoTime() + "@test.local");
        a.setDisplayName(prefix);
        a.setCreatedAt(Instant.now());
        return accountRepo.save(a);
    }

    private Account withRole(Account a, Role role) {
        AccountRole r = new AccountRole();
        r.setAccount(a);
        r.setRole(role);
        r.setGrantedAt(Instant.now());
        accountRoleRepo.save(r);
        return a;
    }

    /** JWT giả: sub = id tài khoản, authorities đặt thẳng (SCOPE_x / TYP_x) như SecurityConfig suy ra. */
    private static RequestPostProcessor as(Account a, String... authorities) {
        return jwt().jwt(j -> j.subject(a.getId().toString()))
                .authorities(Arrays.stream(authorities).map(SimpleGrantedAuthority::new)
                        .toArray(GrantedAuthority[]::new));
    }

    private static String body(String externalId, String stem, int optionCount, int correctIndex, Integer chapter,
                               String replacesId) {
        return bodyWithLabels(externalId, stem, optionCount, correctIndex, chapter, "CEE-II", "D01.02", replacesId);
    }

    private static String bodyWithLabels(String externalId, String stem, int optionCount, int correctIndex,
                                         Integer chapter, String standard, String concept) {
        return bodyWithLabels(externalId, stem, optionCount, correctIndex, chapter, standard, concept, null);
    }

    private static String bodyWithLabels(String externalId, String stem, int optionCount, int correctIndex,
                                         Integer chapter, String standard, String concept, String replacesId) {
        StringBuilder options = new StringBuilder("[");
        for (int i = 0; i < optionCount; i++) {
            options.append(i > 0 ? "," : "").append("\"Lua chon ").append(i).append("\"");
        }
        options.append("]");
        return "{" + (externalId == null ? "" : "\"externalId\":\"" + externalId + "\",")
                + "\"pool\":\"FINTEEN_POST\",\"stem\":\"" + stem + "\",\"options\":" + options
                + ",\"correctIndex\":" + correctIndex + ",\"standard\":\"" + standard + "\",\"concept\":\"" + concept
                + "\",\"source\":\"test\",\"chapter\":" + chapter
                + (replacesId == null ? "" : ",\"replacesId\":\"" + replacesId + "\"") + "}";
    }
}
