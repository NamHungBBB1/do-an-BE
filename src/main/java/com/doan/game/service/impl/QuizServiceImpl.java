package com.doan.game.service.impl;

import com.doan.game.DTO.request.AnswerDto;
import com.doan.game.DTO.request.CreateQuizRequest;
import com.doan.game.DTO.request.ImportQuestionDto;
import com.doan.game.DTO.request.SubmitQuizRequest;
import com.doan.game.DTO.request.UpdateQuizRequest;
import com.doan.game.DTO.response.AnswerReviewResponse;
import com.doan.game.DTO.response.ImportQuestionsResponse;
import com.doan.game.DTO.response.LearnerQuestionResponse;
import com.doan.game.DTO.response.LearnerQuizCompletedResponse;
import com.doan.game.DTO.response.LearnerQuizListResponse;
import com.doan.game.DTO.response.LearnerQuizPendingResponse;
import com.doan.game.DTO.response.LearnerQuizResponse;
import com.doan.game.DTO.response.QuestionResponse;
import com.doan.game.DTO.response.QuizAnswerResponse;
import com.doan.game.DTO.response.QuizDetailResponse;
import com.doan.game.DTO.response.QuizResponse;
import com.doan.game.DTO.response.QuizResultResponse;
import com.doan.game.DTO.response.QuizResultsResponse;
import com.doan.game.DTO.response.QuizResultsSummaryResponse;
import com.doan.game.DTO.response.QuizSlotResultResponse;
import com.doan.game.DTO.response.SubmitQuizResponse;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.entity.Question;
import com.doan.game.entity.Quiz;
import com.doan.game.entity.QuizAnswer;
import com.doan.game.entity.QuizResult;
import com.doan.game.entity.RunState;
import com.doan.game.enums.LearningContext;
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
import com.doan.game.repository.QuizRepository;
import com.doan.game.repository.QuizResultRepository;
import com.doan.game.repository.RunStateRepository;
import com.doan.game.service.EntitlementService;
import com.doan.game.service.QuizService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ruột quiz hai kiểu (quiz.md v2, Hưng chốt 16/10).
 *
 * Đề giáo viên (CLASS_TEST): chọn câu kho chung (chapter NULL), publish rồi bất biến.
 * Hậu trắc (POST_TEST): lớp bật cờ postTestEnabledAt thì MỖI lần đọc danh sách của em, nếu em đã
 * xong chương N mà lớp chưa có đề hậu trắc chương N và chương đó có câu → BE tự tạo (chụp
 * questionIds lúc tạo, published ngay, không hạn). Một điểm tạo duy nhất, không chạm PlayService;
 * bật cờ muộn hay thêm chương sau đều tự đúng ở lần gọi kế tiếp.
 *
 * Máy chủ chấm, mỗi em nộp một lần (UNIQUE quiz + slot, khoá dòng slot bằng lockById như Play),
 * đáp án KHÔNG BAO GIỜ xuống trẻ: DTO học sinh không có correctIndex, sau khi nộp chỉ trả đúng /
 * sai từng câu. Câu không nộp = bỏ trống = sai (OECD/INFE, khung chấm 3.2).
 */
@Service
@RequiredArgsConstructor
public class QuizServiceImpl implements QuizService {

