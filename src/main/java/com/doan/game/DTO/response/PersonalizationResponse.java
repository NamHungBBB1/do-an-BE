package com.doan.game.DTO.response;

import java.util.List;
import java.util.Map;

/**
 * Khảo sát cá nhân hoá của một trẻ (spec build/spec-ca-nhan-hoa-2026-11-11.md).
 * status: NOT_STARTED / WAITING_CHILD / WAITING_GAME / COMPLETED / SKIPPED — tính ra, không lưu.
 * labels: nhãn cuối (chiều → giá trị); chiều vắng = chưa biết → bản gốc. parent: câu của phụ huynh (chỉ trả cho phụ huynh, trẻ nhận null).
 * askChild: chiều cần hỏi trẻ; askInGame: chiều game chọn phụ cần hỏi; catalog: chiều → giá trị hợp lệ (FE dựng nút).
 */
public record PersonalizationResponse(String status, Map<String, String> labels, Map<String, String> parent,
                                      List<String> askChild, List<String> askInGame, Map<String, List<String>> catalog) {
}
