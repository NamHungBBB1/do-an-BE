package com.doan.game.controller;

import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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
 *
 * Từ 10/10 (N-09) health CHẠM DB: `select 1`. Đây là cửa duy nhất ci-deploy.sh dùng để quyết
 * giữ hay lùi bản, nên Postgres chết mà health vẫn "up" là deploy mù. DB không trả lời → 503,
 * `curl -sf` của script coi là hỏng.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    @Value("${app.build-version:dev}")
    private String buildVersion;

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, String>>> health() {
        try {
            jdbc.queryForObject("select 1", Integer.class);
        } catch (RuntimeException e) {
            log.error("health: DB không trả lời — {}", e.getMessage());
            // Mã 1000 dùng chung — không thêm mã mới chỉ cho một endpoint ops.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error(ErrorCode.UNCATEGORIZED.getCode(), "DB không trả lời"));
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "up", "db", "up", "buildVersion", buildVersion)));
    }
}
