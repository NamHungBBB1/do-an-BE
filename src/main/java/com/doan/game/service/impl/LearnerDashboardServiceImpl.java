package com.doan.game.service.impl;

import com.doan.game.DTO.response.LearnerDashboardResponse;
import com.doan.game.DTO.response.LearnerQuizCompletedResponse;
import com.doan.game.DTO.response.LearnerQuizListResponse;
import com.doan.game.DTO.response.LearnerQuizPendingResponse;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.entity.RunState;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.SlotStatus;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.ChoiceEventRepository;
import com.doan.game.repository.GameReleaseRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.MiniGameResultRepository;
import com.doan.game.repository.ReleaseChapterRepository;
import com.doan.game.repository.RunStateRepository;
import com.doan.game.service.LearnerDashboardService;
import com.doan.game.service.QuizService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ruột dashboard người học. Đợt 1 (08/10) chỉ khối learner; đợt 2 (12/10, cùng PR lưu tiến độ)
 * thêm progress lấy từ RunState và recentActivities CHAPTER_DONE — cùng bảng "Theo đợt" contract,
 * hình dạng response giữ nguyên. Đợt 3 (quiz.md v2, 16/10): khối quizzes = đúng dữ liệu của
 * GET /api/learner/quizzes (hình cũ {id, title, dueAt} / {id, title, score, submittedAt}) và
 * recentActivities trộn QUIZ_SUBMITTED; achievements vẫn chờ sau.
 */
@Service
@RequiredArgsConstructor
public class LearnerDashboardServiceImpl implements LearnerDashboardService {

    private static final int MAX_ACTIVITIES = 10;

    private final LearnerSlotRepository slotRepo;
    private final GameReleaseRepository releaseRepo;
    private final ReleaseChapterRepository releaseChapterRepo;
    private final RunStateRepository runStateRepo;
    private final ChoiceEventRepository choiceEventRepo;
    private final MiniGameResultRepository miniGameResultRepo;
    private final QuizService quizService;
    private final ObjectMapper objectMapper;

