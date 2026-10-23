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
import com.doan.game.entity.LearnerSlot;
import com.doan.game.entity.RunState;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.PlanKind;
import com.doan.game.enums.Role;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.LearnerSlotRepository;
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
import static org.hamcrest.Matchers.is;
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
 * Ruột GET /api/slots/{id}/report + GET /api/groups/{id}/report đi qua HTTP thật (MockMvc + H2),
 * token người lớn THẬT theo khuôn AuthFlowTest — 6 ca trong contract report.md (Hưng duyệt 12/10).
 *
 * Số liệu ghi thẳng vào RunState bằng repo thay vì gửi lô chơi qua HTTP: hợp đồng đóng băng nói
 * rõ "kiểm bằng cách sửa RunState trực tiếp trong test" — slot sau khi đóng nhóm đã ARCHIVED thì
 * không gửi lô được nữa, và test không phải dựng bản nội dung cho từng ca.
 *
 * Bản "1.0.3" riêng (idempotent như PlayFlowTest) vì hợp đồng: chưa phát hành bản nào → slot
 * report 1003; thứ tự class reversealphabetical có thể chạy test này trên DB trống.
 *
 * Đồng hồ đóng băng 10:00 02/10/2026 giờ VN — gói cấp trong test luôn còn hạn.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class ReportFlowTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ClockConfig.VN);
    private static final Pattern OTP_IN_MAIL = Pattern.compile(">(\\d{6})</p>");

    /** Bản của test này — ContentFlowTest 1.0.0/1.0.1, PlayFlowTest 1.0.2 nên 1.0.3 luôn trống. */
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
    @Autowired LearnerSlotRepository slotRepo;
    @Autowired RunStateRepository runStateRepo;
    @Autowired AuthFlowTest.MailCatcher mailCatcher;

    @BeforeEach
    void resetMailbox() {
        mailCatcher.sent.clear();
    }

    // ------------------------------------------------------------------ 1. chủ nhóm đọc được
    /** Chủ nhóm FAMILY đọc slot report: learner + progress + 5 chỉ số đủ; WIPED rules chưa đụng; không pinHash / email. */
    @Test
    void ownerReadsSlotReport() throws Exception {
        releaseVersion();
        String parent = adult("repchunho", PlanKind.PARENT);
        String groupId = openGroup(parent, "Nha repchunho", "FAMILY");
        String slotBody = mvc.perform(slotRequest(parent, groupId, "Be Rep", "123456"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String slotId = extractId(slotBody);
        String code = extractSlotCode(slotBody);
        seedRun(slotId, "[1]", 60, 1);
        // publish() sao TOÀN BỘ nháp studio vào bản — tổng chương phụ thuộc test nào chạy trước,
        // nên đọc từ /api/content/current thay vì đoán.
        int total = currentChapterCount();
        boolean allDone = total <= 1;

        var res = mvc.perform(slotReport(parent, slotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result.learner.id").value(slotId))
                .andExpect(jsonPath("$.result.learner.code").value(code))
                .andExpect(jsonPath("$.result.learner.displayName").value("Be Rep"))
                .andExpect(jsonPath("$.result.learner.status").value("ACTIVE"))
                .andExpect(jsonPath("$.result.learner.group.id").value(groupId))
                .andExpect(jsonPath("$.result.learner.group.context").value("FAMILY"))
                .andExpect(jsonPath("$.result.learner.group.closedAt").value(nullValue()))
                .andExpect(jsonPath("$.result.learner.lastActiveAt").value(nullValue()))
                .andExpect(jsonPath("$.result.progress.totalChapters").value(total))
                .andExpect(jsonPath("$.result.progress.completedChapters").value(1))
                .andExpect(jsonPath("$.result.progress.chaptersDone[0]").value(1))
                // Xong [1] với bản 1 chương → hết lượt (null); bản nhiều chương → còn chương sau.
                .andExpect(jsonPath("$.result.progress.continueChapter")
                        .value(allDone ? nullValue() : is(2)))
                .andExpect(jsonPath("$.result.progress.attemptIndex").value(1))
                .andExpect(jsonPath("$.result.progress.lastPlayedAt").value(notNullValue()))
                .andExpect(jsonPath("$.result.indicators.wealth").value(0))
                .andExpect(jsonPath("$.result.indicators.saving").value(60))
                .andExpect(jsonPath("$.result.indicators.happiness").value(55))
                .andExpect(jsonPath("$.result.indicators.risk").value(30))
                .andExpect(jsonPath("$.result.indicators.goal").value(50))
                .andExpect(jsonPath("$.result.achievements").isEmpty())
                .andExpect(jsonPath("$.result.concepts").isEmpty())
                .andExpect(jsonPath("$.result.chapters").isEmpty())
                .andExpect(jsonPath("$.result.quizzes").value(nullValue()))
                .andExpect(jsonPath("$.result.dailySummary").value(nullValue()))
                .andReturn().getResponse();
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("pinHash").doesNotContain("email");
    }

    // ------------------------------------------------------------------ 2. CLASS có object quizzes
    /** Nhóm CLASS: quizzes là object rỗng (đợt 1 assigned 0 / submitted 0 / results []); slot CLASS cần consent trước. */
    @Test
    void classGroupHasEmptyQuizzesObject() throws Exception {
        releaseVersion();
        String teacher = adult("replop", PlanKind.TEACHER);
        String groupId = openGroup(teacher, "Lop rep", "CLASS");
        mvc.perform(post("/api/groups/" + groupId + "/consent")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk());
        String slotBody = mvc.perform(slotRequest(teacher, groupId, "Be Lop", "123456"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String slotId = extractId(slotBody);

        mvc.perform(slotReport(teacher, slotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.learner.group.context").value("CLASS"))
                .andExpect(jsonPath("$.result.quizzes.assigned").value(0))
                .andExpect(jsonPath("$.result.quizzes.submitted").value(0))
                .andExpect(jsonPath("$.result.quizzes.results").isEmpty())
                .andExpect(jsonPath("$.result.indicators").value(nullValue()));
    }

    // ------------------------------------------------------------------ 3. cửa quyền
    /** Tài khoản khác → 3004; token SLOT → 3004 (bị chặn ngay cửa Security); cả hai endpoint. */
    @Test
    void foreignAdultAndSlotTokenRejected() throws Exception {
        releaseVersion();
        String parent = adult("repquyen", PlanKind.PARENT);
        String groupId = openGroup(parent, "Nha repquyen", "FAMILY");
        String slotBody = mvc.perform(slotRequest(parent, groupId, "Be Quyen", "123456"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String slotId = extractId(slotBody);
        String child = childLogin(slotBody, "123456");
        String stranger = adult("replangtem", PlanKind.PARENT);

        mvc.perform(slotReport(stranger, slotId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
        mvc.perform(slotReport(child, slotId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
        mvc.perform(groupReport(stranger, groupId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
    }

    // ------------------------------------------------------------------ 4. lỗi đúng đường
    /** Slot không có → 3007; nhóm không có → 1003. */
    @Test
    void missingSlotAndGroupGetRightErrors() throws Exception {
        String parent = adult("repmaukhong", PlanKind.PARENT);
        UUID noSlot = UUID.randomUUID();
        UUID noGroup = UUID.randomUUID();

        mvc.perform(slotReport(parent, noSlot.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3007));
        mvc.perform(groupReport(parent, noGroup.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1003));
    }

    // ------------------------------------------------------------------ 5. WIPED
    /** Slot WIPED: code / displayName = null, số liệu giữ nguyên (FE hiện "Đã xoá"). */
    @Test
    void wipedSlotHidesIdentityButKeepsFigures() throws Exception {
        releaseVersion();
        String parent = adult("repxoateen", PlanKind.PARENT);
        String groupId = openGroup(parent, "Nha repxoateen", "FAMILY");
        String slotBody = mvc.perform(slotRequest(parent, groupId, "Be Xoa", "123456"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String slotId = extractId(slotBody);
        seedRun(slotId, "[1]", 60, 1);

        mvc.perform(delete("/api/slots/" + slotId)
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk());

        mvc.perform(slotReport(parent, slotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.learner.status").value("WIPED"))
                .andExpect(jsonPath("$.result.learner.code").value(nullValue()))
                .andExpect(jsonPath("$.result.learner.displayName").value(nullValue()))
                .andExpect(jsonPath("$.result.progress.completedChapters").value(1))
                .andExpect(jsonPath("$.result.indicators.saving").value(60));
    }

    // ------------------------------------------------------------------ 6. đóng băng
    /** Đóng nhóm → frozenAt + report-v1; sửa RunState trực tiếp thì số KHÔNG đổi; WIPED xuống cuối; danh tính / status đọc sống. */
    @Test
    void closingGroupFreezesNumbers() throws Exception {
        releaseVersion();
        String parent = adult("repidong", PlanKind.PARENT);
        String groupId = openGroup(parent, "Nha repidong", "FAMILY");
        String slotBody = mvc.perform(slotRequest(parent, groupId, "Be Dong", "123456"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String slotId = extractId(slotBody);
        String code = extractSlotCode(slotBody);
        seedRun(slotId, "[1]", 60, 1);
        // Slot thứ hai bị xoá TRƯỚC khi đóng — dòng WIPED phải xuống cuối dù đổi trạng thái giữa chừng.
        String slot2Body = mvc.perform(slotRequest(parent, groupId, "Be Xoa2", "123456"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String slot2Id = extractId(slot2Body);
        mvc.perform(delete("/api/slots/" + slot2Id)
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk());

        // TRƯỚC khi đóng: nhóm sống, frozenAt null, ACTIVE trước WIPED.
        mvc.perform(groupReport(parent, groupId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.frozenAt").value(nullValue()))
                .andExpect(jsonPath("$.result.formulaVersion").value(nullValue()))
                .andExpect(jsonPath("$.result.summary.learners").value(2))
                .andExpect(jsonPath("$.result.summary.active").value(1))
                .andExpect(jsonPath("$.result.summary.chaptersCompleted").value(1))
                .andExpect(jsonPath("$.result.rows.length()").value(2))
                .andExpect(jsonPath("$.result.rows[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.result.rows[0].code").value(code))
                .andExpect(jsonPath("$.result.rows[0].indicators.saving").value(60))
                .andExpect(jsonPath("$.result.rows[1].status").value("WIPED"))
                .andExpect(jsonPath("$.result.rows[1].code").value(nullValue()));

        mvc.perform(post("/api/groups/" + groupId + "/close")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk());

        // SAU khi đóng: bản đóng băng, số giữ nguyên, code / status đọc SỐNG; summary.active vẫn 1
        // (đóng băng chạy TRƯỚC archiveActiveSlots); WIPED vẫn xuống cuối.
        mvc.perform(groupReport(parent, groupId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.frozenAt").value(notNullValue()))
                .andExpect(jsonPath("$.result.formulaVersion").value("report-v1"))
                .andExpect(jsonPath("$.result.group.closedAt").value(notNullValue()))
                .andExpect(jsonPath("$.result.summary.learners").value(2))
                .andExpect(jsonPath("$.result.summary.active").value(1))
                .andExpect(jsonPath("$.result.summary.chaptersCompleted").value(1))
                .andExpect(jsonPath("$.result.rows.length()").value(2))
                .andExpect(jsonPath("$.result.rows[0].status").value("ARCHIVED"))
                .andExpect(jsonPath("$.result.rows[0].code").value(code))
                .andExpect(jsonPath("$.result.rows[0].chaptersDone[0]").value(1))
                .andExpect(jsonPath("$.result.rows[0].indicators.saving").value(60))
                .andExpect(jsonPath("$.result.rows[1].status").value("WIPED"))
                .andExpect(jsonPath("$.result.rows[1].code").value(nullValue()))
                .andExpect(jsonPath("$.result.rows[1].displayName").value(nullValue()))
                .andExpect(jsonPath("$.result.rows[1].indicators").value(nullValue()));

        // Sửa RunState trực tiếp (slot ARCHIVED không gửi lô được) → báo cáo đã đóng KHÔNG đổi.
        mutateRun(slotId, "[1,2]", 99);
        mvc.perform(groupReport(parent, groupId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.summary.learners").value(2))
                .andExpect(jsonPath("$.result.summary.chaptersCompleted").value(1))
                .andExpect(jsonPath("$.result.rows[0].chaptersDone.length()").value(1))
                .andExpect(jsonPath("$.result.rows[0].indicators.saving").value(60));
    }

    // ------------------------------------------------------------------ dựng dữ liệu
    /**
     * Bản "1.0.3" của test này, idempotent: đã là current thì qua; đã tồn tại mà chưa current
     * (6001) thì chỉ đưa con trỏ về. Một chương tối giản — hợp đồng chỉ cần CÓ bản để slot
     * report không 1003 và totalChapters có giá trị.
     */
    private String releaseVersion() throws Exception {
        if (version == null) {
            version = ContentTestSupport.publishFresh(mvc, admin(), new ContentTestSupport.Repos(
                    releaseChapterRepo, releaseRepo, draftRepo, chapterRepo), Map.of("CH01", minimalChapter()), "report flow test");
        }
        return version;
    }

    /** Tổng chương bản HIỆN TẠI đọc từ /api/content/current — publish() sao mọi nháp studio vào bản. */
    private int currentChapterCount() throws Exception {
        String body = mvc.perform(get("/api/content/current"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
                .path("result").path("chapters").size();
    }

    /** CH01 tối giản — chỉ để bản có ít nhất một chương (đủ để progress.totalChapters không null). */
    private static String minimalChapter() {
        return """
                {"id":"chapter-1","title":"Chuong 1 cho test report","version":"1.0.0",
                 "initialStats":{"SAVING":50,"HAPPINESS":50,"RISK":30,"GOAL":50,"WEALTH":0},
                 "sprites":{"MOM":{"neutral":"{{ASSET_BASE}}/a/abc.webp"}},
                 "scenes":[
                   {"id":"intro","type":"dialogue","background":"{{ASSET_BASE}}/a/bg1.webp","text":"Chao","nextSceneId":"end"},
                   {"id":"end","type":"ending"}
                 ],
                 "endingRules":{"priorityOrder":["x"],"endings":[]}}
                """;
    }

    /** Ghi RunState bằng repo — số liệu test độc lập với lô chơi HTTP; CHECK 0..100 vẫn phải qua. */
    private void seedRun(String slotId, String chaptersDone, int saving, int attempt) {
        LearnerSlot slot = slotRepo.findById(UUID.fromString(slotId)).orElseThrow();
        RunState run = new RunState();
        run.setSlot(slot);
        run.setChaptersDone(chaptersDone);
        run.setWealth(0);
        run.setSaving(saving);
        run.setHappiness(55);
        run.setRisk(30);
        run.setGoal(50);
        run.setUpdatedAt(Instant.now(FIXED_CLOCK));
        run.setAttemptIndex(attempt);
        runStateRepo.save(run);
    }

    /** Sửa RunState sau khi nhóm đã đóng — kiểm báo cáo đóng băng không bị âm thầm tính lại. */
    private void mutateRun(String slotId, String chaptersDone, int saving) {
        RunState run = runStateRepo.findBySlot_Id(UUID.fromString(slotId)).orElseThrow();
        run.setChaptersDone(chaptersDone);
        run.setSaving(saving);
        runStateRepo.save(run);
    }

    // ------------------------------------------------------------------ người dùng / API
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
        a.setEmail("report-admin-" + System.nanoTime() + "@test.local");
        a.setDisplayName("Admin Report");
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

    private static MockHttpServletRequestBuilder slotReport(String jwt, String slotId) {
        return get("/api/slots/" + slotId + "/report").header("Authorization", "Bearer " + jwt);
    }

    private static MockHttpServletRequestBuilder groupReport(String jwt, String groupId) {
        return get("/api/groups/" + groupId + "/report").header("Authorization", "Bearer " + jwt);
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
