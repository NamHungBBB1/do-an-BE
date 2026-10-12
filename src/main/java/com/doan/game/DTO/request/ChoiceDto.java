package com.doan.game.DTO.request;

/** Lựa chọn cuối cùng có hiệu lực của một nhóm lựa chọn (choiceGroup, mặc định = cảnh). */
public record ChoiceDto(String sceneId, String optionId, java.time.Instant chosenAt) {
}
