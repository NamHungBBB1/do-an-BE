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

    @PostMapping("/{id}/close")
    public void closeGroup() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @GetMapping("/{id}/capacity")
    public void countFreeSlots() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    private static UUID currentAccountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

}
