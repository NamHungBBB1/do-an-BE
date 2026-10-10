package com.doan.game;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR platform (10/10, rà soát N-04 / N-09 / G-07 / S-10): lỗi "người gọi sai" phải ra đúng mã 4xx/501 trong
 * vỏ ApiResponse, không rơi xuống 500/1000; health chạm DB; cửa trẻ kiểm typ=SLOT; /api/telemetry không còn mở.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class PlatformGuardsTest {

    @Autowired MockMvc mvc;

    @Test
    void healthReportsDatabase() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("up"))
                .andExpect(jsonPath("$.result.db").value("up"));
    }

    @Test
    void wrongMethodIs405NotServerError() throws Exception {
        mvc.perform(delete("/api/plans").with(jwt().authorities(new SimpleGrantedAuthority("TYP_ACCOUNT"))))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(1006))
                .andExpect(jsonPath("$.message").value(containsString("DELETE")));
    }

    @Test
    void nonJsonBodyIs415() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN).content("email=a&password=b"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(1007));
    }

    @Test
    void badUuidInPathIs400() throws Exception {
        mvc.perform(get("/api/groups/not-a-uuid/slots")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                                .authorities(new SimpleGrantedAuthority("TYP_ACCOUNT"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value(containsString("id")));
    }

    @Test
    void skeletonEndpointIs501() throws Exception {
        mvc.perform(get("/api/play/state")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("typ", "SLOT"))
                                .authorities(new SimpleGrantedAuthority("TYP_SLOT"), new SimpleGrantedAuthority("SCOPE_CHILD"))))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value(1004));
    }

    @Test
    void telemetryIsNoLongerOpen() throws Exception {
        mvc.perform(post("/api/telemetry/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(3003));
    }

    /** G-07: vai CHILD trên token người lớn không mở được cửa trẻ; chỉ typ=SLOT mới qua. */
    @Test
    void learnerGateChecksTokenTypeNotScope() throws Exception {
        mvc.perform(get("/api/learner/dashboard")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("typ", "ACCOUNT"))
                                .authorities(new SimpleGrantedAuthority("TYP_ACCOUNT"), new SimpleGrantedAuthority("SCOPE_CHILD"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
        // typ=SLOT qua cửa; slot không tồn tại thì service trả 3007 chứ không phải 403.
        mvc.perform(get("/api/learner/dashboard")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("typ", "SLOT"))
                                .authorities(new SimpleGrantedAuthority("TYP_SLOT"), new SimpleGrantedAuthority("SCOPE_CHILD"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3007));
    }
}
