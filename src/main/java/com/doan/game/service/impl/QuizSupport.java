package com.doan.game.service.impl;

import com.doan.game.DTO.request.QuestionReferenceDto;
import com.doan.game.DTO.response.PostTestRequiredResponse;
import com.doan.game.DTO.response.QuestionResponse;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.entity.Question;
import com.doan.game.entity.Quiz;
import com.doan.game.entity.QuizAnswer;
import com.doan.game.entity.QuizResult;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.QuizKind;
import com.doan.game.enums.QuizMode;
import com.doan.game.enums.SlotStatus;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.mapper.QuizMapper;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.QuestionRepository;
import com.doan.game.repository.QuizAnswerRepository;
import com.doan.game.repository.QuizRepository;
import com.doan.game.repository.QuizResultRepository;
import com.doan.game.repository.RunStateRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phần dùng chung của ba service quiz (QuizServiceImpl, QuizAttemptServiceImpl, TeacherQuestionServiceImpl, cộng
 * QuestionBankServiceImpl): đọc / ghi JSON text, kiểm slot và đề của học sinh, ghi kết quả chính thức. Gói riêng
 * (package-private) để không thành API; tách ra để quiz v3 không chép lại code v2 (16/11).
 */
@Component
@RequiredArgsConstructor
class QuizSupport {

    /** Một câu đã chấm, theo CHỈ SỐ GỐC của đáp án (QuizAnswer.chosenIndex luôn là chỉ số gốc). */
    record GradedAnswer(Question question, Integer chosenIndex, boolean correct) {
    }

    /** Một vị trí trong đề riêng của em: q = id câu, o[i] = chỉ số gốc của đáp án em thấy ở vị trí i. */
    record PaperEntry(UUID q, List<Integer> o) {
    }

    private static final TypeReference<List<PaperEntry>> PAPER = new TypeReference<>() { };
    private static final TypeReference<Map<Integer, Integer>> ANSWERS = new TypeReference<>() { };

    private final QuestionRepository questionRepo;
    private final QuizRepository quizRepo;
    private final QuizResultRepository quizResultRepo;
    private final QuizAnswerRepository quizAnswerRepo;
    private final LearnerSlotRepository slotRepo;
    private final RunStateRepository runStateRepo;
    private final LearnerGroupRepository groupRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // ------------------------------------------------------------------ học sinh

    /** Học sinh: slot phải có (3007), chưa WIPED (3007) và chưa ARCHIVED (3009) — cùng luật dashboard. */
    LearnerSlot requireLearnerSlot(UUID slotId) {
        LearnerSlot slot = slotRepo.findById(slotId)
                .orElseThrow(() -> new AppException(ErrorCode.SLOT_NOT_FOUND));
        checkLearnerSlot(slot);
        return slot;
    }

    /** Khoá dòng slot (FOR UPDATE) như Play / nộp bài: hai request cùng lúc của một em đi tuần tự. */
    LearnerSlot lockLearnerSlot(UUID slotId) {
        LearnerSlot slot = slotRepo.lockById(slotId)
                .orElseThrow(() -> new AppException(ErrorCode.SLOT_NOT_FOUND));
        checkLearnerSlot(slot);
        return slot;
    }

    static void checkLearnerSlot(LearnerSlot slot) {
        if (slot.getStatus() == SlotStatus.WIPED) {
            throw new AppException(ErrorCode.SLOT_NOT_FOUND);
        }
        if (slot.getStatus() == SlotStatus.ARCHIVED) {
            throw new AppException(ErrorCode.SLOT_ARCHIVED);
        }
    }

    /**
     * Đề hợp lệ với em: của đúng nhóm, đã publish; hậu trắc thêm điều kiện nhóm bật cờ + chương đã xong. Gia đình chỉ có
     * hậu trắc (quiz v3, 16/11) — đề giáo viên không bao giờ thuộc nhóm FAMILY, kiểm thêm cho chắc.
     */
    Quiz requireLearnerQuiz(LearnerSlot slot, UUID quizId) {
        Quiz quiz = quizRepo.findById(quizId)
                .orElseThrow(() -> new AppException(ErrorCode.QUIZ_NOT_FOUND));
        if (quiz.getPublishedAt() == null || quiz.getGroup() == null
                || !quiz.getGroup().getId().equals(slot.getGroup().getId())) {
            throw new AppException(ErrorCode.QUIZ_NOT_FOUND);
        }
        if (isPostTest(quiz)) {
            if (quiz.getChapter() == null
                    || !chaptersDone(slot.getId()).contains(quiz.getChapter())) {
                throw new AppException(ErrorCode.QUIZ_NOT_FOUND);
            }
        } else if (slot.getGroup().getContext() != LearningContext.CLASS) {
            throw new AppException(ErrorCode.QUIZ_NOT_FOUND);
        }
        return quiz;
    }

