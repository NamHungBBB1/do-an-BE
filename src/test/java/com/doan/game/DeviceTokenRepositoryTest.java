package com.doan.game;

import com.doan.game.entity.Account;
import com.doan.game.entity.DeviceToken;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.DeviceTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bảng device_token (26/11): token UNIQUE thật ở DB, tra theo token và theo tài khoản. */
@SpringBootTest
class DeviceTokenRepositoryTest {

    @Autowired AccountRepository accountRepo;
    @Autowired DeviceTokenRepository tokenRepo;

    @Test
    void tokenIsUniqueAndFoundByAccount() {
        Account a = account("a");
        Account b = account("b");
        String token = "fcm-" + System.nanoTime();
        tokenRepo.saveAndFlush(device(a, token));
        tokenRepo.saveAndFlush(device(a, token + "-2"));

        assertThat(tokenRepo.findByToken(token)).get().extracting(t -> t.getAccount().getId()).isEqualTo(a.getId());
        assertThat(tokenRepo.findByAccount_Id(a.getId())).hasSize(2);
        assertThat(tokenRepo.findByAccount_Id(b.getId())).isEmpty();
        assertThatThrownBy(() -> tokenRepo.saveAndFlush(device(b, token)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private DeviceToken device(Account owner, String token) {
        DeviceToken d = new DeviceToken();
        d.setAccount(owner);
        d.setToken(token);
        d.setPlatform("ANDROID");
        d.setCreatedAt(Instant.now());
        d.setLastSeenAt(Instant.now());
        return d;
    }

    private Account account(String tag) {
        Account account = new Account();
        account.setEmail(tag + "-" + System.nanoTime() + "@test.local");
        account.setCreatedAt(Instant.now());
        return accountRepo.save(account);
    }
}
