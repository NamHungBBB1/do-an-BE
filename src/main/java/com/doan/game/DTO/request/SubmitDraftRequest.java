package com.doan.game.DTO.request;

import com.doan.game.enums.ChangeClass;

/** Editor gửi duyệt: đề xuất loại thay đổi (bắt buộc), note = lý do sửa (tuỳ chọn, ghi vào changeNote). */
public record SubmitDraftRequest(ChangeClass changeClass, String note) {
}