    /**
     * Không readOnly: khối quizzes gọi listForLearner — đọc DANH SÁCH chính là chỗ BE tự tạo đề
     * hậu trắc cho chương đã xong (quiz.md v2), nên dashboard cũng là điểm ghi.
     */
    @Override
    @Transactional
    public LearnerDashboardResponse getDashboard(UUID slotId) {
        LearnerSlot slot = slotRepo.findById(slotId)
                .orElseThrow(() -> new AppException(ErrorCode.SLOT_NOT_FOUND));
        // Cùng luật loginSlot (08/10): WIPED / không có → 3007, ARCHIVED → 3009. Token SLOT vẫn
        // sống sau khi slot bị trả / bị xoá (RevocationAwareJwtDecoder bỏ qua typ=SLOT), nên chặn
        // ở đây chứ không phải ở cửa Security.
        if (slot.getStatus() == SlotStatus.WIPED) {
            throw new AppException(ErrorCode.SLOT_NOT_FOUND);
        }
        if (slot.getStatus() == SlotStatus.ARCHIVED) {
            throw new AppException(ErrorCode.SLOT_ARCHIVED);
        }

        LearnerGroup group = slot.getGroup();
        String groupContext = group == null || group.getContext() == null ? null : group.getContext().name();
        LearnerDashboardResponse.Learner learner = new LearnerDashboardResponse.Learner(
                slot.getId(), slot.getDisplayName(), slot.getBadge(), groupContext,
                group == null ? null : group.getName());

        // totalChapters = số chương của bản phát hành hiện tại (có từ 09/10); chưa phát hành → null.
        Integer totalChapters = releaseRepo.findFirstByIsCurrentTrue()
                .map(r -> releaseChapterRepo.findByRelease_IdOrderByChapter_ChapterNumberAsc(r.getId()).size())
                .orElse(null);

        // Đợt 2: tiến độ lấy từ RunState (token SLOT, chưa chơi → mặc định như đợt 1).
        RunState run = runStateRepo.findBySlot_Id(slotId).orElse(null);
        List<Integer> done = run == null ? List.of() : parseChapters(run.getChaptersDone());
        Integer continueChapter = (totalChapters != null && done.size() >= totalChapters)
                ? null
                : done.size() + 1;
        LearnerDashboardResponse.Progress progress = new LearnerDashboardResponse.Progress(
                totalChapters, done.size(), List.copyOf(done), continueChapter,
                run == null ? null : run.getUpdatedAt());

        // Đợt 2: chương đã xong trong lần chơi hiện tại, mới nhất trước, tối đa 10.
        List<LearnerDashboardResponse.RecentActivity> activities =
                new ArrayList<>(run == null ? List.of() : chapterActivities(run, done));

        // Đợt 3: quiz chỉ cho CLASS; FAMILY nhận null (Hưng chốt 08/10) — FE ẩn hẳn khối quiz.
        // CLASS: đúng dữ liệu của GET /api/learner/quizzes, hai mảng hình cũ giữ nguyên.
        LearnerDashboardResponse.Quizzes quizzes = null;
        if (group != null && group.getContext() == LearningContext.CLASS) {
            LearnerQuizListResponse quizList = quizService.listForLearner(slotId);
            List<LearnerDashboardResponse.PendingQuiz> pending = new ArrayList<>();
            for (LearnerQuizPendingResponse p : quizList.pending()) {
                pending.add(new LearnerDashboardResponse.PendingQuiz(p.id(), p.title(), p.dueAt()));
            }
            List<LearnerDashboardResponse.CompletedQuiz> completed = new ArrayList<>();
            for (LearnerQuizCompletedResponse c : quizList.completed()) {
                completed.add(new LearnerDashboardResponse.CompletedQuiz(c.id(), c.title(),
                        c.score(), c.submittedAt()));
                activities.add(new LearnerDashboardResponse.RecentActivity("QUIZ_SUBMITTED",
                        "Nộp quiz " + c.title(), c.submittedAt()));
            }
            quizzes = new LearnerDashboardResponse.Quizzes(pending, completed);
            // Trộn hai nguồn, mới nhất trước, tối đa 10 (quiz.md v2 mục "Dashboard đợt 3").
            activities.sort(Comparator
                    .comparing(LearnerDashboardResponse.RecentActivity::at).reversed());
            if (activities.size() > MAX_ACTIVITIES) {
                activities = new ArrayList<>(activities.subList(0, MAX_ACTIVITIES));
            }
        }

        return new LearnerDashboardResponse(learner, progress, quizzes, List.of(), activities);
    }

    /** at = receivedAt muộn nhất của chương đó trong LẦN CHƠI HIỆN TẠI (cả choices lẫn mini-game). */
    private List<LearnerDashboardResponse.RecentActivity> chapterActivities(RunState run, List<Integer> done) {
        if (done.isEmpty()) {
            return List.of();
        }
        int attempt = run.getAttemptIndex() == null ? 1 : run.getAttemptIndex();
        Map<Integer, Instant> at = new HashMap<>();
        for (Object[] row : choiceEventRepo.maxReceivedAtByChapter(run.getSlot().getId(), attempt)) {
            at.merge((Integer) row[0], (Instant) row[1], (a, b) -> a.isAfter(b) ? a : b);
        }
        for (Object[] row : miniGameResultRepo.maxReceivedAtByChapter(run.getSlot().getId(), attempt)) {
            at.merge((Integer) row[0], (Instant) row[1], (a, b) -> a.isAfter(b) ? a : b);
        }
        List<LearnerDashboardResponse.RecentActivity> out = new ArrayList<>();
        for (int i = done.size() - 1; i >= 0 && out.size() < MAX_ACTIVITIES; i--) {
            Instant when = at.get(done.get(i));
            if (when != null) {
                out.add(new LearnerDashboardResponse.RecentActivity(
                        "CHAPTER_DONE", "Xong chương " + done.get(i), when));
            }
        }
        return out;
    }

    /** chaptersDone lưu dạng mảng JSON text; hỏng / null → mảng rỗng (chưa chơi gì). */
    private List<Integer> parseChapters(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            List<Integer> out = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(n -> out.add(n.asInt()));
            }
            return out;
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "chaptersDone hong trong DB");
        }
    }

}
