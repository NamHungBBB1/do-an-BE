package com.doan.game.DTO.request;

/** Mở một nhóm: gia đình hoặc lớp học. Hạn mức chép từ gói đang giữ. */
public record CreateGroupRequest(String context, String name) {
}
