package com.doan.game.auth;

import com.doan.game.auth.domain.Account;
import com.doan.game.auth.domain.ChildSlot;
import com.doan.game.auth.domain.LearningContext;
import com.doan.game.auth.repository.AccountRepository;
import com.doan.game.auth.repository.ChildSlotRepository;
import com.doan.game.shared.error.AppException;
import com.doan.game.shared.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Toàn bộ nghiệp vụ auth. Một lớp, KHÔNG interface + Impl — chỉ có một hiện thực.
 *
 * Bốn luật của thiết kế này, lấy thẳng từ biên bản brainstorm:
 *  1. Trẻ KHÔNG tự đăng ký. Người lớn tạo slot, phát mã QR + PIN.
 *  2. Hạn mức: phụ huynh 4 slot, giáo viên 40 slot. Slot đã lưu trữ thì không tính.
 *  3. Nhà và lớp RIÊNG HOÀN TOÀN. Không có hàm nào ở đây đọc chéo hai bối cảnh.
 *  4. Tiền là cánh cửa chặn spam: chưa mua gói thì không tạo được slot nào.
 */
@Service
public class AuthService {

    /** Chốt: "1 phụ huynh có max 4 con". */
    static final int MAX_FAMILY_SLOTS = 4;
    /** Chốt: "cô sẽ có 40 slot". */
    static final int MAX_CLASS_SLOTS = 40;

    /** 6 chữ số là 1 triệu tổ hợp. Không khoá thì dò hết trong một buổi. */
    private static final int MAX_PIN_ATTEMPTS = 5;
    private static final Duration LOCK_FOR = Duration.ofMinutes(15);

    /** Bỏ 0/O và 1/I/L: trẻ con gõ tay từ giấy in, nhìn nhầm là vào không được. */
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int CODE_LEN = 8;

    private final AccountRepository accounts;
    private final ChildSlotRepository slots;
    private final PasswordEncoder encoder;
    private final SecureRandom rng = new SecureRandom();

    public AuthService(AccountRepository accounts, ChildSlotRepository slots, PasswordEncoder encoder) {
        this.accounts = accounts;
        this.slots = slots;
        this.encoder = encoder;
    }

    // ---------- người lớn ----------

    @Transactional
    public Account register(String email, String phone, String rawPassword, String displayName) {
        if (accounts.existsByEmailIgnoreCase(email)) throw new AppException(ErrorCode.EMAIL_TAKEN);
        Account a = new Account();
        a.setEmail(email.trim().toLowerCase());
        a.setPhone(phone.trim());
        a.setPasswordHash(encoder.encode(rawPassword));
        a.setDisplayName(displayName.trim());
        return accounts.save(a);
    }

    public Account login(String email, String rawPassword) {
        // Sai email và sai mật khẩu trả về CÙNG một lỗi — khác nhau là tặng kẻ dò
        // một cách kiểm tra email nào đã tồn tại.
        Account a = accounts.findByEmailIgnoreCase(email.trim())
                .orElseThrow(() -> new AppException(ErrorCode.BAD_CREDENTIALS));
        if (!encoder.matches(rawPassword, a.getPasswordHash())) {
            throw new AppException(ErrorCode.BAD_CREDENTIALS);
        }
        return a;
    }

    public Account require(UUID accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
    }

    /**
     * Bật gói. CHƯA thu tiền — cổng thanh toán là tính năng khác.
     * Đặt ở đây vì gói là thứ QUYẾT ĐỊNH VAI, mà vai thì thuộc auth.
     */
    @Transactional
    public Account setPlans(UUID accountId, boolean parentPlan, boolean teacherPlan) {
        Account a = require(accountId);
        a.setParentPlan(parentPlan);
        a.setTeacherPlan(teacherPlan);
        return accounts.save(a);
    }

    // ---------- slot của trẻ ----------

    @Transactional
    public Issued createSlot(UUID ownerId, String displayName, LearningContext context) {
        Account owner = require(ownerId);
        boolean allowed = context == LearningContext.FAMILY ? owner.isParentPlan() : owner.isTeacherPlan();
        if (!allowed) throw new AppException(ErrorCode.PLAN_REQUIRED);

        int max = context == LearningContext.FAMILY ? MAX_FAMILY_SLOTS : MAX_CLASS_SLOTS;
        long used = slots.countByOwnerIdAndContextAndArchivedFalse(ownerId, context);
        if (used >= max) throw new AppException(ErrorCode.SLOT_LIMIT_REACHED, "đã dùng " + used + "/" + max);

        String pin = randomPin();
        ChildSlot s = new ChildSlot();
        s.setCode(freshCode());
        s.setPinHash(encoder.encode(pin));
        s.setDisplayName(displayName.trim());
        s.setOwnerId(ownerId);
        s.setContext(context);
        return new Issued(slots.save(s), pin);
    }

    public List<ChildSlot> slotsOf(UUID ownerId) {
        return slots.findByOwnerIdOrderByCreatedAtAsc(ownerId);
    }

    @Transactional
    public String resetPin(UUID ownerId, UUID slotId) {
        ChildSlot s = ownedSlot(ownerId, slotId);
        String pin = randomPin();
        s.setPinHash(encoder.encode(pin));
        s.setFailedAttempts(0);
        s.setLockedUntil(null);
        slots.save(s);
        return pin;
    }

