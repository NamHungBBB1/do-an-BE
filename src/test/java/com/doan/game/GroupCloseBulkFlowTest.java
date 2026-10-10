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
import java.util.List;
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
 * Ruột PR 2b — (c) POST /api/groups/{id}/close và (d) POST /api/slots/bulk, cộng 3 việc Hưng
 * chốt thêm 07/10: không có API khôi phục nhóm, openSlot kiểm gói đúng loại (3005),
 * DELETE slot nhận cả ARCHIVED. Đi qua HTTP thật (MockMvc + H2), token THẬT theo khuôn
 * GroupSlotFlowTest. Đồng hồ đóng băng 10:00 02/10/2026 giờ VN.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class GroupCloseBulkFlowTest {

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

    // ------------------------------------------------------------------ (c) đóng nhóm
    /**
     * Đóng nhóm: slot ACTIVE → ARCHIVED (trẻ login lại 3009), openContext = NULL nên mở được
     * nhóm mới, GET /api/groups xếp nhóm MỚI mở trước nhóm đã đóng, slotUsed nhóm đóng về 0.
     * Đóng lần hai → 5002. Không có API khôi phục — ghi trong PR.
     */
    @Test
    void closeGroupArchivesSlotsAndFreesContext() throws Exception {
        String jwt = parentToken("dongnhom");
        String closedId = openFamilyGroup(jwt);
        openSlot(jwt, closedId, "Be 1", "123456");
        String slotId = firstSlotOf(jwt, closedId);
        String oldCode = codeOfSlot(jwt, closedId, slotId);

        mvc.perform(post("/api/groups/" + closedId + "/close").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // Slot đã ARCHIVED: trẻ dùng lại mã được 3009, danh sách báo ARCHIVED.
        mvc.perform(post("/api/slots/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + oldCode + "\",\"pin\":\"123456\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(3009));
        mvc.perform(get("/api/groups/" + closedId + "/slots").header("Authorization", "Bearer " + jwt))
                .andExpect(jsonPath("$.result[0].status").value("ARCHIVED"));

        // Trả chỗ UNIQUE: mở được nhóm FAMILY mới; danh sách xếp MỚI mở đứng TRƯỚC nhóm đã đóng.
        String newId = openFamilyGroup(jwt);
        mvc.perform(get("/api/groups").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(2))
                .andExpect(jsonPath("$.result[0].id").value(newId))
                .andExpect(jsonPath("$.result[1].id").value(closedId))
                .andExpect(jsonPath("$.result[1].slotUsed").value(0))
                .andExpect(jsonPath("$.result[0].closedAt").doesNotExist())
                .andExpect(jsonPath("$.result[1].closedAt").isNotEmpty());

        // Đóng lần hai: 5002 (ghi vào PR hỏi lại nếu Hưng muốn idempotent).
        mvc.perform(post("/api/groups/" + closedId + "/close").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5002));
    }

    @Test
    void closeGroupIsOwnerOnly() throws Exception {
        String owner = parentToken("chudong");
        String groupId = openFamilyGroup(owner);
        String stranger = parentToken("nguoiladong");

        mvc.perform(post("/api/groups/" + groupId + "/close").header("Authorization", "Bearer " + stranger))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
        mvc.perform(post("/api/groups/" + UUID.randomUUID() + "/close")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1003));
    }

    // ------------------------------------------------------------------ (d) mở hàng loạt
    @Test
    void bulkOpensWholeLotInOneCall() throws Exception {
        String jwt = parentToken("lot4");
        String groupId = openFamilyGroup(jwt);

        mvc.perform(bulkRequest(jwt, groupId, 4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result.length()").value(4))
                .andExpect(jsonPath("$.result[0].status").value("ACTIVE"));
        mvc.perform(get("/api/groups/" + groupId + "/capacity").header("Authorization", "Bearer " + jwt))
                .andExpect(jsonPath("$.result").value(0));
    }

    /** Vượt hạn mức: TỪ CHỐI CẢ LÔ (3006) — không một slot nào được tạo. */
    @Test
    void bulkOverLimitRejectsWholeLot() throws Exception {
        String jwt = parentToken("lot5");
        String groupId = openFamilyGroup(jwt);

        mvc.perform(bulkRequest(jwt, groupId, 5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3006));
        mvc.perform(get("/api/groups/" + groupId + "/slots").header("Authorization", "Bearer " + jwt))
                .andExpect(jsonPath("$.result.length()").value(0));
        mvc.perform(get("/api/groups/" + groupId + "/capacity").header("Authorization", "Bearer " + jwt))
                .andExpect(jsonPath("$.result").value(4));
    }

    @Test
    void bulkRejectsMixedGroupAndEmptyBody() throws Exception {
        String jwt = parentToken("lotla");
        String groupId = openFamilyGroup(jwt);

        // Hai groupId khác nhau: 1001 nổ TRƯỚC khi tra nhóm nào nên không cần nhóm thứ hai thật.
        mvc.perform(post("/api/slots/bulk")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slots\":[{\"groupId\":\"" + groupId
                                + "\",\"displayName\":\"Be 1\",\"pin\":\"123456\"},"
                                + "{\"groupId\":\"" + UUID.randomUUID()
                                + "\",\"displayName\":\"Be 2\",\"pin\":\"123456\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        mvc.perform(post("/api/slots/bulk")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slots\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void bulkInSomeoneElsesGroupIsForbidden() throws Exception {
        String owner = parentToken("lotchuhom");
        String groupId = openFamilyGroup(owner);
        String stranger = parentToken("lotnguoila");

        mvc.perform(bulkRequest(stranger, groupId, 2))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
    }

    @Test
    void classGroupBulkNeedsConsent() throws Exception {
        String teacher = teacherToken("lotconsent");
        String body = mvc.perform(openGroup(teacher, "Lop 10B", "CLASS"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        mvc.perform(bulkRequest(teacher, extractId(body), 2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5003));
    }

    // ------------------------------------------------------------------ kiểm gói khi mở slot
    /**
     * Hưng chốt 07/10: mở slot kiểm gói đúng loại, thiếu thì 3005.
     *
     * Chọn tài khoản có CẢ HAI gói rồi hết hạn riêng PARENT: nếu hết cả hai thì SCOPE_PARENT
     * bị RevocationAwareJwtDecoder thu hồi ngay và SecurityConfig chặn bằng 403 TRƯỚC khi vào
     * service — 3005 chỉ thấy được khi người gọi còn qua được cửa scope (còn TEACHER) nhưng thiếu
     * đúng loại gói mà nhóm cần. Lưới chặn hai lớp, ghi rõ trong PR.
     */
    @Test
    void expiredPlanCannotOpenNewSlot() throws Exception {
        String jwt = registerAndVerify("hethang-" + System.nanoTime() + "@test.local");
        grant(jwt, PlanKind.PARENT);
        grant(jwt, PlanKind.TEACHER);
        String groupId = openFamilyGroup(jwt);
        openSlot(jwt, groupId, "Be cu", "123456").andExpect(status().isOk());

        expirePlan(UUID.fromString(subject(jwt)), PlanKind.PARENT);

        mvc.perform(slotRequest(jwt, groupId, "Be moi", "123456"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value(3005));
    }

    // ------------------------------------------------------------------ xoá slot đã trả
    /** Hưng chốt 07/10: ARCHIVED xoá được (WIPED thì vẫn 5004). */
    @Test
    void archivedSlotCanBeWiped() throws Exception {
        String jwt = parentToken("xoatralai");
        String groupId = openFamilyGroup(jwt);
        openSlot(jwt, groupId, "Be An", "123456");
        String slotId = firstSlotOf(jwt, groupId);

        mvc.perform(post("/api/slots/" + slotId + "/return").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/slots/" + slotId).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mvc.perform(get("/api/groups/" + groupId + "/slots").header("Authorization", "Bearer " + jwt))
                .andExpect(jsonPath("$.result[0].status").value("WIPED"));
        // Đã WIPED: xoá lần nữa bị 5004.
        mvc.perform(delete("/api/slots/" + slotId).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5004));
    }

    // ------------------------------------------------------------------ hết gói vẫn giữ / xoá được (G-01, 10/10)
    /**
     * Chốt 07/10: hết hạn giữ dữ liệu cũ; WIPED là quyền xoá của phụ huynh. Trước 10/10 cửa
     * SecurityConfig đòi SCOPE_PARENT|TEACHER cho mọi /api/slots/** nên hết cả hai gói là 403 — trả,
     * đổi PIN, xoá đều không làm được. Giờ chỉ MỞ slot mới đòi gói.
     */
    @Test
    void expiredPlanCanStillReturnChangePinAndWipe() throws Exception {
        String jwt = registerAndVerify("hetgoi-" + System.nanoTime() + "@test.local");
        grant(jwt, PlanKind.PARENT);
        String groupId = openFamilyGroup(jwt);
        openSlot(jwt, groupId, "Be cu", "123456").andExpect(status().isOk());
        String slotId = firstSlotOf(jwt, groupId);

        expirePlan(UUID.fromString(subject(jwt)), PlanKind.PARENT);

        mvc.perform(slotRequest(jwt, groupId, "Be moi", "123456"))   // mở thêm: cửa scope chặn
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
        mvc.perform(post("/api/slots/" + slotId + "/pin").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"654321\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/slots/" + slotId + "/return").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/slots/" + slotId).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    /** G-05: nhóm đã đóng không mở thêm được (5002) thì chỗ trống phải là 0, không phải 4/40. */
    @Test
    void closedGroupHasZeroCapacity() throws Exception {
        String jwt = parentToken("dongnhom-cap");
        String groupId = openFamilyGroup(jwt);
        mvc.perform(get("/api/groups/" + groupId + "/capacity").header("Authorization", "Bearer " + jwt))
                .andExpect(jsonPath("$.result").value(4));
        mvc.perform(post("/api/groups/" + groupId + "/close").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
        mvc.perform(get("/api/groups/" + groupId + "/capacity").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(0));
    }

    // ------------------------------------------------------------------ dựng dữ liệu
    /** Tài khoản thật + gói PARENT còn hạn: qua được cửa SCOPE_PARENT của SecurityConfig. */
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
        savePlan(UUID.fromString(subject(jwt)), kind,
                LocalDate.now(FIXED_CLOCK).minusDays(1), LocalDate.now(FIXED_CLOCK).plusMonths(3));
    }

    /** Mô phỏng gói hết hạn: lùi expiresOn của đúng loại đó về hôm qua — giữ dòng để truy vết. */
    private void expirePlan(UUID accountId, PlanKind kind) {
        for (Entitlement e : entitlementRepo.findByAccount_IdOrderByExpiresOnDescCreatedAtDesc(accountId)) {
            if (e.getKind() == kind) {
                e.setExpiresOn(LocalDate.now(FIXED_CLOCK).minusDays(1));
                entitlementRepo.save(e);
            }
        }
    }

    private void savePlan(UUID accountId, PlanKind kind, LocalDate startsOn, LocalDate expiresOn) {
        Account a = accountRepo.findById(accountId).orElseThrow();
        Entitlement e = new Entitlement();
        e.setAccount(a);
        e.setKind(kind);
        e.setStartsOn(startsOn);
        e.setExpiresOn(expiresOn);
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

    /** Body bulk gồm {@code count} phần tử cùng một groupId. */
    private static MockHttpServletRequestBuilder bulkRequest(String jwt, String groupId, int count) {
        StringBuilder slots = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                slots.append(',');
            }
            slots.append("{\"groupId\":\"").append(groupId)
                    .append("\",\"displayName\":\"Be ").append(i)
                    .append("\",\"pin\":\"123456\"}");
        }
        return post("/api/slots/bulk")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slots\":[" + slots + "]}");
    }

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
