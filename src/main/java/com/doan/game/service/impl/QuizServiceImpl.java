package com.doan.game.service.impl;

import com.doan.game.DTO.request.AnswerDto;
import com.doan.game.DTO.request.CreateQuizRequest;
import com.doan.game.DTO.request.ImportQuestionDto;
import com.doan.game.DTO.request.NewQuestionDto;
import com.doan.game.DTO.request.SubmitQuizRequest;
import com.doan.game.DTO.request.UpdateQuizRequest;
import com.doan.game.DTO.response.AnswerReviewResponse;
import com.doan.game.DTO.response.ImportQuestionsResponse;
import com.doan.game.DTO.response.LearnerQuestionResponse;
import com.doan.game.DTO.response.LearnerQuizCompletedResponse;
import com.doan.game.DTO.response.LearnerQuizListResponse;
import com.doan.game.DTO.response.LearnerQuizPendingResponse;
import com.doan.game.DTO.response.LearnerQuizResponse;
import com.doan.game.DTO.response.PaperItemResponse;
import com.doan.game.DTO.response.QuestionResponse;
import com.doan.game.DTO.response.QuizAnswerResponse;
import com.doan.game.DTO.response.QuizDetailResponse;
import com.doan.game.DTO.response.QuizResponse;
import com.doan.game.DTO.response.QuizResultResponse;
import com.doan.game.DTO.response.QuizResultsResponse;
import com.doan.game.DTO.response.QuizResultsSummaryResponse;
import com.doan.game.DTO.response.QuizSlotResultResponse;
import com.doan.game.DTO.response.SavedQuestionResponse;
import com.doan.game.DTO.response.SubmitQuizResponse;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.entity.Question;
import com.doan.game.entity.Quiz;
import com.doan.game.entity.QuizAnswer;
import com.doan.game.entity.QuizAttempt;
import com.doan.game.entity.QuizResult;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.QuizMode;
import com.doan.game.enums.PlanKind;
import com.doan.game.enums.QuizKind;
import com.doan.game.enums.Role;
import com.doan.game.enums.SlotStatus;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.mapper.QuizMapper;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.QuestionRepository;
import com.doan.game.repository.QuizAnswerRepository;
import com.doan.game.repository.QuizAttemptRepository;
import com.doan.game.repository.QuizRepository;
import com.doan.game.repository.QuizResultRepository;
import com.doan.game.service.EntitlementService;
import com.doan.game.service.QuizAttemptService;
import com.doan.game.service.QuizService;
import com.doan.game.service.TeacherQuestionService;
import com.doan.game.service.impl.QuizSupport.GradedAnswer;
import com.doan.game.service.impl.QuizSupport.PaperEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ruột quiz hai kiểu (quiz.md v2, Hưng chốt 16/10; v3 16/11).
 *
 * Đề giáo viên (CLASS_TEST): chọn câu kho chung (chapter null) hoặc câu của chính mình (v3), publish rồi bất biến.
 * Hậu trắc (POST_TEST): luôn có cho mọi nhóm (17/11, Hưng — bỏ cờ postTestEnabledAt). MỖI lần đọc danh sách của em, nếu em đã
 * xong chương N mà nhóm chưa có đề hậu trắc chương N và chương đó có câu → BE tự tạo (chụp
 * questionIds lúc tạo, published ngay, không hạn). 19/11 (BR-53): PlayService cũng tạo ngay khi ghi lô cuối chương — cùng
 * hàm QuizSupport.ensurePostTests; thêm chương sau vẫn tự đúng ở lần gọi kế tiếp. Từ v3 gia đình cũng có hậu trắc.
 *
 * Máy chủ chấm, mỗi em nộp một lần (UNIQUE quiz + slot, khoá dòng slot bằng lockById như Play),
 * đáp án KHÔNG BAO GIỜ xuống trẻ: DTO học sinh không có correctIndex, sau khi nộp chỉ trả đúng /
 * sai từng câu. Câu không nộp = bỏ trống = sai (OECD/INFE, khung chấm 3.2).
 *
 * v3: đề có mode (PRACTICE / TEST) làm qua lượt làm (QuizAttemptService) — đường nộp cũ trả 8010; đề cũ mode NULL
 * và hậu trắc vẫn nộp đường cũ. Kết quả giáo viên chốt lượt quá giờ trước khi đọc.
 */
@Service
@RequiredArgsConstructor
public class QuizServiceImpl implements QuizService {

    private final QuestionRepository questionRepo;
    private final QuizRepository quizRepo;
    private final QuizResultRepository quizResultRepo;
    private final QuizAnswerRepository quizAnswerRepo;
    private final QuizAttemptRepository attemptRepo;
    private final LearnerGroupRepository groupRepo;
    private final LearnerSlotRepository slotRepo;
    private final AccountRoleRepository accountRoleRepo;
    private final EntitlementService entitlementService;
    private final TeacherQuestionService teacherQuestionService;
    private final QuizAttemptService quizAttemptService;
    private final QuizSupport support;
    private final Clock clock;

