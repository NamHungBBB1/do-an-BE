package com.doan.game.controller;

import com.doan.game.DTO.response.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoint sống/chết. KHÔNG sinh bằng script và KHÔNG được xoá khi sinh lại khung.
 *
 * Đây là thứ duy nhất trong repo có người ngoài phụ thuộc: sổ đăng ký trỏ vào nó ở RS-03,
 * đội FE gọi nó để biết backend còn sống. Ngày 25/09 nó bị bộ sinh dọn mất cùng cả gói
 * controller, và không ai biết cho tới lần deploy kế tiếp — vì máy chủ vẫn chạy bản cũ.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @Value("${app.build-version:dev}")
    private String buildVersion;

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Map.of("status", "up", "buildVersion", buildVersion));
    }
}