    private final QuestionRepository questionRepo;
    private final QuizRepository quizRepo;
    private final QuizResultRepository quizResultRepo;
    private final QuizAnswerRepository quizAnswerRepo;
    private final LearnerGroupRepository groupRepo;
    private final LearnerSlotRepository slotRepo;
    private final RunStateRepository runStateRepo;
    private final AccountRoleRepository accountRoleRepo;
    private final EntitlementService entitlementService;
    private final ObjectMapper objectMapper;
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
            q.setOptions(toJson(dto.options()));
            q.setCorrectIndex(dto.correctIndex());
            q.setStandard(dto.standard());
            q.setConcept(dto.concept());
            // license nhập vào source khi source trống — không thêm cột (quiz.md).
            q.setSource(dto.source() != null && !dto.source().isBlank() ? dto.source() : dto.license());
            q.setChapter(dto.chapter());
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
     * Giáo viên chọn câu: kho chung (chapter null) chưa rút, có correctIndex (người lớn cần thấy
     * đáp án để chọn). Gói TEACHER còn hạn hoặc ADMIN — thiếu thì 3005.
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
        for (Question question : questionRepo.findByRetiredAtIsNullAndChapterIsNull()) {
            List<String> options = parseOptions(question.getOptions());
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
                    || !question.getStem().toLowerCase(java.util.Locale.ROOT)
                    .contains(q.toLowerCase(java.util.Locale.ROOT)))) {
                continue;
            }
            out.add(QuizMapper.toResponse(question, options));
        }
        return out;
    }

    // ------------------------------------------------------------------ giáo viên: đề CLASS_TEST

    /** Tạo nháp: lớp CLASS đang mở của người gọi + gói TEACHER; câu phải có trong kho chung (8007). */
    @Override
    @Transactional
    public QuizResponse createQuiz(UUID callerId, CreateQuizRequest req) {
        LearnerGroup group = requireOwnedClassGroup(callerId, req == null ? null : req.groupId());
        requireTeacherPlan(callerId);
        String title = requireTitle(req == null ? null : req.title());
        List<UUID> ids = requireQuestionIds(req == null ? null : req.questionIds());
        requireQuestionsInPool(ids);
        requireDueAtFuture(req == null ? null : req.dueAt());
        Quiz quiz = new Quiz();
        quiz.setOwner(group.getOwner());
        quiz.setGroup(group);
        quiz.setTitle(title);
        quiz.setQuestionIds(toJson(ids));
        quiz.setDueAt(req.dueAt());
        quiz.setKind(QuizKind.CLASS_TEST);
        quiz = quizRepo.save(quiz);
        return QuizMapper.toResponse(quiz, ids.size(), 0);
    }

    /** Đề của một lớp, cả hai kiểu, mới nhất trước — nháp không có publishedAt nên xếp sau bản đã publish cùng giờ. */
    @Override
    @Transactional(readOnly = true)
    public List<QuizResponse> listQuizzes(UUID callerId, UUID groupId) {
        requireOwnedGroup(callerId, groupId);
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
        Map<UUID, Question> questions = loadQuestions(parseIds(quiz.getQuestionIds()));
        List<QuestionResponse> rows = new ArrayList<>();
        for (UUID id : parseIds(quiz.getQuestionIds())) {
            Question question = questions.get(id);
            if (question != null) {
                rows.add(QuizMapper.toResponse(question, parseOptions(question.getOptions())));
            }
        }
        return new QuizDetailResponse(QuizMapper.toResponse(quiz, rows.size(),
                submittedCount(quiz.getId())), rows);
    }

    /** Sửa — chỉ khi chưa publish và không phải đề hậu trắc (8002). Publish rồi là bất biến. */
    @Override
    @Transactional
    public QuizResponse updateQuiz(UUID callerId, UUID quizId, UpdateQuizRequest req) {
        Quiz quiz = requireOwnedQuiz(callerId, quizId);
        requireTeacherPlan(callerId);
        requireMutable(quiz);
        String title = requireTitle(req == null ? null : req.title());
        List<UUID> ids = requireQuestionIds(req == null ? null : req.questionIds());
        requireQuestionsInPool(ids);
        requireDueAtFuture(req == null ? null : req.dueAt());
        quiz.setTitle(title);
        quiz.setQuestionIds(toJson(ids));
        quiz.setDueAt(req.dueAt());
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

    /** Publish: từ đó học sinh thấy, đề bất biến. */
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

    /** Kết quả cả lớp: ACTIVE trước, rồi đã trả / đã xoá nhưng vẫn nộp bài; summary trên bài đã nộp. */
    @Override
    @Transactional(readOnly = true)
    public QuizResultsResponse getClassResults(UUID callerId, UUID quizId) {
        Quiz quiz = requireOwnedQuiz(callerId, quizId);
        Map<UUID, QuizResult> bySlot = new HashMap<>();
        List<QuizResult> results = quizResultRepo.findByQuiz_Id(quizId);
        for (QuizResult result : results) {
            bySlot.put(result.getSlot().getId(), result);
        }
        int totalQuestions = questionCount(quiz);
        List<QuizResultResponse> rows = new ArrayList<>();
        List<LearnerSlot> allSlots = new ArrayList<>(
                slotRepo.findByGroup_IdOrderByCreatedAtAsc(quiz.getGroup().getId()));
        // ACTIVE trước, trong nhóm theo createdAt; không ACTIVE mà đã nộp vẫn vào dòng cuối.
        allSlots.sort(Comparator.comparingInt((LearnerSlot slot) -> statusRank(slot.getStatus())));
        Set<UUID> emitted = new HashSet<>();
        for (LearnerSlot slot : allSlots) {
            QuizResult result = bySlot.get(slot.getId());
            if (slot.getStatus() == SlotStatus.ACTIVE || result != null) {
                rows.add(resultRow(slot, result, totalQuestions));
                emitted.add(slot.getId());
            }
        }
        // Phòng khi findByGroup_IdOrderByCreatedAtAsc vì lý do nào đó sót slot đã nộp — không được mất bài.
        for (QuizResult result : results) {
            if (!emitted.contains(result.getSlot().getId())) {
                rows.add(resultRow(result.getSlot(), result, totalQuestions));
            }
        }
        Double average = results.isEmpty() ? null
                : results.stream().mapToInt(QuizResult::getScore).average().orElse(0);
        return new QuizResultsResponse(QuizMapper.toResponse(quiz, totalQuestions, results.size()),
                new QuizResultsSummaryResponse(rows.size(), results.size(), average), rows);
    }

    /** Từng câu của một em (giáo viên thấy đáp án); chưa nộp → 8003. */
    @Override
    @Transactional(readOnly = true)
    public QuizSlotResultResponse getSlotResult(UUID callerId, UUID quizId, UUID slotId) {
        Quiz quiz = requireOwnedQuiz(callerId, quizId);
        QuizResult result = quizResultRepo.findByQuiz_IdAndSlot_Id(quizId, slotId)
                .orElseThrow(() -> new AppException(ErrorCode.QUIZ_NOT_SUBMITTED));
        List<QuizAnswerResponse> answers = new ArrayList<>();
        for (QuizAnswer answer : quizAnswerRepo.findByQuizResult_Id(result.getId())) {
            answers.add(new QuizAnswerResponse(answer.getQuestion().getId(),
                    answer.getQuestion().getStem(), answer.getChosenIndex(),
                    answer.getQuestion().getCorrectIndex(), answer.isCorrect()));
        }
        return new QuizSlotResultResponse(slotId, result.getScore(), answers.size(),
                result.getSubmittedAt(), answers);
    }

    // ------------------------------------------------------------------ cờ hậu trắc

    // ------------------------------------------------------------------ học sinh

    /**
     * Danh sách của em: đề giáo viên đã publish + hậu trắc — ĐÂY LÀ CHỖ "TỰ MỞ": với mỗi chương
     * em đã xong mà lớp chưa có đề hậu trắc và chương có câu thì tạo ngay lúc đọc. Em chỉ thấy
     * hậu trắc chương mình đã xong (dù đề tồn tại vì bạn xong trước); lớp tắt cờ thì hậu trắc
     * biến khỏi pending, bài đã nộp vẫn trong completed.
     */
    @Override
    @Transactional
    public LearnerQuizListResponse listForLearner(UUID slotId) {
        LearnerSlot slot = requireLearnerSlot(slotId);
        LearnerGroup group = slot.getGroup();
        if (group.getContext() != LearningContext.CLASS) {
            return new LearnerQuizListResponse(List.of(), List.of());
        }
        List<Integer> done = chaptersDone(slotId);
        if (group.getPostTestEnabledAt() != null) {
            ensurePostTests(group, done);
        }
        Set<UUID> myResultQuizIds = new HashSet<>();
        for (QuizResult result : quizResultRepo.findBySlot_IdOrderBySubmittedAtDesc(slotId)) {
            myResultQuizIds.add(result.getQuiz().getId());
        }
        Instant now = Instant.now(clock);
        Map<UUID, Long> submitted = submittedCounts(group.getId());

        List<LearnerQuizPendingResponse> pending = new ArrayList<>();
        List<Quiz> quizzes = quizRepo.findByGroup_Id(group.getId());
        for (Quiz quiz : quizzes) {
            if (quiz.getPublishedAt() == null || myResultQuizIds.contains(quiz.getId())) {
                continue;
            }
            if (isPostTest(quiz)) {
                // Hậu trắc: chỉ khi lớp đang bật và em đã xong đúng chương đó.
                if (group.getPostTestEnabledAt() == null || quiz.getChapter() == null
                        || !done.contains(quiz.getChapter())) {
                    continue;
                }
            }
            boolean overdue = quiz.getDueAt() != null && quiz.getDueAt().isBefore(now);
            pending.add(new LearnerQuizPendingResponse(quiz.getId(), QuizMapper.kindName(quiz),
                    quiz.getChapter(), quiz.getTitle(), questionCount(quiz), quiz.getDueAt(),
                    overdue));
        }
        pending.sort(Comparator
                .comparing((LearnerQuizPendingResponse p) -> p.kind())
                .thenComparing(p -> p.dueAt() == null ? Instant.MAX : p.dueAt())
                .thenComparing(p -> p.chapter() == null ? Integer.MAX_VALUE : p.chapter()));

        List<LearnerQuizCompletedResponse> completed = new ArrayList<>();
        for (QuizResult result : quizResultRepo.findBySlot_IdOrderBySubmittedAtDesc(slotId)) {
            Quiz quiz = result.getQuiz();
            completed.add(new LearnerQuizCompletedResponse(quiz.getId(), QuizMapper.kindName(quiz),
                    quiz.getChapter(), quiz.getTitle(), result.getScore(),
                    quizAnswerRepo.findByQuizResult_Id(result.getId()).size(),
                    result.getSubmittedAt()));
        }
        return new LearnerQuizListResponse(pending, completed);
    }

    /** Đề để làm: KHÔNG có correctIndex. Đáp án không bao giờ xuống trẻ. */
    @Override
    @Transactional(readOnly = true)
    public LearnerQuizResponse getForLearner(UUID slotId, UUID quizId) {
        LearnerSlot slot = requireLearnerSlot(slotId);
        Quiz quiz = requireLearnerQuiz(slot, quizId);
        List<UUID> ids = parseIds(quiz.getQuestionIds());
        Map<UUID, Question> questions = loadQuestions(ids);
        List<LearnerQuestionResponse> rows = new ArrayList<>();
        for (UUID id : ids) {
            Question question = questions.get(id);
            if (question != null) {
                rows.add(new LearnerQuestionResponse(question.getId(), question.getStem(),
                        parseOptions(question.getOptions())));
            }
        }
        LearnerQuizCompletedResponse submission = quizResultRepo
                .findByQuiz_IdAndSlot_Id(quizId, slotId)
                .map(result -> new LearnerQuizCompletedResponse(quiz.getId(),
                        QuizMapper.kindName(quiz), quiz.getChapter(), quiz.getTitle(),
                        result.getScore(), rows.size(), result.getSubmittedAt()))
                .orElse(null);
        return new LearnerQuizResponse(quiz.getId(), QuizMapper.kindName(quiz), quiz.getChapter(),
                quiz.getTitle(), quiz.getDueAt(), rows, submission);
    }

    /**
     * Nộp một lần, máy chủ chấm. lockById dòng slot như Play: hai lần bấm nộp cùng lúc thành lần
     * thứ hai 8005 chứ không phải 500. Câu không có trong answers = bỏ trống = sai.
     */
    @Override
    @Transactional
    public SubmitQuizResponse submitQuiz(UUID slotId, UUID quizId, SubmitQuizRequest req) {
        LearnerSlot slot = slotRepo.lockById(slotId)
                .orElseThrow(() -> new AppException(ErrorCode.SLOT_NOT_FOUND));
        checkLearnerSlot(slot);
        Quiz quiz = requireLearnerQuiz(slot, quizId);
        Instant now = Instant.now(clock);
        if (quiz.getDueAt() != null && quiz.getDueAt().isBefore(now)) {
            throw new AppException(ErrorCode.QUIZ_DUE_PASSED);
        }
        if (quizResultRepo.findByQuiz_IdAndSlot_Id(quizId, slotId).isPresent()) {
            throw new AppException(ErrorCode.QUIZ_ALREADY_SUBMITTED);
        }

        List<UUID> ids = parseIds(quiz.getQuestionIds());
        Map<UUID, Question> questions = loadQuestions(ids);
        Map<UUID, Integer> chosen = validateAnswers(req, ids, questions);

        int score = 0;
        List<AnswerReviewResponse> review = new ArrayList<>(ids.size());
        QuizResult result = new QuizResult();
        result.setQuiz(quiz);
        result.setSlot(slot);
        result.setSubmittedAt(now);
        // save trước vì QuizAnswer.trỏ vào id của bài; score điền sau khi đếm xong.
        result.setScore(0);
        result = quizResultRepo.save(result);
        List<QuizAnswer> answers = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Question question = questions.get(id);
            Integer picked = chosen.get(id);
            boolean correct = picked != null && picked.equals(question.getCorrectIndex());
            if (correct) {
                score++;
            }
            QuizAnswer answer = new QuizAnswer();
            answer.setQuizResult(result);
            answer.setQuestion(question);
            answer.setChosenIndex(picked);
            answer.setCorrect(correct);
            answers.add(answer);
            review.add(new AnswerReviewResponse(id, picked, correct));
        }
        quizAnswerRepo.saveAll(answers);
        result.setScore(score);
        result = quizResultRepo.save(result);
        return new SubmitQuizResponse(quizId, score, ids.size(), result.getSubmittedAt(), review);
    }

    // ------------------------------------------------------------------ hậu trắc tự tạo

    /**
     * Với mỗi chương em đã xong: chưa có đề hậu trắc && chương có ≥ 1 câu chưa rút → tạo. Chụp
     * questionIds (xếp theo externalId) lúc tạo, published ngay — từ đó bất biến. Khóa dòng nhóm
     * (lockById) để hai em xong chương cùng lúc không đụng UNIQUE; nếu vẫn bắt
     * DataIntegrityViolationException thì bỏ qua — lần đọc kế tiếp tự thấy đề của bạn.
     */
    private void ensurePostTests(LearnerGroup group, List<Integer> done) {
        if (done.isEmpty()) {
            return;
        }
        // lockById như closeGroup/openSlot: tuần tự hoá việc tạo đề hậu trắc theo lớp.
        group = groupRepo.lockById(group.getId()).orElse(group);
        for (Integer chapter : done) {
            if (quizRepo.findByGroup_IdAndKindAndChapter(group.getId(), QuizKind.POST_TEST, chapter)
                    .isPresent()) {
                continue;
            }
            List<Question> questions = questionRepo
                    .findByChapterAndRetiredAtIsNullOrderByExternalIdAsc(chapter);
            if (questions.isEmpty()) {
                continue;   // chương không có câu → không tạo gì, không lỗi
            }
            List<UUID> ids = questions.stream().map(Question::getId).toList();
            try {
                Quiz quiz = new Quiz();
                quiz.setOwner(group.getOwner());
                quiz.setGroup(group);
                quiz.setTitle("Hậu trắc chương " + chapter);
                quiz.setQuestionIds(toJson(ids));
                quiz.setKind(QuizKind.POST_TEST);
                quiz.setChapter(chapter);
                quiz.setPublishedAt(Instant.now(clock));
                quizRepo.saveAndFlush(quiz);
            } catch (DataIntegrityViolationException e) {
                // Bạn khác cùng xong chương: UNIQUE(group, kind, chapter) chặn; lần sau đọc lại.
            }
        }
    }

    // ------------------------------------------------------------------ kiểm chung

    /** Học sinh: slot phải có (3007), chưa WIPED (3007) và chưa ARCHIVED (3009) — cùng luật dashboard. */
    private LearnerSlot requireLearnerSlot(UUID slotId) {
        LearnerSlot slot = slotRepo.findById(slotId)
                .orElseThrow(() -> new AppException(ErrorCode.SLOT_NOT_FOUND));
        checkLearnerSlot(slot);
        return slot;
    }

    private static void checkLearnerSlot(LearnerSlot slot) {
        if (slot.getStatus() == SlotStatus.WIPED) {
            throw new AppException(ErrorCode.SLOT_NOT_FOUND);
        }
        if (slot.getStatus() == SlotStatus.ARCHIVED) {
            throw new AppException(ErrorCode.SLOT_ARCHIVED);
        }
    }

    /** Đề hợp lệ với em: của đúng lớp, đã publish; hậu trắc thêm điều kiện lớp bật cờ + chương đã xong. */
    private Quiz requireLearnerQuiz(LearnerSlot slot, UUID quizId) {
        Quiz quiz = quizRepo.findById(quizId)
                .orElseThrow(() -> new AppException(ErrorCode.QUIZ_NOT_FOUND));
        if (quiz.getPublishedAt() == null || quiz.getGroup() == null
                || !quiz.getGroup().getId().equals(slot.getGroup().getId())) {
            throw new AppException(ErrorCode.QUIZ_NOT_FOUND);
        }
        if (isPostTest(quiz)) {
            List<Integer> done = chaptersDone(slot.getId());
            if (slot.getGroup().getPostTestEnabledAt() == null || quiz.getChapter() == null
                    || !done.contains(quiz.getChapter())) {
                throw new AppException(ErrorCode.QUIZ_NOT_FOUND);
            }
        }
        return quiz;
    }

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
        if (isPostTest(quiz) || quiz.getPublishedAt() != null) {
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
        if (title.trim().length() > 120) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "title: tối đa 120 ký tự");
        }
        return title.trim();
    }

    private List<UUID> requireQuestionIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "questionIds: cần ít nhất một câu");
        }
        if (ids.size() > 100) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "questionIds: tối đa 100 câu");
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new AppException(ErrorCode.QUIZ_ANSWER_MISMATCH, "questionIds: có câu trùng");
        }
        return ids;
    }

    /** Mọi câu phải thật, chưa rút và là kho chung — câu hậu trắc không được cho vào đề giáo viên (8007). */
    private void requireQuestionsInPool(List<UUID> ids) {
        Map<UUID, Question> questions = loadQuestions(ids);
        for (UUID id : ids) {
            Question question = questions.get(id);
            if (question == null || question.getRetiredAt() != null
                    || question.getChapter() != null) {
                throw new AppException(ErrorCode.QUESTION_INVALID);
            }
        }
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
            int options = parseOptions(questions.get(answer.questionId()).getOptions()).size();
            if (answer.chosenIndex() < 0 || answer.chosenIndex() >= options) {
                throw new AppException(ErrorCode.QUIZ_ANSWER_MISMATCH);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ tiện ích

    private static boolean isPostTest(Quiz quiz) {
        return quiz.getKind() == QuizKind.POST_TEST;
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

    private int questionCount(Quiz quiz) {
        return parseIds(quiz.getQuestionIds()).size();
    }

    private Map<UUID, Question> loadQuestions(List<UUID> ids) {
        Map<UUID, Question> out = new LinkedHashMap<>();
        for (Question question : questionRepo.findAllById(ids)) {
            out.put(question.getId(), question);
        }
        return out;
    }

    /** chaptersDone của em (RunState, play.md); chưa chơi → rỗng. */
    private List<Integer> chaptersDone(UUID slotId) {
        RunState run = runStateRepo.findBySlot_Id(slotId).orElse(null);
        if (run == null || run.getChaptersDone() == null || run.getChaptersDone().isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(run.getChaptersDone());
            List<Integer> out = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(n -> out.add(n.asInt()));
            }
            return out;
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "chaptersDone hong trong DB");
        }
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

    private List<UUID> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<UUID>>() { });
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "questionIds hong trong DB");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "khong ghi duoc JSON");
        }
    }

    private static int statusRank(SlotStatus status) {
        if (status == SlotStatus.ACTIVE) {
            return 0;
        }
        return status == SlotStatus.ARCHIVED ? 1 : 2;
    }

    private static QuizResultResponse resultRow(LearnerSlot slot, QuizResult result,
                                                int totalQuestions) {
        boolean wiped = slot.getStatus() == SlotStatus.WIPED;
        return new QuizResultResponse(slot.getId(), wiped ? null : slot.getCode(),
                wiped ? null : slot.getDisplayName(), slot.getStatus().name(), result != null,
                result == null ? null : result.getScore(), totalQuestions,
                result == null ? null : result.getSubmittedAt());
    }
}
