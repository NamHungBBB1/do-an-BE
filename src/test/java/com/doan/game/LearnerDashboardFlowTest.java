package com.doan.game;

import com.doan.game.configuration.ClockConfig;
import com.doan.game.entity.Account;
import com.doan.game.entity.Entitlement;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.PlanKind;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.EntitlementRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ruột GET /api/learner/dashboard đi qua HTTP thật (MockMvc + H2), token SLOT THẬT theo khuôn
 * AuthFlowTest: đăng ký chủ nhóm → OTP → mở nhóm → mở slot → POST /api/slots/login.
 *
 * Kiểm contract Hưng duyệt 08/10 (đợt 1): FAMILY trả quizzes = null, CLASS trả object rỗng,
 * token người lớn bị 403/3004, thiếu token 401/3003, slot đã trả → 3009, slot đã xoá → 3007;
 * khối progress / achievements / recentActivities đúng mặc định đợt 1.
 * Đồng hồ đóng băng 10:00 02/10/2026 giờ VN — gói cấp trong test luôn còn hạn.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class LearnerDashboardFlowTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ClockConfig.VN);
    private static final Pattern OTP_IN_MAIL = Pattern.compile(">(\\d{6})</p>");

    @Autowired MockMvc mvc;

    /**
     * totalChapters = số chương của bản phát hành hiện tại (10/10). Các test dùng chung một H2, nên
     * ContentFlowTest có thể đã phát hành trước → hỏi chính API nội dung thay vì đoán null hay 1.
     */
    private Object expectedTotalChapters() throws Exception {
        var res = mvc.perform(get("/api/content/current")).andReturn().getResponse();
        if (res.getStatus() != 200) {
            return null;
        }
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("result").path("chapters").size();
    }
    @Autowired AccountRepository accountRepo;
    @Autowired EntitlementRepository entitlementRepo;
    @Autowired AuthFlowTest.MailCatcher mailCatcher;

    @BeforeEach
    void resetMailbox() {
        mailCatcher.sent.clear();
    }

    // ------------------------------------------------------------------ FAMILY
    /** Đợt 1: learner đầy đủ, mọi khối còn lại mặc định; FAMILY không có quiz (quizzes = null). */
    @Test
    void familyDashboardShowsLearnerAndEmptyBlocks() throws Exception {
        String parent = parentToken("dashfam");
        String groupId = openGroup(parent, "Nha demo", "FAMILY");
        String slotBody = mvc.perform(slotRequest(parent, groupId, "Be An", "123456"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String child = childLogin(slotBody, "123456");

        mvc.perform(get("/api/learner/dashboard").header("Authorization", "Bearer " + child))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result.learner.id").isNotEmpty())
                .andExpect(jsonPath("$.result.learner.displayName").value("Be An"))
                .andExpect(jsonPath("$.result.learner.badge").value("nhi dong"))
                .andExpect(jsonPath("$.result.learner.groupContext").value("FAMILY"))
                .andExpect(jsonPath("$.result.learner.groupName").value("Nha demo"))
                // Đúng mặc định đợt 1 trong bảng "Theo đợt".
                .andExpect(jsonPath("$.result.progress.totalChapters").value(expectedTotalChapters()))
                .andExpect(jsonPath("$.result.progress.completedChapters").value(0))
                .andExpect(jsonPath("$.result.progress.chaptersDone").isEmpty())
                .andExpect(jsonPath("$.result.progress.continueChapter").value(1))
                .andExpect(jsonPath("$.result.progress.lastPlayedAt").value(nullValue()))
                // Quiz chỉ cho CLASS: FAMILY nhận null để FE ẩn hẳn khối quiz.
                .andExpect(jsonPath("$.result.quizzes").value(nullValue()))
                .andExpect(jsonPath("$.result.achievements").isEmpty())
                .andExpect(jsonPath("$.result.recentActivities").isEmpty());
    }

    // ------------------------------------------------------------------ CLASS
    /** CLASS có object quizzes rỗng {pending: [], completed: []} — không phải null. */
    @Test
    void classDashboardHasEmptyQuizLists() throws Exception {
        String teacher = teacherToken("dashclass");
        String groupId = openGroup(teacher, "Lop 10A", "CLASS");
        mvc.perform(post("/api/groups/" + groupId + "/consent")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk());
        String slotBody = mvc.perform(slotRequest(teacher, groupId, "Hoc sinh 1", "123456"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String child = childLogin(slotBody, "123456");

        mvc.perform(get("/api/learner/dashboard").header("Authorization", "Bearer " + child))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.learner.groupContext").value("CLASS"))
                .andExpect(jsonPath("$.result.learner.groupName").value("Lop 10A"))
                .andExpect(jsonPath("$.result.quizzes").isNotEmpty())
                .andExpect(jsonPath("$.result.quizzes.pending").isEmpty())
                .andExpect(jsonPath("$.result.quizzes.completed").isEmpty());
    }

    // ------------------------------------------------------------------ cửa quyền
    /** Token người lớn gọi được dashboard là lỗi lập trình — 403/3004 theo contract. */
    @Test
    void accountTokenIsRejected() throws Exception {
        String parent = parentToken("dashnguolon");

        mvc.perform(get("/api/learner/dashboard").header("Authorization", "Bearer " + parent))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
    }

    @Test
    void missingTokenGets401() throws Exception {
        mvc.perform(get("/api/learner/dashboard"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(3003));
    }

    // ------------------------------------------------------------------ slot hết vòng đời
    /** Slot đã trả (ARCHIVED): token trẻ vẫn sống nhưng service chặn 3009 — cùng luật loginSlot. */
    @Test
    void returnedSlotGets3009() throws Exception {
        String parent = parentToken("dashtra");
        String groupId = openGroup(parent, "Nha tra", "FAMILY");
        String slotBody = mvc.perform(slotRequest(parent, groupId, "Be Tra", "123456"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String child = childLogin(slotBody, "123456");
        String slotId = subject(child);

        mvc.perform(post("/api/slots/" + slotId + "/return")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk());

        mvc.perform(get("/api/learner/dashboard").header("Authorization", "Bearer " + child))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(3009));
    }

    /** Slot đã xoá sạch (WIPED): 3007 (404) — không phải 3009. */
    @Test
    void wipedSlotGets3007() throws Exception {
        String parent = parentToken("dashxoasach");
        String groupId = openGroup(parent, "Nha xoa", "FAMILY");
        String slotBody = mvc.perform(slotRequest(parent, groupId, "Be Xoa", "123456"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String child = childLogin(slotBody, "123456");
        String slotId = subject(child);

        mvc.perform(delete("/api/slots/" + slotId)
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk());

        mvc.perform(get("/api/learner/dashboard").header("Authorization", "Bearer " + child))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3007));
    }

    // ------------------------------------------------------------------ dựng dữ liệu
    private String parentToken(String prefix) throws Exception {
        String jwt = registerAndVerify(prefix + "-" + System.nanoTime() + "@test.local");
        grant(jwt, PlanKind.PARENT);
        return jwt;
    }

    private String teacherToken(String prefix) throws Exception {
        String jwt = registerAndVerify(prefix + "-" + System.nanoTime() + "@test.local");
        grant(jwt, PlanKind.TEACHER);
        return jwt;
    }

    private String openGroup(String jwt, String name, String context) throws Exception {
        String body = mvc.perform(openGroupRequest(jwt, name, context))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return extractId(body);
    }

    /** Trẻ đăng nhập bằng mã in trên giấy + PIN — trả về token SLOT THẬT. */
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

    /** JSON có hai "code": của ApiResponse (số) và mã slot của trẻ (chuỗi 8 ký tự) — bắt bản chuỗi. */
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

    /** Bắt mã OTP GỐC trong lá mail mới nhất — nơi duy nhất nó còn tồn tại sau khi băm BCrypt. */
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
