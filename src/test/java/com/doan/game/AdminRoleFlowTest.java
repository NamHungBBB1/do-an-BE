package com.doan.game;

import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.entity.RoleGrantLog;
import com.doan.game.enums.Role;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.RoleGrantLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vai nội bộ (PR A, 15/10) qua HTTP thật (MockMvc + H2): admin cấp GAME_EDITOR → /auth/me của người đó
 * thấy vai ngay; thu → mất; sổ role_grant_log đủ hai dòng (cấp rồi thu); dữ liệu sai bị 1001 / 1003;
 * người thường 403. Tra tài khoản theo email (tối 15/10) cho màn cấp vai. JWT giả bằng spring-security-test như
 * AdminPlanFlowTest, nên không cần AuthService.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class AdminRoleFlowTest {

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountRoleRepository accountRoleRepo;
    @Autowired RoleGrantLogRepository roleGrantLogRepo;

    @Test
    void grantThenRevokeChangesMeAndWritesLog() throws Exception {
        Account admin = account("admin");
        Account lan = account("lan");
        RequestPostProcessor asAdmin = as(admin, "SCOPE_ADMIN");

        mvc.perform(post("/api/admin/roles/grant").with(asAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(body(lan.getId(), "game_editor", "soan chuong 4")))   // chữ thường vẫn nhận
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.subjectId").value(lan.getId().toString()))
                .andExpect(jsonPath("$.result.roles[0]").value("GAME_EDITOR"));

        // Lan thấy vai ngay ở /auth/me: đọc DB mỗi lần, không cần token mới.
        mvc.perform(get("/api/auth/me").with(as(lan, "TYP_ACCOUNT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.roles[0]").value("GAME_EDITOR"));

        // Cấp lại: lặp vô hại — vẫn một vai, không thêm dòng sổ.
        mvc.perform(post("/api/admin/roles/grant").with(asAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(body(lan.getId(), "GAME_EDITOR", "cap lai")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.roles.length()").value(1));
        assertEquals(1, roleGrantLogRepo.findByTarget_IdOrderByCreatedAtAsc(lan.getId()).size());

        mvc.perform(post("/api/admin/roles/revoke").with(asAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(body(lan.getId(), "GAME_EDITOR", "chuyen sang reviewer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.roles").isEmpty());
        mvc.perform(get("/api/auth/me").with(as(lan, "TYP_ACCOUNT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.roles").isEmpty());

        // Thu lần nữa: không còn gì để thu.
        mvc.perform(post("/api/admin/roles/revoke").with(asAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(body(lan.getId(), "GAME_EDITOR", "thu lan hai")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1003));

        List<RoleGrantLog> log = roleGrantLogRepo.findByTarget_IdOrderByCreatedAtAsc(lan.getId());
        assertEquals(List.of(true, false), log.stream().map(RoleGrantLog::isGranted).toList());
        assertEquals(List.of("GAME_EDITOR", "GAME_EDITOR"), log.stream().map(RoleGrantLog::getRole).toList());
        assertEquals("soan chuong 4", log.get(0).getReason());
    }

    @Test
    void rejectsBadInputAndSelfAdminRevoke() throws Exception {
        Account admin = account("admin");
        Account lan = account("lan");
        RequestPostProcessor asAdmin = as(admin, "SCOPE_ADMIN");

        // thiếu reason
        mvc.perform(post("/api/admin/roles/grant").with(asAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(body(lan.getId(), "GAME_EDITOR", " ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        // vai suy từ gói: không cấp tay
        mvc.perform(post("/api/admin/roles/grant").with(asAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(body(lan.getId(), "PARENT", "cho dung thu")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        // vai không tồn tại
        mvc.perform(post("/api/admin/roles/grant").with(asAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(body(lan.getId(), "BOSS", "thu")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        // tài khoản không có
        mvc.perform(post("/api/admin/roles/grant").with(asAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), "GAME_EDITOR", "thu")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1003));

        // admin tự thu ADMIN của chính mình: giữ cửa cuối
        AccountRole r = new AccountRole();
        r.setAccount(admin);
        r.setRole(Role.ADMIN);
        r.setGrantedAt(Instant.now());
        accountRoleRepo.save(r);
        mvc.perform(post("/api/admin/roles/revoke").with(asAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(body(admin.getId(), "ADMIN", "nghi viec")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        assertTrue(accountRoleRepo.existsByAccount_IdAndRole(admin.getId(), Role.ADMIN));

        // Bị từ chối thì không ghi sổ, không cấp gì.
        assertTrue(roleGrantLogRepo.findByTarget_IdOrderByCreatedAtAsc(lan.getId()).isEmpty());
        assertTrue(accountRoleRepo.findByAccount_Id(lan.getId()).isEmpty());
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        Account parent = account("parent");
        Account lan = account("lan");
        mvc.perform(post("/api/admin/roles/grant").with(as(parent, "SCOPE_PARENT", "TYP_ACCOUNT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(lan.getId(), "GAME_EDITOR", "thu")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
        assertTrue(accountRoleRepo.findByAccount_Id(lan.getId()).isEmpty());
    }

    @Test
    void findsAccountByEmailForRoleScreen() throws Exception {
        Account admin = account("admin");
        Account lan = account("lan");
        RequestPostProcessor asAdmin = as(admin, "SCOPE_ADMIN");

        // Hoa thường lẫn lộn, khoảng trắng hai đầu vẫn ra đúng một người; chưa có vai, chưa có gói.
        mvc.perform(get("/api/admin/accounts").with(asAdmin)
                        .param("email", "  " + lan.getEmail().toUpperCase(Locale.ROOT) + " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(lan.getId().toString()))
                .andExpect(jsonPath("$.result.email").value(lan.getEmail()))
                .andExpect(jsonPath("$.result.roles").isEmpty())
                .andExpect(jsonPath("$.result.plans").isEmpty());

        // Cấp vai xong tra lại thấy ngay: màn cấp vai dùng chính API này để hiện vai hiện có.
        mvc.perform(post("/api/admin/roles/grant").with(asAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(body(lan.getId(), "GAME_REVIEWER", "duyet noi dung")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/accounts").with(asAdmin).param("email", lan.getEmail()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.roles[0]").value("GAME_REVIEWER"));

        mvc.perform(get("/api/admin/accounts").with(asAdmin).param("email", "khong-co@test.local"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1003));
        mvc.perform(get("/api/admin/accounts").with(asAdmin).param("email", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        mvc.perform(get("/api/admin/accounts").with(asAdmin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        mvc.perform(get("/api/admin/accounts").with(as(lan, "SCOPE_PARENT", "TYP_ACCOUNT"))
                        .param("email", lan.getEmail()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
    }

    // ---- phụ trợ ----

    private Account account(String prefix) {
        Account a = new Account();
        a.setEmail(prefix + "-" + System.nanoTime() + "@test.local");
        a.setDisplayName(prefix);
        a.setCreatedAt(Instant.now());
        return accountRepo.save(a);
    }

    /** JWT giả: sub = id tài khoản, authorities đặt thẳng (SCOPE_x / TYP_x) như SecurityConfig suy ra. */
    private static RequestPostProcessor as(Account a, String... authorities) {
        return jwt().jwt(j -> j.subject(a.getId().toString()))
                .authorities(Arrays.stream(authorities).map(SimpleGrantedAuthority::new)
                        .toArray(GrantedAuthority[]::new));
    }

    private static String body(UUID subjectId, String role, String reason) {
        return "{\"subjectId\":\"" + subjectId + "\",\"role\":\"" + role + "\",\"reason\":\"" + reason + "\"}";
    }
}
