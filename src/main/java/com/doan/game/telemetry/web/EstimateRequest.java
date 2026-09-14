package com.doan.game.telemetry.web;

import jakarta.validation.constraints.*;
import java.time.Instant;

/**
 * Một dòng cam kết gửi từ client.
 *
 * seed và buildVersion BẮT BUỘC — thiếu thì dòng log vô dụng cho phân tích,
 * nên chặn ngay ở cổng vào thay vì phát hiện lúc xuất CSV.
 */
public record EstimateRequest(
        @NotBlank @Size(max = 64) String playerKey,
        @NotBlank @Size(max = 32) String seed,
        @NotBlank @Size(max = 32) String buildVersion,
        @Size(max = 32) String vai,
        @NotBlank @Size(max = 64) String itemId,
        @NotNull @PositiveOrZero Long guess,
        @NotNull @PositiveOrZero Long truth,
        @NotBlank @Size(max = 16) String unit,
        @Pattern(regexp = "chac-chan|doan-mo", message = "chỉ nhận 'chac-chan' hoặc 'doan-mo'")
        String confidence,
        @NotNull @Min(0) Integer attemptIndex,
        @NotNull Instant shownAt,
        @NotNull Instant committedAt
) {}
