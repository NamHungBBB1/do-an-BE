package com.doan.game.DTO.response;

/** Giá công khai của một gói; FE hiện ở trang mua. */
public record PlanResponse(String kind, long price, int months, java.time.Instant updatedAt) {
}
