package com.doan.game.service.impl;

import com.doan.game.DTO.request.GrantPackRequest;
import com.doan.game.DTO.request.GrantPlanRequest;
import com.doan.game.DTO.response.EntitlementResponse;
import com.doan.game.DTO.response.GrantPackResponse;
import com.doan.game.entity.Account;
import com.doan.game.entity.Entitlement;
import com.doan.game.entity.SlotAddon;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.PlanKind;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.mapper.EntitlementMapper;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.SlotAddonRepository;
import com.doan.game.service.EntitlementFactory;
import com.doan.game.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Gói đang giữ. Parent / Teacher KHÔNG lưu ở Account mà suy từ Entitlement còn hạn, tính theo NGÀY giờ Việt Nam; mọi thao tác cần gói đều gọi lại activePlans, không tin vai trong JWT. Admin cấp được gói không cần thanh toán.
 *
 * CÓ RUỘT cả ba hàm (06/10):
 * - activePlans (02/10): nơi duy nhất quyết định một tài khoản có gói hay không, nên nó phải chạy
 *   được TRƯỚC khi AuthService phát token. activePlans xong thì AccountResponse và scope
 *   PARENT / TEACHER mới lấp được.
 * - listPlans: lịch sử gói của chính người gọi, cả gói hết hạn.
 * - grantPlan: admin cấp tay, dựng dòng qua EntitlementFactory — cùng một quy tắc ngày với payment.
 *
 * Phần remindExpiringPlans vẫn là KHUNG — ném UnsupportedOperationException để không ai vô tình
 * dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class EntitlementServiceImpl implements EntitlementService {

    /** Số pack tối đa một lần cấp tay — chặn tràn số khi nhân với slot/pack. */
    static final int MAX_GRANT_PACKS = 1000;

    private static final Logger log = LoggerFactory.getLogger(EntitlementServiceImpl.class);

    private final EntitlementRepository entitlementRepo;
    private final SlotAddonRepository slotAddonRepo;
    private final AccountRepository accountRepo;
    private final AccountRoleRepository accountRoleRepo;
    private final EntitlementFactory entitlementFactory;
    private final Clock clock;

    /**
     * Loại gói còn hiệu lực HÔM NAY. "Hôm nay" lấy từ Clock múi giờ Việt Nam, không phải UTC của
     * máy chủ — dùng đêm thì hai bên lệch nhau một ngày và gói vừa mua bị coi là chưa bắt đầu.
     */
    @Override
    @Transactional(readOnly = true)
    public Set<PlanKind> activePlans(UUID accountId) {
        return entitlementRepo.findActivePlanKinds(accountId, LocalDate.now(clock));
    }

    /**
     * Lịch sử gói của chính người gọi, hạn mới nhất trước. Trả cả gói hết hạn — còn hạn hay không
     * là việc của activePlans (/api/auth/me), ở đây người dùng muốn xem đã mua / được cấp gì.
     */
    @Override
    @Transactional(readOnly = true)
    public List<EntitlementResponse> listPlans(UUID accountId) {
        return entitlementRepo.findByAccount_IdOrderByExpiresOnDescCreatedAtDesc(accountId).stream()
                .map(EntitlementMapper::toResponse)
                .toList();
    }

    /**
     * Admin cấp gói không qua thanh toán (dùng thử, đền bù). Từ 28/11/2026 cấp tay cũng là VĨNH VIỄN
     * như gói mua — không còn số tháng. Giữ luật của mua: đã có gói gốc loại đó thì không cấp lần hai.
     */
    @Override
    @Transactional
    public EntitlementResponse grantPlan(UUID adminId, GrantPlanRequest req) {
        if (req.accountId() == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "accountId: bắt buộc");
        }
        PlanKind kind = parsePlanKind(req.kind());
        Account recipient = findCustomer(req.accountId());
        Account admin = findAdmin(adminId);
        if (hasActivePlan(recipient.getId(), kind)) {
            throw new AppException(ErrorCode.PLAN_ALREADY_OWNED,
                    com.doan.game.service.impl.PaymentServiceImpl.planLabel(kind));
        }

        Entitlement entitlement = entitlementFactory.createPermanent(recipient, kind,
                EntitlementSource.ADMIN, null, admin, requireReason(req.reason()));
        entitlementRepo.save(entitlement);
        return EntitlementMapper.toResponse(entitlement);
    }

    /**
     * Admin cấp pack tay để đền bù (hoàn tiền xử lý ngoài hệ thống). Cần có gói gốc cùng loại, giữ
     * nguyên luật mua pack. {@code transaction} để null nên không có webhook/mail biên nhận; lý do
     * được ghi log (bảng slot_addon không có cột reason).
     */
    @Override
    @Transactional
    public GrantPackResponse grantPack(UUID adminId, GrantPackRequest req) {
        if (req.accountId() == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "accountId: bắt buộc");
        }
        PlanKind kind = parsePlanKind(req.kind());
        int packs = req.packs() == null ? 0 : req.packs();
        if (packs < 1 || packs > MAX_GRANT_PACKS) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "packs: phải từ 1 đến " + MAX_GRANT_PACKS);
        }
        String reason = requireReason(req.reason());
        Account recipient = findCustomer(req.accountId());
        findAdmin(adminId);
        if (!hasActivePlan(recipient.getId(), kind)) {
            throw new AppException(ErrorCode.PLAN_REQUIRED,
                    com.doan.game.service.impl.PaymentServiceImpl.planLabel(kind));
        }

        int slots = packs * PlanServiceImpl.addonSlotsOf(kind);
        SlotAddon addon = new SlotAddon();
        addon.setAccount(recipient);
        addon.setKind(kind);
        addon.setSlots(slots);
        addon.setCreatedAt(Instant.now(clock));
        slotAddonRepo.save(addon);
        log.info("Admin {} cấp tay {} pack ({} slot) cho tài khoản {} — lý do: {}",
                adminId, packs, slots, recipient.getId(), reason);
        return new GrantPackResponse(recipient.getId(), kind.name(), packs, slots, reason);
    }

    private Account findCustomer(UUID id) {
        Account account = accountRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có tài khoản " + id));
        if (accountRoleRepo.isInternal(account.getId())) {
            throw new AppException(ErrorCode.INTERNAL_ACCOUNT_NOT_CUSTOMER);
        }
        return account;
    }

    private Account findAdmin(UUID id) {
        return accountRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có tài khoản admin " + id));
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "reason: bắt buộc");
        }
        return reason.trim();
    }

    private boolean hasActivePlan(UUID accountId, PlanKind kind) {
        LocalDate today = LocalDate.now(clock);
        return entitlementRepo.findTopByAccount_IdAndKindOrderByExpiresOnDesc(accountId, kind)
                .filter(e -> !e.getExpiresOn().isBefore(today))
                .isPresent();
    }

    private static PlanKind parsePlanKind(String kind) {
        if (kind == null) {
            throw new AppException(ErrorCode.PLAN_KIND_INVALID);
        }
        try {
            return PlanKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.PLAN_KIND_INVALID);
        }
    }

}
