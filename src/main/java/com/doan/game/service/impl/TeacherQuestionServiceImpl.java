package com.doan.game.service.impl;

import com.doan.game.DTO.request.NewQuestionDto;
import com.doan.game.DTO.response.QuestionErrorResponse;
import com.doan.game.DTO.response.QuestionResponse;
import com.doan.game.DTO.response.SavedQuestionResponse;
import com.doan.game.entity.Question;
import com.doan.game.entity.Quiz;
import com.doan.game.enums.PlanKind;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.QuestionRepository;
import com.doan.game.repository.QuizRepository;
import com.doan.game.service.EntitlementService;
import com.doan.game.service.TeacherQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * "Câu của tôi" (quiz v3, thiết kế mục 2). Câu của giáo viên là Question có ownerId; kho chung (ownerId NULL, chapter
 * NULL) và bộ hậu trắc không đi qua đây. Câu đề đọc "sống" nên cùng luật Studio: câu đã nằm trong đề đã phát thì khoá
 * nội dung (8009); câu đã từng vào đề thì xoá chỉ là ẩn (retiredAt), vì đề và QuizAnswer còn trỏ tới.
 */
@Service
@RequiredArgsConstructor
public class TeacherQuestionServiceImpl implements TeacherQuestionService {

    private final QuestionRepository questionRepo;
    private final QuizRepository quizRepo;
    private final AccountRepository accountRepo;
    private final EntitlementService entitlementService;
    private final QuizSupport support;
    private final Clock clock;

    /** Câu chưa rút của tôi, mới nhất trước, kèm số đề đã phát đang dùng (một truy vấn đề, đếm trong bộ nhớ). */
    @Override
    @Transactional(readOnly = true)
    public List<QuestionResponse> listMine(UUID callerId) {
        requireTeacherPlan(callerId);
        Map<UUID, Integer> used = new HashMap<>();
        for (Quiz quiz : quizRepo.findByOwner_IdAndPublishedAtIsNotNull(callerId)) {
            for (UUID id : support.parseIds(quiz.getQuestionIds())) {
                used.merge(id, 1, Integer::sum);
            }
        }
        List<QuestionResponse> out = new ArrayList<>();
        for (Question question : questionRepo.findByOwner_IdAndRetiredAtIsNullOrderByCreatedAtDesc(callerId)) {
            out.add(support.questionResponse(question, used.getOrDefault(question.getId(), 0)));
        }
        return out;
    }

