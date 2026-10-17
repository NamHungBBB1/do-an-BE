package com.doan.game.service.impl;

import com.doan.game.DTO.request.UpsertQuestionRequest;
import com.doan.game.DTO.response.QuestionResponse;
import com.doan.game.entity.Question;
import com.doan.game.enums.Role;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.mapper.QuizMapper;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.QuestionRepository;
import com.doan.game.repository.QuizRepository;
import com.doan.game.service.QuestionBankService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Kho câu hỏi trong Studio (quiz.md v2, Hưng chốt 17/10 "cách 1"): ba vai nội bộ dùng chung một kho với
 * admin. Cổng /api/studio/questions/** (SecurityConfig) cho bốn vai vào; ai được GHI thì kiểm ở đây:
 * ADMIN, GAME_EDITOR, GAME_MANAGER — GAME_REVIEWER chỉ đọc (3004 khi ghi).
 *
 * Luật giữ số liệu của lớp (cùng nguyên tắc "bản phát hành bất biến" của chương): câu đã nằm trong một
 * đề ĐÃ PHÁT thì khoá chữ / lựa chọn / đáp án (8009) — em đang làm bài không thấy câu đổi, cả lớp cùng
 * một đáp án. Muốn đổi thì tạo câu mới kèm replacesId: câu cũ tự rút, đề cũ giữ câu cũ, đề mới nhận câu
 * mới. Nhãn (standard, concept, source, pool, chapter) sửa được vì trẻ không thấy và không đổi điểm.
 * Không xoá cứng: đề cũ và QuizAnswer còn trỏ tới câu.
 */
@Service
@RequiredArgsConstructor
public class QuestionBankServiceImpl implements QuestionBankService {

