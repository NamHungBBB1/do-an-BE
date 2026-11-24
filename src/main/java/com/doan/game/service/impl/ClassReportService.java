package com.doan.game.service.impl;

import com.doan.game.DTO.response.GroupReportResponse;
import com.doan.game.DTO.response.SlotReportResponse;
import com.doan.game.entity.AiJob;
import com.doan.game.entity.DailySummary;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.entity.Quiz;
import com.doan.game.entity.QuizResult;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.SlotStatus;
import com.doan.game.enums.SummarySource;
import com.doan.game.repository.AiJobRepository;
import com.doan.game.repository.DailySummaryRepository;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.QuizRepository;
import com.doan.game.repository.QuizResultRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Báo cáo lớp + nhận xét cả lớp theo mốc (Hưng chốt 25/11, build/spec-bao-cao-lop-gom-lo-2026-11-25.md).
 *
 * Mốc: 35 / 70 / 100 % số em ĐANG HOẠT ĐỘNG đã nộp bài sau chương; ngưỡng = max(5, ceil(m% × active)), ngưỡng trùng gộp
 * (giữ mốc cao). Mỗi lần một em nộp: khoá dòng nhóm, đếm, chạm mốc cao hơn bản gần nhất thì: bản gần nhất còn CHỜ (việc AI
 * QUEUED / WAITING_RETRY, chỉ nó dùng) → THAY bằng mốc mới, giữ giờ xếp của việc (T tính từ lần xếp đầu); còn lại → bản mới.
 * Không viết lại giữa hai mốc, mốc không lùi. Đóng lớp: em nộp thêm sau bản gần nhất → một bản chốt (milestone 0).
 * Em trong lớp không có bài AI riêng (BR-52 — ReportSummaryService chỉ ghi bản theo luật cho em).
 */
@Slf4j
@Service
public class ClassReportService {

    static final List<Integer> MILESTONES = List.of(35, 70, 100);
    static final int MIN_FIRST = 5;
    /** milestone của bản chốt khi đóng lớp. */
    static final int CLOSE = 0;
    static final String LANE = "CLASS_MILESTONE";

    static final String WAITING = "WAITING";
    static final String PREPARING = "PREPARING";
    static final String READY = "READY";
    static final String BASIC = "BASIC";

    /** Lớp vừa đóng (GroupServiceImpl.closeGroup) — chạy sau commit, transaction riêng. */
    public record ClassClosedEvent(UUID groupId) {
    }

    private static final Set<String> PENDING_JOB = Set.of(AiJobQueue.QUEUED, AiJobQueue.WAITING_RETRY);
    private static final Set<String> ALIVE_JOB = Set.of(AiJobQueue.QUEUED, AiJobQueue.RUNNING, AiJobQueue.WAITING_RETRY,
            AiJobQueue.WAITING_AGENT);

