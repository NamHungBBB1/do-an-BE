package com.doan.game.DTO.request;

/** Phát hành: gom bản nháp mới nhất của mọi chương thành một phiên bản SemVer (vd 1.0.1). note tuỳ chọn. */
public record CreateReleaseRequest(String version, String note) {
}
