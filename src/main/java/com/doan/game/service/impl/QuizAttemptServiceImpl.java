package com.doan.game.service.impl;

import com.doan.game.DTO.request.SaveAnswerRequest;
import com.doan.game.DTO.request.SubmitAttemptRequest;
import com.doan.game.DTO.response.AttemptAnswerReviewResponse;
import com.doan.game.DTO.response.AttemptQuestionResponse;
import com.doan.game.DTO.response.AttemptResponse;
import com.doan.game.DTO.response.AttemptReviewResponse;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.entity.Question;
import com.doan.game.entity.Quiz;
import com.doan.game.entity.QuizAttempt;
import com.doan.game.enums.Mode;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.mapper.QuizMapper;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.QuizAttemptRepository;
import com.doan.game.repository.QuizRepository;
import com.doan.game.repository.QuizResultRepository;
import com.doan.game.service.QuizAttemptService;
import com.doan.game.service.impl.QuizSupport.GradedAnswer;
import com.doan.game.service.impl.QuizSupport.PaperEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Lượt làm quiz v3 (thiết kế mục 4). Đề nào đi đường này: đề giáo viên có mode (PRACTICE / TEST) và hậu trắc; đề cũ
 * mode NULL vẫn nộp đường cũ.
 *
 * - Xáo: TEST xáo câu + đáp án + rút drawCount; PRACTICE và hậu trắc xáo câu + đáp án, làm hết. Câu keepOptionOrder giữ
 *   thứ tự đáp án. Lưu chính bản đã xáo (paper), không lưu seed.
 * - Đồng hồ: TEST deadline = min(mở + durationSeconds, dueAt); PRACTICE = dueAt; hậu trắc không giờ. Chốt lười: quá
 *   deadline + app.quiz.grace-seconds thì chấm phần đã lưu, autoSubmitted = true, nộp lúc deadline.
 * - Kết quả chính thức: lần nộp đầu của (đề, em) ghi QuizResult + QuizAnswer (chỉ số gốc). Luyện tập làm lại không đổi.
 *
 * Mọi đường của em khoá dòng slot trước (lockById) rồi mới đọc lượt, nên hai lần bấm cùng lúc đi tuần tự. Các đường có
 * thể vừa chốt lượt vừa báo lỗi (8011, 8012) chạy noRollbackFor = AppException: lượt đã chốt phải được ghi lại.
 */
@Service
@RequiredArgsConstructor
public class QuizAttemptServiceImpl implements QuizAttemptService {

    private final QuizAttemptRepository attemptRepo;
    private final QuizRepository quizRepo;
    private final QuizResultRepository quizResultRepo;
    private final LearnerSlotRepository slotRepo;
    private final QuizSupport support;
    private final Random quizRandom;
    private final Clock clock;

    /** Trễ cho mạng sau deadline (vận hành chỉnh được, mặc định 30 giây). */
    @Value("${app.quiz.grace-seconds:30}")
    private long graceSeconds;

    /** Mở đề: còn lượt mở thì trả lại đúng đề đó; chưa có thì tạo lượt kế (TEST đã làm → 8012, hậu trắc đã nộp → 8005). */
    @Override
    @Transactional(noRollbackFor = AppException.class)
    public AttemptResponse open(UUID slotId, UUID quizId) {
        LearnerSlot slot = support.lockLearnerSlot(slotId);
        Quiz quiz = support.requireLearnerQuiz(slot, quizId);
        if (!QuizSupport.isPostTest(quiz) && quiz.getMode() == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "đề cũ không có chế độ — nộp qua POST /api/learner/quizzes/{id}/submit");
        }
        Instant now = Instant.now(clock);
        Map<UUID, Question> questions = support.loadQuestions(support.parseIds(quiz.getQuestionIds()));
        List<QuizAttempt> mine = attemptRepo.findByQuiz_IdAndSlot_IdOrderByAttemptNoAsc(quizId, slotId);
        for (QuizAttempt attempt : mine) {
            if (attempt.getSubmittedAt() == null) {
                if (!finalizeIfExpired(attempt, questions, now)) {
                    return toAttemptResponse(attempt, quiz, questions, now);
                }
            }
        }
        if (quiz.getMode() == Mode.TEST && !mine.isEmpty()) {
            throw new AppException(ErrorCode.TEST_SINGLE_ATTEMPT);
        }
        if (QuizSupport.isPostTest(quiz)
                && (!mine.isEmpty() || quizResultRepo.findByQuiz_IdAndSlot_Id(quizId, slotId).isPresent())) {
            throw new AppException(ErrorCode.QUIZ_ALREADY_SUBMITTED);
        }
        if (quiz.getDueAt() != null && !now.isBefore(quiz.getDueAt())) {
            throw new AppException(ErrorCode.QUIZ_DUE_PASSED);
        }
        List<Question> ordered = support.parseIds(quiz.getQuestionIds()).stream()
                .map(questions::get).filter(java.util.Objects::nonNull).toList();
        Integer draw = quiz.getMode() == Mode.TEST ? quiz.getDrawCount() : null;
        List<PaperEntry> paper = buildPaper(ordered, draw, quizRandom,
                q -> support.parseOptions(q.getOptions()).size());

