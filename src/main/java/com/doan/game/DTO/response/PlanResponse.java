package com.doan.game.DTO.response;

/**
 * Giá công khai của một gói; FE hiện ở trang mua (18/11/2026): bỏ {@code months} (gói vĩnh viễn),
 * thêm giá một pack slot ({@code addonSlots} slot/pack). {@code addonPrice} null = admin chưa đặt
 * giá pack, FE ẩn nút mua thêm.
 */
public record PlanResponse(String kind, long price, int addonSlots, Long addonPrice,
                           java.time.Instant updatedAt) {
}
