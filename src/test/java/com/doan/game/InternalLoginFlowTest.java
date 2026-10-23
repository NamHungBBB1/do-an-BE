package com.doan.game;

import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.entity.Credential;
import com.doan.game.enums.AuthProvider;
import com.doan.game.enums.Role;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.CredentialRepository;
import com.doan.game.service.TokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cửa đăng nhập nội bộ (Hưng chốt 23/10: tài khoản nội bộ là riêng, không phải khách hàng), công tắc BẬT:
 * cửa nội bộ phát token ctx=INTERNAL sống 8 giờ; cửa khách hàng từ chối tài khoản nội bộ; token cũ không có dấu
 * mất vai nội bộ (đi qua RevocationAwareJwtDecoder thật, không dùng jwt() giả).
 */
@SpringBootTest(properties = "app.auth.internal-login-required=true")
@AutoConfigureMockMvc
class InternalLoginFlowTest {

    private static final String PASSWORD = "mat-khau-noi-bo-1";

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountRoleRepository accountRoleRepo;
    @Autowired CredentialRepository credentialRepo;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TokenService tokenService;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void internalDoorIssuesShortMarkedTokenAndCustomerDoorRefusesStaff() throws Exception {
        Account admin = account("noi-bo", Role.ADMIN);
        Account parent = account("khach", null);

        // Cửa nội bộ: token có ctx=INTERNAL, sống đúng 8 giờ, vào được Studio.
        String token = login("/api/auth/internal/login", admin.getEmail(), PASSWORD)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String access = json.readTree(token).path("result").path("accessToken").asText();
        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(access.split("\\.")[1]));
        assertThat(claims.path("ctx").asText()).isEqualTo("INTERNAL");
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(8 * 3600);
        mvc.perform(get("/api/studio/chapters").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());

        // Sai mật khẩu: y hệt cửa thường (3002), không lộ email nào là nội bộ. Khách hàng đúng mật khẩu → 3022.
        login("/api/auth/internal/login", admin.getEmail(), "sai-mat-khau-1")
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(3002));
        login("/api/auth/login", admin.getEmail(), "sai-mat-khau-1")
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(3002));
        login("/api/auth/internal/login", parent.getEmail(), PASSWORD)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(3022));

        // Cửa khách hàng: tài khoản nội bộ bị từ chối (3023); khách hàng vẫn vào bình thường.
        login("/api/auth/login", admin.getEmail(), PASSWORD)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(3023));
        login("/api/auth/login", parent.getEmail(), PASSWORD).andExpect(status().isOk());

        // Token cũ phát ở cửa thường (không có dấu) dù ghi ADMIN cũng mất vai nội bộ → Studio 403.
        String legacy = tokenService.issueForAccount(admin.getId(), Set.of("ADMIN"), admin.getTokenVersion())
                .accessToken();
        mvc.perform(get("/api/studio/chapters").header("Authorization", "Bearer " + legacy))
                .andExpect(status().isForbidden());
    }

    /** Tài khoản nội bộ là riêng: chỉ cấp vai cho tài khoản trắng (3024); tài khoản nội bộ không nhận gói, không mở nhóm (3025). */
    @Test
    void internalAndCustomerAccountsStaySeparate() throws Exception {
        Account admin = account("quan-tri", Role.ADMIN);
        Account customer = account("da-mua", null);
        Account clean = account("trang", null);
        String access = json.readTree(login("/api/auth/internal/login", admin.getEmail(), PASSWORD)
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("result").path("accessToken").asText();
        String bearer = "Bearer " + access;

        mvc.perform(post("/api/entitlements/grant").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + customer.getId() + "\",\"kind\":\"PARENT\",\"months\":1,\"reason\":\"thu\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/roles/grant").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content(roleJson(customer, "GAME_EDITOR")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(3024));
        mvc.perform(post("/api/admin/roles/grant").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content(roleJson(clean, "GAME_EDITOR")))
                .andExpect(status().isOk());

        // Ngược lại: tài khoản nội bộ không nhận gói, không mở nhóm.
        mvc.perform(post("/api/entitlements/grant").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + clean.getId() + "\",\"kind\":\"PARENT\",\"months\":1,\"reason\":\"thu\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(3025));
        mvc.perform(post("/api/groups").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nhom admin\",\"context\":\"FAMILY\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(3025));
    }

    private static String roleJson(Account a, String role) {
        return "{\"subjectId\":\"" + a.getId() + "\",\"role\":\"" + role + "\",\"reason\":\"thu\"}";
    }

    private org.springframework.test.web.servlet.ResultActions login(String path, String email, String password)
            throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    /** Tài khoản đã xác minh email, có mật khẩu; role null = khách hàng thường. */
    private Account account(String prefix, Role role) {
        Instant now = Instant.now();
        Account a = new Account();
        a.setEmail(prefix + "-" + System.nanoTime() + "@test.local");
        a.setDisplayName(prefix);
        a.setCreatedAt(now);
        a.setEmailVerifiedAt(now);
        a = accountRepo.save(a);
        Credential c = new Credential();
        c.setAccount(a);
        c.setProvider(AuthProvider.PASSWORD);
        c.setSubject(a.getEmail());
        c.setPasswordHash(passwordEncoder.encode(PASSWORD));
        c.setEmailVerifiedAt(now);
        c.setCreatedAt(now);
        credentialRepo.save(c);
        if (role != null) {
            AccountRole r = new AccountRole();
            r.setAccount(a);
            r.setRole(role);
            r.setGrantedAt(now);
            accountRoleRepo.save(r);
        }
        return a;
    }
}
