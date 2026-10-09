package com.doan.game.service.impl;

import com.doan.game.DTO.request.ChangePasswordRequest;
import com.doan.game.DTO.request.ForgotPasswordRequest;
import com.doan.game.DTO.request.LinkCredentialRequest;
import com.doan.game.DTO.request.LoginRequest;
import com.doan.game.DTO.request.RegisterRequest;
import com.doan.game.DTO.request.ResetPasswordRequest;
import com.doan.game.DTO.request.VerifyEmailRequest;
import com.doan.game.DTO.response.AccountResponse;
import com.doan.game.DTO.response.TokenResponse;
import com.doan.game.security.FirebaseIdTokenDecoder;
import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.entity.Credential;
import com.doan.game.entity.RoleGrantLog;
import com.doan.game.entity.VerificationToken;
import com.doan.game.enums.AuthProvider;
import com.doan.game.enums.Role;
import com.doan.game.enums.TokenPurpose;
import com.doan.game.exception.AppException;
import com.doan.game.exception.DbErrors;
import com.doan.game.exception.ErrorCode;
import com.doan.game.mapper.AccountMapper;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.CredentialRepository;
import com.doan.game.repository.RoleGrantLogRepository;
import com.doan.game.repository.VerificationTokenRepository;
import com.doan.game.service.AuthService;
import com.doan.game.service.EntitlementService;
import com.doan.game.service.OutgoingMail;
import com.doan.game.service.TokenService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Ruột auth (02/10). Đăng ký, xác minh email, đăng nhập, quên / đặt lại mật khẩu.
 * Gộp Google để sau — luồng mật khẩu phải chạy trước, còn chưa có OAuth client ID.
 *
 * Năm luật đã giữ, mỗi luật có test:
 *  1. Email trùng kể cả khác hoa thường vẫn là một tài khoản (UNIQUE ở DB không phân biệt hoa
 *     thường trên một số H2/Postgres, nên phải chuẩn hoá TRƯỚC khi lưu và trước khi tìm).
 *  2. Trong bảng chỉ có SHA-256 của token, không có token gốc. Lộ cả bảng cũng không dùng được
 *     link xác minh.
 *  3. Sai email và sai mật khẩu cùng trả BAD_CREDENTIALS — khác nhau là tặng kẻ dò một cách
 *     kiểm tra xem email nào đã có tài khoản.
 *  4. Quên mật khẩu luôn trả 200, kể cả khi email không tồn tại.
 *  5. login CỐ Ý KHÔNG @Transactional — xem chú thích ngay tại hàm.
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    /** Link xác minh email dùng lâu, đặt lại mật khẩu thì ngắn — token đó mở được tài khoản. */
    /** Mã OTP 6 số (chốt 14/09): hết hạn 10 phút, sai 5 lần là hỏng, phải xin mã mới. */
    private static final Duration OTP_TTL = Duration.ofMinutes(10);
    private static final int MAX_OTP_ATTEMPTS = 5;
    private static final Pattern OTP_PATTERN = Pattern.compile("^\\d{6}$");
    /** Gửi lại mail cách nhau tối thiểu một phút, chặn spam tới hộp thư người khác. */
    private static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);
    private static final int MAX_PASSWORD_ATTEMPTS = 5;
    private static final Duration PASSWORD_LOCK_DURATION = Duration.ofMinutes(15);

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MIN_PASSWORD_LENGTH = 8;
    /** BCrypt chỉ băm 72 byte đầu; Spring Security 6.5 ném IllegalArgumentException khi dài hơn → 500. */
    private static final int MAX_PASSWORD_BYTES = 72;
    // Khớp độ dài cột trong entity (sinh từ ERD): vượt là SQLState 22001 → 500 thay vì 1001.
    private static final int MAX_EMAIL_LENGTH = 160;
    private static final int MAX_PHONE_LENGTH = 20;
    private static final int MAX_DISPLAY_NAME_LENGTH = 80;

    private final AccountRepository accountRepo;
    private final CredentialRepository credentialRepo;
    private final VerificationTokenRepository tokenRepo;
    private final AccountRoleRepository accountRoleRepo;
    private final RoleGrantLogRepository roleGrantLogRepo;
    private final EntitlementService entitlementService;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final FirebaseIdTokenDecoder firebaseDecoder;
    /** Giao dịch tự quản cho đăng nhập Google: va chạm thì phải tra lại trong giao dịch MỚI (L-18). */
    private final TransactionTemplate txTemplate;

    private final SecureRandom rng = new SecureRandom();

    /** Danh sách email được cấp ADMIN ngay khi xác minh xong. Rỗng thì không ai tự cấp. */
    @Value("${app.admin-emails:}")
    String adminEmails;

    // ================================================================ đăng ký
    @Override
    @Transactional
    public AccountResponse register(RegisterRequest req) {
        String email = normalizeEmail(req == null ? null : req.email());
        if (email == null || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "email không đúng dạng");
        }
        validatePassword(req.password(), "mật khẩu");
        if (req.displayName() == null || req.displayName().isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "thiếu tên hiển thị");
        }
        if (email.length() > MAX_EMAIL_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "email tối đa " + MAX_EMAIL_LENGTH + " ký tự");
        }
        if (req.displayName().trim().length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "tên hiển thị tối đa " + MAX_DISPLAY_NAME_LENGTH + " ký tự");
        }
        if (req.phone() != null && req.phone().trim().length() > MAX_PHONE_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "số điện thoại tối đa " + MAX_PHONE_LENGTH + " ký tự");
        }
        Account existing = accountRepo.findByEmailIgnoreCase(email).orElse(null);
        if (existing != null) {
            if (existing.getEmailVerifiedAt() != null) {
                throw new AppException(ErrorCode.EMAIL_TAKEN);
            }
            return registerOverUnverified(existing, req, email);
        }

        Instant now = Instant.now(clock);
        Account a = new Account();
        a.setEmail(email);
        a.setPhone(trim(req.phone()));
        a.setDisplayName(req.displayName().trim());
        a.setFailedAttempts(0);
        a.setMustChangePassword(false);
        a.setCreatedAt(now);
        try {
            // saveAndFlush, không phải save: ràng buộc UNIQUE chỉ nổ lúc INSERT, mà INSERT bị
            // hoãn tới commit — nếu để tới đó thì ngoại lệ vỡ ra ngoài try/catch này.
            a = accountRepo.saveAndFlush(a);

            Credential c = new Credential();
            c.setAccount(a);
            c.setProvider(AuthProvider.PASSWORD);
            // subject của PASSWORD là chính email: đây là đường vào duy nhất, không có nhà cung cấp ngoài.
            c.setSubject(email);
            c.setEmailAtProvider(email);
            c.setPasswordHash(passwordEncoder.encode(req.password()));
            c.setCreatedAt(now);
            credentialRepo.save(c);
        } catch (DataIntegrityViolationException e) {
            // Hai request cùng email cùng vượt qua phép kiểm existsByEmail ở trên rồi cùng chèn.
            // Không bắt thì người dùng thấy 500 "Lỗi chưa phân loại" thay vì "email đã có tài khoản".
            // Ném AppException sẽ rollback transaction — không có Account nửa vời nào bị chừa lại.
            // CHỈ khi trùng khoá thật: 06/10 một cột NOT NULL sót lại trong DB production bị báo nhầm
            // thành "email đã có tài khoản" cho mọi email.
            if (!DbErrors.isUniqueViolation(e)) {
                throw e;
            }
            throw new AppException(ErrorCode.EMAIL_TAKEN);
        }

        sendVerificationMail(a, createOtp(a, TokenPurpose.VERIFY_EMAIL));

        // KHÔNG trả token: chưa xác minh thì chưa đăng nhập được, trả token chỉ là hứa hão.
        return AccountMapper.toResponse(a);
    }

    /**
     * Email đã có dòng nhưng chưa ai nhập được OTP → chưa có chủ (rà soát 09/10, S-01): ghi đè tên, SĐT,
     * mật khẩu bằng dữ liệu của người đăng ký lần này và gửi OTP mới (mã cũ bị huỷ trong createOtp).
     *
     * Trước đây ném 3001: ai đăng ký "nháp" bằng email người khác là KHOÁ được email đó (chủ thật không
     * đăng ký, không đăng nhập, không reset, không Google được), và nếu chủ thật xác minh qua "gửi lại mã"
     * thì mật khẩu vẫn là của kẻ đăng ký trước — hai người cùng vào một tài khoản. Ghi đè thì kẻ xấu đăng
     * ký lại bao nhiêu lần cũng không có mã: mã luôn về hộp thư của chủ email, và mật khẩu đang lưu luôn
     * là của người đăng ký gần nhất.
     */
    private AccountResponse registerOverUnverified(Account a, RegisterRequest req, String email) {
        Instant now = Instant.now(clock);
        a.setPhone(trim(req.phone()));
        a.setDisplayName(req.displayName().trim());
        a.setFailedAttempts(0);
        a.setLockedUntil(null);
        accountRepo.save(a);

        Credential c = credentialRepo.findByAccount_IdAndProvider(a.getId(), AuthProvider.PASSWORD).orElseGet(() -> {
            Credential n = new Credential();
            n.setAccount(a);
            n.setProvider(AuthProvider.PASSWORD);
            n.setSubject(email);
            n.setEmailAtProvider(email);
            n.setCreatedAt(now);
            return n;
        });
        c.setPasswordHash(passwordEncoder.encode(req.password()));
        credentialRepo.save(c);

        log.info("Đăng ký đè lên tài khoản chưa xác minh {}", a.getId());
        sendVerificationMail(a, createOtp(a, TokenPurpose.VERIFY_EMAIL));
        return AccountMapper.toResponse(a);
    }

    private static void validatePassword(String password, String label) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, label + " tối thiểu " + MIN_PASSWORD_LENGTH + " ký tự");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, label + " tối đa " + MAX_PASSWORD_BYTES + " byte");
        }
    }

    // ================================================================ xác minh email
    /**
     * Nhập mã OTP trong mail đăng ký. Đúng thì xác minh VÀ trả token luôn — người dùng vừa chứng
     * minh sở hữu hộp thư, bắt đăng nhập lại chỉ thêm một bước thừa.
     *
     * CỐ Ý KHÔNG @Transactional (cùng bẫy với login): nhập sai phải tăng bộ đếm rồi ném lỗi; trong
     * transaction thì rollback xoá luôn bộ đếm và 5 lần sai không bao giờ khoá được mã.
     */
    @Override
    public TokenResponse verifyEmail(VerifyEmailRequest req) {
        Account found = findAccountForOtp(req == null ? null : req.email(), TokenPurpose.VERIFY_EMAIL);
        // Không hỏi "đã xác minh chưa" trước khi kiểm mã: ném 3014 ở đây là oracle dò email không tốn mail
        // (rà soát 09/10, S-03). Đã xác minh thì không còn mã sống → tự ra 3012 như email lạ.
        VerificationToken checked = checkOtp(found, req.otp(), TokenPurpose.VERIFY_EMAIL);

        return txTemplate.execute(status -> {
            Instant now = Instant.now(clock);
            VerificationToken t = tokenRepo.findById(checked.getId()).orElseThrow();
            Account a = t.getAccount();
            a.setEmailVerifiedAt(now);
            t.setUsedAt(now);
            // Email đã xác minh ở phía mình thì cũng xác minh ở đường vào tương ứng.
            credentialRepo.findByAccount_IdAndProvider(a.getId(), AuthProvider.PASSWORD)
                    .ifPresent(c -> c.setEmailVerifiedAt(now));
            grantBootstrapAdmin(a);
            log.info("Xác minh email tài khoản {} xong", a.getId());
            return issueToken(a);
        });
    }

    // ================================================================ đăng nhập
    /**
     * CỐ Ý KHÔNG @Transactional.
     *
     * Sai mật khẩu thì phải tăng bộ đếm rồi NÉM lỗi. Ném lỗi trong transaction là rollback —
     * bộ đếm bị xoá sạch và khoá chống dò không bao giờ đóng. Bỏ transaction thì mỗi save tự
     * commit ngay, đếm trụ lại được; mỗi nhánh ở đây chỉ có đúng một save nên không mất tính
     * nguyên tử. Đây đúng là bẫy đã ghi ở commit a982abc, chỉ khác tên biến.
     */
    @Override
    public TokenResponse login(LoginRequest req) {
        String email = normalizeEmail(req == null ? null : req.email());
        Account a = email == null ? null : accountRepo.findByEmailIgnoreCase(email).orElse(null);
        if (a == null || req.password() == null) {
            throw new AppException(ErrorCode.BAD_CREDENTIALS);
        }
        Instant now = Instant.now(clock);
        if (a.getLockedUntil() != null && a.getLockedUntil().isAfter(now)) {
            throw new AppException(ErrorCode.ACCOUNT_LOCKED);
        }
        Credential c = credentialRepo.findByAccount_IdAndProvider(a.getId(), AuthProvider.PASSWORD)
                .orElseThrow(() -> new AppException(ErrorCode.BAD_CREDENTIALS));

        if (!passwordEncoder.matches(req.password(), c.getPasswordHash())) {
            // Một câu UPDATE nguyên tử thay cho đọc → +1 → save (rà soát 09/10, S-04).
            accountRepo.recordFailedPassword(a.getId(), MAX_PASSWORD_ATTEMPTS, now.plus(PASSWORD_LOCK_DURATION));
            throw new AppException(ErrorCode.BAD_CREDENTIALS);
        }

        // Mật khẩu ĐÚNG rồi mới nói "chưa xác minh" — đảo lại thứ tự là lộ ra email nào đã đăng ký.
        if (a.getEmailVerifiedAt() == null) {
            throw new AppException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        accountRepo.resetFailedPassword(a.getId());
        credentialRepo.touchLastUsed(c.getId(), now);

        return tokenService.issueForAccount(a.getId(), scopes(a.getId()), a.getTokenVersion());
    }

    // ================================================================ quên mật khẩu
    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordRequest req) {
        String email = normalizeEmail(req == null ? null : req.email());
        if (email == null) {
            return;
        }
        Account a = accountRepo.findByEmailIgnoreCase(email).orElse(null);
        // Email không tồn tại, hoặc tồn tại mà chưa xác minh: IM LẶNG. Trả 200 ở cả hai nhánh —
        // trả lỗi ở nhánh "không tồn tại" là trao công cụ dò email cho kẻ khác.
        if (a == null || a.getEmailVerifiedAt() == null) {
            return;
        }
        // Gửi quá dày cũng IM LẶNG. Ném 3019 thì kẻ biết email người khác gửi 2 lần là kết luận
        // được email đó đã đăng ký — đúng cái mà nhánh trên cố không lộ.
        if (isResendTooSoon(a, TokenPurpose.RESET_PASSWORD)) {
            return;
        }
        sendResetPasswordMail(a, createOtp(a, TokenPurpose.RESET_PASSWORD));
    }

    /**
     * Gửi lại mail xác minh. Tài khoản không có thì cũng trả 200, y hệt quên mật khẩu.
     * Đã xác minh rồi thì không gửi lại gì cả — không có việc gì để xác minh nữa.
     */
    @Override
    @Transactional
    public void resendVerification(String email) {
        String e = normalizeEmail(email);
        Account a = e == null ? null : accountRepo.findByEmailIgnoreCase(e).orElse(null);
        // Không có, hoặc đã xác minh: im lặng — y hệt quên mật khẩu, không được lộ email nào đã đăng ký.
        if (a == null || a.getEmailVerifiedAt() != null) {
            return;
        }
        if (isResendTooSoon(a, TokenPurpose.VERIFY_EMAIL)) {
            return;
        }
        sendVerificationMail(a, createOtp(a, TokenPurpose.VERIFY_EMAIL));
    }

    /** CỐ Ý KHÔNG @Transactional — xem verifyEmail: bộ đếm nhập sai phải sống qua lỗi. */
    @Override
    public void resetPassword(ResetPasswordRequest req) {
        if (req == null || req.newPassword() == null || req.newPassword().length() < MIN_PASSWORD_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "mật khẩu mới tối thiểu " + MIN_PASSWORD_LENGTH + " ký tự");
        }
        Account found = findAccountForOtp(req.email(), TokenPurpose.RESET_PASSWORD);
        VerificationToken checked = checkOtp(found, req.otp(), TokenPurpose.RESET_PASSWORD);
        txTemplate.executeWithoutResult(status -> applyNewPassword(checked.getId(), req.newPassword()));
    }

    private void applyNewPassword(UUID tokenId, String newPassword) {
        VerificationToken t = tokenRepo.findById(tokenId).orElseThrow();
        Account a = t.getAccount();
        Instant now = Instant.now(clock);

        Credential c = credentialRepo.findByAccount_IdAndProvider(a.getId(), AuthProvider.PASSWORD)
                .orElseThrow(() -> new AppException(ErrorCode.BAD_CREDENTIALS));
        c.setPasswordHash(passwordEncoder.encode(newPassword));
        c.setLastUsedAt(now);
        credentialRepo.save(c);

        // Đổi mật khẩu xong thì mở khoá: người dùng đã chứng minh mình sở hữu hộp thư.
        a.setFailedAttempts(0);
        a.setLockedUntil(null);
        a.setMustChangePassword(false);
        // Tăng phiên bản token TRƯỚC khi đổi: mọi token phát trước đó (kể cả của kẻ đang cầm)
        // mang phiên bản cũ nên RevocationAwareJwtDecoder từ chối ngay lần gọi kế tiếp.
        a.setTokenVersion(a.getTokenVersion() + 1);
        accountRepo.save(a);

        // Vô hiệu MỌI mã đặt lại còn hiệu lực, không chỉ mã vừa dùng.
        tokenRepo.findByAccount_IdAndPurposeAndUsedAtIsNull(a.getId(), TokenPurpose.RESET_PASSWORD)
                .forEach(x -> x.setUsedAt(now));

        log.info("Tài khoản {} đặt lại mật khẩu xong", a.getId());
    }

    // ================================================================ tôi là ai / đổi mật khẩu
    /**
     * Gói và vai ĐANG có, lấy từ database chứ không tin JWT — cùng một nguồn với
     * EntitlementService.activePlans mà các endpoint khác vẫn gọi lại mỗi lần.
     */
    @Override
    @Transactional(readOnly = true)
    public AccountResponse getMe(UUID accountId) {
        Account a = accountRepo.findById(accountId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        Set<String> plans = new HashSet<>();
        entitlementService.activePlans(accountId).forEach(k -> plans.add(k.name()));
        List<String> roles = accountRoleRepo.findByAccount_Id(accountId).stream()
                .map(r -> r.getRole().name())
                .toList();
        return AccountMapper.toResponse(a, plans, roles);
    }

    /**
     * CỐ Ý KHÔNG @Transactional (cùng bẫy với login): sai mật khẩu cũ phải đếm và khoá như /login — trước
     * đây ai cầm token đánh cắp (sống 7 ngày) dò mật khẩu hiện tại ở đây không giới hạn (rà soát 09/10,
     * S-05). Phần ghi (tokenVersion + hash mới) gói trong txTemplate để vẫn nguyên tử.
     */
    @Override
    public TokenResponse changePassword(UUID accountId, ChangePasswordRequest req) {
        if (req == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "thiếu body");
        }
        validatePassword(req.newPassword(), "mật khẩu mới");
        Account a = accountRepo.findById(accountId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        Instant now = Instant.now(clock);
        if (a.getLockedUntil() != null && a.getLockedUntil().isAfter(now)) {
            throw new AppException(ErrorCode.ACCOUNT_LOCKED);
        }
        Credential c = credentialRepo.findByAccount_IdAndProvider(a.getId(), AuthProvider.PASSWORD)
                .orElseThrow(() -> new AppException(ErrorCode.BAD_CREDENTIALS));

        // Mật khẩu cũ là bằng chứng sở hữu. Bỏ kiểm này thì token đánh cắp cũng đổi được
        // mật khẩu rồi khoá chủ thật ra ngoài.
        if (req.oldPassword() == null || !passwordEncoder.matches(req.oldPassword(), c.getPasswordHash())) {
            accountRepo.recordFailedPassword(a.getId(), MAX_PASSWORD_ATTEMPTS, now.plus(PASSWORD_LOCK_DURATION));
            throw new AppException(ErrorCode.BAD_CREDENTIALS);
        }

        return txTemplate.execute(status -> {
            Account acc = accountRepo.findById(accountId)
                    .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
            Credential cred = credentialRepo.findById(c.getId())
                    .orElseThrow(() -> new AppException(ErrorCode.BAD_CREDENTIALS));
            acc.setTokenVersion(acc.getTokenVersion() + 1);
            acc.setFailedAttempts(0);
            acc.setLockedUntil(null);
            acc.setMustChangePassword(false);
            accountRepo.save(acc);

            cred.setPasswordHash(passwordEncoder.encode(req.newPassword()));
            cred.setLastUsedAt(now);
            credentialRepo.save(cred);

            log.info("Tài khoản {} đổi mật khẩu khi đang đăng nhập — token cũ đã thu hồi", acc.getId());
            return tokenService.issueForAccount(acc.getId(), scopes(acc.getId()), acc.getTokenVersion());
        });
    }

    // ================================================================ Google (Firebase)
    /**
     * Nút "Đăng nhập bằng Google". FE lấy ID token của Firebase rồi gửi vào đây; BE kiểm chữ ký
     * Google rồi phát JWT FinTeen như đăng nhập mật khẩu — không giữ lại phiên nào của Google.
     *
     * KHÔNG tự gộp khi email đã có tài khoản: người kia có thể đăng ký bằng mật khẩu từ trước
     * và chưa từng biết Google. Trả 3020 để họ đăng nhập mật khẩu rồi tự liên kết (hàm dưới).
     */
    /**
     * CỐ Ý KHÔNG @Transactional (L-18, 04/10). Bấm nút Google hai lần ở lần đầu là hai request cùng
     * tạo một tài khoản: request sau đụng UNIQUE (PostgreSQL) hoặc chờ khoá tới hết giờ (H2). Trong
     * một @Transactional duy nhất thì giao dịch đã hỏng, không tra lại được gì — người dùng nhận 500
     * (H2) hoặc 3020 "email đã có tài khoản" (PostgreSQL), mà đó chính là họ.
     *
     * Nên: kiểm ID token NGOÀI giao dịch (gọi mạng tới Google, không giữ khoá DB trong lúc chờ),
     * tạo / tìm tài khoản trong một giao dịch, va chạm thì tra lại Credential(GOOGLE, sub) trong
     * giao dịch MỚI — thấy thì đăng nhập vào đúng tài khoản request kia vừa tạo. Chỉ khi email thuộc
     * một tài khoản KHÁC (đăng ký mật khẩu) mới trả 3020.
     */
    @Override
    public TokenResponse loginWithGoogle(LinkCredentialRequest req) {
        FirebaseIdTokenDecoder.FirebaseUser user = validateGoogleRequest(req);

        // Phát token NGAY TRONG giao dịch: Account lấy qua Credential là proxy lười, ra ngoài giao
        // dịch mới đọc tokenVersion là LazyInitializationException.
        try {
            return txTemplate.execute(status -> issueToken(findOrCreateGoogleAccount(user)));
        } catch (DataIntegrityViolationException | PessimisticLockingFailureException conflict) {
            if (conflict instanceof DataIntegrityViolationException && !DbErrors.isUniqueViolation(conflict)) {
                throw conflict;
            }
            log.info("Đăng nhập Google {} va chạm với request song song — tra lại", user.sub());
            TokenResponse t = txTemplate.execute(status -> credentialRepo
                    .findByProviderAndSubject(AuthProvider.GOOGLE, user.sub())
                    .map(c -> issueToken(c.getAccount()))
                    .orElse(null));
            if (t == null) {
                // Không phải chính người này tạo song song: email vừa bị một đăng ký mật khẩu chiếm.
                throw new AppException(ErrorCode.GOOGLE_EMAIL_EXISTS);
            }
            return t;
        }
    }

    private TokenResponse issueToken(Account a) {
        return tokenService.issueForAccount(a.getId(), scopes(a.getId()), a.getTokenVersion());
    }

    /** Một giao dịch: có Credential thì cập nhật lần dùng; chưa có thì tạo tài khoản mới. */
    private Account findOrCreateGoogleAccount(FirebaseIdTokenDecoder.FirebaseUser user) {
        Credential existing = credentialRepo.findByProviderAndSubject(AuthProvider.GOOGLE, user.sub())
                .orElse(null);
        if (existing != null) {
            existing.setLastUsedAt(Instant.now(clock));
            credentialRepo.save(existing);
            return existing.getAccount();
        }
        Account byEmail = accountRepo.findByEmailIgnoreCase(normalizeEmail(user.email())).orElse(null);
        if (byEmail != null) {
            if (byEmail.getEmailVerifiedAt() != null) {
                throw new AppException(ErrorCode.GOOGLE_EMAIL_EXISTS);
            }
            return takeOverUnverifiedWithGoogle(byEmail, user);
        }
        return createGoogleAccount(user);
    }

    /**
     * Dòng cùng email tồn tại nhưng CHƯA AI xác minh, còn Google đã xác nhận hộp thư → Google là chủ
     * (rà soát 09/10, S-01): gỡ mật khẩu do người lạ đặt, huỷ OTP cũ, gắn Google, đánh dấu đã xác minh.
     * Trước đây ném 3020 nên một đăng ký "nháp" bằng email người khác chặn luôn cả đường Google.
     */
    private Account takeOverUnverifiedWithGoogle(Account a, FirebaseIdTokenDecoder.FirebaseUser user) {
        Instant now = Instant.now(clock);
        credentialRepo.findByAccount_IdAndProvider(a.getId(), AuthProvider.PASSWORD).ifPresent(credentialRepo::delete);
        tokenRepo.findByAccount_IdAndPurposeAndUsedAtIsNull(a.getId(), TokenPurpose.VERIFY_EMAIL)
                .forEach(t -> t.setUsedAt(now));
        a.setDisplayName(googleDisplayName(user, a.getEmail()));
        a.setEmailVerifiedAt(now);
        a.setFailedAttempts(0);
        a.setLockedUntil(null);
        accountRepo.saveAndFlush(a);

        Credential c = new Credential();
        c.setAccount(a);
        c.setProvider(AuthProvider.GOOGLE);
        c.setSubject(user.sub());
        c.setEmailAtProvider(a.getEmail());
        c.setCreatedAt(now);
        c.setLastUsedAt(now);
        credentialRepo.saveAndFlush(c);

        grantBootstrapAdmin(a);
        log.info("Google chiếm tài khoản chưa xác minh {}", a.getId());
        return a;
    }

    /**
     * Gắn Google vào tài khoản ĐANG đăng nhập. Chỉ người đang cầm JWT FinTeen mới gọi được
     * (controller lấy accountId từ token), nên không cần hỏi lại mật khẩu.
     *
     * Không cho một uid đã gắn tài khoản khác đổi chủ: đó là dấu hiệu gộp nhầm hoặc cố chiếm —
     * trả 3021 thay vì lặng lẽ đổi liên kết.
     */
    @Override
    @Transactional
    public void linkCredential(UUID accountId, LinkCredentialRequest req) {
        FirebaseIdTokenDecoder.FirebaseUser user = validateGoogleRequest(req);
        Account a = accountRepo.findById(accountId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));

        Credential linked = credentialRepo.findByProviderAndSubject(AuthProvider.GOOGLE, user.sub())
                .orElse(null);
        if (linked != null) {
            if (!accountId.equals(linked.getAccount().getId())) {
                throw new AppException(ErrorCode.GOOGLE_ALREADY_LINKED);
            }
            return; // đã gắn rồi — gọi lại là lặp vô hại, không tạo Credential thứ hai
        }

        Instant now = Instant.now(clock);
        Credential c = new Credential();
        c.setAccount(a);
        c.setProvider(AuthProvider.GOOGLE);
        c.setSubject(user.sub());
        c.setEmailAtProvider(normalizeEmail(user.email()));
        c.setCreatedAt(now);
        c.setLastUsedAt(now);
        credentialRepo.save(c);

        // Chỉ công nhận "email đã xác minh" khi hai email TRÙNG: Google xác minh email của nó,
        // không phải email đang đứng trong tài khoản này.
        if (a.getEmailVerifiedAt() == null && normalizeEmail(user.email()).equals(normalizeEmail(a.getEmail()))) {
            a.setEmailVerifiedAt(now);
            accountRepo.save(a);
        }
        log.info("Tài khoản {} liên kết Google {}", accountId, user.sub());
    }

    /**
     * Bean chỉ xác minh được chữ ký / hạn / project. Còn email và việc đã xác minh hay chưa là
     * điều kiện MỞ tài khoản nên phải kiểm lại ở đây — không tin một claim chỉ vì chữ ký đúng.
     */
    private FirebaseIdTokenDecoder.FirebaseUser validateGoogleRequest(LinkCredentialRequest req) {
        if (req == null || req.idToken() == null || req.idToken().isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "thiếu idToken");
        }
        if (req.provider() != null && !AuthProvider.GOOGLE.name().equalsIgnoreCase(req.provider().trim())) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "provider phải là GOOGLE");
        }

        FirebaseIdTokenDecoder.FirebaseUser user = firebaseDecoder.decode(req.idToken());
        if (user.sub() == null || user.sub().isBlank()) {
            throw new AppException(ErrorCode.BAD_CREDENTIALS, "ID token thiếu subject");
        }
        String email = normalizeEmail(user.email());
        if (email == null || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new AppException(ErrorCode.BAD_CREDENTIALS, "ID token thiếu email hợp lệ");
        }
        if (!user.emailVerified()) {
            throw new AppException(ErrorCode.BAD_CREDENTIALS, "email của Google chưa xác minh");
        }
        return user;
    }

    /** Tên lấy từ Google; không có thì cắt phần trước @ của email. Cắt 80 ký tự cho vừa cột. */
    private static String googleDisplayName(FirebaseIdTokenDecoder.FirebaseUser user, String email) {
        String name = trim(user.name());
        if (name == null || name.isBlank()) {
            name = email.substring(0, email.indexOf('@'));
        }
        return name.length() > 80 ? name.substring(0, 80) : name;
    }

    private Account createGoogleAccount(FirebaseIdTokenDecoder.FirebaseUser user) {
        Instant now = Instant.now(clock);
        Account a = new Account();
        a.setEmail(normalizeEmail(user.email()));
        a.setDisplayName(googleDisplayName(user, a.getEmail()));
        // Google đã xác minh email này nên không bắt người dùng xác minh thêm lần nữa.
        a.setEmailVerifiedAt(now);
        a.setFailedAttempts(0);
        a.setMustChangePassword(false);
        a.setCreatedAt(now);
        // Va chạm UNIQUE / khoá KHÔNG bắt ở đây: để nó thoát ra, giao dịch rollback sạch, rồi
        // loginWithGoogle tra lại trong giao dịch mới (L-18).
        a = accountRepo.saveAndFlush(a);

        Credential c = new Credential();
        c.setAccount(a);
        c.setProvider(AuthProvider.GOOGLE);
        // subject là uid Firebase, KHÔNG phải email: email đổi được, uid thì không.
        c.setSubject(user.sub());
        c.setEmailAtProvider(a.getEmail());
        c.setCreatedAt(now);
        c.setLastUsedAt(now);
        credentialRepo.saveAndFlush(c);

        // L-16: email đã được Google xác minh, nên người trong ADMIN_EMAILS đăng ký bằng Google cũng
        // được cấp ADMIN như khi xác minh mail — trước đây chỉ luồng mật khẩu mới cấp.
        grantBootstrapAdmin(a);
        log.info("Tài khoản {} tạo bằng Google", a.getId());
        return a;
    }

    // ================================================================ lõi
    /**
     * Vai trong token: ADMIN lấy từ AccountRole, PARENT / TEACHER lấy từ gói còn hạn.
     * Chỉ chặn cửa sớm — thao tác cần gói vẫn phải gọi lại EntitlementService.activePlans.
     */
    private Set<String> scopes(UUID accountId) {
        Set<String> s = new HashSet<>();
        accountRoleRepo.findByAccount_Id(accountId).forEach(r -> s.add(r.getRole().name()));
        entitlementService.activePlans(accountId).forEach(k -> s.add(k.name()));
        return s;
    }

    /**
     * Sinh mã OTP 6 số, trả mã GỐC để đưa vào mail; bảng chỉ giữ hash BCrypt (có muối, nên hai
     * người trùng mã không trùng hash — cột tokenHash vẫn UNIQUE được). Mã cũ chưa dùng của cùng
     * mục đích bị huỷ: lúc nào cũng chỉ MỘT mã sống, "gửi lại mã" không nhân đôi số lần đoán.
     */
    private String createOtp(Account a, TokenPurpose purpose) {
        Instant now = Instant.now(clock);
        tokenRepo.findByAccount_IdAndPurposeAndUsedAtIsNull(a.getId(), purpose).forEach(x -> x.setUsedAt(now));
        String otp = String.format("%06d", rng.nextInt(1_000_000));
        VerificationToken t = new VerificationToken();
        t.setAccount(a);
        t.setPurpose(purpose);
        t.setTokenHash(passwordEncoder.encode(otp));
        t.setAttempts(0);
        t.setExpiresAt(now.plus(OTP_TTL));
        t.setCreatedAt(now);
        tokenRepo.save(t);
        return otp;
    }

    /** Email không có tài khoản trả CÙNG lỗi với mã sai — không lộ email nào đã đăng ký. */
    private Account findAccountForOtp(String email, TokenPurpose purpose) {
        String e = normalizeEmail(email);
        Account a = e == null ? null : accountRepo.findByEmailIgnoreCase(e).orElse(null);
        if (a == null) {
            throw new AppException(invalidTokenError(purpose));
        }
        return a;
    }

    /**
     * Kiểm mã mới nhất còn sống. Mỗi lần gọi trừ một lượt ngay trong DB (nơi gọi không có transaction)
     * rồi mới so; hết 5 lượt thì mã chết, kể cả lần sau gõ đúng.
     * Hết hạn và sai là hai lỗi khác nhau: hết hạn thì bảo "gửi lại mã", sai thì bảo "không đúng".
     */
    private VerificationToken checkOtp(Account a, String otp, TokenPurpose purpose) {
        ErrorCode invalid = invalidTokenError(purpose);
        // Lấy mã CÒN SỐNG (createOtp bảo đảm tối đa một), không lấy "mới nhất theo createdAt": hai mã sinh
        // cùng mili-giây (đồng hồ đóng băng trong test, hoặc gửi lại mã rất nhanh) là hoà, có thể trúng mã đã huỷ.
        VerificationToken t = tokenRepo.findByAccount_IdAndPurposeAndUsedAtIsNull(a.getId(), purpose).stream()
                .max(Comparator.comparing(VerificationToken::getCreatedAt))
                .orElseThrow(() -> new AppException(invalid));
        if (t.getExpiresAt().isBefore(Instant.now(clock))) {
            throw new AppException(purpose == TokenPurpose.RESET_PASSWORD
                    ? ErrorCode.RESET_TOKEN_EXPIRED : ErrorCode.VERIFY_TOKEN_EXPIRED);
        }
        // Trừ lượt TRƯỚC khi so, bằng UPDATE có điều kiện attempts < max: N request song song không thể
        // cùng "thấy 0 lần sai" (rà soát 09/10, S-04). 0 dòng = mã đã chết, kể cả lần sau gõ đúng.
        if (tokenRepo.consumeAttempt(t.getId(), MAX_OTP_ATTEMPTS) == 0) {
            throw new AppException(invalid);
        }
        String code = otp == null ? "" : otp.trim();
        if (!OTP_PATTERN.matcher(code).matches() || !passwordEncoder.matches(code, t.getTokenHash())) {
            throw new AppException(invalid);
        }
        return t;
    }

    private static ErrorCode invalidTokenError(TokenPurpose expectedPurpose) {
        return expectedPurpose == TokenPurpose.RESET_PASSWORD
                ? ErrorCode.RESET_TOKEN_INVALID : ErrorCode.VERIFY_TOKEN_INVALID;
    }

    /**
     * true nếu mục đích này vừa gửi mail trong RESEND_COOLDOWN.
     *
     * TRẢ BOOLEAN chứ không ném lỗi: cả hai nơi gọi đều phải im lặng và vẫn trả 200. Ném
     * 3015/3019 ra HTTP thì người ta gửi 2 lần, thấy lỗi, biết chắc email đó đã đăng ký.
     */
    private boolean isResendTooSoon(Account a, TokenPurpose purpose) {
        return tokenRepo.findTopByAccount_IdAndPurposeOrderByCreatedAtDesc(a.getId(), purpose)
                .filter(t -> t.getCreatedAt().isAfter(Instant.now(clock).minus(RESEND_COOLDOWN)))
                .isPresent();
    }

    /**
     * Admin đầu tiên: email nằm trong ADMIN_EMAILS mà xác minh xong thì tự cấp ADMIN, có sổ ghi.
     *
     * Chỉ cấp LÚC XÁC MINH, không cấp lúc đăng ký — cấp lúc đăng ký thì ai đăng ký trước bằng
     * đúng email đó sẽ chiếm quyền admin, và đó là email của người quản trị.
     */
    private void grantBootstrapAdmin(Account a) {
        if (!isInAdminList(a.getEmail())) {
            return;
        }
        if (accountRoleRepo.existsByAccount_IdAndRole(a.getId(), Role.ADMIN)) {
            return;
        }
        Instant now = Instant.now(clock);
        AccountRole r = new AccountRole();
        r.setAccount(a);
        r.setRole(Role.ADMIN);
        r.setGrantedAt(now);
        accountRoleRepo.save(r);

        RoleGrantLog grantLog = new RoleGrantLog();
        grantLog.setActor(a);
        grantLog.setTarget(a);
        grantLog.setRole(Role.ADMIN.name());
        grantLog.setGranted(true);
        grantLog.setReason("ADMIN_EMAILS bootstrap");
        grantLog.setCreatedAt(now);
        roleGrantLogRepo.save(grantLog);
        log.info("Cấp ADMIN cho tài khoản {} theo danh sách ADMIN_EMAILS", a.getId());
    }

    /**
     * Có nằm trong ADMIN_EMAILS không — so KHỚP TỪNG EMAIL, không dùng {@code String.contains}.
     *
     * {@code contains} là tìm chuỗi con: với ADMIN_EMAILS=admin@fifteen.com, email
     * {@code min@fifteen.com} là chuỗi con nên cũng được cấp ADMIN. Đó là leo thang đặc quyền
     * chỉ cần tự đăng ký đúng một tài khoản, và không cần biết mật khẩu admin.
     *
     * Tách theo dấu phẩy vì biến môi trường là {@code a@x.com,b@y.com}; bỏ khoảng trắng thừa quanh
     * dấu phẩy vì {@code ADMIN_EMAILS="a@x.com, b@y.com"} rất dễ viết tay thế. So
     * {@code equalsIgnoreCase} vì domain không phân biệt hoa thường còn phần local thì có, và
     * người dùng gõ email tay nên không thể kỳ vọng chữ hoa chữ thường khớp tuyệt đối.
     */
    private boolean isInAdminList(String email) {
        if (email == null || adminEmails == null || adminEmails.isBlank()) {
            return false;
        }
        for (String entry : adminEmails.split(",")) {
            if (!entry.isBlank() && email.equalsIgnoreCase(entry.trim())) {
                return true;
            }
        }
        return false;
    }

    private void sendVerificationMail(Account a, String otp) {
        events.publishEvent(new OutgoingMail(a.getEmail(), "Mã xác minh FinTeen",
                """
                        <p>Chào %s,</p>
                        <p>Cảm ơn bạn đã tạo tài khoản FinTeen. Nhập mã dưới đây vào ứng dụng để xác minh email:</p>
                        <p style="font-size:28px;font-weight:bold;letter-spacing:6px">%s</p>
                        <p>Mã hết hạn sau 10 phút và chỉ dùng được một lần. Không phải bạn đăng ký thì kệ,
                        không có gì xảy ra.</p>
                        """.formatted(escape(a.getDisplayName()), otp)));
    }

    private void sendResetPasswordMail(Account a, String otp) {
        events.publishEvent(new OutgoingMail(a.getEmail(), "Mã đặt lại mật khẩu FinTeen",
                """
                        <p>Chào %s,</p>
                        <p>Có yêu cầu đặt lại mật khẩu cho tài khoản này. Nhập mã dưới đây vào ứng dụng
                        cùng mật khẩu mới:</p>
                        <p style="font-size:28px;font-weight:bold;letter-spacing:6px">%s</p>
                        <p>Mã hết hạn sau 10 phút và chỉ dùng được một lần. Không phải bạn yêu cầu thì đừng
                        đưa mã cho ai — mật khẩu hiện tại của bạn vẫn giữ nguyên.</p>
                        """.formatted(escape(a.getDisplayName()), otp)));
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /** Chèn vào HTML thì thoát ký tự đặc biệt — tên người dùng là dữ liệu, không phải mã. */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
