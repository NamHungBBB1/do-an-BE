package com.doan.game.DTO.request;

/** Nhập khoá AI: provider GEMINI / OPENROUTER, alias tên gợi nhớ (hiện trên UI, nhật ký việc AI), key, priority (nhỏ dùng trước). */
public record AiKeyRequest(String provider, String alias, String key, Integer priority) {
}