    private final QuestionRepository questionRepo;
    private final QuizRepository quizRepo;
    private final AccountRoleRepository accountRoleRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Toàn kho (kể cả bộ hậu trắc); câu đã rút chỉ khi includeRetired. Xếp: kho chung trước, rồi theo chương, rồi externalId. */
    @Override
    @Transactional(readOnly = true)
    public List<QuestionResponse> list(Integer chapter, String pool, String standard, String concept, String q,
                                       boolean includeRetired) {
        List<Question> rows = new ArrayList<>(questionRepo.findAll());
        rows.sort(Comparator
                .comparing((Question x) -> x.getChapter() == null ? 0 : x.getChapter())
                .thenComparing(x -> x.getExternalId() == null ? "" : x.getExternalId()));
        List<QuestionResponse> out = new ArrayList<>();
        for (Question question : rows) {
            if (!includeRetired && question.getRetiredAt() != null) {
                continue;
            }
            if (chapter != null && !chapter.equals(question.getChapter())) {
                continue;
            }
            if (notBlank(pool) && !pool.equals(question.getPool())) {
                continue;
            }
            if (notBlank(standard) && !standard.equals(question.getStandard())) {
                continue;
            }
            if (notBlank(concept) && !concept.equals(question.getConcept())) {
                continue;
            }
            if (notBlank(q) && (question.getStem() == null
                    || !question.getStem().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))) {
                continue;
            }
            out.add(QuizMapper.toResponse(question, parseOptions(question.getOptions())));
        }
        return out;
    }

    /** Thêm một câu; externalId trống thì tự sinh; replacesId → câu cũ bị rút cùng giao dịch. */
    @Override
    @Transactional
    public QuestionResponse create(UUID callerId, UpsertQuestionRequest req) {
        requireWriter(callerId);
        validate(req);
        Instant now = Instant.now(clock);
        String externalId = req.externalId() == null || req.externalId().isBlank()
                ? "ST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT)
                : req.externalId().trim();
        if (externalId.length() > 80) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "externalId: tối đa 80 ký tự");
        }
        if (questionRepo.findByExternalId(externalId).isPresent()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "externalId đã có: " + externalId);
        }
        Question question = new Question();
        question.setExternalId(externalId);
        question.setCreatedAt(now);
        if (req.replacesId() != null) {
            Question old = questionRepo.findById(req.replacesId())
                    .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có câu " + req.replacesId()));
            if (old.getRetiredAt() == null) {
                old.setRetiredAt(now);   // câu cũ rút ngay: đề mới không chụp nó nữa, đề cũ vẫn giữ
                questionRepo.save(old);
            }
            question.setReplaces(old);
        }
        apply(question, req);
        question = questionRepo.save(question);
        return QuizMapper.toResponse(question, parseOptions(question.getOptions()));
    }

    /** Sửa; câu đã nằm trong đề đã phát thì chữ / lựa chọn / đáp án phải giữ nguyên (8009), nhãn thì sửa được. */
    @Override
    @Transactional
    public QuestionResponse update(UUID callerId, UUID questionId, UpsertQuestionRequest req) {
        requireWriter(callerId);
        Question question = require(questionId);
        validate(req);
        if (quizRepo.existsByPublishedAtIsNotNullAndQuestionIdsContaining(questionId.toString())) {
            boolean sameContent = Objects.equals(question.getStem(), req.stem().trim())
                    && parseOptions(question.getOptions()).equals(req.options())
                    && Objects.equals(question.getCorrectIndex(), req.correctIndex());
            if (!sameContent) {
                throw new AppException(ErrorCode.QUESTION_IN_USE);
            }
        }
        apply(question, req);
        question = questionRepo.save(question);
        return QuizMapper.toResponse(question, parseOptions(question.getOptions()));
    }

    /** Rút khỏi kho (idempotent): đề mới không chụp, đề cũ giữ nguyên. */
    @Override
    @Transactional
    public QuestionResponse retire(UUID callerId, UUID questionId) {
        requireWriter(callerId);
        Question question = require(questionId);
        if (question.getRetiredAt() == null) {
            question.setRetiredAt(Instant.now(clock));
            question = questionRepo.save(question);
        }
        return QuizMapper.toResponse(question, parseOptions(question.getOptions()));
    }

    /** Đưa lại vào kho (idempotent). */
    @Override
    @Transactional
    public QuestionResponse restore(UUID callerId, UUID questionId) {
        requireWriter(callerId);
        Question question = require(questionId);
        if (question.getRetiredAt() != null) {
            question.setRetiredAt(null);
            question = questionRepo.save(question);
        }
        return QuizMapper.toResponse(question, parseOptions(question.getOptions()));
    }

    // ------------------------------------------------------------------ phụ trợ

    /** Ghi: ADMIN, GAME_EDITOR, GAME_MANAGER. Reviewer qua được cổng nhưng chỉ đọc. Vai tra DB, không tin JWT. */
    private void requireWriter(UUID callerId) {
        if (callerId == null
                || !(accountRoleRepo.existsByAccount_IdAndRole(callerId, Role.ADMIN)
                || accountRoleRepo.existsByAccount_IdAndRole(callerId, Role.GAME_EDITOR)
                || accountRoleRepo.existsByAccount_IdAndRole(callerId, Role.GAME_MANAGER))) {
            throw new AppException(ErrorCode.FORBIDDEN, "cần vai GAME_EDITOR, GAME_MANAGER hoặc ADMIN để sửa kho");
        }
    }

    private Question require(UUID questionId) {
        if (questionId == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "thiếu id câu");
        }
        return questionRepo.findById(questionId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có câu " + questionId));
    }

    /** Cùng luật với nhập kho hàng loạt (1001). */
    private static void validate(UpsertQuestionRequest req) {
        if (req == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "thiếu body");
        }
        if (req.stem() == null || req.stem().isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "stem: bắt buộc");
        }
        if (req.options() == null || req.options().size() < 2) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "options: cần ít nhất 2 lựa chọn");
        }
        if (req.options().stream().anyMatch(o -> o == null || o.isBlank())) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "options: lựa chọn không được rỗng");
        }
        if (req.correctIndex() == null || req.correctIndex() < 0 || req.correctIndex() >= req.options().size()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "correctIndex: ngoài phạm vi lựa chọn");
        }
        if (req.chapter() != null && (req.chapter() < 1 || req.chapter() > 8)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "chapter: chỉ 1..8 hoặc null");
        }
        if (req.source() != null && req.source().length() > 160) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "source: tối đa 160 ký tự");
        }
    }

    /** Các trường sửa được; externalId, replaces, createdAt, retiredAt không đi qua đây. */
    private void apply(Question question, UpsertQuestionRequest req) {
        question.setPool(req.pool());
        question.setStem(req.stem().trim());
        question.setOptions(toJson(req.options()));
        question.setCorrectIndex(req.correctIndex());
        question.setStandard(req.standard());
        question.setConcept(req.concept());
        // license vào source khi source trống — cùng luật với nhập kho, không thêm cột.
        question.setSource(notBlank(req.source()) ? req.source() : req.license());
        question.setChapter(req.chapter());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private List<String> parseOptions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "options hong trong DB");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "khong ghi duoc JSON");
        }
    }
}
