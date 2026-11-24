package com.doan.game.controller;

import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.NotificationListResponse;
import com.doan.game.DTO.response.NotificationReadAllResponse;
import com.doan.game.DTO.response.NotificationReadResponse;
import com.doan.game.DTO.response.UnreadCountResponse;
import com.doan.game.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Chuông thông báo học tập cho người lớn (24/11/2026). Tầng này MỎNG: danh tính lấy từ token, không
 * có accountId do client truyền lên — không có cửa đọc hộ thông báo của người khác.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** Danh sách mới nhất trước, kèm unreadCount của toàn hộp thư để FE vẽ badge một lần. */
    @GetMapping
    public ApiResponse<NotificationListResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return ApiResponse.ok(notificationService.list(currentAccountId(jwt), page, size, unreadOnly));
    }

    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountResponse> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(notificationService.unreadCount(currentAccountId(jwt)));
    }

    /** Đánh dấu một thông báo đã đọc. Gọi lại trên dòng đã đọc vẫn 200, readAt giữ nguyên bản đầu. */
    @PatchMapping("/{id}/read")
    public ApiResponse<NotificationReadResponse> markRead(@AuthenticationPrincipal Jwt jwt,
                                                          @PathVariable UUID id) {
        return ApiResponse.ok(notificationService.markRead(currentAccountId(jwt), id));
    }

    @PatchMapping("/read-all")
    public ApiResponse<NotificationReadAllResponse> markAllRead(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(notificationService.markAllRead(currentAccountId(jwt)));
    }

    private static UUID currentAccountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
