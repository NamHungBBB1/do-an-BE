package com.doan.game.service.impl;

import com.doan.game.DTO.response.GroupReportResponse;
import com.doan.game.DTO.response.SlotReportResponse;
import com.doan.game.configuration.ClockConfig;
import com.doan.game.entity.Achievement;
import com.doan.game.entity.GroupReport;
import com.doan.game.entity.GroupReportRow;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.entity.RunState;
import com.doan.game.enums.LearningContext;
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
import com.doan.game.repository.ReleaseChapterRepository;
import com.doan.game.repository.RunStateRepository;
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
import java.util.Map;
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
 * quizzes null (FAMILY) hoặc object rỗng (CLASS), dailySummary null — FE dựng giao diện một lần.
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
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * figures JSON trong GroupReportRow — đúng "dòng không có code / displayName / status"
     * (report.md): ba trường danh tính đọc sống từ LearnerSlot, còn lại là con số đông cứng.
     * Record package-private để Jackson serialize / deserialize được, không lọt ra ngoài.
     */
    record RowFigures(List<Integer> chaptersDone, int completedChapters, int attemptIndex,
                      Instant lastActiveAt, SlotReportResponse.Indicators indicators,
                      int quizAssigned, int quizSubmitted, Integer averageScore) {
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

        // Quiz chỉ cho lớp; FAMILY nhận null — FE ẩn hẳn khối quiz (Hưng chốt, cùng dashboard).
        SlotReportResponse.Quizzes quizzes =
                group.getContext() == LearningContext.CLASS
                        ? new SlotReportResponse.Quizzes(0, 0, List.of())
                        : null;

        return new SlotReportResponse(learner, progress, indicatorsOf(run),
                List.copyOf(achievements), List.of(), List.of(), quizzes, null);
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
        GroupReportResponse.GroupInfo info = new GroupReportResponse.GroupInfo(
                group.getId(), group.getName(),
                group.getContext() == null ? null : group.getContext().name(),
                group.getSlotLimit(), group.getOpenedAt(), group.getClosedAt());
        if (group.getClosedAt() != null) {
            GroupReport frozen = groupReportRepo.findFirstByGroup_IdOrderByFrozenAtDesc(groupId)
                    .orElse(null);
            if (frozen != null) {
                return frozenResponse(group, info, frozen);
            }
        }
        return liveResponse(group, info);
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

        List<RowFigures> figures = new ArrayList<>(slots.size());
        for (LearnerSlot slot : slots) {
            figures.add(figuresOf(runs.get(slot.getId()), lastActive.get(slot.getId())));
        }
        GroupReportResponse.Summary summary = new GroupReportResponse.Summary(
                slots.size(),
                countByStatus(slots, SlotStatus.ACTIVE),
                countActiveLast7Days(slots, lastActive),
                sumCompleted(figures),
                currentTotalChapters(),
                0, 0, null);

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
        for (LearnerSlot slot : slotRepo.findByGroup_IdOrderByCreatedAtAsc(group.getId())) {
            RowFigures fig = bySlot.get(slot.getId());
            if (fig != null) {
                rows.add(liveIdentityRow(slot, fig));
            }
        }
        return new GroupReportResponse(info, frozen.getFrozenAt(), frozen.getFormulaVersion(),
                summary, rows);
    }

    /** Nhóm đang mở (hoặc đóng mà chưa có bản đóng băng): tính sống từ RunState / event. */
    private GroupReportResponse liveResponse(LearnerGroup group, GroupReportResponse.GroupInfo info) {
        Map<UUID, Instant> lastActive = lastActiveInGroup(group.getId());
        Map<UUID, RunState> runs = runsInGroup(group.getId());

        List<LearnerSlot> slots = new ArrayList<>(slotRepo.findByGroup_IdOrderByCreatedAtAsc(group.getId()));
        // ACTIVE trước, ARCHIVED, WIPED sau; trong nhóm theo createdAt — sort stable giữ thứ tự đầu vào.
        slots.sort(Comparator.comparingInt(s -> statusRank(s.getStatus())));

        List<GroupReportResponse.ReportRow> rows = new ArrayList<>(slots.size());
        for (LearnerSlot slot : slots) {
            rows.add(liveIdentityRow(slot,
                    figuresOf(runs.get(slot.getId()), lastActive.get(slot.getId()))));
        }
        GroupReportResponse.Summary summary = new GroupReportResponse.Summary(
                slots.size(),
                countByStatus(slots, SlotStatus.ACTIVE),
                countActiveLast7Days(slots, lastActive),
                rows.stream().mapToInt(GroupReportResponse.ReportRow::completedChapters).sum(),
                currentTotalChapters(),
                0, 0, null);
        return new GroupReportResponse(info, null, null, summary, rows);
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
        List<Integer> done = run == null ? List.of() : parseChapters(run.getChaptersDone());
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

    private RowFigures figuresOf(RunState run, Instant lastActiveAt) {
        List<Integer> done = run == null ? List.of() : parseChapters(run.getChaptersDone());
        return new RowFigures(done, done.size(),
                run == null || run.getAttemptIndex() == null ? 1 : run.getAttemptIndex(),
                lastActiveAt, indicatorsOf(run), 0, 0, null);
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

    /** activeLast7Days: lastActiveAt từ đầu ngày VN của (hôm nay − 7) tới giờ — cùng luật "7 ngày" như gói. */
    private int countActiveLast7Days(List<LearnerSlot> slots, Map<UUID, Instant> lastActive) {
        Instant cutoff = LocalDate.now(clock).minusDays(7).atStartOfDay(ClockConfig.VN).toInstant();
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

    private void requireOwner(UUID callerId, LearnerGroup group) {
        if (callerId == null || group.getOwner() == null || !callerId.equals(group.getOwner().getId())) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "khong ghi duoc JSON bao cao");
        }
    }
}
