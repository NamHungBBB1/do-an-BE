package com.doan.game.DTO.request;

import com.doan.game.enums.ChangeClass;

/**
 * Reviewer duyệt (pass = true) hoặc trả về (pass = false, note là lý do, bắt buộc). changeClass = loại đã xác nhận;
 * bỏ trống thì giữ loại Editor đề xuất. Nghi ngờ thì xếp loại cao hơn (quy tắc phát hành 05/10, mục 3).
 */
public record ReviewDraftRequest(Boolean pass, ChangeClass changeClass, String note) {
}