        QuizAttempt attempt = new QuizAttempt();
        attempt.setQuiz(quiz);
        attempt.setSlot(slot);
        attempt.setAttemptNo(mine.size() + 1);
        attempt.setPaper(support.toJson(paper));
        attempt.setAnswers(support.toJson(Map.of()));
        attempt.setStartedAt(now);
        attempt.setDeadlineAt(deadline(quiz, now, paper.size()));
        attempt.setLeaveCount(0);
        attempt = attemptRepo.save(attempt);
        return toAttemptResponse(attempt, quiz, questions, now);
    }

    /** Tự lưu một vị trí; lượt đã nộp hoặc quá deadline + trễ → chốt (nếu chưa) rồi 8011. */
    @Override
    @Transactional(noRollbackFor = AppException.class)
    public void saveAnswer(UUID slotId, UUID attemptId, int pos, SaveAnswerRequest req) {
        LearnerSlot slot = support.lockLearnerSlot(slotId);
        QuizAttempt attempt = requireOwnAttempt(slot, attemptId);
        Instant now = Instant.now(clock);
        if (attempt.getSubmittedAt() != null
                || finalizeIfExpired(attempt, questionsOf(attempt), now)) {
            throw new AppException(ErrorCode.ATTEMPT_CLOSED);
        }
        List<PaperEntry> paper = support.parsePaper(attempt.getPaper());
        if (pos < 0 || pos >= paper.size()) {
            throw new AppException(ErrorCode.QUIZ_ANSWER_MISMATCH, "pos ngoài đề");
        }
        Map<Integer, Integer> answers = support.parseAnswers(attempt.getAnswers());
        Integer choice = req == null ? null : req.choice();
        if (choice == null) {
            answers.remove(pos);   // bỏ chọn
        } else if (choice < 0 || choice >= paper.get(pos).o().size()) {
            throw new AppException(ErrorCode.QUIZ_ANSWER_MISMATCH, "choice ngoài lựa chọn");
        } else {
            answers.put(pos, choice);
        }
        attempt.setAnswers(support.toJson(answers));
        attempt.setLeaveCount(maxLeave(attempt.getLeaveCount(), req == null ? null : req.leaveCount()));
        attemptRepo.save(attempt);
    }

    /** Nộp: chấm theo paper, trống = sai. Đã nộp (kể cả tự nộp vì hết giờ) → trả lại kết quả cũ, không lỗi. */
    @Override
    @Transactional
    public AttemptReviewResponse submit(UUID slotId, UUID attemptId, SubmitAttemptRequest req) {
        LearnerSlot slot = support.lockLearnerSlot(slotId);
        QuizAttempt attempt = requireOwnAttempt(slot, attemptId);
        Instant now = Instant.now(clock);
        Map<UUID, Question> questions = questionsOf(attempt);
        if (attempt.getSubmittedAt() == null && !finalizeIfExpired(attempt, questions, now)) {
            attempt.setLeaveCount(maxLeave(attempt.getLeaveCount(), req == null ? null : req.leaveCount()));
            grade(attempt, questions, now, false);
        }
        return review(attempt, questions, now);
    }

    /** Xem lại lượt của mình (chốt nếu đã quá giờ). Đề Kiểm tra trước hạn nộp: chỉ đã nộp / số câu đã làm. */
    @Override
    @Transactional
    public AttemptReviewResponse get(UUID slotId, UUID attemptId) {
        LearnerSlot slot = support.lockLearnerSlot(slotId);
        QuizAttempt attempt = requireOwnAttempt(slot, attemptId);
        Instant now = Instant.now(clock);
        Map<UUID, Question> questions = questionsOf(attempt);
        finalizeIfExpired(attempt, questions, now);
        return review(attempt, questions, now);
    }

    /**
     * Chốt mọi lượt quá giờ của một đề: lấy id slot bằng một truy vấn lọc, khoá các slot đó (một câu, thứ tự id cố định),
     * rồi mới nạp lượt — nạp trước khi khoá là đọc bản cũ trong lúc em đang bấm nộp.
     */
    @Override
    @Transactional
    public void finalizeExpired(UUID quizId) {
        Instant now = Instant.now(clock);
        Instant cutoff = now.minusSeconds(graceSeconds);
        List<UUID> slotIds = attemptRepo.findSlotIdsWithExpiredOpen(quizId, cutoff);
        if (slotIds.isEmpty()) {
            return;
        }
        slotRepo.lockAllById(slotIds);
        Quiz quiz = quizRepo.findById(quizId).orElseThrow(() -> new AppException(ErrorCode.QUIZ_NOT_FOUND));
        Map<UUID, Question> questions = support.loadQuestions(support.parseIds(quiz.getQuestionIds()));
        Set<UUID> withResult = new HashSet<>();
        quizResultRepo.findByQuiz_Id(quizId).forEach(r -> withResult.add(r.getSlot().getId()));
        for (QuizAttempt attempt : attemptRepo.findByQuiz_IdAndSubmittedAtIsNullAndDeadlineAtBefore(quizId, cutoff)) {
            boolean first = withResult.add(attempt.getSlot().getId());
            grade(attempt, questions, attempt.getDeadlineAt(), true, first);
        }
    }

    // ------------------------------------------------------------------ xáo đề

    /**
     * Đề riêng của một em: xáo thứ tự câu, rút drawCount câu đầu (null = hết), xáo đáp án từng câu trừ câu keepOptionOrder.
     * Thuần (không đụng DB) để test được với Random có seed.
     */
    static List<PaperEntry> buildPaper(List<Question> questions, Integer drawCount, Random random,
                                       ToIntFunction<Question> optionCount) {
        List<Question> order = new ArrayList<>(questions);
        Collections.shuffle(order, random);
        if (drawCount != null && drawCount < order.size()) {
            order = order.subList(0, drawCount);
        }
        List<PaperEntry> paper = new ArrayList<>(order.size());
        for (Question question : order) {
            List<Integer> options = IntStream.range(0, optionCount.applyAsInt(question)).boxed()
                    .collect(Collectors.toCollection(ArrayList::new));
            if (!question.isKeepOptionOrder()) {
                Collections.shuffle(options, random);
            }
            paper.add(new PaperEntry(question.getId(), List.copyOf(options)));
        }
        return paper;
    }

    // ------------------------------------------------------------------ chấm, chốt

    private boolean finalizeIfExpired(QuizAttempt attempt, Map<UUID, Question> questions, Instant now) {
        if (attempt.getSubmittedAt() != null || attempt.getDeadlineAt() == null
                || !now.isAfter(attempt.getDeadlineAt().plusSeconds(graceSeconds))) {
            return false;
        }
        grade(attempt, questions, attempt.getDeadlineAt(), true);
        return true;
    }

    private void grade(QuizAttempt attempt, Map<UUID, Question> questions, Instant submittedAt, boolean auto) {
        boolean first = quizResultRepo.findByQuiz_IdAndSlot_Id(attempt.getQuiz().getId(), attempt.getSlot().getId())
                .isEmpty();
        grade(attempt, questions, submittedAt, auto, first);
    }

    /** Chấm theo paper (vị trí em thấy → chỉ số gốc), trống = sai; lần nộp đầu của (đề, em) ghi kết quả chính thức. */
    private void grade(QuizAttempt attempt, Map<UUID, Question> questions, Instant submittedAt, boolean auto,
                       boolean writeOfficial) {
        List<PaperEntry> paper = support.parsePaper(attempt.getPaper());
        Map<Integer, Integer> answers = support.parseAnswers(attempt.getAnswers());
        List<GradedAnswer> graded = new ArrayList<>(paper.size());
        for (int pos = 0; pos < paper.size(); pos++) {
            Question question = questions.get(paper.get(pos).q());
            Integer original = originalIndex(paper.get(pos), answers.get(pos));
            graded.add(new GradedAnswer(question, original,
                    original != null && original.equals(question.getCorrectIndex())));
        }
        attempt.setScore((int) graded.stream().filter(GradedAnswer::correct).count());
        attempt.setSubmittedAt(submittedAt);
        attempt.setAutoSubmitted(auto);
        attemptRepo.save(attempt);
        if (writeOfficial) {
            support.writeOfficialResult(attempt.getQuiz(), attempt.getSlot(), submittedAt, graded);
        }
    }

    private static Integer originalIndex(PaperEntry entry, Integer shownChoice) {
        return shownChoice == null ? null : entry.o().get(shownChoice);
    }

    // ------------------------------------------------------------------ đọc

    private QuizAttempt requireOwnAttempt(LearnerSlot slot, UUID attemptId) {
        return attemptRepo.findById(attemptId)
                .filter(a -> a.getSlot().getId().equals(slot.getId()))
                .orElseThrow(() -> new AppException(ErrorCode.ATTEMPT_NOT_FOUND));
    }

    private Map<UUID, Question> questionsOf(QuizAttempt attempt) {
        return support.loadQuestions(support.parsePaper(attempt.getPaper()).stream().map(PaperEntry::q).toList());
    }

    private static Instant deadline(Quiz quiz, Instant now, int questionCount) {
        if (quiz.getMode() != Mode.TEST) {
            return quiz.getDueAt();   // PRACTICE: hạn nộp; hậu trắc: null
        }
        int seconds = quiz.getDurationSeconds() != null ? quiz.getDurationSeconds()
                : QuizRules.DEFAULT_SECONDS_PER_QUESTION * questionCount;
        Instant end = now.plusSeconds(seconds);
        return quiz.getDueAt() != null && quiz.getDueAt().isBefore(end) ? quiz.getDueAt() : end;
    }

    private static Integer maxLeave(Integer current, Integer reported) {
        int base = current == null ? 0 : current;
        return reported == null ? base : Math.max(base, reported);
    }

    /** Đề cho trẻ: đáp án theo thứ tự em thấy, không id câu, không correctIndex. */
    private AttemptResponse toAttemptResponse(QuizAttempt attempt, Quiz quiz, Map<UUID, Question> questions,
                                              Instant now) {
        List<PaperEntry> paper = support.parsePaper(attempt.getPaper());
        List<AttemptQuestionResponse> rows = new ArrayList<>(paper.size());
        for (int pos = 0; pos < paper.size(); pos++) {
            Question question = questions.get(paper.get(pos).q());
            List<String> original = support.parseOptions(question.getOptions());
            rows.add(new AttemptQuestionResponse(pos, question.getStem(),
                    paper.get(pos).o().stream().map(original::get).toList()));
        }
        return new AttemptResponse(attempt.getId(), attempt.getAttemptNo(), QuizMapper.modeName(quiz),
                QuizMapper.kindName(quiz), rows, support.parseAnswers(attempt.getAnswers()), attempt.getDeadlineAt(),
                now, attempt.getSubmittedAt());
    }

    /**
     * Xem lại cho trẻ: đúng / sai từng vị trí, không đáp án đúng. Chưa nộp, hoặc đề Kiểm tra trước hạn nộp (để em nộp trước
     * không mách được em nộp sau): chỉ submittedAt / số câu đã làm / tổng.
     */
    private AttemptReviewResponse review(QuizAttempt attempt, Map<UUID, Question> questions, Instant now) {
        Quiz quiz = attempt.getQuiz();
        List<PaperEntry> paper = support.parsePaper(attempt.getPaper());
        Map<Integer, Integer> answers = support.parseAnswers(attempt.getAnswers());
        boolean reveal = attempt.getSubmittedAt() != null
                && (quiz.getMode() != Mode.TEST || quiz.getDueAt() == null || !now.isBefore(quiz.getDueAt()));
        List<AttemptAnswerReviewResponse> rows = null;
        if (reveal) {
            rows = new ArrayList<>(paper.size());
            for (int pos = 0; pos < paper.size(); pos++) {
                Integer chosen = answers.get(pos);
                Integer original = originalIndex(paper.get(pos), chosen);
                rows.add(new AttemptAnswerReviewResponse(pos, chosen,
                        original != null && original.equals(questions.get(paper.get(pos).q()).getCorrectIndex())));
            }
        }
        return new AttemptReviewResponse(attempt.getId(), attempt.getAttemptNo(), QuizMapper.modeName(quiz),
                QuizMapper.kindName(quiz), attempt.getSubmittedAt(), attempt.isAutoSubmitted(), answers.size(),
                paper.size(), reveal ? attempt.getScore() : null, rows);
    }
}
