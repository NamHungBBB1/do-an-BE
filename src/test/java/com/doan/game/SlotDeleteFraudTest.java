package com.doan.game;

import com.doan.game.configuration.ClockConfig;
import com.doan.game.entity.Account;
import com.doan.game.entity.Entitlement;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.PlanKind;
import com.doan.game.enums.SlotStatus;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.LearnerSlotRepository;
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
 * Chống gian lận quanh việc xoá slot (spec slot-quota v3, §9 + §11 ca 4, 5, 7, 8, 21):
 *
 *  - xoá slot là ẩn danh hoá thật, GIỮ `code` trong DB (mã không cấp lại) nhưng API không trả;
 *  - token trẻ đang cầm chết ngay ở decoder (401), login lần hai cũng làm token trước chết;
 *  - xoá KHÔNG hoàn lượt: `used` giữ nguyên và báo cáo nhóm vẫn có dòng (nhãn "Học sinh đã xoá"
 *    do FE vẽ từ displayName = null).
 *
 * Đi qua HTTP thật (MockMvc + H2), token THẬT. Đồng hồ đóng băng 10:00 02/10/2026 giờ VN.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class SlotDeleteFraudTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ClockConfig.VN);
    private static final Pattern OTP_IN_MAIL = Pattern.compile(">(\\d{6})</p>");

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired EntitlementRepository entitlementRepo;
    @Autowired LearnerSlotRepository slotRepo;
    @Autowired AuthFlowTest.MailCatcher mailCatcher;

    @BeforeEach
    void resetMailbox() {
        mailCatcher.sent.clear();
    }

    /** Q14: xoá = bỏ tên / PIN / cá nhân hoá / badge, giữ code + dòng; token trẻ đang cầm 401. */
    @Test
    void wipeAnonymisesKeepsCodeAndKillsChildToken() throws Exception {
        String jwt = parentToken("xoaan");
        String groupId = openFamilyGroup(jwt);
        String slotBody = mvc.perform(slotRequest(jwt, groupId, "Be An", "123456"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String slotId = extractId(slotBody);
        String code = extractSlotCode(slotBody);
        String child = childLogin(code, "123456");

        LearnerSlot before = slotRepo.findById(UUID.fromString(slotId)).orElseThrow();
        before.setPersonalization("{\"gender\":\"FEMALE\"}");
        slotRepo.save(before);

        mvc.perform(delete("/api/slots/" + slotId).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());

        // Ẩn danh thật, nhưng code vẫn nằm trong DB để không cấp lại cho bé khác.
        LearnerSlot after = slotRepo.findByCode(code).orElseThrow();
        assertThat(after.getId()).isEqualTo(UUID.fromString(slotId));
        assertThat(after.getStatus()).isEqualTo(SlotStatus.WIPED);
        assertThat(after.getCode()).isEqualTo(code);
        assertThat(after.getDisplayName()).isNull();
        assertThat(after.getPinHash()).isNull();
        assertThat(after.getBadge()).isNull();
        assertThat(after.getPersonalization()).isNull();
        assertThat(after.getWipedAt()).isNotNull();
        assertThat(after.getTokenVersion()).isGreaterThanOrEqualTo(1);

        // Token đang cầm: chết ngay (401), không đợi hết 8 giờ.
        mvc.perform(get("/api/learner/dashboard").header("Authorization", "Bearer " + child))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(3003));

        // Mã cũ không còn là của ai: login 3007, không phải 3009 (ARCHIVED).
        mvc.perform(post("/api/slots/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"pin\":\"123456\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3007));
    }

    /** Ca 8: login lần hai cùng slot — token của lượt trước 401 (một slot không chia cho nhiều em). */
    @Test
    void secondLoginKillsTheFirstToken() throws Exception {
        String jwt = parentToken("dangnhap2");
        String groupId = openFamilyGroup(jwt);
        String slotBody = mvc.perform(slotRequest(jwt, groupId, "Be Hai", "123456"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String code = extractSlotCode(slotBody);

        String first = childLogin(code, "123456");
        String second = childLogin(code, "123456");

        mvc.perform(get("/api/learner/dashboard").header("Authorization", "Bearer " + first))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(3003));
        mvc.perform(get("/api/learner/dashboard").header("Authorization", "Bearer " + second))
                .andExpect(status().isOk());
    }

    /** Ca 21: slot đã xoá vẫn tính vào `used`; báo cáo nhóm vẫn có dòng, chỉ mất danh tính. */
    @Test
    void wipedSlotStillCountsAndStaysInTheReport() throws Exception {
        String jwt = parentToken("xoatinh");
        String groupId = openFamilyGroup(jwt);
        for (int i = 1; i <= 4; i++) {
            mvc.perform(slotRequest(jwt, groupId, "Be " + i, "123456"))
                    .andExpect(status().isOk());
        }
        String first = firstSlotOf(jwt, groupId);

        mvc.perform(delete("/api/slots/" + first).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());

        mvc.perform(get("/api/me/quota").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].limit").value(4))
                .andExpect(jsonPath("$.result[0].used").value(4))
                .andExpect(jsonPath("$.result[0].free").value(0))
                .andExpect(jsonPath("$.result[0].wiped").value(1));

        // Không hoàn lượt: slot thứ 5 vẫn 3006.
        mvc.perform(slotRequest(jwt, groupId, "Be 5", "123456"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3006));

        // Báo cáo nhóm không lọc status: đủ 4 dòng, dòng đã xoá có displayName / code = null.
        mvc.perform(get("/api/groups/" + groupId + "/report").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.rows.length()").value(4))
                .andExpect(jsonPath("$.result.rows[3].status").value("WIPED"))
                .andExpect(jsonPath("$.result.rows[3].displayName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.result.rows[3].code").value(org.hamcrest.Matchers.nullValue()));
    }

    // ------------------------------------------------------------------ dựng dữ liệu
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

    /** Trẻ đăng nhập bằng mã in trên giấy + PIN — token SLOT THẬT (mang `tv`). */
    private String childLogin(String code, String pin) throws Exception {
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
    private static MockHttpServletRequestBuilder openGroup(String jwt, String name, String context) {
        return post("/api/groups")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"context\":\"" + context + "\",\"plannedSize\":40}");
    }

    private static MockHttpServletRequestBuilder slotRequest(String jwt, String groupId,
                                                             String displayName, String pin) {
        return post("/api/slots")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":\"" + groupId + "\",\"displayName\":\"" + displayName
                        + "\",\"pin\":\"" + pin + "\",\"badge\":\"nhi dong\"}");
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

    // ------------------------------------------------------------------ đọc response
    private static String extractId(String body) {
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]+)\"").matcher(body);
        assertThat(m.find()).as("response phai co id: " + body).isTrue();
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
