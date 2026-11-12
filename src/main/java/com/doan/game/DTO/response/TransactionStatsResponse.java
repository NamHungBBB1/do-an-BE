package com.doan.game.DTO.response;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Thống kê giao dịch trong một khoảng ngày, tính theo giờ Việt Nam.
 * {@code count} đếm theo trạng thái; {@code paidAmount} là tiền thu, {@code refundedAmount} là tiền hoàn,
 * {@code netAmount} = paid − refunded. Doanh thu lọc theo {@code paidAt}, tiền hoàn theo {@code refundedAt},
 * nên mỗi khoản nằm đúng ngày phát sinh của nó.
 */
public record TransactionStatsResponse(Map<String, Long> count, long paidAmount, long refundedAmount,
                                       long netAmount, long refundedCount, List<DayRow> byDay) {

    /** Một ngày có giao dịch PAID hoặc có hoàn tiền. */
    public record DayRow(LocalDate date, long paidCount, long paidAmount, long refundedCount,
                         long refundedAmount, long netAmount) {
    }
}
