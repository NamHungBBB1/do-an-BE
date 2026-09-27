package com.doan.game.DTO.response;

/** Một tài khoản giữ đúng một gói: NONE, STANDARD hoặc EDU. */
public record AccountResponse(java.util.UUID id, String email, String displayName, String plan) {
}
