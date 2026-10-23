package com.doan.game.DTO.request;

/** Thu hồi bản đang chạy (PR C, 23/10): lý do bắt buộc — ghi vào GameRelease.recallReason. */
public record RecallReleaseRequest(String reason) {
}
