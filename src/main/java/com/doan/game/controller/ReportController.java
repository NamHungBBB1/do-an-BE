package com.doan.game.controller;

import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.ClassChapterReportResponse;
import com.doan.game.DTO.response.ClassCourseReportResponse;
import com.doan.game.DTO.response.GroupReportResponse;
import com.doan.game.DTO.response.SlotReportResponse;
import com.doan.game.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 *
 * Hai đường báo cáo người lớn (Hưng duyệt 12/10) đặt path đầy đủ từng method thay vì
 * class-level /api/reports — hợp đồng bỏ hai endpoint khung cũ (POST /api/reports/freeze/{groupId},
 * GET /api/reports/{id}) và đổi đường dẫn theo tài nguyên. Cổng rơi vào anyRequest → chỉ token
 * typ ACCOUNT; token trẻ (typ SLOT) bị 403/3004 ngay ở cửa Security, service vẫn tự kiểm chủ nhóm.
 */
@RestController
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /** Một học sinh, mọi thứ người lớn được xem — chỉ chủ nhóm; WIPED: code / displayName = null. */
    @GetMapping("/api/slots/{id}/report")
    public ApiResponse<SlotReportResponse> slotReport(@AuthenticationPrincipal Jwt jwt,
                                                      @PathVariable UUID id) {
        return ApiResponse.ok(reportService.slotReport(UUID.fromString(jwt.getSubject()), id));
    }

    /** Mọi slot của một nhóm (cả ARCHIVED / WIPED) + tổng kết; nhóm đã đóng lấy bản đóng băng. */
    @GetMapping("/api/groups/{id}/report")
    public ApiResponse<GroupReportResponse> groupReport(@AuthenticationPrincipal Jwt jwt,
                                                        @PathVariable UUID id) {
        return ApiResponse.ok(reportService.groupReport(UUID.fromString(jwt.getSubject()), id));
    }

    /** Một chương của nhóm (26/11): bốn nhóm tiến độ, nội dung, lựa chọn trong truyện — số và tỉ lệ BE tính sẵn. */
    @GetMapping("/api/groups/{id}/reports/chapters/{chapter}")
    public ApiResponse<ClassChapterReportResponse> chapterReport(@AuthenticationPrincipal Jwt jwt,
                                                                 @PathVariable UUID id, @PathVariable int chapter) {
        return ApiResponse.ok(reportService.chapterReport(UUID.fromString(jwt.getSubject()), id, chapter));
    }

    /** Toàn khoá của nhóm (26/11): tiến độ đủ chương, bài sau chương, tám chủ đề cộng dồn, danh sách em. */
    @GetMapping("/api/groups/{id}/reports/course")
    public ApiResponse<ClassCourseReportResponse> courseReport(@AuthenticationPrincipal Jwt jwt,
                                                               @PathVariable UUID id) {
        return ApiResponse.ok(reportService.courseReport(UUID.fromString(jwt.getSubject()), id));
    }
}
