package com.doan.game.mapper;

import com.doan.game.DTO.response.QuizResponse;
import com.doan.game.entity.Question;
import com.doan.game.entity.Quiz;
import com.doan.game.DTO.response.QuestionResponse;

/**
 * Mapper viết tay, phương thức tĩnh — đúng khuôn SWP, không dùng MapStruct.
 *
 * Tầng này tồn tại để entity KHÔNG bao giờ lọt thẳng ra API: options / questionIds lưu dạng
 * JSON text trong DB, ra ngoài phải là mảng; Quiz.kind NULL (dòng cũ) ra ngoài là CLASS_TEST.
 */
public final class QuizMapper {

    private QuizMapper() {
    }

    /** kind NULL (dòng trước 16/10) coi là CLASS_TEST — hợp đồng: null trong DB trả CLASS_TEST. */
    public static String kindName(Quiz source) {
        return source.getKind() == null ? "CLASS_TEST" : source.getKind().name();
    }

    public static QuizResponse toResponse(Quiz source, int questionCount, int submittedCount) {
        return new QuizResponse(source.getId(), source.getGroup().getId(), kindName(source),
                source.getChapter(), source.getTitle(), questionCount, source.getDueAt(),
                source.getPublishedAt(), submittedCount);
    }

    /** Câu cho NGƯỜI LỚN (giáo viên chọn câu, xem đáp án) — có correctIndex. */
    public static QuestionResponse toResponse(Question source, java.util.List<String> options) {
        return new QuestionResponse(source.getId(), source.getExternalId(), source.getPool(),
                source.getStem(), options, source.getCorrectIndex(), source.getStandard(),
                source.getConcept(), source.getSource(), source.getChapter(), source.getRetiredAt(),
                source.getReplaces() == null ? null : source.getReplaces().getId());   // 17/10 Studio kho
    }
}
