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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ruột PR 2a — (a) đọc: GET /api/groups, GET /api/groups/{id}/slots, GET /api/groups/{id}/capacity;
 * (b) vòng đời slot: POST /api/slots/{id}/return, DELETE /api/slots/{id}, POST /api/slots/{id}/pin.
 * Đi qua HTTP thật (MockMvc + H2), token THẬT theo khuôn AuthFlowTest / GroupSlotFlowTest.
 *
 * Kiểm những gì Kidz chốt 07/10 cho PR 2: slotUsed = số ACTIVE, chỉ chủ nhóm xem và thao tác
 * được (3004), slot không ACTIVE thì từ chối (5004 — đề xuất 409 của Kidz), trả slot giữ tên
 * nhưng mã hết hiệu lực (3009), xoá sạch bỏ tên / mã / PIN nhưng giữ dòng (3007),
 * đổi PIN reset bộ đếm sai và thời khoá. Đồng hồ đóng băng 10:00 02/10/2026 giờ VN.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class SlotLifecycleFlowTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ClockConfig.VN);
    private static final Pattern OTP_IN_MAIL = Pattern.compile(">(\\d{6})</p>");

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired EntitlementRepository entitlementRepo;
    @Autowired AuthFlowTest.MailCatcher mailCatcher;

    @BeforeEach
    void resetMailbox() {
        mailCatcher.sent.clear();
    }

    // ------------------------------------------------------------------ (a) đọc
    @Test
    void getMyGroupsShowsSlotUsedFromActiveSlots() throws Exception {
        String jwt = parentToken("docnhom");
        String groupId = openFamilyGroup(jwt);
        openSlot(jwt, groupId, "Be 1", "123456");
        openSlot(jwt, groupId, "Be 2", "123456");

        mvc.perform(get("/api/groups").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result.length()").value(1))
                .andExpect(jsonPath("$.result[0].id").value(groupId))
                .andExpect(jsonPath("$.result[0].slotLimit").value(4))
                .andExpect(jsonPath("$.result[0].slotUsed").value(2));
    }

    @Test
    void slotsAndCapacityAreOwnerOnly() throws Exception {
        String owner = parentToken("docslot");
        String groupId = openFamilyGroup(owner);
        openSlot(owner, groupId, "Be 1", "123456");
        openSlot(owner, groupId, "Be 2", "123456");
        String stranger = parentToken("docloai");

        mvc.perform(get("/api/groups/" + groupId + "/slots").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result.length()").value(2))
                .andExpect(jsonPath("$.result[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.result[0].locked").value(false));
        mvc.perform(get("/api/groups/" + groupId + "/capacity").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(2));

        mvc.perform(get("/api/groups/" + groupId + "/slots").header("Authorization", "Bearer " + stranger))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
        mvc.perform(get("/api/groups/" + groupId + "/capacity").header("Authorization", "Bearer " + stranger))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
    }

    @Test
    void readUnknownGroupReturns404() throws Exception {
        String jwt = parentToken("docla");
        String missing = UUID.randomUUID().toString();

        mvc.perform(get("/api/groups/" + missing + "/slots").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1003));
        mvc.perform(get("/api/groups/" + missing + "/capacity").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1003));
    }

    // ------------------------------------------------------------------ trả slot
    /** Trả slot: giữ tên, mã hết hiệu lực (3009 khi trẻ login lại), trả lại chỗ cho hạn mức. */
    @Test
    void returnSlotArchivesKeepsCodeDeadAndFreesCapacity() throws Exception {
        String jwt = parentToken("tralot");
        String groupId = openFamilyGroup(jwt);
        for (int i = 1; i <= 4; i++) {
            openSlot(jwt, groupId, "Be " + i, "123456");
        }
        // Hết chỗ: slot thứ 5 bị 3006.
        mvc.perform(slotRequest(jwt, groupId, "Be 5", "123456"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3006));

        String first = firstSlotOf(jwt, groupId);
        String oldCode = codeOfSlot(jwt, groupId, first);

        mvc.perform(post("/api/slots/" + first + "/return").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // Trẻ dùng lại mã cũ: 3009 (đã có sẵn từ trước, loginSlot không đổi).
        mvc.perform(post("/api/slots/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + oldCode + "\",\"pin\":\"123456\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(3009));

        // Trả lại chỗ: mở slot thứ 5 giờ qua được; capacity còn 0.
        openSlot(jwt, groupId, "Be 5", "123456").andExpect(status().isOk());
        mvc.perform(get("/api/groups/" + groupId + "/capacity").header("Authorization", "Bearer " + jwt))
                .andExpect(jsonPath("$.result").value(0));

        // Trả lần hai: slot không còn ACTIVE → 5004.
        mvc.perform(post("/api/slots/" + first + "/return").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5004));
    }

    @Test
    void strangerCannotReturnOrWipeOrChangePinMySlot() throws Exception {
        String owner = parentToken("chuslot");
        String groupId = openFamilyGroup(owner);
        openSlot(owner, groupId, "Be 1", "123456");
        String slotId = firstSlotOf(owner, groupId);
        String stranger = parentToken("nguoila3");

        mvc.perform(post("/api/slots/" + slotId + "/return").header("Authorization", "Bearer " + stranger))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
        mvc.perform(delete("/api/slots/" + slotId).header("Authorization", "Bearer " + stranger))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
        mvc.perform(post("/api/slots/" + slotId + "/pin")
                        .header("Authorization", "Bearer " + stranger)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"654321\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
    }

    // ------------------------------------------------------------------ xoá sạch
    /** WIPED: bỏ tên, mã, PIN — trẻ login 3007, dòng vẫn còn trong danh sách. */
    @Test
    void wipeSlotRemovesIdentityButKeepsRow() throws Exception {
        String jwt = parentToken("xoasach");
        String groupId = openFamilyGroup(jwt);
        openSlot(jwt, groupId, "Be An", "123456");
        String slotId = firstSlotOf(jwt, groupId);
        String oldCode = codeOfSlot(jwt, groupId, slotId);

        mvc.perform(delete("/api/slots/" + slotId).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // Mã không còn là của ai: 3007 (khác 3009 của slot ARCHIVED).
        mvc.perform(post("/api/slots/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + oldCode + "\",\"pin\":\"123456\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3007));

        // Dòng vẫn giữ cho báo cáo cũ, nhưng tên và mã đã về null, hết ACTIVE nên không ăn chỗ.
        mvc.perform(get("/api/groups/" + groupId + "/slots").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(1))
                .andExpect(jsonPath("$.result[0].status").value("WIPED"))
                .andExpect(jsonPath("$.result[0].displayName").doesNotExist())
                .andExpect(jsonPath("$.result[0].code").doesNotExist());
        mvc.perform(get("/api/groups/" + groupId + "/capacity").header("Authorization", "Bearer " + jwt))
                .andExpect(jsonPath("$.result").value(4));

        // Xoá lần hai: hết ACTIVE → 5004.
        mvc.perform(delete("/api/slots/" + slotId).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5004));
    }

    // ------------------------------------------------------------------ đổi PIN
    /** Đổi PIN: PIN mới vào được, PIN cũ chết, gỡ thời khoá đang treo. */
    @Test
    void changePinReplacesPinAndClearsLock() throws Exception {
        String jwt = parentToken("doipin");
        String groupId = openFamilyGroup(jwt);
        openSlot(jwt, groupId, "Be An", "123456");
        String slotId = firstSlotOf(jwt, groupId);
        String code = codeOfSlot(jwt, groupId, slotId);

        // Sai PIN 5 lần → khoá 15 phút (3008), đúng PIN trong lúc khoá cũng 3008.
        for (int i = 0; i < 5; i++) {
            loginExpectUnauthorized(code, "000000");
        }
        mvc.perform(post("/api/slots/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"pin\":\"123456\"}"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value(3008));

        mvc.perform(post("/api/slots/" + slotId + "/pin")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"654321\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // Gỡ khoá: PIN mới vào được ngay, PIN cũ chết.
        mvc.perform(post("/api/slots/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"pin\":\"654321\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.accessToken").isNotEmpty());
        loginExpectUnauthorized(code, "123456");

        // PIN không hợp lệ và slot không ACTIVE.
        mvc.perform(post("/api/slots/" + slotId + "/pin")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        mvc.perform(post("/api/slots/" + slotId + "/return").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
        mvc.perform(post("/api/slots/" + slotId + "/pin")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"111111\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5004));
    }

    // ------------------------------------------------------------------ dựng dữ liệu
    /** Tài khoản thật + gói PARENT còn hạn: qua được cửa SCOPE_PARENT của SecurityConfig. */
    private String parentToken(String prefix) throws Exception {
        String jwt = registerAndVerify(prefix + "-" + System.nanoTime() + "@test.local");
        grant(jwt, PlanKind.PARENT);
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

    /** Slot đầu tiên (theo createdAt) của nhóm — thứ tự danh sách BE trả. */
    private String firstSlotOf(String jwt, String groupId) throws Exception {
        String body = mvc.perform(get("/api/groups/" + groupId + "/slots")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]+)\"").matcher(body);
        assertThat(m.find()).as("danh sach slot phai co id: " + body).isTrue();
        return m.group(1);
    }

    private String codeOfSlot(String jwt, String groupId, String slotId) throws Exception {
        String body = mvc.perform(get("/api/groups/" + groupId + "/slots")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Matcher m = Pattern.compile(Pattern.quote("\"id\":\"" + slotId + "\"")
                + ".*?\"code\":\"([A-Z0-9]+)\"", Pattern.DOTALL).matcher(body);
        assertThat(m.find()).as("slot " + slotId + " phai co code: " + body).isTrue();
        return m.group(1);
    }

    private void loginExpectUnauthorized(String code, String pin) throws Exception {
        mvc.perform(post("/api/slots/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"pin\":\"" + pin + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(3002));
    }

    // ------------------------------------------------------------------ đọc response
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
