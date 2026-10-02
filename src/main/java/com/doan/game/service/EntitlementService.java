package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Gói đang giữ. Parent / Teacher KHÔNG lưu ở Account mà suy từ Entitlement còn hạn, tính theo NGÀY giờ Việt Nam; mọi thao tác cần gói đều gọi lại activePlans, không tin vai trong JWT. Admin cấp được gói không cần thanh toán.
 *
 * Bảng phụ trách: Entitlement
 */
public interface EntitlementService {

    java.util.Set<com.doan.game.enums.PlanKind> activePlans(UUID accountId);

    java.util.List<EntitlementResponse> xemGoi(UUID accountId);

    EntitlementResponse capGoi(UUID adminId, GrantPlanRequest req);

    void nhacSapHetHan(java.time.LocalDate homNay);

}
