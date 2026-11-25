package com.doan.game.service.impl;

import com.doan.game.DTO.response.GroupReportResponse;
import com.doan.game.DTO.response.SlotReportResponse;
import com.doan.game.configuration.ClockConfig;
import com.doan.game.entity.Achievement;
import com.doan.game.entity.AiJob;
import com.doan.game.entity.DailySummary;
import com.doan.game.enums.SummarySource;
import com.doan.game.enums.LearningContext;
import com.doan.game.repository.DailySummaryRepository;
import com.doan.game.entity.ChoiceEvent;
import com.doan.game.entity.GroupReport;
import com.doan.game.entity.GroupReportRow;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.entity.MiniGameResult;
import com.doan.game.entity.Quiz;
import com.doan.game.entity.QuizResult;
import com.doan.game.entity.ReleaseChapter;
import com.doan.game.entity.RunState;
import com.doan.game.enums.SlotStatus;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.AchievementRepository;
import com.doan.game.repository.ChoiceEventRepository;
import com.doan.game.repository.GameReleaseRepository;
import com.doan.game.repository.GroupReportRepository;
import com.doan.game.repository.GroupReportRowRepository;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.MiniGameResultRepository;
import com.doan.game.repository.QuestionRepository;
import com.doan.game.repository.QuizAnswerRepository;
import com.doan.game.repository.QuizRepository;
import com.doan.game.repository.QuizResultRepository;
import com.doan.game.repository.ReleaseChapterRepository;
import com.doan.game.repository.RunStateRepository;
import com.doan.game.service.QuizAttemptService;
import com.doan.game.service.ReportService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Ruột báo cáo người lớn (Hưng duyệt 12/10, build/api-docs/report.md).
 *
 * Số thì lấy sống từ RunState / ChoiceEvent / MiniGameResult / Achievement (Play đã ghi), trừ khi
 * nhóm ĐÃ ĐÓNG: lúc đó đọc GroupReport / GroupReportRow đóng băng đúng lúc closeGroup — không bao
 * giờ tính lại, vì tính lại là âm thầm đổi một báo cáo người lớn đã xem rồi (ERD). Danh tính
 * (code / displayName / status) LUÔN đọc sống từ LearnerSlot: số đóng băng, tên đọc sống, nên
 * xoá một em sau khi đóng vẫn ra "Đã xoá" mà số không mất.
 *
 * Đợt 1: learner / progress / indicators / achievements có dữ liệu; concepts / chapters rỗng,
 * dailySummary null — FE dựng giao diện một lần. Đợt 3 (quiz.md v2, 16/10): khối quizzes (slot) và
 * quizAssigned / quizSubmitted / averageScore (dòng + summary nhóm) lấy số THẬT từ QuizResult —
 * nhóm đóng băng vẫn đọc giá trị đóng băng, không tính lại.
 *
 * 19/11 (khung báo cáo AI mục 2.3, 2.4, 3.1, 13.1): chapters / concepts / finalReport có số thật, tính sống từ các bảng
 * sự kiện bất biến (ChoiceEvent, MiniGameResult, QuizResult, QuizAnswer) + JSON đóng băng của bản bé đã chơi. Slot
 * report vốn luôn tính sống (bản đóng băng nhóm chỉ giữ số của dòng nhóm), nên nhóm đã đóng vẫn ra đúng: các bảng
 * nguồn không bao giờ sửa sau khi ghi.
 */
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    /** Phiên bản công thức đóng băng — đổi công thức sau này thì đổi chuỗi, báo cáo cũ giữ phiên bản cũ. */
    static final String FORMULA_VERSION = "report-v1";

    private final LearnerSlotRepository slotRepo;
    private final LearnerGroupRepository groupRepo;
    private final GameReleaseRepository releaseRepo;
    private final ReleaseChapterRepository releaseChapterRepo;
    private final RunStateRepository runStateRepo;
    private final ChoiceEventRepository choiceEventRepo;
    private final MiniGameResultRepository miniGameResultRepo;
    private final AchievementRepository achievementRepo;
    private final GroupReportRepository groupReportRepo;
    private final GroupReportRowRepository groupReportRowRepo;
    private final QuizRepository quizRepo;
    private final QuizResultRepository quizResultRepo;
    private final QuizAnswerRepository quizAnswerRepo;
    private final QuestionRepository questionRepo;
    private final DailySummaryRepository summaryRepo;
    private final Personalizer personalizer;
    private final ObjectMapper objectMapper;
    private final QuizSupport support;
    private final QuizAttemptService quizAttemptService;
    private final Clock clock;
    /** Báo cáo lớp theo chương (25/11). ObjectProvider: ClassReportService cũng dùng chapterBlocksOf của lớp này. */
    private final org.springframework.beans.factory.ObjectProvider<ClassReportService> classReports;

    /**
     * figures JSON trong GroupReportRow — đúng "dòng không có code / displayName / status"
     * (report.md): ba trường danh tính đọc sống từ LearnerSlot, còn lại là con số đông cứng.
     * Record package-private để Jackson serialize / deserialize được, không lọt ra ngoài.
     */
    record RowFigures(List<Integer> chaptersDone, int completedChapters, int attemptIndex,
                      Instant lastActiveAt, SlotReportResponse.Indicators indicators,
                      int quizAssigned, int quizSubmitted, Double averageScore) {
    }

    // ------------------------------------------------------------------ slot report

    /**
     * Một học sinh, mọi thứ người lớn được xem. Thứ tự kiểm: slot 3007 → chủ nhóm 3004 →
     * chưa phát hành 1003 (hợp đồng: chỉ DB mới chưa có bản nào thì báo cáo không có gì để hiện).
     * ARCHIVED vẫn đủ số liệu; WIPED: code, displayName = null (FE hiện "Đã xoá"), số giữ nguyên.
     */
    @Override
    @Transactional(readOnly = true)
    public SlotReportResponse slotReport(UUID callerId, UUID slotId) {
        LearnerSlot slot = slotRepo.findById(slotId)
                .orElseThrow(() -> new AppException(ErrorCode.SLOT_NOT_FOUND));
        LearnerGroup group = slot.getGroup();
        requireOwner(callerId, group);
        // Quiz v3: chốt lượt quá giờ của nhóm trước khi đọc (giao dịch riêng — hàm này readOnly).
        quizAttemptService.finalizeExpiredInGroup(group.getId());
        Integer totalChapters = currentTotalChapters();
        if (totalChapters == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "chưa phát hành bản nội dung nào");
        }

        boolean wiped = slot.getStatus() == SlotStatus.WIPED;
        Instant lastActiveAt = lastActiveAt(slotId);
        RunState run = runStateRepo.findBySlot_Id(slotId).orElse(null);
        SlotReportResponse.Progress progress = progressOf(run, totalChapters);

        List<SlotReportResponse.AchievementRow> achievements = new ArrayList<>();
        for (Achievement a : achievementRepo.findBySlot_IdOrderByEarnedAtDesc(slotId)) {
            achievements.add(new SlotReportResponse.AchievementRow(a.getCode(), a.getEarnedAt()));
        }

        SlotReportResponse.Learner learner = new SlotReportResponse.Learner(
                slot.getId(),
                wiped ? null : slot.getCode(),
                wiped ? null : slot.getDisplayName(),
                slot.getBadge(),
                slot.getStatus().name(),
                new SlotReportResponse.GroupRef(group.getId(), group.getName(),
                        group.getContext() == null ? null : group.getContext().name(),
                        group.getClosedAt()),
                slot.getCreatedAt(), slot.getArchivedAt(), slot.getWipedAt(), lastActiveAt);

        // Đợt 3 (quiz.md v2): assigned = số đề đã publish của nhóm; results = bài của em, mới nhất
        // trước (chưa nộp thì không có dòng, FE hiện "chưa nộp"). 16/11 (v3, Hưng chốt): gia đình cũng
        // có khối này — chỉ chứa hậu trắc (gia đình không có đề giáo viên), không còn null.
        List<QuizResult> myResults = quizResultRepo.findBySlot_IdOrderBySubmittedAtDesc(slotId);
        List<SlotReportResponse.QuizResultRow> resultRows = new ArrayList<>();
        for (QuizResult r : myResults) {
            resultRows.add(new SlotReportResponse.QuizResultRow(r.getQuiz().getId(),
                    r.getQuiz().getTitle(), r.getScore(), support.questionsPerLearner(r.getQuiz()),
                    r.getSubmittedAt()));
        }
        SlotReportResponse.Quizzes quizzes = new SlotReportResponse.Quizzes(
                (int) quizRepo.countByGroup_IdAndPublishedAtIsNotNull(group.getId()),
                myResults.size(), resultRows);

        ChapterBlocks blocks = chapterBlocks(slotId, group.getId(), run, myResults);
        // Bài viết (19/11, khung báo cáo AI 13.3): dòng DailySummary của lần chơi hiện tại theo (kind, chương).
        List<DailySummary> saved = summaryRepo.findBySlotWithJob(slotId);
        Map<String, DailySummary> byKey = new HashMap<>();
        DailySummary latest = null;
        for (DailySummary d : saved) {
            if (d.getAttemptIndex() != null && d.getAttemptIndex() == blocks.attemptIndex()) {
                byKey.put(d.getKind() + "#" + d.getChapter(), d);
            }
            if (d.getCreatedAt() != null && (latest == null || d.getCreatedAt().isAfter(latest.getCreatedAt()))) {
                latest = d;
            }
        }
        List<SlotReportResponse.ChapterRow> chapters = new ArrayList<>(blocks.chapters().size());
        for (SlotReportResponse.ChapterRow r : blocks.chapters()) {
            chapters.add(new SlotReportResponse.ChapterRow(r.chapter(), r.attemptIndex(), r.finishedAt(), r.choices(),
                    r.miniGames(), r.postTest(), r.reportStatus(),
                    summaryOf(r.reportStatus(), byKey.get(ReportPackage.CHAPTER + "#" + r.chapter()),
                            () -> ReportPackage.chapter(blocks, r))));
        }
        String finalStatus = blocks.finalReport().status();
        SlotReportResponse.FinalReport finalReport = new SlotReportResponse.FinalReport(finalStatus,
                NONE.equals(finalStatus) ? null
                        : summaryOf(finalStatus, byKey.get(ReportPackage.FINAL + "#" + FINAL_CHAPTER),
                                () -> ReportPackage.finalPackage(blocks)));
        SlotReportResponse.DailySummaryRow daily = latest == null ? null : new SlotReportResponse.DailySummaryRow(
                LocalDate.ofInstant(latest.getCreatedAt(), ClockConfig.VN), latest.getSummary(),
                latest.getSource() == null ? null : latest.getSource().name(), latest.getTier(), latest.getProvider());
        return new SlotReportResponse(learner, progress, indicatorsOf(run), List.copyOf(achievements),
                blocks.concepts(), chapters, finalReport, quizzes, daily);
    }

    static final String WAITING_POST_TEST = "WAITING_POST_TEST";
    static final String GENERATING = "GENERATING";
    static final String READY = "READY";
    static final String FALLBACK = "FALLBACK";

    /**
     * Trạng thái bài viết (khung 13.3, Hưng 19/11): chưa nộp hậu trắc → WAITING_POST_TEST (không chữ). Có dòng: source LLM →
     * READY; việc AI còn sống (QUEUED / RUNNING / WAITING_RETRY / WAITING_AGENT) → GENERATING (chữ = bản theo luật); còn lại
     * (FAILED, không có việc) → FALLBACK. Chưa có dòng (chương xong trước PR này, hoặc lúc ghi lỗi) → FALLBACK với bản theo
     * luật tính ngay từ cùng số liệu, generatedAt null — lần gửi lô / nộp bài sau sẽ ghi dòng.
     */
    static SlotReportResponse.Summary summaryOf(String reportStatus, DailySummary row,
                                                java.util.function.Supplier<ReportPackage.Pkg> pkg) {
        if (PARTIAL.equals(reportStatus)) {
            return new SlotReportResponse.Summary(WAITING_POST_TEST, null, null, null, null, null, null);
        }
        if (row == null) {
            ReportPackage.Pkg live = pkg.get();
            return new SlotReportResponse.Summary(FALLBACK, ReportPackage.ruleText(live), "RULE", 0, null, null,
                    ReportSections.fromPackage(live));
        }
        AiJob job = row.getAiJob();
        boolean llm = row.getSource() == SummarySource.LLM;
        String status = llm ? READY
                : job != null && ALIVE_JOB.contains(job.getStatus()) ? GENERATING : FALLBACK;
        Instant at = llm && job != null && job.getDoneAt() != null ? job.getDoneAt() : row.getCreatedAt();
        return new SlotReportResponse.Summary(status, row.getSummary(),
                row.getSource() == null ? null : row.getSource().name(), row.getTier(), row.getProvider(), at,
                ReportSections.of(row, pkg));
    }

    private static final Set<String> ALIVE_JOB = Set.of(AiJobQueue.QUEUED, AiJobQueue.RUNNING,
            AiJobQueue.WAITING_RETRY, AiJobQueue.WAITING_AGENT);

    // ------------------------------------------------------------------ chương / khái niệm (19/11)

    /** Bản tổng kết chỉ có khi xong chương này (khung báo cáo AI mục 13.1: "xong CH08"). */
    static final int FINAL_CHAPTER = 8;
    /** BR-44: mã có dưới 3 câu → chưa đủ dữ liệu (một câu đoán làm đổi 33 điểm %). */
    static final int MIN_ITEMS_FOR_BAND = 3;
    static final String NONE = "NONE";
    static final String PENDING = "PENDING";
    static final String SUBMITTED = "SUBMITTED";
    static final String PARTIAL = "PARTIAL";
    static final String COMPLETE = "COMPLETE";

    /**
     * Khối chương của lần chơi hiện tại. Ngoài ba khối trả FE còn giữ phần dựng gói bài viết cần (19/11, ReportSummaryService):
     * JSON gốc (không cá nhân hoá) + bản đã chơi của từng chương, và đếm theo mã của TỪNG chương ([đúng game, tổng game,
     * đúng hậu trắc, tổng hậu trắc]).
     */
    record ChapterBlocks(List<SlotReportResponse.ChapterRow> chapters,
                         List<SlotReportResponse.ConceptRow> concepts,
                         SlotReportResponse.FinalReport finalReport,
                         int attemptIndex,
                         Map<Integer, JsonNode> chapterJson,
                         Map<Integer, String> chapterVersion,
                         Map<Integer, Map<String, int[]>> chapterConcepts) {
    }

    /** Khối chương của một em — cho ReportSummaryService dựng gói, cùng một hàm tính với báo cáo (không lệch số). */
    public ChapterBlocks chapterBlocksOf(UUID slotId) {
        LearnerSlot slot = slotRepo.findById(slotId)
                .orElseThrow(() -> new AppException(ErrorCode.SLOT_NOT_FOUND));
        RunState run = runStateRepo.findBySlot_Id(slotId).orElse(null);
        return chapterBlocks(slotId, slot.getGroup().getId(), run,
                quizResultRepo.findBySlot_IdOrderBySubmittedAtDesc(slotId));
    }

    /**
     * chapters[] + concepts[] + finalReport cho lần chơi hiện tại. Số truy vấn cố định, không theo cảnh / chương:
     * ChoiceEvent + MiniGameResult của lần chơi (2), JSON các bản đã chơi (1), đề của nhóm (1), kho hậu trắc (≤ 1),
     * câu hậu trắc đã nộp (≤ 1).
     */
    private ChapterBlocks chapterBlocks(UUID slotId, UUID groupId, RunState run, List<QuizResult> myResults) {
        Set<Integer> done = run == null ? Set.of() : new TreeSet<>(ChaptersDone.parse(run.getChaptersDone()));
        if (done.isEmpty()) {
            return new ChapterBlocks(List.of(), List.of(), new SlotReportResponse.FinalReport(NONE, null), 1,
                    Map.of(), Map.of(), Map.of());
        }
        int attempt = run.getAttemptIndex() == null ? 1 : run.getAttemptIndex();

        Map<Integer, List<ChoiceEvent>> choicesByChapter = new HashMap<>();
        Set<String> versions = new HashSet<>();
        for (ChoiceEvent e : choiceEventRepo.findBySlot_IdAndAttemptIndex(slotId, attempt)) {
            if (done.contains(e.getChapter())) {
                choicesByChapter.computeIfAbsent(e.getChapter(), k -> new ArrayList<>()).add(e);
                versions.add(e.getBuildVersion());
            }
        }
        Map<Integer, List<MiniGameResult>> minisByChapter = new HashMap<>();
        for (MiniGameResult m : miniGameResultRepo.findBySlot_IdAndAttemptIndex(slotId, attempt)) {
            if (done.contains(m.getChapter())) {
                minisByChapter.computeIfAbsent(m.getChapter(), k -> new ArrayList<>()).add(m);
                versions.add(m.getBuildVersion());
            }
        }
        versions.remove(null);
        // JSON đóng băng của đúng bản bé đã chơi (buildVersion), bản GỐC: token cá nhân hoá điền giá trị mặc định, không
        // ghép theo nhãn của bé (chưa có RunLabelSnapshot; nhãn hiện tại có thể khác lúc bé chơi).
        Map<String, JsonNode> played = new HashMap<>();
        if (!versions.isEmpty()) {
            for (ReleaseChapter rc : releaseChapterRepo.findPlayed(versions, done)) {
                played.put(playedKey(rc.getRelease().getVersion(), rc.getChapter().getChapterNumber()),
                        personalizer.compose(readTree(rc.getContent()), Map.of()));
            }
        }

        // Hậu trắc của nhóm theo chương; bài của bé theo đề (QuizResult = lần nộp đầu, UNIQUE quiz + slot).
        Map<Integer, Quiz> postTests = new HashMap<>();
        for (Quiz q : quizRepo.findByGroup_Id(groupId)) {
            if (QuizSupport.isPostTest(q) && q.getPublishedAt() != null && done.contains(q.getChapter())) {
                postTests.put(q.getChapter(), q);
            }
        }
        Map<UUID, QuizResult> resultByQuiz = new HashMap<>();
        for (QuizResult r : myResults) {
            resultByQuiz.put(r.getQuiz().getId(), r);
        }
        List<Integer> withoutQuiz = done.stream().filter(ch -> !postTests.containsKey(ch)).toList();
        Set<Integer> hasPool = new HashSet<>();
        if (!withoutQuiz.isEmpty()) {
            for (Object[] row : questionRepo.countLiveByChapter(withoutQuiz)) {
                hasPool.add(((Number) row[0]).intValue());
            }
        }

        Map<String, int[]> concepts = new TreeMap<>();   // mã → [đúng game, tổng game, đúng hậu trắc, tổng hậu trắc]
        Map<Integer, Map<String, int[]>> chapterConcepts = new HashMap<>();   // cùng đếm, theo từng chương
        Map<Integer, JsonNode> chapterJson = new HashMap<>();
        Map<Integer, String> chapterVersion = new HashMap<>();
        Map<UUID, Integer> chapterOfResult = new HashMap<>();
        List<UUID> submittedPostTests = new ArrayList<>();
        List<SlotReportResponse.ChapterRow> chapters = new ArrayList<>(done.size());
        for (int ch : done) {
            List<ChoiceEvent> events = choicesByChapter.getOrDefault(ch, List.of());
            List<MiniGameResult> minis = minisByChapter.getOrDefault(ch, List.of());
            Instant finishedAt = null;
            Map<String, int[]> mine = chapterConcepts.computeIfAbsent(ch, k -> new TreeMap<>());
            String version = !events.isEmpty() ? events.get(0).getBuildVersion()
                    : minis.isEmpty() ? null : minis.get(0).getBuildVersion();
            if (version != null) {
                chapterVersion.put(ch, version);
                JsonNode json = played.get(playedKey(version, ch));
                if (json != null) {
                    chapterJson.put(ch, json);
                }
            }

            List<ChoiceEvent> ordered = new ArrayList<>(events);
            ordered.sort(Comparator.comparingInt(e -> sceneIndex(played.get(playedKey(e.getBuildVersion(), ch)),
                    e.getSceneId())));
            List<SlotReportResponse.ChoiceRow> choices = new ArrayList<>(ordered.size());
            for (ChoiceEvent e : ordered) {
                finishedAt = later(finishedAt, e.getReceivedAt());
                JsonNode json = played.get(playedKey(e.getBuildVersion(), ch));
                JsonNode scene = json == null ? null : PlayServiceImpl.findScene(json.path("scenes"), e.getSceneId());
                JsonNode option = scene == null ? null : PlayServiceImpl.byId(scene.path("options"), e.getChoiceId());
                choices.add(new SlotReportResponse.ChoiceRow(e.getSceneId(), e.getChoiceId(),
                        option == null ? null : option.path("text").asText(option.path("label").asText(null)),
                        deliberate(option)));
            }

            int correct = 0;
            List<SlotReportResponse.WrongItem> wrong = new ArrayList<>();
            for (MiniGameResult m : minis) {
                finishedAt = later(finishedAt, m.getReceivedAt());
                JsonNode json = played.get(playedKey(m.getBuildVersion(), ch));
                JsonNode scene = json == null ? null : PlayServiceImpl.findGameScene(json.path("scenes"), m.getGameId());
                String concept = resolveConcept(scene, m);
                if (concept != null && !concept.isBlank()) {
                    for (int[] c : List.of(concepts.computeIfAbsent(concept, k -> new int[4]),
                            mine.computeIfAbsent(concept, k -> new int[4]))) {
                        c[1]++;
                        c[0] += m.isCorrect() ? 1 : 0;
                    }
                }
                if (m.isCorrect()) {
                    correct++;
                } else {
                    // Nhãn tra từ JSON của đúng bản bé đã chơi (m.buildVersion), không phải bản hiện hành.
                    MiniGameLabels.Labels l = MiniGameLabels.of(scene, m.getItemId(), m.getChosen());
                    wrong.add(new SlotReportResponse.WrongItem(m.getGameId(), m.getItemId(), concept, m.getChosen(),
                            l.label(), l.chosenLabel(), l.expected(), l.expectedLabel(), l.explanation()));
                }
            }
            wrong.sort(Comparator.comparing(SlotReportResponse.WrongItem::gameId)
                    .thenComparing(SlotReportResponse.WrongItem::itemId));

            SlotReportResponse.PostTest postTest;
            Quiz quiz = postTests.get(ch);
            QuizResult result = quiz == null ? null : resultByQuiz.get(quiz.getId());
            if (result != null) {
                submittedPostTests.add(result.getId());
                chapterOfResult.put(result.getId(), ch);
                postTest = new SlotReportResponse.PostTest(quiz.getId(), SUBMITTED,
                        result.getScore() == null ? 0 : result.getScore(), support.questionsPerLearner(quiz));
            } else if (quiz != null || hasPool.contains(ch)) {
                // Đề chưa tạo (bé chưa mở danh sách bài) mà chương có câu → vẫn "chờ hậu trắc", quizId null.
                postTest = new SlotReportResponse.PostTest(quiz == null ? null : quiz.getId(), PENDING, null, null);
            } else {
                postTest = new SlotReportResponse.PostTest(null, NONE, null, null);
            }
            chapters.add(new SlotReportResponse.ChapterRow(ch, attempt, finishedAt, choices,
                    new SlotReportResponse.MiniGames(correct, minis.size(), wrong), postTest,
                    PENDING.equals(postTest.status()) ? PARTIAL : COMPLETE, null));
        }

        if (!submittedPostTests.isEmpty()) {
            for (Object[] row : quizAnswerRepo.conceptAnswers(submittedPostTests)) {
                Map<String, int[]> mine = chapterConcepts.get(chapterOfResult.get((UUID) row[0]));
                for (int[] c : List.of(concepts.computeIfAbsent((String) row[1], k -> new int[4]),
                        mine.computeIfAbsent((String) row[1], k -> new int[4]))) {
                    c[3]++;
                    c[2] += Boolean.TRUE.equals(row[2]) ? 1 : 0;
                }
            }
        }
        List<SlotReportResponse.ConceptRow> conceptRows = new ArrayList<>(concepts.size());
        concepts.forEach((concept, c) -> conceptRows.add(new SlotReportResponse.ConceptRow(concept,
                KnowledgeUnits.nameOf(concept),
                KnowledgeUnits.get(concept) == null ? null : KnowledgeUnits.get(concept).objective(),
                new SlotReportResponse.Count(c[0], c[1]), new SlotReportResponse.Count(c[2], c[3]),
                c[0] + c[2], c[1] + c[3], band(c[0] + c[2], c[1] + c[3]))));

        String finalStatus = !done.contains(FINAL_CHAPTER) ? NONE
                : chapters.stream().allMatch(c -> COMPLETE.equals(c.reportStatus())) ? COMPLETE : PARTIAL;
        return new ChapterBlocks(chapters, conceptRows, new SlotReportResponse.FinalReport(finalStatus, null), attempt,
                chapterJson, chapterVersion, chapterConcepts);
    }

    /** BR-44, chỉ số nguyên: < 3 câu NO_DATA; < 50 % PRACTICE; < 70 % FAMILIAR; còn lại GOOD. */
    static String band(int correct, int total) {
        if (total < MIN_ITEMS_FOR_BAND) {
            return "NO_DATA";
        }
        if (2 * correct < total) {
            return "PRACTICE";
        }
        return 10 * correct < 7 * total ? "FAMILIAR" : "GOOD";
    }

    /**
     * BR-45 — "có bước cân nhắc", không phải đúng / sai: evidenceScores có I, C hoặc P ≥ 1 → true; có điểm mà toàn 0 →
     * false; không có điểm → null (không tính vào mẫu số, không coi là 0).
     */
    static Boolean deliberate(JsonNode option) {
        JsonNode scores = option == null ? null : option.get("evidenceScores");
        if (scores == null || !scores.isObject()) {
            return null;
        }
        boolean scored = false;
        for (String key : List.of("I", "C", "P")) {
            JsonNode v = scores.get(key);
            if (v != null && v.isNumber()) {
                if (v.asDouble() >= 1) {
                    return true;
                }
                scored = true;
            }
        }
        return scored ? Boolean.FALSE : null;
    }

    /**
     * Mã của một câu mini-game, tra lúc đọc từ JSON bản đã chơi bằng đúng hàm của bộ chấm: câu grade → grade.concept hoặc
     * mã duy nhất của cảnh; mini-game kiểu cũ → mã duy nhất của cảnh. Không tra được (không còn JSON) → cột đã lưu.
     */
    private static String resolveConcept(JsonNode scene, MiniGameResult m) {
        if (scene == null) {
            return m.getConcept();
        }
        List<MiniGameGrader.Question> questions = MiniGameGrader.questions(scene);
        if (questions.isEmpty()) {
            return MiniGameGrader.singleConcept(scene);
        }
        for (MiniGameGrader.Question q : questions) {
            if (m.getItemId().equals(q.id())) {
                return q.concept();
            }
        }
        return m.getConcept();
    }

    /** Vị trí cảnh trong chương (thứ tự truyện) để xếp lựa chọn; không tra được → cuối. */
    private static int sceneIndex(JsonNode chapterJson, String sceneId) {
        JsonNode scenes = chapterJson == null ? null : chapterJson.path("scenes");
        if (scenes != null && scenes.isArray()) {
            for (int i = 0; i < scenes.size(); i++) {
                if (sceneId.equals(scenes.get(i).path("id").asText(null))) {
                    return i;
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    private static String playedKey(String version, Integer chapter) {
        return version + "#" + chapter;
    }

    private static Instant later(Instant a, Instant b) {
        return a == null || (b != null && b.isAfter(a)) ? b : a;
    }

    private JsonNode readTree(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "JSON chuong hong trong release_chapter");
        }
    }


    // ------------------------------------------------------------------ group report

    /**
     * Nhóm ĐÃ ĐÓNG mà CÓ bản đóng băng → đọc GroupReport / GroupReportRow, không tính lại.
     * Nhóm đang mở, hoặc nhóm đóng TRƯỚC khi API này chạy (chưa có bản đóng băng) → tính sống.
     */
    @Override
    @Transactional(readOnly = true)
    public GroupReportResponse groupReport(UUID callerId, UUID groupId) {
        LearnerGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có nhóm " + groupId));
        requireOwner(callerId, group);
        // Quiz v3: chốt lượt quá giờ của nhóm trước khi đọc (giao dịch riêng — hàm này readOnly).
        quizAttemptService.finalizeExpiredInGroup(group.getId());
        GroupReportResponse.GroupInfo info = new GroupReportResponse.GroupInfo(
                group.getId(), group.getName(),
                group.getContext() == null ? null : group.getContext().name(),
                group.getSlotLimit(), group.getOpenedAt(), group.getClosedAt());
        GroupReportResponse base = null;
        if (group.getClosedAt() != null) {
            GroupReport frozen = groupReportRepo.findFirstByGroup_IdOrderByFrozenAtDesc(groupId)
                    .orElse(null);
            if (frozen != null) {
                base = frozenResponse(group, info, frozen);
            }
        }
        if (base == null) {
            base = liveResponse(group, info);
        }
        List<GroupReportResponse.ClassChapter> chapters = group.getContext() == LearningContext.CLASS
                ? classReports.getObject().liveChapters(group) : List.of();
        return new GroupReportResponse(base.group(), base.frozenAt(), base.formulaVersion(), base.summary(), base.rows(),
                chapters);
    }

    // ------------------------------------------------------------------ đóng băng lúc đóng nhóm

    /**
     * Tính rows + summary đúng lúc nhóm đóng rồi ghi GroupReport (frozenAt, formulaVersion,
     * summary JSON) và GroupReportRow (figures JSON) — không bao giờ tính lại. Gọi từ
     * GroupServiceImpl.closeGroup, CÙNG transaction: một trong hai lỗi thì cả closeGroup rollback.
     */
    @Override
    @Transactional
    public void freezeOnClose(UUID groupId) {
        LearnerGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có nhóm " + groupId));
        List<LearnerSlot> slots = slotRepo.findByGroup_IdOrderByCreatedAtAsc(groupId);
        Map<UUID, Instant> lastActive = lastActiveInGroup(groupId);
        Map<UUID, RunState> runs = runsInGroup(groupId);
        // Đợt 3 (quiz.md v2): số quiz đóng băng cùng lúc như mọi số — sống ở đây, đọc lại từ frozen.
        Map<UUID, QuizSlotFigures> quizBySlot = quizSlotFigures(groupId);
        int quizAssigned = (int) quizRepo.countByGroup_IdAndPublishedAtIsNotNull(groupId);
        Object[] quizTotals = firstRow(quizResultRepo.statsInGroup(groupId));

        List<RowFigures> figures = new ArrayList<>(slots.size());
        for (LearnerSlot slot : slots) {
            figures.add(figuresOf(runs.get(slot.getId()), lastActive.get(slot.getId()),
                    quizAssigned, quizBySlot.get(slot.getId())));
        }
        GroupReportResponse.Summary summary = new GroupReportResponse.Summary(
                slots.size(),
                countByStatus(slots, SlotStatus.ACTIVE),
                countActiveLast7Days(slots, lastActive),
                sumCompleted(figures),
                currentTotalChapters(),
                quizAssigned,
                quizSubmittedOf(quizTotals),
                quizAverageOf(quizTotals));

        GroupReport report = new GroupReport();
        report.setGroup(group);
        report.setFrozenAt(Instant.now(clock));
        report.setFormulaVersion(FORMULA_VERSION);
        report.setSummary(toJson(summary));
        report = groupReportRepo.save(report);
        for (int i = 0; i < slots.size(); i++) {
            GroupReportRow row = new GroupReportRow();
            row.setReport(report);
            row.setSlot(slots.get(i));
            row.setFigures(toJson(figures.get(i)));
            groupReportRowRepo.save(row);
        }
    }

    // ------------------------------------------------------------------ dựng response

    /** Nhóm đã đóng, có bản đóng băng: con số đóng băng, code / displayName / status sống. */
    private GroupReportResponse frozenResponse(LearnerGroup group, GroupReportResponse.GroupInfo info,
                                               GroupReport frozen) {
        GroupReportResponse.Summary summary;
        try {
            summary = frozen.getSummary() == null ? null
                    : objectMapper.readValue(frozen.getSummary(), GroupReportResponse.Summary.class);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "summary hong trong group_report");
        }
        Map<UUID, RowFigures> bySlot = new HashMap<>();
        for (GroupReportRow r : groupReportRowRepo.findByReport_Id(frozen.getId())) {
            try {
                bySlot.put(r.getSlot().getId(),
                        objectMapper.readValue(r.getFigures(), RowFigures.class));
            } catch (JsonProcessingException e) {
                throw new AppException(ErrorCode.UNCATEGORIZED, "figures hong trong group_report_row");
            }
        }
        List<GroupReportResponse.ReportRow> rows = new ArrayList<>();
        // Sort như liveResponse (Kidz review 13/10): thứ tự dòng LUÔN ACTIVE → ARCHIVED → WIPED
        // theo status SỐNG, kể cả nhóm đóng — WIPED sau khi đóng vẫn phải xuống cuối.
        for (LearnerSlot slot : byStatusThenCreatedAt(
                slotRepo.findByGroup_IdOrderByCreatedAtAsc(group.getId()))) {
            RowFigures fig = bySlot.get(slot.getId());
            if (fig != null) {
                rows.add(liveIdentityRow(slot, fig));
            }
        }
        return new GroupReportResponse(info, frozen.getFrozenAt(), frozen.getFormulaVersion(),
                summary, rows, List.of());
    }

    /** Nhóm đang mở (hoặc đóng mà chưa có bản đóng băng): tính sống từ RunState / event. */
    private GroupReportResponse liveResponse(LearnerGroup group, GroupReportResponse.GroupInfo info) {
        Map<UUID, Instant> lastActive = lastActiveInGroup(group.getId());
        Map<UUID, RunState> runs = runsInGroup(group.getId());
        Map<UUID, QuizSlotFigures> quizBySlot = quizSlotFigures(group.getId());
        int quizAssigned = (int) quizRepo.countByGroup_IdAndPublishedAtIsNotNull(group.getId());
        Object[] quizTotals = firstRow(quizResultRepo.statsInGroup(group.getId()));

        List<LearnerSlot> slots = byStatusThenCreatedAt(
                slotRepo.findByGroup_IdOrderByCreatedAtAsc(group.getId()));

        List<GroupReportResponse.ReportRow> rows = new ArrayList<>(slots.size());
        for (LearnerSlot slot : slots) {
            rows.add(liveIdentityRow(slot,
                    figuresOf(runs.get(slot.getId()), lastActive.get(slot.getId()),
                            quizAssigned, quizBySlot.get(slot.getId()))));
        }
        GroupReportResponse.Summary summary = new GroupReportResponse.Summary(
                slots.size(),
                countByStatus(slots, SlotStatus.ACTIVE),
                countActiveLast7Days(slots, lastActive),
                rows.stream().mapToInt(GroupReportResponse.ReportRow::completedChapters).sum(),
                currentTotalChapters(),
                quizAssigned,
                quizSubmittedOf(quizTotals),
                quizAverageOf(quizTotals));
        return new GroupReportResponse(info, null, null, summary, rows, List.of());
    }

    /** Dòng báo cáo: con số (đóng băng hay sống) + danh tính LUÔN đọc sống từ LearnerSlot. */
    private GroupReportResponse.ReportRow liveIdentityRow(LearnerSlot slot, RowFigures fig) {
        boolean wiped = slot.getStatus() == SlotStatus.WIPED;
        return new GroupReportResponse.ReportRow(
                slot.getId(),
                wiped ? null : slot.getCode(),
                wiped ? null : slot.getDisplayName(),
                slot.getStatus().name(),
                fig.chaptersDone(), fig.completedChapters(), fig.attemptIndex(),
                fig.lastActiveAt(), fig.indicators(),
                fig.quizAssigned(), fig.quizSubmitted(), fig.averageScore());
    }

    // ------------------------------------------------------------------ số liệu từng mảng

    /** progress: cùng nguồn với dashboard trẻ (RunState + bản hiện tại) + attemptIndex. */
    private SlotReportResponse.Progress progressOf(RunState run, Integer totalChapters) {
        List<Integer> done = run == null ? List.of() : ChaptersDone.parse(run.getChaptersDone());
        Integer continueChapter = (totalChapters != null && done.size() >= totalChapters)
                ? null
                : done.size() + 1;
        return new SlotReportResponse.Progress(totalChapters, done.size(), List.copyOf(done),
                continueChapter,
                run == null || run.getAttemptIndex() == null ? 1 : run.getAttemptIndex(),
                run == null ? null : run.getUpdatedAt());
    }

    /** 5 chỉ số ẩn từ RunState — chỉ người lớn thấy; chưa chơi → null, không bịa số 50. */
    private SlotReportResponse.Indicators indicatorsOf(RunState run) {
        return run == null ? null : new SlotReportResponse.Indicators(
                run.getWealth(), run.getSaving(), run.getHappiness(), run.getRisk(), run.getGoal());
    }

    private RowFigures figuresOf(RunState run, Instant lastActiveAt, int quizAssigned,
                                 QuizSlotFigures quiz) {
        List<Integer> done = run == null ? List.of() : ChaptersDone.parse(run.getChaptersDone());
        return new RowFigures(done, done.size(),
                run == null || run.getAttemptIndex() == null ? 1 : run.getAttemptIndex(),
                lastActiveAt, indicatorsOf(run), quizAssigned,
                quiz == null ? 0 : quiz.submitted(), quiz == null ? null : quiz.averageScore());
    }

    /** Số quiz của MỘT dòng trong báo cáo nhóm — null = em chưa nộp bài nào (assigned tính riêng). */
    private record QuizSlotFigures(int submitted, Double averageScore) {}

    /** Kết quả gộp statsBySlotInGroup: slotId → nộp / trung bình điểm của slot đó. */
    private Map<UUID, QuizSlotFigures> quizSlotFigures(UUID groupId) {
        Map<UUID, QuizSlotFigures> out = new HashMap<>();
        for (Object[] row : quizResultRepo.statsBySlotInGroup(groupId)) {
            out.put((UUID) row[0],
                    new QuizSlotFigures(((Number) row[1]).intValue(), avgOf(row[2])));
        }
        return out;
    }

    private static int quizSubmittedOf(Object[] totals) {
        return totals == null || totals[0] == null ? 0 : ((Number) totals[0]).intValue();
    }

    /** Trả về dòng duy nhất của statsInGroup ([số bài, trung bình]); không có lượt nộp nào → null. */
    private static Object[] firstRow(List<Object[]> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Double quizAverageOf(Object[] totals) {
        return totals == null ? null : avgOf(totals[1]);
    }

    /** avg() ra Double / BigDecimal tùy H2 hay Postgres — Number là chung được cho cả hai. */
    private static Double avgOf(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    // ------------------------------------------------------------------ truy vấn gộp

    /** lastActiveAt của một slot = receivedAt lớn nhất trong ChoiceEvent ∪ MiniGameResult. */
    private Instant lastActiveAt(UUID slotId) {
        Instant a = choiceEventRepo.maxReceivedAtBySlot(slotId);
        Instant b = miniGameResultRepo.maxReceivedAtBySlot(slotId);
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    private Map<UUID, Instant> lastActiveInGroup(UUID groupId) {
        Map<UUID, Instant> out = new HashMap<>();
        for (Object[] row : choiceEventRepo.lastActiveAtBySlotInGroup(groupId)) {
            out.merge((UUID) row[0], (Instant) row[1], (a, b) -> a.isAfter(b) ? a : b);
        }
        for (Object[] row : miniGameResultRepo.lastActiveAtBySlotInGroup(groupId)) {
            out.merge((UUID) row[0], (Instant) row[1], (a, b) -> a.isAfter(b) ? a : b);
        }
        return out;
    }

    private Map<UUID, RunState> runsInGroup(UUID groupId) {
        Map<UUID, RunState> out = new HashMap<>();
        for (RunState r : runStateRepo.findBySlot_Group_Id(groupId)) {
            out.put(r.getSlot().getId(), r);
        }
        return out;
    }

    /** totalChapters = số chương bản hiện tại; chưa phát hành → null. */
    private Integer currentTotalChapters() {
        return releaseRepo.findFirstByIsCurrentTrue()
                .map(r -> releaseChapterRepo.findByRelease_IdOrderByChapter_ChapterNumberAsc(r.getId()).size())
                .orElse(null);
    }

    /**
     * activeLast7Days: 7 ngày gần nhất TÍNH CẢ HÔM NAY — cutoff = đầu ngày VN của (hôm nay − 6),
     * không phải − 7 (Kidz review 13/10: −7 thành ra 8 ngày). Đúng lúc đóng băng rồi không đổi.
     */
    private int countActiveLast7Days(List<LearnerSlot> slots, Map<UUID, Instant> lastActive) {
        Instant cutoff = LocalDate.now(clock).minusDays(6).atStartOfDay(ClockConfig.VN).toInstant();
        int n = 0;
        for (LearnerSlot slot : slots) {
            Instant at = lastActive.get(slot.getId());
            if (at != null && !at.isBefore(cutoff)) {
                n++;
            }
        }
        return n;
    }

    private static int countByStatus(List<LearnerSlot> slots, SlotStatus status) {
        int n = 0;
        for (LearnerSlot slot : slots) {
            if (slot.getStatus() == status) {
                n++;
            }
        }
        return n;
    }

    private static int sumCompleted(List<RowFigures> figures) {
        int n = 0;
        for (RowFigures fig : figures) {
            n += fig.completedChapters();
        }
        return n;
    }

    /** Thứ tự dòng: ACTIVE 0, ARCHIVED 1, WIPED 2; không rõ trạng thái (null) coi như cuối. */
    private static int statusRank(SlotStatus status) {
        if (status == SlotStatus.ACTIVE) {
            return 0;
        }
        return status == SlotStatus.ARCHIVED ? 1 : 2;
    }

    /** ACTIVE trước, ARCHIVED, WIPED sau; trong nhóm theo createdAt — sort stable giữ thứ tự đầu vào. */
    private static List<LearnerSlot> byStatusThenCreatedAt(List<LearnerSlot> slots) {
        List<LearnerSlot> out = new ArrayList<>(slots);
        out.sort(Comparator.comparingInt(s -> statusRank(s.getStatus())));
        return out;
    }

    private void requireOwner(UUID callerId, LearnerGroup group) {
        if (callerId == null || group.getOwner() == null || !callerId.equals(group.getOwner().getId())) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
    }


    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "khong ghi duoc JSON bao cao");
        }
    }
}
