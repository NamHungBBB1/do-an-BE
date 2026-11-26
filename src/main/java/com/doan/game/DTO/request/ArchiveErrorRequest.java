package com.doan.game.DTO.request;

/** Máy lưu trữ báo một việc không thành: lý do (BE cắt 255 ký tự, chỉ giữ nội bộ). */
public record ArchiveErrorRequest(String error) {
}
