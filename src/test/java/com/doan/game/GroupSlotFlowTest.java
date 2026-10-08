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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ruột POST /api/groups + POST /api/slots đi qua HTTP thật (MockMvc + H2), token THẬT theo
 * khuôn AuthFlowTest: đăng ký → OTP trong MailCatcher → /verify. Cần token thật vì người CHỦ
 * nhóm phải sống qua RevocationAwareJwtDecoder thật — fake JWT chỉ dựng được claim, không kiểm
 * được cửa SCOPE_PARENT ở SecurityConfig bằng đúng token BE tự cấp.
 *
 * Kiểm những gì Hưng chốt 07/10: có PARENT mới mở được FAMILY (không gói → 3005),
 * mỗi tài khoản tối đa 1 nhóm đang mở (5001), mở slot ra mã chữ và login được token SLOT,
 * vượt hạn mức 4 slot (3006), không mở slot vào nhóm người khác (3004),
 * nhóm CLASS phải có consent trước khi mở slot (5003).
 * Đồng hồ đóng băng 10:00 02/10/2026 giờ VN — gói cấp trong test luôn còn hạn.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class GroupSlotFlowTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ClockConfig.VN);
    private static final Pattern OTP_IN_MAIL = Pattern.compile(">(\\d{6})</p>");
    /** Bảng chữ sinh mã: bỏ 0 O 1 I L — trẻ gõ tay nên từng chữ phải đọc được. */
    private static final String SLOT_CODE_REGEX = "[A-HJ-KM-NP-Z2-9]{8}";

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired EntitlementRepository entitlementRepo;
    @Autowired AuthFlowTest.MailCatcher mailCatcher;

    @BeforeEach
    void resetMailbox() {
        mailCatcher.sent.clear();
    }

    // ------------------------------------------------------------------ mở nhóm
    @Test
    void parentOpensFamilyGroup() throws Exception {
        String jwt = parentToken("nhamoi");

        mvc.perform(openGroup(jwt, "Nha test", "FAMILY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result.name").value("Nha test"))
                .andExpect(jsonPath("$.result.context").value("FAMILY"))
                .andExpect(jsonPath("$.result.slotLimit").value(4))
                .andExpect(jsonPath("$.result.slotUsed").value(0))
                .andExpect(jsonPath("$.result.consentConfirmed").value(false));
    }

    @Test
    void withoutPlanCannotOpenGroup() throws Exception {
        String jwt = registerAndVerify("khongoi-" + System.nanoTime() + "@test.local");

        // Dùng lại 3005 PLAN_REQUIRED (Hưng chốt 07/10) — HTTP 402 đúng nghĩa "cần mua gói trước".
        mvc.perform(openGroup(jwt, "Nha test", "FAMILY"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value(3005));
    }

    /** UNIQUE(owner, openContext): còn nhóm cũ đang mở thì không mở thêm — 5001 chứ không phải 500. */
    @Test
    void secondOpenFamilyGroupIsRejected() throws Exception {
        String jwt = parentToken("haiLan");

        mvc.perform(openGroup(jwt, "Nha cu", "FAMILY")).andExpect(status().isOk());
        mvc.perform(openGroup(jwt, "Nha moi", "FAMILY"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5001));
    }

    @Test
    void groupInputValidationRejectsBlankNameAndBadContext() throws Exception {
        String jwt = parentToken("saiqld");

        mvc.perform(openGroup(jwt, "   ", "FAMILY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        mvc.perform(openGroup(jwt, "Nha test", "SCHOOL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    // ------------------------------------------------------------------ mở slot
    @Test
    void openSlotThenChildLogsInWithCodeAndPin() throws Exception {
        String jwt = parentToken("moslot");
        String groupId = openFamilyGroup(jwt);

        String body = openSlot(jwt, groupId, "Be An", "123456")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result.displayName").value("Be An"))
                .andExpect(jsonPath("$.result.status").value("ACTIVE"))
                .andExpect(jsonPath("$.result.locked").value(false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // PIN không bao giờ trở ra: không có plaintext lẫn hash trong response.
        assertThat(body).doesNotContain("123456").doesNotContain("pinHash");

        String code = extractSlotCode(body);
        assertThat(code).matches(SLOT_CODE_REGEX);

        mvc.perform(post("/api/slots/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"pin\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.result.expiresIn").value(8 * 3600))
                // Hồ sơ trẻ trả kèm để FE hiện tên + nhãn gia đình / lớp (token SLOT không gọi được /auth/me)
                .andExpect(jsonPath("$.result.slot.displayName").value("Be An"))
                .andExpect(jsonPath("$.result.slot.groupContext").value("FAMILY"))
                .andExpect(jsonPath("$.result.slot.id").isNotEmpty());
    }

    /** SLOT_LIMIT_REACHED (3006) dùng chung cho cả hai vế hạn mức 4 / 40. */
    @Test
    void fifthSlotIsRejected() throws Exception {
        String jwt = parentToken("slot5");
        String groupId = openFamilyGroup(jwt);

        for (int i = 1; i <= 4; i++) {
            openSlot(jwt, groupId, "Be " + i, "123456").andExpect(status().isOk());
        }
        mvc.perform(slotRequest(jwt, groupId, "Be 5", "123456"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3006));
    }

    @Test
    void anotherParentCannotOpenSlotInMyGroup() throws Exception {
        String owner = parentToken("chunhom");
        String groupId = openFamilyGroup(owner);
        String stranger = parentToken("nguoila");

        mvc.perform(slotRequest(stranger, groupId, "Be la", "123456"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
    }

    @Test
    void slotInputValidationRejectsBadPin() throws Exception {
        String jwt = parentToken("saipin");
        String groupId = openFamilyGroup(jwt);

        mvc.perform(slotRequest(jwt, groupId, "Be", "12345"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    // ------------------------------------------------------------------ consent (CLASS)
    /**
     * Hưng chốt 07/10: nhóm CLASS phải có consentConfirmedAt trước khi mở slot (5003);
     * FAMILY không cần. Consent do CHỦ nhóm bấm — người khác bấm bị 3004.
     */
    @Test
    void classGroupNeedsConsentBeforeOpeningSlot() throws Exception {
        String teacher = teacherToken("classconsent");
        String stranger = teacherToken("nguoila2");
        String groupId = extractId(mvc.perform(openGroup(teacher, "Lop 10A", "CLASS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.slotLimit").value(40))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        mvc.perform(slotRequest(teacher, groupId, "Be 1", "123456"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5003));

        // Không phải chủ nhóm thì không bấm được consent.
        mvc.perform(post("/api/groups/" + groupId + "/consent")
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));

        mvc.perform(post("/api/groups/" + groupId + "/consent")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // Bấm lần hai vẫn 200 (idempotent), và giờ mở slot được.
        mvc.perform(post("/api/groups/" + groupId + "/consent")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk());
        mvc.perform(slotRequest(teacher, groupId, "Be 1", "123456"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ dựng dữ liệu
    /** Tài khoản thật + gói PARENT còn hạn: mở slot qua được cửa SCOPE_PARENT của SecurityConfig. */
    private String parentToken(String prefix) throws Exception {
        String jwt = registerAndVerify(prefix + "-" + System.nanoTime() + "@test.local");
        grant(jwt, PlanKind.PARENT);
        return jwt;
    }

    /** Gói TEACHER: điều kiện mở nhóm CLASS (cửa SCOPE_TEACHER cũng do gói này cấp). */
    private String teacherToken(String prefix) throws Exception {
        String jwt = registerAndVerify(prefix + "-" + System.nanoTime() + "@test.local");
        grant(jwt, PlanKind.TEACHER);
        return jwt;
    }

    private String openFamilyGroup(String jwt) throws Exception {
        String body = mvc.perform(openGroup(jwt, "Nha test", "FAMILY"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return extractId(body);
    }

    private static String extractId(String body) {
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]+)\"").matcher(body);
        assertThat(m.find()).as("response nhom phai co id: " + body).isTrue();
        return m.group(1);
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

    /** Đăng ký rồi xác minh bằng OTP của chính lá mail vừa nhận — trả về token THẬT. */
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
    private static MockHttpServletRequestBuilder openGroup(String jwt, String name, String context) {
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

    private org.springframework.test.web.servlet.ResultActions openSlot(String jwt, String groupId,
                                                                        String displayName, String pin) throws Exception {
        return mvc.perform(slotRequest(jwt, groupId, displayName, pin));
    }

    // ------------------------------------------------------------------ đọc response
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
