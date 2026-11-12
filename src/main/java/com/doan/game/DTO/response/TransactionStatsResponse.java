package com.doan.game.DTO.response;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Thống kê giao dịch trong một khoảng ngày, tính theo giờ Việt Nam.
 * {@code count} đếm theo trạng thái, {@code paidAmount} là doanh thu, {@code byDay} là doanh thu từng ngày.
 */
public record TransactionStatsResponse(Map<String, Long> count, long paidAmount, List<DayRow> byDay) {

    /** Một ngày có giao dịch PAID: số đơn và số tiền. */
    public record DayRow(LocalDate date, long paidCount, long paidAmount) {
    }
}
