package com.doan.game.service;

import com.doan.game.entity.Account;
import com.doan.game.entity.DeviceToken;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.DeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Đăng ký / xoá token thiết bị nhận push (bảng {@code device_token}, 26/11).
 *
 * <p>Token do FCM cấp là UNIQUE toàn hệ: một máy đổi tài khoản thì dòng cũ ĐỔI CHỦ, không tạo dòng
 * thứ hai. Nhờ vậy không bao giờ gửi nhầm tin của tài khoản cũ tới máy đã đăng nhập tài khoản mới,
 * và xoá tài khoản chỉ cần xoá theo khoá ngoại.
 */
@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    /** Khớp cột token VARCHAR(512) của bảng device_token. */
    static final int MAX_TOKEN_LENGTH = 512;
    private static final int MAX_PLATFORM_LENGTH = 16;
    private static final String DEFAULT_PLATFORM = "ANDROID";

    private final DeviceTokenRepository tokenRepo;
    private final AccountRepository accountRepo;
    private final Clock clock;

    /** Đăng ký token cho tài khoản đang đăng nhập. Gọi lặp cùng token chỉ cập nhật lastSeenAt. */
    @Transactional
    public void register(UUID accountId, String rawToken, String rawPlatform) {
        String token = normalizeToken(rawToken);
        String platform = normalizePlatform(rawPlatform);
        Account account = accountRepo.findById(accountId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        Instant now = Instant.now(clock);

        Optional<DeviceToken> existing = tokenRepo.findByToken(token);
        if (existing.isPresent()) {
            DeviceToken row = existing.get();
            row.setAccount(account);
            row.setPlatform(platform);
            row.setLastSeenAt(now);
            tokenRepo.save(row);
            return;
        }

        DeviceToken row = new DeviceToken();
        row.setAccount(account);
        row.setToken(token);
        row.setPlatform(platform);
        row.setCreatedAt(now);
        row.setLastSeenAt(now);
        tokenRepo.save(row);
    }

    /**
     * Xoá token khi đăng xuất. Chỉ xoá được token của CHÍNH tài khoản này — người khác gửi token
     * của máy không phải họ thì không xoá được (trả false, không lộ token có tồn tại hay không).
     */
    @Transactional
    public boolean unregister(UUID accountId, String rawToken) {
        String token = normalizeToken(rawToken);
        return tokenRepo.findByToken(token)
                .filter(row -> row.getAccount() != null && accountId.equals(row.getAccount().getId()))
                .map(row -> {
                    tokenRepo.delete(row);
                    return true;
                })
                .orElse(false);
    }

    /** Mọi token của tài khoản để gửi push; rỗng nếu chưa đăng ký máy nào. */
    @Transactional(readOnly = true)
    public List<String> tokensOf(UUID accountId) {
        return tokenRepo.findByAccount_Id(accountId).stream()
                .map(DeviceToken::getToken)
                .filter(token -> token != null && !token.isBlank())
                .toList();
    }

    /**
     * Xoá token chết khi FCM trả UNREGISTERED/INVALID_ARGUMENT. Không phụ thuộc tài khoản.
     *
     * <p>REQUIRES_NEW vì hàm này được gọi từ afterCompletion: lúc đó transaction cũ đã commit nhưng
     * tài nguyên còn buộc vào thread — dùng REQUIRED sẽ "ghi vào transaction đã kết thúc" và dòng
     * token chết không bao giờ được xoá thật.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void removeToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        tokenRepo.findByToken(token.trim()).ifPresent(tokenRepo::delete);
    }

    /**
     * FCM token là chuỗi ASCII không khoảng trắng. Chặn sớm để dòng rác không vào DB và để lỗi trả
     * 1001 dễ hiểu thay vì lúc gửi mới nhận INVALID_ARGUMENT.
     */
    static String normalizeToken(String raw) {
        if (raw == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "Thiếu token thiết bị");
        }
        String token = raw.trim();
        if (token.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "Token thiết bị không được để trống");
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Token thiết bị tối đa " + MAX_TOKEN_LENGTH + " ký tự");
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c <= ' ' || c == 127) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "Token thiết bị không được chứa khoảng trắng hay ký tự điều khiển");
            }
        }
        return token;
    }

    /** Bỏ trống thì mặc định ANDROID; chỉ nhận chữ, số, gạch dưới (ANDROID, WEB, IOS...). */
    static String normalizePlatform(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PLATFORM;
        }
        String platform = raw.trim().toUpperCase(Locale.ROOT);
        if (platform.length() > MAX_PLATFORM_LENGTH || !platform.matches("[A-Z0-9_]+")) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "platform chỉ gồm chữ, số và dấu gạch dưới");
        }
        return platform;
    }
}
