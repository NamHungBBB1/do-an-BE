package com.doan.game.DTO.request;

/** Máy lưu trữ xin việc tới hạn: tối đa bao nhiêu tệp một lượt (BE kẹp theo app.archive.max-claim). */
public record ArchiveClaimRequest(Integer limit) {
}
