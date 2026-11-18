package com.doan.game.DTO.request;

/**
 * Mua gói slot thêm (pack): {@code kind} = PARENT | TEACHER, {@code packs} ≥ 1. 18/11/2026: KHÔNG
 * có trần số pack — trần duy nhất là tổng tiền {@code packs × giá} ≤ MAX_PRICE_VND (chống tràn số,
 * không phải hạn mức).
 */
public record BuyAddonRequest(String kind, Integer packs) {
}
