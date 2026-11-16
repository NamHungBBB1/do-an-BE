package com.doan.game.DTO.response;

/** Kết quả lưu từng câu của lô (16/11, v3): index = vị trí trong lô gửi lên; reused = trùng câu chưa rút của chính giáo viên nên dùng lại id cũ. */
public record SavedQuestionResponse(int index, java.util.UUID id, boolean reused) {
}
