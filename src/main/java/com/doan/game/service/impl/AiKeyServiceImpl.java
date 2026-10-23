package com.doan.game.service.impl;

import com.doan.game.DTO.request.AiKeyRequest;
import com.doan.game.DTO.response.AiKeyResponse;
import com.doan.game.entity.AiKey;
import com.doan.game.enums.AiProviderKind;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AiKeyRepository;
import com.doan.game.service.AiKeyProbe;
import com.doan.game.service.AiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Khoá AI (24/10). Mã hoá AES-256-GCM, khoá mã hoá = SHA-256("finteen-ai-keys|" + app.ai.keys-secret) — mặc định dùng lại
 * JWT_SECRET, nên đổi JWT_SECRET là phải nhập lại khoá AI. Sao lưu DB vì vậy không chứa khoá AI đọc được.
 */
@Service
public class AiKeyServiceImpl implements AiKeyService {

    private static final Logger log = LoggerFactory.getLogger(AiKeyServiceImpl.class);
    static final Duration QUOTA_COOL_DOWN = Duration.ofMinutes(15);
    private static final int GCM_IV = 12;
    private static final int GCM_TAG_BITS = 128;

    private final AiKeyRepository keyRepo;
    private final AccountRepository accountRepo;
    private final AiKeyProbe probe;
    private final Clock clock;
    private final SecretKeySpec cipherKey;
    private final SecureRandom rng = new SecureRandom();

    public AiKeyServiceImpl(AiKeyRepository keyRepo, AccountRepository accountRepo, AiKeyProbe probe, Clock clock,
                            @Value("${app.ai.keys-secret:${app.jwt.secret}}") String secret) {
        this.keyRepo = keyRepo;
        this.accountRepo = accountRepo;
        this.probe = probe;
        this.clock = clock;
        try {
            byte[] k = MessageDigest.getInstance("SHA-256")
                    .digest(("finteen-ai-keys|" + secret).getBytes(StandardCharsets.UTF_8));
            this.cipherKey = new SecretKeySpec(k, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiKeyResponse> list() {
        return keyRepo.findAll().stream()
                .sorted(Comparator.comparing(AiKey::getProvider)
                        .thenComparing(k -> k.getPriority() == null ? Integer.MAX_VALUE : k.getPriority())
                        .thenComparing(AiKey::getAlias))
                .map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public AiKeyResponse add(UUID callerId, AiKeyRequest req) {
        if (req == null) {
            throw new AppException(ErrorCode.AI_KEY_INVALID, "thiếu body");
        }
        AiProviderKind provider;
        try {
            provider = AiProviderKind.valueOf(req.provider() == null ? "" : req.provider().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.AI_KEY_INVALID, "provider: GEMINI hoặc OPENROUTER");
        }
        String alias = req.alias() == null ? "" : req.alias().trim();
        if (alias.isEmpty() || alias.length() > 40) {
            throw new AppException(ErrorCode.AI_KEY_INVALID, "alias: 1–40 ký tự");
        }
        String key = req.key() == null ? "" : req.key().trim();
        if (key.length() < 10 || key.length() > 300 || key.chars().anyMatch(Character::isWhitespace)) {
            throw new AppException(ErrorCode.AI_KEY_INVALID, "key: 10–300 ký tự, không có khoảng trắng");
        }
        if (keyRepo.existsByAliasIgnoreCase(alias)) {
            throw new AppException(ErrorCode.AI_KEY_ALIAS_TAKEN, alias);
        }
        AiKey k = new AiKey();
        k.setProvider(provider);
        k.setAlias(alias);
        k.setSecretEnc(encrypt(key));
        k.setLast4(key.substring(key.length() - 4));
        k.setEnabled(true);
        k.setPriority(req.priority());
        k.setCreatedBy(accountRepo.findById(callerId).orElse(null));
        k.setCreatedAt(Instant.now(clock));
        k = keyRepo.save(k);
        runProbe(k, key);
        log.info("Nhập khoá AI {} ({}) bởi {}", alias, provider, callerId);
        return toResponse(k);
    }

    @Override
    @Transactional
    public AiKeyResponse test(UUID callerId, UUID id) {
        AiKey k = require(id);
        runProbe(k, decrypt(k.getSecretEnc()));
        log.info("Thử khoá AI {} → {} bởi {}", k.getAlias(), k.isLastTestOk() ? "OK" : k.getLastError(), callerId);
        return toResponse(k);
    }

    @Override
    @Transactional
    public AiKeyResponse setEnabled(UUID callerId, UUID id, boolean enabled) {
        AiKey k = require(id);
        k.setEnabled(enabled);
        log.info("{} khoá AI {} bởi {}", enabled ? "Bật" : "Dừng", k.getAlias(), callerId);
        return toResponse(k);
    }

    @Override
    @Transactional
    public void delete(UUID callerId, UUID id) {
        AiKey k = require(id);
        keyRepo.delete(k);
        log.info("Xoá khoá AI {} bởi {}", k.getAlias(), callerId);
    }

    private void runProbe(AiKey k, String key) {
        AiKeyProbe.Result r = probe.probe(k.getProvider(), key);
        Instant now = Instant.now(clock);
        k.setLastTestAt(now);
        k.setLastTestOk(r.outcome() == AiKeyProbe.Outcome.OK);
        k.setLastError(r.outcome() == AiKeyProbe.Outcome.OK ? null : (r.outcome() + " " + r.detail()).trim());
        k.setCoolDownUntil(r.outcome() == AiKeyProbe.Outcome.QUOTA ? now.plus(QUOTA_COOL_DOWN)
                : r.outcome() == AiKeyProbe.Outcome.OK ? null : k.getCoolDownUntil());
    }

    private AiKey require(UUID id) {
        return keyRepo.findById(id).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có khoá " + id));
    }

    private AiKeyResponse toResponse(AiKey k) {
        return new AiKeyResponse(k.getId(), k.getProvider(), k.getAlias(), "••••" + k.getLast4(), k.isEnabled(),
                k.getPriority(), k.getLastTestAt(), k.isLastTestOk(), k.getLastError(), k.getCoolDownUntil(),
                k.getCreatedBy() == null ? null : k.getCreatedBy().getDisplayName(), k.getCreatedAt());
    }

    // ------------------------------------------------------------------ mã hoá
    /** base64(iv 12 byte ‖ bản mã + tag). */
    String encrypt(String plain) {
        try {
            byte[] iv = new byte[GCM_IV];
            rng.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, cipherKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Dùng cho thử khoá và (đợt 2c) hàng đợi AI. Sai khoá mã hoá (đã đổi JWT_SECRET) → 9001, nhập lại khoá. */
    String decrypt(String enc) {
        try {
            byte[] all = Base64.getDecoder().decode(enc);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, cipherKey, new GCMParameterSpec(GCM_TAG_BITS, all, 0, GCM_IV));
            return new String(c.doFinal(all, GCM_IV, all.length - GCM_IV), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new AppException(ErrorCode.AI_KEY_INVALID, "không giải mã được khoá — có thể đã đổi JWT_SECRET, nhập lại khoá");
        }
    }
}
