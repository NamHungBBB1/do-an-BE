package com.doan.game.DTO.request;

/**
 * Nhập / sửa khoá AI: provider GEMINI / OPENROUTER, alias tên gợi nhớ (hiện trên UI, nhật ký việc AI), key, priority (nhỏ dùng
 * trước). Đợt D (khung báo cáo AI 13.4): purpose GENERAL | DEMO_RESERVE (mặc định GENERAL), project (nhãn project của nhà cung
 * cấp — quota Gemini tính theo project), ownerNote, tierLabel FREE | PAID, rpmLimit / rpdLimit (null = không giới hạn).
 * PATCH chỉ đổi các trường khác null (provider, alias, key bị bỏ qua); chuỗi rỗng = xoá, số ≤ 0 = bỏ giới hạn.
 */
public record AiKeyRequest(String provider, String alias, String key, Integer priority, String purpose, String project,
                           String ownerNote, String tierLabel, Integer rpmLimit, Integer rpdLimit) {
}
