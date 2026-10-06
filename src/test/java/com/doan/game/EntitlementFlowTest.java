package com.doan.game;

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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ruột GET /api/entitlements đi qua HTTP thật (MockMvc + H2). JWT giả bằng spring-security-test
 * như AdminPlanFlowTest — endpoint này chỉ cần subject là id tài khoản, không phải token thật.
 *
 * Kiểm đúng ba điều đã chốt 06/10: trả CẢ gói hết hạn (lịch sử), sắp hạn mới nhất trước, và mỗi
 * người chỉ thấy gói của chính mình.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class EntitlementFlowTest {

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired EntitlementRepository entitlementRepo;

    // ------------------------------------------------------------------ 1
    @Test
    void accountWithNoPlanGetsEmptyList() throws Exception {
        Account a = account();

        mvc.perform(get("/api/entitlements").with(asAccount(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result").isArray())
                .andExpect(jsonPath("$.result").isEmpty());
    }

    // ------------------------------------------------------------------ 2
    /** Cả gói đã hết hạn vẫn trả (lịch sử), hạn mới nhất đứng trước. */
    @Test
    void listsExpiredAndActiveNewestFirst() throws Exception {
        Account a = account();
        entitlement(a, PlanKind.PARENT, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31));
        entitlement(a, PlanKind.TEACHER, LocalDate.of(2025, 4, 1), LocalDate.of(2026, 6, 30));
        entitlement(a, PlanKind.PARENT, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30));

        mvc.perform(get("/api/entitlements").with(asAccount(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(3))
                .andExpect(jsonPath("$.result[0].source").value("ADMIN"))
                .andExpect(jsonPath("$.result[0].expiresOn").value("2026-09-30"))
                .andExpect(jsonPath("$.result[1].expiresOn").value("2026-06-30"))
                .andExpect(jsonPath("$.result[2].expiresOn").value("2025-03-31"));
    }

    // ------------------------------------------------------------------ 3
    /** Gói của người khác không được lọt sang response. */
    @Test
    void neverShowsAnotherAccountsPlan() throws Exception {
        Account mine = account();
        Account khac = account();
        entitlement(khac, PlanKind.PARENT, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));

        mvc.perform(get("/api/entitlements").with(asAccount(mine)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").isEmpty());
    }

    // ------------------------------------------------------------------ 4
    @Test
    void withoutTokenIsRejected() throws Exception {
        mvc.perform(get("/api/entitlements"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(3003));
    }

    // ------------------------------------------------------------------ 5
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

    // ------------------------------------------------------------------ dựng dữ liệu
    private Account account() {
        Account a = new Account();
        a.setEmail("ent-" + System.nanoTime() + "@test.local");
        a.setDisplayName("Ent Test");
        a.setCreatedAt(Instant.now());
        return accountRepo.save(a);
    }

    private Entitlement entitlement(Account owner, PlanKind kind, LocalDate startsOn, LocalDate expiresOn) {
        Entitlement e = new Entitlement();
        e.setAccount(owner);
        e.setKind(kind);
        e.setStartsOn(startsOn);
        e.setExpiresOn(expiresOn);
        e.setSource(EntitlementSource.ADMIN);
        e.setGrantedBy(owner);
        e.setCreatedAt(Instant.now());
        return entitlementRepo.save(e);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor asAccount(Account a) {
        return jwt().jwt(j -> j.subject(a.getId().toString()).claim("typ", "ACCOUNT"))
                .authorities(new SimpleGrantedAuthority("TYP_ACCOUNT"));
    }
}
