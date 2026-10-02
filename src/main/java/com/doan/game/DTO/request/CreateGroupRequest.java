package com.doan.game.DTO.request;

/** Mở một nhóm: context FAMILY (cần gói PARENT, 4 slot) hoặc CLASS (cần gói TEACHER, 40 slot). Hạn mức chép vào nhóm lúc mở. */
public record CreateGroupRequest(String name, String context) {
}
