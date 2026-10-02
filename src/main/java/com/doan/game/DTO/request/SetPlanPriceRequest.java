package com.doan.game.DTO.request;

/** Admin đặt giá (VND) và tuỳ chọn số tháng của một gói; months để trống thì giữ nguyên (mặc định 3). */
public record SetPlanPriceRequest(long price, Integer months) {
}
