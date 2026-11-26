package com.doan.game;

import com.doan.game.entity.Account;
import com.doan.game.entity.DeviceToken;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.DeviceTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API đăng ký / xoá token thiết bị (bảng device_token, 26/11).
 *
 * Token UNIQUE toàn hệ nên đăng ký lại cùng máy phải ĐỔI CHỦ, không đẻ dòng thứ hai — nếu sai, máy
 * đã đăng nhập tài khoản mới vẫn nhận tin của tài khoản cũ.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DeviceTokenFlowTest {

    private static final Instant START = Instant.parse("2026-11-26T02:00:00Z");

    /** Dạng token FCM thật, có dấu hai chấm — kiểm luôn đường dẫn DELETE có ký tự này. */
    private static final String TOKEN = "e8BwGXReSuyW080FLQ3asL:APA91bHdSeSpznqQ105R517-ay0uRKpmesHld"
            + "YC5YCMoiC9WOAqSrY5CjbHAFKmHHbQW0KEm5AWVCT_WItsQBXtx4QvodaXoy-Zvnvz7Q22bR8PVzbueeaQ";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AccountRepository accountRepo;
    @Autowired
    private DeviceTokenRepository tokenRepo;

    @Test
    void sameTokenMovesToTheNewAccountInsteadOfDuplicating() throws Exception {
        Account first = adult("dt-a");
        Account second = adult("dt-b");

        register(first, TOKEN, "ANDROID")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.registered").value(true));
        // Gọi lại cùng máy (app mở lần nữa) — vẫn MỘT dòng, last_seen_at mới hơn.
        register(first, TOKEN, null).andExpect(status().isOk());

        DeviceToken row = tokenRepo.findByToken(TOKEN).orElseThrow();
        assertThat(row.getAccount().getId()).isEqualTo(first.getId());
        assertThat(row.getPlatform()).isEqualTo("ANDROID");
        assertThat(tokenRepo.findByAccount_Id(first.getId())).hasSize(1);

        // Máy đổi sang tài khoản khác: dòng cũ đổi chủ, KHÔNG thêm dòng.
        register(second, TOKEN, "android").andExpect(status().isOk());
        row = tokenRepo.findByToken(TOKEN).orElseThrow();
        assertThat(row.getAccount().getId()).isEqualTo(second.getId());
        assertThat(row.getPlatform()).isEqualTo("ANDROID");
        assertThat(tokenRepo.findByAccount_Id(first.getId())).isEmpty();
        assertThat(tokenRepo.findByAccount_Id(second.getId())).hasSize(1);
    }

    @Test
    void unregisterOnlyDeletesOwnTokenAndIsIdempotent() throws Exception {
        Account owner = adult("dt-chu");
        Account stranger = adult("dt-la");
        register(owner, TOKEN, "ANDROID").andExpect(status().isOk());

        // Người lạ biết token cũng không xoá được.
        unregister(stranger, TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.removed").value(false));
        assertThat(tokenRepo.findByToken(TOKEN)).isPresent();

        unregister(owner, TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.removed").value(true));
        assertThat(tokenRepo.findByToken(TOKEN)).isEmpty();

        // Đăng xuất gọi lặp: vẫn 200, removed = false.
        unregister(owner, TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.removed").value(false));
    }

    @Test
    void rejectsBlankHugeOrDirtyInput() throws Exception {
        Account account = adult("dt-sai");

        register(account, "", "ANDROID")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        register(account, "x".repeat(513), "ANDROID")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        register(account, "co khoang trang", "ANDROID")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        register(account, TOKEN, "AN DROID")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        assertThat(tokenRepo.findByAccount_Id(account.getId())).isEmpty();
    }

    // ------------------------------------------------------------------ dựng dữ liệu

    private ResultActions register(Account account, String token, String platform) throws Exception {
        String body = platform == null
                ? "{\"token\":\"" + token + "\"}"
                : "{\"token\":\"" + token + "\",\"platform\":\"" + platform + "\"}";
        return mvc.perform(post("/api/device-tokens").with(as(account))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions unregister(Account account, String token) throws Exception {
        return mvc.perform(delete("/api/device-tokens/{token}", token).with(as(account)));
    }

    private Account adult(String tag) {
        Account account = new Account();
        account.setEmail(tag + "-" + System.nanoTime() + "@test.local");
        account.setDisplayName("Test " + tag);
        account.setCreatedAt(START);
        return accountRepo.save(account);
    }

    private static RequestPostProcessor as(Account account) {
        String id = account.getId().toString();
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("TYP_ACCOUNT"));
    }
}
