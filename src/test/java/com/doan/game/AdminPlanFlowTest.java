package com.doan.game;

import com.doan.game.entity.Account;
import com.doan.game.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Luồng admin đi qua HTTP thật (MockMvc + H2): bảng giá công khai không cần token; đặt giá cần
 * SCOPE_ADMIN; người thường bị 403 với đúng vỏ ApiResponse. JWT giả bằng spring-security-test, nên
 * không phụ thuộc AuthService (còn là khung).
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class AdminPlanFlowTest {

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;

    /**
     * P-09: trần giá (50 triệu) cho cả gói lẫn pack; 18/11/2026 bỏ trần / tham số số tháng.
     */
    @Test
    void priceHasCeiling() throws Exception {
        Account admin = admin();
        var asAdmin = jwt().jwt(j -> j.subject(admin.getId().toString()))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_ADMIN"));

        mvc.perform(put("/api/admin/plans/parent").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\": 990000000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4007));
        mvc.perform(put("/api/admin/addon-price/parent").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\": 50000001}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4007));
    }

    private Account admin() {
        Account a = new Account();
        a.setEmail("admin-" + System.nanoTime() + "@test.local");
        a.setDisplayName("Admin");
        a.setCreatedAt(Instant.now());
        return accountRepo.save(a);
    }

    @Test
    void adminSetsPlanAndAddonPricesAndAnyoneCanRead() throws Exception {
        Account admin = admin();
        var asAdmin = jwt().jwt(j -> j.subject(admin.getId().toString()))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_ADMIN"));

        mvc.perform(put("/api/admin/plans/parent").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\": 399000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.kind").value("PARENT"))
                .andExpect(jsonPath("$.result.price").value(399000))
                .andExpect(jsonPath("$.result.addonSlots").value(2))
                .andExpect(jsonPath("$.result.addonPrice").doesNotExist());   // chưa đặt giá pack

        // Giá pack đặt riêng; Parent +2, Teacher +20 slot mỗi pack.
        mvc.perform(put("/api/admin/addon-price/PARENT").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\": 99000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.addonPrice").value(99000));
        mvc.perform(put("/api/admin/addon-price/teacher").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\": 399000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.addonSlots").value(20));

        // sửa lại giá gói: vẫn một dòng, giá mới
        mvc.perform(put("/api/admin/plans/PARENT").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\": 420000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.price").value(420000))
                .andExpect(jsonPath("$.result.addonPrice").value(99000));

        mvc.perform(get("/api/plans"))   // không token
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[?(@.kind=='PARENT')].price").value(420000))
                .andExpect(jsonPath("$.result[?(@.kind=='PARENT')].addonPrice").value(99000))
                .andExpect(jsonPath("$.result[?(@.kind=='PARENT')].addonSlots").value(2));
    }

    @Test
    void nonPositivePriceIsRejected() throws Exception {
        Account admin = admin();
        mvc.perform(put("/api/admin/plans/teacher")
                        .with(jwt().jwt(j -> j.subject(admin.getId().toString()))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4007));
    }

    @Test
    void regularUserCannotSetPrice() throws Exception {
        mvc.perform(put("/api/admin/plans/parent")
                        .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_PARENT")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\": 1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));

        mvc.perform(get("/api/admin/transactions")
                        .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items").isArray())
                .andExpect(jsonPath("$.result.totalElements").isNumber());
    }
}
