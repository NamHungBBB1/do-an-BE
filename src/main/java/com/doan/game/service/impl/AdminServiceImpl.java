package com.doan.game.service.impl;

import com.doan.game.DTO.request.GrantRoleRequest;
import com.doan.game.DTO.response.AccountResponse;
import com.doan.game.DTO.response.RoleGrantLogResponse;
import com.doan.game.DTO.response.RoleListResponse;
import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.entity.RoleGrantLog;
import com.doan.game.enums.Role;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.RoleGrantLogRepository;
import com.doan.game.mapper.AccountMapper;
import com.doan.game.service.AdminService;
import com.doan.game.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cấp và thu vai nội bộ (AccountRole), kèm sổ ghi bắt buộc có lý do (PR A, 15/10); tra tài khoản theo email
 * cho màn cấp vai (tối 15/10); danh sách ai đang giữ vai nội bộ và sổ cấp / thu (16/10).
 *
 * Chỉ bốn vai cấp tay được: ADMIN và ba vai Studio. PARENT / TEACHER suy từ gói còn hạn, CHILD là
 * slot, GUEST không có tài khoản — gửi lên là 1001. Decoder đọc AccountRole mỗi request nên cấp
 * xong có scope ngay, thu xong mất ngay, không cần đăng nhập lại. Cửa /api/admin/** đã khoá
 * SCOPE_ADMIN ở SecurityConfig; ở đây không kiểm lại.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminServiceImpl implements AdminService {

    /** Vai cấp tay được. Các giá trị khác của enum Role là suy ra, không ai cấp. */
    static final Set<Role> INTERNAL_ROLES =
            EnumSet.of(Role.ADMIN, Role.GAME_EDITOR, Role.GAME_REVIEWER, Role.GAME_MANAGER);

    private final AccountRepository accountRepo;
    private final AccountRoleRepository accountRoleRepo;
    private final RoleGrantLogRepository roleGrantLogRepo;
    private final EntitlementService entitlementService;
    private final Clock clock;

    @Override
    @Transactional
    public RoleListResponse grantRole(UUID actorId, GrantRoleRequest req) {
        Role role = internalRole(req);
        Account target = target(req);
        // Đã có thì lặp vô hại: không thêm dòng (UNIQUE(accountId, role)) và không ghi sổ, vì không có gì đổi.
        if (!accountRoleRepo.existsByAccount_IdAndRole(target.getId(), role)) {
            Instant now = Instant.now(clock);
            AccountRole r = new AccountRole();
            r.setAccount(target);
            r.setRole(role);
            r.setGrantedAt(now);
            accountRoleRepo.save(r);
            writeLog(actorId, target, role, true, req.reason(), now);
        }
        return roles(target.getId());
    }

    @Override
    @Transactional
    public RoleListResponse revokeRole(UUID actorId, GrantRoleRequest req) {
        Role role = internalRole(req);
        Account target = target(req);
        if (role == Role.ADMIN && actorId.equals(target.getId())) {
            // Mất cửa cuối: admin duy nhất tự thu là không còn ai cấp lại được. Nhờ admin khác thu.
            throw new AppException(ErrorCode.VALIDATION_FAILED, "không tự thu ADMIN của chính mình");
        }
        AccountRole existing = accountRoleRepo.findByAccount_IdAndRole(target.getId(), role)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "tài khoản không giữ vai " + role));
        accountRoleRepo.delete(existing);
        writeLog(actorId, target, role, false, req.reason(), Instant.now(clock));
        return roles(target.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponse findAccount(String email) {
        if (email == null || email.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "cần email");
        }
        String e = email.trim();
        Account a = accountRepo.findByEmailIgnoreCase(e)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có tài khoản " + e));
        // Cùng khuôn với GET /api/auth/me (AuthServiceImpl.getMe) để màn admin đọc đúng một dạng.
        Set<String> plans = new HashSet<>();
        entitlementService.activePlans(a.getId()).forEach(k -> plans.add(k.name()));
        return AccountMapper.toResponse(a, plans, roles(a.getId()).roles());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountResponse> listInternalAccounts() {
        // Một truy vấn lấy cả vai lẫn tài khoản (join fetch) rồi gom theo người. Nhân sự nội bộ chỉ vài người
        // nên tra gói từng người là đủ rẻ; không phân trang.
        Map<UUID, Account> accounts = new LinkedHashMap<>();
        Map<UUID, List<String>> rolesById = new HashMap<>();
        for (AccountRole r : accountRoleRepo.findAllWithAccountOrderByEmail()) {
            UUID id = r.getAccount().getId();
            accounts.putIfAbsent(id, r.getAccount());
            rolesById.computeIfAbsent(id, k -> new ArrayList<>()).add(r.getRole().name());
        }
        return accounts.values().stream().map(a -> {
            Set<String> plans = new HashSet<>();
            entitlementService.activePlans(a.getId()).forEach(k -> plans.add(k.name()));
            return AccountMapper.toResponse(a, plans, rolesById.get(a.getId()));
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleGrantLogResponse> listRoleLog(UUID subjectId, int page, int size) {
        // Cùng cách kẹp trang với giao dịch: page < 0 → 0, size 1..100; không trả tổng, FE lật tới khi rỗng.
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        List<RoleGrantLog> rows = subjectId == null
                ? roleGrantLogRepo.findAllByOrderByCreatedAtDesc(pageable)
                : roleGrantLogRepo.findByTarget_IdOrderByCreatedAtDesc(subjectId, pageable);
        return rows.stream().map(l -> new RoleGrantLogResponse(l.getId(), l.getActor().getId(), l.getActor().getEmail(),
                l.getTarget().getId(), l.getTarget().getEmail(), l.getRole(), l.isGranted(), l.getReason(),
                l.getCreatedAt())).toList();
    }

    // ---- phụ trợ ----

    /** Kiểm dữ liệu vào một chỗ: thiếu trường, vai lạ, vai suy ra → 1001 kèm lý do. Chữ thường vẫn nhận. */
    private static Role internalRole(GrantRoleRequest req) {
        if (req == null || req.subjectId() == null || req.role() == null
                || req.reason() == null || req.reason().isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "cần subjectId, role và reason");
        }
        if (req.reason().trim().length() > 255) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "reason tối đa 255 ký tự");
        }
        Role role;
        try {
            role = Role.valueOf(req.role().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "không có vai " + req.role());
        }
        if (!INTERNAL_ROLES.contains(role)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "vai " + role + " suy từ gói hoặc slot, không cấp tay; chỉ " + INTERNAL_ROLES);
        }
        return role;
    }

    private Account target(GrantRoleRequest req) {
        return accountRepo.findById(req.subjectId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có tài khoản " + req.subjectId()));
    }

    /** Sổ ghi cùng kiểu AuthServiceImpl.grantBootstrapAdmin: ai, cho ai, vai gì, cấp hay thu, lý do, lúc nào. */
    private void writeLog(UUID actorId, Account target, Role role, boolean granted, String reason, Instant now) {
        Account actor = accountRepo.findById(actorId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        RoleGrantLog entry = new RoleGrantLog();
        entry.setActor(actor);
        entry.setTarget(target);
        entry.setRole(role.name());
        entry.setGranted(granted);
        entry.setReason(reason.trim());
        entry.setCreatedAt(now);
        roleGrantLogRepo.save(entry);
        log.info("{} vai {} {} tài khoản {} (admin {})", granted ? "Cấp" : "Thu", role,
                granted ? "cho" : "của", target.getId(), actorId);
    }

    private RoleListResponse roles(UUID accountId) {
        List<String> roles = accountRoleRepo.findByAccount_Id(accountId).stream()
                .map(r -> r.getRole().name()).sorted().toList();
        return new RoleListResponse(accountId, roles);
    }
}