    // ------------------------------------------------------------------ kho câu hỏi

    /**
     * Nhập kho một lần, lặp lại được (ops): upsert theo externalId, câu thiếu lựa chọn bỏ qua
     * (skipped), còn thiếu stem / externalId / sai đáp án / sai chương là lỗi 1001 — dữ liệu đầu
     * vào của admin mà sai thì phải thấy ngay, không âm thầm bỏ.
     */
    @Override
    @Transactional
    public ImportQuestionsResponse importQuestions(List<ImportQuestionDto> questions) {
        if (questions == null || questions.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "questions: cần ít nhất một câu");
        }
        Instant now = Instant.now(clock);
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        for (ImportQuestionDto dto : questions) {
            if (dto.options() == null || dto.options().isEmpty()) {
                skipped++;   // câu không có lựa chọn: bỏ qua, không lỗi (quiz.md)
                continue;
            }
            validateImport(dto);
            Question q = questionRepo.findByExternalId(dto.externalId()).orElseGet(Question::new);
            boolean isNew = q.getId() == null;
            q.setExternalId(dto.externalId());
            q.setPool(dto.pool());
            q.setStem(dto.stem());
            q.setOptions(support.toJson(dto.options()));
            q.setCorrectIndex(dto.correctIndex());
            q.setStandard(dto.standard());
            q.setConcept(dto.concept());
            // license nhập vào source khi source trống — không thêm cột (quiz.md).
            q.setSource(dto.source() != null && !dto.source().isBlank() ? dto.source() : dto.license());
            q.setChapter(dto.chapter());
            // v3: nhóm đặt cờ không xáo đáp án và nguồn kiểm chứng qua nhập kho (giáo viên không đặt được).
            // Không gửi (null) = giữ giá trị đang có: lô cũ không có hai trường này không được xoá chúng.
            if (dto.keepOptionOrder() != null) {
                q.setKeepOptionOrder(dto.keepOptionOrder());
            }
            if (dto.reference() != null) {
                q.setReference(support.toJson(dto.reference()));
            }
            if (isNew) {
                q.setCreatedAt(now);
                questionRepo.save(q);
                inserted++;
            } else {
                // retiredAt giữ nguyên: câu đã rút thì re-import kho không tự sống lại.
                questionRepo.save(q);
                updated++;
            }
        }
        return new ImportQuestionsResponse(inserted, updated, skipped);
    }

    private static void validateImport(ImportQuestionDto dto) {
        if (dto.externalId() == null || dto.externalId().isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "externalId: bắt buộc");
        }
        if (dto.stem() == null || dto.stem().isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "stem: bắt buộc");
        }
        if (dto.options().size() < 2) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "options: cần ít nhất 2 lựa chọn");
        }
        if (dto.correctIndex() < 0 || dto.correctIndex() >= dto.options().size()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "correctIndex: ngoài phạm vi lựa chọn");
        }
        if (dto.chapter() != null && (dto.chapter() < 1 || dto.chapter() > 8)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "chapter: chỉ 1..8 hoặc null");
        }
    }

    /**
     * Giáo viên chọn câu: kho chung (chapter null, không của giáo viên nào) chưa rút, có correctIndex (người lớn cần
     * thấy đáp án để chọn). Gói TEACHER còn hạn hoặc ADMIN — thiếu thì 3005.
     */
    @Override
    @Transactional(readOnly = true)
    public List<QuestionResponse> listQuestions(UUID callerId, String standard, String concept,
                                                String pool, String q) {
        boolean admin = accountRoleRepo.existsByAccount_IdAndRole(callerId, Role.ADMIN);
        if (!admin && !entitlementService.activePlans(callerId).contains(PlanKind.TEACHER)) {
            throw new AppException(ErrorCode.PLAN_REQUIRED);
        }
        List<QuestionResponse> out = new ArrayList<>();
        for (Question question : questionRepo.findByRetiredAtIsNullAndChapterIsNullAndOwnerIsNull()) {
            if (standard != null && !standard.isBlank()
                    && !standard.equals(question.getStandard())) {
                continue;
            }
            if (concept != null && !concept.isBlank()
                    && !concept.equals(question.getConcept())) {
                continue;
            }
            if (pool != null && !pool.isBlank() && !pool.equals(question.getPool())) {
                continue;
            }
            if (q != null && !q.isBlank()
                    && (question.getStem() == null
                    || !question.getStem().toLowerCase(Locale.ROOT)
                    .contains(q.toLowerCase(Locale.ROOT)))) {
                continue;
            }
            out.add(support.questionResponse(question, null));
        }
        return out;
    }

    // ------------------------------------------------------------------ giáo viên: đề CLASS_TEST

    /**
     * Tạo nháp: lớp CLASS đang mở của người gọi + gói TEACHER; câu phải là kho chung hoặc câu của chính người gọi (8007).
     * mode tuỳ chọn: không gửi = đề kiểu v2 (một lần, không giờ, không xáo, nộp đường cũ) để FE đang chạy không gãy; có
     * mode thì bắt buộc dueAt. newQuestions lưu thành câu của giáo viên trong CÙNG giao dịch — lỗi bất kỳ thì không lưu gì.
     */
    @Override
    @Transactional
    public QuizResponse createQuiz(UUID callerId, CreateQuizRequest req) {
        LearnerGroup group = requireOwnedClassGroup(callerId, req == null ? null : req.groupId());
        requireTeacherPlan(callerId);
        String title = requireTitle(req.title());
        QuizMode mode = parseMode(req.mode(), null);   // null = đề kiểu v2
        requireDueAtFuture(req.dueAt());
        List<UUID> ids = collectQuestionIds(callerId, req.questionIds(), req.newQuestions());
        Quiz quiz = new Quiz();
        quiz.setOwner(group.getOwner());
        quiz.setGroup(group);
        quiz.setKind(QuizKind.CLASS_TEST);
        applySettings(quiz, title, ids, req.dueAt(), mode, req.drawCount(), req.durationMinutes());
        quiz = quizRepo.save(quiz);
        return QuizMapper.toResponse(quiz, ids.size(), 0);
    }

    /** Đề của một lớp, cả hai kiểu, mới nhất trước — nháp không có publishedAt nên xếp sau bản đã publish cùng giờ. */
    @Override
    @Transactional(readOnly = true)
    public List<QuizResponse> listQuizzes(UUID callerId, UUID groupId) {
        requireOwnedGroup(callerId, groupId);
        quizAttemptService.finalizeExpiredInGroup(groupId);   // giao dịch riêng: hàm này readOnly
        Map<UUID, Long> submitted = submittedCounts(groupId);
        List<Quiz> quizzes = new ArrayList<>(quizRepo.findByGroup_Id(groupId));
        quizzes.sort(Comparator.comparing((Quiz quiz) -> quiz.getPublishedAt() == null
                ? Instant.MAX : quiz.getPublishedAt()).reversed());
        List<QuizResponse> out = new ArrayList<>(quizzes.size());
        for (Quiz quiz : quizzes) {
            out.add(QuizMapper.toResponse(quiz, questionCount(quiz),
                    submitted.getOrDefault(quiz.getId(), 0L).intValue()));
        }
        return out;
    }

    /** Một đề kèm câu đầy đủ (giáo viên thấy correctIndex). */
    @Override
    @Transactional(readOnly = true)
    public QuizDetailResponse getQuiz(UUID callerId, UUID quizId) {
        Quiz quiz = requireOwnedQuiz(callerId, quizId);
        quizAttemptService.finalizeExpiredInGroup(quiz.getGroup().getId());
        List<UUID> ids = support.parseIds(quiz.getQuestionIds());
        Map<UUID, Question> questions = support.loadQuestions(ids);
        List<QuestionResponse> rows = new ArrayList<>();
        for (UUID id : ids) {
            Question question = questions.get(id);
            if (question != null) {
                rows.add(support.questionResponse(question, null));
            }
        }
        return new QuizDetailResponse(QuizMapper.toResponse(quiz, rows.size(),
                submittedCount(quiz.getId())), rows);
    }

    /** Sửa — chỉ khi chưa publish và không phải đề hậu trắc (8002). mode null = giữ chế độ cũ. */
    @Override
    @Transactional
    public QuizResponse updateQuiz(UUID callerId, UUID quizId, UpdateQuizRequest req) {
        Quiz quiz = requireOwnedQuiz(callerId, quizId);
        requireTeacherPlan(callerId);
        requireMutable(quiz);
        String title = requireTitle(req == null ? null : req.title());
        QuizMode mode = parseMode(req.mode(), quiz.getMode());
        requireDueAtFuture(req.dueAt());
        List<UUID> ids = collectQuestionIds(callerId, req.questionIds(), req.newQuestions());
        applySettings(quiz, title, ids, req.dueAt(), mode, req.drawCount(), req.durationMinutes());
        quiz = quizRepo.save(quiz);
        return QuizMapper.toResponse(quiz, ids.size(), submittedCount(quiz.getId()));
    }

    @Override
    @Transactional
    public void deleteQuiz(UUID callerId, UUID quizId) {
        Quiz quiz = requireOwnedQuiz(callerId, quizId);
        requireTeacherPlan(callerId);
        requireMutable(quiz);
        quizRepo.delete(quiz);
    }

    /** Publish: từ đó học sinh thấy, đề bất biến. Đề không có mode chạy như v2. */
    @Override
    @Transactional
    public QuizResponse publish(UUID callerId, UUID quizId) {
        Quiz quiz = requireOwnedQuiz(callerId, quizId);
        requireTeacherPlan(callerId);
        requireMutable(quiz);
        quiz.setPublishedAt(Instant.now(clock));
        quiz = quizRepo.save(quiz);
        return QuizMapper.toResponse(quiz, questionCount(quiz), 0);
    }

    // ------------------------------------------------------------------ kết quả

    /**
     * Kết quả cả lớp: ACTIVE trước, rồi đã trả / đã xoá nhưng vẫn nộp bài; summary trên bài đã nộp. Chốt lượt quá giờ
     * trước khi đọc (v3) nên không readOnly; cột lượt làm lấy từ lượt đầu của mỗi em (kết quả chính thức = lần nộp đầu).
     */
    @Override
    @Transactional
    public QuizResultsResponse getClassResults(UUID callerId, UUID quizId) {
        Quiz quiz = requireOwnedQuiz(callerId, quizId);
        quizAttemptService.finalizeExpired(quizId);
        Map<UUID, QuizResult> bySlot = new HashMap<>();
        List<QuizResult> results = quizResultRepo.findByQuiz_Id(quizId);
        for (QuizResult result : results) {
            bySlot.put(result.getSlot().getId(), result);
        }
        Map<UUID, QuizAttempt> firstAttempts = new HashMap<>();
        for (QuizAttempt attempt : attemptRepo.findByQuiz_IdAndAttemptNo(quizId, 1)) {
            firstAttempts.put(attempt.getSlot().getId(), attempt);
        }
        int totalQuestions = support.questionsPerLearner(quiz);
        List<QuizResultResponse> rows = new ArrayList<>();
        List<LearnerSlot> allSlots = new ArrayList<>(
                slotRepo.findByGroup_IdOrderByCreatedAtAsc(quiz.getGroup().getId()));
        // ACTIVE trước, trong nhóm theo createdAt; không ACTIVE mà đã nộp vẫn vào dòng cuối.
        allSlots.sort(Comparator.comparingInt((LearnerSlot slot) -> statusRank(slot.getStatus())));
        Set<UUID> emitted = new HashSet<>();
        for (LearnerSlot slot : allSlots) {
            QuizResult result = bySlot.get(slot.getId());
            if (slot.getStatus() == SlotStatus.ACTIVE || result != null) {
                rows.add(resultRow(slot, result, firstAttempts.get(slot.getId()), totalQuestions));
                emitted.add(slot.getId());
            }
        }
        // Phòng khi findByGroup_IdOrderByCreatedAtAsc vì lý do nào đó sót slot đã nộp — không được mất bài.
        for (QuizResult result : results) {
            if (!emitted.contains(result.getSlot().getId())) {
                rows.add(resultRow(result.getSlot(), result, firstAttempts.get(result.getSlot().getId()),
                        totalQuestions));
            }
        }
        Double average = results.isEmpty() ? null
                : results.stream().mapToInt(QuizResult::getScore).average().orElse(0);
        return new QuizResultsResponse(QuizMapper.toResponse(quiz, questionCount(quiz), results.size()),
                new QuizResultsSummaryResponse(rows.size(), results.size(), average), rows);
    }

    /** Từng câu của một em (giáo viên thấy đáp án); chưa nộp → 8003. v3: kèm đề riêng em đó nhận (paper). */
    @Override
    @Transactional
    public QuizSlotResultResponse getSlotResult(UUID callerId, UUID quizId, UUID slotId) {
        Quiz quiz = requireOwnedQuiz(callerId, quizId);
        quizAttemptService.finalizeExpired(quizId);
        QuizResult result = quizResultRepo.findByQuiz_IdAndSlot_Id(quizId, slotId)
                .orElseThrow(() -> new AppException(ErrorCode.QUIZ_NOT_SUBMITTED));
        Map<UUID, Question> questions = support.loadQuestions(support.parseIds(quiz.getQuestionIds()));
        List<QuizAnswerResponse> answers = new ArrayList<>();
        for (QuizAnswer answer : quizAnswerRepo.findByQuizResult_Id(result.getId())) {
            Question question = questions.getOrDefault(answer.getQuestion().getId(), answer.getQuestion());
            answers.add(new QuizAnswerResponse(question.getId(), question.getStem(), answer.getChosenIndex(),
                    question.getCorrectIndex(), answer.isCorrect()));
        }
        List<PaperItemResponse> paper = attemptRepo.findByQuiz_IdAndSlot_IdAndAttemptNo(quizId, slotId, 1)
                .map(attempt -> paperOf(attempt, questions))
                .orElse(null);
        return new QuizSlotResultResponse(slotId, result.getScore(), answers.size(),
                result.getSubmittedAt(), answers, paper);
    }

    /** Đề riêng của em theo thứ tự em thấy, cho người lớn: vị trí em chọn và vị trí đáp án đúng trong thứ tự đó. */
    private List<PaperItemResponse> paperOf(QuizAttempt attempt, Map<UUID, Question> questions) {
        List<PaperEntry> paper = support.parsePaper(attempt.getPaper());
        Map<Integer, Integer> chosen = support.parseAnswers(attempt.getAnswers());
        List<PaperItemResponse> out = new ArrayList<>(paper.size());
        for (int pos = 0; pos < paper.size(); pos++) {
            PaperEntry entry = paper.get(pos);
            Question question = questions.get(entry.q());
            List<String> original = support.parseOptions(question.getOptions());
            Integer pick = chosen.get(pos);
            int correctPos = entry.o().indexOf(question.getCorrectIndex());
            out.add(new PaperItemResponse(pos, question.getId(), question.getStem(),
                    entry.o().stream().map(original::get).toList(), pick,
                    pick != null && pick == correctPos, correctPos));
        }
        return out;
    }

    // ------------------------------------------------------------------ học sinh

    /**
     * Danh sách của em: đề giáo viên đã publish (chỉ lớp) + hậu trắc — ĐÂY LÀ CHỖ "TỰ MỞ": với mỗi chương
     * em đã xong mà nhóm chưa có đề hậu trắc và chương có câu thì tạo ngay lúc đọc. Em chỉ thấy
     * hậu trắc chương mình đã xong (dù đề tồn tại vì bạn xong trước); nhóm tắt cờ thì hậu trắc
     * biến khỏi pending, bài đã nộp vẫn trong completed. Gia đình (v3) chỉ có hậu trắc.
     */
    @Override
    @Transactional
    public LearnerQuizListResponse listForLearner(UUID slotId) {
        LearnerSlot slot = support.requireLearnerSlot(slotId);
        LearnerGroup group = slot.getGroup();
        quizAttemptService.finalizeExpiredInGroup(group.getId());
        boolean isClass = group.getContext() == LearningContext.CLASS;
        List<Integer> done = support.chaptersDone(slotId);
        support.ensurePostTests(group, done);   // 17/11 (Hưng): hậu trắc luôn có cho mọi nhóm, không còn bật / tắt
        List<QuizResult> myResults = quizResultRepo.findBySlot_IdOrderBySubmittedAtDesc(slotId);
        Set<UUID> myResultQuizIds = new HashSet<>();
        for (QuizResult result : myResults) {
            myResultQuizIds.add(result.getQuiz().getId());
        }
        Instant now = Instant.now(clock);
        Map<UUID, UUID> latestAttempt = new HashMap<>();   // quizId → lượt mới nhất (đọc theo attemptNo tăng dần)
        for (QuizAttempt attempt : attemptRepo.findBySlot_IdOrderByAttemptNoAsc(slotId)) {
            latestAttempt.put(attempt.getQuiz().getId(), attempt.getId());
        }

        List<LearnerQuizPendingResponse> pending = new ArrayList<>();
        for (Quiz quiz : quizRepo.findByGroup_Id(group.getId())) {
            if (quiz.getPublishedAt() == null || myResultQuizIds.contains(quiz.getId())) {
                continue;
            }
            if (QuizSupport.isPostTest(quiz)) {
                // Hậu trắc: em đã xong đúng chương đó (17/11: luôn bật, bỏ cờ nhóm).
                if (quiz.getChapter() == null
                        || !done.contains(quiz.getChapter())) {
                    continue;
                }
            } else if (!isClass) {
                continue;   // gia đình không có đề giáo viên
            }
            boolean overdue = quiz.getDueAt() != null && quiz.getDueAt().isBefore(now);
            pending.add(new LearnerQuizPendingResponse(quiz.getId(), QuizMapper.kindName(quiz),
                    quiz.getChapter(), quiz.getTitle(), support.questionsPerLearner(quiz), quiz.getDueAt(),
                    overdue, QuizMapper.modeName(quiz), quiz.getDurationSeconds(), latestAttempt.get(quiz.getId())));
        }
        pending.sort(Comparator
                .comparing((LearnerQuizPendingResponse p) -> p.kind())
                .thenComparing(p -> p.dueAt() == null ? Instant.MAX : p.dueAt())
                .thenComparing(p -> p.chapter() == null ? Integer.MAX_VALUE : p.chapter()));

        List<LearnerQuizCompletedResponse> completed = new ArrayList<>();
        for (QuizResult result : myResults) {
            Quiz quiz = result.getQuiz();
            completed.add(new LearnerQuizCompletedResponse(quiz.getId(), QuizMapper.kindName(quiz),
                    quiz.getChapter(), quiz.getTitle(), visibleScore(quiz, result, now),
                    quizAnswerRepo.findByQuizResult_Id(result.getId()).size(),
                    result.getSubmittedAt(), QuizMapper.modeName(quiz), latestAttempt.get(quiz.getId())));
        }
        return new LearnerQuizListResponse(pending, completed);
    }

    /**
     * Đề để làm: KHÔNG có correctIndex. Đáp án không bao giờ xuống trẻ. Đề có mode (v3) trả questions rỗng: câu chỉ xuống
     * qua lượt làm (đã xáo, đã rút) — trả đủ M câu theo thứ tự gốc ở đây là lộ cả bộ đề Kiểm tra.
     */
    @Override
    @Transactional(readOnly = true)
    public LearnerQuizResponse getForLearner(UUID slotId, UUID quizId) {
        LearnerSlot slot = support.requireLearnerSlot(slotId);
        Quiz quiz = support.requireLearnerQuiz(slot, quizId);
        List<UUID> ids = quiz.getMode() == null ? support.parseIds(quiz.getQuestionIds()) : List.of();
        Map<UUID, Question> questions = support.loadQuestions(ids);
        List<LearnerQuestionResponse> rows = new ArrayList<>();
        for (UUID id : ids) {
            Question question = questions.get(id);
            if (question != null) {
                rows.add(new LearnerQuestionResponse(question.getId(), question.getStem(),
                        support.parseOptions(question.getOptions())));
            }
        }
        Instant now = Instant.now(clock);
        LearnerQuizCompletedResponse submission = quizResultRepo
                .findByQuiz_IdAndSlot_Id(quizId, slotId)
                .map(result -> new LearnerQuizCompletedResponse(quiz.getId(),
                        QuizMapper.kindName(quiz), quiz.getChapter(), quiz.getTitle(),
                        visibleScore(quiz, result, now), support.questionsPerLearner(quiz), result.getSubmittedAt(),
                        QuizMapper.modeName(quiz), latestAttemptId(quizId, slotId)))
                .orElse(null);
        return new LearnerQuizResponse(quiz.getId(), QuizMapper.kindName(quiz), quiz.getChapter(),
                quiz.getTitle(), quiz.getDueAt(), rows, submission);
    }

    /**
     * Nộp một lần, máy chủ chấm. lockById dòng slot như Play: hai lần bấm nộp cùng lúc thành lần
     * thứ hai 8005 chứ không phải 500. Câu không có trong answers = bỏ trống = sai. Đề có mode (v3) → 8010: phải làm
     * qua lượt làm, không để đường cũ vòng qua xáo đề / đồng hồ.
     */
    @Override
    @Transactional
    public SubmitQuizResponse submitQuiz(UUID slotId, UUID quizId, SubmitQuizRequest req) {
        LearnerSlot slot = support.lockLearnerSlot(slotId);
        Quiz quiz = support.requireLearnerQuiz(slot, quizId);
        if (quiz.getMode() != null
                || (QuizSupport.isPostTest(quiz) && attemptRepo.existsByQuiz_IdAndSlot_Id(quizId, slotId))) {
            // hậu trắc em đã mở lượt làm (đề đã xáo) thì đi tiếp đường đó, không nộp chỉ số gốc qua đường cũ
            throw new AppException(ErrorCode.QUIZ_ATTEMPT_REQUIRED);
        }
        Instant now = Instant.now(clock);
        if (quiz.getDueAt() != null && quiz.getDueAt().isBefore(now)) {
            throw new AppException(ErrorCode.QUIZ_DUE_PASSED);
        }
        if (quizResultRepo.findByQuiz_IdAndSlot_Id(quizId, slotId).isPresent()) {
            throw new AppException(ErrorCode.QUIZ_ALREADY_SUBMITTED);
        }

        List<UUID> ids = support.parseIds(quiz.getQuestionIds());
        Map<UUID, Question> questions = support.loadQuestions(ids);
        Map<UUID, Integer> chosen = validateAnswers(req, ids, questions);

        List<GradedAnswer> graded = new ArrayList<>(ids.size());
        List<AnswerReviewResponse> review = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Question question = questions.get(id);
            Integer picked = chosen.get(id);
            boolean correct = picked != null && picked.equals(question.getCorrectIndex());
            graded.add(new GradedAnswer(question, picked, correct));
            review.add(new AnswerReviewResponse(id, picked, correct));
        }
        QuizResult result = support.writeOfficialResult(quiz, slot, now, graded);
        return new SubmitQuizResponse(quizId, result.getScore(), ids.size(), result.getSubmittedAt(), review);
    }

    // ------------------------------------------------------------------ kiểm chung

    /** Giáo viên: đề phải có (8001) và của người gọi (3004). */
    private Quiz requireOwnedQuiz(UUID callerId, UUID quizId) {
        Quiz quiz = quizRepo.findById(quizId)
                .orElseThrow(() -> new AppException(ErrorCode.QUIZ_NOT_FOUND));
        if (callerId == null || quiz.getOwner() == null
                || !callerId.equals(quiz.getOwner().getId())) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return quiz;
    }

    private void requireMutable(Quiz quiz) {
        if (QuizSupport.isPostTest(quiz) || quiz.getPublishedAt() != null) {
            throw new AppException(ErrorCode.QUIZ_IMMUTABLE);
        }
    }

    private LearnerGroup requireOwnedClassGroup(UUID callerId, UUID groupId) {
        LearnerGroup group = requireOwnedGroup(callerId, groupId);
        if (group.getContext() != LearningContext.CLASS || group.getClosedAt() != null) {
            throw new AppException(ErrorCode.GROUP_NOT_CLASS_OR_CLOSED);
        }
        return group;
    }

    private LearnerGroup requireOwnedGroup(UUID callerId, UUID groupId) {
        if (groupId == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "groupId: bắt buộc");
        }
        LearnerGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có nhóm " + groupId));
        if (callerId == null || group.getOwner() == null
                || !callerId.equals(group.getOwner().getId())) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return group;
    }

    private void requireTeacherPlan(UUID callerId) {
        if (!entitlementService.activePlans(callerId).contains(PlanKind.TEACHER)) {
            throw new AppException(ErrorCode.PLAN_REQUIRED);
        }
    }

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "title: bắt buộc");
        }
        if (title.trim().length() > QuizRules.MAX_TITLE_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "title: tối đa " + QuizRules.MAX_TITLE_LENGTH + " ký tự");
        }
        return title.trim();
    }

    /** v3: không gửi mode thì giữ fallback (tạo: null = đề kiểu v2; sửa: chế độ đang có). */
    private static QuizMode parseMode(String mode, QuizMode fallback) {
        if (mode == null || mode.isBlank()) {
            return fallback;
        }
        try {
            return QuizMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "mode: chỉ PRACTICE hoặc TEST");
        }
    }

    /**
     * questionIds (kho chung / câu của tôi) + newQuestions (lưu thành câu của tôi, cùng giao dịch); câu mới trùng câu đã
     * chọn thì không lặp. Tổng 1..100 câu (1001); questionIds trùng / lạ / đã rút / hậu trắc / của người khác → 8007.
     */
    private List<UUID> collectQuestionIds(UUID callerId, List<UUID> questionIds, List<NewQuestionDto> newQuestions) {
        List<UUID> given = questionIds == null ? List.of() : questionIds;
        if (given.size() > QuizRules.MAX_QUESTIONS) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "questionIds: tối đa " + QuizRules.MAX_QUESTIONS + " câu");
        }
        if (new HashSet<>(given).size() != given.size()) {
            // Trùng câu là lỗi của NGƯỜI RA ĐỀ (quiz.md ghi 8007) — 8006 là lỗi học sinh nộp bài.
            throw new AppException(ErrorCode.QUESTION_INVALID, "questionIds: có câu trùng");
        }
        requireQuestionsAllowed(callerId, given);
        Set<UUID> ids = new LinkedHashSet<>(given);
        if (newQuestions != null && !newQuestions.isEmpty()) {
            for (SavedQuestionResponse saved : teacherQuestionService.saveMine(callerId, newQuestions)) {
                ids.add(saved.id());
            }
        }
        if (ids.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "questionIds: cần ít nhất một câu");
        }
        if (ids.size() > QuizRules.MAX_QUESTIONS) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "đề: tối đa " + QuizRules.MAX_QUESTIONS + " câu");
        }
        return new ArrayList<>(ids);
    }

    /** Câu được vào đề giáo viên: kho chung (không chủ, không chương) hoặc câu của chính người gọi, chưa rút (8007). */
    private void requireQuestionsAllowed(UUID callerId, List<UUID> ids) {
        Map<UUID, Question> questions = support.loadQuestions(ids);
        for (UUID id : ids) {
            Question question = questions.get(id);
            boolean allowed = question != null && question.getRetiredAt() == null && question.getChapter() == null
                    && (question.getOwner() == null || question.getOwner().getId().equals(callerId));
            if (!allowed) {
                throw new AppException(ErrorCode.QUESTION_INVALID);
            }
        }
    }

    /**
     * Ghi các trường đề + cấu hình chế độ. Có mode thì dueAt bắt buộc (1001); không mode = luật v2. TEST: drawCount 1..số câu (null = hết), durationMinutes 1..180 (null = 1 phút ×
     * số câu mỗi em). PRACTICE: bỏ qua hai trường đó.
     */
    private void applySettings(Quiz quiz, String title, List<UUID> ids, Instant dueAt, QuizMode mode,
                               Integer drawCount, Integer durationMinutes) {
        if (mode != null && dueAt == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "dueAt: bắt buộc với đề có chế độ (PRACTICE / TEST)");
        }
        quiz.setTitle(title);
        quiz.setQuestionIds(support.toJson(ids));
        quiz.setDueAt(dueAt);
        quiz.setMode(mode);
        if (mode != QuizMode.TEST) {
            quiz.setDrawCount(null);
            quiz.setDurationSeconds(null);
            return;
        }
        if (drawCount != null && (drawCount < 1 || drawCount > ids.size())) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "drawCount: 1.." + ids.size());
        }
        if (durationMinutes != null && (durationMinutes < QuizRules.MIN_DURATION_MINUTES
                || durationMinutes > QuizRules.MAX_DURATION_MINUTES)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "durationMinutes: "
                    + QuizRules.MIN_DURATION_MINUTES + ".." + QuizRules.MAX_DURATION_MINUTES);
        }
        int perLearner = drawCount == null ? ids.size() : drawCount;
        quiz.setDrawCount(drawCount);
        quiz.setDurationSeconds(durationMinutes == null
                ? QuizRules.DEFAULT_SECONDS_PER_QUESTION * perLearner
                : (int) Duration.ofMinutes(durationMinutes).toSeconds());
    }

    private void requireDueAtFuture(Instant dueAt) {
        if (dueAt != null && !dueAt.isAfter(Instant.now(clock))) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "dueAt: phải sau thời điểm hiện tại");
        }
    }

    /** answers không được quá số câu, câu lạ, trùng, sai lựa chọn (8006); câu thiếu = bỏ trống. */
    private Map<UUID, Integer> validateAnswers(SubmitQuizRequest req, List<UUID> ids,
                                               Map<UUID, Question> questions) {
        Map<UUID, Integer> out = new HashMap<>();
        List<AnswerDto> answers = req == null ? null : req.answers();
        if (answers == null) {
            return out;   // không nộp gì = bỏ trống mọi câu = mọi câu sai
        }
        if (answers.size() > ids.size()) {
            throw new AppException(ErrorCode.QUIZ_ANSWER_MISMATCH);
        }
        for (AnswerDto answer : answers) {
            if (answer == null || answer.questionId() == null
                    || !questions.containsKey(answer.questionId())) {
                throw new AppException(ErrorCode.QUIZ_ANSWER_MISMATCH);
            }
            if (out.put(answer.questionId(), answer.chosenIndex()) != null) {
                throw new AppException(ErrorCode.QUIZ_ANSWER_MISMATCH);   // trùng câu
            }
            int options = support.parseOptions(questions.get(answer.questionId()).getOptions()).size();
            if (answer.chosenIndex() < 0 || answer.chosenIndex() >= options) {
                throw new AppException(ErrorCode.QUIZ_ANSWER_MISMATCH);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ tiện ích

    /** Đề Kiểm tra: trước hạn nộp em chỉ biết đã nộp, không thấy điểm (v3) — em nộp trước không mách được em nộp sau. */
    private static Integer visibleScore(Quiz quiz, QuizResult result, Instant now) {
        boolean hidden = quiz.getMode() == QuizMode.TEST && quiz.getDueAt() != null && now.isBefore(quiz.getDueAt());
        return hidden ? null : result.getScore();
    }

    private int submittedCount(UUID quizId) {
        return quizResultRepo.findByQuiz_Id(quizId).size();
    }

    private Map<UUID, Long> submittedCounts(UUID groupId) {
        Map<UUID, Long> out = new HashMap<>();
        for (Object[] row : quizResultRepo.countSubmittedByQuizInGroup(groupId)) {
            out.put((UUID) row[0], (Long) row[1]);
        }
        return out;
    }

    private UUID latestAttemptId(UUID quizId, UUID slotId) {
        List<QuizAttempt> attempts = attemptRepo.findByQuiz_IdAndSlot_IdOrderByAttemptNoAsc(quizId, slotId);
        return attempts.isEmpty() ? null : attempts.get(attempts.size() - 1).getId();
    }

    private int questionCount(Quiz quiz) {
        return support.parseIds(quiz.getQuestionIds()).size();
    }

    private static int statusRank(SlotStatus status) {
        if (status == SlotStatus.ACTIVE) {
            return 0;
        }
        return status == SlotStatus.ARCHIVED ? 1 : 2;
    }

    /** Một dòng kết quả lớp; cột v3 lấy từ lượt đầu của em (null với đề cũ / em chưa mở). */
    private static QuizResultResponse resultRow(LearnerSlot slot, QuizResult result, QuizAttempt attempt,
                                                int totalQuestions) {
        boolean wiped = slot.getStatus() == SlotStatus.WIPED;
        String state = result != null ? "SUBMITTED" : attempt != null ? "IN_PROGRESS" : "NOT_OPENED";
        Integer took = attempt == null || attempt.getSubmittedAt() == null ? null
                : (int) Duration.between(attempt.getStartedAt(), attempt.getSubmittedAt()).toSeconds();
        return new QuizResultResponse(slot.getId(), wiped ? null : slot.getCode(),
                wiped ? null : slot.getDisplayName(), slot.getStatus().name(), result != null,
                result == null ? null : result.getScore(), totalQuestions,
                result == null ? null : result.getSubmittedAt(),
                attempt == null ? null : attempt.getId().toString().substring(0, QuizRules.ATTEMPT_CODE_LENGTH),
                attempt == null ? null : attempt.getStartedAt(), took,
                attempt == null ? null : attempt.getLeaveCount(),
                attempt == null ? null : attempt.isAutoSubmitted(), state);
    }
}
