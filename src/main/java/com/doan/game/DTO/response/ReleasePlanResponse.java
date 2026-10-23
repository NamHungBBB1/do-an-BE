package com.doan.game.DTO.response;

import com.doan.game.enums.ChangeClass;

import java.util.List;

/**
 * Xem trước bản sắp phát hành (23/10) — màn xác nhận "Phát hành 1.2.0 gồm CH04, CH05": số hiệu BE sẽ tính, loại cao nhất
 * (chương mới = MAJOR), bản đang chạy làm nền (null khi chưa phát hành bao giờ) và các nháp đã duyệt sẽ vào bản.
 * Chương không nằm trong drafts giữ nguyên nội dung bản đang chạy.
 */
public record ReleasePlanResponse(String version, ChangeClass changeClass, String currentVersion,
                                  List<DraftSummaryResponse> drafts) {
}
