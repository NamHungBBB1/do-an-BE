package com.doan.game.DTO.request;

/** Mua gói PARENT hoặc TEACHER (3 tháng). Mua mới hay gia hạn do BE tự xét theo gói còn hạn. */
public record BuyPlanRequest(String kind) {
}