    /**
     * Kiểm cả lô trước (lỗi theo vị trí, không lưu gì), rồi lưu: câu trùng nội dung chuẩn hoá với câu chưa rút của chính
     * người gọi — hoặc với câu đứng trước trong cùng lô — thì dùng lại id đó (reused = true).
     */
    @Override
    @Transactional
    public List<SavedQuestionResponse> saveMine(UUID callerId, List<NewQuestionDto> questions) {
        requireTeacherPlan(callerId);
        if (questions == null || questions.isEmpty() || questions.size() > QuizRules.MAX_QUESTIONS) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "questions: cần 1–" + QuizRules.MAX_QUESTIONS + " câu");
        }
        List<QuestionErrorResponse> errors = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            validate(i, questions.get(i), errors);
        }
        if (!errors.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, errors.size() + " câu không hợp lệ", errors);
        }
        Map<String, UUID> byKey = new HashMap<>();
        for (Question existing : questionRepo.findByOwner_IdAndRetiredAtIsNullOrderByCreatedAtDesc(callerId)) {
            byKey.putIfAbsent(dedupKey(existing.getStem(), support.parseOptions(existing.getOptions()),
                    existing.getCorrectIndex()), existing.getId());
        }
        Instant now = Instant.now(clock);
        List<SavedQuestionResponse> out = new ArrayList<>(questions.size());
        for (int i = 0; i < questions.size(); i++) {
            NewQuestionDto dto = questions.get(i);
            String key = dedupKey(dto.stem(), dto.options(), dto.correctIndex());
            UUID reuse = byKey.get(key);
            if (reuse != null) {
                out.add(new SavedQuestionResponse(i, reuse, true));
                continue;
            }
            Question question = new Question();
            question.setOwner(accountRepo.getReferenceById(callerId));
            question.setCreatedAt(now);
            apply(question, dto);
            question = questionRepo.save(question);
            byKey.put(key, question.getId());
            out.add(new SavedQuestionResponse(i, question.getId(), false));
        }
        return out;
    }

    /** Sửa câu của tôi; câu đang nằm trong đề đã phát → 8009 (đề đọc câu sống, em đang làm không được thấy câu đổi). */
    @Override
    @Transactional
    public QuestionResponse updateMine(UUID callerId, UUID questionId, NewQuestionDto req) {
        requireTeacherPlan(callerId);
        Question question = requireMine(callerId, questionId);
        List<QuestionErrorResponse> errors = new ArrayList<>();
        validate(0, req, errors);
        if (!errors.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, errors.get(0).field() + ": "
                    + errors.get(0).message(), errors);
        }
        if (quizRepo.existsByPublishedAtIsNotNullAndQuestionIdsContaining(questionId.toString())) {
            throw new AppException(ErrorCode.QUESTION_IN_USE);
        }
        apply(question, req);
        return support.questionResponse(questionRepo.save(question), null);
    }

    /** Chưa từng vào đề nào (kể cả nháp) → xoá cứng; đã vào đề → ẩn (retiredAt), đề cũ còn nguyên. */
    @Override
    @Transactional
    public void deleteMine(UUID callerId, UUID questionId) {
        requireTeacherPlan(callerId);
        Question question = requireMine(callerId, questionId);
        if (quizRepo.existsByQuestionIdsContaining(questionId.toString())) {
            question.setRetiredAt(Instant.now(clock));
            questionRepo.save(question);
        } else {
            questionRepo.delete(question);
        }
    }

    /** Câu kho chung (owner NULL, chapter NULL, chưa rút) → bản sao của tôi; câu hậu trắc / của người khác → 8007. */
    @Override
    @Transactional
    public QuestionResponse copy(UUID callerId, UUID questionId) {
        requireTeacherPlan(callerId);
        Question source = questionRepo.findById(questionId)
                .orElseThrow(() -> new AppException(ErrorCode.QUESTION_INVALID));
        if (source.getOwner() != null || source.getChapter() != null || source.getRetiredAt() != null) {
            throw new AppException(ErrorCode.QUESTION_INVALID);
        }
        Question copy = new Question();
        copy.setOwner(accountRepo.getReferenceById(callerId));
        copy.setCopiedFrom(source);
        copy.setCreatedAt(Instant.now(clock));
        copy.setStem(source.getStem());
        copy.setOptions(source.getOptions());
        copy.setCorrectIndex(source.getCorrectIndex());
        copy.setStandard(source.getStandard());
        copy.setConcept(source.getConcept());
        copy.setSource(source.getSource());
        copy.setReference(source.getReference());
        copy.setKeepOptionOrder(source.isKeepOptionOrder());
        return support.questionResponse(questionRepo.save(copy), null);
    }

    // ------------------------------------------------------------------ phụ trợ

    private void requireTeacherPlan(UUID callerId) {
        if (callerId == null || !entitlementService.activePlans(callerId).contains(PlanKind.TEACHER)) {
            throw new AppException(ErrorCode.PLAN_REQUIRED);
        }
    }

    /** Câu của người khác → 3004; không có hoặc đã ẩn → 1003. */
    private Question requireMine(UUID callerId, UUID questionId) {
        Question question = questionRepo.findById(questionId)
                .filter(q -> q.getRetiredAt() == null)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có câu " + questionId));
        if (question.getOwner() == null || !callerId.equals(question.getOwner().getId())) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return question;
    }

    /** Luật QuizRules: stem 1–500 sau trim; 2–4 lựa chọn, mỗi lựa chọn 1–200; correctIndex trỏ vào lựa chọn có thật. */
    private static void validate(int index, NewQuestionDto dto, List<QuestionErrorResponse> errors) {
        if (dto == null) {
            errors.add(new QuestionErrorResponse(index, "question", "thiếu câu"));
            return;
        }
        String stem = dto.stem() == null ? "" : dto.stem().trim();
        if (stem.isEmpty() || stem.length() > QuizRules.MAX_STEM_LENGTH) {
            errors.add(new QuestionErrorResponse(index, "stem", "cần 1–" + QuizRules.MAX_STEM_LENGTH + " ký tự"));
        }
        List<String> options = dto.options();
        if (options == null || options.size() < QuizRules.MIN_OPTIONS || options.size() > QuizRules.MAX_OPTIONS) {
            errors.add(new QuestionErrorResponse(index, "options",
                    "cần " + QuizRules.MIN_OPTIONS + "–" + QuizRules.MAX_OPTIONS + " lựa chọn"));
            return;
        }
        for (int i = 0; i < options.size(); i++) {
            String option = options.get(i) == null ? "" : options.get(i).trim();
            if (option.isEmpty() || option.length() > QuizRules.MAX_OPTION_LENGTH) {
                errors.add(new QuestionErrorResponse(index, "options[" + i + "]",
                        "cần 1–" + QuizRules.MAX_OPTION_LENGTH + " ký tự"));
            }
        }
        if (dto.correctIndex() == null || dto.correctIndex() < 0 || dto.correctIndex() >= options.size()) {
            errors.add(new QuestionErrorResponse(index, "correctIndex", "ngoài phạm vi lựa chọn"));
        }
    }

    private void apply(Question question, NewQuestionDto dto) {
        question.setStem(dto.stem().trim());
        question.setOptions(support.toJson(dto.options().stream().map(String::trim).toList()));
        question.setCorrectIndex(dto.correctIndex());
    }

    /** So trùng: trim + gộp khoảng trắng + chữ thường (Locale.ROOT) cho câu hỏi và từng lựa chọn, cộng đáp án đúng. */
    private static String dedupKey(String stem, List<String> options, Integer correctIndex) {
        StringBuilder key = new StringBuilder(normalize(stem));
        for (String option : options) {
            key.append('\u0000').append(normalize(option));
        }
        return key.append('\u0000').append(correctIndex).toString();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
