package com.doan.game.DTO.request;

/** Người lớn mở một slot trong nhóm của mình: đặt tên, PIN 6 số, avatar. Mã chữ / QR do hệ thống sinh. groupId thêm 07/10 (Hưng duyệt); với /bulk thì groupId từng phần tử phải trùng nhau. */
public record CreateSlotRequest(java.util.UUID groupId, String displayName, String pin, String badge) {
}
