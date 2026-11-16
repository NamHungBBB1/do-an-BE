package com.doan.game.DTO.request;

/** Một câu giáo viên soạn / import (16/11, v3): stem 1–500 ký tự, 2–4 lựa chọn mỗi lựa chọn 1–200 ký tự, correctIndex trỏ vào lựa chọn có thật. */
public record NewQuestionDto(String stem, java.util.List<String> options, Integer correctIndex) {
}
