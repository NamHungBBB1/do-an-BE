package com.doan.game;

import com.doan.game.configuration.ClockConfig;
import com.doan.game.entity.Account;
import com.doan.game.entity.Entitlement;
import com.doan.game.entity.SlotAddon;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.PlanKind;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.SlotAddonRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ví slot theo tài khoản (giảng viên chốt 17/11/2026): Parent 4 (+2/pack, trần 10), Teacher 40
 * (+20/pack, trần 200), mỗi kỳ gói reset một lần; slot đã mở là tiêu hao, trả/xoá/đóng lớp không
 * hoàn lượt. Đi qua HTTP thật (MockMvc + H2), token THẬT theo khuôn GroupSlotFlowTest.
 *
 * Đồng hồ đóng băng 10:00 02/10/2026 giờ VN để gói và add-on trong test luôn còn hạn.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class SlotQuotaFlowTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ClockConfig.VN);
    private static final Pattern OTP_IN_MAIL = Pattern.compile(">(\\d{6})</p>");

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired EntitlementRepository entitlementRepo;
    @Autowired SlotAddonRepository addonRepo;
    @Autowired AuthFlowTest.MailCatcher mailCatcher;

    @BeforeEach
    void resetMailbox() {
        mailCatcher.sent.clear();
    }

    // ------------------------------------------------------------------ ví phụ huynh
    @Test
    void parentStartsWithFourSlots() throws Exception {
        String jwt = parentToken("vi4");

        mvc.perform(get("/api/me/quota").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(1))
                .andExpect(jsonPath("$.result[0].context").value("FAMILY"))
                .andExpect(jsonPath("$.result[0].plan").value("PARENT"))
                .andExpect(jsonPath("$.result[0].base").value(4))
                .andExpect(jsonPath("$.result[0].extra").value(0))
                .andExpect(jsonPath("$.result[0].limit").value(4))
                .andExpect(jsonPath("$.result[0].used").value(0))
                .andExpect(jsonPath("$.result[0].remaining").value(4))
                .andExpect(jsonPath("$.result[0].termStart").isNotEmpty())
                .andExpect(jsonPath("$.result[0].termEnd").isNotEmpty());
    }

    /** Mua thêm 2 slot: ví lên 6, slot thứ 5 và 6 mở được, thứ 7 vẫn 3006. */
    @Test
    void boughtAddonRaisesTheParentQuota() throws Exception {
        String jwt = parentToken("vi6");
        addon(jwt, PlanKind.PARENT, 2);

        String groupId = openFamilyGroup(jwt);
        for (int i = 1; i <= 6; i++) {
            openSlot(jwt, groupId, "Be " + i).andExpect(status().isOk());
        }
        mvc.perform(slotRequest(jwt, groupId, "Be 7"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3006));

        mvc.perform(get("/api/me/quota").header("Authorization", "Bearer " + jwt))
                .andExpect(jsonPath("$.result[0].extra").value(2))
                .andExpect(jsonPath("$.result[0].limit").value(6))
                .andExpect(jsonPath("$.result[0].used").value(6))
                .andExpect(jsonPath("$.result[0].remaining").value(0));
    }

    /** Add-on hết hạn cùng gói: dòng đã quá hạn không cộng vào ví. */
    @Test
    void expiredAddonDoesNotCount() throws Exception {
        String jwt = parentToken("vihethan");
        UUID accountId = UUID.fromString(subject(jwt));

        SlotAddon expired = new SlotAddon();
        expired.setAccount(accountRepo.findById(accountId).orElseThrow());
        expired.setKind(PlanKind.PARENT);
        expired.setSlots(4);
        expired.setExpiresOn(LocalDate.now(FIXED_CLOCK).minusDays(1));
        expired.setCreatedAt(Instant.now(FIXED_CLOCK));
        addonRepo.save(expired);

        mvc.perform(get("/api/me/quota").header("Authorization", "Bearer " + jwt))
                .andExpect(jsonPath("$.result[0].extra").value(0))
                .andExpect(jsonPath("$.result[0].limit").value(4));
    }

    // ------------------------------------------------------------------ ví giáo viên: nhiều lớp
    /**
     * Giáo viên mở nhiều lớp, các lớp CHIA NHAU một ví 40 slot: 30 + 10 vừa hết ví thì lớp thứ ba
     * không mở được (3006) và lớp đã đầy trả 3026 chứ không phải 3006.
     */
    @Test
    void teacherSharesOneQuotaAcrossManyClasses() throws Exception {
        String jwt = teacherToken("nhieulop");
        String classA = openClass(jwt, "Lop 10A", 30);
        String classB = openClass(jwt, "Lop 10B", 10);

        mvc.perform(bulk(jwt, classA, 30, 0)).andExpect(status().isOk());
        mvc.perform(bulk(jwt, classB, 10, 100)).andExpect(status().isOk());

        // Lớp B đầy sĩ số: 3026 (ví còn 0 nhưng lỗi cụ thể hơn cho FE).
        mvc.perform(slotRequest(jwt, classB, "Hoc sinh B11"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3026));
        // Mở lớp thứ ba khi ví đã cạn: 3006.
        mvc.perform(openGroupJson(jwt, "Lop 10C", "CLASS", 5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3006));

        mvc.perform(get("/api/me/quota").header("Authorization", "Bearer " + jwt))
                .andExpect(jsonPath("$.result[0].context").value("CLASS"))
                .andExpect(jsonPath("$.result[0].base").value(40))
                .andExpect(jsonPath("$.result[0].used").value(40))
                .andExpect(jsonPath("$.result[0].remaining").value(0));
    }

    /** Sĩ số lớp: không vượt ví, không nhỏ hơn số slot đang mở. */
    @Test
    void plannedSizeCanBeRaisedButNotBeyondTheQuota() throws Exception {
        String jwt = teacherToken("siso");
        String groupId = openClass(jwt, "Lop 20", 20);
        mvc.perform(post("/api/groups/" + groupId + "/consent").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
        mvc.perform(bulk(jwt, groupId, 3, 0)).andExpect(status().isOk());

        // Hạ xuống dưới 3 slot đang mở: 1001.
        mvc.perform(patchSize(jwt, groupId, 2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        // Vượt ví: ACTIVE 3 + còn lại 37 = 40, xin 41 → 3006.
        mvc.perform(patchSize(jwt, groupId, 41))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3006));
        // Hợp lệ: 25.
        mvc.perform(patchSize(jwt, groupId, 25))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.slotLimit").value(25))
                .andExpect(jsonPath("$.result.slotUsed").value(3));
        // FAMILY không có sĩ số: 1001.
        String parentJwt = parentToken("sisogiadinh");
        String familyId = openFamilyGroup(parentJwt);
        mvc.perform(patchSize(parentJwt, familyId, 3))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    /** Đóng lớp KHÔNG trả lượt: slot đã mở vẫn tính vào ví của kỳ. */
    @Test
    void closingAClassDoesNotRefundQuota() throws Exception {
        String jwt = teacherToken("donglop");
        String groupId = openClass(jwt, "Lop dong", 10);
        mvc.perform(post("/api/groups/" + groupId + "/consent").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
        mvc.perform(bulk(jwt, groupId, 4, 0)).andExpect(status().isOk());

        mvc.perform(post("/api/groups/" + groupId + "/close").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());

        mvc.perform(get("/api/me/quota").header("Authorization", "Bearer " + jwt))
                .andExpect(jsonPath("$.result[0].used").value(4))
                .andExpect(jsonPath("$.result[0].remaining").value(36));
        // Mở lớp mới chỉ còn 36 chỗ: xin 37 → 3006, xin 36 → được.
        mvc.perform(openGroupJson(jwt, "Lop moi qua", "CLASS", 37))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3006));
        openClass(jwt, "Lop moi", 36);
    }

    /** FAMILY vẫn độc quyền: một nhóm đang mở thì nhóm thứ hai 5001, không phải 500. */
    @Test
    void secondFamilyGroupIsStillRejected() throws Exception {
        String jwt = parentToken("giahai");
        openFamilyGroup(jwt);

        mvc.perform(openGroupJson(jwt, "Nha hai", "FAMILY", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5001));
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

    private String openFamilyGroup(String jwt) throws Exception {
        String body = mvc.perform(openGroupJson(jwt, "Nha test", "FAMILY", null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return extractId(body);
    }

    private String openClass(String jwt, String name, int plannedSize) throws Exception {
        String body = mvc.perform(openGroupJson(jwt, name, "CLASS", plannedSize))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String groupId = extractId(body);
        // Lớp phải xác nhận đồng ý phụ huynh trước khi mở slot (5003) — mở lớp xong bấm luôn trong test.
        mvc.perform(post("/api/groups/" + groupId + "/consent").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
        return groupId;
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

    private void addon(String jwt, PlanKind kind, int slots) {
        SlotAddon a = new SlotAddon();
        a.setAccount(accountRepo.findById(UUID.fromString(subject(jwt))).orElseThrow());
        a.setKind(kind);
        a.setSlots(slots);
        a.setExpiresOn(LocalDate.now(FIXED_CLOCK).plusMonths(2));
        a.setCreatedAt(Instant.now(FIXED_CLOCK));
        addonRepo.save(a);
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
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ gọi API
    private static MockHttpServletRequestBuilder openGroupJson(String jwt, String name, String context,
                                                               Integer plannedSize) {
        String size = plannedSize == null ? "" : ",\"plannedSize\":" + plannedSize;
        return post("/api/groups")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"context\":\"" + context + "\"" + size + "}");
    }

    private static MockHttpServletRequestBuilder slotRequest(String jwt, String groupId, String displayName) {
        return post("/api/slots")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":\"" + groupId + "\",\"displayName\":\"" + displayName
                        + "\",\"pin\":\"123456\",\"badge\":\"nhi dong\"}");
    }

    private org.springframework.test.web.servlet.ResultActions openSlot(String jwt, String groupId,
                                                                        String displayName) throws Exception {
        return mvc.perform(slotRequest(jwt, groupId, displayName));
    }

    private MockHttpServletRequestBuilder bulk(String jwt, String groupId, int count, int offset) {
        StringBuilder slots = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                slots.append(',');
            }
            slots.append("{\"groupId\":\"").append(groupId).append("\",\"displayName\":\"Hoc sinh ")
                    .append(offset + i).append("\",\"pin\":\"123456\"}");
        }
        return post("/api/slots/bulk")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slots\":[" + slots + "]}");
    }

    private static MockHttpServletRequestBuilder patchSize(String jwt, String groupId, int plannedSize) {
        return patch("/api/groups/" + groupId)
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plannedSize\":" + plannedSize + "}");
    }

    // ------------------------------------------------------------------ đọc response
    private static String extractId(String body) {
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]+)\"").matcher(body);
        assertThat(m.find()).as("response nhom phai co id: " + body).isTrue();
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