    private final ReportServiceImpl reports;
    private final DailySummaryRepository summaryRepo;
    private final AiJobRepository jobRepo;
    private final LearnerGroupRepository groupRepo;
    private final LearnerSlotRepository slotRepo;
    private final QuizRepository quizRepo;
    private final QuizResultRepository quizResultRepo;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ClassReportService(ReportServiceImpl reports, DailySummaryRepository summaryRepo, AiJobRepository jobRepo,
                              LearnerGroupRepository groupRepo, LearnerSlotRepository slotRepo, QuizRepository quizRepo,
                              QuizResultRepository quizResultRepo, PlatformTransactionManager txManager, Clock clock) {
        this.reports = reports;
        this.summaryRepo = summaryRepo;
        this.jobRepo = jobRepo;
        this.groupRepo = groupRepo;
        this.slotRepo = slotRepo;
        this.quizRepo = quizRepo;
        this.quizResultRepo = quizResultRepo;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ mốc

    /** Ngưỡng (số em) → mốc %, xếp tăng; ngưỡng trùng giữ mốc cao nhất. Lớp 40 → {14:35, 28:70, 40:100}; lớp 5 → {5:100}. */
    static TreeMap<Integer, Integer> thresholds(int active) {
        TreeMap<Integer, Integer> out = new TreeMap<>();
        for (int m : MILESTONES) {
            out.put(Math.max(MIN_FIRST, (int) Math.ceil(m * active / 100.0)), m);
        }
        return out;
    }

    /** Mốc cao nhất đã chạm; chưa chạm mốc nào → -1. */
    static int reached(int active, int submitted) {
        Map.Entry<Integer, Integer> e = thresholds(active).floorEntry(submitted);
        return active <= 0 || e == null ? -1 : e.getValue();
    }

    /** Gọi trong transaction (ReportSummaryService sau khi em nộp bài sau chương). */
    void onPostTest(UUID groupId) {
        LearnerGroup peek = groupRepo.findById(groupId).orElse(null);
        if (peek == null || peek.getContext() != LearningContext.CLASS || peek.getClosedAt() != null
                || !anyNewMilestone(peek)) {
            return;   // đếm rẻ trước: phần lớn sự kiện không chạm mốc mới nên không khoá dòng nhóm
        }
        LearnerGroup group = groupRepo.lockById(groupId).orElse(null);
        if (group == null || group.getClosedAt() != null) {
            return;
        }
        List<LearnerSlot> active = activeSlots(group);
        for (Quiz quiz : postTests(groupId).values()) {
            List<UUID> submitted = submittedSlots(quiz, active);
            int m = reached(active.size(), submitted.size());
            DailySummary last = latest(groupId, quiz.getChapter());
            if (m < 0 || (last != null && last.getMilestone() != null
                    && (last.getMilestone() == CLOSE || last.getMilestone() >= m))) {
                continue;
            }
            write(group, quiz.getChapter(), m, submitted, active.size(), last);
        }
    }

    /** Có chương nào chạm mốc cao hơn bản gần nhất (không khoá). */
    private boolean anyNewMilestone(LearnerGroup group) {
        List<LearnerSlot> active = activeSlots(group);
        for (Quiz quiz : postTests(group.getId()).values()) {
            int m = reached(active.size(), submittedSlots(quiz, active).size());
            DailySummary last = latest(group.getId(), quiz.getChapter());
            if (m >= 0 && (last == null || last.getMilestone() == null
                    || (last.getMilestone() != CLOSE && last.getMilestone() < m))) {
                return true;
            }
        }
        return false;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onClosed(ClassClosedEvent event) {
        try {
            tx.executeWithoutResult(s -> closeChapters(event.groupId()));
        } catch (RuntimeException e) {
            log.warn("khong ghi duoc nhan xet chot lop {}: {}", event.groupId(), e.toString());
        }
    }

    /** Đóng lớp: chương có em nộp thêm sau bản gần nhất (và đã đủ ngưỡng đầu) → một bản chốt. */
    void closeChapters(UUID groupId) {
        LearnerGroup group = groupRepo.lockById(groupId).orElse(null);
        if (group == null || group.getContext() != LearningContext.CLASS) {
            return;
        }
        List<LearnerSlot> active = activeSlots(group);
        for (Quiz quiz : postTests(groupId).values()) {
            List<UUID> submitted = submittedSlots(quiz, active);
            DailySummary last = latest(groupId, quiz.getChapter());
            if (last == null && reached(active.size(), submitted.size()) >= 0) {
                // Đủ ngưỡng mà chưa từng có nhận xét (vd sĩ số giảm sau lần nộp cuối) → vẫn có bản chốt.
                write(group, quiz.getChapter(), CLOSE, submitted, active.size(), null);
                continue;
            }
            if (last == null || submitted.size() < thresholds(active.size()).firstKey()
                    || (last.getMilestone() != null && last.getMilestone() == CLOSE)
                    || (last.getSubmittedCount() != null && submitted.size() <= last.getSubmittedCount())) {
                continue;
            }
            write(group, quiz.getChapter(), CLOSE, submitted, active.size(), last);
        }
    }

    private void write(LearnerGroup group, int chapter, int milestone, List<UUID> submitted, int active,
                       DailySummary last) {
        List<ReportServiceImpl.ChapterBlocks> kids = new ArrayList<>(submitted.size());
        for (UUID slotId : submitted) {
            kids.add(reports.chapterBlocksOf(slotId));
        }
        ReportPackage.Pkg pkg = ReportPackage.classChapter(chapter, kids, active);
        String json = ReportPackage.canonicalJson(pkg);
        String hash = ReportPackage.sha256(json);
        String rule = ReportPackage.ruleText(pkg);
        Instant now = Instant.now(clock);

        // Khoá dòng việc trước khi xét: hàng chờ (SKIP LOCKED) không lấy được việc đang bị thay, và việc vừa bị hàng chờ
        // lấy (RUNNING) thì không bị ghi đè.
        AiJob pending = last == null || last.getAiJob() == null ? null
                : jobRepo.lockById(last.getAiJob().getId()).orElse(null);
        if (pending != null && PENDING_JOB.contains(pending.getStatus())
                && summaryRepo.findByAiJob_Id(pending.getId()).size() == 1) {
            // Thay bản đang chờ: cùng dòng, cùng việc, giữ createdAt của việc (T tính từ lần xếp đầu).
            last.setMilestone(milestone);
            last.setSubmittedCount(submitted.size());
            last.setActiveCount(active);
            last.setPackageHash(hash);
            last.setSummary(rule);
            pending.setInput(json);
            pending.setInputHash(hash);
            summaryRepo.saveAndFlush(last);
            return;
        }
        DailySummary s = new DailySummary();
        s.setGroup(group);
        s.setKind(ReportPackage.CLASS);
        s.setChapter(chapter);
        s.setMilestone(milestone);
        s.setSubmittedCount(submitted.size());
        s.setActiveCount(active);
        s.setPackageHash(hash);
        s.setSummary(rule);
        s.setSource(SummarySource.RULE);
        s.setTier(0);
        s.setCreatedAt(now);
        s.setAiJob(newJob(json, hash, now));
        summaryRepo.saveAndFlush(s);
    }

    private AiJob newJob(String json, String hash, Instant now) {
        AiJob job = new AiJob();
        job.setKind(AiJobQueue.REPORT_SUMMARY);
        job.setLane(LANE);
        job.setInput(json);
        job.setInputHash(hash);
        job.setStatus(AiJobQueue.QUEUED);
        job.setTier(1);
        job.setAttempts(0);
        job.setCreatedAt(now);
        return jobRepo.save(job);
    }

    private DailySummary latest(UUID groupId, int chapter) {
        List<DailySummary> rows = summaryRepo.findByGroup_IdAndKindAndChapterOrderByCreatedAtDesc(groupId,
                ReportPackage.CLASS, chapter);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Em tính vào lớp: lớp đang mở → ACTIVE; lớp đã đóng (đóng lớp chuyển mọi em ACTIVE sang ARCHIVED) → mọi em chưa xoá.
     * shortcut: lớp đã đóng gộp cả em lưu trữ tay trước khi đóng; cần chính xác thì so archivedAt với closedAt.
     */
    private List<LearnerSlot> activeSlots(LearnerGroup group) {
        boolean closed = group.getClosedAt() != null;
        return slotRepo.findByGroup_IdOrderByCreatedAtAsc(group.getId()).stream()
                .filter(s -> closed ? s.getStatus() != SlotStatus.WIPED : s.getStatus() == SlotStatus.ACTIVE).toList();
    }

    /** Đề hậu trắc đã phát của nhóm theo chương. */
    private Map<Integer, Quiz> postTests(UUID groupId) {
        Map<Integer, Quiz> out = new TreeMap<>();
        for (Quiz q : quizRepo.findByGroup_Id(groupId)) {
            if (QuizSupport.isPostTest(q) && q.getPublishedAt() != null && q.getChapter() != null) {
                out.put(q.getChapter(), q);
            }
        }
        return out;
    }

    /** Em đang hoạt động đã nộp đề (QuizResult = lần nộp đầu, mỗi em một dòng / đề). */
    private List<UUID> submittedSlots(Quiz quiz, List<LearnerSlot> active) {
        Set<UUID> ids = new java.util.HashSet<>();
        active.forEach(s -> ids.add(s.getId()));
        List<UUID> out = new ArrayList<>();
        for (QuizResult r : quizResultRepo.findByQuiz_Id(quiz.getId())) {
            if (ids.contains(r.getSlot().getId())) {
                out.add(r.getSlot().getId());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ báo cáo lớp (GET /api/groups/{id}/report)

    /**
     * chapters[] của báo cáo lớp, tính sống. Gọi trong transaction đọc của groupReport (đã kiểm chủ nhóm).
     * shortcut: dựng khối chương cho từng em đang hoạt động (khoảng 6 truy vấn / em, lớp 40 em ≈ 240 truy vấn / lần mở);
     * đủ cho quy mô hiện tại, đông người dùng thì gộp truy vấn theo nhóm.
     */
    List<GroupReportResponse.ClassChapter> liveChapters(LearnerGroup group) {
        List<LearnerSlot> active = activeSlots(group);
        Map<UUID, ReportServiceImpl.ChapterBlocks> blocks = new LinkedHashMap<>();
        for (LearnerSlot s : active) {
            blocks.put(s.getId(), reports.chapterBlocksOf(s.getId()));
        }
        Map<Integer, List<DailySummary>> comments = new TreeMap<>();
        for (DailySummary d : summaryRepo.findByGroupWithJob(group.getId(), ReportPackage.CLASS)) {
            comments.computeIfAbsent(d.getChapter(), k -> new ArrayList<>()).add(d);
        }
        Set<Integer> chapters = new TreeSet<>(comments.keySet());
        blocks.values().forEach(b -> b.chapters().forEach(r -> chapters.add(r.chapter())));

        List<GroupReportResponse.ClassChapter> out = new ArrayList<>();
        for (int ch : chapters) {
            out.add(chapterOf(ch, active, blocks, comments.getOrDefault(ch, List.of())));
        }
        return out;
    }

    private GroupReportResponse.ClassChapter chapterOf(int ch, List<LearnerSlot> active,
                                                      Map<UUID, ReportServiceImpl.ChapterBlocks> blocks,
                                                      List<DailySummary> comments) {
        List<GroupReportResponse.LearnerRef> notFinished = new ArrayList<>();
        List<GroupReportResponse.LearnerRef> notSubmitted = new ArrayList<>();
        Map<UUID, SlotReportResponse.ChapterRow> finished = new LinkedHashMap<>();
        List<LearnerSlot> submitted = new ArrayList<>();
        for (LearnerSlot s : active) {
            SlotReportResponse.ChapterRow row = blocks.get(s.getId()).chapters().stream()
                    .filter(r -> r.chapter() == ch).findFirst().orElse(null);
            if (row == null) {
                notFinished.add(ref(s));
                continue;
            }
            finished.put(s.getId(), row);
            if (ReportServiceImpl.SUBMITTED.equals(row.postTest().status())) {
                submitted.add(s);
            } else if (ReportServiceImpl.PENDING.equals(row.postTest().status())) {
                notSubmitted.add(ref(s));
            }
        }
        Map<UUID, LearnerSlot> bySlot = new HashMap<>();
        active.forEach(s -> bySlot.put(s.getId(), s));

        // Phiên bản phổ biến nhất của chương trong lớp: lấy JSON (tiêu đề, cảnh, mã lõi) và đếm lựa chọn theo bản đó.
        Map<String, Integer> versionCount = new TreeMap<>();
        finished.keySet().forEach(id -> {
            String v = blocks.get(id).chapterVersion().get(ch);
            if (v != null) {
                versionCount.merge(v, 1, Integer::sum);
            }
        });
        String version = versionCount.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse(null);
        JsonNode json = null;
        for (UUID id : finished.keySet()) {
            if (version != null && version.equals(blocks.get(id).chapterVersion().get(ch))) {
                json = blocks.get(id).chapterJson().get(ch);
                if (json != null) {
                    break;
                }
            }
        }

        // Nội dung: cộng dồn câu của mọi em đã nộp bài sau chương (khớp gói gửi AI).
        Set<String> core = ReportPackage.coreConcepts(json);
        Map<String, int[]> sum = new TreeMap<>();
        Map<String, List<GroupReportResponse.LearnerRef>> wrong = new HashMap<>();
        for (LearnerSlot s : submitted) {
            blocks.get(s.getId()).chapterConcepts().getOrDefault(ch, Map.of()).forEach((id, c) -> {
                if (!core.isEmpty() && !core.contains(id)) {
                    return;
                }
                int[] t = sum.computeIfAbsent(id, k -> new int[2]);
                t[0] += c[0] + c[2];
                t[1] += c[1] + c[3];
                if (c[0] + c[2] < c[1] + c[3]) {
                    wrong.computeIfAbsent(id, k -> new ArrayList<>()).add(ref(s));
                }
            });
        }
        List<GroupReportResponse.ClassConcept> concepts = new ArrayList<>();
        sum.forEach((id, t) -> {
            KnowledgeUnits.Unit u = KnowledgeUnits.get(id);
            concepts.add(new GroupReportResponse.ClassConcept(KnowledgeUnits.nameOf(id), u == null ? null : u.objective(),
                    t[0], t[1], ReportPackage.classBand(t[0], t[1]), wrong.getOrDefault(id, List.of())));
        });
        // Yếu nhất trước; nội dung chưa có câu nào xuống cuối.
        concepts.sort(Comparator.comparingDouble((GroupReportResponse.ClassConcept c) ->
                c.total() == 0 ? 2.0 : (double) c.correct() / c.total()));

        // Quyết định: em đã xong chương, chơi đúng bản phổ biến nhất.
        Map<String, Map<String, List<GroupReportResponse.LearnerRef>>> picks = new LinkedHashMap<>();
        for (Map.Entry<UUID, SlotReportResponse.ChapterRow> e : finished.entrySet()) {
            if (version == null || !version.equals(blocks.get(e.getKey()).chapterVersion().get(ch))) {
                continue;
            }
            for (SlotReportResponse.ChoiceRow c : e.getValue().choices()) {
                picks.computeIfAbsent(c.sceneId(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(c.optionId(), k -> new ArrayList<>()).add(ref(bySlot.get(e.getKey())));
            }
        }
        List<GroupReportResponse.ClassDecision> decisions = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<GroupReportResponse.LearnerRef>>> e : picks.entrySet()) {
            JsonNode scene = json == null ? null : PlayServiceImpl.findScene(json.path("scenes"), e.getKey());
            List<GroupReportResponse.ClassOption> options = new ArrayList<>();
            if (scene != null && scene.path("options").isArray()) {
                for (JsonNode o : scene.path("options")) {
                    List<GroupReportResponse.LearnerRef> who = e.getValue().getOrDefault(o.path("id").asText(""),
                            List.of());
                    options.add(new GroupReportResponse.ClassOption(o.path("text").asText(o.path("label").asText(null)),
                            who.size(), who));
                }
            }
            String question = scene == null ? null : scene.path("text").asText(scene.path("title").asText(null));
            decisions.add(new GroupReportResponse.ClassDecision(question, options));
        }

        String title = json == null ? null : json.path("title").asText(null);
        CommentPick pick = comment(comments);
        return new GroupReportResponse.ClassChapter(ch, title, active.size(), finished.size(), submitted.size(),
                pick.comment(), pick.history(), concepts, decisions, notFinished, notSubmitted);
    }

    private record CommentPick(GroupReportResponse.Comment comment, List<GroupReportResponse.HistoryItem> history) {}

    /** Bản mới nhất → state; đang chuẩn bị thì hiện bản trước (nếu có). Không lộ mốc / hàng chờ / AI. */
    static CommentPick comment(List<DailySummary> rows) {
        if (rows.isEmpty()) {
            return new CommentPick(new GroupReportResponse.Comment(WAITING, null, null, null), List.of());
        }
        DailySummary latest = rows.get(0);
        int from = 1;
        GroupReportResponse.Comment c;
        if (latest.getSource() == SummarySource.LLM) {
            c = new GroupReportResponse.Comment(READY, latest.getSummary(), at(latest), latest.getSubmittedCount());
        } else if (latest.getAiJob() != null && ALIVE_JOB.contains(latest.getAiJob().getStatus())) {
            DailySummary prev = rows.size() > 1 ? rows.get(1) : null;
            c = new GroupReportResponse.Comment(PREPARING, prev == null ? null : prev.getSummary(),
                    prev == null ? null : at(prev), prev == null ? null : prev.getSubmittedCount());
            from = prev == null ? 1 : 2;
        } else {
            c = new GroupReportResponse.Comment(BASIC, latest.getSummary(), at(latest), latest.getSubmittedCount());
        }
        List<GroupReportResponse.HistoryItem> history = new ArrayList<>();
        for (int i = from; i < rows.size(); i++) {
            DailySummary d = rows.get(i);
            history.add(new GroupReportResponse.HistoryItem(at(d), d.getSubmittedCount(), d.getSummary()));
        }
        return new CommentPick(c, history);
    }

    private static Instant at(DailySummary d) {
        AiJob job = d.getAiJob();
        return d.getSource() == SummarySource.LLM && job != null && job.getDoneAt() != null ? job.getDoneAt()
                : d.getCreatedAt();
    }

    private static GroupReportResponse.LearnerRef ref(LearnerSlot s) {
        return new GroupReportResponse.LearnerRef(s.getId(), s.getDisplayName());
    }
}
