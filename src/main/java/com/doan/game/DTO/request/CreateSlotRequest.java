package com.doan.game.DTO.request;

/** Người lớn mở một slot: đặt tên, PIN 6 số, avatar. Mã chữ / QR do hệ thống sinh. */
public record CreateSlotRequest(String displayName, String pin, String badge) {
}
