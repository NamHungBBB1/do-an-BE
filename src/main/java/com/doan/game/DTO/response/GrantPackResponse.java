package com.doan.game.DTO.response;

/** Kết quả admin cấp pack tay: số slot thực tế đã cộng vào ví của tài khoản. */
public record GrantPackResponse(java.util.UUID accountId, String kind, int packs, int slots, String reason) {
}
