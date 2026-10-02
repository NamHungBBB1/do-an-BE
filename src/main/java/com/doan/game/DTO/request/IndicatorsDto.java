package com.doan.game.DTO.request;

/** Năm chỉ số 0..100 gửi dạng TỔNG CỘNG DỒN, nên lô gửi lại ghi đè vô hại. */
public record IndicatorsDto(int wealth, int saving, int happiness, int risk, int goal) {
}