    /**
     * Nút KẾT THÚC LỚP HỌC (chốt): lưu lại toàn bộ data của lớp hiện tại, trả slot về để tái dùng.
     *
     * Lưu trữ chứ KHÔNG xoá — số liệu của lớp cũ là thứ cô cần để so trước/sau.
     * Hệ quả cố ý: bé chưa liên kết tài khoản thì mất đường vào từ đây.
     */
    @Transactional
    public int endClass(UUID ownerId) {
        List<ChildSlot> open = slots.findByOwnerIdAndContextAndArchivedFalse(ownerId, LearningContext.CLASS);
        Instant now = Instant.now();
        for (ChildSlot s : open) {
            s.setArchived(true);
            s.setArchivedAt(now);
        }
        slots.saveAll(open);
        return open.size();
    }

    // ---------- trẻ ----------

    // CỐ Ý KHÔNG @Transactional. Đếm số lần sai PIN rồi NÉM lỗi, mà ném lỗi trong
    // transaction là rollback — bộ đếm bị xoá sạch và khoá chống dò không bao giờ đóng.
    // Bỏ transaction thì mỗi save tự commit ngay, đếm mới trụ lại được. Mỗi nhánh ở đây
    // chỉ có đúng một save nên không mất tính nguyên tử.
    public ChildSlot childLogin(String code, String pin) {
        ChildSlot s = slots.findByCode(code.trim().toUpperCase())
                .orElseThrow(() -> new AppException(ErrorCode.BAD_CREDENTIALS));
        if (s.isArchived()) throw new AppException(ErrorCode.SLOT_ARCHIVED);
        if (s.isLocked()) throw new AppException(ErrorCode.SLOT_LOCKED);

        if (!encoder.matches(pin, s.getPinHash())) {
            s.setFailedAttempts(s.getFailedAttempts() + 1);
            if (s.getFailedAttempts() >= MAX_PIN_ATTEMPTS) {
                s.setLockedUntil(Instant.now().plus(LOCK_FOR));
                s.setFailedAttempts(0);
            }
            slots.save(s);
            throw new AppException(ErrorCode.BAD_CREDENTIALS);
        }
        s.setFailedAttempts(0);
        s.setLockedUntil(null);
        return slots.save(s);
    }

    /**
     * Liên kết slot vào tài khoản của trẻ. Phải chứng minh đang giữ QR + PIN, không chỉ biết mã.
     *
     * Hai lý do có tính năng này: (1) khỏi phải mở lại QR mỗi lần đăng nhập;
     * (2) giữ được đường vào sau khi lớp kết thúc và slot bị trả lại.
     */
    @Transactional
    public ChildSlot link(UUID accountId, String code, String pin) {
        ChildSlot s = childLogin(code, pin);
        if (s.getLinkedAccountId() != null && !s.getLinkedAccountId().equals(accountId)) {
            throw new AppException(ErrorCode.SLOT_ALREADY_LINKED);
        }
        s.setLinkedAccountId(accountId);
        return slots.save(s);
    }

    /** Các bối cảnh một trẻ đã liên kết có thể vào. Có từ hai trở lên thì trẻ phải CHỌN. */
    public List<ChildSlot> contextsOf(UUID accountId) {
        return slots.findByLinkedAccountIdAndArchivedFalse(accountId);
    }

    @Transactional(readOnly = true)
    public ChildSlot enterContext(UUID accountId, UUID slotId) {
        ChildSlot s = slots.findById(slotId).orElseThrow(() -> new AppException(ErrorCode.SLOT_NOT_FOUND));
        if (!accountId.equals(s.getLinkedAccountId())) throw new AppException(ErrorCode.SLOT_NOT_FOUND);
        if (s.isArchived()) throw new AppException(ErrorCode.SLOT_ARCHIVED);
        return s;
    }

    public ChildSlot requireSlot(UUID slotId) {
        return slots.findById(slotId).orElseThrow(() -> new AppException(ErrorCode.SLOT_NOT_FOUND));
    }

    // ---------- lặt vặt ----------

    private ChildSlot ownedSlot(UUID ownerId, UUID slotId) {
        ChildSlot s = slots.findById(slotId).orElseThrow(() -> new AppException(ErrorCode.SLOT_NOT_FOUND));
        // Slot của người khác trả NOT_FOUND chứ không phải FORBIDDEN: đừng xác nhận nó tồn tại.
        if (!s.getOwnerId().equals(ownerId)) throw new AppException(ErrorCode.SLOT_NOT_FOUND);
        return s;
    }

    private String randomPin() {
        return String.format("%06d", rng.nextInt(1_000_000));
    }

    private String freshCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_LEN);
            for (int i = 0; i < CODE_LEN; i++) {
                sb.append(CODE_ALPHABET.charAt(rng.nextInt(CODE_ALPHABET.length())));
            }
            String code = sb.toString();
            if (!slots.existsByCode(code)) return code;
        }
        // 31^8 tổ hợp — 10 lần trượt liên tiếp nghĩa là có gì đó hỏng, không phải xui.
        throw new AppException(ErrorCode.UNCATEGORIZED, "không sinh được mã slot");
    }

    /** PIN gốc chỉ tồn tại ở đây và trong response tạo slot. Không lưu, không log. */
    public record Issued(ChildSlot slot, String pin) {}
}