    /** chaptersDone của em (RunState, play.md); chưa chơi → rỗng. */
    List<Integer> chaptersDone(UUID slotId) {
        return runStateRepo.findBySlot_Id(slotId)
                .map(run -> ChaptersDone.parse(run.getChaptersDone()))
                .orElseGet(List::of);
    }

    /** Số câu mỗi em làm: đề Kiểm tra rút drawCount câu, còn lại làm hết — một luật cho kết quả, danh sách, báo cáo. */
    int questionsPerLearner(Quiz quiz) {
        int total = parseIds(quiz.getQuestionIds()).size();
        return quiz.getMode() == QuizMode.TEST && quiz.getDrawCount() != null
                ? Math.min(quiz.getDrawCount(), total) : total;
    }

    static boolean isPostTest(Quiz quiz) {
        return quiz.getKind() == QuizKind.POST_TEST;
    }

    // ------------------------------------------------------------------ hậu trắc tự tạo + BR-53

    /**
     * Với mỗi chương trong {@code chapters}: nhóm chưa có đề hậu trắc && chương có ≥ 1 câu chưa rút → tạo. Chụp questionIds
     * (xếp theo externalId) lúc tạo, published ngay — từ đó bất biến. Hai nơi gọi: đọc danh sách của em (QuizServiceImpl) và
     * lúc em xong chương (PlayServiceImpl, 19/11 BR-53) — gọi lại vô hại. Chỉ khoá dòng nhóm (lockById) khi thật sự phải tạo,
     * để hai em xong chương cùng lúc không đụng UNIQUE(group, kind, chapter); vẫn đụng thì bỏ qua, lần đọc sau thấy đề của bạn.
     */
    void ensurePostTests(LearnerGroup group, Collection<Integer> chapters) {
        boolean locked = false;
        for (Integer chapter : chapters) {
            if (quizRepo.findByGroup_IdAndKindAndChapter(group.getId(), QuizKind.POST_TEST, chapter).isPresent()) {
                continue;
            }
            List<Question> questions = questionRepo.findByChapterAndRetiredAtIsNullOrderByExternalIdAsc(chapter);
            if (questions.isEmpty()) {
                continue;   // chương không có câu → không tạo gì, không lỗi
            }
            if (!locked) {
                group = groupRepo.lockById(group.getId()).orElse(group);
                locked = true;
                if (quizRepo.findByGroup_IdAndKindAndChapter(group.getId(), QuizKind.POST_TEST, chapter).isPresent()) {
                    continue;   // bạn khác vừa tạo trong lúc chờ khoá
                }
            }
            try {
                Quiz quiz = new Quiz();
                quiz.setOwner(group.getOwner());
                quiz.setGroup(group);
                quiz.setTitle("Hậu trắc chương " + chapter);
                quiz.setQuestionIds(toJson(questions.stream().map(Question::getId).toList()));
                quiz.setKind(QuizKind.POST_TEST);
                quiz.setChapter(chapter);
                quiz.setPublishedAt(Instant.now(clock));
                quizRepo.saveAndFlush(quiz);
            } catch (DataIntegrityViolationException e) {
                // Bạn khác cùng xong chương: UNIQUE(group, kind, chapter) chặn; lần sau đọc lại.
            }
        }
    }

    /**
     * BR-53 (Hưng 19/11): hậu trắc là bắt buộc — đề hậu trắc chương {@code chapter} của nhóm mà em CHƯA nộp (chưa có
     * QuizResult, đường nộp cũ hay lượt làm v3 đều ghi QuizResult) → trả đề đó, chương kế tiếp bị khoá. Chương không có câu
     * hậu trắc → null (không chặn). Nộp ở lần chơi nào cũng tính: QuizResult là một dòng / em / đề, không theo lần chơi.
     * Tạo đề trước nếu còn thiếu (em xong chương trước khi có BR-53, hoặc kho chương vừa có câu).
     */
    Quiz pendingPostTest(LearnerSlot slot, int chapter) {
        LearnerGroup group = slot.getGroup();
        if (group == null || chapter < 1) {
            return null;
        }
        ensurePostTests(group, List.of(chapter));
        return quizRepo.findByGroup_IdAndKindAndChapter(group.getId(), QuizKind.POST_TEST, chapter)
                .filter(quiz -> quizResultRepo.findByQuiz_IdAndSlot_Id(quiz.getId(), slot.getId()).isEmpty())
                .orElse(null);
    }

