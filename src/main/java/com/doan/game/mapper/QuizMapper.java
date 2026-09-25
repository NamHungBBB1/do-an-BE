package com.doan.game.mapper;

import com.doan.game.DTO.response.QuizResponse;
import com.doan.game.entity.Quiz;

/**
 * Mapper viết tay, phương thức tĩnh — đúng khuôn SWP, không dùng MapStruct.
 *
 * Tầng này tồn tại để entity KHÔNG bao giờ lọt thẳng ra API: lộ một cột nội bộ
 * ra ngoài thì về sau không rút lại được nữa.
 */
public final class QuizMapper {

    private QuizMapper() {
    }

    public static QuizResponse sang(Quiz nguon) {
        throw new UnsupportedOperationException("chua cai dat");
    }
}
