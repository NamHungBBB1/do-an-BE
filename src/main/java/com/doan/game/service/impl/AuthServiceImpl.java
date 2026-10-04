package com.doan.game.service.impl;

import com.doan.game.DTO.request.DoiMatKhauRequest;
import com.doan.game.DTO.request.ForgotPasswordRequest;
import com.doan.game.DTO.request.LinkCredentialRequest;
import com.doan.game.DTO.request.LoginRequest;
import com.doan.game.DTO.request.RegisterRequest;
import com.doan.game.DTO.request.ResetPasswordRequest;
import com.doan.game.DTO.response.AccountResponse;
import com.doan.game.DTO.response.TokenResponse;
import com.doan.game.configuration.FirebaseIdTokenDecoder;
import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.entity.Credential;
import com.doan.game.entity.RoleGrantLog;
import com.doan.game.entity.VerificationToken;
import com.doan.game.enums.AuthProvider;
import com.doan.game.enums.Role;
import com.doan.game.enums.TokenPurpose;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.mapper.AccountMapper;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.CredentialRepository;
import com.doan.game.repository.RoleGrantLogRepository;
import com.doan.game.repository.VerificationTokenRepository;
import com.doan.game.service.AuthService;
import com.doan.game.service.EntitlementService;
import com.doan.game.service.MailCanGui;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
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
 *  5. dangNhap CỐ Ý KHÔNG @Transactional — xem chú thích ngay tại hàm.
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    /** Link xác minh email dùng lâu, đặt lại mật khẩu thì ngắn — token đó mở được tài khoản. */
    private static final Duration HAN_XAC_MINH = Duration.ofHours(24);
    private static final Duration HAN_DAT_LAI_MAT_KHAU = Duration.ofMinutes(30);
    /** Gửi lại mail cách nhau tối thiểu một phút, chặn spam tới hộp thư người khác. */
    private static final Duration GOI_HAN_GUI_LAI = Duration.ofMinutes(1);
    private static final int LAN_SAI_TOI_DA = 5;
    private static final Duration KHOA_SAI_MAT_KHAU = Duration.ofMinutes(15);

    private static final Pattern DANG_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MIN_MAT_KHAU = 8;

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
    private final TransactionTemplate giaoDich;

    private final SecureRandom rng = new SecureRandom();

    /** Gốc URL đặt trong link xác minh. Không có sau dấu "/" để nối link cho sạch. */
    @Value("${app.public-base-url:http://localhost:8080}")
    String publicBaseUrl;

    /** Danh sách email được cấp ADMIN ngay khi xác minh xong. Rỗng thì không ai tự cấp. */
    @Value("${app.admin-emails:}")
    String adminEmails;

    // ================================================================ đăng ký
    @Override
    @Transactional
    public AccountResponse dangKy(RegisterRequest req) {
        String email = chuanHoa(req == null ? null : req.email());
        if (email == null || !DANG_EMAIL.matcher(email).matches()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "email không đúng dạng");
        }
        if (req.password() == null || req.password().length() < MIN_MAT_KHAU) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "mật khẩu tối thiểu " + MIN_MAT_KHAU + " ký tự");
        }
        if (req.displayName() == null || req.displayName().isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "thiếu tên hiển thị");
        }
        if (accountRepo.existsByEmailIgnoreCase(email)) {
            throw new AppException(ErrorCode.EMAIL_TAKEN);
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
            throw new AppException(ErrorCode.EMAIL_TAKEN);
        }

        guiMailXacMinh(a, taoToken(a, TokenPurpose.VERIFY_EMAIL, HAN_XAC_MINH));

        // KHÔNG trả token: chưa xác minh thì chưa đăng nhập được, trả token chỉ là hứa hão.
        return AccountMapper.sang(a);
    }

    // ================================================================ xác minh email
    @Override
    @Transactional
    public void xacMinhEmail(String tokenGoc) {
        VerificationToken t = layToken(tokenGoc, TokenPurpose.VERIFY_EMAIL);

        Account a = t.getAccount();
        Instant now = Instant.now(clock);
        if (a.getEmailVerifiedAt() != null) {
            throw new AppException(ErrorCode.EMAIL_ALREADY_VERIFIED);
        }
        a.setEmailVerifiedAt(now);
        t.setUsedAt(now);
        // Email đã xác minh ở phía mình thì cũng xác minh ở đường vào tương ứng.
        credentialRepo.findByAccount_IdAndProvider(a.getId(), AuthProvider.PASSWORD)
                .ifPresent(c -> c.setEmailVerifiedAt(now));

        capAdminDauTien(a);
        log.info("Xác minh email tài khoản {} xong", a.getId());
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
    public TokenResponse dangNhap(LoginRequest req) {
        String email = chuanHoa(req == null ? null : req.email());
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
            int lan = (a.getFailedAttempts() == null ? 0 : a.getFailedAttempts()) + 1;
            if (lan >= LAN_SAI_TOI_DA) {
                a.setFailedAttempts(0);
                a.setLockedUntil(now.plus(KHOA_SAI_MAT_KHAU));
            } else {
                a.setFailedAttempts(lan);
            }
            accountRepo.save(a);
            throw new AppException(ErrorCode.BAD_CREDENTIALS);
        }

        // Mật khẩu ĐÚNG rồi mới nói "chưa xác minh" — đảo lại thứ tự là lộ ra email nào đã đăng ký.
        if (a.getEmailVerifiedAt() == null) {
            throw new AppException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        a.setFailedAttempts(0);
        a.setLockedUntil(null);
        accountRepo.save(a);
        c.setLastUsedAt(now);
        credentialRepo.save(c);

        return tokenService.choNguoiLon(a.getId(), scopes(a.getId()), a.getTokenVersion());
    }

    // ================================================================ quên mật khẩu
    @Override
    @Transactional
    public void quenMatKhau(ForgotPasswordRequest req) {
        String email = chuanHoa(req == null ? null : req.email());
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
        if (chanGuiLai(a, TokenPurpose.RESET_PASSWORD)) {
            return;
        }
        TokenCap tc = taoToken(a, TokenPurpose.RESET_PASSWORD, HAN_DAT_LAI_MAT_KHAU);
        guiMailDatLaiMatKhau(a, tc);
    }

    /**
     * Gửi lại mail xác minh. Tài khoản không có thì cũng trả 200, y hệt quên mật khẩu.
     * Đã xác minh rồi thì không gửi lại gì cả — không có việc gì để xác minh nữa.
     */
    @Override
    @Transactional
    public void guiLaiXacMinh(String email) {
        String e = chuanHoa(email);
        Account a = e == null ? null : accountRepo.findByEmailIgnoreCase(e).orElse(null);
        // Không có, hoặc đã xác minh: im lặng — y hệt quên mật khẩu, không được lộ email nào đã đăng ký.
        if (a == null || a.getEmailVerifiedAt() != null) {
            return;
        }
        if (chanGuiLai(a, TokenPurpose.VERIFY_EMAIL)) {
            return;
        }
        guiMailXacMinh(a, taoToken(a, TokenPurpose.VERIFY_EMAIL, HAN_XAC_MINH));
    }

    @Override
    @Transactional
    public void datLaiMatKhau(ResetPasswordRequest req) {
        if (req == null || req.newPassword() == null || req.newPassword().length() < MIN_MAT_KHAU) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "mật khẩu mới tối thiểu " + MIN_MAT_KHAU + " ký tự");
        }
        VerificationToken t = layToken(req.token(), TokenPurpose.RESET_PASSWORD);
        Account a = t.getAccount();
        Instant now = Instant.now(clock);

        Credential c = credentialRepo.findByAccount_IdAndProvider(a.getId(), AuthProvider.PASSWORD)
                .orElseThrow(() -> new AppException(ErrorCode.BAD_CREDENTIALS));
        c.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        c.setLastUsedAt(now);
        credentialRepo.save(c);

        // Đổi mật khẩu xong thì mở khoá: người dùng đã chứng minh mình sở hữu hộp thư.
        a.setFailedAttempts(0);
        a.setLockedUntil(null);
        a.setMustChangePassword(false);
        // Tăng phiên bản token TRƯỚC khi đổi: mọi token phát trước đó (kể cả của kẻ đang cầm)
        // mang phiên bản cũ nên TokenThuHoiDecoder từ chối ngay lần gọi kế tiếp.
        a.setTokenVersion(a.getTokenVersion() + 1);
        accountRepo.save(a);

        // Vô hiệu MỌI link đặt lại còn hiệu lực, không chỉ link vừa dùng — nếu không thì một link
        // cũ bị chặn chưa ai dùng vẫn dùng được và đặt lại mật khẩu lần nữa.
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
    public AccountResponse cuaToi(UUID accountId) {
        Account a = accountRepo.findById(accountId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        Set<String> goi = new HashSet<>();
        entitlementService.activePlans(accountId).forEach(k -> goi.add(k.name()));
        List<String> vai = accountRoleRepo.findByAccount_Id(accountId).stream()
                .map(r -> r.getRole().name())
                .toList();
        return AccountMapper.sang(a, goi, vai);
    }

    @Override
    @Transactional
    public TokenResponse doiMatKhau(UUID accountId, DoiMatKhauRequest req) {
        if (req == null || req.newPassword() == null || req.newPassword().length() < MIN_MAT_KHAU) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "mật khẩu mới tối thiểu " + MIN_MAT_KHAU + " ký tự");
        }
        Account a = accountRepo.findById(accountId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        Credential c = credentialRepo.findByAccount_IdAndProvider(a.getId(), AuthProvider.PASSWORD)
                .orElseThrow(() -> new AppException(ErrorCode.BAD_CREDENTIALS));

        // Mật khẩu cũ là bằng chứng sở hữu. Bỏ kiểm này thì token đánh cắp cũng đổi được
        // mật khẩu rồi khoá chủ thật ra ngoài.
        if (req.oldPassword() == null || !passwordEncoder.matches(req.oldPassword(), c.getPasswordHash())) {
            throw new AppException(ErrorCode.BAD_CREDENTIALS);
        }

        a.setTokenVersion(a.getTokenVersion() + 1);
        a.setFailedAttempts(0);
        a.setLockedUntil(null);
        a.setMustChangePassword(false);
        accountRepo.save(a);

        c.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        c.setLastUsedAt(Instant.now(clock));
        credentialRepo.save(c);

        log.info("Tài khoản {} đổi mật khẩu khi đang đăng nhập — token cũ đã thu hồi", a.getId());
        return tokenService.choNguoiLon(a.getId(), scopes(a.getId()), a.getTokenVersion());
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
    public TokenResponse dangNhapGoogle(LinkCredentialRequest req) {
        FirebaseIdTokenDecoder.NguoiFirebase nguoi = kiemTraReqGoogle(req);

        // Phát token NGAY TRONG giao dịch: Account lấy qua Credential là proxy lười, ra ngoài giao
        // dịch mới đọc tokenVersion là LazyInitializationException.
        try {
            return giaoDich.execute(trangThai -> phatToken(timHoacTaoGoogle(nguoi)));
        } catch (DataIntegrityViolationException | PessimisticLockingFailureException vaCham) {
            log.info("Đăng nhập Google {} va chạm với request song song — tra lại", nguoi.sub());
            TokenResponse t = giaoDich.execute(trangThai -> credentialRepo
                    .findByProviderAndSubject(AuthProvider.GOOGLE, nguoi.sub())
                    .map(c -> phatToken(c.getAccount()))
                    .orElse(null));
            if (t == null) {
                // Không phải chính người này tạo song song: email vừa bị một đăng ký mật khẩu chiếm.
                throw new AppException(ErrorCode.GOOGLE_EMAIL_EXISTS);
            }
            return t;
        }
    }

    private TokenResponse phatToken(Account a) {
        return tokenService.choNguoiLon(a.getId(), scopes(a.getId()), a.getTokenVersion());
    }

    /** Một giao dịch: có Credential thì cập nhật lần dùng; chưa có thì tạo tài khoản mới. */
    private Account timHoacTaoGoogle(FirebaseIdTokenDecoder.NguoiFirebase nguoi) {
        Credential co = credentialRepo.findByProviderAndSubject(AuthProvider.GOOGLE, nguoi.sub())
                .orElse(null);
        if (co != null) {
            co.setLastUsedAt(Instant.now(clock));
            credentialRepo.save(co);
            return co.getAccount();
        }
        if (accountRepo.findByEmailIgnoreCase(chuanHoa(nguoi.email())).isPresent()) {
            throw new AppException(ErrorCode.GOOGLE_EMAIL_EXISTS);
        }
        return taoTaiKhoanGoogle(nguoi);
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
    public void lienKetCachDangNhap(UUID accountId, LinkCredentialRequest req) {
        FirebaseIdTokenDecoder.NguoiFirebase nguoi = kiemTraReqGoogle(req);
        Account a = accountRepo.findById(accountId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));

        Credential daCo = credentialRepo.findByProviderAndSubject(AuthProvider.GOOGLE, nguoi.sub())
                .orElse(null);
        if (daCo != null) {
            if (!accountId.equals(daCo.getAccount().getId())) {
                throw new AppException(ErrorCode.GOOGLE_ALREADY_LINKED);
            }
            return; // đã gắn rồi — gọi lại là lặp vô hại, không tạo Credential thứ hai
        }

        Instant now = Instant.now(clock);
        Credential c = new Credential();
        c.setAccount(a);
        c.setProvider(AuthProvider.GOOGLE);
        c.setSubject(nguoi.sub());
        c.setEmailAtProvider(chuanHoa(nguoi.email()));
        c.setCreatedAt(now);
        c.setLastUsedAt(now);
        credentialRepo.save(c);

        // Chỉ công nhận "email đã xác minh" khi hai email TRÙNG: Google xác minh email của nó,
        // không phải email đang đứng trong tài khoản này.
        if (a.getEmailVerifiedAt() == null && chuanHoa(nguoi.email()).equals(chuanHoa(a.getEmail()))) {
            a.setEmailVerifiedAt(now);
            accountRepo.save(a);
        }
        log.info("Tài khoản {} liên kết Google {}", accountId, nguoi.sub());
    }

    /**
     * Bean chỉ xác minh được chữ ký / hạn / project. Còn email và việc đã xác minh hay chưa là
     * điều kiện MỞ tài khoản nên phải kiểm lại ở đây — không tin một claim chỉ vì chữ ký đúng.
     */
    private FirebaseIdTokenDecoder.NguoiFirebase kiemTraReqGoogle(LinkCredentialRequest req) {
        if (req == null || req.idToken() == null || req.idToken().isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "thiếu idToken");
        }
        if (req.provider() != null && !AuthProvider.GOOGLE.name().equalsIgnoreCase(req.provider().trim())) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "provider phải là GOOGLE");
        }

        FirebaseIdTokenDecoder.NguoiFirebase nguoi = firebaseDecoder.decode(req.idToken());
        if (nguoi.sub() == null || nguoi.sub().isBlank()) {
            throw new AppException(ErrorCode.BAD_CREDENTIALS, "ID token thiếu subject");
        }
        String email = chuanHoa(nguoi.email());
        if (email == null || !DANG_EMAIL.matcher(email).matches()) {
            throw new AppException(ErrorCode.BAD_CREDENTIALS, "ID token thiếu email hợp lệ");
        }
        if (!nguoi.emailDaXacMinh()) {
            throw new AppException(ErrorCode.BAD_CREDENTIALS, "email của Google chưa xác minh");
        }
        return nguoi;
    }

    /** Tên lấy từ Google; không có thì cắt phần trước @ của email. Cắt 80 ký tự cho vừa cột. */
    private static String tenCuaGoogle(FirebaseIdTokenDecoder.NguoiFirebase nguoi, String email) {
        String ten = trim(nguoi.ten());
        if (ten == null || ten.isBlank()) {
            ten = email.substring(0, email.indexOf('@'));
        }
        return ten.length() > 80 ? ten.substring(0, 80) : ten;
    }

    private Account taoTaiKhoanGoogle(FirebaseIdTokenDecoder.NguoiFirebase nguoi) {
        Instant now = Instant.now(clock);
        Account a = new Account();
        a.setEmail(chuanHoa(nguoi.email()));
        a.setDisplayName(tenCuaGoogle(nguoi, a.getEmail()));
        // Google đã xác minh email này nên không bắt người dùng xác minh thêm lần nữa.
        a.setEmailVerifiedAt(now);
        a.setFailedAttempts(0);
        a.setMustChangePassword(false);
        a.setCreatedAt(now);
        // Va chạm UNIQUE / khoá KHÔNG bắt ở đây: để nó thoát ra, giao dịch rollback sạch, rồi
        // dangNhapGoogle tra lại trong giao dịch mới (L-18).
        a = accountRepo.saveAndFlush(a);

        Credential c = new Credential();
        c.setAccount(a);
        c.setProvider(AuthProvider.GOOGLE);
        // subject là uid Firebase, KHÔNG phải email: email đổi được, uid thì không.
        c.setSubject(nguoi.sub());
        c.setEmailAtProvider(a.getEmail());
        c.setCreatedAt(now);
        c.setLastUsedAt(now);
        credentialRepo.saveAndFlush(c);

        // L-16: email đã được Google xác minh, nên người trong ADMIN_EMAILS đăng ký bằng Google cũng
        // được cấp ADMIN như khi xác minh mail — trước đây chỉ luồng mật khẩu mới cấp.
        capAdminDauTien(a);
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

    /** Token mới 32 byte; trả cả bản ghi để ghi usedAt, và bản GỐC để đưa vào mail. */
    private record TokenCap(String goc, VerificationToken banGhi) {
    }

    private TokenCap taoToken(Account a, TokenPurpose purpose, Duration han) {
        byte[] raw = new byte[32];
        rng.nextBytes(raw);
        String goc = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = Instant.now(clock);
        VerificationToken t = new VerificationToken();
        t.setAccount(a);
        t.setPurpose(purpose);
        t.setTokenHash(sha256Hex(goc));
        t.setExpiresAt(now.plus(han));
        t.setCreatedAt(now);
        return new TokenCap(goc, tokenRepo.save(t));
    }

    /**
     * Băm lại token người dùng đưa rồi mới tra — bảng KHÔNG có token gốc để mà so.
     * Hết hạn và dùng rồi là hai lỗi khác nhau: hết hạn thì bảo "bấm gửi lại", dùng rồi thì
     * bảo "không đúng hoặc đã dùng" — gộp hai cái lại thì người dùng không biết làm gì.
     */
    private VerificationToken layToken(String tokenGoc, TokenPurpose canDung) {
        if (tokenGoc == null || tokenGoc.isBlank()) {
            throw new AppException(saiPurpose(canDung));
        }
        VerificationToken t = tokenRepo.findByTokenHash(sha256Hex(tokenGoc))
                .orElseThrow(() -> new AppException(saiPurpose(canDung)));
        if (t.getUsedAt() != null) {
            throw new AppException(saiPurpose(canDung));
        }
        if (!canDung.equals(t.getPurpose())) {
            throw new AppException(saiPurpose(canDung));
        }
        if (t.getExpiresAt().isBefore(Instant.now(clock))) {
            throw new AppException(canDung == TokenPurpose.RESET_PASSWORD
                    ? ErrorCode.RESET_TOKEN_EXPIRED : ErrorCode.VERIFY_TOKEN_EXPIRED);
        }
        return t;
    }

    private static ErrorCode saiPurpose(TokenPurpose canDung) {
        return canDung == TokenPurpose.RESET_PASSWORD
                ? ErrorCode.RESET_TOKEN_INVALID : ErrorCode.VERIFY_TOKEN_INVALID;
    }

    /**
     * true nếu mục đích này vừa gửi mail trong GOI_HAN_GUI_LAI.
     *
     * TRẢ BOOLEAN chứ không ném lỗi: cả hai nơi gọi đều phải im lặng và vẫn trả 200. Ném
     * 3015/3019 ra HTTP thì người ta gửi 2 lần, thấy lỗi, biết chắc email đó đã đăng ký.
     */
    private boolean chanGuiLai(Account a, TokenPurpose purpose) {
        return tokenRepo.findTopByAccount_IdAndPurposeOrderByCreatedAtDesc(a.getId(), purpose)
                .filter(t -> t.getCreatedAt().isAfter(Instant.now(clock).minus(GOI_HAN_GUI_LAI)))
                .isPresent();
    }

    /**
     * Admin đầu tiên: email nằm trong ADMIN_EMAILS mà xác minh xong thì tự cấp ADMIN, có sổ ghi.
     *
     * Chỉ cấp LÚC XÁC MINH, không cấp lúc đăng ký — cấp lúc đăng ký thì ai đăng ký trước bằng
     * đúng email đó sẽ chiếm quyền admin, và đó là email của người quản trị.
     */
    private void capAdminDauTien(Account a) {
        if (!trongDanhSachAdmin(a.getEmail())) {
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

        RoleGrantLog ghi = new RoleGrantLog();
        ghi.setActor(a);
        ghi.setTarget(a);
        ghi.setRole(Role.ADMIN.name());
        ghi.setGranted(true);
        ghi.setReason("ADMIN_EMAILS bootstrap");
        ghi.setCreatedAt(now);
        roleGrantLogRepo.save(ghi);
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
    private boolean trongDanhSachAdmin(String email) {
        if (email == null || adminEmails == null || adminEmails.isBlank()) {
            return false;
        }
        for (String muc : adminEmails.split(",")) {
            if (!muc.isBlank() && email.equalsIgnoreCase(muc.trim())) {
                return true;
            }
        }
        return false;
    }

    private void guiMailXacMinh(Account a, TokenCap tc) {
        String link = publicBaseUrl + "/api/auth/verify?token=" + tc.goc();
        events.publishEvent(new MailCanGui(a.getEmail(), "Xác minh email FinTeen",
                """
                        <p>Chào %s,</p>
                        <p>Cảm ơn bạn đã tạo tài khoản FinTeen. Bấm nút dưới để xác minh email, xong là
                        bạn đăng nhập được ngay.</p>
                        <p><a href="%s">Xác minh email</a></p>
                        <p>Link hết hạn sau 24 giờ và chỉ dùng được một lần. Không phải bạn xin link này
                        thì kệ, không có gì xảy ra.</p>
                        """.formatted(escape(a.getDisplayName()), escape(link))));
    }

    private void guiMailDatLaiMatKhau(Account a, TokenCap tc) {
        String link = publicBaseUrl + "/api/auth/password/reset?token=" + tc.goc();
        events.publishEvent(new MailCanGui(a.getEmail(), "Đặt lại mật khẩu FinTeen",
                """
                        <p>Chào %s,</p>
                        <p>Có yêu cầu đặt lại mật khẩu cho tài khoản này. Bấm nút dưới để đặt mật khẩu mới.</p>
                        <p><a href="%s">Đặt lại mật khẩu</a></p>
                        <p>Link hết hạn sau 30 phút và chỉ dùng được một lần. Không phải bạn xin link này
                        thì đừng bấm — khi đó chỉ cần báo lại mật khẩu cũ của bạn, chúng tôi không xem được.</p>
                        """.formatted(escape(a.getDisplayName()), escape(link))));
    }

    private static String chuanHoa(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /** Chèn vào HTML thì thoát ký tự đặc biệt — tên người dùng là dữ liệu, không phải mã. */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * SHA-256 chuẩn hoá hex, 64 ký tự — vừa khớp cột tokenHash CHAR(64). stdlib Java 17,
     * không cần thêm thư viện.
     */
    static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 là bắt buộc phải có trong mọi JRE; không có thì JRE đó hỏng rồi.
            throw new IllegalStateException(e);
        }
    }
}