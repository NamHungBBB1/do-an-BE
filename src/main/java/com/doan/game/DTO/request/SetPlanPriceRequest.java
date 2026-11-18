package com.doan.game.DTO.request;

/**
 * Admin đặt giá (VND) cho một gói hoặc một pack. 18/11/2026: bỏ {@code months} — gói bán ra là vĩnh
 * viễn, không còn khái niệm số tháng trên bảng giá.
 */
public record SetPlanPriceRequest(long price) {
}
