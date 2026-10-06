package com.doan.game;

import com.doan.game.configuration.ClockConfig;
import com.doan.game.entity.Account;
import com.doan.game.entity.Entitlement;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.PlanKind;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.EntitlementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ruột GET + POST /grant của /api/entitlements đi qua HTTP thật (MockMvc + H2). JWT giả bằng
 * spring-security-test như AdminPlanFlowTest — ở đây chỉ cần subject là id tài khoản; ca dùng token
 * THẬT nằm trong AuthFlowTest (đăng ký → OTP → token thật).
 *
 * Kiểm những gì đã chốt 06/10: trả CẢ gói hết hạn sắp hạn mới nhất trước (tie-break createdAt),
 * không lọt dữ liệu người khác, grant với source = ADMIN / grantedBy = admin / reason bắt buộc /
 * months 1–36, và gia hạn nối tiếp từ ngày sau hạn cũ.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class EntitlementFlowTest {

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired EntitlementRepository entitlementRepo;

    // ------------------------------------------------------------------ GET
    @Test
    void accountWithNoPlanGetsEmptyList() throws Exception {
        Account a = account();

        mvc.perform(get("/api/entitlements").with(asAccount(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result").isArray())
                .andExpect(jsonPath("$.result").isEmpty());
    }

    /** Cả gói đã hết hạn vẫn trả (lịch sử), hạn mới nhất đứng trước. */
    @Test
    void listsExpiredAndActiveNewestFirst() throws Exception {
        Account a = account();
        entitlement(a, PlanKind.PARENT, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31), Instant.now());
        entitlement(a, PlanKind.TEACHER, LocalDate.of(2025, 4, 1), LocalDate.of(2026, 6, 30), Instant.now());
        entitlement(a, PlanKind.PARENT, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), Instant.now());

        mvc.perform(get("/api/entitlements").with(asAccount(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(3))
                .andExpect(jsonPath("$.result[0].source").value("ADMIN"))
                .andExpect(jsonPath("$.result[0].expiresOn").value("2026-09-30"))
                .andExpect(jsonPath("$.result[1].expiresOn").value("2026-06-30"))
                .andExpect(jsonPath("$.result[2].expiresOn").value("2025-03-31"));
    }

    /** Hai gói cùng hạn: dòng tạo SAO đứng trước — nếu không thì thứ tự tùy CSDL (khoểnh Kidz Bridge). */
    @Test
    void sameExpiryFallsBackToCreatedAtDesc() throws Exception {
        Account a = account();
        entitlement(a, PlanKind.PARENT, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
                Instant.parse("2026-01-01T00:00:00Z"));
        entitlement(a, PlanKind.TEACHER, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
                Instant.parse("2026-02-01T00:00:00Z"));

        mvc.perform(get("/api/entitlements").with(asAccount(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(2))
                .andExpect(jsonPath("$.result[0].kind").value("TEACHER"))
                .andExpect(jsonPath("$.result[1].kind").value("PARENT"));
    }

    /** Gói của người khác không được lọt sang response. */
    @Test
    void neverShowsAnotherAccountsPlan() throws Exception {
        Account mine = account();
        Account khac = account();
        entitlement(khac, PlanKind.PARENT, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), Instant.now());

        mvc.perform(get("/api/entitlements").with(asAccount(mine)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").isEmpty());
    }

    @Test
    void withoutTokenIsRejected() throws Exception {
        mvc.perform(get("/api/entitlements"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(3003));
    }

    /** Token trẻ (scope CHILD, typ SLOT) không có TYP_ACCOUNT → 403 như mọi API người lớn. */
    @Test
    void childTokenIsRejected() throws Exception {
        Account a = account();
        mvc.perform(get("/api/entitlements").with(jwt()
                        .jwt(j -> j.subject(a.getId().toString()).claim("typ", "SLOT"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_CHILD"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
    }

    // ------------------------------------------------------------------ GRANT
    /** Admin cấp tay: kết quả ngay trong GET của người nhận (không cần đăng nhập lại). */
    @Test
    void adminGrantsPlanAndOwnerSeesItImmediately() throws Exception {
        Account admin = account();
        Account nhan = account();

        mvc.perform(post("/api/entitlements/grant").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantJson(nhan, "PARENT", 3, "dung thu 3 thang")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result.kind").value("PARENT"))
                .andExpect(jsonPath("$.result.source").value("ADMIN"));

        mvc.perform(get("/api/entitlements").with(asAccount(nhan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(1))
                .andExpect(jsonPath("$.result[0].kind").value("PARENT"));
    }

    /** Gia hạn nối tiếp: hết hạn 31/12 thì gói mới bắt đầu 01/01, không mất ngày. */
    @Test
    void grantRenewsFromDayAfterPreviousExpiry() throws Exception {
        Account admin = account();
        Account nhan = account();
        entitlement(nhan, PlanKind.PARENT, LocalDate.of(2025, 10, 1), LocalDate.of(2026, 12, 31), Instant.now());

        mvc.perform(post("/api/entitlements/grant").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantJson(nhan, "PARENT", 3, "keo dai som")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.startsOn").value("2027-01-01"))
                .andExpect(jsonPath("$.result.expiresOn").value("2027-03-31"));
    }

    /** Gói hết hạn rồi: gói mới bắt đầu từ hôm nay, dùng hết ngày startsOn + tháng − 1. */
    @Test
    void grantStartsTodayWhenPreviousPlanExpired() throws Exception {
        Account admin = account();
        Account nhan = account();
        entitlement(nhan, PlanKind.TEACHER, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 6, 30), Instant.now());

        LocalDate homNay = LocalDate.now(ClockConfig.VN);
        mvc.perform(post("/api/entitlements/grant").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantJson(nhan, "TEACHER", 6, "den bu")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.startsOn").value(homNay.toString()))
                .andExpect(jsonPath("$.result.expiresOn").value(homNay.plusMonths(6).minusDays(1).toString()));
    }

    @Test
    void grantRejectsBadInput() throws Exception {
        Account admin = account();
        Account nhan = account();

        // months = 0
        mvc.perform(post("/api/entitlements/grant").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantJson(nhan, "PARENT", 0, "hop le")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        // months = 37 (vượt 36)
        mvc.perform(post("/api/entitlements/grant").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantJson(nhan, "PARENT", 37, "hop le")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        // thiếu reason
        mvc.perform(post("/api/entitlements/grant").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantJson(nhan, "PARENT", 3, "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        // loại gói lạ
        mvc.perform(post("/api/entitlements/grant").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantJson(nhan, "VIP", 3, "hop le")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4001));

        // không có tài khoản nhận
        mvc.perform(post("/api/entitlements/grant").with(asAdmin(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID()
                                + "\",\"kind\":\"PARENT\",\"months\":3,\"reason\":\"hop le\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void nonAdminCannotGrant() throws Exception {
        Account thuong = account();
        Account nhan = account();

        mvc.perform(post("/api/entitlements/grant").with(asAccount(thuong))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantJson(nhan, "PARENT", 3, "khong du quyen")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
    }

    // ------------------------------------------------------------------ dựng dữ liệu
    private Account account() {
        Account a = new Account();
        a.setEmail("ent-" + System.nanoTime() + "@test.local");
        a.setDisplayName("Ent Test");
        a.setCreatedAt(Instant.now());
        return accountRepo.save(a);
    }

    private Entitlement entitlement(Account owner, PlanKind kind, LocalDate startsOn, LocalDate expiresOn,
                                    Instant createdAt) {
        Entitlement e = new Entitlement();
        e.setAccount(owner);
        e.setKind(kind);
        e.setStartsOn(startsOn);
        e.setExpiresOn(expiresOn);
        e.setSource(EntitlementSource.ADMIN);
        e.setGrantedBy(owner);
        e.setCreatedAt(createdAt);
        return entitlementRepo.save(e);
    }

    private static String grantJson(Account nhan, String kind, int months, String reason) {
        return "{\"accountId\":\"" + nhan.getId() + "\",\"kind\":\"" + kind
                + "\",\"months\":" + months + ",\"reason\":\"" + reason + "\"}";
    }

    private static JwtRequestPostProcessor asAccount(Account a) {
        return jwt().jwt(j -> j.subject(a.getId().toString()).claim("typ", "ACCOUNT"))
                .authorities(new SimpleGrantedAuthority("TYP_ACCOUNT"));
    }

    /** Đúng hai quyền mà decoder thật cấp cho admin: SCOPE_ADMIN + TYP_ACCOUNT. */
    private static JwtRequestPostProcessor asAdmin(Account a) {
        return jwt().jwt(j -> j.subject(a.getId().toString()).claim("typ", "ACCOUNT"))
                .authorities(new SimpleGrantedAuthority("SCOPE_ADMIN"),
                        new SimpleGrantedAuthority("TYP_ACCOUNT"));
    }
}
