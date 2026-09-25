package com.doan.game.DTO.request;

/** Một đáp án mini-game. CÓ đúng sai — đây mới là dữ liệu đo. */
public record MiniGameDto(String gameId, String itemId, String chosen, boolean correct) {
}
