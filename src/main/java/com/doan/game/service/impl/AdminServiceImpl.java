package com.doan.game.service.impl;

import com.doan.game.DTO.request.GrantRoleRequest;
import com.doan.game.DTO.response.AccountResponse;
import com.doan.game.DTO.response.AdminAccountDetailResponse;
import com.doan.game.DTO.response.AdminAccountResponse;
import com.doan.game.DTO.response.AdminDashboardResponse;
import com.doan.game.DTO.response.AdminSlotResponse;
import com.doan.game.DTO.response.PageResponse;
import com.doan.game.DTO.response.RoleGrantLogResponse;
import com.doan.game.DTO.response.RoleListResponse;
import com.doan.game.DTO.response.TransactionAdminResponse;
import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.entity.Credential;
import com.doan.game.entity.Entitlement;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.entity.RunState;
import com.doan.game.entity.RoleGrantLog;
import com.doan.game.enums.PlanKind;
import com.doan.game.enums.Role;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.CredentialRepository;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.RunStateRepository;
import com.doan.game.repository.RoleGrantLogRepository;
import com.doan.game.repository.TransactionRepository;
import com.doan.game.mapper.AccountMapper;
import com.doan.game.service.AdminService;
import com.doan.game.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
    private final EntitlementRepository entitlementRepo;
    private final TransactionRepository transactionRepo;
    private final LearnerGroupRepository groupRepo;
    private final LearnerSlotRepository slotRepo;
    private final RunStateRepository runStateRepo;
    private final CredentialRepository credentialRepo;
    private final EntitlementService entitlementService;
    private final Clock clock;

    @Override
    @Transactional
    public RoleListResponse grantRole(UUID actorId, GrantRoleRequest req) {
        Role role = internalRole(req);
        Account target = target(req);
        // Tài khoản nội bộ là riêng, không phải khách hàng (Hưng chốt 23/10): chỉ cấp cho tài khoản trắng.
        if (entitlementRepo.existsByAccount_Id(target.getId()) || transactionRepo.existsByAccount_Id(target.getId())
                || groupRepo.existsByOwner_Id(target.getId())) {
            throw new AppException(ErrorCode.CUSTOMER_ACCOUNT_NOT_INTERNAL);
        }
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
    public PageResponse<AdminAccountResponse> listAccounts(String q, Role role, PlanKind plan, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.ASC, "email"));
        Page<Account> pageResult = accountRepo.findAll(accountFilter(q, role, plan), pageable);
        List<Account> accounts = pageResult.getContent();
        if (accounts.isEmpty()) return PageResponse.of(pageResult, List.of());
        // Vai, gói, slot, tiến độ của cả trang: 4 truy vấn gom theo id, không tra từng người.
        List<UUID> ids = accounts.stream().map(Account::getId).toList();
        Map<UUID, List<String>> roles = new HashMap<>();
        accountRoleRepo.findByAccount_IdIn(ids).forEach(r ->
                roles.computeIfAbsent(r.getAccount().getId(), k -> new ArrayList<>()).add(r.getRole().name()));
        Map<UUID, List<String>> plans = new HashMap<>();
        for (Object[] row : entitlementRepo.findActivePlanKinds(ids, LocalDate.now(clock))) {
            plans.computeIfAbsent((UUID) row[0], k -> new ArrayList<>()).add(row[1].toString());
        }
        Instant now = Instant.now(clock);
        List<LearnerSlot> slotRows = slotRepo.findAllByOwnerIds(ids);
        Map<UUID, RunState> runs = new HashMap<>();
        if (!slotRows.isEmpty()) {
            runStateRepo.findBySlot_IdIn(slotRows.stream().map(LearnerSlot::getId).toList())
                    .forEach(r -> runs.put(r.getSlot().getId(), r));
        }
        Map<UUID, List<AdminSlotResponse>> slots = new HashMap<>();
        for (LearnerSlot s : slotRows) {
            RunState run = runs.get(s.getId());
            // Không tên, không mã đăng nhập, không huy hiệu: admin chỉ cần biết tài khoản có bao nhiêu slot, ở nhóm nào, trạng thái gì.
            slots.computeIfAbsent(s.getGroup().getOwner().getId(), k -> new ArrayList<>()).add(new AdminSlotResponse(
                    s.getId(), s.getGroup().getId(), String.valueOf(s.getGroup().getContext()), String.valueOf(s.getStatus()),
                    s.getLockedUntil() != null && s.getLockedUntil().isAfter(now), s.getCreatedAt(),
                    run == null ? List.of() : ChaptersDone.parse(run.getChaptersDone()),
                    run == null ? null : run.getAttemptIndex(), run == null ? null : run.getUpdatedAt()));
        }
        return PageResponse.of(pageResult, accounts.stream().map(a -> new AdminAccountResponse(a.getId(), a.getEmail(), a.getDisplayName(),
                plans.getOrDefault(a.getId(), List.of()).stream().sorted().toList(),
                roles.getOrDefault(a.getId(), List.of()).stream().sorted().toList(),
                a.getCreatedAt(), slots.getOrDefault(a.getId(), List.of()))).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public AdminAccountDetailResponse accountDetail(UUID id) {
        Account a = accountRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có tài khoản " + id));
        // Đường vào: providers và lần đăng nhập gần nhất — đủ "lịch sử đăng nhập" mà không cần bảng mới.
        List<Credential> credentials = credentialRepo.findByAccount_Id(id);
        List<String> providers = credentials.stream().map(c -> c.getProvider().name())
                .distinct().sorted().toList();
        Instant lastLoginAt = credentials.stream().map(Credential::getLastUsedAt)
                .filter(java.util.Objects::nonNull).max(java.util.Comparator.naturalOrder()).orElse(null);

        List<String> roles = accountRoleRepo.findByAccount_IdIn(List.of(id)).stream()
                .map(r -> r.getRole().name()).sorted().toList();

        LocalDate today = LocalDate.now(clock);
        List<AdminAccountDetailResponse.EntitlementRow> entitlements =
                entitlementRepo.findByAccount_IdOrderByExpiresOnDescCreatedAtDesc(id).stream()
                        .map(e -> new AdminAccountDetailResponse.EntitlementRow(
                                e.getKind().name(), e.getStartsOn(), e.getExpiresOn(),
                                e.getStartsOn() != null && e.getExpiresOn() != null
                                        && !e.getStartsOn().isAfter(today) && !e.getExpiresOn().isBefore(today),
                                e.getSource().name(),
                                e.getTransaction() == null ? null : e.getTransaction().getOrderCode(),
                                e.getGrantedBy() == null ? null : e.getGrantedBy().getEmail(),
                                e.getReason(), e.getCreatedAt()))
                        .toList();

        List<TransactionAdminResponse> transactions = transactionRepo
                .findByAccount_IdOrderByCreatedAtDesc(id, PageRequest.of(0, 20))
                .map(PaymentServiceImpl::toAdmin).getContent();

        Map<String, Long> slots = new LinkedHashMap<>();
        for (com.doan.game.enums.SlotStatus s : com.doan.game.enums.SlotStatus.values()) {
            slots.put(s.name(), 0L);
        }
        for (LearnerSlot s : slotRepo.findAllByOwnerIds(List.of(id))) {
            slots.merge(String.valueOf(s.getStatus()), 1L, Long::sum);
        }

        return new AdminAccountDetailResponse(new AdminAccountDetailResponse.AccountInfo(
                a.getId(), a.getEmail(), a.getDisplayName(), a.getCreatedAt(), a.getEmailVerifiedAt(),
                a.isMustChangePassword(), a.getLockedUntil(), providers, lastLoginAt),
                roles, entitlements, transactions, slots);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminDashboardResponse dashboard() {
        LocalDate today = LocalDate.now(clock);
        Instant since = Instant.now(clock).minus(30, java.time.temporal.ChronoUnit.DAYS);
        return new AdminDashboardResponse(
                new AdminDashboardResponse.Accounts(accountRepo.count(),
                        entitlementRepo.countActiveAccountsByKind(PlanKind.PARENT, today),
                        entitlementRepo.countActiveAccountsByKind(PlanKind.TEACHER, today),
                        accountRoleRepo.countDistinctAccountsByRoleIn(AccountRoleRepository.INTERNAL_ROLES)),
                new AdminDashboardResponse.Learners(
                        slotRepo.countByStatus(com.doan.game.enums.SlotStatus.ACTIVE),
                        slotRepo.countByStatus(com.doan.game.enums.SlotStatus.ARCHIVED),
                        slotRepo.countByStatus(com.doan.game.enums.SlotStatus.WIPED)),
                new AdminDashboardResponse.Revenue(
                        transactionRepo.sumAmountByStatus(com.doan.game.enums.TransactionStatus.PAID),
                        transactionRepo.sumAmountByStatusAndPaidAtAfter(
                                com.doan.game.enums.TransactionStatus.PAID, since)),
                new AdminDashboardResponse.Transactions(
                        transactionRepo.countByStatus(com.doan.game.enums.TransactionStatus.PENDING),
                        transactionRepo.countByStatus(com.doan.game.enums.TransactionStatus.FAILED),
                        transactionRepo.countByNeedsReviewTrue()),
                new AdminDashboardResponse.Plans(
                        entitlementRepo.countActive(today),
                        entitlementRepo.countExpiringBetween(today, today.plusDays(7))),
                // Đợt 1 chưa có bảng nhật ký thao tác admin chung — dùng sổ cấp / thu vai.
                listRoleLog(null, 0, 10).items());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RoleGrantLogResponse> listRoleLog(UUID subjectId, int page, int size) {
        // Cùng cách kẹp trang với giao dịch: page < 0 → 0, size 1..100; trả kèm tổng số dòng.
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<RoleGrantLog> pageResult = subjectId == null
                ? roleGrantLogRepo.findAllByOrderByCreatedAtDesc(pageable)
                : roleGrantLogRepo.findByTarget_IdOrderByCreatedAtDesc(subjectId, pageable);
        return PageResponse.of(pageResult, pageResult.getContent().stream().map(l -> new RoleGrantLogResponse(
                l.getId(), l.getActor().getId(), l.getActor().getEmail(),
                l.getTarget().getId(), l.getTarget().getEmail(), l.getRole(), l.isGranted(), l.getReason(),
                l.getCreatedAt())).toList());
    }

    // ---- phụ trợ ----

    /**
     * Lọc danh sách tài khoản: q (email / tên hiển thị), vai nội bộ và gói còn hạn.
     * Hai điều kiện sau dựng bằng subquery exists để không nhân dòng khi một người có nhiều vai / nhiều gói.
     * "Còn hạn" tính theo ngày VN, cùng mốc với EntitlementService: startsOn ≤ hôm nay ≤ expiresOn.
     */
    private Specification<Account> accountFilter(String q, Role role, PlanKind plan) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("displayName"), "")), like)));
            }
            if (role != null) {
                Subquery<UUID> sub = query.subquery(UUID.class);
                Root<AccountRole> ar = sub.from(AccountRole.class);
                sub.select(ar.get("id")).where(cb.equal(ar.get("account"), root), cb.equal(ar.get("role"), role));
                where.add(cb.exists(sub));
            }
            if (plan != null) {
                LocalDate today = LocalDate.now(clock);
                Subquery<UUID> sub = query.subquery(UUID.class);
                Root<Entitlement> e = sub.from(Entitlement.class);
                sub.select(e.get("id")).where(cb.equal(e.get("account"), root), cb.equal(e.get("kind"), plan),
                        cb.lessThanOrEqualTo(e.get("startsOn"), today),
                        cb.greaterThanOrEqualTo(e.get("expiresOn"), today));
                where.add(cb.exists(sub));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
    }

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
