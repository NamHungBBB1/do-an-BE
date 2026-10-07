package com.doan.game.controller;

import com.doan.game.DTO.request.CreateGroupRequest;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.LearnerGroupResponse;
import com.doan.game.service.GroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 *
 * Cổng rơi vào anyRequest → chỉ token typ ACCOUNT (token trẻ bị 403). Việc kiểm gói PARENT /
 * TEACHER nằm hết ở service — vai trong JWT không đáng tin, mọi thao tác tự tra activePlans.
 */
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    /** Mở nhóm: FAMILY (cần gói PARENT, 4 slot) hoặc CLASS (cần gói TEACHER, 40 slot). */
    @PostMapping
    public ApiResponse<LearnerGroupResponse> openGroup(@AuthenticationPrincipal Jwt jwt,
                                                       @RequestBody CreateGroupRequest req) {
        return ApiResponse.ok(groupService.openGroup(currentAccountId(jwt), req));
    }

    /** Nhóm của tôi: đang mở trước, đã đóng sau; kèm slotUsed (số slot ACTIVE). */
    @GetMapping
    public ApiResponse<java.util.List<LearnerGroupResponse>> getMyGroups(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(groupService.getMyGroups(currentAccountId(jwt)));
    }

    /** Slot trong nhóm (cả ARCHIVED / WIPED), chỉ chủ nhóm — người khác 3004. */
    @GetMapping("/{id}/slots")
    public ApiResponse<java.util.List<com.doan.game.DTO.response.SlotResponse>> listSlots(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponse.ok(groupService.listSlots(currentAccountId(jwt), id));
    }

    /** Chỗ trống = slotLimit − số slot ACTIVE; chỉ chủ nhóm. */
    @GetMapping("/{id}/capacity")
    public ApiResponse<Integer> countFreeSlots(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponse.ok(groupService.countFreeSlots(currentAccountId(jwt), id));
    }

    /**
     * Chủ nhóm CLASS xác nhận đã có đồng ý của phụ huynh — bắt buộc trước khi mở slot trong
     * nhóm CLASS (5003); FAMILY không cần. Idempotent.
     */
    @PostMapping("/{id}/consent")
    public ApiResponse<Void> confirmConsent(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable UUID id) {
        groupService.confirmConsent(currentAccountId(jwt), id);
        return ApiResponse.ok();
    }

    /**
     * Kết thúc nhóm: closedAt = now, slot ACTIVE → ARCHIVED, không xoá gì và không có API khôi
     * phục (Hưng chốt 07/10 — muốn dạy tiếp thì mở lớp mới). Chỉ chủ nhóm.
     */
    @PostMapping("/{id}/close")
    public ApiResponse<Void> closeGroup(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        groupService.closeGroup(currentAccountId(jwt), id);
        return ApiResponse.ok();
    }

    private static UUID currentAccountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

}
