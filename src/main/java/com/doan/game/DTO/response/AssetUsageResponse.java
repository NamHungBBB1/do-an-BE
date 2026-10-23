package com.doan.game.DTO.response;

import java.util.List;

/** Tệp được dùng ở đâu: chương, nơi (DRAFT nháp / CURRENT bản đang chạy / OLD bản trước), các phiên bản, các cảnh. */
public record AssetUsageResponse(String chapterCode, String where, List<String> versions, List<String> sceneIds) {
}