    /**
     * BR-53, phía đọc (GET /api/play/state, dashboard): chương kế tiếp đang khoá vì em chưa nộp hậu trắc chương trước →
     * {chapter, quizId}; không khoá → null. Chưa chơi, hoặc xong hết chương (lần chơi mới bắt đầu từ chương 1) → null.
     */
    PostTestRequiredResponse postTestRequired(LearnerSlot slot, Integer totalChapters, List<Integer> done) {
        if (done.isEmpty() || (totalChapters != null && done.size() >= totalChapters)) {
            return null;
        }
        int chapter = done.get(done.size() - 1);   // chương vừa xong — cùng chương PlayServiceImpl kiểm khi gửi lô
        Quiz quiz = pendingPostTest(slot, chapter);
        return quiz == null ? null : new PostTestRequiredResponse(chapter, quiz.getId());
    }

    // ------------------------------------------------------------------ kết quả chính thức

    /**
     * Ghi QuizResult + QuizAnswer từng câu (chỉ số gốc) — kết quả chính thức mà báo cáo, dashboard, kết quả giáo viên
     * đọc. Chỉ gọi khi (quiz, slot) chưa có QuizResult: đường nộp cũ và lần nộp đầu của lượt làm dùng chung.
     */
    QuizResult writeOfficialResult(Quiz quiz, LearnerSlot slot, Instant submittedAt, List<GradedAnswer> graded) {
        QuizResult result = new QuizResult();
        result.setQuiz(quiz);
        result.setSlot(slot);
        result.setSubmittedAt(submittedAt);
        result.setScore((int) graded.stream().filter(GradedAnswer::correct).count());
        result = quizResultRepo.save(result);
        List<QuizAnswer> answers = new ArrayList<>(graded.size());
        for (GradedAnswer g : graded) {
            QuizAnswer answer = new QuizAnswer();
            answer.setQuizResult(result);
            answer.setQuestion(g.question());
            answer.setChosenIndex(g.chosenIndex());
            answer.setCorrect(g.correct());
            answers.add(answer);
        }
        quizAnswerRepo.saveAll(answers);
        return result;
    }

    // ------------------------------------------------------------------ câu hỏi

    Map<UUID, Question> loadQuestions(Collection<UUID> ids) {
        Map<UUID, Question> out = new LinkedHashMap<>();
        for (Question question : questionRepo.findAllById(ids)) {
            out.put(question.getId(), question);
        }
        return out;
    }

    /** Câu cho người lớn, đủ trường v3 (reference, keepOptionOrder, copiedFromId). */
    QuestionResponse questionResponse(Question question, Integer usedInPublished) {
        return QuizMapper.toResponse(question, parseOptions(question.getOptions()),
                parseReference(question.getReference()), usedInPublished);
    }

    // ------------------------------------------------------------------ JSON text trong DB

    List<String> parseOptions(String json) {
        return json == null || json.isBlank() ? List.of() : read(json, new TypeReference<List<String>>() { }, "options");
    }

    List<UUID> parseIds(String json) {
        return json == null || json.isBlank() ? List.of() : read(json, new TypeReference<List<UUID>>() { }, "questionIds");
    }

    QuestionReferenceDto parseReference(String json) {
        return json == null || json.isBlank() ? null : read(json, new TypeReference<QuestionReferenceDto>() { }, "reference");
    }

    List<PaperEntry> parsePaper(String json) {
        return json == null || json.isBlank() ? List.of() : read(json, PAPER, "paper");
    }

    Map<Integer, Integer> parseAnswers(String json) {
        return json == null || json.isBlank() ? new java.util.TreeMap<>()
                : new java.util.TreeMap<>(read(json, ANSWERS, "answers"));
    }

    String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "khong ghi duoc JSON");
        }
    }

    private <T> T read(String json, TypeReference<T> type, String what) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, what + " hong trong DB");
        }
    }
}
