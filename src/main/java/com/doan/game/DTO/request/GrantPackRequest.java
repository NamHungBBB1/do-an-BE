package com.doan.game.DTO.request;

/** Admin cấp tay số pack slot để đền bù (hoàn tiền xử lý ngoài hệ thống). `reason` bắt buộc. */
public record GrantPackRequest(java.util.UUID accountId, String kind, Integer packs, String reason) {
}
