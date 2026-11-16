package com.doan.game.DTO.request;

/** Nguồn kiểm chứng của một câu kho chung (16/11, v3): nhập kho gửi lên, QuestionResponse trả lại nguyên. Chỉ hiển thị, không truy vấn. stemOriginal / optionsOriginal = câu gốc tiếng Anh; image để trống tới khi ảnh lên kho VPS. */
public record QuestionReferenceDto(String title, String url, String page, String location, String accessedOn, String image, String stemOriginal, java.util.List<String> optionsOriginal, String license, String licenseClass, Boolean standardFromSource) {
}
